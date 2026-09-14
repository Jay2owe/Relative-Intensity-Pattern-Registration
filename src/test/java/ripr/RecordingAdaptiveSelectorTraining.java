/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.AutomaticRegistrationSelector;
import ripr.api.ImageType;
import ripr.api.Preprocessing;
import ripr.api.RegistrationRecipe;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Nested source-group training for the frozen recording_evidence_v2 contract. */
public final class RecordingAdaptiveSelectorTraining {
    static final double[] RIDGES = {0.1, 1, 10, 100, 1000};
    static final double[] THRESHOLDS = {0, 0.0005, 0.001, 0.002, 0.005,
            0.01, 0.02, 0.05, 0.1};
    static final int MAX_RETAINED_PER_TYPE = 12;

    private RecordingAdaptiveSelectorTraining() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        boolean namedCommand = args.length > 0 && ("fit".equals(args[0])
                || "validate".equals(args[0]) || "generate-source".equals(args[0])
                || "finalize".equals(args[0]) || "override-fixed".equals(args[0]));
        String command = namedCommand ? args[0] : "fit";
        Path project = (args.length == 0 || (namedCommand && args.length < 2)
                ? Paths.get("") : Paths.get(namedCommand ? args[1] : args[0]))
                .toAbsolutePath().normalize();
        if ("fit".equals(command)) fit(project);
        else if ("validate".equals(command)) validate(project);
        else if ("generate-source".equals(command)) generateSource(project);
        else if ("finalize".equals(command)) finalizeModel(project);
        else if ("override-fixed".equals(command)) overrideFixedPolicy(project);
        else throw new IllegalArgumentException(
                    "usage: <fit|validate|generate-source|finalize|override-fixed> [project]");
    }

    static void fit(Path project) throws Exception {
        Path root = project.resolve("library/recording_adaptive_selector_v1");
        Properties headroom = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(
                root.resolve("headroom_decision.properties"), StandardCharsets.UTF_8)) {
            headroom.load(reader);
        }
        Map<String, RecordingAdaptiveSelectorHeadroom.CaseOutcomes> outcomes =
                RecordingAdaptiveSelectorHeadroom.read(root.resolve("outcomes/development.csv"));
        Map<String, Feature> features = readFeatures(root.resolve("features/development.csv"));
        List<Row> rows = join(outcomes, features, true);
        Map<String, Double> selectorTimes = readSelectorTimes(
                root.resolve("timings/development.csv"));
        Path training = root.resolve("training");
        Files.createDirectories(training);

        Map<String, String> fixedPolicy =
                RecordingAdaptiveSelectorHeadroom.selectFullFixed(outcomes);
        writeFixedPolicy(training.resolve("fixed_policy.csv"), fixedPolicy);

        if (!"ADAPTIVE_ALLOWED".equals(headroom.getProperty("decision"))) {
            Model fixed = fixedModel(rows, fixedPolicy);
            writeModel(training, fixed, "headroom stop gate required fixed policy");
            writeValidationRequest(training, fixed, fixedPolicy);
            writeComparison(training, Double.NaN,
                    RecordingAdaptiveSelectorHeadroom.balanced(
                            RecordingAdaptiveSelectorHeadroom.fixedCrossValidatedPolicy(outcomes)
                                    .values()), "FIXED_POLICY_HEADROOM_STOP");
            return;
        }

        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixedCv =
                RecordingAdaptiveSelectorHeadroom.fixedCrossValidatedPolicy(outcomes);
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> linearCv = new LinkedHashMap<>();
        List<Tuning> tunings = new ArrayList<>();
        Set<String> groups = new LinkedHashSet<>();
        for (Row row : rows) groups.add(row.feature.group);
        for (String outer : groups) {
            String type = groupType(rows, outer);
            Tuning tuning = tuneInner(rows, type, outer);
            tunings.add(tuning);
            Set<String> excluded = new LinkedHashSet<>();
            excluded.add(outer);
            Model model = fitLinear(rows, type, excluded, tuning.ridge, tuning.threshold);
            for (Row row : rows) {
                if (!outer.equals(row.feature.group)) continue;
                RecordingAdaptiveSelectorHeadroom.Outcome chosen = chosenOutcome(row, model);
                if (serves(model, row.feature.imageType)) {
                    Double overhead = selectorTimes.get(row.feature.caseId);
                    if (overhead == null || !Double.isFinite(overhead)) {
                        throw new IOException("missing selector timing " + row.feature.caseId);
                    }
                    chosen = withRuntime(chosen, chosen.runtime + overhead);
                }
                linearCv.put(row.feature.caseId, chosen);
            }
        }
        double fixedError = RecordingAdaptiveSelectorHeadroom.balanced(fixedCv.values());
        double linearError = RecordingAdaptiveSelectorHeadroom.balanced(linearCv.values());
        Validation developmentGates = validation("development_linear_cv", linearCv, fixedCv);
        boolean linearWins = developmentGates.pass;
        Model finalModel;
        String decision;
        if (linearWins) {
            double ridge = modeRidge(tunings);
            double threshold = modeThreshold(tunings);
            finalModel = fitLinear(rows, null, Collections.emptySet(), ridge, threshold);
            decision = "LINEAR_GAIN";
        } else {
            finalModel = fixedModel(rows, fixedPolicy);
            decision = "IMAGE_TYPE_RULE";
        }
        writeOuterPredictions(training.resolve("outer_predictions.csv"), rows, fixedCv, linearCv);
        writeTuning(training.resolve("nested_tuning.csv"), tunings);
        Files.write(training.resolve("development_gates.properties"),
                developmentGates.properties("linear_cv").getBytes(StandardCharsets.UTF_8));
        writeModel(training, finalModel, "development decision " + decision);
        writeValidationRequest(training, finalModel, fixedPolicy);
        writeComparison(training, linearError, fixedError, decision);
        System.out.printf(Locale.ROOT,
                "development chose %s: linear %.9f px, fixed %.9f px; validation still required%n",
                decision, linearError, fixedError);
    }

    /** Apply the frozen development decision once to the reserved validation partition. */
    static void validate(Path project) throws Exception {
        Path root = project.resolve("library/recording_adaptive_selector_v1");
        Path training = root.resolve("training");
        Path validationDecision = training.resolve("validation_decision.properties");
        if (Files.exists(validationDecision)) {
            throw new IOException("reserved validation has already been evaluated: "
                    + validationDecision);
        }
        Model candidate = readModel(training, "candidate_model");
        Map<String, String> fixedPolicy = readFixedPolicy(
                training.resolve("fixed_policy.csv"));
        Map<String, RecordingAdaptiveSelectorHeadroom.CaseOutcomes> outcomes =
                RecordingAdaptiveSelectorHeadroom.read(root.resolve("outcomes/validation.csv"));
        Map<String, Feature> features = readFeatures(root.resolve("features/validation.csv"));
        List<Row> rows = join(outcomes, features, false);
        Map<String, Double> selectorTimes = readSelectorTimes(
                root.resolve("timings/validation.csv"));

        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> category = new LinkedHashMap<>();
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixed = new LinkedHashMap<>();
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = new LinkedHashMap<>();
        for (Row row : rows) {
            RecordingAdaptiveSelectorHeadroom.Outcome fallback = category(row);
            category.put(row.feature.caseId, fallback);
            RecordingAdaptiveSelectorHeadroom.Outcome fixedOutcome =
                    row.outcomes.byRecipe.get(fixedPolicy.get(row.feature.imageType));
            fixed.put(row.feature.caseId, fixedOutcome == null ? fallback : fixedOutcome);
            RecordingAdaptiveSelectorHeadroom.Outcome chosen = chosenOutcome(row, candidate);
            if ("linear_gain".equals(candidate.kind)) {
                Double overhead = selectorTimes.get(row.feature.caseId);
                if (overhead == null || !Double.isFinite(overhead)) {
                    throw new IOException("missing selector timing " + row.feature.caseId);
                }
                chosen = withRuntime(chosen, chosen.runtime + overhead);
            }
            selected.put(row.feature.caseId, chosen);
        }

        Validation adaptive = validation("adaptive_candidate", selected, fixed);
        Validation fixedGate = validation("frozen_fixed_policy", fixed, category);
        boolean adaptiveRequested = "linear_gain".equals(candidate.kind);
        Model promoted;
        String decision;
        if (adaptiveRequested && adaptive.pass) {
            promoted = candidate;
            decision = "ADAPTIVE";
        } else if (fixedGate.safeFallback) {
            Map<String, RecordingAdaptiveSelectorHeadroom.CaseOutcomes> developmentOutcomes =
                    RecordingAdaptiveSelectorHeadroom.read(
                            root.resolve("outcomes/development.csv"));
            List<Row> development = join(developmentOutcomes,
                    readFeatures(root.resolve("features/development.csv")), true);
            promoted = fixedModel(development, fixedPolicy);
            decision = "FIXED";
        } else {
            List<Row> development = join(
                    RecordingAdaptiveSelectorHeadroom.read(
                            root.resolve("outcomes/development.csv")),
                    readFeatures(root.resolve("features/development.csv")), true);
            promoted = new Model("image_type_rule", 0, 0, stats(development));
            decision = "CATEGORY_RECOMMENDATION";
        }
        writeValidationRows(training.resolve("validation_predictions.csv"), rows,
                category, fixed, selected);
        writeValidationDecision(training.resolve("validation_decision.properties"),
                project, decision, adaptive, fixedGate);
        writeNamedModel(training, "promoted_model", promoted,
                "reserved validation decision " + decision);
        generateSource(project);
        System.out.printf(Locale.ROOT,
                "validation chose %s: adaptive pass=%s; fixed fallback safe=%s%n",
                decision, adaptive.pass, fixedGate.safeFallback);
    }

    /** Generate a run-scoped model source. Production installation remains a separate stage. */
    static void generateSource(Path project) throws Exception {
        Path root = project.resolve("library/recording_adaptive_selector_v1");
        Path training = root.resolve("training");
        Properties validation = loadProperties(
                training.resolve("validation_decision.properties"));
        generateNamedSource(project, "promoted_model", validation,
                training.resolve("validation_decision.properties"));
    }

    /** Regenerate only the policy selected by the sealed final report; no outcomes are retuned. */
    static void finalizeModel(Path project) throws Exception {
        Path root = project.resolve("library/recording_adaptive_selector_v1");
        Path training = root.resolve("training");
        Path reportPath = root.resolve("final/gate_report.properties");
        Properties report = loadProperties(reportPath);
        String decision = report.getProperty("final_policy");
        Model model;
        if ("ADAPTIVE".equals(decision)) {
            model = readModel(training, "promoted_model");
            if (!"linear_gain".equals(model.kind)) {
                throw new IOException("final report requested adaptive without a linear model");
            }
        } else {
            List<Row> development = join(
                    RecordingAdaptiveSelectorHeadroom.read(
                            root.resolve("outcomes/development.csv")),
                    readFeatures(root.resolve("features/development.csv")), true);
            if ("FIXED".equals(decision)) {
                model = fixedModel(development,
                        readFixedPolicy(training.resolve("fixed_policy.csv")));
            } else if ("CATEGORY_RECOMMENDATION".equals(decision)) {
                model = new Model("image_type_rule", 0, 0, stats(development));
            } else {
                throw new IOException("unknown final policy " + decision);
            }
        }
        writeNamedModel(training, "final_model", model,
                "sealed final decision " + decision);
        String decisionText = "decision=" + decision + '\n'
                + "model_version=recording_adaptive_selector_v1_final_"
                + decision.toLowerCase(Locale.ROOT) + '\n'
                + "decision_basis=sealed final report; no retuning\n"
                + "final_gate_report_sha256=" + sha256(reportPath) + '\n'
                + "final_outcomes_sha256=" + report.getProperty("final_outcomes_sha256") + '\n'
                + "freeze_record_sha256=" + report.getProperty("freeze_record_sha256") + '\n';
        Path decisionPath = training.resolve("final_decision.properties");
        Files.write(decisionPath, decisionText.getBytes(StandardCharsets.UTF_8));
        generateNamedSource(project, "final_model", loadProperties(decisionPath), decisionPath);
    }

    /**
     * Promote the sealed fixed image-type policy by an explicit operator decision.
     *
     * <p>This does not alter or reinterpret the once-opened final report. It creates a new,
     * separately hashed decision that accepts only the observed crop/runtime trade-off; every
     * frozen accuracy, tail, recording and diagnostic gate must still have passed.
     */
    static void overrideFixedPolicy(Path project) throws Exception {
        Path root = project.resolve("library/recording_adaptive_selector_v1");
        Path training = root.resolve("training");
        Path reportPath = root.resolve("final/gate_report.properties");
        Properties report = loadProperties(reportPath);
        requireFixedOverrideEligible(report);

        Path decisionPath = training.resolve("operator_override_decision.properties");
        if (Files.exists(decisionPath)) {
            throw new IOException("fixed-policy operator override already exists: " + decisionPath);
        }
        List<Row> development = join(
                RecordingAdaptiveSelectorHeadroom.read(root.resolve("outcomes/development.csv")),
                readFeatures(root.resolve("features/development.csv")), true);
        Model model = fixedModel(development,
                readFixedPolicy(training.resolve("fixed_policy.csv")));
        writeNamedModel(training, "operator_override_model", model,
                "user-approved fixed policy; sealed crop/runtime failures explicitly accepted");

        String decisionText = "decision=USER_APPROVED_FIXED_POLICY\n"
                + "model_version=recording_adaptive_selector_v1_user_approved_fixed_policy_v1\n"
                + "decision_basis=user-approved fixed policy after sealed final report; "
                + "accepts one 0.030639648438 crop loss and one 1.65781806354497 runtime ratio\n"
                + "sealed_final_policy=" + report.getProperty("final_policy") + '\n'
                + "sealed_fixed_improvement_px="
                + report.getProperty("fixed_improvement_px") + '\n'
                + "sealed_fixed_crop_gate=" + report.getProperty("fixed_crop_gate") + '\n'
                + "sealed_fixed_runtime_gate=" + report.getProperty("fixed_runtime_gate") + '\n'
                + "final_gate_report_sha256=" + sha256(reportPath) + '\n'
                + "final_outcomes_sha256=" + report.getProperty("final_outcomes_sha256") + '\n'
                + "freeze_record_sha256=" + report.getProperty("freeze_record_sha256") + '\n';
        Files.write(decisionPath, decisionText.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        generateNamedSource(project, "operator_override_model",
                loadProperties(decisionPath), decisionPath);
    }

    static void requireFixedOverrideEligible(Properties report) throws IOException {
        if (!"CATEGORY_RECOMMENDATION".equals(report.getProperty("final_policy"))) {
            throw new IOException("operator override requires the sealed category fallback");
        }
        String[] requiredPasses = {"fixed_primary_gate", "fixed_image_type_gate",
                "fixed_p90_gate", "fixed_worst_gate", "fixed_recording_guard",
                "fixed_diagnostic_gate", "fixed_status_gate", "fixed_refused_pairs_gate",
                "fixed_non_converged_pairs_gate", "fixed_shift_bound_pairs_gate",
                "fixed_rotation_bound_pairs_gate", "fixed_both_bound_pairs_gate",
                "fixed_repaired_frames_gate", "fixed_unsupported_frames_gate"};
        for (String gate : requiredPasses) {
            if (!"true".equals(report.getProperty(gate))) {
                throw new IOException("cannot override failed accuracy/safety gate " + gate);
            }
        }
        if (Double.parseDouble(report.getProperty("fixed_improvement_px", "NaN")) <= 0) {
            throw new IOException("cannot override a fixed policy without measured improvement");
        }
        if ("true".equals(report.getProperty("fixed_crop_gate"))
                && "true".equals(report.getProperty("fixed_runtime_gate"))) {
            throw new IOException("fixed policy already passed crop and runtime; no override needed");
        }
    }

    private static void generateNamedSource(Path project, String stem, Properties decision,
                                            Path decisionPath) throws Exception {
        Path root = project.resolve("library/recording_adaptive_selector_v1");
        Path training = root.resolve("training");
        Model model = readModel(training, stem);
        String artifactHash = modelArtifactHash(training, stem, decisionPath);
        Path generated = training.resolve("generated/AutomaticRegistrationSelectorModel.java");
        Files.createDirectories(generated.getParent());
        Files.write(generated, modelSource(project, model, decision, artifactHash)
                .getBytes(StandardCharsets.UTF_8));
        Files.write(generated.resolveSibling("AutomaticRegistrationSelectorModel.java.sha256"),
                (sha256(generated) + "  AutomaticRegistrationSelectorModel.java\n")
                        .getBytes(StandardCharsets.UTF_8));
        Path python = training.resolve("generated/recording_selector_model.py");
        Files.write(python, modelPythonSource(project, model, decision, artifactHash)
                .getBytes(StandardCharsets.UTF_8));
        Files.write(python.resolveSibling("recording_selector_model.py.sha256"),
                (sha256(python) + "  recording_selector_model.py\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- nested fitting

    private static Tuning tuneInner(List<Row> rows, String type, String outer) {
        Set<String> innerGroups = new LinkedHashSet<>();
        for (Row row : rows) {
            if (type.equals(row.feature.imageType) && !outer.equals(row.feature.group)) {
                innerGroups.add(row.feature.group);
            }
        }
        Tuning best = null;
        for (double ridge : RIDGES) {
            Map<String, Prediction> predictions = new LinkedHashMap<>();
            for (String inner : innerGroups) {
                Set<String> excluded = new LinkedHashSet<>();
                excluded.add(outer);
                excluded.add(inner);
                Model model = fitLinear(rows, type, excluded, ridge, 0);
                for (Row row : rows) {
                    if (inner.equals(row.feature.group)) {
                        predictions.put(row.feature.caseId, model.predict(row.feature));
                    }
                }
            }
            for (double threshold : THRESHOLDS) {
                List<RecordingAdaptiveSelectorHeadroom.Outcome> selected = new ArrayList<>();
                for (Row row : rows) {
                    if (!innerGroups.contains(row.feature.group)) continue;
                    Prediction prediction = predictions.get(row.feature.caseId);
                    selected.add(outcome(row, prediction, threshold));
                }
                double error = RecordingAdaptiveSelectorHeadroom.balanced(selected);
                if (best == null || error < best.innerError - 1e-12
                        || (Math.abs(error - best.innerError) <= 1e-12
                        && threshold > best.threshold)) {
                    best = new Tuning(outer, type, ridge, threshold, error);
                }
            }
        }
        return best == null ? new Tuning(outer, type, 1000, 0.1,
                Double.POSITIVE_INFINITY) : best;
    }

    private static Model fitLinear(List<Row> rows, String onlyType, Set<String> excluded,
                                   double ridge, double threshold) {
        List<Row> training = new ArrayList<>();
        for (Row row : rows) if (!excluded.contains(row.feature.group)) training.add(row);
        Stats stats = stats(training);
        Model model = new Model("linear_gain", ridge, threshold, stats);
        for (ImageType imageType : ImageType.values()) {
            String type = imageType.name();
            if (onlyType != null && !onlyType.equals(type)) continue;
            List<CandidateGain> retained = retain(training, type);
            for (CandidateGain candidate : retained) {
                List<double[]> x = new ArrayList<>();
                List<Double> y = new ArrayList<>();
                for (Row row : training) {
                    if (!type.equals(row.feature.imageType)) continue;
                    RecordingAdaptiveSelectorHeadroom.Outcome category = category(row);
                    RecordingAdaptiveSelectorHeadroom.Outcome outcome =
                            row.outcomes.byRecipe.get(candidate.recipe);
                    x.add(standardise(row.feature.values, stats));
                    y.add(category.median - outcome.median);
                }
                double[][] matrix = x.toArray(new double[0][]);
                double[] target = new double[y.size()];
                for (int i = 0; i < target.length; i++) target[i] = y.get(i);
                double[] fitted = FullSelectorTraining.ridge(matrix, target, ridge);
                double[] weights = new double[AutomaticRegistrationSelector.FEATURE_COUNT];
                System.arraycopy(fitted, 0, weights, 0, weights.length);
                model.candidates.add(new CandidateModel(type, candidate.recipe, candidate.gain,
                        fitted[weights.length], weights));
            }
        }
        return model;
    }

    private static List<CandidateGain> retain(List<Row> rows, String type) {
        Set<String> ids = new LinkedHashSet<>();
        for (Row row : rows) if (type.equals(row.feature.imageType)) {
            ids.addAll(row.outcomes.byRecipe.keySet());
        }
        ids.remove(RecordingAdaptiveSelectorBenchmark.CATEGORY);
        List<CandidateGain> retained = new ArrayList<>();
        for (String id : ids) {
            Map<String, List<Double>> gains = new LinkedHashMap<>();
            boolean safe = true;
            List<RecordingAdaptiveSelectorHeadroom.Outcome> candidateOutcomes = new ArrayList<>();
            List<RecordingAdaptiveSelectorHeadroom.Outcome> categoryOutcomes = new ArrayList<>();
            for (Row row : rows) {
                if (!type.equals(row.feature.imageType)) continue;
                RecordingAdaptiveSelectorHeadroom.Outcome category = category(row);
                RecordingAdaptiveSelectorHeadroom.Outcome candidate = row.outcomes.byRecipe.get(id);
                if (!RecordingAdaptiveSelectorHeadroom.safe(candidate, category)) {
                    safe = false;
                    break;
                }
                gains.computeIfAbsent(row.feature.group, ignored -> new ArrayList<>())
                        .add(category.median - candidate.median);
                candidateOutcomes.add(candidate);
                categoryOutcomes.add(category);
            }
            if (!safe || gains.isEmpty()) continue;
            int winningGroups = 0;
            for (List<Double> values : gains.values()) if (median(values) > 0) winningGroups++;
            double gain = RecordingAdaptiveSelectorHeadroom.balanced(categoryOutcomes)
                    - RecordingAdaptiveSelectorHeadroom.balanced(candidateOutcomes);
            if (winningGroups >= 3 && gain > 0) retained.add(new CandidateGain(id, gain));
        }
        retained.sort(Comparator.comparingDouble((CandidateGain c) -> c.gain).reversed()
                .thenComparing(c -> c.recipe));
        if (retained.size() > MAX_RETAINED_PER_TYPE) {
            return new ArrayList<>(retained.subList(0, MAX_RETAINED_PER_TYPE));
        }
        return retained;
    }

    private static Model fixedModel(List<Row> rows, Map<String, String> fixedPolicy) {
        Stats stats = stats(rows);
        Model model = new Model("image_type_rule", 0, 0, stats);
        for (Map.Entry<String, String> entry : fixedPolicy.entrySet()) {
            if (RecordingAdaptiveSelectorBenchmark.CATEGORY.equals(entry.getValue())) continue;
            double gain = trainingGain(rows, entry.getKey(), entry.getValue());
            model.candidates.add(new CandidateModel(entry.getKey(), entry.getValue(), gain,
                    gain, new double[AutomaticRegistrationSelector.FEATURE_COUNT]));
        }
        return model;
    }

    private static double trainingGain(List<Row> rows, String type, String recipe) {
        List<RecordingAdaptiveSelectorHeadroom.Outcome> category = new ArrayList<>();
        List<RecordingAdaptiveSelectorHeadroom.Outcome> candidate = new ArrayList<>();
        for (Row row : rows) if (type.equals(row.feature.imageType)) {
            category.add(category(row));
            candidate.add(row.outcomes.byRecipe.get(recipe));
        }
        return RecordingAdaptiveSelectorHeadroom.balanced(category)
                - RecordingAdaptiveSelectorHeadroom.balanced(candidate);
    }

    // ---------------------------------------------------------------- prediction/evaluation

    static RecordingAdaptiveSelectorHeadroom.Outcome chosenOutcome(Row row, Model model) {
        Prediction prediction = model.predict(row.feature);
        return outcome(row, prediction, model.threshold);
    }

    private static RecordingAdaptiveSelectorHeadroom.Outcome outcome(
            Row row, Prediction prediction, double threshold) {
        if (prediction == null || prediction.ood || prediction.recipe == null
                || prediction.gain < threshold) return category(row);
        RecordingAdaptiveSelectorHeadroom.Outcome candidate =
                row.outcomes.byRecipe.get(prediction.recipe);
        return candidate == null ? category(row) : candidate;
    }

    private static boolean serves(Model model, String imageType) {
        for (CandidateModel candidate : model.candidates) {
            if (candidate.imageType.equals(imageType)) return true;
        }
        return false;
    }

    private static String groupType(List<Row> rows, String group) {
        String type = null;
        for (Row row : rows) if (group.equals(row.feature.group)) {
            if (type != null && !type.equals(row.feature.imageType)) {
                throw new IllegalArgumentException("group crosses image types: " + group);
            }
            type = row.feature.imageType;
        }
        if (type == null) throw new IllegalArgumentException("unknown group " + group);
        return type;
    }

    private static double modeRidge(List<Tuning> tunings) { return mode(tunings, true); }
    private static double modeThreshold(List<Tuning> tunings) { return mode(tunings, false); }

    private static double mode(List<Tuning> tunings, boolean ridge) {
        Map<Double, Integer> counts = new LinkedHashMap<>();
        for (Tuning tuning : tunings) {
            double value = ridge ? tuning.ridge : tuning.threshold;
            counts.put(value, counts.getOrDefault(value, 0) + 1);
        }
        double best = ridge ? 1000 : 0.1;
        int count = -1;
        for (Map.Entry<Double, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > count || (entry.getValue() == count
                    && entry.getKey() > best)) {
                best = entry.getKey(); count = entry.getValue();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- input and artifacts

    static Map<String, Feature> readFeatures(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> header = FullSelectorFactorialBenchmark.fields(lines.get(0));
        for (String name : header) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String forbidden : new String[]{"truth", "error", "oracle", "winner", "recipe"}) {
                if (lower.contains(forbidden)) throw new IOException(
                        "forbidden feature column " + name);
            }
        }
        if (header.size() != 9 + AutomaticRegistrationSelector.FEATURE_COUNT) {
            throw new IOException("feature schema width drifted");
        }
        Map<String, Feature> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> r = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (!"recording_evidence_v2".equals(r.get(5))) {
                throw new IOException("feature contract mismatch at row " + (i + 1));
            }
            double[] values = new double[AutomaticRegistrationSelector.FEATURE_COUNT];
            for (int j = 0; j < values.length; j++) values[j] = Double.parseDouble(r.get(9 + j));
            Feature feature = new Feature(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4),
                    Boolean.parseBoolean(r.get(6)), r.get(8), values);
            if (out.put(feature.caseId, feature) != null) {
                throw new IOException("duplicate feature " + feature.caseId);
            }
        }
        return out;
    }

    static List<Row> join(
            Map<String, RecordingAdaptiveSelectorHeadroom.CaseOutcomes> outcomes,
            Map<String, Feature> features, boolean requireValid) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, RecordingAdaptiveSelectorHeadroom.CaseOutcomes> entry
                : outcomes.entrySet()) {
            Feature feature = features.get(entry.getKey());
            if (feature == null) throw new IOException("missing feature " + entry.getKey());
            if (requireValid && !feature.valid) throw new IOException("invalid development evidence "
                    + feature.caseId + ": " + feature.reason);
            rows.add(new Row(feature, entry.getValue()));
        }
        if (rows.size() != features.size()) throw new IOException("feature/outcome join mismatch");
        return rows;
    }

    private static Stats stats(List<Row> rows) {
        int p = AutomaticRegistrationSelector.FEATURE_COUNT;
        double[] mean = new double[p];
        double[] scale = new double[p];
        for (Row row : rows) for (int i = 0; i < p; i++) mean[i] += row.feature.values[i];
        for (int i = 0; i < p; i++) mean[i] /= rows.size();
        for (Row row : rows) for (int i = 0; i < p; i++) {
            double d = row.feature.values[i] - mean[i]; scale[i] += d * d;
        }
        for (int i = 0; i < p; i++) scale[i] = Math.sqrt(scale[i] / rows.size());
        return new Stats(mean, scale);
    }

    private static double[] standardise(double[] raw, Stats stats) {
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = stats.scale[i] > 0 ? (raw[i] - stats.mean[i]) / stats.scale[i] : 0;
        }
        return out;
    }

    static RecordingAdaptiveSelectorHeadroom.Outcome category(Row row) {
        return row.outcomes.byRecipe.get(RecordingAdaptiveSelectorBenchmark.CATEGORY);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int m = sorted.size() / 2;
        return (sorted.size() & 1) == 1 ? sorted.get(m)
                : 0.5 * (sorted.get(m - 1) + sorted.get(m));
    }

    private static void writeFixedPolicy(Path path, Map<String, String> policy)
            throws IOException {
        StringBuilder out = new StringBuilder("image_type,recipe_id\n");
        for (Map.Entry<String, String> entry : policy.entrySet()) {
            out.append(entry.getKey()).append(',').append(entry.getValue()).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeModel(Path training, Model model, String reason) throws IOException {
        writeNamedModel(training, "candidate_model", model, reason);
    }

    private static void writeNamedModel(Path training, String stem, Model model, String reason)
            throws IOException {
        String properties = "model_kind=" + model.kind + '\n'
                + "model_version=recording_adaptive_selector_v1_candidate\n"
                + "feature_contract_version=recording_evidence_v2\n"
                + "ridge=" + fmt(model.ridge) + '\n'
                + "threshold=" + fmt(model.threshold) + '\n'
                + "candidate_count=" + model.candidates.size() + '\n'
                + "reason=" + reason + '\n';
        Files.write(training.resolve(stem + ".properties"),
                properties.getBytes(StandardCharsets.UTF_8));
        StringBuilder stats = new StringBuilder("index,feature_name,mean,scale\n");
        for (int i = 0; i < model.stats.mean.length; i++) {
            stats.append(i).append(',').append(csv(AutomaticRegistrationSelector.FEATURE_NAMES[i]))
                    .append(',').append(fmt(model.stats.mean[i])).append(',')
                    .append(fmt(model.stats.scale[i])).append('\n');
        }
        Files.write(training.resolve(stem + "_stats.csv"),
                stats.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder candidates = new StringBuilder(
                "image_type,recipe_id,training_gain,intercept,weights\n");
        for (CandidateModel candidate : model.candidates) {
            candidates.append(candidate.imageType).append(',').append(candidate.recipe).append(',')
                    .append(fmt(candidate.trainingGain)).append(',').append(fmt(candidate.intercept))
                    .append(',').append(csv(join(candidate.weights))).append('\n');
        }
        Files.write(training.resolve(stem + "_coefficients.csv"),
                candidates.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Model readModel(Path training, String stem) throws IOException {
        Properties properties = loadProperties(training.resolve(stem + ".properties"));
        if (!"recording_evidence_v2".equals(
                properties.getProperty("feature_contract_version"))) {
            throw new IOException("model feature contract mismatch");
        }
        List<String> statLines = Files.readAllLines(
                training.resolve(stem + "_stats.csv"), StandardCharsets.UTF_8);
        if (statLines.size() != 1 + AutomaticRegistrationSelector.FEATURE_COUNT) {
            throw new IOException("model statistics width drifted");
        }
        double[] mean = new double[AutomaticRegistrationSelector.FEATURE_COUNT];
        double[] scale = new double[mean.length];
        for (int i = 0; i < mean.length; i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(statLines.get(i + 1));
            if (Integer.parseInt(row.get(0)) != i
                    || !AutomaticRegistrationSelector.FEATURE_NAMES[i].equals(row.get(1))) {
                throw new IOException("model feature order drifted at " + i);
            }
            mean[i] = Double.parseDouble(row.get(2));
            scale[i] = Double.parseDouble(row.get(3));
        }
        Model model = new Model(properties.getProperty("model_kind"),
                Double.parseDouble(properties.getProperty("ridge")),
                Double.parseDouble(properties.getProperty("threshold")),
                new Stats(mean, scale));
        List<String> coefficientLines = Files.readAllLines(
                training.resolve(stem + "_coefficients.csv"), StandardCharsets.UTF_8);
        for (int i = 1; i < coefficientLines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(coefficientLines.get(i));
            String[] rawWeights = row.get(4).isEmpty() ? new String[0] : row.get(4).split(";");
            if (rawWeights.length != AutomaticRegistrationSelector.FEATURE_COUNT) {
                throw new IOException("model coefficient width drifted at row " + (i + 1));
            }
            double[] weights = new double[rawWeights.length];
            for (int j = 0; j < weights.length; j++) {
                weights[j] = Double.parseDouble(rawWeights[j]);
            }
            model.candidates.add(new CandidateModel(row.get(0), row.get(1),
                    Double.parseDouble(row.get(2)), Double.parseDouble(row.get(3)), weights));
        }
        if (model.candidates.size() != Integer.parseInt(
                properties.getProperty("candidate_count"))) {
            throw new IOException("model candidate count drifted");
        }
        return model;
    }

    private static void writeValidationRequest(Path training, Model model,
                                               Map<String, String> fixed) throws IOException {
        Set<String> recipes = new LinkedHashSet<>();
        for (CandidateModel candidate : model.candidates) recipes.add(candidate.recipe);
        for (String recipe : fixed.values()) if (!RecordingAdaptiveSelectorBenchmark.CATEGORY
                .equals(recipe)) recipes.add(recipe);
        StringBuilder out = new StringBuilder();
        for (String recipe : recipes) out.append(recipe).append('\n');
        Files.write(training.resolve("validation_recipe_request.txt"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeComparison(Path training, double linear, double fixed, String decision)
            throws IOException {
        String text = "family,source_grouped_primary_error_px,decision\n"
                + "LINEAR_GAIN," + fmt(linear) + ',' + decision + '\n'
                + "IMAGE_TYPE_RULE," + fmt(fixed) + ',' + decision + '\n';
        Files.write(training.resolve("development_model_comparison.csv"),
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeOuterPredictions(Path path, List<Row> rows,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixed,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> linear) throws IOException {
        StringBuilder out = new StringBuilder("case_id,independent_group,image_type,"
                + "fixed_recipe,fixed_error_px,linear_recipe,linear_error_px\n");
        for (Row row : rows) {
            RecordingAdaptiveSelectorHeadroom.Outcome f = fixed.get(row.feature.caseId);
            RecordingAdaptiveSelectorHeadroom.Outcome l = linear.get(row.feature.caseId);
            out.append(csv(row.feature.caseId)).append(',').append(csv(row.feature.group)).append(',')
                    .append(row.feature.imageType).append(',').append(f.recipe).append(',')
                    .append(fmt(f.median)).append(',').append(l.recipe).append(',')
                    .append(fmt(l.median)).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTuning(Path path, List<Tuning> tunings) throws IOException {
        StringBuilder out = new StringBuilder(
                "held_out_group,image_type,ridge,threshold,inner_error_px\n");
        for (Tuning t : tunings) out.append(t.outer).append(',').append(t.imageType).append(',')
                .append(fmt(t.ridge)).append(',').append(fmt(t.threshold)).append(',')
                .append(fmt(t.innerError)).append('\n');
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, String> readFixedPolicy(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out.put(row.get(0), row.get(1));
        }
        return out;
    }

    static Map<String, Double> readSelectorTimes(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (row.size() != 5 || !RecordingAdaptiveSelectorBenchmark.TIMING_CONTRACT_VERSION
                    .equals(row.get(4))) {
                throw new IOException("selector timing contract mismatch at row " + (i + 1));
            }
            double complete = Double.parseDouble(row.get(1))
                    + Double.parseDouble(row.get(2)) + Double.parseDouble(row.get(3));
            if (out.put(row.get(0), complete) != null) {
                throw new IOException("duplicate selector timing " + row.get(0));
            }
        }
        return out;
    }

    static RecordingAdaptiveSelectorHeadroom.Outcome withRuntime(
            RecordingAdaptiveSelectorHeadroom.Outcome value, double runtime) {
        return new RecordingAdaptiveSelectorHeadroom.Outcome(value.caseId, value.imageType,
                value.group, value.recipe, value.ok, value.median, value.p90, value.worst,
                value.medianAngle, value.p90Angle, value.worstAngle,
                value.medianTranslation, value.worstTranslation,
                value.diagnosticCounts, value.crop, runtime);
    }

    static Validation validation(String name,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> baseline) {
        double base = RecordingAdaptiveSelectorHeadroom.balanced(baseline.values());
        double actual = RecordingAdaptiveSelectorHeadroom.balanced(selected.values());
        double improvement = base - actual;
        double required = Math.max(0.001, 0.05 * base);
        boolean primary = Double.isFinite(actual) && improvement >= required;
        boolean type = true;
        for (ImageType imageType : ImageType.values()) {
            double b = balancedMetric(baseline.values(), imageType.name(), "median");
            double a = balancedMetric(selected.values(), imageType.name(), "median");
            if (!Double.isFinite(a) || a > b + 0.002) type = false;
        }
        boolean p90 = balancedMetric(selected.values(), null, "p90")
                <= balancedMetric(baseline.values(), null, "p90") + 0.005;
        boolean worst = balancedMetric(selected.values(), null, "worst")
                <= balancedMetric(baseline.values(), null, "worst") + 0.05;
        boolean guard = true, diagnostics = true, crop = true, runtime = true;
        for (Map.Entry<String, RecordingAdaptiveSelectorHeadroom.Outcome> entry
                : baseline.entrySet()) {
            RecordingAdaptiveSelectorHeadroom.Outcome b = entry.getValue();
            RecordingAdaptiveSelectorHeadroom.Outcome a = selected.get(entry.getKey());
            if (a == null || !a.ok || !Double.isFinite(a.median)) {
                guard = diagnostics = crop = runtime = false;
                continue;
            }
            if (a.median > 2 * b.median && a.median > b.median + 0.05) guard = false;
            if (RecordingAdaptiveSelectorHeadroom.diagnosticRegression(a, b)) {
                diagnostics = false;
            }
            if (!Double.isFinite(a.crop) || !Double.isFinite(b.crop)
                    || a.crop < b.crop - 0.02) crop = false;
            if (!Double.isFinite(a.runtime) || !Double.isFinite(b.runtime)
                    || a.runtime > 1.50 * b.runtime) runtime = false;
        }
        boolean safety = type && p90 && worst && guard && diagnostics && crop && runtime;
        return new Validation(name, base, actual, improvement, required, primary, type, p90,
                worst, guard, diagnostics, crop, runtime, primary && safety, safety);
    }

    private static double balancedMetric(
            Iterable<RecordingAdaptiveSelectorHeadroom.Outcome> outcomes,
            String imageType, String metric) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (RecordingAdaptiveSelectorHeadroom.Outcome outcome : outcomes) {
            if (outcome == null || !outcome.ok
                    || (imageType != null && !imageType.equals(outcome.imageType))) continue;
            double value = "p90".equals(metric) ? outcome.p90
                    : "worst".equals(metric) ? outcome.worst : outcome.median;
            grouped.computeIfAbsent(outcome.group, ignored -> new ArrayList<>()).add(value);
        }
        List<Double> values = new ArrayList<>();
        for (List<Double> group : grouped.values()) values.add(median(group));
        return values.isEmpty() ? Double.NaN : median(values);
    }

    private static void writeValidationRows(Path path, List<Row> rows,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> category,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixed,
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected) throws IOException {
        StringBuilder out = new StringBuilder("case_id,independent_group,image_type,"
                + "evidence_valid,category_recipe,category_error_px,fixed_recipe,fixed_error_px,"
                + "candidate_recipe,candidate_error_px\n");
        for (Row row : rows) {
            RecordingAdaptiveSelectorHeadroom.Outcome c = category.get(row.feature.caseId);
            RecordingAdaptiveSelectorHeadroom.Outcome f = fixed.get(row.feature.caseId);
            RecordingAdaptiveSelectorHeadroom.Outcome a = selected.get(row.feature.caseId);
            out.append(csv(row.feature.caseId)).append(',').append(csv(row.feature.group))
                    .append(',').append(row.feature.imageType).append(',')
                    .append(row.feature.valid).append(',').append(csv(c.recipe)).append(',')
                    .append(fmt(c.median)).append(',').append(csv(f.recipe)).append(',')
                    .append(fmt(f.median)).append(',').append(csv(a.recipe)).append(',')
                    .append(fmt(a.median)).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeValidationDecision(Path path, Path project, String decision,
            Validation adaptive, Validation fixed) throws IOException {
        String text = "decision=" + decision + '\n'
                + "model_version=recording_adaptive_selector_v1_"
                + decision.toLowerCase(Locale.ROOT) + '\n'
                + "feature_contract_version=recording_evidence_v2\n"
                + "protocol_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/selector_protocol.md")) + '\n'
                + "candidate_manifest_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/candidate_manifest.csv")) + '\n'
                + "validation_features_sha256=" + sha256(project.resolve(
                        "library/recording_adaptive_selector_v1/features/validation.csv")) + '\n'
                + "validation_outcomes_sha256=" + sha256(project.resolve(
                        "library/recording_adaptive_selector_v1/outcomes/validation.csv")) + '\n'
                + "validation_timings_sha256=" + sha256(project.resolve(
                        "library/recording_adaptive_selector_v1/timings/validation.csv")) + '\n'
                + adaptive.properties("adaptive") + fixed.properties("fixed");
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties out = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            out.load(reader);
        }
        return out;
    }

    private static String modelArtifactHash(Path training, String stem, Path validation)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path path : new Path[]{training.resolve(stem + ".properties"),
                    training.resolve(stem + "_stats.csv"),
                    training.resolve(stem + "_coefficients.csv"), validation}) {
                digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    static String modelSource(Path project, Model model, Properties validation,
                                      String artifactHash) throws IOException {
        Map<String, RegistrationRecipe> recipes = new LinkedHashMap<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.allCandidates()) {
            recipes.put(recipe.id(), recipe);
        }
        List<RegistrationRecipe> retained = new ArrayList<>();
        for (CandidateModel candidate : model.candidates) {
            RegistrationRecipe recipe = recipes.get(candidate.recipe);
            if (recipe == null) throw new IOException("unknown retained recipe " + candidate.recipe);
            retained.add(recipe);
        }
        String version = validation.getProperty("model_version");
        String decision = validation.getProperty("decision");
        String decisionBasis = validation.getProperty("decision_basis",
                "reserved validation decision " + decision);
        StringBuilder out = new StringBuilder();
        out.append("/*\n * Copyright (c) 2026 Jamie Malcolm\n")
                .append(" * Released under the BSD 3-Clause License. See LICENSE for terms.\n */\n")
                .append("package ripr.api;\n\n")
                .append("import ripr.core.PairAligner;\n")
                .append("import ripr.core.PairEstimator;\n\n")
                .append("import java.util.ArrayList;\nimport java.util.Arrays;\n")
                .append("import java.util.List;\n\n")
                .append("/** GENERATED from recording_adaptive_selector_v1; do not hand-edit. */\n")
                .append("public final class AutomaticRegistrationSelectorModel {\n")
                .append("    public static final int FEATURE_COUNT = readAtRuntime(")
                .append(AutomaticRegistrationSelector.FEATURE_COUNT).append(");\n")
                .append("    public static final String MODEL_KIND = readAtRuntime(\"")
                .append(java(model.kind)).append("\");\n")
                .append("    public static final String MODEL_VERSION = readAtRuntime(\"")
                .append(java(version)).append("\");\n")
                .append("    public static final String FEATURE_CONTRACT_VERSION = readAtRuntime(\"recording_evidence_v2\");\n")
                .append("    public static final String PROTOCOL_SHA256 = readAtRuntime(\"")
                .append(sha256(project.resolve("docs/recording-adaptive-selector/selector_protocol.md")))
                .append("\");\n")
                .append("    public static final String CANDIDATE_MANIFEST_SHA256 = readAtRuntime(\"")
                .append(sha256(project.resolve("docs/recording-adaptive-selector/candidate_manifest.csv")))
                .append("\");\n")
                .append("    public static final String MODEL_ARTIFACT_SHA256 = readAtRuntime(\"")
                .append(artifactHash).append("\");\n")
                .append("    public static final String VALIDATION_STATUS = readAtRuntime(\"")
                .append(java(decision)).append("\");\n")
                .append("    public static final String TRAINED_ON = readAtRuntime(\"recording_adaptive_selector_v1; ")
                .append(java(decisionBasis)).append("\");\n")
                .append("    public static final double CONFIDENCE_THRESHOLD = readAtRuntime(")
                .append(fmt(model.threshold)).append(");\n\n")
                .append("    private static int readAtRuntime(int value) { return value; }\n")
                .append("    private static double readAtRuntime(double value) { return value; }\n")
                .append("    private static String readAtRuntime(String value) { return value; }\n\n");
        appendDoubleArray(out, "FEATURE_MEAN", model.stats.mean);
        appendDoubleArray(out, "FEATURE_SCALE", model.stats.scale);
        appendStringArray(out, "CANDIDATE_IMAGE_TYPE", model.candidates, "type");
        appendRecipeArray(out, "CANDIDATE_ESTIMATOR", retained, "estimator");
        out.append("    static final boolean[] CANDIDATE_RIGID_VALIDATED = {");
        for (int i = 0; i < retained.size(); i++) out.append(i == 0 ? "\n            true" : ",\n            true");
        out.append(retained.isEmpty() ? "};\n\n" : "\n    };\n\n");
        appendRecipeArray(out, "CANDIDATE_SUPPORT", retained, "support");
        appendRecipeArray(out, "CANDIDATE_BAND", retained, "band");
        appendRecipeArray(out, "CANDIDATE_FILTER", retained, "filter");
        appendRecipeArray(out, "CANDIDATE_MASK", retained, "mask");
        double[] gains = new double[model.candidates.size()];
        double[] intercept = new double[gains.length];
        for (int i = 0; i < gains.length; i++) {
            gains[i] = model.candidates.get(i).trainingGain;
            intercept[i] = model.candidates.get(i).intercept;
        }
        appendDoubleArray(out, "CANDIDATE_TRAINING_GAIN", gains);
        appendDoubleArray(out, "INTERCEPT", intercept);
        out.append("    static final double[][] WEIGHTS = {\n");
        for (int i = 0; i < model.candidates.size(); i++) {
            out.append("            {");
            double[] weights = model.candidates.get(i).weights;
            for (int j = 0; j < weights.length; j++) {
                if (j > 0) out.append(", ");
                out.append(fmt(weights[j]));
            }
            out.append("}").append(i + 1 == model.candidates.size() ? "\n" : ",\n");
        }
        out.append("    };\n\n    private AutomaticRegistrationSelectorModel() { }\n\n")
                .append("    public static List<RegistrationRecipe> candidates() {\n")
                .append("        List<RegistrationRecipe> out = new ArrayList<>();\n")
                .append("        for (int i = 0; i < CANDIDATE_SUPPORT.length; i++) {\n")
                .append("            out.add(RegistrationRecipe.swept(PairEstimator.Kind.valueOf(CANDIDATE_ESTIMATOR[i]),\n")
                .append("                    PairAligner.PixelSupport.valueOf(CANDIDATE_SUPPORT[i]),\n")
                .append("                    RegistrationRecipe.Band.valueOf(CANDIDATE_BAND[i]),\n")
                .append("                    Preprocessing.valueOf(CANDIDATE_FILTER[i]),\n")
                .append("                    RegistrationRecipe.Mask.valueOf(CANDIDATE_MASK[i])));\n")
                .append("        }\n        return out;\n    }\n\n")
                .append("    public static List<ImageType> candidateImageTypes() {\n")
                .append("        List<ImageType> out = new ArrayList<>();\n")
                .append("        for (String name : CANDIDATE_IMAGE_TYPE) out.add(ImageType.valueOf(name));\n")
                .append("        return out;\n    }\n\n")
                .append("    public static boolean candidateRigidValidated(int index) {\n")
                .append("        return index >= 0 && index < CANDIDATE_RIGID_VALIDATED.length\n")
                .append("                && CANDIDATE_RIGID_VALIDATED[index];\n    }\n\n")
                .append("    public static double[] predictGains(double[] standardised, ImageType imageType) {\n")
                .append("        double[] out = new double[CANDIDATE_SUPPORT.length];\n")
                .append("        Arrays.fill(out, Double.NEGATIVE_INFINITY);\n")
                .append("        for (int i = 0; i < out.length; i++) {\n")
                .append("            if (imageType == null || !CANDIDATE_IMAGE_TYPE[i].equals(imageType.name())) continue;\n")
                .append("            if (\"image_type_rule\".equals(MODEL_KIND)) { out[i] = CANDIDATE_TRAINING_GAIN[i]; continue; }\n")
                .append("            double value = INTERCEPT[i];\n")
                .append("            for (int j = 0; j < WEIGHTS[i].length; j++) value += WEIGHTS[i][j] * standardised[j];\n")
                .append("            out[i] = value;\n        }\n        return out;\n    }\n}\n");
        return out.toString();
    }

    static String modelPythonSource(Path project, Model model, Properties validation,
                                    String artifactHash) throws IOException {
        Map<String, RegistrationRecipe> recipes = new LinkedHashMap<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.allCandidates()) {
            recipes.put(recipe.id(), recipe);
        }
        StringBuilder out = new StringBuilder(
                "\"\"\"GENERATED selector constants; rewritten from the shared Java training artifact.\"\"\"\n\n");
        out.append("MODEL_KIND = \"").append(java(model.kind)).append("\"\n")
                .append("MODEL_VERSION = \"")
                .append(java(validation.getProperty("model_version"))).append("\"\n")
                .append("FEATURE_CONTRACT_VERSION = \"recording_evidence_v2\"\n")
                .append("PROTOCOL_SHA256 = \"").append(sha256(project.resolve(
                        "docs/recording-adaptive-selector/selector_protocol.md"))).append("\"\n")
                .append("CANDIDATE_MANIFEST_SHA256 = \"").append(sha256(project.resolve(
                        "docs/recording-adaptive-selector/candidate_manifest.csv"))).append("\"\n")
                .append("MODEL_ARTIFACT_SHA256 = \"").append(artifactHash).append("\"\n")
                .append("VALIDATION_STATUS = \"")
                .append(java(validation.getProperty("decision"))).append("\"\n")
                .append("CONFIDENCE_THRESHOLD = ").append(fmt(model.threshold)).append("\n\n");
        appendPythonTuple(out, "FEATURE_MEAN", model.stats.mean);
        appendPythonTuple(out, "FEATURE_SCALE", model.stats.scale);
        out.append("# image type, recipe ID, estimator, support, band, filter, mask, "
                + "training gain, intercept, weights, rigid validated\nMODEL_CANDIDATES = (\n");
        for (CandidateModel candidate : model.candidates) {
            RegistrationRecipe recipe = recipes.get(candidate.recipe);
            if (recipe == null) throw new IOException("unknown retained recipe " + candidate.recipe);
            out.append("    (\"").append(candidate.imageType).append("\", \"")
                    .append(candidate.recipe).append("\", \"").append(recipe.estimator.name())
                    .append("\", \"").append(recipe.pixelSupport.name()).append("\", \"")
                    .append(recipe.band().name()).append("\", \"")
                    .append(recipe.preprocessing.name()).append("\", \"")
                    .append(recipe.mask().name()).append("\", ").append(fmt(candidate.trainingGain))
                    .append(", ").append(fmt(candidate.intercept)).append(", (");
            for (int i = 0; i < candidate.weights.length; i++) {
                if (i > 0) out.append(", ");
                out.append(fmt(candidate.weights[i]));
            }
            if (candidate.weights.length == 1) out.append(',');
            out.append("), True),\n");
        }
        out.append(")\n");
        return out.toString();
    }

    private static void appendPythonTuple(StringBuilder out, String name, double[] values) {
        out.append(name).append(" = (\n");
        for (double value : values) out.append("    ").append(fmt(value)).append(",\n");
        out.append(")\n\n");
    }

    private static void appendDoubleArray(StringBuilder out, String name, double[] values) {
        out.append("    public static final double[] ").append(name).append(" = {");
        for (int i = 0; i < values.length; i++) {
            out.append(i == 0 ? "\n            " : ",\n            ").append(fmt(values[i]));
        }
        out.append(values.length == 0 ? "};\n\n" : "\n    };\n\n");
    }

    private static void appendStringArray(StringBuilder out, String name,
            List<CandidateModel> candidates, String ignored) {
        out.append("    static final String[] ").append(name).append(" = {");
        for (int i = 0; i < candidates.size(); i++) {
            out.append(i == 0 ? "\n            \"" : ",\n            \"")
                    .append(java(candidates.get(i).imageType)).append("\"");
        }
        out.append(candidates.isEmpty() ? "};\n\n" : "\n    };\n\n");
    }

    private static void appendRecipeArray(StringBuilder out, String name,
            List<RegistrationRecipe> recipes, String field) {
        out.append("    static final String[] ").append(name).append(" = {");
        for (int i = 0; i < recipes.size(); i++) {
            RegistrationRecipe recipe = recipes.get(i);
            String value = "estimator".equals(field) ? recipe.estimator.name()
                    : "support".equals(field) ? recipe.pixelSupport.name()
                    : "band".equals(field) ? recipe.band().name()
                    : "filter".equals(field) ? recipe.preprocessing.name()
                    : recipe.mask().name();
            out.append(i == 0 ? "\n            \"" : ",\n            \"")
                    .append(value).append("\"");
        }
        out.append(recipes.isEmpty() ? "};\n\n" : "\n    };\n\n");
    }

    private static String java(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value));
        return out.toString();
    }

    private static String join(double[] values) {
        StringBuilder out = new StringBuilder();
        for (double value : values) {
            if (out.length() > 0) out.append(';');
            out.append(fmt(value));
        }
        return out.toString();
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.17g", value) : "NaN";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ---------------------------------------------------------------- data

    static final class Feature {
        final String caseId, series, group, imageType, motionType, reason;
        final boolean valid;
        final double[] values;
        Feature(String caseId, String series, String group, String imageType, String motionType,
                boolean valid, String reason, double[] values) {
            this.caseId = caseId; this.series = series; this.group = group;
            this.imageType = imageType; this.motionType = motionType; this.valid = valid;
            this.reason = reason; this.values = values;
        }
    }

    static final class Row {
        final Feature feature;
        final RecordingAdaptiveSelectorHeadroom.CaseOutcomes outcomes;
        Row(Feature feature, RecordingAdaptiveSelectorHeadroom.CaseOutcomes outcomes) {
            this.feature = feature; this.outcomes = outcomes;
        }
    }

    static final class Stats {
        final double[] mean, scale;
        Stats(double[] mean, double[] scale) { this.mean = mean; this.scale = scale; }
    }

    static final class CandidateGain {
        final String recipe; final double gain;
        CandidateGain(String recipe, double gain) { this.recipe = recipe; this.gain = gain; }
    }

    static final class CandidateModel {
        final String imageType, recipe;
        final double trainingGain, intercept;
        final double[] weights;
        CandidateModel(String imageType, String recipe, double trainingGain, double intercept,
                       double[] weights) {
            this.imageType = imageType; this.recipe = recipe; this.trainingGain = trainingGain;
            this.intercept = intercept; this.weights = weights;
        }
    }

    static final class Prediction {
        final String recipe; final double gain; final boolean ood;
        Prediction(String recipe, double gain, boolean ood) {
            this.recipe = recipe; this.gain = gain; this.ood = ood;
        }
    }

    static final class Model {
        final String kind;
        final double ridge, threshold;
        final Stats stats;
        final List<CandidateModel> candidates = new ArrayList<>();
        Model(String kind, double ridge, double threshold, Stats stats) {
            this.kind = kind; this.ridge = ridge; this.threshold = threshold; this.stats = stats;
        }
        Prediction predict(Feature feature) {
            if ("linear_gain".equals(kind) && (!feature.valid || ood(feature.values))) {
                return new Prediction(null, 0, true);
            }
            double[] x = standardise(feature.values, stats);
            CandidateModel best = null;
            double gain = Double.NEGATIVE_INFINITY;
            for (CandidateModel candidate : candidates) {
                if (!feature.imageType.equals(candidate.imageType)) continue;
                double value = "image_type_rule".equals(kind)
                        ? candidate.trainingGain : candidate.intercept;
                if (!"image_type_rule".equals(kind)) {
                    for (int i = 0; i < x.length; i++) value += candidate.weights[i] * x[i];
                }
                if (value > gain) { gain = value; best = candidate; }
            }
            return best == null ? new Prediction(null, 0, false)
                    : new Prediction(best.recipe, gain, false);
        }
        private boolean ood(double[] raw) {
            double sum = 0; int count = 0;
            for (int i = 0; i < AutomaticRegistrationSelector.CONTINUOUS_FEATURE_COUNT; i++) {
                if (!Double.isFinite(raw[i]) || !Double.isFinite(stats.mean[i])
                        || !Double.isFinite(stats.scale[i]) || stats.scale[i] < 0) return true;
                if (stats.scale[i] == 0) {
                    if (Double.compare(raw[i], stats.mean[i]) != 0) return true;
                    continue;
                }
                double z = (raw[i] - stats.mean[i]) / stats.scale[i];
                if (Math.abs(z) > 8) return true;
                sum += z * z; count++;
            }
            return count > 0 && Math.sqrt(sum / count) > 3;
        }
    }

    static final class Tuning {
        final String outer, imageType;
        final double ridge, threshold, innerError;
        Tuning(String outer, String imageType, double ridge, double threshold,
               double innerError) {
            this.outer = outer; this.imageType = imageType; this.ridge = ridge;
            this.threshold = threshold; this.innerError = innerError;
        }
    }

    static final class Validation {
        final String name;
        final double baseline, selected, improvement, required;
        final boolean primary, type, p90, worst, guard, diagnostics, crop, runtime;
        final boolean pass, safeFallback;
        Validation(String name, double baseline, double selected, double improvement,
                   double required, boolean primary, boolean type, boolean p90,
                   boolean worst, boolean guard, boolean diagnostics, boolean crop,
                   boolean runtime, boolean pass, boolean safeFallback) {
            this.name = name; this.baseline = baseline; this.selected = selected;
            this.improvement = improvement; this.required = required;
            this.primary = primary; this.type = type; this.p90 = p90; this.worst = worst;
            this.guard = guard; this.diagnostics = diagnostics; this.crop = crop;
            this.runtime = runtime; this.pass = pass; this.safeFallback = safeFallback;
        }
        String properties(String prefix) {
            return prefix + "_baseline_primary_px=" + fmt(baseline) + '\n'
                    + prefix + "_selected_primary_px=" + fmt(selected) + '\n'
                    + prefix + "_improvement_px=" + fmt(improvement) + '\n'
                    + prefix + "_required_improvement_px=" + fmt(required) + '\n'
                    + prefix + "_primary_gate=" + primary + '\n'
                    + prefix + "_image_type_gate=" + type + '\n'
                    + prefix + "_p90_gate=" + p90 + '\n'
                    + prefix + "_worst_gate=" + worst + '\n'
                    + prefix + "_recording_guard=" + guard + '\n'
                    + prefix + "_diagnostic_gate=" + diagnostics + '\n'
                    + prefix + "_crop_gate=" + crop + '\n'
                    + prefix + "_runtime_gate=" + runtime + '\n'
                    + prefix + "_all_gates=" + pass + '\n'
                    + prefix + "_safe_fallback=" + safeFallback + '\n';
        }
    }
}
