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
import logratio.core.RobustNorm;
import logratio.core.Transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Benchmark-only modal translation from a spatial grid of local tile votes.
 *
 * <p>Every textured tile starts from the recording's provisional global shift and makes a small
 * translation-only Lucas-Kanade refinement. The final pair displacement is the densest cluster in the
 * resulting {@code (dx,dy)} plane, provided that cluster is spread across the field. That spatial test
 * stops one compact moving object from becoming the "global" mode merely because its tiles agree very
 * tightly with one another.
 */
final class ModalTileAligner {

    static final int TILE_SIZE = 32;
    static final int TILE_STRIDE = 16;
    private static final int MAX_ITERATIONS = 6;
    private static final double CONVERGENCE = 1e-3;
    private static final double MAX_LOCAL_CORRECTION = 8.0;
    private static final double MIN_TEXTURE_RATIO = 0.01;
    private static final double MIN_COVERAGE = 0.50;
    private static final double SCALE_FLOOR = 1e-4;
    /** Share of all valid votes whose neighbourhood defines the local density. */
    private static final double MODE_NEIGHBOUR_SHARE = 0.35;

    static final class Vote {
        final double dx;
        final double dy;
        final double information;
        final double x;
        final double y;

        Vote(double dx, double dy, double information, double x, double y) {
            this.dx = dx;
            this.dy = dy;
            this.information = information;
            this.x = x;
            this.y = y;
        }
    }

    static final class Result {
        final Transform transform;
        final int tileVotes;
        final int inlierTiles;
        final double coverageX;
        final double coverageY;
        final double directionDegrees;
        final double bandwidth;
        final boolean usable;

        Result(Transform transform, int tileVotes, int inlierTiles,
               double coverageX, double coverageY, double bandwidth, boolean usable) {
            this.transform = transform;
            this.tileVotes = tileVotes;
            this.inlierTiles = inlierTiles;
            this.coverageX = coverageX;
            this.coverageY = coverageY;
            this.directionDegrees = Math.toDegrees(Math.atan2(transform.dy, transform.dx));
            this.bandwidth = bandwidth;
            this.usable = usable;
        }
    }

    private ModalTileAligner() {
    }

    static Result align(LogPlane a, LogPlane b, Transform start) {
        List<Vote> votes = votes(a, b, start);
        int tile = Math.min(TILE_SIZE, Math.min(a.width, a.height));
        return mode(votes, start, a.width, a.height, tile);
    }

    /** Local votes on the deterministic tile grid, exposed for recording-level lag graphs. */
    static List<Vote> votes(LogPlane a, LogPlane b, Transform start) {
        return votes(a, b, start, Transform.IDENTITY);
    }

    /**
     * Local votes whose identities stay on one recording-wide reference grid.
     *
     * <p>{@code referenceToA} maps each reference-grid tile centre into frame {@code a}; the local
     * refinement uses that mapped source window, but the returned vote keeps the reference-grid
     * centre. Consequently the same {@code (x,y)} key represents the same sample area on every lag
     * edge instead of merely the same sensor coordinates.
     */
    static List<Vote> votes(LogPlane a, LogPlane b, Transform start, Transform referenceToA) {
        if (a.width != b.width || a.height != b.height) {
            throw new IllegalArgumentException("tile frames differ in size");
        }
        if (start == null) throw new IllegalArgumentException("start transform must not be null");
        if (referenceToA == null) {
            throw new IllegalArgumentException("reference transform must not be null");
        }
        int tile = Math.min(TILE_SIZE, Math.min(a.width, a.height));
        if (tile < 8) return new ArrayList<>();

        List<Integer> xs = origins(a.width, tile, Math.min(TILE_STRIDE, tile));
        List<Integer> ys = origins(a.height, tile, Math.min(TILE_STRIDE, tile));
        Scratch scratch = new Scratch(tile * tile);
        List<Vote> votes = new ArrayList<>(xs.size() * ys.size());
        double[] mapped = new double[2];
        for (int referenceY0 : ys) {
            for (int referenceX0 : xs) {
                double referenceX = referenceX0 + 0.5 * tile;
                double referenceY = referenceY0 + 0.5 * tile;
                referenceToA.apply(referenceX, referenceY,
                        0.5 * (a.width - 1), 0.5 * (a.height - 1), mapped);
                int sourceX0 = (int) Math.round(mapped[0] - 0.5 * tile);
                int sourceY0 = (int) Math.round(mapped[1] - 0.5 * tile);
                if (sourceX0 < 0 || sourceY0 < 0
                        || sourceX0 + tile > a.width || sourceY0 + tile > a.height) {
                    continue;
                }
                Vote vote = refineTile(a, b, start, sourceX0, sourceY0, tile, scratch);
                if (vote != null) {
                    votes.add(new Vote(vote.dx, vote.dy, vote.information,
                            referenceX, referenceY));
                }
            }
        }
        return votes;
    }

    /** Visible for a deterministic competing-motion test without constructing an artificial movie. */
    static Result mode(List<Vote> votes, Transform fallback, int width, int height, int tile) {
        int n = votes.size();
        if (n < 6) return fallback(fallback, n);
        int neighbours = Math.max(3, (int) Math.ceil(MODE_NEIGHBOUR_SHARE * n));
        double bestRadius2 = Double.POSITIVE_INFINITY;
        Vote seed = null;
        double[] distance2 = new double[n];
        double[] ordered = new double[n];

        // A candidate is eligible to seed the mode only when its nearest movement neighbours are
        // distributed over the image. Density alone would prefer a compact moving cell population.
        for (Vote candidate : votes) {
            for (int j = 0; j < n; j++) {
                Vote other = votes.get(j);
                double dx = other.dx - candidate.dx;
                double dy = other.dy - candidate.dy;
                distance2[j] = dx * dx + dy * dy;
            }
            System.arraycopy(distance2, 0, ordered, 0, n);
            Arrays.sort(ordered);
            double radius2 = ordered[neighbours - 1];
            Coverage coverage = coverageWithin(votes, candidate.dx, candidate.dy,
                    radius2 + 1e-15, width, height, tile);
            if (coverage.x >= MIN_COVERAGE && coverage.y >= MIN_COVERAGE
                    && coverage.quadrants >= 3 && radius2 < bestRadius2) {
                bestRadius2 = radius2;
                seed = candidate;
            }
        }
        if (seed == null) return fallback(fallback, n);

        double centreX = seed.dx;
        double centreY = seed.dy;
        double bandwidth = Math.max(0.05, 1.25 * Math.sqrt(bestRadius2));
        double medianInformation = medianInformation(votes);
        for (int iteration = 0; iteration < 12; iteration++) {
            double sx = 0;
            double sy = 0;
            double sw = 0;
            for (Vote vote : votes) {
                double dx = vote.dx - centreX;
                double dy = vote.dy - centreY;
                double u2 = (dx * dx + dy * dy) / (bandwidth * bandwidth);
                if (u2 >= 1) continue;
                double kernel = (1 - u2) * (1 - u2);
                double informationWeight = medianInformation > 0
                        ? Math.sqrt(Math.min(4.0, vote.information / medianInformation)) : 1.0;
                double weight = kernel * informationWeight;
                sx += weight * vote.dx;
                sy += weight * vote.dy;
                sw += weight;
            }
            if (!(sw > 0)) return fallback(fallback, n);
            double nextX = sx / sw;
            double nextY = sy / sw;
            if (Math.hypot(nextX - centreX, nextY - centreY) < 1e-4) {
                centreX = nextX;
                centreY = nextY;
                break;
            }
            centreX = nextX;
            centreY = nextY;
        }

        Coverage coverage = coverageWithin(votes, centreX, centreY,
                bandwidth * bandwidth, width, height, tile);
        boolean usable = coverage.count >= 3 && coverage.x >= MIN_COVERAGE
                && coverage.y >= MIN_COVERAGE && coverage.quadrants >= 3;
        Transform transform = usable
                ? Transform.translation(centreX, centreY)
                : fallback;
        return new Result(transform, n, coverage.count, coverage.x, coverage.y,
                bandwidth, usable);
    }

    private static Vote refineTile(LogPlane a, LogPlane b, Transform start,
                                   int x0, int y0, int tile, Scratch s) {
        double dx = start.dx;
        double dy = start.dy;
        double information = 0;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            int n = 0;
            int possible = 0;
            for (int y = y0; y < y0 + tile; y++) {
                int row = y * a.width;
                for (int x = x0; x < x0 + tile; x++) {
                    possible++;
                    int p = row + x;
                    if (!a.valid[p]) continue;
                    float value = b.sample(x + dx, y + dy);
                    float gx = b.sampleGx(x + dx, y + dy);
                    float gy = b.sampleGy(x + dx, y + dy);
                    if (Float.isNaN(value) || Float.isNaN(gx) || Float.isNaN(gy)) continue;
                    s.d[n] = value - a.v[p];
                    s.gx[n] = gx;
                    s.gy[n] = gy;
                    n++;
                }
            }
            if (n < Math.max(16, possible / 2)) return null;

            System.arraycopy(s.d, 0, s.work, 0, n);
            double gain = select(s.work, n, n / 2);
            for (int i = 0; i < n; i++) s.work[i] = Math.abs(s.d[i] - gain);
            double mad = select(s.work, n, n / 2);
            if (!(mad > 0)) mad = select(s.work, n, (int) (0.9 * (n - 1)));
            double scale = Math.max(SCALE_FLOOR, 1.4826 * mad);
            double threshold = RobustNorm.TUKEY.threshold(scale);

            double hxx = 0;
            double hxy = 0;
            double hyy = 0;
            double bx = 0;
            double by = 0;
            for (int i = 0; i < n; i++) {
                double residual = s.d[i] - gain;
                double weight = RobustNorm.TUKEY.weight(residual, threshold);
                if (weight == 0) continue;
                double wx = weight * s.gx[i];
                double wy = weight * s.gy[i];
                hxx += wx * s.gx[i];
                hxy += wx * s.gy[i];
                hyy += wy * s.gy[i];
                bx += wx * residual;
                by += wy * residual;
            }
            double trace = hxx + hyy;
            double discriminant = Math.sqrt((hxx - hyy) * (hxx - hyy) + 4 * hxy * hxy);
            double small = 0.5 * (trace - discriminant);
            double large = 0.5 * (trace + discriminant);
            if (!(small > 0) || small / Math.max(small, large) < MIN_TEXTURE_RATIO) return null;
            information = small / n;

            double determinant = hxx * hyy - hxy * hxy;
            if (!(determinant > 1e-12 * (Math.abs(hxx * hyy) + hxy * hxy + 1e-30))) {
                return null;
            }
            double stepX = (-hyy * bx + hxy * by) / determinant;
            double stepY = (hxy * bx - hxx * by) / determinant;
            double step = Math.hypot(stepX, stepY);
            if (step > 1.0) {
                stepX /= step;
                stepY /= step;
                step = 1.0;
            }
            dx += stepX;
            dy += stepY;
            if (Math.hypot(dx - start.dx, dy - start.dy) > MAX_LOCAL_CORRECTION) return null;
            if (step < CONVERGENCE) break;
        }
        return new Vote(dx, dy, information, x0 + 0.5 * tile, y0 + 0.5 * tile);
    }

    private static List<Integer> origins(int length, int tile, int stride) {
        List<Integer> out = new ArrayList<>();
        for (int x = 0; x + tile <= length; x += stride) out.add(x);
        int last = length - tile;
        if (out.isEmpty() || out.get(out.size() - 1) != last) out.add(last);
        return out;
    }

    private static double medianInformation(List<Vote> votes) {
        double[] values = new double[votes.size()];
        for (int i = 0; i < values.length; i++) values[i] = votes.get(i).information;
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static Coverage coverageWithin(List<Vote> votes, double dx, double dy, double radius2,
                                           int width, int height, int tile) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean[] quadrant = new boolean[4];
        int count = 0;
        for (Vote vote : votes) {
            double vx = vote.dx - dx;
            double vy = vote.dy - dy;
            if (vx * vx + vy * vy > radius2) continue;
            count++;
            minX = Math.min(minX, vote.x);
            maxX = Math.max(maxX, vote.x);
            minY = Math.min(minY, vote.y);
            maxY = Math.max(maxY, vote.y);
            int q = (vote.x >= width / 2.0 ? 1 : 0) + (vote.y >= height / 2.0 ? 2 : 0);
            quadrant[q] = true;
        }
        int quadrants = 0;
        for (boolean present : quadrant) if (present) quadrants++;
        double availableX = Math.max(1, width - tile);
        double availableY = Math.max(1, height - tile);
        double coverageX = count == 0 ? 0 : Math.min(1, (maxX - minX) / availableX);
        double coverageY = count == 0 ? 0 : Math.min(1, (maxY - minY) / availableY);
        return new Coverage(count, coverageX, coverageY, quadrants);
    }

    private static Result fallback(Transform transform, int votes) {
        return new Result(transform, votes, 0, 0, 0, Double.NaN, false);
    }

    /** In-place Hoare selection over {@code values[0..n)}. */
    private static double select(double[] values, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            double pivot = values[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (values[i] < pivot) i++;
                while (values[j] > pivot) j--;
                if (i <= j) {
                    double swap = values[i];
                    values[i] = values[j];
                    values[j] = swap;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return values[k];
        }
        return values[lo];
    }

    private static final class Scratch {
        final double[] d;
        final double[] gx;
        final double[] gy;
        final double[] work;

        Scratch(int capacity) {
            d = new double[capacity];
            gx = new double[capacity];
            gy = new double[capacity];
            work = new double[capacity];
        }
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
