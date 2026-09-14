/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternResult;
import ripr.api.PixelSelectionStrategy;
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
import ripr.core.PairEstimator;
import ripr.core.PairScheduler;
import ripr.core.Transform;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the area-correlation estimator is worth inside our own pipeline, measured against the
 * log-ratio fit with everything else held still.
 *
 * <p>Three arms, and the reason there are three rather than two is the whole point of the design:
 *
 * <ol>
 *   <li><b>{@code 1_shipped_default_log_ratio}</b> — what a user gets today: the full automatic
 *       selector resolving support, band, filter and mask, then the log-ratio fit. This is the
 *       comparison that decides whether anything should change.</li>
 *   <li><b>{@code 2_recommended_log_ratio_fit}</b> — the image-and-motion recommendation with no
 *       automatic override, log-ratio fit.</li>
 *   <li><b>{@code 3_recommended_area_correlation}</b> — the same recommendation, the same pyramids,
 *       the same lag set, the same pair plan, the same reconciler, the same repair, the same warp;
 *       normalised cross-correlation instead of the log-ratio fit.</li>
 * </ol>
 *
 * <p>Arms 2 and 3 differ in exactly one thing, so the difference between them is the estimator and
 * nothing else. Arm 1 answers the shipping question. Quoting only arm 3 against arm 1 would credit
 * or blame the estimator for the selector's work, and quoting only arm 3 against arm 2 would leave
 * the practical question unanswered — so both are reported.
 *
 * <p><b>One deliberate asymmetry, declared here rather than buried.</b> The sparse low-light
 * recommendation carries a second-pass pixel mask for three of the four movement profiles. That is a
 * second pass <em>of the log-ratio fit</em>; an area method has no per-pixel support to mask, so it
 * cannot be given one. Arms 2 and 3 therefore both run with the mask switched off, and arm 1 keeps
 * whatever the shipped selector chooses. The consequence is that on those twelve recordings arm 2 is
 * not the shipped recommendation, and the arm-2-versus-arm-3 comparison stays honest at the cost of
 * arm 2 no longer being a familiar number there.
 *
 * <p>Timing is reported as elapsed and processor seconds separately. The two answer different
 * questions — elapsed is what the user waits, processor time is what the machine spends — and an
 * estimator that parallelises differently can move one without the other.
 *
 * <p>Point it at a root with {@code -Dlogratio.comparisonRoot=<relative path>}; the default is the
 * development controlled-motion tree. Nothing here may be pointed at a sealed set before the stage
 * that opens it.
 */
public final class PairEstimatorComparisonBenchmark {
    static final String RUN_ID = "pair_estimator_v1";

    static final String HEADER = "experiment,image_series_class,series_id,independent_group,"
            + "motion_category,condition,arm_id,status,median_error_px,p90_error_px,max_error_px,"
            + "total_seconds,cpu_seconds,resolved_recipe_id,resolved_recipe,details";

    /** The arm every other arm is paired against: what the plugin ships today. */
    static final String OUR_DEFAULT = "1_shipped_default_log_ratio";
    /** The log-ratio control that differs from the area arm only by the estimator. */
    static final String LOG_RATIO_CONTROL = "2_recommended_log_ratio_fit";
    static final String AREA_ARM = "3_recommended_area_correlation";
    /**
     * The same correlation over pyramids blurred in the intensity domain rather than the log domain.
     *
     * <p>Coarse levels only decide which basin the fine search starts in, so this cannot fix a peak
     * that is displaced at full resolution -- but it is the one difference left between this
     * estimator and a conventional area method, and Stage 3 has to say whether it accounts for
     * anything before the stage can close.
     */
    static final String AREA_LINEAR_ARM = "4_recommended_area_correlation_linear_pyramid";

    enum Arm {
        DEFAULT(OUR_DEFAULT, "Shipped default: full automatic selector, log-ratio fit"),
        RECOMMENDED(LOG_RATIO_CONTROL, "Category recommendation, log-ratio fit"),
        AREA(AREA_ARM, "Category recommendation, area correlation"),
        AREA_LINEAR(AREA_LINEAR_ARM,
                "Category recommendation, area correlation over intensity-domain pyramids");

        final String id;
        final String label;

        Arm(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private PairEstimatorComparisonBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        String relative = System.getProperty("ripr.comparisonRoot",
                "library/benchmark/v2/benchmarks/controlled_motion");
        Path root = project.resolve(relative).normalize();
        if (!Files.isDirectory(root)) throw new IOException("missing " + root);
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Files.createDirectories(summary);

        List<FullSelectorFactorialBenchmark.Recording> recordings =
                FullSelectorFactorialBenchmark.recordings(root);
        if (recordings.isEmpty()) throw new IOException("no controlled recordings under " + root);
        Set<String> onlySeries = requested("ripr.onlySeries");
        boolean rewrite = Boolean.getBoolean("ripr.rewrite");

        List<String> failures = new ArrayList<>();
        int completed = 0;
        int resumed = 0;
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            if (!onlySeries.isEmpty() && !onlySeries.contains(recording.series)) continue;
            ImagePlus input = IJ.openImage(recording.input.toString());
            if (input == null) {
                failures.add("could not open " + recording.input);
                continue;
            }
            try {
                for (Arm arm : Arm.values()) {
                    Path output = recording.folder.resolve(RUN_ID).resolve(arm.id);
                    if (!rewrite && complete(output)) {
                        resumed++;
                        continue;
                    }
                    try {
                        runArm(recording, input, arm, output);
                        completed++;
                    } catch (RuntimeException | IOException error) {
                        Files.createDirectories(output);
                        String message = recording.key() + " / " + arm.id + ": " + rootMessage(error);
                        Files.write(output.resolve("run_failure.txt"),
                                (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                        failures.add(message);
                        System.err.println("FAILED " + message);
                    }
                }
            } finally {
                input.close();
            }
        }
        writeSummaries(root, summary, recordings);
        System.out.printf(Locale.ROOT,
                "pair estimator comparison over %s: completed %d, resumed %d, failed %d%n",
                relative, completed, resumed, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("comparison failures:\n" + String.join("\n", failures));
        }
    }

    private static void runArm(FullSelectorFactorialBenchmark.Recording recording, ImagePlus input,
                               Arm arm, Path output) throws IOException {
        Files.createDirectories(output);
        RelativeIntensityPatternParameters category = SelectorComparisonBenchmark.categoryBase(recording);
        RelativeIntensityPatternParameters parameters;
        String details = arm.label;
        switch (arm) {
            case DEFAULT:
                parameters = category.toBuilder().selectionMode(SelectionMode.AUTOMATIC).build();
                break;
            case RECOMMENDED:
                parameters = withoutSecondPass(category);
                break;
            case AREA:
                parameters = withoutSecondPass(category).toBuilder()
                        .estimator(PairEstimator.Kind.AREA_CORRELATION).build();
                break;
            case AREA_LINEAR:
                parameters = withoutSecondPass(category).toBuilder()
                        .estimator(PairEstimator.Kind.AREA_CORRELATION_LINEAR).build();
                break;
            default:
                throw new IllegalStateException("unhandled arm " + arm);
        }

        long cpuStart = processCpuTime();
        long start = System.nanoTime();
        RelativeIntensityPatternResult result = RelativeIntensityPatternRegistration.register(input, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double seconds = (System.nanoTime() - start) / 1e9;
        double cpuSeconds = (processCpuTime() - cpuStart) / 1e9;
        try {
            double[] metrics = FullSelectorFactorialBenchmark.controlledMetrics(
                    result.registration().cumulative, recording.motion);
            writeTransforms(output.resolve("transforms.csv"), result.registration().cumulative,
                    recording.motion);
            RegistrationRecipe recipe = result.resolvedRecipe();
            writeSettings(output.resolve("settings.csv"), recording, arm, result.parameters(),
                    recipe);
            String row = csv("controlled") + ',' + csv(recording.imageClass) + ','
                    + csv(recording.series) + ',' + csv(recording.independentGroup) + ','
                    + csv(recording.motion) + ',' + csv(recording.condition) + ','
                    + csv(arm.id) + ",ok," + format(metrics[0]) + ',' + format(metrics[1]) + ','
                    + format(metrics[2]) + ',' + format(seconds) + ',' + format(cpuSeconds) + ','
                    + csv(recipe.id()) + ',' + csv(recipe.describe()) + ',' + csv(details);
            Files.write(output.resolve("comparison.csv"),
                    (HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(output.resolve("run_failure.txt"));
            System.out.printf(Locale.ROOT, "%-16s %-34s %-34s %.4f px %.2f s%n",
                    recording.imageClass, recording.series, arm.id, metrics[0], seconds);
        } finally {
            result.correctedImage().changes = false;
            result.close();
        }
    }

    /**
     * The recommendation with the second-pass pixel mask removed.
     *
     * <p>See the class comment: the mask is a second pass of the log-ratio fit and has no meaning for
     * an area method, so removing it from both arms is what keeps their comparison about the
     * estimator. Everything else — norm, support, band, filter, lag set, iteration and sample
     * budgets, movement bound — is left exactly as recommended.
     */
    private static RelativeIntensityPatternParameters withoutSecondPass(RelativeIntensityPatternParameters category) {
        if (category.pixelSelectionStrategy == PixelSelectionStrategy.NONE) return category;
        return category.toBuilder()
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .build();
    }

    // ---- summaries ----------------------------------------------------------------------- //

    private static void writeSummaries(Path root, Path summary,
                                       List<FullSelectorFactorialBenchmark.Recording> recordings)
            throws IOException {
        StringBuilder all = new StringBuilder(HEADER).append('\n');
        List<List<String>> rows = new ArrayList<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            for (Arm arm : Arm.values()) {
                Path file = recording.folder.resolve(RUN_ID).resolve(arm.id)
                        .resolve("comparison.csv");
                if (!Files.isRegularFile(file)) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() < 2) continue;
                all.append(lines.get(1)).append('\n');
                rows.add(FullSelectorFactorialBenchmark.fields(lines.get(1)));
            }
        }
        Files.write(summary.resolve("all_recordings.csv"),
                all.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, Aggregate> overall = new LinkedHashMap<>();
        Map<String, Map<String, Aggregate>> byClass = new LinkedHashMap<>();
        Map<String, Map<String, Aggregate>> byMotion = new LinkedHashMap<>();
        Map<String, Map<String, double[]>> byRecording = new LinkedHashMap<>();
        Map<String, String> classOf = new LinkedHashMap<>();
        Map<String, String> motionOf = new LinkedHashMap<>();
        for (List<String> row : rows) {
            String arm = row.get(6);
            String imageClass = row.get(1);
            String motion = row.get(4);
            String key = row.get(2) + '/' + motion + '/' + row.get(5);
            classOf.put(key, imageClass);
            motionOf.put(key, motion);
            overall.computeIfAbsent(arm, k -> new Aggregate()).add(row);
            byClass.computeIfAbsent(arm, k -> new TreeMap<>())
                    .computeIfAbsent(imageClass, k -> new Aggregate()).add(row);
            byMotion.computeIfAbsent(arm, k -> new TreeMap<>())
                    .computeIfAbsent(motion, k -> new Aggregate()).add(row);
            double median = "ok".equals(row.get(7)) ? Double.parseDouble(row.get(8)) : Double.NaN;
            double elapsed = "ok".equals(row.get(7)) ? Double.parseDouble(row.get(11)) : Double.NaN;
            double cpu = "ok".equals(row.get(7)) ? Double.parseDouble(row.get(12)) : Double.NaN;
            byRecording.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .put(arm, new double[]{median, elapsed, cpu});
        }

        StringBuilder armRows = new StringBuilder("arm_id,arm_label,recordings,missing,"
                + "mean_median_error_px,median_of_median_error_px,worst_median_error_px,"
                + "mean_p90_error_px,worst_max_error_px,mean_elapsed_seconds,mean_cpu_seconds\n");
        for (Arm arm : Arm.values()) {
            Aggregate a = overall.get(arm.id);
            if (a == null) continue;
            armRows.append(csv(arm.id)).append(',').append(csv(arm.label)).append(',')
                    .append(a.row()).append('\n');
        }
        Files.write(summary.resolve("arm_summary.csv"),
                armRows.toString().getBytes(StandardCharsets.UTF_8));

        writeScoped(summary.resolve("arm_summary_by_image_type.csv"), "image_series_class",
                byClass);
        writeScoped(summary.resolve("arm_summary_by_motion_profile.csv"), "motion_category",
                byMotion);
        writePaired(summary, byRecording, classOf, motionOf);
        writePerRecording(summary, byRecording, classOf, motionOf);
        writeFindings(summary, overall, byRecording, classOf, motionOf, recordings.size());
    }

    private static void writeScoped(Path path, String scopeColumn,
                                    Map<String, Map<String, Aggregate>> byScope) throws IOException {
        StringBuilder out = new StringBuilder("arm_id," + scopeColumn + ",recordings,missing,"
                + "mean_median_error_px,median_of_median_error_px,worst_median_error_px,"
                + "mean_p90_error_px,worst_max_error_px,mean_elapsed_seconds,mean_cpu_seconds\n");
        for (Arm arm : Arm.values()) {
            Map<String, Aggregate> scopes = byScope.get(arm.id);
            if (scopes == null) continue;
            for (Map.Entry<String, Aggregate> entry : scopes.entrySet()) {
                out.append(csv(arm.id)).append(',').append(csv(entry.getKey())).append(',')
                        .append(entry.getValue().row()).append('\n');
            }
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Paired medians, by exactly the rule {@link ExternalComparisonSummary} uses: a recording counts
     * only when the reference arm produced a finite answer, an arm that did not is counted as a drop
     * rather than quietly excluded, and the reported number is the middle value of the paired list
     * rather than a mean, because one recording where an estimator loses lock decides any mean by
     * itself.
     */
    private static void writePaired(Path summary, Map<String, Map<String, double[]>> byRecording,
                                    Map<String, String> classOf, Map<String, String> motionOf)
            throws IOException {
        StringBuilder out = new StringBuilder("arm_id,reference_arm,scope,scope_kind,"
                + "paired_recordings,dropped_recordings,arm_median_px,reference_median_px,ratio\n");
        for (String reference : new String[]{OUR_DEFAULT, LOG_RATIO_CONTROL}) {
            for (Arm arm : Arm.values()) {
                if (arm.id.equals(reference)) continue;
                for (String[] scope : scopes(classOf, motionOf)) {
                    Paired paired = pair(arm.id, reference, scope[0], scope[1], byRecording,
                            classOf, motionOf);
                    if (paired.n == 0) continue;
                    out.append(csv(arm.id)).append(',').append(csv(reference)).append(',')
                            .append(csv(scope[1])).append(',').append(csv(scope[0])).append(',')
                            .append(paired.n).append(',').append(paired.dropped).append(',')
                            .append(format(paired.arm())).append(',')
                            .append(format(paired.reference())).append(',')
                            .append(format(paired.arm() / paired.reference())).append('\n');
                }
            }
        }
        Files.write(summary.resolve("paired_against_default.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String[]> scopes(Map<String, String> classOf, Map<String, String> motionOf) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"all", "ALL"});
        for (String value : new TreeSet<>(classOf.values())) out.add(new String[]{"image_type", value});
        for (String value : new TreeSet<>(motionOf.values())) {
            out.add(new String[]{"motion_profile", value});
        }
        return out;
    }

    private static final class Paired {
        final List<Double> arm = new ArrayList<>();
        final List<Double> reference = new ArrayList<>();
        int n;
        int dropped;

        double arm() {
            return middle(arm);
        }

        double reference() {
            return middle(reference);
        }

        private static double middle(List<Double> values) {
            if (values.isEmpty()) return Double.NaN;
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }
    }

    private static Paired pair(String arm, String reference, String scopeKind, String scope,
                               Map<String, Map<String, double[]>> byRecording,
                               Map<String, String> classOf, Map<String, String> motionOf) {
        Paired out = new Paired();
        for (Map.Entry<String, Map<String, double[]>> entry : byRecording.entrySet()) {
            if ("image_type".equals(scopeKind) && !scope.equals(classOf.get(entry.getKey()))) continue;
            if ("motion_profile".equals(scopeKind) && !scope.equals(motionOf.get(entry.getKey()))) {
                continue;
            }
            double[] mine = entry.getValue().get(reference);
            double[] theirs = entry.getValue().get(arm);
            if (mine == null || !Double.isFinite(mine[0])) continue;
            if (theirs == null || !Double.isFinite(theirs[0])) {
                out.dropped++;
                continue;
            }
            out.arm.add(theirs[0]);
            out.reference.add(mine[0]);
            out.n++;
        }
        return out;
    }

    private static void writePerRecording(Path summary,
                                          Map<String, Map<String, double[]>> byRecording,
                                          Map<String, String> classOf,
                                          Map<String, String> motionOf) throws IOException {
        StringBuilder out = new StringBuilder("image_series_class,motion_category,recording,"
                + "default_median_px,log_ratio_median_px,area_median_px,"
                + "area_over_default,area_over_log_ratio,"
                + "default_elapsed_s,log_ratio_elapsed_s,area_elapsed_s,"
                + "default_cpu_s,log_ratio_cpu_s,area_cpu_s\n");
        for (Map.Entry<String, Map<String, double[]>> entry
                : new TreeMap<>(byRecording).entrySet()) {
            double[] def = entry.getValue().get(OUR_DEFAULT);
            double[] log = entry.getValue().get(LOG_RATIO_CONTROL);
            double[] area = entry.getValue().get(AREA_ARM);
            out.append(csv(classOf.get(entry.getKey()))).append(',')
                    .append(csv(motionOf.get(entry.getKey()))).append(',')
                    .append(csv(entry.getKey())).append(',')
                    .append(format(value(def, 0))).append(',')
                    .append(format(value(log, 0))).append(',')
                    .append(format(value(area, 0))).append(',')
                    .append(format(value(area, 0) / value(def, 0))).append(',')
                    .append(format(value(area, 0) / value(log, 0))).append(',')
                    .append(format(value(def, 1))).append(',')
                    .append(format(value(log, 1))).append(',')
                    .append(format(value(area, 1))).append(',')
                    .append(format(value(def, 2))).append(',')
                    .append(format(value(log, 2))).append(',')
                    .append(format(value(area, 2))).append('\n');
        }
        Files.write(summary.resolve("all_recordings_paired.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static double value(double[] row, int index) {
        return row == null ? Double.NaN : row[index];
    }

    private static void writeFindings(Path summary, Map<String, Aggregate> overall,
                                      Map<String, Map<String, double[]>> byRecording,
                                      Map<String, String> classOf, Map<String, String> motionOf,
                                      int recordings) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Area correlation against the log-ratio fit, inside our own pipeline\n\n");
        out.append("Run id `").append(RUN_ID).append("`, ").append(recordings)
                .append(" development recordings, three arms. Arms 2 and 3 differ only in which ")
                .append("estimator turns a frame pair into a movement; arm 1 is what the plugin ")
                .append("ships today.\n\n");
        out.append("| Arm | Recordings | Mean median (px) | Median of median (px) | "
                + "Worst median (px) | Mean elapsed s | Mean CPU s |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (Arm arm : Arm.values()) {
            Aggregate a = overall.get(arm.id);
            if (a == null) continue;
            out.append("| ").append(arm.label).append(" | ").append(a.ok)
                    .append(" | ").append(format(a.meanMedian()))
                    .append(" | ").append(format(a.medianOfMedians()))
                    .append(" | ").append(format(a.worstMedian()))
                    .append(" | ").append(format(a.meanElapsed()))
                    .append(" | ").append(format(a.meanCpu())).append(" |\n");
        }

        out.append("\n## Per image type, paired\n\n");
        out.append("Ratios below 1 mean the area estimator wins. `vs default` is against what ships;"
                + " `vs log-ratio` is the same recipe with only the estimator changed.\n\n");
        out.append("| Image type | Paired | Area (px) | Default (px) | vs default | "
                + "Log-ratio (px) | vs log-ratio |\n|---|---|---|---|---|---|---|\n");
        for (String imageClass : new TreeSet<>(classOf.values())) {
            Paired againstDefault = pair(AREA_ARM, OUR_DEFAULT, "image_type", imageClass,
                    byRecording, classOf, motionOf);
            Paired againstControl = pair(AREA_ARM, LOG_RATIO_CONTROL, "image_type", imageClass,
                    byRecording, classOf, motionOf);
            if (againstDefault.n == 0) continue;
            out.append("| ").append(imageClass).append(" | ").append(againstDefault.n)
                    .append(" | ").append(format(againstDefault.arm()))
                    .append(" | ").append(format(againstDefault.reference()))
                    .append(" | ").append(format(againstDefault.arm() / againstDefault.reference()))
                    .append("x | ").append(format(againstControl.reference()))
                    .append(" | ").append(format(againstControl.arm() / againstControl.reference()))
                    .append("x |\n");
        }

        out.append("\n## Per movement profile, paired against the shipped default\n\n");
        out.append("| Movement profile | Paired | Area (px) | Default (px) | Ratio |\n"
                + "|---|---|---|---|---|\n");
        for (String motion : new TreeSet<>(motionOf.values())) {
            Paired paired = pair(AREA_ARM, OUR_DEFAULT, "motion_profile", motion, byRecording,
                    classOf, motionOf);
            if (paired.n == 0) continue;
            out.append("| ").append(motion).append(" | ").append(paired.n).append(" | ")
                    .append(format(paired.arm())).append(" | ").append(format(paired.reference()))
                    .append(" | ").append(format(paired.arm() / paired.reference()))
                    .append("x |\n");
        }
        out.append("\nPer-recording numbers, including elapsed and processor seconds for every arm, "
                + "are in `all_recordings_paired.csv`. The scoped paired table is "
                + "`paired_against_default.csv`.\n");
        Files.write(summary.resolve("FINDINGS.md"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final class Aggregate {
        final List<Double> medians = new ArrayList<>();
        double medianSum;
        double p90Sum;
        double elapsedSum;
        double cpuSum;
        double worstMax;
        int ok;
        int missing;

        void add(List<String> row) {
            if (!"ok".equals(row.get(7))) {
                missing++;
                return;
            }
            double median = Double.parseDouble(row.get(8));
            if (!Double.isFinite(median)) {
                missing++;
                return;
            }
            ok++;
            medians.add(median);
            medianSum += median;
            p90Sum += Double.parseDouble(row.get(9));
            double max = Double.parseDouble(row.get(10));
            if (Double.isFinite(max)) worstMax = Math.max(worstMax, max);
            elapsedSum += Double.parseDouble(row.get(11));
            cpuSum += Double.parseDouble(row.get(12));
        }

        double meanMedian() {
            return ok == 0 ? Double.NaN : medianSum / ok;
        }

        double medianOfMedians() {
            if (medians.isEmpty()) return Double.NaN;
            List<Double> sorted = new ArrayList<>(medians);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        double worstMedian() {
            double worst = Double.NaN;
            for (double value : medians) if (Double.isNaN(worst) || value > worst) worst = value;
            return worst;
        }

        double meanElapsed() {
            return ok == 0 ? Double.NaN : elapsedSum / ok;
        }

        double meanCpu() {
            return ok == 0 ? Double.NaN : cpuSum / ok;
        }

        double meanP90() {
            return ok == 0 ? Double.NaN : p90Sum / ok;
        }

        String row() {
            return ok + "," + missing + ',' + format(meanMedian()) + ','
                    + format(medianOfMedians()) + ',' + format(worstMedian()) + ','
                    + format(meanP90()) + ',' + format(worstMax) + ',' + format(meanElapsed())
                    + ',' + format(meanCpu());
        }
    }

    // ---- small shared bits ---------------------------------------------------------------- //

    private static void writeSettings(Path path, FullSelectorFactorialBenchmark.Recording recording,
                                      Arm arm, RelativeIntensityPatternParameters p, RegistrationRecipe recipe)
            throws IOException {
        StringBuilder out = new StringBuilder("setting,value\n");
        out.append(csv("run_id")).append(',').append(csv(RUN_ID)).append('\n');
        out.append(csv("arm_id")).append(',').append(csv(arm.id)).append('\n');
        out.append(csv("arm_label")).append(',').append(csv(arm.label)).append('\n');
        out.append(csv("recording")).append(',').append(csv(recording.key())).append('\n');
        out.append(csv("estimator")).append(',').append(csv(p.estimator.id())).append('\n');
        out.append(csv("selection_mode")).append(',')
                .append(csv(p.selectionMode.macroValue())).append('\n');
        out.append(csv("resolved_recipe_id")).append(',').append(csv(recipe.id())).append('\n');
        out.append(csv("resolved_recipe")).append(',').append(csv(recipe.describe())).append('\n');
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTransforms(Path path, Transform[] transforms, String motion)
            throws IOException {
        int[] x = new int[Benchmark.FRAMES];
        int[] y = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(x, y);
        StringBuilder out = new StringBuilder(
                "frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n");
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            double tx = x[i] / (double) Benchmark.FINE;
            double ty = y[i] / (double) Benchmark.FINE;
            out.append(i + 1).append(',').append(format(transform.dx)).append(',')
                    .append(format(transform.dy)).append(',').append(format(transform.theta))
                    .append(',').append(format(tx)).append(',').append(format(ty)).append(',')
                    .append(format(Math.hypot(transform.dx - tx, transform.dy - ty)))
                    .append(",ok\n");
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean complete(Path output) {
        Path comparison = output.resolve("comparison.csv");
        if (!Files.isRegularFile(comparison)) return false;
        try {
            List<String> lines = Files.readAllLines(comparison, StandardCharsets.UTF_8);
            if (lines.size() < 2 || !lines.get(0).equals(HEADER)) return false;
        } catch (IOException error) {
            return false;
        }
        return Files.isRegularFile(output.resolve("transforms.csv"))
                && Files.isRegularFile(output.resolve("settings.csv"));
    }

    private static Set<String> requested(String property) {
        Set<String> out = new LinkedHashSet<>();
        String value = System.getProperty(property, "");
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return 0L;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.toString();
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }

    private static String format(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
