/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RecordingAdaptiveSelectorTrainingTest {
    @Test
    public void forbiddenOutcomeColumnIsRejected() throws Exception {
        Path file = Files.createTempFile("selector-features", ".csv");
        try {
            Files.write(file, "case_id,truth_error\na,1\n".getBytes(StandardCharsets.UTF_8));
            try {
                RecordingAdaptiveSelectorTraining.readFeatures(file);
                throw new AssertionError("truth column was accepted");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("forbidden feature column"));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void frozenTrainingGridsAreComplete() {
        assertTrue(java.util.Arrays.equals(new double[]{0.1, 1, 10, 100, 1000},
                RecordingAdaptiveSelectorTraining.RIDGES));
        assertTrue(java.util.Arrays.equals(new double[]{0, 0.0005, 0.001, 0.002, 0.005,
                        0.01, 0.02, 0.05, 0.1},
                RecordingAdaptiveSelectorTraining.THRESHOLDS));
    }

    @Test
    public void generatedSourceCarriesRuntimeReadContractAndAllFeatures() throws Exception {
        int count = ripr.api.AutomaticRegistrationSelector.FEATURE_COUNT;
        RecordingAdaptiveSelectorTraining.Model model =
                new RecordingAdaptiveSelectorTraining.Model("image_type_rule", 0, 0,
                        new RecordingAdaptiveSelectorTraining.Stats(
                                new double[count], new double[count]));
        Properties validation = new Properties();
        validation.setProperty("model_version", "fixture_model");
        validation.setProperty("decision", "CATEGORY_RECOMMENDATION");
        String source = RecordingAdaptiveSelectorTraining.modelSource(
                Paths.get("").toAbsolutePath(), model, validation,
                "0123456789abcdef");
        assertTrue(source.contains("FEATURE_COUNT = readAtRuntime(48)"));
        assertTrue(source.contains("MODEL_VERSION = readAtRuntime(\"fixture_model\")"));
        assertTrue(source.contains("FEATURE_CONTRACT_VERSION = readAtRuntime(\"recording_evidence_v2\")"));
        assertTrue(source.contains("MODEL_ARTIFACT_SHA256 = readAtRuntime(\"0123456789abcdef\")"));
        String python = RecordingAdaptiveSelectorTraining.modelPythonSource(
                Paths.get("").toAbsolutePath(), model, validation,
                "0123456789abcdef");
        assertTrue(python.contains("MODEL_VERSION = \"fixture_model\""));
        assertTrue(python.contains("MODEL_ARTIFACT_SHA256 = \"0123456789abcdef\""));
        assertTrue(python.contains("FEATURE_CONTRACT_VERSION = \"recording_evidence_v2\""));
    }

    @Test
    public void selectorRuntimeIncludesPilotEvidenceAndDecision() throws Exception {
        Path file = Files.createTempFile("selector-timings", ".csv");
        try {
            String csv = RecordingAdaptiveSelectorBenchmark.TIMING_HEADER + "\n"
                    + "case,1.25,0.50,0.025,"
                    + RecordingAdaptiveSelectorBenchmark.TIMING_CONTRACT_VERSION + "\n";
            Files.write(file, csv.getBytes(StandardCharsets.UTF_8));
            assertEquals(1.775,
                    RecordingAdaptiveSelectorTraining.readSelectorTimes(file).get("case"),
                    1e-12);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void fixedPolicyDoesNotDependOnRecordingEvidence() {
        int count = ripr.api.AutomaticRegistrationSelector.FEATURE_COUNT;
        RecordingAdaptiveSelectorTraining.Model model =
                new RecordingAdaptiveSelectorTraining.Model("image_type_rule", 0, 0,
                        new RecordingAdaptiveSelectorTraining.Stats(
                                new double[count], new double[count]));
        model.candidates.add(new RecordingAdaptiveSelectorTraining.CandidateModel(
                "PHASE_CONTRAST", "fixed_recipe", 0.02, 0.02, new double[count]));
        RecordingAdaptiveSelectorTraining.Feature invalid =
                new RecordingAdaptiveSelectorTraining.Feature(
                        "case", "series", "group", "PHASE_CONTRAST",
                        "SUBPIXEL_RANDOM_WALK", false, "invalid fixture",
                        new double[count]);
        RecordingAdaptiveSelectorTraining.Prediction prediction = model.predict(invalid);
        assertEquals("fixed_recipe", prediction.recipe);
        assertTrue(!prediction.ood);
    }

    @Test
    public void fixedOverrideAcceptsOnlyCropAndRuntimeTradeoffs() throws Exception {
        Properties report = eligibleOverrideReport();
        RecordingAdaptiveSelectorTraining.requireFixedOverrideEligible(report);

        report.setProperty("fixed_p90_gate", "false");
        try {
            RecordingAdaptiveSelectorTraining.requireFixedOverrideEligible(report);
            throw new AssertionError("failed accuracy gate was overridden");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("fixed_p90_gate"));
        }
    }

    private static Properties eligibleOverrideReport() {
        Properties report = new Properties();
        report.setProperty("final_policy", "CATEGORY_RECOMMENDATION");
        report.setProperty("fixed_improvement_px", "0.005");
        report.setProperty("fixed_crop_gate", "false");
        report.setProperty("fixed_runtime_gate", "false");
        for (String gate : new String[]{"fixed_primary_gate", "fixed_image_type_gate",
                "fixed_p90_gate", "fixed_worst_gate", "fixed_recording_guard",
                "fixed_diagnostic_gate", "fixed_status_gate", "fixed_refused_pairs_gate",
                "fixed_non_converged_pairs_gate", "fixed_shift_bound_pairs_gate",
                "fixed_rotation_bound_pairs_gate", "fixed_both_bound_pairs_gate",
                "fixed_repaired_frames_gate", "fixed_unsupported_frames_gate"}) {
            report.setProperty(gate, "true");
        }
        return report;
    }
}
