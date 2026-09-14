/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.Transform;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExternalPluginComparisonStacksTest {

    @Test
    public void rectangularProcessorsKeepEveryPixel() throws Exception {
        java.lang.reflect.Method method = ExternalPluginComparisonStacks.class.getDeclaredMethod(
                "processor", float[].class, int.class);
        method.setAccessible(true);
        float[] pixels = new float[37 * 23];
        for (int i = 0; i < pixels.length; i++) pixels[i] = i;
        ij.process.FloatProcessor processor = (ij.process.FloatProcessor) method.invoke(null, pixels, 37);
        assertEquals(37, processor.getWidth());
        assertEquals(23, processor.getHeight());
        org.junit.Assert.assertArrayEquals(pixels, (float[]) processor.getPixels(), 0f);
        assertTrue(pixels != processor.getPixels());
    }

    @Test
    public void rectangularRigidTransformsRotateAroundTheActualImageCentre() throws Exception {
        java.lang.reflect.Method method = ExternalPluginComparisonStacks.class.getDeclaredMethod(
                "transformFromAffine", double.class, double.class, double.class,
                double.class, double.class, double.class, int.class, int.class);
        method.setAccessible(true);
        int width = 1536, height = 1152;
        double theta = 0.21, c = Math.cos(theta), s = Math.sin(theta);
        double cx = (width - 1) / 2.0, cy = (height - 1) / 2.0;
        double bx = cx - c * cx + s * cy + 3.0;
        double by = cy - s * cx - c * cy - 4.0;
        Transform result = (Transform) method.invoke(null, c, -s, bx, s, c, by, width, height);
        assertEquals(3.0, result.dx, 1e-12);
        assertEquals(-4.0, result.dy, 1e-12);
        assertEquals(theta, result.theta, 1e-12);
        // Square inputs retain the exact original arithmetic, including its reduction order.
        double centre = (width - 1) / 2.0;
        Transform square = (Transform) method.invoke(null, c, -s, bx, s, c, by, width, width);
        assertEquals(Double.doubleToLongBits(bx - centre + (c * centre - s * centre)),
                Double.doubleToLongBits(square.dx));
        assertEquals(Double.doubleToLongBits(by - centre + (s * centre + c * centre)),
                Double.doubleToLongBits(square.dy));
    }

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
    public void sweepRegistryHasTwentySixUniqueConfigurations() {
        Set<String> ids = new HashSet<>();
        for (ExternalPluginComparisonStacks.SweepConfig value :
                ExternalPluginComparisonStacks.sweepConfigs()) {
            assertTrue("configuration ids must be distinct", ids.add(value.id));
        }
        assertEquals(26, ids.size());
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

        double[] exactRigid = ExternalPluginComparisonStacks.geometricMetrics(
                new Transform[]{new Transform(2.5, -3.5, angle)}, new double[]{2.5},
                new double[]{-3.5}, new double[]{angle}, width);
        assertEquals(0, exactRigid[0], 0);

        double[] angleErrors = ExternalPluginComparisonStacks.angleErrorsDegrees(
                new Transform[]{new Transform(0, 0, angle)}, new double[]{0});
        assertEquals(1.5, angleErrors[0], 1e-12);
    }
}
