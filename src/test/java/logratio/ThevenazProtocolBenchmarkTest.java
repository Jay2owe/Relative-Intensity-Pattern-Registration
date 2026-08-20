/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.core.Transform;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Gates the metric and the previously unproven rotation path before the protocol is interpreted. */
public class ThevenazProtocolBenchmarkTest {

    /** The Stage 1 exit gate: the general definition and translation shortcut agree bit exactly. */
    @Test
    public void generalWarpingIndexExactlyMatchesTranslationShortcut() {
        double trueDx = 4.25;
        double trueDy = -2.5;
        double estimatedDx = -1.75;
        double estimatedDy = 0.125;
        double shortcut = ThevenazProtocolBenchmark.translationWarpingShortcut(
                trueDx, trueDy, estimatedDx, estimatedDy);
        double general = ThevenazProtocolBenchmark.warpingIndex(
                ThevenazProtocolBenchmark.Affine2D.translation(trueDx, trueDy),
                ThevenazProtocolBenchmark.Affine2D.translation(estimatedDx, estimatedDy),
                256, 256, ThevenazProtocolBenchmark.Region.FULL_FRAME);
        assertEquals("general minus shortcut must print as 0.000e+00", 0.0, general - shortcut, 0.0);
    }

    @Test
    public void affineLandmarksRecoverAKnownGeneralMap() {
        double[][] from = {{13, 7}, {5, 91}, {83, 74}};
        ThevenazProtocolBenchmark.Affine2D truth =
                new ThevenazProtocolBenchmark.Affine2D(1.02, 0.04, -0.03, 0.98, 3.2, -4.1);
        double[][] to = new double[3][2];
        for (int i = 0; i < from.length; i++) {
            to[i][0] = truth.a00 * from[i][0] + truth.a01 * from[i][1] + truth.tx;
            to[i][1] = truth.a10 * from[i][0] + truth.a11 * from[i][1] + truth.ty;
        }
        ThevenazProtocolBenchmark.Affine2D recovered =
                ThevenazProtocolBenchmark.Affine2D.fromPointPairs(from, to);
        assertEquals(truth.a00, recovered.a00, 1e-12);
        assertEquals(truth.a01, recovered.a01, 1e-12);
        assertEquals(truth.a10, recovered.a10, 1e-12);
        assertEquals(truth.a11, recovered.a11, 1e-12);
        assertEquals(truth.tx, recovered.tx, 1e-12);
        assertEquals(truth.ty, recovered.ty, 1e-12);
    }

    @Test
    public void collinearTurboRegLandmarksRecoverAKnownRigidMap() {
        double[][] from = {{128, 128}, {128, 39}, {128, 217}};
        double angle = Math.toRadians(4.0);
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        ThevenazProtocolBenchmark.Affine2D truth =
                new ThevenazProtocolBenchmark.Affine2D(c, -s, s, c, 2.3, -1.7);
        double[][] to = new double[from.length][2];
        for (int i = 0; i < from.length; i++) {
            to[i][0] = truth.a00 * from[i][0] + truth.a01 * from[i][1] + truth.tx;
            to[i][1] = truth.a10 * from[i][0] + truth.a11 * from[i][1] + truth.ty;
        }
        ThevenazProtocolBenchmark.Affine2D recovered =
                ThevenazProtocolBenchmark.Affine2D.rigidFromPointPairs(from, to);
        assertEquals(truth.a00, recovered.a00, 1e-12);
        assertEquals(truth.a01, recovered.a01, 1e-12);
        assertEquals(truth.a10, recovered.a10, 1e-12);
        assertEquals(truth.a11, recovered.a11, 1e-12);
        assertEquals(truth.tx, recovered.tx, 1e-12);
        assertEquals(truth.ty, recovered.ty, 1e-12);
    }

    @Test
    public void randomTransformationsAreSeededSharedAndInsidePublishedRanges() {
        List<ThevenazProtocolBenchmark.Trial> first = ThevenazProtocolBenchmark.trials(
                ThevenazProtocolBenchmark.RANDOM_SEED, 100, true);
        List<ThevenazProtocolBenchmark.Trial> second = ThevenazProtocolBenchmark.trials(
                ThevenazProtocolBenchmark.RANDOM_SEED, 100, true);
        assertEquals(100, first.size());
        for (int i = 0; i < first.size(); i++) {
            Transform a = first.get(i).half;
            Transform b = second.get(i).half;
            assertEquals(a, b);
            assertTrue(Math.abs(a.dx) <= ThevenazProtocolBenchmark.HALF_TRANSLATION_RANGE_PIXELS);
            assertTrue(Math.abs(a.dy) <= ThevenazProtocolBenchmark.HALF_TRANSLATION_RANGE_PIXELS);
            assertTrue(Math.abs(a.thetaDegrees())
                    <= ThevenazProtocolBenchmark.HALF_ROTATION_RANGE_DEGREES);
            assertTrue(Math.abs(first.get(i).truth.thetaDegrees()) <= 10.0);
        }
    }

    /** The beta(7) prefilter and sampler must interpolate, not merely approximate, input samples. */
    @Test
    public void degreeSevenSplineReconstructsItsSamplesAtIdentity() {
        int width = 31;
        int height = 29;
        float[] samples = ThevenazProtocolBenchmark.rotationFixture(width, height);
        double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                samples, width, height);
        float[] reconstructed = ThevenazProtocolBenchmark.spline7Warp(
                coefficients, width, height, new Transform(0, 0, 0));
        for (int i = 0; i < samples.length; i++) {
            assertEquals("sample " + i, samples[i], reconstructed[i], 1e-3);
        }
    }

    /** Stage 3 gate 1: recover a known rotation and translation to the declared tolerances. */
    @Test
    public void rotationRecoveryPassesItsPredeclaredSyntheticGate() {
        ThevenazProtocolBenchmark.RotationGate gate =
                ThevenazProtocolBenchmark.rotationGate();
        assertTrue("rotation error was " + gate.rotationErrorDegrees,
                gate.rotationErrorDegrees
                        <= ThevenazProtocolBenchmark.ROTATION_GATE_TOLERANCE_DEGREES);
        assertTrue("translation error was " + gate.translationErrorPixels,
                gate.translationErrorPixels
                        <= ThevenazProtocolBenchmark.ROTATION_GATE_TRANSLATION_TOLERANCE_PIXELS);
    }

    /** Stage 3 gate 2: enabling rotation must not damage the pure-translation answer. */
    @Test
    public void rotationFitDoesNotDegradePureTranslation() {
        ThevenazProtocolBenchmark.RotationGate gate =
                ThevenazProtocolBenchmark.rotationGate();
        assertTrue("fit off/on errors were " + gate.zeroRotationErrorWithoutFit + " / "
                        + gate.zeroRotationErrorWithFit,
                gate.zeroRotationErrorWithFit <= gate.zeroRotationErrorWithoutFit
                        + ThevenazProtocolBenchmark.ZERO_ROTATION_ALLOWED_DEGRADATION_PIXELS);
        assertTrue(gate.passed);
    }

    @Test
    public void publishedConstantAndToleranceWereFixedAtTableThreeValues() {
        assertEquals(0.0009, ThevenazProtocolBenchmark.PUBLISHED_ML_STAR_3_WARPING_INDEX, 0.0);
        assertEquals(0.00045, ThevenazProtocolBenchmark.STAGE_ZERO_MIN, 0.0);
        assertEquals(0.0018, ThevenazProtocolBenchmark.STAGE_ZERO_MAX, 0.0);
    }

    @Test
    public void gaussianNoiseIsDeterministicIndependentAndAtTheRequestedSnr() {
        int width = 256;
        int height = 256;
        float[] signal = ThevenazProtocolBenchmark.rotationFixture(width, height);
        ThevenazProtocolBenchmark.Frames clean = new ThevenazProtocolBenchmark.Frames(
                signal.clone(), signal.clone(), width, height, new Transform(0, 0, 0));
        double signalVariance = ThevenazProtocolBenchmark.sampleVariance(signal);
        double sigma = ThevenazProtocolBenchmark.noiseSigma(signalVariance, 0);
        long testSeed = ThevenazProtocolBenchmark.noiseSeed(7, 5, false);
        long referenceSeed = ThevenazProtocolBenchmark.noiseSeed(7, 5, true);
        ThevenazProtocolBenchmark.Frames first = ThevenazProtocolBenchmark.addGaussianNoise(
                clean, sigma, testSeed, referenceSeed);
        ThevenazProtocolBenchmark.Frames second = ThevenazProtocolBenchmark.addGaussianNoise(
                clean, sigma, testSeed, referenceSeed);

        assertArrayEquals(first.test, second.test, 0f);
        assertArrayEquals(first.reference, second.reference, 0f);
        assertFalse(java.util.Arrays.equals(first.test, first.reference));
        assertEquals(0.0, ThevenazProtocolBenchmark.snrDb(signalVariance,
                ThevenazProtocolBenchmark.differenceVariance(clean.test, first.test)), 0.05);
        assertEquals(0.0, ThevenazProtocolBenchmark.snrDb(signalVariance,
                ThevenazProtocolBenchmark.differenceVariance(clean.reference, first.reference)),
                0.05);
    }
}
