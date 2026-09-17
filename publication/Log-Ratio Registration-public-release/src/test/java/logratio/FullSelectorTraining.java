/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import logratio.api.AutomaticRegistrationSelector;
import logratio.api.LogRatioParameters;
import logratio.core.Transform;
import logratio.core.Warper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Trains and validates the full automatic selector without letting any source series see itself.
 *
 * <p>Validation is leave-one-source-series-out: all four movement versions of one source are
 * withheld together, and candidate pruning, the ridge penalty, the coefficients and the confidence
 * threshold are all recomputed from the other nineteen sources inside every fold. Two model families
 * are compared under identical folds, and the winner is chosen on held-out results alone.
 *
 * <p>Two lessons from the first attempt are built in. Candidates are kept per declared image type,
 * because a filter that helps brightfield destroys sparse low-light; and the confidence threshold is
 * chosen on inner held-out sources rather than on the rows it was fitted to, because an in-sample
 * threshold always looks best at zero and therefore never learns to decline.
 *
 * <p>The runner ends by refitting the winning family on all twenty sources and rewriting
 * {@code AutomaticRegistrationSelectorModel.java}. Nothing here reads the locked test set.
 */
public final class FullSelectorTraining {
    static final String RUN_ID = FullSelectorFactorialBenchmark.RUN_ID;

    /** Gates declared in the plan, before any fold was run. */
    static final double GATE_MEAN_MEDIAN = 0.025545;
    static final double GATE_MEAN_P90 = 0.095121;
    static final double GATE_IMAGE_TYPE_REGRESSION = 0.002;
    static final double GATE_RECORDING_RATIO = 2.0;
    static final double GATE_RECORDING_ABSOLUTE = 0.05;
    static final double GATE_MEAN_SECONDS = 1.479984;

    /** The neutral provisional pass: every pixel, no band, no filter, no mask. */
    static final String NEUTRAL_RECIPE = "support_all__band_full__filter_none__mask_none";

    /** Ridge penalties offered to each fold; the fold picks one from its own rows only. */
    static final double[] RIDGE_GRID = {1.0, 10.0, 100.0};
    /** Confidence thresholds offered to each fold, in pixels of predicted gain. */
    static final double[] THRESHOLD_GRID = {
            0.0, 0.0005, 0.001, 0.002, 0.005, 0.010, 0.020, 0.050, 0.100};

    /** A candidate must help this many independent source series of its own image type. */
    static final int MINIMUM_INDEPENDENT_WINS = FullSelectorSweepSummary.MINIMUM_INDEPENDENT_WINS;

    enum Family {
        /** One interpretable rule per declared image type: the safest measured improvement. */
        IMAGE_TYPE_RULE,
        /** Conservative one-versus-rest linear gain regressions over the measured evidence. */
        LINEAR_GAIN
    }

    private FullSelectorTraining() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path root = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Files.createDirectories(summary);

        List<FullSelectorFactorialBenchmark.Recipe> recipes = FullSelectorFactorialBenchmark.recipes();
        List<FullSelectorFactorialBenchmark.Recording> recordings =
                FullSelectorFactorialBenchmark.recordings(root);
        List<String> recipeIds = new ArrayList<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) recipeIds.add(recipe.id);

        Map<String, Map<String, Outcome>> outcomes = readOutcomes(summary, recordings, recipeIds);
        List<Case> cases = buildCases(summary, recordings, outcomes);
        System.out.printf(Locale.ROOT, "prepared %d cases with %d features%n",
                cases.size(), AutomaticRegistrationSelector.FEATURE_COUNT);

        Map<Family, List<FoldResult>> folds = new LinkedHashMap<>();
        for (Family family : Family.values()) {
            folds.put(family, crossValidate(cases, recipeIds, family));
            System.out.println("cross-validated " + family);
        }
        writeFoldResults(summary, folds);

        Map<Family, GateReport> reports = new LinkedHashMap<>();
        for (Family family : Family.values()) {
            reports.put(family, evaluate(folds.get(family)));
        }
        Family winner = reports.get(Family.IMAGE_TYPE_RULE).meanMedian
                <= reports.get(Family.LINEAR_GAIN).meanMedian
                ? Family.IMAGE_TYPE_RULE : Family.LINEAR_GAIN;

        Model finalModel = fit(cases, recipeIds, winner, null);
        writeModelSource(project, finalModel, winner, cases, reports.get(winner));
        writeValidation(summary, reports, winner, finalModel, cases);
        System.out.printf(Locale.ROOT,
                "chose %s: held-out mean median %.6f px versus category %.6f px, mean p90 %.6f px, "
                        + "mean %.6f s, %d overrides of %d, gates %s%n",
                winner, reports.get(winner).meanMedian, reports.get(winner).categoryMeanMedian,
                reports.get(winner).meanP90, reports.get(winner).meanSeconds,
                reports.get(winner).overrides, reports.get(winner).count,
                reports.get(winner).failures().isEmpty() ? "PASS" : "FAIL");
        for (String failure : reports.get(winner).failures()) {
            System.out.println("  gate failed: " + failure);
        }
    }

    // ---------------------------------------------------------------- inputs

    private static Map<String, Map<String, Outcome>> readOutcomes(
            Path summary, List<FullSelectorFactorialBenchmark.Recording> recordings,
            List<String> recipeIds) throws IOException {
        Path file = summary.resolve("all_recipes.csv");
        if (!Files.isRegularFile(file)) {
            throw new IOException("missing " + file + "; run FullSelectorSweepSummary first");
        }
        Map<String, Map<String, Outcome>> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> fields = FullSelectorFactorialBenchmark.fields(lines.get(i));
            String key = fields.get(0) + '/' + fields.get(1) + '/' + fields.get(3) + '/'
                    + fields.get(4);
            out.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(fields.get(5),
                    new Outcome("ok".equals(fields.get(10)), number(fields.get(11)),
                            number(fields.get(12)), number(fields.get(14))));
        }
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            Map<String, Outcome> byRecipe = out.get(recording.key());
            if (byRecipe == null) throw new IOException("no sweep rows for " + recording.key());
            for (String id : recipeIds) {
                if (!byRecipe.containsKey(id)) {
                    throw new IOException("missing " + id + " for " + recording.key());
                }
            }
        }
        return out;
    }

    /**
     * Measure the production evidence for every recording. Movement evidence is taken from the
     * neutral sweep recipe, which is exactly the provisional pass the plugin runs, so training and
     * production see the same numbers rather than two implementations of the same idea.
     */
    private static List<Case> buildCases(Path summary,
            List<FullSelectorFactorialBenchmark.Recording> recordings,
            Map<String, Map<String, Outcome>> outcomes) throws IOException {
        Path cache = summary.resolve("selector_features.csv");
        Map<String, double[]> cached = readFeatureCache(cache);
        List<Case> cases = new ArrayList<>();
        boolean measured = false;
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            double[] features = cached.get(recording.key());
            if (features == null) {
                features = measure(recording);
                cached.put(recording.key(), features);
                measured = true;
                System.out.println("measured evidence for " + recording.key());
            }
            cases.add(new Case(recording, features, outcomes.get(recording.key()),
                    categoryOutcome(recording)));
        }
        if (measured) writeFeatureCache(cache, recordings, cached);
        return cases;
    }

    private static double[] measure(FullSelectorFactorialBenchmark.Recording recording)
            throws IOException {
        Path transforms = recording.folder.resolve(RUN_ID).resolve(NEUTRAL_RECIPE)
                .resolve("transforms.csv");
        if (!Files.isRegularFile(transforms)) {
            throw new IOException("missing neutral provisional transforms " + transforms);
        }
        Transform[] provisional = readTransforms(transforms);
        ImagePlus image = IJ.openImage(recording.input.toString());
        if (image == null) throw new IOException("could not open " + recording.input);
        try {
            LogRatioParameters base = categoryBase(recording);
            AutomaticRegistrationSelector.Evidence evidence = AutomaticRegistrationSelector.measure(
                    StackFrames.of(image, base.channel, base.slice), provisional, base);
            return evidence.vector();
        } finally {
            image.close();
        }
    }

    static LogRatioParameters categoryBase(FullSelectorFactorialBenchmark.Recording recording) {
        return LogRatioParameters.builder()
                .recommendation(FullSelectorFactorialBenchmark.imageType(recording.imageClass),
                        FullSelectorFactorialBenchmark.motionType(recording.motion))
                .autoMaxShift(false)
                .maxShift(FullSelectorFactorialBenchmark.knownMaxShift(recording.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .build();
    }

    /**
     * The category recommendation's own result, read from the frozen 2026-08-16 arm. That is the same
     * configuration the plugin loads today, so the comparison is like for like.
     */
    private static Outcome categoryOutcome(FullSelectorFactorialBenchmark.Recording recording)
            throws IOException {
        Path file = recording.folder.resolve(FullSelectorSweepSummary.CATEGORY_ARM)
                .resolve("comparison.csv");
        if (!Files.isRegularFile(file)) throw new IOException("missing " + file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> fields = FullSelectorFactorialBenchmark.fields(lines.get(1));
        return new Outcome("ok".equals(fields.get(7)), number(fields.get(9)),
                number(fields.get(10)), number(fields.get(12)));
    }

    private static Transform[] readTransforms(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Transform> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            String[] fields = lines.get(i).split(",");
            out.add(new Transform(Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3])));
        }
        return out.toArray(new Transform[0]);
    }

    private static Map<String, double[]> readFeatureCache(Path cache) throws IOException {
        Map<String, double[]> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(cache)) return out;
        List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return out;
        List<String> header = FullSelectorFactorialBenchmark.fields(lines.get(0));
        if (header.size() != 1 + AutomaticRegistrationSelector.FEATURE_COUNT) return out;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> fields = FullSelectorFactorialBenchmark.fields(lines.get(i));
            double[] values = new double[AutomaticRegistrationSelector.FEATURE_COUNT];
            for (int j = 0; j < values.length; j++) values[j] = number(fields.get(j + 1));
            out.put(fields.get(0), values);
        }
        return out;
    }

    private static void writeFeatureCache(Path cache,
            List<FullSelectorFactorialBenchmark.Recording> recordings,
            Map<String, double[]> features) throws IOException {
        StringBuilder out = new StringBuilder(csv("recording"));
        for (String name : AutomaticRegistrationSelector.FEATURE_NAMES) {
            out.append(',').append(csv(name));
        }
        out.append('\n');
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            double[] values = features.get(recording.key());
            if (values == null) continue;
            out.append(csv(recording.key()));
            for (double value : values) out.append(',').append(precise(value));
            out.append('\n');
        }
        Files.write(cache, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- validation

    private static List<FoldResult> crossValidate(List<Case> cases, List<String> recipeIds,
                                                  Family family) {
        List<FoldResult> out = new ArrayList<>();
        for (String held : series(cases)) {
            List<Case> training = trainingFor(cases, held);
            List<Case> testing = testingFor(cases, held);
            Model model = fit(training, recipeIds, family, held);
            for (Case one : testing) out.add(new FoldResult(held, one, model.choose(one)));
        }
        return out;
    }

    static Set<String> series(List<Case> cases) {
        Set<String> out = new LinkedHashSet<>();
        for (Case one : cases) out.add(one.recording.series);
        return out;
    }

    /**
     * Training rows for one fold. All four movement versions of the held-out source are removed
     * together: leaving one in would let the fold see the very field of view it is judged on.
     */
    static List<Case> trainingFor(List<Case> cases, String heldSeries) {
        List<Case> out = new ArrayList<>();
        for (Case one : cases) {
            if (!one.recording.series.equals(heldSeries)) out.add(one);
        }
        return out;
    }

    /** The withheld rows for one fold: every movement version of that one source series. */
    static List<Case> testingFor(List<Case> cases, String heldSeries) {
        List<Case> out = new ArrayList<>();
        for (Case one : cases) {
            if (one.recording.series.equals(heldSeries)) out.add(one);
        }
        return out;
    }

    private static GateReport evaluate(List<FoldResult> folds) {
        GateReport report = new GateReport();
        Map<String, double[]> byImageType = new TreeMap<>();
        for (FoldResult fold : folds) {
            Outcome chosen = fold.choice.outcome(fold.one);
            if (chosen == null) {
                report.missing++;
                continue;
            }
            if (!chosen.ok) report.registrationFailures++;
            report.count++;
            report.medianTotal += chosen.median;
            report.p90Total += chosen.p90;
            // The plugin always runs the neutral provisional pass before it can choose anything.
            report.secondsTotal += chosen.seconds + fold.one.neutralSeconds();
            report.categoryMedianTotal += fold.one.category.median;
            report.categoryP90Total += fold.one.category.p90;
            report.categorySecondsTotal += fold.one.category.seconds;
            if (!fold.choice.fallback) report.overrides++;
            double[] totals = byImageType.computeIfAbsent(fold.one.recording.imageClass,
                    key -> new double[3]);
            totals[0] += chosen.median;
            totals[1] += fold.one.category.median;
            totals[2] += 1;
            boolean ratio = chosen.median > GATE_RECORDING_RATIO * fold.one.category.median;
            boolean absolute = chosen.median > fold.one.category.median + GATE_RECORDING_ABSOLUTE;
            if (ratio && absolute) report.badRecordings.add(fold.one.recording.key());
        }
        for (Map.Entry<String, double[]> entry : byImageType.entrySet()) {
            double[] totals = entry.getValue();
            report.imageTypeSelector.put(entry.getKey(), totals[0] / totals[2]);
            report.imageTypeCategory.put(entry.getKey(), totals[1] / totals[2]);
        }
        if (report.count > 0) {
            report.meanMedian = report.medianTotal / report.count;
            report.meanP90 = report.p90Total / report.count;
            report.meanSeconds = report.secondsTotal / report.count;
            report.categoryMeanMedian = report.categoryMedianTotal / report.count;
            report.categoryMeanP90 = report.categoryP90Total / report.count;
            report.categoryMeanSeconds = report.categorySecondsTotal / report.count;
        }
        return report;
    }

    // ---------------------------------------------------------------- model fitting

    /**
     * Prune, standardise, choose a penalty and a threshold, and fit, using only the rows supplied.
     * The held-out series name is carried for the report; it never reaches the arithmetic.
     */
    static Model fit(List<Case> training, List<String> recipeIds, Family family, String heldOut) {
        int features = AutomaticRegistrationSelector.FEATURE_COUNT;
        double[] mean = new double[features];
        double[] scale = new double[features];
        for (int j = 0; j < features; j++) {
            double total = 0;
            int count = 0;
            for (Case one : training) {
                if (Double.isFinite(one.features[j])) {
                    total += one.features[j];
                    count++;
                }
            }
            mean[j] = count == 0 ? 0 : total / count;
            double squared = 0;
            for (Case one : training) {
                double value = Double.isFinite(one.features[j]) ? one.features[j] : mean[j];
                squared += (value - mean[j]) * (value - mean[j]);
            }
            // A zero-variance column carries no information; standardise() then contributes nothing,
            // which is the feature selection this model performs beyond ridge shrinkage.
            scale[j] = training.isEmpty() ? 1 : Math.sqrt(squared / training.size());
            if (!(scale[j] > 1e-12)) scale[j] = 0;
        }

        // Penalty and threshold are chosen together on inner held-out sources. Fitting them on the
        // rows they are judged on is what made the first attempt override almost every recording.
        Tuning tuning = tune(training, recipeIds, family, mean, scale);
        Model model = fitCore(training, recipeIds, family, mean, scale, tuning.ridge, heldOut);
        model.threshold = tuning.threshold;
        model.innerMeanMedian = tuning.innerMeanMedian;
        return model;
    }

    private static Model fitCore(List<Case> training, List<String> recipeIds, Family family,
                                 double[] mean, double[] scale, double ridge, String heldOut) {
        List<Candidate> retained = prune(training, recipeIds, family);
        Model model = new Model(family, retained, mean, scale, heldOut);
        model.ridge = ridge;
        if (retained.isEmpty() || family == Family.IMAGE_TYPE_RULE) return model;
        double[][] rows = new double[training.size()][];
        for (int i = 0; i < training.size(); i++) {
            rows[i] = standardise(training.get(i).features, mean, scale);
        }
        model.intercept = new double[retained.size()];
        model.weights = new double[retained.size()][];
        for (int c = 0; c < retained.size(); c++) {
            double[] target = new double[training.size()];
            for (int i = 0; i < training.size(); i++) {
                target[i] = training.get(i).gain(retained.get(c).recipeId);
            }
            double[] fitted = ridge(rows, target, ridge);
            model.intercept[c] = fitted[fitted.length - 1];
            model.weights[c] = Arrays.copyOf(fitted, fitted.length - 1);
        }
        return model;
    }

    private static final class Tuning {
        final double ridge;
        final double threshold;
        final double innerMeanMedian;

        Tuning(double ridge, double threshold, double innerMeanMedian) {
            this.ridge = ridge;
            this.threshold = threshold;
            this.innerMeanMedian = innerMeanMedian;
        }
    }

    /** Inner leave-one-source-series-out over the training rows only. */
    private static Tuning tune(List<Case> training, List<String> recipeIds, Family family,
                               double[] mean, double[] scale) {
        double[] penalties = family == Family.LINEAR_GAIN ? RIDGE_GRID : new double[]{0.0};
        Set<String> innerSeries = series(training);
        double bestPenalty = penalties[0];
        double bestThreshold = THRESHOLD_GRID[THRESHOLD_GRID.length - 1];
        double bestError = Double.POSITIVE_INFINITY;
        for (double penalty : penalties) {
            double[] totals = new double[THRESHOLD_GRID.length];
            int count = 0;
            for (String held : innerSeries) {
                List<Case> inner = trainingFor(training, held);
                List<Case> outer = testingFor(training, held);
                if (inner.isEmpty() || outer.isEmpty()) continue;
                Model model = fitCore(inner, recipeIds, family, mean, scale, penalty, held);
                for (Case one : outer) {
                    double[] row = standardise(one.features, mean, scale);
                    for (int t = 0; t < THRESHOLD_GRID.length; t++) {
                        Choice choice = model.choose(one, row, THRESHOLD_GRID[t]);
                        Outcome outcome = choice.outcome(one);
                        totals[t] += outcome == null || !outcome.ok ? 1.0 : outcome.median;
                    }
                    count++;
                }
            }
            if (count == 0) continue;
            for (int t = 0; t < THRESHOLD_GRID.length; t++) {
                double error = totals[t] / count;
                // Ties go to the stricter threshold, which is the conservative direction.
                boolean better = error < bestError - 1e-12;
                boolean tiedButStricter = Math.abs(error - bestError) <= 1e-12
                        && THRESHOLD_GRID[t] > bestThreshold;
                if (better || tiedButStricter) {
                    bestError = error;
                    bestPenalty = penalty;
                    bestThreshold = THRESHOLD_GRID[t];
                }
            }
        }
        return new Tuning(bestPenalty, bestThreshold, bestError);
    }

    /**
     * Retain a candidate only where it is measured as safe and helpful for one declared image type.
     *
     * <p>Three conditions, all measured on the supplied rows alone. It must never breach the
     * per-recording guard on its own image type; its mean gain over the category recommendation on
     * that image type must be positive; and it must help at least two independent source series of
     * that image type, so one lucky recording cannot create a production branch.
     */
    static List<Candidate> prune(List<Case> training, List<String> recipeIds, Family family) {
        Map<String, List<Case>> byClass = new LinkedHashMap<>();
        for (Case one : training) {
            byClass.computeIfAbsent(one.recording.imageClass, key -> new ArrayList<>()).add(one);
        }
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<String, List<Case>> entry : byClass.entrySet()) {
            List<Candidate> perClass = new ArrayList<>();
            for (String id : recipeIds) {
                double total = 0;
                int count = 0;
                boolean safe = true;
                Set<String> helped = new LinkedHashSet<>();
                for (Case one : entry.getValue()) {
                    Outcome outcome = one.outcomes.get(id);
                    if (outcome == null || !outcome.ok) {
                        safe = false;
                        break;
                    }
                    boolean ratio = outcome.median > GATE_RECORDING_RATIO * one.category.median;
                    boolean absolute = outcome.median > one.category.median + GATE_RECORDING_ABSOLUTE;
                    if (ratio && absolute) {
                        safe = false;
                        break;
                    }
                    double gain = one.category.median - outcome.median;
                    total += gain;
                    count++;
                    if (gain > 0) helped.add(one.recording.independentGroup);
                }
                if (!safe || count == 0) continue;
                double meanGain = total / count;
                if (!(meanGain > 0)) continue;
                if (helped.size() < MINIMUM_INDEPENDENT_WINS) continue;
                perClass.add(new Candidate(entry.getKey(), id, meanGain));
            }
            perClass.sort((left, right) -> Double.compare(right.trainingGain, left.trainingGain));
            if (family == Family.IMAGE_TYPE_RULE) {
                // One interpretable rule per image type: the safest measured improvement.
                if (!perClass.isEmpty()) out.add(perClass.get(0));
            } else {
                out.addAll(perClass);
            }
        }
        return out;
    }

    /** Ridge-regularised least squares with an unpenalised intercept; returns weights then intercept. */
    static double[] ridge(double[][] rows, double[] target, double penalty) {
        int n = rows.length;
        int p = n == 0 ? 0 : rows[0].length;
        double[][] normal = new double[p + 1][p + 2];
        for (int i = 0; i < n; i++) {
            double[] row = rows[i];
            for (int a = 0; a < p; a++) {
                double va = row[a];
                if (va == 0) continue;
                for (int b = a; b < p; b++) normal[a][b] += va * row[b];
                normal[a][p] += va;
                normal[a][p + 1] += va * target[i];
            }
            for (int b = 0; b < p; b++) normal[p][b] += row[b];
            normal[p][p] += 1;
            normal[p][p + 1] += target[i];
        }
        for (int a = 0; a < p; a++) {
            for (int b = 0; b < a; b++) normal[a][b] = normal[b][a];
            normal[a][a] += penalty;
        }
        return solve(normal, p + 1);
    }

    private static double[] solve(double[][] matrix, int size) {
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) pivot = row;
            }
            double[] swap = matrix[column];
            matrix[column] = matrix[pivot];
            matrix[pivot] = swap;
            double head = matrix[column][column];
            if (Math.abs(head) < 1e-12) {
                matrix[column][column] = 1e-12;
                head = 1e-12;
            }
            for (int row = column + 1; row < size; row++) {
                double factor = matrix[row][column] / head;
                if (factor == 0) continue;
                for (int k = column; k <= size; k++) matrix[row][k] -= factor * matrix[column][k];
            }
        }
        double[] out = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double value = matrix[row][size];
            for (int k = row + 1; k < size; k++) value -= matrix[row][k] * out[k];
            out[row] = value / matrix[row][row];
        }
        return out;
    }

    static double[] standardise(double[] raw, double[] mean, double[] scale) {
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double value = Double.isFinite(raw[i]) ? raw[i] : mean[i];
            out[i] = scale[i] > 0 ? (value - mean[i]) / scale[i] : 0;
        }
        return out;
    }

    // ---------------------------------------------------------------- reports

    private static void writeFoldResults(Path summary, Map<Family, List<FoldResult>> folds)
            throws IOException {
        StringBuilder out = new StringBuilder("model_family,held_out_series,image_series_class,"
                + "series_id,motion_category,chosen_recipe_id,fallback,predicted_gain_px,"
                + "confidence_threshold_px,selector_median_error_px,selector_p90_error_px,"
                + "selector_seconds,provisional_seconds,category_median_error_px,"
                + "category_p90_error_px,category_seconds,improvement_px\n");
        for (Map.Entry<Family, List<FoldResult>> entry : folds.entrySet()) {
            for (FoldResult fold : entry.getValue()) {
                Outcome chosen = fold.choice.outcome(fold.one);
                out.append(csv(entry.getKey().name())).append(',').append(csv(fold.heldOut))
                        .append(',').append(csv(fold.one.recording.imageClass)).append(',')
                        .append(csv(fold.one.recording.series)).append(',')
                        .append(csv(fold.one.recording.motion)).append(',')
                        .append(csv(fold.choice.recipeId)).append(',').append(fold.choice.fallback)
                        .append(',').append(format(fold.choice.predictedGain)).append(',')
                        .append(format(fold.choice.threshold)).append(',')
                        .append(format(chosen == null ? Double.NaN : chosen.median)).append(',')
                        .append(format(chosen == null ? Double.NaN : chosen.p90)).append(',')
                        .append(format(chosen == null ? Double.NaN : chosen.seconds)).append(',')
                        .append(format(fold.one.neutralSeconds())).append(',')
                        .append(format(fold.one.category.median)).append(',')
                        .append(format(fold.one.category.p90)).append(',')
                        .append(format(fold.one.category.seconds)).append(',')
                        .append(format(chosen == null ? Double.NaN
                                : fold.one.category.median - chosen.median)).append('\n');
            }
        }
        Files.write(summary.resolve("stage4_fold_results.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeValidation(Path summary, Map<Family, GateReport> reports,
                                        Family winner, Model model, List<Case> cases)
            throws IOException {
        StringBuilder out = new StringBuilder("gate,model_family,measured,limit,passed\n");
        for (Map.Entry<Family, GateReport> entry : reports.entrySet()) {
            GateReport report = entry.getValue();
            String family = entry.getKey().name();
            gate(out, "missing_selections", family, report.missing, 0);
            gate(out, "registration_failures", family, report.registrationFailures, 0);
            gate(out, "mean_median_error_px", family, report.meanMedian, GATE_MEAN_MEDIAN);
            gate(out, "mean_p90_error_px", family, report.meanP90, GATE_MEAN_P90);
            gate(out, "mean_seconds", family, report.meanSeconds, GATE_MEAN_SECONDS);
            for (String imageClass : report.imageTypeSelector.keySet()) {
                double regression = report.imageTypeSelector.get(imageClass)
                        - report.imageTypeCategory.get(imageClass);
                gate(out, "image_type_regression_px[" + imageClass + "]", family, regression,
                        GATE_IMAGE_TYPE_REGRESSION);
            }
            gate(out, "sparse_lowlight_mean_error_px", family,
                    report.imageTypeSelector.getOrDefault("SPARSE_LOWLIGHT", Double.NaN),
                    report.imageTypeCategory.getOrDefault("SPARSE_LOWLIGHT", Double.NaN));
            gate(out, "recordings_more_than_double_and_0.05_px_worse", family,
                    report.badRecordings.size(), 0);
        }
        Files.write(summary.resolve("stage4_validation.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));

        GateReport chosen = reports.get(winner);
        StringBuilder md = new StringBuilder();
        md.append("# Full automatic selector, source-held-out validation\n\n");
        md.append("Twenty folds. Each fold withholds all four movement versions of one source ")
                .append("series and recomputes candidate pruning, the ridge penalty, the ")
                .append("coefficients and the confidence threshold from the other nineteen.\n\n");

        md.append("## Two corrections carried into this fit\n\n");
        md.append("The first attempt overrode 76 of 80 recordings and landed at 0.140671 px, five ")
                .append("times worse than the category recommendation. Two causes, both fixed here ")
                .append("using development folds only:\n\n");
        md.append("1. The confidence threshold was chosen on the rows it had just been fitted to, ")
                .append("where a fitted model always looks near-perfect. Zero therefore always won ")
                .append("and the selector could never learn to decline. The threshold is now chosen ")
                .append("on inner held-out source series.\n");
        md.append("2. Candidates were pooled across image types, so a filter measured as helpful on ")
                .append("brightfield was offered to sparse low-light, where it is destructive. ")
                .append("Candidates are now retained per declared image type, and only where they ")
                .append("never breach the per-recording guard on that type, improve it on average, ")
                .append("and help at least ").append(MINIMUM_INDEPENDENT_WINS)
                .append(" independent source series of that type.\n\n");

        md.append("## Model families compared\n\n");
        md.append("| Family | Mean median (px) | Mean p90 (px) | Mean s | Overrides | Failures |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Map.Entry<Family, GateReport> entry : reports.entrySet()) {
            GateReport report = entry.getValue();
            md.append("| ").append(entry.getKey().name()).append(" | ")
                    .append(format(report.meanMedian)).append(" | ")
                    .append(format(report.meanP90)).append(" | ")
                    .append(format(report.meanSeconds)).append(" | ")
                    .append(report.overrides).append(" of ").append(report.count).append(" | ")
                    .append(report.registrationFailures).append(" |\n");
        }
        md.append("| Category recommendation (reference) | ")
                .append(format(chosen.categoryMeanMedian)).append(" | ")
                .append(format(chosen.categoryMeanP90)).append(" | ")
                .append(format(chosen.categoryMeanSeconds)).append(" | 0 of ")
                .append(chosen.count).append(" | 0 |\n");
        md.append("\nChosen on held-out results alone: **").append(winner.name()).append("**.\n\n");

        md.append("## Gates\n\n| Gate | Measured | Limit | Result |\n|---|---|---|---|\n");
        md.append(row("Missing selections", chosen.missing, 0, chosen.missing == 0));
        md.append(row("Registration failures", chosen.registrationFailures, 0,
                chosen.registrationFailures == 0));
        md.append(row("Mean median error (px)", chosen.meanMedian, GATE_MEAN_MEDIAN,
                chosen.meanMedian <= GATE_MEAN_MEDIAN));
        md.append(row("Mean 90th-percentile error (px)", chosen.meanP90, GATE_MEAN_P90,
                chosen.meanP90 <= GATE_MEAN_P90));
        md.append(row("Mean execution time (s)", chosen.meanSeconds, GATE_MEAN_SECONDS,
                chosen.meanSeconds <= GATE_MEAN_SECONDS));
        for (String imageClass : chosen.imageTypeSelector.keySet()) {
            double regression = chosen.imageTypeSelector.get(imageClass)
                    - chosen.imageTypeCategory.get(imageClass);
            md.append(row(imageClass + " regression (px)", regression, GATE_IMAGE_TYPE_REGRESSION,
                    regression <= GATE_IMAGE_TYPE_REGRESSION));
        }
        double sparse = chosen.imageTypeSelector.getOrDefault("SPARSE_LOWLIGHT", Double.NaN);
        double sparseCategory = chosen.imageTypeCategory.getOrDefault("SPARSE_LOWLIGHT", Double.NaN);
        md.append(row("Sparse low-light mean error (px)", sparse, sparseCategory,
                sparse <= sparseCategory));
        md.append(row("Recordings both twice and 0.05 px worse", chosen.badRecordings.size(), 0,
                chosen.badRecordings.isEmpty()));
        md.append('\n');
        if (!chosen.badRecordings.isEmpty()) {
            md.append("Recordings breaching the per-recording guard: ")
                    .append(String.join(", ", chosen.badRecordings)).append("\n\n");
        }

        md.append("## Per image type, held out\n\n");
        md.append("| Image type | Category mean median (px) | Selector mean median (px) | Change |\n");
        md.append("|---|---|---|---|\n");
        for (String imageClass : chosen.imageTypeSelector.keySet()) {
            double selector = chosen.imageTypeSelector.get(imageClass);
            double category = chosen.imageTypeCategory.get(imageClass);
            md.append("| ").append(imageClass).append(" | ").append(format(category)).append(" | ")
                    .append(format(selector)).append(" | ").append(format(selector - category))
                    .append(" |\n");
        }
        md.append('\n');

        md.append("## Frozen model\n\n");
        md.append("- Family: `").append(winner.name()).append("`\n");
        md.append("- Retained candidate recipes: ").append(model.retained.size()).append('\n');
        md.append("- Confidence threshold: ").append(format(model.threshold)).append(" px\n");
        if (winner == Family.LINEAR_GAIN) {
            md.append("- Ridge penalty chosen on inner held-out sources: ")
                    .append(format(model.ridge)).append('\n');
        }
        md.append("- Refitted on all ").append(cases.size()).append(" development recordings after ")
                .append("the held-out comparison, using the same procedure as every fold.\n\n");
        md.append("### Retained recipes, by declared image type\n\n");
        md.append("| Image type | Recipe | Training mean gain (px) |\n|---|---|---|\n");
        for (Candidate candidate : model.retained) {
            md.append("| ").append(candidate.imageClass).append(" | `").append(candidate.recipeId)
                    .append("` | ").append(format(candidate.trainingGain)).append(" |\n");
        }
        md.append("\nEvery retained recipe is one of the 96 the sweep measured. The selector cannot ")
                .append("emit anything else, cannot offer a recipe to an image type it was not ")
                .append("measured on, and returns the complete category recommendation whenever no ")
                .append("candidate clears the threshold.\n\n");
        md.append("Fold-by-fold rows are in `stage4_fold_results.csv`; the gate table is in ")
                .append("`stage4_validation.csv`. The locked test set was not read.\n");
        Files.write(summary.resolve("SELECTOR_TRAINING.md"),
                md.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String row(String name, double measured, double limit, boolean passed) {
        return "| " + name + " | " + format(measured) + " | " + format(limit) + " | "
                + (passed ? "pass" : "FAIL") + " |\n";
    }

    private static void gate(StringBuilder out, String name, String family, double measured,
                             double limit) {
        out.append(csv(name)).append(',').append(csv(family)).append(',').append(format(measured))
                .append(',').append(format(limit)).append(',')
                .append(measured <= limit || (Double.isNaN(measured) && Double.isNaN(limit)))
                .append('\n');
    }

    // ---------------------------------------------------------------- generated source

    private static void writeModelSource(Path project, Model model, Family family,
                                         List<Case> cases, GateReport report) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("/*\n * Copyright (c) 2026 Jamie Malcolm\n"
                + " * Released under the BSD 3-Clause License. See LICENSE for terms.\n */\n");
        out.append("package logratio.api;\n\n");
        out.append("import logratio.core.PairAligner;\n");
        out.append("import logratio.core.PairEstimator;\n\n");
        out.append("import java.util.ArrayList;\n");
        out.append("import java.util.Arrays;\n");
        out.append("import java.util.List;\n\n");
        out.append("/**\n");
        out.append(" * Frozen coefficients for {@link AutomaticRegistrationSelector}.\n *\n");
        out.append(" * <p>GENERATED FILE. Rewritten by {@code logratio.FullSelectorTraining} from the")
                .append(" full candidate sweep:\n");
        out.append(" * 96 log-ratio recipes over support, band, filter and mask, and 16 ")
                .append("area-correlation candidates\n");
        out.append(" * over band and filter.\n");
        out.append(" * Do not hand-edit: the numbers below are the output of ")
                .append("leave-one-source-series-out training\n");
        out.append(" * and carry no meaning apart from that fit. Retraining is the only supported ")
                .append("way to change them.\n");
        out.append(" */\n");
        out.append("public final class AutomaticRegistrationSelectorModel {\n\n");
        out.append("    /** Number of standardised evidence columns the frozen fit expects. */\n");
        out.append("    public static final int FEATURE_COUNT = readAtRuntime(")
                .append(AutomaticRegistrationSelector.FEATURE_COUNT).append(");\n\n");
        out.append("    /** Either {@code image_type_rule}, {@code linear_gain}, or ")
                .append("{@code untrained}. */\n");
        out.append("    public static final String MODEL_KIND = readAtRuntime(\"")
                .append(family == Family.IMAGE_TYPE_RULE ? "image_type_rule" : "linear_gain")
                .append("\");\n\n");
        out.append("    /** Provenance of the fit, printed in the settings report. */\n");
        out.append("    public static final String TRAINED_ON = readAtRuntime(\"")
                .append(RUN_ID).append(": ").append(cases.size())
                .append(" controlled recordings, 20 leave-one-source-series-out folds; held-out ")
                .append("mean median ").append(format(report.meanMedian))
                .append(" px against a category recommendation of ")
                .append(format(report.categoryMeanMedian)).append(" px\");\n\n");
        out.append("    /** Minimum predicted gain, in pixels, before an override is allowed. */\n");
        out.append("    public static final double CONFIDENCE_THRESHOLD = readAtRuntime(")
                .append(literal(model.threshold)).append(");\n\n");
        appendRuntimeReadHelpers(out);
        appendArray(out, "    public static final double[] FEATURE_MEAN", model.mean);
        appendArray(out, "    public static final double[] FEATURE_SCALE", model.scale);

        List<String> imageTypes = new ArrayList<>();
        List<String> estimators = new ArrayList<>();
        List<String> supports = new ArrayList<>();
        List<String> bands = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        List<String> masks = new ArrayList<>();
        double[] gains = new double[model.retained.size()];
        for (int i = 0; i < model.retained.size(); i++) {
            Candidate candidate = model.retained.get(i);
            FullSelectorFactorialBenchmark.Recipe recipe = recipeById(candidate.recipeId);
            imageTypes.add(FullSelectorFactorialBenchmark.imageType(candidate.imageClass).name());
            estimators.add(recipe.estimator.name());
            supports.add(recipe.support.name());
            bands.add(recipe.band.name());
            filters.add(recipe.filter.name());
            masks.add(recipe.mask.name());
            gains[i] = candidate.trainingGain;
        }
        out.append("    /**\n");
        out.append("     * The declared image type each candidate is allowed to serve. A recipe safe")
                .append(" for one image\n");
        out.append("     * type is never offered to another, which is where the first attempt at ")
                .append("this selector\n");
        out.append("     * went wrong: a filter that helps brightfield destroys sparse low-light.\n");
        out.append("     */\n");
        appendStrings(out, "    static final String[] CANDIDATE_IMAGE_TYPE", imageTypes);
        out.append("    /**\n");
        out.append("     * Which estimator each candidate uses. The axis beside the four settings ")
                .append("dimensions:\n");
        out.append("     * {@code LOG_RATIO_FIT} is the engine this plugin is named for, ")
                .append("{@code AREA_CORRELATION}\n");
        out.append("     * is normalised cross-correlation, and the settings below are inert under ")
                .append("the latter.\n");
        out.append("     */\n");
        appendStrings(out, "    static final String[] CANDIDATE_ESTIMATOR", estimators);
        appendStrings(out, "    static final String[] CANDIDATE_SUPPORT", supports);
        appendStrings(out, "    static final String[] CANDIDATE_BAND", bands);
        appendStrings(out, "    static final String[] CANDIDATE_FILTER", filters);
        appendStrings(out, "    static final String[] CANDIDATE_MASK", masks);
        out.append("    /** Mean measured gain over the category recommendation on that image type. */\n");
        appendArray(out, "    static final double[] CANDIDATE_TRAINING_GAIN", gains);

        out.append("    /** One-versus-rest gain regressions; row per candidate, column per feature. */\n");
        appendArray(out, "    static final double[] INTERCEPT",
                model.intercept == null ? new double[0] : model.intercept);
        out.append("    static final double[][] WEIGHTS = {\n");
        if (model.weights != null) {
            for (double[] weights : model.weights) {
                out.append("            {");
                for (int i = 0; i < weights.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append(literal(weights[i]));
                }
                out.append("},\n");
            }
        }
        out.append("    };\n\n");

        out.append("    private AutomaticRegistrationSelectorModel() {\n    }\n\n");
        out.append("    /** The retained candidate recipes, in the frozen model's order. */\n");
        out.append("    public static List<RegistrationRecipe> candidates() {\n");
        out.append("        List<RegistrationRecipe> out = new ArrayList<>();\n");
        out.append("        for (int i = 0; i < CANDIDATE_SUPPORT.length; i++) {\n");
        out.append("            out.add(RegistrationRecipe.swept(\n");
        out.append("                    PairEstimator.Kind.valueOf(CANDIDATE_ESTIMATOR[i]),\n");
        out.append("                    PairAligner.PixelSupport.valueOf(CANDIDATE_SUPPORT[i]),\n");
        out.append("                    RegistrationRecipe.Band.valueOf(CANDIDATE_BAND[i]),\n");
        out.append("                    Preprocessing.valueOf(CANDIDATE_FILTER[i]),\n");
        out.append("                    RegistrationRecipe.Mask.valueOf(CANDIDATE_MASK[i])));\n");
        out.append("        }\n        return out;\n    }\n\n");
        out.append("    /** The declared image type each candidate serves, aligned with candidates(). */\n");
        out.append("    public static List<ImageType> candidateImageTypes() {\n");
        out.append("        List<ImageType> out = new ArrayList<>();\n");
        out.append("        for (String name : CANDIDATE_IMAGE_TYPE) out.add(ImageType.valueOf(name));\n");
        out.append("        return out;\n    }\n\n");
        out.append("    /**\n");
        out.append("     * Predicted gain over the category recommendation for every retained ")
                .append("candidate. Candidates\n");
        out.append("     * that do not serve the declared image type score negative infinity and ")
                .append("can never be chosen.\n");
        out.append("     */\n");
        out.append("    public static double[] predictGains(double[] standardised, ImageType imageType) {\n");
        out.append("        int count = CANDIDATE_SUPPORT.length;\n");
        out.append("        double[] out = new double[count];\n");
        out.append("        Arrays.fill(out, Double.NEGATIVE_INFINITY);\n");
        out.append("        if (count == 0) return out;\n");
        out.append("        for (int i = 0; i < count; i++) {\n");
        out.append("            if (imageType == null "
                + "|| !CANDIDATE_IMAGE_TYPE[i].equals(imageType.name())) continue;\n");
        out.append("            if (\"image_type_rule\".equals(MODEL_KIND)) {\n");
        out.append("                out[i] = CANDIDATE_TRAINING_GAIN[i];\n");
        out.append("                continue;\n            }\n");
        out.append("            double value = INTERCEPT[i];\n");
        out.append("            double[] weights = WEIGHTS[i];\n");
        out.append("            for (int j = 0; j < weights.length; j++) "
                + "value += weights[j] * standardised[j];\n");
        out.append("            out[i] = value;\n        }\n        return out;\n    }\n");
        out.append("}\n");
        Path target = project.resolve(
                "src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java");
        Files.write(target, out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + target);
    }

    private static FullSelectorFactorialBenchmark.Recipe recipeById(String id) {
        for (FullSelectorFactorialBenchmark.Recipe recipe : FullSelectorFactorialBenchmark.recipes()) {
            if (recipe.id.equals(id)) return recipe;
        }
        throw new IllegalArgumentException("unknown recipe " + id);
    }

    /**
     * Emit the three identity wrappers the generated file routes its scalar constants through.
     *
     * <p><b>Why the generated model must not declare bare literals.</b> A {@code static final} field
     * initialised with a literal is a compile-time constant, and javac copies its value into every
     * class that reads it rather than compiling a field read. This file is regenerated by training,
     * so after a retrain any class that was not recompiled keeps answering with the previous model's
     * numbers — silently. That has cost this project twice: it scored the first opening of the sealed
     * test set against the previously shipped model, and it failed {@code mvn -o test} on a test
     * class Maven judged up to date. Routing each value through a method makes the initialiser a
     * method call, so the field is no longer a constant and stale readers read the current value.
     *
     * <p>Emitted here rather than left in the file by hand, because the file is overwritten wholesale
     * on every retrain and anything not emitted is lost.
     */
    private static void appendRuntimeReadHelpers(StringBuilder out) {
        out.append("    /**\n");
        out.append("     * Identity, and the only reason it exists is to stop javac copying the ")
                .append("value into its callers.\n");
        out.append("     *\n");
        out.append("     * <p>A {@code static final} field initialised with a literal is a ")
                .append("compile-time constant, and\n");
        out.append("     * javac copies its value into every class that reads it instead of ")
                .append("compiling a field read.\n");
        out.append("     * This file is regenerated by training, so a class that is not recompiled ")
                .append("afterwards would\n");
        out.append("     * keep answering with the previous model's values, silently — which has ")
                .append("happened twice, once\n");
        out.append("     * scoring a sealed test set against the wrong model. Routing the value ")
                .append("through a method makes\n");
        out.append("     * the initialiser a method call rather than a constant expression, so ")
                .append("stale readers read the\n");
        out.append("     * current value. See {@code ")
                .append("docs/performance_optimisation_findings.md}.\n");
        out.append("     *\n");
        out.append("     * <p>Emitted by {@code logratio.FullSelectorTraining}. A regeneration that ")
                .append("writes bare literals\n");
        out.append("     * reopens the trap.\n");
        out.append("     */\n");
        out.append("    private static int readAtRuntime(int value) {\n");
        out.append("        return value;\n");
        out.append("    }\n\n");
        out.append("    /** See {@link #readAtRuntime(int)}. */\n");
        out.append("    private static double readAtRuntime(double value) {\n");
        out.append("        return value;\n");
        out.append("    }\n\n");
        out.append("    /** See {@link #readAtRuntime(int)}. */\n");
        out.append("    private static String readAtRuntime(String value) {\n");
        out.append("        return value;\n");
        out.append("    }\n\n");
    }

    private static void appendArray(StringBuilder out, String declaration, double[] values) {
        out.append(declaration).append(" = {");
        for (int i = 0; i < values.length; i++) {
            if (i % 4 == 0) out.append("\n            ");
            else out.append(' ');
            out.append(literal(values[i]));
            if (i + 1 < values.length) out.append(',');
        }
        out.append(values.length == 0 ? "};\n\n" : "\n    };\n\n");
    }

    private static void appendStrings(StringBuilder out, String declaration, List<String> values) {
        out.append(declaration).append(" = {");
        for (int i = 0; i < values.size(); i++) {
            out.append("\n            \"").append(values.get(i)).append('"');
            if (i + 1 < values.size()) out.append(',');
        }
        out.append(values.isEmpty() ? "};\n\n" : "\n    };\n\n");
    }

    private static String literal(double value) {
        if (!Double.isFinite(value)) {
            return value > 0 ? "Double.POSITIVE_INFINITY"
                    : (value < 0 ? "Double.NEGATIVE_INFINITY" : "Double.NaN");
        }
        return Double.toString(value);
    }

    // ---------------------------------------------------------------- types

    static final class Outcome {
        final boolean ok;
        final double median;
        final double p90;
        final double seconds;

        Outcome(boolean ok, double median, double p90, double seconds) {
            this.ok = ok;
            this.median = median;
            this.p90 = p90;
            this.seconds = seconds;
        }
    }

    static final class Candidate {
        final String imageClass;
        final String recipeId;
        final double trainingGain;

        Candidate(String imageClass, String recipeId, double trainingGain) {
            this.imageClass = imageClass;
            this.recipeId = recipeId;
            this.trainingGain = trainingGain;
        }
    }

    static final class Case {
        final FullSelectorFactorialBenchmark.Recording recording;
        final double[] features;
        final Map<String, Outcome> outcomes;
        final Outcome category;

        Case(FullSelectorFactorialBenchmark.Recording recording, double[] features,
             Map<String, Outcome> outcomes, Outcome category) {
            this.recording = recording;
            this.features = features;
            this.outcomes = outcomes;
            this.category = category;
        }

        double gain(String recipeId) {
            Outcome outcome = outcomes.get(recipeId);
            if (outcome == null || !outcome.ok) return -1.0;
            return category.median - outcome.median;
        }

        double neutralSeconds() {
            Outcome neutral = outcomes.get(NEUTRAL_RECIPE);
            return neutral == null ? 0 : neutral.seconds;
        }
    }

    static final class Choice {
        final String recipeId;
        final boolean fallback;
        final double predictedGain;
        final double threshold;

        Choice(String recipeId, boolean fallback, double predictedGain, double threshold) {
            this.recipeId = recipeId;
            this.fallback = fallback;
            this.predictedGain = predictedGain;
            this.threshold = threshold;
        }

        Outcome outcome(Case one) {
            return fallback ? one.category : one.outcomes.get(recipeId);
        }
    }

    static final class FoldResult {
        final String heldOut;
        final Case one;
        final Choice choice;

        FoldResult(String heldOut, Case one, Choice choice) {
            this.heldOut = heldOut;
            this.one = one;
            this.choice = choice;
        }
    }

    static final class Model {
        final Family family;
        final List<Candidate> retained;
        final double[] mean;
        final double[] scale;
        final String heldOut;
        double ridge;
        double threshold = Double.POSITIVE_INFINITY;
        double innerMeanMedian = Double.NaN;
        double[] intercept;
        double[][] weights;

        Model(Family family, List<Candidate> retained, double[] mean, double[] scale, String heldOut) {
            this.family = family;
            this.retained = retained;
            this.mean = mean;
            this.scale = scale;
            this.heldOut = heldOut;
        }

        Choice choose(Case one) {
            return choose(one, standardise(one.features, mean, scale), threshold);
        }

        Choice choose(Case one, double[] row, double activeThreshold) {
            int best = -1;
            double bestGain = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < retained.size(); i++) {
                Candidate candidate = retained.get(i);
                // A candidate may only serve the image type it was measured safe on.
                if (!candidate.imageClass.equals(one.recording.imageClass)) continue;
                double gain = family == Family.IMAGE_TYPE_RULE
                        ? candidate.trainingGain : predict(row, i);
                if (!Double.isFinite(gain)) continue;
                if (gain > bestGain) {
                    bestGain = gain;
                    best = i;
                }
            }
            if (best < 0 || bestGain < activeThreshold) {
                return new Choice("category", true,
                        Double.isFinite(bestGain) ? bestGain : 0, activeThreshold);
            }
            return new Choice(retained.get(best).recipeId, false, bestGain, activeThreshold);
        }

        private double predict(double[] row, int candidate) {
            if (intercept == null || weights == null) return Double.NEGATIVE_INFINITY;
            double value = intercept[candidate];
            double[] w = weights[candidate];
            for (int j = 0; j < w.length; j++) value += w[j] * row[j];
            return value;
        }
    }

    static final class GateReport {
        int count;
        int missing;
        int registrationFailures;
        int overrides;
        double medianTotal;
        double p90Total;
        double secondsTotal;
        double categoryMedianTotal;
        double categoryP90Total;
        double categorySecondsTotal;
        final List<String> badRecordings = new ArrayList<>();
        final Map<String, Double> imageTypeSelector = new TreeMap<>();
        final Map<String, Double> imageTypeCategory = new TreeMap<>();

        double meanMedian = Double.NaN;
        double meanP90 = Double.NaN;
        double meanSeconds = Double.NaN;
        double categoryMeanMedian = Double.NaN;
        double categoryMeanP90 = Double.NaN;
        double categoryMeanSeconds = Double.NaN;

        List<String> failures() {
            List<String> out = new ArrayList<>();
            if (missing > 0) out.add(missing + " missing selections");
            if (registrationFailures > 0) out.add(registrationFailures + " registration failures");
            if (!(meanMedian <= GATE_MEAN_MEDIAN)) out.add("mean median " + format(meanMedian));
            if (!(meanP90 <= GATE_MEAN_P90)) out.add("mean p90 " + format(meanP90));
            if (!(meanSeconds <= GATE_MEAN_SECONDS)) out.add("mean seconds " + format(meanSeconds));
            for (String imageClass : imageTypeSelector.keySet()) {
                double regression = imageTypeSelector.get(imageClass)
                        - imageTypeCategory.get(imageClass);
                if (regression > GATE_IMAGE_TYPE_REGRESSION) {
                    out.add(imageClass + " regresses " + format(regression) + " px");
                }
            }
            Double sparse = imageTypeSelector.get("SPARSE_LOWLIGHT");
            Double sparseCategory = imageTypeCategory.get("SPARSE_LOWLIGHT");
            if (sparse != null && sparseCategory != null && sparse > sparseCategory) {
                out.add("sparse low-light worse than the category recommendation");
            }
            if (!badRecordings.isEmpty()) {
                out.add(badRecordings.size() + " recordings both twice and 0.05 px worse");
            }
            return out;
        }
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String precise(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }
}
