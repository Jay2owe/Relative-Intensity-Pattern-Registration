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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The transform type, and the difference between exact and parameter-space composition. */
public class TransformTest {

    @Test
    public void translationsCompose() {
        Transform a = Transform.translation(3, -1);
        Transform b = Transform.translation(-1, 5);
        assertEquals(2, a.then(b).dx, 1e-12);
        assertEquals(4, a.then(b).dy, 1e-12);
    }

    /** Rotations about a common centre add exactly; that is why the centre is fixed by convention. */
    @Test
    public void rotationsAddExactly() {
        Transform a = new Transform(0, 0, 0.3);
        Transform b = new Transform(0, 0, -0.1);
        assertEquals(0.2, a.then(b).theta, 1e-15);
    }

    @Test
    public void inverseUndoesExactly() {
        Transform t = new Transform(4.5, -2.25, 0.17);
        Transform round = t.then(t.inverse());
        assertEquals(0, round.dx, 1e-12);
        assertEquals(0, round.dy, 1e-12);
        assertEquals(0, round.theta, 1e-15);
        Transform other = t.inverse().then(t);
        assertEquals(0, other.dx, 1e-12);
        assertEquals(0, other.dy, 1e-12);
    }

    /**
     * Exact composition and parameter addition differ by a rotation of the earlier translation.
     *
     * <p>Recorded as a test because the multi-lag solve is necessarily linear in its parameters and so
     * must use {@link Transform#plus}. The discrepancy is of order {@code |t| * theta} — for the
     * fractions of a degree that stage shake produces it is far below a hundredth of a pixel, and this
     * test pins the size of it so enabling rotation later cannot quietly change what the solve means.
     */
    @Test
    public void parameterAdditionIsFirstOrderInTheAngle() {
        Transform a = Transform.translation(10, 0);
        double smallAngle = Math.toRadians(0.5);
        Transform b = new Transform(0, 0, smallAngle);
        Transform exact = a.then(b);
        Transform approx = a.plus(b);
        double gap = Math.hypot(exact.dx - approx.dx, exact.dy - approx.dy);
        assertTrue("gap " + gap + " should be small for half a degree", gap < 0.1);
        assertTrue("but not zero — the two are genuinely different operations", gap > 0);

        Transform big = new Transform(0, 0, Math.toRadians(45));
        double bigGap = Math.hypot(a.then(big).dx - a.plus(big).dx,
                a.then(big).dy - a.plus(big).dy);
        assertTrue("at 45 degrees they diverge badly: " + bigGap, bigGap > 5);
    }

    @Test
    public void scaleTranslationLeavesTheAngle() {
        Transform t = new Transform(3, 4, 0.2);
        Transform s = t.scaleTranslation(2);
        assertEquals(6, s.dx, 1e-12);
        assertEquals(8, s.dy, 1e-12);
        assertEquals(0.2, s.theta, 1e-15);
    }

    @Test
    public void applyMovesAPointThroughARotation() {
        Transform quarter = new Transform(0, 0, Math.PI / 2);
        double[] out = new double[2];
        quarter.apply(1, 0, 0, 0, out);
        assertEquals("a quarter turn takes (1,0) to (0,1)", 0, out[0], 1e-12);
        assertEquals(1, out[1], 1e-12);
    }

    @Test
    public void applyIsAPlainOffsetWhenThereIsNoRotation() {
        double[] out = new double[2];
        Transform.translation(2, 3).apply(10, 10, 50, 50, out);
        assertEquals(12, out[0], 0);
        assertEquals(13, out[1], 0);
    }

    @Test
    public void identityAndPurityFlags() {
        assertTrue(Transform.IDENTITY.isPureTranslation());
        assertEquals(0, Transform.IDENTITY.magnitude(), 0);
        assertTrue(Transform.translation(1, 1).isPureTranslation());
        assertFalse(new Transform(0, 0, 1e-9).isPureTranslation());
        assertEquals(5, Transform.translation(3, 4).magnitude(), 1e-12);
        assertEquals(180, new Transform(0, 0, Math.PI).thetaDegrees(), 1e-9);
    }

    @Test
    public void equalityAndHashing() {
        assertEquals(new Transform(1, 2, 3), new Transform(1, 2, 3));
        assertEquals(new Transform(1, 2, 3).hashCode(), new Transform(1, 2, 3).hashCode());
        assertFalse(new Transform(1, 2, 3).equals(new Transform(1, 2, 3.5)));
    }
}
