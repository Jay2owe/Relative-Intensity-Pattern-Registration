/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Translation-only phase correlation over progressively wider frame gaps.
 *
 * <p>The adjacent-frame pass preserves abrupt movement. Wider gaps then correct accumulated errors
 * caused by a temporary intensity pulse, without smoothing a real jump away. The update schedule is
 * the one used by Correct 3D Drift's multi-time-scale route: gaps 1, 3, 9, 27, and the full recording.
 */
public final class MultiTimeScalePhaseCorrelation {

    /** Registration output plus the number of frame pairs measured. */
    public static final class Result {
        public final Transform[] cumulative;
        public final int pairCount;

        Result(Transform[] cumulative, int pairCount) {
            this.cumulative = cumulative;
            this.pairCount = pairCount;
        }
    }

    private MultiTimeScalePhaseCorrelation() {
    }

    /**
     * Estimate cumulative content displacement for every frame.
     *
     * @param wholePixels round each pair measurement to a whole pixel, matching the native
     *                    Correct 3D Drift phase-correlation engine
     */
    public static Result register(float[][] frames, int width, int height, boolean wholePixels) {
        return register(frames, width, height, wholePixels, false);
    }

    /** Register using phase-peak verification against all overlapping tissue pixels. */
    public static Result registerVerified(float[][] frames, int width, int height,
                                          boolean wholePixels) {
        return register(frames, width, height, wholePixels, true);
    }

    /**
     * Register every frame directly to image 1 using verified phase peaks.
     *
     * <p>No later measurement is distributed over earlier frames. This matters for recordings with
     * light pulses: a bad or corrective long-gap edge cannot turn one pulse into a smooth artificial
     * excursion, and a real jump in the final frame remains confined to that frame.
     */
    public static Result registerVerifiedToFirst(float[][] frames, int width, int height,
                                                 boolean wholePixels) {
        validate(frames, width, height);
        Transform[] cumulative = new Transform[frames.length];
        cumulative[0] = Transform.IDENTITY;
        for (int frame = 1; frame < frames.length; frame++) {
            double[] content = PhaseCorrelation.shiftVerified(
                    frames[0], frames[frame], width, height, 5);
            double dx = wholePixels ? Math.round(content[0]) : content[0];
            double dy = wholePixels ? Math.round(content[1]) : content[1];
            if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
                throw new IllegalStateException("phase correlation returned a non-finite shift");
            }
            cumulative[frame] = Transform.translation(dx, dy);
        }
        return new Result(cumulative, Math.max(0, frames.length - 1));
    }

    /**
     * Direct-to-image-1 registration constrained to piecewise-constant jump motion.
     *
     * <p>Large, persistent position changes split the recording into stationary sections. Each section
     * uses its median direct measurement, removing isolated one-pixel pulse responses without moving a
     * true terminal jump into earlier frames.
     */
    public static Result registerVerifiedJumpsToFirst(float[][] frames, int width, int height,
                                                      boolean wholePixels) {
        Result direct = registerVerifiedToFirst(frames, width, height, wholePixels);
        return new Result(stabiliseJumpTrace(direct.cumulative), direct.pairCount);
    }

    /** Visible for regression tests of the temporal rule, independent of the image estimator. */
    public static Transform[] stabiliseJumpTrace(Transform[] direct) {
        if (direct == null || direct.length == 0) {
            throw new IllegalArgumentException("at least one transform is required");
        }
        int n = direct.length;
        List<Integer> cuts = new ArrayList<>();
        cuts.add(0);
        for (int frame = 1; frame < n; frame++) {
            Transform before = direct[frame - 1];
            Transform after = direct[frame];
            if (before == null || after == null) {
                throw new IllegalArgumentException("transforms must not be null");
            }
            if (Math.hypot(after.dx - before.dx, after.dy - before.dy) < 2.0) continue;

            int left0 = Math.max(cuts.get(cuts.size() - 1), frame - 3);
            int right1 = Math.min(n, frame + 3);
            double leftX = median(direct, left0, frame, true);
            double leftY = median(direct, left0, frame, false);
            double rightX = median(direct, frame, right1, true);
            double rightY = median(direct, frame, right1, false);
            if (Math.hypot(rightX - leftX, rightY - leftY) >= 2.0) cuts.add(frame);
        }
        cuts.add(n);

        Transform[] stable = new Transform[n];
        for (int part = 0; part < cuts.size() - 1; part++) {
            int from = cuts.get(part);
            int to = cuts.get(part + 1);
            double dx = median(direct, from, to, true);
            double dy = median(direct, from, to, false);
            for (int frame = from; frame < to; frame++) {
                stable[frame] = Transform.translation(dx, dy);
            }
        }
        return stable;
    }

    private static double median(Transform[] values, int from, int to, boolean x) {
        double[] sample = new double[to - from];
        for (int i = from; i < to; i++) sample[i - from] = x ? values[i].dx : values[i].dy;
        Arrays.sort(sample);
        int middle = sample.length / 2;
        return sample.length % 2 == 0
                ? 0.5 * (sample[middle - 1] + sample[middle]) : sample[middle];
    }

    private static Result register(float[][] frames, int width, int height,
                                   boolean wholePixels, boolean verifyPeaks) {
        validate(frames, width, height);
        double[] correctionX = new double[frames.length];
        double[] correctionY = new double[frames.length];
        int pairCount = 0;
        for (int gap : lags(frames.length)) {
            pairCount += update(frames, width, height, gap, wholePixels, verifyPeaks,
                    correctionX, correctionY);
        }
        Transform[] cumulative = new Transform[frames.length];
        for (int frame = 0; frame < frames.length; frame++) {
            // The schedule accumulates image corrections. RIPR stores content displacement and
            // applies its inverse during warping, so invert the correction here.
            cumulative[frame] = Transform.translation(-correctionX[frame], -correctionY[frame]);
        }
        return new Result(cumulative, pairCount);
    }

    /** Progressive gaps, with the last frame always tied directly to the first. */
    public static int[] lags(int frameCount) {
        if (frameCount < 1) throw new IllegalArgumentException("frameCount must be positive");
        List<Integer> values = new ArrayList<>();
        for (int gap = 1; gap < frameCount; gap *= 3) {
            values.add(gap);
            if (gap > Integer.MAX_VALUE / 3) break;
        }
        int last = frameCount - 1;
        if (last > 0 && (values.isEmpty() || values.get(values.size() - 1) != last)) {
            values.add(last);
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static int update(float[][] frames, int width, int height, int gap,
                              boolean wholePixels, boolean verifyPeaks,
                              double[] correctionX, double[] correctionY) {
        int pairs = 0;
        int n = frames.length;
        for (int target = gap; target < n + gap; target += gap) {
            int at = Math.min(target, n - 1);
            double[] content = verifyPeaks
                    ? PhaseCorrelation.shiftVerified(
                            frames[at - gap], frames[at], width, height, 5)
                    : PhaseCorrelation.shift(
                            frames[at - gap], frames[at], width, height, true);
            double dx = wholePixels ? Math.round(content[0]) : content[0];
            double dy = wholePixels ? Math.round(content[1]) : content[1];
            if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
                throw new IllegalStateException("phase correlation returned a non-finite shift");
            }
            double addX = -dx - (correctionX[at] - correctionX[at - gap]);
            double addY = -dy - (correctionY[at] - correctionY[at - gap]);
            for (int frame = at - gap; frame < n; frame++) {
                double fraction = (frame - (at - gap)) / (double) gap;
                correctionX[frame] += fraction * addX;
                correctionY[frame] += fraction * addY;
            }
            pairs++;
            if (at == n - 1) break;
        }
        return pairs;
    }

    private static void validate(float[][] frames, int width, int height) {
        if (frames == null || frames.length == 0) {
            throw new IllegalArgumentException("at least one frame is required");
        }
        if (width < 1 || height < 1 || width > Integer.MAX_VALUE / height) {
            throw new IllegalArgumentException("invalid dimensions");
        }
        int expected = width * height;
        for (int frame = 0; frame < frames.length; frame++) {
            if (frames[frame] == null || frames[frame].length != expected) {
                throw new IllegalArgumentException("frame " + frame + " has the wrong size");
            }
        }
    }
}
