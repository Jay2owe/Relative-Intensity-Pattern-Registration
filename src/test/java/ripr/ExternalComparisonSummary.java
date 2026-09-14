/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.Transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One table holding this plugin's arms and the installed third-party engines side by side.
 *
 * <p>The selector comparison answers "which of our settings is best". It cannot say whether any of
 * them is worth using, because every arm in it shares the same engine. This joins those arms to the
 * twelve third-party Fiji engines already run on the same recordings, so the two questions are
 * answered from one file.
 *
 * <p>Nothing is registered again. Third-party accuracy is recomputed from each engine's saved
 * {@code <stem>_transforms.csv} with {@link FullSelectorFactorialBenchmark#controlledMetrics}, the
 * same metric and the same code path used for our own arms, so a difference in the table cannot be a
 * difference in how the two were scored. Timing is read from the engines' own {@code comparison.csv},
 * which is the only place it was recorded.
 *
 * <p>Point it at a root with {@code -Dlogratio.comparisonRoot=<relative path>}.
 */
public final class ExternalComparisonSummary {
    static final String RUN_ID = "external_comparison_v1";
    static final String SELECTOR_RUN = SelectorComparisonBenchmark.RUN_ID;

    static final String ROW_HEADER = "image_series_class,series_id,independent_group,"
            + "motion_category,condition,method_id,method_label,origin,family,status,"
            + "median_error_px,p90_error_px,max_error_px,elapsed_seconds";

    static final String SUMMARY_HEADER = "method_id,method_label,origin,family,recordings,"
            + "missing,mean_median_error_px,median_of_median_error_px,mean_p90_error_px,"
            + "worst_median_error_px,mean_seconds";

    /** Third-party engines whose whole pipeline is theirs, including how frames are chained. */
    private static final String NATIVE_ORIGIN = "third-party engine, native strategy";

    /**
     * Third-party engines used only to estimate one frame pair, with our multi-lag redundant
     * cross-correlation solver reconciling those pairs. Their accuracy here is not their plugin's
     * accuracy, and the row is labelled so nobody reads it as such.
     */
    private static final String HYBRID_ORIGIN = "third-party pair estimator, our multi-lag solver";

    static final class External {
        final String stem;
        final String label;
        final String origin;

        External(String stem, String label, String origin) {
            this.stem = stem;
            this.label = label;
            this.origin = origin;
        }
    }

    static List<External> externals() {
        return Arrays.asList(
                new External("21_real_stackreg_turboreg_translation_chain",
                        "StackReg: rigid body, previous frame", NATIVE_ORIGIN),
                new External("22_real_turboreg_translation_multilag_rcc",
                        "TurboReg: rigid body, multiple lags", HYBRID_ORIGIN),
                new External("23_real_multistackreg_translation_same_engine",
                        "MultiStackReg: rigid body", NATIVE_ORIGIN),
                new External("24_real_image_stabilizer_lucas_kanade_rolling_template",
                        "Image Stabilizer: translation", NATIVE_ORIGIN),
                new External("25_real_fast4dreg_nanoj_previous_frame",
                        "Fast4DReg: previous-frame reference", NATIVE_ORIGIN),
                new External("26_real_fast4dreg_nanoj_first_frame",
                        "Fast4DReg: first-frame reference", NATIVE_ORIGIN),
                new External("27_real_correct_3d_drift_phase_correlation_standard",
                        "Correct 3D Drift: standard", NATIVE_ORIGIN),
                new External("28_real_correct_3d_drift_phase_correlation_multitime",
                        "Correct 3D Drift: multi-time-scale", NATIVE_ORIGIN),
                new External("29_real_linear_stack_alignment_sift_translation_chain",
                        "Linear Stack Alignment with SIFT: rigid", NATIVE_ORIGIN),
                new External("30_real_sift_translation_multilag_rcc",
                        "SIFT: rigid, multiple lags", HYBRID_ORIGIN),
                new External("31_register_virtual_stack_slices_same_sift_engine",
                        "Register Virtual Stack Slices", NATIVE_ORIGIN),
                new External("32_real_descriptor_based_series_translation",
                        "Descriptor-based series registration: rigid", NATIVE_ORIGIN));
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        String relative = System.getProperty("ripr.comparisonRoot",
                "library/benchmark/v2/benchmarks/controlled_motion");
        Path root = project.resolve(relative).normalize();
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Files.createDirectories(summary);

        List<FullSelectorFactorialBenchmark.Recording> recordings =
                FullSelectorFactorialBenchmark.recordings(root);
        List<External> externals = externals();
        Map<String, Map<String, String[]>> ours = readOurArms(
                root.resolve("summaries").resolve(SELECTOR_RUN).resolve("all_recordings.csv"));

        List<String> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            String recordingKey = recording.key();
            Map<String, Double> timing = readTiming(recording.folder.resolve("comparison.csv"));
            Map<String, String> families = readFamilies(recording.folder.resolve("comparison.csv"));

            Map<String, String[]> mine = ours.get(recordingKey);
            if (mine != null) {
                for (Map.Entry<String, String[]> entry : new TreeMap<>(mine).entrySet()) {
                    String[] f = entry.getValue();
                    rows.add(prefix(recording) + csv(entry.getKey()) + ','
                            + csv(f[4]) + ',' + csv("this plugin") + ',' + csv("log-ratio engine")
                            + ',' + csv(f[0]) + ',' + f[1] + ',' + f[2] + ',' + f[3] + ','
                            + f[5]);
                }
            } else {
                missing.add(recordingKey + ": no selector arms in " + SELECTOR_RUN);
            }

            for (External external : externals) {
                Path transforms = recording.folder.resolve(external.stem + "_transforms.csv");
                if (!Files.isRegularFile(transforms)) {
                    missing.add(recordingKey + " / " + external.stem + ": no transforms.csv");
                    rows.add(prefix(recording) + csv(external.stem) + ',' + csv(external.label)
                            + ',' + csv(external.origin) + ','
                            + csv(families.getOrDefault(external.stem, "")) + ",missing,"
                            + "NaN,NaN,NaN,NaN");
                    continue;
                }
                Transform[] cumulative = SweepProxyOracleBenchmark.readTransforms(transforms);
                int width = ExternalPluginComparisonStacks.frameWidth(
                        recording.folder.resolve("00_input_uncorrected.tif"));
                double[] metrics = ExternalPluginComparisonStacks.controlledGeometricMetrics(
                        cumulative, recording.motion, width);
                Double seconds = timing.get(external.stem);
                rows.add(prefix(recording) + csv(external.stem) + ',' + csv(external.label) + ','
                        + csv(external.origin) + ','
                        + csv(families.getOrDefault(external.stem, "")) + ",ok,"
                        + format(metrics[0]) + ',' + format(metrics[1]) + ','
                        + format(metrics[2]) + ','
                        + (seconds == null ? "NaN" : format(seconds)));
            }
        }

        write(summary.resolve("all_methods_by_recording.csv"), ROW_HEADER, rows);
        writeSummaries(summary, rows, recordings.size());
        System.out.printf(Locale.ROOT, "%s: %d recordings, %d rows, %d missing%n",
                RUN_ID, recordings.size(), rows.size(), missing.size());
        for (String one : missing) System.out.println("  missing: " + one);
    }

    private static String prefix(FullSelectorFactorialBenchmark.Recording recording) {
        return csv(recording.imageClass) + ',' + csv(recording.series) + ','
                + csv(recording.independentGroup) + ',' + csv(recording.motion) + ','
                + csv(recording.condition) + ',';
    }

    /**
     * Our own arms, keyed by recording then arm. Each value is
     * {@code {status, median, p90, max, label, seconds}} taken verbatim from the selector comparison,
     * so the two records cannot drift apart.
     */
    private static Map<String, Map<String, String[]>> readOurArms(Path path) throws IOException {
        Map<String, Map<String, String[]>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return out;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> header = FullSelectorFactorialBenchmark.fields(lines.get(0));
        int classAt = header.indexOf("image_series_class");
        int seriesAt = header.indexOf("series_id");
        int motionAt = header.indexOf("motion_category");
        int conditionAt = header.indexOf("condition");
        int armAt = header.indexOf("arm_id");
        int statusAt = header.indexOf("status");
        int medianAt = header.indexOf("median_error_px");
        int p90At = header.indexOf("p90_error_px");
        int maxAt = header.indexOf("max_error_px");
        int secondsAt = header.indexOf("total_seconds");
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            String key = f.get(classAt) + '/' + f.get(seriesAt) + '/' + f.get(motionAt) + '/'
                    + f.get(conditionAt);
            out.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(f.get(armAt), new String[]{
                    f.get(statusAt), f.get(medianAt), f.get(p90At), f.get(maxAt),
                    label(f.get(armAt)), f.get(secondsAt)});
        }
        return out;
    }

    /** Turn an arm folder name into something a reader can compare against a plugin name. */
    private static String label(String armId) {
        int underscore = armId.indexOf('_');
        String rest = underscore < 0 ? armId : armId.substring(underscore + 1);
        return rest.replace('_', ' ');
    }

    private static Map<String, Double> readTiming(Path comparison) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(comparison)) return out;
        List<String> lines = Files.readAllLines(comparison, StandardCharsets.UTF_8);
        List<String> header = FullSelectorFactorialBenchmark.fields(lines.get(0));
        int methodAt = header.indexOf("method");
        int elapsedAt = header.indexOf("elapsed_seconds_per_stack");
        if (methodAt < 0 || elapsedAt < 0) return out;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            try {
                out.put(f.get(methodAt), Double.parseDouble(f.get(elapsedAt)));
            } catch (RuntimeException ignored) {
                // A method with no usable timing simply has none; accuracy still counts.
            }
        }
        return out;
    }

    private static Map<String, String> readFamilies(Path comparison) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(comparison)) return out;
        List<String> lines = Files.readAllLines(comparison, StandardCharsets.UTF_8);
        List<String> header = FullSelectorFactorialBenchmark.fields(lines.get(0));
        int methodAt = header.indexOf("method");
        int familyAt = header.indexOf("family");
        if (methodAt < 0 || familyAt < 0) return out;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out.put(f.get(methodAt), f.get(familyAt));
        }
        return out;
    }

    private static void writeSummaries(Path summary, List<String> rows, int recordings)
            throws IOException {
        List<String> header = FullSelectorFactorialBenchmark.fields(ROW_HEADER);
        int classAt = header.indexOf("image_series_class");
        int methodAt = header.indexOf("method_id");
        int labelAt = header.indexOf("method_label");
        int originAt = header.indexOf("origin");
        int familyAt = header.indexOf("family");
        int statusAt = header.indexOf("status");
        int medianAt = header.indexOf("median_error_px");
        int p90At = header.indexOf("p90_error_px");
        int secondsAt = header.indexOf("elapsed_seconds");

        Map<String, Aggregate> overall = new LinkedHashMap<>();
        Map<String, Map<String, Aggregate>> byType = new LinkedHashMap<>();
        for (String row : rows) {
            List<String> f = FullSelectorFactorialBenchmark.fields(row);
            String method = f.get(methodAt);
            Aggregate all = overall.computeIfAbsent(method,
                    k -> new Aggregate(f.get(labelAt), f.get(originAt), f.get(familyAt)));
            all.add(f, statusAt, medianAt, p90At, secondsAt);
            byType.computeIfAbsent(f.get(classAt), k -> new LinkedHashMap<>())
                    .computeIfAbsent(method,
                            k -> new Aggregate(f.get(labelAt), f.get(originAt), f.get(familyAt)))
                    .add(f, statusAt, medianAt, p90At, secondsAt);
        }

        List<String> summaryRows = new ArrayList<>();
        for (Map.Entry<String, Aggregate> entry : new TreeMap<>(overall).entrySet()) {
            summaryRows.add(csv(entry.getKey()) + ',' + entry.getValue().row(recordings));
        }
        write(summary.resolve("method_summary.csv"), SUMMARY_HEADER, summaryRows);

        List<String> typeRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Aggregate>> type : new TreeMap<>(byType).entrySet()) {
            for (Map.Entry<String, Aggregate> entry
                    : new TreeMap<>(type.getValue()).entrySet()) {
                typeRows.add(csv(type.getKey()) + ',' + csv(entry.getKey()) + ','
                        + entry.getValue().row(-1));
            }
        }
        write(summary.resolve("method_summary_by_image_type.csv"),
                "image_series_class," + SUMMARY_HEADER, typeRows);

        Map<String, Map<String, double[]>> byRecording = new LinkedHashMap<>();
        Map<String, String[]> identity = new LinkedHashMap<>();
        Map<String, String> classOf = new LinkedHashMap<>();
        for (String row : rows) {
            List<String> f = FullSelectorFactorialBenchmark.fields(row);
            String recordingKey = f.get(header.indexOf("series_id")) + '/'
                    + f.get(header.indexOf("motion_category")) + '/'
                    + f.get(header.indexOf("condition"));
            classOf.put(recordingKey, f.get(classAt));
            identity.put(f.get(methodAt),
                    new String[]{f.get(labelAt), f.get(originAt), f.get(familyAt)});
            double median = "ok".equals(f.get(statusAt))
                    ? Double.parseDouble(f.get(medianAt)) : Double.NaN;
            byRecording.computeIfAbsent(recordingKey, k -> new LinkedHashMap<>())
                    .put(f.get(methodAt), new double[]{median});
        }
        writePaired(summary, byRecording, identity, classOf);
        writeFindings(summary, overall, byRecording, identity, classOf, recordings);
    }

    private static final class Aggregate {
        final String label;
        final String origin;
        final String family;
        final List<Double> medians = new ArrayList<>();
        double medianSum;
        double p90Sum;
        double secondsSum;
        int secondsCount;
        int ok;
        int missing;

        Aggregate(String label, String origin, String family) {
            this.label = label;
            this.origin = origin;
            this.family = family;
        }

        void add(List<String> f, int statusAt, int medianAt, int p90At, int secondsAt) {
            if (!"ok".equals(f.get(statusAt))) {
                missing++;
                return;
            }
            double median = Double.parseDouble(f.get(medianAt));
            double p90 = Double.parseDouble(f.get(p90At));
            // An engine that returned shifts but non-finite ones has not produced an answer. Counting
            // it as a success would let it drop its hardest recordings and keep the flattering ones.
            if (!Double.isFinite(median)) {
                missing++;
                return;
            }
            ok++;
            medians.add(median);
            medianSum += median;
            p90Sum += p90;
            double seconds = Double.parseDouble(f.get(secondsAt));
            if (Double.isFinite(seconds)) {
                secondsSum += seconds;
                secondsCount++;
            }
        }

        double meanMedian() {
            return ok == 0 ? Double.NaN : medianSum / ok;
        }

        double medianOfMedians() {
            if (medians.isEmpty()) return Double.NaN;
            List<Double> sorted = new ArrayList<>(medians);
            java.util.Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        double worst() {
            double worst = Double.NaN;
            for (double value : medians) {
                if (Double.isNaN(worst) || value > worst) worst = value;
            }
            return worst;
        }

        double meanSeconds() {
            return secondsCount == 0 ? Double.NaN : secondsSum / secondsCount;
        }

        String row(int expected) {
            int shortfall = expected < 0 ? missing : missing + Math.max(0, expected - ok - missing);
            return csv(label) + ',' + csv(origin) + ',' + csv(family) + ',' + ok + ','
                    + shortfall + ',' + format(meanMedian()) + ',' + format(medianOfMedians())
                    + ',' + format(p90Sum / Math.max(1, ok)) + ',' + format(worst()) + ','
                    + format(meanSeconds());
        }
    }

    /** The arm the plugin ships with, and the only fair thing to measure another method against. */
    static final String OUR_DEFAULT = "4_new_full_automatic_selector";

    /**
     * Compare each method to our default on the recordings where both produced a finite answer.
     *
     * <p>Unpaired medians flatter any method that fails on the hard recordings, because a failure
     * removes that recording from its column but not from ours. Two of the installed engines fail on
     * exactly the sparse source every method finds hardest, which is enough to invert their ranking.
     */
    private static void writePaired(Path summary, Map<String, Map<String, double[]>> byRecording,
                                    Map<String, String[]> identity, Map<String, String> classOf)
            throws IOException {
        List<String> rows = new ArrayList<>();
        for (String method : new TreeMap<>(identity).keySet()) {
            for (String scope : scopes(classOf)) {
                Paired paired = pair(method, scope, byRecording, classOf);
                if (paired.n == 0) continue;
                String[] who = identity.get(method);
                rows.add(csv(method) + ',' + csv(who[0]) + ',' + csv(who[1]) + ',' + csv(scope)
                        + ',' + paired.n + ',' + paired.dropped + ','
                        + format(paired.method()) + ',' + format(paired.ours()) + ','
                        + format(paired.method() / paired.ours()));
            }
        }
        write(summary.resolve("paired_against_default.csv"),
                "method_id,method_label,origin,scope,paired_recordings,dropped_recordings,"
                        + "method_median_px,our_default_median_px,ratio", rows);
    }

    private static List<String> scopes(Map<String, String> classOf) {
        List<String> out = new ArrayList<>();
        out.add("ALL");
        out.addAll(new java.util.TreeSet<>(classOf.values()));
        return out;
    }

    private static final class Paired {
        final List<Double> method = new ArrayList<>();
        final List<Double> ours = new ArrayList<>();
        int n;
        int dropped;

        double method() {
            return middle(method);
        }

        double ours() {
            return middle(ours);
        }

        private static double middle(List<Double> values) {
            if (values.isEmpty()) return Double.NaN;
            List<Double> sorted = new ArrayList<>(values);
            java.util.Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }
    }

    private static Paired pair(String method, String scope,
                               Map<String, Map<String, double[]>> byRecording,
                               Map<String, String> classOf) {
        Paired out = new Paired();
        for (Map.Entry<String, Map<String, double[]>> entry : byRecording.entrySet()) {
            if (!"ALL".equals(scope) && !scope.equals(classOf.get(entry.getKey()))) continue;
            double[] theirs = entry.getValue().get(method);
            double[] mine = entry.getValue().get(OUR_DEFAULT);
            if (mine == null || !Double.isFinite(mine[0])) continue;
            if (theirs == null || !Double.isFinite(theirs[0])) {
                out.dropped++;
                continue;
            }
            out.method.add(theirs[0]);
            out.ours.add(mine[0]);
            out.n++;
        }
        return out;
    }

    private static void writeFindings(Path summary, Map<String, Aggregate> overall,
                                      Map<String, Map<String, double[]>> byRecording,
                                      Map<String, String[]> identity,
                                      Map<String, String> classOf, int recordings)
            throws IOException {
        List<Map.Entry<String, Aggregate>> ranked = new ArrayList<>(overall.entrySet());
        ranked.sort((a, b) -> Double.compare(a.getValue().medianOfMedians(),
                b.getValue().medianOfMedians()));

        StringBuilder out = new StringBuilder();
        out.append("# This plugin against the installed third-party engines\n\n");
        out.append("Run id `").append(RUN_ID).append("`, ").append(recordings)
                .append(" recordings. Third-party accuracy is recomputed from each engine's saved ")
                .append("shifts with the same metric used for our own arms, so the comparison is ")
                .append("not resting on two different scorers.\n\n");
        out.append("Ranked by the median across recordings, not the mean: a handful of recordings ")
                .append("where an engine loses lock entirely produce whole-pixel errors that decide ")
                .append("any mean by themselves. Both columns are given.\n\n");
        out.append("| # | Method | Origin | Median of median (px) | Mean median (px) | Worst (px) "
                + "| Mean s |\n|---|---|---|---|---|---|---|\n");
        int rank = 1;
        for (Map.Entry<String, Aggregate> entry : ranked) {
            Aggregate a = entry.getValue();
            out.append("| ").append(unattainable(entry.getKey()) ? "--" : Integer.toString(rank++))
                    .append(" | ").append(a.label)
                    .append(unattainable(entry.getKey()) ? " (not a method: needs the answer)" : "")
                    .append(" | ").append(a.origin).append(" | ")
                    .append(format(a.medianOfMedians()))
                    .append(" | ").append(format(a.meanMedian())).append(" | ")
                    .append(format(a.worst())).append(" | ").append(format(a.meanSeconds()))
                    .append(" |\n");
        }
        out.append("\nRows marked `third-party pair estimator, our multi-lag solver` are not that ")
                .append("plugin's own pipeline: only its frame-pair estimate is theirs, and our ")
                .append("redundant cross-correlation solver reconciles the pairs. They measure what ")
                .append("their estimator is worth inside our scheduler, and should not be quoted as ")
                .append("that plugin's accuracy.\n");

        out.append("\n## Per image type, paired against our default\n\n");
        out.append("Every row below is restricted to the recordings where **both** that method and ")
                .append("our default produced a finite answer, and states how many recordings were ")
                .append("dropped to achieve that. Unpaired medians reward a method for failing: a ")
                .append("failure removes the recording from its column but not from ours. Two of the ")
                .append("installed engines fail on exactly the sparse source everything finds ")
                .append("hardest, which is enough to invert their ranking if this is not done.\n\n");
        out.append("The oracle is excluded: it is a bookkeeping ceiling, not something a user can ")
                .append("run.\n\n");
        out.append("| Image type | Best installable plugin | Paired n | Dropped | Its median (px) "
                + "| Our default (px) | Ratio |\n|---|---|---|---|---|---|---|\n");
        for (String scope : scopes(classOf)) {
            if ("ALL".equals(scope)) continue;
            String bestId = null;
            Paired best = null;
            for (String method : identity.keySet()) {
                if (!NATIVE_ORIGIN.equals(identity.get(method)[1])) continue;
                Paired paired = pair(method, scope, byRecording, classOf);
                if (paired.n == 0 || !Double.isFinite(paired.method())) continue;
                if (best == null || paired.method() < best.method()) {
                    best = paired;
                    bestId = method;
                }
            }
            if (best == null) continue;
            out.append("| ").append(scope).append(" | ").append(identity.get(bestId)[0])
                    .append(" | ").append(best.n).append(" | ").append(best.dropped)
                    .append(" | ").append(format(best.method())).append(" | ")
                    .append(format(best.ours())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2fx", best.method() / best.ours()))
                    .append(" |\n");
        }
        out.append("\n`Best installable plugin` is restricted to engines a user can run as shipped. ")
                .append("A ratio above 1 means that plugin is that many times worse than our default ")
                .append("on the shared recordings; below 1 means it is better. A non-zero `Dropped` ")
                .append("means the comparison excludes recordings that method could not complete, ")
                .append("so read its median as conditional on succeeding. Full paired figures for ")
                .append("every method, including the hybrids, are in `paired_against_default.csv`.\n");
        Files.write(summary.resolve("FINDINGS.md"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The oracle arm is a ceiling computed with hindsight, so it never takes a rank. */
    private static boolean unattainable(String methodId) {
        return methodId.contains("oracle");
    }

    private static void write(Path path, String header, List<String> rows) throws IOException {
        StringBuilder out = new StringBuilder(header).append('\n');
        for (String row : rows) out.append(row).append('\n');
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }

    private ExternalComparisonSummary() {
    }
}
