/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.PixelSelectionStrategy;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;

import static org.junit.Assert.assertEquals;

public class SingleChannelFailureTuningBenchmarkTest {

    private static RelativeIntensityPatternParameters base() {
        return RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        MotionType.INTERMITTENT_JUMPS)
                .build();
    }

    @Test
    public void previousEccUsesOnlyTheRequestedReferenceAndEstimator() {
        RelativeIntensityPatternParameters result = SingleChannelFailureTuningBenchmark.apply(
                base(), SingleChannelFailureTuningBenchmark.Attempt.A003_PREVIOUS_ECC);
        assertEquals(Reconciler.Reference.CONSECUTIVE, result.reference);
        assertEquals(PairEstimator.Kind.AREA_CORRELATION_ECC, result.estimator);
        assertEquals(PixelSelectionStrategy.NONE, result.pixelSelectionStrategy);
        assertEquals(1, result.channel);
    }

    @Test
    public void pulseMasksStayOnPreviousFrameLogRatio() {
        RelativeIntensityPatternParameters unstable = SingleChannelFailureTuningBenchmark.apply(
                base(), SingleChannelFailureTuningBenchmark.Attempt.A005_PREVIOUS_REMOVE_MOST_UNSTABLE_25);
        RelativeIntensityPatternParameters growth = SingleChannelFailureTuningBenchmark.apply(
                base(), SingleChannelFailureTuningBenchmark.Attempt.A006_PREVIOUS_REMOVE_MOST_LAG_GROWTH_25);
        assertEquals(Reconciler.Reference.CONSECUTIVE, unstable.reference);
        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, unstable.estimator);
        assertEquals(PixelSelectionStrategy.REMOVE_MOST_UNSTABLE, unstable.pixelSelectionStrategy);
        assertEquals(PixelSelectionStrategy.REMOVE_MOST_LAG_GROWTH, growth.pixelSelectionStrategy);
    }
}
