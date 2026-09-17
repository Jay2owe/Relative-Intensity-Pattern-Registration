/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.LogRatioParameters;
import logratio.api.Preprocessing;
import logratio.api.PixelSelectionStrategy;
import logratio.core.RobustNorm;
import org.junit.Test;

import static org.junit.Assert.*;

public class LogRatioSweepPlanTest {
    @Test
    public void crossesAxesAndAppliesEveryValueWithoutChangingBase() {
        LogRatioParameters base = LogRatioParameters.builder().build();
        LogRatioSweepPlan plan = new LogRatioSweepPlan(base,
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.ESTIMATION_SCALE, "1, 0.5"),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.ROBUST_WEIGHTING, "Huber, Tukey"));
        assertEquals(4, plan.combinations.size());
        assertEquals(1.0, base.estimationScale, 0.0);
        assertEquals(RobustNorm.HUBER, plan.combinations.get(0).parameters.norm);
        assertEquals(0.5, plan.combinations.get(2).parameters.estimationScale, 0.0);
        assertEquals(RobustNorm.TUKEY, plan.combinations.get(3).parameters.norm);
        assertFalse(plan.combinations.get(3).parameters.useRecommendation);
    }

    @Test
    public void exclusionIsExpressedAsPercentRemovedButStoredAsPercentileBoundary() {
        LogRatioSweepPlan plan = new LogRatioSweepPlan(LogRatioParameters.builder().build(),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.BRIGHT_EXCLUSION, "off, 10"),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.DIM_EXCLUSION, "25"));
        assertTrue(Double.isNaN(plan.combinations.get(0).parameters.ceilingPercentile));
        assertEquals(90, plan.combinations.get(1).parameters.ceilingPercentile, 0.0);
        assertEquals(25, plan.combinations.get(1).parameters.floorPercentile, 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesDuplicateAxes() {
        LogRatioSweepPlan.Axis axis = new LogRatioSweepPlan.Axis(
                LogRatioSweepPlan.Parameter.MAX_ITERATIONS, "12,25");
        new LogRatioSweepPlan(LogRatioParameters.builder().build(), axis, axis);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesGridAboveBound() {
        new LogRatioSweepPlan(LogRatioParameters.builder().build(),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.MAX_ITERATIONS, "1,2,3,4,5"),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.MAX_SAMPLES, "1,2,3,4,5"));
    }

    @Test
    public void allSampleValueMapsToUnboundedIntegerLimit() {
        LogRatioSweepPlan plan = new LogRatioSweepPlan(LogRatioParameters.builder().build(),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.MAX_SAMPLES, "all"));
        assertEquals(Integer.MAX_VALUE, plan.combinations.get(0).parameters.maxSamples);
    }

    @Test
    public void preprocessingIsSweepable() {
        LogRatioSweepPlan plan = new LogRatioSweepPlan(LogRatioParameters.builder().build(),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.PREPROCESSING,
                        "none, median_3x3"));
        assertEquals(Preprocessing.NONE, plan.combinations.get(0).parameters.preprocessing);
        assertEquals(Preprocessing.MEDIAN_3X3, plan.combinations.get(1).parameters.preprocessing);
    }

    @Test
    public void pixelSelectionFilterAndPercentageAreSweepable() {
        LogRatioSweepPlan plan = new LogRatioSweepPlan(LogRatioParameters.builder().build(),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.PIXEL_SELECTION,
                        "none, remove_least_informative"),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.MASK_PREPROCESSING,
                        "none, anscombe"),
                new LogRatioSweepPlan.Axis(LogRatioSweepPlan.Parameter.PIXEL_REMOVAL, "25"));
        assertEquals(4, plan.combinations.size());
        assertEquals(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                plan.combinations.get(2).parameters.pixelSelectionStrategy);
        assertEquals(Preprocessing.ANSCOMBE,
                plan.combinations.get(3).parameters.pixelSelectionPreprocessing);
        assertEquals(25.0, plan.combinations.get(3).parameters.pixelRemovalPercent, 0.0);
    }
}
