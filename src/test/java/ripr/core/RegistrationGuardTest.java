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

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The three configurations the benchmark measured to be wrong, and what the API now does about each.
 *
 * <p>All three used to be silent: a caller could combine a redescending norm with a consecutive chain,
 * turn gain profiling off under multi-lag, or leave a 30 px bound on a recording that moves 200, and
 * get a result that looked like every other result. Each of those is a measured accuracy loss of
 * between 1.7x and 163x, so none of them may be silent.
 */
public class RegistrationGuardTest {

    private static final int W = Synth.DEFAULT_SIZE;
    private static final int H = Synth.DEFAULT_SIZE;

    private static FrameSource drifting(int frames, double stepX, double stepY) {
        float[][] planes = new float[frames][];
        for (int t = 0; t < frames; t++) planes[t] = Synth.frame(W, H, stepX * t, stepY * t);
        return Synth.source(W, H, planes);
    }

    private static boolean has(List<Registration.Warning> warnings, Registration.Warning.Kind kind) {
        for (Registration.Warning w : warnings) {
            if (w.kind == kind) return true;
        }
        return false;
    }

    // ---- item 2: the norm's default belongs to the reconciliation ------------------------ //

    /** Measured: chain + Tukey 0.466 px against chain + least squares 0.281 px on clean data. */
    @Test
    public void aRedescendingNormOnAChainIsWarnedAbout() {
        Registration.Options o = new Registration.Options();
        o.reference = Reconciler.Reference.CONSECUTIVE;
        o.aligner.norm = RobustNorm.TUKEY;
        assertTrue("a chain with Tukey must warn",
                has(Registration.configurationWarnings(o), Registration.Warning.Kind.NORM_FOR_REFERENCE));
    }

    /** And the inverse: multi-lag's redundancy is what makes Tukey pay, so dropping it also warns. */
    @Test
    public void leastSquaresUnderMultiLagIsWarnedAbout() {
        Registration.Options o = new Registration.Options();
        o.reference = Reconciler.Reference.MULTILAG;
        o.aligner.norm = RobustNorm.LEAST_SQUARES;
        assertTrue("multi-lag with least squares must warn",
                has(Registration.configurationWarnings(o), Registration.Warning.Kind.NORM_FOR_REFERENCE));
    }

    /** The recommended combination for each reconciliation must be the one that does not warn. */
    @Test
    public void theRecommendedCombinationIsSilent() {
        for (Reconciler.Reference reference : Reconciler.Reference.values()) {
            Registration.Options o = Registration.Options.recommendedFor(reference);
            assertEquals(reference, o.reference);
            assertTrue(reference + " recommended combination must not warn: "
                            + Registration.configurationWarnings(o),
                    Registration.configurationWarnings(o).isEmpty());
        }
        assertSame("multi-lag earns the redescending norm",
                RobustNorm.TUKEY, Registration.Options.recommendedFor(
                        Reconciler.Reference.MULTILAG).aligner.norm);
        assertSame("a chain does not",
                RobustNorm.LEAST_SQUARES, Registration.Options.recommendedFor(
                        Reconciler.Reference.CONSECUTIVE).aligner.norm);
    }

    /** Warnings reach the caller through the result too, not only through the pre-run check. */
    @Test
    public void theResultCarriesTheConfigurationWarning() {
        Registration.Options o = new Registration.Options();
        o.reference = Reconciler.Reference.CONSECUTIVE;
        o.aligner.norm = RobustNorm.TUKEY;
        o.threads = 1;
        Registration.Result r = Registration.run(drifting(6, 0.4, 0.3), o, null, null);
        assertTrue("the result must repeat the configuration warning",
                has(r.warnings, Registration.Warning.Kind.NORM_FOR_REFERENCE));
    }

    // ---- item 5: unregistrable pixels are said so before the wait, not after -------------- //

    /** A smooth featureless recording correlates near 1 and cannot be registered. Preflight says so. */
    @Test
    public void preflightWarnsAboutPixelsNothingCanRegister() {
        float[][] planes = new float[4][];
        for (int t = 0; t < planes.length; t++) {
            planes[t] = new float[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    double dx = (x - 0.4 * t - W / 2.0) / (0.45 * W);
                    double dy = (y - W / 2.0) / (0.45 * H);
                    planes[t][y * W + x] = (float) (1000 + 2000 * Math.exp(-(dx * dx + dy * dy)));
                }
            }
        }
        Registration.Options o = Registration.Options.recommendedFor(Reconciler.Reference.CONSECUTIVE);
        assertTrue("a smooth blob must be flagged before the run",
                has(Registration.preflight(Synth.source(W, H, planes), o),
                        Registration.Warning.Kind.LOW_LOCALISABILITY));
    }

    /**
     * And pixel-scale structure must not be, or the warning is noise.
     *
     * <p>The fixture is deliberately <em>not</em> {@link Synth}. Synth's content is a sum of 3-13 cycle
     * sinusoids, so its features are 15-60 px wide and a one-pixel shift barely changes it: it scores
     * 0.032, below the threshold, while the estimator still recovers its shift to a hundredth of a
     * pixel because it carries no noise. That is not a fault in either — localisability answers "can a
     * one-pixel error be seen in these pixels", and the threshold was calibrated on real noisy
     * recordings where it cannot. Structure at the pixel scale is what has to pass.
     */
    @Test
    public void preflightIsQuietOnPixelScaleStructure() {
        float[][] planes = new float[4][];
        for (int t = 0; t < planes.length; t++) {
            planes[t] = new float[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    double u = x - 0.4 * t;
                    double v = y - 0.3 * t;
                    double s = Math.sin(1.7 * u + 0.4) * Math.cos(2.1 * v - 0.7)
                            + 0.7 * Math.sin(2.6 * u - 1.1) * Math.cos(1.3 * v + 0.2);
                    planes[t][y * W + x] = (float) (2000 + 600 * s);
                }
            }
        }
        Registration.Options o = Registration.Options.recommendedFor(Reconciler.Reference.MULTILAG);
        assertTrue("pixel-scale texture must not be flagged: "
                        + Registration.preflight(Synth.source(W, H, planes), o),
                Registration.preflight(Synth.source(W, H, planes), o).isEmpty());
    }

    // ---- item 3: multi-lag without gain profiling is not a configuration ------------------ //

    /** Measured: turning gain profiling off degrades a chain 3.3x and multi-lag 163x. */
    @Test
    public void multiLagWithoutGainProfilingIsRefused() {
        Registration.Options o = Registration.Options.recommendedFor(Reconciler.Reference.MULTILAG);
        o.aligner.profileGain = false;
        o.threads = 1;
        try {
            Registration.run(drifting(6, 0.4, 0.3), o, null, null);
            fail("expected a refusal, not a 163x accuracy loss");
        } catch (IllegalArgumentException e) {
            assertTrue("the message must say why: " + e.getMessage(),
                    e.getMessage().contains("163x"));
        }
    }

    /** A chain without gain profiling is the SSD baseline and stays legal — it is only worse, not unsafe. */
    @Test
    public void aChainWithoutGainProfilingStillRuns() {
        Registration.Options o = new Registration.Options();
        o.reference = Reconciler.Reference.CONSECUTIVE;
        o.aligner.norm = RobustNorm.LEAST_SQUARES;
        o.aligner.profileGain = false;
        o.threads = 1;
        Registration.Result r = Registration.run(drifting(6, 0.4, 0.3), o, null, null);
        assertEquals(6, r.cumulative.length);
    }

    // ---- item 4: the saturation ceiling, which had never been measured -------------------- //

    /**
     * Bright constant patches over a tenth of the field are what the benchmark loses on, and what
     * {@code saturationPercentile} is for. Measured there: log-ratio Tukey with gradient support goes
     * from 24.4 px to 0.31 on a chain once every pixel above each frame's 90th percentile is excluded.
     * This is the same effect on a fixture small enough to be a unit test.
     */
    @Test
    public void aSaturationCeilingRecoversAlignmentUnderBrightBlocks() {
        int frames = 6;
        float[][] planes = new float[frames][];
        for (int t = 0; t < frames; t++) {
            planes[t] = Synth.withSparseChange(Synth.frame(W, H, 1.5 * t, 1.0 * t), W, H, 0.10, t);
        }
        FrameSource source = Synth.source(W, H, planes);

        Registration.Options plain =
                Registration.Options.recommendedFor(Reconciler.Reference.CONSECUTIVE);
        plain.threads = 1;
        Registration.Options cut = plain.copy();
        cut.saturationPercentile = 90;

        double withoutCut = netError(Registration.run(source, plain, null, null), frames, 1.5, 1.0);
        double withCut = netError(Registration.run(source, cut, null, null), frames, 1.5, 1.0);
        assertTrue("the ceiling must help, not merely change the answer: "
                + withoutCut + " then " + withCut, withCut < withoutCut);
    }

    /** And it must be off unless asked for, or every existing result silently changes. */
    @Test
    public void theSaturationCeilingIsOffByDefault() {
        assertTrue("saturationPercentile must default to unset",
                Double.isNaN(new Registration.Options().saturationPercentile));
        assertTrue("and must survive a copy",
                Double.isNaN(new Registration.Options().copy().saturationPercentile));
        Registration.Options o = new Registration.Options();
        o.saturationPercentile = 90;
        assertEquals(90.0, o.copy().saturationPercentile, 0.0);
    }

    private static double netError(Registration.Result r, int frames, double sx, double sy) {
        int last = frames - 1;
        return Math.hypot(r.cumulative[last].dx - sx * last, r.cumulative[last].dy - sy * last);
    }

    // ---- the whole loop: measure, then actually put the frames back ---------------------- //

    /**
     * <b>Registering a drifting stack with its own recovered transforms must hold it still.</b>
     *
     * <p>Every other test here checks the numbers. This one checks that applying them to pixels moves
     * the pixels the right way, which is a separate thing and is easy to get backwards: {@link Warper}
     * samples <em>through</em> a transform, so it already undoes the motion the transform describes,
     * and negating it first doubles the drift instead of removing it. That mistake produced a
     * plausible-looking stack and a panel in which the corrected half was 5-8% worse than the
     * uncorrected one — a sign error is not visible in a median error in pixels, only here.
     *
     * <p>The measure is per-pixel temporal standard deviation: on a stack held still it collapses to
     * the noise, and on a drifting one every edge draws an outline as it sweeps across.
     */
    @Test
    public void appliedTransformsHoldTheStackStill() {
        int frames = 8;
        float[][] planes = new float[frames][];
        for (int t = 0; t < frames; t++) planes[t] = Synth.frame(W, H, 1.7 * t, 1.1 * t);
        FrameSource source = Synth.source(W, H, planes);

        Registration.Options o = Registration.Options.recommendedFor(Reconciler.Reference.MULTILAG);
        o.lags = new int[]{1, 2, 4};
        o.threads = 1;
        Registration.Result r = Registration.run(source, o, null, null);

        float[][] held = new float[frames][];
        for (int t = 0; t < frames; t++) {
            held[t] = new float[W * H];
            Warper.warp(planes[t], held[t], W, H, r.cumulative[t],
                    Warper.Interpolation.BILINEAR, Float.NaN);
        }

        double before = temporalSd(planes);
        double after = temporalSd(held);
        assertTrue("drifting stack must vary: " + before, before > 1.0);
        assertTrue("applying the transforms must hold it still, not move it further: "
                + before + " then " + after, after < 0.1 * before);
    }

    /** Mean over pixels of the temporal standard deviation, skipping the warp margin. */
    private static double temporalSd(float[][] planes) {
        double total = 0;
        int counted = 0;
        for (int i = 0; i < planes[0].length; i++) {
            double sum = 0;
            int k = 0;
            for (float[] p : planes) {
                if (Float.isNaN(p[i])) continue;
                sum += p[i];
                k++;
            }
            if (k < planes.length) continue;         // touched by the margin in some frame
            double mean = sum / k;
            double ss = 0;
            for (float[] p : planes) ss += (p[i] - mean) * (p[i] - mean);
            total += Math.sqrt(ss / k);
            counted++;
        }
        return counted > 0 ? total / counted : Double.NaN;
    }

    // ---- item 6: maxShift must not saturate silently ------------------------------------- //

    /** A recording that moves further than the bound must say so, not report the bound as the motion. */
    @Test
    public void hittingTheShiftBoundIsAWarningAndNotJustAColumn() {
        Registration.Options o = new Registration.Options();
        o.reference = Reconciler.Reference.CONSECUTIVE;
        o.aligner.maxShift = 2;                 // deliberately below the 6 px per-frame truth
        o.threads = 1;
        Registration.Result r = Registration.run(drifting(5, 6, 0), o, null, null);
        assertTrue("frames pinned on the bound must produce a warning",
                has(r.warnings, Registration.Warning.Kind.AT_SHIFT_BOUND));
        for (Registration.Warning w : r.warnings) {
            if (w.kind == Registration.Warning.Kind.AT_SHIFT_BOUND) {
                assertTrue("the warning must name the remedy: " + w.message,
                        w.message.contains("autoMaxShift"));
            }
        }
    }

    /** A bound that is not reached must stay quiet, or the warning means nothing. */
    @Test
    public void aBoundThatIsNotReachedIsSilent() {
        Registration.Options o = Registration.Options.recommendedFor(Reconciler.Reference.CONSECUTIVE);
        o.aligner.maxShift = 30;
        o.threads = 1;
        Registration.Result r = Registration.run(drifting(5, 1.0, 0.7), o, null, null);
        assertFalse("nothing was pinned, so nothing may be claimed: " + r.warnings,
                has(r.warnings, Registration.Warning.Kind.AT_SHIFT_BOUND));
    }

    /** The derived bound has to cover the real motion, and derive it from the pixels rather than a guess. */
    @Test
    public void theShiftBoundCanBeDerivedFromTheRecording() {
        FrameSource source = drifting(6, 6, 0);       // 30 px net, 6 px per frame
        Registration.Options o = Registration.Options.recommendedFor(Reconciler.Reference.CONSECUTIVE);
        Registration.ShiftEstimate est = Registration.estimateShiftBound(source, o);
        assertTrue("the bound must cover the 6 px step it was measured from: " + est,
                est.suggestedMaxShift >= 6);
        assertTrue("and must not be the arbitrary 30 px default when the motion exceeds it: " + est,
                est.largestStepPx > 0);

        o.aligner.maxShift = 2;
        o.autoMaxShift = true;
        o.threads = 1;
        Registration.Result r = Registration.run(source, o, null, null);
        assertFalse("deriving the bound must clear the warning the 2 px guess produced: " + r.warnings,
                has(r.warnings, Registration.Warning.Kind.AT_SHIFT_BOUND));
        assertEquals("and the motion must then be recovered", 30.0,
                r.cumulative[5].dx, 0.5);
    }

    /** The formerly silent planning pass must identify itself and report every surveyed pair. */
    @Test
    public void shiftBoundPlanningReportsVisibleProgress() {
        FrameSource source = drifting(4, 2, 0);
        Registration.Options o = Registration.Options.recommendedFor(
                Reconciler.Reference.CONSECUTIVE);
        List<String> events = new ArrayList<>();
        Registration.estimateShiftBound(source, o, new PairScheduler.Progress() {
            @Override public void update(int done, int total) { }

            @Override public void phase(String name, int done, int total) {
                events.add(name + ":" + done + "/" + total);
            }
        }, PairScheduler.Cancellation.NEVER);
        assertEquals(Registration.PHASE_SHIFT_BOUND + ":0/3", events.get(0));
        assertEquals(Registration.PHASE_SHIFT_BOUND + ":3/3",
                events.get(events.size() - 1));
    }

    /** Parallel planning changes elapsed time, never the bound or its evidence count. */
    @Test
    public void parallelShiftBoundMatchesSerialExactly() {
        FrameSource source = drifting(12, 1.3, -0.7);
        Registration.Options serial = Registration.Options.recommendedFor(
                Reconciler.Reference.MULTILAG);
        serial.lags = new int[]{1, 2, 4, 8};
        serial.threads = 1;
        Registration.Options parallel = serial.copy();
        parallel.threads = 4;
        Registration.ShiftEstimate expected = Registration.estimateShiftBound(source, serial);
        Registration.ShiftEstimate actual = Registration.estimateShiftBound(source, parallel);
        assertEquals(expected.largestStepPx, actual.largestStepPx, 0.0);
        assertEquals(expected.suggestedMaxShift, actual.suggestedMaxShift, 0.0);
        assertEquals(expected.resolutionPx, actual.resolutionPx, 0.0);
        assertEquals(expected.pairs, actual.pairs);
    }

    @Test
    public void parallelShiftBoundProgressNeverMovesBackwards() {
        Registration.Options options = Registration.Options.recommendedFor(
                Reconciler.Reference.MULTILAG);
        options.lags = new int[]{1, 2, 4, 8};
        options.threads = 4;
        List<Integer> updates = new ArrayList<>();
        Registration.estimateShiftBound(drifting(12, 1.1, -0.6), options,
                new PairScheduler.Progress() {
                    @Override public void update(int done, int total) { }

                    @Override public void phase(String name, int done, int total) {
                        if (Registration.PHASE_SHIFT_BOUND.equals(name)) updates.add(done);
                    }
                }, PairScheduler.Cancellation.NEVER);
        for (int i = 1; i < updates.size(); i++) {
            assertTrue("progress regressed at " + i + ": " + updates,
                    updates.get(i) >= updates.get(i - 1));
        }
    }

    /** Rigid registration must not turn the cheap translation-bound survey into an angular search. */
    @Test
    public void rigidModeKeepsShiftBoundPlanningTranslationOnly() {
        Registration.Options o = new Registration.Options();
        o.aligner.fitRotation = true;
        o.aligner.maxRotation = Math.toRadians(12.0);
        PairAligner.Options pass = Registration.shiftBoundAlignerOptions(
                o, 16, PairScheduler.Cancellation.NEVER);
        assertFalse("the pre-pass estimates a translation bound only", pass.fitRotation);
        assertEquals(16.0, pass.maxShift, 0.0);
    }

    /** Multi-lag pairs span many steps, so the bound has to be measured over the pairs actually planned. */
    @Test
    public void theDerivedBoundCoversTheLongestLagNotJustTheStep() {
        FrameSource source = drifting(9, 4, 0);
        Registration.Options chain =
                Registration.Options.recommendedFor(Reconciler.Reference.CONSECUTIVE);
        Registration.Options multi =
                Registration.Options.recommendedFor(Reconciler.Reference.MULTILAG);
        multi.lags = new int[]{1, 2, 4, 8};
        double chainBound = Registration.estimateShiftBound(source, chain).largestStepPx;
        double multiBound = Registration.estimateShiftBound(source, multi).largestStepPx;
        assertTrue("a lag-8 pair spans 32 px where a lag-1 pair spans 4: "
                + chainBound + " then " + multiBound, multiBound > chainBound);
    }
}
