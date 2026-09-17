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
import logratio.api.RegistrationRecipe;
import logratio.api.SelectionMode;
import logratio.core.PairAligner;
import logratio.core.PairScheduler;
import logratio.core.Warper;

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

/**
 * The two sensitivity checks the plan defers until the accuracy winner is frozen.
 *
 * <p>Gradient multiplier: the sweep fixed it at 0.5 for every recipe. This asks whether 0.25 or 1.0
 * would have changed the answer for the winning gradient-based recipes. Compute budget: the sweep
 * fixed 25 iterations and 200,000 samples even for mutual-noise recipes, whose production
 * recommendation uses 12 and 50,000. This asks what that costs and buys.
 *
 * <p>Neither check may change the frozen selector. They exist to say whether the constants held
 * fixed during the sweep were load-bearing, which is a question the sweep itself cannot answer.
 */
public final class SensitivityBenchmark {
    static final String RUN_ID = "sensitivity_v1";

    static final String HEADER = "check,arm_id,image_series_class,series_id,motion_category,"
            + "recipe_id,gradient_multiplier,max_iterations,max_samples,status,median_error_px,"
            + "p90_error_px,max_error_px,total_seconds";

    /** The winning gradient-based recipes from the development sweep. */
    static final String[] GRADIENT_WINNERS = {
            "support_gradient__band_no_top_25__filter_none__mask_least_informative_25",
            "support_gradient__band_no_top_25__filter_median_3x3__mask_least_informative_25"};

    /** The winning mutual-noise recipes from the development sweep. */
    static final String[] MUTUAL_WINNERS = {
            "support_mutual_noise_gradient__band_no_top_25__filter_none__mask_least_informative_25",
            "support_mutual_noise_gradient__band_no_top_25__filter_median_3x3__mask_least_informative_25"};

    static final double[] GRADIENT_MULTIPLIERS = {0.25, 0.5, 1.0};
    static final int[][] COMPUTE_BUDGETS = {{12, 50_000}, {25, 200_000}};

    private SensitivityBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path root = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Files.createDirectories(summary);
        List<FullSelectorFactorialBenchmark.Recording> recordings =
                FullSelectorFactorialBenchmark.recordings(root);

        List<String> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int done = 0;
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            ImagePlus input = IJ.openImage(recording.input.toString());
            if (input == null) {
                failures.add("could not open " + recording.input);
                continue;
            }
            try {
                for (String id : GRADIENT_WINNERS) {
                    for (double multiplier : GRADIENT_MULTIPLIERS) {
                        rows.add(run(recording, input, "gradient_multiplier", id,
                                multiplier, 25, 200_000, failures));
                        done++;
                    }
                }
                for (String id : MUTUAL_WINNERS) {
                    for (int[] budget : COMPUTE_BUDGETS) {
                        rows.add(run(recording, input, "compute_budget", id,
                                0.5, budget[0], budget[1], failures));
                        done++;
                    }
                }
            } finally {
                input.close();
            }
            System.out.printf(Locale.ROOT, "%s: %d runs%n", recording.key(), done);
        }

        StringBuilder all = new StringBuilder(HEADER).append('\n');
        for (String row : rows) if (row != null) all.append(row).append('\n');
        Files.write(summary.resolve("all_sensitivity_runs.csv"),
                all.toString().getBytes(StandardCharsets.UTF_8));
        writeSummary(summary, rows);
        System.out.printf(Locale.ROOT, "sensitivity: %d runs, %d failures%n", done, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("sensitivity failures:\n" + String.join("\n", failures));
        }
    }

    private static String run(FullSelectorFactorialBenchmark.Recording recording, ImagePlus input,
                              String check, String recipeId, double multiplier, int iterations,
                              int samples, List<String> failures) {
        String armId = check + "__" + recipeId + "__"
                + (check.equals("gradient_multiplier")
                        ? "g" + String.format(Locale.ROOT, "%.2f", multiplier)
                        : iterations + "it_" + samples + "s");
        try {
            RegistrationRecipe recipe = recipeById(recipeId);
            LogRatioParameters parameters = LogRatioParameters.builder()
                    .recommendation(FullSelectorFactorialBenchmark.imageType(recording.imageClass),
                            FullSelectorFactorialBenchmark.motionType(recording.motion))
                    .selectionMode(SelectionMode.MANUAL)
                    .autoMaxShift(false)
                    .maxShift(FullSelectorFactorialBenchmark.knownMaxShift(recording.motion))
                    .crop(false).interpolation(Warper.Interpolation.NONE)
                    .pixelSupport(recipe.pixelSupport)
                    .floorPercentile(recipe.floorPercentile)
                    .ceilingPercentile(recipe.ceilingPercentile)
                    .preprocessing(recipe.preprocessing)
                    .pixelSelectionStrategy(recipe.pixelSelectionStrategy)
                    .pixelSelectionPreprocessing(recipe.pixelSelectionPreprocessing)
                    .pixelRemovalPercent(recipe.pixelRemovalPercent)
                    .gradientFraction(multiplier)
                    .maxIterations(iterations).maxSamples(samples)
                    .build();
            long start = System.nanoTime();
            LogRatioResult result = LogRatioRegistration.register(input, parameters,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            double seconds = (System.nanoTime() - start) / 1e9;
            try {
                double[] metrics = FullSelectorFactorialBenchmark.controlledMetrics(
                        result.registration().cumulative, recording.motion);
                return csv(check) + ',' + csv(armId) + ',' + csv(recording.imageClass) + ','
                        + csv(recording.series) + ',' + csv(recording.motion) + ','
                        + csv(recipeId) + ',' + format(multiplier) + ',' + iterations + ','
                        + samples + ",ok," + format(metrics[0]) + ',' + format(metrics[1]) + ','
                        + format(metrics[2]) + ',' + format(seconds);
            } finally {
                result.correctedImage().changes = false;
                result.close();
            }
        } catch (RuntimeException error) {
            failures.add(recording.key() + " / " + armId + ": " + error);
            return csv(check) + ',' + csv(armId) + ',' + csv(recording.imageClass) + ','
                    + csv(recording.series) + ',' + csv(recording.motion) + ',' + csv(recipeId)
                    + ',' + format(multiplier) + ',' + iterations + ',' + samples
                    + ",failed,NaN,NaN,NaN,NaN";
        }
    }

    private static RegistrationRecipe recipeById(String id) {
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            if (recipe.id().equals(id)) return recipe;
        }
        throw new IllegalArgumentException("unknown recipe " + id);
    }

    private static void writeSummary(Path summary, List<String> rows) throws IOException {
        Map<String, double[]> byArm = new LinkedHashMap<>();
        Map<String, String> checkOf = new LinkedHashMap<>();
        for (String row : rows) {
            if (row == null) continue;
            List<String> fields = FullSelectorFactorialBenchmark.fields(row);
            if (!"ok".equals(fields.get(9))) continue;
            double[] totals = byArm.computeIfAbsent(fields.get(1), key -> new double[4]);
            checkOf.put(fields.get(1), fields.get(0));
            totals[0] += Double.parseDouble(fields.get(10));
            totals[1] += Double.parseDouble(fields.get(11));
            totals[2] += Double.parseDouble(fields.get(13));
            totals[3] += 1;
        }
        StringBuilder out = new StringBuilder(
                "check,arm_id,recordings,mean_median_error_px,mean_p90_error_px,mean_seconds\n");
        for (Map.Entry<String, double[]> entry : new TreeMap<>(byArm).entrySet()) {
            double[] totals = entry.getValue();
            out.append(csv(checkOf.get(entry.getKey()))).append(',').append(csv(entry.getKey()))
                    .append(',').append((int) totals[3]).append(',')
                    .append(format(totals[0] / totals[3])).append(',')
                    .append(format(totals[1] / totals[3])).append(',')
                    .append(format(totals[2] / totals[3])).append('\n');
        }
        Files.write(summary.resolve("sensitivity_summary.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }
}
