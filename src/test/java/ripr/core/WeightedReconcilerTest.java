/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Analytic generalized-least-squares cases for translation and rigid graphs. */
public class WeightedReconcilerTest {

    @Test
    public void preciseRouteOutvotesBroadConflictingEdge() {
        PairUncertainty precise = covariance2(0.01, 0, 0.01);
        PairUncertainty broad = covariance2(100, 0, 100);
        List<Reconciler.Observation> observations = new ArrayList<>();
        observations.add(observation(0, 1, 1, 0, precise));
        observations.add(observation(1, 2, 1, 0, precise));
        observations.add(observation(0, 2, 10, 0, broad));

        Reconciler.Solution equal = Reconciler.multiLag(3, observations);
        Reconciler.Solution weighted = solve(observations, 3,
                Reconciler.Weighting.UNCERTAINTY, 2);
        assertTrue(Math.abs(weighted.cumulative[2].dx - 2)
                < Math.abs(equal.cumulative[2].dx - 2));
        assertEquals(2.0, weighted.cumulative[2].dx, 0.01);
    }

    @Test
    public void anisotropyChangesOnlyTheUncertainDirection() {
        PairUncertainty xPrecise = covariance2(0.01, 0, 100);
        PairUncertainty yPrecise = covariance2(100, 0, 0.01);
        List<Reconciler.Observation> observations = Arrays.asList(
                observation(0, 1, 1, 9, xPrecise),
                observation(0, 1, 8, 2, yPrecise));
        Reconciler.Solution weighted = solve(observations, 2,
                Reconciler.Weighting.UNCERTAINTY, 2);
        assertEquals(1.0, weighted.cumulative[1].dx, 0.01);
        assertEquals(2.0, weighted.cumulative[1].dy, 0.01);
    }

    @Test
    public void reversingAnObservationAndItsCovarianceIsInvariant() {
        Transform displacement = new Transform(2.5, -1.25, 0.12);
        PairUncertainty uncertainty = PairUncertainty.fromCovariance(3, new double[]{
                0.2, 0.03, 0.002,
                0.03, 0.4, -0.001,
                0.002, -0.001, 0.0004
        }, Double.NaN, false, false, false);
        List<Reconciler.Observation> forward = Collections.singletonList(
                new Reconciler.Observation(0, 1, displacement, uncertainty));
        List<Reconciler.Observation> reverse = Collections.singletonList(
                new Reconciler.Observation(1, 0, displacement.inverse(),
                        uncertainty.inverseFor(displacement)));
        Transform a = solve(forward, 2, Reconciler.Weighting.UNCERTAINTY, 3).cumulative[1];
        Transform b = solve(reverse, 2, Reconciler.Weighting.UNCERTAINTY, 3).cumulative[1];
        assertEquals(a.dx, b.dx, 1e-12);
        assertEquals(a.dy, b.dy, 1e-12);
        assertEquals(a.theta, b.theta, 1e-12);
    }

    @Test
    public void globalCovarianceScaleCancelsDuringNormalization() {
        List<Reconciler.Observation> first = scaledGraph(1.0);
        List<Reconciler.Observation> second = scaledGraph(37.0);
        Transform[] a = solve(first, 3, Reconciler.Weighting.UNCERTAINTY, 2).cumulative;
        Transform[] b = solve(second, 3, Reconciler.Weighting.UNCERTAINTY, 2).cumulative;
        for (int frame = 0; frame < a.length; frame++) {
            assertEquals(a[frame].dx, b[frame].dx, 1e-12);
            assertEquals(a[frame].dy, b[frame].dy, 1e-12);
        }
    }

    @Test
    public void unavailableUncertaintyFallsBackWithoutRemovingSupport() {
        List<Reconciler.Observation> observations = Arrays.asList(
                new Reconciler.Observation(0, 1, Transform.translation(1, 0),
                        PairUncertainty.unavailable(2)),
                observation(1, 2, 1, 0, covariance2(1, 0, 1)));
        Reconciler.Solution result = solve(observations, 3,
                Reconciler.Weighting.UNCERTAINTY, 2);
        assertTrue(result.support[1] > 0);
        assertTrue(result.support[2] > 0);
        assertTrue(result.influences.get(0).uncertaintyFallback);
    }

    @Test
    public void rigidCrossTermChangesJointTranslationAndAngle() {
        PairUncertainty coupled = PairUncertainty.fromCovariance(3, new double[]{
                1, 0, 0.08,
                0, 1, 0,
                0.08, 0, 0.01
        }, Double.NaN, false, false, false);
        PairUncertainty diagonal = PairUncertainty.fromCovariance(3, new double[]{
                1, 0, 0,
                0, 1, 0,
                0, 0, 0.01
        }, Double.NaN, false, false, false);
        List<Reconciler.Observation> common = Arrays.asList(
                new Reconciler.Observation(0, 1, new Transform(1, 0, 0.05), coupled),
                new Reconciler.Observation(1, 2, new Transform(1, 0, 0.05), coupled),
                new Reconciler.Observation(0, 2, new Transform(3, 0, 0.02), coupled));
        List<Reconciler.Observation> uncoupled = Arrays.asList(
                new Reconciler.Observation(0, 1, new Transform(1, 0, 0.05), diagonal),
                new Reconciler.Observation(1, 2, new Transform(1, 0, 0.05), diagonal),
                new Reconciler.Observation(0, 2, new Transform(3, 0, 0.02), diagonal));
        Transform coupledResult = solve(common, 3,
                Reconciler.Weighting.UNCERTAINTY, 3).cumulative[2];
        Transform diagonalResult = solve(uncoupled, 3,
                Reconciler.Weighting.UNCERTAINTY, 3).cumulative[2];
        assertTrue(Math.abs(coupledResult.dx - diagonalResult.dx) > 1e-5);
        assertTrue(Math.abs(coupledResult.theta - diagonalResult.theta) > 1e-7);
    }

    private static List<Reconciler.Observation> scaledGraph(double scale) {
        PairUncertainty a = covariance2(0.1 * scale, 0.01 * scale, 0.2 * scale);
        PairUncertainty b = covariance2(2 * scale, 0, 3 * scale);
        return Arrays.asList(observation(0, 1, 1, 2, a),
                observation(1, 2, 1, -1, a), observation(0, 2, 2.4, 0.5, b));
    }

    static Reconciler.Solution solve(List<Reconciler.Observation> observations, int frames,
                                     Reconciler.Weighting weighting, int dimensions) {
        return Reconciler.multiLag(frames, observations,
                new Reconciler.Options(weighting, dimensions, 50.0));
    }

    private static PairUncertainty covariance2(double xx, double xy, double yy) {
        return PairUncertainty.fromCovariance(2, new double[]{xx, xy, xy, yy},
                Double.NaN, false, false, false);
    }

    private static Reconciler.Observation observation(
            int from, int to, double dx, double dy, PairUncertainty uncertainty) {
        return new Reconciler.Observation(from, to, Transform.translation(dx, dy), uncertainty);
    }
}
