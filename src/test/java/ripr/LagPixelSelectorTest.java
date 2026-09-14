/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.LogPlane;
import ripr.core.Transform;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests that multi-lag residual scores localise change and produce deterministic support masks. */
public class LagPixelSelectorTest {

    private static final int W = 40;
    private static final int H = 40;
    private static final int FRAMES = 8;

    @Test
    public void instabilityLocalisesAChangingTexturedPatch() {
        LogPlane[] frames = changingPatchFrames();
        Transform[] cumulative = identities();
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                frames, cumulative, new int[]{1, 2, 4});

        double changed = mean(scores.instability, 15, 15, 10, 10);
        double stable = mean(scores.instability, 1, 1, 8, 8);
        assertTrue("changed patch " + changed + " should outrank stable field " + stable,
                changed > stable + 0.5);
    }

    @Test
    public void oppositeTailsRemoveExactAndDifferentPixels() {
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                changingPatchFrames(), identities(), new int[]{1, 2, 4});
        boolean[] high = LagPixelSelector.mask(
                scores, LagPixelSelector.Score.INSTABILITY, 25, true);
        boolean[] low = LagPixelSelector.mask(
                scores, LagPixelSelector.Score.INSTABILITY, 25, false);
        int expected = (int) Math.round(0.25 * scores.eligibleCount);
        assertEquals(expected, LagPixelSelector.removedEligible(high, scores));
        assertEquals(expected, LagPixelSelector.removedEligible(low, scores));

        int highPatch = removedIn(high, 15, 15, 10, 10);
        int lowPatch = removedIn(low, 15, 15, 10, 10);
        assertTrue("high-instability removal should target the changing patch: "
                + highPatch + " vs " + lowPatch, highPatch > lowPatch);
    }

    @Test
    public void spatialInformationCanDriveMaskWithoutChangingFitPixels() {
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                changingPatchFrames(), identities(), new int[]{1, 2, 4});
        boolean[] keepInformative = LagPixelSelector.mask(
                scores, LagPixelSelector.Score.INFORMATION, 50, false);
        assertEquals((int) Math.round(0.5 * scores.eligibleCount),
                LagPixelSelector.removedEligible(keepInformative, scores));
    }

    @Test
    public void spatialOnlyScoreAvoidsTemporalEvidence() {
        LagPixelSelector.Scores scores = LagPixelSelector.spatialInformationScore(
                changingPatchFrames()[0]);
        boolean[] mask = LagPixelSelector.mask(
                scores, LagPixelSelector.Score.INFORMATION, 25, false);
        assertEquals((int) Math.round(0.25 * scores.eligibleCount),
                LagPixelSelector.removedEligible(mask, scores));
        assertTrue(Double.isNaN(scores.instability[0]));
    }

    @Test
    public void referenceMaskFollowsTheFrameTransform() {
        boolean[] reference = new boolean[W * H];
        java.util.Arrays.fill(reference, true);
        reference[10 * W + 10] = false;
        LogPlane[] pyramid = LogPlane.of(base(), W, H, 1.0).pyramid(1);
        boolean[][] source = LagPixelSelector.sourceSupport(
                reference, W, H, pyramid, Transform.translation(3, -2));
        assertFalse("reference (10,10) appears at source (13,8)", source[0][8 * W + 13]);
        assertTrue(source[0][10 * W + 10]);
    }

    @Test
    public void alternateCoordinatesCanExplainOnlyThePermittedRegion() {
        boolean[] eligible = {true, true, true, true};
        LagPixelSelector.Scores pilot = new LagPixelSelector.Scores(2, 2,
                new double[]{4, 3, 2, 1}, new double[4], new double[4],
                new double[]{1, 2, 3, 4}, new int[]{0, 1, 2, 3}, eligible, 4);
        LagPixelSelector.Scores alternate = new LagPixelSelector.Scores(2, 2,
                new double[]{0.5, 0.5, 0.5, 0.5}, new double[4], new double[4],
                new double[]{1, 2, 3, 4}, new int[]{0, 1, 2, 3}, eligible, 4);

        LagPixelSelector.Scores combined = LagPixelSelector.dualCoordinateTrust(
                pilot, alternate, new boolean[]{true, false, true, false});
        assertEquals(0.5, combined.instability[0], 0);
        assertEquals(3.0, combined.instability[1], 0);
        assertEquals(0.5, combined.instability[2], 0);
        assertEquals(1.0, combined.instability[3], 0);
    }

    @Test
    public void stratifiedMaskRemovesAnchorsAcrossTheField() {
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                changingPatchFrames(), identities(), new int[]{1, 2, 4});
        boolean[] mask = LagPixelSelector.stratifiedMask(
                scores, LagPixelSelector.Score.ANCHOR_TRUST, 55, false, 20, 4);
        assertTrue(removedIn(mask, 0, 0, 20, 20) > 0);
        assertTrue(removedIn(mask, 20, 0, 20, 20) > 0);
        assertTrue(removedIn(mask, 0, 20, 20, 20) > 0);
        assertTrue(removedIn(mask, 20, 20, 20, 20) > 0);
    }

    private static LogPlane[] changingPatchFrames() {
        LogPlane[] out = new LogPlane[FRAMES];
        for (int t = 0; t < FRAMES; t++) {
            float[] frame = base();
            for (int y = 15; y < 25; y++) {
                for (int x = 15; x < 25; x++) {
                    double checker = ((x + y + t) & 1) == 0 ? 900 : -500;
                    frame[y * W + x] += (float) (checker + 80 * t);
                }
            }
            out[t] = LogPlane.of(frame, W, H, 1.0);
        }
        return out;
    }

    private static float[] base() {
        float[] out = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                out[y * W + x] = (float) (2000 + 300 * Math.sin(0.31 * x + 0.17 * y)
                        + 180 * Math.cos(0.13 * x - 0.29 * y));
            }
        }
        return out;
    }

    private static Transform[] identities() {
        Transform[] out = new Transform[FRAMES];
        java.util.Arrays.fill(out, Transform.IDENTITY);
        return out;
    }

    private static double mean(double[] values, int x0, int y0, int width, int height) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y0 + height; y++) {
            for (int x = x0; x < x0 + width; x++) {
                double value = values[y * W + x];
                if (Double.isFinite(value)) {
                    sum += value;
                    n++;
                }
            }
        }
        return sum / n;
    }

    private static int removedIn(boolean[] mask, int x0, int y0, int width, int height) {
        int n = 0;
        for (int y = y0; y < y0 + height; y++) {
            for (int x = x0; x < x0 + width; x++) if (!mask[y * W + x]) n++;
        }
        return n;
    }
}
