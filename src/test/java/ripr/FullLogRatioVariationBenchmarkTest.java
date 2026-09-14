/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FullLogRatioVariationBenchmarkTest {
    @Test
    public void frozenMatrixHasSeventyThreeUniqueExecutableArms() {
        List<FullLogRatioVariationBenchmark.Arm> arms =
                FullLogRatioVariationBenchmark.arms();
        Set<String> ids = new HashSet<>();
        for (FullLogRatioVariationBenchmark.Arm arm : arms) ids.add(arm.id);
        assertEquals(73, arms.size());
        assertEquals(73, ids.size());
        assertTrue(ids.contains("035_automatic_filter_selector"));
        assertTrue(ids.contains("scale_025_percent"));
        assertTrue(ids.contains("preprocess_anscombe_gaussian_1_0"));
        assertTrue(ids.contains("mask_none__remove_most_unstable__remove_75"));
        assertTrue(ids.contains("mask_median_3x3__remove_least_informative__remove_25"));
    }
}
