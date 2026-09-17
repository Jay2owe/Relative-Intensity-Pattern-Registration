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
 * Replaces frames whose transform is not evidence.
 *
 * <p><b>Why this exists at all.</b> Every chained registration method has the same silent failure: one
 * unusable pair — a dropped frame, a shutter blink, a stage jump, a bleached-out interval — produces a
 * wrong step, and because the chain is cumulative that wrongness is inherited by <em>every later
 * frame</em>. Nothing in the output says so. The existing plugins do not detect it, and a user's first
 * sign is a stack that looks fine for forty frames and then is visibly offset for the remaining sixty.
 *
 * <p>Two conditions are repaired. A frame the aligner refused, or that no observation constrained, has
 * no estimate; and a step wildly inconsistent with its neighbours is more likely an alignment failure
 * than motion. Both are replaced by linear interpolation between the nearest trusted frames either
 * side, and both are reported, so a repaired frame is visible in the {@code status} column rather than
 * hidden in a plausible-looking number.
 *
 * <p>The outlier threshold is deliberately loose. A tight one would erase genuine fast motion, which is
 * the thing the user is trying to measure; the job here is to catch gross failure, not to smooth. At the
 * default of 8 median absolute deviations, only a step around an order of magnitude out of family is
 * touched.
 */
public final class ChainRepair {

    /** Default outlier threshold, in median absolute deviations of the step magnitude. */
    public static final double DEFAULT_OUTLIER_MADS = 8.0;

    /** A step is never called an outlier below this multiple of the median step. */
    private static final double RELATIVE_LIMIT = 6.0;

    private ChainRepair() {
    }

    /** Why a frame's transform was replaced. Null entries mean it was not. */
    public enum Reason {
        /** The aligner refused the pair, or no observation constrained the frame. */
        UNSUPPORTED,
        /** The step into this frame was grossly inconsistent with the rest of the recording. */
        OUTLIER_STEP
    }

    public static final class Result {
        /** The repaired cumulative transforms. A fresh array; the input is not modified. */
        public final Transform[] cumulative;
        /** Per frame, why it was replaced, or null. */
        public final Reason[] reasons;
        public final int repairedCount;

        Result(Transform[] cumulative, Reason[] reasons, int repairedCount) {
            this.cumulative = cumulative;
            this.reasons = reasons;
            this.repairedCount = repairedCount;
        }

        public boolean wasRepaired(int frame) {
            return reasons[frame] != null;
        }
    }

    /**
     * @param cumulative      per-frame transforms; not modified
     * @param support         how many observations constrained each frame; zero means no evidence
     * @param referenceFrame  always trusted, since it defines the coordinate system
     * @param outlierMads     step-magnitude outlier threshold, or 0 to skip outlier detection
     */
    public static Result repair(Transform[] cumulative, int[] support, int referenceFrame,
                                double outlierMads) {
        int n = cumulative.length;
        Reason[] reasons = new Reason[n];
        boolean[] trusted = new boolean[n];
        for (int t = 0; t < n; t++) {
            trusted[t] = cumulative[t] != null && (support == null || support[t] > 0);
            if (!trusted[t]) reasons[t] = Reason.UNSUPPORTED;
        }
        if (referenceFrame >= 0 && referenceFrame < n) {
            trusted[referenceFrame] = true;
            reasons[referenceFrame] = null;
        }

        if (outlierMads > 0) {
            markOutlierSteps(cumulative, trusted, reasons, outlierMads);
        }

        boolean any = false;
        for (int t = 0; t < n; t++) {
            if (!trusted[t]) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new Result(cumulative.clone(), reasons, 0);
        }

        Transform[] out = new Transform[n];
        int repaired = 0;
        for (int t = 0; t < n; t++) {
            if (trusted[t]) {
                out[t] = cumulative[t];
                continue;
            }
            repaired++;
            int before = -1;
            for (int i = t - 1; i >= 0; i--) {
                if (trusted[i]) {
                    before = i;
                    break;
                }
            }
            int after = -1;
            for (int i = t + 1; i < n; i++) {
                if (trusted[i]) {
                    after = i;
                    break;
                }
            }
            if (before < 0 && after < 0) {
                out[t] = Transform.IDENTITY;          // nothing at all to interpolate from
            } else if (before < 0) {
                out[t] = cumulative[after];           // leading gap: hold the first good value
            } else if (after < 0) {
                out[t] = cumulative[before];          // trailing gap: hold the last good value
            } else {
                double f = (double) (t - before) / (after - before);
                out[t] = lerp(cumulative[before], cumulative[after], f);
            }
        }
        return new Result(out, reasons, repaired);
    }

    /**
     * Flags steps whose magnitude is a gross outlier.
     *
     * <p>Measured between consecutive frames of the cumulative sequence rather than from the raw
     * per-transition estimates, so it applies equally to a chained solution and a multi-lag one.
     */
    private static void markOutlierSteps(Transform[] cum, boolean[] trusted, Reason[] reasons,
                                         double mads) {
        int n = cum.length;
        if (n < 4) return;                      // too short for a scale estimate to mean anything
        double[] mag = new double[n - 1];
        int m = 0;
        for (int t = 1; t < n; t++) {
            if (cum[t] == null || cum[t - 1] == null) continue;
            mag[m++] = Math.hypot(cum[t].dx - cum[t - 1].dx, cum[t].dy - cum[t - 1].dy);
        }
        if (m < 4) return;
        double[] sel = new double[m];
        System.arraycopy(mag, 0, sel, 0, m);
        double median = RobustNorm.select(sel, m, m / 2);
        for (int i = 0; i < m; i++) sel[i] = mag[i] - median;
        double scale = RobustNorm.scale(sel, m, 1e-6);

        // Two limits, and the larger wins. The MAD term alone has a specific failure: on a recording
        // whose step sizes are nearly constant — a smooth stage drift, which is common — the MAD
        // collapses to zero and the threshold degenerates to the median itself, so *any* deviation
        // becomes an outlier. Measured before this was fixed: a fourfold burst of genuine motion had
        // all four of its frames replaced by interpolation, which is the opposite of the intent.
        //
        // The relative term is the one that encodes "an order of magnitude out of family". A 4x burst
        // survives; a 400x jump does not.
        double limit = Math.max(median + mads * scale, RELATIVE_LIMIT * median);

        int idx = 0;
        for (int t = 1; t < n; t++) {
            if (cum[t] == null || cum[t - 1] == null) continue;
            if (mag[idx] > limit && trusted[t]) {
                trusted[t] = false;
                reasons[t] = Reason.OUTLIER_STEP;
            }
            idx++;
        }
    }

    /**
     * Linear interpolation in parameter space.
     *
     * <p>Parameter space rather than exact composition: a repaired frame is a guess between two
     * measurements, so the difference between {@link Transform#plus} and {@link Transform#then} — of
     * order {@code |t| * theta} — is far below the uncertainty already being accepted by interpolating
     * at all.
     */
    static Transform lerp(Transform a, Transform b, double f) {
        return new Transform(a.dx + f * (b.dx - a.dx),
                a.dy + f * (b.dy - a.dy),
                a.theta + f * (b.theta - a.theta));
    }
}
