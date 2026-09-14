/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.AutomaticRegistrationSelector;
import ripr.api.RegistrationRecipe;
import ripr.core.Transform;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingAdaptiveSelectorBenchmarkTest {
    @Test
    public void frozenDevelopmentSourcesProduceElevenCasesEach() throws Exception {
        List<RecordingAdaptiveSelectorBenchmark.Source> sources =
                RecordingAdaptiveSelectorBenchmark.sources(Paths.get("").toAbsolutePath(),
                        "development");
        assertEquals(25, sources.size());
        assertEquals(275, sources.size() * 11);
    }

    @Test
    public void candidateManifestAndCodeExposeTheFrozenCount() {
        assertEquals(128, RegistrationRecipe.allCandidates().size());
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.allCandidates()) ids.add(recipe.id());
        assertEquals(128, ids.size());
    }

    @Test
    public void featureAndOutcomeSchemasArePhysicallySeparated() {
        String feature = RecordingAdaptiveSelectorBenchmark.featureHeader()
                .toLowerCase(java.util.Locale.ROOT);
        assertFalse(feature.contains("truth"));
        assertFalse(feature.contains("error"));
        assertFalse(feature.contains("winner"));
        assertFalse(feature.contains("oracle"));
        assertFalse(feature.contains("recipe"));
        assertTrue(feature.contains("feature_contract_version"));
        assertTrue(RecordingAdaptiveSelectorBenchmark.OUTCOME_HEADER.contains(
                "median_warp_central50_px"));
        assertFalse(RecordingAdaptiveSelectorBenchmark.OUTCOME_HEADER.contains(
                AutomaticRegistrationSelector.FEATURE_NAMES[0]));
        assertEquals("case_id,pilot_seconds,evidence_seconds,selector_seconds,"
                        + "timing_contract_version",
                RecordingAdaptiveSelectorBenchmark.TIMING_HEADER);
        assertEquals("complete_selector_timing_v2",
                RecordingAdaptiveSelectorBenchmark.TIMING_CONTRACT_VERSION);
    }

    @Test
    public void zeroRotationWarpingIndexIsTranslationError() {
        Transform truth = new Transform(1.25, -0.75, 0);
        Transform measured = new Transform(1.5, -0.5, 0);
        double warping = ThevenazProtocolBenchmark.warpingIndex(
                ThevenazProtocolBenchmark.Affine2D.rigid(truth, 64, 64),
                ThevenazProtocolBenchmark.Affine2D.rigid(measured, 64, 64), 64, 64,
                ThevenazProtocolBenchmark.Region.CENTRAL_50);
        assertEquals(Math.hypot(measured.dx - truth.dx, measured.dy - truth.dy),
                warping, 1e-12);
    }
}
