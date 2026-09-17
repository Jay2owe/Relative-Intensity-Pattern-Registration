/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns pairwise alignments into one cumulative transform per frame.
 *
 * <p>This is where the reference strategy lives, and the choice matters more than it looks.
 *
 * <p><b>Chaining lag-1 steps is the obvious method and it has a specific, measurable failure.</b>
 * Each transition's estimate carries a small bias; cumulating a hundred of them integrates it. On one
 * 101-frame recording in the microglia pipeline the lag-1 chain left <em>more</em> net drift than doing
 * nothing at all — 4.61 px became 6.32 px — while its per-transition residual looked healthy at
 * 0.52 px, because a per-transition measure is structurally blind to a slow common bias. Anything
 * reading a fixed pixel's trajectory across many frames depends on the cumulative number, not the
 * per-transition one.
 *
 * <p><b>Registering everything to one reference frame fails worse, in the opposite direction.</b> Over
 * a long recording the field changes enough that late frames no longer resemble the first: measured on
 * the same data, all-to-frame-0 gave 2.598 px against 1.788 px for no correction. Offered because it is
 * what users expect and what most plugins do, but it is the wrong default for anything long.
 *
 * <p><b>The fix is to over-determine the problem and solve it globally.</b> Measure displacements at
 * several lags — 1, 2, 4, 8, 16 — and treat each as an observation {@code u[t+k] - u[t] = d}, then
 * solve the whole over-determined system at once so that a bias in the short lags is out-voted rather
 * than compounded. This is standard practice under other names: bundle adjustment for camera poses,
 * frame-motion correction for cryo-EM, and specifically <b>redundant cross-correlation</b> (Wang et
 * al., <i>Opt Express</i> 22:15982, 2014), which ships in Picasso and ThunderSTORM. <b>No novelty is
 * claimed for it here</b> — what is new is that a Fiji user can select it.
 *
 * <p>Long lags cost nothing in robustness: each pair alignment is bounded by {@code maxShift} and
 * refused when the overlap is too small, so a pair that has genuinely moved apart drops out rather
 * than misleading the fit.
 *
 * <p><b>Rotation and the parameter-space caveat.</b> The least-squares system is linear in its
 * unknowns, so the multi-lag solve necessarily works in parameter space, where composing two
 * transforms is {@link Transform#plus} rather than the exact {@link Transform#then}. The two differ by
 * a rotation applied to the earlier translation, so the solve is exact in the angle and first-order in
 * the translation, with an error of order {@code |t| * theta} — under a hundredth of a pixel for the
 * fractions of a degree that stage shake produces. The chained and fixed strategies use exact
 * composition and have no such caveat. v0.1.0 fits translation only, which makes the point moot; it is
 * recorded so that enabling rotation later does not silently change what the solve means.
 */
public final class Reconciler {

    /** Diagonal loading, as a fraction of the mean diagonal, to keep the normal equations SPD. */
    private static final double RIDGE = 1e-9;

    private Reconciler() {
    }

    /** Which frames are compared against which. */
    public enum Reference {
        /** Each frame to the one before, cumulated. The robust default. */
        CONSECUTIVE,
        /** Displacements at several lags, reconciled by least squares. Redundant cross-correlation. */
        MULTILAG,
        /** Every frame to one chosen frame. Expected, and wrong for long recordings. */
        FIXED,
        /** Each frame to a running mean of those already registered. Sequential by construction. */
        ROLLING
    }

    /** One measured displacement between two frames. */
    public static final class Observation {
        public final int from;
        public final int to;
        public final Transform displacement;
        public Observation(int from, int to, Transform displacement) {
            if (from == to) {
                throw new IllegalArgumentException("observation from == to == " + from);
            }
            this.from = from;
            this.to = to;
            this.displacement = displacement;
        }

        public int lag() {
            return Math.abs(to - from);
        }
    }

    /** Cumulative transforms plus how well each frame was constrained. */
    public static final class Solution {
        /** Per-frame transform relative to the reference frame. */
        public final Transform[] cumulative;
        /** How many observations touched each frame. Zero means the value is not evidence. */
        public final int[] support;

        Solution(Transform[] cumulative, int[] support) {
            this.cumulative = cumulative;
            this.support = support;
        }
    }

    /**
     * Which pairs a strategy needs, in a deterministic order so that the parallel scheduler's output
     * does not depend on completion order.
     *
     * <p>{@link Reference#ROLLING} returns an empty list: its target for frame {@code t} is built from
     * frames already registered, so its pairs cannot be enumerated in advance. That is a correctness
     * constraint, not an oversight — see {@link #rolling}.
     */
    public static List<Observation> planPairs(Reference reference, int frames, int referenceFrame,
                                              int lag, int[] lags) {
        List<Observation> out = new ArrayList<>();
        switch (reference) {
            case CONSECUTIVE:
                for (int t = 0; t + lag < frames; t++) out.add(new Observation(t, t + lag, null));
                break;
            case MULTILAG:
                // Frame-major, not lag-major. Both orders produce the same set of pairs and the same
                // answer, but lag-major sweeps the whole recording once per lag, so the pyramid cache
                // sees repeated 0..T scans and thrashes: measured at 38 pyramid builds for 12 frames.
                // Frame-major keeps the working set to maxLag + 1 frames, which is exactly what
                // PyramidCache.reachFor sizes the cache to hold.
                for (int t = 0; t < frames; t++) {
                    for (int k : lags) {
                        if (k < 1 || t + k >= frames) continue;
                        out.add(new Observation(t, t + k, null));
                    }
                }
                break;
            case FIXED:
                for (int t = 0; t < frames; t++) {
                    if (t != referenceFrame) out.add(new Observation(referenceFrame, t, null));
                }
                break;
            case ROLLING:
            default:
                break;
        }
        return out;
    }

    /**
     * Cumulate consecutive steps by exact composition. {@code steps[t]} maps frame {@code t} to
     * {@code t+1}; a null step is treated as the identity and recorded as unsupported.
     */
    public static Solution chain(Transform[] steps) {
        int frames = steps.length + 1;
        Transform[] cum = new Transform[frames];
        int[] support = new int[frames];
        cum[0] = Transform.IDENTITY;
        support[0] = 1;
        for (int t = 0; t < steps.length; t++) {
            Transform s = steps[t] == null ? Transform.IDENTITY : steps[t];
            cum[t + 1] = cum[t].then(s);
            support[t + 1] = steps[t] == null ? 0 : 1;
        }
        return new Solution(cum, support);
    }

    /**
     * Every frame measured directly against {@code referenceFrame}. {@code toRef[t]} is the
     * displacement from the reference to frame {@code t}; the reference's own entry is ignored.
     */
    public static Solution fixed(Transform[] toRef, int referenceFrame) {
        Transform[] cum = new Transform[toRef.length];
        int[] support = new int[toRef.length];
        for (int t = 0; t < toRef.length; t++) {
            if (t == referenceFrame) {
                cum[t] = Transform.IDENTITY;
                support[t] = 1;
            } else if (toRef[t] == null) {
                cum[t] = Transform.IDENTITY;
                support[t] = 0;
            } else {
                cum[t] = toRef[t];
                support[t] = 1;
            }
        }
        return new Solution(cum, support);
    }

    /**
     * Sequential alignment to a running template. The caller supplies the alignment because each step
     * depends on the previous result, so this cannot be planned or parallelised.
     *
     * @param steps displacement from the template in force at frame {@code t} to frame {@code t}
     */
    public static Solution rolling(Transform[] steps) {
        Transform[] cum = new Transform[steps.length];
        int[] support = new int[steps.length];
        for (int t = 0; t < steps.length; t++) {
            cum[t] = steps[t] == null ? Transform.IDENTITY : steps[t];
            support[t] = steps[t] == null ? 0 : 1;
        }
        return new Solution(cum, support);
    }

    /**
     * Least-squares reconciliation of displacements measured at several lags.
     *
     * <p>Frame 0 is the gauge and is <b>eliminated by substitution</b>, not pinned with a heavy
     * weight. Pinning works, and it is what a quick implementation does, but it adds
     * {@code W * u[0]^2} to the objective and therefore biases every other unknown by an amount that
     * depends on {@code W}. Substituting {@code u[0] = 0} removes the freedom exactly and leaves
     * nothing to tune.
     *
     * <p>The normal equations are <b>banded</b>: an observation contributes only to entries
     * {@code (from, from)}, {@code (to, to)} and {@code (from, to)}, so the bandwidth is the largest
     * lag present — 16 for the default lag set, whatever the frame count. Factoring that as a banded
     * Cholesky is {@code O(T b^2)}: about 256k operations at {@code T = 1000}, against 1.3 GFLOP and
     * 32 MB for the dense factorisation the same problem invites.
     */
    public static Solution multiLag(int frames, List<Observation> observations) {
        if (frames < 1) throw new IllegalArgumentException("frames must be >= 1");
        Transform[] cum = new Transform[frames];
        int[] support = new int[frames];
        if (frames == 1) {
            cum[0] = Transform.IDENTITY;
            support[0] = 1;
            return new Solution(cum, support);
        }

        List<Observation> used = new ArrayList<>();
        int bandwidth = 1;
        for (Observation ob : observations) {
            if (ob.displacement == null) continue;
            if (ob.from < 0 || ob.to < 0 || ob.from >= frames || ob.to >= frames) continue;
            used.add(ob);
            support[ob.from]++;
            support[ob.to]++;
            bandwidth = Math.max(bandwidth, ob.lag());
        }
        support[0]++;   // frame 0 is the gauge; it is constrained by definition

        int n = frames - 1;               // unknowns are u[1..frames-1]
        bandwidth = Math.min(bandwidth, n - 1 < 1 ? 1 : n - 1);
        double[][] band = new double[n][bandwidth + 1];
        double[] bx = new double[n];
        double[] by = new double[n];
        double[] bt = new double[n];

        for (Observation ob : used) {
            // Orient so that `hi` is the later frame; a reversed observation is the negation.
            int lo = Math.min(ob.from, ob.to);
            int hi = Math.max(ob.from, ob.to);
            double sign = ob.to > ob.from ? 1.0 : -1.0;
            double dx = sign * ob.displacement.dx;
            double dy = sign * ob.displacement.dy;
            double dt = sign * ob.displacement.theta;
            int i = hi - 1;               // index of u[hi]
            addBand(band, i, i, 1.0, bandwidth);
            bx[i] += dx;
            by[i] += dy;
            bt[i] += dt;
            if (lo > 0) {
                int j = lo - 1;
                addBand(band, j, j, 1.0, bandwidth);
                addBand(band, i, j, -1.0, bandwidth);
                bx[j] -= dx;
                by[j] -= dy;
                bt[j] -= dt;
            }
        }

        // Diagonal loading. A frame touched by no observation leaves a zero pivot; the ridge makes
        // the factorisation succeed and drives that frame to the gauge, which `support` flags as
        // unsupported so ChainRepair can interpolate it from its neighbours instead.
        double meanDiag = 0;
        for (int i = 0; i < n; i++) meanDiag += band[i][0];
        meanDiag = n > 0 ? meanDiag / n : 1;
        double ridge = Math.max(RIDGE * (meanDiag > 0 ? meanDiag : 1), Double.MIN_NORMAL);
        for (int i = 0; i < n; i++) band[i][0] += ridge;

        if (!choleskyBanded(band, bandwidth)) {
            // Should be unreachable after loading. Degrade to the chain rather than throwing:
            // a wrong-but-bounded answer with `support` zeroed is more useful than an exception
            // in the middle of a hundred-frame run.
            Transform[] steps = new Transform[frames - 1];
            for (Observation ob : used) {
                if (ob.lag() == 1) {
                    int t = Math.min(ob.from, ob.to);
                    steps[t] = ob.to > ob.from ? ob.displacement : ob.displacement.inverse();
                }
            }
            return chain(steps);
        }
        solveBanded(band, bandwidth, bx);
        solveBanded(band, bandwidth, by);
        solveBanded(band, bandwidth, bt);

        cum[0] = Transform.IDENTITY;
        for (int t = 1; t < frames; t++) {
            cum[t] = new Transform(bx[t - 1], by[t - 1], bt[t - 1]);
        }
        return new Solution(cum, support);
    }

    /** Rebase a solution so {@code frame} is the identity, by exact composition. */
    public static Transform[] rebase(Transform[] cumulative, int frame) {
        Transform inv = cumulative[frame].inverse();
        Transform[] out = new Transform[cumulative.length];
        for (int t = 0; t < cumulative.length; t++) {
            out[t] = inv.then(cumulative[t]);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ //
    // Banded symmetric positive-definite linear algebra. Lower band, row-major:
    //     band[i][d] holds M[i][i-d]  for d = 0..bandwidth,  band[i][0] the diagonal.
    // ------------------------------------------------------------------------------------ //

    private static void addBand(double[][] band, int i, int j, double value, int bandwidth) {
        int row = Math.max(i, j);
        int d = Math.abs(i - j);
        if (d > bandwidth) {
            throw new IllegalStateException("entry (" + i + "," + j + ") outside bandwidth "
                    + bandwidth);
        }
        band[row][d] += value;
    }

    private static double get(double[][] band, int i, int j, int bandwidth) {
        int d = i - j;
        if (d < 0 || d > bandwidth) return 0;
        return band[i][d];
    }

    /** In-place banded Cholesky. Returns false if the matrix is not positive definite. */
    static boolean choleskyBanded(double[][] band, int bandwidth) {
        int n = band.length;
        double[][] l = new double[n][bandwidth + 1];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - bandwidth);
            for (int j = lo; j < i; j++) {
                double s = get(band, i, j, bandwidth);
                for (int k = lo; k < j; k++) {
                    s -= l[i][i - k] * l[j][j - k];
                }
                l[i][i - j] = s / l[j][0];
            }
            double s = get(band, i, i, bandwidth);
            for (int k = lo; k < i; k++) {
                s -= l[i][i - k] * l[i][i - k];
            }
            if (!(s > 0)) return false;
            l[i][0] = Math.sqrt(s);
        }
        for (int i = 0; i < n; i++) System.arraycopy(l[i], 0, band[i], 0, bandwidth + 1);
        return true;
    }

    /** Forward then back substitution against a factor from {@link #choleskyBanded}, in place. */
    static void solveBanded(double[][] l, int bandwidth, double[] b) {
        int n = b.length;
        for (int i = 0; i < n; i++) {
            double s = b[i];
            for (int k = Math.max(0, i - bandwidth); k < i; k++) {
                s -= l[i][i - k] * b[k];
            }
            b[i] = s / l[i][0];
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int k = i + 1; k < Math.min(n, i + bandwidth + 1); k++) {
                s -= l[k][k - i] * b[k];
            }
            b[i] = s / l[i][0];
        }
    }
}
