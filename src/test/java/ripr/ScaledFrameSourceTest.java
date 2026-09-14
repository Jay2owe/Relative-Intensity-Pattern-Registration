/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.FrameSource;
import org.junit.Test;

import static org.junit.Assert.*;

public class ScaledFrameSourceTest {
    @Test
    public void halfScaleAreaAveragesTwoByTwoBlocks() {
        FrameSource source = source(64, 64);
        ScaledFrameSource scaled = new ScaledFrameSource(source, 0.5);
        assertEquals(32, scaled.width());
        assertEquals(32, scaled.height());
        float[] pixels = scaled.plane(0);
        assertEquals((0 + 1 + 64 + 65) / 4f, pixels[0], 0.0);
        assertEquals((2 + 3 + 66 + 67) / 4f, pixels[1], 0.0);
    }

    @Test
    public void nonIntegerScaleProducesExpectedDimensionsAndFinitePixels() {
        ScaledFrameSource scaled = new ScaledFrameSource(source(80, 60), 0.75);
        assertEquals(60, scaled.width());
        assertEquals(45, scaled.height());
        for (float value : scaled.plane(0)) assertTrue(Float.isFinite(value));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesScaleThatLeavesTooLittleEvidence() {
        new ScaledFrameSource(source(64, 64), 0.25);
    }

    private static FrameSource source(final int width, final int height) {
        return new FrameSource() {
            @Override public int count() { return 2; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
            @Override public float[] plane(int frame) {
                float[] values = new float[width * height];
                for (int i = 0; i < values.length; i++) values[i] = i;
                return values;
            }
        };
    }
}
