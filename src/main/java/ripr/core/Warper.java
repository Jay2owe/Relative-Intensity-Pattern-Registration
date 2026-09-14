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
        BICUBIC,
        /**
         * Band-limited resampling using Fourier shifts. Rotation is applied as three Fourier shears.
         * Sharper than the local kernels, but can ring near hard edges.
         */
        FOURIER
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
        // Bilinear and Fourier need one extra pixel to exclude a fractional coordinate outside the
        // source. Bicubic has a two-pixel local kernel. Fourier's sinc kernel is global, but the plane
        // is zero-padded before transforming; there is no finite kernel radius to add here.
        int reach = interpolation == Interpolation.NONE ? 0
                : (interpolation == Interpolation.BICUBIC ? 2 : 1);
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
        if (interpolation == Interpolation.FOURIER) {
            if (t.isPureTranslation()) {
                fourierTranslate(src, dst, width, height, t.dx, t.dy, fill);
            } else {
                fourierRigid(src, dst, width, height, t, fill);
            }
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
                    case FOURIER:
                        // Handled once per plane above; keeping the case makes enum additions explicit.
                        throw new AssertionError("Fourier translation did not take its plane path");
                    case BILINEAR:
                    default:
                        dst[row + x] = bilinear(src, width, height, out[0], out[1], fill);
                        break;
                }
            }
        }
    }

    /**
     * Translate a plane by multiplying its Fourier spectrum by a phase ramp.
     *
     * <p>The source is centred in a plane at least twice as wide and high before transforming. That
     * turns the FFT's periodic shift into the required filled-border shift for every displacement that
     * still overlaps the image. Coordinates whose inverse-warp sample lies outside the source are set
     * back to {@code fill} explicitly. Finite fill values are used as the padding baseline so a flat
     * image remains flat rather than acquiring a step at its border.
     */
    private static void fourierTranslate(float[] src, float[] dst, int width, int height,
                                         double dx, double dy, float fill) {
        if (Math.abs(dx) > width - 1 || Math.abs(dy) > height - 1) {
            java.util.Arrays.fill(dst, fill);
            return;
        }
        int paddedWidth = PhaseCorrelation.nextPowerOfTwo(2 * width);
        int paddedHeight = PhaseCorrelation.nextPowerOfTwo(2 * height);
        int xOffset = (paddedWidth - width) / 2;
        int yOffset = (paddedHeight - height) / 2;
        double baseline = Float.isFinite(fill) ? fill : 0.0;
        double[] real = new double[paddedWidth * paddedHeight];
        double[] imaginary = new double[real.length];
        for (int y = 0; y < height; y++) {
            int from = y * width;
            int to = (y + yOffset) * paddedWidth + xOffset;
            for (int x = 0; x < width; x++) real[to + x] = src[from + x] - baseline;
        }

        fft2(real, imaginary, paddedWidth, paddedHeight, false);
        for (int y = 0; y < paddedHeight; y++) {
            int ky = y < (paddedHeight + 1) / 2 ? y : y - paddedHeight;
            for (int x = 0; x < paddedWidth; x++) {
                int kx = x < (paddedWidth + 1) / 2 ? x : x - paddedWidth;
                double phase = 2.0 * Math.PI
                        * (kx * dx / paddedWidth + ky * dy / paddedHeight);
                double cosine = Math.cos(phase);
                double sine = Math.sin(phase);
                int i = y * paddedWidth + x;
                double shiftedReal = real[i] * cosine - imaginary[i] * sine;
                imaginary[i] = real[i] * sine + imaginary[i] * cosine;
                real[i] = shiftedReal;
            }
        }
        fft2(real, imaginary, paddedWidth, paddedHeight, true);

        for (int y = 0; y < height; y++) {
            double sourceY = y + dy;
            int row = y * width;
            int paddedRow = (y + yOffset) * paddedWidth + xOffset;
            for (int x = 0; x < width; x++) {
                double sourceX = x + dx;
                dst[row + x] = sourceX < 0 || sourceY < 0
                        || sourceX > width - 1 || sourceY > height - 1
                        ? fill : (float) (real[paddedRow + x] + baseline);
            }
        }
    }

    /**
     * Apply a rigid transform with Fourier interpolation.
     *
     * <p>A rotation is exactly decomposed into horizontal, vertical and horizontal shears:
     * {@code R(theta) = Sx(-tan(theta/2)) Sy(sin(theta)) Sx(-tan(theta/2))}. Every shear is a set of
     * independent one-dimensional Fourier shifts, so no spatial interpolation kernel is introduced.
     * Large rotations are split into steps of at most 45 degrees to keep the shear factors bounded.
     */
    private static void fourierRigid(float[] src, float[] dst, int width, int height,
                                     Transform transform, float fill) {
        int side = PhaseCorrelation.nextPowerOfTwo(2 * Math.max(width, height));
        int xOffset = (side - width) / 2;
        int yOffset = (side - height) / 2;
        double centerX = xOffset + (width - 1) / 2.0;
        double centerY = yOffset + (height - 1) / 2.0;
        double baseline = Float.isFinite(fill) ? fill : 0.0;
        double[] plane = new double[side * side];
        for (int y = 0; y < height; y++) {
            int from = y * width;
            int to = (y + yOffset) * side + xOffset;
            for (int x = 0; x < width; x++) plane[to + x] = src[from + x] - baseline;
        }

        // Transform.apply() depends only on sin/cos, so reducing complete turns is exact and prevents
        // an unnecessarily long sequence when a cumulative trajectory crosses 2*pi.
        double angle = Math.IEEEremainder(transform.theta, 2.0 * Math.PI);
        int steps = Math.max(1, (int) Math.ceil(Math.abs(angle) / (Math.PI / 4.0)));
        double step = angle / steps;
        double horizontal = -Math.tan(step / 2.0);
        double vertical = Math.sin(step);
        if (angle != 0.0) {
            for (int i = 0; i < steps; i++) {
                shearHorizontal(plane, side, horizontal, centerY);
                shearVertical(plane, side, vertical, centerX);
                shearHorizontal(plane, side, horizontal, centerY);
            }
        }

        // The shears have made r(p) = src(R p). To obtain src(R p + d), sample r at
        // p + inverse(R)d.
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        double translatedX = cosine * transform.dx + sine * transform.dy;
        double translatedY = -sine * transform.dx + cosine * transform.dy;
        if (translatedX != 0.0) shiftHorizontal(plane, side, translatedX);
        if (translatedY != 0.0) shiftVertical(plane, side, translatedY);

        double[] source = new double[2];
        double localCenterX = (width - 1) / 2.0;
        double localCenterY = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int paddedRow = (y + yOffset) * side + xOffset;
            for (int x = 0; x < width; x++) {
                transform.apply(x, y, localCenterX, localCenterY, source);
                dst[row + x] = source[0] < 0 || source[1] < 0
                        || source[0] > width - 1 || source[1] > height - 1
                        ? fill : (float) (plane[paddedRow + x] + baseline);
            }
        }
    }

    /** Sample every row at {@code x + slope * (y - centerY)}. */
    private static void shearHorizontal(double[] plane, int side, double slope, double centerY) {
        double[] real = new double[side];
        double[] imaginary = new double[side];
        for (int y = 0; y < side; y++) {
            System.arraycopy(plane, y * side, real, 0, side);
            java.util.Arrays.fill(imaginary, 0.0);
            fourierShift(real, imaginary, slope * (y - centerY));
            System.arraycopy(real, 0, plane, y * side, side);
        }
    }

    /** Sample every column at {@code y + slope * (x - centerX)}. */
    private static void shearVertical(double[] plane, int side, double slope, double centerX) {
        double[] real = new double[side];
        double[] imaginary = new double[side];
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) real[y] = plane[y * side + x];
            java.util.Arrays.fill(imaginary, 0.0);
            fourierShift(real, imaginary, slope * (x - centerX));
            for (int y = 0; y < side; y++) plane[y * side + x] = real[y];
        }
    }

    private static void shiftHorizontal(double[] plane, int side, double shift) {
        double[] real = new double[side];
        double[] imaginary = new double[side];
        for (int y = 0; y < side; y++) {
            System.arraycopy(plane, y * side, real, 0, side);
            java.util.Arrays.fill(imaginary, 0.0);
            fourierShift(real, imaginary, shift);
            System.arraycopy(real, 0, plane, y * side, side);
        }
    }

    private static void shiftVertical(double[] plane, int side, double shift) {
        double[] real = new double[side];
        double[] imaginary = new double[side];
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) real[y] = plane[y * side + x];
            java.util.Arrays.fill(imaginary, 0.0);
            fourierShift(real, imaginary, shift);
            for (int y = 0; y < side; y++) plane[y * side + x] = real[y];
        }
    }

    /** In-place one-dimensional sampling at {@code x + shift}. */
    private static void fourierShift(double[] real, double[] imaginary, double shift) {
        PhaseCorrelation.fft(real, imaginary, false);
        int length = real.length;
        for (int i = 0; i < length; i++) {
            int frequency = i < (length + 1) / 2 ? i : i - length;
            double phase = 2.0 * Math.PI * frequency * shift / length;
            double cosine = Math.cos(phase);
            double sine = Math.sin(phase);
            double shiftedReal = real[i] * cosine - imaginary[i] * sine;
            imaginary[i] = real[i] * sine + imaginary[i] * cosine;
            real[i] = shiftedReal;
        }
        PhaseCorrelation.fft(real, imaginary, true);
    }

    /** Rectangular 2-D FFT assembled from the project's dependency-free radix-2 transform. */
    private static void fft2(double[] real, double[] imaginary, int width, int height,
                             boolean inverse) {
        double[] rowReal = new double[width];
        double[] rowImaginary = new double[width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(real, y * width, rowReal, 0, width);
            System.arraycopy(imaginary, y * width, rowImaginary, 0, width);
            PhaseCorrelation.fft(rowReal, rowImaginary, inverse);
            System.arraycopy(rowReal, 0, real, y * width, width);
            System.arraycopy(rowImaginary, 0, imaginary, y * width, width);
        }
        double[] columnReal = new double[height];
        double[] columnImaginary = new double[height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                columnReal[y] = real[y * width + x];
                columnImaginary[y] = imaginary[y * width + x];
            }
            PhaseCorrelation.fft(columnReal, columnImaginary, inverse);
            for (int y = 0; y < height; y++) {
                real[y * width + x] = columnReal[y];
                imaginary[y * width + x] = columnImaginary[y];
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
