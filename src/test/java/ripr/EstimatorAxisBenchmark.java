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
import ripr.api.Preprocessing;
import ripr.api.SelectionMode;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.PairScheduler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stage 3 of {@code docs/pairwise_estimator_axis_plan.md}: what is the pairwise estimator worth?
 *
 * <p>Two arms over the same recordings, differing in exactly one setting. Both run our scheduler, our
 * lag set, our {@code Reconciler.multiLag}, our warp and our metric; both read the same pyramids at
 * the same {@code maxShift} and the same compute budget. The only difference is which estimator turns
 * a frame pair into a movement.
 *
 * <p><b>Why both arms use the base recipe rather than the shipped default.</b> The selector's chosen
 * recipes are settings <em>of</em> the log-ratio fit — pixel support, intensity band, estimation
 * filter, pixel removal — and {@code RelativeIntensityPatternParameters} rejects them outright when the estimator is
 * not the log-ratio fit, because there is no per-pixel support to mask in a whole-window correlation.
 * Comparing a tuned log-ratio arm against an untunable correlation arm would measure the selector, not
 * the estimator. Holding both at the base recipe is the only single-variable comparison available, and
 * it is the comparison the question asks for.
 *
 * <p>That makes this an estimator comparison and <b>not</b> a claim about the shipped default. The
 * shipped default is the six-arm {@code selector_comparison_v1}, which is unaffected by anything here.
 * Whether the selector should ever choose the area estimator is Stage 4, and needs this answer first.
 *
 * <p>Point it at a root with {@code -Dlogratio.comparisonRoot=<relative path>}; the default is the
 * development controlled-motion tree. It writes nothing into any sealed tree unless asked to.
 */
public final class EstimatorAxisBenchmark {
    static final String RUN_ID = "estimator_axis_v1";

    static final String HEADER = "experiment,image_series_class,series_id,independent_group,"
            + "motion_category,condition,arm_id,estimator_id,status,median_error_px,p90_error_px,"
            + "max_error_px,total_seconds,cpu_seconds,details";

    enum Arm {
        LOG_RATIO("1_base_log_ratio_fit", "Base recipe, log-ratio fit",
                PairEstimator.Kind.LOG_RATIO_FIT),
        AREA("2_base_area_correlation", "Base recipe, area correlation",
                PairEstimator.Kind.AREA_CORRELATION),
        /**
         * The linear-pyramid variant, added after the first run of this benchmark showed our area
         * estimator roughly three times less accurate than TurboReg's under identical surroundings.
         * It differs from {@link #AREA} in one thing: coarse pyramid levels are arithmetic means of
         * intensity rather than geometric ones. If the gap is the decimation domain, this arm closes
         * it; if it does not, the domain was not the cause and the area family closes on our own
         * evidence.
         */
        AREA_LINEAR("3_base_area_correlation_linear", "Base recipe, area correlation, linear pyramid",
                PairEstimator.Kind.AREA_CORRELATION_LINEAR);

        final String id;
        final String label;
        final PairEstimator.Kind estimator;

        Arm(String id, String label, PairEstimator.Kind estimator) {
            this.id = id;
            this.label = label;
            this.estimator = estimator;
        }
    }

    private EstimatorAxisBenchmark() {
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

        List<String> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int completed = 0;
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
                    Path line = output.resolve("comparison.csv");
                    if (!rewrite && Files.isRegularFile(line)) {
                        rows.add(Files.readAllLines(line, StandardCharsets.UTF_8).get(1));
                        continue;
                    }
                    try {
                        String row = runArm(recording, input, arm, output);
                        rows.add(row);
                        completed++;
                    } catch (RuntimeException | IOException error) {
                        Files.createDirectories(output);
                        String message = recording + " / " + arm.id + ": " + error;
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

        writeAll(summary, rows);
        writeArmSummary(summary, rows);
        writeByImageType(summary, rows);
        writeByMotion(summary, rows);
        writePaired(summary, rows);
        System.out.printf(Locale.ROOT, "estimator axis over %s: %d rows, %d computed, %d failures%n",
                relative, rows.size(), completed, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("estimator axis failures:\n" + String.join("\n", failures));
        }
    }

    /**
     * One arm on one recording. The parameter block below is deliberately identical for both arms
     * apart from {@code estimator}; anything that differed would make the result uninterpretable.
     */
    private static String runArm(FullSelectorFactorialBenchmark.Recording recording, ImagePlus input,
                                 Arm arm, Path output) throws IOException {
        Files.createDirectories(output);
        RelativeIntensityPatternParameters parameters = SelectorComparisonBenchmark.categoryBase(recording)
                .toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .estimator(arm.estimator)
                .pixelSupport(PairAligner.PixelSupport.ALL)
                .floorPercentile(Double.NaN).ceilingPercentile(Double.NaN)
                .preprocessing(Preprocessing.NONE)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .pixelSelectionPreprocessing(Preprocessing.NONE)
                .pixelRemovalPercent(25.0)
                .build();

        long cpuStart = processCpuTime();
        long start = System.nanoTime();
        RelativeIntensityPatternResult result = RelativeIntensityPatternRegistration.register(input, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double seconds = (System.nanoTime() - start) / 1e9;
        double cpuSeconds = (processCpuTime() - cpuStart) / 1e9;

        double[] metrics = FullSelectorFactorialBenchmark.controlledMetrics(
                result.registration().cumulative, recording.motion);
        String row = row(recording, arm, "ok", metrics, seconds, cpuSeconds, arm.label);
        Files.write(output.resolve("comparison.csv"),
                (HEADER + "\n" + row + "\n").getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(output.resolve("run_failure.txt"));
        System.out.printf(Locale.ROOT, "%-16s %-34s %-28s %.4f px %.2f s%n",
                recording.imageClass, recording.series, arm.id, metrics[0], seconds);
        return row;
    }

    private static String row(FullSelectorFactorialBenchmark.Recording recording, Arm arm,
                              String status, double[] metrics, double seconds, double cpuSeconds,
                              String details) {
        StringBuilder out = new StringBuilder();
        out.append(csv("controlled")).append(',').append(csv(recording.imageClass)).append(',')
                .append(csv(recording.series)).append(',').append(csv(recording.independentGroup))
                .append(',').append(csv(recording.motion)).append(',').append(csv(recording.condition))
                .append(',').append(csv(arm.id)).append(',').append(csv(arm.estimator.id()))
                .append(',').append(status).append(',')
                .append(number(metrics[0])).append(',').append(number(metrics[1])).append(',')
                .append(number(metrics[2])).append(',').append(number(seconds)).append(',')
                .append(number(cpuSeconds)).append(',').append(csv(details));
        return out.toString();
    }

    private static void writeAll(Path summary, List<String> rows) throws IOException {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (String row : rows) out.append(row).append('\n');
        Files.write(summary.resolve("all_recordings.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeArmSummary(Path summary, List<String> rows) throws IOException {
        StringBuilder out = new StringBuilder("arm_id,estimator_id,recordings,"
                + "median_of_median_error_px,mean_median_error_px,worst_median_error_px,"
                + "lock_lost_recordings,mean_seconds,mean_cpu_seconds\n");
        for (Arm arm : Arm.values()) {
            List<double[]> values = valuesFor(rows, arm);
            if (values.isEmpty()) continue;
            out.append(csv(arm.id)).append(',').append(csv(arm.estimator.id())).append(',')
                    .append(values.size()).append(',')
                    .append(number(median(column(values, 0)))).append(',')
                    .append(number(mean(column(values, 0)))).append(',')
                    .append(number(max(column(values, 0)))).append(',')
                    .append(lockLost(column(values, 0))).append(',')
                    .append(number(mean(column(values, 3)))).append(',')
                    .append(number(mean(column(values, 4)))).append('\n');
        }
        Files.write(summary.resolve("arm_summary.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeByImageType(Path summary, List<String> rows) throws IOException {
        writeGrouped(summary.resolve("arm_summary_by_image_type.csv"), rows, 1, "image_series_class");
    }

    private static void writeByMotion(Path summary, List<String> rows) throws IOException {
        writeGrouped(summary.resolve("arm_summary_by_motion_profile.csv"), rows, 4, "motion_category");
    }

    private static void writeGrouped(Path file, List<String> rows, int keyColumn, String keyName)
            throws IOException {
        StringBuilder out = new StringBuilder(keyName + ",arm_id,recordings,"
                + "median_of_median_error_px,mean_median_error_px,worst_median_error_px,"
                + "lock_lost_recordings,mean_seconds\n");
        Map<String, Map<String, List<double[]>>> grouped = new TreeMap<>();
        for (String row : rows) {
            String[] cells = split(row);
            grouped.computeIfAbsent(cells[keyColumn], k -> new LinkedHashMap<>())
                    .computeIfAbsent(cells[6], k -> new ArrayList<>())
                    .add(metricsOf(cells));
        }
        for (Map.Entry<String, Map<String, List<double[]>>> group : grouped.entrySet()) {
            for (Arm arm : Arm.values()) {
                List<double[]> values = group.getValue().get(arm.id);
                if (values == null || values.isEmpty()) continue;
                out.append(csv(group.getKey())).append(',').append(csv(arm.id)).append(',')
                        .append(values.size()).append(',')
                        .append(number(median(column(values, 0)))).append(',')
                        .append(number(mean(column(values, 0)))).append(',')
                        .append(number(max(column(values, 0)))).append(',')
                        .append(lockLost(column(values, 0))).append(',')
                        .append(number(mean(column(values, 3)))).append('\n');
            }
        }
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Paired per image type, on the recordings where both arms produced a finite answer, with drops
     * stated — the same rule {@link ExternalComparisonSummary} uses, so the numbers can sit beside the
     * third-party table without a footnote about how each was computed.
     */
    private static void writePaired(Path summary, List<String> rows) throws IOException {
        Map<String, double[]> logRatio = new LinkedHashMap<>();
        Map<String, double[]> area = new LinkedHashMap<>();
        Map<String, String> imageClass = new LinkedHashMap<>();
        for (String row : rows) {
            String[] cells = split(row);
            String key = cells[2] + "|" + cells[4] + "|" + cells[5];
            imageClass.put(key, cells[1]);
            if (cells[6].equals(Arm.LOG_RATIO.id)) logRatio.put(key, metricsOf(cells));
            if (cells[6].equals(Arm.AREA.id)) area.put(key, metricsOf(cells));
        }
        Map<String, List<double[]>> pairedByClass = new TreeMap<>();
        Map<String, Integer> droppedByClass = new TreeMap<>();
        for (Map.Entry<String, double[]> entry : logRatio.entrySet()) {
            String type = imageClass.get(entry.getKey());
            droppedByClass.putIfAbsent(type, 0);
            double[] other = area.get(entry.getKey());
            if (other == null || !finite(other[0]) || !finite(entry.getValue()[0])) {
                droppedByClass.merge(type, 1, Integer::sum);
                continue;
            }
            pairedByClass.computeIfAbsent(type, k -> new ArrayList<>())
                    .add(new double[]{entry.getValue()[0], other[0]});
        }
        StringBuilder out = new StringBuilder("image_series_class,paired_recordings,dropped,"
                + "log_ratio_median_px,area_correlation_median_px,ratio_area_over_log_ratio\n");
        for (Map.Entry<String, List<double[]>> entry : pairedByClass.entrySet()) {
            double[] ours = new double[entry.getValue().size()];
            double[] theirs = new double[entry.getValue().size()];
            for (int i = 0; i < ours.length; i++) {
                ours[i] = entry.getValue().get(i)[0];
                theirs[i] = entry.getValue().get(i)[1];
            }
            double a = median(ours);
            double b = median(theirs);
            out.append(csv(entry.getKey())).append(',').append(ours.length).append(',')
                    .append(droppedByClass.getOrDefault(entry.getKey(), 0)).append(',')
                    .append(number(a)).append(',').append(number(b)).append(',')
                    .append(a == 0 ? "" : number(b / a)).append('\n');
        }
        Files.write(summary.resolve("paired_by_image_type.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<double[]> valuesFor(List<String> rows, Arm arm) {
        List<double[]> out = new ArrayList<>();
        for (String row : rows) {
            String[] cells = split(row);
            if (cells[6].equals(arm.id)) out.add(metricsOf(cells));
        }
        return out;
    }

    private static double[] metricsOf(String[] cells) {
        return new double[]{parse(cells[9]), parse(cells[10]), parse(cells[11]),
                parse(cells[12]), parse(cells[13])};
    }

    private static double parse(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private static double[] column(List<double[]> values, int index) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i)[index];
        return out;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** A whole-pixel median error on a 256-pixel crop is not a registration. Counted, never hidden. */
    private static int lockLost(double[] values) {
        int count = 0;
        for (double value : values) if (finite(value) && value >= 1.0) count++;
        return count;
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        if (copy.length == 0) return Double.NaN;
        int mid = copy.length / 2;
        return copy.length % 2 == 1 ? copy[mid] : 0.5 * (copy[mid - 1] + copy[mid]);
    }

    private static double mean(double[] values) {
        double sum = 0;
        int n = 0;
        for (double value : values) if (finite(value)) { sum += value; n++; }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static double max(double[] values) {
        double out = Double.NEGATIVE_INFINITY;
        for (double value : values) if (finite(value)) out = Math.max(out, value);
        return out == Double.NEGATIVE_INFINITY ? Double.NaN : out;
    }

    private static String[] split(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells.toArray(new String[0]);
    }

    private static Set<String> requested(String property) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String value : System.getProperty(property, "").split(",")) {
            if (!value.trim().isEmpty()) out.add(value.trim());
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

    private static String number(double value) {
        return finite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }
}
