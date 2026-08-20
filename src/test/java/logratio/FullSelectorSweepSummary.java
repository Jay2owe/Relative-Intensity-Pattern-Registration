/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.LogRatioParameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Summarises the 96-recipe factorial sweep and names the winner under a rule fixed in advance.
 *
 * <p>The equivalence rule, its tie-breaks and the two-source consistency gate are all declared
 * before any result is read, so the winner cannot be chosen by inspecting the table first. Every
 * decision written here can be recomputed from {@code all_recipes.csv} alone.
 */
public final class FullSelectorSweepSummary {
    static final String RUN_ID = FullSelectorFactorialBenchmark.RUN_ID;
    static final String CATEGORY_ARM = "full_benchmark_2026-08-16/033_current_category_recommendation";

    /** Declared before the results were examined. */
    static final double MINIMUM_EQUIVALENCE_WIDTH = 0.001;
    static final double EQUIVALENCE_FRACTION = 0.05;
    /** A recipe may stay an automatic output only with support from two independent source series. */
    static final int MINIMUM_INDEPENDENT_WINS = 2;

    /** Joins the image and motion parts of a cell key; never appears in either name. */
    private static final String SEP = "::";

    private FullSelectorSweepSummary() {
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
        Map<String, FullSelectorFactorialBenchmark.Recipe> byId = new LinkedHashMap<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) byId.put(recipe.id, recipe);

        List<Row> rows = new ArrayList<>();
        Map<String, Baseline> baselines = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            baselines.put(recording.key(), baseline(recording));
            for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) {
                Path file = recording.folder.resolve(RUN_ID).resolve(recipe.id)
                        .resolve("comparison.csv");
                if (!Files.isRegularFile(file)) {
                    missing.add(recording + " / " + recipe.id);
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() < 2) {
                    missing.add(recording + " / " + recipe.id + " (empty)");
                    continue;
                }
                rows.add(Row.parse(lines.get(1), recording, recipe));
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("missing comparison rows for " + missing.size()
                    + " recording-recipe pairs; first: " + missing.get(0));
        }

        writeAllRecipes(summary, rows, baselines);
        Map<String, Aggregate> overall = aggregate(rows, recipes);
        Map<String, List<String>> oracle = writeOracle(summary, rows, recordings, baselines);
        Map<String, Integer> independentWins = independentWins(oracle, recordings);
        Map<String, Integer> recordingWins = recordingWins(oracle);
        writeRecipeSummary(summary, recipes, overall, independentWins, recordingWins, baselines,
                recordings);
        writeCellSummary(summary, rows, recipes, recordings, baselines);
        writeFindings(summary, recipes, recordings, rows, overall, independentWins, recordingWins,
                baselines, byId);
        System.out.printf(Locale.ROOT, "summarised %d rows over %d recordings and %d recipes%n",
                rows.size(), recordings.size(), recipes.size());
    }

    // ---------------------------------------------------------------- outputs

    private static void writeAllRecipes(Path summary, List<Row> rows, Map<String, Baseline> baselines)
            throws IOException {
        StringBuilder out = new StringBuilder("image_series_class,series_id,independent_group,"
                + "motion_category,condition,recipe_id,base_pixel_support,intensity_band,"
                + "estimation_filter,spatial_mask,status,median_error_px,p90_error_px,max_error_px,"
                + "total_seconds,cpu_seconds,changes_from_recommendation,"
                + "category_median_error_px,category_p90_error_px,category_seconds,"
                + "improvement_px,improvement_percent\n");
        for (Row row : rows) {
            Baseline base = baselines.get(row.recording.key());
            double improvement = base.median - row.median;
            out.append(csv(row.recording.imageClass)).append(',').append(csv(row.recording.series))
                    .append(',').append(csv(row.recording.independentGroup)).append(',')
                    .append(csv(row.recording.motion)).append(',')
                    .append(csv(row.recording.condition)).append(',').append(csv(row.recipe.id))
                    .append(',').append(csv(row.recipe.support.name())).append(',')
                    .append(csv(row.recipe.band.id)).append(',')
                    .append(csv(row.recipe.filter.name())).append(',')
                    .append(csv(row.recipe.mask.id)).append(',').append(csv(row.status))
                    .append(',').append(format(row.median)).append(',').append(format(row.p90))
                    .append(',').append(format(row.max)).append(',').append(format(row.seconds))
                    .append(',').append(format(row.cpuSeconds)).append(',')
                    .append(changes(row.recording, row.recipe)).append(',')
                    .append(format(base.median)).append(',').append(format(base.p90)).append(',')
                    .append(format(base.seconds)).append(',').append(format(improvement))
                    .append(',').append(format(base.median > 0 ? 100 * improvement / base.median
                            : Double.NaN)).append('\n');
        }
        Files.write(summary.resolve("all_recipes.csv"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, List<String>> writeOracle(
            Path summary, List<Row> rows,
            List<FullSelectorFactorialBenchmark.Recording> recordings,
            Map<String, Baseline> baselines) throws IOException {
        Map<String, List<Row>> byRecording = new LinkedHashMap<>();
        for (Row row : rows) {
            byRecording.computeIfAbsent(row.recording.key(), key -> new ArrayList<>()).add(row);
        }
        Map<String, List<String>> equivalent = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder("image_series_class,series_id,independent_group,"
                + "motion_category,condition,oracle_recipe_id,oracle_median_error_px,"
                + "oracle_p90_error_px,oracle_cpu_seconds,equivalence_width_px,"
                + "equivalent_recipe_count,changes_from_recommendation,"
                + "category_median_error_px,improvement_px,equivalent_recipe_ids\n");
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            List<Row> candidates = byRecording.getOrDefault(recording.key(), new ArrayList<>());
            if (candidates.isEmpty()) continue;
            double best = Double.POSITIVE_INFINITY;
            for (Row row : candidates) {
                if (row.ok() && row.median < best) best = row.median;
            }
            double width = equivalenceWidth(best);
            List<Row> pool = new ArrayList<>();
            for (Row row : candidates) {
                if (row.ok() && row.median <= best + width) pool.add(row);
            }
            pool.sort(preference(recording));
            Row winner = pool.get(0);
            List<String> ids = new ArrayList<>();
            for (Row row : pool) ids.add(row.recipe.id);
            equivalent.put(recording.key(), ids);
            Baseline base = baselines.get(recording.key());
            out.append(csv(recording.imageClass)).append(',').append(csv(recording.series))
                    .append(',').append(csv(recording.independentGroup)).append(',')
                    .append(csv(recording.motion)).append(',').append(csv(recording.condition))
                    .append(',').append(csv(winner.recipe.id)).append(',')
                    .append(format(winner.median)).append(',').append(format(winner.p90))
                    .append(',').append(format(winner.cpuSeconds)).append(',').append(format(width))
                    .append(',').append(pool.size()).append(',')
                    .append(changes(recording, winner.recipe)).append(',')
                    .append(format(base.median)).append(',')
                    .append(format(base.median - winner.median)).append(',')
                    .append(csv(String.join(";", ids))).append('\n');
        }
        Files.write(summary.resolve("oracle_by_recording.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
        return equivalent;
    }

    private static void writeRecipeSummary(Path summary,
                                           List<FullSelectorFactorialBenchmark.Recipe> recipes,
                                           Map<String, Aggregate> overall,
                                           Map<String, Integer> independentWins,
                                           Map<String, Integer> recordingWins,
                                           Map<String, Baseline> baselines,
                                           List<FullSelectorFactorialBenchmark.Recording> recordings)
            throws IOException {
        double categoryMean = 0;
        double categoryP90 = 0;
        double categorySeconds = 0;
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            Baseline base = baselines.get(recording.key());
            categoryMean += base.median;
            categoryP90 += base.p90;
            categorySeconds += base.seconds;
        }
        categoryMean /= recordings.size();
        categoryP90 /= recordings.size();
        categorySeconds /= recordings.size();

        StringBuilder out = new StringBuilder("recipe_id,base_pixel_support,intensity_band,"
                + "estimation_filter,spatial_mask,recordings,failures,mean_median_error_px,"
                + "median_of_median_error_px,mean_p90_error_px,max_error_px,mean_seconds,"
                + "mean_cpu_seconds,oracle_wins_recordings,oracle_wins_independent_sources,"
                + "retained_by_two_source_rule,category_mean_median_error_px,improvement_px\n");
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) {
            Aggregate aggregate = overall.get(recipe.id);
            int independent = independentWins.getOrDefault(recipe.id, 0);
            out.append(csv(recipe.id)).append(',').append(csv(recipe.support.name())).append(',')
                    .append(csv(recipe.band.id)).append(',').append(csv(recipe.filter.name()))
                    .append(',').append(csv(recipe.mask.id)).append(',').append(aggregate.count)
                    .append(',').append(aggregate.failures).append(',')
                    .append(format(aggregate.meanMedian())).append(',')
                    .append(format(aggregate.medianOfMedian())).append(',')
                    .append(format(aggregate.meanP90())).append(',')
                    .append(format(aggregate.maximum)).append(',')
                    .append(format(aggregate.meanSeconds())).append(',')
                    .append(format(aggregate.meanCpuSeconds())).append(',')
                    .append(recordingWins.getOrDefault(recipe.id, 0)).append(',')
                    .append(independent).append(',')
                    .append(independent >= MINIMUM_INDEPENDENT_WINS).append(',')
                    .append(format(categoryMean)).append(',')
                    .append(format(categoryMean - aggregate.meanMedian())).append('\n');
        }
        Files.write(summary.resolve("recipe_summary.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeCellSummary(Path summary, List<Row> rows,
                                         List<FullSelectorFactorialBenchmark.Recipe> recipes,
                                         List<FullSelectorFactorialBenchmark.Recording> recordings,
                                         Map<String, Baseline> baselines) throws IOException {
        StringBuilder out = new StringBuilder("scope,image_series_class,motion_category,recipe_id,"
                + "recordings,failures,mean_median_error_px,mean_p90_error_px,max_error_px,"
                + "mean_seconds,mean_cpu_seconds,category_mean_median_error_px,improvement_px,"
                + "is_scope_winner\n");
        appendScope(out, "overall", rows, recipes, recordings, baselines, row -> "", key -> "");
        appendScope(out, "image_type", rows, recipes, recordings, baselines,
                row -> row.recording.imageClass, key -> key);
        appendScope(out, "motion_type", rows, recipes, recordings, baselines,
                row -> row.recording.motion, key -> key);
        appendScope(out, "image_type_x_motion_type", rows, recipes, recordings, baselines,
                row -> row.recording.imageClass + SEP + row.recording.motion, key -> key);
        Files.write(summary.resolve("cell_summary.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private interface RowKey {
        String of(Row row);
    }

    private interface KeyLabel {
        String of(String key);
    }

    private static void appendScope(StringBuilder out, String scope, List<Row> rows,
                                    List<FullSelectorFactorialBenchmark.Recipe> recipes,
                                    List<FullSelectorFactorialBenchmark.Recording> recordings,
                                    Map<String, Baseline> baselines, RowKey key, KeyLabel label)
            throws IOException {
        Map<String, Map<String, Aggregate>> byKey = new TreeMap<>();
        for (Row row : rows) {
            byKey.computeIfAbsent(key.of(row), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.recipe.id, ignored -> new Aggregate()).add(row);
        }
        Map<String, Double> categoryByKey = new TreeMap<>();
        Map<String, Integer> categoryCount = new TreeMap<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            String recordingKey;
            if ("overall".equals(scope)) recordingKey = "";
            else if ("image_type".equals(scope)) recordingKey = recording.imageClass;
            else if ("motion_type".equals(scope)) recordingKey = recording.motion;
            else recordingKey = recording.imageClass + SEP + recording.motion;
            categoryByKey.merge(recordingKey, baselines.get(recording.key()).median, Double::sum);
            categoryCount.merge(recordingKey, 1, Integer::sum);
        }
        for (Map.Entry<String, Map<String, Aggregate>> entry : byKey.entrySet()) {
            String winner = scopeWinner(entry.getValue(), recipes, recordings, entry.getKey(), scope);
            double category = categoryByKey.get(entry.getKey()) / categoryCount.get(entry.getKey());
            String[] parts = entry.getKey().split(SEP, -1);
            String imageClass = "image_type".equals(scope) ? parts[0]
                    : ("image_type_x_motion_type".equals(scope) ? parts[0] : "");
            String motion = "motion_type".equals(scope) ? parts[0]
                    : ("image_type_x_motion_type".equals(scope) ? parts[1] : "");
            for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) {
                Aggregate aggregate = entry.getValue().get(recipe.id);
                if (aggregate == null) continue;
                out.append(csv(scope)).append(',').append(csv(imageClass)).append(',')
                        .append(csv(motion)).append(',').append(csv(recipe.id)).append(',')
                        .append(aggregate.count).append(',').append(aggregate.failures).append(',')
                        .append(format(aggregate.meanMedian())).append(',')
                        .append(format(aggregate.meanP90())).append(',')
                        .append(format(aggregate.maximum)).append(',')
                        .append(format(aggregate.meanSeconds())).append(',')
                        .append(format(aggregate.meanCpuSeconds())).append(',')
                        .append(format(category)).append(',')
                        .append(format(category - aggregate.meanMedian())).append(',')
                        .append(recipe.id.equals(winner)).append('\n');
            }
        }
    }

    // ---------------------------------------------------------------- decision rules

    static double equivalenceWidth(double best) {
        return Math.max(MINIMUM_EQUIVALENCE_WIDTH, EQUIVALENCE_FRACTION * best);
    }

    /**
     * The declared preference order inside the equivalence band: no failure, then lower
     * 90th-percentile error, then fewer automatic changes, then shorter processor time.
     */
    private static Comparator<Row> preference(FullSelectorFactorialBenchmark.Recording recording) {
        return Comparator.<Row>comparingInt(row -> row.ok() ? 0 : 1)
                .thenComparingDouble(row -> row.p90)
                .thenComparingInt(row -> changes(recording, row.recipe))
                .thenComparingDouble(row -> row.cpuSeconds)
                .thenComparing(row -> row.recipe.id);
    }

    private static String scopeWinner(Map<String, Aggregate> aggregates,
                                      List<FullSelectorFactorialBenchmark.Recipe> recipes,
                                      List<FullSelectorFactorialBenchmark.Recording> recordings,
                                      String key, String scope) {
        double best = Double.POSITIVE_INFINITY;
        for (Aggregate aggregate : aggregates.values()) {
            if (aggregate.failures == 0 && aggregate.meanMedian() < best) best = aggregate.meanMedian();
        }
        if (!Double.isFinite(best)) return "";
        double width = equivalenceWidth(best);
        List<FullSelectorFactorialBenchmark.Recipe> pool = new ArrayList<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) {
            Aggregate aggregate = aggregates.get(recipe.id);
            if (aggregate != null && aggregate.meanMedian() <= best + width) pool.add(recipe);
        }
        if (pool.isEmpty()) return "";
        // Changes from the recommendation are only defined against a concrete recording; average the
        // count over every recording inside this scope so the tie-break stays balanced.
        Map<String, Double> meanChanges = new LinkedHashMap<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : pool) {
            double total = 0;
            int count = 0;
            for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
                if (!inScope(recording, key, scope)) continue;
                total += changes(recording, recipe);
                count++;
            }
            meanChanges.put(recipe.id, count == 0 ? 4 : total / count);
        }
        pool.sort(Comparator
                .<FullSelectorFactorialBenchmark.Recipe>comparingInt(
                        recipe -> aggregates.get(recipe.id).failures == 0 ? 0 : 1)
                .thenComparingDouble(recipe -> aggregates.get(recipe.id).meanP90())
                .thenComparingDouble(recipe -> meanChanges.get(recipe.id))
                .thenComparingDouble(recipe -> aggregates.get(recipe.id).meanCpuSeconds())
                .thenComparing(recipe -> recipe.id));
        return pool.get(0).id;
    }

    private static boolean inScope(FullSelectorFactorialBenchmark.Recording recording, String key,
                                   String scope) {
        switch (scope) {
            case "overall": return true;
            case "image_type": return recording.imageClass.equals(key);
            case "motion_type": return recording.motion.equals(key);
            default: return (recording.imageClass + SEP + recording.motion).equals(key);
        }
    }

    /** How many of the four swept dimensions differ from the category recommendation. */
    static int changes(FullSelectorFactorialBenchmark.Recording recording,
                       FullSelectorFactorialBenchmark.Recipe recipe) {
        LogRatioParameters recommended = LogRatioParameters.builder().recommendation(
                FullSelectorFactorialBenchmark.imageType(recording.imageClass),
                FullSelectorFactorialBenchmark.motionType(recording.motion)).build();
        int changes = 0;
        if (recommended.pixelSupport != recipe.support) changes++;
        if (!sameBand(recommended, recipe)) changes++;
        if (recommended.preprocessing != recipe.filter) changes++;
        if (recommended.pixelSelectionStrategy != recipe.mask.strategy) changes++;
        return changes;
    }

    private static boolean sameBand(LogRatioParameters recommended,
                                    FullSelectorFactorialBenchmark.Recipe recipe) {
        return same(recommended.floorPercentile, recipe.band.floor)
                && same(recommended.ceilingPercentile, recipe.band.ceiling);
    }

    private static boolean same(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) return true;
        return Math.abs(a - b) < 1e-9;
    }

    /** The recipe the category recommendation is equivalent to inside the 96-recipe set. */
    static String recommendedRecipeId(FullSelectorFactorialBenchmark.Recording recording,
                                      List<FullSelectorFactorialBenchmark.Recipe> recipes) {
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) {
            if (changes(recording, recipe) == 0) return recipe.id;
        }
        return "";
    }

    private static Map<String, Integer> independentWins(Map<String, List<String>> oracle,
            List<FullSelectorFactorialBenchmark.Recording> recordings) {
        Map<String, String> groupByKey = new LinkedHashMap<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            groupByKey.put(recording.key(), recording.independentGroup);
        }
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : oracle.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            // Winning means landing inside the equivalence band for that recording, not only being
            // the single tie-break survivor; otherwise the tie-break silently prunes good recipes.
            for (String id : entry.getValue()) {
                groups.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                        .add(groupByKey.get(entry.getKey()));
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            out.put(entry.getKey(), entry.getValue().size());
        }
        return out;
    }

    private static Map<String, Integer> recordingWins(Map<String, List<String>> oracle) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (List<String> ids : oracle.values()) {
            if (!ids.isEmpty()) out.merge(ids.get(0), 1, Integer::sum);
        }
        return out;
    }

    private static Map<String, Aggregate> aggregate(
            List<Row> rows, List<FullSelectorFactorialBenchmark.Recipe> recipes) {
        Map<String, Aggregate> out = new LinkedHashMap<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) out.put(recipe.id, new Aggregate());
        for (Row row : rows) out.get(row.recipe.id).add(row);
        return out;
    }

    // ---------------------------------------------------------------- findings

    private static void writeFindings(Path summary,
                                      List<FullSelectorFactorialBenchmark.Recipe> recipes,
                                      List<FullSelectorFactorialBenchmark.Recording> recordings,
                                      List<Row> rows, Map<String, Aggregate> overall,
                                      Map<String, Integer> independentWins,
                                      Map<String, Integer> recordingWins,
                                      Map<String, Baseline> baselines,
                                      Map<String, FullSelectorFactorialBenchmark.Recipe> byId)
            throws IOException {
        List<FullSelectorFactorialBenchmark.Recipe> ranked = new ArrayList<>(recipes);
        ranked.sort(Comparator.comparingDouble(recipe -> overall.get(recipe.id).meanMedian()));
        double best = overall.get(ranked.get(0).id).meanMedian();
        double width = equivalenceWidth(best);

        double categoryMean = 0;
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            categoryMean += baselines.get(recording.key()).median;
        }
        categoryMean /= recordings.size();

        int totalFailures = 0;
        for (Aggregate aggregate : overall.values()) totalFailures += aggregate.failures;

        int retained = 0;
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) {
            if (independentWins.getOrDefault(recipe.id, 0) >= MINIMUM_INDEPENDENT_WINS) retained++;
        }

        StringBuilder out = new StringBuilder();
        out.append("# Full automatic selector sweep, development results\n\n");
        out.append("Run identifier `").append(RUN_ID).append("`. ")
                .append(recordings.size()).append(" controlled recordings, ")
                .append(recipes.size()).append(" complete recipes, ")
                .append(rows.size()).append(" registration results.\n\n");

        out.append("## What was swept\n\n");
        out.append("Every recipe is a complete, explicit configuration. Four dimensions vary; ")
                .append("everything else comes from the category recommendation for the declared ")
                .append("image and motion types.\n\n");
        out.append("| Dimension | Values |\n|---|---|\n");
        out.append("| Base pixel support | ALL, GRADIENT, MUTUAL_NOISE_GRADIENT |\n");
        out.append("| Intensity restriction | none, exclude brightest 10%, exclude brightest 25%,")
                .append(" exclude dimmest 25% |\n");
        out.append("| Estimation filter | none, Gaussian 0.7 px, Gaussian 1.0 px, median 3 by 3 |\n");
        out.append("| Spatial removal | none, remove least informative 25% |\n\n");
        out.append("Frozen for this sweep: gradient multiplier ")
                .append(format(FullSelectorFactorialBenchmark.GRADIENT_MULTIPLIER))
                .append(", removal percentage ")
                .append(format(FullSelectorFactorialBenchmark.REMOVAL_PERCENT))
                .append(", mask scoring on the raw image, ")
                .append(FullSelectorFactorialBenchmark.MAX_ITERATIONS).append(" iterations and ")
                .append(FullSelectorFactorialBenchmark.MAX_SAMPLES).append(" samples.\n\n");
        out.append("The compute budget is fixed even for mutual-noise recipes, whose production ")
                .append("recommendation uses 12 iterations and 50,000 samples. That difference is ")
                .append("measured separately in the compute sensitivity check, not here.\n\n");

        out.append("## Decision rule, fixed before the results were read\n\n");
        out.append("```text\nequivalence width = max(")
                .append(format(MINIMUM_EQUIVALENCE_WIDTH)).append(" px, ")
                .append((int) (100 * EQUIVALENCE_FRACTION))
                .append("% of the lowest median error)\n```\n\n");
        out.append("Inside that width, prefer: no failure, then lower 90th-percentile error, then ")
                .append("fewer automatic changes from the category recommendation, then shorter ")
                .append("processor time. A recipe may remain an automatic output only if it lands ")
                .append("inside the equivalence band on at least ").append(MINIMUM_INDEPENDENT_WINS)
                .append(" independent source series, or is the category fallback.\n\n");

        out.append("## Headline\n\n");
        out.append("| Measure | Value |\n|---|---|\n");
        out.append("| Registration failures across the whole sweep | ").append(totalFailures)
                .append(" |\n");
        out.append("| Current category recommendation, mean median error | ")
                .append(format(categoryMean)).append(" px |\n");
        out.append("| Best single fixed recipe, mean median error | ").append(format(best))
                .append(" px |\n");
        out.append("| Equivalence width at that level | ").append(format(width)).append(" px |\n");
        out.append("| Recipes retained by the two-source rule | ").append(retained).append(" of ")
                .append(recipes.size()).append(" |\n\n");

        out.append("## Ten best fixed recipes\n\n");
        out.append("| Recipe | Support | Band | Filter | Mask | Mean median (px) | Mean p90 (px) |")
                .append(" Mean s | Oracle wins | Independent sources |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < Math.min(10, ranked.size()); i++) {
            FullSelectorFactorialBenchmark.Recipe recipe = ranked.get(i);
            Aggregate aggregate = overall.get(recipe.id);
            out.append("| `").append(recipe.id).append("` | ").append(recipe.support.name())
                    .append(" | ").append(recipe.band.id).append(" | ").append(recipe.filter.name())
                    .append(" | ").append(recipe.mask.id).append(" | ")
                    .append(format(aggregate.meanMedian())).append(" | ")
                    .append(format(aggregate.meanP90())).append(" | ")
                    .append(format(aggregate.meanSeconds())).append(" | ")
                    .append(recordingWins.getOrDefault(recipe.id, 0)).append(" | ")
                    .append(independentWins.getOrDefault(recipe.id, 0)).append(" |\n");
        }
        out.append('\n');

        out.append("## Per-image-type oracle headroom\n\n");
        out.append("The oracle is the best recipe chosen per recording after seeing its own answer.")
                .append(" It is an unattainable upper bound and is never a production route.\n\n");
        out.append("| Image type | Category mean median (px) | Oracle mean median (px) |")
                .append(" Headroom (px) |\n|---|---|---|---|\n");
        Map<String, double[]> perClass = new TreeMap<>();
        Map<String, List<Row>> byRecording = new LinkedHashMap<>();
        for (Row row : rows) {
            byRecording.computeIfAbsent(row.recording.key(), key -> new ArrayList<>()).add(row);
        }
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            double oracle = Double.POSITIVE_INFINITY;
            for (Row row : byRecording.getOrDefault(recording.key(), new ArrayList<>())) {
                if (row.ok()) oracle = Math.min(oracle, row.median);
            }
            double[] totals = perClass.computeIfAbsent(recording.imageClass,
                    key -> new double[3]);
            totals[0] += baselines.get(recording.key()).median;
            totals[1] += oracle;
            totals[2] += 1;
        }
        for (Map.Entry<String, double[]> entry : perClass.entrySet()) {
            double[] totals = entry.getValue();
            out.append("| ").append(entry.getKey()).append(" | ")
                    .append(format(totals[0] / totals[2])).append(" | ")
                    .append(format(totals[1] / totals[2])).append(" | ")
                    .append(format((totals[0] - totals[1]) / totals[2])).append(" |\n");
        }
        out.append('\n');

        out.append("## Reconstructing every decision\n\n");
        out.append("- `all_recipes.csv` holds one row per recording and recipe with both errors, ")
                .append("both timings and the category comparison.\n");
        out.append("- `recipe_summary.csv` holds the balanced per-recipe aggregate and the ")
                .append("two-source retention flag.\n");
        out.append("- `cell_summary.csv` holds the same aggregate by image type, motion type and ")
                .append("image-by-motion cell, with the scope winner marked.\n");
        out.append("- `oracle_by_recording.csv` holds the per-recording oracle, its equivalence ")
                .append("width and every recipe inside that width.\n");
        out.append("- `manifest.csv` and `recipe_manifest.csv` fix the inputs, checksums, ")
                .append("environment and candidate definitions.\n");
        out.append("- `artifact_audit.csv` records the presence of every expected artifact.\n\n");
        out.append("Every number above is recomputable from `all_recipes.csv` using the rule ")
                .append("stated in this file. No result influenced the rule.\n");

        Files.write(summary.resolve("FINDINGS.md"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- support types

    private static Baseline baseline(FullSelectorFactorialBenchmark.Recording recording)
            throws IOException {
        Path file = recording.folder.resolve(CATEGORY_ARM).resolve("comparison.csv");
        if (!Files.isRegularFile(file)) {
            throw new IOException("missing frozen category recommendation result " + file);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < 2) throw new IOException("empty category recommendation result " + file);
        List<String> fields = FullSelectorFactorialBenchmark.fields(lines.get(1));
        return new Baseline(Double.parseDouble(fields.get(9)), Double.parseDouble(fields.get(10)),
                Double.parseDouble(fields.get(12)));
    }

    private static final class Baseline {
        final double median;
        final double p90;
        final double seconds;

        Baseline(double median, double p90, double seconds) {
            this.median = median;
            this.p90 = p90;
            this.seconds = seconds;
        }
    }

    static final class Row {
        final FullSelectorFactorialBenchmark.Recording recording;
        final FullSelectorFactorialBenchmark.Recipe recipe;
        final String status;
        final double median;
        final double p90;
        final double max;
        final double seconds;
        final double cpuSeconds;

        Row(FullSelectorFactorialBenchmark.Recording recording,
            FullSelectorFactorialBenchmark.Recipe recipe, String status, double median, double p90,
            double max, double seconds, double cpuSeconds) {
            this.recording = recording;
            this.recipe = recipe;
            this.status = status;
            this.median = median;
            this.p90 = p90;
            this.max = max;
            this.seconds = seconds;
            this.cpuSeconds = cpuSeconds;
        }

        boolean ok() {
            return "ok".equals(status) && Double.isFinite(median);
        }

        static Row parse(String line, FullSelectorFactorialBenchmark.Recording recording,
                         FullSelectorFactorialBenchmark.Recipe recipe) {
            List<String> fields = FullSelectorFactorialBenchmark.fields(line);
            return new Row(recording, recipe, fields.get(10), number(fields.get(12)),
                    number(fields.get(13)), number(fields.get(14)), number(fields.get(15)),
                    number(fields.get(16)));
        }

        private static double number(String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException error) {
                return Double.NaN;
            }
        }
    }

    static final class Aggregate {
        int count;
        int failures;
        double medianTotal;
        double p90Total;
        double secondsTotal;
        double cpuTotal;
        double maximum;
        final List<Double> medians = new ArrayList<>();

        void add(Row row) {
            count++;
            if (!row.ok()) {
                failures++;
                return;
            }
            medianTotal += row.median;
            p90Total += row.p90;
            secondsTotal += row.seconds;
            cpuTotal += row.cpuSeconds;
            maximum = Math.max(maximum, row.max);
            medians.add(row.median);
        }

        int scored() {
            return count - failures;
        }

        double meanMedian() {
            return scored() == 0 ? Double.POSITIVE_INFINITY : medianTotal / scored();
        }

        double medianOfMedian() {
            if (medians.isEmpty()) return Double.NaN;
            List<Double> sorted = new ArrayList<>(medians);
            sorted.sort(Double::compare);
            int size = sorted.size();
            return size % 2 == 1 ? sorted.get(size / 2)
                    : 0.5 * (sorted.get(size / 2 - 1) + sorted.get(size / 2));
        }

        double meanP90() {
            return scored() == 0 ? Double.NaN : p90Total / scored();
        }

        double meanSeconds() {
            return scored() == 0 ? Double.NaN : secondsTotal / scored();
        }

        double meanCpuSeconds() {
            return scored() == 0 ? Double.NaN : cpuTotal / scored();
        }
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }
}
