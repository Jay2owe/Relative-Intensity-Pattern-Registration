/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Warping, and the bit-exactness claim that justifies the default. */
public class WarperTest {

    private static final int W = 32;
    private static final int H = 24;

    private static float[] ramp() {
        float[] a = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) a[y * W + x] = y * 1000 + x;
        }
        return a;
    }

    /**
     * A whole-pixel shift must be <b>bit-exact</b>, not merely close.
     *
     * <p>This is what makes {@link Warper.Interpolation#NONE} the right default for measurement: a
     * registered 16-bit stack still contains exactly the original counts, so a per-pixel intensity
     * trajectory measured afterwards is measuring the sample and not the interpolator.
     */
    @Test
    public void wholePixelShiftIsBitExact() {
        float[] src = ramp();
        float[] dst = new float[W * H];
        Warper.warp(src, dst, W, H, Transform.translation(3, -2), Warper.Interpolation.NONE, -1f);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int sx = x + 3;
                int sy = y - 2;
                float expected = (sx >= 0 && sx < W && sy >= 0 && sy < H)
                        ? src[sy * W + sx] : -1f;
                assertEquals("pixel " + x + "," + y,
                        Float.floatToRawIntBits(expected),
                        Float.floatToRawIntBits(dst[y * W + x]));
            }
        }
    }

    /** At an integer shift the resampling path and the block-copy path must agree exactly. */
    @Test
    public void interpolationPathsAgreeAtIntegerShifts() {
        float[] src = ramp();
        float[] fast = new float[W * H];
        float[] slow = new float[W * H];
        Warper.warp(src, fast, W, H, Transform.translation(4, 3), Warper.Interpolation.NONE, 0f);
        // A theta of exactly zero plus a whole-pixel translation takes the block-copy path, so force
        // the sampler by asking for a shift that is integral but flagged as needing interpolation.
        Warper.warp(src, slow, W, H, new Transform(4, 3, 1e-300), Warper.Interpolation.BILINEAR, 0f);
        for (int i = 0; i < src.length; i++) {
            assertEquals("pixel " + i, fast[i], slow[i], 1e-3);
        }
    }

    /** A half-pixel shift must actually interpolate, and stay within the neighbouring values. */
    @Test
    public void bilinearInterpolatesAndStaysBounded() {
        float[] src = ramp();
        float[] dst = new float[W * H];
        Warper.warp(src, dst, W, H, Transform.translation(0.5, 0), Warper.Interpolation.BILINEAR, 0f);
        int i = 10 * W + 10;
        assertEquals("halfway between x=10 and x=11", src[i] + 0.5f, dst[i], 1e-3);
    }

    /** Fourier phase shifting accurately samples a smooth band-limited plane between pixels. */
    @Test
    public void fourierInterpolatesAFractionalTranslation() {
        float[] src = new float[W * H];
        float[] dst = new float[W * H];
        double cx = (W - 1) / 2.0;
        double cy = (H - 1) / 2.0;
        double sigma = 3.0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                src[y * W + x] = (float) Math.exp(-((x - cx) * (x - cx)
                        + (y - cy) * (y - cy)) / (2 * sigma * sigma));
            }
        }
        double dx = 0.375;
        double dy = -0.625;
        Warper.warp(src, dst, W, H, Transform.translation(dx, dy),
                Warper.Interpolation.FOURIER, 0f);
        double maximumError = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double sx = x + dx;
                double sy = y + dy;
                if (sx < 0 || sy < 0 || sx > W - 1 || sy > H - 1) continue;
                double expected = Math.exp(-((sx - cx) * (sx - cx)
                        + (sy - cy) * (sy - cy)) / (2 * sigma * sigma));
                maximumError = Math.max(maximumError, Math.abs(dst[y * W + x] - expected));
            }
        }
        assertTrue("maximum Fourier interpolation error: " + maximumError,
                maximumError < 5e-5);
    }

    /** Fourier mode keeps the existing bit-exact fast path when no interpolation is needed. */
    @Test
    public void fourierWholePixelTranslationIsBitExact() {
        float[] none = new float[W * H];
        float[] fourier = new float[W * H];
        Warper.warp(ramp(), none, W, H, Transform.translation(3, -2),
                Warper.Interpolation.NONE, -1f);
        Warper.warp(ramp(), fourier, W, H, Transform.translation(3, -2),
                Warper.Interpolation.FOURIER, -1f);
        for (int i = 0; i < none.length; i++) {
            assertEquals(Float.floatToRawIntBits(none[i]),
                    Float.floatToRawIntBits(fourier[i]));
        }
    }

    /** Three Fourier shears accurately apply rotation and translation together. */
    @Test
    public void fourierInterpolatesARigidTransform() {
        int width = 64;
        int height = 48;
        double imageCenterX = (width - 1) / 2.0;
        double imageCenterY = (height - 1) / 2.0;
        double peakX = imageCenterX - 3.0;
        double peakY = imageCenterY + 2.0;
        double sigmaX = 5.0;
        double sigmaY = 2.5;
        float[] src = new float[width * height];
        float[] dst = new float[src.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                src[y * width + x] = (float) Math.exp(-0.5 * (
                        square((x - peakX) / sigmaX) + square((y - peakY) / sigmaY)));
            }
        }
        for (double angle : new double[]{0.22, Math.toRadians(100)}) {
            Transform transform = new Transform(0.7, -0.45, angle);
            Warper.warp(src, dst, width, height, transform, Warper.Interpolation.FOURIER, 0f);
            double maximumError = 0;
            double[] source = new double[2];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    transform.apply(x, y, imageCenterX, imageCenterY, source);
                    if (source[0] < 0 || source[1] < 0
                            || source[0] > width - 1 || source[1] > height - 1) continue;
                    double expected = Math.exp(-0.5 * (
                            square((source[0] - peakX) / sigmaX)
                                    + square((source[1] - peakY) / sigmaY)));
                    maximumError = Math.max(maximumError,
                            Math.abs(dst[y * width + x] - expected));
                }
            }
            assertTrue("maximum rigid Fourier interpolation error at " + angle
                            + " radians: " + maximumError,
                    maximumError < 1e-5);
        }
    }

    private static double square(double value) {
        return value * value;
    }

    /**
     * The valid margin. A recording that drifts leaves a real strip of fill, and any statistic
     * computed there measures the fill rather than the sample.
     */
    @Test
    public void validMarginIsExactForTranslation() {
        Transform[] cum = {
                Transform.translation(0, 0),
                Transform.translation(3, -2),      // needs 3 on the right, 2 on the top
                Transform.translation(-5, 4)};     // needs 5 on the left, 4 on the bottom
        Warper.Margin m = Warper.validMargin(cum, 100, 100, Warper.Interpolation.NONE);
        assertEquals("top", 2, m.top);
        assertEquals("bottom", 4, m.bottom);
        assertEquals("left", 5, m.left);
        assertEquals("right", 3, m.right);
        assertEquals(92, m.croppedWidth(100));
        assertEquals(94, m.croppedHeight(100));
    }

    /** Interpolation reads beyond its sample, so the trustworthy region is smaller. */
    @Test
    public void validMarginGrowsWithInterpolationReach() {
        Transform[] cum = {Transform.translation(0, 0), Transform.translation(3, 0)};
        assertEquals(3, Warper.validMargin(cum, 100, 100, Warper.Interpolation.NONE).right);
        assertEquals(4, Warper.validMargin(cum, 100, 100, Warper.Interpolation.BILINEAR).right);
        assertEquals(5, Warper.validMargin(cum, 100, 100, Warper.Interpolation.BICUBIC).right);
        assertEquals(4, Warper.validMargin(cum, 100, 100, Warper.Interpolation.FOURIER).right);
    }

    /** A wild transform must not crop the frame out of existence. */
    @Test
    public void validMarginNeverCropsEverything() {
        Transform[] cum = {Transform.translation(0, 0), Transform.translation(10_000, 10_000)};
        Warper.Margin m = Warper.validMargin(cum, 100, 100, Warper.Interpolation.NONE);
        assertTrue("width left: " + m.croppedWidth(100), m.croppedWidth(100) >= 2);
        assertTrue("height left: " + m.croppedHeight(100), m.croppedHeight(100) >= 2);
    }

    @Test
    public void detectsWholePixelTranslation() {
        assertTrue(Warper.isWholePixelTranslation(
                new Transform[]{Transform.translation(1, 2), Transform.translation(-3, 0)}, 1e-9));
        assertFalse("a fractional shift is not whole-pixel", Warper.isWholePixelTranslation(
                new Transform[]{Transform.translation(1.5, 2)}, 1e-9));
        assertFalse("a rotation is not a translation", Warper.isWholePixelTranslation(
                new Transform[]{new Transform(1, 2, 0.01)}, 1e-9));
    }

    @Test
    public void cropTakesTheRequestedRegion() {
        float[] src = ramp();
        Warper.Margin m = Warper.validMargin(
                new Transform[]{Transform.translation(2, 1)}, W, H, Warper.Interpolation.NONE);
        float[] cropped = Warper.crop(src, W, H, m);
        assertEquals(m.croppedWidth(W) * m.croppedHeight(H), cropped.length);
        assertEquals("top-left of the crop", src[m.top * W + m.left], cropped[0], 0);
    }

    /**
     * {@link Warper.Interpolation#NONE} must round a fractional shift, not sample it.
     *
     * <p>A real bug, found by the validation harness rather than by any unit test: the integer and
     * bilinear runs produced identical numbers to four figures because {@code NONE} fell through to the
     * bilinear sampler whenever the shift was fractional. The documented default was therefore
     * interpolating — injecting exactly the frame-varying blur it exists to avoid — on every real stack,
     * since real shifts are essentially never whole pixels.
     */
    @Test
    public void noneRoundsAFractionalShiftInsteadOfInterpolating() {
        float[] src = ramp();
        float[] none = new float[W * H];
        float[] whole = new float[W * H];
        Warper.warp(src, none, W, H, Transform.translation(2.4, -1.6),
                Warper.Interpolation.NONE, 0f);
        Warper.warp(src, whole, W, H, Transform.translation(2, -2),
                Warper.Interpolation.NONE, 0f);
        for (int i = 0; i < src.length; i++) {
            assertEquals("NONE at (2.4, -1.6) must equal a whole-pixel shift of (2, -2), bit for bit",
                    Float.floatToRawIntBits(whole[i]), Float.floatToRawIntBits(none[i]));
        }

        float[] bilinear = new float[W * H];
        Warper.warp(src, bilinear, W, H, Transform.translation(2.4, -1.6),
                Warper.Interpolation.BILINEAR, 0f);
        boolean differs = false;
        for (int i = 0; i < src.length && !differs; i++) {
            if (Math.abs(bilinear[i] - none[i]) > 1e-6) differs = true;
        }
        assertTrue("and bilinear must actually differ from it, or the test proves nothing", differs);
    }

    /** A rotation with NONE uses nearest-neighbour, not a silent upgrade to bilinear. */
    @Test
    public void noneUsesNearestNeighbourWhenThereIsRotation() {
        float[] src = ramp();
        float[] dst = new float[W * H];
        Warper.warp(src, dst, W, H, new Transform(0, 0, 0.05), Warper.Interpolation.NONE, -1f);
        for (float v : dst) {
            if (v == -1f) continue;
            assertEquals("every value must be one of the source values, unblended",
                    v, Math.rint(v), 1e-6);
        }
    }

    /** A null transform is the identity, not a crash. */
    @Test
    public void nullTransformCopies() {
        float[] src = ramp();
        float[] dst = new float[W * H];
        Warper.warp(src, dst, W, H, null, Warper.Interpolation.NONE, 0f);
        for (int i = 0; i < src.length; i++) assertEquals(src[i], dst[i], 0);
    }

    /** A shift larger than the frame leaves fill, not an exception or a wrapped copy. */
    @Test
    public void shiftBeyondTheFrameIsAllFill() {
        float[] dst = new float[W * H];
        Warper.warp(ramp(), dst, W, H, Transform.translation(W + 5, 0),
                Warper.Interpolation.NONE, 7f);
        for (float v : dst) assertEquals(7f, v, 0);
    }
}
