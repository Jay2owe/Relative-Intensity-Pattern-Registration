/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import ripr.api.AutomaticReconciliationSelector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Auditable strategy-isolation helpers for the gated confidence-weighting benchmark. */
public final class ConfidenceWeightingBenchmark {

    public enum Arm {
        EQUAL(Reconciler.Weighting.EQUAL),
        UNCERTAINTY_ONLY(Reconciler.Weighting.UNCERTAINTY),
        ROBUST_ONLY(Reconciler.Weighting.ROBUST),
        UNCERTAINTY_AND_ROBUST(Reconciler.Weighting.COMBINED);

        final Reconciler.Weighting weighting;

        Arm(Reconciler.Weighting weighting) {
            this.weighting = weighting;
        }
    }

    public enum Split { DEVELOPMENT, VALIDATION, FINAL }

    public static final class RecordingKey {
        public final String recordingId;
        public final String originalSource;
        public final Split split;

        public RecordingKey(String recordingId, String originalSource, Split split) {
            this.recordingId = recordingId;
            this.originalSource = originalSource;
            this.split = split;
        }
    }

    private ConfidenceWeightingBenchmark() {
    }

    /** RMS point-displacement error; at zero angle this is exactly Euclidean translation error. */
    public static double warpingIndex(Transform truth, Transform estimate, double rmsRadius) {
        Transform error = truth.inverse().then(estimate);
        double squared = error.dx * error.dx + error.dy * error.dy
                + 2.0 * rmsRadius * rmsRadius * (1.0 - Math.cos(error.theta));
        return Math.sqrt(Math.max(0.0, squared));
    }

    /** Keep every derivative of one acquisition in exactly one evidence split. */
    public static void assertSourceIsolation(List<RecordingKey> recordings) {
        Map<String, Split> bySource = new LinkedHashMap<>();
        for (RecordingKey recording : recordings) {
            if (recording.originalSource == null || recording.originalSource.trim().isEmpty()) {
                throw new IllegalArgumentException("recording has no original source: "
                        + recording.recordingId);
            }
            Split previous = bySource.putIfAbsent(recording.originalSource, recording.split);
            if (previous != null && previous != recording.split) {
                throw new IllegalArgumentException("original source crosses evidence splits: "
                        + recording.originalSource + " is in " + previous + " and "
                        + recording.split);
            }
        }
    }

    /** Fail closed if a truth/outcome column reaches the selector side of the join. */
    public static void assertTruthFreeSelectorColumns(String[] columns) {
        if (columns.length != AutomaticReconciliationSelector.FEATURE_NAMES.length) {
            throw new IllegalArgumentException("selector feature count changed");
        }
        for (int i = 0; i < columns.length; i++) {
            if (!columns[i].equals(AutomaticReconciliationSelector.FEATURE_NAMES[i])) {
                throw new IllegalArgumentException("selector feature order changed at " + i);
            }
            String lower = columns[i].toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("truth") || lower.contains("winner")
                    || lower.contains("arm_error") || lower.contains("corrected")
                    || lower.contains("post_repair")) {
                throw new IllegalArgumentException("prohibited selector feature: " + columns[i]);
            }
        }
    }

    /** Hash every pair field used by an arm, proving all arms received identical measurements. */
    public static String pairFitHash(List<Registration.PairResult> pairs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer numbers = ByteBuffer.allocate(8);
            for (Registration.PairResult pair : pairs) {
                updateInt(digest, pair.from);
                updateInt(digest, pair.to);
                PairAligner.Fit fit = pair.fit;
                updateDouble(digest, numbers, fit.transform.dx);
                updateDouble(digest, numbers, fit.transform.dy);
                updateDouble(digest, numbers, fit.transform.theta);
                updateDouble(digest, numbers, fit.residualBefore);
                updateDouble(digest, numbers, fit.residualAfter);
                updateDouble(digest, numbers, fit.logGain);
                updateDouble(digest, numbers, fit.validFraction);
                updateInt(digest, fit.iterations);
                updateInt(digest, fit.status.ordinal());
                PairAligner.RotationEvidence rotation = fit.rotationEvidence;
                updateInt(digest, rotation == null ? 0 : 1);
                if (rotation != null) {
                    updateDouble(digest, numbers, rotation.residualGain);
                    updateDouble(digest, numbers, rotation.translationResidual);
                    updateDouble(digest, numbers, rotation.rigidResidual);
                    updateDouble(digest, numbers, rotation.proposedAngleRadians);
                    updateInt(digest, rotation.accepted ? 1 : 0);
                }
                PairUncertainty uncertainty = fit.uncertainty;
                updateInt(digest, uncertainty.dimensions);
                updateInt(digest, uncertainty.available() ? 1 : 0);
                if (uncertainty.available()) {
                    for (double value : uncertainty.covariance()) {
                        updateDouble(digest, numbers, value);
                    }
                }
                updateDouble(digest, numbers, uncertainty.peakAmbiguity);
                updateInt(digest, uncertainty.calibrated ? 1 : 0);
                updateInt(digest, uncertainty.eigenvalueFloored ? 1 : 0);
                updateInt(digest, uncertainty.eigenvalueCapped ? 1 : 0);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Reconcile retained immutable pair fits without rerunning an estimator. */
    public static Reconciler.Solution reconcile(
            int frames, List<Registration.PairResult> pairs, Arm arm,
            int dimensions, double rotationRadius) {
        List<Reconciler.Observation> observations = new ArrayList<>();
        for (int index = 0; index < pairs.size(); index++) {
            Registration.PairResult pair = pairs.get(index);
            if (pair.fit.usable()) {
                observations.add(new Reconciler.Observation(pair.from, pair.to,
                        pair.fit.transform, pair.fit.uncertainty, index));
            }
        }
        return Reconciler.multiLag(frames, observations,
                new Reconciler.Options(arm.weighting, dimensions, rotationRadius));
    }

    /** Gated entry point for the resumable frozen-evidence runner. */
    public static boolean writeGatedEvidenceStatus(Path output) throws IOException {
        if (!Boolean.getBoolean("confidenceWeighting.run")) return false;
        return ConfidenceWeightingBenchmarkRunner.run(output);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value
        });
    }

    private static void updateDouble(MessageDigest digest, ByteBuffer buffer, double value) {
        buffer.clear();
        buffer.putLong(Double.doubleToLongBits(value));
        digest.update(buffer.array());
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }
}
