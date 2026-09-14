/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.RegistrationRecipe;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Sweep cardinality, resume behaviour, artifact auditing and source-group separation. */
public class FullSelectorSweepTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The candidate space is two axes now: 96 log-ratio recipes over support, band, filter and mask,
     * and 16 estimator candidates over band and filter. Support and mask are settings of the
     * log-ratio fit, so the estimator axis holds them neutral rather than sweeping them.
     */
    @Test
    public void theSweepEnumeratesOneHundredAndTwentyEightUniqueRecipes() {
        List<FullSelectorFactorialBenchmark.Recipe> recipes =
                FullSelectorFactorialBenchmark.recipes();
        assertEquals(128, recipes.size());
        Set<String> identifiers = new LinkedHashSet<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : recipes) {
            assertTrue("duplicate " + recipe.id, identifiers.add(recipe.id));
        }
        // 96 log-ratio recipes, then the same band-by-filter grid once per area
        // estimator: grid refinement and Gauss-Newton refinement.
        assertEquals(3 * 4 * 4 * 2 + 4 * 4 + 4 * 4, recipes.size());
    }

    @Test
    public void theSweepAndTheProductionApiAgreeOnTheCandidateSet() {
        Set<String> sweep = new LinkedHashSet<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : FullSelectorFactorialBenchmark.recipes()) {
            sweep.add(recipe.id);
        }
        Set<String> production = new LinkedHashSet<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.allCandidates()) {
            production.add(recipe.id());
        }
        assertEquals("the benchmark and the plugin must name recipes identically", sweep, production);
    }

    @Test
    public void anIncompleteFolderIsRerunAndACompleteOneIsResumed() throws IOException {
        Path output = folder.newFolder("recipe").toPath();
        assertFalse("an empty folder is not complete",
                FullSelectorFactorialBenchmark.complete(output, true));

        Files.write(output.resolve("comparison.csv"), ("wrong,header\nrow\n")
                .getBytes(StandardCharsets.UTF_8));
        assertFalse("a corrupt comparison header is not complete",
                FullSelectorFactorialBenchmark.complete(output, true));

        writeComparison(output, "ok");
        assertFalse("a result without its transforms is not complete",
                FullSelectorFactorialBenchmark.complete(output, true));

        Files.write(output.resolve("transforms.csv"), "frame\n".getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("settings.csv"), "setting,value\n".getBytes(StandardCharsets.UTF_8));
        assertFalse("a result without its corrected stack is not complete",
                FullSelectorFactorialBenchmark.complete(output, true));
        assertTrue("the same result is complete when images were not requested",
                FullSelectorFactorialBenchmark.complete(output, false));

        Files.write(output.resolve("corrected.tif"), new byte[]{0});
        assertTrue(FullSelectorFactorialBenchmark.complete(output, true));
    }

    @Test
    public void arecordedFailureIsNotSilentlyRetried() throws IOException {
        Path output = folder.newFolder("failed").toPath();
        writeComparison(output, "failed");
        Files.write(output.resolve("run_failure.txt"), "boom\n".getBytes(StandardCharsets.UTF_8));
        assertTrue("a recorded failure is a complete, explicit result",
                FullSelectorFactorialBenchmark.complete(output, true));
    }

    @Test
    public void theArtifactAuditNamesEveryStructuralGap() throws IOException {
        Path root = folder.newFolder("controlled").toPath();
        Path summary = root.resolve("summaries").resolve(FullSelectorFactorialBenchmark.RUN_ID);
        Files.createDirectories(summary);
        Path recordingFolder = root.resolve("PHASE/series_01/SUBPIXEL_RANDOM_WALK/CLEAN");
        Files.createDirectories(recordingFolder);
        FullSelectorFactorialBenchmark.Recording recording =
                new FullSelectorFactorialBenchmark.Recording("PHASE", "series_01",
                        "SUBPIXEL_RANDOM_WALK", "CLEAN", recordingFolder,
                        recordingFolder.resolve("00_input_uncorrected.tif"), 48, "group_01",
                        "sha", 1024);
        List<FullSelectorFactorialBenchmark.Recipe> recipes =
                FullSelectorFactorialBenchmark.recipes().subList(0, 2);

        List<String> errors = FullSelectorFactorialBenchmark.audit(root, summary,
                Arrays.asList(recording), recipes, true);
        assertEquals("both recipes are missing everything", 2, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("missing comparison.csv"));

        Path complete = recordingFolder.resolve(FullSelectorFactorialBenchmark.RUN_ID)
                .resolve(recipes.get(0).id);
        Files.createDirectories(complete);
        writeComparison(complete, "ok");
        Files.write(complete.resolve("transforms.csv"), "frame\n".getBytes(StandardCharsets.UTF_8));
        Files.write(complete.resolve("settings.csv"), "setting,value\n".getBytes(StandardCharsets.UTF_8));
        Files.write(complete.resolve("corrected.tif"), new byte[]{0});

        errors = FullSelectorFactorialBenchmark.audit(root, summary,
                Arrays.asList(recording), recipes, true);
        assertEquals("only the untouched recipe should remain", 1, errors.size());
        assertTrue(Files.isRegularFile(summary.resolve("artifact_audit.csv")));
        String audit = new String(Files.readAllBytes(summary.resolve("artifact_audit.csv")),
                StandardCharsets.UTF_8);
        assertTrue(audit, audit.contains(recipes.get(0).id));
        assertTrue(audit, audit.contains(recipes.get(1).id));
    }

    @Test
    public void aFoldWithholdsEveryMovementVersionOfOneSource() {
        List<FullSelectorTraining.Case> cases = new ArrayList<>();
        String[] motions = {"CURVED_OSCILLATING_DRIFT", "STEADY_DIRECTIONAL_DRIFT",
                "SUBPIXEL_RANDOM_WALK", "INTERMITTENT_JUMPS"};
        for (String series : new String[]{"series_01", "series_02", "series_03"}) {
            for (String motion : motions) {
                cases.add(syntheticCase(series, motion, "group_" + series, 0.02));
            }
        }
        List<FullSelectorTraining.Case> training =
                FullSelectorTraining.trainingFor(cases, "series_02");
        List<FullSelectorTraining.Case> testing =
                FullSelectorTraining.testingFor(cases, "series_02");
        assertEquals(4, testing.size());
        assertEquals(8, training.size());
        for (FullSelectorTraining.Case one : training) {
            assertFalse("no training row may come from the held-out source",
                    "series_02".equals(one.recording.series));
        }
        Set<String> withheldMotions = new LinkedHashSet<>();
        for (FullSelectorTraining.Case one : testing) withheldMotions.add(one.recording.motion);
        assertEquals("all four movement versions must be withheld together", 4,
                withheldMotions.size());
    }

    @Test
    public void aRecipeNeedsTwoIndependentSourcesToStayACandidate() {
        List<String> recipeIds = new ArrayList<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : FullSelectorFactorialBenchmark.recipes()) {
            recipeIds.add(recipe.id);
        }
        String lucky = recipeIds.get(5);
        String consistent = recipeIds.get(9);

        List<FullSelectorTraining.Case> cases = new ArrayList<>();
        // One source where only `lucky` wins, and two independent sources where `consistent` wins.
        cases.add(caseWithWinner("series_01", "group_01", recipeIds, lucky));
        cases.add(caseWithWinner("series_02", "group_02", recipeIds, consistent));
        cases.add(caseWithWinner("series_03", "group_03", recipeIds, consistent));
        cases.add(caseWithWinner("series_04", "group_04", recipeIds, consistent));

        List<FullSelectorTraining.Candidate> retainedCandidates =
                FullSelectorTraining.prune(cases, recipeIds,
                        FullSelectorTraining.Family.LINEAR_GAIN);
        List<String> retained = new ArrayList<>();
        for (FullSelectorTraining.Candidate candidate : retainedCandidates) {
            retained.add(candidate.recipeId);
        }
        assertTrue("a recipe backed by two independent sources is retained",
                retained.contains(consistent));
        assertFalse("one lucky recording must not create a production branch",
                retained.contains(lucky));
    }

    private static FullSelectorTraining.Case caseWithWinner(String series, String group,
                                                            List<String> recipeIds, String winner) {
        Map<String, FullSelectorTraining.Outcome> outcomes = new LinkedHashMap<>();
        for (String id : recipeIds) {
            // Losers are mildly worse, not catastrophic, so the per-recording safety guard cannot
            // remove them and the two-independent-source rule is the only thing under test.
            double median = id.equals(winner) ? 0.010 : 0.105;
            outcomes.put(id, new FullSelectorTraining.Outcome(true, median, median, 1.0));
        }
        return new FullSelectorTraining.Case(recording(series, "SUBPIXEL_RANDOM_WALK", group),
                new double[ripr.api.AutomaticRegistrationSelector.FEATURE_COUNT], outcomes,
                new FullSelectorTraining.Outcome(true, 0.100, 0.200, 1.0));
    }

    private static FullSelectorTraining.Case syntheticCase(String series, String motion,
                                                           String group, double median) {
        Map<String, FullSelectorTraining.Outcome> outcomes = new LinkedHashMap<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe : FullSelectorFactorialBenchmark.recipes()) {
            outcomes.put(recipe.id, new FullSelectorTraining.Outcome(true, median, median, 1.0));
        }
        return new FullSelectorTraining.Case(recording(series, motion, group),
                new double[ripr.api.AutomaticRegistrationSelector.FEATURE_COUNT], outcomes,
                new FullSelectorTraining.Outcome(true, median, median, 1.0));
    }

    private static FullSelectorFactorialBenchmark.Recording recording(String series, String motion,
                                                                      String group) {
        Path folder = java.nio.file.Paths.get("PHASE", series, motion, "CLEAN");
        return new FullSelectorFactorialBenchmark.Recording("PHASE", series, motion, "CLEAN",
                folder, folder.resolve("00_input_uncorrected.tif"), 48, group, "sha", 1024);
    }

    private static void writeComparison(Path output, String status) throws IOException {
        String row = "\"controlled\",\"PHASE\",\"series_01\",\"SUBPIXEL_RANDOM_WALK\",\"CLEAN\","
                + "\"recipe\",\"ALL\",\"full\",\"NONE\",\"none\"," + status
                + ",48,0.01,0.02,0.03,1.0,2.0,\"detail\"";
        Files.write(output.resolve("comparison.csv"),
                (FullSelectorFactorialBenchmark.RESULT_HEADER + "\n" + row + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }
}
