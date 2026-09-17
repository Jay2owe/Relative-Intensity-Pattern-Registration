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

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The norms, the robust scale, and the selection that keeps the inner loop linear. */
public class RobustNormTest {

    @Test
    public void leastSquaresIsUnweightedAndQuadratic() {
        assertEquals(1.0, RobustNorm.LEAST_SQUARES.weight(1e9, 1.0), 0);
        assertEquals(4.0, RobustNorm.LEAST_SQUARES.rho(2.0, 1.0), 1e-12);
    }

    /** Huber: quadratic inside the threshold, bounded influence outside. */
    @Test
    public void huberBoundsInfluence() {
        double k = 2.0;
        assertEquals("inside", 1.0, RobustNorm.HUBER.weight(1.0, k), 1e-12);
        assertEquals("at the threshold", 1.0, RobustNorm.HUBER.weight(2.0, k), 1e-12);
        assertEquals("outside", 0.2, RobustNorm.HUBER.weight(10.0, k), 1e-12);
        // influence = weight * residual, and must not grow without bound
        assertEquals("influence saturates at k", k, RobustNorm.HUBER.weight(1e6, k) * 1e6, 1e-6);
        assertEquals("quadratic inside", 1.0, RobustNorm.HUBER.rho(1.0, k), 1e-12);
        assertEquals("continuous at the threshold",
                RobustNorm.HUBER.rho(1.999999, k), RobustNorm.HUBER.rho(2.000001, k), 1e-5);
    }

    /**
     * Tukey redescends to exactly zero. That is the property the sparse-change case depends on: a
     * residual judged to be genuine change is discarded, not merely discounted.
     */
    @Test
    public void tukeyGivesZeroWeightBeyondTheThreshold() {
        double k = 3.0;
        assertTrue(RobustNorm.TUKEY.weight(0.5, k) > 0.9);
        assertEquals("exactly zero at the threshold", 0.0, RobustNorm.TUKEY.weight(3.0, k), 0);
        assertEquals("and beyond", 0.0, RobustNorm.TUKEY.weight(100.0, k), 0);
        assertEquals("rho saturates", RobustNorm.TUKEY.rho(3.0, k),
                RobustNorm.TUKEY.rho(1e6, k), 1e-12);
        assertTrue("rho is monotone inside",
                RobustNorm.TUKEY.rho(1.0, k) < RobustNorm.TUKEY.rho(2.0, k));
    }

    @Test
    public void thresholdsScaleWithTheEstimatedNoise() {
        assertEquals(1.345 * 0.5, RobustNorm.HUBER.threshold(0.5), 1e-12);
        assertEquals(4.685 * 0.5, RobustNorm.TUKEY.threshold(0.5), 1e-12);
        assertTrue("least squares never cuts off",
                Double.isInfinite(RobustNorm.LEAST_SQUARES.threshold(1.0)));
        assertTrue("a zero scale must not produce a zero threshold",
                RobustNorm.HUBER.threshold(0) > 0);
    }

    /** The MAD estimate recovers a Gaussian sigma. */
    @Test
    public void scaleEstimatesGaussianSigma() {
        Random rng = new Random(4);
        double[] r = new double[20000];
        for (int i = 0; i < r.length; i++) r[i] = 3.0 * rng.nextGaussian();
        assertEquals(3.0, RobustNorm.scale(r, r.length, 1e-9), 0.1);
    }

    /**
     * Contamination must move the robust scale a little where it moves a standard deviation enormously.
     *
     * <p>Measured on this fixture: 20% of values replaced by 500 takes the MAD-based scale from 1.00 to
     * 1.32 — a 32% shift — while the standard deviation of the same data goes from 1.00 to about 200.
     * That two-orders-of-magnitude difference is the entire reason the threshold is derived from a MAD.
     *
     * <p>Note the scale is measured about <b>zero</b>, not about the median, because the caller has
     * already removed the gain. One-sided contamination therefore does shift it, and 32% is the honest
     * number — not the "barely moves at all" that a median-centred MAD would give.
     */
    @Test
    public void scaleResistsContaminationFarBetterThanAStandardDeviation() {
        Random rng = new Random(5);
        double[] clean = new double[10000];
        for (int i = 0; i < clean.length; i++) clean[i] = rng.nextGaussian();
        double[] dirty = clean.clone();
        for (int i = 0; i < 2000; i++) dirty[i] = 500;

        double robustClean = RobustNorm.scale(clean.clone(), clean.length, 1e-9);
        double robustDirty = RobustNorm.scale(dirty.clone(), dirty.length, 1e-9);
        double sdClean = sd(clean);
        double sdDirty = sd(dirty);

        double robustFactor = robustDirty / robustClean;
        double sdFactor = sdDirty / sdClean;
        assertTrue("the robust scale moved by a factor of " + robustFactor,
                robustFactor < 1.5);
        assertTrue("a standard deviation should be wrecked: factor " + sdFactor,
                sdFactor > 100);
        assertTrue("the robust scale must be at least fifty times more stable",
                sdFactor > 50 * robustFactor);
    }

    private static double sd(double[] a) {
        double mean = 0;
        for (double x : a) mean += x;
        mean /= a.length;
        double v = 0;
        for (double x : a) v += (x - mean) * (x - mean);
        return Math.sqrt(v / a.length);
    }

    /** Identical frames give an all-zero residual, which must not become a zero threshold. */
    @Test
    public void scaleIsFloored() {
        double[] zeros = new double[100];
        assertEquals(0.25, RobustNorm.scale(zeros, zeros.length, 0.25), 0);
        assertEquals("an empty set falls back to the floor too",
                0.25, RobustNorm.scale(new double[0], 0, 0.25), 0);
    }

    /** Selection must agree with a full sort, for every k, on awkward inputs. */
    @Test
    public void selectionMatchesSorting() {
        Random rng = new Random(6);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + rng.nextInt(60);
            double[] a = new double[n];
            for (int i = 0; i < n; i++) {
                // deliberately many duplicates: the classic partition-loop hazard
                a[i] = rng.nextInt(5);
            }
            double[] sorted = a.clone();
            Arrays.sort(sorted);
            int k = rng.nextInt(n);
            assertEquals("n=" + n + " k=" + k + " " + Arrays.toString(a),
                    sorted[k], RobustNorm.select(a.clone(), n, k), 0);
        }
    }

    @Test
    public void selectionHandlesAllIdenticalValues() {
        double[] a = new double[50];
        Arrays.fill(a, 7.0);
        assertEquals(7.0, RobustNorm.select(a, a.length, 25), 0);
    }

    @Test
    public void selectionHandlesSingletons() {
        assertEquals(3.0, RobustNorm.select(new double[]{3.0}, 1, 0), 0);
    }
}
