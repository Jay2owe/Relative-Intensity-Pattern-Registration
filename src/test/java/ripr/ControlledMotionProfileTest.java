/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ControlledMotionProfileTest {
    @Test
    public void profilesAreDeterministicDistinctAndRebased() {
        Set<String> traces = new HashSet<>();
        for (ControlledMotionProfile profile : ControlledMotionProfile.values()) {
            int[] x = new int[48];
            int[] y = new int[48];
            profile.fill(x, y);
            assertEquals(0, x[0]);
            assertEquals(0, y[0]);
            String trace = java.util.Arrays.toString(x) + java.util.Arrays.toString(y);
            assertTrue("duplicate controlled motion profile " + profile, traces.add(trace));
            int[] secondX = new int[48];
            int[] secondY = new int[48];
            profile.fill(secondX, secondY);
            assertEquals(trace, java.util.Arrays.toString(secondX)
                    + java.util.Arrays.toString(secondY));
        }
    }
}
