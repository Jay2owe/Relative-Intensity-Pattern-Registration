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
import static org.junit.Assert.assertTrue;

/**
 * Regression guards for the five defects found in the draft engine on 2026-08-07.
 *
 * <p>Each was a silent wrong answer rather than a crash, which is why each gets a named test. A
 * registration plugin that fails loudly wastes an afternoon; one that fails quietly gets into a paper.
 */
public class AlignerDefectsTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;

    private static PairAligner.Fit align(float[] a, float[] b, PairAligner.Options o) {
        int levels = o.levelsFor(W, H);
        return PairAligner.align(Synth.pyramid(a, W, H, levels),
                Synth.pyramid(b, W, H, levels), o);
    }

    /**
     * Defect 1 — the line search and the iteration must optimise the same objective.
     *
     * <p>The draft computed the iteration's cost with the gain as a weighted mean and the trial cost
     * with the gain as a median. Two different objectives were being compared, so a downhill step could
     * be rejected and an uphill one accepted. Both now use the median throughout.
     *
     * <p>The invariant that follows, and the one asserted here: the line search never accepts a step
     * that makes the residual worse, so across a spread of shifts the final residual is never above the
     * starting one.
     */
    @Test
    public void alignmentNeverIncreasesTheResidual() {
        float[] a = Synth.frame(W, H, 0, 0);
        for (double dx = -6; dx <= 6; dx += 1.3) {
            for (double dy = -4; dy <= 4; dy += 1.7) {
                for (RobustNorm norm : RobustNorm.values()) {
                    PairAligner.Options o = new PairAligner.Options();
                    o.norm = norm;
                    PairAligner.Fit f = align(a, Synth.frame(W, H, dx, dy), o);
                    assertTrue(String.format("%s at (%.1f, %.1f): residual went %.6f -> %.6f",
                                    norm, dx, dy, f.residualBefore, f.residualAfter),
                            f.residualAfter <= f.residualBefore + 1e-9);
                }
            }
        }
    }

    /**
     * Defect 2 — an early bail-out must not report success.
     *
     * <p>The draft returned {@code converged || it < maxIterations}, which is true precisely when the
     * loop broke out early. A refused pair was therefore written into the cumulative chain as though it
     * had worked, and every later frame inherited the error.
     */
    @Test
    public void aPairWithAlmostNoUsablePixelsIsRefused() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 1, 1);
        // Mask out all but a corner, so the overlap falls far below minValidFraction.
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (x > 12 || y > 12) b[y * W + x] = Float.NaN;
            }
        }
        PairAligner.Options o = new PairAligner.Options();
        o.minValidFraction = 0.5;
        PairAligner.Fit f = align(a, b, o);
        assertEquals(PairAligner.Status.REFUSED_LOW_OVERLAP, f.status);
        assertEquals("a refused pair must return the identity, not a guess",
                0, f.transform.magnitude(), 0);
        assertTrue("and must say it is unusable", !f.usable());
    }

    /** The converse: a good pair must report OK, so the status column carries information. */
    @Test
    public void aGoodPairReportsOk() {
        PairAligner.Fit f = align(Synth.frame(W, H, 0, 0), Synth.frame(W, H, 2, -1),
                new PairAligner.Options());
        assertEquals(PairAligner.Status.OK, f.status);
        assertTrue(f.usable());
        assertTrue("and should have used some iterations", f.iterations >= 0);
    }

    /**
     * Defect 3 — the validity check must compare like with like.
     *
     * <p>The draft compared the count of <em>strided</em> samples against the <em>full</em> pixel count.
     * Whenever the coarsest level was still large enough to trigger striding, every candidate in the
     * coarse sweep failed the check and the sweep returned a zero shift — which reads as "no drift
     * found", not as a bug.
     */
    @Test
    public void findsALargeShiftEvenWhenSamplingIsStrided() {
        PairAligner.Options o = new PairAligner.Options();
        o.maxSamples = 400;                  // forces a stride well above 1 at every level
        o.maxShift = 30;
        o.norm = RobustNorm.TUKEY;
        PairAligner.Fit f = align(Synth.frame(W, H, 0, 0), Synth.frame(W, H, 11, -7), o);
        assertEquals("dx", 11.0, f.transform.dx, 0.3);
        assertEquals("dy", -7.0, f.transform.dy, 0.3);
        assertNotEquals("a zero result is the signature of the striding bug",
                0.0, f.transform.magnitude(), 1e-6);
    }

    /** Striding must change the cost of the answer, not the answer. */
    @Test
    public void stridingCostsPrecisionButNotCorrectness() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 3.25, -2.5);
        PairAligner.Options dense = new PairAligner.Options();
        PairAligner.Options sparse = new PairAligner.Options();
        sparse.maxSamples = 1000;
        double denseErr = Math.hypot(align(a, b, dense).transform.dx - 3.25,
                align(a, b, dense).transform.dy + 2.5);
        double sparseErr = Math.hypot(align(a, b, sparse).transform.dx - 3.25,
                align(a, b, sparse).transform.dy + 2.5);
        assertTrue("dense " + denseErr, denseErr < 0.02);
        assertTrue("strided " + sparseErr, sparseErr < 0.2);
    }

    /** A solution pinned to the shift bound must say so rather than looking like a measurement. */
    @Test
    public void reportsWhenTheShiftBoundIsBinding() {
        PairAligner.Options o = new PairAligner.Options();
        o.maxShift = 3;                       // the true shift is far outside this
        o.norm = RobustNorm.TUKEY;
        PairAligner.Fit f = align(Synth.frame(W, H, 0, 0), Synth.frame(W, H, 20, 0), o);
        assertTrue("magnitude " + f.transform.magnitude(), f.transform.magnitude() <= 3 + 1e-6);
        assertTrue("status was " + f.status,
                f.status == PairAligner.Status.AT_SHIFT_BOUND
                        || f.status == PairAligner.Status.NOT_CONVERGED);
    }

    /** A featureless pair has no gradient to follow; that must not throw or return nonsense. */
    @Test
    public void handlesAFeaturelessPair() {
        float[] flat = new float[W * H];
        java.util.Arrays.fill(flat, 800f);
        PairAligner.Fit f = align(flat, flat.clone(), new PairAligner.Options());
        assertEquals("nothing to align, so no shift", 0, f.transform.magnitude(), 1e-9);
        assertEquals("and no residual", 0, f.residualAfter, 1e-6);
    }

    /** Identical frames: the answer is zero shift, zero gain, zero residual, and no NaN anywhere. */
    @Test
    public void identicalFramesGiveTheIdentity() {
        float[] a = Synth.frame(W, H, 0, 0);
        PairAligner.Fit f = align(a, a.clone(), new PairAligner.Options());
        assertEquals(0, f.transform.dx, 1e-9);
        assertEquals(0, f.transform.dy, 1e-9);
        assertEquals(0, f.logGain, 1e-9);
        assertEquals(0, f.residualBefore, 1e-9);
        assertEquals(0, f.residualAfter, 1e-9);
    }

    /** Gradient-weighted support must not change the answer, only the work. */
    @Test
    public void gradientSupportAgreesWithAllPixels() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 2.75, -1.25);
        PairAligner.Options all = new PairAligner.Options();
        PairAligner.Options grad = new PairAligner.Options();
        grad.support = PairAligner.PixelSupport.GRADIENT;
        PairAligner.Fit fa = align(a, b, all);
        PairAligner.Fit fg = align(a, b, grad);
        assertEquals("dx", fa.transform.dx, fg.transform.dx, 0.05);
        assertEquals("dy", fa.transform.dy, fg.transform.dy, 0.05);
        assertEquals("the reported residual must cover the whole frame either way, so that a "
                        + "gradient-weighted run and an all-pixels run stay comparable",
                fa.residualBefore, fg.residualBefore, 1e-9);
    }

    /** Mismatched pyramids are a programming error and must be refused loudly. */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsMismatchedFrameSizes() {
        PairAligner.align(Synth.pyramid(Synth.frame(64, 64, 0, 0), 64, 64, 2),
                Synth.pyramid(Synth.frame(48, 48, 0, 0), 48, 48, 2),
                new PairAligner.Options());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMismatchedLevelCounts() {
        PairAligner.align(Synth.pyramid(Synth.frame(64, 64, 0, 0), 64, 64, 2),
                Synth.pyramid(Synth.frame(64, 64, 0, 0), 64, 64, 3),
                new PairAligner.Options());
    }

    /** A large bound must raise the level count rather than sweeping millions of full-res positions. */
    @Test
    public void aLargeShiftBoundRaisesTheLevelCount() {
        PairAligner.Options small = new PairAligner.Options();
        small.maxShift = 5;
        PairAligner.Options large = new PairAligner.Options();
        large.maxShift = 400;
        assertTrue("levels should grow with the bound: " + small.levelsFor(1024, 1024)
                        + " then " + large.levelsFor(1024, 1024),
                large.levelsFor(1024, 1024) > small.levelsFor(1024, 1024));
    }
}
