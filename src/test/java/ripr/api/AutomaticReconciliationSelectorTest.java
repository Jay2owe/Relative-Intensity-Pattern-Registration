/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.PairEstimator;
import ripr.core.Reconciler;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutomaticReconciliationSelectorTest {

    @Test
    public void featureOrderMatchesFrozenModelHash() throws Exception {
        assertEquals(18, AutomaticReconciliationSelector.FEATURE_COUNT);
        String joined = String.join("\n", AutomaticReconciliationSelector.FEATURE_NAMES);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(joined.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format("%02X", value & 0xff));
        assertEquals(hash.toString(),
                AutomaticReconciliationSelectorModel.featureOrderSha256());
    }

    @Test
    public void fixedOnlyPolicyDeterministicallyFallsBackToEqual() {
        Reconciler.Solution equal = Reconciler.multiLag(2, Collections.emptyList());
        AutomaticReconciliationSelector.Evidence evidence =
                AutomaticReconciliationSelector.measure(Collections.emptyList(), equal,
                        PairEstimator.Kind.LOG_RATIO_FIT, false);
        assertTrue(evidence.valid());
        AutomaticReconciliationSelector.Result first =
                AutomaticReconciliationSelector.select(evidence, Reconciler.Weighting.EQUAL);
        AutomaticReconciliationSelector.Result second =
                AutomaticReconciliationSelector.select(evidence, Reconciler.Weighting.EQUAL);
        assertEquals(Reconciler.Weighting.EQUAL, first.weighting);
        assertTrue(first.fallback);
        assertEquals(first.explanation, second.explanation);
        assertArrayEquals(first.candidateGains, second.candidateGains, 0.0);
        assertTrue(first.explanation.contains("fixed_only"));
    }

    @Test
    public void invalidAndUnsupportedRigidEvidenceUseSafeFallback() {
        double[] invalid = new double[AutomaticReconciliationSelector.FEATURE_COUNT];
        invalid[5] = Double.NaN;
        AutomaticReconciliationSelector.Evidence bad =
                new AutomaticReconciliationSelector.Evidence(
                        PairEstimator.Kind.LOG_RATIO_FIT, false, invalid);
        assertFalse(bad.valid());
        assertEquals(Reconciler.Weighting.EQUAL,
                AutomaticReconciliationSelector.select(bad, Reconciler.Weighting.EQUAL).weighting);

        Reconciler.Solution equal = Reconciler.multiLag(2, Collections.emptyList());
        AutomaticReconciliationSelector.Evidence rigid =
                AutomaticReconciliationSelector.measure(Collections.emptyList(), equal,
                        PairEstimator.Kind.AREA_CORRELATION, true);
        assertEquals(Reconciler.Weighting.EQUAL,
                AutomaticReconciliationSelector.select(
                        rigid, Reconciler.Weighting.EQUAL).weighting);
    }

    @Test
    public void returnedVectorsAreDefensiveCopies() {
        Reconciler.Solution equal = Reconciler.multiLag(2, Collections.emptyList());
        AutomaticReconciliationSelector.Evidence evidence =
                AutomaticReconciliationSelector.measure(Collections.emptyList(), equal,
                        PairEstimator.Kind.LOG_RATIO_FIT, false);
        double[] first = evidence.vector();
        first[0] = 999;
        assertFalse(evidence.vector()[0] == 999);
        AutomaticReconciliationSelector.Result result =
                AutomaticReconciliationSelector.select(evidence, Reconciler.Weighting.EQUAL);
        result.candidateGains[0] = 999;
        assertFalse(AutomaticReconciliationSelector.select(
                evidence, Reconciler.Weighting.EQUAL).candidateGains[0] == 999);
    }
}
