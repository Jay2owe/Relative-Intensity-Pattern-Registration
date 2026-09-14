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
 * How a log-ratio residual is penalised, and the robust scale it is measured against.
 *
 * <p><b>Why the choice of norm is the single most consequential setting in this plugin.</b>
 * The residual field of a misregistered pair is <em>dense and edge-aligned</em>: displace a
 * frame by {@code d} and every edge in it contributes about {@code grad(I) . d}, everywhere at
 * once. The residual field of genuine change is <em>sparse</em>: a few objects moved, appeared
 * or brightened. Those two are separable by norm alone. A squared norm weights each residual
 * by its own magnitude, so the sparse-but-large genuine changes outvote the dense-but-small
 * misregistration signal and the transform is dragged toward aligning whatever changed most.
 * A norm that saturates lets them be outliers, and the dense term wins because there is more
 * of it. That is the same principle as sparse-plus-low-rank batch alignment (Peng et al.,
 * <i>IEEE TPAMI</i> 34:2233, 2012).
 *
 * <p>It matters most on data that has already been thresholded or background-subtracted, where
 * a pixel switching between "zero" and "signal" produces a residual of
 * {@code +-log2(range/epsilon)} â€” order 10 in log2 units against a real gradient term of order
 * 0.1. Under {@link #LEAST_SQUARES} those switches are the cost function; under
 * {@link #TUKEY} they contribute nothing at all.
 *
 * <p>The scale is estimated per iteration from the data as a median absolute deviation, so the
 * threshold adapts to the noise of the frame pair instead of needing a value in log2 units
 * that no user can guess.
 */
public enum RobustNorm {

    /**
     * Plain squared residual. The classical ratio image uniformity criterion â€” minimising the
     * variance of the log-ratio field is exactly this with the free gain profiled out.
     * Correct when everything that differs between the two frames <em>is</em> misregistration.
     * Offered because it is the published criterion and the right baseline to compare against,
     * not because it is the right default.
     */
    LEAST_SQUARES {
        @Override public double tuningConstant() { return Double.POSITIVE_INFINITY; }
        @Override public double weight(double r, double k) { return 1.0; }
        @Override public double rho(double r, double k) { return r * r; }
    },

    /**
     * Squared inside {@code k}, linear outside. Bounded influence: an outlier still pulls, but
     * no harder than a residual at the threshold. The safe middle, and the default.
     */
    HUBER {
        @Override public double tuningConstant() { return 1.345; }
        @Override public double weight(double r, double k) {
            double a = Math.abs(r);
            return a <= k ? 1.0 : k / a;
        }
        @Override public double rho(double r, double k) {
            double a = Math.abs(r);
            return a <= k ? r * r : k * (2 * a - k);
        }
    },

    /**
     * Redescending: zero weight beyond {@code k}, so a residual judged to be genuine change is
     * discarded outright rather than merely discounted. The strongest option when the frames
     * contain real structural change, and the one to reach for on thresholded data. The cost
     * of redescending is a cost surface with more local minima, which is why the coarse-to-fine
     * search in {@link PairAligner} is not optional.
     */
    TUKEY {
        @Override public double tuningConstant() { return 4.685; }
        @Override public double weight(double r, double k) {
            double a = Math.abs(r);
            if (a >= k) return 0.0;
            double u = 1.0 - (r / k) * (r / k);
            return u * u;
        }
        @Override public double rho(double r, double k) {
            double a = Math.abs(r);
            double k2 = k * k;
            if (a >= k) return k2 / 6.0;
            double u = 1.0 - (r / k) * (r / k);
            return (k2 / 6.0) * (1.0 - u * u * u);
        }
    };

    /** Multiplier on the robust scale that gives this norm's cut-off. */
    public abstract double tuningConstant();

    /** IRLS weight for residual {@code r} at threshold {@code k}. */
    public abstract double weight(double r, double k);

    /** The penalty itself. Used only for the line search, so its constant offset is free. */
    public abstract double rho(double r, double k);

    /** The cut-off in residual units, given a robust scale. */
    public double threshold(double scale) {
        double k = tuningConstant() * scale;
        return k > 0 ? k : Double.MIN_NORMAL;
    }

    /**
     * Median absolute deviation about zero, rescaled to estimate a Gaussian sigma.
     *
     * <p>About zero rather than about the median because the caller has already removed the
     * weighted mean as the gain term: the residual field is centred by construction, and
     * re-centring on the median here would silently absorb part of a genuine offset.
     *
     * <p>Floored, because a scale of zero makes every threshold zero and every weight either
     * one or undefined. Identical frames legitimately produce an all-zero residual.
     *
     * @param r     residuals; <b>reordered in place</b> by the selection
     * @param n     how many entries of {@code r} are valid
     * @param floor smallest scale to report, in the same units as {@code r}
     */
    public static double scale(double[] r, int n, double floor) {
        if (n <= 0) return floor;
        for (int i = 0; i < n; i++) r[i] = Math.abs(r[i]);
        double mad = select(r, n, n / 2);
        if (!(mad > 0)) {
            // Degenerate, and on real data this is common rather than exotic. Background-subtracted,
            // thresholded or clipped stacks are mostly exactly zero — measured at 82-93% of pixels on
            // the microglia recordings this was developed against — so most residuals are exactly
            // zero and the median absolute deviation is zero with them.
            //
            // That is NOT a statement that the noise is zero. A MAD assumes a unimodal continuous
            // distribution; a point mass of 0.9 at zero is not one. Taken at face value it floors the
            // scale, which floors the threshold, which gives every informative pixel zero weight
            // under a redescending norm — so the solver sees only background and the fit collapses to
            // no shift at all. Measured before this guard: Tukey on 95_A1 moved the residual 0.853 to
            // 0.842 and marked 18 of 101 frames unusable, where Huber managed 0.776.
            //
            // Escalating to a high quantile makes the threshold reflect the part of the field that
            // carries information, which is what the scale was always meant to describe.
            mad = select(r, n, (int) (HIGH_QUANTILE * (n - 1)));
        }
        double s = 1.4826 * mad;
        return s > floor ? s : floor;
    }

    /** Quantile of |r| used when the median absolute deviation degenerates to zero. */
    private static final double HIGH_QUANTILE = 0.9;

    /**
     * {@code k}-th smallest of {@code a[0..n)}, Hoare selection, partially sorting in place.
     * Linear on average â€” a full sort of every residual on every iteration of every level of
     * every frame pair is the one place where an O(n log n) would actually show up.
     */
    static double select(double[] a, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            double pivot = a[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    double t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return a[k];
        }
        return a[lo];
    }
}
