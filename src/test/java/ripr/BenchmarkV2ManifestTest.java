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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevents a plentiful image type or repeated experiment from dominating benchmark version 2. */
public class BenchmarkV2ManifestTest {

    private static final Path POLICY = Paths.get(
            "library", "benchmark", "benchmark_v2_balance_policy.csv");
    private static final Path SERIES = Paths.get(
            "library", "benchmark", "benchmark_v2_series_manifest.csv");
    private static final Path METHODS = Paths.get(
            "library", "benchmark", "benchmark_v2_method_manifest.csv");
    private static final Path NATIVE = Paths.get(
            "library", "benchmark", "benchmark_v2_native_series_manifest.csv");
    private static final Path CONTROLLED_MOTION = Paths.get(
            "library", "benchmark", "benchmark_v2_controlled_motion_manifest.csv");

    @Test
    public void policyHasFiveEqualClassesAndBalancedSplits() throws IOException {
        List<String[]> rows = rows(POLICY, 9);
        assertEquals(5, rows.size());
        Set<String> classes = new HashSet<>();
        double headline = 0;
        for (String[] row : rows) {
            assertTrue("duplicate class " + row[0], classes.add(row[0]));
            headline += Double.parseDouble(row[2]);
            assertEquals(0.20, Double.parseDouble(row[2]), 0);
            assertEquals(1.0, Double.parseDouble(row[3])
                    + Double.parseDouble(row[4]) + Double.parseDouble(row[5]), 1e-12);
            assertTrue(Integer.parseInt(row[6]) >= 4);
            assertTrue(Integer.parseInt(row[7]) >= 4);
        }
        assertEquals(1.0, headline, 1e-12);
    }

    @Test
    public void headlineSeriesAreLicensedBalancedAndDoNotLeakAcrossSplits() throws IOException {
        Set<String> classes = new HashSet<>();
        for (String[] row : rows(POLICY, 9)) classes.add(row[0]);

        Map<String, String> groupSplit = new HashMap<>();
        Set<String> allowedSplits = new HashSet<>();
        allowedSplits.add("development");
        allowedSplits.add("validation");
        allowedSplits.add("locked_test");
        allowedSplits.add("supplementary");
        allowedSplits.add("unassigned");
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        counts.put("development", new HashMap<String, Integer>());
        counts.put("validation", new HashMap<String, Integer>());
        counts.put("locked_test", new HashMap<String, Integer>());
        for (String imageClass : classes) {
            for (Map<String, Integer> split : counts.values()) split.put(imageClass, 0);
        }

        for (String[] row : rows(SERIES, 10)) {
            String source = row[1];
            String group = source + "/" + row[2];
            String imageClass = row[3];
            String split = row[4];
            boolean headlineEligible = Boolean.parseBoolean(row[5]);
            boolean licenceVerified = Boolean.parseBoolean(row[6]);
            assertTrue("unknown class " + imageClass, classes.contains(imageClass));
            assertTrue("unknown split " + split, allowedSplits.contains(split));
            if (counts.containsKey(split)) {
                String previous = groupSplit.put(group, split);
                assertTrue("experiment appears in both " + previous + " and " + split,
                        previous == null || previous.equals(split));
            }
            if (headlineEligible) {
                assertTrue("unverified licence for " + row[0], licenceVerified);
                assertTrue("headline series cannot be " + split, counts.containsKey(split));
                Map<String, Integer> byClass = counts.get(split);
                byClass.put(imageClass, byClass.get(imageClass) + 1);
            }
        }

        for (Map.Entry<String, Map<String, Integer>> split : counts.entrySet()) {
            Set<Integer> distinct = new HashSet<>(split.getValue().values());
            assertEquals("headline class counts differ in " + split.getKey()
                    + ": " + split.getValue(), 1, distinct.size());
        }
        assertFalse(classes.isEmpty());
    }

    @Test
    public void allThirtyTwoMethodsHaveStableNumbersAndPerSeriesFolders() throws IOException {
        List<String[]> methods = rows(METHODS, 6);
        assertEquals(32, methods.size());
        Set<String> numbers = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < methods.size(); i++) {
            String expected = String.format("%02d", i + 1);
            assertEquals(expected, methods.get(i)[0]);
            assertTrue("duplicate method number " + expected, numbers.add(methods.get(i)[0]));
            assertTrue("duplicate method id " + methods.get(i)[1], ids.add(methods.get(i)[1]));
            if ("alias".equals(methods.get(i)[4])) {
                assertTrue("unknown alias target " + methods.get(i)[5], ids.contains(methods.get(i)[5]));
            }
        }

        for (String[] series : rows(SERIES, 10)) {
            if ("LOCAL_EXISTING".equals(series[1])) continue;
            Path folder = Paths.get("library", "benchmark").resolve(series[8]).getParent();
            assertTrue("missing source folder for " + series[0], Files.isDirectory(folder.resolve("source")));
            assertTrue("missing comparisons folder for " + series[0],
                    Files.isDirectory(folder.resolve("comparisons")));
            assertTrue("missing series metadata for " + series[0],
                    existsAsFile(folder.resolve("series.csv")));
            assertTrue("missing method list for " + series[0],
                    existsAsFile(folder.resolve("comparisons").resolve("methods_to_run.csv")));
        }
    }

    /** Dropbox placeholders are files but Java reports their Windows reparse points as non-regular. */
    private static boolean existsAsFile(Path path) {
        return Files.exists(path) && !Files.isDirectory(path);
    }

    @Test
    public void twoTracksAreBalancedAndUseDeclaredMotionSources() throws IOException {
        List<String[]> nativeRows = rows(NATIVE, 11);
        Map<String, Integer> byClass = new HashMap<>();
        Set<String> groups = new HashSet<>();
        for (String[] row : nativeRows) {
            assertEquals("native series must be prepared before benchmarking", "ready", row[9]);
            byClass.put(row[1], byClass.containsKey(row[1]) ? byClass.get(row[1]) + 1 : 1);
            assertTrue("duplicate native independent group " + row[2], groups.add(row[2]));
            assertTrue("native source path must exist for " + row[0],
                    Files.isDirectory(Paths.get("library", "benchmark").resolve(row[3])));
            assertTrue("native layout must preserve a source sequence",
                    "STACK".equals(row[4]) || "IMAGE_SEQUENCE".equals(row[4]));
        }
        assertEquals(5, byClass.size());
        for (Integer count : byClass.values()) assertEquals(Integer.valueOf(4), count);

        List<String[]> motionRows = rows(CONTROLLED_MOTION, 5);
        assertEquals(4, motionRows.size());
        Set<String> profiles = new HashSet<>();
        for (String[] row : motionRows) {
            assertTrue(profiles.add(row[0]));
            assertEquals("48", row[1]);
            assertEquals("quarter_pixels", row[2]);
        }
    }

    private static List<String[]> rows(Path path, int columns) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertFalse("empty manifest " + path, lines.isEmpty());
        List<String[]> parsed = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            String[] row = lines.get(i).split(",", -1);
            assertEquals("column count at " + path + ":" + (i + 1), columns, row.length);
            parsed.add(row);
        }
        return parsed;
    }
}
