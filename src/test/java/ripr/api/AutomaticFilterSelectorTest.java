/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.FrameSource;
import ripr.core.Transform;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;

public class AutomaticFilterSelectorTest {

    @Test
    public void frozenBoundariesReachEveryValidatedRecipe() {
        assertEquals(AutomaticFilterSelector.Recipe.MEDIAN_3X3_AND_SPATIAL_MASK_25,
                AutomaticFilterSelector.choose(1.51, 0.86, 9, 9, 9));
        assertEquals(AutomaticFilterSelector.Recipe.SPATIAL_MASK_25,
                AutomaticFilterSelector.choose(1.51, 0.85, 9, 9, 9));
        assertEquals(AutomaticFilterSelector.Recipe.MEDIAN_3X3,
                AutomaticFilterSelector.choose(1.50, 9, 2.01, 9, 9));
        assertEquals(AutomaticFilterSelector.Recipe.GAUSSIAN_0_7,
                AutomaticFilterSelector.choose(1.50, 9, 2.00, 1.26, 9));
        assertEquals(AutomaticFilterSelector.Recipe.GAUSSIAN_1_0,
                AutomaticFilterSelector.choose(1.50, 9, 2.00, 1.25, 1.26));
        assertEquals(AutomaticFilterSelector.Recipe.NONE,
                AutomaticFilterSelector.choose(1.50, 0.85, 2.00, 1.25, 1.25));
    }

    @Test
    public void evidenceUsesDeclaredFiniteImageAndMovementFeatures() {
        int width = 40;
        int height = 32;
        float[][] planes = new float[5][width * height];
        Random random = new Random(20260816L);
        for (int t = 0; t < planes.length; t++) {
            for (int i = 0; i < planes[t].length; i++) {
                planes[t][i] = (float) (20 + 5 * t + 80 * random.nextDouble());
            }
        }
        FrameSource source = new FrameSource() {
            @Override public int count() { return planes.length; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
            @Override public float[] plane(int frame) { return planes[frame].clone(); }
        };
        Transform[] motion = new Transform[planes.length];
        for (int t = 0; t < motion.length; t++) motion[t] = Transform.translation(0.4 * t, -0.2 * t);
        AutomaticFilterSelector.Evidence evidence = AutomaticFilterSelector.measure(source, motion);
        assertEquals(AutomaticFilterSelector.IMAGE_FEATURE_NAMES.length, evidence.imageFeatures.length);
        assertEquals(AutomaticFilterSelector.MOTION_FEATURE_NAMES.length, evidence.motionFeatures.length);
        for (double value : evidence.imageFeatures) assertFalse(Double.isNaN(value));
        for (double value : evidence.motionFeatures) assertFalse(Double.isNaN(value));
    }

    @Test
    public void uncertainEvidenceFallsBackAndClearsExistingAddOns() {
        double[] image = new double[AutomaticFilterSelector.IMAGE_FEATURE_NAMES.length];
        double[] motion = new double[AutomaticFilterSelector.MOTION_FEATURE_NAMES.length];
        java.util.Arrays.fill(image, Double.NaN);
        java.util.Arrays.fill(motion, Double.NaN);
        RelativeIntensityPatternParameters base = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        MotionType.STEADY_DIRECTIONAL_DRIFT)
                .automaticFilterSelection(true)
                .build();
        AutomaticFilterSelector.Result result = AutomaticFilterSelector.select(
                new AutomaticFilterSelector.Evidence(image, motion), base);
        assertEquals(AutomaticFilterSelector.Recipe.NONE, result.recipe);
        assertEquals(Preprocessing.NONE, result.parameters.preprocessing);
        assertEquals(PixelSelectionStrategy.NONE, result.parameters.pixelSelectionStrategy);
        assertFalse(result.parameters.automaticFilterSelection);
    }

    @Test
    public void rawImageFeaturesAreDeterministicAndGainOffsetInvariant() {
        int width = 32;
        int height = 24;
        float[][] raw = new float[3][width * height];
        float[][] changed = new float[3][width * height];
        for (int t = 0; t < raw.length; t++) {
            for (int i = 0; i < raw[t].length; i++) {
                raw[t][i] = (i * 37 + t * 11) % 251;
                changed[t][i] = raw[t][i] * 3 + 17;
            }
        }
        double[] first = AutomaticFilterSelector.imageFeatures(source(width, height, raw));
        double[] repeated = AutomaticFilterSelector.imageFeatures(source(width, height, raw));
        assertArrayEquals(first, repeated, 0);
        assertArrayEquals(first,
                AutomaticFilterSelector.imageFeatures(source(width, height, changed)), 1e-6);
    }

    private static FrameSource source(int width, int height, float[][] planes) {
        return new FrameSource() {
            @Override public int count() { return planes.length; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
            @Override public float[] plane(int frame) { return planes[frame].clone(); }
        };
    }
}
