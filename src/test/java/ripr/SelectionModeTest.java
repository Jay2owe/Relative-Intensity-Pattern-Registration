/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternBatchParameters;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.MotionType;
import ripr.api.PixelSelectionStrategy;
import ripr.api.Preprocessing;
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
import ripr.core.PairAligner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The three modes are exclusive, replayable, and still accept the older macro syntax. */
public class SelectionModeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void exactlyOneModeIsEverActive() {
        RelativeIntensityPatternParameters recommended = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.INTERMITTENT_JUMPS).build();
        assertEquals(SelectionMode.RECOMMENDED, recommended.selectionMode);
        assertTrue(recommended.useRecommendation);
        assertFalse(recommended.automaticFilterSelection);

        RelativeIntensityPatternParameters automatic = recommended.toBuilder()
                .selectionMode(SelectionMode.AUTOMATIC).build();
        assertEquals(SelectionMode.AUTOMATIC, automatic.selectionMode);
        assertFalse("automatic and recommended can no longer both be on",
                automatic.useRecommendation);
        assertTrue(automatic.automaticFilterSelection);

        RelativeIntensityPatternParameters manual = recommended.toBuilder()
                .selectionMode(SelectionMode.MANUAL).build();
        assertEquals(SelectionMode.MANUAL, manual.selectionMode);
        assertFalse(manual.useRecommendation);
        assertFalse(manual.automaticFilterSelection);
    }

    @Test
    public void theLegacyFlagsStillSelectTheSameModes() {
        assertEquals(SelectionMode.RECOMMENDED,
                MacroOptionsParser.parse("recommended").selectionMode);
        assertEquals(SelectionMode.AUTOMATIC,
                MacroOptionsParser.parse("automatic_filters").selectionMode);
        assertEquals(SelectionMode.MANUAL, MacroOptionsParser.parse("manual").selectionMode);
        assertEquals(SelectionMode.MANUAL, MacroOptionsParser.parse("").selectionMode);
        // The legacy flag must still load the same base model it always did.
        RelativeIntensityPatternParameters legacy = MacroOptionsParser.parse(
                "image_type=SPARSE_LOW_LIGHT_FLUORESCENCE motion_type=INTERMITTENT_JUMPS "
                        + "automatic_filters");
        RelativeIntensityPatternParameters modern = MacroOptionsParser.parse(
                "image_type=SPARSE_LOW_LIGHT_FLUORESCENCE motion_type=INTERMITTENT_JUMPS "
                        + "selection_mode=automatic");
        assertEquals(modern.selectionMode, legacy.selectionMode);
        assertEquals(modern.norm, legacy.norm);
        assertEquals(modern.pixelSupport, legacy.pixelSupport);
    }

    @Test
    public void theNewTokenAcceptsEveryMode() {
        assertEquals(SelectionMode.RECOMMENDED,
                MacroOptionsParser.parse("selection_mode=recommended").selectionMode);
        assertEquals(SelectionMode.AUTOMATIC,
                MacroOptionsParser.parse("selection_mode=automatic").selectionMode);
        assertEquals(SelectionMode.MANUAL,
                MacroOptionsParser.parse("selection_mode=manual").selectionMode);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aContradictoryModePairIsRejectedRatherThanGuessed() {
        MacroOptionsParser.parse("selection_mode=manual automatic_filters");
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticModeRejectsAnExplicitPixelSupport() {
        MacroOptionsParser.parse("selection_mode=automatic support=GRADIENT");
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticModeRejectsAnExplicitBrightnessLimit() {
        MacroOptionsParser.parse("selection_mode=automatic ceiling=90");
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticModeRejectsAnExplicitFilter() {
        MacroOptionsParser.parse("selection_mode=automatic preprocessing=MEDIAN_3X3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticModeRejectsAnExplicitEstimator() {
        MacroOptionsParser.parse("selection_mode=automatic estimator=area_correlation_newton");
    }

    @Test
    public void aManualRecipeSurvivesAMacroRoundTrip() {
        RelativeIntensityPatternParameters original = RelativeIntensityPatternParameters.builder()
                .imageType(ImageType.BRIGHTFIELD_DIC)
                .motionType(MotionType.STEADY_DIRECTIONAL_DRIFT)
                .selectionMode(SelectionMode.MANUAL)
                .pixelSupport(PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT)
                .gradientFraction(0.25)
                .floorPercentile(25.0)
                .preprocessing(Preprocessing.MEDIAN_3X3)
                .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE)
                .pixelSelectionPreprocessing(Preprocessing.GAUSSIAN_1_0)
                .pixelRemovalPercent(40.0)
                .maxIterations(12).maxSamples(50_000)
                .channel(2).slice(3).threads(2).build();
        String options = new RelativeIntensityPatternDialogModel(original).toMacroOptions();
        RelativeIntensityPatternParameters replay = MacroOptionsParser.parse(options);
        assertEquals(SelectionMode.MANUAL, replay.selectionMode);
        assertEquals(RegistrationRecipe.of(original), RegistrationRecipe.of(replay));
        assertEquals(original.channel, replay.channel);
        assertEquals(original.slice, replay.slice);
        assertEquals(original.threads, replay.threads);
        assertEquals(original.norm, replay.norm);
        assertEquals(original.reference, replay.reference);
    }

    @Test
    public void everySweptRecipeSurvivesAMacroRoundTrip() {
        RelativeIntensityPatternParameters base = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK).build();
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            RelativeIntensityPatternParameters resolved = recipe.applyTo(base);
            String options = new RelativeIntensityPatternDialogModel(resolved).toMacroOptions();
            RelativeIntensityPatternParameters replay = MacroOptionsParser.parse(options);
            assertEquals(recipe.id(), recipe, RegistrationRecipe.of(replay));
        }
    }

    @Test
    public void aBatchReplaysOneExplicitRecipeOverEveryStack() throws java.io.IOException {
        RelativeIntensityPatternParameters manual = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE, MotionType.CURVED_OSCILLATING_DRIFT)
                .selectionMode(SelectionMode.MANUAL).build();
        RelativeIntensityPatternBatchParameters batch = RelativeIntensityPatternBatchParameters.builder(
                folder.newFolder("manual_input"), folder.newFolder("manual_output"),
                manual).build();
        String options = RelativeIntensityPatternBatchMacroOptionsParser.record(batch);
        assertTrue(options, options.contains("selection_mode=manual"));
        RelativeIntensityPatternBatchParameters replay = RelativeIntensityPatternBatchMacroOptionsParser.parse(options);
        assertEquals(SelectionMode.MANUAL, replay.registration.selectionMode);
        assertEquals(RegistrationRecipe.of(manual), RegistrationRecipe.of(replay.registration));
    }

    @Test
    public void anAutomaticBatchResolvesPerStackRatherThanFreezingOneRecipe() throws java.io.IOException {
        RelativeIntensityPatternParameters automatic = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        MotionType.STEADY_DIRECTIONAL_DRIFT)
                .selectionMode(SelectionMode.AUTOMATIC).build();
        RelativeIntensityPatternBatchParameters batch = RelativeIntensityPatternBatchParameters.builder(
                folder.newFolder("automatic_input"), folder.newFolder("automatic_output"),
                automatic).build();
        String options = RelativeIntensityPatternBatchMacroOptionsParser.record(batch);
        assertTrue(options, options.contains("selection_mode=automatic"));
        assertFalse("an automatic batch must not freeze one recipe into its macro",
                options.contains("preprocessing="));
        assertFalse("an automatic request must let the per-stack model choose the estimator",
                options.contains("estimator="));
        RelativeIntensityPatternBatchParameters replay = RelativeIntensityPatternBatchMacroOptionsParser.parse(options);
        assertEquals(SelectionMode.AUTOMATIC, replay.registration.selectionMode);
    }

    @Test
    public void aRecommendedRunRoundTripsWithoutClaimingControlsItDoesNotHonour() {
        RelativeIntensityPatternParameters recommended = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.FIDUCIAL_STATIC, MotionType.STEADY_DIRECTIONAL_DRIFT)
                .build();
        String options = new RelativeIntensityPatternDialogModel(recommended).toMacroOptions();
        assertTrue(options, options.contains("selection_mode=recommended"));
        assertFalse(options, options.contains("support="));
        assertFalse(options, options.contains("norm="));
        RelativeIntensityPatternParameters replay = MacroOptionsParser.parse(options);
        assertEquals(SelectionMode.RECOMMENDED, replay.selectionMode);
        assertEquals(RegistrationRecipe.of(recommended), RegistrationRecipe.of(replay));
    }

    @Test
    public void aSweptCombinationIsAlwaysAnExplicitManualBundle() {
        RelativeIntensityPatternParameters automatic = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK)
                .selectionMode(SelectionMode.AUTOMATIC).build();
        RelativeIntensityPatternSweepPlan plan = new RelativeIntensityPatternSweepPlan(automatic,
                new RelativeIntensityPatternSweepPlan.Axis(RelativeIntensityPatternSweepPlan.Parameter.PIXEL_SUPPORT,
                        "ALL, GRADIENT, MUTUAL_NOISE_GRADIENT"));
        assertEquals(3, plan.combinations.size());
        for (RelativeIntensityPatternSweepPlan.Combination combination : plan.combinations) {
            assertEquals(SelectionMode.MANUAL, combination.parameters.selectionMode);
            assertTrue(combination.macroOptions().contains("selection_mode=manual"));
            assertEquals(RegistrationRecipe.of(combination.parameters), combination.recipe());
        }
    }
}
