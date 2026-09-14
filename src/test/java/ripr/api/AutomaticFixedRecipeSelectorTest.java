/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import org.junit.Test;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class AutomaticFixedRecipeSelectorTest {
    @Test
    public void declaredJumpFluorescenceUsesMaximumAccuracyRoutesWithoutReadingTheImage() {
        AutomaticRegistrationSelector.Result dense = resolve(
                ImageType.DENSE_FLUORESCENCE, MotionType.INTERMITTENT_JUMPS);
        AutomaticRegistrationSelector.Result sparse = resolve(
                ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE, MotionType.INTERMITTENT_JUMPS);
        assertNull("a fixed policy must not measure the recording", dense.evidence);
        assertNull("a fixed policy must not measure the recording", sparse.evidence);
        assertFalse(dense.fallback);
        assertFalse(sparse.fallback);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, dense.parameters.estimator);
        assertEquals(Reconciler.Reference.CONSECUTIVE, dense.parameters.reference);
        assertEquals(Preprocessing.MEDIAN_3X3, dense.parameters.preprocessing);
        assertEquals(90.0, dense.parameters.ceilingPercentile, 0.0);
        assertEquals(0.0, dense.parameters.outlierMads, 0.0);
        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, sparse.parameters.estimator);
        assertEquals(Reconciler.Reference.MULTILAG, sparse.parameters.reference);
        assertEquals(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                sparse.parameters.pixelSelectionStrategy);
        for (AutomaticRegistrationSelector.Result result :
                new AutomaticRegistrationSelector.Result[]{dense, sparse}) {
            assertEquals(SelectionMode.MANUAL, result.parameters.selectionMode);
            assertTrue(result.parameters.recipeProvenance.contains(
                    "selector_model=" + AutomaticEmissionPolicy.VERSION));
        }
        assertTrue(dense.parameters.recipeProvenance.contains(
                "fixed_route=dense_filtered_previous_image_ecc"));
        assertTrue(sparse.parameters.recipeProvenance.contains(
                "fixed_route=sparse_lowlight_image_and_motion_logratio_preset"));
    }

    @Test
    public void ordinaryFluorescenceUsesTheSameDeclaredTypePolicy() {
        AutomaticRegistrationSelector.Result dense = resolve(
                ImageType.DENSE_FLUORESCENCE, MotionType.STEADY_DIRECTIONAL_DRIFT);
        AutomaticRegistrationSelector.Result sparse = resolve(
                ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                MotionType.STEADY_DIRECTIONAL_DRIFT);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, dense.parameters.estimator);
        assertEquals(Reconciler.Reference.CONSECUTIVE, dense.parameters.reference);
        assertEquals(Preprocessing.MEDIAN_3X3, dense.parameters.preprocessing);
        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, sparse.parameters.estimator);
        assertEquals(Reconciler.Reference.MULTILAG, sparse.parameters.reference);
    }

    @Test
    public void imageAndMotionPresetRemainsASeparateFixedChoice() {
        RelativeIntensityPatternParameters preset = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.RECOMMENDED)
                .build();
        assertEquals(SelectionMode.RECOMMENDED, preset.selectionMode);
        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, preset.estimator);
        assertEquals(Reconciler.Reference.MULTILAG, preset.reference);
        assertEquals(0.0, preset.outlierMads, 0.0);
        assertTrue(Double.isNaN(preset.outlierProtectionResidualGain));
    }

    private static AutomaticRegistrationSelector.Result resolve(
            ImageType imageType, MotionType motionType) {
        RelativeIntensityPatternParameters automatic =
                RelativeIntensityPatternParameters.builder()
                        .recommendation(imageType, motionType)
                        .selectionMode(SelectionMode.AUTOMATIC)
                        .build();
        ImagePlus unread = new ImagePlus("must not be inspected", new FloatProcessor(1, 1));
        try {
            return RelativeIntensityPatternRegistration.resolveAutomaticSettings(unread, automatic);
        } finally {
            unread.close();
        }
    }
}
