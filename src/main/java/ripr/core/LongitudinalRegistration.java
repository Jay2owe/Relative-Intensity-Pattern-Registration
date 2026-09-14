/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whole-recording, one-channel refinement for longitudinal microscopy.
 *
 * <p>Emission images are fitted independently to bright and dim references from the selected
 * channel.  Transmitted-light images are fitted to a median of tissue edges and locally dark
 * landmarks.  A trajectory guard removes short returning excursions caused by light pulses while
 * retaining persistent stage jumps.  A rigid pair-and-block agreement test handles rare brightfield
 * remounts.  This is intentionally separate from the general Automatic fixed recipe.
 */
public final class LongitudinalRegistration {
    private static final double CANONICAL_SIDE = 512.0;

    private LongitudinalRegistration() { }

    /** Fixed-route decisions retained for run logs and regression tests. */
    public static final class Diagnostics {
        public final String route;
        public final int brightReferenceFrame;
        public final int dimReferenceFrame;
        public final int[] weakFrames;
        public final int[] persistentJumpFrames;
        public final int[] rigidJumpFrames;
        public final int endpointJumpFrame;

        Diagnostics(String route, int brightReferenceFrame, int dimReferenceFrame,
                    int[] weakFrames, int[] persistentJumpFrames, int[] rigidJumpFrames,
                    int endpointJumpFrame) {
            this.route = route;
            this.brightReferenceFrame = brightReferenceFrame;
            this.dimReferenceFrame = dimReferenceFrame;
            this.weakFrames = weakFrames.clone();
            this.persistentJumpFrames = persistentJumpFrames.clone();
            this.rigidJumpFrames = rigidJumpFrames.clone();
            this.endpointJumpFrame = endpointJumpFrame;
        }
    }

    /** Refined registration plus the fixed-route decisions behind it. */
    public static final class Outcome {
        public final Registration.Result registration;
        public final Diagnostics diagnostics;

        Outcome(Registration.Result registration, Diagnostics diagnostics) {
            this.registration = registration;
            this.diagnostics = diagnostics;
        }
    }

    static final class Fit {
        final Transform transform;
        final double score;
        Fit(Transform transform, double score) {
            this.transform = transform;
            this.score = score;
        }
    }

    private static final class TrajectoryFit {
        final double[][] trajectory;
        final double[] confidence;
        final int bright;
        final int dim;
        TrajectoryFit(double[][] trajectory, double[] confidence, int bright, int dim) {
            this.trajectory = trajectory;
            this.confidence = confidence;
            this.bright = bright;
            this.dim = dim;
        }
    }

    private static final class Repair {
        final double[][] trajectory;
        final int[] jumps;
        Repair(double[][] trajectory, int[] jumps) {
            this.trajectory = trajectory;
            this.jumps = jumps;
        }
    }

    private static final class Event {
        final int boundary;
        final Transform transform;
        Event(int boundary, Transform transform) {
            this.boundary = boundary;
            this.transform = transform;
        }
    }

    private static final class Endpoint {
        final Transform[] transforms;
        final int boundary;
        Endpoint(Transform[] transforms, int boundary) {
            this.transforms = transforms;
            this.boundary = boundary;
        }
    }

    /** Apply the validated longitudinal refinement to an existing Automatic fixed-recipe result. */
    public static Outcome refine(FrameSource source, Registration.Result baseline,
                                 boolean transmittedLight, double maximumRotationDegrees,
                                 int requestedThreads, PairScheduler.Progress progress,
                                 PairScheduler.Cancellation cancellation) {
        if (source == null || baseline == null) {
            throw new IllegalArgumentException("source and preliminary registration are required");
        }
        if (source.count() < 2 || source.width() < 2 || source.height() < 2) {
            throw new IllegalArgumentException("longitudinal input needs at least two 2-D frames");
        }
        if (baseline.cumulative.length != source.count()) {
            throw new IllegalArgumentException("source and preliminary trajectory differ in length");
        }
        PairScheduler.Progress sink = progress == null ? PairScheduler.Progress.NONE : progress;
        PairScheduler.Cancellation cancel = cancellation == null
                ? PairScheduler.Cancellation.NEVER : cancellation;
        int workers = requestedThreads > 0 ? requestedThreads
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        workers = Math.max(1, Math.min(workers, source.count()));

        check(cancel);
        sink.phase("Longitudinal maximum accuracy: building one-channel features", 0,
                source.count());
        float[][] features = features(source, transmittedLight, workers, sink, cancel);
        check(cancel);

        TrajectoryFit fitted = transmittedLight
                ? landmarkTrajectory(source, features, baseline.cumulative, workers, sink, cancel)
                : dualReferenceTrajectory(source, features, baseline.cumulative, workers,
                        sink, cancel);
        double diagonal = Math.hypot(source.width(), source.height());
        Repair repaired = repairTrajectory(fitted.trajectory, fitted.confidence, diagonal);
        double radius = 0.5 * diagonal;
        Transform[] transforms = new Transform[source.count()];
        for (int i = 0; i < transforms.length; i++) {
            transforms[i] = new Transform(repaired.trajectory[i][0], repaired.trajectory[i][1],
                    repaired.trajectory[i][2] / radius);
        }

        List<Event> rigid = Collections.emptyList();
        List<Event> terminalRigid = Collections.emptyList();
        String route;
        if (transmittedLight) {
            rigid = detectRigidBridges(source, features, baseline.cumulative,
                    maximumRotationDegrees, sink, cancel);
            transforms = applyRigidBridges(transforms, rigid);
            route = rigid.isEmpty() ? "edge_dark_landmarks"
                    : "edge_dark_landmarks_guarded_rigid_jump";
        } else {
            route = "bright_dim_references";
        }
        Endpoint endpoint = transmittedLight ? new Endpoint(transforms, -1)
                : repairEndpoint(source, transforms, workers, sink, cancel);
        transforms = endpoint.transforms;
        if (!transmittedLight) {
            terminalRigid = terminalRigidChain(source, baseline.cumulative,
                    maximumRotationDegrees, sink, cancel);
            transforms = applyRigidBridges(transforms, terminalRigid);
            if (!terminalRigid.isEmpty()) {
                route = "bright_dim_references_guarded_terminal_rigid_chain";
            }
        }

        Registration.Warning warning = new Registration.Warning(
                Registration.Warning.Kind.LONGITUDINAL_ACCURACY_SCOPE,
                "Longitudinal maximum accuracy used the complete selected channel; it is not "
                + "validated for repeated oscillation or continuous rotation.");
        Registration.Result result = baseline.withCumulative(transforms, warning);
        List<Event> allRigid = new ArrayList<>(rigid);
        allRigid.addAll(terminalRigid);
        int endpointBoundary = !terminalRigid.isEmpty()
                ? terminalRigid.get(0).boundary : endpoint.boundary;
        Diagnostics diagnostics = new Diagnostics(route,
                fitted.bright < 0 ? 0 : fitted.bright + 1,
                fitted.dim < 0 ? 0 : fitted.dim + 1,
                oneBased(indicesAtMost(fitted.confidence, 0.0)),
                oneBased(repaired.jumps),
                oneBased(allRigid.stream().mapToInt(value -> value.boundary).toArray()),
                endpointBoundary < 0 ? 0 : endpointBoundary + 1);
        sink.phase("Longitudinal maximum accuracy: complete", 1, 1);
        return new Outcome(result, diagnostics);
    }

    private static float[][] features(FrameSource source, boolean transmitted, int workers,
                                      PairScheduler.Progress progress,
                                      PairScheduler.Cancellation cancellation) {
        int count = source.count();
        float[][] output = new float[count][];
        AtomicInteger done = new AtomicInteger();
        parallel(count, workers, cancellation, frame -> {
            output[frame] = transmitted
                    ? landmarkFeature(source.plane(frame), source.width(), source.height())
                    : emissionFeature(source.plane(frame), source.width(), source.height());
            progress.phase("Longitudinal maximum accuracy: building one-channel features",
                    done.incrementAndGet(), count);
            return null;
        });
        return output;
    }

    private static TrajectoryFit dualReferenceTrajectory(
            FrameSource source, float[][] features, Transform[] baseline, int workers,
            PairScheduler.Progress progress, PairScheduler.Cancellation cancellation) {
        int[] references = chooseReferenceFrames(source, workers, cancellation);
        int bright = references[0];
        int dim = references[1];
        int width = source.width();
        int height = source.height();
        Transform inheritedBridge = baseline[bright].inverse().then(baseline[dim]);
        Fit bridge = bright == dim ? new Fit(Transform.IDENTITY, 1.0)
                : fit(features[bright], features[dim], width, height,
                        inheritedBridge, cancellation, true);
        Fit[] brightFits = new Fit[source.count()];
        Fit[] dimFits = new Fit[source.count()];
        AtomicInteger done = new AtomicInteger();
        progress.phase("Longitudinal maximum accuracy: fitting bright and dim references", 0,
                2 * source.count());
        parallel(source.count(), workers, cancellation, frame -> {
            brightFits[frame] = frame == bright ? new Fit(Transform.IDENTITY, 1.0)
                    : fit(features[bright], features[frame], width, height,
                            baseline[bright].inverse().then(baseline[frame]), cancellation, true);
            progress.phase("Longitudinal maximum accuracy: fitting bright and dim references",
                    done.incrementAndGet(), 2 * source.count());
            dimFits[frame] = frame == dim ? new Fit(Transform.IDENTITY, 1.0)
                    : fit(features[dim], features[frame], width, height,
                            baseline[dim].inverse().then(baseline[frame]), cancellation, true);
            progress.phase("Longitudinal maximum accuracy: fitting bright and dim references",
                    done.incrementAndGet(), 2 * source.count());
            return null;
        });

        Transform[] selected = new Transform[source.count()];
        double[] scores = new double[source.count()];
        double[] brightScores = new double[source.count()];
        double[] dimScores = new double[source.count()];
        double[] agreement = new double[source.count()];
        double radius = 0.5 * Math.hypot(width, height);
        for (int frame = 0; frame < source.count(); frame++) {
            Transform fromBright = brightFits[frame].transform;
            Transform fromDim = bridge.transform.then(dimFits[frame].transform);
            double brightScore = brightFits[frame].score;
            double dimScore = Math.min(dimFits[frame].score, bridge.score);
            selected[frame] = brightScore >= dimScore ? fromBright : fromDim;
            scores[frame] = Math.max(brightScore, dimScore);
            brightScores[frame] = brightScore;
            dimScores[frame] = dimScore;
            agreement[frame] = distance(fromBright, fromDim, radius);
        }
        selected = normalise(selected);
        double[][] trajectory = trajectory(selected, radius);
        double median = percentile(scores, 50.0);
        double mad = 1.4826 * percentile(absoluteDifference(scores, median), 50.0);
        double floor = Math.max(0.18, Math.min(0.50, median - 2.5 * mad));
        double[] confidence = new double[scores.length];
        double gapLimit = Math.max(1.5, 0.003 * Math.hypot(width, height));
        for (int frame = 0; frame < scores.length; frame++) {
            confidence[frame] = clamp((scores[frame] - floor) / Math.max(median - floor, 0.05),
                    0.0, 1.0);
            if (agreement[frame] > gapLimit) confidence[frame] = 0.0;
        }
        protectTerminalEvents(trajectory, confidence, agreement, brightScores, dimScores,
                trajectory(normalise(baseline), radius), Math.hypot(width, height));
        return new TrajectoryFit(trajectory, confidence, bright, dim);
    }

    private static TrajectoryFit landmarkTrajectory(
            FrameSource source, float[][] features, Transform[] baseline, int workers,
            PairScheduler.Progress progress, PairScheduler.Cancellation cancellation) {
        int width = source.width();
        int height = source.height();
        float[] reference = landmarkReference(features, baseline, width, height, cancellation);
        Fit[] fits = new Fit[source.count()];
        AtomicInteger done = new AtomicInteger();
        progress.phase("Longitudinal maximum accuracy: fitting edge and dark landmarks", 0,
                source.count());
        parallel(source.count(), workers, cancellation, frame -> {
            fits[frame] = fit(reference, features[frame], width, height,
                    baseline[frame], cancellation, false);
            progress.phase("Longitudinal maximum accuracy: fitting edge and dark landmarks",
                    done.incrementAndGet(), source.count());
            return null;
        });
        Transform[] transforms = new Transform[fits.length];
        double[] scores = new double[fits.length];
        for (int i = 0; i < fits.length; i++) {
            transforms[i] = fits[i].transform;
            scores[i] = fits[i].score;
        }
        transforms = normalise(transforms);
        double median = percentile(scores, 50.0);
        double mad = 1.4826 * percentile(absoluteDifference(scores, median), 50.0);
        double floor = Math.max(0.15, Math.min(0.45, median - 3.0 * mad));
        double[] confidence = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            confidence[i] = clamp((scores[i] - floor) / Math.max(median - floor, 0.05),
                    0.0, 1.0);
        }
        double radius = 0.5 * Math.hypot(width, height);
        return new TrajectoryFit(trajectory(transforms, radius), confidence, -1, -1);
    }

    private static Fit fit(float[] reference, float[] moving, int width, int height,
                           Transform inherited, PairScheduler.Cancellation cancellation,
                           boolean fitSmallRotation) {
        Transform phase = phaseSeed(reference, moving, width, height);
        Transform[] candidates = {inherited, phase, Transform.IDENTITY};
        Transform seed = candidates[0];
        double score = correlation(reference, moving, width, height, seed);
        for (int i = 1; i < candidates.length; i++) {
            if (sameTransform(seed, candidates[i])) continue;
            double candidateScore = correlation(reference, moving, width, height, candidates[i]);
            if (candidateScore > score) {
                seed = candidates[i];
                score = candidateScore;
            }
        }
        double[] steps = {1.0, 0.25, 0.0625};
        return refineCorrelation(reference, moving, width, height, seed, score, steps,
                fitSmallRotation, cancellation);
    }

    private static Fit refineCorrelation(float[] reference, float[] moving, int width, int height,
                                         Transform seed, double seedScore, double[] steps,
                                         boolean fitRotation,
                                         PairScheduler.Cancellation cancellation) {
        double radius = Math.max(1.0, 0.5 * Math.hypot(width, height));
        Transform accepted = seed;
        double score = seedScore;
        if (!Double.isFinite(score)) throw new IllegalStateException("insufficient correlation support");
        // One immutable image pair, bounded by this search's existing iteration caps.
        // Transform equality uses exact doubles (including signed zero), never a rounded key.
        // Preserve candidate evaluation order and strict tie-breaking; reuse only identical work.
        Map<Transform, Double> scores = new HashMap<>(128);
        scores.put(seed, seedScore);
        for (double step : steps) {
            for (int iteration = 0; iteration < 4; iteration++) {
                check(cancellation);
                Transform winner = accepted;
                double winnerScore = score;
                int angleStart = fitRotation ? -1 : 0;
                int angleEnd = fitRotation ? 1 : 0;
                for (int kt = angleStart; kt <= angleEnd; kt++) {
                    for (int ky = -1; ky <= 1; ky++) {
                        for (int kx = -1; kx <= 1; kx++) {
                            if (kx == 0 && ky == 0 && kt == 0) continue;
                            Transform candidate = new Transform(
                                    accepted.dx + kx * step,
                                    accepted.dy + ky * step,
                                    accepted.theta + kt * step / radius);
                            Double cachedScore = scores.get(candidate);
                            double candidateScore;
                            if (cachedScore != null) {
                                candidateScore = cachedScore;
                            } else {
                                candidateScore = correlation(
                                        reference, moving, width, height, candidate);
                                scores.put(candidate, candidateScore);
                            }
                            if (candidateScore > winnerScore) {
                                winner = candidate;
                                winnerScore = candidateScore;
                            }
                        }
                    }
                }
                if (winner == accepted) break;
                accepted = winner;
                score = winnerScore;
            }
        }
        return new Fit(accepted, score);
    }

    private static boolean sameTransform(Transform left, Transform right) {
        return Double.doubleToLongBits(left.dx) == Double.doubleToLongBits(right.dx)
                && Double.doubleToLongBits(left.dy) == Double.doubleToLongBits(right.dy)
                && Double.doubleToLongBits(left.theta) == Double.doubleToLongBits(right.theta);
    }

    static Transform phaseSeed(float[] reference, float[] moving, int width, int height) {
        try {
            double[] shift = PhaseCorrelation.shiftVerified(reference, moving, width, height, 8);
            return Transform.translation(shift[0], shift[1]);
        } catch (RuntimeException ignored) {
            return Transform.IDENTITY;
        }
    }

    static double correlation(float[] reference, float[] moving, int width, int height,
                              Transform transform) {
        double cosine = Math.cos(transform.theta);
        double sine = Math.sin(transform.theta);
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        int stride = Math.max(1, (int) Math.floor(Math.sqrt(
                (double) width * height / 400_000.0)));
        long count = 0;
        double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;
        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                float a = reference[y * width + x];
                if (!Float.isFinite(a)) continue;
                double u = x - cx;
                double v = y - cy;
                double sx = cosine * u - sine * v + cx + transform.dx;
                double sy = sine * u + cosine * v + cy + transform.dy;
                float b = bilinear(moving, width, height, sx, sy);
                if (!Float.isFinite(b)) continue;
                count++;
                sumA += a;
                sumB += b;
                sumAA += a * (double) a;
                sumBB += b * (double) b;
                sumAB += a * (double) b;
            }
        }
        if (count < 64) return Double.NEGATIVE_INFINITY;
        double covariance = sumAB - sumA * sumB / count;
        double varianceA = sumAA - sumA * sumA / count;
        double varianceB = sumBB - sumB * sumB / count;
        double denominator = Math.sqrt(Math.max(0.0, varianceA * varianceB));
        return denominator > 0 ? covariance / denominator : Double.NEGATIVE_INFINITY;
    }

    private static float bilinear(float[] source, int width, int height, double x, double y) {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return Float.NaN;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        float a = source[y0 * width + x0];
        float b = source[y0 * width + x1];
        float c = source[y1 * width + x0];
        float d = source[y1 * width + x1];
        if (!Float.isFinite(a) || !Float.isFinite(b)
                || !Float.isFinite(c) || !Float.isFinite(d)) return Float.NaN;
        double fx = x - x0;
        double fy = y - y0;
        return (float) ((1 - fy) * ((1 - fx) * a + fx * b)
                + fy * ((1 - fx) * c + fx * d));
    }

    static float[] emissionFeature(float[] frame, int width, int height) {
        float[] image = percentileNormalise(frame, 1.0, 99.0);
        double sigma = Math.max(2.0, Math.min(width, height) / 32.0);
        float[] broad = gaussianApproximation(image, width, height, sigma);
        float[] feature = new float[image.length];
        for (int i = 0; i < feature.length; i++) feature[i] = image[i] - broad[i];
        feature = robustScale(feature);
        for (int i = 0; i < feature.length; i++) feature[i] += 6.0f;
        return feature;
    }

    static float[] landmarkFeature(float[] frame, int width, int height) {
        float[] image = percentileNormalise(frame, 1.0, 99.0);
        double size = Math.min(width, height);
        float[] fine = gaussianApproximation(image, width, height, Math.max(0.8, size / 512.0));
        float[] middle = gaussianApproximation(image, width, height, Math.max(3.0, size / 64.0));
        float[] broad = gaussianApproximation(image, width, height, Math.max(8.0, size / 20.0));
        float[] darkFine = new float[image.length];
        float[] darkBroad = new float[image.length];
        for (int i = 0; i < image.length; i++) {
            darkFine[i] = Math.max(middle[i] - fine[i], 0.0f);
            darkBroad[i] = Math.max(broad[i] - middle[i], 0.0f);
        }
        darkFine = robustScale(darkFine);
        darkBroad = robustScale(darkBroad);
        float[] edgeSource = gaussianApproximation(
                image, width, height, Math.max(1.5, size / 256.0));
        float[] edges = new float[image.length];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                double gx = (edgeSource[i - width + 1] + 2 * edgeSource[i + 1]
                        + edgeSource[i + width + 1])
                        - (edgeSource[i - width - 1] + 2 * edgeSource[i - 1]
                        + edgeSource[i + width - 1]);
                double gy = (edgeSource[i + width - 1] + 2 * edgeSource[i + width]
                        + edgeSource[i + width + 1])
                        - (edgeSource[i - width - 1] + 2 * edgeSource[i - width]
                        + edgeSource[i - width + 1]);
                edges[i] = (float) Math.hypot(gx, gy);
            }
        }
        edges = robustScale(edges);
        float[] feature = new float[image.length];
        for (int i = 0; i < feature.length; i++) {
            feature[i] = 0.65f * edges[i] + darkFine[i] + 0.55f * darkBroad[i];
        }
        int marginY = Math.max(2, (int) Math.round(0.04 * height));
        int marginX = Math.max(2, (int) Math.round(0.04 * width));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (y < marginY || y >= height - marginY || x < marginX || x >= width - marginX) {
                    feature[y * width + x] = 0.0f;
                }
            }
        }
        feature = robustScale(feature);
        for (int i = 0; i < feature.length; i++) feature[i] += 6.0f;
        return feature;
    }

    private static float[] percentileNormalise(float[] source, double low, double high) {
        float[] sorted = sortedPercentileSample(source);
        double lo = percentileSorted(sorted, low);
        double hi = percentileSorted(sorted, high);
        double scale = 1.0 / Math.max(hi - lo, 1e-6);
        float[] output = new float[source.length];
        for (int i = 0; i < output.length; i++) {
            output[i] = (float) clamp((source[i] - lo) * scale, 0.0, 1.0);
        }
        return output;
    }

    private static float[] robustScale(float[] source) {
        double centre = percentile(source, 50.0);
        float[] deviations = new float[source.length];
        for (int i = 0; i < source.length; i++) deviations[i] = (float) Math.abs(source[i] - centre);
        double scale = Math.max(1e-5, 1.4826 * percentile(deviations, 50.0));
        float[] output = new float[source.length];
        for (int i = 0; i < output.length; i++) {
            output[i] = (float) clamp((source[i] - centre) / scale, -5.0, 5.0);
        }
        return output;
    }

    private static float[] gaussianApproximation(float[] source, int width, int height,
                                                 double sigma) {
        int radius = Math.max(1, (int) Math.round(sigma));
        float[] value = source.clone();
        for (int pass = 0; pass < 3; pass++) value = boxBlur(value, width, height, radius);
        return value;
    }

    private static float[] boxBlur(float[] source, int width, int height, int radius) {
        float[] horizontal = new float[source.length];
        int diameter = 2 * radius + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            double sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += source[row + clampIndex(x, width)];
            }
            for (int x = 0; x < width; x++) {
                horizontal[row + x] = (float) (sum / diameter);
                sum += source[row + clampIndex(x + radius + 1, width)]
                        - source[row + clampIndex(x - radius, width)];
            }
        }
        float[] output = new float[source.length];
        for (int x = 0; x < width; x++) {
            double sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += horizontal[clampIndex(y, height) * width + x];
            }
            for (int y = 0; y < height; y++) {
                output[y * width + x] = (float) (sum / diameter);
                sum += horizontal[clampIndex(y + radius + 1, height) * width + x]
                        - horizontal[clampIndex(y - radius, height) * width + x];
            }
        }
        return output;
    }

    private static int[] chooseReferenceFrames(FrameSource source, int workers,
                                               PairScheduler.Cancellation cancellation) {
        int count = source.count();
        double[] light = new double[count];
        double[] structure = new double[count];
        int x0 = (int) Math.round(0.12 * source.width());
        int x1 = (int) Math.round(0.88 * source.width());
        int y0 = (int) Math.round(0.12 * source.height());
        int y1 = (int) Math.round(0.88 * source.height());
        parallel(count, workers, cancellation, frame -> {
            float[] plane = source.plane(frame);
            float[] centre = new float[(x1 - x0) * (y1 - y0)];
            int at = 0;
            for (int y = y0; y < y1; y++) {
                System.arraycopy(plane, y * source.width() + x0, centre, at, x1 - x0);
                at += x1 - x0;
            }
            float[] sorted = sortedPercentileSample(centre);
            light[frame] = percentileSorted(sorted, 75.0);
            structure[frame] = percentileSorted(sorted, 95.0) - percentileSorted(sorted, 5.0);
            return null;
        });
        double medianStructure = percentile(structure, 50.0);
        boolean[] usable = new boolean[count];
        int usableCount = 0;
        for (int i = 0; i < count; i++) {
            usable[i] = structure[i] >= Math.max(1e-6, 0.35 * medianStructure);
            if (usable[i]) usableCount++;
        }
        if (usableCount < 4) Arrays.fill(usable, true);
        int bright = -1, dim = -1;
        for (int i = 0; i < count; i++) {
            if (!usable[i]) continue;
            if (bright < 0 || light[i] > light[bright]) bright = i;
            if (dim < 0 || light[i] < light[dim]) dim = i;
        }
        return new int[]{bright, dim};
    }

    private static float[] landmarkReference(float[][] features, Transform[] baseline,
                                             int width, int height,
                                             PairScheduler.Cancellation cancellation) {
        float[][] aligned = new float[features.length][];
        for (int frame = 0; frame < features.length; frame++) {
            check(cancellation);
            aligned[frame] = warp(features[frame], width, height, baseline[frame], Float.NaN);
        }
        float[] reference = new float[width * height];
        int[] support = new int[reference.length];
        float[] values = new float[features.length];
        for (int pixel = 0; pixel < reference.length; pixel++) {
            int count = 0;
            for (float[] frame : aligned) {
                float value = frame[pixel];
                if (Float.isFinite(value)) values[count++] = value;
            }
            support[pixel] = count;
            reference[pixel] = count == 0 ? Float.NaN : (float) percentile(values, count, 50.0);
        }
        float[] salience = new float[reference.length];
        int supportedCount = 0;
        for (int i = 0; i < reference.length; i++) {
            if (support[i] >= Math.ceil(0.80 * features.length)) {
                salience[supportedCount++] = Math.abs(reference[i] - 6.0f);
            }
        }
        if (supportedCount == 0) throw new IllegalStateException("no common landmark support");
        double threshold = percentile(salience, supportedCount, 55.0);
        boolean[] mask = new boolean[reference.length];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = support[i] >= Math.ceil(0.80 * features.length)
                    && Math.abs(reference[i] - 6.0f) >= threshold;
        }
        int size = Math.max(1, (int) Math.round(7.0 * Math.min(width, height) / CANONICAL_SIDE));
        mask = dilate(mask, width, height, Math.max(0, size / 2));
        int count = 0;
        for (int i = 0; i < mask.length; i++) {
            mask[i] &= support[i] >= Math.ceil(0.80 * features.length);
            if (mask[i]) count++;
            else reference[i] = Float.NaN;
        }
        if (count < Math.max(16, Math.round(0.0009765625f * mask.length))) {
            throw new IllegalStateException("insufficient edge and dark-landmark support");
        }
        return reference;
    }

    private static void protectTerminalEvents(double[][] trajectory, double[] confidence,
                                              double[] agreement, double[] brightScores,
                                              double[] dimScores, double[][] baseline,
                                              double diagonal) {
        double gapLimit = Math.max(1.5, 0.003 * diagonal);
        double threshold = Math.max(2.5, 0.006 * diagonal);
        int tail = Math.max(2, Math.min(8, (int) Math.ceil(0.04 * trajectory.length)));
        for (int frame = Math.max(1, trajectory.length - tail); frame < trajectory.length; frame++) {
            if (confidence[frame] > 0 || agreement[frame] > gapLimit) continue;
            double movement = distance(trajectory[frame], trajectory[frame - 1]);
            if (movement < threshold) continue;
            boolean twoReferences = brightScores[frame] >= 0.18 && dimScores[frame] >= 0.18;
            double baselineMovement = distance(baseline[frame], baseline[frame - 1]);
            boolean baselineMatch = baselineMovement >= threshold
                    && distance(trajectory[frame], baseline[frame])
                    <= Math.max(gapLimit, 0.005 * diagonal);
            if (!twoReferences && !baselineMatch) continue;
            if (baselineMatch && !twoReferences) {
                trajectory[frame] = baseline[frame].clone();
                if (confidence[frame - 1] <= 0) {
                    trajectory[frame - 1] = baseline[frame - 1].clone();
                }
            }
            confidence[frame] = 1e-6;
            confidence[frame - 1] = Math.max(confidence[frame - 1], 1e-6);
        }
    }

    static Repair repairTrajectory(double[][] input, double[] confidence, double diagonal) {
        double[][] values = copy(input);
        boolean[] reliable = new boolean[confidence.length];
        for (int i = 0; i < reliable.length; i++) reliable[i] = confidence[i] > 0;
        int[] jumps = persistentJumps(values, reliable, diagonal);
        double[][] repaired = copy(values);
        int start = 0;
        for (int part = 0; part <= jumps.length; part++) {
            int end = part < jumps.length ? jumps[part] : values.length;
            double[][] segment = Arrays.copyOfRange(values, start, end);
            boolean[] segmentReliable = Arrays.copyOfRange(reliable, start, end);
            segment = removeReturningExcursions(fillWeak(segment, segmentReliable), diagonal);
            for (int i = 0; i < segment.length; i++) repaired[start + i] = segment[i];
            start = end;
        }
        double[] zero = repaired[0].clone();
        for (double[] row : repaired) for (int axis = 0; axis < 3; axis++) row[axis] -= zero[axis];
        return new Repair(repaired, jumps);
    }

    private static int[] persistentJumps(double[][] trajectory, boolean[] reliable,
                                         double diagonal) {
        double threshold = Math.max(2.5, 0.006 * diagonal);
        double gentle = Math.max(1.5, 0.004 * diagonal);
        List<double[]> candidates = new ArrayList<>();
        int count = trajectory.length;
        for (int boundary = 1; boundary < count; boundary++) {
            double[][] left = Arrays.copyOfRange(trajectory, Math.max(0, boundary - 6), boundary);
            double[][] right = Arrays.copyOfRange(trajectory, boundary, Math.min(count, boundary + 6));
            double[] leftCentre = vectorMedian(left);
            double[] rightCentre = vectorMedian(right);
            double displacement = distance(rightCentre, leftCentre);
            double instant = distance(trajectory[boundary], trajectory[boundary - 1]);
            if (displacement < threshold || instant < 0.55 * threshold) continue;
            if (boundary != count - 1) {
                double[][] near = Arrays.copyOfRange(trajectory, boundary, Math.min(count, boundary + 3));
                double[][] far = Arrays.copyOfRange(trajectory, Math.min(count, boundary + 3),
                        Math.min(count, boundary + 8));
                if (far.length >= 2 && distance(vectorMedian(near), vectorMedian(far))
                        > Math.max(gentle, 0.30 * displacement)) continue;
            }
            double leftScatter = medianDistance(left, leftCentre);
            double rightScatter = medianDistance(right, rightCentre);
            if (leftScatter > Math.max(gentle, 0.35 * displacement)) continue;
            if (right.length > 1 && rightScatter > Math.max(gentle, 0.35 * displacement)) continue;
            if (!any(reliable, Math.max(0, boundary - 3), boundary)
                    || !any(reliable, boundary, Math.min(count, boundary + 3))) continue;
            candidates.add(new double[]{displacement + instant, boundary});
        }
        candidates.sort((left, right) -> Double.compare(right[0], left[0]));
        List<Integer> selected = new ArrayList<>();
        for (double[] candidate : candidates) {
            int boundary = (int) candidate[1];
            boolean distant = true;
            for (int other : selected) if (Math.abs(boundary - other) <= 2) distant = false;
            if (distant) selected.add(boundary);
        }
        Collections.sort(selected);
        return selected.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double[][] fillWeak(double[][] values, boolean[] reliable) {
        double[][] result = copy(values);
        List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < reliable.length; i++) if (reliable[i]) anchors.add(i);
        if (anchors.isEmpty()) {
            double[] median = vectorMedian(values);
            for (int i = 0; i < result.length; i++) result[i] = median.clone();
            return result;
        }
        for (int frame = 0; frame < result.length; frame++) {
            if (reliable[frame]) continue;
            Integer before = null, after = null;
            for (int anchor : anchors) {
                if (anchor < frame) before = anchor;
                else if (anchor > frame) { after = anchor; break; }
            }
            if (before != null && after != null) {
                double fraction = (frame - before) / (double) (after - before);
                for (int axis = 0; axis < 3; axis++) {
                    result[frame][axis] = (1 - fraction) * result[before][axis]
                            + fraction * result[after][axis];
                }
            } else if (before != null) result[frame] = result[before].clone();
            else result[frame] = result[after].clone();
        }
        return result;
    }

    private static double[][] removeReturningExcursions(double[][] values, double diagonal) {
        double[][] result = copy(values);
        double threshold = Math.max(2.5, 0.006 * diagonal);
        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            for (int span = 2; span < Math.min(13, result.length); span++) {
                for (int start = 0; start + span < result.length; start++) {
                    int end = start + span;
                    double maximumDeviation = 0;
                    double path = 0;
                    for (int i = start; i <= end; i++) {
                        double fraction = (i - start) / (double) span;
                        double[] line = interpolate(result[start], result[end], fraction);
                        maximumDeviation = Math.max(maximumDeviation, distance(result[i], line));
                        if (i > start) path += distance(result[i], result[i - 1]);
                    }
                    double endpoint = distance(result[end], result[start]);
                    if (maximumDeviation > threshold && path - endpoint > 2 * threshold) {
                        for (int i = start + 1; i < end; i++) {
                            result[i] = interpolate(result[start], result[end],
                                    (i - start) / (double) span);
                        }
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        return result;
    }

    private static List<Event> detectRigidBridges(
            FrameSource source, float[][] features, Transform[] baseline,
            double maximumRotationDegrees, PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
        List<Integer> boundaries = lowConfidenceBoundaries(features, baseline,
                source.width(), source.height());
        List<Event> events = new ArrayList<>();
        double diagonal = Math.hypot(source.width(), source.height());
        double radius = 0.5 * diagonal;
        int done = 0;
        for (int boundary : boundaries) {
            check(cancellation);
            progress.phase("Longitudinal maximum accuracy: checking rare rigid jumps",
                    done++, boundaries.size());
            int width = Math.min(8, Math.min(boundary, source.count() - boundary));
            if (width < 3) continue;
            try {
                Fit pair = rigidBridge(source.plane(boundary - 1), source.plane(boundary),
                        source.width(), source.height(), maximumRotationDegrees, cancellation);
                float[] before = medianBlock(source, boundary - width, boundary, cancellation);
                float[] after = medianBlock(source, boundary, boundary + width, cancellation);
                Fit block = rigidBridge(before, after, source.width(), source.height(),
                        maximumRotationDegrees, cancellation);
                double disagreement = Math.hypot(pair.transform.dx - block.transform.dx,
                        pair.transform.dy - block.transform.dy);
                double angularDisagreement = Math.abs(pair.transform.theta - block.transform.theta);
                double movement = Math.max(block.transform.magnitude(),
                        radius * Math.abs(block.transform.theta));
                if (disagreement > 0.01 * diagonal) continue;
                if (angularDisagreement > Math.toRadians(1.0)) continue;
                if (movement < 0.005 * diagonal) continue;
                events.add(new Event(boundary, block.transform));
            } catch (RuntimeException ignored) {
                // A candidate is optional; failed evidence means the preliminary translation stays.
            }
        }
        return events;
    }

    static List<Integer> lowConfidenceBoundaries(float[][] features, Transform[] baseline,
                                                 int width, int height) {
        float[][] thumbnails = new float[features.length][];
        int[] dimensions = thumbnailDimensions(width, height, 128);
        for (int i = 0; i < features.length; i++) {
            thumbnails[i] = resizeBilinear(features[i], width, height, dimensions[0], dimensions[1]);
        }
        double[] scores = new double[Math.max(0, features.length - 1)];
        for (int i = 1; i < features.length; i++) {
            Transform seed = phaseSeed(thumbnails[i - 1], thumbnails[i], dimensions[0], dimensions[1]);
            scores[i - 1] = correlation(thumbnails[i - 1], thumbnails[i], dimensions[0],
                    dimensions[1], seed);
        }
        if (scores.length == 0) return Collections.emptyList();
        double median = percentile(scores, 50.0);
        double cutoff = Math.min(0.65, 0.70 * median);
        double diagonal = Math.hypot(width, height);
        List<Integer> result = new ArrayList<>();
        for (int boundary = 1; boundary < features.length; boundary++) {
            Transform predicted = baseline[boundary - 1].inverse().then(baseline[boundary]);
            if (scores[boundary - 1] < cutoff || predicted.magnitude() > 0.01 * diagonal) {
                result.add(boundary);
            }
        }
        return result;
    }

    static Fit rigidBridge(float[] before, float[] after, int width, int height,
                           double maximumRotationDegrees,
                           PairScheduler.Cancellation cancellation) {
        float[] left = landmarkFeature(before, width, height);
        float[] right = landmarkFeature(after, width, height);
        Transform seed = coarseRigidSeed(left, right, width, height,
                maximumRotationDegrees, cancellation);
        return refineCorrelation(left, right, width, height, seed,
                correlation(left, right, width, height, seed),
                new double[]{2.0, 1.0, 0.5, 0.25, 0.125}, true, cancellation);
    }

    private static Transform coarseRigidSeed(float[] reference, float[] moving, int width, int height,
                                             double maximumRotationDegrees,
                                             PairScheduler.Cancellation cancellation) {
        int[] dimensions = thumbnailDimensions(width, height, 192);
        float[] left = resizeBilinear(reference, width, height, dimensions[0], dimensions[1]);
        float[] right = resizeBilinear(moving, width, height, dimensions[0], dimensions[1]);
        double scaleX = dimensions[0] / (double) width;
        double scaleY = dimensions[1] / (double) height;
        double maximum = Math.max(15.0, maximumRotationDegrees);
        double radius = Math.max(1.0, 0.5 * Math.hypot(dimensions[0], dimensions[1]));
        double step = Math.max(0.25, Math.toDegrees(0.75 / radius));
        int count = Math.max(1, (int) Math.ceil(maximum / step));
        Transform winner = Transform.IDENTITY;
        double winnerScore = Double.NEGATIVE_INFINITY;
        // The thumbnail is immutable throughout the rotation search.
        float rotationBackground = (float) percentile(right, 50.0);
        for (int index = -count; index <= count; index++) {
            check(cancellation);
            double theta = Math.toRadians(index * maximum / count);
            Transform rotation = new Transform(0, 0, theta);
            // Phase correlation cannot consume the NaN corners left by rotation.  The
            // accepted Python route fills those corners with the median landmark value
            // before measuring the translation, so do the same here.  Previously every
            // rotated trial failed silently and the search collapsed to the identity.
            float[] rotated = warp(right, dimensions[0], dimensions[1], rotation,
                    rotationBackground);
            Transform shift = phaseSeed(left, rotated, dimensions[0], dimensions[1]);
            double cosine = Math.cos(theta);
            double sine = Math.sin(theta);
            double dx = cosine * shift.dx / scaleX - sine * shift.dy / scaleY;
            double dy = sine * shift.dx / scaleX + cosine * shift.dy / scaleY;
            Transform candidate = new Transform(dx, dy, theta);
            double score = correlation(reference, moving, width, height, candidate);
            if (score > winnerScore) {
                winner = candidate;
                winnerScore = score;
            }
        }
        return winner;
    }

    private static Transform[] applyRigidBridges(Transform[] input, List<Event> events) {
        Transform[] result = input.clone();
        for (Event event : events) {
            Transform predicted = result[event.boundary - 1].inverse().then(result[event.boundary]);
            Transform correction = predicted.inverse().then(event.transform);
            for (int frame = event.boundary; frame < result.length; frame++) {
                result[frame] = result[frame].then(correction);
            }
        }
        return result;
    }

    /**
     * Recover adjacent endpoint remounts that a before/after block would mix together.
     *
     * <p>The fixed Automatic trajectory must independently contain the same large,
     * persistent endpoint movement.  Direct same-channel rigid fits must also agree in
     * direction and approximate displacement.  This rejects a returning light-pulse
     * excursion while allowing two genuine stage moves on consecutive final frames.
     */
    private static List<Event> terminalRigidChain(
            FrameSource source, Transform[] baseline, double maximumRotationDegrees,
            PairScheduler.Progress progress, PairScheduler.Cancellation cancellation) {
        int count = source.count();
        if (count < 6 || baseline.length != count) return Collections.emptyList();
        double diagonal = Math.hypot(source.width(), source.height());
        double radius = 0.5 * diagonal;
        Transform[] increments = new Transform[count];
        double[] movementValues = new double[count - 1];
        double[] movement = new double[count];
        for (int boundary = 1; boundary < count; boundary++) {
            increments[boundary] = baseline[boundary - 1].inverse().then(baseline[boundary]);
            movement[boundary] = Math.max(increments[boundary].magnitude(),
                    radius * Math.abs(increments[boundary].theta));
            movementValues[boundary - 1] = movement[boundary];
        }
        double cutoff = percentile(movementValues, 80.0);
        double[] ordinaryValues = Arrays.stream(movementValues)
                .filter(value -> value <= cutoff).toArray();
        double ordinary = ordinaryValues.length == 0 ? 0.0
                : percentile(ordinaryValues, 50.0);
        double threshold = Math.max(0.04 * diagonal, 8.0 * Math.max(ordinary, 1.0));
        int tail = Math.max(4, Math.min(8, (int) Math.ceil(0.04 * count)));
        List<Integer> large = new ArrayList<>();
        for (int boundary = Math.max(1, count - tail); boundary < count - 1; boundary++) {
            if (movement[boundary] >= threshold) large.add(boundary);
        }
        if (large.isEmpty()) return Collections.emptyList();

        int groupStart = large.size() - 1;
        while (groupStart > 0
                && large.get(groupStart) - large.get(groupStart - 1) == 1) {
            groupStart--;
        }
        List<Integer> group = large.subList(groupStart, large.size());
        int first = group.get(0);
        int last = group.get(group.size() - 1);
        if (group.size() > 3 || count - last < 2) return Collections.emptyList();
        Transform persistent = baseline[first - 1].inverse().then(baseline[count - 1]);
        if (Math.max(persistent.magnitude(), radius * Math.abs(persistent.theta)) < threshold) {
            return Collections.emptyList();
        }

        List<Event> events = new ArrayList<>();
        int total = count - first;
        progress.phase("Longitudinal maximum accuracy: checking consecutive final jumps", 0,
                total);
        for (int boundary = first; boundary < count; boundary++) {
            check(cancellation);
            Fit pair;
            try {
                pair = rigidBridge(source.plane(boundary - 1), source.plane(boundary),
                        source.width(), source.height(), maximumRotationDegrees, cancellation);
            } catch (RuntimeException failure) {
                return Collections.emptyList();
            }
            progress.phase("Longitudinal maximum accuracy: checking consecutive final jumps",
                    boundary - first + 1, total);
            if (pair.score < 0.15) return Collections.emptyList();
            double pairMovement = Math.max(pair.transform.magnitude(),
                    radius * Math.abs(pair.transform.theta));
            if (group.contains(boundary)) {
                Transform expected = increments[boundary];
                double disagreement = Math.hypot(pair.transform.dx - expected.dx,
                        pair.transform.dy - expected.dy);
                double lengths = Math.max(pair.transform.magnitude() * expected.magnitude(), 1e-9);
                double direction = (pair.transform.dx * expected.dx
                        + pair.transform.dy * expected.dy) / lengths;
                if (pairMovement < 0.70 * threshold) return Collections.emptyList();
                if (disagreement > 0.04 * diagonal || direction < 0.75) {
                    return Collections.emptyList();
                }
            } else if (pairMovement >= threshold) {
                return Collections.emptyList();
            }
            events.add(new Event(boundary, pair.transform));
        }
        return events;
    }

    private static Endpoint repairEndpoint(FrameSource source, Transform[] input, int workers,
                                           PairScheduler.Progress progress,
                                           PairScheduler.Cancellation cancellation) {
        if (source.count() < 6) return new Endpoint(input, -1);
        double[][] centroids = new double[source.count()][2];
        double[] areas = new double[source.count()];
        AtomicInteger done = new AtomicInteger();
        try {
            parallel(source.count(), workers, cancellation, frame -> {
                double[] measurement = brightTissueCentroid(
                        source.plane(frame), source.width(), source.height());
                centroids[frame][0] = measurement[0];
                centroids[frame][1] = measurement[1];
                areas[frame] = measurement[2];
                progress.phase("Longitudinal maximum accuracy: checking final stage jump",
                        done.incrementAndGet(), source.count());
                return null;
            });
        } catch (RuntimeException failure) {
            return new Endpoint(input, -1);
        }
        double diagonal = Math.hypot(source.width(), source.height());
        double[] rawSteps = new double[source.count() - 1];
        for (int i = 1; i < source.count(); i++) {
            rawSteps[i - 1] = distance(centroids[i], centroids[i - 1]);
        }
        double cutoff = percentile(rawSteps, 80.0);
        double[] ordinaryValues = Arrays.stream(rawSteps).filter(value -> value <= cutoff).toArray();
        double ordinary = percentile(ordinaryValues, 50.0);
        double cx = (source.width() - 1) / 2.0;
        double cy = (source.height() - 1) / 2.0;
        double[][] aligned = new double[source.count()][2];
        double[] point = new double[2];
        for (int frame = 0; frame < source.count(); frame++) {
            input[frame].inverse().apply(centroids[frame][0], centroids[frame][1], cx, cy, point);
            aligned[frame] = point.clone();
        }
        int tail = Math.max(2, Math.min(8, (int) Math.ceil(0.04 * source.count())));
        double bestResidual = Double.NEGATIVE_INFINITY;
        int bestBoundary = -1;
        Transform bestEvent = null;
        for (int boundary = Math.max(2, source.count() - tail);
             boundary < source.count() - 1; boundary++) {
            int width = Math.min(4, Math.min(boundary, source.count() - boundary));
            if (width < 2) continue;
            double[] before = vectorMedian(Arrays.copyOfRange(centroids, boundary - width, boundary));
            double[] after = vectorMedian(Arrays.copyOfRange(centroids, boundary, boundary + width));
            double dx = after[0] - before[0];
            double dy = after[1] - before[1];
            double movement = Math.hypot(dx, dy);
            double residual = distance(
                    vectorMedian(Arrays.copyOfRange(aligned, boundary, boundary + width)),
                    vectorMedian(Arrays.copyOfRange(aligned, boundary - width, boundary)));
            double spread = Math.max(maximumDistance(
                    Arrays.copyOfRange(centroids, boundary - width, boundary), before),
                    maximumDistance(Arrays.copyOfRange(centroids, boundary, boundary + width), after));
            double areaRatio = percentile(Arrays.copyOfRange(areas, boundary, boundary + width), 50.0)
                    / Math.max(percentile(Arrays.copyOfRange(areas, boundary - width, boundary), 50.0),
                    1e-9);
            if (movement < Math.max(0.08 * diagonal, 8.0 * Math.max(ordinary, 1.0))) continue;
            if (residual < 0.02 * diagonal || spread > 0.02 * diagonal) continue;
            if (areaRatio < 0.40 || areaRatio > 2.50) continue;
            if (residual > bestResidual) {
                bestResidual = residual;
                bestBoundary = boundary;
                bestEvent = Transform.translation(dx, dy);
            }
        }
        if (bestBoundary < 0) return new Endpoint(input, -1);
        Transform[] result = input.clone();
        Transform predicted = result[bestBoundary - 1].inverse().then(result[bestBoundary]);
        Transform correction = predicted.inverse().then(bestEvent);
        for (int frame = bestBoundary; frame < result.length; frame++) {
            result[frame] = result[frame].then(correction);
        }
        return new Endpoint(result, bestBoundary);
    }

    private static double[] brightTissueCentroid(float[] frame, int width, int height) {
        double scale = Math.min(1.0, CANONICAL_SIDE / Math.min(width, height));
        int targetWidth = Math.max(2, (int) Math.round(width * scale));
        int targetHeight = Math.max(2, (int) Math.round(height * scale));
        float[] small = scale < 1.0
                ? resizeBilinear(frame, width, height, targetWidth, targetHeight) : frame;
        float[] normalised = percentileNormalise(small, 20.0, 99.8);
        boolean[] mask = new boolean[normalised.length];
        for (int i = 0; i < mask.length; i++) mask[i] = normalised[i] > 0.22f;
        int kernel = Math.max(3, (int) Math.round(9.0 * scale));
        int radius = Math.max(1, kernel / 2);
        mask = dilate(erode(mask, targetWidth, targetHeight, radius),
                targetWidth, targetHeight, radius);
        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];
        int bestCount = 0;
        double bestX = 0, bestY = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int count = 0;
            double sumX = 0, sumY = 0;
            while (head < tail) {
                int pixel = queue[head++];
                int y = pixel / targetWidth;
                int x = pixel % targetWidth;
                count++;
                sumX += x;
                sumY += y;
                if (x > 0) tail = add(queue, tail, seen, mask, pixel - 1);
                if (x + 1 < targetWidth) tail = add(queue, tail, seen, mask, pixel + 1);
                if (y > 0) tail = add(queue, tail, seen, mask, pixel - targetWidth);
                if (y + 1 < targetHeight) tail = add(queue, tail, seen, mask, pixel + targetWidth);
            }
            if (count > bestCount) {
                bestCount = count;
                bestX = sumX / count;
                bestY = sumY / count;
            }
        }
        double area = bestCount / (double) mask.length;
        if (bestCount == 0 || area < 0.003) {
            throw new IllegalStateException("no usable bright tissue component");
        }
        return new double[]{bestX / scale, bestY / scale, area};
    }

    private static int add(int[] queue, int tail, boolean[] seen, boolean[] mask, int pixel) {
        if (mask[pixel] && !seen[pixel]) {
            seen[pixel] = true;
            queue[tail++] = pixel;
        }
        return tail;
    }

    private static float[] medianBlock(FrameSource source, int start, int end,
                                       PairScheduler.Cancellation cancellation) {
        float[][] frames = new float[end - start][];
        for (int i = 0; i < frames.length; i++) {
            check(cancellation);
            frames[i] = source.plane(start + i);
        }
        float[] output = new float[source.width() * source.height()];
        float[] values = new float[frames.length];
        for (int pixel = 0; pixel < output.length; pixel++) {
            for (int frame = 0; frame < frames.length; frame++) values[frame] = frames[frame][pixel];
            output[pixel] = (float) percentile(values, values.length, 50.0);
        }
        return output;
    }

    private static Transform[] normalise(Transform[] transforms) {
        Transform inverse = transforms[0].inverse();
        Transform[] output = new Transform[transforms.length];
        for (int i = 0; i < output.length; i++) output[i] = inverse.then(transforms[i]);
        return output;
    }

    private static double[][] trajectory(Transform[] transforms, double radius) {
        double[][] output = new double[transforms.length][3];
        for (int i = 0; i < output.length; i++) {
            output[i][0] = transforms[i].dx;
            output[i][1] = transforms[i].dy;
            output[i][2] = radius * transforms[i].theta;
        }
        return output;
    }

    private static float[] warp(float[] source, int width, int height, Transform transform,
                                float fill) {
        float[] output = new float[source.length];
        Warper.warp(source, output, width, height, transform,
                Warper.Interpolation.BILINEAR, fill);
        return output;
    }

    private static int[] thumbnailDimensions(int width, int height, int maximum) {
        double scale = Math.min(1.0, maximum / (double) Math.max(width, height));
        return new int[]{Math.max(2, (int) Math.round(width * scale)),
                Math.max(2, (int) Math.round(height * scale))};
    }

    private static float[] resizeBilinear(float[] source, int sourceWidth, int sourceHeight,
                                          int width, int height) {
        if (sourceWidth == width && sourceHeight == height) return source.clone();
        float[] output = new float[width * height];
        double scaleX = sourceWidth / (double) width;
        double scaleY = sourceHeight / (double) height;
        for (int y = 0; y < height; y++) {
            double sourceY = (y + 0.5) * scaleY - 0.5;
            sourceY = clamp(sourceY, 0, sourceHeight - 1);
            for (int x = 0; x < width; x++) {
                double sourceX = (x + 0.5) * scaleX - 0.5;
                sourceX = clamp(sourceX, 0, sourceWidth - 1);
                output[y * width + x] = bilinear(
                        source, sourceWidth, sourceHeight, sourceX, sourceY);
            }
        }
        return output;
    }

    private static boolean[] erode(boolean[] source, int width, int height, int radius) {
        boolean[] output = new boolean[source.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            boolean keep = true;
            for (int oy = -radius; oy <= radius && keep; oy++) {
                int yy = y + oy;
                if (yy < 0 || yy >= height) { keep = false; break; }
                for (int ox = -radius; ox <= radius; ox++) {
                    int xx = x + ox;
                    if (xx < 0 || xx >= width || !source[yy * width + xx]) {
                        keep = false;
                        break;
                    }
                }
            }
            output[y * width + x] = keep;
        }
        return output;
    }

    private static boolean[] dilate(boolean[] source, int width, int height, int radius) {
        if (radius <= 0) return source.clone();
        boolean[] output = new boolean[source.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            boolean keep = false;
            for (int oy = -radius; oy <= radius && !keep; oy++) {
                int yy = y + oy;
                if (yy < 0 || yy >= height) continue;
                for (int ox = -radius; ox <= radius; ox++) {
                    int xx = x + ox;
                    if (xx >= 0 && xx < width && source[yy * width + xx]) {
                        keep = true;
                        break;
                    }
                }
            }
            output[y * width + x] = keep;
        }
        return output;
    }

    private interface IndexedTask<T> { T call(int index) throws Exception; }

    private static <T> List<T> parallel(int count, int workers,
                                        PairScheduler.Cancellation cancellation,
                                        IndexedTask<T> task) {
        if (workers <= 1) {
            List<T> output = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                check(cancellation);
                try { output.add(task.call(i)); }
                catch (RuntimeException error) { throw error; }
                catch (Exception error) { throw new RuntimeException(error); }
            }
            return output;
        }
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<T>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit((Callable<T>) () -> task.call(index)));
            }
            List<T> output = new ArrayList<>(count);
            for (Future<T> future : futures) {
                check(cancellation);
                try { output.add(future.get()); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("longitudinal registration interrupted");
                } catch (java.util.concurrent.ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    throw new RuntimeException(cause);
                }
            }
            return output;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void check(PairScheduler.Cancellation cancellation) {
        if (cancellation != null && cancellation.cancelled()) {
            throw new CancellationException("longitudinal registration cancelled");
        }
    }

    private static double percentile(float[] values, double percentile) {
        return percentileSorted(sortedPercentileSample(values), percentile);
    }

    /** Keep the accepted sampling, finite-value filtering and sort order exactly. */
    private static float[] sortedPercentileSample(float[] values) {
        int stride = Math.max(1, values.length / 250_000);
        float[] finite = new float[(values.length + stride - 1) / stride];
        int count = 0;
        for (int i = 0; i < values.length; i += stride) {
            if (Float.isFinite(values[i])) finite[count++] = values[i];
        }
        float[] sorted = Arrays.copyOf(finite, count);
        Arrays.sort(sorted);
        return sorted;
    }

    private static double percentileSorted(float[] sorted, double percentile) {
        if (sorted.length == 0) return 0.0;
        if (sorted.length == 1) return sorted[0];
        double position = clamp(percentile, 0, 100) * (sorted.length - 1) / 100.0;
        int lower = (int) Math.floor(position);
        int upper = Math.min(sorted.length - 1, lower + 1);
        double fraction = position - lower;
        return sorted[lower] * (1 - fraction) + sorted[upper] * fraction;
    }

    private static double percentile(float[] values, int length, double percentile) {
        float[] copied = Arrays.copyOf(values, length);
        Arrays.sort(copied);
        if (copied.length == 1) return copied[0];
        double position = clamp(percentile, 0, 100) * (copied.length - 1) / 100.0;
        int lower = (int) Math.floor(position);
        int upper = Math.min(copied.length - 1, lower + 1);
        double fraction = position - lower;
        return copied[lower] * (1 - fraction) + copied[upper] * fraction;
    }

    private static double percentile(double[] values, double percentile) {
        if (values.length == 0) return 0.0;
        double[] copied = values.clone();
        Arrays.sort(copied);
        double position = clamp(percentile, 0, 100) * (copied.length - 1) / 100.0;
        int lower = (int) Math.floor(position);
        int upper = Math.min(copied.length - 1, lower + 1);
        double fraction = position - lower;
        return copied[lower] * (1 - fraction) + copied[upper] * fraction;
    }

    private static double[] absoluteDifference(double[] values, double centre) {
        double[] output = new double[values.length];
        for (int i = 0; i < output.length; i++) output[i] = Math.abs(values[i] - centre);
        return output;
    }

    private static double[] vectorMedian(double[][] values) {
        if (values.length == 0) return new double[]{0, 0, 0};
        int axes = values[0].length;
        double[] output = new double[axes];
        for (int axis = 0; axis < axes; axis++) {
            double[] selected = new double[values.length];
            for (int i = 0; i < values.length; i++) selected[i] = values[i][axis];
            output[axis] = percentile(selected, 50.0);
        }
        return output;
    }

    private static double medianDistance(double[][] values, double[] centre) {
        double[] distances = new double[values.length];
        for (int i = 0; i < values.length; i++) distances[i] = distance(values[i], centre);
        return percentile(distances, 50.0);
    }

    private static double maximumDistance(double[][] values, double[] centre) {
        double maximum = 0;
        for (double[] value : values) maximum = Math.max(maximum, distance(value, centre));
        return maximum;
    }

    private static double distance(double[] left, double[] right) {
        double sum = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            double difference = left[i] - right[i];
            sum += difference * difference;
        }
        return Math.sqrt(sum);
    }

    private static double distance(Transform left, Transform right, double radius) {
        return Math.sqrt(Math.pow(left.dx - right.dx, 2) + Math.pow(left.dy - right.dy, 2)
                + Math.pow(radius * (left.theta - right.theta), 2));
    }

    private static double[][] copy(double[][] values) {
        double[][] output = new double[values.length][];
        for (int i = 0; i < output.length; i++) output[i] = values[i].clone();
        return output;
    }

    private static double[] interpolate(double[] left, double[] right, double fraction) {
        double[] output = new double[Math.min(left.length, right.length)];
        for (int i = 0; i < output.length; i++) {
            output[i] = (1 - fraction) * left[i] + fraction * right[i];
        }
        return output;
    }

    private static boolean any(boolean[] values, int start, int end) {
        for (int i = start; i < end; i++) if (values[i]) return true;
        return false;
    }

    private static int[] indicesAtMost(double[] values, double maximum) {
        int count = 0;
        for (double value : values) if (value <= maximum) count++;
        int[] output = new int[count];
        int at = 0;
        for (int i = 0; i < values.length; i++) if (values[i] <= maximum) output[at++] = i;
        return output;
    }

    private static int[] oneBased(int[] values) {
        int[] output = values.clone();
        for (int i = 0; i < output.length; i++) output[i]++;
        return output;
    }

    private static int clampIndex(int value, int length) {
        return Math.max(0, Math.min(length - 1, value));
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
