/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import java.util.Arrays;

/**
 * Chooses the recording-level pixel-evidence rule from the pixels themselves.
 *
 * <p>The selector has no modality input. It first asks whether the field is sparse from the first
 * frame's robust intensity distribution. Sparse fields use edges that clear the raw-noise estimate in
 * both frames. A dense field gets one gain-invariant phase-correlation alignment, after which the tail
 * of the aligned log residual distinguishes locally moving structure from a stable field. Moving dense
 * fields use ordinary gradient support; stable dense fields exclude the brightest tenth. A manually
 * supplied intensity band is authoritative and disables all automatic band selection.
 *
 * <p>The constants are the frozen values measured by benchmark version 1. They remain public so a
 * settings report can state the rule exactly. They must be retuned only on Development and Validation
 * data, never on the locked benchmark version 2 Test set.
 */
public final class AutomaticInformationSelector {

    /** Below this robust-range position, most pixels are background rather than field texture. */
    public static final double SPARSE_BOUNDARY = 0.50;
    /** Above this q99/q95 residual ratio, a dense field contains locally moving structure. */
    public static final double MOVING_TAIL_BOUNDARY = 1.55;
    /** Frozen information band for a dense stable field. */
    public static final double STABLE_BRIGHTEST_CEILING_PERCENTILE = 90.0;

    /** The concrete branch selected for one recording. */
    public enum Choice {
        /** Preserve the intensity band the caller supplied and use ordinary gradient evidence. */
        EXPLICIT_INTENSITY_BAND,
        /** Sparse field: require a significant raw-noise edge in both mapped frames. */
        SPARSE_MUTUAL_NOISE_EDGES,
        /** Dense field with local change: use ordinary gradient evidence. */
        MOVING_STANDARD_GRADIENT,
        /** Dense stable field: discard its brightest tenth as low-information support. */
        STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT
    }

    /** The decision and the evidence that produced it, suitable for a user-visible settings report. */
    public static final class Result {
        public final Choice choice;
        /** Median position inside the first frame's first-to-99th-percentile intensity range. */
        public final double sparseScore;
        /** Share beyond Tukey's residual threshold after the pilot alignment, or NaN if not needed. */
        public final double temporalOutlierFraction;
        /** 99th/95th percentile of scaled absolute residuals, or NaN if not needed. */
        public final double movingTailRatio;
        /** True when a caller-supplied floor or ceiling prevented automatic band selection. */
        public final boolean explicitIntensityBand;

        Result(Choice choice, double sparseScore, double temporalOutlierFraction,
               double movingTailRatio, boolean explicitIntensityBand) {
            this.choice = choice;
            this.sparseScore = sparseScore;
            this.temporalOutlierFraction = temporalOutlierFraction;
            this.movingTailRatio = movingTailRatio;
            this.explicitIntensityBand = explicitIntensityBand;
        }

        /** Apply only the pixel-evidence part of this decision to registration options. */
        public void applyTo(Registration.Options options) {
            if (options == null) throw new IllegalArgumentException("options is null");
            if (choice == Choice.SPARSE_MUTUAL_NOISE_EDGES) {
                options.aligner.support = PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT;
                options.aligner.gradientFraction = 0.5;
                // The sparse branch's frozen compute budget is part of the measured rule.
                options.aligner.maxIterations = 12;
                options.aligner.maxSamples = 50_000;
            } else {
                options.aligner.support = PairAligner.PixelSupport.GRADIENT;
                options.aligner.gradientFraction = 0.5;
            }
            if (choice == Choice.STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT) {
                options.saturationPercentile = STABLE_BRIGHTEST_CEILING_PERCENTILE;
            }
        }

        @Override
        public String toString() {
            return choice + " (sparse=" + sparseScore + ", movingTail=" + movingTailRatio + ")";
        }
    }

    private static final class TemporalEvidence {
        final double outlierFraction;
        final double tailRatio;

        TemporalEvidence(double outlierFraction, double tailRatio) {
            this.outlierFraction = outlierFraction;
            this.tailRatio = tailRatio;
        }
    }

    private AutomaticInformationSelector() {
    }

    /** Select from the first two frames supplied by the production frame boundary. */
    public static Result select(FrameSource source, double epsilon,
                                boolean explicitIntensityBand) {
        if (source == null) throw new IllegalArgumentException("source is null");
        if (source.count() < 1) throw new IllegalArgumentException("source has no frames");
        float[] first = source.plane(0);
        float[] second = source.count() > 1 ? source.plane(1) : first;
        return select(first, second, source.width(), source.height(), epsilon,
                explicitIntensityBand);
    }

    /** Select from two explicit planes; used by the benchmark and by headless callers. */
    public static Result select(float[] first, float[] second, int width, int height,
                                double epsilon, boolean explicitIntensityBand) {
        int expected = width * height;
        if (width < 1 || height < 1) throw new IllegalArgumentException("invalid dimensions");
        if (first == null || first.length != expected) {
            throw new IllegalArgumentException("first plane length does not match dimensions");
        }
        if (second == null || second.length != expected) {
            throw new IllegalArgumentException("second plane length does not match dimensions");
        }
        if (!(epsilon > 0) || Double.isInfinite(epsilon)) {
            throw new IllegalArgumentException("epsilon must be finite and positive");
        }

        double sparse = sparseFieldScore(first);
        if (explicitIntensityBand) {
            return new Result(Choice.EXPLICIT_INTENSITY_BAND, sparse,
                    Double.NaN, Double.NaN, true);
        }
        if (sparse < SPARSE_BOUNDARY) {
            return new Result(Choice.SPARSE_MUTUAL_NOISE_EDGES, sparse,
                    Double.NaN, Double.NaN, false);
        }
        TemporalEvidence temporal = temporalEvidence(first, second, width, height, epsilon);
        Choice choice = temporal.tailRatio > MOVING_TAIL_BOUNDARY
                ? Choice.MOVING_STANDARD_GRADIENT
                : Choice.STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT;
        return new Result(choice, sparse, temporal.outlierFraction, temporal.tailRatio, false);
    }

    /** Pure boundary decision used to pin and audit the declared automatic rule. */
    public static Choice choose(double sparseScore, double movingTailRatio,
                                boolean explicitIntensityBand) {
        if (explicitIntensityBand) return Choice.EXPLICIT_INTENSITY_BAND;
        if (sparseScore < SPARSE_BOUNDARY) return Choice.SPARSE_MUTUAL_NOISE_EDGES;
        return movingTailRatio > MOVING_TAIL_BOUNDARY
                ? Choice.MOVING_STANDARD_GRADIENT
                : Choice.STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT;
    }

    /** Median position within the robust intensity range; near zero means background-dominated. */
    static double sparseFieldScore(float[] frame) {
        float[] finite = new float[frame.length];
        int n = 0;
        for (float value : frame) if (Float.isFinite(value)) finite[n++] = value;
        if (n == 0) return Double.NaN;
        Arrays.sort(finite, 0, n);
        double p1 = finite[(int) Math.round(0.01 * (n - 1))];
        double p50 = finite[n / 2];
        double p99 = finite[(int) Math.round(0.99 * (n - 1))];
        return (p50 - p1) / Math.max(1e-9, p99 - p1);
    }

    /** Residual-tail evidence after one fast, gain-invariant shift estimate. */
    private static TemporalEvidence temporalEvidence(float[] first, float[] second,
                                                       int width, int height, double epsilon) {
        double[] shift = PhaseCorrelation.shift(first, second, width, height, true);
        double[] residual = new double[width * height];
        double invLn2 = 1.0 / Math.log(2.0);
        int n = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double tx = x + shift[0];
                double ty = y + shift[1];
                if (!(tx >= 0 && ty >= 0 && tx <= width - 1 && ty <= height - 1)) continue;
                int sourceIndex = y * width + x;
                if (!Float.isFinite(first[sourceIndex])) continue;
                int x0 = (int) tx;
                int y0 = (int) ty;
                int x1 = Math.min(width - 1, x0 + 1);
                int y1 = Math.min(height - 1, y0 + 1);
                float v00 = second[y0 * width + x0];
                float v10 = second[y0 * width + x1];
                float v01 = second[y1 * width + x0];
                float v11 = second[y1 * width + x1];
                if (!(Float.isFinite(v00) && Float.isFinite(v10)
                        && Float.isFinite(v01) && Float.isFinite(v11))) continue;
                double fx = tx - x0;
                double fy = ty - y0;
                double top = v00 * (1 - fx) + v10 * fx;
                double bottom = v01 * (1 - fx) + v11 * fx;
                double target = top * (1 - fy) + bottom * fy;
                double a = first[sourceIndex] + epsilon;
                double b = target + epsilon;
                if (!(a > 0 && b > 0)) continue;
                residual[n++] = (Math.log(b) - Math.log(a)) * invLn2;
            }
        }
        if (n == 0) return new TemporalEvidence(Double.NaN, Double.NaN);
        Arrays.sort(residual, 0, n);
        double gain = residual[n / 2];
        double[] absolute = new double[n];
        for (int i = 0; i < n; i++) absolute[i] = Math.abs(residual[i] - gain);
        Arrays.sort(absolute);
        double scale = Math.max(1e-4, 1.4826 * absolute[n / 2]);
        double threshold = RobustNorm.TUKEY.threshold(scale);
        int outliers = 0;
        for (double value : absolute) if (value > threshold) outliers++;
        double q95 = absolute[(int) (0.95 * (n - 1))] / scale;
        double q99 = absolute[(int) (0.99 * (n - 1))] / scale;
        return new TemporalEvidence(outliers / (double) n, q99 / Math.max(1e-9, q95));
    }
}
