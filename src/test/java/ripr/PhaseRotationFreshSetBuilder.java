/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds fresh Phase-only controlled sources after sealed-test-3 was spent. */
public final class PhaseRotationFreshSetBuilder {
    static final String DEVELOPMENT_ROOT =
            "library/benchmark/v2/benchmarks/rotation_phase_development";
    static final String VALIDATION_ROOT =
            "library/benchmark/v2/benchmarks/rotation_phase_validation";

    private PhaseRotationFreshSetBuilder() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Set<String> own = new LinkedHashSet<>();
        own.add(Paths.get(DEVELOPMENT_ROOT).getFileName().toString());
        own.add(Paths.get(VALIDATION_ROOT).getFileName().toString());
        Set<String> spent = SealedTestSetBuilder.spentSeriesNames(project, own);
        for (String species : new String[]{"lysobacter", "pputida", "pveronii", "rahnella"}) {
            build(project, "phase_strack_" + species + "_04", DEVELOPMENT_ROOT, spent);
            build(project, "phase_strack_" + species + "_05", VALIDATION_ROOT, spent);
        }
    }

    private static void build(Path project, String series, String root, Set<String> spent)
            throws IOException {
        if (spent.contains(series)) {
            throw new IllegalStateException(series + " already appears in a benchmark on disk");
        }
        Path source = project.resolve("library/benchmark/v2/supplementary_series")
                .resolve(series).resolve("source/images");
        Path seed = representativeImage(source);
        String previous = System.getProperty("ripr.onlyMethod");
        System.setProperty("ripr.onlyMethod", "__fresh_phase_rotation_input_only__");
        try {
            for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                Path output = project.resolve(root).resolve("PHASE").resolve(series)
                        .resolve(motion.name()).resolve("CLEAN");
                Path target = output.resolve("00_input_uncorrected.tif");
                if (!Files.isRegularFile(target)) {
                    Files.createDirectories(output);
                    BenchmarkComparisonStacks.writeRecording(seed, series,
                            Benchmark.Condition.CLEAN, motion, output);
                }
                System.out.println(series + " / " + motion.name() + " / "
                        + FullSelectorFactorialBenchmark.sha256(Files.readAllBytes(target)));
            }
        } finally {
            if (previous == null) System.clearProperty("ripr.onlyMethod");
            else System.setProperty("ripr.onlyMethod", previous);
        }
    }

    private static Path representativeImage(Path source) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("missing source " + source);
        List<Path> images = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(source)) {
            stream.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".tif") || name.endsWith(".tiff");
            }).forEach(images::add);
        }
        if (images.isEmpty()) throw new IOException("no TIFF under " + source);
        images.sort(Comparator.comparing(Path::toString));
        return images.get(0);
    }
}
