/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingAdaptiveSelectorHeadroomTest {
    private static RecordingAdaptiveSelectorHeadroom.Outcome outcome(
            String id, String group, double error) {
        return new RecordingAdaptiveSelectorHeadroom.Outcome(id, "PHASE_CONTRAST", group,
                "recipe", true, error, error, error, 0, 1.0, 1.0);
    }

    @Test
    public void duplicateDerivedCasesDoNotChangeSourceWeight() {
        double balanced = RecordingAdaptiveSelectorHeadroom.balanced(Arrays.asList(
                outcome("a", "source_a", 1), outcome("b", "source_a", 3),
                outcome("c", "source_b", 10)));
        double duplicated = RecordingAdaptiveSelectorHeadroom.balanced(Arrays.asList(
                outcome("a", "source_a", 1), outcome("b", "source_a", 3),
                outcome("a2", "source_a", 1), outcome("b2", "source_a", 3),
                outcome("c", "source_b", 10)));
        assertEquals(balanced, duplicated, 0);
    }

    @Test
    public void failedAndReliabilityRegressingCandidatesAreUnsafe() {
        RecordingAdaptiveSelectorHeadroom.Outcome baseline = outcome("a", "g", 0.1);
        RecordingAdaptiveSelectorHeadroom.Outcome failed =
                new RecordingAdaptiveSelectorHeadroom.Outcome("a", "PHASE_CONTRAST", "g",
                        "failed", false, 0.01, 0.01, 0.01, 0, 1, 1);
        RecordingAdaptiveSelectorHeadroom.Outcome repaired =
                new RecordingAdaptiveSelectorHeadroom.Outcome("a", "PHASE_CONTRAST", "g",
                        "repaired", true, 0.01, 0.01, 0.01, 1, 1, 1);
        assertFalse(RecordingAdaptiveSelectorHeadroom.safe(failed, baseline));
        assertFalse(RecordingAdaptiveSelectorHeadroom.safe(repaired, baseline));
    }

    @Test
    public void gainDispersionCountsOnlyPracticallyMeaningfulImprovements() {
        RecordingAdaptiveSelectorHeadroom.Outcome baseline = outcome("a", "g", 0.10);
        assertFalse(RecordingAdaptiveSelectorHeadroom.meaningfulGain(
                outcome("a", "g", 0.099), baseline));
        assertTrue(RecordingAdaptiveSelectorHeadroom.meaningfulGain(
                outcome("a", "g", 0.094), baseline));
        assertFalse(RecordingAdaptiveSelectorHeadroom.meaningfulGain(baseline, baseline));
    }
}
