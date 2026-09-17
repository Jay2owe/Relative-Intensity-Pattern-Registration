/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.ImageType;
import logratio.api.LogRatioBatchParameters;
import logratio.api.LogRatioParameters;
import logratio.api.MotionType;
import logratio.api.PixelSelectionStrategy;
import logratio.api.Preprocessing;
import logratio.api.RegistrationRecipe;
import logratio.api.SelectionMode;
import logratio.core.PairAligner;
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
        LogRatioParameters recommended = LogRatioParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.INTERMITTENT_JUMPS).build();
        assertEquals(SelectionMode.RECOMMENDED, recommended.selectionMode);
        assertTrue(recommended.useRecommendation);
        assertFalse(recommended.automaticFilterSelection);

        LogRatioParameters automatic = recommended.toBuilder()
                .selectionMode(SelectionMode.AUTOMATIC).build();
        assertEquals(SelectionMode.AUTOMATIC, automatic.selectionMode);
        assertFalse("automatic and recommended can no longer both be on",
                automatic.useRecommendation);
        assertTrue(automatic.automaticFilterSelection);

        LogRatioParameters manual = recommended.toBuilder()
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
        LogRatioParameters legacy = MacroOptionsParser.parse(
                "image_type=SPARSE_LOW_LIGHT_FLUORESCENCE motion_type=INTERMITTENT_JUMPS "
                        + "automatic_filters");
        LogRatioParameters modern = MacroOptionsParser.parse(
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

    @Test
    public void aManualRecipeSurvivesAMacroRoundTrip() {
        LogRatioParameters original = LogRatioParameters.builder()
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
        String options = new LogRatioDialogModel(original).toMacroOptions();
        LogRatioParameters replay = MacroOptionsParser.parse(options);
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
        LogRatioParameters base = LogRatioParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK).build();
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            LogRatioParameters resolved = recipe.applyTo(base);
            String options = new LogRatioDialogModel(resolved).toMacroOptions();
            LogRatioParameters replay = MacroOptionsParser.parse(options);
            assertEquals(recipe.id(), recipe, RegistrationRecipe.of(replay));
        }
    }

    @Test
    public void aBatchReplaysOneExplicitRecipeOverEveryStack() throws java.io.IOException {
        LogRatioParameters manual = LogRatioParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE, MotionType.CURVED_OSCILLATING_DRIFT)
                .selectionMode(SelectionMode.MANUAL).build();
        LogRatioBatchParameters batch = LogRatioBatchParameters.builder(
                folder.newFolder("manual_input"), folder.newFolder("manual_output"),
                manual).build();
        String options = LogRatioBatchMacroOptionsParser.record(batch);
        assertTrue(options, options.contains("selection_mode=manual"));
        LogRatioBatchParameters replay = LogRatioBatchMacroOptionsParser.parse(options);
        assertEquals(SelectionMode.MANUAL, replay.registration.selectionMode);
        assertEquals(RegistrationRecipe.of(manual), RegistrationRecipe.of(replay.registration));
    }

    @Test
    public void anAutomaticBatchResolvesPerStackRatherThanFreezingOneRecipe() throws java.io.IOException {
        LogRatioParameters automatic = LogRatioParameters.builder()
                .recommendation(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        MotionType.STEADY_DIRECTIONAL_DRIFT)
                .selectionMode(SelectionMode.AUTOMATIC).build();
        LogRatioBatchParameters batch = LogRatioBatchParameters.builder(
                folder.newFolder("automatic_input"), folder.newFolder("automatic_output"),
                automatic).build();
        String options = LogRatioBatchMacroOptionsParser.record(batch);
        assertTrue(options, options.contains("selection_mode=automatic"));
        assertFalse("an automatic batch must not freeze one recipe into its macro",
                options.contains("preprocessing="));
        LogRatioBatchParameters replay = LogRatioBatchMacroOptionsParser.parse(options);
        assertEquals(SelectionMode.AUTOMATIC, replay.registration.selectionMode);
    }

    @Test
    public void aRecommendedRunRoundTripsWithoutClaimingControlsItDoesNotHonour() {
        LogRatioParameters recommended = LogRatioParameters.builder()
                .recommendation(ImageType.FIDUCIAL_STATIC, MotionType.STEADY_DIRECTIONAL_DRIFT)
                .build();
        String options = new LogRatioDialogModel(recommended).toMacroOptions();
        assertTrue(options, options.contains("selection_mode=recommended"));
        assertFalse(options, options.contains("support="));
        assertFalse(options, options.contains("norm="));
        LogRatioParameters replay = MacroOptionsParser.parse(options);
        assertEquals(SelectionMode.RECOMMENDED, replay.selectionMode);
        assertEquals(RegistrationRecipe.of(recommended), RegistrationRecipe.of(replay));
    }

    @Test
    public void aSweptCombinationIsAlwaysAnExplicitManualBundle() {
        LogRatioParameters automatic = LogRatioParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK)
                .selectionMode(SelectionMode.AUTOMATIC).build();
        LogRatioSweepPlan plan = new LogRatioSweepPlan(automatic,
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.PIXEL_SUPPORT,
                        "ALL, GRADIENT, MUTUAL_NOISE_GRADIENT"));
        assertEquals(3, plan.combinations.size());
        for (LogRatioSweepPlan.Combination combination : plan.combinations) {
            assertEquals(SelectionMode.MANUAL, combination.parameters.selectionMode);
            assertTrue(combination.macroOptions().contains("selection_mode=manual"));
            assertEquals(RegistrationRecipe.of(combination.parameters), combination.recipe());
        }
    }
}
