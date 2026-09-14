/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.Preprocessing;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PreprocessedFrameSourceTest {
    @Test
    public void noPreprocessingReturnsAnIndependentExactCopy() {
        float[] input = {1, 2, 3, 4};
        float[] output = PreprocessedFrameSource.apply(input, 2, 2, Preprocessing.NONE);
        assertArrayEquals(input, output, 0.0f);
        output[0] = 99;
        assertEquals(1, input[0], 0.0f);
    }

    @Test
    public void gaussianSmoothingPreservesAConstantPlane() {
        float[] input = new float[49];
        Arrays.fill(input, 17.5f);
        for (Preprocessing option : new Preprocessing[]{Preprocessing.GAUSSIAN_0_7,
                Preprocessing.GAUSSIAN_1_0, Preprocessing.GAUSSIAN_1_4}) {
            float[] output = PreprocessedFrameSource.apply(input, 7, 7, option);
            for (float value : output) assertEquals(17.5f, value, 1e-6f);
        }
    }

    @Test
    public void medianDenoisingRemovesAnIsolatedImpulse() {
        float[] input = new float[25];
        input[12] = 100;
        float[] output = PreprocessedFrameSource.apply(input, 5, 5, Preprocessing.MEDIAN_3X3);
        assertEquals(0, output[12], 0.0f);
    }

    @Test
    public void photonNoiseStabilisationUsesAnscombeTransform() {
        float[] output = PreprocessedFrameSource.apply(new float[]{0, 4}, 2, 1,
                Preprocessing.ANSCOMBE);
        assertEquals(2 * Math.sqrt(3.0 / 8.0), output[0], 1e-6);
        assertEquals(2 * Math.sqrt(4 + 3.0 / 8.0), output[1], 1e-6);
    }

    @Test
    public void localContrastRemovesAUniformFieldAndKeepsALocalFeature() {
        float[] input = new float[64 * 64];
        Arrays.fill(input, 20f);
        input[32 * 64 + 32] = 100f;
        float[] output = PreprocessedFrameSource.apply(
                input, 64, 64, Preprocessing.LOCAL_CONTRAST_1_16);
        org.junit.Assert.assertTrue(
                output[32 * 64 + 32] - output[10 * 64 + 10] > 50f);
    }

    @Test
    public void localContrastShiftsSignedResidualsIntoTheValidIntensityDomain() {
        float[] input = new float[64 * 64];
        Arrays.fill(input, 20f);
        input[31 * 64 + 31] = 0f;
        input[32 * 64 + 32] = 100f;
        float[] output = PreprocessedFrameSource.apply(
                input, 64, 64, Preprocessing.LOCAL_CONTRAST_1_8);
        float minimum = Float.POSITIVE_INFINITY;
        for (float value : output) {
            org.junit.Assert.assertTrue(Float.isFinite(value));
            minimum = Math.min(minimum, value);
        }
        assertEquals(0.0, minimum, 0.0);
        org.junit.Assert.assertTrue(output[32 * 64 + 32] > output[10 * 64 + 10]);
    }

    @Test
    public void structuralGradientIgnoresUniformBrightness() {
        float[] input = new float[25];
        Arrays.fill(input, 7f);
        float[] output = PreprocessedFrameSource.apply(
                input, 5, 5, Preprocessing.STRUCTURAL_GRADIENT);
        for (float value : output) assertEquals(0.0, value, 1e-6);
    }

    @Test
    public void everyRelativeLocalContrastScaleRemovesAUniformField() {
        float[] input = new float[64 * 64];
        Arrays.fill(input, 11f);
        for (Preprocessing option : new Preprocessing[]{Preprocessing.LOCAL_CONTRAST_1_8,
                Preprocessing.LOCAL_CONTRAST_1_16, Preprocessing.LOCAL_CONTRAST_1_32}) {
            float[] output = PreprocessedFrameSource.apply(input, 64, 64, option);
            for (float value : output) assertEquals(0.0, value, 1e-6);
        }
    }

}
