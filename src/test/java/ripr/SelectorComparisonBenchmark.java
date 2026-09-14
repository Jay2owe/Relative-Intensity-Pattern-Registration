/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.AutomaticFilterSelector;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternResult;
import ripr.api.PixelSelectionStrategy;
import ripr.api.Preprocessing;
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
import ripr.core.AutomaticInformationSelector;
import ripr.core.PairAligner;
import ripr.core.PairScheduler;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs the six comparison arms that decide whether the new selector earns default status.
 *
 * <p>The same runner serves the development set and the locked test set, so the two are measured by
 * one implementation rather than two. The oracle arm is an unattainable upper bound chosen after
 * seeing each recording's own answer; it is reported to show remaining headroom and is never a
 * production route. It is available only where the 96-recipe sweep exists.
 *
 * <p>Point it at a root with {@code -Dlogratio.comparisonRoot=<relative path>}; the default is the
 * development controlled-motion tree.
 */
public final class SelectorComparisonBenchmark {
    static final String RUN_ID = "selector_comparison_v1";

    static final String HEADER = "experiment,image_series_class,series_id,independent_group,"
            + "motion_category,condition,arm_id,status,median_error_px,p90_error_px,max_error_px,"
            + "total_seconds,cpu_seconds,resolved_recipe_id,resolved_recipe,details";

    enum Arm {
        CATEGORY("1_current_category_recommendation",
                "Current category recommendation"),
        INFORMATION("2_old_automatic_information_selector",
                "Older automatic information selector"),
        FILTER("3_current_automatic_filter_selector",
                "Current automatic filter-and-mask selector"),
        FULL("4_new_full_automatic_selector",
                "New full automatic selector"),
        ORACLE("5_manual_oracle_recipe",
                "Manual oracle recipe per recording (unattainable upper bound, not a route)"),
        BASE("6_base_without_automatic_changes",
                "Base model without any automatic change");

        final String id;
        final String label;

        Arm(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private SelectorComparisonBenchmark() {
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
        boolean writeImages = !Boolean.getBoolean("ripr.noImages");
        Map<String, String> oracleByRecording = readOracle(project);

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
                    if (!rewrite && complete(output, writeImages)) {
                        resumed++;
                        continue;
                    }
                    String oracleRecipe = oracleByRecording.get(recording.key());
                    if (arm == Arm.ORACLE && oracleRecipe == null) {
                        writeUnavailable(recording, arm, output,
                                "no 96-recipe sweep exists for this recording, so no oracle is "
                                        + "defined; the sweep was never run on the locked test set "
                                        + "before the selector was frozen");
                        continue;
                    }
                    try {
                        runArm(recording, input, arm, output, writeImages, oracleRecipe);
                        completed++;
                    } catch (RuntimeException | IOException error) {
                        Files.createDirectories(output);
                        String message = recording + " / " + arm.id + ": " + rootMessage(error);
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
        writeSummary(root, summary, recordings);
        System.out.printf(Locale.ROOT,
                "selector comparison over %s: completed %d, resumed %d, failed %d%n",
                relative, completed, resumed, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("comparison failures:\n" + String.join("\n", failures));
        }
    }

    private static void runArm(FullSelectorFactorialBenchmark.Recording recording, ImagePlus input,
                               Arm arm, Path output, boolean writeImages, String oracleRecipe)
            throws IOException {
        Files.createDirectories(output);
        RelativeIntensityPatternParameters category = categoryBase(recording);
        long cpuStart = processCpuTime();
        long start = System.nanoTime();
        RelativeIntensityPatternParameters parameters;
        String details = arm.label;
        switch (arm) {
            case CATEGORY:
                parameters = category;
                break;
            case INFORMATION:
                parameters = informationSelectorParameters(input, category);
                details += "; " + parameters.recipeProvenance;
                break;
            case FILTER: {
                AutomaticFilterSelector.Result selection =
                        RelativeIntensityPatternRegistration.resolveAutomaticFilters(input, category);
                parameters = selection.parameters;
                details += "; " + selection.explanation();
                break;
            }
            case FULL: {
                AutomaticRegistrationSelector.Result selection =
                        RelativeIntensityPatternRegistration.resolveAutomaticSettings(input,
                                category.toBuilder().selectionMode(SelectionMode.AUTOMATIC).build());
                parameters = selection.parameters;
                details += "; " + selection.explanation();
                break;
            }
            case ORACLE:
                parameters = recipeById(oracleRecipe).applyTo(category);
                details += "; recipe " + oracleRecipe
                        + " chosen after seeing this recording's own answer";
                break;
            case BASE:
                parameters = category.toBuilder()
                        .selectionMode(SelectionMode.MANUAL)
                        .pixelSupport(PairAligner.PixelSupport.ALL)
                        .floorPercentile(Double.NaN).ceilingPercentile(Double.NaN)
                        .preprocessing(Preprocessing.NONE)
                        .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                        .pixelSelectionPreprocessing(Preprocessing.NONE)
                        .pixelRemovalPercent(25.0).build();
                break;
            default:
                throw new IllegalStateException("unhandled arm " + arm);
        }
        RelativeIntensityPatternResult result = RelativeIntensityPatternRegistration.register(input, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double seconds = (System.nanoTime() - start) / 1e9;
        double cpuSeconds = (processCpuTime() - cpuStart) / 1e9;
        try {
            double[] metrics = FullSelectorFactorialBenchmark.controlledMetrics(
                    result.registration().cumulative, recording.motion);
            if (writeImages) {
                IJ.saveAsTiff(result.correctedImage(), output.resolve("corrected.tif").toString());
            }
            writeTransforms(output.resolve("transforms.csv"), result.registration().cumulative,
                    recording.motion);
            RegistrationRecipe recipe = result.resolvedRecipe();
            writeSettings(output.resolve("settings.csv"), recording, arm, result.parameters(), recipe);
            String row = csv("controlled") + ',' + csv(recording.imageClass) + ','
                    + csv(recording.series) + ',' + csv(recording.independentGroup) + ','
                    + csv(recording.motion) + ',' + csv(recording.condition) + ','
                    + csv(arm.id) + ",ok," + format(metrics[0]) + ',' + format(metrics[1]) + ','
                    + format(metrics[2]) + ',' + format(seconds) + ',' + format(cpuSeconds) + ','
                    + csv(recipe.id()) + ',' + csv(recipe.describe()) + ',' + csv(details);
            Files.write(output.resolve("comparison.csv"),
                    (HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(output.resolve("run_failure.txt"));
            Files.deleteIfExists(output.resolve("unavailable.txt"));
            System.out.printf(Locale.ROOT, "%-16s %-34s %-38s %.4f px %.2f s%n",
                    recording.imageClass, recording.series, arm.id, metrics[0], seconds);
        } finally {
            result.correctedImage().changes = false;
            result.close();
        }
    }

    /**
     * The older information selector expressed as explicit parameters. This is the same decision its
     * {@code applyTo} makes on registration options, written into the public parameter fields so the
     * arm runs through exactly the production path the other arms use.
     */
    private static RelativeIntensityPatternParameters informationSelectorParameters(ImagePlus input,
                                                                    RelativeIntensityPatternParameters category) {
        StackFrames frames = StackFrames.of(input, category.channel, category.slice);
        AutomaticInformationSelector.Result decision =
                AutomaticInformationSelector.select(frames, category.epsilon, false);
        RelativeIntensityPatternParameters.Builder builder = category.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .preprocessing(Preprocessing.NONE)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .pixelSelectionPreprocessing(Preprocessing.NONE)
                .pixelRemovalPercent(25.0)
                .floorPercentile(Double.NaN)
                .ceilingPercentile(Double.NaN)
                .gradientFraction(0.5);
        if (decision.choice == AutomaticInformationSelector.Choice.SPARSE_MUTUAL_NOISE_EDGES) {
            builder.pixelSupport(PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT)
                    .maxIterations(12).maxSamples(50_000);
        } else {
            builder.pixelSupport(PairAligner.PixelSupport.GRADIENT);
        }
        if (decision.choice
                == AutomaticInformationSelector.Choice.STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT) {
            builder.ceilingPercentile(
                    AutomaticInformationSelector.STABLE_BRIGHTEST_CEILING_PERCENTILE);
        }
        return builder.recipeProvenance("information selector chose " + decision.choice
                + String.format(Locale.ROOT, " (sparse %.3f, moving tail %.3f)",
                        decision.sparseScore, decision.movingTailRatio)).build();
    }

    static RelativeIntensityPatternParameters categoryBase(FullSelectorFactorialBenchmark.Recording recording) {
        return RelativeIntensityPatternParameters.builder()
                .recommendation(FullSelectorFactorialBenchmark.imageType(recording.imageClass),
                        FullSelectorFactorialBenchmark.motionType(recording.motion))
                .autoMaxShift(false)
                .maxShift(FullSelectorFactorialBenchmark.knownMaxShift(recording.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .build();
    }

    /**
     * Rebuild an oracle pick by identifier, over <em>both</em> axes.
     *
     * <p>{@link RegistrationRecipe#allCandidates()} rather than {@code sweptCandidates()}, and that
     * is a fix rather than a widening: the oracle identifier is read straight out of the sweep
     * summary, the sweep has covered 112 candidates since the estimator axis was added, and on five
     * of the 80 development recordings the per-recording best is an area-correlation recipe. Looking
     * those up in the 96 log-ratio recipes threw, so the oracle arm failed on exactly the recordings
     * where the second axis won — the ones it most needed to report.
     */
    private static RegistrationRecipe recipeById(String id) {
        for (RegistrationRecipe recipe : RegistrationRecipe.allCandidates()) {
            if (recipe.id().equals(id)) return recipe;
        }
        throw new IllegalArgumentException("unknown oracle recipe " + id);
    }

    /** Per-recording oracle recipes taken from the development sweep, when one exists. */
    private static Map<String, String> readOracle(Path project) throws IOException {
        Path file = project.resolve("library/benchmark/v2/benchmarks/controlled_motion/summaries")
                .resolve(FullSelectorFactorialBenchmark.RUN_ID).resolve("oracle_by_recording.csv");
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> fields = FullSelectorFactorialBenchmark.fields(lines.get(i));
            String key = fields.get(0) + '/' + fields.get(1) + '/' + fields.get(3) + '/'
                    + fields.get(4);
            out.put(key, fields.get(5));
        }
        return out;
    }

    private static void writeUnavailable(FullSelectorFactorialBenchmark.Recording recording, Arm arm,
                                         Path output, String reason) throws IOException {
        Files.createDirectories(output);
        Files.write(output.resolve("unavailable.txt"),
                (reason + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        String row = csv("controlled") + ',' + csv(recording.imageClass) + ','
                + csv(recording.series) + ',' + csv(recording.independentGroup) + ','
                + csv(recording.motion) + ',' + csv(recording.condition) + ',' + csv(arm.id)
                + ",unavailable,NaN,NaN,NaN,NaN,NaN,,," + csv(reason);
        Files.write(output.resolve("comparison.csv"),
                (HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(output.resolve("corrected.tif"));
        Files.deleteIfExists(output.resolve("transforms.csv"));
    }

    private static void writeSettings(Path path, FullSelectorFactorialBenchmark.Recording recording,
                                      Arm arm, RelativeIntensityPatternParameters p, RegistrationRecipe recipe)
            throws IOException {
        StringBuilder out = new StringBuilder("setting,value\n");
        out.append(csv("run_id")).append(',').append(csv(RUN_ID)).append('\n');
        out.append(csv("arm_id")).append(',').append(csv(arm.id)).append('\n');
        out.append(csv("arm_label")).append(',').append(csv(arm.label)).append('\n');
        out.append(csv("recording")).append(',').append(csv(recording.key())).append('\n');
        out.append(csv("selection_mode")).append(',')
                .append(csv(p.selectionMode.macroValue())).append('\n');
        out.append(csv("recipe_provenance")).append(',').append(csv(p.recipeProvenance)).append('\n');
        out.append(csv("resolved_recipe_id")).append(',').append(csv(recipe.id())).append('\n');
        out.append(csv("resolved_recipe")).append(',').append(csv(recipe.describe())).append('\n');
        out.append(csv("macro_options")).append(',')
                .append(csv(new RelativeIntensityPatternDialogModel(p).toMacroOptions())).append('\n');
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

    private static boolean complete(Path output, boolean writeImages) {
        Path comparison = output.resolve("comparison.csv");
        if (!Files.isRegularFile(comparison)) return false;
        try {
            List<String> lines = Files.readAllLines(comparison, StandardCharsets.UTF_8);
            if (lines.size() < 2 || !lines.get(0).equals(HEADER)) return false;
        } catch (IOException error) {
            return false;
        }
        if (Files.isRegularFile(output.resolve("unavailable.txt"))) return true;
        if (Files.isRegularFile(output.resolve("run_failure.txt"))) return true;
        return Files.isRegularFile(output.resolve("transforms.csv"))
                && Files.isRegularFile(output.resolve("settings.csv"))
                && (!writeImages || Files.isRegularFile(output.resolve("corrected.tif")));
    }

    private static void writeSummary(Path root, Path summary,
                                     List<FullSelectorFactorialBenchmark.Recording> recordings)
            throws IOException {
        List<List<String>> rows = new ArrayList<>();
        StringBuilder all = new StringBuilder(HEADER).append('\n');
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

        Map<String, double[]> byArm = new LinkedHashMap<>();
        Map<String, Map<String, double[]>> byArmAndClass = new LinkedHashMap<>();
        for (List<String> row : rows) {
            if (!"ok".equals(row.get(7))) continue;
            double median = Double.parseDouble(row.get(8));
            double p90 = Double.parseDouble(row.get(9));
            double seconds = Double.parseDouble(row.get(11));
            add(byArm.computeIfAbsent(row.get(6), key -> new double[4]), median, p90, seconds);
            add(byArmAndClass.computeIfAbsent(row.get(6), key -> new TreeMap<>())
                    .computeIfAbsent(row.get(1), key -> new double[4]), median, p90, seconds);
        }
        StringBuilder out = new StringBuilder(
                "arm_id,arm_label,recordings,mean_median_error_px,mean_p90_error_px,mean_seconds\n");
        for (Arm arm : Arm.values()) {
            double[] totals = byArm.get(arm.id);
            if (totals == null) continue;
            out.append(csv(arm.id)).append(',').append(csv(arm.label)).append(',')
                    .append((int) totals[3]).append(',').append(format(totals[0] / totals[3]))
                    .append(',').append(format(totals[1] / totals[3])).append(',')
                    .append(format(totals[2] / totals[3])).append('\n');
        }
        Files.write(summary.resolve("arm_summary.csv"), out.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder byClass = new StringBuilder(
                "arm_id,image_series_class,recordings,mean_median_error_px,mean_p90_error_px,mean_seconds\n");
        for (Arm arm : Arm.values()) {
            Map<String, double[]> perClass = byArmAndClass.get(arm.id);
            if (perClass == null) continue;
            for (Map.Entry<String, double[]> entry : perClass.entrySet()) {
                double[] totals = entry.getValue();
                byClass.append(csv(arm.id)).append(',').append(csv(entry.getKey())).append(',')
                        .append((int) totals[3]).append(',').append(format(totals[0] / totals[3]))
                        .append(',').append(format(totals[1] / totals[3])).append(',')
                        .append(format(totals[2] / totals[3])).append('\n');
            }
        }
        Files.write(summary.resolve("arm_summary_by_image_type.csv"),
                byClass.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void add(double[] totals, double median, double p90, double seconds) {
        totals[0] += median;
        totals[1] += p90;
        totals[2] += seconds;
        totals[3] += 1;
    }

    private static Set<String> requested(String property) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : System.getProperty(property, "").split(",")) {
            if (!value.trim().isEmpty()) out.add(value.trim());
        }
        return out;
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return System.nanoTime();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }
}
