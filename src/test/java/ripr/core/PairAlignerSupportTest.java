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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Pins the opt-in warm-start and solver-only support seam used by two-pass experiments. */
public class PairAlignerSupportTest {

    private static final int W = 96;
    private static final int H = 96;
    private static final double DX = 2.4;
    private static final double DY = -1.7;

    @Test
    public void aSmallIntentionalSupportIsNotMisreportedAsLostOverlap() {
        PairAligner.Options options = new PairAligner.Options();
        options.norm = RobustNorm.TUKEY;
        options.support = PairAligner.PixelSupport.GRADIENT;
        options.maxShift = 10;
        int levels = options.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, DX, DY), W, H, levels);

        // Only about 4% of the full frame is selected: deliberately below minValidFraction=10%.
        // It remains enough textured evidence to refine a good pilot.
        boolean[][] support = centralSupport(a, 20);
        PairAligner.Fit fit = PairAligner.alignFrom(a, b, options,
                Transform.translation(2.0, -1.4), support);

        assertNotEquals(PairAligner.Status.REFUSED_LOW_OVERLAP, fit.status);
        assertTrue("masked warm start recovered " + fit.transform,
                Math.hypot(fit.transform.dx - DX, fit.transform.dy - DY) < 0.05);
        assertTrue("quality valid fraction must still describe the complete frame: "
                + fit.validFraction, fit.validFraction > 0.9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void supportShapeMustMatchEveryPyramidLevel() {
        PairAligner.Options options = new PairAligner.Options();
        LogPlane[] a = Synth.pyramid(Synth.frame(W, H, 0, 0), W, H, 2);
        LogPlane[] b = Synth.pyramid(Synth.frame(W, H, DX, DY), W, H, 2);
        PairAligner.alignFrom(a, b, options, Transform.IDENTITY,
                new boolean[][]{new boolean[W * H]});
    }

    private static boolean[][] centralSupport(LogPlane[] pyramid, int fullWidth) {
        boolean[][] out = new boolean[pyramid.length][];
        for (int l = 0; l < pyramid.length; l++) {
            int scale = 1 << l;
            int selected = Math.max(3, fullWidth / scale);
            int x0 = (pyramid[l].width - selected) / 2;
            int y0 = (pyramid[l].height - selected) / 2;
            out[l] = new boolean[pyramid[l].width * pyramid[l].height];
            for (int y = y0; y < y0 + selected; y++) {
                for (int x = x0; x < x0 + selected; x++) {
                    out[l][y * pyramid[l].width + x] = true;
                }
            }
        }
        return out;
    }
}
