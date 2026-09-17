/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The guard that stops a sealed test set being built on material that has already been read.
 *
 * <p>A sealed set is only worth anything if nothing in development has touched its sources. The
 * guard used to consult a hard-coded list of four benchmark names, which omitted the two the sealed
 * set itself writes. That was invisible while only one sealed set existed, and would have become a
 * silent reuse the moment a successor builder was copied from it — the successor would have scanned
 * the same four names and cheerfully re-spent everything the first sealed set consumed.
 *
 * <p>These tests pin the fixed behaviour: the benchmark list is discovered from the directory, so a
 * benchmark nobody remembered to name is still covered, while the builder's own output is excluded
 * so it can still rebuild its own set.
 */
public class SpentMaterialGuardTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path benchmarks() throws IOException {
        return folder.getRoot().toPath().resolve("library/benchmark/v2/benchmarks");
    }

    private void series(String benchmark, String imageClass, String seriesId) throws IOException {
        Files.createDirectories(benchmarks().resolve(benchmark).resolve(imageClass).resolve(seriesId));
    }

    @Test
    public void aSeriesUnderAnyBenchmarkCountsAsSpent() throws IOException {
        series("controlled_motion", "DENSE_FLUOR", "dense_ssbd197_fig3");
        series("natural_motion", "PHASE", "phase_strack_pputida_02");

        Set<String> spent = SealedTestSetBuilder.spentSeriesNames(folder.getRoot().toPath());

        assertTrue("a controlled-motion series is spent", spent.contains("dense_ssbd197_fig3"));
        assertTrue("a natural-motion series is spent", spent.contains("phase_strack_pputida_02"));
    }

    /**
     * The regression this class exists for. Before the fix the benchmark list was
     * {@code {controlled_motion, locked_test, locked_test_natural, natural_motion}}, so a series
     * that existed only under {@code sealed_test} was not spent as far as the guard was concerned.
     */
    @Test
    public void aSeriesUsedOnlyByAPreviousSealedSetIsStillSpentForASuccessor() throws IOException {
        series("sealed_test", "DENSE_FLUOR", "dense_watabe_pge2_fret");
        series("sealed_test_natural", "DENSE_FLUOR", "dense_watabe_pge2_fret");

        // A successor builder writes elsewhere, so sealed_test is not its own output.
        Set<String> ownRoots = new LinkedHashSet<>();
        ownRoots.add("sealed_test_3");
        ownRoots.add("sealed_test_3_natural");
        Set<String> spentForSuccessor =
                SealedTestSetBuilder.spentSeriesNames(folder.getRoot().toPath(), ownRoots);

        assertTrue("material spent by the second sealed set must be refused to a third",
                spentForSuccessor.contains("dense_watabe_pge2_fret"));
    }

    @Test
    public void aBuilderDoesNotTreatItsOwnOutputAsSpent() throws IOException {
        series("sealed_test", "DENSE_FLUOR", "dense_watabe_pge2_fret");

        Set<String> spent = SealedTestSetBuilder.spentSeriesNames(folder.getRoot().toPath());

        assertFalse("this builder must still be able to rebuild its own set",
                spent.contains("dense_watabe_pge2_fret"));
    }

    @Test
    public void aBenchmarkNobodyNamedIsCoveredAnyway() throws IOException {
        series("controlled_motion_gain_fade", "DENSE_FLUOR", "dense_ssbd197_fig4b_lck");
        series("some_future_benchmark", "PHASE", "phase_from_a_benchmark_not_yet_invented");

        Set<String> spent = SealedTestSetBuilder.spentSeriesNames(folder.getRoot().toPath());

        assertTrue("gain-fade was never in the hard-coded list",
                spent.contains("dense_ssbd197_fig4b_lck"));
        assertTrue("a benchmark added later is covered without editing this class",
                spent.contains("phase_from_a_benchmark_not_yet_invented"));
    }

    @Test
    public void anEmptyTreeIsNotAnError() throws IOException {
        assertTrue(SealedTestSetBuilder.spentSeriesNames(folder.getRoot().toPath()).isEmpty());
    }
}
