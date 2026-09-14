/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.LogPlane;
import ripr.core.Transform;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests the simple global modal-movement baseline before it is used in the benchmark. */
public class ModalTileAlignerTest {

    private static final int W = 128;
    private static final int H = 128;

    @Test
    public void recoversGlobalMovementFromAnImperfectPilot() {
        double dx = 2.4;
        double dy = -1.7;
        LogPlane a = LogPlane.of(frame(0, 0), W, H, 1.0);
        LogPlane b = LogPlane.of(frame(dx, dy), W, H, 1.0);
        ModalTileAligner.Result result = ModalTileAligner.align(
                a, b, Transform.translation(2.0, -1.4));

        assertTrue("modal result fell back to its pilot", result.usable);
        assertEquals(dx, result.transform.dx, 0.05);
        assertEquals(dy, result.transform.dy, 0.05);
        assertTrue(result.inlierTiles >= 12);
        assertTrue(result.coverageX >= 0.5);
        assertTrue(result.coverageY >= 0.5);
    }

    @Test
    public void widespreadModeBeatsATighterButLocalMotionGroup() {
        List<ModalTileAligner.Vote> votes = new ArrayList<>();
        for (int y = 16; y <= 112; y += 32) {
            for (int x = 16; x <= 112; x += 24) {
                votes.add(new ModalTileAligner.Vote(2.0, -1.0, 1.0, x, y));
            }
        }
        // More than the 35% density neighbourhood, but confined to one corner of the field.
        for (int i = 0; i < 18; i++) {
            votes.add(new ModalTileAligner.Vote(5.0, 4.0, 2.0,
                    8 + i % 5, 8 + i / 5));
        }

        ModalTileAligner.Result result = ModalTileAligner.mode(
                votes, Transform.IDENTITY, 128, 128, 32);
        assertTrue(result.usable);
        assertEquals(2.0, result.transform.dx, 1e-12);
        assertEquals(-1.0, result.transform.dy, 1e-12);
    }

    @Test
    public void aLocalisedModeFallsBackToTheGraphPrediction() {
        List<ModalTileAligner.Vote> votes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            votes.add(new ModalTileAligner.Vote(5.0, 4.0, 1.0,
                    8 + i % 5, 8 + i / 5));
        }
        Transform fallback = Transform.translation(0.7, -0.3);
        ModalTileAligner.Result result = ModalTileAligner.mode(
                votes, fallback, 128, 128, 32);
        assertFalse(result.usable);
        assertEquals(fallback, result.transform);
    }

    @Test
    public void referenceGridKeepsTileIdentitiesFixedWhileSourceWindowsMove() {
        LogPlane a = LogPlane.of(frame(3, -2), W, H, 1.0);
        LogPlane b = LogPlane.of(frame(5.4, -3.7), W, H, 1.0);
        List<ModalTileAligner.Vote> votes = ModalTileAligner.votes(
                a, b, Transform.translation(2.2, -1.5), Transform.translation(3, -2));

        assertTrue(votes.size() >= 20);
        for (ModalTileAligner.Vote vote : votes) {
            // Tile centres belong to the unchanged 16-pixel reference grid, even though their source
            // windows were shifted by (+3,-2) in frame a.
            assertEquals(0.0, (vote.x - 16.0) % 16.0, 1e-12);
            assertEquals(0.0, (vote.y - 16.0) % 16.0, 1e-12);
        }
    }

    private static float[] frame(double dx, double dy) {
        float[] out = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double u = 2 * Math.PI * (x - dx) / W;
                double v = 2 * Math.PI * (y - dy) / H;
                out[y * W + x] = (float) (2000
                        + 500 * Math.sin(3 * u + 0.4) * Math.cos(2 * v - 0.7)
                        + 350 * Math.sin(5 * u - 1.1) * Math.cos(7 * v + 0.2)
                        + 225 * Math.cos(11 * u + 0.9) * Math.sin(6 * v + 1.3));
            }
        }
        return out;
    }
}
