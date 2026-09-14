/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs internal comparison methods into the version-2 folder belonging to each image series. */
public final class BenchmarkV2ComparisonStacks {

    private BenchmarkV2ComparisonStacks() {
    }

    public static void main(String[] args) throws IOException {
        Path project = args.length == 0 ? Paths.get("") : Paths.get(args[0]);
        project = project.toAbsolutePath().normalize();
        Path benchmark = project.resolve("library/benchmark");
        Path manifest = benchmark.resolve("benchmark_v2_series_manifest.csv");
        if (!Files.isRegularFile(manifest)) throw new IOException("missing " + manifest);

        Set<String> requested = requestedSeries();
        if (requested.isEmpty() && !Boolean.getBoolean("ripr.confirmBalancedRun")) {
            throw new IllegalArgumentException("Refusing an accidental all-series run. Set "
                    + "-Dlogratio.onlySeries=id1,id2 or -Dlogratio.confirmBalancedRun=true");
        }
        String requestedCondition = System.getProperty("ripr.onlyCondition", "CLEAN");
        String requestedMotion = System.getProperty("ripr.onlyMotionProfile", "");
        String requestedClass = System.getProperty("ripr.onlyClass", "");
        boolean includeSupplementary = Boolean.getBoolean("ripr.includeSupplementary");
        // A condition other than CLEAN belongs in its own recording tree, not beside the CLEAN one:
        // every summary tool walks a root and aggregates whatever it finds, so two conditions sharing
        // a root would blend into one another's tables and overwrite the CLEAN record on any re-run.
        Path comparisonRoot = project.resolve(System.getProperty("ripr.comparisonRoot",
                "library/benchmark/v2/benchmarks/controlled_motion")).normalize();

        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        int completed = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            String[] row = lines.get(i).split(",", -1);
            String seriesId = row[0];
            if ("LOCAL_EXISTING".equals(row[1])) continue;
            if (!requested.isEmpty() && !requested.contains(seriesId)) continue;
            if (!requestedClass.isEmpty() && !requestedClass.equals(row[3])) continue;
            if (!includeSupplementary && "supplementary".equals(row[4])) continue;

            Path source = benchmark.resolve(row[8]).normalize();
            Path seed = representativeImage(source);
            for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                if (!requestedMotion.isEmpty() && !requestedMotion.equals(motion.name())) continue;
                for (Benchmark.Condition condition : Benchmark.Condition.values()) {
                    if (!requestedCondition.isEmpty()
                            && !requestedCondition.equals(condition.name())) continue;
                    Path output = comparisonRoot
                            .resolve(row[3]).resolve(seriesId).resolve(motion.name())
                            .resolve(condition.name());
                    try {
                        BenchmarkComparisonStacks.writeRecording(
                                seed, seriesId, condition, motion, output);
                        Files.deleteIfExists(output.resolve("run_failure.txt"));
                        completed++;
                    } catch (IOException | RuntimeException error) {
                        Files.createDirectories(output);
                        String message = seriesId + " / " + motion.name() + " / "
                                + condition.name() + ": " + error;
                        Files.write(output.resolve("run_failure.txt"),
                                (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                        failures.add(message);
                        System.err.println("FAILED " + message);
                    }
                }
            }
        }
        System.out.println("completed " + completed + " series-condition runs; failed "
                + failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("benchmark failures:\n" + String.join("\n", failures));
        }
    }

    private static Set<String> requestedSeries() {
        Set<String> requested = new HashSet<>();
        String property = System.getProperty("ripr.onlySeries", "");
        for (String value : property.split(",")) {
            if (!value.trim().isEmpty()) requested.add(value.trim());
        }
        return requested;
    }

    private static Path representativeImage(Path source) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).filter(path -> {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/annotations/")) return false;
                String name = path.getFileName().toString().toLowerCase();
                return name.endsWith(".tif") || name.endsWith(".tiff")
                        || name.endsWith(".png") || name.endsWith(".stk");
            }).forEach(candidates::add);
        }
        if (candidates.isEmpty()) throw new IOException("no image source under " + source);
        candidates.sort(Comparator.comparing(Path::toString));
        return candidates.get(0);
    }
}
