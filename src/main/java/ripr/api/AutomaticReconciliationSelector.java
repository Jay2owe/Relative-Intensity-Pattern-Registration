/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.PairUncertainty;
import ripr.core.Reconciler;
import ripr.core.Registration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Truth-free, post-pair reconciliation evidence and the frozen confidence fallback. */
public final class AutomaticReconciliationSelector {

    /** Exact Stage 01 feature order. Movement truth and candidate outcomes are deliberately absent. */
    public static final String[] FEATURE_NAMES = {
            "estimator_kind",
            "rigid",
            "usable_pair_fraction",
            "failed_pair_fraction",
            "bound_hit_pair_fraction",
            "unavailable_uncertainty_fraction",
            "calibrated_uncertainty_fraction",
            "covariance_floor_fraction",
            "covariance_cap_fraction",
            "median_translation_standard_deviation",
            "median_rotation_standard_deviation",
            "median_covariance_anisotropy",
            "median_peak_ambiguity",
            "median_equal_graph_residual",
            "p90_equal_graph_residual",
            "maximum_equal_graph_residual",
            "minimum_frame_support",
            "disconnected_frame_fraction"
    };

    public static final int FEATURE_COUNT = FEATURE_NAMES.length;

    private AutomaticReconciliationSelector() {
    }

    public static final class Evidence {
        public final PairEstimator.Kind estimator;
        public final boolean rigid;
        private final double[] pairAndGraphFeatures;

        Evidence(PairEstimator.Kind estimator, boolean rigid, double[] vector) {
            this.estimator = estimator;
            this.rigid = rigid;
            this.pairAndGraphFeatures = vector.clone();
        }

        public double[] vector() {
            return pairAndGraphFeatures.clone();
        }

        public boolean valid() {
            if (pairAndGraphFeatures.length != FEATURE_COUNT) return false;
            for (double value : pairAndGraphFeatures) {
                if (!Double.isFinite(value)) return false;
            }
            return estimator != null;
        }
    }

    public static final class Result {
        public final Reconciler.Weighting weighting;
        public final boolean fallback;
        public final double predictedGain;
        public final double confidenceThreshold;
        public final double[] candidateGains;
        public final Evidence evidence;
        public final String explanation;

        Result(Reconciler.Weighting weighting, boolean fallback, double predictedGain,
               double confidenceThreshold, double[] candidateGains, Evidence evidence,
               String explanation) {
            this.weighting = weighting;
            this.fallback = fallback;
            this.predictedGain = predictedGain;
            this.confidenceThreshold = confidenceThreshold;
            this.candidateGains = candidateGains.clone();
            this.evidence = evidence;
            this.explanation = explanation;
        }
    }

    /** Measure only evidence that exists after pair fitting and an equal provisional solve. */
    public static Evidence measure(
            List<Registration.PairResult> pairs,
            Reconciler.Solution equalProvisional,
            PairEstimator.Kind estimator,
            boolean rigid) {
        if (pairs == null || equalProvisional == null || estimator == null) {
            throw new IllegalArgumentException("pairs, equal solution and estimator are required");
        }
        int count = pairs.size();
        int usable = 0;
        int failed = 0;
        int bound = 0;
        int unavailable = 0;
        int calibrated = 0;
        int floored = 0;
        int capped = 0;
        List<Double> translationStd = new ArrayList<>();
        List<Double> rotationStd = new ArrayList<>();
        List<Double> anisotropy = new ArrayList<>();
        List<Double> ambiguity = new ArrayList<>();
        for (Registration.PairResult pair : pairs) {
            PairAligner.Fit fit = pair.fit;
            if (fit != null && fit.usable()) usable++; else failed++;
            if (fit != null && isBound(fit.status)) bound++;
            PairUncertainty uncertainty = fit == null ? null : fit.uncertainty;
            if (uncertainty == null || !uncertainty.available()) {
                unavailable++;
                continue;
            }
            if (uncertainty.calibrated) calibrated++;
            if (uncertainty.eigenvalueFloored) floored++;
            if (uncertainty.eigenvalueCapped) capped++;
            double[] covariance = uncertainty.covariance();
            int dimensions = uncertainty.dimensions;
            double trace = covariance[0] + covariance[dimensions + 1];
            translationStd.add(Math.sqrt(Math.max(0.0, trace / 2.0)));
            double difference = covariance[0] - covariance[dimensions + 1];
            double offDiagonal = 0.5 * (covariance[1] + covariance[dimensions]);
            double discriminant = Math.sqrt(Math.max(0.0,
                    difference * difference + 4 * offDiagonal * offDiagonal));
            double low = Math.max(1e-300, 0.5 * (trace - discriminant));
            double high = Math.max(low, 0.5 * (trace + discriminant));
            anisotropy.add(Math.sqrt(high / low));
            if (dimensions == 3) {
                rotationStd.add(Math.sqrt(Math.max(0.0, covariance[8])));
            }
            if (Double.isFinite(uncertainty.peakAmbiguity)) {
                ambiguity.add(uncertainty.peakAmbiguity);
            }
        }

        List<Double> graphResidual = new ArrayList<>();
        for (Reconciler.PairInfluence influence : equalProvisional.influences) {
            if (influence.used && Double.isFinite(influence.standardizedResidual)) {
                graphResidual.add(influence.standardizedResidual);
            }
        }
        int minimumSupport = 0;
        int disconnected = 0;
        if (equalProvisional.support.length > 0) {
            minimumSupport = Integer.MAX_VALUE;
            for (int support : equalProvisional.support) {
                minimumSupport = Math.min(minimumSupport, support);
                if (support == 0) disconnected++;
            }
        }

        double denominator = Math.max(1, count);
        double[] vector = {
                estimator.ordinal(),
                rigid ? 1.0 : 0.0,
                usable / denominator,
                failed / denominator,
                bound / denominator,
                unavailable / denominator,
                calibrated / denominator,
                floored / denominator,
                capped / denominator,
                median(translationStd, count == 0 ? 0.0 : Double.NaN),
                rigid ? median(rotationStd, count == 0 ? 0.0 : Double.NaN) : 0.0,
                median(anisotropy, count == 0 ? 0.0 : Double.NaN),
                median(ambiguity, 0.0),
                percentile(graphResidual, 0.5, 0.0),
                percentile(graphResidual, 0.9, 0.0),
                percentile(graphResidual, 1.0, 0.0),
                minimumSupport,
                equalProvisional.support.length == 0 ? 0.0
                        : disconnected / (double) equalProvisional.support.length
        };
        return new Evidence(estimator, rigid, vector);
    }

    /** Apply the frozen policy, falling back when evidence or scope is unsupported. */
    public static Result select(Evidence evidence, Reconciler.Weighting safeFixedFallback) {
        Reconciler.Weighting fallback = safeFixedFallback == null
                ? AutomaticReconciliationSelectorModel.safeFallback() : safeFixedFallback;
        double threshold = AutomaticReconciliationSelectorModel.confidenceThreshold();
        double[] gains = evidence == null
                ? new double[]{Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY}
                : AutomaticReconciliationSelectorModel.predictGains(evidence.vector());
        if (evidence == null || !evidence.valid()) {
            return fallback(fallback, gains, evidence, threshold, "invalid selector evidence");
        }
        if ("fixed_only".equals(AutomaticReconciliationSelectorModel.modelKind())
                || !AutomaticReconciliationSelectorModel.eligible(evidence.rigid)) {
            return fallback(fallback, gains, evidence, threshold,
                    "no scope cleared the frozen evidence gates; policy is fixed_only");
        }

        Reconciler.Weighting[] candidates = {
                Reconciler.Weighting.UNCERTAINTY,
                Reconciler.Weighting.ROBUST,
                Reconciler.Weighting.COMBINED
        };
        int best = -1;
        for (int i = 0; i < gains.length; i++) {
            if (Double.isFinite(gains[i]) && (best < 0 || gains[i] > gains[best])) best = i;
        }
        if (best < 0 || gains[best] < threshold) {
            double predicted = best < 0 ? 0.0 : gains[best];
            return new Result(fallback, true, predicted, threshold, gains, evidence,
                    String.format(Locale.ROOT,
                            "safe fixed fallback %s; predicted gain %.4f did not clear %.4f",
                            fallback, predicted, threshold));
        }
        return new Result(candidates[best], false, gains[best], threshold, gains, evidence,
                String.format(Locale.ROOT, "%s; predicted gain %.4f clears %.4f",
                        candidates[best], gains[best], threshold));
    }

    private static Result fallback(Reconciler.Weighting weighting, double[] gains,
                                   Evidence evidence, double threshold, String reason) {
        return new Result(weighting, true, 0.0, threshold, gains, evidence,
                "safe fixed fallback " + weighting + "; " + reason);
    }

    private static boolean isBound(PairAligner.Status status) {
        return status == PairAligner.Status.AT_SHIFT_BOUND
                || status == PairAligner.Status.AT_ROTATION_BOUND
                || status == PairAligner.Status.AT_SHIFT_AND_ROTATION_BOUND;
    }

    private static double median(List<Double> values, double empty) {
        return percentile(values, 0.5, empty);
    }

    private static double percentile(List<Double> values, double quantile, double empty) {
        if (values.isEmpty()) return empty;
        double[] ordered = new double[values.size()];
        for (int i = 0; i < ordered.length; i++) ordered[i] = values.get(i);
        Arrays.sort(ordered);
        int index = (int) Math.ceil(quantile * ordered.length) - 1;
        return ordered[Math.max(0, Math.min(ordered.length - 1, index))];
    }
}
