/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.ImagePlus;
import ij.ImageStack;
import org.junit.Test;
import ripr.api.Preprocessing;
import ripr.api.RegistrationRecipe;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.SelectionMode;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class SingleChannelGeneralisationBenchmarkTest {

    private static RelativeIntensityPatternParameters base() {
        return RelativeIntensityPatternParameters.builder().outlierMads(0).build();
    }

    @Test
    public void acceptedBaselineIsPreviousFrameEnhancedCorrelation() {
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                base(), SingleChannelGeneralisationBenchmark.Attempt.A000_ACCEPTED_PREVIOUS_ECC, 40);
        assertEquals(Reconciler.Reference.CONSECUTIVE, result.reference);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, result.estimator);
        assertEquals(0.0, result.outlierMads, 0.0);
    }

    @Test
    public void repairCandidateChangesOnlyRepairThreshold() {
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                base(), SingleChannelGeneralisationBenchmark.Attempt.A101_PREVIOUS_ECC_REPAIR_8, 40);
        assertEquals(Reconciler.Reference.CONSECUTIVE, result.reference);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, result.estimator);
        assertEquals(8.0, result.outlierMads, 0.0);
    }

    @Test
    public void sparseAnchorUsesOneFifthOfAvailableTransitions() {
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                base(), SingleChannelGeneralisationBenchmark.Attempt.A102_RELATIVE_SPARSE_ANCHOR_ECC, 40);
        assertEquals(Reconciler.Reference.MULTILAG, result.reference);
        assertArrayEquals(new int[]{1, 8}, result.lags);
    }

    @Test
    public void rollingAndFixedCandidatesRetainEnhancedCorrelation() {
        RelativeIntensityPatternParameters rolling = SingleChannelGeneralisationBenchmark.apply(
                base(), SingleChannelGeneralisationBenchmark.Attempt.A103_ROLLING_TEMPLATE_ECC, 40);
        RelativeIntensityPatternParameters fixed = SingleChannelGeneralisationBenchmark.apply(
                base(), SingleChannelGeneralisationBenchmark.Attempt.A104_FIRST_FRAME_ECC, 40);
        assertEquals(Reconciler.Reference.ROLLING, rolling.reference);
        assertEquals(5, rolling.templateWindow);
        assertEquals(Reconciler.Reference.FIXED, fixed.reference);
        assertEquals(1, fixed.referenceFrame);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, rolling.estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, fixed.estimator);
    }

    @Test
    public void imagePreparationsDoNotChangeTheAcceptedReferenceOrEstimator() {
        RelativeIntensityPatternParameters contrast = SingleChannelGeneralisationBenchmark.apply(
                base(), SingleChannelGeneralisationBenchmark.Attempt.A105_LOCAL_CONTRAST_ECC, 40);
        RelativeIntensityPatternParameters gradient = SingleChannelGeneralisationBenchmark.apply(
                base(), SingleChannelGeneralisationBenchmark.Attempt.A106_STRUCTURAL_GRADIENT_ECC, 40);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_16, contrast.preprocessing);
        assertEquals(Preprocessing.STRUCTURAL_GRADIENT, gradient.preprocessing);
        assertEquals(Reconciler.Reference.CONSECUTIVE, contrast.reference);
        assertEquals(Reconciler.Reference.CONSECUTIVE, gradient.reference);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, contrast.estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, gradient.estimator);
    }

    @Test
    public void jumpOnlyRecipesLeaveSlowDriftPreparationAndRepairUntouched() {
        RelativeIntensityPatternParameters slow = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.STEADY_DIRECTIONAL_DRIFT)
                .preprocessing(Preprocessing.GAUSSIAN_1_0)
                .outlierMads(6.0).build();
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                slow, SingleChannelGeneralisationBenchmark.Attempt.A108_JUMP_LOCAL_CONTRAST_1_16_REPAIR_8_ECC, 40);
        assertEquals(Preprocessing.GAUSSIAN_1_0, result.preprocessing);
        assertEquals(6.0, result.outlierMads, 0.0);
    }

    @Test
    public void jumpOnlyScaleGridChangesOnlyThePreparation() {
        RelativeIntensityPatternParameters jump = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS).outlierMads(0).build();
        RelativeIntensityPatternParameters wide = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A109_JUMP_LOCAL_CONTRAST_1_8_ECC, 40);
        RelativeIntensityPatternParameters narrow = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A110_JUMP_LOCAL_CONTRAST_1_32_ECC, 40);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, wide.preprocessing);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_32, narrow.preprocessing);
        assertEquals(0.0, wide.outlierMads, 0.0);
        assertEquals(0.0, narrow.outlierMads, 0.0);
    }

    @Test
    public void jumpOnlyCorrelationCandidatesLeaveSlowDriftEstimatorUntouched() {
        RelativeIntensityPatternParameters slow = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.STEADY_DIRECTIONAL_DRIFT)
                .preprocessing(Preprocessing.GAUSSIAN_1_0)
                .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC).build();
        RelativeIntensityPatternParameters grid = SingleChannelGeneralisationBenchmark.apply(
                slow, SingleChannelGeneralisationBenchmark.Attempt.A111_JUMP_LOCAL_CONTRAST_1_8_CORRELATION, 40);
        RelativeIntensityPatternParameters newton = SingleChannelGeneralisationBenchmark.apply(
                slow, SingleChannelGeneralisationBenchmark.Attempt.A112_JUMP_LOCAL_CONTRAST_1_8_CORRELATION_NEWTON, 40);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, grid.estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, newton.estimator);
        assertEquals(Preprocessing.GAUSSIAN_1_0, grid.preprocessing);
        assertEquals(Preprocessing.GAUSSIAN_1_0, newton.preprocessing);
    }

    @Test
    public void jumpOnlyCorrelationCandidatesCombineDeclaredPreparationAndEstimator() {
        RelativeIntensityPatternParameters jump = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS).build();
        RelativeIntensityPatternParameters grid = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A111_JUMP_LOCAL_CONTRAST_1_8_CORRELATION, 40);
        RelativeIntensityPatternParameters newton = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A112_JUMP_LOCAL_CONTRAST_1_8_CORRELATION_NEWTON, 40);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, grid.preprocessing);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, newton.preprocessing);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION, grid.estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_NEWTON, newton.estimator);
    }

    @Test
    public void correctedPositiveLocalContrastAttemptKeepsEnhancedCorrelation() {
        RelativeIntensityPatternParameters jump = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS).build();
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A113_JUMP_POSITIVE_LOCAL_CONTRAST_1_8_ECC, 40);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, result.preprocessing);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, result.estimator);
        assertEquals(Reconciler.Reference.CONSECUTIVE, result.reference);
    }

    @Test
    public void jumpFirstFrameCombinationLeavesSlowDriftOnPreviousFrames() {
        RelativeIntensityPatternParameters jump = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS).build();
        RelativeIntensityPatternParameters slow = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.STEADY_DIRECTIONAL_DRIFT)
                .preprocessing(Preprocessing.GAUSSIAN_1_0).build();
        RelativeIntensityPatternParameters jumpResult = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A125_JUMP_FIRST_FRAME_POSITIVE_LOCAL_CONTRAST_ECC, 40);
        RelativeIntensityPatternParameters slowResult = SingleChannelGeneralisationBenchmark.apply(
                slow, SingleChannelGeneralisationBenchmark.Attempt.A125_JUMP_FIRST_FRAME_POSITIVE_LOCAL_CONTRAST_ECC, 40);
        assertEquals(Reconciler.Reference.FIXED, jumpResult.reference);
        assertEquals(1, jumpResult.referenceFrame);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, jumpResult.preprocessing);
        assertEquals(Reconciler.Reference.CONSECUTIVE, slowResult.reference);
        assertEquals(Preprocessing.GAUSSIAN_1_0, slowResult.preprocessing);
    }

    @Test
    public void jumpFirstFrameRepairCombinationUsesLooseDeclaredThreshold() {
        RelativeIntensityPatternParameters jump = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS).outlierMads(0).build();
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A126_JUMP_FIRST_FRAME_POSITIVE_LOCAL_CONTRAST_REPAIR_8_ECC, 40);
        assertEquals(Reconciler.Reference.FIXED, result.reference);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, result.preprocessing);
        assertEquals(8.0, result.outlierMads, 0.0);
    }

    @Test
    public void roundThreeProbeExplicitlyDisablesInheritedJumpRepair() {
        RelativeIntensityPatternParameters jump = RelativeIntensityPatternParameters.builder()
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS).outlierMads(8).build();
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                jump,
                SingleChannelGeneralisationBenchmark.Attempt.A201_JUMP_FIRST_FRAME_LOCAL_CONTRAST_KEEP_JUMPS,
                40);
        assertEquals(Reconciler.Reference.FIXED, result.reference);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, result.preprocessing);
        assertEquals(0.0, result.outlierMads, 0.0);
    }

    @Test
    public void scalePerturbationsPreserveOneChannelAndFrameCount() {
        ImageStack stack = new ImageStack(8, 8);
        for (int frame = 0; frame < 3; frame++) stack.addSlice(null, new float[64]);
        ImagePlus source = new ImagePlus("source", stack);
        source.setDimensions(1, 1, 3);
        ImagePlus small = SingleChannelGeneralisationBenchmark.perturb(
                source, SingleChannelGeneralisationBenchmark.Perturbation.SCALE_0_75);
        ImagePlus large = SingleChannelGeneralisationBenchmark.perturb(
                source, SingleChannelGeneralisationBenchmark.Perturbation.SCALE_1_50);
        assertEquals(1, small.getNChannels());
        assertEquals(3, small.getNFrames());
        assertEquals(6, small.getWidth());
        assertEquals(12, large.getWidth());
        small.close();
        large.close();
        source.close();
    }

    @Test
    public void affinePerturbationUsesFloatOutputWithoutClipping() {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(null, new float[]{0f, 65535f});
        stack.addSlice(null, new float[]{0f, 65535f});
        ImagePlus source = new ImagePlus("source", stack);
        source.setDimensions(1, 1, 2);
        ImagePlus changed = SingleChannelGeneralisationBenchmark.perturb(
                source, SingleChannelGeneralisationBenchmark.Perturbation.AFFINE_INTENSITY);
        assertEquals(1, changed.getNChannels());
        assertEquals(2, changed.getNFrames());
        assertEquals(32, changed.getBitDepth());
        org.junit.Assert.assertTrue(changed.getStack().getProcessor(2).getf(1, 0) > 65535f);
        changed.close();
        source.close();
    }

    @Test
    public void denseSlowFilterRemovalDoesNotReachSparseOrJumpRecipes() {
        RelativeIntensityPatternParameters denseSlow = RelativeIntensityPatternParameters.builder()
                .imageType(ripr.api.ImageType.DENSE_FLUORESCENCE)
                .motionType(ripr.api.MotionType.STEADY_DIRECTIONAL_DRIFT)
                .preprocessing(Preprocessing.MEDIAN_3X3).build();
        RelativeIntensityPatternParameters sparseSlow = denseSlow.toBuilder()
                .imageType(ripr.api.ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE)
                .preprocessing(Preprocessing.GAUSSIAN_1_0).build();
        RelativeIntensityPatternParameters jump = denseSlow.toBuilder()
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS).build();
        RelativeIntensityPatternParameters denseResult = SingleChannelGeneralisationBenchmark.apply(
                denseSlow, SingleChannelGeneralisationBenchmark.Attempt.A127_JUMP_A126_DENSE_SLOW_NO_FILTER, 40);
        RelativeIntensityPatternParameters sparseResult = SingleChannelGeneralisationBenchmark.apply(
                sparseSlow, SingleChannelGeneralisationBenchmark.Attempt.A127_JUMP_A126_DENSE_SLOW_NO_FILTER, 40);
        RelativeIntensityPatternParameters jumpResult = SingleChannelGeneralisationBenchmark.apply(
                jump, SingleChannelGeneralisationBenchmark.Attempt.A127_JUMP_A126_DENSE_SLOW_NO_FILTER, 40);
        assertEquals(Preprocessing.NONE, denseResult.preprocessing);
        assertEquals(Preprocessing.GAUSSIAN_1_0, sparseResult.preprocessing);
        assertEquals(Preprocessing.LOCAL_CONTRAST_1_8, jumpResult.preprocessing);
        assertEquals(Reconciler.Reference.FIXED, jumpResult.reference);
    }

    @Test
    public void productionAutomaticAttemptDoesNotOverrideTheResolvedRecipe() {
        RelativeIntensityPatternParameters resolved = RelativeIntensityPatternParameters.builder()
                .imageType(ripr.api.ImageType.DENSE_FLUORESCENCE)
                .motionType(ripr.api.MotionType.INTERMITTENT_JUMPS)
                .selectionMode(ripr.api.SelectionMode.MANUAL)
                .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                .reference(Reconciler.Reference.FIXED).referenceFrame(1)
                .preprocessing(Preprocessing.LOCAL_CONTRAST_1_8).outlierMads(8.0)
                .build();
        RelativeIntensityPatternParameters actual = SingleChannelGeneralisationBenchmark.apply(
                resolved, SingleChannelGeneralisationBenchmark.Attempt.A129_PRODUCTION_AUTOMATIC, 40);
        assertEquals(RegistrationRecipe.of(resolved), RegistrationRecipe.of(actual));
        assertEquals(resolved.reference, actual.reference);
        assertEquals(resolved.referenceFrame, actual.referenceFrame);
        assertEquals(resolved.outlierMads, actual.outlierMads, 0.0);
    }

    @Test
    public void roundFourHybridCandidatesUseTheDeclaredGraphWeighting() {
        assertEquals(Reconciler.Weighting.EQUAL,
                SingleChannelGeneralisationBenchmark.hybridWeighting(
                        SingleChannelGeneralisationBenchmark.Attempt
                                .A203_HYBRID_FIXED_PREVIOUS_ECC_EQUAL));
        assertEquals(Reconciler.Weighting.COMBINED,
                SingleChannelGeneralisationBenchmark.hybridWeighting(
                        SingleChannelGeneralisationBenchmark.Attempt
                                .A204_HYBRID_FIXED_PREVIOUS_ECC_COMBINED));
    }

    @Test
    public void roundFourKnownVisualControlKeepsTheImageAndMotionPreset() {
        RelativeIntensityPatternParameters preset = RelativeIntensityPatternParameters.builder()
                .recommendation(ripr.api.ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        ripr.api.MotionType.INTERMITTENT_JUMPS)
                .selectionMode(ripr.api.SelectionMode.AUTOMATIC).build();
        RelativeIntensityPatternParameters result = SingleChannelGeneralisationBenchmark.apply(
                preset, SingleChannelGeneralisationBenchmark.Attempt.A205_RECOMMENDED_PRESET, 40);
        assertEquals(ripr.api.SelectionMode.RECOMMENDED, result.selectionMode);
        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, result.estimator);
        assertEquals(Reconciler.Reference.MULTILAG, result.reference);
    }

    @Test
    public void roundFourDeclaredTypeRescueUsesPer2PresetAndDensePreviousEcc() {
        RelativeIntensityPatternParameters sparse = RelativeIntensityPatternParameters.builder()
                .recommendation(ripr.api.ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        ripr.api.MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.AUTOMATIC).build();
        RelativeIntensityPatternParameters dense = RelativeIntensityPatternParameters.builder()
                .recommendation(ripr.api.ImageType.DENSE_FLUORESCENCE,
                        ripr.api.MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.AUTOMATIC).build();

        RelativeIntensityPatternParameters sparseResult =
                SingleChannelGeneralisationBenchmark.apply(
                        sparse,
                        SingleChannelGeneralisationBenchmark.Attempt
                                .A206_DECLARED_TYPE_RESCUE,
                        40);
        RelativeIntensityPatternParameters denseResult =
                SingleChannelGeneralisationBenchmark.apply(
                        dense,
                        SingleChannelGeneralisationBenchmark.Attempt
                                .A206_DECLARED_TYPE_RESCUE,
                        40);

        assertEquals(SelectionMode.RECOMMENDED, sparseResult.selectionMode);
        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, sparseResult.estimator);
        assertEquals(Reconciler.Reference.MULTILAG, sparseResult.reference);
        assertEquals(SelectionMode.MANUAL, denseResult.selectionMode);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, denseResult.estimator);
        assertEquals(Reconciler.Reference.CONSECUTIVE, denseResult.reference);
        assertEquals(Preprocessing.NONE, denseResult.preprocessing);
        assertEquals(0.0, denseResult.outlierMads, 0.0);
    }

    @Test
    public void roundFourFastRescueRestoresSparseTunedRecipeAndKeepsDensePreviousEcc() {
        RelativeIntensityPatternParameters sparse = RelativeIntensityPatternParameters.builder()
                .recommendation(ripr.api.ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        ripr.api.MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.AUTOMATIC).build();
        RelativeIntensityPatternParameters dense = RelativeIntensityPatternParameters.builder()
                .recommendation(ripr.api.ImageType.DENSE_FLUORESCENCE,
                        ripr.api.MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.AUTOMATIC).build();

        RelativeIntensityPatternParameters sparseResult =
                SingleChannelGeneralisationBenchmark.apply(
                        sparse,
                        SingleChannelGeneralisationBenchmark.Attempt
                                .A207_RESTORED_SPARSE_DENSE_PREVIOUS,
                        40);
        RelativeIntensityPatternParameters denseResult =
                SingleChannelGeneralisationBenchmark.apply(
                        dense,
                        SingleChannelGeneralisationBenchmark.Attempt
                                .A207_RESTORED_SPARSE_DENSE_PREVIOUS,
                        40);

        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, sparseResult.estimator);
        assertEquals(Reconciler.Reference.MULTILAG, sparseResult.reference);
        assertEquals(ripr.core.PairAligner.PixelSupport.ALL, sparseResult.pixelSupport);
        assertEquals(Preprocessing.GAUSSIAN_1_0, sparseResult.preprocessing);
        assertEquals(90.0, sparseResult.ceilingPercentile, 0.0);
        assertEquals(SelectionMode.MANUAL, denseResult.selectionMode);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, denseResult.estimator);
        assertEquals(Reconciler.Reference.CONSECUTIVE, denseResult.reference);
    }

    @Test
    public void roundFourMaximumAccuracyRescueUsesFilteredDensePreviousEcc() {
        RelativeIntensityPatternParameters dense = RelativeIntensityPatternParameters.builder()
                .recommendation(ripr.api.ImageType.DENSE_FLUORESCENCE,
                        ripr.api.MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.AUTOMATIC).build();

        RelativeIntensityPatternParameters result =
                SingleChannelGeneralisationBenchmark.apply(
                        dense,
                        SingleChannelGeneralisationBenchmark.Attempt
                                .A208_MAX_ACCURACY_DECLARED_TYPE,
                        40);

        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, result.estimator);
        assertEquals(Reconciler.Reference.CONSECUTIVE, result.reference);
        assertEquals(ripr.core.PairAligner.PixelSupport.GRADIENT, result.pixelSupport);
        assertEquals(Preprocessing.MEDIAN_3X3, result.preprocessing);
        assertEquals(90.0, result.ceilingPercentile, 0.0);
        assertEquals(0.0, result.outlierMads, 0.0);
    }

}
