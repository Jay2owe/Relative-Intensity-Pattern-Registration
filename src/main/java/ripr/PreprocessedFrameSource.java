/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.Preprocessing;
import ripr.core.FrameSource;

import java.util.Arrays;

/** Lazily prepares estimation planes while leaving the source and corrected output untouched. */
public final class PreprocessedFrameSource implements FrameSource {
    private final FrameSource source;
    private final Preprocessing preprocessing;
    private final float[][] cache;
    private final Object[] locks;

    public PreprocessedFrameSource(FrameSource source, Preprocessing preprocessing) {
        if (source == null || preprocessing == null) {
            throw new IllegalArgumentException("source and preprocessing are required");
        }
        this.source = source;
        this.preprocessing = preprocessing;
        this.cache = new float[source.count()][];
        this.locks = new Object[source.count()];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    @Override public int count() { return source.count(); }
    @Override public int width() { return source.width(); }
    @Override public int height() { return source.height(); }

    @Override
    public float[] plane(int frame) {
        float[] prepared = cache[frame];
        if (prepared == null) {
            synchronized (locks[frame]) {
                prepared = cache[frame];
                if (prepared == null) {
                    prepared = apply(source.plane(frame), width(), height(), preprocessing);
                    cache[frame] = prepared;
                }
            }
        }
        return prepared.clone();
    }

    static float[] apply(float[] input, int width, int height, Preprocessing preprocessing) {
        switch (preprocessing) {
            case NONE:
                return input.clone();
            case GAUSSIAN_0_7:
                return separable(input, width, height, new double[]{1, 2, 1});
            case GAUSSIAN_1_0:
                return separable(input, width, height, new double[]{1, 4, 6, 4, 1});
            case GAUSSIAN_1_4:
                return separable(separable(input, width, height,
                        new double[]{1, 4, 6, 4, 1}), width, height,
                        new double[]{1, 4, 6, 4, 1});
            case MEDIAN_3X3:
                return median3(input, width, height);
            case ANSCOMBE:
                return anscombe(input);
            case ANSCOMBE_GAUSSIAN_1_0:
                return separable(anscombe(input), width, height,
                        new double[]{1, 4, 6, 4, 1});
            case UNSHARP_0_5:
                return unsharp(input, width, height, 0.5);
            case LOCAL_CONTRAST_1_8:
                return localContrast(input, width, height, 8.0);
            case LOCAL_CONTRAST_1_16:
                return localContrast(input, width, height, 16.0);
            case LOCAL_CONTRAST_1_32:
                return localContrast(input, width, height, 32.0);
            case STRUCTURAL_GRADIENT:
                return structuralGradient(input, width, height);
            default:
                throw new IllegalStateException("unknown preprocessing " + preprocessing);
        }
    }

    private static float[] separable(float[] input, int width, int height, double[] kernel) {
        int radius = kernel.length / 2;
        float[] horizontal = new float[input.length];
        float[] output = new float[input.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                double sum = 0;
                double weight = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = Math.max(0, Math.min(width - 1, x + k));
                    float value = input[row + sx];
                    if (Float.isNaN(value)) continue;
                    double w = kernel[k + radius];
                    sum += w * value;
                    weight += w;
                }
                horizontal[row + x] = weight > 0 ? (float) (sum / weight) : Float.NaN;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0;
                double weight = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = Math.max(0, Math.min(height - 1, y + k));
                    float value = horizontal[sy * width + x];
                    if (Float.isNaN(value)) continue;
                    double w = kernel[k + radius];
                    sum += w * value;
                    weight += w;
                }
                output[y * width + x] = weight > 0 ? (float) (sum / weight) : Float.NaN;
            }
        }
        return output;
    }

    private static float[] median3(float[] input, int width, int height) {
        float[] output = new float[input.length];
        float[] values = new float[9];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int sy = Math.max(0, Math.min(height - 1, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = Math.max(0, Math.min(width - 1, x + dx));
                        float value = input[sy * width + sx];
                        if (!Float.isNaN(value)) values[n++] = value;
                    }
                }
                if (n == 0) output[y * width + x] = Float.NaN;
                else {
                    Arrays.sort(values, 0, n);
                    output[y * width + x] = values[n / 2];
                }
            }
        }
        return output;
    }

    private static float[] anscombe(float[] input) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            float value = input[i];
            output[i] = Float.isNaN(value) ? Float.NaN
                    : (float) (2.0 * Math.sqrt(Math.max(0, value) + 3.0 / 8.0));
        }
        return output;
    }

    private static float[] unsharp(float[] input, int width, int height, double amount) {
        float[] blurred = separable(input, width, height, new double[]{1, 4, 6, 4, 1});
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            if (Float.isNaN(input[i]) || Float.isNaN(blurred[i])) output[i] = Float.NaN;
            else output[i] = (float) Math.max(0, input[i] + amount * (input[i] - blurred[i]));
        }
        return output;
    }

    /** Remove broad spatial variation, then retain every finite pixel in the positive log domain. */
    private static float[] localContrast(
            float[] input, int width, int height, double spanDivisor) {
        int radius = Math.max(2,
                (int) Math.round(Math.min(width, height) / (2.0 * spanDivisor)));
        float[] background = boxMean(input, width, height, radius);
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = Float.isFinite(input[i]) && Float.isFinite(background[i])
                    ? input[i] - background[i] : Float.NaN;
        }
        shiftFiniteMinimumToZero(output);
        return output;
    }

    /**
     * A local residual is signed, while {@code LogPlane} requires {@code intensity + epsilon > 0}.
     * A frame-wide offset preserves the residual's spatial pattern and is removed again by the
     * mean-centring used by every area-correlation estimator.
     */
    private static void shiftFiniteMinimumToZero(float[] values) {
        float minimum = Float.POSITIVE_INFINITY;
        for (float value : values) {
            if (Float.isFinite(value) && value < minimum) minimum = value;
        }
        if (!(minimum < 0)) return;
        for (int i = 0; i < values.length; i++) {
            if (Float.isFinite(values[i])) values[i] -= minimum;
        }
    }

    /** Sobel-like gradient magnitude; uniform gain and offset do not create positional structure. */
    private static float[] structuralGradient(float[] input, int width, int height) {
        float[] smoothed = separable(input, width, height, new double[]{1, 2, 1});
        float[] output = new float[input.length];
        for (int y = 0; y < height; y++) {
            int ym = Math.max(0, y - 1);
            int yp = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int xm = Math.max(0, x - 1);
                int xp = Math.min(width - 1, x + 1);
                float left = smoothed[y * width + xm];
                float right = smoothed[y * width + xp];
                float up = smoothed[ym * width + x];
                float down = smoothed[yp * width + x];
                output[y * width + x] = Float.isFinite(left) && Float.isFinite(right)
                        && Float.isFinite(up) && Float.isFinite(down)
                        ? (float) Math.hypot(0.5 * (right - left), 0.5 * (down - up))
                        : Float.NaN;
            }
        }
        return output;
    }

    /** Finite-aware constant-time box mean using summed values and valid-pixel counts. */
    private static float[] boxMean(float[] input, int width, int height, int radius) {
        int stride = width + 1;
        double[] sum = new double[(width + 1) * (height + 1)];
        int[] count = new int[sum.length];
        for (int y = 0; y < height; y++) {
            double rowSum = 0;
            int rowCount = 0;
            for (int x = 0; x < width; x++) {
                float value = input[y * width + x];
                if (Float.isFinite(value)) {
                    rowSum += value;
                    rowCount++;
                }
                int at = (y + 1) * stride + x + 1;
                sum[at] = sum[y * stride + x + 1] + rowSum;
                count[at] = count[y * stride + x + 1] + rowCount;
            }
        }
        float[] output = new float[input.length];
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height, y + radius + 1);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width, x + radius + 1);
                double total = sum[y1 * stride + x1] - sum[y0 * stride + x1]
                        - sum[y1 * stride + x0] + sum[y0 * stride + x0];
                int n = count[y1 * stride + x1] - count[y0 * stride + x1]
                        - count[y1 * stride + x0] + count[y0 * stride + x0];
                output[y * width + x] = n == 0 ? Float.NaN : (float) (total / n);
            }
        }
        return output;
    }
}
