/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import ripr.api.RelativeIntensityPatternParameters;
import org.junit.Test;

import static org.junit.Assert.*;

public class EventRotationContractTest {
    @Test
    public void publicFramesConvertToZeroBasedExactlyOnceAndAreDefensive() {
        int[] callerEvents = {5, 8};
        RelativeIntensityPatternParameters parameters = RelativeIntensityPatternParameters.builder()
                .rotationMode(RotationMode.KNOWN_EVENTS)
                .rotationEventFrames(callerEvents)
                .rotationEventWindow(2)
                .build();
        callerEvents[0] = 99;

        assertEquals(RotationMode.KNOWN_EVENTS, parameters.rotationMode);
        assertTrue(parameters.fitRotation);
        assertFalse(parameters.incrementalRotation);
        assertArrayEquals(new int[]{5, 8}, parameters.rotationEventFrames());
        int[] exposed = parameters.rotationEventFrames();
        exposed[0] = 77;
        assertArrayEquals(new int[]{5, 8}, parameters.rotationEventFrames());

        Registration.Options core = parameters.registrationOptions();
        assertArrayEquals(new int[]{4, 7}, core.rotationEventFrames);
        assertFalse("ordinary pairs must not search angle", core.aligner.fitRotation);
        assertArrayEquals(new int[]{5, 8}, parameters.toBuilder().build().rotationEventFrames());
    }

    @Test
    public void cumulativeFixtureAnglesChangeOnlyAtEvents() {
        double first = Math.toRadians(2.0);
        double second = Math.toRadians(-0.75);
        double[] angles = EventRotationFixtures.trajectory(
                10, new int[]{4, 7}, new double[]{first, second});
        for (int frame = 0; frame < 4; frame++) assertEquals(0.0, angles[frame], 0.0);
        for (int frame = 4; frame < 7; frame++) assertEquals(first, angles[frame], 0.0);
        for (int frame = 7; frame < 10; frame++) {
            assertEquals(first + second, angles[frame], 0.0);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void explicitLegacyAndEventModesCannotContradict() {
        RelativeIntensityPatternParameters.builder().fitRotation(true)
                .rotationMode(RotationMode.KNOWN_EVENTS)
                .rotationEventFrames(5).build();
    }

    @Test
    public void invalidPublicEventsNameTheOffendingFrame() {
        assertInvalid(1);
        assertInvalid(5, 5);
        assertInvalid(6, 4);
    }

    @Test
    public void runtimeRejectsAnEventBeyondTheRecording() {
        Registration.Options options = EventRotationFixtures.options(8);
        try {
            Registration.run(EventRotationFixtures.source(
                    8, new int[]{4}, new double[]{Math.toRadians(2)}),
                    options, null, null);
            fail("expected an out-of-range event to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("frame 9"));
        }
    }

    @Test
    public void coreEventFieldsCannotSilentlyAffectAnotherMode() {
        Registration.Options options = new Registration.Options();
        options.rotationEventFrames = new int[]{4};
        try {
            Registration.run(EventRotationFixtures.source(
                    8, new int[]{4}, new double[]{Math.toRadians(2)}),
                    options, null, null);
            fail("event fields outside known-events mode must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("require rotation mode known_events"));
        }
    }

    @Test
    public void switchingToKnownEventsClearsContinuousOnlySelectorMetadata() {
        RelativeIntensityPatternParameters continuous = RelativeIntensityPatternParameters.builder()
                .fitRotation(true)
                .rotationRecipeId("old_continuous_recipe")
                .globalRotationProposal(true)
                .rotationSelectorStep90(3.0)
                .build();
        RelativeIntensityPatternParameters known = continuous.toBuilder()
                .rotationMode(RotationMode.KNOWN_EVENTS)
                .rotationEventFrames(5)
                .build();
        assertEquals("", known.rotationRecipeId);
        assertFalse(known.globalRotationProposal);
        assertTrue(Double.isNaN(known.rotationSelectorStep90));
    }

    private static void assertInvalid(int... events) {
        try {
            RelativeIntensityPatternParameters.builder().rotationMode(RotationMode.KNOWN_EVENTS)
                    .rotationEventFrames(events).build();
            fail("expected invalid events to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(Integer.toString(events[events.length - 1])));
        }
    }
}
