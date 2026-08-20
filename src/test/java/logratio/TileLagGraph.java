/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.core.Reconciler;
import logratio.core.Transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reconciles persistent tile trajectories and extracts the spatially distributed motion layer. */
final class TileLagGraph {

    private static final double CLUSTER_SHARE = 0.35;
    private static final double MIN_COVERAGE = 0.50;

    static final class EdgeVotes {
        final int from;
        final int to;
        final Transform pilot;
        final List<ModalTileAligner.Vote> votes;
        final ModalTileAligner.Result mode;

        EdgeVotes(int from, int to, Transform pilot, List<ModalTileAligner.Vote> votes,
                  int width, int height) {
            this.from = from;
            this.to = to;
            this.pilot = pilot;
            this.votes = votes;
            this.mode = ModalTileAligner.mode(votes, pilot, width, height,
                    Math.min(ModalTileAligner.TILE_SIZE, Math.min(width, height)));
        }
    }

    static final class Result {
        final Transform[] raw;
        final Transform[] guarded;
        final double[] correctionWeight;
        final int tileTracks;
        final int inlierTracks;
        final double coverageX;
        final double coverageY;
        final double trajectoryBandwidth;
        final double medianClosure;
        final double medianDirectionErrorDegrees;
        final boolean[] referenceSupport;
        final boolean usable;

        Result(Transform[] raw, Transform[] guarded, double[] correctionWeight,
               int tileTracks, int inlierTracks, double coverageX, double coverageY,
               double trajectoryBandwidth, double medianClosure,
               double medianDirectionErrorDegrees, boolean[] referenceSupport, boolean usable) {
            this.raw = raw;
            this.guarded = guarded;
            this.correctionWeight = correctionWeight;
            this.tileTracks = tileTracks;
            this.inlierTracks = inlierTracks;
            this.coverageX = coverageX;
            this.coverageY = coverageY;
            this.trajectoryBandwidth = trajectoryBandwidth;
            this.medianClosure = medianClosure;
            this.medianDirectionErrorDegrees = medianDirectionErrorDegrees;
            this.referenceSupport = referenceSupport;
            this.usable = usable;
        }
    }

    private static final class Track {
        final double x;
        final double y;
        final List<Reconciler.Observation> observations = new ArrayList<>();
        Transform[] cumulative;
        double closure;
        double directionError;

        Track(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private TileLagGraph() {
    }

    static Result reconcile(int frames, Transform[] pilotCumulative,
                            List<EdgeVotes> edgeVotes, int width, int height) {
        if (frames < 2 || pilotCumulative.length != frames) {
            throw new IllegalArgumentException("pilot trajectory shape does not match frame count");
        }
        Map<Long, Track> byPosition = new LinkedHashMap<>();
        for (EdgeVotes edge : edgeVotes) {
            for (ModalTileAligner.Vote vote : edge.votes) {
                long key = positionKey(vote.x, vote.y);
                Track track = byPosition.get(key);
                if (track == null) {
                    track = new Track(vote.x, vote.y);
                    byPosition.put(key, track);
                }
                track.observations.add(new Reconciler.Observation(
                        edge.from, edge.to, Transform.translation(vote.dx, vote.dy)));
            }
        }

        List<Track> tracks = new ArrayList<>();
        int minimumObservations = Math.max(frames - 1, edgeVotes.size() / 2);
        for (Track track : byPosition.values()) {
            if (track.observations.size() < minimumObservations) continue;
            Reconciler.Solution solution = Reconciler.multiLag(frames, track.observations);
            track.cumulative = solution.cumulative;
            track.closure = closure(track);
            track.directionError = directionError(track, edgeVotes);
            tracks.add(track);
        }
        if (tracks.size() < 6) return fallback(pilotCumulative, tracks.size());

        int neighbours = Math.max(3, (int) Math.ceil(CLUSTER_SHARE * tracks.size()));
        double bestRadius = Double.POSITIVE_INFINITY;
        Track seed = null;
        double[] distances = new double[tracks.size()];
        for (Track candidate : tracks) {
            for (int j = 0; j < tracks.size(); j++) {
                distances[j] = trajectoryDistance(candidate, tracks.get(j));
            }
            Arrays.sort(distances);
            double radius = distances[neighbours - 1];
            Coverage coverage = coverage(tracks, candidate, radius + 1e-12, width, height);
            if (coverage.x >= MIN_COVERAGE && coverage.y >= MIN_COVERAGE
                    && coverage.quadrants >= 3 && radius < bestRadius) {
                bestRadius = radius;
                seed = candidate;
            }
        }
        if (seed == null) return fallback(pilotCumulative, tracks.size());

        double bandwidth = Math.max(0.02, 1.25 * bestRadius);
        List<Track> inliers = new ArrayList<>();
        for (Track track : tracks) {
            if (trajectoryDistance(seed, track) <= bandwidth) inliers.add(track);
        }
        Coverage coverage = coverage(inliers, null, Double.POSITIVE_INFINITY, width, height);
        if (inliers.size() < 3 || coverage.x < MIN_COVERAGE || coverage.y < MIN_COVERAGE
                || coverage.quadrants < 3) {
            return fallback(pilotCumulative, tracks.size());
        }

        Transform[] raw = new Transform[frames];
        Transform[] guarded = new Transform[frames];
        double[] weight = new double[frames];
        double[] xs = new double[inliers.size()];
        double[] ys = new double[inliers.size()];
        double[] radial = new double[inliers.size()];
        for (int t = 0; t < frames; t++) {
            for (int i = 0; i < inliers.size(); i++) {
                Transform transform = inliers.get(i).cumulative[t];
                xs[i] = transform.dx;
                ys[i] = transform.dy;
            }
            double x = median(xs);
            double y = median(ys);
            raw[t] = Transform.translation(x, y);
            for (int i = 0; i < inliers.size(); i++) {
                radial[i] = Math.hypot(xs[i] - x, ys[i] - y);
            }
            double medianDeviation = median(radial);
            // 50%-overlapping tiles contribute about four correlated votes per nominal independent
            // area, and their edge fits still share the same interpolation and pilot. Count only the
            // square root of those areas when converting spread to uncertainty; treating all areas as
            // independent made a small common bias look significant in the persistent-layer test.
            double effective = Math.max(1.0, Math.sqrt(inliers.size() / 4.0));
            double standardError = 1.4826 * medianDeviation / Math.sqrt(effective);
            double correctionX = x - pilotCumulative[t].dx;
            double correctionY = y - pilotCumulative[t].dy;
            double correction2 = correctionX * correctionX + correctionY * correctionY;
            // Require a two-standard-error displacement before changing the already measured pilot.
            // Below that, the tile graph is evidence about uncertainty, not evidence of a correction.
            double uncertainty2 = 4.0 * standardError * standardError;
            weight[t] = correction2 > uncertainty2 && correction2 > 0
                    ? Math.min(1.0, 1.0 - uncertainty2 / correction2) : 0.0;
            guarded[t] = Transform.translation(
                    pilotCumulative[t].dx + weight[t] * correctionX,
                    pilotCumulative[t].dy + weight[t] * correctionY);
        }

        double[] closures = new double[inliers.size()];
        double[] directions = new double[inliers.size()];
        for (int i = 0; i < inliers.size(); i++) {
            closures[i] = inliers.get(i).closure;
            directions[i] = inliers.get(i).directionError;
        }
        boolean[] referenceSupport = majoritySupport(tracks, inliers, width, height);
        return new Result(raw, guarded, weight, tracks.size(), inliers.size(),
                coverage.x, coverage.y, bandwidth, median(closures), median(directions),
                referenceSupport, true);
    }

    private static double closure(Track track) {
        double[] residual = new double[track.observations.size()];
        int n = 0;
        for (Reconciler.Observation observation : track.observations) {
            Transform prediction = track.cumulative[observation.from].inverse()
                    .then(track.cumulative[observation.to]);
            residual[n++] = Math.hypot(
                    observation.displacement.dx - prediction.dx,
                    observation.displacement.dy - prediction.dy);
        }
        return median(residual);
    }

    private static double directionError(Track track, List<EdgeVotes> edges) {
        Map<Long, ModalTileAligner.Vote> voteByEdge = new LinkedHashMap<>();
        for (Reconciler.Observation observation : track.observations) {
            // The observation order is deterministic but does not itself store the tile location.
            voteByEdge.put(edgeKey(observation.from, observation.to),
                    new ModalTileAligner.Vote(observation.displacement.dx,
                            observation.displacement.dy, 1, track.x, track.y));
        }
        double[] error = new double[edges.size()];
        int n = 0;
        for (EdgeVotes edge : edges) {
            ModalTileAligner.Vote vote = voteByEdge.get(edgeKey(edge.from, edge.to));
            if (vote == null || voteMagnitude(vote) < 0.1 || edge.mode.transform.magnitude() < 0.1) {
                continue;
            }
            error[n++] = angularDistance(
                    Math.toDegrees(Math.atan2(vote.dy, vote.dx)), edge.mode.directionDegrees);
        }
        return n == 0 ? 180 : median(Arrays.copyOf(error, n));
    }

    private static double trajectoryDistance(Track a, Track b) {
        double sum = 0;
        for (int t = 1; t < a.cumulative.length; t++) {
            double dx = a.cumulative[t].dx - b.cumulative[t].dx;
            double dy = a.cumulative[t].dy - b.cumulative[t].dy;
            sum += dx * dx + dy * dy;
        }
        return Math.sqrt(sum / Math.max(1, a.cumulative.length - 1));
    }

    private static Coverage coverage(List<Track> tracks, Track centre, double radius,
                                     int width, int height) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean[] quadrant = new boolean[4];
        int count = 0;
        for (Track track : tracks) {
            if (centre != null && trajectoryDistance(centre, track) > radius) continue;
            minX = Math.min(minX, track.x);
            maxX = Math.max(maxX, track.x);
            minY = Math.min(minY, track.y);
            maxY = Math.max(maxY, track.y);
            quadrant[(track.x >= width / 2.0 ? 1 : 0)
                    + (track.y >= height / 2.0 ? 2 : 0)] = true;
            count++;
        }
        int quadrants = 0;
        for (boolean present : quadrant) if (present) quadrants++;
        double availableX = Math.max(1, width - ModalTileAligner.TILE_SIZE);
        double availableY = Math.max(1, height - ModalTileAligner.TILE_SIZE);
        return new Coverage(count,
                count == 0 ? 0 : Math.min(1, (maxX - minX) / availableX),
                count == 0 ? 0 : Math.min(1, (maxY - minY) / availableY), quadrants);
    }

    private static Result fallback(Transform[] pilot, int tracks) {
        Transform[] raw = pilot.clone();
        Transform[] guarded = pilot.clone();
        boolean[] support = new boolean[0];
        return new Result(raw, guarded, new double[pilot.length], tracks, 0,
                0, 0, Double.NaN, Double.NaN, Double.NaN, support, false);
    }

    /**
     * Keep reference pixels for which the field-wide layer supplies at least half of all overlapping
     * persistent tile tracks. This is a data-derived majority decision, not a removed-percentage
     * setting. Pixels outside every persistent tile are left available to the ordinary gradient rule.
     */
    private static boolean[] majoritySupport(List<Track> tracks, List<Track> inliers,
                                             int width, int height) {
        int[] all = new int[width * height];
        int[] accepted = new int[width * height];
        java.util.HashSet<Track> acceptedSet = new java.util.HashSet<>(inliers);
        int half = ModalTileAligner.TILE_SIZE / 2;
        for (Track track : tracks) {
            int x0 = Math.max(0, (int) Math.round(track.x) - half);
            int y0 = Math.max(0, (int) Math.round(track.y) - half);
            int x1 = Math.min(width, x0 + ModalTileAligner.TILE_SIZE);
            int y1 = Math.min(height, y0 + ModalTileAligner.TILE_SIZE);
            boolean acceptedTrack = acceptedSet.contains(track);
            for (int y = y0; y < y1; y++) {
                int row = y * width;
                for (int x = x0; x < x1; x++) {
                    int p = row + x;
                    all[p]++;
                    if (acceptedTrack) accepted[p]++;
                }
            }
        }
        boolean[] support = new boolean[width * height];
        for (int p = 0; p < support.length; p++) {
            support[p] = all[p] == 0 || accepted[p] * 2 >= all[p];
        }
        return support;
    }

    private static long positionKey(double x, double y) {
        return ((long) Math.round(x * 16) << 32) ^ (Math.round(y * 16) & 0xffffffffL);
    }

    private static long edgeKey(int from, int to) {
        return ((long) from << 32) ^ (to & 0xffffffffL);
    }

    private static double voteMagnitude(ModalTileAligner.Vote vote) {
        return Math.hypot(vote.dx, vote.dy);
    }

    private static double angularDistance(double a, double b) {
        double difference = Math.abs(a - b) % 360.0;
        return difference > 180 ? 360 - difference : difference;
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static final class Coverage {
        final int count;
        final double x;
        final double y;
        final int quadrants;

        Coverage(int count, double x, double y, int quadrants) {
            this.count = count;
            this.x = x;
            this.y = y;
            this.quadrants = quadrants;
        }
    }
}
