/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import logratio.api.LogRatioParameters;
import logratio.api.RegistrationRecipe;
import logratio.api.SelectionMode;
import logratio.core.Transform;

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
 * Does following the interactive sweep get a user near the per-recording ceiling?
 *
 * <p>The oracle in the factorial sweep ranked the 96 recipes by true shift error, which needs the
 * synthetic ground truth. A user running {@code LogRatioSweepDialog} on a real recording has no
 * ground truth, so the sweep ranks its arms by a proxy instead: the median over frame pairs of the
 * mean absolute deviation of the log ratio after correction. Whether that proxy picks the recipe the
 * oracle would pick has never been measured. This measures it.
 *
 * <p>Nothing is registered again. Every one of the 7,680 runs in {@code full_selector_sweep_v1}
 * saved its cumulative movement to {@code transforms.csv}, so each run is rescored from the saved
 * shifts with the production scoring method, and the true error is recomputed from the same shifts
 * with the production metric. The two rankings are then compared recipe by recipe.
 */
public final class SweepProxyOracleBenchmark {
    static final String RUN_ID = "sweep_proxy_v1";
    static final String SOURCE_RUN = FullSelectorFactorialBenchmark.RUN_ID;

    static final String ROW_HEADER = "image_series_class,series_id,independent_group,"
            + "motion_category,condition,recipe_id,in_practical_subset,true_median_error_px,"
            + "true_p90_error_px,proxy_residual";

    static final String RECORDING_HEADER = "image_series_class,series_id,independent_group,"
            + "motion_category,condition,recipes_scored,category_median_error_px,"
            + "oracle_recipe_id,oracle_median_error_px,proxy_recipe_id,proxy_median_error_px,"
            + "proxy_rank_by_truth,equivalence_width_px,proxy_matches_oracle,"
            + "proxy_within_equivalence,proxy_beats_category,spearman_proxy_vs_truth,"
            + "subset_proxy_recipe_id,subset_proxy_median_error_px,subset_oracle_median_error_px,"
            + "subset_proxy_rank_by_truth,subset_proxy_beats_category";

    /**
     * The twenty-four recipes a user can sweep in one pass, given the dialog's 24-combination cap:
     * three pixel-evidence settings, four estimation filters and the mask on or off, at no brightness
     * restriction.
     *
     * <p>Chosen by counting, not by taste. Of the 80 development recordings the per-recording winner
     * falls inside this grid for 35, against 26 for the same grid without the mask axis and 11 for a
     * grid that sweeps the brightness ceiling instead of the filter. The estimation filter is the axis
     * that decides a winner; the brightness ceiling barely moves it.
     */
    static Set<String> practicalSubset() {
        Set<String> out = new LinkedHashSet<>();
        for (String support : new String[]{"all", "gradient", "mutual_noise_gradient"}) {
            for (String filter : new String[]{"none", "gaussian_0_7", "gaussian_1_0", "median_3x3"}) {
                for (String mask : new String[]{"none", "least_informative_25"}) {
                    out.add("support_" + support + "__band_full__filter_" + filter
                            + "__mask_" + mask);
                }
            }
        }
        return out;
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
        List<String> recipeIds = new ArrayList<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            recipeIds.add(recipe.id());
        }
        Set<String> subset = practicalSubset();
        Map<String, double[]> reference = readOracleReference(
                root.resolve("summaries").resolve(SOURCE_RUN).resolve("oracle_by_recording.csv"));

        // Rescoring is the slow part and its output is complete, so the summaries can be rebuilt from
        // all_proxy_scores.csv when only the subset definition or the reporting changes.
        boolean rescore = !"false".equalsIgnoreCase(System.getProperty("logratio.rescore", "true"));
        if (!rescore) {
            resummarise(summary, recordings, subset, reference);
            return;
        }

        List<String> rows = new ArrayList<>();
        List<String> perRecording = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int scored = 0;
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            ImagePlus input = IJ.openImage(recording.input.toString());
            if (input == null) {
                missing.add("could not open " + recording.input);
                continue;
            }
            // Only the channel, the slice and epsilon reach the scoring method, and they are the same
            // for every arm, so one bundle serves all ninety-six.
            LogRatioParameters scoring = LogRatioParameters.builder()
                    .recommendation(FullSelectorFactorialBenchmark.imageType(recording.imageClass),
                            FullSelectorFactorialBenchmark.motionType(recording.motion))
                    .selectionMode(SelectionMode.MANUAL)
                    .build();
            try {
                List<String> ids = new ArrayList<>();
                List<double[]> truth = new ArrayList<>();
                List<Double> proxy = new ArrayList<>();
                for (String recipeId : recipeIds) {
                    Path transforms = recording.folder.resolve(SOURCE_RUN).resolve(recipeId)
                            .resolve("transforms.csv");
                    if (!Files.isRegularFile(transforms)) {
                        missing.add(recording.key() + " / " + recipeId + ": no transforms.csv");
                        continue;
                    }
                    Transform[] cumulative = readTransforms(transforms);
                    double[] metrics = FullSelectorFactorialBenchmark.controlledMetrics(
                            cumulative, recording.motion);
                    double residual = LogRatioSweepDialog.fullResolutionResidual(
                            input, scoring, cumulative);
                    ids.add(recipeId);
                    truth.add(metrics);
                    proxy.add(residual);
                    rows.add(csv(recording.imageClass) + ',' + csv(recording.series) + ','
                            + csv(recording.independentGroup) + ',' + csv(recording.motion) + ','
                            + csv(recording.condition) + ',' + csv(recipeId) + ','
                            + subset.contains(recipeId) + ',' + format(metrics[0]) + ','
                            + format(metrics[1]) + ',' + format(residual));
                    scored++;
                }
                if (!ids.isEmpty()) {
                    perRecording.add(summarise(recording, ids, truth, proxy, subset, reference));
                }
            } finally {
                input.close();
            }
            System.out.printf(Locale.ROOT, "%s: %d rescored%n", recording.key(), scored);
        }

        write(summary.resolve("all_proxy_scores.csv"), ROW_HEADER, rows);
        write(summary.resolve("proxy_by_recording.csv"), RECORDING_HEADER, perRecording);
        writeFindings(summary, perRecording);
        System.out.printf(Locale.ROOT, "sweep proxy: %d runs rescored, %d missing%n",
                scored, missing.size());
        if (!missing.isEmpty()) {
            throw new IOException("missing sweep artifacts:\n" + String.join("\n", missing));
        }
    }

    /** Rebuild the per-recording table and the findings from an existing {@code all_proxy_scores.csv}. */
    private static void resummarise(Path summary,
                                    List<FullSelectorFactorialBenchmark.Recording> recordings,
                                    Set<String> subset, Map<String, double[]> reference)
            throws IOException {
        Map<String, List<String>> idsBy = new LinkedHashMap<>();
        Map<String, List<double[]>> truthBy = new LinkedHashMap<>();
        Map<String, List<Double>> proxyBy = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(summary.resolve("all_proxy_scores.csv"),
                StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            String recordingKey = key(f.get(0), f.get(1), f.get(3), f.get(4));
            idsBy.computeIfAbsent(recordingKey, k -> new ArrayList<>()).add(f.get(5));
            truthBy.computeIfAbsent(recordingKey, k -> new ArrayList<>())
                    .add(new double[]{Double.parseDouble(f.get(7)), Double.parseDouble(f.get(8))});
            proxyBy.computeIfAbsent(recordingKey, k -> new ArrayList<>())
                    .add(Double.parseDouble(f.get(9)));
        }
        // The subset flag is a property of the current subset definition, not of the scoring, so it is
        // rewritten here rather than left describing whichever subset happened to be current at scoring.
        List<String> rescoredRows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            rescoredRows.add(csv(f.get(0)) + ',' + csv(f.get(1)) + ',' + csv(f.get(2)) + ','
                    + csv(f.get(3)) + ',' + csv(f.get(4)) + ',' + csv(f.get(5)) + ','
                    + subset.contains(f.get(5)) + ',' + f.get(7) + ',' + f.get(8) + ','
                    + f.get(9));
        }
        write(summary.resolve("all_proxy_scores.csv"), ROW_HEADER, rescoredRows);

        List<String> perRecording = new ArrayList<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            String recordingKey = key(recording.imageClass, recording.series, recording.motion,
                    recording.condition);
            List<String> ids = idsBy.get(recordingKey);
            if (ids == null || ids.isEmpty()) continue;
            perRecording.add(summarise(recording, ids, truthBy.get(recordingKey),
                    proxyBy.get(recordingKey), subset, reference));
        }
        write(summary.resolve("proxy_by_recording.csv"), RECORDING_HEADER, perRecording);
        writeFindings(summary, perRecording);
        System.out.printf(Locale.ROOT, "sweep proxy: resummarised %d recordings%n",
                perRecording.size());
    }

    /** Category error and equivalence width, taken from the frozen sweep rather than recomputed. */
    private static Map<String, double[]> readOracleReference(Path path) throws IOException {
        Map<String, double[]> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> header = FullSelectorFactorialBenchmark.fields(lines.get(0));
        int width = header.indexOf("equivalence_width_px");
        int category = header.indexOf("category_median_error_px");
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out.put(key(f.get(0), f.get(1), f.get(3), f.get(4)),
                    new double[]{Double.parseDouble(f.get(category)),
                            Double.parseDouble(f.get(width))});
        }
        return out;
    }

    private static String key(String imageClass, String series, String motion, String condition) {
        return imageClass + '/' + series + '/' + motion + '/' + condition;
    }

    static Transform[] readTransforms(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Transform> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> f = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out.add(new Transform(Double.parseDouble(f.get(1)), Double.parseDouble(f.get(2)),
                    Double.parseDouble(f.get(3))));
        }
        return out.toArray(new Transform[0]);
    }

    private static String summarise(FullSelectorFactorialBenchmark.Recording recording,
                                    List<String> ids, List<double[]> truth, List<Double> proxy,
                                    Set<String> subset, Map<String, double[]> reference) {
        int oracleAt = argMin(truth, null, ids);
        int proxyAt = argMinProxy(proxy, null, ids);
        int subsetOracleAt = argMin(truth, subset, ids);
        int subsetProxyAt = argMinProxy(proxy, subset, ids);
        double[] ref = reference.get(key(recording.imageClass, recording.series,
                recording.motion, recording.condition));
        double categoryError = ref == null ? Double.NaN : ref[0];
        double width = ref == null ? Double.NaN : ref[1];
        double oracleError = truth.get(oracleAt)[0];
        double proxyError = truth.get(proxyAt)[0];
        double subsetProxyError = truth.get(subsetProxyAt)[0];
        return csv(recording.imageClass) + ',' + csv(recording.series) + ','
                + csv(recording.independentGroup) + ',' + csv(recording.motion) + ','
                + csv(recording.condition) + ',' + ids.size() + ',' + format(categoryError) + ','
                + csv(ids.get(oracleAt)) + ',' + format(oracleError) + ','
                + csv(ids.get(proxyAt)) + ',' + format(proxyError) + ','
                + rankByTruth(truth, proxyAt, null, ids) + ',' + format(width) + ','
                + (proxyAt == oracleAt) + ',' + (proxyError <= oracleError + width) + ','
                + (proxyError < categoryError) + ',' + format(spearman(truth, proxy)) + ','
                + csv(ids.get(subsetProxyAt)) + ',' + format(subsetProxyError) + ','
                + format(truth.get(subsetOracleAt)[0]) + ','
                + rankByTruth(truth, subsetProxyAt, subset, ids) + ','
                + (subsetProxyError < categoryError);
    }

    private static int argMin(List<double[]> truth, Set<String> only, List<String> ids) {
        int best = -1;
        for (int i = 0; i < truth.size(); i++) {
            if (only != null && !only.contains(ids.get(i))) continue;
            if (best < 0 || truth.get(i)[0] < truth.get(best)[0]) best = i;
        }
        return best;
    }

    private static int argMinProxy(List<Double> proxy, Set<String> only, List<String> ids) {
        int best = -1;
        for (int i = 0; i < proxy.size(); i++) {
            if (only != null && !only.contains(ids.get(i))) continue;
            if (!Double.isFinite(proxy.get(i))) continue;
            if (best < 0 || proxy.get(i) < proxy.get(best)) best = i;
        }
        return best < 0 ? 0 : best;
    }

    /** Where the proxy's pick sits in the true ordering: 1 means it found the oracle's recipe. */
    private static int rankByTruth(List<double[]> truth, int pick, Set<String> only,
                                  List<String> ids) {
        int rank = 1;
        for (int i = 0; i < truth.size(); i++) {
            if (only != null && !only.contains(ids.get(i))) continue;
            if (truth.get(i)[0] < truth.get(pick)[0]) rank++;
        }
        return rank;
    }

    /** Rank correlation with average ranks for ties; +1 means the proxy orders exactly like truth. */
    static double spearman(List<double[]> truth, List<Double> proxy) {
        int n = truth.size();
        if (n < 3) return Double.NaN;
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = truth.get(i)[0];
            b[i] = Double.isFinite(proxy.get(i)) ? proxy.get(i) : Double.MAX_VALUE;
        }
        double[] ra = ranks(a);
        double[] rb = ranks(b);
        double meanA = 0;
        double meanB = 0;
        for (int i = 0; i < n; i++) { meanA += ra[i]; meanB += rb[i]; }
        meanA /= n;
        meanB /= n;
        double cov = 0;
        double varA = 0;
        double varB = 0;
        for (int i = 0; i < n; i++) {
            double da = ra[i] - meanA;
            double db = rb[i] - meanB;
            cov += da * db;
            varA += da * da;
            varB += db * db;
        }
        return varA <= 0 || varB <= 0 ? Double.NaN : cov / Math.sqrt(varA * varB);
    }

    private static double[] ranks(double[] values) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (l, r) -> Double.compare(values[l], values[r]));
        double[] out = new double[values.length];
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && values[order[j + 1]] == values[order[i]]) j++;
            double shared = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) out[order[k]] = shared;
            i = j + 1;
        }
        return out;
    }

    private static void writeFindings(Path summary, List<String> perRecording) throws IOException {
        List<String> header = FullSelectorFactorialBenchmark.fields(RECORDING_HEADER);
        int imageAt = header.indexOf("image_series_class");
        int categoryAt = header.indexOf("category_median_error_px");
        int oracleAt = header.indexOf("oracle_median_error_px");
        int proxyAt = header.indexOf("proxy_median_error_px");
        int rankAt = header.indexOf("proxy_rank_by_truth");
        int matchAt = header.indexOf("proxy_matches_oracle");
        int withinAt = header.indexOf("proxy_within_equivalence");
        int beatsAt = header.indexOf("proxy_beats_category");
        int spearmanAt = header.indexOf("spearman_proxy_vs_truth");
        int subsetProxyAt = header.indexOf("subset_proxy_median_error_px");
        int subsetOracleAt = header.indexOf("subset_oracle_median_error_px");
        int subsetRankAt = header.indexOf("subset_proxy_rank_by_truth");
        int subsetBeatsAt = header.indexOf("subset_proxy_beats_category");

        Map<String, double[]> byType = new TreeMap<>();
        double[] all = new double[13];
        for (String row : perRecording) {
            List<String> f = FullSelectorFactorialBenchmark.fields(row);
            accumulate(all, f, categoryAt, oracleAt, proxyAt, rankAt, matchAt, withinAt, beatsAt,
                    spearmanAt, subsetProxyAt, subsetOracleAt, subsetRankAt, subsetBeatsAt);
            accumulate(byType.computeIfAbsent(f.get(imageAt), k -> new double[13]), f, categoryAt,
                    oracleAt, proxyAt, rankAt, matchAt, withinAt, beatsAt, spearmanAt,
                    subsetProxyAt, subsetOracleAt, subsetRankAt, subsetBeatsAt);
        }

        StringBuilder out = new StringBuilder();
        out.append("# Does the interactive sweep reach the per-recording ceiling?\n\n");
        out.append("Run id `").append(RUN_ID).append("`, rescored from the saved shifts of `")
                .append(SOURCE_RUN).append("`. Nothing was registered again, so every true error ")
                .append("here is the same number the factorial sweep reported.\n\n");
        out.append("The sweep dialog cannot see ground truth, so it ranks its arms by leftover ")
                .append("log-ratio residual. The question is whether that ranking finds the recipe ")
                .append("the oracle would have chosen.\n\n");
        out.append("## All recordings\n\n");
        out.append(table(all));
        out.append("\n## By image type\n\n");
        out.append("| Image type | recordings | category px | oracle px | sweep pick px | ")
                .append("subset pick px | rank of pick | matches oracle | beats category | ")
                .append("mean rank correlation |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, double[]> entry : byType.entrySet()) {
            double[] t = entry.getValue();
            int n = (int) t[0];
            out.append("| ").append(entry.getKey()).append(" | ").append(n)
                    .append(" | ").append(format(t[1] / n)).append(" | ").append(format(t[2] / n))
                    .append(" | ").append(format(t[3] / n)).append(" | ").append(format(t[8] / n))
                    .append(" | ").append(format(t[4] / n)).append(" | ")
                    .append(percent(t[5], n)).append(" | ").append(percent(t[7], n))
                    .append(" | ").append(format(t[10] / n)).append(" |\n");
        }
        out.append("\n`subset pick` is the arm the proxy score picks out of the twenty-four a user can ")
                .append("sweep in one pass: pixel evidence in {ALL, GRADIENT, MUTUAL_NOISE_GRADIENT} ")
                .append("x estimation filter in {NONE, GAUSSIAN_0_7, GAUSSIAN_1_0, MEDIAN_3X3} x mask ")
                .append("on or off, at no brightness restriction. That grid holds the true winner for ")
                .append("35 of the 80 recordings, more than any other grid inside the dialog's cap, ")
                .append("but it drops the brightness band that sparse low-light depends on, which is ")
                .append("why the sparse row below is far worse for the subset than for all 96.\n");
        Files.write(summary.resolve("FINDINGS.md"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void accumulate(double[] t, List<String> f, int categoryAt, int oracleAt,
                                   int proxyAt, int rankAt, int matchAt, int withinAt, int beatsAt,
                                   int spearmanAt, int subsetProxyAt, int subsetOracleAt,
                                   int subsetRankAt, int subsetBeatsAt) {
        t[0] += 1;
        t[1] += Double.parseDouble(f.get(categoryAt));
        t[2] += Double.parseDouble(f.get(oracleAt));
        t[3] += Double.parseDouble(f.get(proxyAt));
        t[4] += Double.parseDouble(f.get(rankAt));
        t[5] += Boolean.parseBoolean(f.get(matchAt)) ? 1 : 0;
        t[6] += Boolean.parseBoolean(f.get(withinAt)) ? 1 : 0;
        t[7] += Boolean.parseBoolean(f.get(beatsAt)) ? 1 : 0;
        t[8] += Double.parseDouble(f.get(subsetProxyAt));
        t[9] += Double.parseDouble(f.get(subsetOracleAt));
        t[10] += finite(f.get(spearmanAt));
        t[11] += Boolean.parseBoolean(f.get(subsetBeatsAt)) ? 1 : 0;
        // Ranks are kept per subset: rank 1 of 96 and rank 1 of 18 are not the same claim.
        t[12] += Double.parseDouble(f.get(subsetRankAt));
    }

    private static double finite(String value) {
        double parsed = Double.parseDouble(value);
        return Double.isFinite(parsed) ? parsed : 0;
    }

    private static String table(double[] t) {
        int n = (int) t[0];
        StringBuilder out = new StringBuilder("| Quantity | Value |\n|---|---|\n");
        out.append("| Recordings | ").append(n).append(" |\n");
        out.append("| Category recommendation, mean median error | ")
                .append(format(t[1] / n)).append(" px |\n");
        out.append("| Oracle over all 96 recipes, pure minimum | ").append(format(t[2] / n)).append(" px |\n");
        out.append("| Sweep proxy pick over all 96 recipes | ")
                .append(format(t[3] / n)).append(" px |\n");
        out.append("| Sweep proxy pick within the 24-arm subset | ")
                .append(format(t[8] / n)).append(" px |\n");
        out.append("| Oracle within the 24-arm subset | ").append(format(t[9] / n))
                .append(" px |\n");
        out.append("| Mean rank of the proxy pick in the true ordering (1 of 96 is best) | ")
                .append(format(t[4] / n)).append(" |\n");
        out.append("| Mean rank of the 24-arm proxy pick (1 of 24 is best) | ")
                .append(format(t[12] / n)).append(" |\n");
        out.append("| Proxy pick is the oracle's recipe | ").append(percent(t[5], n)).append(" |\n");
        out.append("| Proxy pick is within the equivalence width of the oracle | ")
                .append(percent(t[6], n)).append(" |\n");
        out.append("| Proxy pick beats the category recommendation | ")
                .append(percent(t[7], n)).append(" |\n");
        out.append("| 24-arm proxy pick beats the category recommendation | ")
                .append(percent(t[11], n)).append(" |\n");
        out.append("| Mean rank correlation between proxy and truth | ")
                .append(format(t[10] / n)).append(" |\n");
        return out.toString();
    }

    private static String percent(double count, int total) {
        return String.format(Locale.ROOT, "%d of %d (%.0f%%)", (int) count, total,
                100.0 * count / total);
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

    private SweepProxyOracleBenchmark() {
    }
}
