/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Estimates one robust angular jump from all short cross-boundary frame pairs per known event. */
public final class EventRotationEstimator {
    public static final int MINIMUM_USABLE_PAIRS = 3;
    private static final double HUBER_K = 1.345;
    /** Warning only; an absolute refusal threshold requires fresh validation evidence. */
    private static final double WARNING_BOUND_FRACTION = 0.25;

    private EventRotationEstimator() { }

    private static final class Candidate {
        final int from;
        final int to;
        final PairAligner.Fit fit;
        Candidate(int from, int to, PairAligner.Fit fit) {
            this.from = from;
            this.to = to;
            this.fit = fit;
        }
    }

    public static RotationEventResult estimate(
            FrameSource source, int[] zeroBasedEventFrames, int window,
            Registration.Options options, PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
        if (source == null) throw new IllegalArgumentException("event rotation source is null");
        if (window < 1) throw new IllegalArgumentException("rotation event window must be at least 1");
        int[] eventFrames = zeroBasedEventFrames == null
                ? new int[0] : zeroBasedEventFrames.clone();
        validateEvents(eventFrames, source.count());
        Registration.Options o = options == null ? new Registration.Options() : options.copy();
        PairScheduler.Progress prog = progress == null ? PairScheduler.Progress.NONE : progress;
        PairScheduler.Cancellation cancel = cancellation == null
                ? PairScheduler.Cancellation.NEVER : cancellation;
        o.aligner.cancellation = cancel;
        o.aligner.fitRotation = true;
        int levels = o.aligner.levelsFor(source.width(), source.height());
        PyramidCache cache = new PyramidCache(
                frame -> Registration.pyramidFor(source, frame, levels, o),
                PyramidCache.capacityFor(source.count(), source.width(), source.height(),
                        Math.max(1, 2 * window), Math.max(1, o.threads),
                        o.memoryBudgetBytes, Registration.bytesPerPyramidPixel(o)));
        RotationEventResult.Event[] results = new RotationEventResult.Event[eventFrames.length];
        double[] frameAngles = new double[source.count()];
        double cumulative = 0.0;
        int totalPairs = 0;
        for (int event : eventFrames) {
            totalPairs += Math.min(window, event)
                    * Math.min(window, source.count() - event);
        }
        int completed = 0;
        prog.begin(totalPairs, Math.max(1, Math.min(totalPairs, o.threads > 0 ? o.threads : 1)));
        try {
            for (int eventIndex = 0; eventIndex < eventFrames.length; eventIndex++) {
                if (cancel.cancelled()) throw new java.util.concurrent.CancellationException("cancelled");
                int event = eventFrames[eventIndex];
                int firstPre = Math.max(0, event - window);
                int lastPre = event - 1;
                int firstPost = event;
                int lastPost = Math.min(source.count() - 1, event + window - 1);
                List<int[]> plan = new ArrayList<>();
                for (int from = firstPre; from <= lastPre; from++) {
                    for (int to = firstPost; to <= lastPost; to++) plan.add(new int[]{from, to});
                }
                int workers = PairScheduler.workersFor(plan.size(), o.threads,
                        2L * Registration.bytesPerPyramidPixel(o)
                                * source.width() * source.height(), o.memoryBudgetBytes);
                final int progressOffset = completed;
                List<Candidate> candidates = PairScheduler.map(plan.size(), workers, index -> {
                    int[] pair = plan.get(index);
                    PairAligner.Fit fit = PairAligner.align(
                            cache.get(pair[0]), cache.get(pair[1]), o.aligner);
                    return new Candidate(pair[0], pair[1], fit);
                }, offsetProgress(prog, progressOffset, totalPairs), cancel);
                completed += plan.size();

                List<Candidate> usable = new ArrayList<>();
                for (Candidate candidate : candidates) if (usable(candidate.fit)) usable.add(candidate);
                double delta = Double.NaN;
                double spread = Double.NaN;
                int inliers = 0;
                RotationEventResult.Status status = RotationEventResult.Status.INSUFFICIENT_SUPPORT;
                if (usable.size() >= MINIMUM_USABLE_PAIRS) {
                    double[] angles = new double[usable.size()];
                    double[] weights = new double[usable.size()];
                    for (int i = 0; i < usable.size(); i++) {
                        Candidate candidate = usable.get(i);
                        angles[i] = wrap(candidate.fit.transform.theta);
                        int distance = event - candidate.from + candidate.to - event + 1;
                        weights[i] = Math.max(1e-6, candidate.fit.validFraction) / distance;
                    }
                    double centre = circularMedian(angles, weights);
                    spread = circularMad(angles, centre);
                    double scale = Math.max(1e-9, 1.4826 * spread);
                    for (int iteration = 0; iteration < 20; iteration++) {
                        double sine = 0.0;
                        double cosine = 0.0;
                        for (int i = 0; i < angles.length; i++) {
                            double residual = wrap(angles[i] - centre);
                            double robust = Math.abs(residual) <= HUBER_K * scale
                                    ? 1.0 : HUBER_K * scale / Math.abs(residual);
                            double weight = weights[i] * robust;
                            sine += weight * Math.sin(angles[i]);
                            cosine += weight * Math.cos(angles[i]);
                        }
                        double next = Math.atan2(sine, cosine);
                        if (Math.abs(wrap(next - centre)) < 1e-12) {
                            centre = next;
                            break;
                        }
                        centre = next;
                    }
                    delta = wrap(centre);
                    for (double angle : angles) {
                        if (Math.abs(wrap(angle - delta)) <= HUBER_K * scale + 1e-12) inliers++;
                    }
                    status = spread > WARNING_BOUND_FRACTION * o.aligner.maxRotation
                            ? RotationEventResult.Status.HIGH_DISAGREEMENT
                            : RotationEventResult.Status.OK;
                    cumulative += delta;
                }
                results[eventIndex] = new RotationEventResult.Event(event, delta, cumulative,
                        plan.size(), usable.size(), inliers, spread,
                        firstPre, lastPre, firstPost, lastPost, status);
            }
        } finally {
            cache.clear();
        }

        cumulative = 0.0;
        int eventIndex = 0;
        for (int frame = 0; frame < frameAngles.length; frame++) {
            if (eventIndex < results.length && frame == results[eventIndex].frame) {
                if (Double.isFinite(results[eventIndex].deltaTheta)) {
                    cumulative += results[eventIndex].deltaTheta;
                }
                eventIndex++;
            }
            frameAngles[frame] = cumulative;
        }
        return new RotationEventResult(results, frameAngles);
    }

    public static void validateEvents(int[] events, int frames) {
        int previous = 0;
        for (int event : events) {
            if (event < 1 || event >= frames) {
                throw new IllegalArgumentException("rotation event frame " + (event + 1)
                        + " is outside public frame range 2.." + frames);
            }
            if (event <= previous) {
                throw new IllegalArgumentException("rotation event frame " + (event + 1)
                        + " must be unique and strictly increasing");
            }
            previous = event;
        }
    }

    private static boolean usable(PairAligner.Fit fit) {
        if (fit == null || !fit.usable() || fit.transform == null) return false;
        if (!Double.isFinite(fit.transform.theta)) return false;
        return fit.status != PairAligner.Status.AT_ROTATION_BOUND
                && fit.status != PairAligner.Status.AT_SHIFT_AND_ROTATION_BOUND;
    }

    private static double circularMedian(double[] values, double[] weights) {
        double best = values[0];
        double bestCost = Double.POSITIVE_INFINITY;
        for (double candidate : values) {
            double cost = 0.0;
            for (int i = 0; i < values.length; i++) {
                cost += weights[i] * Math.abs(wrap(values[i] - candidate));
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = candidate;
            }
        }
        return best;
    }

    private static double circularMad(double[] values, double centre) {
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(wrap(values[i] - centre));
        }
        return RobustNorm.select(deviations, deviations.length, deviations.length / 2);
    }

    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    private static PairScheduler.Progress offsetProgress(
            PairScheduler.Progress sink, int offset, int total) {
        return new PairScheduler.Progress() {
            @Override public void begin(int ignoredTotal, int workers) { }
            @Override public void taskStarted(int index, int ignoredTotal) {
                sink.taskStarted(offset + index, total);
            }
            @Override public void update(int done, int ignoredTotal) {
                sink.update(offset + done, total);
            }
        };
    }
}
