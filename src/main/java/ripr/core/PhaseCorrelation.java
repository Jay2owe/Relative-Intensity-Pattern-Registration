/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

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
        Surface surface = surface(a, b, w, h, normalise);
        double[] br = surface.values;
        int n = surface.size;
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
     * Whole-pixel displacement chosen by overlap correlation from the strongest phase peaks.
     *
     * <p>A local light pulse can make a false phase peak taller than the true movement peak. Testing
     * several candidates against all overlapping pixels makes the final choice depend on the tissue as
     * a whole. Pearson correlation is used for that check, so a global intensity gain does not matter.
     */
    public static double[] shiftVerified(float[] a, float[] b, int w, int h, int peakCount) {
        if (peakCount < 1) throw new IllegalArgumentException("peakCount must be positive");
        Surface surface = surface(a, b, w, h, true);
        int n = surface.size;
        int[] peaks = strongestLocalPeaks(surface.values, n, peakCount);
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestX = 0;
        int bestY = 0;
        for (int peak : peaks) {
            int px = peak % n;
            int py = peak / n;
            int baseX = px > n / 2 ? px - n : px;
            int baseY = py > n / 2 ? py - n : py;
            int[] possibleX = {baseX, baseX < 0 ? baseX + n : baseX - n};
            int[] possibleY = {baseY, baseY < 0 ? baseY + n : baseY - n};
            for (int dx : possibleX) {
                for (int dy : possibleY) {
                    // A periodic Fourier peak also names a wrapped copy of the same position. A copy
                    // with only a thin strip of real overlap can score almost perfectly by accident;
                    // it cannot support registration of the tissue as a whole.
                    if (Math.abs(dx) * 2 > w || Math.abs(dy) * 2 > h) continue;
                    double score = overlapCorrelation(a, b, w, h, dx, dy);
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = dx;
                        bestY = dy;
                    }
                }
            }
        }
        if (!Double.isFinite(bestScore)) {
            throw new IllegalStateException("no phase peak has enough image overlap");
        }
        return new double[]{bestX, bestY};
    }

    private static Surface surface(float[] a, float[] b, int w, int h, boolean normalise) {
        if (a == null || b == null || w < 1 || h < 1
                || a.length != w * h || b.length != w * h) {
            throw new IllegalArgumentException("planes do not match the declared dimensions");
        }
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
        return new Surface(br, n);
    }

    private static int[] strongestLocalPeaks(double[] values, int n, int count) {
        java.util.List<Integer> peaks = new java.util.ArrayList<>();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double value = values[y * n + x];
                boolean maximum = true;
                for (int oy = -1; oy <= 1 && maximum; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) continue;
                        if (values[wrap(y + oy, n) * n + wrap(x + ox, n)] > value) {
                            maximum = false;
                            break;
                        }
                    }
                }
                if (maximum) peaks.add(y * n + x);
            }
        }
        peaks.sort((left, right) -> Double.compare(values[right], values[left]));
        int size = Math.min(count, peaks.size());
        int[] out = new int[size];
        for (int i = 0; i < size; i++) out[i] = peaks.get(i);
        return out;
    }

    /** Pearson correlation between the overlapping pixels at a proposed content displacement. */
    private static double overlapCorrelation(float[] a, float[] b, int w, int h,
                                             int dx, int dy) {
        int ax0 = Math.max(0, -dx);
        int ax1 = Math.min(w, w - dx);
        int ay0 = Math.max(0, -dy);
        int ay1 = Math.min(h, h - dy);
        if (ax1 - ax0 < 3 || ay1 - ay0 < 3) return Double.NaN;
        long count = (long) (ax1 - ax0) * (ay1 - ay0);
        double sumA = 0;
        double sumB = 0;
        for (int y = ay0; y < ay1; y++) {
            int aRow = y * w;
            int bRow = (y + dy) * w;
            for (int x = ax0; x < ax1; x++) {
                sumA += a[aRow + x];
                sumB += b[bRow + x + dx];
            }
        }
        double meanA = sumA / count;
        double meanB = sumB / count;
        double varianceA = 0;
        double varianceB = 0;
        double covariance = 0;
        for (int y = ay0; y < ay1; y++) {
            int aRow = y * w;
            int bRow = (y + dy) * w;
            for (int x = ax0; x < ax1; x++) {
                double da = a[aRow + x] - meanA;
                double db = b[bRow + x + dx] - meanB;
                varianceA += da * da;
                varianceB += db * db;
                covariance += da * db;
            }
        }
        if (varianceA == 0 || varianceB == 0) {
            return varianceA == varianceB && meanA == meanB ? 1.0 : 0.0;
        }
        return covariance / Math.sqrt(varianceA * varianceB);
    }

    private static final class Surface {
        final double[] values;
        final int size;

        Surface(double[] values, int size) {
            this.values = values;
            this.size = size;
        }
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
