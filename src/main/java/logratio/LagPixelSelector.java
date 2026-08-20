/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.core.LogPlane;
import logratio.core.Reconciler;
import logratio.core.Transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds pixel scores from spatial information and residual evidence shared across a multi-lag graph.
 *
 * <p>A low raw frame-to-frame difference is not evidence that a pixel is a good anchor: blank
 * background and detector-fixed defects can both look motionless. This class first places every frame
 * in one provisional reference coordinate system, removes each edge's median log-gain, normalises by
 * that edge's own robust noise scale, and only then asks which textured locations remain inconsistent.
 * The production engine uses the cheap spatial-only path for the validated sparse-signal rule; the
 * temporal scores remain available for manual sweeps and benchmark comparisons.
 */
final class LagPixelSelector {

    private static final double SCALE_FLOOR = 1e-4;
    private static final double RESIDUAL_CLIP = 8.0;
    private static final int SMOOTH_RADIUS = 3;

    enum Score {
        /** Equal-weight mean of standardised residuals across lag groups. */
        INSTABILITY,
        /** Linear growth of the standardised residual with log2(lag). */
        LAG_GROWTH,
        /** Local two-axis spatial information measured on the scoring copy. */
        INFORMATION,
        /** Rank of two-axis spatial information minus rank of temporal instability. */
        ANCHOR_TRUST
    }

    static final class Scores {
        final int width;
        final int height;
        final double[] instability;
        final double[] lagGrowth;
        final double[] anchorTrust;
        final double[] information;
        final int[] orientationBin;
        final boolean[] eligible;
        final int eligibleCount;

        Scores(int width, int height, double[] instability, double[] lagGrowth,
               double[] anchorTrust, double[] information, int[] orientationBin,
               boolean[] eligible, int eligibleCount) {
            this.width = width;
            this.height = height;
            this.instability = instability;
            this.lagGrowth = lagGrowth;
            this.anchorTrust = anchorTrust;
            this.information = information;
            this.orientationBin = orientationBin;
            this.eligible = eligible;
            this.eligibleCount = eligibleCount;
        }

        double[] values(Score score) {
            switch (score) {
                case INSTABILITY:
                    return instability;
                case LAG_GROWTH:
                    return lagGrowth;
                case INFORMATION:
                    return information;
                case ANCHOR_TRUST:
                    return anchorTrust;
                default:
                    throw new IllegalArgumentException("unknown score " + score);
            }
        }
    }

    private LagPixelSelector() {
    }

    /**
     * Score reference-coordinate pixels from all requested graph edges.
     *
     * @param frames     full-resolution log planes
     * @param cumulative provisional motion from frame 0 to each frame
     * @param lags       lag groups; every group contributes equal weight regardless of edge count
     */
    static Scores score(LogPlane[] frames, Transform[] cumulative, int[] lags) {
        return score(frames, cumulative, lags, 0);
    }

    static Scores score(LogPlane[] frames, Transform[] cumulative, int[] lags,
                        int referenceFrame) {
        if (frames == null || cumulative == null || frames.length != cumulative.length
                || frames.length == 0) {
            throw new IllegalArgumentException("frames and cumulative transforms must be non-empty "
                    + "and have equal length");
        }
        if (lags == null || lags.length == 0) {
            throw new IllegalArgumentException("at least one lag is required");
        }
        if (referenceFrame < 0 || referenceFrame >= frames.length) {
            throw new IllegalArgumentException("reference frame is outside the recording");
        }
        int width = frames[0].width;
        int height = frames[0].height;
        int pixels = width * height;
        for (int t = 0; t < frames.length; t++) {
            if (frames[t].width != width || frames[t].height != height || cumulative[t] == null) {
                throw new IllegalArgumentException("frame " + t + " differs in shape or has no transform");
            }
        }

        double[][] sum = new double[lags.length][pixels];
        short[][] count = new short[lags.length][pixels];
        double[] differences = new double[pixels];
        int[] referenceIndex = new int[pixels];
        double[] pointA = new double[2];
        double[] pointB = new double[2];
        double centreX = (width - 1) / 2.0;
        double centreY = (height - 1) / 2.0;

        List<Reconciler.Observation> edges = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, frames.length, 0, 1, lags);
        for (Reconciler.Observation edge : edges) {
            int lagIndex = lagIndex(lags, edge.lag());
            if (lagIndex < 0) continue;
            LogPlane a = frames[edge.from];
            LogPlane b = frames[edge.to];
            Transform referenceToA = cumulative[edge.from];
            Transform referenceToB = cumulative[edge.to];
            int n = 0;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    referenceToA.apply(x, y, centreX, centreY, pointA);
                    referenceToB.apply(x, y, centreX, centreY, pointB);
                    float av = a.sample(pointA[0], pointA[1]);
                    if (Float.isNaN(av)) continue;
                    float bv = b.sample(pointB[0], pointB[1]);
                    if (Float.isNaN(bv)) continue;
                    differences[n] = bv - av;
                    referenceIndex[n] = row + x;
                    n++;
                }
            }
            if (n == 0) continue;

            double gain = median(differences, n);
            double scale = robustScale(differences, n, gain);
            for (int i = 0; i < n; i++) {
                int p = referenceIndex[i];
                double z = Math.min(RESIDUAL_CLIP, Math.abs(differences[i] - gain) / scale);
                sum[lagIndex][p] += z;
                if (count[lagIndex][p] < Short.MAX_VALUE) count[lagIndex][p]++;
            }
        }

        double[] instability = new double[pixels];
        double[] growth = new double[pixels];
        Arrays.fill(instability, Double.NaN);
        Arrays.fill(growth, Double.NaN);
        double invLn2 = 1.0 / Math.log(2.0);
        for (int p = 0; p < pixels; p++) {
            int groups = 0;
            double mean = 0;
            double sx = 0;
            double sy = 0;
            double sxx = 0;
            double sxy = 0;
            for (int l = 0; l < lags.length; l++) {
                if (count[l][p] == 0) continue;
                double y = sum[l][p] / count[l][p];
                double x = Math.log(lags[l]) * invLn2;
                groups++;
                mean += y;
                sx += x;
                sy += y;
                sxx += x * x;
                sxy += x * y;
            }
            if (groups == 0) continue;
            instability[p] = mean / groups;
            double denominator = groups * sxx - sx * sx;
            if (groups >= 2 && denominator > 0) {
                growth[p] = (groups * sxy - sx * sy) / denominator;
            }
        }

        instability = boxMean(instability, width, height, SMOOTH_RADIUS);
        growth = boxMean(growth, width, height, SMOOTH_RADIUS);
        double[] information = twoAxisInformation(frames[referenceFrame], 2);
        boolean[] eligible = gradientCandidates(frames[referenceFrame]);
        int eligibleCount = 0;
        for (int p = 0; p < pixels; p++) {
            eligible[p] &= Double.isFinite(instability[p]);
            if (eligible[p]) eligibleCount++;
        }

        double[] instabilityRank = ranks(instability, eligible);
        double[] informationRank = ranks(information, eligible);
        double[] trust = new double[pixels];
        Arrays.fill(trust, Double.NaN);
        for (int p = 0; p < pixels; p++) {
            if (eligible[p]) trust[p] = informationRank[p] - instabilityRank[p];
        }
        int[] orientationBin = orientationBins(frames[referenceFrame], 4);
        return new Scores(width, height, instability, growth, trust, information, orientationBin,
                eligible, eligibleCount);
    }

    /**
     * Score only the reference frame's two-axis spatial information.
     *
     * <p>This is the cheap equivalent for an information-only mask: it does not calculate temporal
     * residuals that the mask never reads. The caller still uses a provisional trajectory to map the
     * resulting reference mask into every source frame.
     */
    static Scores spatialInformationScore(LogPlane reference) {
        if (reference == null) throw new IllegalArgumentException("reference frame is null");
        int pixels = reference.width * reference.height;
        double[] missing = new double[pixels];
        Arrays.fill(missing, Double.NaN);
        double[] information = twoAxisInformation(reference, 2);
        boolean[] eligible = gradientCandidates(reference);
        int eligibleCount = 0;
        for (boolean value : eligible) if (value) eligibleCount++;
        return new Scores(reference.width, reference.height, missing.clone(), missing.clone(),
                missing.clone(), information, orientationBins(reference, 4), eligible,
                eligibleCount);
    }

    /**
     * Re-rank anchor trust using the better of two geometrical explanations only inside a permitted
     * reference-coordinate region. Outside that region the pilot score remains authoritative.
     */
    static Scores dualCoordinateTrust(Scores pilot, Scores alternate, boolean[] alternateRegion) {
        if (pilot.width != alternate.width || pilot.height != alternate.height
                || alternateRegion.length != pilot.width * pilot.height) {
            throw new IllegalArgumentException("dual-coordinate score shapes differ");
        }
        double[] instability = pilot.instability.clone();
        for (int p = 0; p < instability.length; p++) {
            if (alternateRegion[p] && Double.isFinite(alternate.instability[p])) {
                instability[p] = Double.isFinite(instability[p])
                        ? Math.min(instability[p], alternate.instability[p])
                        : alternate.instability[p];
            }
        }
        double[] instabilityRank = ranks(instability, pilot.eligible);
        double[] informationRank = ranks(pilot.information, pilot.eligible);
        double[] trust = new double[instability.length];
        Arrays.fill(trust, Double.NaN);
        for (int p = 0; p < trust.length; p++) {
            if (pilot.eligible[p]) trust[p] = informationRank[p] - instabilityRank[p];
        }
        return new Scores(pilot.width, pilot.height, instability, pilot.lagGrowth, trust,
                pilot.information, pilot.orientationBin, pilot.eligible, pilot.eligibleCount);
    }

    /** Build a deterministic reference-coordinate support mask for one percentage and score tail. */
    static boolean[] mask(Scores scores, Score score, double removePercent, boolean removeHighest) {
        if (!(removePercent >= 0 && removePercent <= 100)) {
            throw new IllegalArgumentException("remove percent must be in [0,100]");
        }
        boolean[] keep = new boolean[scores.width * scores.height];
        Arrays.fill(keep, true);
        if (removePercent == 0 || scores.eligibleCount == 0) return keep;

        double[] values = scores.values(score);
        List<Integer> order = new ArrayList<>(scores.eligibleCount);
        for (int p = 0; p < keep.length; p++) {
            if (scores.eligible[p] && Double.isFinite(values[p])) order.add(p);
        }
        order.sort(Comparator.comparingDouble((Integer p) -> values[p]).thenComparingInt(p -> p));
        int remove = Math.min(order.size(), (int) Math.round(removePercent * order.size() / 100.0));
        for (int k = 0; k < remove; k++) {
            int rank = removeHighest ? order.size() - 1 - k : k;
            keep[order.get(rank)] = false;
        }
        return keep;
    }

    /**
     * Remove the same score tail independently within spatial cells and gradient-direction groups.
     * This stops one textured object or one edge direction from consuming the recording-wide quota.
     */
    static boolean[] stratifiedMask(Scores scores, Score score, double removePercent,
                                    boolean removeHighest, int cellSize, int orientationBins) {
        if (!(removePercent >= 0 && removePercent <= 100)) {
            throw new IllegalArgumentException("remove percent must be in [0,100]");
        }
        if (cellSize < 1 || orientationBins < 1) {
            throw new IllegalArgumentException("stratification sizes must be positive");
        }
        boolean[] keep = new boolean[scores.width * scores.height];
        Arrays.fill(keep, true);
        if (removePercent == 0 || scores.eligibleCount == 0) return keep;
        double[] values = scores.values(score);
        int cellsX = (scores.width + cellSize - 1) / cellSize;
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int p = 0; p < keep.length; p++) {
            if (!scores.eligible[p] || !Double.isFinite(values[p])) continue;
            int x = p % scores.width;
            int y = p / scores.width;
            int direction = Math.min(orientationBins - 1,
                    scores.orientationBin[p] * orientationBins / 4);
            int key = ((y / cellSize) * cellsX + x / cellSize) * orientationBins + direction;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(p);
        }
        for (List<Integer> group : groups.values()) {
            group.sort(Comparator.comparingDouble((Integer p) -> values[p]).thenComparingInt(p -> p));
            int remove = Math.min(group.size(),
                    (int) Math.round(removePercent * group.size() / 100.0));
            for (int k = 0; k < remove; k++) {
                int rank = removeHighest ? group.size() - 1 - k : k;
                keep[group.get(rank)] = false;
            }
        }
        return keep;
    }

    /** Number of gradient-qualified, scored pixels that a mask deliberately removes. */
    static int removedEligible(boolean[] mask, Scores scores) {
        int removed = 0;
        for (int p = 0; p < mask.length; p++) {
            if (scores.eligible[p] && !mask[p]) removed++;
        }
        return removed;
    }

    /**
     * Map one common reference-coordinate mask into a source frame at every pyramid level.
     * The cumulative transform maps reference coordinates into that source frame, so its inverse is
     * used here. A false value is solver-only support, not image invalidity.
     */
    static boolean[][] sourceSupport(boolean[] referenceMask, int width, int height,
                                     LogPlane[] sourcePyramid, Transform cumulative) {
        if (referenceMask.length != width * height) {
            throw new IllegalArgumentException("reference mask shape does not match the frame");
        }
        boolean[][] out = new boolean[sourcePyramid.length][];
        Transform sourceToReference = cumulative.inverse();
        double centreX = (width - 1) / 2.0;
        double centreY = (height - 1) / 2.0;
        double[] point = new double[2];
        for (int l = 0; l < sourcePyramid.length; l++) {
            LogPlane level = sourcePyramid[l];
            int scale = 1 << l;
            boolean[] support = new boolean[level.width * level.height];
            for (int y = 0; y < level.height; y++) {
                for (int x = 0; x < level.width; x++) {
                    sourceToReference.apply(x * scale, y * scale, centreX, centreY, point);
                    int rx = (int) Math.round(point[0]);
                    int ry = (int) Math.round(point[1]);
                    support[y * level.width + x] = rx >= 0 && ry >= 0 && rx < width && ry < height
                            && referenceMask[ry * width + rx];
                }
            }
            out[l] = support;
        }
        return out;
    }

    /** Build source-coordinate masks when only the production pyramid dimensions are needed. */
    static boolean[][] sourceSupport(boolean[] referenceMask, int width, int height,
                                     int levels, Transform cumulative) {
        if (levels < 1) throw new IllegalArgumentException("levels must be positive");
        if (referenceMask.length != width * height) {
            throw new IllegalArgumentException("reference mask shape does not match the frame");
        }
        boolean[][] out = new boolean[levels][];
        Transform sourceToReference = cumulative.inverse();
        double centreX = (width - 1) / 2.0;
        double centreY = (height - 1) / 2.0;
        double[] point = new double[2];
        int levelWidth = width;
        int levelHeight = height;
        for (int l = 0; l < levels; l++) {
            int scale = 1 << l;
            boolean[] support = new boolean[levelWidth * levelHeight];
            for (int y = 0; y < levelHeight; y++) {
                for (int x = 0; x < levelWidth; x++) {
                    sourceToReference.apply(x * scale, y * scale, centreX, centreY, point);
                    int rx = (int) Math.round(point[0]);
                    int ry = (int) Math.round(point[1]);
                    support[y * levelWidth + x] = rx >= 0 && ry >= 0
                            && rx < width && ry < height && referenceMask[ry * width + rx];
                }
            }
            out[l] = support;
            levelWidth /= 2;
            levelHeight /= 2;
        }
        return out;
    }

    private static int lagIndex(int[] lags, int lag) {
        for (int i = 0; i < lags.length; i++) if (lags[i] == lag) return i;
        return -1;
    }

    private static double median(double[] values, int n) {
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        return sorted[n / 2];
    }

    private static double robustScale(double[] values, int n, double centre) {
        double[] absolute = new double[n];
        for (int i = 0; i < n; i++) absolute[i] = Math.abs(values[i] - centre);
        Arrays.sort(absolute);
        double mad = absolute[n / 2];
        if (!(mad > 0)) mad = absolute[(int) (0.9 * (n - 1))];
        return Math.max(SCALE_FLOOR, 1.4826 * mad);
    }

    private static boolean[] gradientCandidates(LogPlane reference) {
        double[] gradient = new double[reference.validCount];
        int n = 0;
        for (int p = 0; p < reference.v.length; p++) {
            if (reference.valid[p]) gradient[n++] = reference.gradMagnitude(p);
        }
        Arrays.sort(gradient, 0, n);
        double threshold = n == 0 ? 0 : 0.5 * gradient[n / 2];
        boolean[] eligible = new boolean[reference.v.length];
        for (int p = 0; p < eligible.length; p++) {
            eligible[p] = reference.valid[p] && reference.gradMagnitude(p) >= threshold;
        }
        return eligible;
    }

    /** Smaller eigenvalue of the local 2x2 gradient matrix: information in both movement axes. */
    private static double[] twoAxisInformation(LogPlane plane, int radius) {
        int width = plane.width;
        int height = plane.height;
        double[] out = new double[width * height];
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);
                double xx = 0;
                double xy = 0;
                double yy = 0;
                int n = 0;
                for (int j = y0; j <= y1; j++) {
                    int row = j * width;
                    for (int i = x0; i <= x1; i++) {
                        int p = row + i;
                        if (!plane.valid[p]) continue;
                        double gx = plane.gx[p];
                        double gy = plane.gy[p];
                        xx += gx * gx;
                        xy += gx * gy;
                        yy += gy * gy;
                        n++;
                    }
                }
                if (n == 0) continue;
                double trace = xx + yy;
                double discriminant = Math.sqrt((xx - yy) * (xx - yy) + 4 * xy * xy);
                out[y * width + x] = Math.max(0, 0.5 * (trace - discriminant) / n);
            }
        }
        return out;
    }

    private static int[] orientationBins(LogPlane plane, int bins) {
        int[] out = new int[plane.width * plane.height];
        for (int p = 0; p < out.length; p++) {
            double angle = Math.atan2(plane.gy[p], plane.gx[p]);
            if (angle < 0) angle += Math.PI;
            if (angle >= Math.PI) angle -= Math.PI;
            out[p] = Math.min(bins - 1, (int) (bins * angle / Math.PI));
        }
        return out;
    }

    private static double[] ranks(double[] values, boolean[] eligible) {
        List<Integer> order = new ArrayList<>();
        for (int p = 0; p < values.length; p++) {
            if (eligible[p] && Double.isFinite(values[p])) order.add(p);
        }
        order.sort(Comparator.comparingDouble((Integer p) -> values[p]).thenComparingInt(p -> p));
        double[] rank = new double[values.length];
        Arrays.fill(rank, Double.NaN);
        double denominator = Math.max(1, order.size() - 1);
        for (int k = 0; k < order.size(); k++) rank[order.get(k)] = k / denominator;
        return rank;
    }

    /** Box mean with integral images; non-finite values do not contribute. */
    private static double[] boxMean(double[] source, int width, int height, int radius) {
        int stride = width + 1;
        double[] sum = new double[(width + 1) * (height + 1)];
        int[] count = new int[sum.length];
        for (int y = 0; y < height; y++) {
            double rowSum = 0;
            int rowCount = 0;
            for (int x = 0; x < width; x++) {
                double value = source[y * width + x];
                if (Double.isFinite(value)) {
                    rowSum += value;
                    rowCount++;
                }
                int p = (y + 1) * stride + x + 1;
                sum[p] = sum[p - stride] + rowSum;
                count[p] = count[p - stride] + rowCount;
            }
        }
        double[] out = new double[source.length];
        Arrays.fill(out, Double.NaN);
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height, y + radius + 1);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width, x + radius + 1);
                int a = y0 * stride + x0;
                int b = y0 * stride + x1;
                int c = y1 * stride + x0;
                int d = y1 * stride + x1;
                int n = count[d] - count[b] - count[c] + count[a];
                if (n > 0) out[y * width + x] = (sum[d] - sum[b] - sum[c] + sum[a]) / n;
            }
        }
        return out;
    }
}
