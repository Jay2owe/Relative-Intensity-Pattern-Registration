/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.Transform;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Guards the rigid selector generator before its frozen inputs are produced. */
public class RigidSelectorFactorialBenchmarkTest {

    @Test
    public void everyAngularProfileStaysInsideTheDeclaredPairBound() {
        int frames = Benchmark.FRAMES;
        int[] lags = {1, 2, 4, 8, 16};
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
            double[] angle = new double[frames];
            for (int frame = 0; frame < frames; frame++) {
                angle[frame] = RigidSelectorFactorialBenchmark.rotationDegrees(
                        motion.name(), frame, frames);
                assertTrue(motion.name(), Math.abs(angle[frame]) <= 8.0 + 1e-12);
            }
            assertEquals(motion.name() + " must start at the reference angle", 0, angle[0], 0);
            for (int lag : lags) {
                for (int from = 0; from + lag < frames; from++) {
                    assertTrue(motion.name() + " lag " + lag,
                            Math.abs(angle[from + lag] - angle[from]) <= 10.0 + 1e-12);
                }
            }
        }
    }

    @Test
    public void nativeLengthMotionSamplesTheFrozenPathWithoutChangingFrameCount() {
        int nativeFrames = 21;
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
            int[] frozenX = new int[Benchmark.FRAMES];
            int[] frozenY = new int[Benchmark.FRAMES];
            motion.fill(frozenX, frozenY);
            Transform first = RigidSelectorFactorialBenchmark.nativeTranslation(
                    motion.name(), 0, nativeFrames);
            Transform last = RigidSelectorFactorialBenchmark.nativeTranslation(
                    motion.name(), nativeFrames - 1, nativeFrames);
            assertEquals(0, first.dx, 0);
            assertEquals(0, first.dy, 0);
            assertEquals(frozenX[Benchmark.FRAMES - 1] / (double) Benchmark.FINE,
                    last.dx, 1e-12);
            assertEquals(frozenY[Benchmark.FRAMES - 1] / (double) Benchmark.FINE,
                    last.dy, 1e-12);
        }
    }

    @Test
    public void fortyEightFrameNativeMotionIsExactlyTheOriginalMotion() {
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
            int[] frozenX = new int[Benchmark.FRAMES];
            int[] frozenY = new int[Benchmark.FRAMES];
            motion.fill(frozenX, frozenY);
            for (int frame = 0; frame < Benchmark.FRAMES; frame++) {
                Transform sampled = RigidSelectorFactorialBenchmark.nativeTranslation(
                        motion.name(), frame, Benchmark.FRAMES);
                assertEquals(frozenX[frame] / (double) Benchmark.FINE, sampled.dx, 0);
                assertEquals(frozenY[frame] / (double) Benchmark.FINE, sampled.dy, 0);
                assertEquals(0, sampled.theta, 0);
            }
        }
    }

    @Test
    public void selectorScreenSamplesTheWholeTrajectoryIncludingBothEndpoints() {
        int[] sampled = RigidSelectorFactorialBenchmark.sampledIndices(48, 12);
        assertEquals(12, sampled.length);
        assertEquals(0, sampled[0]);
        assertEquals(47, sampled[sampled.length - 1]);
        for (int i = 1; i < sampled.length; i++) {
            assertTrue(sampled[i] > sampled[i - 1]);
        }
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
            for (int lag : new int[]{1, 2, 4}) {
                for (int from = 0; from + lag < sampled.length; from++) {
                    double first = RigidSelectorFactorialBenchmark.rotationDegrees(
                            motion.name(), sampled[from], 48);
                    double second = RigidSelectorFactorialBenchmark.rotationDegrees(
                            motion.name(), sampled[from + lag], 48);
                    assertTrue(motion + " sampled lag " + lag,
                            Math.abs(second - first) <= 10.0 + 1e-12);
                }
            }
        }
    }

    @Test
    public void combinedTruthUsesExactRigidComposition() {
        Transform translation = new Transform(4.25, -2.5, 0);
        Transform rotation = new Transform(0, 0, Math.toRadians(8));
        Transform combined = translation.then(rotation);
        double[] sequential = new double[2];
        double[] once = new double[2];
        double[] intermediate = new double[2];
        translation.apply(17, 23, 59.5, 59.5, intermediate);
        rotation.apply(intermediate[0], intermediate[1], 59.5, 59.5, sequential);
        combined.apply(17, 23, 59.5, 59.5, once);
        assertEquals(sequential[0], once[0], 1e-12);
        assertEquals(sequential[1], once[1], 1e-12);
    }

    @Test
    public void consecutiveScreenUsesExactRelativeTruthBetweenFrames() {
        Transform[] trajectory = {
                Transform.IDENTITY,
                new Transform(4.25, -2.5, Math.toRadians(3.0)),
                new Transform(-1.75, 5.5, Math.toRadians(-2.0))
        };
        Transform relative = RigidSelectorFactorialBenchmark.pairTruth(trajectory, 1, 2);
        Transform recomposed = trajectory[1].then(relative);
        assertEquals(trajectory[2].dx, recomposed.dx, 1e-12);
        assertEquals(trajectory[2].dy, recomposed.dy, 1e-12);
        assertEquals(trajectory[2].theta, recomposed.theta, 1e-12);
    }

    @Test
    public void splitConfidencePoliciesKeepRecipeAndThresholdDistinct() {
        String low = RigidSelectorFactorialBenchmark.splitConfidenceId("category", 0.001);
        String high = RigidSelectorFactorialBenchmark.splitConfidenceId("category", 0.005);
        assertTrue(low.startsWith("category__min_rotation_gain_"));
        assertTrue(!low.equals(high));
    }

    @Test
    public void warpingIndexReducesToEuclideanErrorAtZeroRotation() {
        int size = 120;
        Transform truth = new Transform(4.25, -2.5, 0);
        Transform estimate = new Transform(3.75, -1.75, 0);
        double expected = Math.hypot(truth.dx - estimate.dx, truth.dy - estimate.dy);
        double measured = ThevenazProtocolBenchmark.warpingIndex(
                ThevenazProtocolBenchmark.Affine2D.rigid(truth, size, size),
                ThevenazProtocolBenchmark.Affine2D.rigid(estimate, size, size),
                size, size, ThevenazProtocolBenchmark.Region.CENTRAL_50);
        assertEquals(expected, measured, 1e-9);
    }

    @Test
    public void gridAndNewtonCorrelationAreExactAliasesWhenRotationIsFitted() {
        int size = 120;
        float[] base = ThevenazProtocolBenchmark.rotationFixture(size, size);
        ThevenazProtocolBenchmark.Frames frames = ThevenazProtocolBenchmark.generate(
                base, size, size, new Transform(1.25, -0.75, Math.toRadians(2.5)));
        PairAligner.Fit grid = ThevenazProtocolBenchmark.estimateInternalFit(
                PairEstimator.Kind.AREA_CORRELATION, frames, true, Math.toRadians(10));
        PairAligner.Fit newton = ThevenazProtocolBenchmark.estimateInternalFit(
                PairEstimator.Kind.AREA_CORRELATION_NEWTON, frames, true, Math.toRadians(10));
        assertEquals(grid.transform, newton.transform);
        assertEquals(grid.status, newton.status);
        assertEquals(grid.iterations, newton.iterations);
        assertEquals(grid.residualBefore, newton.residualBefore, 0);
        assertEquals(grid.residualAfter, newton.residualAfter, 0);
        assertEquals(grid.logGain, newton.logGain, 0);
        assertEquals(grid.validFraction, newton.validFraction, 0);
    }
}
