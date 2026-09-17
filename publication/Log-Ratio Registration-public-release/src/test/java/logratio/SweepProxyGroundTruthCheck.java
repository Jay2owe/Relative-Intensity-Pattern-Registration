/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import logratio.api.LogRatioParameters;
import logratio.api.SelectionMode;
import logratio.core.Transform;

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

/**
 * The decisive check on the interactive sweep's score: what does it say about a perfect answer?
 *
 * <p>{@link SweepProxyOracleBenchmark} found the sweep's residual score anti-correlated with true
 * error, which is a strong enough claim about a shipped feature to deserve a test that cannot be
 * explained away by anything about the recipes. This scores the exact ground-truth shifts of every
 * controlled recording and asks where they rank among the 96 recipes by that same score.
 *
 * <p>A perfect registration must score best if the score measures registration quality. If it does
 * not, the score is measuring something else, and no amount of sweeping can find the right recipe
 * with it.
 */
public final class SweepProxyGroundTruthCheck {
    static final String RUN_ID = "sweep_proxy_v1";

    static final String HEADER = "image_series_class,series_id,motion_category,condition,"
            + "ground_truth_residual,best_recipe_residual,best_recipe_id,"
            + "ground_truth_rank_of_96,recipes_scoring_better_than_truth,"
            + "rounded_truth_residual,rounded_truth_rank,identity_residual,identity_rank";

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path root = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Map<String, List<double[]>> proxyByRecording = readProxyScores(
                summary.resolve("all_proxy_scores.csv"));
        Map<String, List<String>> idsByRecording = readProxyIds(
                summary.resolve("all_proxy_scores.csv"));

        List<String> rows = new ArrayList<>();
        int truthWins = 0;
        int total = 0;
        int roundedBeatsTruth = 0;
        double meanRank = 0;
        double meanRoundedRank = 0;
        double meanIdentityRank = 0;
        for (FullSelectorFactorialBenchmark.Recording recording
                : FullSelectorFactorialBenchmark.recordings(root)) {
            ImagePlus input = IJ.openImage(recording.input.toString());
            if (input == null) continue;
            try {
                LogRatioParameters scoring = LogRatioParameters.builder()
                        .recommendation(
                                FullSelectorFactorialBenchmark.imageType(recording.imageClass),
                                FullSelectorFactorialBenchmark.motionType(recording.motion))
                        .selectionMode(SelectionMode.MANUAL)
                        .build();
                Transform[] truth = truthTransforms(recording.motion);
                double truthResidual = LogRatioSweepDialog.fullResolutionResidual(
                        input, scoring, truth);
                // Whole-pixel shifts are copied rather than interpolated, so this arm is strictly
                // less accurate than truth but carries no interpolation blur. If it scores better,
                // the score is reading blur rather than alignment.
                double roundedResidual = LogRatioSweepDialog.fullResolutionResidual(
                        input, scoring, rounded(truth));
                double identityResidual = LogRatioSweepDialog.fullResolutionResidual(
                        input, scoring, identity(truth.length));
                List<double[]> scores = proxyByRecording.get(recording.key());
                List<String> ids = idsByRecording.get(recording.key());
                if (scores == null || scores.isEmpty()) continue;
                int better = 0;
                int betterThanRounded = 0;
                int betterThanIdentity = 0;
                int bestAt = 0;
                for (int i = 0; i < scores.size(); i++) {
                    if (scores.get(i)[0] < truthResidual) better++;
                    if (scores.get(i)[0] < roundedResidual) betterThanRounded++;
                    if (scores.get(i)[0] < identityResidual) betterThanIdentity++;
                    if (scores.get(i)[0] < scores.get(bestAt)[0]) bestAt = i;
                }
                total++;
                meanRank += better + 1;
                meanRoundedRank += betterThanRounded + 1;
                meanIdentityRank += betterThanIdentity + 1;
                if (better == 0) truthWins++;
                if (roundedResidual < truthResidual) roundedBeatsTruth++;
                rows.add(csv(recording.imageClass) + ',' + csv(recording.series) + ','
                        + csv(recording.motion) + ',' + csv(recording.condition) + ','
                        + format(truthResidual) + ',' + format(scores.get(bestAt)[0]) + ','
                        + csv(ids.get(bestAt)) + ',' + (better + 1) + ',' + better + ','
                        + format(roundedResidual) + ',' + (betterThanRounded + 1) + ','
                        + format(identityResidual) + ',' + (betterThanIdentity + 1));
            } finally {
                input.close();
            }
        }

        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (String row : rows) out.append(row).append('\n');
        Files.write(summary.resolve("ground_truth_check.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT,
                "ground truth scores best on %d of %d recordings; mean rank %.2f of 97%n",
                truthWins, total, meanRank / total);
        System.out.printf(Locale.ROOT,
                "whole-pixel truth mean rank %.2f of 97; beats exact truth on %d of %d%n",
                meanRoundedRank / total, roundedBeatsTruth, total);
        System.out.printf(Locale.ROOT, "no correction at all: mean rank %.2f of 97%n",
                meanIdentityRank / total);
    }

    private static Transform[] rounded(Transform[] source) {
        Transform[] out = new Transform[source.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Transform(Math.rint(source[i].dx), Math.rint(source[i].dy), 0);
        }
        return out;
    }

    private static Transform[] identity(int count) {
        Transform[] out = new Transform[count];
        for (int i = 0; i < count; i++) out[i] = Transform.IDENTITY;
        return out;
    }

    static Transform[] truthTransforms(String motion) {
        int[] x = new int[Benchmark.FRAMES];
        int[] y = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(x, y);
        Transform[] out = new Transform[Benchmark.FRAMES];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Transform(x[i] / (double) Benchmark.FINE,
                    y[i] / (double) Benchmark.FINE, 0);
        }
        return out;
    }

    private static Map<String, List<double[]>> readProxyScores(Path path) throws IOException {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out.computeIfAbsent(key(f), k -> new ArrayList<>())
                    .add(new double[]{Double.parseDouble(f.get(9))});
        }
        return out;
    }

    private static Map<String, List<String>> readProxyIds(Path path) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out.computeIfAbsent(key(f), k -> new ArrayList<>()).add(f.get(5));
        }
        return out;
    }

    private static String key(List<String> f) {
        return f.get(0) + '/' + f.get(1) + '/' + f.get(3) + '/' + f.get(4);
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }

    private SweepProxyGroundTruthCheck() {
    }
}
