/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.SelectionMode;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;

import static org.junit.Assert.assertEquals;

public class IndependentCircadianBenchmarkTest {

    @Test
    public void routesRetainTheirDeclaredEstimatorAndReferenceChoice() {
        assertEquals(SelectionMode.AUTOMATIC,
                parameters(ExternalPublicationBenchmark.InternalRecipe.AUTOMATIC).selectionMode);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION,
                parameters(ExternalPublicationBenchmark.InternalRecipe.AREA_GRID).estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                parameters(ExternalPublicationBenchmark.InternalRecipe.AREA_NEWTON).estimator);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC,
                parameters(ExternalPublicationBenchmark.InternalRecipe.AREA_ECC).estimator);
        assertFirstImage(ExternalPublicationBenchmark.InternalRecipe.AREA_GRID_FIRST,
                PairEstimator.Kind.AREA_CORRELATION);
        assertFirstImage(ExternalPublicationBenchmark.InternalRecipe.AREA_NEWTON_FIRST,
                PairEstimator.Kind.AREA_CORRELATION_NEWTON);
        assertFirstImage(ExternalPublicationBenchmark.InternalRecipe.AREA_ECC_FIRST,
                PairEstimator.Kind.AREA_CORRELATION_ECC);
    }

    private static RelativeIntensityPatternParameters parameters(
            ExternalPublicationBenchmark.InternalRecipe recipe) {
        return ExternalPublicationBenchmark.parameters(
                "DENSE_FLUOR", "INTERMITTENT_JUMPS",
                ExternalPublicationBenchmark.Scope.TRANSLATION, recipe);
    }

    private static void assertFirstImage(ExternalPublicationBenchmark.InternalRecipe recipe,
                                         PairEstimator.Kind estimator) {
        RelativeIntensityPatternParameters parameters = parameters(recipe);
        assertEquals(estimator, parameters.estimator);
        assertEquals(Reconciler.Reference.FIXED, parameters.reference);
        assertEquals(1, parameters.referenceFrame);
    }
}
