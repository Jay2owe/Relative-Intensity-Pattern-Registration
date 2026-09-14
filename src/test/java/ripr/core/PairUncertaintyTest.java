/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** Matrix validity, physical propagation and final-fit uncertainty. */
public class PairUncertaintyTest {

    @Test
    public void covarianceIsDefensiveAndInformationIsItsInverse() {
        double[] covariance = {4.0, 1.0, 1.0, 9.0};
        PairUncertainty uncertainty = PairUncertainty.fromCovariance(
                2, covariance, Double.NaN, false, false, false);
        covariance[0] = 99;
        assertEquals(4.0, uncertainty.covariance()[0], 0.0);
        assertNotSame(uncertainty.covariance(), uncertainty.covariance());
        double[] information = uncertainty.information();
        assertEquals(9.0 / 35.0, information[0], 1e-14);
        assertEquals(-1.0 / 35.0, information[1], 1e-14);
        assertEquals(4.0 / 35.0, information[3], 1e-14);
    }

    @Test
    public void scalingAndRigidInverseUseAnalyticJacobians() {
        PairUncertainty uncertainty = PairUncertainty.fromCovariance(3, new double[]{
                4, 0.5, 0.02,
                0.5, 9, -0.03,
                0.02, -0.03, 0.004
        }, Double.NaN, false, false, false);
        PairUncertainty scaled = uncertainty.scaleTranslations(2.0);
        assertArrayEquals(new double[]{
                16, 2, 0.04,
                2, 36, -0.06,
                0.04, -0.06, 0.004
        }, scaled.covariance(), 1e-14);

        Transform forward = new Transform(3, -2, 0.25);
        PairUncertainty reversed = uncertainty.inverseFor(forward);
        assertTrue(reversed.available());
        assertEquals(3, reversed.dimensions);
        assertSymmetricPositiveDefinite(reversed.covariance(), 3);
        PairUncertainty roundTrip = reversed.inverseFor(forward.inverse());
        assertArrayEquals(uncertainty.covariance(), roundTrip.covariance(), 1e-10);
    }

    @Test
    public void unavailableAndEigenvalueBoundsAreExplicit() {
        PairUncertainty unavailable = PairUncertainty.unavailable(2);
        assertFalse(unavailable.available());
        assertEquals(null, unavailable.covariance());
        PairUncertainty bounded = PairUncertainty.bounded(2,
                new double[]{1e-12, 0, 0, 1e8}, Double.NaN, false, 1.0);
        assertTrue(bounded.available());
        assertTrue(bounded.eigenvalueFloored);
        assertTrue(bounded.eigenvalueCapped);
        assertSymmetricPositiveDefinite(bounded.covariance(), 2);
    }

    @Test
    public void finalLogRatioFitCarriesFiniteDirectionalCovariance() {
        int size = 96;
        PairAligner.Options options = new PairAligner.Options();
        options.levels = 2;
        PairAligner.Fit fit = PairAligner.align(
                Synth.pyramid(Synth.frame(size, size, 0, 0), size, size, 2),
                Synth.pyramid(Synth.withNoise(
                        Synth.frame(size, size, 1.3, -0.7), 2.0, 42), size, size, 2),
                options);
        assertTrue(fit.usable());
        assertTrue(fit.uncertainty.available());
        assertEquals(2, fit.uncertainty.dimensions);
        assertSymmetricPositiveDefinite(fit.uncertainty.covariance(), 2);
    }

    private static void assertSymmetricPositiveDefinite(double[] matrix, int dimensions) {
        for (int row = 0; row < dimensions; row++) {
            assertTrue(matrix[row * dimensions + row] > 0);
            for (int col = 0; col < dimensions; col++) {
                assertEquals(matrix[row * dimensions + col],
                        matrix[col * dimensions + row], 1e-12);
            }
        }
        assertTrue(PairUncertainty.inverseSpd(matrix, dimensions) != null);
    }
}
