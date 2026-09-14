/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Guards the A000 instrumentation without running the large external input during Maven tests. */
public class RigidRotationStackA000Test {
    @Test
    public void progressRecorderCapturesActivityBeforeFirstCompletion() {
        RigidRotationStackA000.EventProgress progress =
                new RigidRotationStackA000.EventProgress();
        progress.phase("Estimating automatic shift bound", 0, 3);
        progress.phase("Estimating automatic shift bound", 3, 3);
        progress.begin(8, 2);
        progress.taskStarted(0, 8);
        progress.taskStarted(1, 8);
        progress.update(1, 8);
        assertTrue(progress.firstTaskMs() >= 0);
        assertTrue(progress.firstCompletionMs() >= progress.firstTaskMs());
        assertEquals(6, progress.size());
    }

    @Test
    public void finitePercentileIgnoresMissingFits() {
        double value = RigidRotationStackA000.percentileFinite(
                new double[]{Double.NaN, 1, 4, 2, Double.POSITIVE_INFINITY}, 0.5);
        assertEquals(2.0, value, 0.0);
    }
}
