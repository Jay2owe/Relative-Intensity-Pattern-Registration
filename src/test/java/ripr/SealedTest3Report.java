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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Scores the <b>third</b> sealed test set once, against gates written before the set was opened.
 *
 * <p>The set was built on 2026-08-19 by {@link SealedTestSet3Builder} and has not been read. Ten
 * source series, two per image type, forty controlled recordings, no independent group shared with
 * any development, locked or previously sealed series. What it is made of and why is in
 * {@code docs/third_sealed_set_material.md}.
 *
 * <p><b>These gates were written before any number from this set existed.</b> Nothing scored here
 * may change a coefficient, a threshold, a feature, a candidate or the estimator. A failure restores
 * the previous model; it does not start a round of tuning, because a set that has been tuned against
 * is not sealed and building another costs a fortnight and two source hunts.
 *
 * <h2>The question this answers</h2>
 *
 * <p>Candidate against baseline, on material neither has seen: two models, two runs, one join. Not
 * candidate against the category recommendation — that measures the selector rather than the change
 * to it. The category arm appears anyway, as the control described under
 * {@link #GATE_CONTROL_ARM_DIFFERENCE}.
 *
 * <pre>
 *   java -Dlogratio.comparisonRoot=library/benchmark/v2/benchmarks/sealed_test_3 \
 *        -Dlogratio.rewrite=true -Dlogratio.noImages=true \
 *        ripr.SelectorComparisonBenchmark &lt;project&gt;      # once per model, private build
 *   java ripr.SealedTest3Report &lt;project&gt; &lt;baseline.csv&gt; &lt;candidate.csv&gt;
 * </pre>
 *
 * <h2>Separate class, on purpose</h2>
 *
 * <p>{@link SealedTestReport} holds the estimator axis's gates over the second sealed set and
 * {@link LockedTestReport} holds the previous plan's gates over the locked set. Both are frozen
 * evidence of decisions already taken. Editing either to run a new test would overwrite one plan's
 * record with another's. {@link NewtonSealedReport} is a third such record, and the note in
 * {@code docs/newton_stage4_notice.md} explains why this class exists instead of it.
 */
public final class SealedTest3Report {

    static final String ROOT = "library/benchmark/v2/benchmarks/sealed_test_3";
    static final String CATEGORY_ARM = "1_current_category_recommendation";
    static final String SELECTOR_ARM = "4_new_full_automatic_selector";

    // ---- the gates, declared before the set was opened ------------------------------------ //

    /**
     * The whole set must be scored, or nothing is.
     *
     * <p>A gate on coverage rather than on a number, and it comes first because it is the one that
     * stops the others being quietly weakened. Scoring thirty-one of forty recordings and reporting
     * a mean is not a sealed-set result, and a run that lost nine recordings to a crash would
     * otherwise pass every accuracy gate below.
     */
    static final int GATE_RECORDINGS = 40;
    static final int GATE_SERIES = 10;
    static final int GATE_IMAGE_TYPES = 5;
    static final int GATE_SERIES_PER_IMAGE_TYPE = 2;

    /** Every recording must produce an answer on both arms of both runs. */
    static final int GATE_FAILURES = 0;

    /**
     * The candidate's paired mean median error, as a fraction of the baseline's on the same
     * recordings in the same arm.
     *
     * <p>A ratio and not an absolute, because this set is different material and its absolute
     * difficulty is unknown by construction — that is what makes it worth having. One, not something
     * above it: a change offered as a speed-up does not get to buy speed with accuracy.
     */
    static final double GATE_MEAN_RATIO = 1.0;

    /**
     * No image type may lose more than this, in pixels, against the baseline.
     *
     * <p>Carried unchanged from the second sealed set and the locked set before it, so that a
     * per-type regression means the same thing across all three. Two thousandths of a pixel is well
     * below the errors being measured and well above the run-to-run noise floor.
     */
    static final double GATE_IMAGE_TYPE_REGRESSION = 0.002;

    /** No single recording may worsen by more than this, in pixels, against the baseline. */
    static final double GATE_RECORDING_REGRESSION = 0.05;

    /**
     * The candidate's mean elapsed seconds as a fraction of the baseline's.
     *
     * <p><b>A ratio, where the previous two sets used an absolute limit of 2.2 seconds, and the
     * change is deliberate.</b> That absolute was calibrated on material that is almost entirely
     * 512x512. This set is not: the ChromaLIVE dense record is 1900x1900 and the BBBC028 ring record
     * is 1376x1032, so two of its ten series carry roughly seven and five times the pixels of a
     * typical earlier seed. Registration cost scales with pixels, so an absolute seconds gate
     * carried over unchanged would fail this set on frame size rather than on anything about the
     * model — and would fail it identically for a model that had not changed at all. The ratio is
     * measured against the baseline on the same recordings in the same run, which is
     * material-independent by construction.
     *
     * <p>One, not less than one. A speed-up is claimed by Stage 2 and Stage 3 on development
     * material, where it can be measured repeatedly and cheaply. What a sealed set is for is finding
     * out whether something broke, and "the candidate got slower on unseen material" is a break.
     * Asking a one-shot set to confirm a specific speed-up would be spending the only unread
     * material this project has on a measurement that does not need it.
     */
    static final double GATE_SECONDS_RATIO = 1.0;

    /**
     * The category recommendation does not depend on the model, so it must reproduce exactly between
     * the two runs.
     *
     * <p>This is the control that makes everything else attributable: if the arm neither model
     * touches has moved, then something other than the model differed between the two builds — a
     * stale class file, a different classpath, a changed default — and no difference in the
     * automatic arm can be blamed on the model. Exactly zero, because the same code over the same
     * bytes is deterministic; anything else is a bug in the comparison, not a tolerance to widen.
     */
    static final double GATE_CONTROL_ARM_DIFFERENCE = 0.0;

    /**
     * Refuse to score a set that has already been scored, unless told otherwise in as many words.
     *
     * <p>Every plan in this project says a sealed set is opened once. Nothing has ever enforced it,
     * and the one time it mattered the set was scored against the wrong model and had to be scored
     * again. If {@code sealed_test_3_gates.csv} already exists this class stops, and
     * {@code -Dlogratio.reopenSealedSet=true} is the only way past — which leaves a deliberate,
     * greppable mark in whatever command history reopened it.
     */
    static final String REOPEN_PROPERTY = "ripr.reopenSealedSet";

    private SealedTest3Report() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: SealedTest3Report <project> <baseline csv> <candidate csv>");
        }
        Path project = Paths.get(args[0]).toAbsolutePath().normalize();
        Path out = project.resolve(ROOT).resolve("summaries");
        Path gatesFile = out.resolve("sealed_test_3_gates.csv");
        if (Files.isRegularFile(gatesFile) && !Boolean.getBoolean(REOPEN_PROPERTY)) {
            throw new IOException("the third sealed set has already been scored: " + gatesFile
                    + System.lineSeparator()
                    + "A sealed set is opened once. Re-run with -D" + REOPEN_PROPERTY + "=true "
                    + "only if you intend a second reading, and say so in the write-up.");
        }

        Map<String, Map<String, Row>> baseline = read(Paths.get(args[1]));
        Map<String, Map<String, Row>> candidate = read(Paths.get(args[2]));

        Result result = score(baseline, candidate);
        Files.createDirectories(out);
        writeGates(gatesFile, result);
        writeRows(out.resolve("sealed_test_3_paired_rows.csv"), result);

        System.out.printf(Locale.ROOT,
                "third sealed set: %d recordings, candidate %.6f px versus baseline %.6f px "
                        + "(ratio %.4f), seconds ratio %.4f, %d failures, gates %s%n",
                result.counted, result.candidateMean(), result.baselineMean(), result.ratio(),
                result.secondsRatio(), result.failures.size(),
                result.passed() ? "PASS" : "FAIL");
        for (String reason : result.reasons()) System.out.println("  gate failed: " + reason);
    }

    static Result score(Map<String, Map<String, Row>> baseline,
                        Map<String, Map<String, Row>> candidate) {
        Result r = new Result();
        for (Map.Entry<String, Map<String, Row>> entry : new TreeMap<>(baseline).entrySet()) {
            String key = entry.getKey();
            Row before = entry.getValue().get(SELECTOR_ARM);
            Row beforeCategory = entry.getValue().get(CATEGORY_ARM);
            Map<String, Row> other = candidate.get(key);
            Row after = other == null ? null : other.get(SELECTOR_ARM);
            Row afterCategory = other == null ? null : other.get(CATEGORY_ARM);

            if (before == null || after == null) {
                r.failures.add(key + ": missing selector arm");
                continue;
            }
            if (!before.ok || !after.ok) {
                r.failures.add(key + ": arm did not complete");
                continue;
            }
            if (beforeCategory != null && afterCategory != null
                    && Math.abs(beforeCategory.median - afterCategory.median)
                    > GATE_CONTROL_ARM_DIFFERENCE) {
                r.controlDrift.add(String.format(Locale.ROOT, "%s: %.6f -> %.6f",
                        key, beforeCategory.median, afterCategory.median));
            }

            r.series.add(before.series);
            r.imageTypes.add(before.imageClass);
            r.seriesByType.computeIfAbsent(before.imageClass, k -> new TreeSet<>())
                    .add(before.series);
            r.baselineTotal += before.median;
            r.candidateTotal += after.median;
            r.baselineSeconds += before.seconds;
            r.candidateSeconds += after.seconds;
            r.byImageType.computeIfAbsent(before.imageClass, k -> new ArrayList<>())
                    .add(new double[]{before.median, after.median});
            if (after.median - before.median > GATE_RECORDING_REGRESSION) {
                r.recordingRegressions.add(String.format(Locale.ROOT, "%s: %.6f -> %.6f",
                        key, before.median, after.median));
            }
            r.rows.add(new Paired(before, after));
            r.counted++;
        }
        return r;
    }

    static Map<String, Map<String, Row>> read(Path csv) throws IOException {
        if (!Files.isRegularFile(csv)) throw new IOException("missing " + csv);
        Map<String, Map<String, Row>> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            Row row = Row.parse(FullSelectorFactorialBenchmark.fields(lines.get(i)));
            out.computeIfAbsent(row.key, key -> new LinkedHashMap<>()).put(row.arm, row);
        }
        return out;
    }

    private static void writeGates(Path path, Result r) throws IOException {
        StringBuilder out = new StringBuilder("gate,measured,limit,passed\n");
        gate(out, "recordings_scored", r.counted, GATE_RECORDINGS, r.counted == GATE_RECORDINGS);
        gate(out, "series_scored", r.series.size(), GATE_SERIES, r.series.size() == GATE_SERIES);
        gate(out, "image_types_scored", r.imageTypes.size(), GATE_IMAGE_TYPES,
                r.imageTypes.size() == GATE_IMAGE_TYPES);
        gate(out, "series_per_image_type", r.minSeriesPerType(), GATE_SERIES_PER_IMAGE_TYPE,
                r.balanced());
        gate(out, "registration_failures", r.failures.size(), GATE_FAILURES,
                r.failures.size() == GATE_FAILURES);
        gate(out, "control_arm_drift_rows", r.controlDrift.size(), 0, r.controlDrift.isEmpty());
        out.append("\"mean_median_ratio\",").append(format(r.ratio())).append(',')
                .append(format(GATE_MEAN_RATIO)).append(',').append(r.ratioOk()).append('\n');
        for (Map.Entry<String, List<double[]>> entry : r.byImageType.entrySet()) {
            double d = r.regression(entry.getKey());
            out.append("\"image_type_regression_px[").append(entry.getKey()).append("]\",")
                    .append(format(d)).append(',').append(format(GATE_IMAGE_TYPE_REGRESSION))
                    .append(',').append(d <= GATE_IMAGE_TYPE_REGRESSION).append('\n');
        }
        gate(out, "recording_regressions", r.recordingRegressions.size(), 0,
                r.recordingRegressions.isEmpty());
        out.append("\"mean_seconds_ratio\",").append(format(r.secondsRatio())).append(',')
                .append(format(GATE_SECONDS_RATIO)).append(',').append(r.secondsOk()).append('\n');
        out.append("\"overall\",\"").append(r.passed() ? "PASS" : "FAIL").append("\",,")
                .append(r.passed()).append('\n');
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeRows(Path path, Result r) throws IOException {
        StringBuilder out = new StringBuilder("image_series_class,series_id,motion_category,"
                + "condition,baseline_median_px,candidate_median_px,difference_px,"
                + "baseline_seconds,candidate_seconds,candidate_recipe\n");
        for (Paired p : r.rows) {
            out.append(FullSelectorFactorialBenchmark.csv(p.before.imageClass)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(p.before.series)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(p.before.motion)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(p.before.condition)).append(',')
                    .append(format(p.before.median)).append(',')
                    .append(format(p.after.median)).append(',')
                    .append(format(p.after.median - p.before.median)).append(',')
                    .append(format(p.before.seconds)).append(',')
                    .append(format(p.after.seconds)).append(',')
                    .append(FullSelectorFactorialBenchmark.csv(p.after.recipe)).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void gate(StringBuilder out, String name, int measured, int limit,
                             boolean passed) {
        out.append('"').append(name).append("\",").append(measured).append(',').append(limit)
                .append(',').append(passed).append('\n');
    }

    static final class Result {
        final List<String> failures = new ArrayList<>();
        final List<String> controlDrift = new ArrayList<>();
        final List<String> recordingRegressions = new ArrayList<>();
        final List<Paired> rows = new ArrayList<>();
        final Map<String, List<double[]>> byImageType = new TreeMap<>();
        final Map<String, TreeSet<String>> seriesByType = new TreeMap<>();
        final TreeSet<String> series = new TreeSet<>();
        final TreeSet<String> imageTypes = new TreeSet<>();
        int counted;
        double baselineTotal;
        double candidateTotal;
        double baselineSeconds;
        double candidateSeconds;

        double baselineMean() {
            return counted == 0 ? Double.NaN : baselineTotal / counted;
        }

        double candidateMean() {
            return counted == 0 ? Double.NaN : candidateTotal / counted;
        }

        double ratio() {
            return baselineMean() > 0 ? candidateMean() / baselineMean() : Double.NaN;
        }

        double secondsRatio() {
            return baselineSeconds > 0 ? candidateSeconds / baselineSeconds : Double.NaN;
        }

        double regression(String imageType) {
            List<double[]> pairs = byImageType.get(imageType);
            if (pairs == null || pairs.isEmpty()) return 0;
            double b = 0;
            double a = 0;
            for (double[] pair : pairs) {
                b += pair[0];
                a += pair[1];
            }
            return (a - b) / pairs.size();
        }

        int minSeriesPerType() {
            int min = Integer.MAX_VALUE;
            for (TreeSet<String> value : seriesByType.values()) min = Math.min(min, value.size());
            return min == Integer.MAX_VALUE ? 0 : min;
        }

        boolean balanced() {
            if (seriesByType.isEmpty()) return false;
            for (TreeSet<String> value : seriesByType.values()) {
                if (value.size() != GATE_SERIES_PER_IMAGE_TYPE) return false;
            }
            return true;
        }

        boolean ratioOk() {
            return ratio() <= GATE_MEAN_RATIO;
        }

        boolean secondsOk() {
            return secondsRatio() <= GATE_SECONDS_RATIO;
        }

        boolean passed() {
            return reasons().isEmpty();
        }

        List<String> reasons() {
            List<String> out = new ArrayList<>();
            if (counted != GATE_RECORDINGS) {
                out.add("scored " + counted + " recordings, expected " + GATE_RECORDINGS);
            }
            if (series.size() != GATE_SERIES) {
                out.add("scored " + series.size() + " series, expected " + GATE_SERIES);
            }
            if (imageTypes.size() != GATE_IMAGE_TYPES) {
                out.add("scored " + imageTypes.size() + " image types, expected "
                        + GATE_IMAGE_TYPES);
            }
            if (!balanced()) out.add("image types are not " + GATE_SERIES_PER_IMAGE_TYPE + " series each");
            if (failures.size() != GATE_FAILURES) {
                out.add("registration failures " + failures.size() + ": " + failures);
            }
            if (!controlDrift.isEmpty()) {
                out.add("control arm moved between runs, so nothing is attributable: "
                        + controlDrift);
            }
            if (!ratioOk()) out.add("mean median ratio " + format(ratio()));
            for (String imageType : byImageType.keySet()) {
                double d = regression(imageType);
                if (d > GATE_IMAGE_TYPE_REGRESSION) {
                    out.add(imageType + " regression " + format(d) + " px");
                }
            }
            if (!recordingRegressions.isEmpty()) {
                out.add("per-recording regressions: " + recordingRegressions);
            }
            if (!secondsOk()) out.add("mean seconds ratio " + format(secondsRatio()));
            return out;
        }
    }

    static final class Paired {
        final Row before;
        final Row after;

        Paired(Row before, Row after) {
            this.before = before;
            this.after = after;
        }
    }

    static final class Row {
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
