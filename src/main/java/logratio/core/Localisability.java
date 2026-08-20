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
 * How much frame-to-frame correlation a recording loses when one frame is displaced a single pixel.
 *
 * <p><b>What it is for.</b> Deciding, before anything is registered, whether this channel can be
 * registered at all — and which of several channels to estimate from. It is computed from the pixels
 * alone, needs no transform, no truth and no second method, and costs one extra correlation per frame
 * pair.
 *
 * <p><b>Why plain frame correlation is the wrong criterion, and this is the fix.</b> Correlation near
 * zero does rule out photon-starved noise, which is what it was introduced for. But it is equally near
 * one for a smooth, featureless blob on a smooth background, and a smooth blob cannot be localised
 * either — for the opposite reason. Ranking by correlation on the first recording surveyed chose a
 * saturated fluorescence channel, on which two independent estimators then disagreed by 15.7 px per
 * step. What registration actually needs is a correlation that <em>falls away sharply</em> when the
 * frames are misaligned, because that fall is the gradient the solver descends.
 *
 * <p><b>What the numbers mean.</b> Across 24 real IncuCyte recordings the measure separated the usable
 * from the unusable by an order of magnitude, and predicted the agreement between two independent
 * estimators — which is the closest thing to ground truth available without injecting known motion:
 *
 * <table border="1">
 *   <caption>Survey of 24 recordings, {@code library/survey/motion_survey.csv}</caption>
 *   <tr><th>recordings</th><th>localisability</th><th>agreement of two independent methods</th></tr>
 *   <tr><td>13</td><td>0.086 – 0.284</td><td>0.53 – 0.68 px</td></tr>
 *   <tr><td>11</td><td>0.008 – 0.031</td><td>1.6 – 18.6 px</td></tr>
 * </table>
 *
 * <p>The gap between the two bands is where {@link #WARN_BELOW} sits. It is a warning threshold, not a
 * refusal: a low value means no method will localise this channel well, not that this one has failed.
 *
 * <p><b>The threshold is calibrated on real, noisy recordings, and that is a real caveat.</b> The
 * measure asks whether a one-pixel error is visible in these pixels. On noise-free synthetic content it
 * can read low and registration still succeed: the analytic fixture in {@code Synth} is built from
 * 3-13 cycle sinusoids, scores 0.032, and its shift is recovered to a hundredth of a pixel, because
 * with no noise even a shallow correlation fall is a clean gradient. Read the threshold as "on data
 * like the survey's", not as a law.
 */
public final class Localisability {

    /**
     * Below this, warn the user that the channel is poorly localisable.
     *
     * <p>Placed in the empty gap between the two bands measured across the survey — 0.031 was the best
     * of the eleven unusable recordings and 0.086 the worst of the thirteen usable ones. Nothing was
     * measured between them, so the threshold is not fitted to a boundary case.
     */
    public static final double WARN_BELOW = 0.05;

    private Localisability() {
    }

    /**
     * Localisability of a whole recording: the mean over consecutive frame pairs of the fall in
     * correlation caused by displacing the second frame one pixel, averaged over the two axes.
     *
     * @return {@link Double#NaN} when fewer than two frames carry a defined correlation
     */
    public static double of(FrameSource source) {
        if (source == null) throw new IllegalArgumentException("source is null");
        int n = source.count();
        int w = source.width();
        int h = source.height();
        if (n < 2) return Double.NaN;
        double total = 0;
        int pairs = 0;
        float[] prev = source.plane(0);
        for (int t = 1; t < n; t++) {
            float[] cur = source.plane(t);
            double d = ofPair(prev, cur, w, h);
            if (!Double.isNaN(d)) {
                total += d;
                pairs++;
            }
            prev = cur;
        }
        return pairs > 0 ? total / pairs : Double.NaN;
    }

    /**
     * Localisability of one frame pair. Exposed so a caller can rank frames, or spot the transition
     * where a recording stops being registrable, rather than only the recording as a whole.
     */
    public static double ofPair(float[] a, float[] b, int width, int height) {
        if (a.length != b.length || a.length != width * height) {
            throw new IllegalArgumentException("expected two " + width + "x" + height + " planes");
        }
        double at = correlation(a, b);
        if (Double.isNaN(at)) return Double.NaN;
        double shiftedX = correlation(a, roll(b, width, height, 1, 0));
        double shiftedY = correlation(a, roll(b, width, height, 0, 1));
        if (Double.isNaN(shiftedX) || Double.isNaN(shiftedY)) return Double.NaN;
        return at - 0.5 * (shiftedX + shiftedY);
    }

    /** True when {@code value} is a measured number below {@link #WARN_BELOW}. NaN does not warn. */
    public static boolean poor(double value) {
        return value < WARN_BELOW;
    }

    /** Whole-pixel shift with edge clamping. Only ever one pixel, so the clamp is immaterial. */
    private static float[] roll(float[] a, int w, int h, int dx, int dy) {
        float[] out = new float[a.length];
        for (int y = 0; y < h; y++) {
            int sy = y + dy < 0 ? 0 : (y + dy >= h ? h - 1 : y + dy);
            int srow = sy * w;
            int drow = y * w;
            for (int x = 0; x < w; x++) {
                int sx = x + dx < 0 ? 0 : (x + dx >= w ? w - 1 : x + dx);
                out[drow + x] = a[srow + sx];
            }
        }
        return out;
    }

    /**
     * Pearson correlation over the pixels defined in both planes.
     *
     * <p>NaN pixels are skipped in pairs rather than treated as zero: a masked margin is a common way
     * to mark "not measured", and letting it into the sums would make two frames look correlated
     * because their masks agree.
     */
    public static double correlation(float[] a, float[] b) {
        double ma = 0;
        double mb = 0;
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (Float.isNaN(a[i]) || Float.isNaN(b[i])) continue;
            ma += a[i];
            mb += b[i];
            n++;
        }
        if (n < 2) return Double.NaN;
        ma /= n;
        mb /= n;
        double saa = 0;
        double sbb = 0;
        double sab = 0;
        for (int i = 0; i < a.length; i++) {
            if (Float.isNaN(a[i]) || Float.isNaN(b[i])) continue;
            double da = a[i] - ma;
            double db = b[i] - mb;
            saa += da * da;
            sbb += db * db;
            sab += da * db;
        }
        double denom = Math.sqrt(saa * sbb);
        return denom > 0 ? sab / denom : Double.NaN;
    }
}
