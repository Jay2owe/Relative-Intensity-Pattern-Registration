/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;
import ripr.core.LogPlane;
import ripr.core.Transform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CircadianStableAnchorBenchmarkTest {

    @Test
    public void temporalEvidenceIncludesCircadianLagWithoutChangingTheConstant() {
        int[] copy = CircadianStableAnchorBenchmark.temporalScoringLags();
        assertArrayEquals(new int[] {1, 2, 4, 8, 16, 48}, copy);
        copy[0] = 99;
        assertArrayEquals(new int[] {1, 2, 4, 8, 16, 48},
                CircadianStableAnchorBenchmark.temporalScoringLags());
    }

    @Test
    public void stableAnchorMaskRemovesExactlyTheDeclaredEligibleQuarter() {
        int width = 32;
        int height = 32;
        int frames = 49;
        LogPlane[] planes = new LogPlane[frames];
        Transform[] cumulative = new Transform[frames];
        for (int t = 0; t < frames; t++) {
            float[] pixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double stable = 30 + 0.8 * x + 0.4 * y
                            + 4 * Math.sin(x * 0.4) * Math.cos(y * 0.3);
                    boolean movingPatch = x >= 3 + t % 12 && x < 9 + t % 12
                            && y >= 12 && y < 18;
                    pixels[y * width + x] = (float) (stable + (movingPatch ? 50 : 0));
                }
            }
            planes[t] = LogPlane.of(pixels, width, height, 1.0);
            cumulative[t] = Transform.IDENTITY;
        }
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                planes, cumulative, CircadianStableAnchorBenchmark.temporalScoringLags(), 0);
        boolean[] mask = CircadianStableAnchorBenchmark.stableAnchorMask(planes, cumulative, 0);
        int removed = LagPixelSelector.removedEligible(mask, scores);
        assertEquals((int) Math.round(0.25 * scores.eligibleCount), removed);
    }
}
