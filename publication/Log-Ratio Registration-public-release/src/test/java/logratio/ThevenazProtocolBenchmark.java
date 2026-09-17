/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import logratio.core.LogPlane;
import logratio.core.PairAligner;
import logratio.core.PairEstimator;
import logratio.core.Transform;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Reconstructs the ideal-case experiment of Thevenaz, Ruttimann and Unser (1998), then puts this
 * project's estimators inside the same data design.
 *
 * <p>This is deliberately a test-side, command-line benchmark. It changes no shipped code. Stage 0
 * is a control and is run first: TurboReg in affine mode must reproduce the paper's ML*3 warping
 * index within the predeclared factor-of-two interval. If it does not, the program records the
 * failure and exits before running any estimator from this project.
 *
 * <p>The paper is IEEE Transactions on Image Processing 7(1):27-41, DOI 10.1109/83.650848. The
 * official author PDF is {@value #PAPER_URL}. Table III is rasterised in that PDF; its final ML*3
 * warping index was visually transcribed before this harness was run. The native 256-by-256 Fig. 3
 * raster is recovered from the authors' PostScript instead of using the downsampled PDF copy.
 */
public final class ThevenazProtocolBenchmark {

    /** Table III, final row, ML*3, warping index in pixels. Read from the rendered paper page. */
    public static final double PUBLISHED_ML_STAR_3_WARPING_INDEX = 0.0009;
    /** Predeclared Stage 0 tolerance from docs/thevenaz_protocol_plan.md. */
    public static final double STAGE_ZERO_FACTOR_TOLERANCE = 2.0;
    public static final double STAGE_ZERO_MIN =
            PUBLISHED_ML_STAR_3_WARPING_INDEX / STAGE_ZERO_FACTOR_TOLERANCE;
    public static final double STAGE_ZERO_MAX =
            PUBLISHED_ML_STAR_3_WARPING_INDEX * STAGE_ZERO_FACTOR_TOLERANCE;

    public static final String PAPER_URL =
            "https://bigwww.epfl.ch/publications/thevenaz9801.pdf";
    public static final String PAPER_SHA256 =
            "4810ce6fd21dbd5056a1107b5b9f3a108fb835145c9514f0ef054cce7e940564";
    public static final String PAPER_POSTSCRIPT_URL =
            "https://bigwww.epfl.ch/publications/thevenaz9801.ps";
    public static final String PAPER_POSTSCRIPT_SHA256 =
            "c9174518663beebbd272146b2b35deeca855ba9505a8c2a4074743384db8ac24";
    /** Correct Fig. 3 image as extracted from PDF page 2 (the PDF copy is 239 by 240). */
    public static final String FIGURE_3_PDF_IMAGE_SHA256 =
            "9afb7aaf9df3b56a21814366647911c5d7e1c5655db7ac50f66324814b67cbfa";
    /** Native 256-by-256 Fig. 3 sample bytes embedded in the official PostScript. */
    public static final String FIGURE_3_POSTSCRIPT_RASTER_SHA256 =
            "ec6bfcda55e01d7047807a3f9536fbf96df17837e438b4dd22bc018ac2241d85";

    /** Fixed before generation. The same trials are supplied to every arm and every image. */
    public static final long RANDOM_SEED = 0x54484556454e415aL;
    /** Independent fixed stream for the Stage 4 Gaussian-noise realizations. */
    public static final long NOISE_RANDOM_SEED = 0x4e4f4953455f5357L;
    /** The six noise levels plotted in Fig. 5 of the paper, run from mildest to strongest. */
    static final double[] NOISE_SNR_DB = {25, 20, 15, 10, 5, 0};
    public static final int DEFAULT_TRIALS = 100;
    public static final double HALF_ROTATION_RANGE_DEGREES = 5.0;
    public static final double HALF_TRANSLATION_RANGE_PIXELS = 2.5;

    /** Stage 3 gates, stated before the rotation path is measured. */
    public static final double ROTATION_GATE_TOLERANCE_DEGREES = 0.10;
    public static final double ROTATION_GATE_TRANSLATION_TOLERANCE_PIXELS = 0.10;
    public static final double ZERO_ROTATION_ALLOWED_DEGRADATION_PIXELS = 0.01;

    private static final int DEVELOPMENT_CROP = 256;
    private static final double[] SPLINE_7_POLES = {
            -0.5352804307964381655424037816816460718339231523426924148812,
            -0.122554615192326690515272264359357343605486549427295558490763,
            -0.0091486948096082769285930216516478534156925639545994482648003
    };
    private static final int[] BINOMIAL_8 = {1, 8, 28, 56, 70, 56, 28, 8, 1};
    private static final PairEstimator.Kind[] TRANSLATION_ESTIMATORS = {
            PairEstimator.Kind.LOG_RATIO_FIT,
            PairEstimator.Kind.AREA_CORRELATION,
            PairEstimator.Kind.AREA_CORRELATION_NEWTON
    };

    private ThevenazProtocolBenchmark() {
    }

    /** Regions tried in order if the paper's incompletely specified V makes the full-frame gate fail. */
    public enum Region {
        FULL_FRAME(1.00),
        CENTRAL_90(0.90),
        CENTRAL_80(0.80),
        CENTRAL_75(0.75),
        CENTRAL_50(0.50);

        final double fraction;

        Region(double fraction) {
            this.fraction = fraction;
        }
    }

    enum TurboMode {
        TRANSLATION("translation", 1),
        RIGID_BODY("rigidBody", 3),
        AFFINE("affine", 3);

        final String option;
        final int points;

        TurboMode(String option, int points) {
            this.option = option;
            this.points = points;
        }
    }

    /** A general 2-D affine map: x' = a00 x + a01 y + tx; y' = a10 x + a11 y + ty. */
    public static final class Affine2D {
        public final double a00;
        public final double a01;
        public final double a10;
        public final double a11;
        public final double tx;
        public final double ty;

        public Affine2D(double a00, double a01, double a10, double a11,
                        double tx, double ty) {
            this.a00 = a00;
            this.a01 = a01;
            this.a10 = a10;
            this.a11 = a11;
            this.tx = tx;
            this.ty = ty;
        }

        public static Affine2D identity() {
            return new Affine2D(1, 0, 0, 1, 0, 0);
        }

        public static Affine2D translation(double dx, double dy) {
            return new Affine2D(1, 0, 0, 1, dx, dy);
        }

        /** Convert the project's centre-based rigid transform to an ordinary affine map. */
        public static Affine2D rigid(Transform transform, int width, int height) {
            double c = Math.cos(transform.theta);
            double s = Math.sin(transform.theta);
            double cx = (width - 1) / 2.0;
            double cy = (height - 1) / 2.0;
            return new Affine2D(c, -s, s, c,
                    cx - c * cx + s * cy + transform.dx,
                    cy - s * cx - c * cy + transform.dy);
        }

        /** Exact affine map taking {@code from[i]} to {@code to[i]} for three non-collinear points. */
        static Affine2D fromPointPairs(double[][] from, double[][] to) {
            if (from == null || to == null || from.length < 3 || to.length < 3) {
                throw new IllegalArgumentException("three point pairs are required for an affine map");
            }
            double[][] m = {
                    {from[0][0], from[0][1], 1},
                    {from[1][0], from[1][1], 1},
                    {from[2][0], from[2][1], 1}
            };
            double[] x = solve3(m, new double[]{to[0][0], to[1][0], to[2][0]});
            double[] y = solve3(m, new double[]{to[0][1], to[1][1], to[2][1]});
            return new Affine2D(x[0], x[1], y[0], y[1], x[2], y[2]);
        }

        /** Least-squares orientation-preserving rigid map from two or more point pairs. */
        static Affine2D rigidFromPointPairs(double[][] from, double[][] to) {
            if (from == null || to == null || from.length != to.length || from.length < 2) {
                throw new IllegalArgumentException("two matching point pairs are required");
            }
            double fromX = 0;
            double fromY = 0;
            double toX = 0;
            double toY = 0;
            for (int i = 0; i < from.length; i++) {
                fromX += from[i][0];
                fromY += from[i][1];
                toX += to[i][0];
                toY += to[i][1];
            }
            fromX /= from.length;
            fromY /= from.length;
            toX /= from.length;
            toY /= from.length;
            double dot = 0;
            double cross = 0;
            for (int i = 0; i < from.length; i++) {
                double px = from[i][0] - fromX;
                double py = from[i][1] - fromY;
                double qx = to[i][0] - toX;
                double qy = to[i][1] - toY;
                dot += px * qx + py * qy;
                cross += px * qy - py * qx;
            }
            if (!(Math.hypot(dot, cross) > 1e-12)) {
                throw new IllegalArgumentException("rigid landmarks have no spatial extent");
            }
            double angle = Math.atan2(cross, dot);
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            return new Affine2D(c, -s, s, c,
                    toX - c * fromX + s * fromY,
                    toY - s * fromX - c * fromY);
        }

        private static double[] solve3(double[][] matrix, double[] rhs) {
            double[][] a = new double[3][4];
            for (int r = 0; r < 3; r++) {
                System.arraycopy(matrix[r], 0, a[r], 0, 3);
                a[r][3] = rhs[r];
            }
            for (int col = 0; col < 3; col++) {
                int pivot = col;
                for (int r = col + 1; r < 3; r++) {
                    if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
                }
                if (!(Math.abs(a[pivot][col]) > 1e-12)) {
                    throw new IllegalArgumentException("landmarks are collinear");
                }
                double[] swap = a[col];
                a[col] = a[pivot];
                a[pivot] = swap;
                double scale = a[col][col];
                for (int k = col; k < 4; k++) a[col][k] /= scale;
                for (int r = 0; r < 3; r++) {
                    if (r == col) continue;
                    double f = a[r][col];
                    for (int k = col; k < 4; k++) a[r][k] -= f * a[col][k];
                }
            }
            return new double[]{a[0][3], a[1][3], a[2][3]};
        }
    }

    /**
     * Equation (32) of the paper, evaluated over an explicit region V.
     *
     * <p>The difference map is formed from affine coefficients before applying it to each pixel.
     * That avoids subtracting two large, nearly equal mapped coordinates. The online mean also means
     * that a translation-only case, whose distance is identical at every pixel, remains bit-exactly
     * equal to the translation shortcut instead of accumulating rounding error N times.
     */
    public static double warpingIndex(Affine2D truth, Affine2D estimate,
                                      int width, int height, Region region) {
        int rw = Math.max(1, (int) Math.round(width * region.fraction));
        int rh = Math.max(1, (int) Math.round(height * region.fraction));
        int x0 = (width - rw) / 2;
        int y0 = (height - rh) / 2;
        double d00 = truth.a00 - estimate.a00;
        double d01 = truth.a01 - estimate.a01;
        double d10 = truth.a10 - estimate.a10;
        double d11 = truth.a11 - estimate.a11;
        double dtx = truth.tx - estimate.tx;
        double dty = truth.ty - estimate.ty;
        long n = 0;
        double mean = 0;
        for (int y = y0; y < y0 + rh; y++) {
            for (int x = x0; x < x0 + rw; x++) {
                double dx = d00 * x + d01 * y + dtx;
                double dy = d10 * x + d11 * y + dty;
                double distance = Math.hypot(dx, dy);
                n++;
                mean += (distance - mean) / n;
            }
        }
        return mean;
    }

    /** The existing controlled-metric shortcut, expressed for one translation-only transform. */
    public static double translationWarpingShortcut(double trueDx, double trueDy,
                                                     double estimatedDx, double estimatedDy) {
        return Math.hypot(estimatedDx - trueDx, estimatedDy - trueDy);
    }

    /** One deterministic half-transform and the square that the algorithms must recover. */
    public static final class Trial {
        public final int index;
        public final Transform half;
        public final Transform truth;

        Trial(int index, Transform half) {
            this.index = index;
            this.half = half;
            this.truth = half.then(half);
        }
    }

    public static List<Trial> trials(long seed, int count, boolean rotate) {
        Random random = new Random(seed);
        List<Trial> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double theta = rotate
                    ? Math.toRadians((2 * random.nextDouble() - 1) * HALF_ROTATION_RANGE_DEGREES)
                    : 0;
            double dx = (2 * random.nextDouble() - 1) * HALF_TRANSLATION_RANGE_PIXELS;
            double dy = (2 * random.nextDouble() - 1) * HALF_TRANSLATION_RANGE_PIXELS;
            out.add(new Trial(i, new Transform(dx, dy, theta)));
        }
        return Collections.unmodifiableList(out);
    }

    static final class Frames {
        final float[] test;
        final float[] reference;
        final int width;
        final int height;
        final Transform truth;

        Frames(float[] test, float[] reference, int width, int height, Transform truth) {
            this.test = test;
            this.reference = reference;
            this.width = width;
            this.height = height;
            this.truth = truth;
        }
    }

    /**
     * Apply the half-transform twice in opposite directions about the image centre.
     *
     * <p>The sampler looks through the supplied transform, so supplying {@code half} makes test
     * content move by {@code half.inverse()}, and supplying {@code half.inverse()} makes reference
     * content move by {@code half}. The content motion from test to reference is therefore
     * {@code half.then(half)}. Generation uses the paper's degree-7 interpolating B-spline with the
     * mirror-off-bounds convention used by the authors' TurboReg implementation.
     */
    static Frames generate(float[] base, int width, int height, Transform half) {
        double[] coefficients = spline7Coefficients(base, width, height);
        float[] test = spline7Warp(coefficients, width, height, half);
        float[] reference = spline7Warp(coefficients, width, height, half.inverse());
        return new Frames(test, reference, width, height, half.then(half));
    }

    /** Add separate deterministic white-Gaussian realizations after geometric generation. */
    static Frames addGaussianNoise(Frames clean, double sigma,
                                   long testSeed, long referenceSeed) {
        if (!(sigma >= 0) || !Double.isFinite(sigma)) {
            throw new IllegalArgumentException("noise sigma must be finite and non-negative");
        }
        return new Frames(addGaussianNoise(clean.test, sigma, testSeed),
                addGaussianNoise(clean.reference, sigma, referenceSeed),
                clean.width, clean.height, clean.truth);
    }

    private static float[] addGaussianNoise(float[] clean, double sigma, long seed) {
        Random random = new Random(seed);
        float[] noisy = new float[clean.length];
        for (int i = 0; i < clean.length; i++) {
            noisy[i] = (float) (clean[i] + sigma * random.nextGaussian());
        }
        return noisy;
    }

    /** Population variance, matching the variance ratio in equation (33). */
    static double sampleVariance(float[] values) {
        if (values.length == 0) return Double.NaN;
        double mean = 0;
        double m2 = 0;
        long n = 0;
        for (float value : values) {
            n++;
            double delta = value - mean;
            mean += delta / n;
            m2 += delta * (value - mean);
        }
        return m2 / n;
    }

    /** Population variance of the noise actually realized between two planes. */
    static double differenceVariance(float[] clean, float[] noisy) {
        if (clean.length != noisy.length || clean.length == 0) return Double.NaN;
        double mean = 0;
        double m2 = 0;
        long n = 0;
        for (int i = 0; i < clean.length; i++) {
            double value = noisy[i] - clean[i];
            n++;
            double delta = value - mean;
            mean += delta / n;
            m2 += delta * (value - mean);
        }
        return m2 / n;
    }

    static double noiseSigma(double signalVariance, double snrDb) {
        return Math.sqrt(signalVariance / Math.pow(10.0, snrDb / 10.0));
    }

    static double snrDb(double signalVariance, double noiseVariance) {
        return 10.0 * Math.log10(signalVariance / noiseVariance);
    }

    /** Stable, non-overlapping test/reference seeds for every trial and SNR level. */
    static long noiseSeed(int trial, int snrIndex, boolean reference) {
        long value = NOISE_RANDOM_SEED + 0x9e3779b97f4a7c15L * (trial + 1L);
        value ^= 0xbf58476d1ce4e5b9L * (snrIndex + 1L);
        if (reference) value ^= 0x94d049bb133111ebL;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /** Convert samples to separable degree-7 B-spline interpolation coefficients. */
    static double[] spline7Coefficients(float[] samples, int width, int height) {
        if (samples.length != width * height || width < 2 || height < 2) {
            throw new IllegalArgumentException("invalid spline image dimensions");
        }
        double[] coefficients = new double[samples.length];
        for (int i = 0; i < samples.length; i++) coefficients[i] = samples[i];
        double[] line = new double[Math.max(width, height)];
        for (int y = 0; y < height; y++) {
            System.arraycopy(coefficients, y * width, line, 0, width);
            samplesToSpline7Coefficients(line, width);
            System.arraycopy(line, 0, coefficients, y * width, width);
        }
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) line[y] = coefficients[y * width + x];
            samplesToSpline7Coefficients(line, height);
            for (int y = 0; y < height; y++) coefficients[y * width + x] = line[y];
        }
        return coefficients;
    }

    /** Apply the paper's near-sinc beta(7) generator to a centre-based rigid transform. */
    static float[] spline7Warp(double[] coefficients, int width, int height, Transform transform) {
        if (coefficients.length != width * height) {
            throw new IllegalArgumentException("invalid spline coefficient dimensions");
        }
        float[] out = new float[coefficients.length];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        IntStream.range(0, height).parallel().forEach(y -> {
            double[] point = new double[2];
            double[] xWeights = new double[8];
            double[] yWeights = new double[8];
            int[] xIndexes = new int[8];
            int[] yIndexes = new int[8];
            int row = y * width;
            for (int x = 0; x < width; x++) {
                transform.apply(x, y, cx, cy, point);
                int xStart = (int) Math.floor(point[0]) - 3;
                int yStart = (int) Math.floor(point[1]) - 3;
                spline7Weights(point[0], xWeights);
                spline7Weights(point[1], yWeights);
                for (int k = 0; k < 8; k++) {
                    xIndexes[k] = mirrorOffBounds(xStart + k, width);
                    yIndexes[k] = mirrorOffBounds(yStart + k, height);
                }
                double value = 0;
                for (int j = 0; j < 8; j++) {
                    double horizontal = 0;
                    int sourceRow = yIndexes[j] * width;
                    for (int i = 0; i < 8; i++) {
                        horizontal += xWeights[i] * coefficients[sourceRow + xIndexes[i]];
                    }
                    value += yWeights[j] * horizontal;
                }
                out[row + x] = (float) value;
            }
        });
        return out;
    }

    private static void samplesToSpline7Coefficients(double[] values, int length) {
        double lambda = 1.0;
        for (double pole : SPLINE_7_POLES) {
            lambda *= (1.0 - pole) * (1.0 - 1.0 / pole);
        }
        for (int i = 0; i < length; i++) values[i] *= lambda;
        for (double pole : SPLINE_7_POLES) {
            values[0] = initialCausalMirrorOffBounds(values, length, pole);
            for (int i = 1; i < length; i++) values[i] += pole * values[i - 1];
            values[length - 1] = pole * values[length - 1] / (pole - 1.0);
            for (int i = length - 2; i >= 0; i--) {
                values[i] = pole * (values[i + 1] - values[i]);
            }
        }
    }

    private static double initialCausalMirrorOffBounds(double[] values, int length, double pole) {
        double z1 = pole;
        double zn = Math.pow(pole, length);
        double sum = (1.0 + pole) * (values[0] + zn * values[length - 1]);
        zn *= zn;
        for (int i = 1; i < length - 1; i++) {
            z1 *= pole;
            zn /= pole;
            sum += (z1 + zn) * values[i];
        }
        return sum / (1.0 - Math.pow(pole, 2 * length));
    }

    /** Half-sample-symmetric extension: -1 maps to 0 and size maps to size-1. */
    private static int mirrorOffBounds(int coordinate, int size) {
        int period = 2 * size;
        int q = coordinate < 0 ? -1 - coordinate : coordinate;
        q %= period;
        return q < size ? q : period - 1 - q;
    }

    /** The eight beta(7) weights around one coordinate, sharing the eight seventh powers. */
    private static void spline7Weights(double coordinate, double[] weights) {
        double fraction = coordinate - Math.floor(coordinate);
        double[] powers = new double[8];
        for (int q = 0; q < 8; q++) {
            double value = fraction + q;
            double square = value * value;
            powers[q] = square * square * square * value;
        }
        for (int r = 0; r < 8; r++) {
            double sum = 0;
            int maximumJ = 7 - r;
            for (int j = 0; j <= maximumJ; j++) {
                double term = BINOMIAL_8[j] * powers[maximumJ - j];
                sum += (j & 1) == 0 ? term : -term;
            }
            weights[r] = sum / 5040.0;
        }
    }

    static Transform estimateInternal(PairEstimator.Kind kind, Frames frames, boolean fitRotation) {
        PairAligner.Options options = new PairAligner.Options();
        options.maxShift = 12;
        options.fitRotation = fitRotation;
        int levels = options.levelsFor(frames.width, frames.height);
        LogPlane test = LogPlane.of(frames.test, frames.width, frames.height, 1.0);
        LogPlane reference = LogPlane.of(frames.reference, frames.width, frames.height, 1.0);
        LogPlane[] a = kind.prefersLinearPyramid()
                ? test.linearPyramid(levels) : test.pyramid(levels);
        LogPlane[] b = kind.prefersLinearPyramid()
                ? reference.linearPyramid(levels) : reference.pyramid(levels);
        return kind.estimate(a, b, options).transform;
    }

    /** A deterministic positive, textured fixture used only for the Stage 3 rotation gate. */
    static float[] rotationFixture(int width, int height) {
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            double v = 2 * Math.PI * y / height;
            for (int x = 0; x < width; x++) {
                double u = 2 * Math.PI * x / width;
                double value = 2200
                        + 550 * Math.sin(3 * u + 0.3) * Math.cos(2 * v - 0.7)
                        + 370 * Math.sin(7 * u - 1.0) * Math.cos(5 * v + 0.2)
                        + 260 * Math.cos(11 * u + 0.8) * Math.sin(9 * v + 1.1)
                        + 150 * Math.sin(13 * u + 0.4 * v);
                out[y * width + x] = (float) value;
            }
        }
        return out;
    }

    public static final class RotationGate {
        public final Transform truth;
        public final Transform recovered;
        public final double rotationErrorDegrees;
        public final double translationErrorPixels;
        public final double zeroRotationErrorWithoutFit;
        public final double zeroRotationErrorWithFit;
        public final boolean passed;

        RotationGate(Transform truth, Transform recovered, double rotationErrorDegrees,
                     double translationErrorPixels, double zeroRotationErrorWithoutFit,
                     double zeroRotationErrorWithFit) {
            this.truth = truth;
            this.recovered = recovered;
            this.rotationErrorDegrees = rotationErrorDegrees;
            this.translationErrorPixels = translationErrorPixels;
            this.zeroRotationErrorWithoutFit = zeroRotationErrorWithoutFit;
            this.zeroRotationErrorWithFit = zeroRotationErrorWithFit;
            this.passed = rotationErrorDegrees <= ROTATION_GATE_TOLERANCE_DEGREES
                    && translationErrorPixels <= ROTATION_GATE_TRANSLATION_TOLERANCE_PIXELS
                    && zeroRotationErrorWithFit <= zeroRotationErrorWithoutFit
                    + ZERO_ROTATION_ALLOWED_DEGRADATION_PIXELS;
        }
    }

    public static RotationGate rotationGate() {
        int size = 192;
        float[] base = rotationFixture(size, size);
        Frames rotated = generate(base, size, size,
                new Transform(1.25, -0.75, Math.toRadians(1.5)));
        Transform recovered = estimateInternal(PairEstimator.Kind.LOG_RATIO_FIT, rotated, true);
        double rotationError = Math.abs(recovered.thetaDegrees() - rotated.truth.thetaDegrees());
        double translationError = Math.hypot(recovered.dx - rotated.truth.dx,
                recovered.dy - rotated.truth.dy);

        Frames translated = generate(base, size, size, new Transform(1.25, -0.75, 0));
        Transform without = estimateInternal(PairEstimator.Kind.LOG_RATIO_FIT, translated, false);
        Transform with = estimateInternal(PairEstimator.Kind.LOG_RATIO_FIT, translated, true);
        Affine2D translatedTruth = Affine2D.rigid(translated.truth, size, size);
        double withoutError = warpingIndex(translatedTruth, Affine2D.rigid(without, size, size),
                size, size, Region.FULL_FRAME);
        double withError = warpingIndex(translatedTruth, Affine2D.rigid(with, size, size),
                size, size, Region.FULL_FRAME);
        return new RotationGate(rotated.truth, recovered, rotationError, translationError,
                withoutError, withError);
    }

    static final class ReferenceImage {
        final String name;
        final float[] pixels;
        final int width;
        final int height;
        final String source;
        final String sha256;

        ReferenceImage(String name, float[] pixels, int width, int height,
                       String source, String sha256) {
            this.name = name;
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.source = source;
            this.sha256 = sha256;
        }
    }

    /** Extract the native 256-by-256 Fig. 3 raster from the authors' checksummed PostScript. */
    static ReferenceImage paperFigure3(Path postscript) throws IOException {
        byte[] file = Files.readAllBytes(postscript);
        String fileHash = sha256(file);
        if (!PAPER_POSTSCRIPT_SHA256.equals(fileHash)) {
            throw new IOException("official PostScript checksum mismatch: " + fileHash);
        }
        String text = new String(file, StandardCharsets.ISO_8859_1);
        String header = "256 256 8 256 [128 0 0 128 224 98] F T 1 F :f";
        int headerAt = text.indexOf(header);
        int start = headerAt < 0 ? -1 : text.indexOf(":k ", headerAt);
        int end = start < 0 ? -1 : text.indexOf("%ADOeod", start);
        if (headerAt < 0 || start < 0 || end < 0) {
            throw new IOException("could not locate native Fig. 3 raster in " + postscript);
        }
        start += 3;
        StringBuilder hex = new StringBuilder(256 * 256 * 2);
        for (int i = start; i < end; i++) {
            char value = text.charAt(i);
            if ((value >= '0' && value <= '9') || (value >= 'A' && value <= 'F')
                    || (value >= 'a' && value <= 'f')) {
                hex.append(value);
            }
        }
        if (hex.length() != 256 * 256 * 2) {
            throw new IOException("unexpected Fig. 3 raster length: " + hex.length());
        }
        byte[] raw = new byte[256 * 256];
        float[] pixels = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            raw[i] = (byte) ((high << 4) | low);
            // The PostScript's grayscale lookup table is inverted; apply it as the page does.
            pixels[i] = 255 - (raw[i] & 0xff);
        }
        String rasterHash = sha256(raw);
        if (!FIGURE_3_POSTSCRIPT_RASTER_SHA256.equals(rasterHash)) {
            throw new IOException("Fig. 3 raster checksum mismatch: " + rasterHash);
        }
        return new ReferenceImage("paper_figure_3_mri", pixels, 256, 256,
                postscript + " (native PostScript raster; file sha256=" + fileHash + ")",
                rasterHash);
    }

    /** The first plane from one controlled stack for each of the twenty development source series. */
    static List<ReferenceImage> developmentReferences(Path project) throws IOException {
        Path root = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        if (!Files.isDirectory(root)) throw new IOException("controlled benchmark is absent: " + root);
        Map<String, Path> onePerSeries = new LinkedHashMap<>();
        List<Path> inputs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName().toString().equals("00_input_uncorrected.tif"))
                    .forEach(inputs::add);
        }
        inputs.sort(Comparator.comparing(Path::toString));
        for (Path input : inputs) {
            Path relative = root.relativize(input);
            if (relative.getNameCount() < 5) continue;
            String series = relative.getName(1).toString();
            if (!onePerSeries.containsKey(series)) onePerSeries.put(series, input);
        }
        if (onePerSeries.size() != 20) {
            throw new IOException("expected 20 development series, found " + onePerSeries.size());
        }
        List<ReferenceImage> out = new ArrayList<>();
        for (Map.Entry<String, Path> entry : onePerSeries.entrySet()) {
            ImagePlus image = IJ.openImage(entry.getValue().toString());
            if (image == null) throw new IOException("could not open " + entry.getValue());
            try {
                ImageProcessor processor = image.getStack().getProcessor(1);
                int size = Math.min(DEVELOPMENT_CROP,
                        Math.min(processor.getWidth(), processor.getHeight()));
                int x = (processor.getWidth() - size) / 2;
                int y = (processor.getHeight() - size) / 2;
                ImageProcessor copy = processor.duplicate();
                copy.setRoi(x, y, size, size);
                float[] pixels = (float[]) copy.crop().convertToFloatProcessor().getPixels();
                out.add(new ReferenceImage(entry.getKey(), pixels, size, size,
                        entry.getValue().toString(), sha256(Files.readAllBytes(entry.getValue()))));
            } finally {
                image.close();
            }
        }
        return out;
    }

    /** TurboReg loaded reflectively, retaining the project's dependency-free production package. */
    static final class TurboReg implements AutoCloseable {
        private static final String SOURCE = "lr_thevenaz_source";
        private static final String TARGET = "lr_thevenaz_target";
        private static final double GOLDEN_RATIO = 0.5 * (Math.sqrt(5.0) - 1.0);

        private final URLClassLoader loader;
        private final Class<?> type;
        private final Method run;
        private final Method sourcePoints;
        private final Method targetPoints;
        private ImagePlus source;
        private ImagePlus target;
        private int width;
        private int height;

        TurboReg(Path jar) throws Exception {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    ThevenazProtocolBenchmark.class.getClassLoader());
            type = Class.forName("TurboReg_", true, loader);
            run = type.getMethod("run", String.class);
            sourcePoints = type.getMethod("getSourcePoints");
            targetPoints = type.getMethod("getTargetPoints");
        }

        Affine2D estimate(float[] test, float[] reference, int w, int h, TurboMode mode) {
            windows(w, h);
            // TurboReg aligns source onto target. Reference is source and test is target so its
            // returned target->source landmarks are the content motion test->reference.
            source.getProcessor().setPixels(reference.clone());
            target.getProcessor().setPixels(test.clone());
            source.updateAndDraw();
            target.updateAndDraw();
            double[][] initial = initialPoints(w, h, mode);
            StringBuilder options = new StringBuilder("-align")
                    .append(" -window ").append(SOURCE).append(" 0 0 ")
                    .append(w - 1).append(' ').append(h - 1)
                    .append(" -window ").append(TARGET).append(" 0 0 ")
                    .append(w - 1).append(' ').append(h - 1)
                    .append(" -").append(mode.option);
            for (int i = 0; i < mode.points; i++) {
                options.append(' ').append(format(initial[i][0]))
                        .append(' ').append(format(initial[i][1]))
                        .append(' ').append(format(initial[i][0]))
                        .append(' ').append(format(initial[i][1]));
            }
            options.append(" -hideOutput");
            try {
                Object plugin = type.getDeclaredConstructor().newInstance();
                run.invoke(plugin, options.toString());
                double[][] sourceLandmarks = (double[][]) sourcePoints.invoke(plugin);
                double[][] targetLandmarks = (double[][]) targetPoints.invoke(plugin);
                if (sourceLandmarks == null || targetLandmarks == null) {
                    throw new IllegalStateException("TurboReg returned no landmarks");
                }
                if (mode == TurboMode.TRANSLATION) {
                    return Affine2D.translation(
                            sourceLandmarks[0][0] - targetLandmarks[0][0],
                            sourceLandmarks[0][1] - targetLandmarks[0][1]);
                }
                if (mode == TurboMode.RIGID_BODY) {
                    return Affine2D.rigidFromPointPairs(targetLandmarks, sourceLandmarks);
                }
                return Affine2D.fromPointPairs(targetLandmarks, sourceLandmarks);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("TurboReg failed in " + mode + " mode", error);
            }
        }

        private static double[][] initialPoints(int width, int height, TurboMode mode) {
            double cx = Math.floor(0.5 * width);
            double cy = Math.floor(0.5 * height);
            if (mode == TurboMode.TRANSLATION) return new double[][]{{cx, cy}};
            double gxLow = Math.floor(0.25 * GOLDEN_RATIO * width);
            double gyLow = Math.floor(0.25 * GOLDEN_RATIO * height);
            double gxHigh = width - Math.ceil(0.25 * GOLDEN_RATIO * width);
            double gyHigh = height - Math.ceil(0.25 * GOLDEN_RATIO * height);
            if (mode == TurboMode.RIGID_BODY) {
                double gy = Math.ceil(0.25 * GOLDEN_RATIO * height);
                return new double[][]{{cx, cy}, {cx, gy}, {cx, height - gy}};
            }
            return new double[][]{{cx, gyLow}, {gxLow, gyHigh}, {gxHigh, gyHigh}};
        }

        private void windows(int w, int h) {
            if (source != null && width == w && height == h) return;
            closeWindows();
            width = w;
            height = h;
            source = new ImagePlus(SOURCE, new FloatProcessor(w, h, new float[w * h], null));
            target = new ImagePlus(TARGET, new FloatProcessor(w, h, new float[w * h], null));
            source.show();
            target.show();
        }

        private void closeWindows() {
            if (source != null) source.close();
            if (target != null) target.close();
            source = null;
            target = null;
        }

        @Override
        public void close() {
            closeWindows();
            try {
                loader.close();
            } catch (IOException ignored) {
                // Nothing useful can be done during benchmark shutdown.
            }
        }
    }

    static final class Measurement {
        final String image;
        final String arm;
        final int trial;
        final Affine2D estimate;
        final double error;

        Measurement(String image, String arm, int trial, Affine2D estimate, double error) {
            this.image = image;
            this.arm = arm;
            this.trial = trial;
            this.estimate = estimate;
            this.error = error;
        }
    }

    public static final class Summary {
        public final int n;
        public final double mean;
        public final double median;
        public final double worst;

        Summary(List<Double> values) {
            n = values.size();
            if (n == 0) {
                mean = median = worst = Double.NaN;
                return;
            }
            double sum = 0;
            for (double value : values) sum += value;
            mean = sum / n;
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            median = quantile(sorted, 0.5);
            worst = sorted.get(sorted.size() - 1);
        }

        private static double quantile(List<Double> sorted, double q) {
            if (sorted.size() == 1) return sorted.get(0);
            double position = q * (sorted.size() - 1);
            int lo = (int) Math.floor(position);
            int hi = (int) Math.ceil(position);
            double f = position - lo;
            return sorted.get(lo) + f * (sorted.get(hi) - sorted.get(lo));
        }
    }

    static final class RunState {
        final Path project;
        final Path output;
        final Path turboJar;
        final Path paperPdf;
        final Path paperPostscript;
        final int trialCount;
        final StringBuilder report = new StringBuilder();
        Region stageZeroRegion;
        boolean stageZeroPassed;
        boolean stageTwoClean;

        RunState(Path project, Path output, Path turboJar, Path paperPdf, Path paperPostscript,
                 int trialCount) {
            this.project = project;
            this.output = output;
            this.turboJar = turboJar;
            this.paperPdf = paperPdf;
            this.paperPostscript = paperPostscript;
            this.trialCount = trialCount;
        }
    }

    public static void main(String[] args) throws Exception {
        Path project = args.length > 0 ? Paths.get(args[0]).toAbsolutePath().normalize()
                : Paths.get("").toAbsolutePath().normalize();
        Path output = args.length > 1 ? Paths.get(args[1]).toAbsolutePath().normalize()
                : project.resolve("target/thevenaz-protocol/results");
        Path turboJar = args.length > 2 ? Paths.get(args[2]).toAbsolutePath().normalize()
                : project.resolve("target/thevenaz-protocol/deps/TurboReg_-2.0.0.jar");
        Path paperPdf = args.length > 3 ? Paths.get(args[3]).toAbsolutePath().normalize()
                : project.resolve("target/thevenaz-protocol/deps/thevenaz9801.pdf");
        Path paperPostscript = args.length > 4 ? Paths.get(args[4]).toAbsolutePath().normalize()
                : project.resolve("target/thevenaz-protocol/deps/thevenaz9801.ps");
        int count = Integer.getInteger("thevenaz.trials", DEFAULT_TRIALS);
        RunState state = new RunState(project, output, turboJar, paperPdf, paperPostscript, count);
        int status;
        try {
            status = execute(state);
        } catch (Throwable error) {
            Files.createDirectories(output);
            state.report.append("\n## Unhandled failure\n\n`")
                    .append(error.toString().replace("`", "'")).append("`\n");
            writeReport(state);
            error.printStackTrace(System.err);
            status = 1;
        }
        System.exit(status); // TurboReg/ImageJ leave AWT service threads behind.
    }

    static int execute(RunState state) throws Exception {
        Files.createDirectories(state.output);
        requireFile(state.paperPdf, "paper PDF");
        requireFile(state.paperPostscript, "paper PostScript");
        requireFile(state.turboJar, "TurboReg jar");
        ReferenceImage paperImage = paperFigure3(state.paperPostscript);
        writeManifest(state, paperImage);
        writeTransformations(state.output.resolve("transformations_rigid.csv"),
                trials(RANDOM_SEED, state.trialCount, true));
        writeTransformations(state.output.resolve("transformations_translation.csv"),
                trials(RANDOM_SEED, state.trialCount, false));

        state.report.append("# Thevenaz protocol benchmark\n\n")
                .append("Generated by `ThevenazProtocolBenchmark` with seed `")
                .append(RANDOM_SEED).append("` and ").append(state.trialCount)
                .append(" transformations per arm.\n\n")
                .append("The paper's headline value is an affine result. This project's Stage 2 ")
                .append("result is translation-restricted and must not be quoted against it.\n\n")
                .append("The control uses the native 256-by-256 Fig. 3 raster from the authors' ")
                .append("PostScript and the published degree-7 B-spline generator.\n\n");

        try (TurboReg turbo = new TurboReg(state.turboJar)) {
            if (!runStageZero(state, paperImage, turbo)) {
                writeReport(state);
                return 2;
            }
            if (!runStageTwo(state, paperImage, turbo)) {
                writeReport(state);
                return 3;
            }
            runStageThree(state, paperImage, turbo);
            if (!runStageFour(state, paperImage, turbo)) {
                writeReport(state);
                return 4;
            }
        }
        writeReport(state);
        return 0;
    }

    private static boolean runStageZero(RunState state, ReferenceImage reference, TurboReg turbo)
            throws IOException {
        System.out.println("Stage 0: TurboReg affine control on " + state.trialCount + " trials");
        Map<Region, List<Double>> errors = new EnumMap<>(Region.class);
        for (Region region : Region.values()) errors.put(region, new ArrayList<Double>());
        Path csv = state.output.resolve("stage0_affine_trials.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("trial,half_dx,half_dy,half_theta_degrees,true_a00,true_a01,true_a10,"
                    + "true_a11,true_tx,true_ty,estimate_a00,estimate_a01,estimate_a10,"
                    + "estimate_a11,estimate_tx,estimate_ty");
            for (Region region : Region.values()) writer.write(",warp_" + region.name().toLowerCase(Locale.ROOT));
            writer.newLine();
            for (Trial trial : trials(RANDOM_SEED, state.trialCount, true)) {
                Frames frames = generate(reference.pixels, reference.width, reference.height, trial.half);
                Affine2D truth = Affine2D.rigid(frames.truth, frames.width, frames.height);
                Affine2D estimate = turbo.estimate(frames.test, frames.reference,
                        frames.width, frames.height, TurboMode.AFFINE);
                writer.write(Integer.toString(trial.index));
                writer.write("," + format(trial.half.dx) + "," + format(trial.half.dy)
                        + "," + format(trial.half.thetaDegrees()));
                writeAffine(writer, truth);
                writeAffine(writer, estimate);
                for (Region region : Region.values()) {
                    double value = warpingIndex(truth, estimate, frames.width, frames.height, region);
                    errors.get(region).add(value);
                    writer.write("," + format(value));
                }
                writer.newLine();
                if ((trial.index + 1) % 10 == 0) {
                    System.out.println("  affine " + (trial.index + 1) + "/" + state.trialCount);
                }
            }
        }

        state.report.append("## Stage 0 - affine reproduction control\n\n")
                .append("Published Table III ML*3 final warping index: **")
                .append(format(PUBLISHED_ML_STAR_3_WARPING_INDEX))
                .append(" px**. Predeclared factor-of-two interval: **")
                .append(format(STAGE_ZERO_MIN)).append(" to ").append(format(STAGE_ZERO_MAX))
                .append(" px**.\n\n")
                .append("| Region V | n | Mean px | Median px | Worst px | Gate |\n")
                .append("|---|---:|---:|---:|---:|---|\n");
        for (Region region : Region.values()) {
            Summary summary = new Summary(errors.get(region));
            boolean pass = summary.mean >= STAGE_ZERO_MIN && summary.mean <= STAGE_ZERO_MAX;
            if (state.stageZeroRegion == null && pass) state.stageZeroRegion = region;
            state.report.append('|').append(region).append('|').append(summary.n).append('|')
                    .append(format(summary.mean)).append('|').append(format(summary.median)).append('|')
                    .append(format(summary.worst)).append('|').append(pass ? "PASS" : "FAIL")
                    .append("|\n");
        }
        state.stageZeroPassed = state.stageZeroRegion != null;
        if (state.stageZeroPassed) {
            state.report.append("\n**Stage 0 passed** using `").append(state.stageZeroRegion)
                    .append("`. Full-frame was evaluated first; central regions were the ")
                    .append("predeclared diagnostic for the paper's incompletely specified V.\n\n");
        } else {
            state.report.append("\n**Stage 0 failed. The protocol stops here.** No estimator from ")
                    .append("this project was run, because the TurboReg-affine control did not ")
                    .append("reproduce the published experiment within tolerance.\n\n");
        }
        return state.stageZeroPassed;
    }

    private static boolean runStageTwo(RunState state, ReferenceImage paperImage, TurboReg turbo)
            throws IOException {
        System.out.println("Stage 2: translation-restricted matched comparison");
        List<ReferenceImage> references = new ArrayList<>();
        references.add(paperImage);
        references.addAll(developmentReferences(state.project));
        List<Trial> trials = trials(RANDOM_SEED, state.trialCount, false);
        List<Measurement> measurements = new ArrayList<>();
        Path csv = state.output.resolve("stage2_translation_trials.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("image,trial,arm,true_dx,true_dy,estimated_dx,estimated_dy,warping_index_px");
            writer.newLine();
            for (int imageIndex = 0; imageIndex < references.size(); imageIndex++) {
                ReferenceImage reference = references.get(imageIndex);
                System.out.println("  image " + (imageIndex + 1) + "/" + references.size()
                        + ": " + reference.name);
                for (Trial trial : trials) {
                    Frames frames = generate(reference.pixels, reference.width, reference.height,
                            trial.half);
                    Affine2D truth = Affine2D.rigid(frames.truth, frames.width, frames.height);
                    addMeasurement(measurements, writer, reference.name, "DO_NOTHING", trial.index,
                            truth, Affine2D.identity(), frames.width, frames.height);
                    Affine2D turboEstimate = turbo.estimate(frames.test, frames.reference,
                            frames.width, frames.height, TurboMode.TRANSLATION);
                    addMeasurement(measurements, writer, reference.name, "TURBOREG_TRANSLATION",
                            trial.index, truth, turboEstimate, frames.width, frames.height);
                    for (PairEstimator.Kind kind : TRANSLATION_ESTIMATORS) {
                        Transform estimate = estimateInternal(kind, frames, false);
                        addMeasurement(measurements, writer, reference.name, kind.name(), trial.index,
                                truth, Affine2D.rigid(estimate, frames.width, frames.height),
                                frames.width, frames.height);
                    }
                }
            }
        }

        Map<String, List<Double>> groups = new LinkedHashMap<>();
        for (Measurement measurement : measurements) {
            add(groups, measurement.image + "\t" + measurement.arm, measurement.error);
            if (!measurement.image.startsWith("paper_figure_3")) {
                add(groups, "DEVELOPMENT_20_POOLED\t" + measurement.arm, measurement.error);
            }
        }
        writeSummaries(state.output.resolve("stage2_translation_summary.csv"), groups);
        state.report.append("## Stage 2 - matched translation comparison\n\n")
                .append("Rotation was fixed to zero. Every arm received the same ")
                .append(state.trialCount).append(" translations per image.\n\n")
                .append("### Paper Fig. 3 case study\n\n");
        appendSummaryTable(state.report, groups, paperImage.name);
        state.report.append("\n### Twenty development images, pooled\n\n");
        appendSummaryTable(state.report, groups, "DEVELOPMENT_20_POOLED");

        int expectedArms = TRANSLATION_ESTIMATORS.length + 2;
        boolean countsClean = measurements.size()
                == references.size() * state.trialCount * expectedArms;
        Summary doNothing = summary(groups, "DEVELOPMENT_20_POOLED\tDO_NOTHING");
        boolean doNothingClean = doNothing.n == 20 * state.trialCount && doNothing.mean > 2.0;
        boolean finite = true;
        for (Measurement measurement : measurements) {
            if (!Double.isFinite(measurement.error)) finite = false;
        }
        state.stageTwoClean = countsClean && doNothingClean && finite;
        state.report.append("\nEvidence gate: **").append(state.stageTwoClean ? "PASS" : "FAIL")
                .append("**. Counts correct: ").append(countsClean)
                .append("; all values finite: ").append(finite)
                .append("; do-nothing mean > 2 px: ").append(doNothingClean).append(".\n\n");
        return state.stageTwoClean;
    }

    private static void runStageThree(RunState state, ReferenceImage reference, TurboReg turbo)
            throws IOException {
        System.out.println("Stage 3: rotation gate");
        RotationGate gate = rotationGate();
        try (BufferedWriter writer = Files.newBufferedWriter(
                state.output.resolve("stage3_rotation_gate.csv"), StandardCharsets.UTF_8)) {
            writer.write("truth_dx,truth_dy,truth_theta_degrees,estimate_dx,estimate_dy,"
                    + "estimate_theta_degrees,rotation_error_degrees,translation_error_px,"
                    + "zero_rotation_error_without_fit_px,zero_rotation_error_with_fit_px,passed");
            writer.newLine();
            writer.write(format(gate.truth.dx) + "," + format(gate.truth.dy) + ","
                    + format(gate.truth.thetaDegrees()) + "," + format(gate.recovered.dx) + ","
                    + format(gate.recovered.dy) + "," + format(gate.recovered.thetaDegrees()) + ","
                    + format(gate.rotationErrorDegrees) + "," + format(gate.translationErrorPixels)
                    + "," + format(gate.zeroRotationErrorWithoutFit) + ","
                    + format(gate.zeroRotationErrorWithFit) + "," + gate.passed);
            writer.newLine();
        }
        state.report.append("## Stage 3 - rigid body\n\n")
                .append("Synthetic rotation gate: rotation error ")
                .append(format(gate.rotationErrorDegrees)).append(" degrees (limit ")
                .append(format(ROTATION_GATE_TOLERANCE_DEGREES)).append("), translation error ")
                .append(format(gate.translationErrorPixels)).append(" px (limit ")
                .append(format(ROTATION_GATE_TRANSLATION_TOLERANCE_PIXELS)).append("). ")
                .append("On zero rotation, fit off/on errors were ")
                .append(format(gate.zeroRotationErrorWithoutFit)).append(" / ")
                .append(format(gate.zeroRotationErrorWithFit)).append(" px, with at most ")
                .append(format(ZERO_ROTATION_ALLOWED_DEGRADATION_PIXELS))
                .append(" px degradation allowed.\n\n");
        if (!gate.passed) {
            state.report.append("**Stage 3 stopped at its gate.** The shipped-off rotation path is ")
                    .append("not sound enough for a method comparison; no rigid comparison was run.\n\n");
            return;
        }

        System.out.println("Stage 3 gate passed; running rigid comparison");
        List<Trial> trials = trials(RANDOM_SEED, state.trialCount, true);
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        Path csv = state.output.resolve("stage3_rigid_trials.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("trial,arm,true_dx,true_dy,true_theta_degrees,estimated_a00,estimated_a01,"
                    + "estimated_a10,estimated_a11,estimated_tx,estimated_ty,warping_index_px");
            writer.newLine();
            for (Trial trial : trials) {
                Frames frames = generate(reference.pixels, reference.width, reference.height,
                        trial.half);
                Affine2D truth = Affine2D.rigid(frames.truth, frames.width, frames.height);
                Affine2D turboEstimate = turbo.estimate(frames.test, frames.reference,
                        frames.width, frames.height, TurboMode.RIGID_BODY);
                writeRigidMeasurement(writer, groups, trial, "TURBOREG_RIGID_BODY", truth,
                        turboEstimate, frames.width, frames.height);
                Transform local = estimateInternal(PairEstimator.Kind.LOG_RATIO_FIT, frames, true);
                writeRigidMeasurement(writer, groups, trial, "LOG_RATIO_FIT_ROTATION", truth,
                        Affine2D.rigid(local, frames.width, frames.height),
                        frames.width, frames.height);
            }
        }
        writeSummaries(state.output.resolve("stage3_rigid_summary.csv"), groups);
        state.report.append("**Stage 3 gate passed.** Full-range rigid results on paper Fig. 3:\n\n")
                .append("| Arm | n | Mean px | Median px | Worst px |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (Map.Entry<String, List<Double>> entry : groups.entrySet()) {
            Summary summary = new Summary(entry.getValue());
            appendSummaryRow(state.report, entry.getKey(), summary);
        }
        state.report.append('\n');
    }

    private static boolean runStageFour(RunState state, ReferenceImage reference, TurboReg turbo)
            throws IOException {
        System.out.println("Stage 4: Fig. 5 Gaussian-noise sweep");
        List<Trial> trials = trials(RANDOM_SEED, state.trialCount, false);
        double signalVariance = sampleVariance(reference.pixels);
        Map<String, List<Double>> errors = new LinkedHashMap<>();
        Map<String, List<Double>> runtimes = new LinkedHashMap<>();
        Map<String, List<Double>> measuredSnrs = new LinkedHashMap<>();
        int rows = 0;

        Path csv = state.output.resolve("stage4_noise_trials.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("target_snr_db,trial,test_noise_seed,reference_noise_seed,"
                    + "measured_test_snr_db,measured_reference_snr_db,arm,true_dx,true_dy,"
                    + "estimated_dx,estimated_dy,warping_index_px,runtime_ms");
            writer.newLine();
            for (Trial trial : trials) {
                Frames clean = generate(reference.pixels, reference.width, reference.height,
                        trial.half);
                Affine2D truth = Affine2D.rigid(clean.truth, clean.width, clean.height);
                for (int snrIndex = 0; snrIndex < NOISE_SNR_DB.length; snrIndex++) {
                    double targetSnr = NOISE_SNR_DB[snrIndex];
                    double sigma = noiseSigma(signalVariance, targetSnr);
                    long testSeed = noiseSeed(trial.index, snrIndex, false);
                    long referenceSeed = noiseSeed(trial.index, snrIndex, true);
                    Frames noisy = addGaussianNoise(clean, sigma, testSeed, referenceSeed);
                    double measuredTestSnr = snrDb(signalVariance,
                            differenceVariance(clean.test, noisy.test));
                    double measuredReferenceSnr = snrDb(signalVariance,
                            differenceVariance(clean.reference, noisy.reference));
                    String snrKey = Integer.toString((int) targetSnr);
                    add(measuredSnrs, snrKey, measuredTestSnr);
                    add(measuredSnrs, snrKey, measuredReferenceSnr);

                    writeNoiseMeasurement(writer, errors, runtimes, targetSnr, trial.index,
                            testSeed, referenceSeed, measuredTestSnr, measuredReferenceSnr,
                            "DO_NOTHING", truth, Affine2D.identity(), clean.width, clean.height, 0);
                    rows++;

                    long started = System.nanoTime();
                    Affine2D turboEstimate = turbo.estimate(noisy.test, noisy.reference,
                            noisy.width, noisy.height, TurboMode.TRANSLATION);
                    double runtimeMs = (System.nanoTime() - started) / 1.0e6;
                    writeNoiseMeasurement(writer, errors, runtimes, targetSnr, trial.index,
                            testSeed, referenceSeed, measuredTestSnr, measuredReferenceSnr,
                            "TURBOREG_TRANSLATION", truth, turboEstimate,
                            clean.width, clean.height, runtimeMs);
                    rows++;

                    for (PairEstimator.Kind kind : TRANSLATION_ESTIMATORS) {
                        started = System.nanoTime();
                        Transform estimate = estimateInternal(kind, noisy, false);
                        runtimeMs = (System.nanoTime() - started) / 1.0e6;
                        writeNoiseMeasurement(writer, errors, runtimes, targetSnr, trial.index,
                                testSeed, referenceSeed, measuredTestSnr, measuredReferenceSnr,
                                kind.name(), truth,
                                Affine2D.rigid(estimate, clean.width, clean.height),
                                clean.width, clean.height, runtimeMs);
                        rows++;
                    }
                }
                if ((trial.index + 1) % 10 == 0) {
                    System.out.println("  noise " + (trial.index + 1) + "/" + state.trialCount);
                }
            }
        }

        writeNoiseSummaries(state.output.resolve("stage4_noise_summary.csv"),
                errors, runtimes, measuredSnrs);
        state.report.append("## Stage 4 - Gaussian-noise sweep\n\n")
                .append("Independent white Gaussian noise was added to both generated Fig. 3 ")
                .append("planes at the six SNR levels in paper Fig. 5. Following equation (33), ")
                .append("noise variance was set from the variance of the original Fig. 3 signal. ")
                .append("This remains a translation-restricted comparison; it is not a direct ")
                .append("reproduction of the paper's affine ML* curve. Runtime is indicative ")
                .append("wall time per fit, not the paper's convergence-level count.\n\n")
                .append("| SNR dB | Arm | n | Mean px | Median px | Worst px | Median ms |\n")
                .append("|---:|---|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, List<Double>> entry : errors.entrySet()) {
            int tab = entry.getKey().indexOf('\t');
            String snr = entry.getKey().substring(0, tab);
            String arm = entry.getKey().substring(tab + 1);
            Summary errorSummary = new Summary(entry.getValue());
            Summary runtimeSummary = summary(runtimes, entry.getKey());
            state.report.append('|').append(snr).append('|').append(arm).append('|')
                    .append(errorSummary.n).append('|').append(format(errorSummary.mean)).append('|')
                    .append(format(errorSummary.median)).append('|').append(format(errorSummary.worst))
                    .append('|').append(format(runtimeSummary.median)).append("|\n");
        }

        int expectedArms = TRANSLATION_ESTIMATORS.length + 2;
        boolean countsClean = rows == NOISE_SNR_DB.length * state.trialCount * expectedArms;
        boolean finite = allFinite(errors) && allFinite(runtimes) && allFinite(measuredSnrs);
        boolean snrClean = true;
        double doNothingMin = Double.POSITIVE_INFINITY;
        double doNothingMax = Double.NEGATIVE_INFINITY;
        for (double targetSnr : NOISE_SNR_DB) {
            String key = Integer.toString((int) targetSnr);
            Summary actual = summary(measuredSnrs, key);
            snrClean &= actual.n == 2 * state.trialCount
                    && Math.abs(actual.mean - targetSnr) <= 0.05;
            double control = summary(errors, key + "\tDO_NOTHING").mean;
            doNothingMin = Math.min(doNothingMin, control);
            doNothingMax = Math.max(doNothingMax, control);
        }
        boolean controlClean = doNothingMax - doNothingMin <= 1e-12;
        boolean passed = countsClean && finite && snrClean && controlClean;
        state.report.append("\nEvidence gate: **").append(passed ? "PASS" : "FAIL")
                .append("**. Counts correct: ").append(countsClean)
                .append("; all values finite: ").append(finite)
                .append("; mean realized SNR within 0.05 dB: ").append(snrClean)
                .append("; do-nothing geometry invariant across noise levels: ")
                .append(controlClean).append(".\n\n");
        return passed;
    }

    private static void writeNoiseMeasurement(BufferedWriter writer,
                                              Map<String, List<Double>> errors,
                                              Map<String, List<Double>> runtimes,
                                              double targetSnr, int trial,
                                              long testSeed, long referenceSeed,
                                              double measuredTestSnr,
                                              double measuredReferenceSnr,
                                              String arm, Affine2D truth, Affine2D estimate,
                                              int width, int height, double runtimeMs)
            throws IOException {
        double error = warpingIndex(truth, estimate, width, height, Region.FULL_FRAME);
        String key = Integer.toString((int) targetSnr) + "\t" + arm;
        add(errors, key, error);
        add(runtimes, key, runtimeMs);
        writer.write(format(targetSnr) + "," + trial + "," + testSeed + "," + referenceSeed
                + "," + format(measuredTestSnr) + "," + format(measuredReferenceSnr) + ","
                + arm + "," + format(truth.tx) + "," + format(truth.ty) + ","
                + format(estimate.tx) + "," + format(estimate.ty) + "," + format(error)
                + "," + format(runtimeMs));
        writer.newLine();
    }

    private static boolean allFinite(Map<String, List<Double>> groups) {
        for (List<Double> values : groups.values()) {
            for (double value : values) if (!Double.isFinite(value)) return false;
        }
        return true;
    }

    private static void writeNoiseSummaries(Path path, Map<String, List<Double>> errors,
                                            Map<String, List<Double>> runtimes,
                                            Map<String, List<Double>> measuredSnrs)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("target_snr_db,mean_measured_snr_db,arm,n,mean_warping_index_px,"
                    + "median_warping_index_px,worst_warping_index_px,mean_runtime_ms,"
                    + "median_runtime_ms,worst_runtime_ms");
            writer.newLine();
            for (Map.Entry<String, List<Double>> entry : errors.entrySet()) {
                int tab = entry.getKey().indexOf('\t');
                String snr = entry.getKey().substring(0, tab);
                String arm = entry.getKey().substring(tab + 1);
                Summary errorSummary = new Summary(entry.getValue());
                Summary runtimeSummary = summary(runtimes, entry.getKey());
                Summary snrSummary = summary(measuredSnrs, snr);
                writer.write(snr + "," + format(snrSummary.mean) + "," + arm + ","
                        + errorSummary.n + "," + format(errorSummary.mean) + ","
                        + format(errorSummary.median) + "," + format(errorSummary.worst) + ","
                        + format(runtimeSummary.mean) + "," + format(runtimeSummary.median) + ","
                        + format(runtimeSummary.worst));
                writer.newLine();
            }
        }
    }

    private static void addMeasurement(List<Measurement> measurements, BufferedWriter writer,
                                       String image, String arm, int trial, Affine2D truth,
                                       Affine2D estimate, int width, int height) throws IOException {
        double error = warpingIndex(truth, estimate, width, height, Region.FULL_FRAME);
        measurements.add(new Measurement(image, arm, trial, estimate, error));
        writer.write(csv(image) + "," + trial + "," + arm + "," + format(truth.tx) + ","
                + format(truth.ty) + "," + format(estimate.tx) + "," + format(estimate.ty)
                + "," + format(error));
        writer.newLine();
    }

    private static void writeRigidMeasurement(BufferedWriter writer, Map<String, List<Double>> groups,
                                              Trial trial, String arm, Affine2D truth,
                                              Affine2D estimate, int width, int height)
            throws IOException {
        double error = warpingIndex(truth, estimate, width, height, Region.FULL_FRAME);
        add(groups, arm, error);
        writer.write(trial.index + "," + arm + "," + format(trial.truth.dx) + ","
                + format(trial.truth.dy) + "," + format(trial.truth.thetaDegrees()));
        writeAffine(writer, estimate);
        writer.write("," + format(error));
        writer.newLine();
    }

    private static void writeAffine(BufferedWriter writer, Affine2D affine) throws IOException {
        writer.write("," + format(affine.a00) + "," + format(affine.a01)
                + "," + format(affine.a10) + "," + format(affine.a11)
                + "," + format(affine.tx) + "," + format(affine.ty));
    }

    private static void add(Map<String, List<Double>> groups, String key, double value) {
        List<Double> values = groups.get(key);
        if (values == null) {
            values = new ArrayList<>();
            groups.put(key, values);
        }
        values.add(value);
    }

    private static Summary summary(Map<String, List<Double>> groups, String key) {
        List<Double> values = groups.get(key);
        return new Summary(values == null ? Collections.<Double>emptyList() : values);
    }

    private static void appendSummaryTable(StringBuilder report, Map<String, List<Double>> groups,
                                           String scope) {
        report.append("| Arm | n | Mean px | Median px | Worst px |\n")
                .append("|---|---:|---:|---:|---:|\n");
        String prefix = scope + "\t";
        for (Map.Entry<String, List<Double>> entry : groups.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            appendSummaryRow(report, entry.getKey().substring(prefix.length()),
                    new Summary(entry.getValue()));
        }
    }

    private static void appendSummaryRow(StringBuilder report, String arm, Summary summary) {
        report.append('|').append(arm).append('|').append(summary.n).append('|')
                .append(format(summary.mean)).append('|').append(format(summary.median)).append('|')
                .append(format(summary.worst)).append("|\n");
    }

    private static void writeSummaries(Path path, Map<String, List<Double>> groups)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("scope,arm,n,mean_warping_index_px,median_warping_index_px,worst_warping_index_px");
            writer.newLine();
            for (Map.Entry<String, List<Double>> entry : groups.entrySet()) {
                int tab = entry.getKey().indexOf('\t');
                String scope = tab < 0 ? "paper_figure_3_mri" : entry.getKey().substring(0, tab);
                String arm = tab < 0 ? entry.getKey() : entry.getKey().substring(tab + 1);
                Summary summary = new Summary(entry.getValue());
                writer.write(csv(scope) + "," + csv(arm) + "," + summary.n + ","
                        + format(summary.mean) + "," + format(summary.median) + ","
                        + format(summary.worst));
                writer.newLine();
            }
        }
    }

    private static void writeTransformations(Path path, List<Trial> trials) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("seed,trial,half_dx,half_dy,half_theta_radians,half_theta_degrees,"
                    + "true_dx,true_dy,true_theta_radians,true_theta_degrees");
            writer.newLine();
            for (Trial trial : trials) {
                writer.write(RANDOM_SEED + "," + trial.index + "," + format(trial.half.dx) + ","
                        + format(trial.half.dy) + "," + format(trial.half.theta) + ","
                        + format(trial.half.thetaDegrees()) + "," + format(trial.truth.dx) + ","
                        + format(trial.truth.dy) + "," + format(trial.truth.theta) + ","
                        + format(trial.truth.thetaDegrees()));
                writer.newLine();
            }
        }
    }

    private static void writeManifest(RunState state, ReferenceImage paperImage) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("paper_url=").append(PAPER_URL).append('\n')
                .append("paper_doi=10.1109/83.650848\n")
                .append("paper_expected_sha256=").append(PAPER_SHA256).append('\n')
                .append("paper_actual_sha256=")
                .append(sha256(Files.readAllBytes(state.paperPdf))).append('\n')
                .append("paper_postscript_url=").append(PAPER_POSTSCRIPT_URL).append('\n')
                .append("paper_postscript_expected_sha256=")
                .append(PAPER_POSTSCRIPT_SHA256).append('\n')
                .append("paper_postscript_actual_sha256=")
                .append(sha256(Files.readAllBytes(state.paperPostscript))).append('\n')
                .append("figure3_pdf_image_sha256=")
                .append(FIGURE_3_PDF_IMAGE_SHA256).append('\n')
                .append("figure3_expected_sha256=")
                .append(FIGURE_3_POSTSCRIPT_RASTER_SHA256).append('\n')
                .append("figure3_actual_sha256=").append(paperImage.sha256).append('\n')
                .append("figure3_dimensions=").append(paperImage.width).append('x')
                .append(paperImage.height).append('\n')
                .append("turboreg_jar=").append(state.turboJar).append('\n')
                .append("turboreg_sha256=")
                .append(sha256(Files.readAllBytes(state.turboJar))).append('\n')
                .append("published_ml_star_3_warping_index_px=")
                .append(PUBLISHED_ML_STAR_3_WARPING_INDEX).append('\n')
                .append("stage0_factor_tolerance=").append(STAGE_ZERO_FACTOR_TOLERANCE).append('\n')
                .append("random_seed=").append(RANDOM_SEED).append('\n')
                .append("noise_random_seed=").append(NOISE_RANDOM_SEED).append('\n')
                .append("noise_snr_db=25,20,15,10,5,0\n")
                .append("noise_model=independent white Gaussian noise after image generation\n")
                .append("noise_signal_variance_source=original Fig. 3 raster\n")
                .append("trials_per_arm=").append(state.trialCount).append('\n')
                .append("half_rotation_range_degrees=+-").append(HALF_ROTATION_RANGE_DEGREES).append('\n')
                .append("half_translation_range_pixels=+-").append(HALF_TRANSLATION_RANGE_PIXELS).append('\n')
                .append("generator_resampler=interpolating cardinal B-spline degree 7 (beta7)\n")
                .append("generator_boundary=mirror off bounds (half-sample symmetric)\n")
                .append("paper_used_beta7_generator=true\n")
                .append("known_protocol_departures=none in image dimensions or generator order\n")
                .append("java_version=").append(System.getProperty("java.version")).append('\n')
                .append("os=").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n');
        Files.write(state.output.resolve("protocol_manifest.properties"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeReport(RunState state) throws IOException {
        Files.write(state.output.resolve("REPORT.md"),
                state.report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException(label + " not found: " + path);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte value : hash) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
