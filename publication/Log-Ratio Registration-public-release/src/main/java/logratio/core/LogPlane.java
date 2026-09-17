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
 * One frame held as {@code log2(I + epsilon)}, with its gradients and a validity mask, at one
 * pyramid level.
 *
 * <p><b>Why log, and why it is the whole point.</b> Registration by least squares on raw intensities
 * assumes the two frames differ only by geometry. They almost never do: laser power drifts, the lamp
 * ages, the detector gain is retuned between sessions, the sample bleaches. Under a global gain
 * {@code g} the log image becomes {@code log I + log g} — a constant offset — so a cost built on the
 * <em>difference</em> of log images with a free additive constant is exactly invariant to that gain,
 * with no extra parameter to fit and nothing to converge. The difference of two log images is the log
 * of their ratio, which is why this is the log-ratio criterion and not a least-squares one.
 *
 * <p>Minimising the spread of that log-ratio field is, to first order, Woods' ratio image uniformity
 * criterion (Woods, Cherry &amp; Mazziotta 1992, <i>J Comput Assist Tomogr</i> 16:620), since the
 * coefficient of variation of a ratio equals the standard deviation of its log for small variations.
 * The log domain is the better-conditioned way to compute it: no division, and the criterion is
 * symmetric under swapping the two frames.
 *
 * <p><b>Why the epsilon is not cosmetic.</b> A bare log diverges at zero, and zero is common —
 * background-subtracted, clipped or masked data can be mostly zero. The offset makes an unchanged
 * zero pixel read exactly zero in the ratio rather than undefined, and bounds the extremes at
 * {@code +-log2(range/epsilon)}. It costs a small compression of genuinely dim signal and buys a cost
 * function that is finite everywhere.
 *
 * <p><b>Why the pyramid is built in the log domain, not before it.</b> Blurring log values averages
 * logs, which is the log of a geometric mean; blurring intensities first and taking the log afterwards
 * is the log of an arithmetic mean. Only the former commutes with a global gain —
 * {@code mean(log(gI)) = mean(log I) + log g} exactly, at every level. Since gain invariance is the
 * reason this criterion exists, it is worth keeping exact all the way down the pyramid rather than
 * approximately true at full resolution only.
 *
 * <p><b>Why there is a validity mask and not just NaN in the values.</b> A NaN value would propagate
 * through the blur and the gradient stencil and poison an entire frame's cost from one bad pixel. That
 * is not hypothetical: a registration margin is conventionally written as NaN rather than zero
 * precisely so that a zero always means "measured, unchanged", and a 32-bit image that has been masked
 * in Fiji carries NaN wherever the mask was. So invalidity is tracked separately, the blur renormalises
 * over the valid weight it actually saw, gradients fall back to one-sided differences at a validity
 * border, and sampling refuses any position whose bilinear footprint touches an invalid pixel.
 */
public final class LogPlane {

    /** No upper intensity limit — the default for {@link #of}. */
    public static final double NO_SATURATION = Double.POSITIVE_INFINITY;
    /** No lower intensity limit. Negative so that a legitimate zero pixel stays valid. */
    public static final double NO_FLOOR = Double.NEGATIVE_INFINITY;

    /** A coarse pixel needs at least this share of the blur weight to be valid. */
    private static final float VALID_WEIGHT_SHARE = 0.5f;

    /** {@code log2(I + epsilon)}, row-major. Undefined where {@link #valid} is false. */
    public final float[] v;
    /** d/dx of {@link #v}. One-sided at image and validity borders. Zero where invalid. */
    public final float[] gx;
    /** d/dy of {@link #v}. */
    public final float[] gy;
    /** Reconstructed {@code intensity + epsilon}, cached for raw-noise edge support. */
    private volatile float[] raw;
    /** Raw-count gradient magnitude corresponding to {@link #gx} and {@link #gy}. */
    private volatile float[] rawGradient;
    /** False where the pixel carries no usable measurement. */
    public final boolean[] valid;
    public final int width;
    public final int height;
    /** How many pixels are valid. Cached because every caller wants it. */
    public final int validCount;
    private volatile double rawNoiseSigma = Double.NaN;
    /**
     * {@link AreaCorrelation}'s prepared view of this plane, or null until it asks for one.
     *
     * <p><b>Why it lives on the plane rather than in a cache of its own.</b> The preparation —
     * undoing the log, centring on the plane mean, and solving the B-spline coefficients — is a pure
     * function of this plane, and {@link PyramidCache} hands the same plane to every pair the frame
     * appears in. Under multi-lag that is about nine pairs per frame, so rebuilding it per pair is
     * eight ninths waste. Holding it here rather than in a separate map means its lifetime is exactly
     * the plane's: it is evicted when the pyramid is evicted, cleared when the cache is cleared, and
     * cannot outlive the thing it describes. The cost of holding it is declared to the cache by
     * {@link PairEstimator#cachedBytesPerPixel()} so the capacity calculation sees it.
     *
     * <p>Racing workers may both build one. The result is a pure function of the plane, so the two
     * are identical and the last write wins; {@link AreaCorrelation.Window}'s fields are final, so
     * the volatile write publishes it safely. Locking would serialise the workers to save an
     * occasional duplicate build, which is the wrong trade.
     */
    volatile AreaCorrelation.Window areaWindow;
    private LogPlane(float[] v, boolean[] valid, int width, int height) {
        this.v = v;
        this.valid = valid;
        this.width = width;
        this.height = height;
        this.gx = new float[v.length];
        this.gy = new float[v.length];
        int n = 0;
        for (boolean b : valid) if (b) n++;
        this.validCount = n;
        gradients();
    }

    /** Level 0 with no floor and no saturation limit. */
    public static LogPlane of(float[] intensity, int width, int height, double epsilon) {
        return of(intensity, width, height, epsilon, NO_FLOOR, NO_SATURATION);
    }

    /**
     * Take the log of an intensity plane and build level 0.
     *
     * @param intensity row-major intensities, not modified. NaN marks a pixel invalid
     * @param epsilon   offset added before the log; must be &gt; 0
     * @param floor     intensities strictly below this are invalid. This is the pixel-support
     *                  "intensity floor" option, and it belongs here rather than in the solver: a
     *                  pixel excluded for being background should also be excluded from the gain
     *                  estimate and from the reported residual, not only from the Jacobian
     * @param satMax    intensities at or above this are invalid — a saturated pixel has lost the
     *                  gradient information the solver needs and cannot be brightened, so it biases
     *                  the gain estimate in one direction only
     */
    public static LogPlane of(float[] intensity, int width, int height, double epsilon,
                              double floor, double satMax) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("epsilon must be > 0, was " + epsilon);
        }
        if (intensity.length != width * height) {
            throw new IllegalArgumentException("expected " + (width * height)
                    + " pixels for " + width + "x" + height + ", got " + intensity.length);
        }
        float[] out = new float[intensity.length];
        boolean[] ok = new boolean[intensity.length];
        double invLn2 = 1.0 / Math.log(2.0);
        for (int i = 0; i < intensity.length; i++) {
            float raw = intensity[i];
            if (Float.isNaN(raw) || raw < floor || raw >= satMax) continue;
            double x = raw + epsilon;
            if (!(x > 0)) continue;      // a negative pixel below -epsilon has no log
            out[i] = (float) (Math.log(x) * invLn2);
            ok[i] = true;
        }
        return new LogPlane(out, ok, width, height);
    }

    /**
     * Build a pyramid, coarsest last. {@code levels == 1} returns just this plane.
     *
     * <p>Each step is a 5-tap binomial blur (Burt–Adelson {@code [1 4 6 4 1]/16}, sigma about 1.0)
     * then a 2x decimation. The blur is not optional: decimating without it aliases the high
     * frequencies that carry the alignment, and the coarse search then locks onto an alias rather than
     * the structure.
     */
    public LogPlane[] pyramid(int levels) {
        if (levels < 1) throw new IllegalArgumentException("levels must be >= 1");
        LogPlane[] out = new LogPlane[levels];
        out[0] = this;
        for (int l = 1; l < levels; l++) {
            out[l] = out[l - 1].halve();
        }
        return out;
    }

    /**
     * How many pyramid levels a frame of this size supports before the coarsest is smaller than
     * {@code minSize}, capped at {@code maxLevels}.
     */
    public static int autoLevels(int width, int height, int minSize, int maxLevels) {
        int levels = 1;
        int w = width;
        int h = height;
        while (levels < maxLevels && w / 2 >= minSize && h / 2 >= minSize) {
            w /= 2;
            h /= 2;
            levels++;
        }
        return levels;
    }

    /**
     * Levels needed to bring a shift of {@code maxShift} full-resolution pixels within
     * {@code radiusBudget} coarse pixels of the origin.
     *
     * <p>The coarse sweep is quadratic in its radius, so a large {@code maxShift} at too few levels
     * asks for a search that dominates the whole run — a 500 px bound at one level is a million
     * full-resolution evaluations. Callers use this to raise the level count rather than let that
     * happen silently.
     */
    public static int levelsForShift(double maxShift, int radiusBudget) {
        int levels = 1;
        double r = Math.abs(maxShift);
        while (r > radiusBudget && levels < 16) {
            r /= 2;
            levels++;
        }
        return levels;
    }

    /**
     * The same pyramid, but with every coarse level formed in the intensity domain.
     *
     * <p>{@link #pyramid(int)} blurs log values, so a coarse pixel is the log of a weighted
     * <em>geometric</em> mean of its neighbours. That is deliberate and is exactly right for the
     * log-ratio criterion, because it keeps gain invariance exact at every level. It is not what a
     * classical area method does: TurboReg and its relatives blur intensities, so their coarse pixel
     * is an <em>arithmetic</em> mean.
     *
     * <p>The two differ wherever a neighbourhood is not flat, and they differ most where it is most
     * uneven — a geometric mean is pulled towards the darkest pixel in the window, so a coarse level
     * built in the log domain systematically under-weights bright structure. That is the structure a
     * correlation peak is made of.
     *
     * <p>Levels built this way must not be handed to {@link PairAligner}: its invariance argument
     * assumes log-domain decimation, and this would silently weaken it. Only estimators that declare
     * {@link PairEstimator#prefersLinearPyramid()} receive these.
     *
     * <p>The epsilon needs no special handling. Level 0 holds {@code log2(I + epsilon)}, so
     * exponentiating gives {@code I + epsilon}, and an arithmetic mean of that is
     * {@code mean(I) + epsilon} — the offset passes through the average unchanged.
     */
    public LogPlane[] linearPyramid(int levels) {
        if (levels < 1) throw new IllegalArgumentException("levels must be >= 1");
        LogPlane[] out = new LogPlane[levels];
        out[0] = this;
        for (int l = 1; l < levels; l++) {
            out[l] = out[l - 1].linearHalve();
        }
        return out;
    }

    private LogPlane linearHalve() {
        float[] intensity = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            intensity[i] = valid[i] ? (float) Math.exp(v[i] * LN2) : 0f;
        }
        Blurred b = blur5(intensity, valid, width, height);
        int nw = width / 2;
        int nh = height / 2;
        if (nw < 1 || nh < 1) {
            throw new IllegalStateException("cannot halve a " + width + "x" + height + " plane");
        }
        float[] small = new float[nw * nh];
        boolean[] ok = new boolean[nw * nh];
        for (int y = 0; y < nh; y++) {
            int src = (2 * y) * width;
            int dst = y * nw;
            for (int x = 0; x < nw; x++) {
                int from = src + 2 * x;
                boolean usable = b.valid[from] && b.v[from] > 0;
                ok[dst + x] = usable;
                small[dst + x] = usable ? (float) (Math.log(b.v[from]) / LN2) : 0f;
            }
        }
        return new LogPlane(small, ok, nw, nh);
    }

    private static final double LN2 = Math.log(2);

    private LogPlane halve() {
        Blurred b = blur5(v, valid, width, height);
        int nw = width / 2;
        int nh = height / 2;
        if (nw < 1 || nh < 1) {
            throw new IllegalStateException("cannot halve a " + width + "x" + height + " plane");
        }
        float[] small = new float[nw * nh];
        boolean[] ok = new boolean[nw * nh];
        for (int y = 0; y < nh; y++) {
            int src = (2 * y) * width;
            int dst = y * nw;
            for (int x = 0; x < nw; x++) {
                small[dst + x] = b.v[src + 2 * x];
                ok[dst + x] = b.valid[src + 2 * x];
            }
        }
        return new LogPlane(small, ok, nw, nh);
    }

    private static final class Blurred {
        final float[] v;
        final boolean[] valid;

        Blurred(float[] v, boolean[] valid) {
            this.v = v;
            this.valid = valid;
        }
    }

    /**
     * Separable {@code [1 4 6 4 1]/16} with border replication, renormalised over valid weight.
     *
     * <p>Renormalising rather than treating invalid pixels as zero matters: a zero in the log domain
     * is an intensity of {@code 1 - epsilon}, not "no contribution", so letting invalid pixels into
     * the average would drag every pixel near a mask edge toward that value and manufacture a
     * gradient along the whole mask boundary — exactly the kind of frame-invariant edge that anchors
     * a registration at zero shift.
     */
    private static Blurred blur5(float[] a, boolean[] ok, int w, int h) {
        float[] tv = new float[a.length];
        float[] tw = new float[a.length];
        final float[] k = {1f, 4f, 6f, 4f, 1f};
        final float full = 16f;

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float sv = 0;
                float sw = 0;
                for (int d = -2; d <= 2; d++) {
                    int xi = row + clamp(x + d, w);
                    if (!ok[xi]) continue;
                    float kw = k[d + 2];
                    sv += kw * a[xi];
                    sw += kw;
                }
                tv[row + x] = sw > 0 ? sv / sw : 0f;
                tw[row + x] = sw / full;
            }
        }

        float[] ov = new float[a.length];
        boolean[] oo = new boolean[a.length];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float sv = 0;
                float sw = 0;
                for (int d = -2; d <= 2; d++) {
                    int yi = clamp(y + d, h) * w + x;
                    float share = tw[yi];
                    if (share <= 0) continue;
                    float kw = k[d + 2] * share;
                    sv += kw * tv[yi];
                    sw += kw;
                }
                int i = y * w + x;
                ov[i] = sw > 0 ? sv / sw : 0f;
                oo[i] = sw / full >= VALID_WEIGHT_SHARE;
            }
        }
        return new Blurred(ov, oo);
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    /**
     * Central differences, degrading to one-sided at an image border or a validity border.
     *
     * <p>One-sided rather than eroding the valid region: eroding costs a pixel of usable data per
     * level, which compounds down the pyramid, and a one-sided difference is the same estimator the
     * image border already uses.
     */
    private void gradients() {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                if (!valid[i]) continue;
                gx[i] = difference(i, row + clamp(x - 1, width), row + clamp(x + 1, width));
                gy[i] = difference(i, clamp(y - 1, height) * width + x,
                        clamp(y + 1, height) * width + x);
            }
        }
    }

    private float difference(int centre, int minus, int plus) {
        boolean okMinus = valid[minus] && minus != centre;
        boolean okPlus = valid[plus] && plus != centre;
        if (okMinus && okPlus) return 0.5f * (v[plus] - v[minus]);
        if (okPlus) return v[plus] - v[centre];
        if (okMinus) return v[centre] - v[minus];
        return 0f;
    }

    /** Gradient magnitude at a pixel, {@code |gx| + |gy|}. Cheap and adequate for ranking. */
    public float gradMagnitude(int index) {
        return Math.abs(gx[index]) + Math.abs(gy[index]);
    }

    /**
     * Robust raw-intensity noise estimate from the four-neighbour high-pass residual.
     * The stored plane is logarithmic, but {@code 2^v = intensity + epsilon}; epsilon cancels in the
     * difference. The value is computed once per immutable pyramid plane and then cached.
     */
    public double rawNoiseSigma() {
        double cached = rawNoiseSigma;
        if (Double.isFinite(cached)) return cached;
        ensureRawSupport();
        float[] rawValues = raw;
        double[] residual = new double[Math.max(1, (width - 2) * (height - 2))];
        int n = 0;
        for (int y = 1; y + 1 < height; y++) {
            int row = y * width;
            for (int x = 1; x + 1 < width; x++) {
                int p = row + x;
                if (!valid[p] || !valid[p - 1] || !valid[p + 1]
                        || !valid[p - width] || !valid[p + width]) continue;
                double centre = rawValues[p];
                double neighbours = 0.25 * (rawValues[p - 1] + rawValues[p + 1]
                        + rawValues[p - width] + rawValues[p + width]);
                residual[n++] = centre - neighbours;
            }
        }
        if (n == 0) cached = 1e-6;
        else {
            double centre = RobustNorm.select(residual, n, n / 2);
            for (int i = 0; i < n; i++) residual[i] -= centre;
            cached = RobustNorm.scale(residual, n, 1e-6) / Math.sqrt(1.25);
        }
        rawNoiseSigma = cached;
        return cached;
    }

    /** Raw-count edge strength at a source pixel. */
    public float rawGradient(int index) {
        ensureRawSupport();
        return rawGradient[index];
    }

    /** Build raw-intensity caches only for the opt-in mutual-noise selector. */
    private void ensureRawSupport() {
        if (raw != null) return;
        synchronized (this) {
            if (raw != null) return;
            float[] rawValues = new float[v.length];
            float[] gradientValues = new float[v.length];
            double ln2 = Math.log(2.0);
            for (int i = 0; i < v.length; i++) {
                if (!valid[i]) continue;
                rawValues[i] = (float) Math.pow(2.0, v[i]);
                gradientValues[i] = (float) ((Math.abs(gx[i]) + Math.abs(gy[i]))
                        * rawValues[i] * ln2);
            }
            rawGradient = gradientValues;
            raw = rawValues;
        }
    }

    /**
     * Bilinear sample of {@link #v}. Returns {@link Float#NaN} when the position is out of bounds or
     * any of the four contributing pixels is invalid.
     *
     * <p>Returning NaN rather than a clamped edge value is deliberate. A criterion that can lower its
     * own cost by sliding the image off the edge will do exactly that, and a clamped border gives it
     * a free, perfectly uniform region to slide into.
     */
    public float sample(double x, double y) {
        return sampleOf(v, x, y);
    }

    /** Bilinear sample of the x-gradient. NaN under the same conditions as {@link #sample}. */
    public float sampleGx(double x, double y) {
        return sampleOf(gx, x, y);
    }

    /** Bilinear sample of the y-gradient. NaN under the same conditions as {@link #sample}. */
    public float sampleGy(double x, double y) {
        return sampleOf(gy, x, y);
    }

    private float sampleOf(float[] a, double x, double y) {
        if (!(x >= 0 && y >= 0 && x <= width - 1 && y <= height - 1)) return Float.NaN;
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = x0 + 1 < width ? x0 + 1 : x0;
        int y1 = y0 + 1 < height ? y0 + 1 : y0;
        int r0 = y0 * width;
        int r1 = y1 * width;
        if (!(valid[r0 + x0] && valid[r0 + x1] && valid[r1 + x0] && valid[r1 + x1])) {
            return Float.NaN;
        }
        double fx = x - x0;
        double fy = y - y0;
        double top = a[r0 + x0] + fx * (a[r0 + x1] - a[r0 + x0]);
        double bot = a[r1 + x0] + fx * (a[r1 + x1] - a[r1 + x0]);
        return (float) (top + fy * (bot - top));
    }

    private static float interpolate(float[] a, int r0, int r1, int x0, int x1,
                                     double fx, double fy) {
        double top = a[r0 + x0] + fx * (a[r0 + x1] - a[r0 + x0]);
        double bot = a[r1 + x0] + fx * (a[r1 + x1] - a[r1 + x0]);
        return (float) (top + fy * (bot - top));
    }
}
