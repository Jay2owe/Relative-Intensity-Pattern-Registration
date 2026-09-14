/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The log transform, the validity mask, and the property the pyramid exists to preserve. */
public class LogPlaneTest {

    private static final int W = 64;
    private static final int H = 48;

    /**
     * A global gain is an additive constant in the log domain <b>at every pyramid level</b>.
     *
     * <p>This is the reason the blur is applied to log values rather than to intensities. Averaging
     * logs is the log of a geometric mean, which commutes with a gain exactly; averaging intensities
     * first does not. If this test fails, gain invariance holds at full resolution only and the coarse
     * search — which is what chooses the basin — has quietly lost it.
     */
    @Test
    public void gainIsAnExactAdditiveConstantAtEveryLevel() {
        float[] plain = Synth.frame(W, H, 0, 0);
        float[] bright = Synth.frame(W, H, 0, 0, 4.0, 0);
        // epsilon must be negligible against the signal for the identity to be exact rather than
        // approximate; Synth's values are around 2000, so 1e-6 is far below the float noise floor.
        LogPlane[] a = LogPlane.of(plain, W, H, 1e-6).pyramid(3);
        LogPlane[] b = LogPlane.of(bright, W, H, 1e-6).pyramid(3);
        for (int l = 0; l < a.length; l++) {
            for (int i = 0; i < a[l].v.length; i++) {
                if (!a[l].valid[i]) continue;
                assertEquals("level " + l + " pixel " + i,
                        2.0, b[l].v[i] - a[l].v[i], 2e-3);
            }
        }
    }

    /**
     * A NaN input pixel is excluded, and never propagates.
     *
     * <p>Defect 4 of the draft engine. A registration margin is conventionally written as NaN rather
     * than zero, precisely so a zero always means "measured, unchanged" — so NaN input is the normal
     * case for this plugin's own output, not an edge case. Left unhandled, one NaN travels through the
     * blur and the gradient stencil and makes an entire frame's cost NaN.
     */
    @Test
    public void nanInputIsExcludedAndNeverPropagates() {
        float[] plane = Synth.frame(W, H, 0, 0);
        for (int y = 10; y < 20; y++) {
            for (int x = 5; x < 15; x++) plane[y * W + x] = Float.NaN;
        }
        LogPlane[] pyr = LogPlane.of(plane, W, H, 1.0).pyramid(3);
        for (int l = 0; l < pyr.length; l++) {
            LogPlane p = pyr[l];
            assertTrue("level " + l + " should keep most pixels",
                    p.validCount > 0.5 * p.v.length);
            for (int i = 0; i < p.v.length; i++) {
                assertFalse("level " + l + " value " + i + " is NaN", Float.isNaN(p.v[i]));
                assertFalse("level " + l + " gx " + i + " is NaN", Float.isNaN(p.gx[i]));
                assertFalse("level " + l + " gy " + i + " is NaN", Float.isNaN(p.gy[i]));
            }
        }
        assertFalse("the masked region must be invalid at level 0", pyr[0].valid[12 * W + 8]);
        assertTrue("an untouched region must stay valid", pyr[0].valid[40 * W + 40]);
    }

    /** Sampling inside the masked region refuses rather than returning a plausible number. */
    @Test
    public void samplingRefusesInvalidAndOutOfBounds() {
        float[] plane = Synth.frame(W, H, 0, 0);
        plane[20 * W + 20] = Float.NaN;
        LogPlane p = LogPlane.of(plane, W, H, 1.0);
        assertTrue("a footprint touching the invalid pixel", Float.isNaN(p.sample(19.5, 19.5)));
        assertTrue("out of bounds left", Float.isNaN(p.sample(-0.01, 5)));
        assertTrue("out of bounds right", Float.isNaN(p.sample(W - 0.99, 5)));
        assertTrue("out of bounds bottom", Float.isNaN(p.sample(5, H - 0.5)));
        assertFalse("well inside should sample", Float.isNaN(p.sample(40.25, 30.75)));
    }

    /** The intensity floor and the saturation ceiling both mark pixels invalid. */
    @Test
    public void floorAndSaturationExcludePixels() {
        float[] plane = new float[W * H];
        for (int i = 0; i < plane.length; i++) plane[i] = i % 100;      // 0..99 repeating
        LogPlane floored = LogPlane.of(plane, W, H, 1.0, 50, LogPlane.NO_SATURATION);
        LogPlane capped = LogPlane.of(plane, W, H, 1.0, LogPlane.NO_FLOOR, 50);
        assertFalse("49 is below a floor of 50", floored.valid[49]);
        assertTrue("50 is not", floored.valid[50]);
        assertTrue("49 is below a ceiling of 50", capped.valid[49]);
        assertFalse("50 is at the ceiling", capped.valid[50]);
    }

    /** Level counts, and the automatic escalation that keeps the coarse sweep affordable. */
    @Test
    public void levelCounts() {
        assertEquals(1, LogPlane.autoLevels(64, 64, 48, 4));
        assertEquals(2, LogPlane.autoLevels(128, 128, 48, 4));
        assertEquals(4, LogPlane.autoLevels(1024, 1024, 48, 4));
        assertEquals("a 30 px bound needs 3 levels to fit an 8 px sweep",
                3, LogPlane.levelsForShift(30, 8));
        assertEquals("a bound within budget needs one level",
                1, LogPlane.levelsForShift(5, 8));
        assertEquals("a 500 px bound needs 7", 7, LogPlane.levelsForShift(500, 8));
    }

    /** A frame with no texture has no gradient, which is how the solver detects it. */
    @Test
    public void flatFrameHasNoGradient() {
        float[] flat = new float[W * H];
        java.util.Arrays.fill(flat, 1234f);
        LogPlane p = LogPlane.of(flat, W, H, 1.0);
        for (int i = 0; i < p.v.length; i++) {
            assertEquals(0f, p.gradMagnitude(i), 1e-6);
        }
    }
}
