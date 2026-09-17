/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.core.FrameSource;
import logratio.core.Registration;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PixelSelectionEngineTest {
    @Test
    public void automaticMovementBoundIsResolvedBeforeSupportPyramidAllocation() {
        FrameSource source = texturedSource(64, 64);
        Registration.Options options = new Registration.Options();
        options.autoMaxShift = true;
        options.aligner.maxShift = 1;
        options.aligner.coarseRadiusBudget = 1;
        options.threads = 1;

        int levelsBeforeAutomaticBound = options.aligner.levelsFor(source.width(), source.height());
        Registration.Options effective = PixelSelectionEngine.prepareRawRefitOptions(source, options);
        int expectedLevels = effective.aligner.levelsFor(source.width(), source.height());

        assertTrue(!effective.autoMaxShift);
        assertTrue(expectedLevels > levelsBeforeAutomaticBound);
        assertEquals(4, expectedLevels);
    }

    private static FrameSource texturedSource(final int width, final int height) {
        final float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = 20f + (x % 7) * 3f + (y % 5) * 2f;
            }
        }
        return new FrameSource() {
            @Override public int count() { return 2; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
            @Override public float[] plane(int frame) { return pixels; }
        };
    }
}
