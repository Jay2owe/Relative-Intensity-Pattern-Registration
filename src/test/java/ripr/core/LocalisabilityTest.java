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

/**
 * Localisability has to separate the two things frame correlation cannot tell apart: structure that
 * can be localised, and a smooth blob that correlates just as well and cannot.
 */
public class LocalisabilityTest {

    private static final int W = 96;
    private static final int H = 96;

    /** A wide, smooth Gaussian bump. High frame correlation, nothing to localise. */
    private static float[] blob() {
        float[] out = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double dx = (x - W / 2.0) / (0.45 * W);
                double dy = (y - H / 2.0) / (0.45 * H);
                out[y * W + x] = (float) (1000 + 2000 * Math.exp(-(dx * dx + dy * dy)));
            }
        }
        return out;
    }

    /** Flat: no structure at all, and the degenerate case the measure must not claim anything about. */
    private static float[] flat() {
        float[] out = new float[W * H];
        java.util.Arrays.fill(out, 1500f);
        return out;
    }

    /**
     * <b>The measurement the survey rests on.</b> Both frames correlate near 1 with their successors,
     * so plain frame correlation cannot rank them — and ranking by it once chose a saturated
     * fluorescence channel on which two estimators disagreed by 15.7 px.
     */
    @Test
    public void structureIsLocalisableAndASmoothBlobIsNot() {
        float[] textured = Synth.frame(W, H, 0, 0);
        float[] smooth = blob();

        double corrTextured = Localisability.correlation(textured, Synth.frame(W, H, 0.25, 0.25));
        double corrSmooth = Localisability.correlation(smooth, smooth);
        assertTrue("both must correlate highly, or the test is not about the right thing: "
                        + corrTextured + " and " + corrSmooth,
                corrTextured > 0.9 && corrSmooth > 0.9);

        double sharp = Localisability.ofPair(textured, Synth.frame(W, H, 0.25, 0.25), W, H);
        double dull = Localisability.ofPair(smooth, smooth, W, H);
        assertTrue("textured " + sharp + " must exceed smooth " + dull, sharp > 10 * dull);
        assertTrue("a smooth blob is poor: " + dull, Localisability.poor(dull));
    }

    /**
     * The threshold has to sit in the empty gap the survey measured — 0.031 was the best of the eleven
     * recordings on which two estimators disagreed by 1.6 to 18.6 px, and 0.086 the worst of the
     * thirteen on which they agreed to better than 0.7 px. Nothing was measured between them.
     */
    @Test
    public void theWarningThresholdSitsInTheGapTheSurveyMeasured() {
        assertTrue("must not condemn a recording the survey found usable",
                Localisability.WARN_BELOW < 0.086);
        assertTrue("must condemn every recording the survey found unusable",
                Localisability.WARN_BELOW > 0.031);
        assertTrue(Localisability.poor(0.031));
        assertFalse(Localisability.poor(0.086));
    }

    /** A constant frame has no correlation at all, and NaN must not be reported as "poor". */
    @Test
    public void aFlatFrameIsUndefinedRatherThanBad() {
        double d = Localisability.ofPair(flat(), flat(), W, H);
        assertTrue("expected NaN, got " + d, Double.isNaN(d));
        assertFalse("NaN must not trip the warning", Localisability.poor(d));
    }

    /** Whole-recording localisability is the mean over consecutive pairs, and is defined at n >= 2. */
    @Test
    public void aWholeRecordingAveragesItsPairs() {
        float[][] planes = new float[4][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.frame(W, H, 0.3 * t, 0.2 * t);
        double whole = Localisability.of(Synth.source(W, H, planes));

        double sum = 0;
        for (int t = 1; t < planes.length; t++) {
            sum += Localisability.ofPair(planes[t - 1], planes[t], W, H);
        }
        assertEquals(sum / (planes.length - 1), whole, 1e-9);

        assertTrue("a single frame has no pair",
                Double.isNaN(Localisability.of(Synth.source(W, H, planes[0]))));
    }

    /** A masked margin must not make two frames look correlated because their masks agree. */
    @Test
    public void naNPixelsAreSkippedInPairs() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 0.25, 0.25);
        float[] maskedA = a.clone();
        float[] maskedB = b.clone();
        for (int x = 0; x < W; x++) {
            maskedA[x] = Float.NaN;
            maskedB[x] = Float.NaN;
        }
        double full = Localisability.correlation(a, b);
        double masked = Localisability.correlation(maskedA, maskedB);
        assertEquals("one masked row must barely move the correlation", full, masked, 0.05);
        assertTrue("and must stay a real number", masked > 0.5);
    }
}
