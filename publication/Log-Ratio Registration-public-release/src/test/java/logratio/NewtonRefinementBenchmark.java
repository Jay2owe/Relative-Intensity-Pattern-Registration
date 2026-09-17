/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import logratio.api.LogRatioParameters;
import logratio.api.LogRatioRegistration;
import logratio.api.LogRatioResult;
import logratio.api.PixelSelectionStrategy;
import logratio.api.RegistrationRecipe;
import logratio.api.SelectionMode;
import logratio.core.PairEstimator;
import logratio.core.PairScheduler;
import logratio.core.Transform;

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
 * Stage 2 of {@code docs/newton_refinement_plan.md}: what the Gauss-Newton sub-pixel refinement is
 * worth on real recordings, per image type, with the tail beside the median.
 *
 * <p>Four arms over the 80 development recordings, at the category recommendation so that only the
 * estimator differs:
 *
 * <ol>
 *   <li><b>{@code 1_shipped_default_log_ratio}</b> — what a user gets today: the full automatic
 *       selector resolving support, band, filter and mask, then the log-ratio fit.</li>
 *   <li><b>{@code 2_recommended_log_ratio_fit}</b> — the image-and-motion recommendation with no
 *       automatic override, log-ratio fit.</li>
 *   <li><b>{@code 3_recommended_area_correlation_grid}</b> — the same recommendation with area
 *       correlation and the shipping shrinking-grid refinement.</li>
 *   <li><b>{@code 4_recommended_area_correlation_newton}</b> — the same again with the Gauss-Newton
 *       refinement. Everything above the seam, the whole preparation, the interpolator, the integer
 *       sweep and the pyramid descent are the same code as arm 3.</li>
 * </ol>
 *
 * <p><b>Arm 4 against arm 3 is the whole question.</b> Arms 1 and 2 are here so the numbers can be
 * read against the record that already exists in {@code docs/pairwise_estimator_axis_findings.md},
 * not because they are what the stage decides.
 *
 * <p><b>Why this is a separate run id from {@link PairEstimatorComparisonBenchmark}.</b> That
 * benchmark's {@code all_recordings.csv} is the artifact the Stage 1 gate was measured on, and its
 * before/after pair is saved in {@code summaries/newton_gate_v1/}. Adding a fifth arm there would
 * change the file's shape and make a future gate comparison against those saved halves impossible.
 * So this is its own run, and the two remain comparable.
 *
 * <p><b>The same deliberate asymmetry the estimator axis declared.</b> The sparse low-light
 * recommendation carries a second-pass pixel mask for three of the four movement profiles. That is a
 * second pass <em>of the log-ratio fit</em>; an area method has no per-pixel support to mask, so it
 * cannot be given one. Arms 2, 3 and 4 all run with the mask switched off and arm 1 keeps whatever
 * the shipped selector chooses, which is what keeps the arm-3-against-arm-4 comparison honest.
 *
 * <h2>The exit gate, written here before the run</h2>
 *
 * <p>{@code GATE.md} is produced by this class and states the verdict. Three conditions, all of
 * which must hold:
 *
 * <ol>
 *   <li>arm 4's paired median is no worse than arm 3's, <b>on every image type</b>, by more than
 *       {@value #MEDIAN_TOLERANCE_PX} px;</li>
 *   <li>arm 4's worst recording is no worse than arm 3's, on every image type;</li>
 *   <li>arm 4 is at least {@link Candidate#requiredCpuSavingPercent}% faster on mean processor
 *       seconds — 30% for the Gauss-Newton step, 20% for the subsampled refinement.</li>
 * </ol>
 *
 * <p>Processor seconds rather than wall clock, because {@code docs/performance_optimisation_findings.md}
 * measured run-to-run wall drift on this machine at about 4% and Stage 0 saw identical repeated
 * measurements swing by a factor of three. A change of this size has to be visible above that.
 *
 * <p><b>If the gate fails the plan stops</b>, the value is documented as a dead end the way
 * {@code AREA_CORRELATION_LINEAR} is, and the shipped estimator is left alone. That is a complete and
 * useful outcome, and saying so here is what stops it being renegotiated once the numbers are in.
 *
 * <p>Point it at a root with {@code -Dlogratio.comparisonRoot=<relative path>}; the default is the
 * development controlled-motion tree. Nothing here may be pointed at a sealed set before the stage
 * that opens it.
 */
public final class NewtonRefinementBenchmark {

    /**
     * The candidate refinement arm 4 runs, and with it the run id and the speed bar.
     *
     * <p>One gate implementation, more than one candidate. Arm 4 is "the refinement being proposed";
     * which one that is comes from {@code -Dlogratio.candidate=}, and the run writes into a folder
     * named after it so two candidates cannot overwrite each other's evidence. The default
     * reproduces the Stage 2 run of {@code docs/newton_refinement_plan.md} exactly.
     *
     * <p><b>The speed bar is per candidate and comes from the plan that proposed it</b>, not from
     * whatever the candidate turns out to achieve: 30% for the Gauss-Newton step, which is Stage 2's
     * own gate, and 20% for the subsampled refinement, which is the low end of the 20-30% that item 3
     * of {@code docs/performance_optimisation_plan.md} predicts for itself. Both were written here
     * before either was run.
     */
    enum Candidate {
        NEWTON("area_correlation_newton", "newton_refinement_v1",
                "Category recommendation, area correlation, Newton refinement", 30),
        SUBSAMPLED("area_correlation_subsampled", "subsampled_refinement_v1",
                "Category recommendation, area correlation, subsampled refinement", 20);

        final String estimatorId;
        final String runId;
        final String armLabel;
        final int requiredCpuSavingPercent;

        Candidate(String estimatorId, String runId, String armLabel, int requiredCpuSavingPercent) {
            this.estimatorId = estimatorId;
            this.runId = runId;
            this.armLabel = armLabel;
            this.requiredCpuSavingPercent = requiredCpuSavingPercent;
        }

        static Candidate current() {
            String wanted = System.getProperty("logratio.candidate", "").trim();
            if (wanted.isEmpty()) return NEWTON;
            for (Candidate candidate : values()) {
                if (candidate.estimatorId.equals(wanted)
                        || candidate.name().equalsIgnoreCase(wanted)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("no candidate named " + wanted);
        }
    }

    static final Candidate CANDIDATE = Candidate.current();
    static final String RUN_ID = CANDIDATE.runId;

    static final String HEADER = "experiment,image_series_class,series_id,independent_group,"
            + "motion_category,condition,arm_id,status,median_error_px,p90_error_px,max_error_px,"
            + "total_seconds,cpu_seconds,resolved_recipe_id,resolved_recipe,details";

    /** How far arm 4's paired median may sit above arm 3's on any image type, in pixels. */
    static final double MEDIAN_TOLERANCE_PX = 0.001;
    /** How much of arm 3's mean processor time arm 4 must save. Per candidate, see {@link Candidate}. */
    static final int REQUIRED_CPU_SAVING_PERCENT = CANDIDATE.requiredCpuSavingPercent;

    static final String DEFAULT_ARM = "1_shipped_default_log_ratio";
    static final String LOG_RATIO_ARM = "2_recommended_log_ratio_fit";
    static final String GRID_ARM = "3_recommended_area_correlation_grid";
    static final String NEWTON_ARM = "4_recommended_" + CANDIDATE.estimatorId;

    enum Arm {
        DEFAULT(DEFAULT_ARM, "Shipped default: full automatic selector, log-ratio fit"),
        RECOMMENDED(LOG_RATIO_ARM, "Category recommendation, log-ratio fit"),
        GRID(GRID_ARM, "Category recommendation, area correlation, grid refinement"),
        NEWTON(NEWTON_ARM, CANDIDATE.armLabel);

        final String id;
        final String label;

        Arm(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private NewtonRefinementBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        String relative = System.getProperty("logratio.comparisonRoot",
                "library/benchmark/v2/benchmarks/controlled_motion");
        Path root = project.resolve(relative).normalize();
        if (!Files.isDirectory(root)) throw new IOException("missing " + root);
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Files.createDirectories(summary);

        List<FullSelectorFactorialBenchmark.Recording> recordings =
                FullSelectorFactorialBenchmark.recordings(root);
        if (recordings.isEmpty()) throw new IOException("no controlled recordings under " + root);
        Set<String> onlySeries = requested("logratio.onlySeries");
        boolean rewrite = Boolean.getBoolean("logratio.rewrite");

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
                "%s comparison over %s: completed %d, resumed %d, failed %d%n",
                RUN_ID, relative, completed, resumed, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("comparison failures:\n" + String.join("\n", failures));
        }
    }

    private static void runArm(FullSelectorFactorialBenchmark.Recording recording, ImagePlus input,
                               Arm arm, Path output) throws IOException {
        Files.createDirectories(output);
        LogRatioParameters category = SelectorComparisonBenchmark.categoryBase(recording);
        LogRatioParameters parameters;
        switch (arm) {
            case DEFAULT:
                parameters = category.toBuilder().selectionMode(SelectionMode.AUTOMATIC).build();
                break;
            case RECOMMENDED:
                parameters = withoutSecondPass(category);
                break;
            case GRID:
                parameters = withoutSecondPass(category).toBuilder()
                        .estimator(PairEstimator.Kind.AREA_CORRELATION).build();
                break;
            case NEWTON:
                parameters = withoutSecondPass(category).toBuilder()
                        .estimator(PairEstimator.Kind.of(CANDIDATE.estimatorId)).build();
                break;
            default:
                throw new IllegalStateException("unhandled arm " + arm);
        }

        long cpuStart = processCpuTime();
        long start = System.nanoTime();
        LogRatioResult result = LogRatioRegistration.register(input, parameters,
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
                    + csv(recipe.id()) + ',' + csv(recipe.describe()) + ',' + csv(arm.label);
            Files.write(output.resolve("comparison.csv"),
                    (HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(output.resolve("run_failure.txt"));
            System.out.printf(Locale.ROOT, "%-16s %-34s %-40s %.4f px %.2f s %.2f cpu s%n",
                    recording.imageClass, recording.series, arm.id, metrics[0], seconds, cpuSeconds);
        } finally {
            result.correctedImage().changes = false;
            result.close();
        }
    }

    /** See the class comment: the second-pass mask has no meaning for an area method. */
    private static LogRatioParameters withoutSecondPass(LogRatioParameters category) {
        if (category.pixelSelectionStrategy == PixelSelectionStrategy.NONE) return category;
        return category.toBuilder().pixelSelectionStrategy(PixelSelectionStrategy.NONE).build();
    }

    // ---- summaries ------------------------------------------------------------------------- //

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
            boolean ok = "ok".equals(row.get(7));
            byRecording.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(arm, new double[]{
                    ok ? Double.parseDouble(row.get(8)) : Double.NaN,
                    ok ? Double.parseDouble(row.get(11)) : Double.NaN,
                    ok ? Double.parseDouble(row.get(12)) : Double.NaN,
                    ok ? Double.parseDouble(row.get(9)) : Double.NaN,
                    ok ? Double.parseDouble(row.get(10)) : Double.NaN});
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

        StringBuilder scoped = new StringBuilder("arm_id,image_series_class,recordings,missing,"
                + "mean_median_error_px,median_of_median_error_px,worst_median_error_px,"
                + "mean_p90_error_px,worst_max_error_px,mean_elapsed_seconds,mean_cpu_seconds\n");
        for (Arm arm : Arm.values()) {
            Map<String, Aggregate> scopes = byClass.get(arm.id);
            if (scopes == null) continue;
            for (Map.Entry<String, Aggregate> entry : scopes.entrySet()) {
                scoped.append(csv(arm.id)).append(',').append(csv(entry.getKey())).append(',')
                        .append(entry.getValue().row()).append('\n');
            }
        }
        Files.write(summary.resolve("arm_summary_by_image_type.csv"),
                scoped.toString().getBytes(StandardCharsets.UTF_8));

        writePerRecording(summary, byRecording, classOf, motionOf);
        writeGate(summary, overall, byClass, byRecording, classOf, recordings.size());
    }

    private static void writePerRecording(Path summary,
                                          Map<String, Map<String, double[]>> byRecording,
                                          Map<String, String> classOf,
                                          Map<String, String> motionOf) throws IOException {
        StringBuilder out = new StringBuilder("image_series_class,motion_category,recording,"
                + "default_median_px,log_ratio_median_px,grid_median_px,newton_median_px,"
                + "newton_minus_grid_px,newton_over_grid,"
                + "grid_elapsed_s,newton_elapsed_s,grid_cpu_s,newton_cpu_s,newton_cpu_over_grid\n");
        for (Map.Entry<String, Map<String, double[]>> entry : new TreeMap<>(byRecording).entrySet()) {
            double[] def = entry.getValue().get(DEFAULT_ARM);
            double[] log = entry.getValue().get(LOG_RATIO_ARM);
            double[] grid = entry.getValue().get(GRID_ARM);
            double[] newton = entry.getValue().get(NEWTON_ARM);
            out.append(csv(classOf.get(entry.getKey()))).append(',')
                    .append(csv(motionOf.get(entry.getKey()))).append(',')
                    .append(csv(entry.getKey())).append(',')
                    .append(format(value(def, 0))).append(',')
                    .append(format(value(log, 0))).append(',')
                    .append(format(value(grid, 0))).append(',')
                    .append(format(value(newton, 0))).append(',')
                    .append(format(value(newton, 0) - value(grid, 0))).append(',')
                    .append(format(value(newton, 0) / value(grid, 0))).append(',')
                    .append(format(value(grid, 1))).append(',')
                    .append(format(value(newton, 1))).append(',')
                    .append(format(value(grid, 2))).append(',')
                    .append(format(value(newton, 2))).append(',')
                    .append(format(value(newton, 2) / value(grid, 2))).append('\n');
        }
        Files.write(summary.resolve("all_recordings_paired.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The verdict, computed rather than read off by eye.
     *
     * <p>Every threshold comes from the constants at the top of this class, which were written before
     * the run. A gate a human evaluates by looking at a table is a gate that gets renegotiated.
     */
    private static void writeGate(Path summary, Map<String, Aggregate> overall,
                                  Map<String, Map<String, Aggregate>> byClass,
                                  Map<String, Map<String, double[]>> byRecording,
                                  Map<String, String> classOf, int recordings) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(CANDIDATE.armLabel).append("\n\n");
        out.append("Run id `").append(RUN_ID).append("`, ").append(recordings)
                .append(" development recordings, four arms. Arm 4 against arm 3 is the question;")
                .append(" everything but the sub-pixel refinement is the same code.\n\n");

        out.append("## Every arm, pooled\n\n");
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
        out.append("\n**Pooled numbers decide nothing here.** They are the first table because they "
                + "are the familiar one, and the per-image-type table below is the one the gate "
                + "reads.\n");

        out.append("\n## Arm 4 against arm 3, per image type\n\n");
        out.append("Paired: a recording counts only when both arms produced a finite answer, and the "
                + "reported number is the middle of the paired list rather than a mean, because one "
                + "recording where an estimator loses lock decides any mean by itself.\n\n");
        out.append("| Image type | Paired | Grid median (px) | Newton median (px) | Difference (px) |"
                + " Grid worst (px) | Newton worst (px) | Grid CPU s | Newton CPU s | CPU saved |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|\n");

        List<String> medianBreaches = new ArrayList<>();
        List<String> worstBreaches = new ArrayList<>();
        for (String imageClass : new TreeSet<>(classOf.values())) {
            Paired paired = pair(NEWTON_ARM, GRID_ARM, imageClass, byRecording, classOf);
            if (paired.n == 0) continue;
            Aggregate grid = scoped(byClass, GRID_ARM, imageClass);
            Aggregate newton = scoped(byClass, NEWTON_ARM, imageClass);
            double difference = paired.arm() - paired.reference();
            double gridWorst = grid == null ? Double.NaN : grid.worstMedian();
            double newtonWorst = newton == null ? Double.NaN : newton.worstMedian();
            double gridCpu = grid == null ? Double.NaN : grid.meanCpu();
            double newtonCpu = newton == null ? Double.NaN : newton.meanCpu();
            out.append("| ").append(imageClass).append(" | ").append(paired.n)
                    .append(" | ").append(format(paired.reference()))
                    .append(" | ").append(format(paired.arm()))
                    .append(" | ").append(format(difference))
                    .append(" | ").append(format(gridWorst))
                    .append(" | ").append(format(newtonWorst))
                    .append(" | ").append(format(gridCpu))
                    .append(" | ").append(format(newtonCpu))
                    .append(" | ").append(format(100 * (1 - newtonCpu / gridCpu)))
                    .append("% |\n");
            if (!(difference <= MEDIAN_TOLERANCE_PX)) {
                medianBreaches.add(String.format(Locale.ROOT,
                        "%s: newton %.6f px against grid %.6f px, worse by %.6f px",
                        imageClass, paired.arm(), paired.reference(), difference));
            }
            if (!(newtonWorst <= gridWorst + 1e-12)) {
                worstBreaches.add(String.format(Locale.ROOT,
                        "%s: worst recording newton %.6f px against grid %.6f px",
                        imageClass, newtonWorst, gridWorst));
            }
        }

        Aggregate grid = overall.get(GRID_ARM);
        Aggregate newton = overall.get(NEWTON_ARM);
        double cpuSaved = grid == null || newton == null ? Double.NaN
                : 100 * (1 - newton.meanCpu() / grid.meanCpu());
        boolean fastEnough = cpuSaved >= REQUIRED_CPU_SAVING_PERCENT;
        boolean medianOk = medianBreaches.isEmpty();
        boolean worstOk = worstBreaches.isEmpty();

        out.append("\n## The gate\n\n");
        out.append("| Condition | Limit | Measured | Verdict |\n|---|---|---|---|\n");
        out.append("| Paired median, every image type | no worse by more than ")
                .append(format(MEDIAN_TOLERANCE_PX)).append(" px | ")
                .append(medianOk ? "within" : String.join("; ", medianBreaches))
                .append(" | ").append(medianOk ? "**pass**" : "**FAIL**").append(" |\n");
        out.append("| Worst recording, every image type | no worse than the grid | ")
                .append(worstOk ? "within" : String.join("; ", worstBreaches))
                .append(" | ").append(worstOk ? "**pass**" : "**FAIL**").append(" |\n");
        out.append("| Mean processor seconds | at least ").append(REQUIRED_CPU_SAVING_PERCENT)
                .append("% faster | ").append(format(cpuSaved)).append("% saved | ")
                .append(fastEnough ? "**pass**" : "**FAIL**").append(" |\n");
        out.append("\n**GATE: ").append(medianOk && worstOk && fastEnough ? "PASS" : "FAIL")
                .append("**\n");
        if (!(medianOk && worstOk && fastEnough)) {
            out.append("\nThe plan stops here. Record the numbers, document or delete the value, and "
                    + "leave the shipped estimator alone. See `docs/newton_refinement_plan.md`.\n");
        }
        out.append("\nPer-recording numbers, including elapsed and processor seconds for both area "
                + "arms, are in `all_recordings_paired.csv`.\n");
        Files.write(summary.resolve("GATE.md"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println();
        System.out.println(out);
    }

    private static Aggregate scoped(Map<String, Map<String, Aggregate>> byClass, String arm,
                                    String imageClass) {
        Map<String, Aggregate> scopes = byClass.get(arm);
        return scopes == null ? null : scopes.get(imageClass);
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

    private static Paired pair(String arm, String reference, String imageClass,
                               Map<String, Map<String, double[]>> byRecording,
                               Map<String, String> classOf) {
        Paired out = new Paired();
        for (Map.Entry<String, Map<String, double[]>> entry : byRecording.entrySet()) {
            if (!imageClass.equals(classOf.get(entry.getKey()))) continue;
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

    // ---- small shared bits ------------------------------------------------------------------ //

    private static void writeSettings(Path path, FullSelectorFactorialBenchmark.Recording recording,
                                      Arm arm, LogRatioParameters p, RegistrationRecipe recipe)
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

    private static double value(double[] row, int index) {
        return row == null ? Double.NaN : row[index];
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
