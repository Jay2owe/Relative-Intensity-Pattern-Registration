/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End to end: a synthetic recording that drifts, wobbles and bleaches, registered four ways.
 *
 * <p>Measured 2026-08-07 on the twelve-frame fixture below — mean error over all frames, and net error
 * at the last frame:
 * <ul>
 *   <li>consecutive chain: 0.0030 px mean, 0.0058 px net</li>
 *   <li><b>multi-lag: 0.0009 px mean, 0.0011 px net</b> — five times better on the cumulative number,
 *       which is exactly the quantity it exists to improve</li>
 *   <li>fixed reference: 0.0010 px mean (flattered here, because synthetic frames do not change over
 *       time the way a real recording does)</li>
 *   <li>rolling template: 0.0013 px mean, and the only strategy that reads the bleaching exactly</li>
 * </ul>
 */
public class RegistrationTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;
    private static final int FRAMES = 12;

    /** Drift plus a wobble plus a gain that halves across the recording. */
    private static final class Truth {
        final double[] tx = new double[FRAMES];
        final double[] ty = new double[FRAMES];
        final FrameSource source;

        Truth() {
            float[][] planes = new float[FRAMES][];
            for (int t = 0; t < FRAMES; t++) {
                tx[t] = 0.8 * t + 0.5 * Math.sin(t);
                ty[t] = -0.45 * t + 0.3 * Math.cos(1.7 * t);
                planes[t] = Synth.frame(W, H, tx[t], ty[t],
                        Math.pow(0.5, t / (double) (FRAMES - 1)), 0);
            }
            // Frame 0 is the reference, so cumulative[0] is the identity by definition and the truth
            // has to be expressed relative to frame 0 as well.
            double x0 = tx[0];
            double y0 = ty[0];
            for (int t = 0; t < FRAMES; t++) {
                tx[t] -= x0;
                ty[t] -= y0;
            }
            source = Synth.source(W, H, planes);
        }

        double error(Registration.Result r, int t) {
            return Math.hypot(r.cumulative[t].dx - tx[t], r.cumulative[t].dy - ty[t]);
        }

        double meanError(Registration.Result r) {
            double s = 0;
            for (int t = 0; t < FRAMES; t++) s += error(r, t);
            return s / FRAMES;
        }
    }

    private static Registration.Options options(Reconciler.Reference reference) {
        Registration.Options o = new Registration.Options();
        o.reference = reference;
        o.lags = new int[]{1, 2, 4, 8};
        o.aligner.norm = RobustNorm.TUKEY;
        o.threads = 1;
        return o;
    }

    @Test
    public void everyStrategyRecoversTheDrift() {
        Truth truth = new Truth();
        for (Reconciler.Reference reference : Reconciler.Reference.values()) {
            Registration.Result r = Registration.run(truth.source, options(reference), null, null);
            assertEquals("frame 0 is the reference", 0, r.cumulative[0].magnitude(), 1e-12);
            assertTrue(reference + " mean error " + truth.meanError(r),
                    truth.meanError(r) < 0.05);
            assertTrue(reference + " should reduce the residual",
                    r.medianResidualAfter() < 0.2 * r.medianResidualBefore());
            for (int t = 0; t < FRAMES; t++) {
                assertNotNull(reference + " frame " + t, r.cumulative[t]);
            }
        }
    }

    /** The point of multi-lag is the cumulative number, so that is what is asserted. */
    @Test
    public void multiLagBeatsChainingOnTheCumulativeNumber() {
        Truth truth = new Truth();
        Registration.Result chain =
                Registration.run(truth.source, options(Reconciler.Reference.CONSECUTIVE), null, null);
        Registration.Result multi =
                Registration.run(truth.source, options(Reconciler.Reference.MULTILAG), null, null);
        double chainNet = truth.error(chain, FRAMES - 1);
        double multiNet = truth.error(multi, FRAMES - 1);
        assertTrue("chain net " + chainNet + " vs multi-lag net " + multiNet,
                multiNet <= chainNet);
    }

    /** The bleaching trace, free from the same fit. Truth is -1.0 log2 across the recording. */
    @Test
    public void reportsTheBleachingTrace() {
        Truth truth = new Truth();
        for (Reconciler.Reference reference : Reconciler.Reference.values()) {
            Registration.Result r = Registration.run(truth.source, options(reference), null, null);
            assertEquals(reference + " gain at frame 0", 0, r.log2Gain[0], 1e-12);
            assertEquals(reference + " gain at the last frame",
                    -1.0, r.log2Gain[FRAMES - 1], 0.02);
            for (int t = 1; t < FRAMES; t++) {
                assertTrue(reference + " the trace must be monotone as the sample fades: "
                                + r.log2Gain[t - 1] + " then " + r.log2Gain[t],
                        r.log2Gain[t] <= r.log2Gain[t - 1] + 0.05);
            }
        }
    }

    /**
     * Every frame's pyramid is built exactly once.
     *
     * <p>A regression guard on a measured bug: with a lag-major pair plan and a cache sized to the
     * strategy's bare reach, multi-lag built 38 pyramids for 12 frames because each lag pass swept the
     * whole recording and evicted the early frames. Frame-major planning plus a heap-derived cache
     * brought it to 12 builds and 54 hits.
     */
    @Test
    public void buildsEachPyramidOnce() {
        Truth truth = new Truth();
        for (Reconciler.Reference reference : new Reconciler.Reference[]{
                Reconciler.Reference.CONSECUTIVE, Reconciler.Reference.MULTILAG,
                Reconciler.Reference.FIXED}) {
            Registration.Result r = Registration.run(truth.source, options(reference), null, null);
            assertTrue(reference + " built " + r.pyramidsBuilt + " pyramids for " + FRAMES
                            + " frames — the cache is thrashing",
                    r.pyramidsBuilt <= FRAMES);
        }
    }

    /** Serial and parallel must agree bit for bit. */
    @Test
    public void serialAndParallelAgreeExactly() {
        Truth truth = new Truth();
        Registration.Options serial = options(Reconciler.Reference.MULTILAG);
        serial.threads = 1;
        Registration.Options parallel = options(Reconciler.Reference.MULTILAG);
        parallel.threads = 4;
        Registration.Result a = Registration.run(truth.source, serial, null, null);
        Registration.Result b = Registration.run(truth.source, parallel, null, null);
        for (int t = 0; t < FRAMES; t++) {
            assertEquals("frame " + t + " dx", a.cumulative[t].dx, b.cumulative[t].dx, 0);
            assertEquals("frame " + t + " dy", a.cumulative[t].dy, b.cumulative[t].dy, 0);
            assertEquals("frame " + t + " gain", a.log2Gain[t], b.log2Gain[t], 0);
        }
    }

    /** A gain-invariant estimator should not care that the recording bleaches at all. */
    @Test
    public void bleachingDoesNotDegradeTheGeometry() {
        float[][] steady = new float[FRAMES][];
        float[][] fading = new float[FRAMES][];
        double[] tx = new double[FRAMES];
        for (int t = 0; t < FRAMES; t++) {
            tx[t] = 0.9 * t;
            steady[t] = Synth.frame(W, H, tx[t], 0, 1.0, 0);
            fading[t] = Synth.frame(W, H, tx[t], 0, Math.pow(0.4, t / (double) (FRAMES - 1)), 0);
        }
        Registration.Result a = Registration.run(Synth.source(W, H, steady),
                options(Reconciler.Reference.CONSECUTIVE), null, null);
        Registration.Result b = Registration.run(Synth.source(W, H, fading),
                options(Reconciler.Reference.CONSECUTIVE), null, null);
        for (int t = 0; t < FRAMES; t++) {
            assertEquals("frame " + t + " must be unaffected by a 2.5x fade",
                    a.cumulative[t].dx, b.cumulative[t].dx, 0.01);
        }
    }

    @Test
    public void cancellationIsHonoured() {
        Truth truth = new Truth();
        try {
            Registration.run(truth.source, options(Reconciler.Reference.MULTILAG), null,
                    () -> true);
            fail("expected cancellation");
        } catch (java.util.concurrent.CancellationException expected) {
            // the contract
        }
    }

    @Test
    public void aSingleFrameIsTrivialRatherThanAnError() {
        Registration.Result r = Registration.run(
                Synth.source(W, H, Synth.frame(W, H, 0, 0)), options(
                        Reconciler.Reference.CONSECUTIVE), null, null);
        assertEquals(1, r.cumulative.length);
        assertEquals(0, r.cumulative[0].magnitude(), 0);
    }

    /** A lag set without 1 leaves frames unconstrained, so it is refused rather than half-solved. */
    @Test
    public void rejectsALagSetWithoutLagOne() {
        Truth truth = new Truth();
        Registration.Options o = options(Reconciler.Reference.MULTILAG);
        o.lags = new int[]{2, 4};
        try {
            Registration.run(truth.source, o, null, null);
            fail("expected a rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("lag set must contain 1"));
        }
    }

    @Test
    public void rejectsAReferenceFrameOutsideTheStack() {
        Truth truth = new Truth();
        Registration.Options o = options(Reconciler.Reference.FIXED);
        o.referenceFrame = FRAMES + 3;
        try {
            Registration.run(truth.source, o, null, null);
            fail("expected a rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reference frame"));
        }
    }

    /** A non-zero fixed reference must anchor there. */
    @Test
    public void fixedReferenceAnchorsWhereAsked() {
        Truth truth = new Truth();
        Registration.Options o = options(Reconciler.Reference.FIXED);
        o.referenceFrame = 5;
        Registration.Result r = Registration.run(truth.source, o, null, null);
        assertEquals("frame 5 is the origin", 0, r.cumulative[5].magnitude(), 1e-12);
        for (int t = 0; t < FRAMES; t++) {
            assertEquals("frame " + t + " relative to frame 5",
                    truth.tx[t] - truth.tx[5], r.cumulative[t].dx, 0.05);
        }
    }
}
