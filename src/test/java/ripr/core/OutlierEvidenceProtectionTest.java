/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pair evidence may protect real motion from generic step-size repair. */
public final class OutlierEvidenceProtectionTest {
    @Test
    public void protectsUnsettledFitOnlyAboveTheConfiguredGain() {
        PairAligner.Fit weak = fit(1.0, 0.985, PairAligner.Status.NOT_CONVERGED);
        PairAligner.Fit strong = fit(1.0, 0.96, PairAligner.Status.NOT_CONVERGED);
        assertFalse(protectedFrame(weak, 0.04));
        assertTrue(protectedFrame(strong, 0.04));
    }

    @Test
    public void protectsAConvergedFitEvenWhenItsResidualGainIsSmall() {
        assertTrue(protectedFrame(fit(1.0, 0.999, PairAligner.Status.OK), 0.04));
    }

    @Test
    public void disabledProtectionLeavesEveryFrameUnprotected() {
        assertFalse(protectedFrame(fit(1.0, 0.5, PairAligner.Status.OK), Double.NaN));
    }

    private static boolean protectedFrame(PairAligner.Fit fit, double threshold) {
        Registration.PairResult pair = new Registration.PairResult(0, 1, fit);
        return Registration.outlierProtectionFrames(
                Collections.singletonList(pair), 2, threshold)[1];
    }

    private static PairAligner.Fit fit(
            double before, double after, PairAligner.Status status) {
        return new PairAligner.Fit(Transform.translation(50, 0), before, after,
                0.0, 0.8, 25, status);
    }
}
