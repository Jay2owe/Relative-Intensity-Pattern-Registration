/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scores the sealed test set once, against gates written before the set was opened.
 *
 * <p>This is Stage 5 of {@code docs/pairwise_estimator_axis_plan.md}: the estimator axis has been
 * trained into the selector on development material, the runtime position has been declared in
 * {@code docs/pairwise_estimator_axis_runtime_declaration.md}, and the sealed set gets one run.
 *
 * <p><b>Nothing here may change a coefficient, a threshold, a feature, a candidate or the estimator.</b>
 * The model was frozen by {@link FullSelectorTraining} before this ran and the gates below were
 * written before the numbers existed. A failure restores the previous model; it does not start a
 * round of tuning, because a set that has been tuned against is no longer sealed and this project
 * has only one of them left.
 *
 * <p>Separate from {@link LockedTestReport}, which holds the previous plan's gates over the previous
 * sealed set and is frozen evidence of that decision. Sharing a class would mean editing one plan's
 * record to run another plan's test.
 */
public final class SealedTestReport {

    static final String ROOT = "library/benchmark/v2/benchmarks/sealed_test";
    static final String CATEGORY_ARM = "1_current_category_recommendation";
    static final String SELECTOR_ARM = "4_new_full_automatic_selector";

    // ---- the gates, declared before the set was opened ------------------------------------ //

    /** Every recording must produce an answer on both arms. */
    static final int GATE_FAILURES = 0;
    /**
     * Paired mean median error must be no worse than the shipped default's.
     *
     * <p>Expressed as a ratio rather than an absolute, because the sealed set is different material
     * and its absolute difficulty is unknown by construction. The comparison is against the category
     * recommendation measured on the same recordings in the same run.
     */
    static final double GATE_MEAN_RATIO = 1.0;
    /** No image type may lose more than this, in pixels, against the category recommendation. */
    static final double GATE_IMAGE_TYPE_REGRESSION = 0.002;
    /** Mean elapsed seconds per recording for the automatic arm. Declared in Stage 4. */
    static final double GATE_MEAN_SECONDS = 2.2;
    /**
     * The sparse low-light guard: the selector holds no candidate for that image type, so it must
     * return the category recommendation there and match it to within rounding.
     */
    static final double GATE_SPARSE_TOLERANCE = 1e-9;

    private SealedTestReport() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path root = project.resolve(ROOT);
        Path summary = root.resolve("summaries").resolve(SelectorComparisonBenchmark.RUN_ID);
        Path rows = summary.resolve("all_recordings.csv");
        if (!Files.isRegularFile(rows)) {
            throw new IOException("missing " + rows + "; run SelectorComparisonBenchmark over "
                    + ROOT + " first");
        }

        Map<String, Map<String, Row>> byRecording = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(rows, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            Row row = Row.parse(FullSelectorFactorialBenchmark.fields(lines.get(i)));
            byRecording.computeIfAbsent(row.key, key -> new LinkedHashMap<>()).put(row.arm, row);
        }

        List<Paired> paired = new ArrayList<>();
        for (Map.Entry<String, Map<String, Row>> entry : byRecording.entrySet()) {
            Row category = entry.getValue().get(CATEGORY_ARM);
            Row selector = entry.getValue().get(SELECTOR_ARM);
            if (category == null || selector == null) continue;
            paired.add(new Paired(category, selector));
        }
        paired.sort(Comparator.comparing(one -> one.category.key));
        if (paired.isEmpty()) throw new IOException("no paired sealed-test rows found");

        Gates gates = score(paired);
        write(summary, gates, paired);
        System.out.printf(Locale.ROOT,
                "sealed test: %d recordings, selector %.6f px versus category %.6f px, %.3f s, "
                        + "%d failures, gates %s%n",
                gates.count, gates.meanSelector(), gates.meanCategory(), gates.meanSeconds(),
                gates.failures, gates.passed() ? "PASS" : "FAIL");
        for (String reason : gates.reasons()) System.out.println("  gate failed: " + reason);
    }

    private static Gates score(List<Paired> paired) {
        Gates gates = new Gates();
        for (Paired one : paired) {
            gates.count++;
            if (!one.selector.ok || !one.category.ok) {
                gates.failures++;
                continue;
            }
            gates.scored++;
            gates.selectorTotal += one.selector.median;
            gates.categoryTotal += one.category.median;
            gates.secondsTotal += one.selector.seconds;
            double[] perClass = gates.byClass.computeIfAbsent(one.selector.imageClass,
                    key -> new double[3]);
            perClass[0]++;
            perClass[1] += one.selector.median;
            perClass[2] += one.category.median;
        }
        return gates;
    }

    private static void write(Path summary, Gates gates, List<Paired> paired) throws IOException {
        StringBuilder rows = new StringBuilder("image_series_class,series_id,motion_category,"
                + "condition,category_median_px,selector_median_px,difference_px,selector_seconds,"
                + "selector_recipe\n");
        for (Paired one : paired) {
            rows.append(FullSelectorFactorialBenchmark.csv(one.selector.imageClass)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(one.selector.series)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(one.selector.motion)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(one.selector.condition)).append(',')
                    .append(format(one.category.median)).append(',')
                    .append(format(one.selector.median)).append(',')
                    .append(format(one.selector.median - one.category.median)).append(',')
                    .append(format(one.selector.seconds)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(one.selector.recipe)).append('\n');
        }
        Files.write(summary.resolve("sealed_paired_rows.csv"),
                rows.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder out = new StringBuilder("gate,measured,limit,passed\n");
        out.append("\"registration_failures\",").append(gates.failures).append(',')
                .append(GATE_FAILURES).append(',').append(gates.failures <= GATE_FAILURES)
                .append('\n');
        out.append("\"mean_median_ratio_against_category\",").append(format(gates.ratio()))
                .append(',').append(format(GATE_MEAN_RATIO)).append(',')
                .append(gates.ratio() <= GATE_MEAN_RATIO).append('\n');
        for (Map.Entry<String, double[]> entry : new TreeMap<>(gates.byClass).entrySet()) {
            double[] value = entry.getValue();
            double regression = (value[1] - value[2]) / value[0];
            out.append("\"image_type_regression_px[").append(entry.getKey()).append("]\",")
                    .append(format(regression)).append(',')
                    .append(format(GATE_IMAGE_TYPE_REGRESSION)).append(',')
                    .append(regression <= GATE_IMAGE_TYPE_REGRESSION).append('\n');
        }
        out.append("\"mean_seconds\",").append(format(gates.meanSeconds())).append(',')
                .append(format(GATE_MEAN_SECONDS)).append(',')
                .append(gates.meanSeconds() <= GATE_MEAN_SECONDS).append('\n');
        out.append("\"sparse_lowlight_matches_category\",")
                .append(format(gates.sparseDifference())).append(',')
                .append(format(GATE_SPARSE_TOLERANCE)).append(',')
                .append(gates.sparseDifference() <= GATE_SPARSE_TOLERANCE).append('\n');
        Files.write(summary.resolve("sealed_gates.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final class Gates {
        final Map<String, double[]> byClass = new LinkedHashMap<>();
        int count;
        int scored;
        int failures;
        double selectorTotal;
        double categoryTotal;
        double secondsTotal;

        double meanSelector() {
            return scored == 0 ? Double.NaN : selectorTotal / scored;
        }

        double meanCategory() {
            return scored == 0 ? Double.NaN : categoryTotal / scored;
        }

        double meanSeconds() {
            return scored == 0 ? Double.NaN : secondsTotal / scored;
        }

        double ratio() {
            return meanCategory() > 0 ? meanSelector() / meanCategory() : Double.NaN;
        }

        /** How far sparse low-light moved from the category recommendation, in pixels. */
        double sparseDifference() {
            double[] value = byClass.get("SPARSE_LOWLIGHT");
            if (value == null || value[0] == 0) return 0;
            return Math.abs(value[1] - value[2]) / value[0];
        }

        boolean passed() {
            return reasons().isEmpty();
        }

        List<String> reasons() {
            List<String> out = new ArrayList<>();
            if (failures > GATE_FAILURES) out.add("registration failures " + failures);
            if (!(ratio() <= GATE_MEAN_RATIO)) {
                out.add("mean median ratio " + format(ratio()));
            }
            for (Map.Entry<String, double[]> entry : new TreeMap<>(byClass).entrySet()) {
                double[] value = entry.getValue();
                double regression = (value[1] - value[2]) / value[0];
                if (regression > GATE_IMAGE_TYPE_REGRESSION) {
                    out.add(entry.getKey() + " regression " + format(regression) + " px");
                }
            }
            if (!(meanSeconds() <= GATE_MEAN_SECONDS)) {
                out.add("mean seconds " + format(meanSeconds()));
            }
            if (!(sparseDifference() <= GATE_SPARSE_TOLERANCE)) {
                out.add("sparse low-light moved " + format(sparseDifference()) + " px");
            }
            return out;
        }
    }

    private static final class Paired {
        final Row category;
        final Row selector;

        Paired(Row category, Row selector) {
            this.category = category;
            this.selector = selector;
        }
    }

    private static final class Row {
        final String key;
        final String arm;
        final String imageClass;
        final String series;
        final String motion;
        final String condition;
        final boolean ok;
        final double median;
        final double seconds;
        final String recipe;

        private Row(String key, String arm, String imageClass, String series, String motion,
                    String condition, boolean ok, double median, double seconds, String recipe) {
            this.key = key;
            this.arm = arm;
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.condition = condition;
            this.ok = ok;
            this.median = median;
            this.seconds = seconds;
            this.recipe = recipe;
        }

        static Row parse(List<String> f) {
            String imageClass = f.get(1);
            String series = f.get(2);
            String motion = f.get(4);
            String condition = f.get(5);
            boolean ok = "ok".equals(f.get(7));
            return new Row(imageClass + '/' + series + '/' + motion + '/' + condition,
                    f.get(6), imageClass, series, motion, condition, ok,
                    ok ? Double.parseDouble(f.get(8)) : Double.NaN,
                    ok ? Double.parseDouble(f.get(11)) : Double.NaN,
                    f.size() > 13 ? f.get(13) : "");
        }
    }

    private static String format(double value) {
        if (Double.isNaN(value)) return "NaN";
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
