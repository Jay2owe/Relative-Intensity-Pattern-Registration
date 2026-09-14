/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.AutomaticRegistrationSelector;
import ripr.api.AutomaticRegistrationSelectorModel;
import ripr.api.ImageType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** One-opening final evaluator for recording_adaptive_selector_v1. */
public final class RecordingAdaptiveSelectorFinalReport {
    static final String REPORT_CLASS = "RecordingAdaptiveSelectorFinalReport";
    static final String GATE_TEST_CLASS = "RecordingAdaptiveSelectorFinalGatesTest";
    static final String[] DIAGNOSTIC_NAMES = {
            "refused_pairs", "non_converged_pairs", "shift_bound_pairs",
            "rotation_bound_pairs", "both_bound_pairs", "repaired_frames",
            "unsupported_frames"
    };

    private RecordingAdaptiveSelectorFinalReport() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: <freeze|open|report> [project]");
        }
        Path project = (args.length == 2 ? Paths.get(args[1]) : Paths.get(""))
                .toAbsolutePath().normalize();
        if ("freeze".equals(args[0])) freeze(project);
        else if ("open".equals(args[0])) open(project);
        else if ("report".equals(args[0])) report(project);
        else throw new IllegalArgumentException("unknown command " + args[0]);
    }

    /** Freeze every identity, hash and numeric gate before any final outcome exists. */
    static void freeze(Path project) throws Exception {
        Path root = artifactRoot(project);
        Path finalRoot = root.resolve("final");
        Path freeze = finalRoot.resolve("freeze_record.properties");
        if (Files.exists(freeze)) throw new IOException("final set is already frozen: " + freeze);
        assertNoFinalOutcomes(root);
        verifyFinalSources(project);

        Path input = root.resolve("manifests/input_manifest_final.csv");
        Path features = root.resolve("features/final.csv");
        requireRows(input, 110, "final input manifest");
        requireRows(features, 110, "final feature table");
        RecordingAdaptiveSelectorTraining.readFeatures(features);

        Path generatedJava = root.resolve(
                "training/generated/AutomaticRegistrationSelectorModel.java");
        Path generatedPython = root.resolve("training/generated/recording_selector_model.py");
        Path installedJava = project.resolve(
                "src/main/java/ripr/api/AutomaticRegistrationSelectorModel.java");
        Path installedPython = project.resolve("src/ripr/recording_selector_model.py");
        requireSameHash(generatedJava, installedJava, "generated and installed Java models");
        requireSameHash(generatedPython, installedPython, "generated and installed Python models");

        Path recipeRequest = root.resolve("training/validation_recipe_request.txt");
        List<String> recipes = nonEmptyLines(recipeRequest);
        String recipeProperty = recipes.isEmpty() ? "__none__" : String.join(",", recipes);
        String text = "run_id=recording_adaptive_selector_v1\n"
                + "protocol_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/selector_protocol.md")) + '\n'
                + "source_split_manifest_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/source_split_manifest.csv")) + '\n'
                + "final_input_manifest_sha256=" + sha256(input) + '\n'
                + "final_features_sha256=" + sha256(features) + '\n'
                + "feature_contract_version="
                + AutomaticRegistrationSelector.FEATURE_CONTRACT_VERSION + '\n'
                + "candidate_manifest_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/candidate_manifest.csv")) + '\n'
                + "java_model_source_sha256=" + sha256(installedJava) + '\n'
                + "python_model_source_sha256=" + sha256(installedPython) + '\n'
                + "candidate_model_version="
                + AutomaticRegistrationSelectorModel.MODEL_VERSION + '\n'
                + "candidate_model_artifact_sha256="
                + AutomaticRegistrationSelectorModel.MODEL_ARTIFACT_SHA256 + '\n'
                + "baseline_policy_id=complete_category_recommendation\n"
                + "fixed_policy_sha256=" + sha256(root.resolve(
                        "training/fixed_policy.csv")) + '\n'
                + "final_report_source_sha256=" + sha256(project.resolve(
                        "src/test/java/ripr/RecordingAdaptiveSelectorFinalReport.java")) + '\n'
                + "final_gate_test_source_sha256=" + sha256(project.resolve(
                        "src/test/java/ripr/RecordingAdaptiveSelectorFinalGatesTest.java")) + '\n'
                + "primary_gate=max(0.001 px,5 percent)\n"
                + "image_type_median_regression_limit_px=0.002\n"
                + "p90_regression_limit_px=0.005\n"
                + "worst_regression_limit_px=0.05\n"
                + "recording_guard=not both 2x and +0.05 px\n"
                + "crop_fraction_loss_limit=0.02\n"
                + "runtime_ratio_limit=1.50\n"
                + "diagnostic_regression_limit=0 for each diagnostic\n"
                + "report_class=" + REPORT_CLASS + '\n'
                + "gate_test_class=" + GATE_TEST_CLASS + '\n'
                + "recipe_request=" + recipeProperty + '\n'
                + "planned_features_command=RecordingAdaptiveSelectorBenchmark features <project> final\n"
                + "planned_open_command=" + REPORT_CLASS + " open <project>\n"
                + "planned_outcome_command=RecordingAdaptiveSelectorBenchmark run <project> final;"
                + " adaptive.onlyImageType=<each type>; adaptive.onlyRecipe="
                + recipeProperty + '\n'
                + "planned_report_command=" + REPORT_CLASS + " report <project>\n"
                + "frozen_at_utc=" + Instant.now() + '\n'
                + "opened=false\n";
        Files.createDirectories(finalRoot);
        writeNew(freeze, text);
        System.out.println(text);
    }

    /** Record the single authorized opening. This must run immediately before outcome execution. */
    static void open(Path project) throws Exception {
        Path root = artifactRoot(project);
        Path finalRoot = root.resolve("final");
        Path freeze = finalRoot.resolve("freeze_record.properties");
        Path opening = finalRoot.resolve("opening_record.properties");
        if (!Files.isRegularFile(freeze)) throw new IOException("missing final freeze record");
        if (Files.exists(opening)) throw new IOException("final set has already been opened");
        assertNoFinalOutcomes(root);
        verifyFrozenHashes(project, load(freeze));
        String text = "run_id=recording_adaptive_selector_v1\n"
                + "freeze_record_sha256=" + sha256(freeze) + '\n'
                + "opened=true\nopen_count=1\nopened_at_utc=" + Instant.now() + '\n';
        writeNew(opening, text);
        System.out.println(text);
    }

    /** Apply the frozen gates to complete category, fixed and candidate arms. */
    static void report(Path project) throws Exception {
        Path root = artifactRoot(project);
        Path finalRoot = root.resolve("final");
        Path freezePath = finalRoot.resolve("freeze_record.properties");
        Path openingPath = finalRoot.resolve("opening_record.properties");
        Path reportPath = finalRoot.resolve("gate_report.properties");
        if (Files.exists(reportPath)) throw new IOException("final report already exists");
        Properties freeze = load(freezePath);
        Properties opening = load(openingPath);
        if (!"true".equals(opening.getProperty("opened"))
                || !"1".equals(opening.getProperty("open_count"))
                || !sha256(freezePath).equals(opening.getProperty("freeze_record_sha256"))) {
            throw new IOException("invalid final opening record");
        }
        verifyFrozenHashes(project, freeze);

        Path outcomesPath = root.resolve("outcomes/final.csv");
        Path featuresPath = root.resolve("features/final.csv");
        Path timingsPath = root.resolve("timings/final.csv");
        Map<String, RecordingAdaptiveSelectorHeadroom.CaseOutcomes> outcomes =
                RecordingAdaptiveSelectorHeadroom.read(outcomesPath);
        Map<String, RecordingAdaptiveSelectorTraining.Feature> features =
                RecordingAdaptiveSelectorTraining.readFeatures(featuresPath);
        List<RecordingAdaptiveSelectorTraining.Row> rows =
                RecordingAdaptiveSelectorTraining.join(outcomes, features, false);
        if (rows.size() != 110) throw new IOException("final case count is not 110");

        Path training = root.resolve("training");
        RecordingAdaptiveSelectorTraining.Model candidate =
                RecordingAdaptiveSelectorTraining.readModel(training, "promoted_model");
        Map<String, String> fixedPolicy = RecordingAdaptiveSelectorTraining.readFixedPolicy(
                training.resolve("fixed_policy.csv"));
        Map<String, Double> selectorTimes = "linear_gain".equals(candidate.kind)
                ? RecordingAdaptiveSelectorTraining.readSelectorTimes(timingsPath)
                : new LinkedHashMap<>();

        Set<String> requestedRecipes = new LinkedHashSet<>(nonEmptyLines(
                training.resolve("validation_recipe_request.txt")));
        int expectedOutcomeRows = rows.size() * (requestedRecipes.size() + 1);
        requireRows(outcomesPath, expectedOutcomeRows, "final outcome table");

        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> category = new LinkedHashMap<>();
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixed = new LinkedHashMap<>();
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = new LinkedHashMap<>();
        for (RecordingAdaptiveSelectorTraining.Row row : rows) {
            RecordingAdaptiveSelectorHeadroom.Outcome complete =
                    RecordingAdaptiveSelectorTraining.category(row);
            if (complete == null) throw new IOException("missing category arm " + row.feature.caseId);
            category.put(row.feature.caseId, complete);
            String fixedRecipe = fixedPolicy.get(row.feature.imageType);
            RecordingAdaptiveSelectorHeadroom.Outcome fixedOutcome =
                    row.outcomes.byRecipe.get(fixedRecipe);
            if (fixedOutcome == null) fixedOutcome = complete;
            fixed.put(row.feature.caseId, fixedOutcome);
            RecordingAdaptiveSelectorHeadroom.Outcome chosen =
                    RecordingAdaptiveSelectorTraining.chosenOutcome(row, candidate);
            if ("linear_gain".equals(candidate.kind)) {
                Double overhead = selectorTimes.get(row.feature.caseId);
                if (overhead == null || !Double.isFinite(overhead)) {
                    throw new IOException("missing complete selector timing " + row.feature.caseId);
                }
                chosen = RecordingAdaptiveSelectorTraining.withRuntime(
                        chosen, chosen.runtime + overhead);
            }
            selected.put(row.feature.caseId, chosen);
        }

        GateResult adaptive = evaluate("adaptive_candidate", selected, fixed);
        GateResult fixedGate = evaluate("frozen_fixed_policy", fixed, category);
        String decision = "linear_gain".equals(candidate.kind) && adaptive.pass
                ? "ADAPTIVE" : fixedGate.safeFallback ? "FIXED" : "CATEGORY_RECOMMENDATION";
        String text = "run_id=recording_adaptive_selector_v1\n"
                + "final_policy=" + decision + '\n'
                + "opened_once=true\n"
                + "freeze_record_sha256=" + sha256(freezePath) + '\n'
                + "opening_record_sha256=" + sha256(openingPath) + '\n'
                + "final_features_sha256=" + sha256(featuresPath) + '\n'
                + "final_timings_sha256=" + sha256(timingsPath) + '\n'
                + "final_outcomes_sha256=" + sha256(outcomesPath) + '\n'
                + "candidate_model_version="
                + AutomaticRegistrationSelectorModel.MODEL_VERSION + '\n'
                + "candidate_model_artifact_sha256="
                + AutomaticRegistrationSelectorModel.MODEL_ARTIFACT_SHA256 + '\n'
                + adaptive.properties("adaptive") + fixedGate.properties("fixed");
        writeNew(reportPath, text);
        writeNew(finalRoot.resolve("paired_policy_rows.csv"),
                pairedRows(rows, category, fixed, selected));
        writeNew(finalRoot.resolve("scope_summary.csv"),
                scopeSummary(rows, category, fixed, selected));
        System.out.println(text);
    }

    static GateResult evaluate(String name,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> baseline) {
        RecordingAdaptiveSelectorTraining.Validation validation =
                RecordingAdaptiveSelectorTraining.validation(name, selected, baseline);
        boolean status = true;
        boolean[] diagnostics = new boolean[DIAGNOSTIC_NAMES.length];
        java.util.Arrays.fill(diagnostics, true);
        for (Map.Entry<String, RecordingAdaptiveSelectorHeadroom.Outcome> entry
                : baseline.entrySet()) {
            RecordingAdaptiveSelectorHeadroom.Outcome b = entry.getValue();
            RecordingAdaptiveSelectorHeadroom.Outcome a = selected.get(entry.getKey());
            if (a == null || (b != null && b.ok && !a.ok)) status = false;
            if (a == null || b == null) {
                java.util.Arrays.fill(diagnostics, false);
                continue;
            }
            for (int i = 0; i < diagnostics.length; i++) {
                if (a.diagnosticCounts[i] > b.diagnosticCounts[i]) diagnostics[i] = false;
            }
        }
        boolean diagnosticsPass = true;
        for (boolean value : diagnostics) diagnosticsPass &= value;
        return new GateResult(validation, status, diagnostics,
                validation.pass && status && diagnosticsPass,
                validation.safeFallback && status && diagnosticsPass);
    }

    private static String pairedRows(List<RecordingAdaptiveSelectorTraining.Row> rows,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> category,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixed,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected) {
        StringBuilder out = new StringBuilder("arm,case_id,independent_group,image_type,"
                + "declared_motion_type,condition,recipe_id,status,median_warp_px,p90_warp_px,"
                + "worst_warp_px,refused_pairs,non_converged_pairs,shift_bound_pairs,"
                + "rotation_bound_pairs,both_bound_pairs,repaired_frames,unsupported_frames,"
                + "median_angle_error_degrees,p90_angle_error_degrees,"
                + "worst_angle_error_degrees,median_translation_error_px,"
                + "worst_translation_error_px,retained_crop_fraction,complete_runtime_seconds\n");
        for (RecordingAdaptiveSelectorTraining.Row row : rows) {
            appendArm(out, "category", row, category.get(row.feature.caseId));
            appendArm(out, "fixed", row, fixed.get(row.feature.caseId));
            appendArm(out, "candidate", row, selected.get(row.feature.caseId));
        }
        return out.toString();
    }

    private static void appendArm(StringBuilder out, String arm,
            RecordingAdaptiveSelectorTraining.Row row,
            RecordingAdaptiveSelectorHeadroom.Outcome value) {
        String[] id = row.feature.caseId.split("/", -1);
        String condition = id.length > 3 ? id[3] : "";
        out.append(arm).append(',').append(csv(row.feature.caseId)).append(',')
                .append(csv(row.feature.group)).append(',').append(row.feature.imageType)
                .append(',').append(row.feature.motionType).append(',').append(condition)
                .append(',').append(csv(value.recipe)).append(',')
                .append(value.ok ? "ok" : "failed").append(',').append(fmt(value.median))
                .append(',').append(fmt(value.p90)).append(',').append(fmt(value.worst));
        for (int count : value.diagnosticCounts) out.append(',').append(count);
        out.append(',').append(fmt(value.medianAngle)).append(',')
                .append(fmt(value.p90Angle)).append(',').append(fmt(value.worstAngle))
                .append(',').append(fmt(value.medianTranslation)).append(',')
                .append(fmt(value.worstTranslation));
        out.append(',').append(fmt(value.crop)).append(',').append(fmt(value.runtime)).append('\n');
    }

    private static String scopeSummary(List<RecordingAdaptiveSelectorTraining.Row> rows,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> category,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixed,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected) {
        Map<String, Map<String, RecordingAdaptiveSelectorHeadroom.Outcome>> arms =
                new LinkedHashMap<>();
        arms.put("category", category); arms.put("fixed", fixed); arms.put("candidate", selected);
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add("overall");
        for (RecordingAdaptiveSelectorTraining.Row row : rows) {
            scopes.add("image_type=" + row.feature.imageType);
            String[] id = row.feature.caseId.split("/", -1);
            if (id.length > 3) scopes.add("condition=" + id[3]);
        }
        StringBuilder out = new StringBuilder("arm,scope,recordings,independent_groups,"
                + "primary_median_px,primary_mean_px,p90_px,worst_px,"
                + "median_angle_error_degrees,median_translation_error_px,"
                + "failures,diagnostics,candidate_coverage\n");
        for (Map.Entry<String, Map<String, RecordingAdaptiveSelectorHeadroom.Outcome>> arm
                : arms.entrySet()) for (String scope : scopes) {
            List<RecordingAdaptiveSelectorHeadroom.Outcome> values = new ArrayList<>();
            Set<String> groups = new LinkedHashSet<>();
            int failures = 0, diagnosticCount = 0, coverage = 0;
            for (RecordingAdaptiveSelectorTraining.Row row : rows) {
                if (!inScope(row, scope)) continue;
                RecordingAdaptiveSelectorHeadroom.Outcome value = arm.getValue().get(
                        row.feature.caseId);
                values.add(value); groups.add(value.group);
                if (!value.ok) failures++;
                diagnosticCount += value.diagnostics;
                if (!RecordingAdaptiveSelectorBenchmark.CATEGORY.equals(value.recipe)) coverage++;
            }
            out.append(arm.getKey()).append(',').append(csv(scope)).append(',')
                    .append(values.size()).append(',').append(groups.size()).append(',')
                    .append(fmt(balanced(values, 0))).append(',')
                    .append(fmt(balancedMean(values, 0))).append(',')
                    .append(fmt(balanced(values, 1))).append(',')
                    .append(fmt(balanced(values, 2))).append(',')
                    .append(fmt(balanced(values, 3))).append(',')
                    .append(fmt(balanced(values, 4))).append(',').append(failures).append(',')
                    .append(diagnosticCount).append(',').append(coverage).append('\n');
        }
        return out.toString();
    }

    private static boolean inScope(RecordingAdaptiveSelectorTraining.Row row, String scope) {
        if ("overall".equals(scope)) return true;
        if (scope.startsWith("image_type=")) {
            return row.feature.imageType.equals(scope.substring("image_type=".length()));
        }
        String[] id = row.feature.caseId.split("/", -1);
        return scope.startsWith("condition=") && id.length > 3
                && id[3].equals(scope.substring("condition=".length()));
    }

    private static double balanced(List<RecordingAdaptiveSelectorHeadroom.Outcome> values,
                                   int metric) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (RecordingAdaptiveSelectorHeadroom.Outcome value : values) {
            double number = metric == 1 ? value.p90 : metric == 2 ? value.worst
                    : metric == 3 ? value.medianAngle
                    : metric == 4 ? value.medianTranslation : value.median;
            if (!value.ok || !Double.isFinite(number)) return Double.NaN;
            grouped.computeIfAbsent(value.group, ignored -> new ArrayList<>()).add(number);
        }
        List<Double> summaries = new ArrayList<>();
        for (List<Double> group : grouped.values()) summaries.add(median(group));
        return median(summaries);
    }

    private static double balancedMean(List<RecordingAdaptiveSelectorHeadroom.Outcome> values,
                                       int metric) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (RecordingAdaptiveSelectorHeadroom.Outcome value : values) {
            double number = metric == 1 ? value.p90 : metric == 2 ? value.worst : value.median;
            if (!value.ok || !Double.isFinite(number)) return Double.NaN;
            grouped.computeIfAbsent(value.group, ignored -> new ArrayList<>()).add(number);
        }
        if (grouped.isEmpty()) return Double.NaN;
        double total = 0;
        for (List<Double> group : grouped.values()) total += median(group);
        return total / grouped.size();
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int m = sorted.size() / 2;
        return (sorted.size() & 1) == 1 ? sorted.get(m)
                : 0.5 * (sorted.get(m - 1) + sorted.get(m));
    }

    private static void verifyFrozenHashes(Path project, Properties frozen) throws Exception {
        Path root = artifactRoot(project);
        verifyHash(project.resolve("docs/recording-adaptive-selector/selector_protocol.md"),
                frozen.getProperty("protocol_sha256"));
        verifyHash(project.resolve("docs/recording-adaptive-selector/source_split_manifest.csv"),
                frozen.getProperty("source_split_manifest_sha256"));
        verifyHash(root.resolve("manifests/input_manifest_final.csv"),
                frozen.getProperty("final_input_manifest_sha256"));
        verifyHash(root.resolve("features/final.csv"), frozen.getProperty("final_features_sha256"));
        verifyHash(project.resolve("docs/recording-adaptive-selector/candidate_manifest.csv"),
                frozen.getProperty("candidate_manifest_sha256"));
        verifyHash(project.resolve(
                "src/main/java/ripr/api/AutomaticRegistrationSelectorModel.java"),
                frozen.getProperty("java_model_source_sha256"));
        verifyHash(project.resolve("src/ripr/recording_selector_model.py"),
                frozen.getProperty("python_model_source_sha256"));
        verifyHash(root.resolve("training/fixed_policy.csv"),
                frozen.getProperty("fixed_policy_sha256"));
        verifyHash(project.resolve(
                        "src/test/java/ripr/RecordingAdaptiveSelectorFinalReport.java"),
                frozen.getProperty("final_report_source_sha256"));
        verifyHash(project.resolve(
                        "src/test/java/ripr/RecordingAdaptiveSelectorFinalGatesTest.java"),
                frozen.getProperty("final_gate_test_source_sha256"));
        if (!AutomaticRegistrationSelectorModel.MODEL_VERSION.equals(
                frozen.getProperty("candidate_model_version"))
                || !AutomaticRegistrationSelectorModel.MODEL_ARTIFACT_SHA256.equals(
                frozen.getProperty("candidate_model_artifact_sha256"))) {
            throw new IOException("loaded selector model no longer matches freeze record");
        }
        verifyFinalSources(project);
    }

    private static void verifyFinalSources(Path project) throws Exception {
        List<String> lines = Files.readAllLines(project.resolve(
                "docs/recording-adaptive-selector/source_split_manifest.csv"),
                StandardCharsets.UTF_8);
        Map<String, String> groupPartition = new LinkedHashMap<>();
        Map<String, Integer> types = new LinkedHashMap<>();
        int finals = 0;
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            String previous = groupPartition.putIfAbsent(row.get(1), row.get(3));
            if (previous != null && !previous.equals(row.get(3))) {
                throw new IOException("independent group crosses partitions: " + row.get(1));
            }
            if (!"final".equals(row.get(3))) continue;
            finals++;
            types.put(row.get(2), types.getOrDefault(row.get(2), 0) + 1);
            verifyHash(project.resolve(row.get(4)), row.get(5));
        }
        if (finals != 10) throw new IOException("expected 10 final sources, found " + finals);
        for (ImageType type : ImageType.values()) if (types.getOrDefault(type.name(), 0) != 2) {
            throw new IOException("final source imbalance for " + type);
        }
    }

    private static void assertNoFinalOutcomes(Path root) throws IOException {
        Path folder = root.resolve("outcomes");
        if (!Files.isDirectory(folder)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "final*.csv")) {
            for (Path path : stream) if (Files.readAllLines(path, StandardCharsets.UTF_8).size() > 1) {
                throw new IOException("final outcomes already exist: " + path);
            }
        }
    }

    private static void requireRows(Path path, int expected, String label) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + label + ": " + path);
        int rows = Math.max(0, Files.readAllLines(path, StandardCharsets.UTF_8).size() - 1);
        if (rows != expected) throw new IOException(label + " rows " + rows + " != " + expected);
    }

    private static List<String> nonEmptyLines(Path path) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    private static void requireSameHash(Path a, Path b, String label) throws IOException {
        if (!Files.isRegularFile(a) || !Files.isRegularFile(b) || !sha256(a).equals(sha256(b))) {
            throw new IOException(label + " differ");
        }
    }

    private static void verifyHash(Path path, String expected) throws IOException {
        if (expected == null || !Files.isRegularFile(path) || !sha256(path).equals(expected)) {
            throw new IOException("hash mismatch for " + path);
        }
    }

    private static Properties load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + path);
        Properties out = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            out.load(reader);
        }
        return out;
    }

    private static void writeNew(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }

    private static Path artifactRoot(Path project) {
        return project.resolve("library/recording_adaptive_selector_v1");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) {
                out.append(String.format(Locale.ROOT, "%02x", value));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "NaN";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static final class GateResult {
        final RecordingAdaptiveSelectorTraining.Validation validation;
        final boolean status;
        final boolean[] diagnostics;
        final boolean pass, safeFallback;
        GateResult(RecordingAdaptiveSelectorTraining.Validation validation, boolean status,
                   boolean[] diagnostics, boolean pass, boolean safeFallback) {
            this.validation = validation; this.status = status;
            this.diagnostics = diagnostics.clone(); this.pass = pass;
            this.safeFallback = safeFallback;
        }
        String properties(String prefix) {
            StringBuilder out = new StringBuilder(validation.properties(prefix));
            out.append(prefix).append("_status_gate=").append(status).append('\n');
            for (int i = 0; i < diagnostics.length; i++) {
                out.append(prefix).append('_').append(DIAGNOSTIC_NAMES[i])
                        .append("_gate=").append(diagnostics[i]).append('\n');
            }
            out.append(prefix).append("_all_gates=").append(pass).append('\n')
                    .append(prefix).append("_safe_fallback_all_gates=")
                    .append(safeFallback).append('\n');
            return out.toString();
        }
    }
}
