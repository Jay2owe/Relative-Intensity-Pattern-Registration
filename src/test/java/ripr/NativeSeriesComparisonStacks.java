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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs the internal comparison methods on genuine source sequences grouped by observed movement. */
public final class NativeSeriesComparisonStacks {
    private NativeSeriesComparisonStacks() { }

    public static void main(String[] args) throws IOException {
        Path project = args.length == 0 ? Paths.get("") : Paths.get(args[0]);
        project = project.toAbsolutePath().normalize();
        Path benchmark = project.resolve("library/benchmark");
        Path manifest = benchmark.resolve("benchmark_v2_native_series_manifest.csv");
        if (!Files.isRegularFile(manifest)) throw new IOException("missing " + manifest);

        Set<String> requested = requestedSeries();
        if (requested.isEmpty() && !Boolean.getBoolean("ripr.confirmBalancedRun")) {
            throw new IllegalArgumentException("Refusing an accidental all-series native run. Set "
                    + "-Dlogratio.onlySeries=id1,id2 or -Dlogratio.confirmBalancedRun=true");
        }
        String requestedClass = System.getProperty("ripr.onlyClass", "");
        boolean profileOnly = Boolean.getBoolean("ripr.profileOnly");

        List<String> failures = new ArrayList<>();
        int completed = 0;
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).trim().isEmpty()) continue;
            String[] row = lines.get(line).split(",", -1);
            if (row.length != 11) throw new IOException("bad native manifest row " + (line + 1));
            String seriesId = row[0];
            String imageClass = row[1];
            if (!"ready".equals(row[9])) continue;
            if (!requested.isEmpty() && !requested.contains(seriesId)) continue;
            if (!requestedClass.isEmpty() && !requestedClass.equals(imageClass)) continue;

            Path source = benchmark.resolve(row[3]).normalize();
            try {
                NativeSeriesFrames.Recording recording = NativeSeriesFrames.load(source,
                        NativeSeriesFrames.Layout.valueOf(row[4]), Integer.parseInt(row[5]),
                        Integer.parseInt(row[6]), Integer.parseInt(row[7]),
                        Integer.parseInt(row[8]));
                NativeMotionProfiler.Profile profile = NativeMotionProfiler.profile(
                        recording.frames, recording.width);
                Path output = benchmark.resolve("v2/benchmarks/natural_motion")
                        .resolve(imageClass).resolve(profile.category).resolve(seriesId);
                NativeMotionProfiler.write(output, profile, recording);
                if (!profileOnly) {
                    BenchmarkComparisonStacks.writeNativeRecording(seriesId, recording.frames,
                            recording.width, profile, output);
                }
                Files.deleteIfExists(output.resolve("run_failure.txt"));
                completed++;
                System.out.println(seriesId + " -> " + profile.category);
            } catch (IOException | RuntimeException error) {
                String message = seriesId + ": " + error;
                failures.add(message);
                System.err.println("FAILED " + message);
            }
        }
        System.out.println("native completed " + completed + "; failed " + failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("native benchmark failures:\n" + String.join("\n", failures));
        }
    }

    private static Set<String> requestedSeries() {
        Set<String> requested = new HashSet<>();
        for (String value : System.getProperty("ripr.onlySeries", "").split(",")) {
            if (!value.trim().isEmpty()) requested.add(value.trim());
        }
        return requested;
    }
}
