/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.ImageType;
import logratio.api.LogRatioParameters;
import logratio.api.MotionType;
import logratio.api.SelectionMode;
import logratio.api.Preprocessing;
import logratio.api.PixelSelectionStrategy;
import logratio.core.PairAligner;
import logratio.core.RobustNorm;
import org.junit.Test;

import static org.junit.Assert.*;

public class MacroOptionsParserTest {
    @Test
    public void recommendedLoadsImageAndMotionSpecificValues() {
        LogRatioParameters p = MacroOptionsParser.parse(
                "image_type=DENSE_FLUORESCENCE motion_type=CURVED_OSCILLATING_DRIFT recommended");
        assertTrue(p.useRecommendation);
        assertEquals(25.0, p.floorPercentile, 0.0);
        assertEquals(RobustNorm.TUKEY, p.norm);
    }

    @Test
    public void recommendedPreservesSharedEdgePerformanceSettings() {
        LogRatioParameters p = MacroOptionsParser.parse(
                "image_type=PHASE_CONTRAST motion_type=STEADY_DIRECTIONAL_DRIFT recommended");
        assertEquals(PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT, p.pixelSupport);
        assertEquals(12, p.maxIterations);
        assertEquals(50_000, p.maxSamples);
    }

    @Test
    public void explicitOptionsRoundTripWithoutDependingOnRecommendationTable() {
        LogRatioParameters original = LogRatioParameters.builder()
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
                .maxSamples(54321)
                .crop(false)
                .build();
        String options = new LogRatioDialogModel(original).toMacroOptions();
        LogRatioParameters replay = MacroOptionsParser.parse(options);
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
        assertEquals(original.maxSamples, replay.maxSamples);
        assertEquals(original.crop, replay.crop);
    }

    @Test
    public void automaticFilterSelectionRoundTripsWithoutExplicitAddOns() {
        LogRatioParameters original = LogRatioParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE,
                        MotionType.CURVED_OSCILLATING_DRIFT)
                .automaticFilterSelection(true).build();
        String options = new LogRatioDialogModel(original).toMacroOptions();
        assertTrue(options.contains("selection_mode=automatic"));
        assertFalse(options.contains("preprocessing="));
        assertFalse(options.contains("pixel_selection="));
        LogRatioParameters replay = MacroOptionsParser.parse(options);
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
