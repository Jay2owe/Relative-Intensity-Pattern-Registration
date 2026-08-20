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
 * Applies cumulative transforms to pixel data, and works out which part of the result is real.
 *
 * <p><b>Sign convention.</b> {@code cumulative[t]} is the motion of the content from the reference
 * frame to frame {@code t}. Holding the field still therefore means sampling frame {@code t} at
 * {@code cumulative[t](x)} to produce output pixel {@code x} — the inverse warp. A feature that drifted
 * right returns to where it started.
 *
 * <p><b>Why {@link Interpolation#NONE} is the default.</b> A fractional shift has to interpolate,
 * interpolation smooths, and the amount of smoothing varies frame to frame with the fractional part.
 * For per-pixel temporal work — a pixel's intensity trajectory, correlation between trajectories, frame
 * differencing, the log-ratio map this plugin also produces — that injects a frame-varying blur into
 * precisely the quantity being measured, and it looks like signal. Rounding to whole pixels caps the
 * achievable residual at {@code hypot(0.5, 0.5) = 0.707} px and is the right trade for measurement.
 * Interpolate for display, or when the statistic is per-object rather than per-pixel.
 *
 * <p>Integer translation additionally takes a block-copy path that does not resample at all, so the
 * output is bit-identical to the input up to the shift. That is not merely faster; it means a registered
 * 16-bit stack still contains exactly the original counts.
 */
public final class Warper {

    /** Cubic convolution parameter. -0.5 is Catmull–Rom: interpolating, C1, no free ringing. */
    private static final double CUBIC_A = -0.5;

    private Warper() {
    }

    public enum Interpolation {
        /** Whole-pixel shift, no resampling. Bit-exact. The default. */
        NONE,
        /** Bilinear. */
        BILINEAR,
        /** Cubic convolution, Catmull–Rom. Sharper than bilinear; can overshoot at hard edges. */
        BICUBIC
    }

    /** Rows and columns that are real in every registered frame. */
    public static final class Margin {
        public final int top;
        public final int bottom;
        public final int left;
        public final int right;

        Margin(int top, int bottom, int left, int right) {
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
        }

        public boolean isEmpty() {
            return top == 0 && bottom == 0 && left == 0 && right == 0;
        }

        public int croppedWidth(int width) {
            return Math.max(0, width - left - right);
        }

        public int croppedHeight(int height) {
            return Math.max(0, height - top - bottom);
        }

        @Override
        public String toString() {
            return "(" + top + ", " + bottom + ", " + left + ", " + right + ")";
        }
    }

    /**
     * The region real in every frame after warping.
     *
     * <p>Registration fills the vacated edge, so any statistic computed there is measuring the fill
     * rather than the sample. A recording that drifts 6 px over its length leaves a genuine 6 px strip,
     * not a formality, and a probe that samples near the border must clip to this.
     *
     * <p>Exact for pure translation. When a rotation is present the valid region is not a rectangle, so
     * each margin is padded by {@code |theta|} times the half-diagonal — conservative, and it over-crops
     * rather than admitting fill.
     */
    public static Margin validMargin(Transform[] cumulative, int width, int height,
                                     Interpolation interpolation) {
        int top = 0;
        int bottom = 0;
        int left = 0;
        int right = 0;
        // Interpolation reads one pixel beyond the sample for bilinear and two for bicubic, so the
        // trustworthy region shrinks by that much on every side.
        int reach = interpolation == Interpolation.NONE ? 0
                : (interpolation == Interpolation.BILINEAR ? 1 : 2);
        double halfDiag = 0.5 * Math.hypot(width, height);
        for (Transform t : cumulative) {
            if (t == null) continue;
            int iy = (int) Math.round(t.dy);
            int ix = (int) Math.round(t.dx);
            int pad = reach + (int) Math.ceil(Math.abs(t.theta) * halfDiag);
            top = Math.max(top, Math.max(0, -iy) + pad);
            bottom = Math.max(bottom, Math.max(0, iy) + pad);
            left = Math.max(left, Math.max(0, -ix) + pad);
            right = Math.max(right, Math.max(0, ix) + pad);
        }
        // Never crop away the whole frame, however wild the transforms.
        top = Math.min(top, Math.max(0, height / 2 - 1));
        bottom = Math.min(bottom, Math.max(0, height / 2 - 1));
        left = Math.min(left, Math.max(0, width / 2 - 1));
        right = Math.min(right, Math.max(0, width / 2 - 1));
        return new Margin(top, bottom, left, right);
    }

    /** True when every transform is a whole-pixel translation, so the block-copy path applies. */
    public static boolean isWholePixelTranslation(Transform[] cumulative, double tolerance) {
        for (Transform t : cumulative) {
            if (t == null) continue;
            if (t.theta != 0) return false;
            if (Math.abs(t.dx - Math.round(t.dx)) > tolerance) return false;
            if (Math.abs(t.dy - Math.round(t.dy)) > tolerance) return false;
        }
        return true;
    }

    /**
     * Warp one plane. {@code dst} is filled completely; positions with no source are set to
     * {@code fill}.
     *
     * @param t transform to sample through — pass {@code cumulative[frame]} directly, not its inverse
     */
    public static void warp(float[] src, float[] dst, int width, int height, Transform t,
                            Interpolation interpolation, float fill) {
        if (src.length != width * height || dst.length != width * height) {
            throw new IllegalArgumentException("plane arrays must be " + (width * height) + " long");
        }
        if (t == null) {
            System.arraycopy(src, 0, dst, 0, src.length);
            return;
        }
        // NONE means never interpolate, so a fractional translation is ROUNDED rather than sampled.
        // Falling through to the sampler here was a real bug: the documented default silently
        // bilinear-interpolated, which is exactly the frame-varying blur the default exists to avoid,
        // and it showed up as integer and bilinear runs producing identical numbers to four figures.
        if (interpolation == Interpolation.NONE && t.isPureTranslation()) {
            blockCopy(src, dst, width, height, (int) Math.round(t.dx), (int) Math.round(t.dy), fill);
            return;
        }
        if (t.isPureTranslation()
                && Math.abs(t.dx - Math.round(t.dx)) < 1e-9
                && Math.abs(t.dy - Math.round(t.dy)) < 1e-9) {
            blockCopy(src, dst, width, height, (int) Math.round(t.dx), (int) Math.round(t.dy), fill);
            return;
        }
        double[] out = new double[2];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                t.apply(x, y, cx, cy, out);
                switch (interpolation) {
                    case NONE:
                        // A rotation cannot be reduced to a block copy, so honour "no interpolation"
                        // with nearest-neighbour rather than quietly upgrading to bilinear.
                        dst[row + x] = nearest(src, width, height, out[0], out[1], fill);
                        break;
                    case BICUBIC:
                        dst[row + x] = bicubic(src, width, height, out[0], out[1], fill);
                        break;
                    case BILINEAR:
                    default:
                        dst[row + x] = bilinear(src, width, height, out[0], out[1], fill);
                        break;
                }
            }
        }
    }

    private static float nearest(float[] a, int w, int h, double x, double y, float fill) {
        int ix = (int) Math.round(x);
        int iy = (int) Math.round(y);
        if (ix < 0 || iy < 0 || ix >= w || iy >= h) return fill;
        return a[iy * w + ix];
    }

    /**
     * Whole-pixel shift with no resampling. Output pixel {@code (x, y)} takes source
     * {@code (x + ix, y + iy)}; everything outside becomes {@code fill}.
     */
    static void blockCopy(float[] src, float[] dst, int width, int height, int ix, int iy,
                          float fill) {
        java.util.Arrays.fill(dst, fill);
        if (Math.abs(ix) >= width || Math.abs(iy) >= height) return;
        int y0 = Math.max(0, -iy);
        int y1 = Math.min(height, height - iy);
        int x0 = Math.max(0, -ix);
        int x1 = Math.min(width, width - ix);
        int len = x1 - x0;
        if (len <= 0) return;
        for (int y = y0; y < y1; y++) {
            System.arraycopy(src, (y + iy) * width + x0 + ix, dst, y * width + x0, len);
        }
    }

    private static float bilinear(float[] a, int w, int h, double x, double y, float fill) {
        if (!(x >= 0 && y >= 0 && x <= w - 1 && y <= h - 1)) return fill;
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = x0 + 1 < w ? x0 + 1 : x0;
        int y1 = y0 + 1 < h ? y0 + 1 : y0;
        double fx = x - x0;
        double fy = y - y0;
        int r0 = y0 * w;
        int r1 = y1 * w;
        double top = a[r0 + x0] + fx * (a[r0 + x1] - a[r0 + x0]);
        double bot = a[r1 + x0] + fx * (a[r1 + x1] - a[r1 + x0]);
        return (float) (top + fy * (bot - top));
    }

    private static float bicubic(float[] a, int w, int h, double x, double y, float fill) {
        if (!(x >= 0 && y >= 0 && x <= w - 1 && y <= h - 1)) return fill;
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = x - ix;
        double fy = y - iy;
        double sum = 0;
        for (int m = -1; m <= 2; m++) {
            double wy = cubic(m - fy);
            if (wy == 0) continue;
            int yy = clamp(iy + m, h);
            double row = 0;
            for (int n = -1; n <= 2; n++) {
                row += cubic(n - fx) * a[yy * w + clamp(ix + n, w)];
            }
            sum += wy * row;
        }
        return (float) sum;
    }

    private static double cubic(double t) {
        double x = Math.abs(t);
        if (x < 1) {
            return ((CUBIC_A + 2) * x - (CUBIC_A + 3)) * x * x + 1;
        }
        if (x < 2) {
            return ((CUBIC_A * x - 5 * CUBIC_A) * x + 8 * CUBIC_A) * x - 4 * CUBIC_A;
        }
        return 0;
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    /** Crop a plane to a margin. */
    public static float[] crop(float[] src, int width, int height, Margin m) {
        int nw = m.croppedWidth(width);
        int nh = m.croppedHeight(height);
        float[] out = new float[nw * nh];
        for (int y = 0; y < nh; y++) {
            System.arraycopy(src, (y + m.top) * width + m.left, out, y * nw, nw);
        }
        return out;
    }
}
