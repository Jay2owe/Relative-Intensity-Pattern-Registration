package ripr;

import org.junit.Test;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.SelectionMode;
import ripr.core.RotationMode;
import ripr.core.PairEstimator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class DynamicForegroundBenchmarkTest {
    @Test
    public void productionPilotRequestsAutomaticTranslation() {
        RelativeIntensityPatternParameters parameters =
                DynamicForegroundBenchmark.parameters("SUBPIXEL_RANDOM_WALK");
        assertEquals(SelectionMode.AUTOMATIC, parameters.selectionMode);
        assertEquals(RotationMode.OFF, parameters.rotationMode);
    }

    @Test
    public void comparisonRunnerCanRequestEnhancedCorrelationCoefficientTranslation() {
        String previous = System.getProperty("ripr.estimator");
        try {
            System.setProperty("ripr.estimator", "area_correlation_ecc");
            RelativeIntensityPatternParameters parameters =
                    DynamicForegroundBenchmark.parameters("SUBPIXEL_RANDOM_WALK");
            assertEquals(SelectionMode.MANUAL, parameters.selectionMode);
            assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, parameters.estimator);
            assertEquals(RotationMode.OFF, parameters.rotationMode);
        } finally {
            if (previous == null) System.clearProperty("ripr.estimator");
            else System.setProperty("ripr.estimator", previous);
        }
    }

    @Test
    public void quantileUsesAllFrames() {
        assertEquals(2.5, DynamicForegroundBenchmark.quantile(
                new double[] {1, 2, 3, 4}, 0.5), 1e-12);
        assertEquals(4, DynamicForegroundBenchmark.quantile(
                new double[] {1, 2, 3, 4}, 1.0), 1e-12);
    }

    @Test
    public void genericRunnerRejectsNonPositiveExpectedCount() {
        assertThrows(IllegalArgumentException.class, () ->
                DynamicForegroundBenchmark.run(
                        java.nio.file.Paths.get("unused"),
                        java.nio.file.Paths.get("unused.csv"), 0));
    }
}
