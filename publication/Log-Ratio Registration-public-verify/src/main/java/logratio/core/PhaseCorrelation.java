/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

/**
 * Small self-contained phase correlation used by the automatic information selector and exposed as an
 * independent benchmark baseline.
 *
 * <p>This exists to keep the survey honest. The motion trace used to classify a recording comes from
 * the plugin, so it cannot also be the evidence that the plugin read that recording correctly. Phase
 * correlation shares no machinery with the log-ratio estimator — different domain, different objective,
 * no robust weighting, no gain model — so where the two agree the classification is trustworthy, and
 * where they disagree that is a finding in its own right.
 *
 * <p>It has no external numerical dependency: the radix-2 Fourier transform below is part of the class.
 * The automatic selector needs one fast, gain-invariant shift estimate before it can decide whether a
 * dense field contains independently moving structure. Keeping the exact benchmark-tested implementation
 * here prevents the production selector becoming a different algorithm from the measured selector.
 *
 * <p><b>Sign convention matches the rest of the project.</b> {@link #shift} returns the displacement of
 * the content from {@code a} to {@code b}, so if {@code b(x) = a(x - d)} the answer is {@code d} — the
 * same thing {@code PairAligner.align(a, b)} reports.
 */
public final class PhaseCorrelation {

    /**
     * A frequency must carry at least this fraction of its own image's peak amplitude to vote.
     *
     * <p><b>Relative, and that is the whole point.</b> Normalising the cross-power spectrum gives every
     * frequency equal weight, which is what makes phase correlation contrast-invariant — and also means
     * a frequency holding nothing but windowing leakage arrives with weight one and a meaningless phase.
     * With an absolute floor instead of this one, two of the tests in {@code PhaseCorrelationTest}
     * failed: a half-pixel shift came back as 0.25 px, and scaling one frame by 0.4 moved a 3 px answer
     * to 0.36 px. The second failure is the diagnostic — an absolute threshold is not scale-free, so
     * changing the brightness changed which empty frequencies were admitted, in a routine whose entire
     * selling point as a baseline is that brightness does not matter to it.
     */
    private static final double SPECTRAL_FLOOR = 1e-6;
    /** Total fraction of each axis given over to the border taper, split between the two edges. */
    private static final double TAPER_FRACTION = 0.25;

    private PhaseCorrelation() {
    }

    /**
     * Displacement of the content from {@code a} to {@code b}, in pixels, to sub-pixel precision.
     *
     * <p>Both planes are mean-subtracted and Hann-windowed before transforming. The window is not
     * optional: a rectangular edge is a step discontinuity to the transform, and its spectrum swamps a
     * few pixels of real translation.
     */
    public static double[] shift(float[] a, float[] b, int w, int h) {
        return shift(a, b, w, h, true);
    }

    /**
     * @param normalise true for phase correlation; false for plain cross-correlation, which leaves the
     *                  cross-power spectrum unnormalised so high-energy low frequencies dominate the
     *                  peak. Included as a baseline because it isolates what the phase normalisation
     *                  itself buys — and it is also the only one of these that is not gain-invariant.
     */
    public static double[] shift(float[] a, float[] b, int w, int h, boolean normalise) {
        int n = Math.max(nextPowerOfTwo(w), nextPowerOfTwo(h));
        double[] ar = new double[n * n];
        double[] ai = new double[n * n];
        double[] br = new double[n * n];
        double[] bi = new double[n * n];
        window(a, ar, w, h, n);
        window(b, br, w, h, n);

        fft2(ar, ai, n, false);
        fft2(br, bi, n, false);

        // R = F_b * conj(F_a), normalised to unit magnitude. The inverse transform of that is a delta at
        // the displacement itself, which is why phase correlation is insensitive to contrast: every
        // frequency contributes equally regardless of how much energy the image has there.
        //
        // Each spectrum is thresholded against its OWN peak before that, so a frequency neither image
        // actually has is dropped rather than promoted to full weight.
        double maxA = 0;
        double maxB = 0;
        for (int i = 0; i < ar.length; i++) {
            maxA = Math.max(maxA, Math.hypot(ar[i], ai[i]));
            maxB = Math.max(maxB, Math.hypot(br[i], bi[i]));
        }
        double floorA = SPECTRAL_FLOOR * maxA;
        double floorB = SPECTRAL_FLOOR * maxB;
        for (int i = 0; i < ar.length; i++) {
            if (normalise && (Math.hypot(ar[i], ai[i]) < floorA
                    || Math.hypot(br[i], bi[i]) < floorB)) {
                br[i] = 0;
                bi[i] = 0;
                continue;
            }
            double re = br[i] * ar[i] + bi[i] * ai[i];
            double im = bi[i] * ar[i] - br[i] * ai[i];
            double mag = normalise ? Math.hypot(re, im) : 1.0;
            if (mag <= 0) {
                br[i] = 0;
                bi[i] = 0;
                continue;
            }
            br[i] = re / mag;
            bi[i] = im / mag;
        }
        fft2(br, bi, n, true);

        int peak = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < br.length; i++) {
            if (br[i] > best) {
                best = br[i];
                peak = i;
            }
        }
        int py = peak / n;
        int px = peak % n;
        double dx = px + parabolic(br, n, px, py, true);
        double dy = py + parabolic(br, n, px, py, false);
        // The correlation surface is periodic, so a displacement to the left appears near the far edge.
        if (dx > n / 2.0) dx -= n;
        if (dy > n / 2.0) dy -= n;
        return new double[]{dx, dy};
    }

    /**
     * Sub-pixel offset of the peak from a parabola through its two neighbours.
     *
     * <p>Clamped to half a pixel. An unclamped fit on a flat or double-peaked surface can return a
     * large offset from three nearly equal samples, which would be a confident answer built on nothing.
     */
    private static double parabolic(double[] s, int n, int px, int py, boolean horizontal) {
        int before = horizontal ? wrap(px - 1, n) + py * n : px + wrap(py - 1, n) * n;
        int at = px + py * n;
        int after = horizontal ? wrap(px + 1, n) + py * n : px + wrap(py + 1, n) * n;
        double denom = s[before] - 2 * s[at] + s[after];
        if (Math.abs(denom) < 1e-20) return 0;
        double d = 0.5 * (s[before] - s[after]) / denom;
        return Math.max(-0.5, Math.min(0.5, d));
    }

    private static int wrap(int i, int n) {
        return (i + n) % n;
    }

    /** Mean-subtract inside the real region, taper its border, zero-pad the rest. */
    private static void window(float[] src, double[] dst, int w, int h, int n) {
        double mean = 0;
        for (float v : src) mean += v;
        mean /= src.length;
        double[] wx = taper(w);
        double[] wy = taper(h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst[y * n + x] = (src[y * w + x] - mean) * wx[x] * wy[y];
            }
        }
    }

    /**
     * Cosine taper over the outer {@link #TAPER_FRACTION} of each axis, flat in the middle.
     *
     * <p><b>Not a Hann window, and the difference is a 30% error.</b> A Hann window modulates the whole
     * frame, so the two windowed frames are no longer translations of one another —
     * {@code w(x)a(x-d)} is not {@code w(x-d)a(x-d)} — and the correlation peak is pulled toward zero.
     * Measured on the tests here, a full Hann returned 2.77 px for a true 4 px shift and 2.09 px for a
     * true 3 px: the right direction, uniformly about 70% of the right size, which is the kind of error
     * that survives a sanity check and quietly biases everything downstream. A flat interior leaves the
     * translation intact and the taper still removes the edge discontinuity that the transform would
     * otherwise read as broadband signal.
     */
    private static double[] taper(int n) {
        double[] t = new double[n];
        int edge = Math.max(1, (int) Math.round(TAPER_FRACTION * n / 2.0));
        for (int i = 0; i < n; i++) {
            int d = Math.min(i, n - 1 - i);
            t[i] = d >= edge ? 1.0 : 0.5 - 0.5 * Math.cos(Math.PI * (d + 0.5) / edge);
        }
        return t;
    }

    public static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /** In-place 2-D transform of a square {@code n x n} array stored row-major. */
    public static void fft2(double[] re, double[] im, int n, boolean inverse) {
        double[] tr = new double[n];
        double[] ti = new double[n];
        for (int y = 0; y < n; y++) {
            System.arraycopy(re, y * n, tr, 0, n);
            System.arraycopy(im, y * n, ti, 0, n);
            fft(tr, ti, inverse);
            System.arraycopy(tr, 0, re, y * n, n);
            System.arraycopy(ti, 0, im, y * n, n);
        }
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                tr[y] = re[y * n + x];
                ti[y] = im[y * n + x];
            }
            fft(tr, ti, inverse);
            for (int y = 0; y < n; y++) {
                re[y * n + x] = tr[y];
                im[y * n + x] = ti[y];
            }
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey. {@code re.length} must be a power of two. */
    public static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k;
                    int v = i + k + len / 2;
                    double vr = re[v] * cr - im[v] * ci;
                    double vi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
