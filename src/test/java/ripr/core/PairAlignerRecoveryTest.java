/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Does the criterion recover a known displacement, and does the robust norm earn its place?
 *
 * <p><b>This class is the plugin's kill criterion, not a unit test.</b> The whole case for building it
 * rests on two claims: that the log-ratio criterion is exactly invariant to a global intensity gain, and
 * that a redescending norm keeps working when the frames contain genuine change. Neither is novel — the
 * first is Woods 1992 and the second is the argument behind RASL (Peng et al. 2012) — but "known in the
 * literature" is not "true in this implementation". If {@link #tukeyBeatsLeastSquaresOnSparseChange}
 * fails, the differentiator is unproven and the plugin should not ship.
 *
 * <p>Every threshold below is set from measured behaviour on 2026-08-07, with the measurement in the
 * comment, so a future regression shows up as a number moving rather than as a vague failure.
 */
public class PairAlignerRecoveryTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;
    private static final double DX = 2.4;
    private static final double DY = -1.7;

    private static PairAligner.Fit align(float[] a, float[] b, RobustNorm norm) {
        PairAligner.Options o = new PairAligner.Options();
        o.norm = norm;
        int levels = o.levelsFor(W, H);
        return PairAligner.align(Synth.pyramid(a, W, H, levels),
                Synth.pyramid(b, W, H, levels), o);
    }

    private static double error(PairAligner.Fit f) {
        return Math.hypot(f.transform.dx - DX, f.transform.dy - DY);
    }

    @Test
    public void boundedAngularInitializerIncludesZeroAndBothEndpoints() {
        double bound = Math.toRadians(7.0);
        double radius = 48.0;
        double[] candidates = PairAligner.angularCandidates(bound, radius);
        assertEquals(0.0, candidates[0], 0.0);
        assertEquals(-bound, candidates[candidates.length - 2], 1e-15);
        assertEquals(bound, candidates[candidates.length - 1], 1e-15);
        for (int i = 3; i < candidates.length; i += 2) {
            assertTrue("angular edge step exceeded one coarse pixel",
                    Math.abs(candidates[i] - candidates[i - 2]) * radius <= 1.0 + 1e-12);
        }
    }

    /** Escape must interrupt an expensive rigid fit inside the pair solver, not after the pair. */
    @Test(expected = CancellationException.class)
    public void rigidFitCanBeCancelledBeforeItCompletes() {
        PairAligner.Options o = new PairAligner.Options();
        o.fitRotation = true;
        o.maxRotation = Math.toRadians(12.0);
        o.cancellation = () -> true;
        float[] frame = Synth.frame(W, H, 0, 0);
        int levels = o.levelsFor(W, H);
        PairAligner.align(Synth.pyramid(frame, W, H, levels),
                Synth.pyramid(frame, W, H, levels), o);
    }

    @Test
    public void translationFirstComparisonRecoversSupportedRotationAndCanDeclineIt() {
        int width = 128;
        int height = 128;
        float[] a = Synth.frame(width, height, 0, 0);
        float[] b = new float[a.length];
        Transform truth = new Transform(1.2, -0.8, Math.toRadians(1.5));
        Warper.warp(a, b, width, height, truth.inverse(),
                Warper.Interpolation.BILINEAR, Float.NaN);
        PairAligner.Options o = new PairAligner.Options();
        o.fitRotation = true;
        o.maxRotation = Math.toRadians(10);
        o.maxShift = 8;
        int levels = o.levelsFor(width, height);

        PairAligner.RotationComparison comparison =
                PairAligner.compareTranslationAndRotation(
                        Synth.pyramid(a, width, height, levels),
                        Synth.pyramid(b, width, height, levels), o);

        assertEquals(0.0, comparison.translation.transform.theta, 0.0);
        assertEquals(truth.thetaDegrees(), comparison.rigid.transform.thetaDegrees(), 0.05);
        assertTrue(comparison.residualGain > 0);
        PairAligner.Fit accepted = comparison.select(0);
        PairAligner.Fit declined = comparison.select(1.0);
        assertEquals(comparison.rigid.transform, accepted.transform);
        assertTrue(accepted.rotationEvidence.accepted);
        assertEquals(comparison.translation.transform, declined.transform);
        assertTrue(!declined.rotationEvidence.accepted);

        PairAligner.RotationComparison global =
                PairAligner.compareGlobalRotationToTranslation(
                        Synth.pyramid(a, width, height, levels),
                        Synth.pyramid(b, width, height, levels), o);
        assertEquals(0.0, global.translation.transform.theta, 0.0);
        assertEquals(truth.thetaDegrees(), global.rigid.transform.thetaDegrees(), 0.05);
        assertTrue(global.residualGain > 0);
    }

    @Test
    public void incrementalAngularInitializerCrossesAnEightDegreeJump() {
        int width = 128;
        int height = 128;
        float[] a = Synth.frame(width, height, 0, 0);
        float[] b = new float[a.length];
        Transform truth = new Transform(1.0, -0.6, Math.toRadians(-8.0));
        Warper.warp(a, b, width, height, truth.inverse(),
                Warper.Interpolation.BILINEAR, Float.NaN);
        PairAligner.Options rigidOptions = new PairAligner.Options();
        rigidOptions.fitRotation = true;
        rigidOptions.maxRotation = Math.toRadians(10);
        rigidOptions.maxShift = 8;
        int levels = rigidOptions.levelsFor(width, height);
        LogPlane[] pa = Synth.pyramid(a, width, height, levels);
        LogPlane[] pb = Synth.pyramid(b, width, height, levels);
        PairAligner.Options translationOptions = rigidOptions.copy();
        translationOptions.fitRotation = false;
        PairAligner.Fit translation = PairAligner.align(pa, pb, translationOptions);

        PairAligner.Fit rigid = PairAligner.alignRigidFromTranslation(
                pa, pb, rigidOptions, translation, null);

        assertEquals(truth.thetaDegrees(), rigid.transform.thetaDegrees(), 0.10);
        assertTrue(rigid.residualAfter < translation.residualAfter);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rotationComparisonRefusesTranslationOnlyOptions() {
        int width = 32;
        int height = 32;
        float[] frame = Synth.frame(width, height, 0, 0);
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(width, height);
        PairAligner.compareTranslationAndRotation(
                Synth.pyramid(frame, width, height, levels),
                Synth.pyramid(frame, width, height, levels), o);
    }

    /** Sub-pixel recovery, every norm. Measured: 0.0008–0.0012 px. */
    @Test
    public void recoversKnownSubPixelShift() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, DX, DY);
        for (RobustNorm norm : RobustNorm.values()) {
            PairAligner.Fit f = align(a, b, norm);
            assertEquals(norm + " status", PairAligner.Status.OK, f.status);
            assertTrue(norm + " recovered " + f.transform + ", error " + error(f),
                    error(f) < 0.01);
            assertTrue(norm + " should reduce the residual",
                    f.residualAfter < 0.1 * f.residualBefore);
        }
    }

    /**
     * A global intensity gain changes nothing about the recovered geometry, and is reported.
     *
     * <p>This is the property that no SSD-based registration in Fiji has. Measured: the recovered shift
     * is identical to four decimal places whether the second frame is at 1x, 2x or 0.25x brightness,
     * and the gain reads within 0.002 log2 of truth.
     */
    @Test
    public void isInvariantToGlobalGainAndMeasuresIt() {
        float[] a = Synth.frame(W, H, 0, 0);
        double clean = error(align(a, Synth.frame(W, H, DX, DY), RobustNorm.TUKEY));
        for (double gain : new double[]{2.0, 0.25, 8.0, 0.125}) {
            PairAligner.Fit f = align(a, Synth.frame(W, H, DX, DY, gain, 0), RobustNorm.TUKEY);
            assertTrue("gain " + gain + " error " + error(f),
                    error(f) < 0.01);
            assertEquals("gain " + gain + " degraded the fit", clean, error(f), 0.005);
            assertEquals("log2 gain for x" + gain,
                    Math.log(gain) / Math.log(2), f.logGain, 0.01);
        }
    }

    /**
     * The differentiator, and the ship/don't-ship gate.
     *
     * <p>With sparse genuine change present, a squared norm is dragged toward aligning whatever changed
     * most, because it weights each residual by its own magnitude. A redescending norm gives those
     * residuals zero weight and the dense edge-aligned misregistration signal wins instead.
     *
     * <p>Measured 2026-08-07 — error in px, and the factor by which Tukey beats least squares:
     * <ul>
     *   <li>5% of pixels changed: LS 0.167, Huber 0.005, Tukey 0.0003 — <b>555x</b></li>
     *   <li>20%: LS 0.385, Huber 0.063, Tukey 0.005 — <b>79x</b></li>
     *   <li>20% plus a 2x gain: LS 0.219, Huber 0.009, Tukey 0.002 — <b>95x</b></li>
     * </ul>
     */
    @Test
    public void tukeyBeatsLeastSquaresOnSparseChange() {
        float[] a = Synth.frame(W, H, 0, 0);
        for (double fraction : new double[]{0.05, 0.20}) {
            float[] b = Synth.withSparseChange(
                    Synth.frame(W, H, DX, DY), W, H, fraction, 12345);
            double ls = error(align(a, b, RobustNorm.LEAST_SQUARES));
            double tukey = error(align(a, b, RobustNorm.TUKEY));
            assertTrue(String.format("at %.0f%% change Tukey recovered to %.4f px, "
                            + "which is not accurate enough", fraction * 100, tukey),
                    tukey < 0.05);
            assertTrue(String.format("at %.0f%% change least squares gave %.4f px and Tukey %.4f px "
                            + "— the robust norm is not earning its place", fraction * 100, ls, tukey),
                    ls > 10 * tukey);
        }
    }

    /** Gain and sparse change together: the realistic case, and the one SSD handles worst. */
    @Test
    public void handlesGainAndSparseChangeTogether() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.withSparseChange(
                Synth.frame(W, H, DX, DY, 2.0, 0), W, H, 0.20, 999);
        PairAligner.Fit tukey = align(a, b, RobustNorm.TUKEY);
        assertTrue("error " + error(tukey), error(tukey) < 0.05);
        assertEquals("gain should still read 1.0 log2", 1.0, tukey.logGain, 0.01);
        assertTrue("least squares should do measurably worse",
                error(align(a, b, RobustNorm.LEAST_SQUARES)) > 10 * error(tukey));
    }

    /**
     * The documented limit, asserted so it stays honest.
     *
     * <p>At 40% of the field genuinely changed, <b>every norm fails</b> — measured LS 1.40 px, Huber
     * 1.13 px, Tukey 0.77 px. Tukey is still the best of a bad set, and that is all this test claims.
     * The plugin's documentation must say the same rather than implying robustness is unbounded.
     */
    @Test
    public void allNormsFailWhenMostOfTheFieldChanges() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.withSparseChange(Synth.frame(W, H, DX, DY), W, H, 0.40, 4242);
        double ls = error(align(a, b, RobustNorm.LEAST_SQUARES));
        double tukey = error(align(a, b, RobustNorm.TUKEY));
        assertTrue("Tukey should still be the least bad: LS " + ls + ", Tukey " + tukey,
                tukey < ls);
        assertTrue("this test exists to record that Tukey does NOT rescue 40% change; "
                + "if it now recovers to better than 0.1 px the fixture has become too easy",
                tukey > 0.1);
    }

    /** Noise at 2% of signal costs about 0.02 px. Measured: 0.020–0.026. */
    @Test
    public void toleratesNoise() {
        float[] a = Synth.withNoise(Synth.frame(W, H, 0, 0), 40.0, 7);
        float[] b = Synth.withNoise(Synth.frame(W, H, DX, DY), 40.0, 8);
        for (RobustNorm norm : RobustNorm.values()) {
            assertTrue(norm + " error " + error(align(a, b, norm)),
                    error(align(a, b, norm)) < 0.1);
        }
    }

    /**
     * Aligning A to B and B to A must give inverse transforms.
     *
     * <p>The criterion is symmetric because swapping
     * the frames flips the sign of the log-ratio and the norm is even — so an asymmetry would mean a
     * bug in the solver rather than a property of the measure.
     */
    @Test
    public void isSymmetricUnderSwappingTheFrames() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, DX, DY, 1.7, 0);
        PairAligner.Fit forward = align(a, b, RobustNorm.TUKEY);
        PairAligner.Fit backward = align(b, a, RobustNorm.TUKEY);
        assertEquals("dx", -forward.transform.dx, backward.transform.dx, 0.02);
        assertEquals("dy", -forward.transform.dy, backward.transform.dy, 0.02);
        assertEquals("the gain should invert too",
                -forward.logGain, backward.logGain, 0.01);
    }

    /** A large shift is found because the coarse sweep, not the gradient, chooses the basin. */
    @Test
    public void findsLargeShiftsViaTheCoarseSweep() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 17.0, -12.0);
        PairAligner.Options o = new PairAligner.Options();
        o.norm = RobustNorm.TUKEY;
        o.maxShift = 30;
        int levels = o.levelsFor(W, H);
        PairAligner.Fit f = PairAligner.align(Synth.pyramid(a, W, H, levels),
                Synth.pyramid(b, W, H, levels), o);
        assertEquals("dx", 17.0, f.transform.dx, 0.05);
        assertEquals("dy", -12.0, f.transform.dy, 0.05);
    }
}
