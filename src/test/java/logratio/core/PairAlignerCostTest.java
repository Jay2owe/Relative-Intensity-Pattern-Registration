/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import logratio.Timing;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What one pair alignment is allowed to cost, on the data that was once believed to make it cost 3,400x
 * more than usual.
 *
 * <p>The 2026-08-10 benchmark reported 237 s for a pair of a low-structure seed under a gain fade. That
 * turned out to be a suspended laptop rather than the estimator — see {@link Timing} and
 * {@code logratio.TimingTest} — but the fixture the report asked for is worth having anyway, because
 * nothing else in the suite would notice if a future change made the solver's cost data-dependent.
 *
 * <p><b>The assertion is a ratio, not a stopwatch.</b> An absolute budget in seconds passes or fails on
 * how fast the machine is and how warm the JIT is, so it would have to be set so loose as to catch
 * nothing. A ratio against a well-textured control pair measured in the same JVM, on the same thread,
 * seconds apart, is a statement about the algorithm: the hard case may cost more than the easy one, and
 * it may not cost <i>orders of magnitude</i> more. The work is hard-bounded by construction —
 * {@code maxIterations} iterations, six line-search halvings, one exhaustive sweep at the coarsest level
 * only — so any large ratio means that bound has been broken.
 *
 * <p>CPU time throughout, so that the guard cannot itself be tripped by the thing it was written about.
 */
public class PairAlignerCostTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;
    /** Aligns per measured span, so each span clears the Windows CPU-clock tick of about 15.6 ms. */
    private static final int REPEATS = 5;
    /**
     * Generous by design. The measured ratio is under 3x; the defect this guards against was 3,400x.
     * Anything between is not a machine artefact, and is worth failing on.
     */
    private static final double MAX_RATIO = 50;

    /**
     * A frame with little to localise on: faint texture over a large flat background, then a fade and
     * a clip at zero.
     *
     * <p>The clip is the part that matters and it is not decoration. As the fade takes the signal down,
     * a fixed noise floor pushes a growing share of pixels to exactly zero, so the log plane fills with
     * exact ties — which is the state the real seed reaches, and the state under which any selection,
     * median or scale estimate in the solver has to stay linear.
     */
    private static float[] faded(double dx, double dy, double gain, long seed) {
        float[] base = Synth.frame(W, H, dx, dy);
        float[] out = new float[base.length];
        Random rng = new Random(seed);
        for (int i = 0; i < base.length; i++) {
            // Flatten towards the mean, so the frame carries an order of magnitude less structure
            // than Synth's default, then fade and add a noise floor that does not fade with it.
            double weak = 2000.0 + 0.06 * (base[i] - 2000.0);
            double v = gain * weak + 12.0 * rng.nextGaussian();
            out[i] = (float) (v > 0 ? v : 0);
        }
        return out;
    }

    private static PairAligner.Fit align(float[] a, float[] b, PairAligner.Options o) {
        int levels = o.levelsFor(W, H);
        return PairAligner.align(Synth.pyramid(a, W, H, levels), Synth.pyramid(b, W, H, levels), o);
    }

    private static long cpuNs(float[] a, float[] b, PairAligner.Options o) {
        long best = Long.MAX_VALUE;
        for (int trial = 0; trial < 3; trial++) {
            Timing t = Timing.start();
            for (int i = 0; i < REPEATS; i++) align(a, b, o);
            best = Math.min(best, t.stop().cpuNs());
        }
        return best;
    }

    /**
     * The guard. A low-structure pair under a 4x fade must not cost a different order of magnitude from
     * a well-textured pair with no fade.
     */
    @Test
    public void aFadedLowStructurePairCostsTheSameOrderAsAnEasyOne() {
        PairAligner.Options o = new PairAligner.Options();
        o.norm = RobustNorm.HUBER;
        o.maxShift = 43;

        float[] easyA = Synth.frame(W, H, 0, 0);
        float[] easyB = Synth.frame(W, H, 1.25, -0.75);
        float[] hardA = faded(0, 0, 1.00, 11);
        float[] hardB = faded(1.25, -0.75, 0.25, 12);          // 4x fade, as in the benchmark

        align(easyA, easyB, o);                                 // warm the compiler on both paths
        align(hardA, hardB, o);

        long easy = cpuNs(easyA, easyB, o);
        long hard = cpuNs(hardA, hardB, o);
        double ratio = hard / (double) Math.max(1, easy);

        assertTrue(String.format("a faded low-structure pair cost %.1fx a textured one "
                        + "(%.0f ms against %.0f ms of CPU for %d aligns); the solver's work is "
                        + "bounded by construction, so this means the bound is broken",
                        ratio, hard / 1e6, easy / 1e6, REPEATS),
                ratio < MAX_RATIO);
    }

    /**
     * And it must still be solving the problem. A guard on cost alone would pass just as happily if the
     * solver had started bailing out of the hard case immediately.
     */
    @Test
    public void theFadedPairIsStillSolvedCorrectly() {
        PairAligner.Options o = new PairAligner.Options();
        o.norm = RobustNorm.HUBER;
        o.maxShift = 43;
        PairAligner.Fit f = align(faded(0, 0, 1.00, 11), faded(1.25, -0.75, 0.25, 12), o);

        assertTrue("must not refuse a pair that is merely dim: " + f.status, f.usable());
        assertEquals("dx", 1.25, f.transform.dx, 0.35);
        assertEquals("dy", -0.75, f.transform.dy, 0.35);
        assertTrue("and the gain must be recovered as the fade, about -2 in log2: " + f.logGain,
                f.logGain < -1.0 && f.logGain > -3.0);
    }
}
