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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Stage 4 of {@code docs/newton_refinement_plan.md}: does the retrained selector survive the sealed
 * set? Scored against gates written into this class before the set was opened.
 *
 * <h2>This is a second opening of a spent set, and that is declared rather than hidden</h2>
 *
 * <p>The 40-recording sealed set was opened once on 2026-08-18 and its numbers are published in
 * {@code docs/pairwise_estimator_axis_findings.md}. It validated the model this run's model would
 * replace. <b>The number this class produces is therefore not an independent validation</b> — it is a
 * second reading of a set that has already influenced what ships, and it is optimistically biased by
 * an amount nobody can measure. The bias is probably small, because the set was opened once and
 * accepted rather than iterated against, but "probably small" is not "zero" and no write-up of this
 * result may describe it as a clean held-out number.
 *
 * <p>It is being read anyway because the alternative was to leave Stage 4 unopened indefinitely: the
 * material check in the plan found zero unused dense-fluorescence sources on disk and zero fetchable,
 * so a genuinely third set cannot be built today. Re-reading this one preserves the option of
 * building one later and validating properly then; folding it into training would not have.
 *
 * <p><b>Nothing here may change a coefficient, a threshold, a feature, a candidate or the estimator.</b>
 * The gates below were written before the numbers existed. A failure restores the previous model; it
 * does not start a round of tuning.
 *
 * <p>Separate class from {@link SealedTestReport} on purpose, exactly as that one is separate from
 * {@link LockedTestReport}: that class holds the estimator axis's gates over this set and is frozen
 * evidence of that decision. Editing it to run this plan's test would overwrite one plan's record
 * with another's.
 *
 * <h2>How it is run</h2>
 *
 * <p>The question is <em>retrained against shipped</em>, so the sealed comparison is run twice — once
 * with each model compiled into a private build — and this class joins the two
 * {@code all_recordings.csv} files. Two runs rather than one because the automatic arm is the only
 * thing the model touches, and comparing it against the category recommendation alone would measure
 * the selector rather than the change to it.
 *
 * <pre>
 *   java -Dlogratio.comparisonRoot=library/benchmark/v2/benchmarks/sealed_test \
 *        -Dlogratio.rewrite=true -Dlogratio.noImages=true \
 *        ripr.SelectorComparisonBenchmark &lt;project&gt;      # once per model
 *   java ripr.NewtonSealedReport &lt;project&gt; &lt;shipped.csv&gt; &lt;retrained.csv&gt;
 * </pre>
 */
public final class NewtonSealedReport {

    static final String ROOT = "library/benchmark/v2/benchmarks/sealed_test";
    static final String CATEGORY_ARM = "1_current_category_recommendation";
    static final String SELECTOR_ARM = "4_new_full_automatic_selector";

    // ---- the gates, declared before the set was opened ------------------------------------ //

    /** Every recording must produce an answer on both arms of both runs. */
    static final int GATE_FAILURES = 0;
    /**
     * The retrained model's paired mean median error, as a fraction of the shipped model's on the
     * same recordings in the same arm.
     *
     * <p>A ratio and not an absolute, because the sealed set is different material and its absolute
     * difficulty is unknown by construction. One, not something above it: the retrained model was
     * chosen for being faster, and this plan does not buy speed with accuracy.
     */
    static final double GATE_MEAN_RATIO = 1.0;
    /** No image type may lose more than this, in pixels, against the shipped model. */
    static final double GATE_IMAGE_TYPE_REGRESSION = 0.002;
    /** No single recording may worsen by more than this, in pixels, against the shipped model. */
    static final double GATE_RECORDING_REGRESSION = 0.05;
    /** Mean elapsed seconds per recording for the automatic arm. The Stage 4 runtime declaration. */
    static final double GATE_MEAN_SECONDS = 2.2;
    /**
     * The category recommendation does not depend on the model, so it must reproduce exactly between
     * the two runs.
     *
     * <p>This is the control that makes the rest of the comparison mean anything: if the arm neither
     * model touches has moved, then something other than the model differed between the two builds
     * and no difference in the automatic arm can be attributed.
     */
    static final double GATE_CONTROL_ARM_DIFFERENCE = 0.0;

    private NewtonSealedReport() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: NewtonSealedReport <project> <shipped csv> <retrained csv>");
        }
        Path project = Paths.get(args[0]).toAbsolutePath().normalize();
        Map<String, Map<String, Row>> shipped = read(Paths.get(args[1]));
        Map<String, Map<String, Row>> retrained = read(Paths.get(args[2]));

        List<String> failures = new ArrayList<>();
        List<String> controlDrift = new ArrayList<>();
        List<Double> shippedMedians = new ArrayList<>();
        List<Double> retrainedMedians = new ArrayList<>();
        Map<String, List<double[]>> byImageType = new TreeMap<>();
        List<String> recordingRegressions = new ArrayList<>();
        double secondsSum = 0;
        int counted = 0;

        for (Map.Entry<String, Map<String, Row>> entry : new TreeMap<>(shipped).entrySet()) {
            String key = entry.getKey();
            Row before = entry.getValue().get(SELECTOR_ARM);
            Row beforeCategory = entry.getValue().get(CATEGORY_ARM);
            Map<String, Row> other = retrained.get(key);
            Row after = other == null ? null : other.get(SELECTOR_ARM);
            Row afterCategory = other == null ? null : other.get(CATEGORY_ARM);
            if (before == null || after == null) {
                failures.add(key + ": missing selector arm");
                continue;
            }
            if (!before.ok || !after.ok) {
                failures.add(key + ": arm did not complete");
                continue;
            }
            if (beforeCategory != null && afterCategory != null
                    && Math.abs(beforeCategory.median - afterCategory.median)
                    > GATE_CONTROL_ARM_DIFFERENCE) {
                controlDrift.add(String.format(Locale.ROOT, "%s: %.6f -> %.6f",
                        key, beforeCategory.median, afterCategory.median));
            }
            shippedMedians.add(before.median);
            retrainedMedians.add(after.median);
            byImageType.computeIfAbsent(before.imageClass, k -> new ArrayList<>())
                    .add(new double[]{before.median, after.median});
            if (after.median - before.median > GATE_RECORDING_REGRESSION) {
                recordingRegressions.add(String.format(Locale.ROOT, "%s: %.6f -> %.6f",
                        key, before.median, after.median));
            }
            secondsSum += after.seconds;
            counted++;
        }

        double shippedMean = mean(shippedMedians);
        double retrainedMean = mean(retrainedMedians);
        double ratio = shippedMean == 0 ? Double.NaN : retrainedMean / shippedMean;
        double meanSeconds = counted == 0 ? Double.NaN : secondsSum / counted;

        List<String> imageTypeRegressions = new ArrayList<>();
        StringBuilder perType = new StringBuilder();
        for (Map.Entry<String, List<double[]>> entry : byImageType.entrySet()) {
            double b = 0;
            double a = 0;
            for (double[] pair : entry.getValue()) {
                b += pair[0];
                a += pair[1];
            }
            b /= entry.getValue().size();
            a /= entry.getValue().size();
            perType.append(String.format(Locale.ROOT, "| %s | %d | %.6f | %.6f | %+.6f |%n",
                    entry.getKey(), entry.getValue().size(), b, a, a - b));
            if (a - b > GATE_IMAGE_TYPE_REGRESSION) {
                imageTypeRegressions.add(String.format(Locale.ROOT, "%s worse by %.6f px",
                        entry.getKey(), a - b));
            }
        }

        boolean failuresOk = failures.size() == GATE_FAILURES;
        boolean controlOk = controlDrift.isEmpty();
        boolean ratioOk = ratio <= GATE_MEAN_RATIO;
        boolean imageTypeOk = imageTypeRegressions.isEmpty();
        boolean recordingOk = recordingRegressions.isEmpty();
        boolean secondsOk = meanSeconds <= GATE_MEAN_SECONDS;
        boolean passed = failuresOk && controlOk && ratioOk && imageTypeOk && recordingOk
                && secondsOk;

        StringBuilder out = new StringBuilder();
        out.append("# Newton refinement, Stage 4 — the sealed set, opened a second time\n\n");
        out.append("**This is a re-reading of a spent set, not an independent validation.** ")
                .append("It was opened once on 2026-08-18 and its numbers are published; it ")
                .append("validated the model this run's model would replace. The result below is ")
                .append("optimistically biased by an amount nobody can measure. See this class's ")
                .append("javadoc for why it was read anyway.\n\n");
        out.append("Recordings paired: ").append(counted).append("\n\n");
        out.append("## Per image type, automatic arm, mean median error\n\n");
        out.append("| Image type | Recordings | Shipped (px) | Retrained (px) | Difference |\n");
        out.append("|---|---|---|---|---|\n").append(perType);
        out.append("\n## The gates\n\n");
        out.append("| Gate | Limit | Measured | Verdict |\n|---|---|---|---|\n");
        row(out, "Registration failures", String.valueOf(GATE_FAILURES),
                String.valueOf(failures.size()), failuresOk);
        row(out, "Control arm reproduces between runs", "exact",
                controlDrift.isEmpty() ? "exact on every recording"
                        : controlDrift.size() + " moved: " + String.join("; ", controlDrift),
                controlOk);
        row(out, "Paired mean median, retrained over shipped",
                String.format(Locale.ROOT, "%.3f", GATE_MEAN_RATIO),
                String.format(Locale.ROOT, "%.6f px over %.6f px = %.4f", retrainedMean,
                        shippedMean, ratio), ratioOk);
        row(out, "Image-type regression",
                String.format(Locale.ROOT, "%.3f px", GATE_IMAGE_TYPE_REGRESSION),
                imageTypeRegressions.isEmpty() ? "none"
                        : String.join("; ", imageTypeRegressions), imageTypeOk);
        row(out, "Per-recording regression",
                String.format(Locale.ROOT, "%.3f px", GATE_RECORDING_REGRESSION),
                recordingRegressions.isEmpty() ? "none"
                        : String.join("; ", recordingRegressions), recordingOk);
        row(out, "Mean elapsed seconds, automatic arm",
                String.format(Locale.ROOT, "%.3f", GATE_MEAN_SECONDS),
                String.format(Locale.ROOT, "%.6f", meanSeconds), secondsOk);
        out.append("\n**GATE: ").append(passed ? "PASS" : "FAIL").append("**\n");
        if (!passed) {
            out.append("\nA failure restores the previous model. It does not start a round of ")
                    .append("tuning: a set that has been tuned against is not sealed, and this ")
                    .append("project has none left.\n");
        }
        for (String failure : failures) out.append("\n- failure: ").append(failure);

        Path summary = project.resolve(ROOT).resolve("summaries").resolve("newton_stage4");
        Files.createDirectories(summary);
        Files.write(summary.resolve("GATE.md"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(out);
    }

    private static void row(StringBuilder out, String gate, String limit, String measured,
                            boolean ok) {
        out.append("| ").append(gate).append(" | ").append(limit).append(" | ").append(measured)
                .append(" | ").append(ok ? "**pass**" : "**FAIL**").append(" |\n");
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    private static Map<String, Map<String, Row>> read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + path);
        Map<String, Map<String, Row>> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            Row row = Row.parse(FullSelectorFactorialBenchmark.fields(lines.get(i)));
            out.computeIfAbsent(row.key, key -> new LinkedHashMap<>()).put(row.arm, row);
        }
        return out;
    }

    private static final class Row {
        final String key;
        final String arm;
        final String imageClass;
        final boolean ok;
        final double median;
        final double seconds;

        private Row(String key, String arm, String imageClass, boolean ok, double median,
                    double seconds) {
            this.key = key;
            this.arm = arm;
            this.imageClass = imageClass;
            this.ok = ok;
            this.median = median;
            this.seconds = seconds;
        }

        static Row parse(List<String> f) {
            String key = f.get(1) + '/' + f.get(2) + '/' + f.get(4) + '/' + f.get(5);
            boolean ok = "ok".equals(f.get(7));
            return new Row(key, f.get(6), f.get(1), ok,
                    ok ? Double.parseDouble(f.get(8)) : Double.NaN,
                    ok ? Double.parseDouble(f.get(11)) : Double.NaN);
        }
    }
}
