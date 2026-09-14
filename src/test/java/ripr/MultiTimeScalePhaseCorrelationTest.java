/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;
import ripr.core.MultiTimeScalePhaseCorrelation;
import ripr.core.Transform;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class MultiTimeScalePhaseCorrelationTest {

    private static final int W = 96;
    private static final int H = 80;
    private static final int MARGIN = 64;
    private static final float[] FIELD = field(W + MARGIN, H + MARGIN, 20260903L);

    @Test
    public void usesCorrect3DDriftsProgressiveGapsAndTerminalTie() {
        assertArrayEquals(new int[]{1, 3, 9, 27, 39},
                MultiTimeScalePhaseCorrelation.lags(40));
        assertArrayEquals(new int[]{1, 2}, MultiTimeScalePhaseCorrelation.lags(3));
        assertArrayEquals(new int[0], MultiTimeScalePhaseCorrelation.lags(1));
    }

    @Test
    public void preservesAnAbruptJumpAtTheFinalFrame() {
        float[][] frames = new float[40][];
        for (int frame = 0; frame < frames.length - 1; frame++) frames[frame] = crop(0, 0, 1.0);
        frames[frames.length - 1] = crop(6, -4, 1.0);
        MultiTimeScalePhaseCorrelation.Result result =
                MultiTimeScalePhaseCorrelation.register(frames, W, H, true);
        assertEquals(0.0, result.cumulative[38].dx, 0.0);
        assertEquals(0.0, result.cumulative[38].dy, 0.0);
        assertEquals(6.0, result.cumulative[39].dx, 0.0);
        assertEquals(-4.0, result.cumulative[39].dy, 0.0);
        assertEquals(60, result.pairCount);
    }

    @Test
    public void aGlobalPulseDoesNotBecomeMovement() {
        float[][] frames = new float[40][];
        for (int frame = 0; frame < frames.length; frame++) {
            double gain = frame >= 14 && frame <= 20 ? 3.5 : 1.0;
            frames[frame] = crop(0, 0, gain);
        }
        MultiTimeScalePhaseCorrelation.Result result =
                MultiTimeScalePhaseCorrelation.register(frames, W, H, false);
        for (int frame = 0; frame < frames.length; frame++) {
            assertEquals("dx frame " + frame, 0.0, result.cumulative[frame].dx, 0.03);
            assertEquals("dy frame " + frame, 0.0, result.cumulative[frame].dy, 0.03);
        }
    }

    @Test
    public void directFirstFrameRouteDoesNotSmearAPulseOrFinalJump() {
        float[][] frames = new float[40][];
        for (int frame = 0; frame < frames.length - 1; frame++) {
            frames[frame] = crop(0, 0, 1.0);
            if (frame >= 14 && frame <= 20) {
                for (int y = 18; y < 48; y++) {
                    for (int x = 36; x < 70; x++) frames[frame][y * W + x] *= 5.0f;
                }
            }
        }
        frames[frames.length - 1] = crop(6, -4, 1.0);

        MultiTimeScalePhaseCorrelation.Result result =
                MultiTimeScalePhaseCorrelation.registerVerifiedToFirst(frames, W, H, true);

        for (int frame = 0; frame < frames.length - 1; frame++) {
            assertEquals("dx frame " + frame, 0.0, result.cumulative[frame].dx, 0.0);
            assertEquals("dy frame " + frame, 0.0, result.cumulative[frame].dy, 0.0);
        }
        assertEquals(6.0, result.cumulative[39].dx, 0.0);
        assertEquals(-4.0, result.cumulative[39].dy, 0.0);
        assertEquals(39, result.pairCount);
    }

    @Test
    public void jumpTraceCleanupRemovesPulseBlipsButKeepsTheTerminalStep() {
        Transform[] direct = new Transform[40];
        for (int frame = 0; frame < direct.length; frame++) {
            double y = frame == 24 || frame == 25 || frame == 27
                    || frame == 30 || frame == 32 || frame == 33 || frame == 34 ? -1 : 0;
            direct[frame] = Transform.translation(0, y);
        }
        direct[37] = Transform.translation(-3, 4);
        direct[38] = Transform.translation(-3, 5);
        direct[39] = Transform.translation(-3, 5);

        Transform[] stable = MultiTimeScalePhaseCorrelation.stabiliseJumpTrace(direct);

        for (int frame = 0; frame < 37; frame++) {
            assertEquals("dx frame " + frame, 0.0, stable[frame].dx, 0.0);
            assertEquals("dy frame " + frame, 0.0, stable[frame].dy, 0.0);
        }
        for (int frame = 37; frame < 40; frame++) {
            assertEquals("dx frame " + frame, -3.0, stable[frame].dx, 0.0);
            assertEquals("dy frame " + frame, 5.0, stable[frame].dy, 0.0);
        }
    }

    private static float[] crop(int dx, int dy, double gain) {
        int fw = W + MARGIN;
        int ox = MARGIN / 2 - dx;
        int oy = MARGIN / 2 - dy;
        float[] out = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                out[y * W + x] = (float) (gain * FIELD[(y + oy) * fw + x + ox]);
            }
        }
        return out;
    }

    private static float[] field(int w, int h, long seed) {
        Random random = new Random(seed);
        float[] values = new float[w * h];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) (120 + 35 * random.nextGaussian());
        }
        for (int pass = 0; pass < 2; pass++) {
            float[] source = values.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    values[y * w + x] = (source[y * w + x] * 4
                            + source[y * w + x - 1] + source[y * w + x + 1]
                            + source[(y - 1) * w + x] + source[(y + 1) * w + x]) / 8f;
                }
            }
        }
        return values;
    }
}
