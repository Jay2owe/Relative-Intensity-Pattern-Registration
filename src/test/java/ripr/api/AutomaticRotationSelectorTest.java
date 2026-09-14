/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import org.junit.Test;

import static org.junit.Assert.*;

public class AutomaticRotationSelectorTest {
    @Test
    public void phaseBoundaryIsStrictAndFrozen() {
        assertFalse(AutomaticRotationSelector.needsGlobalPhaseProposal(1.9999));
        assertFalse(AutomaticRotationSelector.needsGlobalPhaseProposal(2.0));
        assertTrue(AutomaticRotationSelector.needsGlobalPhaseProposal(2.0001));
    }

    @Test
    public void everyNonPhaseTypeResolvesToItsFrozenReplayablePolicy() {
        for (ImageType type : ImageType.values()) {
            if (type == ImageType.PHASE_CONTRAST) continue;
            RelativeIntensityPatternParameters base = RelativeIntensityPatternParameters.builder()
                    .recommendation(type, MotionType.CURVED_OSCILLATING_DRIFT)
                    .fitRotation(true).build();
            AutomaticRotationSelector.Result selected =
                    AutomaticRotationSelector.select(base, null);
            assertFalse(selected.parameters.rotationRecipeId.isEmpty());
            assertTrue(selected.parameters.fitRotation);
            assertNotNull(AutomaticRotationSelector.fromResolved(selected.parameters));
        }
    }

    @Test
    public void phaseGlobalPlanReplaysWithoutRemeasuringEvidence() {
        RelativeIntensityPatternParameters resolved = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST,
                        MotionType.CURVED_OSCILLATING_DRIFT)
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(true)
                .rotationRecipeId(
                        "support_all__band_no_top_25__filter_none__mask_none")
                .globalRotationProposal(true)
                .rotationSelectorStep90(3.29574)
                .minimumRotationResidualGain(0.005)
                .build();
        AutomaticRotationSelector.Result replay =
                AutomaticRotationSelector.fromResolved(resolved);
        assertNotNull(replay);
        assertTrue(replay.globalProposalComparison);
        assertEquals(0.005, replay.minimumResidualGain, 0.0);
        assertEquals(3.29574, replay.provisionalStep90, 0.0);
        assertEquals("support_all__band_no_top_25__filter_none__mask_none",
                replay.recipe.id());
    }
}
