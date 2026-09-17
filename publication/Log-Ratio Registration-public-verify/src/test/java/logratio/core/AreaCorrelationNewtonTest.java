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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The Gauss-Newton sub-pixel refinement, and the promise that adding it moved nothing else.
 *
 * <p>{@link AreaCorrelationRecoveryTest} covers the estimator as a whole. This class covers only what
 * Stage 1 of {@code docs/newton_refinement_plan.md} added: the third value on the estimator axis,
 * {@link PairEstimator.Kind#AREA_CORRELATION_NEWTON}, which shares the preparation, the interpolator,
 * the integer sweep and the pyramid descent with {@link PairEstimator.Kind#AREA_CORRELATION} and
 * differs only in how the peak is located to a fraction of a pixel.
 *
 * <p>Every threshold below is a measurement from
 * {@code docs/newton_refinement_stage0_findings.md}, quoted in the comment beside it, so a regression
 * reads as a number moving rather than as a vague failure.
 */
public class AreaCorrelationNewtonTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;

    /**
     * The basis derivatives must sum to zero at every fractional position.
     *
     * <p>The cubic B-spline basis itself sums to one everywhere, so its derivative sums to zero. A
     * set of derivative weights that did not cancel would report a gradient on a constant image —
     * manufactured out of the background level, largest where the background is brightest, and
     * therefore worst exactly on the low-contrast frames this estimator is hardest on.
     */
    @Test
    public void theBasisDerivativesSumToZeroAndTheBasisSumsToOne() {
        double[] scratch = new double[16];
        double[] positions = {0.0, 1e-9, 0.1, 0.25, 1.0 / 3, 0.5, 0.625, 0.75, 0.9, 1 - 1e-9};
        for (double f : positions) {
            AreaCorrelation.Window.fillWeights(f, scratch, 0);
            AreaCorrelation.Window.fillWeightDerivatives(f, scratch, 4);
            double basis = scratch[0] + scratch[1] + scratch[2] + scratch[3];
            double derivative = scratch[4] + scratch[5] + scratch[6] + scratch[7];
            assertEquals("basis at f=" + f, 1.0, basis, 1e-15);
            assertEquals("basis derivative at f=" + f, 0.0, derivative, 1e-15);
        }
    }

    /**
     * The basis derivatives are the derivatives of the basis, checked against a central difference.
     *
     * <p>Cheap, and it catches the transcription error the sum-to-zero test cannot: two weights whose
     * errors cancel still sum to zero.
     */
    @Test
    public void theBasisDerivativesAreTheDerivativesOfTheBasis() {
        double[] up = new double[16];
        double[] down = new double[16];
        double[] exact = new double[16];
        double h = 1e-6;
        for (double f = 0.05; f < 0.96; f += 0.05) {
            AreaCorrelation.Window.fillWeights(f + h, up, 0);
            AreaCorrelation.Window.fillWeights(f - h, down, 0);
            AreaCorrelation.Window.fillWeightDerivatives(f, exact, 0);
            for (int i = 0; i < 4; i++) {
                assertEquals("weight " + i + " derivative at f=" + f,
                        (up[i] - down[i]) / (2 * h), exact[i], 1e-8);
            }
        }
    }

    /**
     * The analytic gradient of the correlation is the gradient of the correlation.
     *
     * <p>The plan named this the check that had to pass before any pipeline work, because a
     * derivation that is approximately right looks like it works and then biases the answer. Two
     * regimes, and the second is the interesting one.
     *
     * <p><b>Interior offsets take a central difference.</b> Measured worst relative disagreement
     * 8.3e-06, and a central difference at {@code h = 1e-4} cannot itself do better than about 1e-6,
     * so that is agreement to the limit of the test.
     *
     * <p><b>Whole-pixel offsets take a one-sided difference, and the central difference is what is
     * wrong there.</b> The correlation surface is only piecewise smooth: at a whole-pixel offset the
     * four-by-four spline neighbourhood shifts a column off the frame edge and 189 samples of 35,721
     * leave the overlap, stepping the score by 1.17e-05. A central difference straddles that step and
     * measures it — it disagrees by 80% — while a one-sided difference stays on one side of it and
     * agrees to 1.3e-05. This is not a defect being tested around: every refinement starts at the
     * integer offset the sweep returned, so the first gradient is always evaluated on one of these
     * boundaries, and this test is what pins the size of it.
     */
    @Test
    public void theAnalyticGradientMatchesANumericalDerivative() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        LogPlane[] pa = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] pb = Synth.pyramid(Synth.frame(W, H, 2.4, -1.7), W, H, levels);
        AreaCorrelation.Window wa = AreaCorrelation.Window.of(pa[0], o);
        AreaCorrelation.Window wb = AreaCorrelation.Window.of(pb[0], o);
        double[] scratch = new double[16];
        double h = 1e-4;

        double[][] interior = {{2.4, -1.7}, {2.37, -1.63}, {1.9, -1.2}, {3.1, -2.4}, {2.5, -1.5}};
        for (double[] d : interior) {
            AreaCorrelation.Surface s = AreaCorrelation.surface(wa, wb, d[0], d[1], o,
                    new double[3], scratch);
            assertTrue("no surface at (" + d[0] + ", " + d[1] + ")", s.valid);
            double numX = (AreaCorrelation.score(wa, wb, d[0] + h, d[1], o, scratch)
                    - AreaCorrelation.score(wa, wb, d[0] - h, d[1], o, scratch)) / (2 * h);
            double numY = (AreaCorrelation.score(wa, wb, d[0], d[1] + h, o, scratch)
                    - AreaCorrelation.score(wa, wb, d[0], d[1] - h, o, scratch)) / (2 * h);
            assertRelative("d/dx at (" + d[0] + ", " + d[1] + ")", s.gx, numX, 1e-4);
            assertRelative("d/dy at (" + d[0] + ", " + d[1] + ")", s.gy, numY, 1e-4);
        }

        // Forward, not central: at a whole-pixel offset the sample set is the one the forward side
        // shares, because the spline neighbourhood is chosen by the integer part of the position.
        double[][] whole = {{2.0, -2.0}, {3.0, -1.0}, {2.0, -1.0}};
        for (double[] d : whole) {
            AreaCorrelation.Surface s = AreaCorrelation.surface(wa, wb, d[0], d[1], o,
                    new double[3], scratch);
            assertTrue("no surface at (" + d[0] + ", " + d[1] + ")", s.valid);
            double here = AreaCorrelation.score(wa, wb, d[0], d[1], o, scratch);
            double numX = (AreaCorrelation.score(wa, wb, d[0] + h, d[1], o, scratch) - here) / h;
            double numY = (AreaCorrelation.score(wa, wb, d[0], d[1] + h, o, scratch) - here) / h;
            assertRelative("d/dx at whole-pixel (" + d[0] + ", " + d[1] + ")", s.gx, numX, 1e-3);
            assertRelative("d/dy at whole-pixel (" + d[0] + ", " + d[1] + ")", s.gy, numY, 1e-3);
        }
    }

    /**
     * The value and its gradient are read at exactly the same positions.
     *
     * <p>A line search judged on a different sample set from the gradient that proposed the step is
     * comparing two different functions and would accept or reject on the difference between them.
     * The border is where the two rules could diverge, so the sweep below deliberately runs off the
     * edge of the frame.
     */
    @Test
    public void theScoreAndTheGradientAgreeAboutWhichPositionsExist() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        LogPlane[] pb = Synth.pyramid(Synth.frame(W, H, 2.4, -1.7), W, H, levels);
        AreaCorrelation.Window w = AreaCorrelation.Window.of(pb[0], o);
        double[] out = new double[3];
        double[] scratch = new double[16];
        int agreed = 0;
        for (double y = -2.5; y < H + 2.5; y += 3.7) {
            for (double x = -2.5; x < W + 2.5; x += 3.3) {
                boolean gradient = w.sampleWithGradient(x, y, out, scratch);
                double value = w.sampleInterior(x, y, scratch);
                assertEquals("at (" + x + ", " + y + ")", gradient, !Double.isNaN(value));
                if (gradient) {
                    assertEquals("value at (" + x + ", " + y + ")", out[0], value, 0.0);
                    agreed++;
                }
            }
        }
        assertTrue("the sweep must actually reach interior positions", agreed > 100);
    }

    /**
     * The refinement recovers a known sub-pixel shift at least as accurately as the grid does.
     *
     * <p>Ten shifts, the same list Stage 0 used, both refinements inside the same pyramid descent and
     * the same integer sweep so the refinement is the only difference. Measured 2026-08-19: worst
     * error 0.000243 px for the step against 0.000692 px for the grid, better on eight of ten and
     * equal on one. The bar here is the weaker claim the plan actually requires — no worse than the
     * grid, and inside the 0.000600 px the interpolator rather than the search is the limit at.
     */
    @Test
    public void recoversKnownSubPixelShiftsAtLeastAsWellAsTheGrid() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        double[][] shifts = {
                {0.00, 0.00}, {0.25, 0.00}, {0.50, 0.50}, {0.37, -0.62}, {1.50, -0.50},
                {2.40, -1.70}, {-3.20, 2.85}, {4.05, 3.95}, {0.10, -0.10}, {2.50, 9.50},
        };
        LogPlane[] pa = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        double worstGrid = 0;
        double worstNewton = 0;
        StringBuilder record = new StringBuilder();
        for (double[] s : shifts) {
            LogPlane[] pb = Synth.pyramid(Synth.frame(W, H, s[0], s[1]), W, H, levels);
            PairAligner.Fit grid = AreaCorrelation.align(pa, pb, o, AreaCorrelation.Refiner.GRID);
            PairAligner.Fit newton = AreaCorrelation.align(pa, pb, o, AreaCorrelation.Refiner.NEWTON);
            double eg = Math.hypot(grid.transform.dx - s[0], grid.transform.dy - s[1]);
            double en = Math.hypot(newton.transform.dx - s[0], newton.transform.dy - s[1]);
            worstGrid = Math.max(worstGrid, eg);
            worstNewton = Math.max(worstNewton, en);
            record.append(String.format(java.util.Locale.ROOT, "%n  (%6.2f, %6.2f) grid %.6f"
                    + " newton %.6f", s[0], s[1], eg, en));
        }
        assertTrue("the step must be no worse than the grid; measured grid 0.000692 px, newton"
                + " 0.000243 px" + record, worstNewton <= worstGrid + 1e-9);
        assertTrue("worst newton error must stay inside the plan's 0.000600 px" + record,
                worstNewton <= 0.0006);
    }

    /**
     * The seam reaches this refinement, and the axis can name it.
     *
     * <p>{@link PairEstimator.Kind#AREA_CORRELATION_NEWTON} must be the same computation as asking
     * {@link AreaCorrelation} for the Newton refiner directly, or every measurement taken through the
     * axis in Stage 2 is measuring something other than what this file covers. The parse matters for
     * the same reason: a macro, a batch run and a sweep plan all reach the value by its identifier.
     */
    @Test
    public void theSeamReachesTheNewtonRefinement() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, 2.4, -1.7), W, H, levels);
        PairAligner.Fit direct = AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.NEWTON);
        PairAligner.Fit viaSeam = PairEstimator.Kind.AREA_CORRELATION_NEWTON.estimate(a, b, o);
        assertEquals("dx", direct.transform.dx, viaSeam.transform.dx, 0.0);
        assertEquals("dy", direct.transform.dy, viaSeam.transform.dy, 0.0);
        assertEquals("status", direct.status, viaSeam.status);

        assertEquals("id", "area_correlation_newton",
                PairEstimator.Kind.AREA_CORRELATION_NEWTON.id());
        assertSame("parsed by id", PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                PairEstimator.Kind.of("area_correlation_newton"));
        assertSame("parsed by hyphens", PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                PairEstimator.Kind.of("Area-Correlation-Newton"));
        assertSame("parsed by name", PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                PairEstimator.Kind.of("AREA_CORRELATION_NEWTON"));
        assertFalse("it reads the same log-domain pyramid the other area arm reads",
                PairEstimator.Kind.AREA_CORRELATION_NEWTON.prefersLinearPyramid());
        assertTrue("it memoises the same prepared window, so it must declare the same cost",
                PairEstimator.Kind.AREA_CORRELATION_NEWTON.cachedBytesPerPixel()
                        == PairEstimator.Kind.AREA_CORRELATION.cachedBytesPerPixel());
    }

    /**
     * Adding the third value did not move the second, and did not move the default.
     *
     * <p>This is the half of the Stage 1 gate a unit test can carry. The other half is 800 arm-runs
     * over the 80 development recordings from a before/after pair of builds, recorded in
     * {@code docs/newton_refinement_stage1_findings.md}; a unit test cannot reach the selector, the
     * reconciler or the repair, so it asserts the narrow thing it can: the shipping estimator's
     * entry point, its seam and its refiner all still produce one identical answer.
     */
    @Test
    public void theGridRefinementIsUnchangedAndTheDefaultIsStillTheLogRatioFit() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, 2.4, -1.7), W, H, levels);

        PairAligner.Fit shipped = AreaCorrelation.align(a, b, o);
        PairAligner.Fit explicitGrid = AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.GRID);
        PairAligner.Fit viaSeam = PairEstimator.Kind.AREA_CORRELATION.estimate(a, b, o);
        assertEquals("the default overload must be the grid refiner, dx",
                shipped.transform.dx, explicitGrid.transform.dx, 0.0);
        assertEquals("the default overload must be the grid refiner, dy",
                shipped.transform.dy, explicitGrid.transform.dy, 0.0);
        assertEquals("the seam must still reach the grid refiner, dx",
                shipped.transform.dx, viaSeam.transform.dx, 0.0);
        assertEquals("the seam must still reach the grid refiner, dy",
                shipped.transform.dy, viaSeam.transform.dy, 0.0);
        assertEquals("iterations", shipped.iterations, viaSeam.iterations);
        assertEquals("status", shipped.status, viaSeam.status);

        assertSame("the default estimator must still be the log-ratio fit",
                PairEstimator.Kind.LOG_RATIO_FIT, new Registration.Options().estimator);
    }

    private static void assertRelative(String what, double analytic, double numeric, double limit) {
        double scale = Math.max(Math.abs(analytic), Math.abs(numeric));
        double relative = scale < 1e-12
                ? Math.abs(analytic - numeric)
                : Math.abs(analytic - numeric) / scale;
        assertTrue(what + ": analytic " + analytic + ", numeric " + numeric
                + ", relative " + relative + " over limit " + limit, relative < limit);
    }
}
