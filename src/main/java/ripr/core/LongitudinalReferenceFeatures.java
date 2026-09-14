/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Arrays;

/**
 * Round-17 feature definitions for cross-language parity probes.
 * Not selected by production registration until the complete parity gates pass.
 * Uses the full image, Gaussian kernels and 3/10/3 edge weights, not box-blur approximations.
 */
final class LongitudinalReferenceFeatures {
    private LongitudinalReferenceFeatures() { }

    static float[] landmark(float[] frame, int width, int height) {
        return landmark(frame, width, height, LongitudinalReferenceFeatures::gaussianNearest);
    }

    static float[] landmark(float[] frame, int width, int height, GaussianFilter filter) {
        return landmark(frame, width, height, filter, false);
    }

    static float[] landmark(float[] frame, int width, int height, GaussianFilter filter, boolean selectMedian) {
        check(frame, width, height);
        float[] image = normalise(frame, false);
        double size = Math.min(width, height);
        float[] fine = filter.apply(image, width, height, Math.max(.8, size / 512));
        float[] middle = filter.apply(image, width, height, Math.max(3, size / 64));
        float[] broad = filter.apply(image, width, height, Math.max(8, size / 20));
        float[] darkFine = new float[frame.length], darkBroad = new float[frame.length];
        for (int i = 0; i < frame.length; i++) {
            darkFine[i] = Math.max(middle[i] - fine[i], 0f);
            darkBroad[i] = Math.max(broad[i] - middle[i], 0f);
        }
        float[] edgeSource = filter.apply(image, width, height, Math.max(1.5, size / 256));
        float[] sx = smoothThree(edgeSource, width, height, false);
        float[] sy = smoothThree(edgeSource, width, height, true);
        float[] edges = new float[frame.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float gx = sx[y * width + Math.max(0, x - 1)] - sx[y * width + Math.min(width - 1, x + 1)];
                float gy = sy[Math.max(0, y - 1) * width + x] - sy[Math.min(height - 1, y + 1) * width + x];
                edges[y * width + x] = (float) Math.hypot(gx, gy);
            }
        }
        edges = robustScale(edges, selectMedian);
        darkFine = robustScale(darkFine, selectMedian);
        darkBroad = robustScale(darkBroad, selectMedian);
        float[] feature = new float[frame.length];
        int marginX = Math.max(2, (int) Math.rint(.04 * width));
        int marginY = Math.max(2, (int) Math.rint(.04 * height));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                float value = .65f * edges[i] + darkFine[i];
                value += .55f * darkBroad[i];
                feature[i] = x < marginX || x >= width - marginX || y < marginY || y >= height - marginY
                        ? 0f : value;
            }
        }
        feature = robustScale(feature, selectMedian);
        for (int i = 0; i < feature.length; i++) feature[i] += 6f;
        return feature;
    }

    static float[] emission(float[] frame, int width, int height) {
        return emission(frame, width, height, LongitudinalReferenceFeatures::gaussianReflect101);
    }

    @FunctionalInterface
    interface GaussianFilter {
        float[] apply(float[] source, int width, int height, double sigma);
    }

    static float[] emission(float[] frame, int width, int height, GaussianFilter filter) {
        return emission(frame, width, height, filter, false);
    }

    static float[] emission(float[] frame, int width, int height, GaussianFilter filter, boolean selectMedian) {
        check(frame, width, height);
        float[] image = normalise(frame, true);
        double sigma = Math.max(2, Math.min(width, height) / 32.0);
        float[] broad = filter.apply(image, width, height, sigma);
        float[] feature = new float[frame.length];
        for (int i = 0; i < feature.length; i++) feature[i] = image[i] - broad[i];
        float centre = median(feature, selectMedian);
        float[] deviations = new float[feature.length];
        for (int i = 0; i < feature.length; i++) deviations[i] = Math.abs(feature[i] - centre);
        float scale = Math.max(1e-5f, 1.4826f * median(deviations, selectMedian));
        // Round 17 divides the uncentred high-pass image, with no +6 offset.
        for (int i = 0; i < feature.length; i++) feature[i] = clip(feature[i] / scale, -5f, 5f);
        return feature;
    }

    static float[] normalise(float[] frame, boolean floatArithmetic) {
        float[] sorted = sortedFinite(frame);
        double lo = percentileSorted(sorted, 1), hi = percentileSorted(sorted, 99);
        double range = Math.max(hi - lo, 1e-6);
        float[] output = new float[frame.length];
        for (int i = 0; i < frame.length; i++) {
            output[i] = floatArithmetic ? clip((frame[i] - (float) lo) / (float) range, 0f, 1f)
                    : (float) Math.max(0, Math.min(1, (frame[i] - lo) / range));
        }
        return output;
    }

    static float[] robustScale(float[] source) {
        return robustScale(source, false);
    }

    static float[] robustScale(float[] source, boolean selectMedian) {
        float centre = median(source, selectMedian);
        float[] differences = new float[source.length];
        for (int i = 0; i < source.length; i++) differences[i] = Math.abs(source[i] - centre);
        float scale = Math.max(1e-5f, 1.4826f * median(differences, selectMedian));
        float[] output = new float[source.length];
        for (int i = 0; i < source.length; i++) output[i] = clip((source[i] - centre) / scale, -5f, 5f);
        return output;
    }

    static float median(float[] source, boolean selectMedian) {
        if (selectMedian) return selectedMedian(source);
        float[] values = sortedFinite(source);
        int n = values.length;
        if (n == 0) return 0f;
        return (n & 1) != 0 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2f;
    }

    /** Same finite values and Float.compare order as Arrays.sort, including signed zero. */
    private static float selectedMedian(float[] source) {
        float[] values = new float[source.length]; int n = 0;
        for (float value : source) if (Float.isFinite(value)) values[n++] = value;
        if (n == 0) return 0f;
        int rank = n / 2, left = 0, right = n - 1;
        int budget = 2 * (32 - Integer.numberOfLeadingZeros(n)) + 4;
        while (left < right) {
            // Bound pathological partitions without changing the selected order statistic.
            if (--budget == 0) { Arrays.sort(values, left, right + 1); break; }
            float pivot = values[left + (right - left) / 2];
            int lo = left, scan = left, hi = right;
            while (scan <= hi) {
                int order = Float.compare(values[scan], pivot);
                if (order < 0) { float t = values[lo]; values[lo++] = values[scan]; values[scan++] = t; }
                else if (order > 0) { float t = values[hi]; values[hi--] = values[scan]; values[scan] = t; }
                else scan++;
            }
            if (rank < lo) right = lo - 1;
            else if (rank > hi) left = hi + 1;
            else break;
        }
        float upper = values[rank];
        if ((n & 1) != 0) return upper;
        float lower = values[0];
        for (int i = 1; i < rank; i++) if (Float.compare(values[i], lower) > 0) lower = values[i];
        return (lower + upper) / 2f;
    }

    private static float[] sortedFinite(float[] source) {
        float[] values = new float[source.length];
        int n = 0;
        for (float value : source) if (Float.isFinite(value)) values[n++] = value;
        values = Arrays.copyOf(values, n);
        Arrays.sort(values);
        return values;
    }

    static double percentileSorted(float[] sorted, double percentile) {
        if (sorted.length == 0) return 0;
        double position = percentile / 100 * (sorted.length - 1);
        int lower = (int) position, upper = Math.min(sorted.length - 1, lower + 1);
        double fraction = position - lower;
        double difference = (double) sorted[upper] - sorted[lower];
        return fraction >= .5 ? sorted[upper] - difference * (1 - fraction)
                : sorted[lower] + difference * fraction;
    }

    static float[] gaussianNearest(float[] source, int width, int height, double sigma) {
        int radius = (int) (4 * sigma + .5);
        double[] kernel = gaussianKernel(sigma, radius);
        // scipy.ndimage.gaussian_filter applies the Y axis, then the X axis;
        // each intermediate is float32 and each convolution accumulates in double.
        float[] vertical = gaussianAxis(source, width, height, kernel, false, false, false);
        return gaussianAxis(vertical, width, height, kernel, true, false, false);
    }

    static float[] gaussianReflect101(float[] source, int width, int height, double sigma) {
        int size = ((int) Math.rint(sigma * 8 + 1)) | 1;
        double[] kernel = gaussianKernel(sigma, size / 2);
        for (int i = 0; i < kernel.length; i++) kernel[i] = (float) kernel[i];
        float[] horizontal = gaussianAxis(source, width, height, kernel, true, true, true);
        return gaussianAxis(horizontal, width, height, kernel, false, true, true);
    }

    private static double[] gaussianKernel(double sigma, int radius) {
        double[] weights = new double[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            weights[i + radius] = Math.exp(-.5 * i * i / (sigma * sigma));
            sum += weights[i + radius];
        }
        for (int i = 0; i < weights.length; i++) weights[i] /= sum;
        return weights;
    }

    private static float[] gaussianAxis(float[] source, int width, int height, double[] kernel,
                                        boolean horizontal, boolean reflect, boolean floatAccumulator) {
        int radius = kernel.length / 2, length = horizontal ? width : height;
        float[] output = new float[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x, coordinate = horizontal ? x : y;
                double sum = source[i] * kernel[radius];
                if (floatAccumulator) sum = (float) sum;
                for (int distance = radius; distance >= 1; distance--) {
                    int a = border(coordinate - distance, length, reflect);
                    int b = border(coordinate + distance, length, reflect);
                    double left = source[horizontal ? y * width + a : a * width + x];
                    double right = source[horizontal ? y * width + b : b * width + x];
                    if (floatAccumulator) {
                        float pair = (float) left + (float) right;
                        sum = (float) sum + pair * (float) kernel[radius - distance];
                    } else sum += (left + right) * kernel[radius - distance];
                }
                output[i] = (float) sum;
            }
        }
        return output;
    }

    private static int border(int position, int length, boolean reflect) {
        if (!reflect || length <= 1) return Math.max(0, Math.min(length - 1, position));
        while (position < 0 || position >= length) position = position < 0 ? -position : 2 * length - 2 - position;
        return position;
    }

    private static float[] smoothThree(float[] source, int width, int height, boolean horizontal) {
        float[] output = new float[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int before = horizontal ? y * width + Math.max(0, x - 1) : Math.max(0, y - 1) * width + x;
                int after = horizontal ? y * width + Math.min(width - 1, x + 1) : Math.min(height - 1, y + 1) * width + x;
                output[y * width + x] = (float) (10.0 * source[y * width + x]
                        + 3.0 * (source[before] + (double) source[after]));
            }
        }
        return output;
    }

    private static float clip(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static void check(float[] source, int width, int height) {
        if (width < 2 || height < 2 || source == null || source.length != width * height)
            throw new IllegalArgumentException("Expected a 2-D frame");
    }
}
