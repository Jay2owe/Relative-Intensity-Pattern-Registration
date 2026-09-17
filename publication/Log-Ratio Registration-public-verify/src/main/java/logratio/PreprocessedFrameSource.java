/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.Preprocessing;
import logratio.core.FrameSource;

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
}
