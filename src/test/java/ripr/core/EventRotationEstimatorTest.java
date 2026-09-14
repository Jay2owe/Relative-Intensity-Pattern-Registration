/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

public class EventRotationEstimatorTest {
    @Test
    public void allCrossPairsRecoverOneGainChangingEvent() {
        double truth = Math.toRadians(2.0);
        FrameSource source = EventRotationFixtures.source(
                8, new int[]{4}, new double[]{truth}, true);
        Registration.Options options = EventRotationFixtures.options(4);

        RotationEventResult result = EventRotationEstimator.estimate(
                source, new int[]{4}, 2, options, null, null);

        assertTrue(result.usable());
        assertEquals(1, result.events.length);
        RotationEventResult.Event event = result.events[0];
        assertEquals(5, event.publicFrame());
        assertEquals(4, event.candidatePairs);
        assertTrue(event.usablePairs >= EventRotationEstimator.MINIMUM_USABLE_PAIRS);
        assertEquals(2, event.firstPreFrame);
        assertEquals(3, event.lastPreFrame);
        assertEquals(4, event.firstPostFrame);
        assertEquals(5, event.lastPostFrame);
        assertEquals(RotationEventResult.Status.OK, event.status);
        assertEquals(truth, event.deltaTheta, Math.toRadians(0.03));
        for (int frame = 0; frame < 4; frame++) {
            assertEquals(0.0, result.frameAngles[frame], 0.0);
        }
        for (int frame = 4; frame < 8; frame++) {
            assertEquals(event.deltaTheta, result.frameAngles[frame], 0.0);
        }
    }

    @Test
    public void severalEventsAccumulateWithoutWithinSegmentJitter() {
        double[] deltas = {Math.toRadians(1.8), Math.toRadians(-0.7)};
        FrameSource source = EventRotationFixtures.source(11, new int[]{4, 8}, deltas);
        Registration.Options options = EventRotationFixtures.options(4, 8);

        RotationEventResult result = EventRotationEstimator.estimate(
                source, new int[]{4, 8}, 2, options, null, null);

        assertTrue(result.usable());
        assertEquals(deltas[0], result.events[0].deltaTheta, Math.toRadians(0.04));
        assertEquals(deltas[1], result.events[1].deltaTheta, Math.toRadians(0.04));
        for (int frame = 4; frame < 8; frame++) {
            assertEquals(result.frameAngles[4], result.frameAngles[frame], 0.0);
        }
        for (int frame = 8; frame < 11; frame++) {
            assertEquals(result.frameAngles[8], result.frameAngles[frame], 0.0);
        }
    }

    @Test
    public void insufficientPairsAreReportedWithoutManufacturingAnAngle() {
        FrameSource source = EventRotationFixtures.source(
                8, new int[]{7}, new double[]{Math.toRadians(2)});
        RotationEventResult result = EventRotationEstimator.estimate(
                source, new int[]{7}, 1, EventRotationFixtures.options(7), null, null);

        assertFalse(result.usable());
        assertEquals(RotationEventResult.Status.INSUFFICIENT_SUPPORT,
                result.events[0].status);
        assertEquals(1, result.events[0].candidatePairs);
        assertTrue(Double.isNaN(result.events[0].deltaTheta));
        try {
            result.requireUsable();
            fail("insufficient event evidence must stop registration");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("at least 3"));
        }
    }

    @Test(expected = CancellationException.class)
    public void cancellationStopsBeforeEventPairWorkContinues() {
        EventRotationEstimator.estimate(
                EventRotationFixtures.source(
                        8, new int[]{4}, new double[]{Math.toRadians(2)}),
                new int[]{4}, 2, EventRotationFixtures.options(4), null, () -> true);
    }
}
