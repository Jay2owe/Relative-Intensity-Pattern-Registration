/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The repair pass, which exists because one bad pair otherwise corrupts every later frame in any
 * chained registration — silently, with nothing in the output to say so.
 */
public class ChainRepairTest {

    private static Transform[] ramp(int n, double step) {
        Transform[] a = new Transform[n];
        for (int i = 0; i < n; i++) a[i] = Transform.translation(i * step, 0);
        return a;
    }

    private static int[] allSupported(int n) {
        int[] s = new int[n];
        java.util.Arrays.fill(s, 1);
        return s;
    }

    @Test
    public void leavesAGoodChainAlone() {
        Transform[] cum = ramp(10, 1.0);
        ChainRepair.Result r = ChainRepair.repair(cum, allSupported(10), 0,
                ChainRepair.DEFAULT_OUTLIER_MADS);
        assertEquals(0, r.repairedCount);
        for (int t = 0; t < 10; t++) {
            assertEquals(cum[t].dx, r.cumulative[t].dx, 0);
            assertNull("frame " + t, r.reasons[t]);
        }
    }

    @Test
    public void interpolatesAnUnsupportedFrame() {
        Transform[] cum = ramp(10, 1.0);
        cum[4] = Transform.IDENTITY;              // the value is meaningless
        int[] support = allSupported(10);
        support[4] = 0;
        ChainRepair.Result r = ChainRepair.repair(cum, support, 0, 0);
        assertEquals(1, r.repairedCount);
        assertEquals(ChainRepair.Reason.UNSUPPORTED, r.reasons[4]);
        assertEquals("interpolated between 3 and 5", 4.0, r.cumulative[4].dx, 1e-12);
    }

    @Test
    public void interpolatesAcrossARunOfUnsupportedFrames() {
        Transform[] cum = ramp(12, 2.0);
        int[] support = allSupported(12);
        for (int t = 4; t <= 7; t++) {
            support[t] = 0;
            cum[t] = Transform.IDENTITY;
        }
        ChainRepair.Result r = ChainRepair.repair(cum, support, 0, 0);
        assertEquals(4, r.repairedCount);
        for (int t = 4; t <= 7; t++) {
            assertEquals("frame " + t, t * 2.0, r.cumulative[t].dx, 1e-9);
        }
    }

    /** A leading or trailing gap has nothing to interpolate between, so it holds the nearest value. */
    @Test
    public void holdsTheNearestValueAtTheEnds() {
        Transform[] cum = ramp(8, 1.0);
        int[] support = allSupported(8);
        support[0] = 0;
        support[7] = 0;
        cum[0] = Transform.translation(999, 0);
        cum[7] = Transform.translation(-999, 0);
        // frame 0 is the anchor and is trusted by definition, so only the trailing gap is repaired
        ChainRepair.Result r = ChainRepair.repair(cum, support, 0, 0);
        assertEquals(1, r.repairedCount);
        assertEquals("the anchor keeps its own value", 999, r.cumulative[0].dx, 0);
        assertEquals("the trailing gap holds frame 6", 6.0, r.cumulative[7].dx, 1e-12);
    }

    @Test
    public void replacesAGrossOutlierStep() {
        Transform[] cum = ramp(20, 1.0);
        cum[10] = Transform.translation(500, 0);      // an alignment failure, not motion
        for (int t = 11; t < 20; t++) cum[t] = Transform.translation(t, 0);
        ChainRepair.Result r = ChainRepair.repair(cum, allSupported(20), 0,
                ChainRepair.DEFAULT_OUTLIER_MADS);
        assertEquals(ChainRepair.Reason.OUTLIER_STEP, r.reasons[10]);
        assertEquals("interpolated back onto the ramp", 10.0, r.cumulative[10].dx, 1e-9);
    }

    /**
     * The threshold is deliberately loose. Erasing genuine fast motion would be worse than missing a
     * marginal failure, because fast motion is often the thing being measured.
     */
    @Test
    public void doesNotEraseGenuineFastMotion() {
        Transform[] cum = new Transform[20];
        double x = 0;
        for (int t = 0; t < 20; t++) {
            x += (t >= 8 && t <= 11) ? 4.0 : 1.0;      // a burst of four times the usual speed
            cum[t] = Transform.translation(x, 0);
        }
        ChainRepair.Result r = ChainRepair.repair(cum, allSupported(20), 0,
                ChainRepair.DEFAULT_OUTLIER_MADS);
        assertEquals("a 4x burst is motion, not failure", 0, r.repairedCount);
    }

    /** Too short to estimate a scale means no outlier detection, not a spurious one. */
    @Test
    public void skipsOutlierDetectionOnVeryShortSequences() {
        Transform[] cum = {Transform.IDENTITY, Transform.translation(100, 0)};
        ChainRepair.Result r = ChainRepair.repair(cum, allSupported(2), 0,
                ChainRepair.DEFAULT_OUTLIER_MADS);
        assertEquals(0, r.repairedCount);
    }

    @Test
    public void survivesEverythingBeingUnsupported() {
        Transform[] cum = ramp(5, 1.0);
        ChainRepair.Result r = ChainRepair.repair(cum, new int[5], -1, 0);
        assertEquals(5, r.repairedCount);
        for (Transform t : r.cumulative) {
            assertTrue("nothing to interpolate from, so the identity", t.magnitude() == 0);
        }
    }

    @Test
    public void doesNotModifyTheInput() {
        Transform[] cum = ramp(10, 1.0);
        Transform original = cum[4];
        int[] support = allSupported(10);
        support[4] = 0;
        ChainRepair.repair(cum, support, 0, 0);
        assertEquals("the caller's array must be untouched", original, cum[4]);
    }

    @Test
    public void lerpIsLinear() {
        Transform a = new Transform(0, 0, 0);
        Transform b = new Transform(10, 20, 0.4);
        Transform mid = ChainRepair.lerp(a, b, 0.25);
        assertEquals(2.5, mid.dx, 1e-12);
        assertEquals(5.0, mid.dy, 1e-12);
        assertEquals(0.1, mid.theta, 1e-12);
    }
}
