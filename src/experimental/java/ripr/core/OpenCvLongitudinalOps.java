/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_core.TermCriteria;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur;
import static org.bytedeco.opencv.global.opencv_video.*;

/** Native operations under test; deliberately not wired to the installed plugin. */
final class OpenCvLongitudinalOps {
    private OpenCvLongitudinalOps() { }

    static float[] gaussian(float[] source, int width, int height, double sigma) {
        check(source, width, height);
        try (FloatPointer pixels = new FloatPointer(source);
             Mat input = new Mat(height, width, CV_32FC1, pixels);
             Mat output = new Mat(); Size size = new Size(0, 0)) {
            GaussianBlur(input, output, size, sigma, 0.0, BORDER_DEFAULT);
            return read(output, source.length);
        }
    }

    static final class Fit {
        final float[] matrix;
        final double score;
        Fit(float[] matrix, double score) { this.matrix = matrix; this.score = score; }
    }

    /**
     * Unwindowed phase correlation, refined on a 0.1-pixel local DFT grid.
     * Returns the moving-image sampling matrix, not the opposite correction shift.
     * The grid matches the frozen reference's upsample_factor=10; unlike the old
     * Java phase seed it neither adds a window nor verifies different integer peaks.
     */
    static float[] phase(float[] reference, float[] moving, int width, int height) {
        try (Spectrum a = new Spectrum(reference, width, height); Spectrum b = new Spectrum(moving, width, height)) {
            return phase(a, b, width, height);
        }
    }

    /** Owns one immutable forward transform, never used as an output matrix. */
    static final class Spectrum implements AutoCloseable {
        final Mat value = new Mat();
        Spectrum(float[] source, int width, int height) {
            try {
                check(source, width, height);
                try (FloatPointer pixels = new FloatPointer(source);
                     Mat input = new Mat(height, width, CV_32FC1, pixels)) {
                    dft(input, value, DFT_COMPLEX_OUTPUT, 0);
                }
            } catch (RuntimeException | Error failure) { value.close(); throw failure; }
        }
        public void close() { value.close(); }
    }

    static float[] phase(Spectrum reference, Spectrum moving, int width, int height) {
        int pixels = width * height;
        float[] product;
        int peak = 0;
        try (Mat cross = new Mat(); Mat surface = new Mat()) {
            mulSpectrums(reference.value, moving.value, cross, 0, true);
            product = read(cross, 2 * pixels);
            float floor = 100f * Math.ulp(1f);
            for (int i = 0; i < pixels; i++) {
                float re = product[2*i], im = product[2*i+1];
                float divisor = Math.max(floor, (float) Math.hypot(re, im));
                product[2*i] /= divisor; product[2*i+1] /= divisor;
            }
            java.nio.FloatBuffer crossBuffer = cross.createBuffer();
            crossBuffer.put(product);
            dft(cross, surface, DFT_INVERSE | DFT_SCALE | DFT_COMPLEX_OUTPUT, 0);
            float[] values = read(surface, 2 * pixels);
            double best = -1;
            for (int i = 0; i < pixels; i++) {
                double magnitude = Math.hypot(values[2*i], values[2*i+1]);
                if (magnitude > best) { best = magnitude; peak = i; }
            }
        }
        int sx = peak % width, sy = peak / width;
        if (sx > width / 2) sx -= width;
        if (sy > height / 2) sy -= height;
        final int side = 15, centre = 7;
        double[] cosine = new double[side * width], sine = new double[side * width];
        for (int dx = 0; dx < side; dx++) for (int x = 0; x < width; x++) {
            int frequency = x <= (width - 1) / 2 ? x : x - width;
            double angle = 2 * Math.PI * frequency / width * (sx + (dx - centre) / 10.0);
            cosine[dx * width + x] = Math.cos(angle);
            sine[dx * width + x] = Math.sin(angle);
        }
        double[] tempRe = new double[side * height], tempIm = new double[side * height];
        for (int dx = 0; dx < side; dx++) for (int y = 0; y < height; y++) {
            double re = 0, im = 0;
            for (int x = 0; x < width; x++) {
                double c = cosine[dx * width + x], s = sine[dx * width + x];
                int index = 2 * (y * width + x);
                re += product[index] * c - product[index+1] * s;
                im += product[index] * s + product[index+1] * c;
            }
            tempRe[dx * height + y] = re; tempIm[dx * height + y] = im;
        }
        double best = -1; int bestX = 0, bestY = 0;
        for (int dy = 0; dy < side; dy++) {
            double[] cy = new double[height], iy = new double[height];
            for (int y = 0; y < height; y++) {
                int frequency = y <= (height - 1) / 2 ? y : y - height;
                double angle = 2 * Math.PI * frequency / height * (sy + (dy - centre) / 10.0);
                cy[y] = Math.cos(angle); iy[y] = Math.sin(angle);
            }
            for (int dx = 0; dx < side; dx++) {
                double re = 0, im = 0;
                for (int y = 0; y < height; y++) {
                    double a = tempRe[dx * height + y], b = tempIm[dx * height + y];
                    re += a * cy[y] - b * iy[y]; im += a * iy[y] + b * cy[y];
                }
                double magnitude = Math.hypot(re, im);
                if (magnitude > best) { best = magnitude; bestX = dx; bestY = dy; }
            }
        }
        // The reference stores its upsampled shift in float32 before negation.
        float xShift = (float) sx + (float) (bestX - centre) / 10f;
        float yShift = (float) sy + (float) (bestY - centre) / 10f;
        return new float[]{1, 0, -xShift, 0, 1, -yShift};
    }

    /** Input/output matrix maps reference coordinates into the moving image. */
    static Fit fit(float[] reference, float[] moving, int width, int height,
                   float[] initial, int filterSize) {
        check(reference, width, height);
        check(moving, width, height);
        if (initial == null || initial.length != 6 || filterSize < 1 || filterSize % 2 != 1)
            throw new IllegalArgumentException("Expected 2x3 matrix and positive odd filter size");
        for (float value : initial) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Non-finite starting matrix");
        try (FloatPointer left = new FloatPointer(reference);
             FloatPointer right = new FloatPointer(moving);
             FloatPointer seed = new FloatPointer(initial);
             Mat a = new Mat(height, width, CV_32FC1, left);
             Mat b = new Mat(height, width, CV_32FC1, right);
             Mat warp = new Mat(2, 3, CV_32FC1, seed);
             Mat mask = new Mat();
             TermCriteria criteria = new TermCriteria(TermCriteria.COUNT | TermCriteria.EPS, 100, 1e-7)) {
            double score = findTransformECC(a, b, warp, MOTION_EUCLIDEAN, criteria, mask, filterSize);
            float[] result = read(warp, 6);
            if (!Double.isFinite(score)) throw new IllegalStateException("Non-finite ECC score");
            for (float value : result) if (!Float.isFinite(value))
                throw new IllegalStateException("Non-finite ECC matrix");
            return new Fit(result, score);
        }
    }

    /** Pair-owned inputs and scratch handles; never shared between frame workers. */
    static final class FitBuffer implements AutoCloseable {
        private final Mat a = new Mat(), b = new Mat(), warp = new Mat(), mask = new Mat();
        private final TermCriteria criteria = new TermCriteria(TermCriteria.COUNT | TermCriteria.EPS, 100, 1e-7);
        private final boolean reuseSmoothing;
        private Mat smoothedA, smoothedB;
        private int preparedFilter = -1;
        FitBuffer(float[] reference, float[] moving, int width, int height) {
            this(reference,moving,width,height,false);
        }
        FitBuffer(float[] reference, float[] moving, int width, int height, boolean reuseSmoothing) {
            this.reuseSmoothing=reuseSmoothing;
            try {
                check(reference, width, height); check(moving, width, height);
                a.create(height, width, CV_32FC1); b.create(height, width, CV_32FC1); warp.create(2, 3, CV_32FC1);
                java.nio.FloatBuffer left = a.createBuffer(), right = b.createBuffer();
                left.put(reference); right.put(moving);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        Fit fit(float[] initial, int filterSize) {
            if (initial == null || initial.length != 6 || filterSize < 1 || filterSize % 2 != 1)
                throw new IllegalArgumentException("Expected 2x3 matrix and positive odd filter size");
            for (float value : initial) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite starting matrix");
            java.nio.FloatBuffer seed = warp.createBuffer(); seed.put(initial);
            mask.release();
            Mat left=a,right=b;int nativeFilter=filterSize;
            if(reuseSmoothing) {
                if(smoothedA==null)smoothedA=new Mat();
                if(smoothedB==null)smoothedB=new Mat();
                if(preparedFilter!=filterSize) {
                    try(Size size=new Size(filterSize,filterSize)) {
                        GaussianBlur(a,smoothedA,size,0,0,BORDER_DEFAULT);
                        GaussianBlur(b,smoothedB,size,0,0,BORDER_DEFAULT);
                    }
                    preparedFilter=filterSize;
                }
                left=smoothedA;right=smoothedB;nativeFilter=1;
            }
            // This route always has an empty input mask. Its all-one native pre-mask remains
            // all one after the original blur/rounding; raw score/warp-bit tests bind this optimisation.
            double score = findTransformECC(left, right, warp, MOTION_EUCLIDEAN, criteria, mask, nativeFilter);
            float[] result = read(warp, 6);
            if (!Double.isFinite(score)) throw new IllegalStateException("Non-finite ECC score");
            for (float value : result) if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite ECC matrix");
            return new Fit(result, score);
        }
        public void close() {
            if(smoothedB!=null)smoothedB.close();if(smoothedA!=null)smoothedA.close();
            criteria.close(); mask.close(); warp.close(); b.close(); a.close();
        }
    }

    private static float[] read(Mat source, int count) {
        float[] values = new float[count];
        // Mat owns this memory. The non-owning view must not outlive it.
        java.nio.FloatBuffer buffer = source.createBuffer();
        buffer.get(values);
        return values;
    }

    private static void check(float[] image, int width, int height) {
        if (image == null || width < 2 || height < 2 || (long) width * height != image.length)
            throw new IllegalArgumentException("Expected one complete 2-D image");
        for (float value : image) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Non-finite image pixel");
    }
}
