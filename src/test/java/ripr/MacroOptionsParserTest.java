/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.MotionType;
import ripr.api.SelectionMode;
import ripr.api.Preprocessing;
import ripr.api.PixelSelectionStrategy;
import ripr.core.PairAligner;
import ripr.core.RobustNorm;
import ripr.core.RotationMode;
import ripr.core.Warper;
import org.junit.Test;

import static org.junit.Assert.*;

public class MacroOptionsParserTest {
    @Test
    public void longitudinalModeRoundTripsAndOwnsItsFixedRecipe() {
        RelativeIntensityPatternParameters parsed = MacroOptionsParser.parse(
                "image_type=SPARSE_LOW_LIGHT_FLUORESCENCE motion_type=INTERMITTENT_JUMPS "
                + "selection_mode=longitudinal_accuracy channel=2 no_crop");
        assertEquals(SelectionMode.LONGITUDINAL_ACCURACY, parsed.selectionMode);
        assertEquals(2, parsed.channel);
        assertFalse(parsed.crop);
        assertTrue(new RelativeIntensityPatternDialogModel(parsed).toMacroOptions()
                .contains("selection_mode=longitudinal_accuracy"));
        try {
            MacroOptionsParser.parse(
                    "selection_mode=longitudinal_accuracy estimator=area_correlation_ecc");
            fail("expected fixed-route override to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("longitudinal_accuracy"));
        }
    }

    @Test
    public void recommendedLoadsImageAndMotionSpecificValues() {
        RelativeIntensityPatternParameters p = MacroOptionsParser.parse(
                "image_type=DENSE_FLUORESCENCE motion_type=CURVED_OSCILLATING_DRIFT recommended");
        assertTrue(p.useRecommendation);
        assertEquals(25.0, p.floorPercentile, 0.0);
        assertEquals(RobustNorm.TUKEY, p.norm);
    }

    @Test
    public void recommendedPreservesSharedEdgePerformanceSettings() {
        RelativeIntensityPatternParameters p = MacroOptionsParser.parse(
                "image_type=PHASE_CONTRAST motion_type=STEADY_DIRECTIONAL_DRIFT recommended");
        assertEquals(PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT, p.pixelSupport);
        assertEquals(12, p.maxIterations);
        assertEquals(50_000, p.maxSamples);
    }

    @Test
    public void explicitOptionsRoundTripWithoutDependingOnRecommendationTable() {
        RelativeIntensityPatternParameters original = RelativeIntensityPatternParameters.builder()
                .imageType(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE)
                .motionType(MotionType.INTERMITTENT_JUMPS)
                .useRecommendation(false)
                .norm(RobustNorm.HUBER)
                .pixelSupport(PairAligner.PixelSupport.GRADIENT)
                .gradientFraction(2.0)
                .estimationScale(0.5)
                .preprocessing(Preprocessing.MEDIAN_3X3)
                .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_MOST_UNSTABLE)
                .pixelSelectionPreprocessing(Preprocessing.ANSCOMBE)
                .pixelRemovalPercent(37.5)
                .floorPercentile(12.5)
                .ceilingPercentile(97.5)
                .fitRotation(true)
                .maxRotationDegrees(7.25)
                .incrementalRotation(true)
                .minimumRotationResidualGain(0.0075)
                .rotationRecipeId("support_all__band_no_top_10__filter_gaussian_0_7__mask_none")
                .globalRotationProposal(true)
                .rotationSelectorStep90(3.25)
                .outlierProtectionResidualGain(0.04)
                .maxSamples(54321)
                .crop(false)
                .build();
        String options = new RelativeIntensityPatternDialogModel(original).toMacroOptions();
        RelativeIntensityPatternParameters replay = MacroOptionsParser.parse(options);
        assertFalse(replay.useRecommendation);
        assertEquals(original.imageType, replay.imageType);
        assertEquals(original.motionType, replay.motionType);
        assertEquals(original.norm, replay.norm);
        assertEquals(original.pixelSupport, replay.pixelSupport);
        assertEquals(original.gradientFraction, replay.gradientFraction, 0.0);
        assertEquals(original.estimationScale, replay.estimationScale, 0.0);
        assertEquals(original.preprocessing, replay.preprocessing);
        assertEquals(original.pixelSelectionStrategy, replay.pixelSelectionStrategy);
        assertEquals(original.pixelSelectionPreprocessing, replay.pixelSelectionPreprocessing);
        assertEquals(original.pixelRemovalPercent, replay.pixelRemovalPercent, 0.0);
        assertEquals(original.floorPercentile, replay.floorPercentile, 0.0);
        assertEquals(original.ceilingPercentile, replay.ceilingPercentile, 0.0);
        assertEquals(original.fitRotation, replay.fitRotation);
        assertEquals(original.maxRotationDegrees, replay.maxRotationDegrees, 0.0);
        assertEquals(original.incrementalRotation, replay.incrementalRotation);
        assertEquals(original.minimumRotationResidualGain,
                replay.minimumRotationResidualGain, 0.0);
        assertEquals(original.rotationRecipeId, replay.rotationRecipeId);
        assertEquals(original.globalRotationProposal, replay.globalRotationProposal);
        assertEquals(original.rotationSelectorStep90, replay.rotationSelectorStep90, 0.0);
        assertEquals(original.outlierProtectionResidualGain,
                replay.outlierProtectionResidualGain, 0.0);
        assertEquals(original.outlierProtectionResidualGain,
                replay.registrationOptions().outlierProtectionResidualGain, 0.0);
        assertEquals(original.maxSamples, replay.maxSamples);
        assertEquals(original.crop, replay.crop);
    }

    @Test
    public void oldMacrosRemainTranslationOnly() {
        RelativeIntensityPatternParameters parsed = MacroOptionsParser.parse("manual max_shift=12");
        assertFalse(parsed.fitRotation);
        assertFalse(parsed.registrationOptions().aligner.fitRotation);
        assertTrue(parsed.incrementalRotation);
    }

    @Test
    public void knownEventSettingsRoundTripWithOneBasedFrames() {
        RelativeIntensityPatternParameters original = RelativeIntensityPatternParameters.builder()
                .useRecommendation(false)
                .rotationMode(RotationMode.KNOWN_EVENTS)
                .rotationEventFrames(25, 51)
                .rotationEventWindow(4)
                .maxRotationDegrees(8)
                .build();
        String options = new RelativeIntensityPatternDialogModel(original).toMacroOptions();
        assertTrue(options.contains("rotation_mode=known_events"));
        assertTrue(options.contains("rotation_events=25,51"));
        assertFalse(options.contains("fit_rotation"));

        RelativeIntensityPatternParameters replay = MacroOptionsParser.parse(options);
        assertEquals(RotationMode.KNOWN_EVENTS, replay.rotationMode);
        assertArrayEquals(new int[]{25, 51}, replay.rotationEventFrames());
        assertEquals(4, replay.rotationEventWindow);
        assertArrayEquals(new int[]{24, 50}, replay.registrationOptions().rotationEventFrames);
    }

    @Test
    public void legacyFitRotationStillMeansContinuousMode() {
        RelativeIntensityPatternParameters parsed = MacroOptionsParser.parse(
                "manual fit_rotation max_rotation_degrees=7");
        assertEquals(RotationMode.CONTINUOUS, parsed.rotationMode);
        assertTrue(parsed.registrationOptions().aligner.fitRotation);
    }

    @Test
    public void contradictoryOldAndNewRotationTokensAreRejectedClearly() {
        try {
            MacroOptionsParser.parse("manual fit_rotation rotation_mode=off");
            fail("expected contradictory rotation controls to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("keep only one rotation control"));
        }
    }

    @Test
    public void fourierInterpolationParsesAndRoundTrips() {
        RelativeIntensityPatternParameters parsed = MacroOptionsParser.parse("manual interpolation=FOURIER");
        assertEquals(Warper.Interpolation.FOURIER, parsed.interpolation);
        assertTrue(new RelativeIntensityPatternDialogModel(parsed).toMacroOptions()
                .contains("interpolation=FOURIER"));
    }

    @Test
    public void fourierInterpolationAcceptsRotation() {
        RelativeIntensityPatternParameters parsed = MacroOptionsParser.parse(
                "manual fit_rotation max_rotation_degrees=8 interpolation=FOURIER");
        assertTrue(parsed.fitRotation);
        assertEquals(Warper.Interpolation.FOURIER, parsed.interpolation);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rigidModeRejectsANonPositiveAngularBound() {
        MacroOptionsParser.parse("manual fit_rotation max_rotation_degrees=0");
    }

    @Test
    public void automaticFilterSelectionRoundTripsWithoutExplicitAddOns() {
        RelativeIntensityPatternParameters original = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE,
                        MotionType.CURVED_OSCILLATING_DRIFT)
                .automaticFilterSelection(true).build();
        String options = new RelativeIntensityPatternDialogModel(original).toMacroOptions();
        assertTrue(options.contains("selection_mode=automatic"));
        assertFalse(options.contains("preprocessing="));
        assertFalse(options.contains("pixel_selection="));
        assertFalse(options.contains("estimator="));
        RelativeIntensityPatternParameters replay = MacroOptionsParser.parse(options);
        assertEquals(SelectionMode.AUTOMATIC, replay.selectionMode);
        assertTrue(replay.automaticFilterSelection);
        assertEquals(original.norm, replay.norm);
        assertEquals(original.pixelSupport, replay.pixelSupport);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAutomaticFilterSelectionWithExplicitFilter() {
        MacroOptionsParser.parse("manual automatic_filters preprocessing=MEDIAN_3X3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownOption() {
        MacroOptionsParser.parse("manual mystery=7");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRecommendedCombinedWithManualFitOverride() {
        MacroOptionsParser.parse("recommended norm=HUBER");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRecommendedCombinedWithPreprocessingOverride() {
        MacroOptionsParser.parse("recommended preprocessing=MEDIAN_3X3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRecommendedCombinedWithPixelSelectionOverride() {
        MacroOptionsParser.parse("recommended pixel_selection=REMOVE_LEAST_INFORMATIVE");
    }
}
