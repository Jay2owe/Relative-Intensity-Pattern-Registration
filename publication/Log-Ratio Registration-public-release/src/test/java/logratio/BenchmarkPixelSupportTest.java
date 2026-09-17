/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.core.AutomaticInformationSelector;
import logratio.core.PairAligner;
import logratio.core.RobustNorm;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Pins the benchmark-only gradient-fraction sweep without changing the production selector. */
public class BenchmarkPixelSupportTest {

    @Test
    public void gradientSweepUsesOneSelectorAtTheDeclaredFractions() {
        Benchmark.Estimator[] arms = {
                Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_0,
                Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_025,
                Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_05,
                Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_1,
                Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_2,
                Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_4
        };
        double[] expected = {0, 0.25, 0.5, 1, 2, 4};
        double[] actual = new double[arms.length];
        for (int i = 0; i < arms.length; i++) {
            PairAligner.Options options = arms[i].options(30);
            actual[i] = options.gradientFraction;
            assertEquals(PairAligner.PixelSupport.GRADIENT, options.support);
            assertEquals(RobustNorm.TUKEY, options.norm);
        }
        assertArrayEquals(expected, actual, 0);
    }

    @Test
    public void explicitHalfGradientControlMatchesTheStandingConfiguration() {
        PairAligner.Options standing = Benchmark.Estimator.LOGRATIO_TUKEY.options(30);
        PairAligner.Options control = Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_05.options(30);
        assertEquals(standing.support, control.support);
        assertEquals(standing.norm, control.norm);
        assertEquals(standing.gradientFraction, control.gradientFraction, 0);
        assertEquals(standing.profileGain, control.profileGain);
    }

    @Test
    public void automaticInformationSupportPinsAllThreeEvidenceBranches() {
        assertEquals(Benchmark.Estimator.LOGRATIO_TUKEY_MUTUAL_NOISE,
                Benchmark.chooseAutoInformation(0.20, 2.0, false));
        assertEquals(Benchmark.Estimator.LOGRATIO_TUKEY,
                Benchmark.chooseAutoInformation(0.75, 1.60, false));
        assertEquals(Benchmark.Estimator.LOGRATIO_TUKEY_NO_TOP_10,
                Benchmark.chooseAutoInformation(0.75, 1.50, false));
        assertEquals("an explicit artefact band remains authoritative",
                Benchmark.Estimator.LOGRATIO_TUKEY,
                Benchmark.chooseAutoInformation(0.20, 1.50, true));
        assertEquals("the benchmark delegates the boundary decision to production",
                AutomaticInformationSelector.Choice.MOVING_STANDARD_GRADIENT,
                AutomaticInformationSelector.choose(0.75, 1.60, false));
    }

    @Test
    public void sparseBranchUsesMutualRawNoiseEdgesWithTheFrozenBudget() {
        PairAligner.Options options = Benchmark.Estimator.LOGRATIO_TUKEY_MUTUAL_NOISE.options(30);
        assertEquals(PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT, options.support);
        assertEquals(0.5, options.gradientFraction, 0);
        assertEquals(12, options.maxIterations);
        assertEquals(50_000, options.maxSamples);
    }
}
