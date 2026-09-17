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
import logratio.core.Transform;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins the integral-image tile approximation used by the fast graph screen. */
public class FastTileVotesTest {

    private static final int SIZE = 128;

    @Test
    public void oneLinearTilePassRecoversAGlobalShift() {
        double dx = 2.4;
        double dy = -1.7;
        LogPlane a = LogPlane.of(frame(0, 0), SIZE, SIZE, 1.0);
        LogPlane b = LogPlane.of(frame(dx, dy), SIZE, SIZE, 1.0);
        Transform pilot = Transform.translation(2.0, -1.4);
        List<ModalTileAligner.Vote> votes = FastTileVotes.votes(
                a, b, pilot, Transform.IDENTITY);
        ModalTileAligner.Result result = ModalTileAligner.mode(
                votes, pilot, SIZE, SIZE, ModalTileAligner.TILE_SIZE);

        assertTrue(result.usable);
        assertTrue(votes.size() >= 20);
        assertEquals(dx, result.transform.dx, 0.08);
        assertEquals(dy, result.transform.dy, 0.08);
    }

    private static float[] frame(double dx, double dy) {
        float[] out = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = 2 * Math.PI * (x - dx) / SIZE;
                double v = 2 * Math.PI * (y - dy) / SIZE;
                out[y * SIZE + x] = (float) (2000
                        + 500 * Math.sin(3 * u + 0.4) * Math.cos(2 * v - 0.7)
                        + 350 * Math.sin(5 * u - 1.1) * Math.cos(7 * v + 0.2)
                        + 225 * Math.cos(11 * u + 0.9) * Math.sin(6 * v + 1.3));
            }
        }
        return out;
    }
}
