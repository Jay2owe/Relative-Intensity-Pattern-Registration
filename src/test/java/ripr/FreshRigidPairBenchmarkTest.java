/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.PairEstimator;
import ripr.core.Transform;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Guards the A11 matched-frame truth before its hashed fixtures are generated. */
public class FreshRigidPairBenchmarkTest {

    @Test
    public void matchedPairTruthIsRecoveredWithTheDeclaredDirection() {
        int size = 192;
        float[] base = ThevenazProtocolBenchmark.rotationFixture(size, size);
        FreshRigidPairBenchmark.PairPixels pixels = FreshRigidPairBenchmark.pixels(
                base, size, size, "STEADY_DIRECTIONAL_DRIFT", "RIGID_CLEAN", 1, 10, 21);
        ThevenazProtocolBenchmark.Frames frames = new ThevenazProtocolBenchmark.Frames(
                pixels.test, pixels.reference, size, size, pixels.truth);
        Transform estimate = ThevenazProtocolBenchmark.estimateInternal(
                PairEstimator.Kind.LOG_RATIO_FIT, frames, true);
        assertEquals(pixels.truth.thetaDegrees(), estimate.thetaDegrees(), 0.2);
        assertEquals(pixels.truth.dx, estimate.dx, 0.25);
        assertEquals(pixels.truth.dy, estimate.dy, 0.25);
    }

    @Test
    public void zeroRotationControlHasExactZeroAngleAndGainIsPhotometricOnly() {
        int size = 96;
        float[] base = ThevenazProtocolBenchmark.rotationFixture(size, size);
        FreshRigidPairBenchmark.PairPixels clean = FreshRigidPairBenchmark.pixels(
                base, size, size, "SUBPIXEL_RANDOM_WALK", "RIGID_CLEAN", -1, 20, 21);
        FreshRigidPairBenchmark.PairPixels zero = FreshRigidPairBenchmark.pixels(
                base, size, size, "SUBPIXEL_RANDOM_WALK", "ZERO_ROTATION_CLEAN", -1, 20, 21);
        FreshRigidPairBenchmark.PairPixels gain = FreshRigidPairBenchmark.pixels(
                base, size, size, "SUBPIXEL_RANDOM_WALK", "RIGID_GAIN_FADE_0_5", -1, 20, 21);
        assertEquals(0, zero.truth.theta, 0);
        assertEquals(clean.truth, gain.truth);
        assertEquals(0.5, gain.gain, 0);
        assertTrue(clean.truth.theta != 0);
    }

    @Test
    public void translationArmUsesASeparatelyGeneratedZeroAnglePair() {
        int size = 160;
        float[] base = ThevenazProtocolBenchmark.rotationFixture(size, size);
        FreshRigidPairBenchmark.PairPixels pixels = FreshRigidPairBenchmark.translationPixels(
                base, size, size, "STEADY_DIRECTIONAL_DRIFT", "RIGID_GAIN_FADE_0_5", 10, 21);
        ThevenazProtocolBenchmark.Frames frames = new ThevenazProtocolBenchmark.Frames(
                pixels.test, pixels.reference, size, size, pixels.truth);
        Transform estimate = ThevenazProtocolBenchmark.estimateInternal(
                PairEstimator.Kind.LOG_RATIO_FIT, frames, false);
        assertEquals(0, pixels.truth.theta, 0);
        assertEquals(pixels.truth.dx, estimate.dx, 0.1);
        assertEquals(pixels.truth.dy, estimate.dy, 0.1);
        assertEquals(0.75, pixels.gain, 0);
    }

    @Test
    public void confidenceGridIncludesEveryFrozenA05Threshold() {
        String previous = System.getProperty("rigid.rotationGains");
        try {
            System.clearProperty("rigid.rotationGains");
            double[] thresholds = FreshRigidPairBenchmark.confidenceThresholds();
            assertEquals(11, thresholds.length);
            assertEquals(0, thresholds[0], 0);
            assertEquals(0.05, thresholds[10], 0);
            assertEquals("global_rigid_min_gain_0_000250000000000",
                    FreshRigidPairBenchmark.confidenceId(0.00025));
        } finally {
            if (previous == null) System.clearProperty("rigid.rotationGains");
            else System.setProperty("rigid.rotationGains", previous);
        }
    }
}
