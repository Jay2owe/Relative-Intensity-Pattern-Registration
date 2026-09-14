/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.Reconciler;

/**
 * Frozen post-pair reconciliation policy.
 *
 * <p>The source-separated 2026-08-25/26 protocol evaluated 320 development, 160 validation and 160
 * final known-motion recordings. No non-equal strategy passed the frozen accuracy, repair,
 * convergence and runtime gates, and oracle headroom stayed below the 10% selector prerequisite.
 * The frozen policy is therefore {@code fixed_only/EQUAL}. Runtime accessors are intentional:
 * callers invoke a method instead of compiling a stale copy of a public constant.
 */
public final class AutomaticReconciliationSelectorModel {

    private static final String MODEL_KIND = "fixed_only";
    private static final String FEATURE_ORDER_SHA256 =
            "72DD9888EB704A95A3B35E3280B1F9E4DCAB60BD029A689DCAB4A09861C1EDF0";
    private static final String TRAINING_PROVENANCE =
            "confidence_weighted_reconciliation_v2; 320 development + 160 validation + "
                    + "160 final recordings; NO_PROMOTION_FAILED_FROZEN_GATES";
    private static final Reconciler.Weighting SAFE_FALLBACK = Reconciler.Weighting.EQUAL;
    private static final double CONFIDENCE_THRESHOLD = 0.02;

    private AutomaticReconciliationSelectorModel() {
    }

    public static String modelKind() {
        return MODEL_KIND;
    }

    public static String featureOrderSha256() {
        return FEATURE_ORDER_SHA256;
    }

    public static String trainingProvenance() {
        return TRAINING_PROVENANCE;
    }

    public static Reconciler.Weighting safeFallback() {
        return SAFE_FALLBACK;
    }

    public static double confidenceThreshold() {
        return CONFIDENCE_THRESHOLD;
    }

    /** No estimator/movement scope cleared every frozen development, validation and final gate. */
    public static boolean eligible(boolean rigid) {
        return false;
    }

    /** Predicted gains for uncertainty-only, robust-only and combined, in that order. */
    public static double[] predictGains(double[] evidence) {
        return new double[]{
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY
        };
    }
}
