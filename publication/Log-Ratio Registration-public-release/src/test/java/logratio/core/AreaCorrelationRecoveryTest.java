/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Does the area estimator do what {@link AreaCorrelation}'s documentation claims?
 *
 * <p>{@link PairAlignerRecoveryTest} is the equivalent for the log-ratio fit and is the plugin's kill
 * criterion. This class is narrower: the area estimator is an <em>option</em>, not the differentiator,
 * so the bar is that it is correct, that it is honest when it cannot answer, and that the seam in
 * {@link PairEstimator} really reaches it. Every claim tested here is one the estimator's own javadoc
 * makes, because an implementation whose documentation is wrong is worse than one with none.
 *
 * <p>Thresholds are set from measured behaviour on 2026-08-18, with the measurement in the comment, so
 * a regression shows up as a number moving rather than as a vague failure.
 */
public class AreaCorrelationRecoveryTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;
    private static final double DX = 2.4;
    private static final double DY = -1.7;

    private static PairAligner.Options options() {
        return new PairAligner.Options();
    }

    private static PairAligner.Fit area(float[] a, float[] b, PairAligner.Options o) {
        int levels = o.levelsFor(W, H);
        return AreaCorrelation.align(Synth.pyramid(a, W, H, levels),
                Synth.pyramid(b, W, H, levels), o);
    }

    private static PairAligner.Fit area(float[] a, float[] b) {
        return area(a, b, options());
    }

    private static double error(PairAligner.Fit f) {
        return Math.hypot(f.transform.dx - DX, f.transform.dy - DY);
    }

    /**
     * Sub-pixel recovery. Measured error 0.0009 px, which is the same order as the log-ratio fit's
     * 0.0008–0.0012 px on the identical fixture — the point of the sub-pixel refinement described in
     * the estimator's javadoc, and the reason a bare quadratic through the integer peak is not enough.
     */
    @Test
    public void recoversKnownSubPixelShift() {
        PairAligner.Fit f = area(Synth.frame(W, H, 0, 0), Synth.frame(W, H, DX, DY));
        assertEquals("status", PairAligner.Status.OK, f.status);
        assertTrue("recovered " + f.transform + ", error " + error(f), error(f) < 0.01);
    }

    /**
     * The sign convention matches {@link Transform} and therefore {@link PairAligner}.
     *
     * <p>Worth its own test rather than being implied by the one above: an estimator that returned the
     * negation would still pass a symmetric error bound if the fixture happened to be symmetric, and
     * it would then corrupt every cumulative path downstream while looking locally plausible.
     */
    @Test
    public void followsTheSameSignConventionAsTheLogRatioFit() {
        PairAligner.Options o = options();
        int levels = o.levelsFor(W, H);
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, DX, DY);
        PairAligner.Fit fit = PairAligner.align(Synth.pyramid(a, W, H, levels),
                Synth.pyramid(b, W, H, levels), o);
        PairAligner.Fit corr = area(a, b, o);
        assertTrue("log-ratio dx " + fit.transform.dx + " and area dx " + corr.transform.dx
                + " should agree in sign", fit.transform.dx * corr.transform.dx > 0);
        assertTrue("log-ratio dy " + fit.transform.dy + " and area dy " + corr.transform.dy
                + " should agree in sign", fit.transform.dy * corr.transform.dy > 0);
        assertTrue("the two estimators should agree to well under a pixel",
                Math.hypot(fit.transform.dx - corr.transform.dx,
                        fit.transform.dy - corr.transform.dy) < 0.05);
    }

    /**
     * Normalised cross-correlation subtracts each window's mean and divides by its standard deviation,
     * so any {@code aI + b} rescaling of a frame cancels exactly. That is the estimator's claim to the
     * same bleaching tolerance the log-ratio fit gets by profiling the gain out, reached by a different
     * route. Measured: recovery is identical to four decimal places across a 64-fold gain range.
     */
    @Test
    public void isInvariantToGainAndOffset() {
        float[] a = Synth.frame(W, H, 0, 0);
        double clean = error(area(a, Synth.frame(W, H, DX, DY)));
        double[][] rescalings = {{2.0, 0}, {0.25, 0}, {8.0, 0}, {0.125, 0}, {1.0, 500}, {3.0, -400}};
        for (double[] rescaling : rescalings) {
            double gain = rescaling[0];
            double offset = rescaling[1];
            PairAligner.Fit f = area(a, Synth.frame(W, H, DX, DY, gain, offset));
            assertEquals("status at gain " + gain + " offset " + offset,
                    PairAligner.Status.OK, f.status);
            assertEquals("gain " + gain + " offset " + offset + " moved the recovered shift",
                    clean, error(f), 1e-4);
        }
    }

    /**
     * A frame with no texture must not produce a confident answer.
     *
     * <p>The failure this guards against is named in the estimator's javadoc: on a flat frame every
     * candidate offset scores alike, and a sweep that accepts the first equal-best it scans walks to
     * the corner of its own search box and reports it as a measurement. Refusing is the correct
     * outcome; so is returning zero. Returning {@code maxShift} is not.
     */
    @Test
    public void doesNotInventMovementOnATexturelessFrame() {
        float[] flat = new float[W * H];
        java.util.Arrays.fill(flat, 2000f);
        PairAligner.Options o = options();
        PairAligner.Fit f = area(flat, flat.clone(), o);
        if (f.status == PairAligner.Status.OK) {
            assertTrue("a flat pair should not report movement, got " + f.transform,
                    f.transform.magnitude() < 0.5);
        }
        assertNotEquals("a flat pair must never report a shift at the search bound",
                PairAligner.Status.AT_SHIFT_BOUND, f.status);
    }

    /**
     * The seam reaches this implementation.
     *
     * <p>{@link PairEstimator.Kind#AREA_CORRELATION} must be the same computation as calling
     * {@link AreaCorrelation} directly, or every measurement taken through the axis is measuring
     * something other than what this test file covers.
     */
    @Test
    public void theEstimatorSeamReachesThisImplementation() {
        PairAligner.Options o = options();
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, DX, DY), W, H, levels);
        PairAligner.Fit direct = AreaCorrelation.align(a, b, o);
        PairAligner.Fit viaSeam = PairEstimator.Kind.AREA_CORRELATION.estimate(a, b, o);
        assertEquals("dx", direct.transform.dx, viaSeam.transform.dx, 0.0);
        assertEquals("dy", direct.transform.dy, viaSeam.transform.dy, 0.0);
        assertEquals("status", direct.status, viaSeam.status);
        assertEquals("id", "area_correlation", PairEstimator.Kind.AREA_CORRELATION.id());
        assertEquals("parsed by id", PairEstimator.Kind.AREA_CORRELATION,
                PairEstimator.Kind.of("area-correlation"));
        assertEquals("parsed by name", PairEstimator.Kind.AREA_CORRELATION,
                PairEstimator.Kind.of("AREA_CORRELATION"));
    }

    /**
     * The prepared window is built once per plane and reused, and reusing it changes no number.
     *
     * <p>Two claims, and both matter. The cheap one is that the memoisation happens at all: multi-lag
     * over 48 frames plans 209 pairs, so each frame is handed to the estimator about nine times, and
     * rebuilding its intensities and B-spline coefficients on every one of them was eight ninths
     * waste. The load-bearing one is that a reused window is the same window: the preparation is a
     * pure function of the plane, so a second alignment against a cached view has to return the
     * identical fit, to the last bit, or the cache is not a cache but an unreviewed change to the
     * estimator.
     */
    @Test
    public void thePreparedWindowIsBuiltOncePerPlaneAndChangesNothing() {
        PairAligner.Options o = options();
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, DX, DY), W, H, levels);
        for (LogPlane plane : a) {
            assertNull("nothing is prepared before the estimator asks", plane.areaWindow);
        }

        PairAligner.Fit first = AreaCorrelation.align(a, b, o);
        AreaCorrelation.Window[] prepared = new AreaCorrelation.Window[a.length];
        for (int level = 0; level < a.length; level++) {
            prepared[level] = a[level].areaWindow;
            assertNotNull("level " + level + " must be prepared after one alignment",
                    prepared[level]);
        }

        PairAligner.Fit second = AreaCorrelation.align(a, b, o);
        for (int level = 0; level < a.length; level++) {
            assertSame("level " + level + " must be reused, not rebuilt",
                    prepared[level], a[level].areaWindow);
        }
        assertEquals("dx must not move", first.transform.dx, second.transform.dx, 0.0);
        assertEquals("dy must not move", first.transform.dy, second.transform.dy, 0.0);
        assertEquals("status must not move", first.status, second.status);

        // The log-ratio fit prepares nothing, so it must declare nothing, or every pyramid cache in
        // the plugin would be sized for a cost the default estimator never pays.
        assertEquals("log-ratio fit caches nothing per pixel", 0L,
                PairEstimator.Kind.LOG_RATIO_FIT.cachedBytesPerPixel());
        assertTrue("area correlation must declare what it caches",
                PairEstimator.Kind.AREA_CORRELATION.cachedBytesPerPixel() > 0);
    }

    /**
     * The default estimator is still the log-ratio fit, and its seam is likewise the real thing.
     *
     * <p>This is the half of the Stage 2 gate that a unit test can carry: whatever the axis does, the
     * shipped path must be unchanged. The benchmark carries the other half, on 80 recordings.
     */
    @Test
    public void theDefaultEstimatorIsStillTheLogRatioFit() {
        PairAligner.Options o = options();
        assertEquals("default on Registration.Options", PairEstimator.Kind.LOG_RATIO_FIT,
                new Registration.Options().estimator);
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, DX, DY), W, H, levels);
        PairAligner.Fit direct = PairAligner.align(a, b, o);
        PairAligner.Fit viaSeam = PairEstimator.Kind.LOG_RATIO_FIT.estimate(a, b, o);
        assertEquals("dx", direct.transform.dx, viaSeam.transform.dx, 0.0);
        assertEquals("dy", direct.transform.dy, viaSeam.transform.dy, 0.0);
    }

    /**
     * Genuine change costs the area estimator more than it costs a redescending norm.
     *
     * <p>Not a defect, and the reason the axis exists as a choice rather than a replacement: the
     * estimator's javadoc states that a region which appears or disappears enters the correlation at
     * full weight, where Tukey would have pushed it out. Asserting the direction of that difference
     * keeps the documented trade-off honest — if this ever fails, either the norm stopped working or
     * the correlation gained a robustness nobody wrote.
     */
    @Test
    public void isLessRobustToSparseChangeThanTukey() {
        PairAligner.Options o = options();
        o.norm = RobustNorm.TUKEY;
        int levels = o.levelsFor(W, H);
        float[] a = Synth.frame(W, H, 0, 0);
        float[] changed = Synth.withSparseChange(Synth.frame(W, H, DX, DY), W, H, 0.05, 42L);
        LogPlane[] pa = Synth.pyramid(a, W, H, levels);
        LogPlane[] pb = Synth.pyramid(changed, W, H, levels);
        double tukey = Math.hypot(PairAligner.align(pa, pb, o).transform.dx - DX,
                PairAligner.align(pa, pb, o).transform.dy - DY);
        double correlation = Math.hypot(AreaCorrelation.align(pa, pb, o).transform.dx - DX,
                AreaCorrelation.align(pa, pb, o).transform.dy - DY);
        assertTrue("Tukey should tolerate sparse change at least as well as raw correlation; "
                + "Tukey " + tukey + " px, area correlation " + correlation + " px",
                tukey <= correlation + 1e-6);
    }
}
