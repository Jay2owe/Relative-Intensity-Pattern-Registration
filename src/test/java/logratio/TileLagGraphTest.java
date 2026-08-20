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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins the recording-level distinction between a field-wide stage layer and a compact moving layer. */
public class TileLagGraphTest {

    private static final int FRAMES = 24;
    private static final int WIDTH = 256;
    private static final int HEIGHT = 256;
    private static final int[] LAGS = {1, 2, 4, 8};

    @Test
    public void fieldWideTrajectoryBeatsAConsistentCompactMovingLayer() {
        Transform[] truth = trajectory(0.0);
        Transform[] moving = trajectory(0.18);
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, FRAMES, 0, 1, LAGS);
        List<TileLagGraph.EdgeVotes> edges = new ArrayList<>();
        for (Reconciler.Observation edge : plan) {
            Transform pilot = difference(truth, edge.from, edge.to);
            List<ModalTileAligner.Vote> votes = new ArrayList<>();
            int id = 0;
            for (int y = 16; y <= 240; y += 32) {
                for (int x = 16; x <= 240; x += 48) {
                    double noise = 0.003 * Math.sin(0.7 * id + 0.3 * edge.from);
                    votes.add(new ModalTileAligner.Vote(
                            pilot.dx + noise, pilot.dy - 0.5 * noise, 1.0, x, y));
                    id++;
                }
            }
            Transform local = difference(moving, edge.from, edge.to);
            for (int i = 0; i < 24; i++) {
                votes.add(new ModalTileAligner.Vote(local.dx, local.dy, 2.0,
                        5 + i % 6, 5 + i / 6));
            }
            edges.add(new TileLagGraph.EdgeVotes(
                    edge.from, edge.to, pilot, votes, WIDTH, HEIGHT));
        }

        TileLagGraph.Result result = TileLagGraph.reconcile(
                FRAMES, truth, edges, WIDTH, HEIGHT);
        assertTrue(result.usable);
        assertTrue(result.inlierTracks >= 20);
        assertTrue(result.coverageX >= 0.5);
        assertTrue(result.coverageY >= 0.5);
        assertTrue("field-wide layer should support the image centre",
                result.referenceSupport[128 * WIDTH + 128]);
        assertTrue("compact competing layer should be excluded",
                !result.referenceSupport[6 * WIDTH + 6]);
        for (int t = 0; t < FRAMES; t++) {
            assertEquals("frame " + t + " dx", truth[t].dx, result.raw[t].dx, 0.02);
            assertEquals("frame " + t + " dy", truth[t].dy, result.raw[t].dy, 0.02);
        }
    }

    @Test
    public void uncertaintyGuardShrinksAnInsignificantTileCorrection() {
        Transform[] truth = trajectory(0.0);
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, FRAMES, 0, 1, LAGS);
        List<TileLagGraph.EdgeVotes> edges = new ArrayList<>();
        for (Reconciler.Observation edge : plan) {
            Transform pilot = difference(truth, edge.from, edge.to);
            List<ModalTileAligner.Vote> votes = new ArrayList<>();
            int id = 0;
            for (int y = 16; y <= 240; y += 32) {
                for (int x = 16; x <= 240; x += 32) {
                    double noise = 0.02 * Math.sin(id++ + 0.7 * edge.from + 0.13 * edge.to);
                    votes.add(new ModalTileAligner.Vote(
                            pilot.dx + noise, pilot.dy - noise, 1.0, x, y));
                }
            }
            edges.add(new TileLagGraph.EdgeVotes(
                    edge.from, edge.to, pilot, votes, WIDTH, HEIGHT));
        }
        TileLagGraph.Result result = TileLagGraph.reconcile(
                FRAMES, truth, edges, WIDTH, HEIGHT);
        assertTrue(result.usable);
        double[] raw = new double[FRAMES];
        double[] guarded = new double[FRAMES];
        for (int t = 0; t < FRAMES; t++) {
            raw[t] = Math.hypot(result.raw[t].dx - truth[t].dx,
                    result.raw[t].dy - truth[t].dy);
            guarded[t] = Math.hypot(result.guarded[t].dx - truth[t].dx,
                    result.guarded[t].dy - truth[t].dy);
            assertTrue("frame " + t + " guard did not shrink " + raw[t],
                    guarded[t] <= raw[t] + 1e-12);
        }
        java.util.Arrays.sort(raw);
        java.util.Arrays.sort(guarded);
        assertTrue("median guard " + guarded[FRAMES / 2] + " vs raw " + raw[FRAMES / 2],
                guarded[FRAMES / 2] < raw[FRAMES / 2]);
        assertTrue("guard retained a broad noisy trajectory: p90 " + guarded[21],
                guarded[21] < 0.04);
    }

    private static Transform[] trajectory(double extraVelocity) {
        Transform[] out = new Transform[FRAMES];
        for (int t = 0; t < FRAMES; t++) {
            out[t] = Transform.translation(
                    1.4 * Math.sin(0.31 * t) + extraVelocity * t,
                    -0.9 * Math.cos(0.23 * t) + 0.9);
        }
        return out;
    }

    private static Transform difference(Transform[] trajectory, int from, int to) {
        return trajectory[from].inverse().then(trajectory[to]);
    }
}
