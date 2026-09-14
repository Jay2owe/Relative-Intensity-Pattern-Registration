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

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/** Pins the production automatic selector that the benchmark and future Fiji menu both call. */
public class AutomaticInformationSelectorTest {

    @Test
    public void declaredBoundariesSelectAllFourBranches() {
        assertEquals(AutomaticInformationSelector.Choice.SPARSE_MUTUAL_NOISE_EDGES,
                AutomaticInformationSelector.choose(0.20, 2.0, false));
        assertEquals(AutomaticInformationSelector.Choice.MOVING_STANDARD_GRADIENT,
                AutomaticInformationSelector.choose(0.75, 1.60, false));
        assertEquals(AutomaticInformationSelector.Choice.STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT,
                AutomaticInformationSelector.choose(0.75, 1.50, false));
        assertEquals(AutomaticInformationSelector.Choice.EXPLICIT_INTENSITY_BAND,
                AutomaticInformationSelector.choose(0.20, 2.0, true));
    }

    @Test
    public void sparseBranchAppliesItsFrozenEvidenceRuleAndComputeBudget() {
        Registration.Options options = new Registration.Options();
        AutomaticInformationSelector.Result result = new AutomaticInformationSelector.Result(
                AutomaticInformationSelector.Choice.SPARSE_MUTUAL_NOISE_EDGES,
                0.2, Double.NaN, Double.NaN, false);
        result.applyTo(options);
        assertEquals(PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT, options.aligner.support);
        assertEquals(0.5, options.aligner.gradientFraction, 0);
        assertEquals(12, options.aligner.maxIterations);
        assertEquals(50_000, options.aligner.maxSamples);
    }

    @Test
    public void stableDenseFramesAreDecidedFromPixelsAndApplyTheBrightBand() {
        int width = 64;
        float[] frame = new float[width * width];
        Random random = new Random(20260813L);
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (float) (20 + Math.sqrt(random.nextDouble()) * 200);
        }
        AutomaticInformationSelector.Result result = AutomaticInformationSelector.select(
                frame, frame.clone(), width, width, 1.0, false);
        assertEquals(AutomaticInformationSelector.Choice.STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT,
                result.choice);
        assertFalse(Double.isNaN(result.movingTailRatio));
        Registration.Options options = new Registration.Options();
        result.applyTo(options);
        assertEquals(90.0, options.saturationPercentile, 0);
        assertEquals(PairAligner.PixelSupport.GRADIENT, options.aligner.support);
    }

    @Test
    public void registrationReportsTheProductionAutomaticDecision() {
        int width = 64;
        float[] frame = new float[width * width];
        Random random = new Random(7L);
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (float) (50 + Math.sqrt(random.nextDouble()) * 100);
        }
        FrameSource source = Synth.source(width, width, new float[][]{frame, frame.clone()});
        Registration.Options options = new Registration.Options();
        options.automaticInformationSupport = true;
        options.threads = 1;
        Registration.Result registration = Registration.run(source, options, null, null);
        assertNotNull(registration.automaticInformation);
        assertEquals(AutomaticInformationSelector.Choice.STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT,
                registration.automaticInformation.choice);
    }
}
