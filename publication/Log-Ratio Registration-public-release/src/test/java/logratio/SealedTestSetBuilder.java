/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the joint sealed test set: ten source series, forty recordings, two per image type.
 *
 * <p>This is the successor to {@link LockedTestSetBuilder}. The locked set has been spent — it was
 * run once against a frozen model and its results have been read — so nothing chosen after that can
 * be validated on it. This set exists to validate the pairwise estimator axis described in
 * {@code docs/pairwise_estimator_axis_plan.md}, and it is the only sealed set being built: the
 * sparse low-light programme was folded into that plan once the mechanism evidence pointed at the
 * estimator rather than at pairing or outlier rejection. See
 * {@code docs/sparse_low_light_gap_findings.md}.
 *
 * <p>Every source is checked against every benchmark on disk that this builder does not itself
 * write, not against the series manifest alone, because the natural-motion benchmark has read
 * series the manifest does not mark as spent. See {@link #spentSeriesNames(Path)}.
 *
 * <p>Three compromises are declared rather than buried, and every one is repeated in the manifest
 * this class writes:
 * <ul>
 *   <li>Both phase sources are Strack fields of view. Every phase record in this project comes from
 *       that one deposit, so a second phase laboratory does not exist in the current inventory.</li>
 *   <li>Both fiducial sources are commercial-microscope bead acquisitions from the same published
 *       record as the development bead series. Different microscope, same laboratory.</li>
 *   <li>The sparse pair are both single-molecule EGFR acquisitions, from different laboratories and
 *       different instruments but similar biology.</li>
 * </ul>
 */
public final class SealedTestSetBuilder {
    static final String ROOT = "library/benchmark/v2/benchmarks/sealed_test";
    static final String NATURAL_ROOT = "library/benchmark/v2/benchmarks/sealed_test_natural";

    /** One sealed-test source. {@code nativePath} is empty when no real time series is available. */
    static final class Source {
        final String imageClass;
        final String seriesId;
        final String independentGroup;
        final String seedPath;
        final String nativePath;
        final NativeSeriesFrames.Layout layout;
        final int firstFrame;
        final int stride;
        final String note;

        Source(String imageClass, String seriesId, String independentGroup, String seedPath,
               String nativePath, NativeSeriesFrames.Layout layout, int firstFrame, int stride,
               String note) {
            this.imageClass = imageClass;
            this.seriesId = seriesId;
            this.independentGroup = independentGroup;
            this.seedPath = seedPath;
            this.nativePath = nativePath;
            this.layout = layout;
            this.firstFrame = firstFrame;
            this.stride = stride;
            this.note = note;
        }
    }

    private SealedTestSetBuilder() {
    }

    /** Chosen before any sealed-test result existed, on independence rather than convenience. */
    static List<Source> sources() {
        List<Source> out = new ArrayList<>();
        out.add(new Source("PHASE", "phase_strack_pputida_02", "strack_pputida_02",
                "v2/supplementary_series/phase_strack_pputida_02/source/images",
                "v2/supplementary_series/phase_strack_pputida_02/source/images",
                NativeSeriesFrames.Layout.IMAGE_SEQUENCE, 1, 1,
                "CAVEAT: second Pseudomonas putida field of view; every phase record in this "
                        + "project comes from the one Strack deposit"));
        out.add(new Source("PHASE", "phase_strack_rahnella_02", "strack_rahnella_02",
                "v2/supplementary_series/phase_strack_rahnella_02/source/images",
                "v2/supplementary_series/phase_strack_rahnella_02/source/images",
                NativeSeriesFrames.Layout.IMAGE_SEQUENCE, 1, 1,
                "CAVEAT: second Rahnella field of view; every phase record in this project comes "
                        + "from the one Strack deposit"));
        out.add(new Source("BRIGHTFIELD_DIC", "brightfield_microbundle_type3_05",
                "microbundle_type3_05",
                "v2/supplementary_series/brightfield_microbundle_type3_05/source",
                "v2/supplementary_series/brightfield_microbundle_type3_05/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "fifth microbundle acquisition; used nowhere in this project"));
        out.add(new Source("BRIGHTFIELD_DIC", "dic_bbbc028_hangar_01", "bbbc028_hangar",
                "v2/supplementary_series/dic_bbbc028_hangar_01/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "differential interference contrast shape absent from development and locked sets; "
                        + "stored as an RGB container whose three channels are identical, so the "
                        + "blue-channel seed is lossless"));
        // Repacked losslessly from the zstd-compressed original, which ImageJ cannot open. The
        // repack was verified pixel-identical; this is the record LockedTestSetBuilder had to skip.
        // No native path: benchmark_v2_download_manifest.csv records this asset as a "dense
        // fluorescence volume", so its 106 planes are focus, not time. Profiling them as a time
        // series measures how fast the specimen goes out of focus and reports it as stage movement,
        // which is why the first build of this set classified it JUMP_DOMINATED__LARGE while every
        // genuine series here came out STATIC__NEAR_STATIC. One plane of it is a perfectly good
        // controlled-motion seed, which is the use the manifest always intended.
        out.add(new Source("DENSE_FLUOR", "dense_aydin_ankrd11", "opencell_leonetti",
                "v2/sealed_test_series/dense_aydin_ankrd11/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "OpenCell field of view, a wholly separate dense record from the development "
                        + "calcium-signalling series; repacked losslessly from zstd TIFF. A volume, "
                        + "not a time series, so it seeds controlled motion only"));
        // The ND2 holds four channels at each of 21 time points, saved channel-interleaved, so a
        // stride of one would hand the natural arm four different channels in a row rather than one
        // channel over time. Stride four selects channel one throughout, as the root-hair source in
        // benchmark_v2_native_series_manifest.csv already does with a stride of two.
        out.add(new Source("DENSE_FLUOR", "dense_watabe_pge2_fret", "watabe_pge2_fret",
                "v2/sealed_test_series/dense_watabe_pge2_fret/source",
                "v2/sealed_test_series/dense_watabe_pge2_fret/source",
                NativeSeriesFrames.Layout.STACK, 1, 4,
                "FRET biosensor acquisition, different laboratory from every other dense record "
                        + "in this project; channel one of four across 21 time points"));
        out.add(new Source("SPARSE_LOWLIGHT", "sparse_takayama_egfr_tirf", "takayama_egfr_tirf",
                "v2/sealed_test_series/sparse_takayama_egfr_tirf/source",
                "v2/sealed_test_series/sparse_takayama_egfr_tirf/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "single-molecule total internal reflection fluorescence, 16-bit raw sensor data; "
                        + "the first genuinely low-light sparse seed in the project"));
        out.add(new Source("SPARSE_LOWLIGHT", "sparse_hiroshima_aisis_singlemol",
                "hiroshima_aisis_singlemol",
                "v2/sealed_test_series/sparse_hiroshima_aisis_singlemol/source",
                "v2/sealed_test_series/sparse_hiroshima_aisis_singlemol/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: automated in-cell single-molecule acquisition, different laboratory from "
                        + "the Takayama pair, but distributed 8-bit rather than at sensor depth"));
        out.add(new Source("FIDUCIAL_STATIC", "fiducial_cage_commercial_d1", "cage_commercial_d1",
                "v2/sealed_test_series/fiducial_cage_commercial_d1/source",
                "v2/sealed_test_series/fiducial_cage_commercial_d1/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: commercial-microscope bead acquisition from the same published record as "
                        + "cage_d1 to cage_d5; different microscope, same laboratory"));
        out.add(new Source("FIDUCIAL_STATIC", "fiducial_cage_commercial_d2", "cage_commercial_d2",
                "v2/sealed_test_series/fiducial_cage_commercial_d2/source",
                "v2/sealed_test_series/fiducial_cage_commercial_d2/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: commercial-microscope bead acquisition from the same published record as "
                        + "cage_d1 to cage_d5; different microscope, same laboratory"));
        return out;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path benchmark = project.resolve("library/benchmark");
        List<Source> sources = sources();
        checkBalance(sources);
        checkNotSpent(project, sources);

        // BenchmarkComparisonStacks writes the injected-motion stack and then runs its own
        // comparison methods. Only the stack is wanted here, so no method name can match.
        String previousMethod = System.getProperty("logratio.onlyMethod");
        System.setProperty("logratio.onlyMethod", "__sealed_test_input_only__");
        int built = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (Source source : sources) {
                Path seed = representativeImage(benchmark.resolve(source.seedPath).normalize());
                for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                    Path recording = project.resolve(ROOT).resolve(source.imageClass)
                            .resolve(source.seriesId).resolve(motion.name()).resolve("CLEAN");
                    if (Files.isRegularFile(recording.resolve("00_input_uncorrected.tif"))
                            && !Boolean.getBoolean("logratio.rewrite")) {
                        built++;
                        continue;
                    }
                    try {
                        Files.createDirectories(recording);
                        BenchmarkComparisonStacks.writeRecording(seed, source.seriesId,
                                Benchmark.Condition.CLEAN, motion, recording);
                        built++;
                        System.out.println("built " + source.seriesId + " / " + motion.name());
                    } catch (IOException | RuntimeException error) {
                        String message = source.seriesId + " / " + motion.name() + ": " + error;
                        failures.add(message);
                        System.err.println("FAILED " + message);
                    }
                }
            }
        } finally {
            if (previousMethod == null) System.clearProperty("logratio.onlyMethod");
            else System.setProperty("logratio.onlyMethod", previousMethod);
        }

        int natural = buildNatural(project, benchmark, sources, failures);
        writeManifest(project, sources);
        System.out.printf(Locale.ROOT,
                "sealed test set: %d controlled recordings, %d natural stacks, %d failures%n",
                built, natural, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("sealed test set failures:\n" + String.join("\n", failures));
        }
    }

    /** Ten sources, two per image type, or the set is not balanced and must not be used. */
    static void checkBalance(List<Source> sources) {
        Map<String, Integer> perClass = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (Source source : sources) {
            if (!ids.add(source.seriesId)) {
                throw new IllegalStateException("duplicate sealed-test series " + source.seriesId);
            }
            if (!groups.add(source.independentGroup)) {
                throw new IllegalStateException(
                        "two sealed-test sources share independent group " + source.independentGroup);
            }
            perClass.merge(source.imageClass, 1, Integer::sum);
        }
        if (sources.size() != 10) {
            throw new IllegalStateException("expected 10 sealed-test sources, got " + sources.size());
        }
        if (perClass.size() != 5) {
            throw new IllegalStateException("expected 5 image types, got " + perClass.size());
        }
        for (Map.Entry<String, Integer> entry : perClass.entrySet()) {
            if (entry.getValue() != 2) {
                throw new IllegalStateException(entry.getKey() + " has " + entry.getValue()
                        + " sealed-test sources; the balanced set needs 2");
            }
        }
    }

    /**
     * Refuses any source whose series already appears in a benchmark on disk. The series manifest is
     * not the authority here: it does not record that the natural-motion benchmark has already read
     * several series, and a sealed set built on material that has been read is not sealed. Directory
     * names under each benchmark are the record of what was actually run.
     */
    static void checkNotSpent(Path project, List<Source> sources) throws IOException {
        Set<String> spent = spentSeriesNames(project);
        List<String> clashes = new ArrayList<>();
        for (Source source : sources) {
            if (spent.contains(source.seriesId)) {
                clashes.add(source.seriesId + " already appears under a benchmark on disk");
            }
        }
        if (!clashes.isEmpty()) {
            throw new IllegalStateException("sealed set would reuse spent material:\n"
                    + String.join("\n", clashes));
        }
    }

    /**
     * Every series name recorded under a benchmark this builder does not own.
     *
     * <p>The list is <b>discovered</b> rather than written down, and that is the whole point. It
     * used to be a hard-coded array of four benchmark names, which was correct on the day it was
     * written and silently wrong afterwards: {@code sealed_test} and {@code sealed_test_natural}
     * were absent, so a successor builder copied from this one would have allowed a third set to
     * reuse the material this one already spent — the exact accident the check exists to prevent.
     * Listing the directory instead means a new benchmark is covered the moment it appears.
     *
     * <p>This builder's own two outputs are excluded, otherwise the check would refuse to let this
     * set be rebuilt from its own recordings. A successor writing to different roots therefore sees
     * {@code sealed_test} in the spent list automatically, which is the desired behaviour.
     *
     * <p>One limit, stated because it is not obvious. Directory names are <i>series</i> names, so
     * this catches a repeated series but not a fresh series drawn from an already-spent independent
     * group — a new field of view from a source that has been read would pass. Three files record
     * something called {@code independent_group} and they disagree: the download manifest holds an
     * asset label, the series manifest holds the grouping used for statistics and is incomplete,
     * and only the manifest each set writes holds the judgement that matters. The group is a
     * judgement about which acquisitions could have failed separately, not a naming convention, so
     * it cannot be derived mechanically. {@code docs/third_sealed_set_material.md} sets out the
     * rule and the evidence for it; the group check remains the reviewer's job.
     */
    static Set<String> spentSeriesNames(Path project) throws IOException {
        Set<String> own = new LinkedHashSet<>();
        own.add(Paths.get(ROOT).getFileName().toString());
        own.add(Paths.get(NATURAL_ROOT).getFileName().toString());
        return spentSeriesNames(project, own);
    }

    /**
     * The same discovery, told which benchmark roots the caller owns. A successor builder passes its
     * own roots and therefore sees this builder's {@code sealed_test} output as spent, which is the
     * behaviour that keeps a third set genuinely sealed.
     */
    static Set<String> spentSeriesNames(Path project, Set<String> own) throws IOException {
        Set<String> spent = new LinkedHashSet<>();
        Path benchmarks = project.resolve("library/benchmark/v2/benchmarks");
        if (!Files.isDirectory(benchmarks)) return spent;
        List<Path> roots = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(benchmarks)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !own.contains(path.getFileName().toString()))
                    .forEach(roots::add);
        }
        for (Path root : roots) {
            try (java.util.stream.Stream<Path> stream = Files.walk(root, 3)) {
                stream.filter(Files::isDirectory).forEach(path -> spent.add(
                        path.getFileName().toString()));
            }
        }
        return spent;
    }

    private static int buildNatural(Path project, Path benchmark, List<Source> sources,
                                    List<String> failures) throws IOException {
        int built = 0;
        for (Source source : sources) {
            if (source.nativePath.isEmpty()) continue;
            Path output = project.resolve(NATURAL_ROOT).resolve(source.imageClass)
                    .resolve(source.seriesId);
            Path target = output.resolve("00_input_native.tif");
            if (Files.isRegularFile(target) && !Boolean.getBoolean("logratio.rewrite")) {
                built++;
                continue;
            }
            try {
                NativeSeriesFrames.Recording recording = NativeSeriesFrames.load(
                        benchmark.resolve(source.nativePath).normalize(), source.layout,
                        source.firstFrame, source.stride, 48, 256);
                Files.createDirectories(output);
                IJ.saveAsTiff(BenchmarkStacks.stack(recording.frames, recording.width,
                        "Native source frames"), target.toString());
                NativeMotionProfiler.Profile profile = NativeMotionProfiler.profile(
                        recording.frames, recording.width);
                Files.write(output.resolve("motion_profile.txt"),
                        (source.seriesId + " natural motion category " + profile.category
                                + System.lineSeparator()
                                + "Residual movement here is stability, never accuracy against "
                                + "unknown truth." + System.lineSeparator())
                                .getBytes(StandardCharsets.UTF_8));
                built++;
                System.out.println("built natural " + source.seriesId + " -> " + profile.category);
            } catch (IOException | RuntimeException error) {
                String message = "natural " + source.seriesId + ": " + error;
                failures.add(message);
                System.err.println("FAILED " + message);
            }
        }
        return built;
    }

    private static void writeManifest(Path project, List<Source> sources) throws IOException {
        Path summary = project.resolve(ROOT).resolve("summaries");
        Files.createDirectories(summary);
        StringBuilder out = new StringBuilder("image_series_class,series_id,independent_group,"
                + "seed_source,native_source,motion_profiles,controlled_recordings,"
                + "input_sha256_curved,note\n");
        for (Source source : sources) {
            Path curved = project.resolve(ROOT).resolve(source.imageClass).resolve(source.seriesId)
                    .resolve("CURVED_OSCILLATING_DRIFT").resolve("CLEAN")
                    .resolve("00_input_uncorrected.tif");
            String checksum = Files.isRegularFile(curved)
                    ? FullSelectorFactorialBenchmark.sha256(Files.readAllBytes(curved)) : "";
            out.append(csv(source.imageClass)).append(',').append(csv(source.seriesId)).append(',')
                    .append(csv(source.independentGroup)).append(',').append(csv(source.seedPath))
                    .append(',').append(csv(source.nativePath)).append(',')
                    .append(csv(String.join(";", motionNames()))).append(',')
                    .append(ControlledMotionProfile.values().length).append(',')
                    .append(csv(checksum)).append(',').append(csv(source.note)).append('\n');
        }
        Files.write(summary.resolve("sealed_test_manifest.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> motionNames() {
        List<String> out = new ArrayList<>();
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) out.add(motion.name());
        return out;
    }

    private static Path representativeImage(Path source) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("missing sealed-test source " + source);
        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).filter(path -> {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/annotations/")) return false;
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".tif") || name.endsWith(".tiff")
                        || name.endsWith(".png") || name.endsWith(".stk");
            }).forEach(candidates::add);
        }
        if (candidates.isEmpty()) throw new IOException("no image source under " + source);
        candidates.sort(Comparator.comparing(Path::toString));
        return candidates.get(0);
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }
}
