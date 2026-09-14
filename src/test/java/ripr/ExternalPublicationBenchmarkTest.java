/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;
import ripr.api.PixelSelectionStrategy;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.SelectionMode;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ExternalPublicationBenchmarkTest {
    @Test
    public void translationAndRigidScopesUseTheSameTranslationTrajectory() {
        for (ControlledMotionProfile profile : ControlledMotionProfile.values()) {
            assertEquals(0, ExternalPublicationBenchmark.rigidAngle(profile, 0), 0);
            assertNotEquals(0, ExternalPublicationBenchmark.rigidAngle(profile, 47), 0);
            assertEquals(48, profile.translationTrajectory().length);
        }
    }

    @Test
    public void csvParserKeepsQuotedPathsAndCommasTogether() {
        String[] fields = ExternalPublicationBenchmark.parseCsv(
                "plain,\"folder with spaces\\file.tif\",\"status, with comma\"");
        assertEquals(3, fields.length);
        assertEquals("folder with spaces\\file.tif", fields[1]);
        assertEquals("status, with comma", fields[2]);
    }

    @Test
    public void gainFadeIsOneToOneQuarterAndMonotonic() {
        assertEquals(1.0, ExternalPublicationBenchmark.gain(
                ExternalPublicationBenchmark.Condition.GAIN_FADE, 0), 0);
        assertEquals(0.25, ExternalPublicationBenchmark.gain(
                ExternalPublicationBenchmark.Condition.GAIN_FADE, 47), 1e-15);
        for (int frame = 1; frame < 48; frame++) {
            double previous = ExternalPublicationBenchmark.gain(
                    ExternalPublicationBenchmark.Condition.GAIN_FADE, frame - 1);
            double current = ExternalPublicationBenchmark.gain(
                    ExternalPublicationBenchmark.Condition.GAIN_FADE, frame);
            org.junit.Assert.assertTrue(current < previous);
        }
        assertEquals(1.0, ExternalPublicationBenchmark.gain(
                ExternalPublicationBenchmark.Condition.CLEAN, 47), 0);
    }

    @Test
    public void sharedFinalRecipesResolveOnlyTheirDeclaredDifference() {
        RelativeIntensityPatternParameters automatic = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.AUTOMATIC);
        RelativeIntensityPatternParameters recommended = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.RECOMMENDED);
        RelativeIntensityPatternParameters grid = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.AREA_GRID);
        RelativeIntensityPatternParameters newton = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.AREA_NEWTON);
        RelativeIntensityPatternParameters ecc = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.AREA_ECC);
        RelativeIntensityPatternParameters gridFirst = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.AREA_GRID_FIRST);
        RelativeIntensityPatternParameters newtonFirst = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.AREA_NEWTON_FIRST);
        RelativeIntensityPatternParameters eccFirst = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.AREA_ECC_FIRST);
        RelativeIntensityPatternParameters longitudinal = ExternalPublicationBenchmark.parameters(
                "SPARSE_LOWLIGHT", "STEADY_DIRECTIONAL_DRIFT",
                ExternalPublicationBenchmark.Scope.TRANSLATION,
                ExternalPublicationBenchmark.InternalRecipe.LONGITUDINAL_ACCURACY);

        assertEquals(SelectionMode.AUTOMATIC, automatic.selectionMode);
        assertEquals(SelectionMode.RECOMMENDED, recommended.selectionMode);
        assertEquals(SelectionMode.LONGITUDINAL_ACCURACY, longitudinal.selectionMode);
        assertNotEquals(PixelSelectionStrategy.NONE, recommended.pixelSelectionStrategy);
        assertEquals(PixelSelectionStrategy.NONE, grid.pixelSelectionStrategy);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION, grid.estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_NEWTON, newton.estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, ecc.estimator);
        assertFirstImage(gridFirst, PairEstimator.Kind.AREA_CORRELATION);
        assertFirstImage(newtonFirst, PairEstimator.Kind.AREA_CORRELATION_NEWTON);
        assertFirstImage(eccFirst, PairEstimator.Kind.AREA_CORRELATION_ECC);
    }

    @Test
    public void internalRecipeTokensAreStable() {
        assertEquals(ExternalPublicationBenchmark.InternalRecipe.AUTOMATIC,
                ExternalPublicationBenchmark.InternalRecipe.of("automatic"));
        assertEquals(ExternalPublicationBenchmark.InternalRecipe.AREA_ECC,
                ExternalPublicationBenchmark.InternalRecipe.of("area_ecc"));
        assertEquals(ExternalPublicationBenchmark.InternalRecipe.AREA_GRID_FIRST,
                ExternalPublicationBenchmark.InternalRecipe.of("area_grid_first"));
        assertEquals(ExternalPublicationBenchmark.InternalRecipe.LONGITUDINAL_ACCURACY,
                ExternalPublicationBenchmark.InternalRecipe.of("longitudinal_accuracy"));
    }

    private static void assertFirstImage(RelativeIntensityPatternParameters parameters,
                                         PairEstimator.Kind estimator) {
        assertEquals(SelectionMode.MANUAL, parameters.selectionMode);
        assertEquals(PixelSelectionStrategy.NONE, parameters.pixelSelectionStrategy);
        assertEquals(estimator, parameters.estimator);
        assertEquals(Reconciler.Reference.FIXED, parameters.reference);
        assertEquals(1, parameters.referenceFrame);
    }
}
