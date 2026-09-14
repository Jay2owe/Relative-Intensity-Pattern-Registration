/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Graph-consistency factors, floors, convergence and deterministic ordering. */
public class RobustReconcilerTest {

    @Test
    public void robustModesContainOneGrossConflictingEdge() {
        List<Reconciler.Observation> graph = consistentGraphWithOutlier(20.0);
        Reconciler.Solution equal = Reconciler.multiLag(4, graph);
        Reconciler.Solution robust = WeightedReconcilerTest.solve(
                graph, 4, Reconciler.Weighting.ROBUST, 2);
        assertTrue(Math.abs(robust.cumulative[3].dx - 3)
                < Math.abs(equal.cumulative[3].dx - 3));
        Reconciler.PairInfluence outlier = robust.influences.stream()
                .filter(influence -> influence.from == 0 && influence.to == 3)
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(outlier.robustFactor < 1.0);
        assertTrue(outlier.robustFactor > 0.0);
    }

    @Test
    public void exactlyConsistentGraphKeepsUnitFactors() {
        List<Reconciler.Observation> graph = consistentGraphWithOutlier(3.0);
        Reconciler.Solution robust = WeightedReconcilerTest.solve(
                graph, 4, Reconciler.Weighting.ROBUST, 2);
        for (Reconciler.PairInfluence influence : robust.influences) {
            assertEquals(1.0, influence.robustFactor, 0.0);
        }
        assertEquals(3.0, robust.cumulative[3].dx, 1e-8);
    }

    @Test
    public void grossEdgeReachesButNeverCrossesInfluenceFloor() {
        Reconciler.Solution robust = WeightedReconcilerTest.solve(
                consistentGraphWithOutlier(1e9), 4, Reconciler.Weighting.ROBUST, 2);
        for (Reconciler.PairInfluence influence : robust.influences) {
            assertTrue(Double.isFinite(influence.robustFactor));
            assertTrue(influence.robustFactor >= 0.05);
            assertTrue(influence.robustFactor <= 1.0);
        }
    }

    @Test
    public void inputOrderCannotChangeTrajectoryOrEdgeFactors() {
        List<Reconciler.Observation> forward = consistentGraphWithOutlier(20.0);
        List<Reconciler.Observation> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        Reconciler.Solution a = WeightedReconcilerTest.solve(
                forward, 4, Reconciler.Weighting.ROBUST, 2);
        Reconciler.Solution b = WeightedReconcilerTest.solve(
                reverse, 4, Reconciler.Weighting.ROBUST, 2);
        for (int frame = 0; frame < 4; frame++) {
            assertEquals(a.cumulative[frame].dx, b.cumulative[frame].dx, 1e-12);
            assertEquals(a.cumulative[frame].dy, b.cumulative[frame].dy, 1e-12);
        }
        for (Reconciler.PairInfluence influence : a.influences) {
            Reconciler.PairInfluence match = b.influences.stream()
                    .filter(other -> other.from == influence.from && other.to == influence.to)
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals(influence.robustFactor, match.robustFactor, 1e-12);
        }
    }

    private static List<Reconciler.Observation> consistentGraphWithOutlier(double finalEdge) {
        List<Reconciler.Observation> graph = new ArrayList<>();
        graph.add(observation(0, 1, 1));
        graph.add(observation(1, 2, 1));
        graph.add(observation(2, 3, 1));
        graph.add(observation(0, 2, 2));
        graph.add(observation(1, 3, 2));
        graph.add(observation(0, 3, finalEdge));
        return graph;
    }

    private static Reconciler.Observation observation(int from, int to, double dx) {
        return new Reconciler.Observation(from, to, Transform.translation(dx, 0));
    }
}
