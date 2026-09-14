/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.ImageType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Truth-only oracle analyzer; it never reads or writes the production feature table. */
public final class RecordingAdaptiveSelectorHeadroom {
    static final double OVERALL_ABSOLUTE_GATE = 0.005;
    static final double OVERALL_RELATIVE_GATE = 0.15;
    static final double TYPE_ABSOLUTE_GATE = 0.003;
    static final double TYPE_RELATIVE_GATE = 0.10;
    static final String SHIPPED_RECIPE = "estimator_area_correlation_newton__support_all__"
            + "band_full__filter_gaussian_0_7__mask_none";

    private RecordingAdaptiveSelectorHeadroom() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        analyze(project);
    }

    static void analyze(Path project) throws Exception {
        Path root = project.resolve("library/recording_adaptive_selector_v1");
        Path matrix = root.resolve("outcomes/development.csv");
        Map<String, CaseOutcomes> cases = read(matrix);
        Map<String, String> fullFixed = selectFullFixed(cases);
        Map<String, Outcome> categoryPolicy = policy(cases, "category");
        Map<String, Outcome> shippedPolicy = policy(cases, "shipped");
        Map<String, Outcome> fixedCvPolicy = fixedCrossValidatedPolicy(cases);
        Map<String, Map<String, Outcome>> policies = new LinkedHashMap<>();
        policies.put("complete_category", categoryPolicy);
        policies.put("shipped_image_type_rule", shippedPolicy);
        policies.put("source_heldout_fixed_table", fixedCvPolicy);
        String baselineName = null;
        double baselineScore = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, Map<String, Outcome>> entry : policies.entrySet()) {
            double score = balanced(entry.getValue().values());
            if (score < baselineScore) {
                baselineScore = score;
                baselineName = entry.getKey();
            }
        }
        Map<String, Outcome> baseline = policies.get(baselineName);
        Map<String, Outcome> oracle = new LinkedHashMap<>();
        Map<String, Integer> winnerGroups = new LinkedHashMap<>();
        Map<String, Set<String>> typeWinnerRecipes = new LinkedHashMap<>();
        Map<String, Set<String>> typeWinnerGroups = new LinkedHashMap<>();
        for (CaseOutcomes c : cases.values()) {
            Outcome base = baseline.get(c.id);
            Outcome best = base;
            for (Outcome candidate : c.byRecipe.values()) {
                if (!safe(candidate, base)) continue;
                double width = Math.max(0.001, 0.05 * Math.min(candidate.median, best.median));
                if (candidate.median < best.median - width
                        || (Math.abs(candidate.median - best.median) <= width
                        && prefer(candidate, best) < 0)) best = candidate;
            }
            oracle.put(c.id, best);
            String winnerGroup = best.recipe + "|" + c.group;
            winnerGroups.put(winnerGroup, winnerGroups.getOrDefault(winnerGroup, 0) + 1);
            typeWinnerRecipes.computeIfAbsent(c.imageType, ignored -> new LinkedHashSet<>())
                    .add(best.recipe);
            if (meaningfulGain(best, base)) {
                typeWinnerGroups.computeIfAbsent(c.imageType, ignored -> new LinkedHashSet<>())
                        .add(c.group);
            }
        }

        List<Scope> scopes = new ArrayList<>();
        scopes.add(scope("overall", baselineName, baseline, oracle, null,
                OVERALL_ABSOLUTE_GATE, OVERALL_RELATIVE_GATE));
        List<String> adaptiveTypes = new ArrayList<>();
        for (ImageType type : ImageType.values()) {
            Scope scope = scope("image_type=" + type.name(), baselineName, baseline, oracle,
                    type.name(), TYPE_ABSOLUTE_GATE, TYPE_RELATIVE_GATE);
            int recipes = typeWinnerRecipes.getOrDefault(type.name(), Collections.emptySet()).size();
            int groups = typeWinnerGroups.getOrDefault(type.name(), Collections.emptySet()).size();
            scope.gatePass = scope.gatePass && recipes >= 2 && groups >= 3;
            scopes.add(scope);
            if (scope.gatePass) adaptiveTypes.add(type.name());
        }
        boolean overallPass = scopes.get(0).gatePass;
        String decision = overallPass && !adaptiveTypes.isEmpty()
                ? "ADAPTIVE_ALLOWED" : "FIXED_POLICY";

        StringBuilder table = new StringBuilder("scope,independent_groups,recordings,"
                + "baseline_policy,baseline_primary_error,oracle_primary_error,absolute_headroom,"
                + "relative_headroom,oracle_failure_count,absolute_gate,relative_gate,gate_pass\n");
        for (Scope scope : scopes) table.append(scope.row()).append('\n');
        Files.write(root.resolve("headroom.csv"), table.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder winners = new StringBuilder("recipe_id,independent_group,recordings\n");
        for (Map.Entry<String, Integer> entry : winnerGroups.entrySet()) {
            int split = entry.getKey().lastIndexOf('|');
            winners.append(csv(entry.getKey().substring(0, split))).append(',')
                    .append(csv(entry.getKey().substring(split + 1))).append(',')
                    .append(entry.getValue()).append('\n');
        }
        Files.write(root.resolve("oracle_winner_dispersion.csv"),
                winners.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder fixed = new StringBuilder();
        for (Map.Entry<String, String> entry : fullFixed.entrySet()) {
            if (fixed.length() > 0) fixed.append(';');
            fixed.append(entry.getKey()).append('=').append(entry.getValue());
        }
        String properties = "decision=" + decision + '\n'
                + "adaptive_image_types=" + String.join(",", adaptiveTypes) + '\n'
                + "protocol_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/selector_protocol.md")) + '\n'
                + "candidate_manifest_sha256=2d482270049113410b25ced202f41f852afc0c4a50f4fd684bb20b748438bc3c\n"
                + "outcome_matrix_sha256=" + sha256(matrix) + '\n'
                + "primary_metric=source-balanced median central-50% general warping index px\n"
                + "baseline_policy=" + baselineName + '\n'
                + "measured_headroom=" + precise(scopes.get(0).absolute) + '\n'
                + "measured_relative_headroom=" + precise(scopes.get(0).relative) + '\n'
                + "required_headroom=0.005 px and 15%\n"
                + "fixed_policy_id=" + fixed + '\n';
        Files.write(root.resolve("headroom_decision.properties"),
                properties.getBytes(StandardCharsets.UTF_8));
        System.out.println(properties);
    }

    static Map<String, CaseOutcomes> read(Path matrix) throws IOException {
        List<String> lines = Files.readAllLines(matrix, StandardCharsets.UTF_8);
        Map<String, CaseOutcomes> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> r = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (r.size() < 32) throw new IOException("bad outcome row " + (i + 1));
            CaseOutcomes c = out.computeIfAbsent(r.get(0), ignored ->
                    new CaseOutcomes(r.get(0), r.get(2), r.get(4)));
            Outcome value = new Outcome(r.get(0), r.get(2), r.get(4), r.get(7),
                    "ok".equals(r.get(9)), number(r.get(11)), number(r.get(12)),
                    number(r.get(13)), number(r.get(17)), number(r.get(18)),
                    number(r.get(19)), number(r.get(20)), number(r.get(21)),
                    diagnosticCounts(r), number(r.get(29)), number(r.get(30)));
            c.byRecipe.put(value.recipe, value);
        }
        return out;
    }

    private static Map<String, Outcome> policy(Map<String, CaseOutcomes> cases, String kind) {
        Map<String, Outcome> out = new LinkedHashMap<>();
        for (CaseOutcomes c : cases.values()) {
            Outcome category = c.byRecipe.get(RecordingAdaptiveSelectorBenchmark.CATEGORY);
            Outcome selected = category;
            if ("shipped".equals(kind) && ("BRIGHTFIELD_DIC".equals(c.imageType)
                    || "FIDUCIAL_STATIC".equals(c.imageType))) {
                Outcome candidate = c.byRecipe.get(SHIPPED_RECIPE);
                if (safe(candidate, category)) selected = candidate;
            }
            out.put(c.id, selected);
        }
        return out;
    }

    static Map<String, Outcome> fixedCrossValidatedPolicy(
            Map<String, CaseOutcomes> cases) {
        Map<String, Outcome> out = new LinkedHashMap<>();
        Set<String> groups = new LinkedHashSet<>();
        for (CaseOutcomes c : cases.values()) groups.add(c.group);
        for (String held : groups) {
            Map<String, String> fitted = selectFixed(cases, held);
            for (CaseOutcomes c : cases.values()) {
                if (!held.equals(c.group)) continue;
                Outcome category = c.byRecipe.get(RecordingAdaptiveSelectorBenchmark.CATEGORY);
                Outcome candidate = c.byRecipe.get(fitted.get(c.imageType));
                out.put(c.id, safe(candidate, category) ? candidate : category);
            }
        }
        return out;
    }

    static Map<String, String> selectFullFixed(Map<String, CaseOutcomes> cases) {
        return selectFixed(cases, null);
    }

    static Map<String, String> selectFixed(Map<String, CaseOutcomes> cases,
                                                    String excludedGroup) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ImageType type : ImageType.values()) {
            Set<String> ids = new LinkedHashSet<>();
            for (CaseOutcomes c : cases.values()) {
                if (type.name().equals(c.imageType)) ids.addAll(c.byRecipe.keySet());
            }
            String best = RecordingAdaptiveSelectorBenchmark.CATEGORY;
            double bestScore = Double.POSITIVE_INFINITY;
            for (String id : ids) {
                List<Outcome> values = new ArrayList<>();
                boolean safe = true;
                for (CaseOutcomes c : cases.values()) {
                    if (!type.name().equals(c.imageType)
                            || (excludedGroup != null && excludedGroup.equals(c.group))) continue;
                    Outcome category = c.byRecipe.get(RecordingAdaptiveSelectorBenchmark.CATEGORY);
                    Outcome candidate = c.byRecipe.get(id);
                    if (!safe(candidate, category)) { safe = false; break; }
                    values.add(candidate);
                }
                if (!safe || values.isEmpty()) continue;
                double score = balanced(values);
                if (score < bestScore) { bestScore = score; best = id; }
            }
            out.put(type.name(), best);
        }
        return out;
    }

    static boolean safe(Outcome candidate, Outcome baseline) {
        if (candidate == null || baseline == null || !candidate.ok || !baseline.ok
                || !Double.isFinite(candidate.median) || !Double.isFinite(baseline.median)
                || !Double.isFinite(candidate.p90) || !Double.isFinite(baseline.p90)
                || !Double.isFinite(candidate.worst) || !Double.isFinite(baseline.worst)
                || !Double.isFinite(candidate.crop) || !Double.isFinite(baseline.crop)
                || !Double.isFinite(candidate.runtime) || !Double.isFinite(baseline.runtime)) {
            return false;
        }
        if (diagnosticRegression(candidate, baseline)) return false;
        if (candidate.crop < baseline.crop - 0.02) return false;
        return !(candidate.median > 2 * baseline.median
                && candidate.median > baseline.median + 0.05);
    }

    private static int prefer(Outcome a, Outcome b) {
        int cmp = Integer.compare(a.diagnostics, b.diagnostics);
        if (cmp != 0) return cmp;
        cmp = Double.compare(a.p90, b.p90);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(changes(a.recipe), changes(b.recipe));
        if (cmp != 0) return cmp;
        cmp = Double.compare(a.runtime, b.runtime);
        return cmp != 0 ? cmp : a.recipe.compareTo(b.recipe);
    }

    private static int changes(String id) {
        if (RecordingAdaptiveSelectorBenchmark.CATEGORY.equals(id)) return 0;
        int changes = 0;
        if (!id.contains("support_all")) changes++;
        if (!id.contains("band_full")) changes++;
        if (!id.contains("filter_none")) changes++;
        if (!id.contains("mask_none")) changes++;
        if (id.startsWith("estimator_")) changes++;
        return changes;
    }

    static double balanced(Iterable<Outcome> outcomes) {
        Map<String, List<Double>> byGroup = new LinkedHashMap<>();
        for (Outcome o : outcomes) {
            if (o == null || !o.ok || !Double.isFinite(o.median)) return Double.POSITIVE_INFINITY;
            byGroup.computeIfAbsent(o.group, ignored -> new ArrayList<>()).add(o.median);
        }
        List<Double> summaries = new ArrayList<>();
        for (List<Double> values : byGroup.values()) summaries.add(median(values));
        return median(summaries);
    }

    private static Scope scope(String name, String baselineName,
            Map<String, Outcome> baseline, Map<String, Outcome> oracle, String type,
            double absoluteGate, double relativeGate) {
        List<Outcome> b = new ArrayList<>();
        List<Outcome> o = new ArrayList<>();
        Set<String> groups = new LinkedHashSet<>();
        int failures = 0;
        for (Map.Entry<String, Outcome> entry : baseline.entrySet()) {
            if (type != null && !type.equals(entry.getValue().imageType)) continue;
            b.add(entry.getValue());
            Outcome chosen = oracle.get(entry.getKey());
            o.add(chosen);
            groups.add(entry.getValue().group);
            if (chosen == null || !chosen.ok) failures++;
        }
        double base = balanced(b);
        double best = balanced(o);
        double absolute = base - best;
        double relative = base > 0 ? absolute / base : 0;
        return new Scope(name, groups.size(), b.size(), baselineName, base, best, absolute,
                relative, failures, absoluteGate, relativeGate,
                absolute >= absoluteGate && relative >= relativeGate && failures == 0);
    }

    private static int[] diagnosticCounts(List<String> r) {
        int[] out = new int[7];
        for (int i = 0; i < out.length; i++) out[i] = Integer.parseInt(r.get(22 + i));
        return out;
    }

    static boolean diagnosticRegression(Outcome candidate, Outcome baseline) {
        for (int i = 0; i < candidate.diagnosticCounts.length; i++) {
            if (candidate.diagnosticCounts[i] > baseline.diagnosticCounts[i]) return true;
        }
        return false;
    }

    static boolean meaningfulGain(Outcome candidate, Outcome baseline) {
        if (candidate == null || baseline == null || candidate == baseline) return false;
        double width = Math.max(0.001, 0.05 * Math.min(candidate.median, baseline.median));
        return candidate.median < baseline.median - width;
    }

    private static double number(String raw) {
        try { return Double.parseDouble(raw); }
        catch (RuntimeException ignored) { return Double.NaN; }
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        return (sorted.size() & 1) == 1 ? sorted.get(middle)
                : 0.5 * (sorted.get(middle - 1) + sorted.get(middle));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String precise(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "NaN";
    }

    static final class CaseOutcomes {
        final String id, imageType, group;
        final Map<String, Outcome> byRecipe = new LinkedHashMap<>();
        CaseOutcomes(String id, String imageType, String group) {
            this.id = id; this.imageType = imageType; this.group = group;
        }
    }

    static final class Outcome {
        final String caseId, imageType, group, recipe;
        final boolean ok;
        final double median, p90, worst;
        final double medianAngle, p90Angle, worstAngle;
        final double medianTranslation, worstTranslation;
        final int diagnostics;
        final int[] diagnosticCounts;
        final double crop, runtime;
        Outcome(String caseId, String imageType, String group, String recipe, boolean ok,
                double median, double p90, double worst, int diagnostics,
                double crop, double runtime) {
            this(caseId, imageType, group, recipe, ok, median, p90, worst,
                    new int[]{diagnostics, 0, 0, 0, 0, 0, 0}, crop, runtime);
        }
        Outcome(String caseId, String imageType, String group, String recipe, boolean ok,
                double median, double p90, double worst, int[] diagnosticCounts,
                double crop, double runtime) {
            this(caseId, imageType, group, recipe, ok, median, p90, worst,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    diagnosticCounts, crop, runtime);
        }
        Outcome(String caseId, String imageType, String group, String recipe, boolean ok,
                double median, double p90, double worst, double medianAngle,
                double p90Angle, double worstAngle, double medianTranslation,
                double worstTranslation, int[] diagnosticCounts,
                double crop, double runtime) {
            this.caseId = caseId; this.imageType = imageType; this.group = group;
            this.recipe = recipe; this.ok = ok; this.median = median; this.p90 = p90;
            this.medianAngle = medianAngle; this.p90Angle = p90Angle;
            this.worstAngle = worstAngle; this.medianTranslation = medianTranslation;
            this.worstTranslation = worstTranslation;
            this.worst = worst; this.diagnosticCounts = diagnosticCounts.clone();
            int total = 0;
            for (int value : diagnosticCounts) total += value;
            this.diagnostics = total; this.crop = crop;
            this.runtime = runtime;
        }
    }

    static final class Scope {
        final String name, baseline;
        final int groups, recordings, failures;
        final double baselineError, oracleError, absolute, relative, absoluteGate, relativeGate;
        boolean gatePass;
        Scope(String name, int groups, int recordings, String baseline, double baselineError,
              double oracleError, double absolute, double relative, int failures,
              double absoluteGate, double relativeGate, boolean gatePass) {
            this.name = name; this.groups = groups; this.recordings = recordings;
            this.baseline = baseline; this.baselineError = baselineError;
            this.oracleError = oracleError; this.absolute = absolute; this.relative = relative;
            this.failures = failures; this.absoluteGate = absoluteGate;
            this.relativeGate = relativeGate; this.gatePass = gatePass;
        }
        String row() {
            return csv(name) + ',' + groups + ',' + recordings + ',' + csv(baseline) + ','
                    + precise(baselineError) + ',' + precise(oracleError) + ','
                    + precise(absolute) + ',' + precise(relative) + ',' + failures + ','
                    + precise(absoluteGate) + ',' + precise(relativeGate) + ',' + gatePass;
        }
    }
}
