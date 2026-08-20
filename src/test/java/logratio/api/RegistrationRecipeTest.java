/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import logratio.core.PairAligner;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The 96 swept recipes, their identifiers, and the parameters each one resolves to. */
public class RegistrationRecipeTest {

    @Test
    public void theCandidateSetIsExactlyNinetySixDistinctRecipes() {
        List<RegistrationRecipe> candidates = RegistrationRecipe.sweptCandidates();
        assertEquals(96, candidates.size());
        Set<String> identifiers = new LinkedHashSet<>();
        for (RegistrationRecipe recipe : candidates) {
            assertTrue("duplicate identifier " + recipe.id(), identifiers.add(recipe.id()));
        }
        assertEquals(96, identifiers.size());
    }

    @Test
    public void everyIdentifierNamesAllFourSweptDimensions() {
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            String id = recipe.id();
            assertTrue(id, id.startsWith("support_"));
            assertTrue(id, id.contains("__band_"));
            assertTrue(id, id.contains("__filter_"));
            assertTrue(id, id.contains("__mask_"));
            assertFalse("a swept recipe must not be marked off-grid", id.endsWith("__offgrid"));
            assertTrue(recipe.isSwept());
        }
    }

    @Test
    public void everyRecipeMapsOntoTheEditableParameterFields() {
        LogRatioParameters base = LogRatioParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK).build();
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            LogRatioParameters resolved = recipe.applyTo(base);
            assertEquals(recipe.pixelSupport, resolved.pixelSupport);
            assertEquals(recipe.gradientFraction, resolved.gradientFraction, 1e-12);
            assertEquals(recipe.preprocessing, resolved.preprocessing);
            assertEquals(recipe.pixelSelectionStrategy, resolved.pixelSelectionStrategy);
            assertEquals(recipe.pixelSelectionPreprocessing, resolved.pixelSelectionPreprocessing);
            assertEquals(recipe.pixelRemovalPercent, resolved.pixelRemovalPercent, 1e-12);
            assertEquals(recipe.maxIterations, resolved.maxIterations);
            assertEquals(recipe.maxSamples, resolved.maxSamples);
            assertSamePercentile(recipe.floorPercentile, resolved.floorPercentile);
            assertSamePercentile(recipe.ceilingPercentile, resolved.ceilingPercentile);
            // Reading the parameters back must reproduce the recipe exactly, which is what makes an
            // automatic choice replayable by hand.
            assertEquals(recipe, RegistrationRecipe.of(resolved));
            assertEquals(recipe.id(), RegistrationRecipe.of(resolved).id());
        }
    }

    @Test
    public void applyingARecipeLeavesEveryOtherSettingAlone() {
        LogRatioParameters base = LogRatioParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE, MotionType.INTERMITTENT_JUMPS)
                .channel(2).slice(3).templateWindow(9).epsilon(2.5).threads(4)
                .outlierMads(4.5).minValidFraction(0.2).lags(1, 3, 9).build();
        RegistrationRecipe recipe = RegistrationRecipe.swept(PairAligner.PixelSupport.GRADIENT,
                RegistrationRecipe.Band.NO_TOP_25, Preprocessing.MEDIAN_3X3,
                RegistrationRecipe.Mask.LEAST_INFORMATIVE_25);
        LogRatioParameters resolved = recipe.applyTo(base);
        assertEquals(base.channel, resolved.channel);
        assertEquals(base.slice, resolved.slice);
        assertEquals(base.templateWindow, resolved.templateWindow);
        assertEquals(base.epsilon, resolved.epsilon, 1e-12);
        assertEquals(base.threads, resolved.threads);
        assertEquals(base.outlierMads, resolved.outlierMads, 1e-12);
        assertEquals(base.minValidFraction, resolved.minValidFraction, 1e-12);
        assertEquals(base.norm, resolved.norm);
        assertEquals(base.reference, resolved.reference);
        assertEquals(base.lags.length, resolved.lags.length);
        assertEquals(SelectionMode.MANUAL, resolved.selectionMode);
    }

    @Test
    public void theBandsCoverExactlyTheDeclaredBrightnessExclusions() {
        assertEquals(RegistrationRecipe.Band.FULL,
                RegistrationRecipe.Band.from(Double.NaN, Double.NaN));
        assertEquals(RegistrationRecipe.Band.NO_TOP_10,
                RegistrationRecipe.Band.from(Double.NaN, 90.0));
        assertEquals(RegistrationRecipe.Band.NO_TOP_25,
                RegistrationRecipe.Band.from(Double.NaN, 75.0));
        assertEquals(RegistrationRecipe.Band.NO_BOTTOM_25,
                RegistrationRecipe.Band.from(25.0, Double.NaN));
        assertNull("an off-grid band must not be silently matched to a swept one",
                RegistrationRecipe.Band.from(10.0, 80.0));
    }

    @Test
    public void anOffGridRecipeIsNotMistakenForASweptOne() {
        RegistrationRecipe offGrid = new RegistrationRecipe(PairAligner.PixelSupport.GRADIENT,
                0.25, 10.0, 80.0, Preprocessing.UNSHARP_0_5,
                PixelSelectionStrategy.REMOVE_MOST_UNSTABLE, Preprocessing.GAUSSIAN_1_0,
                40.0, 12, 50_000);
        assertFalse(offGrid.isSwept());
        assertTrue(offGrid.id().endsWith("__offgrid"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsARemovalPercentageOutsideTheOpenRange() {
        new RegistrationRecipe(PairAligner.PixelSupport.ALL, 0.5, Double.NaN, Double.NaN,
                Preprocessing.NONE, PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                Preprocessing.NONE, 100.0, 25, 200_000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnEmptyComputeBudget() {
        new RegistrationRecipe(PairAligner.PixelSupport.ALL, 0.5, Double.NaN, Double.NaN,
                Preprocessing.NONE, PixelSelectionStrategy.NONE, Preprocessing.NONE, 25.0, 0, 200_000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAMissingSupportRule() {
        new RegistrationRecipe(null, 0.5, Double.NaN, Double.NaN, Preprocessing.NONE,
                PixelSelectionStrategy.NONE, Preprocessing.NONE, 25.0, 25, 200_000);
    }

    @Test
    public void theDescriptionNamesEveryValueRatherThanALabel() {
        RegistrationRecipe recipe = RegistrationRecipe.swept(
                PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT, RegistrationRecipe.Band.NO_BOTTOM_25,
                Preprocessing.GAUSSIAN_1_0, RegistrationRecipe.Mask.LEAST_INFORMATIVE_25);
        String description = recipe.describe();
        assertTrue(description, description.contains("MUTUAL_NOISE_GRADIENT"));
        assertTrue(description, description.contains("exclude below 25"));
        assertTrue(description, description.contains("exclude above off"));
        assertTrue(description, description.contains("GAUSSIAN_1_0"));
        assertTrue(description, description.contains("REMOVE_LEAST_INFORMATIVE"));
        assertTrue(description, description.contains("25%"));
        assertTrue(description, description.contains("iterations"));
        assertTrue(description, description.contains("samples"));
    }

    @Test
    public void everyCategoryRecommendationIsOneOfTheSweptRecipes() {
        Set<String> swept = new LinkedHashSet<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) swept.add(recipe.id());
        for (ImageType image : ImageType.values()) {
            for (MotionType motion : MotionType.values()) {
                LogRatioParameters recommended = LogRatioParameters.builder()
                        .recommendation(image, motion).build();
                RegistrationRecipe recipe = RegistrationRecipe.of(recommended);
                // The compute budget may differ, so compare the four swept dimensions only.
                RegistrationRecipe onGrid = RegistrationRecipe.swept(recipe.pixelSupport,
                        RegistrationRecipe.Band.from(recipe.floorPercentile, recipe.ceilingPercentile),
                        recipe.preprocessing,
                        recipe.pixelSelectionStrategy == PixelSelectionStrategy.NONE
                                ? RegistrationRecipe.Mask.NONE
                                : RegistrationRecipe.Mask.LEAST_INFORMATIVE_25);
                assertNotNull(image + "/" + motion + " has no swept band", onGrid.band());
                assertTrue(image + "/" + motion + " is outside the swept set: " + onGrid.id(),
                        swept.contains(onGrid.id()));
            }
        }
    }

    private static void assertSamePercentile(double expected, double actual) {
        if (Double.isNaN(expected)) {
            assertTrue("expected the percentile to stay disabled", Double.isNaN(actual));
        } else {
            assertEquals(expected, actual, 1e-12);
        }
    }
}
