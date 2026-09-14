/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

/** Deterministic raw-image fixtures for piecewise-constant remount rotation. */
final class EventRotationFixtures {
    static final int WIDTH = 96;
    static final int HEIGHT = 96;

    private EventRotationFixtures() { }

    static double[] trajectory(int frames, int[] eventFrames, double[] eventDeltas) {
        if (eventFrames.length != eventDeltas.length) {
            throw new IllegalArgumentException("event frames and deltas differ in length");
        }
        double[] angles = new double[frames];
        double cumulative = 0.0;
        int event = 0;
        for (int frame = 0; frame < frames; frame++) {
            if (event < eventFrames.length && frame == eventFrames[event]) {
                cumulative += eventDeltas[event++];
            }
            angles[frame] = cumulative;
        }
        return angles;
    }

    static Transform[] truth(int frames, int[] eventFrames, double[] eventDeltas) {
        double[] angles = trajectory(frames, eventFrames, eventDeltas);
        Transform[] truth = new Transform[frames];
        for (int frame = 0; frame < frames; frame++) {
            truth[frame] = new Transform(0.35 * frame, -0.22 * frame, angles[frame]);
        }
        return truth;
    }

    static FrameSource source(int frames, int[] eventFrames, double[] eventDeltas) {
        return source(frames, eventFrames, eventDeltas, true);
    }

    static FrameSource source(
            int frames, int[] eventFrames, double[] eventDeltas, boolean fade) {
        float[] base = Synth.frame(WIDTH, HEIGHT, 0, 0);
        Transform[] truth = truth(frames, eventFrames, eventDeltas);
        float[][] planes = new float[frames][];
        for (int frame = 0; frame < frames; frame++) {
            float[] moved = new float[base.length];
            Warper.warp(base, moved, WIDTH, HEIGHT, truth[frame].inverse(),
                    Warper.Interpolation.BILINEAR, Float.NaN);
            double gain = fade ? Math.pow(2.0, -frame / 20.0) : 1.0;
            if (gain != 1.0) {
                for (int pixel = 0; pixel < moved.length; pixel++) {
                    if (Float.isFinite(moved[pixel])) moved[pixel] *= gain;
                }
            }
            planes[frame] = moved;
        }
        return Synth.source(WIDTH, HEIGHT, planes);
    }

    static Registration.Options options(int... zeroBasedEvents) {
        Registration.Options options = new Registration.Options();
        options.rotationMode = RotationMode.KNOWN_EVENTS;
        options.rotationEventFrames = zeroBasedEvents.clone();
        options.rotationEventWindow = 2;
        options.reference = Reconciler.Reference.MULTILAG;
        options.lags = new int[]{1, 2, 4};
        options.aligner.maxShift = 8;
        options.aligner.maxRotation = Math.toRadians(5);
        options.aligner.norm = RobustNorm.HUBER;
        options.aligner.support = PairAligner.PixelSupport.ALL;
        options.threads = 1;
        return options;
    }
}
