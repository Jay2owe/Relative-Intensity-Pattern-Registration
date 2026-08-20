/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.RegistrationRecipe;
import logratio.core.Transform;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The pure parts of the proxy-versus-oracle rescoring: subset, transform reading, rank correlation. */
public class SweepProxyOracleTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void thePracticalSubsetIsTwentyFourRealRecipes() {
        Set<String> subset = SweepProxyOracleBenchmark.practicalSubset();
        assertEquals(24, subset.size());
        List<String> known = new ArrayList<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            known.add(recipe.id());
        }
        for (String id : subset) {
            assertTrue(id + " is not one of the 96 swept recipes", known.contains(id));
        }
    }

    @Test
    public void thePracticalSubsetFitsOneSweepDialogGrid() {
        assertTrue(SweepProxyOracleBenchmark.practicalSubset().size()
                <= LogRatioSweepPlan.MAX_COMBINATIONS);
    }

    @Test
    public void savedTransformsReadBackAsTheShiftsThatWereWritten() throws IOException {
        Path path = folder.newFile("transforms.csv").toPath();
        Files.write(path, ("frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n"
                + "1,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,ok\n"
                + "2,1.250000,-0.500000,0.010000,1.250000,-0.500000,0.000000,ok\n"
                + "3,2.500000,-1.000000,0.020000,2.500000,-1.000000,0.000000,ok\n")
                .getBytes(StandardCharsets.UTF_8));
        Transform[] read = SweepProxyOracleBenchmark.readTransforms(path);
        assertEquals(3, read.length);
        assertEquals(0.0, read[0].dx, 1e-12);
        assertEquals(1.25, read[1].dx, 1e-12);
        assertEquals(-0.5, read[1].dy, 1e-12);
        assertEquals(0.01, read[1].theta, 1e-12);
        assertEquals(2.5, read[2].dx, 1e-12);
    }

    @Test
    public void aProxyThatOrdersLikeTruthCorrelatesPerfectly() {
        List<double[]> truth = new ArrayList<>();
        List<Double> proxy = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            truth.add(new double[]{i * 0.01});
            proxy.add(i * 3.0 + 1.0);
        }
        assertEquals(1.0, SweepProxyOracleBenchmark.spearman(truth, proxy), 1e-9);
    }

    @Test
    public void aProxyThatOrdersBackwardsCorrelatesNegatively() {
        List<double[]> truth = new ArrayList<>();
        List<Double> proxy = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            truth.add(new double[]{i * 0.01});
            proxy.add(-i * 3.0);
        }
        assertEquals(-1.0, SweepProxyOracleBenchmark.spearman(truth, proxy), 1e-9);
    }

    @Test
    public void tiedProxyScoresShareTheirRankRatherThanBreakingArbitrarily() {
        List<double[]> truth = new ArrayList<>();
        List<Double> proxy = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            truth.add(new double[]{i * 0.01});
            proxy.add(1.0);
        }
        // Every proxy score identical means no ordering information at all, not a spurious ranking.
        assertTrue(Double.isNaN(SweepProxyOracleBenchmark.spearman(truth, proxy)));
    }
}
