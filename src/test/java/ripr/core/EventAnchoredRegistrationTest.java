/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class EventAnchoredRegistrationTest {
    @Test
    public void estimatesEventOnceThenFitsAccurateTranslationAtThatExactAngle() {
        int frames = 8;
        int event = 4;
        double delta = Math.toRadians(2.0);
        FrameSource source = EventRotationFixtures.source(
                frames, new int[]{event}, new double[]{delta});
        Transform[] truth = EventRotationFixtures.truth(
                frames, new int[]{event}, new double[]{delta});
        Registration.Options options = EventRotationFixtures.options(event);

        Registration.Result result = Registration.run(source, options, null, null);

        assertNotNull(result.eventRotations);
        double accepted = result.eventRotations.events[0].deltaTheta;
        assertEquals(delta, accepted, Math.toRadians(0.03));
        for (int frame = 0; frame < frames; frame++) {
            assertEquals(frame < event ? 0.0 : accepted,
                    result.cumulative[frame].theta, 0.0);
            assertEquals(truth[frame].dx, result.cumulative[frame].dx, 0.04);
            assertEquals(truth[frame].dy, result.cumulative[frame].dy, 0.04);
        }

        Registration.Result refit = Registration.refitWithSupport(
                source, options, result, null, null, null);
        assertEquals(Double.doubleToLongBits(accepted), Double.doubleToLongBits(
                refit.eventRotations.events[0].deltaTheta));
        for (int frame = 0; frame < frames; frame++) {
            assertEquals(Double.doubleToLongBits(result.cumulative[frame].theta),
                    Double.doubleToLongBits(refit.cumulative[frame].theta));
        }
    }

    @Test
    public void fixedReferenceRebasesThePiecewiseTrajectory() {
        int event = 4;
        FrameSource source = EventRotationFixtures.source(
                8, new int[]{event}, new double[]{Math.toRadians(2)});
        Registration.Options options = EventRotationFixtures.options(event);
        options.reference = Reconciler.Reference.FIXED;
        options.referenceFrame = 5;

        Registration.Result result = Registration.run(source, options, null, null);
        double accepted = result.eventRotations.events[0].deltaTheta;
        for (int frame = 0; frame < event; frame++) {
            assertEquals(-accepted, result.cumulative[frame].theta, 0.0);
        }
        for (int frame = event; frame < 8; frame++) {
            assertEquals(0.0, result.cumulative[frame].theta, 0.0);
        }
    }

    @Test
    public void fixedAnglePairFitNeverChangesThePrescribedAngle() {
        int width = 96;
        int height = 96;
        float[] base = Synth.frame(width, height, 0, 0);
        float[] moved = new float[base.length];
        Transform truth = new Transform(1.2, -0.8, Math.toRadians(1.5));
        Warper.warp(base, moved, width, height, truth.inverse(),
                Warper.Interpolation.BILINEAR, Float.NaN);
        PairAligner.Options options = new PairAligner.Options();
        options.maxShift = 8;
        int levels = options.levelsFor(width, height);

        PairAligner.Fit fit = PairAligner.alignTranslationAtFixedRotation(
                Synth.pyramid(base, width, height, levels),
                Synth.pyramid(moved, width, height, levels), options,
                new Transform(0, 0, truth.theta), null);

        assertEquals(Double.doubleToLongBits(truth.theta),
                Double.doubleToLongBits(fit.transform.theta));
        assertEquals(truth.dx, fit.transform.dx, 0.03);
        assertEquals(truth.dy, fit.transform.dy, 0.03);
    }
}
