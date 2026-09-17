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
import static org.junit.Assert.assertTrue;

/**
 * Does {@link LogPlane#linearPyramid(int)} decimate in the intensity domain, and only there?
 *
 * <p>It exists to isolate one variable in the estimator comparison, so the only thing that matters is
 * that it changes exactly the thing it claims and nothing else. A bug here would not fail loudly; it
 * would produce a plausible number and send the estimator question the wrong way. Every claim below is
 * one the method's javadoc makes.
 */
public class LinearPyramidTest {

    private static final int W = 64;
    private static final int H = 64;
    private static final double EPSILON = 1.0;

    private static LogPlane plane(float[] intensity) {
        return LogPlane.of(intensity, W, H, EPSILON);
    }

    /** A field with strong local contrast, where a geometric and an arithmetic mean must disagree. */
    private static float[] checker(float dark, float bright) {
        float[] out = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                out[y * W + x] = ((x + y) & 1) == 0 ? dark : bright;
            }
        }
        return out;
    }

    private static float[] flat(float value) {
        float[] out = new float[W * H];
        java.util.Arrays.fill(out, value);
        return out;
    }

    @Test
    public void levelZeroIsTheSamePlaneInBothPyramids() {
        LogPlane base = plane(checker(10, 400));
        assertTrue("level 0 must be shared, not rebuilt",
                base.pyramid(3)[0] == base.linearPyramid(3)[0]);
    }

    @Test
    public void bothPyramidsHaveTheSameShape() {
        LogPlane base = plane(checker(10, 400));
        LogPlane[] log = base.pyramid(3);
        LogPlane[] linear = base.linearPyramid(3);
        assertEquals(log.length, linear.length);
        for (int level = 0; level < log.length; level++) {
            assertEquals(log[level].width, linear[level].width);
            assertEquals(log[level].height, linear[level].height);
        }
    }

    /**
     * On a uniform field every mean is the same mean, so the two pyramids must agree exactly. This is
     * the control: a difference here would mean the linear path has an arithmetic error rather than a
     * different averaging rule.
     */
    @Test
    public void aFlatFieldDecimatesIdenticallyInEitherDomain() {
        LogPlane base = plane(flat(250f));
        LogPlane log = base.pyramid(3)[2];
        LogPlane linear = base.linearPyramid(3)[2];
        for (int i = 0; i < log.v.length; i++) {
            if (!log.valid[i] || !linear.valid[i]) continue;
            assertEquals("flat field, level 2, pixel " + i, log.v[i], linear.v[i], 1e-4);
        }
    }

    /**
     * On an uneven field they must differ, and in a known direction: the arithmetic mean of positive
     * numbers is never below the geometric mean, so the linear pyramid must be at least as bright
     * everywhere and strictly brighter somewhere.
     */
    @Test
    public void anUnevenFieldIsBrighterUnderArithmeticDecimation() {
        LogPlane base = plane(checker(10, 400));
        LogPlane log = base.pyramid(2)[1];
        LogPlane linear = base.linearPyramid(2)[1];
        int strictlyBrighter = 0;
        for (int i = 0; i < log.v.length; i++) {
            if (!log.valid[i] || !linear.valid[i]) continue;
            assertTrue("arithmetic mean below geometric at pixel " + i,
                    linear.v[i] >= log.v[i] - 1e-5);
            if (linear.v[i] > log.v[i] + 1e-4) strictlyBrighter++;
        }
        assertTrue("expected the two decimations to differ on a high-contrast field",
                strictlyBrighter > 0);
    }

    /**
     * The epsilon added before the log passes through an arithmetic mean unchanged, so a coarse pixel
     * of a two-value field must be {@code log2(mean(I) + epsilon)} and not something that has lost or
     * doubled the offset. Checked against the value computed directly.
     *
     * <p>Interior pixels only. The 5-tap kernel splits a checkerboard exactly half and half — offsets
     * -2, 0, +2 carry weight 1 + 6 + 1 against -1, +1 carrying 4 + 4 — but only where all five taps
     * land inside the frame. At a border the clamp doubles up the near side and the split becomes
     * uneven, which is a property of the shared blur rather than of the decimation domain being tested
     * here.
     */
    @Test
    public void theEpsilonPassesThroughTheArithmeticMean() {
        float dark = 10;
        float bright = 400;
        LogPlane linear = plane(checker(dark, bright)).linearPyramid(2)[1];
        double expected = Math.log((dark + bright) / 2.0 + EPSILON) / Math.log(2);
        int checked = 0;
        for (int y = 3; y < linear.height - 3; y++) {
            for (int x = 3; x < linear.width - 3; x++) {
                int i = y * linear.width + x;
                if (!linear.valid[i]) continue;
                assertEquals("coarse pixel " + x + "," + y, expected, linear.v[i], 0.02);
                checked++;
            }
        }
        assertTrue("no valid interior coarse pixels to check", checked > 0);
    }

    /** Only the linear estimator asks for the linear pyramid; nothing else may be given one. */
    @Test
    public void onlyTheLinearEstimatorAsksForIntensityDomainLevels() {
        assertFalse(PairEstimator.Kind.LOG_RATIO_FIT.prefersLinearPyramid());
        assertFalse(PairEstimator.Kind.AREA_CORRELATION.prefersLinearPyramid());
        assertTrue(PairEstimator.Kind.AREA_CORRELATION_LINEAR.prefersLinearPyramid());
    }

    /** The new arm must reach the same correlation code, not a copy that could drift from it. */
    @Test
    public void theLinearArmRecoversAKnownSubPixelShift() {
        int size = Synth.DEFAULT_SIZE;
        float[] a = Synth.frame(size, size, 0, 0);
        float[] b = Synth.frame(size, size, 2.4, -1.7);
        PairAligner.Options o = new PairAligner.Options();
        int levels = o.levelsFor(size, size);
        LogPlane[] pa = LogPlane.of(a, size, size, 1.0).linearPyramid(levels);
        LogPlane[] pb = LogPlane.of(b, size, size, 1.0).linearPyramid(levels);
        PairAligner.Fit fit = PairEstimator.Kind.AREA_CORRELATION_LINEAR.estimate(pa, pb, o);
        assertEquals(PairAligner.Status.OK, fit.status);
        double error = Math.hypot(fit.transform.dx - 2.4, fit.transform.dy + 1.7);
        assertTrue("linear-pyramid arm recovered " + fit.transform + ", error " + error,
                error < 0.05);
    }

    /** An estimator kind must round-trip through its identifier, since it reaches macros and logs. */
    @Test
    public void theLinearArmIsNameableAndParsable() {
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_LINEAR,
                PairEstimator.Kind.of("area_correlation_linear"));
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_LINEAR,
                PairEstimator.Kind.of("Area-Correlation-Linear"));
    }
}
