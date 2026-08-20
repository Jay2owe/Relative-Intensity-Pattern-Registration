/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.core.Transform;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExternalPluginComparisonStacksTest {

    @Test
    public void installedDefaultsAreTheAuditedValues() {
        ExternalPluginComparisonStacks.ExternalSettings settings =
                ExternalPluginComparisonStacks.ExternalSettings.defaults();
        assertEquals("rigid", settings.turboRegTransformation);
        assertEquals(1, settings.stabilizerPyramidLevel);
        assertEquals(0.9, settings.stabilizerTemplateUpdate, 0);
        assertEquals(2, settings.fast4dPeakMethod);
        assertEquals(5, settings.correct3dPeaks);
        assertEquals("rigid", settings.siftModel);
        assertEquals(8, settings.siftDescriptorSize);
        assertEquals(2, settings.siftMinInliers);
        assertEquals("rigid", settings.descriptorModel);
        assertEquals(0, settings.descriptorBrightestPoints);
        assertTrue(settings.descriptorMaxima);
        assertTrue(!settings.descriptorMinima);
    }

    @Test
    public void sweepRegistryHasTwentyFourUniqueConfigurations() {
        Set<String> ids = new HashSet<>();
        for (ExternalPluginComparisonStacks.SweepConfig value :
                ExternalPluginComparisonStacks.sweepConfigs()) {
            assertTrue("configuration ids must be distinct", ids.add(value.id));
        }
        assertEquals(24, ids.size());
    }

    @Test
    public void geometricMetricIsTranslationCompatibleAndRotationAware() {
        int width = 129;
        double[] zero = {0};
        double[] translated = ExternalPluginComparisonStacks.geometricMetrics(
                new Transform[]{Transform.translation(2.5, -3.5)}, new double[]{2.5},
                new double[]{-3.5}, width);
        assertEquals(0, translated[0], 0);

        double angle = Math.toRadians(1.5);
        double expected = Math.sqrt(4.0 * (width * (double) width - 1.0) / 12.0
                * (1.0 - Math.cos(angle)));
        double[] rotated = ExternalPluginComparisonStacks.geometricMetrics(
                new Transform[]{new Transform(0, 0, angle)}, zero, zero, width);
        assertEquals(expected, rotated[0], 1e-12);
    }
}
