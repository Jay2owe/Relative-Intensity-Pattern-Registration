/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.PhaseCorrelation;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The arbiter has to be right before it can arbitrate.
 *
 * <p>{@link PhaseCorrelation} cross-checks the log-ratio estimator's motion traces in
 * {@link MotionSurvey}, so a bug in it would not present as a failure — it would present as the two
 * methods disagreeing, which reads as a finding about the data rather than about the code.
 *
 * <p><b>The content here is broadband on purpose.</b> The first version of this test used a sum of three
 * sinusoids, and phase correlation could not recover a 4 px shift from it to better than a factor of two.
 * That was not a bug: normalising the cross-power spectrum gives every frequency equal weight, so an
 * image with only a few frequencies produces a correlation surface that is a sum of a few cosines —
 * broad, oscillatory, and with many near-equal maxima. Phase correlation needs a broad spectrum to
 * localise anything, which real microscope frames have and a test tone does not. The limitation is
 * pinned as its own test at the bottom, because it also explains what the survey's agreement column
 * means on a recording whose field is nearly empty.
 */
public class PhaseCorrelationTest {

    private static final int W = 96;
    private static final int H = 80;
    /** Oversampling factor for the sub-pixel frames. A shift of one fine pixel is 1/4 of a coarse one. */
    private static final int FINE = 4;
    private static final int MARGIN = 64;

    /** The oversampled source, built once. Broadband, so the correlation peak is sharp. */
    private static final int FW = FINE * (W + MARGIN);
    private static final int FH = FINE * (H + MARGIN);
    private static final float[] FIELD = field(FW, FH, 20260810L, 3);

    /**
     * A frame at coarse resolution, cut from the oversampled field at a fine-pixel offset.
     *
     * <p><b>This is how to get exact sub-pixel truth without assuming an interpolator.</b> Shifting by a
     * whole fine pixel and then averaging 4x4 blocks down is a quarter-pixel shift at coarse resolution,
     * and the resampling is pixel integration — what a camera sensor does. Interpolating the test data
     * instead would bake a particular kernel into the truth and quietly favour whichever method assumes
     * the smoothest image.
     *
     * <p>Sign convention as everywhere else: content at {@code p} in the unshifted frame sits at
     * {@code p + (dx, dy)} here, which is why the crop origin moves by minus the shift.
     */
    private static float[] frame(double dx, double dy) {
        int ox = FINE * MARGIN / 2 - (int) Math.round(dx * FINE);
        int oy = FINE * MARGIN / 2 - (int) Math.round(dy * FINE);
        float[] out = new float[W * H];
        double norm = 1.0 / (FINE * FINE);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double s = 0;
                for (int j = 0; j < FINE; j++) {
                    int row = (oy + y * FINE + j) * FW + ox + x * FINE;
                    for (int i = 0; i < FINE; i++) s += FIELD[row + i];
                }
                out[y * W + x] = (float) (s * norm);
            }
        }
        return out;
    }

    /**
     * A smoothed random field: broadband, with structure at every scale down to a few pixels.
     *
     * <p>{@code passes} controls how much of the fine detail survives. Too much smoothing and the finest
     * feature becomes coarser than the border taper the transform applies, at which point the taper is
     * the dominant structure in the frame, it does not move, and the correlation peak sits at zero.
     * That is exactly how the first version of this test failed.
     */
    private static float[] field(int w, int h, long seed, int passes) {
        Random rng = new Random(seed);
        float[] f = new float[w * h];
        for (int i = 0; i < f.length; i++) f[i] = (float) (128 + 40 * rng.nextGaussian());
        for (int pass = 0; pass < passes; pass++) {
            float[] t = f.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    f[y * w + x] = (t[y * w + x] * 4
                            + t[y * w + x - 1] + t[y * w + x + 1]
                            + t[(y - 1) * w + x] + t[(y + 1) * w + x]) / 8f;
                }
            }
        }
        return f;
    }

    private static float[] crop(float[] f, int fw, int ox, int oy) {
        float[] out = new float[W * H];
        for (int y = 0; y < H; y++) {
            System.arraycopy(f, (y + oy) * fw + ox, out, y * W, W);
        }
        return out;
    }

    /**
     * A whole-pixel shift taken as two crops of one field: an exact translation, no model assumed.
     *
     * <p>The strongest form of this test, because nothing is interpolated and nothing is generated by a
     * formula the estimator could be in sympathy with. Content at {@code p} in {@code a} sits at
     * {@code p + d} in {@code b} when {@code b}'s crop origin is {@code a}'s minus {@code d}.
     */
    @Test
    public void recoversAnExactCropOffsetWithTheProjectSignConvention() {
        int fw = W + 64;
        int fh = H + 64;
        float[] f = field(fw, fh, 4242L, 2);
        int ox = 32;
        int oy = 32;
        int dx = 5;
        int dy = -4;
        double[] d = PhaseCorrelation.shift(crop(f, fw, ox, oy), crop(f, fw, ox - dx, oy - dy), W, H);
        assertEquals("dx", dx, d[0], 0.10);
        assertEquals("dy", dy, d[1], 0.10);
    }

    @Test
    public void recoversWholePixelShift() {
        double[] d = PhaseCorrelation.shift(frame(0, 0), frame(4, -3), W, H);
        assertEquals("dx", 4.0, d[0], 0.10);
        assertEquals("dy", -3.0, d[1], 0.10);
    }

    /**
     * Sub-pixel accuracy, to the tolerance phase correlation actually achieves rather than a hoped-for
     * one.
     *
     * <p>A quarter-pixel truth comes back to within about 0.15 px here, on clean broadband synthetic
     * data with no noise and no intensity change. That is the parabolic peak fit's honest limit, not a
     * defect: the correlation surface is sampled on the pixel grid, and three samples through a peak
     * only locate it so well. It is also the right context for the injected-drift result in
     * {@code 00_CASE.md}, where phase correlation's median error on a real recording was 0.980 px —
     * most of that gap is the data, but a fifth of a pixel of it is the method's own floor.
     */
    @Test
    public void recoversSubPixelShift() {
        double[] d = PhaseCorrelation.shift(frame(0, 0), frame(2.5, 1.25), W, H);
        assertEquals("dx", 2.5, d[0], 0.25);
        assertEquals("dy", 1.25, d[1], 0.25);
    }

    @Test
    public void isAntisymmetricUnderSwappingTheFrames() {
        float[] a = frame(0, 0);
        float[] b = frame(3, 2);
        double[] ab = PhaseCorrelation.shift(a, b, W, H);
        double[] ba = PhaseCorrelation.shift(b, a, W, H);
        assertEquals("dx", -ab[0], ba[0], 0.05);
        assertEquals("dy", -ab[1], ba[1], 0.05);
    }

    /**
     * A brightness change must not move the peak.
     *
     * <p>This is why phase correlation is a fair baseline for the gain-invariance claim rather than a
     * straw man: for a pure global scaling it is already invariant, so the comparison in
     * {@code 00_CASE.md} is against a method that does not lose on this axis for free. Where it does
     * lose is sparse change, which is measured separately.
     *
     * <p>It is also the test that caught the real defect in this file. With an absolute magnitude floor
     * instead of a relative one, scaling by 0.4 moved a 3 px answer to 0.36 px — brightness changed
     * which near-empty frequencies were admitted, in the one routine whose whole premise is that
     * brightness is irrelevant.
     */
    @Test
    public void isInvariantToAGlobalGain() {
        float[] a = frame(0, 0);
        float[] b = frame(3, -2);
        for (int i = 0; i < b.length; i++) b[i] *= 0.4f;
        double[] d = PhaseCorrelation.shift(a, b, W, H);
        assertEquals("dx", 3.0, d[0], 0.10);
        assertEquals("dy", -2.0, d[1], 0.10);
    }

    @Test
    public void verifiedPeakSurvivesALocalIntensityPulse() {
        float[] a = frame(0, 0);
        float[] b = a.clone();
        for (int y = 20; y < 48; y++) {
            for (int x = 42; x < 72; x++) b[y * W + x] *= 5.0f;
        }
        double[] d = PhaseCorrelation.shiftVerified(a, b, W, H, 5);
        assertEquals("dx", 0.0, d[0], 0.0);
        assertEquals("dy", 0.0, d[1], 0.0);
    }

    @Test
    public void verifiedPeakRejectsAThinWrappedOverlap() {
        int size = 64;
        float[] a = field(size, size, 9090L, 2);
        float[] b = new float[a.length];
        for (int y = 3; y < size; y++) {
            System.arraycopy(a, (y - 3) * size, b, y * size, size);
        }
        // Make the wrapped -61 px interpretation look perfect if its three-row overlap is allowed.
        for (int y = 0; y < 3; y++) {
            System.arraycopy(a, (size - 3 + y) * size, b, y * size, size);
        }
        for (int y = 16; y < 46; y++) {
            for (int x = 8; x < 56; x++) b[y * size + x] *= 4.0f;
        }

        double[] d = PhaseCorrelation.shiftVerified(a, b, size, size, 5);

        assertEquals("dx", 0.0, d[0], 0.0);
        assertEquals("dy", 3.0, d[1], 0.0);
    }

    @Test
    public void zeroShiftIsZero() {
        double[] d = PhaseCorrelation.shift(frame(0, 0), frame(0, 0), W, H);
        assertEquals(0.0, Math.hypot(d[0], d[1]), 0.02);
    }

    /**
     * Documents the limitation rather than hiding it: a nearly empty spectrum defeats the method.
     *
     * <p>Three sinusoids, a true 4 px shift, and phase correlation cannot get within a pixel of it. This
     * matters for reading {@link MotionSurvey}'s agreement column — on a recording whose field is mostly
     * flat background, a disagreement between the two estimators is expected and is not evidence that
     * the log-ratio trace is wrong.
     */
    @Test
    public void aNearlyEmptySpectrumDefeatsIt() {
        float[] a = tone(0, 0);
        float[] b = tone(4, -3);
        double[] d = PhaseCorrelation.shift(a, b, W, H);
        double error = Math.hypot(d[0] - 4, d[1] + 3);
        assertTrue("a three-component image should NOT be recoverable to a pixel; got error "
                + error, error > 1.0);
    }

    private static float[] tone(double dx, double dy) {
        float[] a = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double u = x - dx;
                double v = y - dy;
                a[y * W + x] = (float) (128
                        + 40 * Math.sin(2 * Math.PI * u / 17.0)
                        + 30 * Math.cos(2 * Math.PI * v / 13.0)
                        + 20 * Math.sin(2 * Math.PI * (u + v) / 23.0));
            }
        }
        return a;
    }

    /** The transform must round-trip, or every number downstream is built on a broken kernel. */
    @Test
    public void fftInvertsItself() {
        int n = 64;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.sin(i * 0.37) + 0.5 * Math.cos(i * 1.1);
        double[] originalRe = re.clone();
        PhaseCorrelation.fft(re, im, false);
        PhaseCorrelation.fft(re, im, true);
        for (int i = 0; i < n; i++) {
            assertEquals("sample " + i, originalRe[i], re[i], 1e-9);
            assertEquals("imaginary part " + i, 0.0, im[i], 1e-9);
        }
    }

    @Test
    public void nextPowerOfTwoIsExactAtPowersOfTwo() {
        assertEquals(64, PhaseCorrelation.nextPowerOfTwo(64));
        assertEquals(128, PhaseCorrelation.nextPowerOfTwo(65));
        assertEquals(512, PhaseCorrelation.nextPowerOfTwo(384));
        assertTrue(PhaseCorrelation.nextPowerOfTwo(1) >= 1);
    }
}
