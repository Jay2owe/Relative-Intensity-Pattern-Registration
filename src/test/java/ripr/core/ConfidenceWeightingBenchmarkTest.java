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
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfidenceWeightingBenchmarkTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void warpingIndexIsExactlyTranslationErrorAtZeroAngle() {
        Transform truth = Transform.translation(2, -3);
        Transform estimate = Transform.translation(5, 1);
        assertEquals(5.0, ConfidenceWeightingBenchmark.warpingIndex(truth, estimate, 50), 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void sourceVariantsCannotCrossSplits() {
        ConfidenceWeightingBenchmark.assertSourceIsolation(Arrays.asList(
                new ConfidenceWeightingBenchmark.RecordingKey(
                        "source_a_clean", "source_a", ConfidenceWeightingBenchmark.Split.DEVELOPMENT),
                new ConfidenceWeightingBenchmark.RecordingKey(
                        "source_a_fade", "source_a", ConfidenceWeightingBenchmark.Split.VALIDATION)));
    }

    @Test
    public void frozenSelectorColumnsContainNoTruthOrOutcome() {
        ConfidenceWeightingBenchmark.assertTruthFreeSelectorColumns(
                AutomaticReconciliationSelector.FEATURE_NAMES.clone());
    }

    @Test
    public void armsReuseByteIdenticalPairFitsAndAreDeterministic() {
        PairUncertainty uncertainty = PairUncertainty.fromCovariance(2,
                new double[]{0.1, 0.02, 0.02, 0.2}, Double.NaN, false, false, false);
        List<Registration.PairResult> pairs = Arrays.asList(
                pair(0, 1, Transform.translation(1, 0), uncertainty),
                pair(1, 2, Transform.translation(1, 0), uncertainty),
                pair(0, 2, Transform.translation(2.1, 0), uncertainty));
        String frozen = ConfidenceWeightingBenchmark.pairFitHash(pairs);
        for (ConfidenceWeightingBenchmark.Arm arm : ConfidenceWeightingBenchmark.Arm.values()) {
            Reconciler.Solution first = ConfidenceWeightingBenchmark.reconcile(
                    3, pairs, arm, 2, 50);
            Reconciler.Solution second = ConfidenceWeightingBenchmark.reconcile(
                    3, pairs, arm, 2, 50);
            assertEquals(frozen, ConfidenceWeightingBenchmark.pairFitHash(pairs));
            for (int frame = 0; frame < 3; frame++) {
                assertEquals(Double.doubleToLongBits(first.cumulative[frame].dx),
                        Double.doubleToLongBits(second.cumulative[frame].dx));
                assertEquals(Double.doubleToLongBits(first.cumulative[frame].dy),
                        Double.doubleToLongBits(second.cumulative[frame].dy));
            }
        }
    }

    @Test
    public void serializedPairFitsRoundTripByteIdentically() throws Exception {
        PairUncertainty uncertainty = PairUncertainty.fromCovariance(3,
                new double[]{0.1, 0.01, 0.001, 0.01, 0.2, 0.002,
                        0.001, 0.002, 0.0003}, 0.25, true, true, false);
        PairAligner.RotationEvidence rotation = new PairAligner.RotationEvidence(
                0.1, 1.0, 0.9, 0.01, true);
        PairAligner.Fit fit = new PairAligner.Fit(new Transform(1, -2, 0.01),
                2, 1, 0.2, 0.9, 7, PairAligner.Status.OK, rotation, uncertainty);
        List<Registration.PairResult> pairs = Collections.singletonList(
                new Registration.PairResult(1, 4, fit));
        Path cache = temporary.newFile("pairs.bin").toPath();
        ConfidenceWeightingBenchmarkRunner.writePairs(cache, pairs);
        List<Registration.PairResult> restored =
                ConfidenceWeightingBenchmarkRunner.readPairs(cache);
        assertEquals(ConfidenceWeightingBenchmark.pairFitHash(pairs),
                ConfidenceWeightingBenchmark.pairFitHash(restored));
    }

    @Test
    public void longRunIsDisabledUnlessExplicitlyEnabled() throws Exception {
        if (Boolean.getBoolean("confidenceWeighting.run")) {
            assertTrue(ConfidenceWeightingBenchmark.writeGatedEvidenceStatus(Paths.get(
                    "target", "confidence_weighted_reconciliation_v1")));
        } else {
            assertFalse(ConfidenceWeightingBenchmark.writeGatedEvidenceStatus(Paths.get(
                    "target", "confidence_weighted_reconciliation_v1")));
        }
    }

    private static Registration.PairResult pair(
            int from, int to, Transform transform, PairUncertainty uncertainty) {
        PairAligner.Fit fit = new PairAligner.Fit(transform, 1, 0.1, 0,
                1, 1, PairAligner.Status.OK, null, uncertainty);
        return new Registration.PairResult(from, to, fit);
    }
}
