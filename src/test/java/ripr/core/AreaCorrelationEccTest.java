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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Contract tests for guarded Enhanced Correlation Coefficient refinement. */
public class AreaCorrelationEccTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;

    @Test
    public void closedFormStepMatchesAnIndependentMatrixSolve() {
        AreaCorrelation.EccSurface s = new AreaCorrelation.EccSurface();
        s.valid = true;
        s.vb = 31.0;
        s.num = 17.0;
        s.px = 2.5;
        s.py = -1.2;
        s.qx = 1.1;
        s.qy = -0.4;
        s.hxx = 8.0;
        s.hyy = 5.0;
        s.hxy = 1.5;

        double[] actual = AreaCorrelation.eccStep(s);
        assertNotNull(actual);

        double[][] inverse = inverse(s.hxx, s.hxy, s.hyy);
        double[] hq = multiply(inverse, s.qx, s.qy);
        double lambda = (s.vb - dot(s.qx, s.qy, hq))
                / (s.num - dot(s.px, s.py, hq));
        double[] expected = multiply(inverse,
                lambda * s.px - s.qx, lambda * s.py - s.qy);
        assertEquals("dx", expected[0], actual[0], 1e-15);
        assertEquals("dy", expected[1], actual[1], 1e-15);
    }

    @Test
    public void recoversKnownSubPixelShifts() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        double[][] shifts = {
                {0.25, 0.00}, {0.50, 0.50}, {0.37, -0.62}, {1.50, -0.50},
                {2.40, -1.70}, {-3.20, 2.85}, {4.05, 3.95}, {0.10, -0.10},
        };
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        double worst = 0;
        StringBuilder record = new StringBuilder();
        for (double[] truth : shifts) {
            LogPlane[] b = Synth.pyramid(
                    Synth.frame(W, H, truth[0], truth[1]), W, H, levels);
            PairAligner.Fit fit = AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.ECC);
            assertTrue("usable fit at " + truth[0] + ", " + truth[1], fit.usable());
            double error = Math.hypot(fit.transform.dx - truth[0],
                    fit.transform.dy - truth[1]);
            worst = Math.max(worst, error);
            record.append(String.format(java.util.Locale.ROOT,
                    "%n  (%6.2f, %6.2f) ecc %.6f", truth[0], truth[1], error));
        }
        assertTrue("worst Enhanced Correlation Coefficient error " + worst + record,
                worst <= 0.001);
    }

    @Test
    public void geometryIsInvariantToGainAndOffset() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] plain = Synth.pyramid(Synth.frame(W, H, 2.4, -1.7), W, H, levels);
        LogPlane[] changed = Synth.pyramid(
                Synth.frame(W, H, 2.4, -1.7, 0.37, 900.0), W, H, levels);
        PairAligner.Fit first = PairEstimator.Kind.AREA_CORRELATION_ECC.estimate(a, plain, o);
        PairAligner.Fit second = PairEstimator.Kind.AREA_CORRELATION_ECC.estimate(a, changed, o);
        assertEquals("dx", first.transform.dx, second.transform.dx, 2e-5);
        assertEquals("dy", first.transform.dy, second.transform.dy, 2e-5);
    }

    @Test
    public void anUnavailableEccSurfaceFallsBackToTheGrid() {
        PairAligner.Options o = new PairAligner.Options();
        o.minValidFraction = 1.0;
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        PairAligner.Fit grid = AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.GRID);
        PairAligner.Fit ecc = AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.ECC);
        assertEquals("dx", grid.transform.dx, ecc.transform.dx, 0.0);
        assertEquals("dy", grid.transform.dy, ecc.transform.dy, 0.0);
        assertEquals("status", grid.status, ecc.status);
    }

    @Test
    public void seamAndIdentifierReachOnlyTheNewRefinement() {
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, 2.4, -1.7), W, H, levels);
        PairAligner.Fit direct = AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.ECC);
        PairAligner.Fit throughSeam = PairEstimator.Kind.AREA_CORRELATION_ECC.estimate(a, b, o);
        assertEquals("dx", direct.transform.dx, throughSeam.transform.dx, 0.0);
        assertEquals("dy", direct.transform.dy, throughSeam.transform.dy, 0.0);
        assertEquals("status", direct.status, throughSeam.status);
        assertSame(PairEstimator.Kind.AREA_CORRELATION_ECC,
                PairEstimator.Kind.of("area-correlation-ecc"));
        assertFalse(PairEstimator.Kind.AREA_CORRELATION_ECC.prefersLinearPyramid());
        assertEquals(PairEstimator.Kind.AREA_CORRELATION.cachedBytesPerPixel(),
                PairEstimator.Kind.AREA_CORRELATION_ECC.cachedBytesPerPixel());
        assertSame("Automatic default remains untouched", PairEstimator.Kind.LOG_RATIO_FIT,
                new Registration.Options().estimator);
    }

    private static double[][] inverse(double hxx, double hxy, double hyy) {
        double determinant = hxx * hyy - hxy * hxy;
        return new double[][]{
                {hyy / determinant, -hxy / determinant},
                {-hxy / determinant, hxx / determinant},
        };
    }

    private static double[] multiply(double[][] matrix, double x, double y) {
        return new double[]{matrix[0][0] * x + matrix[0][1] * y,
                matrix[1][0] * x + matrix[1][1] * y};
    }

    private static double dot(double x, double y, double[] vector) {
        return x * vector[0] + y * vector[1];
    }
}
