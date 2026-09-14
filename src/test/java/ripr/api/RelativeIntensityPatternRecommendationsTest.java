/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.PairAligner;
import ripr.core.RobustNorm;
import org.junit.Test;

import static org.junit.Assert.*;

public class RelativeIntensityPatternRecommendationsTest {
    @Test
    public void coversEveryImageAndMotionCombination() {
        for (ImageType image : ImageType.values()) {
            for (MotionType motion : MotionType.values()) {
                RelativeIntensityPatternPreset preset = RelativeIntensityPatternRecommendations.forTypes(image, motion);
                assertNotNull(preset.name());
                assertTrue(preset.options().aligner.profileGain);
            }
        }
    }

    @Test
    public void denseFluorescenceCurvedDriftExcludesDimmestQuarter() {
        RelativeIntensityPatternPreset preset = RelativeIntensityPatternRecommendations.forTypes(
                ImageType.DENSE_FLUORESCENCE, MotionType.CURVED_OSCILLATING_DRIFT);
        assertEquals(25.0, preset.floorPercentile(), 0.0);
        assertTrue(Double.isNaN(preset.ceilingPercentile()));
        assertEquals(RobustNorm.TUKEY, preset.norm());
    }

    @Test
    public void phaseSteadyDriftUsesSharedSignificantEdges() {
        RelativeIntensityPatternPreset preset = RelativeIntensityPatternRecommendations.forTypes(
                ImageType.PHASE_CONTRAST, MotionType.STEADY_DIRECTIONAL_DRIFT);
        assertEquals(PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT, preset.support());
        assertEquals(12, preset.maxIterations());
        assertEquals(50_000, preset.maxSamples());
    }

    @Test
    public void preprocessingIsRecommendedOnlyForTheFourValidatedCategories() {
        assertEquals(Preprocessing.GAUSSIAN_0_7, RelativeIntensityPatternRecommendations.forTypes(
                ImageType.PHASE_CONTRAST, MotionType.INTERMITTENT_JUMPS).preprocessing());
        assertEquals(Preprocessing.GAUSSIAN_1_0, RelativeIntensityPatternRecommendations.forTypes(
                ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK).preprocessing());
        assertEquals(Preprocessing.MEDIAN_3X3, RelativeIntensityPatternRecommendations.forTypes(
                ImageType.BRIGHTFIELD_DIC, MotionType.SUBPIXEL_RANDOM_WALK).preprocessing());
        assertEquals(Preprocessing.MEDIAN_3X3, RelativeIntensityPatternRecommendations.forTypes(
                ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                MotionType.STEADY_DIRECTIONAL_DRIFT).preprocessing());
        assertEquals(Preprocessing.NONE, RelativeIntensityPatternRecommendations.forTypes(
                ImageType.FIDUCIAL_STATIC, MotionType.INTERMITTENT_JUMPS).preprocessing());
    }

    @Test
    public void sparseLowLightUsesValidatedSpatialMaskExceptForRandomWalk() {
        for (MotionType motion : MotionType.values()) {
            RelativeIntensityPatternPreset preset = RelativeIntensityPatternRecommendations.forTypes(
                    ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE, motion);
            PixelSelectionStrategy expected = motion == MotionType.SUBPIXEL_RANDOM_WALK
                    ? PixelSelectionStrategy.NONE
                    : PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE;
            assertEquals(expected, preset.pixelSelectionStrategy());
            assertEquals(Preprocessing.NONE, preset.pixelSelectionPreprocessing());
            assertEquals(25.0, preset.pixelRemovalPercent(), 0.0);
        }
        assertEquals(PixelSelectionStrategy.NONE, RelativeIntensityPatternRecommendations.forTypes(
                ImageType.DENSE_FLUORESCENCE,
                MotionType.CURVED_OSCILLATING_DRIFT).pixelSelectionStrategy());
    }

    @Test
    public void intermittentJumpRecommendationDoesNotEraseDeclaredRealSteps() {
        for (ImageType image : ImageType.values()) {
            RelativeIntensityPatternParameters jumps = RelativeIntensityPatternParameters.builder()
                    .recommendation(image, MotionType.INTERMITTENT_JUMPS).build();
            assertEquals(0.0, jumps.outlierMads, 0.0);
            RelativeIntensityPatternParameters smooth = RelativeIntensityPatternParameters.builder()
                    .recommendation(image, MotionType.STEADY_DIRECTIONAL_DRIFT).build();
            assertEquals(6.0, smooth.outlierMads, 0.0);
        }
    }
}
