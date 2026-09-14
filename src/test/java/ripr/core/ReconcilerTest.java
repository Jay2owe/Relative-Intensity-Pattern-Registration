/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.lang.reflect.Modifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Reference strategies, the banded solve, and the specific failure multi-lag exists to fix. */
public class ReconcilerTest {

    @Test
    public void solutionConstructorsRemainPublicForFijiBenchmarkClassLoaders() {
        for (java.lang.reflect.Constructor<?> constructor
                : Reconciler.Solution.class.getDeclaredConstructors()) {
            assertTrue("Fiji benchmark loaders need public Solution constructors",
                    Modifier.isPublic(constructor.getModifiers()));
        }
    }

    private static Reconciler.Observation obs(int from, int to, double dx, double dy) {
        return new Reconciler.Observation(from, to, new Transform(dx, dy, 0));
    }

    @Test
    public void chainCumulatesExactly() {
        Transform[] steps = {
                new Transform(1, 2, 0), new Transform(3, -1, 0), new Transform(-2, 0.5, 0)};
        Reconciler.Solution s = Reconciler.chain(steps);
        assertEquals(4, s.cumulative.length);
        assertEquals(0, s.cumulative[0].dx, 0);
        assertEquals(1, s.cumulative[1].dx, 1e-12);
        assertEquals(4, s.cumulative[2].dx, 1e-12);
        assertEquals(2, s.cumulative[3].dx, 1e-12);
        assertEquals(1.5, s.cumulative[3].dy, 1e-12);
    }

    @Test
    public void chainMarksAMissingStepUnsupported() {
        Transform[] steps = {new Transform(1, 0, 0), null, new Transform(1, 0, 0)};
        Reconciler.Solution s = Reconciler.chain(steps);
        assertEquals("a null step contributes nothing", 2, s.cumulative[3].dx, 1e-12);
        assertEquals("and is flagged", 0, s.support[2]);
        assertTrue(s.support[1] > 0);
        assertTrue(s.support[3] > 0);
    }

    /** With perfect observations the all-pairs solve returns the truth exactly. */
    @Test
    public void multiLagRecoversTruthFromConsistentObservations() {
        int frames = 20;
        double[] tx = new double[frames];
        double[] ty = new double[frames];
        for (int t = 0; t < frames; t++) {
            tx[t] = 0.7 * t + Math.sin(t);
            ty[t] = -0.3 * t * t / frames;
        }
        List<Reconciler.Observation> o = new ArrayList<>();
        for (int k : new int[]{1, 2, 4, 8, 16}) {
            for (int t = 0; t + k < frames; t++) {
                o.add(obs(t, t + k, tx[t + k] - tx[t], ty[t + k] - ty[t]));
            }
        }
        Reconciler.Solution s = Reconciler.multiLag(frames, o);
        for (int t = 0; t < frames; t++) {
            assertEquals("frame " + t + " dx", tx[t] - tx[0], s.cumulative[t].dx, 1e-6);
            assertEquals("frame " + t + " dy", ty[t] - ty[0], s.cumulative[t].dy, 1e-6);
        }
    }

    @Test
    public void multiLagRecoversExactRigidPosesIncludingReverseObservations() {
        Transform[] truth = {
                Transform.IDENTITY,
                new Transform(3, -2, Math.toRadians(15)),
                new Transform(7, 1, Math.toRadians(-8)),
                new Transform(10, 4, Math.toRadians(22))
        };
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {0, 2}, {1, 3}, {3, 0}};
        List<Reconciler.Observation> observations = new ArrayList<>();
        for (int[] edge : edges) {
            Transform relative = truth[edge[0]].inverse().then(truth[edge[1]]);
            observations.add(new Reconciler.Observation(edge[0], edge[1], relative));
        }

        Reconciler.Solution solved = Reconciler.multiLag(truth.length, observations);
        for (int t = 0; t < truth.length; t++) {
            assertEquals("frame " + t + " dx", truth[t].dx, solved.cumulative[t].dx, 1e-7);
            assertEquals("frame " + t + " dy", truth[t].dy, solved.cumulative[t].dy, 1e-7);
            assertEquals("frame " + t + " angle", truth[t].theta,
                    solved.cumulative[t].theta, 1e-7);
        }
    }

    @Test
    public void knownAnglesReplaceTheAngularSolveExactlyAndCanBeRebased() {
        double[] known = {0, 0, Math.toRadians(2), Math.toRadians(2)};
        Transform[] truth = {
                Transform.IDENTITY,
                new Transform(1, -0.5, known[1]),
                new Transform(2, -0.9, known[2]),
                new Transform(3, -1.3, known[3])
        };
        List<Reconciler.Observation> observations = new ArrayList<>();
        for (int from = 0; from < truth.length; from++) {
            for (int lag : new int[]{1, 2}) {
                int to = from + lag;
                if (to < truth.length) {
                    observations.add(new Reconciler.Observation(
                            from, to, truth[from].inverse().then(truth[to])));
                }
            }
        }

        Reconciler.Solution solved = Reconciler.multiLagKnownAngles(
                truth.length, observations, new Reconciler.Options(), known);
        for (int frame = 0; frame < truth.length; frame++) {
            assertEquals(truth[frame].dx, solved.cumulative[frame].dx, 1e-7);
            assertEquals(truth[frame].dy, solved.cumulative[frame].dy, 1e-7);
            assertEquals(Double.doubleToLongBits(known[frame]),
                    Double.doubleToLongBits(solved.cumulative[frame].theta));
        }

        Reconciler.Solution rebased = Reconciler.withKnownAngles(solved, known, 2);
        assertEquals(-known[2], rebased.cumulative[0].theta, 0.0);
        assertEquals(0.0, rebased.cumulative[2].theta, 0.0);
        assertEquals(0.0, rebased.cumulative[3].theta, 0.0);
    }

    /**
     * The reason multi-lag exists, reproduced as a test.
     *
     * <p>The true drift is zero. Every lag-1 observation carries the same small bias; the long-lag
     * observations are unbiased. Chaining the lag-1 steps integrates the bias into a large net drift,
     * while the all-pairs solve lets the long lags out-vote it.
     *
     * <p>The same failure was measured on real data in the microglia pipeline: cumulating lag-1
     * estimates left <em>more</em> net drift than doing nothing at all — 4.61 px became 6.32 px — while
     * the per-transition residual looked healthy at 0.52 px, because a per-transition measure cannot see
     * a slow common bias.
     */
    @Test
    public void multiLagResistsABiasThatChainingIntegrates() {
        int frames = 40;
        double bias = 0.05;                       // px per transition, always the same direction
        List<Reconciler.Observation> o = new ArrayList<>();
        Transform[] steps = new Transform[frames - 1];
        for (int t = 0; t + 1 < frames; t++) {
            o.add(obs(t, t + 1, bias, 0));
            steps[t] = new Transform(bias, 0, 0);
        }
        for (int k : new int[]{8, 16}) {
            for (int t = 0; t + k < frames; t++) o.add(obs(t, t + k, 0, 0));
        }

        double chained = Math.abs(Reconciler.chain(steps).cumulative[frames - 1].dx);
        double reconciled = Math.abs(Reconciler.multiLag(frames, o).cumulative[frames - 1].dx);

        assertEquals("chaining integrates the bias over 39 transitions",
                39 * bias, chained, 1e-9);
        assertTrue("multi-lag left " + reconciled + " px against chaining's " + chained,
                reconciled < 0.25 * chained);
    }

    /** The banded factorisation must agree with a dense reference solve. */
    @Test
    public void bandedSolveMatchesDenseReference() {
        int n = 12;
        int bandwidth = 3;
        double[][] dense = new double[n][n];
        for (int i = 0; i < n; i++) {
            dense[i][i] = 4 + i * 0.1;
            for (int d = 1; d <= bandwidth && i - d >= 0; d++) {
                dense[i][i - d] = -1.0 / d;
                dense[i - d][i] = -1.0 / d;
            }
        }
        double[] rhs = new double[n];
        for (int i = 0; i < n; i++) rhs[i] = Math.sin(i) + 0.5 * i;

        double[][] band = new double[n][bandwidth + 1];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d <= bandwidth && i - d >= 0; d++) band[i][d] = dense[i][i - d];
        }
        double[] x = rhs.clone();
        assertTrue(Reconciler.choleskyBanded(band, bandwidth));
        Reconciler.solveBanded(band, bandwidth, x);

        // Verify by residual against the original dense matrix rather than by a second factorisation:
        // an independent check, not the same arithmetic run twice.
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) sum += dense[i][j] * x[j];
            assertEquals("row " + i, rhs[i], sum, 1e-9);
        }
    }

    @Test
    public void fixedReferenceIsDirectAndAnchored() {
        Transform[] toRef = new Transform[4];
        toRef[0] = null;
        toRef[1] = new Transform(5, 0, 0);
        toRef[2] = null;
        toRef[3] = new Transform(9, 1, 0);
        Reconciler.Solution s = Reconciler.fixed(toRef, 0);
        assertEquals(0, s.cumulative[0].dx, 0);
        assertEquals(5, s.cumulative[1].dx, 0);
        assertEquals("an unmeasured frame falls back to the identity", 0, s.cumulative[2].dx, 0);
        assertEquals("and is flagged", 0, s.support[2]);
        assertEquals(9, s.cumulative[3].dx, 0);
    }

    /** A frame no observation touches must be reported as unsupported, not silently zeroed. */
    @Test
    public void multiLagFlagsUnconstrainedFrames() {
        List<Reconciler.Observation> o = new ArrayList<>();
        o.add(obs(0, 1, 1, 0));
        o.add(obs(3, 4, 1, 0));         // frame 2 is touched by nothing
        Reconciler.Solution s = Reconciler.multiLag(5, o);
        assertEquals("frame 2 has no evidence", 0, s.support[2]);
        assertTrue("frame 1 does", s.support[1] > 0);
    }

    /** Frame-major planning is what keeps the pyramid cache from thrashing. */
    @Test
    public void multiLagPlanIsFrameMajor() {
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, 30, 0, 1, new int[]{1, 2, 4, 8});
        int maxFromSoFar = -1;
        int regressions = 0;
        for (Reconciler.Observation ob : plan) {
            if (ob.from < maxFromSoFar) regressions++;
            maxFromSoFar = Math.max(maxFromSoFar, ob.from);
        }
        assertEquals("the plan must sweep frames once, not once per lag — lag-major ordering was "
                + "measured to build 38 pyramids for 12 frames", 0, regressions);
    }

    @Test
    public void consecutivePlanCoversEveryTransition() {
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.CONSECUTIVE, 10, 0, 1, null);
        assertEquals(9, plan.size());
        for (int i = 0; i < plan.size(); i++) {
            assertEquals(i, plan.get(i).from);
            assertEquals(i + 1, plan.get(i).to);
        }
    }

    @Test
    public void rollingPlanIsEmptyBecauseItCannotBePlanned() {
        assertTrue(Reconciler.planPairs(Reconciler.Reference.ROLLING, 10, 0, 1, null).isEmpty());
    }

    @Test
    public void rebaseMovesTheAnchor() {
        Transform[] cum = {new Transform(0, 0, 0), new Transform(3, 1, 0), new Transform(7, 4, 0)};
        Transform[] r = Reconciler.rebase(cum, 1);
        assertEquals(-3, r[0].dx, 1e-12);
        assertEquals(0, r[1].dx, 1e-12);
        assertEquals(4, r[2].dx, 1e-12);
        assertEquals(3, r[2].dy, 1e-12);
    }
}
