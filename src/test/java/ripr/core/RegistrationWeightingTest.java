/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Full-pipeline routing and diagnostic ownership for the four experimental strategies. */
public class RegistrationWeightingTest {

    @Test
    public void equalDefaultIsBitExactAndEveryArmCarriesPairDiagnostics() {
        int frames = 6;
        float[][] planes = new float[frames][];
        for (int frame = 0; frame < frames; frame++) {
            planes[frame] = Synth.frame(64, 64, 0.8 * frame, -0.35 * frame,
                    Math.pow(0.8, frame), 0);
        }
        FrameSource source = Synth.source(64, 64, planes);
        Registration.Result legacy = Registration.run(source, options(null), null, null);
        Registration.Result explicit = Registration.run(
                source, options(Reconciler.Weighting.EQUAL), null, null);
        for (int frame = 0; frame < frames; frame++) {
            assertEquals(Double.doubleToLongBits(legacy.cumulative[frame].dx),
                    Double.doubleToLongBits(explicit.cumulative[frame].dx));
            assertEquals(Double.doubleToLongBits(legacy.cumulative[frame].dy),
                    Double.doubleToLongBits(explicit.cumulative[frame].dy));
        }

        for (Reconciler.Weighting weighting : Reconciler.Weighting.values()) {
            Registration.Result result = Registration.run(source, options(weighting), null, null);
            assertEquals(weighting + " pair count", legacy.pairs.size(), result.pairs.size());
            assertTrue(weighting + " should expose at least one covariance",
                    result.unavailableUncertaintyPairs() < result.pairs.size());
            for (int index = 0; index < result.pairs.size(); index++) {
                Registration.PairResult pair = result.pairs.get(index);
                assertNotNull(pair.fit.uncertainty);
                assertNotNull(pair.influence);
                assertEquals(index, pair.influence.planIndex);
                assertTrue(pair.influence.used);
            }
            assertTrue(result.downweightedPairs() >= 0);
        }
    }

    private static Registration.Options options(Reconciler.Weighting weighting) {
        Registration.Options options = new Registration.Options();
        options.reference = Reconciler.Reference.MULTILAG;
        options.lags = new int[]{1, 2, 4};
        options.threads = 1;
        options.aligner.levels = 1;
        options.aligner.maxShift = 8;
        if (weighting != null) options.reconciliationWeighting = weighting;
        return options;
    }
}
