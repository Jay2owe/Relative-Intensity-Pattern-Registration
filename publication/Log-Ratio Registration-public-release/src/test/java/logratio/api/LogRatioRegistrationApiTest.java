/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import logratio.core.Reconciler;
import org.junit.Test;

import static org.junit.Assert.*;

public class LogRatioRegistrationApiTest {
    @Test
    public void returnsCorrectedImageWithoutMutatingInput() {
        int width = 64;
        ImageStack stack = new ImageStack(width, width);
        for (int t = 0; t < 3; t++) {
            float[] pixels = new float[width * width];
            for (int y = 12; y < 50; y++) for (int x = 12; x < 50; x++) {
                pixels[y * width + x] = (float) (20 + x * 0.8 + y * 0.4
                        + 50 * Math.exp(-((x - 28 - t) * (x - 28 - t) + (y - 31) * (y - 31)) / 60.0));
            }
            stack.addSlice(new FloatProcessor(width, width, pixels));
        }
        ImagePlus input = new ImagePlus("input", stack);
        float firstBefore = input.getStack().getProcessor(1).getf(28, 31);
        LogRatioParameters parameters = LogRatioParameters.builder()
                .useRecommendation(false)
                .reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false)
                .maxShift(4)
                .threads(1)
                .crop(false)
                .build();
        LogRatioResult result = LogRatioRegistration.register(input, parameters);
        try {
            assertNotSame(input, result.correctedImage());
            assertEquals(3, result.correctedImage().getStackSize());
            assertEquals(firstBefore, input.getStack().getProcessor(1).getf(28, 31), 0.0);
            assertEquals(3, result.registration().cumulative.length);
        } finally {
            result.close();
            input.close();
        }
    }

    @Test
    public void selectedHyperstackChannelDrivesOneTransformAppliedToEveryChannelAndZSlice() {
        int width = 64;
        int channels = 2;
        int slices = 2;
        int frames = 3;
        ImageStack stack = new ImageStack(width, width);
        float[] selectedTexture = new float[width * width];
        float[] unselectedTexture = new float[width * width];
        java.util.Random random = new java.util.Random(20260814L);
        for (int i = 0; i < selectedTexture.length; i++) {
            selectedTexture[i] = 20 + random.nextFloat() * 180;
            unselectedTexture[i] = 20 + random.nextFloat() * 180;
        }
        for (int t = 0; t < frames; t++) {
            for (int z = 0; z < slices; z++) {
                for (int c = 0; c < channels; c++) {
                    float[] pixels = new float[width * width];
                    int shift = c == 1 ? 2 * t : 0;
                    float[] texture = c == 1 ? selectedTexture : unselectedTexture;
                    for (int y = 0; y < width; y++) for (int x = 0; x < width; x++) {
                        int sourceX = x - shift;
                        pixels[y * width + x] = sourceX >= 0
                                ? texture[y * width + sourceX] + z * 3 : 20 + z * 3;
                    }
                    stack.addSlice(new FloatProcessor(width, width, pixels));
                }
            }
        }
        ImagePlus input = new ImagePlus("two-channel hyperstack", stack);
        input.setDimensions(channels, slices, frames);
        input.setOpenAsHyperStack(true);
        LogRatioParameters parameters = LogRatioParameters.builder()
                .useRecommendation(false)
                .channel(2)
                .slice(0)
                .reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false)
                .maxShift(6)
                .threads(1)
                .crop(false)
                .build();
        LogRatioResult result = LogRatioRegistration.register(input, parameters);
        try {
            ImagePlus corrected = result.correctedImage();
            assertEquals(channels, corrected.getNChannels());
            assertEquals(slices, corrected.getNSlices());
            assertEquals(frames, corrected.getNFrames());
            assertTrue(corrected.isHyperStack());
            assertEquals(channels * slices * frames, corrected.getStackSize());
            assertEquals(frames, result.registration().cumulative.length);
            assertTrue("selected channel should recover non-zero movement",
                    Math.abs(result.registration().cumulative[2].dx) > 2.0);
            // The unselected channel was static, so changing it proves the selected channel's
            // non-zero transform was propagated rather than each channel being registered alone.
            int c1 = corrected.getStackIndex(1, 1, 3);
            int c2 = corrected.getStackIndex(2, 1, 3);
            int inputC1 = input.getStackIndex(1, 1, 3);
            assertNotEquals(input.getStack().getProcessor(inputC1).getf(30, 30),
                    corrected.getStack().getProcessor(c1).getf(30, 30), 0.0);
            assertEquals(corrected.getStack().getProcessor(c1).getWidth(),
                    corrected.getStack().getProcessor(c2).getWidth());
        } finally {
            result.close();
            input.close();
        }
    }

    @Test
    public void reducedResolutionEstimateIsReturnedInNativePixelCoordinates() {
        int width = 96;
        ImageStack stack = new ImageStack(width, width);
        float[] texture = new float[width * width];
        java.util.Random random = new java.util.Random(144L);
        for (int i = 0; i < texture.length; i++) texture[i] = 20 + random.nextFloat() * 180;
        for (int frame = 0; frame < 3; frame++) {
            float[] pixels = new float[width * width];
            int shift = 4 * frame;
            for (int y = 0; y < width; y++) for (int x = 0; x < width; x++) {
                int source = x - shift;
                pixels[y * width + x] = source >= 0 ? texture[y * width + source] : 20;
            }
            stack.addSlice(new FloatProcessor(width, width, pixels));
        }
        ImagePlus input = new ImagePlus("scale", stack);
        LogRatioParameters parameters = LogRatioParameters.builder()
                .useRecommendation(false).estimationScale(0.5)
                .reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false).maxShift(12).threads(1).crop(false).build();
        LogRatioResult result = LogRatioRegistration.register(input, parameters);
        try {
            assertEquals("movement should be rescaled into native pixels", 8.0,
                    result.registration().cumulative[2].dx, 0.8);
            assertEquals(width, result.correctedImage().getWidth());
            assertEquals(width, result.correctedImage().getHeight());
        } finally {
            result.close();
            input.close();
        }
    }

    @Test
    public void pixelMaskIsSelectedFromScoringCopyButOutputUsesNativePixels() {
        int width = 64;
        ImageStack stack = new ImageStack(width, width);
        java.util.Random random = new java.util.Random(987L);
        float[] texture = new float[width * width];
        for (int i = 0; i < texture.length; i++) texture[i] = 30 + 200 * random.nextFloat();
        for (int frame = 0; frame < 3; frame++) {
            float[] pixels = new float[width * width];
            for (int y = 0; y < width; y++) for (int x = 0; x < width; x++) {
                int sourceX = x - frame;
                pixels[y * width + x] = sourceX >= 0 ? texture[y * width + sourceX] : 30;
            }
            stack.addSlice(new FloatProcessor(width, width, pixels));
        }
        ImagePlus input = new ImagePlus("masked", stack);
        float original = input.getStack().getProcessor(1).getf(20, 20);
        LogRatioParameters parameters = LogRatioParameters.builder()
                .useRecommendation(false)
                .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE)
                .pixelSelectionPreprocessing(Preprocessing.ANSCOMBE)
                .pixelRemovalPercent(25)
                .reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false).maxShift(4).threads(1).crop(false).build();
        LogRatioResult result = LogRatioRegistration.register(input, parameters);
        try {
            assertEquals(3, result.registration().cumulative.length);
            assertEquals(width, result.correctedImage().getWidth());
            assertEquals(original, input.getStack().getProcessor(1).getf(20, 20), 0.0);
        } finally {
            result.close();
            input.close();
        }
    }

    @Test
    public void automaticFilterSelectionReturnsTheResolvedRecipeAndExplicitParameters() {
        int width = 64;
        ImageStack stack = new ImageStack(width, width);
        java.util.Random random = new java.util.Random(20260816L);
        float[] texture = new float[width * width];
        for (int i = 0; i < texture.length; i++) texture[i] = 30 + 170 * random.nextFloat();
        for (int frame = 0; frame < 4; frame++) {
            float[] pixels = new float[width * width];
            for (int y = 0; y < width; y++) for (int x = 0; x < width; x++) {
                int sourceX = x - frame;
                pixels[y * width + x] = sourceX >= 0 ? texture[y * width + sourceX] : 30;
            }
            stack.addSlice(new FloatProcessor(width, width, pixels));
        }
        ImagePlus input = new ImagePlus("automatic", stack);
        LogRatioParameters parameters = LogRatioParameters.builder()
                .useRecommendation(false)
                .automaticFilterSelection(true)
                .reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false).maxShift(5).threads(1).crop(false).build();
        LogRatioResult result = LogRatioRegistration.register(input, parameters);
        try {
            assertNotNull(result.automaticSelection());
            assertNotNull(result.automaticSelection().recipe);
            assertFalse("the returned settings must be recordable without rerunning selection",
                    result.parameters().automaticFilterSelection);
            assertEquals(4, result.registration().cumulative.length);
            assertEquals(4, result.correctedImage().getStackSize());
        } finally {
            result.close();
            input.close();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticFilterSelectionRejectsRollingReferenceBecauseItsMotionEvidenceIsIncompatible() {
        LogRatioParameters.builder()
                .useRecommendation(false)
                .automaticFilterSelection(true)
                .reference(Reconciler.Reference.ROLLING)
                .build();
    }
}
