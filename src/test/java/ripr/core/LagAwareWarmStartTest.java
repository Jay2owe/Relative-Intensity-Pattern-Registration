/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import ripr.api.RelativeIntensityPatternParameters;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A001 remains opt-in while its frozen accuracy and runtime gates are measured. */
public class LagAwareWarmStartTest {
    @Test
    public void acceptedPublicRecipeEnablesWarmStartsByDefault() {
        assertTrue(RelativeIntensityPatternParameters.builder().build().lagAwareWarmStarts);
    }

    @Test
    public void rigidWarmStartsMatchTheGlobalMultiLagTrajectoryOnKnownMotion() {
        int width = 128;
        int height = 128;
        int frames = 10;
        float[] base = Synth.frame(width, height, 0, 0);
        float[][] planes = new float[frames][];
        for (int t = 0; t < frames; t++) {
            planes[t] = new float[width * height];
            Transform truth = new Transform(0.35 * t, -0.20 * t, Math.toRadians(0.25 * t));
            Warper.warp(base, planes[t], width, height, truth.inverse(),
                    Warper.Interpolation.BILINEAR, Float.NaN);
        }
        Registration.Options baseline = options();
        Registration.Options candidate = baseline.copy();
        candidate.lagAwareWarmStarts = true;
        Registration.Result expected = Registration.run(
                Synth.source(width, height, planes), baseline, null, null);
        Registration.Result actual = Registration.run(
                Synth.source(width, height, planes), candidate, null, null);
        assertEquals(expected.pairs.size(), actual.pairs.size());
        for (int t = 0; t < frames; t++) {
            assertTrue("frame " + t + " translation differs: " + expected.cumulative[t]
                            + " vs " + actual.cumulative[t],
                    Math.hypot(expected.cumulative[t].dx - actual.cumulative[t].dx,
                            expected.cumulative[t].dy - actual.cumulative[t].dy) < 0.08);
            assertEquals("frame " + t + " angle", expected.cumulative[t].thetaDegrees(),
                    actual.cumulative[t].thetaDegrees(), 0.03);
        }
    }

    @Test
    public void translationOnlyIgnoresWarmStartSwitchExactly() {
        FrameSource source = Synth.source(96, 96,
                Synth.frame(96, 96, 0, 0), Synth.frame(96, 96, 0.7, -0.4),
                Synth.frame(96, 96, 1.4, -0.8), Synth.frame(96, 96, 2.1, -1.2));
        Registration.Options baseline = options();
        baseline.aligner.fitRotation = false;
        Registration.Options flagged = baseline.copy();
        flagged.lagAwareWarmStarts = true;
        Registration.Result expected = Registration.run(source, baseline, null, null);
        Registration.Result actual = Registration.run(source, flagged, null, null);
        for (int i = 0; i < expected.cumulative.length; i++) {
            assertEquals(expected.cumulative[i], actual.cumulative[i]);
            assertEquals(expected.status[i], actual.status[i]);
        }
    }

    @Test
    public void confidenceThresholdIsIgnoredExactlyWhileThePathIsDisabled() {
        FrameSource source = Synth.source(96, 96,
                Synth.frame(96, 96, 0, 0), Synth.frame(96, 96, 0.7, -0.4),
                Synth.frame(96, 96, 1.4, -0.8), Synth.frame(96, 96, 2.1, -1.2));
        Registration.Options baseline = options();
        Registration.Options flagged = baseline.copy();
        flagged.minimumRotationResidualGain = 0.05;
        Registration.Result expected = Registration.run(source, baseline, null, null);
        Registration.Result actual = Registration.run(source, flagged, null, null);
        for (int i = 0; i < expected.cumulative.length; i++) {
            assertEquals(expected.cumulative[i], actual.cumulative[i]);
            assertEquals(expected.status[i], actual.status[i]);
        }
    }

    private static Registration.Options options() {
        Registration.Options options = Registration.Options.recommendedFor(
                Reconciler.Reference.MULTILAG);
        options.lags = new int[]{1, 2, 4, 8};
        options.aligner.fitRotation = true;
        options.aligner.maxRotation = Math.toRadians(10);
        options.aligner.maxShift = 12;
        options.autoMaxShift = false;
        options.threads = 1;
        return options;
    }
}
