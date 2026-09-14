/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.ImageType;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pins each predeclared final promotion gate independently. */
public class RecordingAdaptiveSelectorFinalGatesTest {
    @Test
    public void passingFixturePassesEveryGate() {
        assertTrue(evaluate(fixture(0.80, null, -1)).pass);
    }

    @Test
    public void primaryMeanGateIsBinding() {
        assertFalse(evaluate(fixture(0.96, null, -1)).validation.primary);
    }

    @Test
    public void tailGateIsBinding() {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = fixture(0.80, null, -1);
        selected = replaceAll(selected, 0.80, 1.006, 1.0, true, -1, 1.0, 1.0);
        assertFalse(evaluate(selected).validation.p90);
    }

    @Test
    public void worstGateIsBinding() {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = fixture(0.80, null, -1);
        selected = replaceAll(selected, 0.80, 1.0, 1.051, true, -1, 1.0, 1.0);
        assertFalse(evaluate(selected).validation.worst);
    }

    @Test
    public void perImageRegressionGateIsBinding() {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = fixture(0.80, null, -1);
        String id = ImageType.values()[0].name();
        selected.put(id, outcome(id, ImageType.values()[0], 1.003, 1.0, 1.0,
                true, -1, 1.0, 1.0));
        assertFalse(evaluate(selected).validation.type);
    }

    @Test
    public void recordingGuardIsBinding() {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = fixture(0.80, null, -1);
        String id = ImageType.values()[0].name();
        selected.put(id, outcome(id, ImageType.values()[0], 2.01, 1.0, 1.0,
                true, -1, 1.0, 1.0));
        assertFalse(evaluate(selected).validation.guard);
    }

    @Test
    public void failedStatusGateIsBinding() {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = fixture(0.80, null, -1);
        String id = ImageType.values()[0].name();
        selected.put(id, outcome(id, ImageType.values()[0], 0.80, 1.0, 1.0,
                false, -1, 1.0, 1.0));
        assertFalse(evaluate(selected).status);
    }

    @Test
    public void eachDiagnosticGateIsBinding() {
        for (int diagnostic = 0; diagnostic < 7; diagnostic++) {
            RecordingAdaptiveSelectorFinalReport.GateResult result = evaluate(
                    fixture(0.80, null, diagnostic));
            assertFalse(RecordingAdaptiveSelectorFinalReport.DIAGNOSTIC_NAMES[diagnostic],
                    result.diagnostics[diagnostic]);
            assertFalse(result.pass);
        }
    }

    @Test
    public void cropGateIsBinding() {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = fixture(0.80, null, -1);
        String id = ImageType.values()[0].name();
        selected.put(id, outcome(id, ImageType.values()[0], 0.80, 1.0, 1.0,
                true, -1, 0.979, 1.0));
        assertFalse(evaluate(selected).validation.crop);
    }

    @Test
    public void runtimeGateIncludesCompleteWorkflow() {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected = fixture(0.80, null, -1);
        String id = ImageType.values()[0].name();
        selected.put(id, outcome(id, ImageType.values()[0], 0.80, 1.0, 1.0,
                true, -1, 1.0, 1.501));
        assertFalse(evaluate(selected).validation.runtime);
    }

    private static RecordingAdaptiveSelectorFinalReport.GateResult evaluate(
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> selected) {
        return RecordingAdaptiveSelectorFinalReport.evaluate("fixture", selected,
                fixture(1.0, null, -1));
    }

    private static Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> fixture(
            double median, ImageType ignored, int diagnostic) {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> out = new LinkedHashMap<>();
        for (ImageType type : ImageType.values()) {
            out.put(type.name(), outcome(type.name(), type, median, 1.0, 1.0,
                    true, diagnostic, 1.0, 1.0));
        }
        return out;
    }

    private static Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> replaceAll(
            Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> input, double median,
            double p90, double worst, boolean ok, int diagnostic, double crop, double runtime) {
        Map<String, RecordingAdaptiveSelectorHeadroom.Outcome> out = new LinkedHashMap<>();
        for (String id : input.keySet()) {
            out.put(id, outcome(id, ImageType.valueOf(id), median, p90, worst,
                    ok, diagnostic, crop, runtime));
        }
        return out;
    }

    private static RecordingAdaptiveSelectorHeadroom.Outcome outcome(String id, ImageType type,
            double median, double p90, double worst, boolean ok, int diagnostic,
            double crop, double runtime) {
        int[] counts = new int[7];
        if (diagnostic >= 0) counts[diagnostic] = 1;
        return new RecordingAdaptiveSelectorHeadroom.Outcome(id, type.name(), "g_" + id,
                "recipe", ok, median, p90, worst, counts, crop, runtime);
    }
}
