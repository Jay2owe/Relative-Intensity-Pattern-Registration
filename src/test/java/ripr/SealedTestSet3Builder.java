/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the third sealed test set: ten source series, forty recordings, two per image type.
 *
 * <p>Successor to {@link SealedTestSetBuilder}, for the same reason that class succeeded
 * {@link LockedTestSetBuilder}: the second sealed set has been spent, so nothing chosen after it
 * was opened can be validated on it. This set exists so that {@code docs/newton_refinement_plan.md}
 * has somewhere to land if its Stage 4 is reached.
 *
 * <p>It is a separate class rather than a parameter on the old one because the spent-material check
 * has to see the previous set's output as spent. {@link SealedTestSetBuilder#spentSeriesNames} takes
 * the roots its caller owns and treats every other benchmark on disk as spent, so this class passes
 * its own two roots and the second sealed set becomes off-limits automatically.
 *
 * <p>Material was counted in {@code docs/third_sealed_set_material.md}, which also records the
 * mistake that count corrected: the natural-motion benchmark stores series one directory deeper than
 * every other benchmark, so a hand count that assumed a uniform layout missed four spent series.
 *
 * <p>Four compromises are declared rather than buried, and every one is repeated in the manifest
 * this class writes:
 * <ul>
 *   <li>Both phase sources are Strack fields of view, for the third set running. Every phase record
 *       in this project comes from that one deposit.</li>
 *   <li>Both fiducial sources are commercial-microscope bead acquisitions from the same published
 *       record as the development beads. Different microscope, same laboratory.</li>
 *   <li>Both sparse sources are single-molecule FRET acquisitions from one deposit, taken on
 *       different days. Different sessions, same laboratory and instrument.</li>
 *   <li>The two dense sources are strongly independent of each other and of everything else, but
 *       neither has a second acquisition behind it, so neither could have supplied the pair alone.
 *       </li>
 * </ul>
 */
public final class SealedTestSet3Builder {
    static final String ROOT = "library/benchmark/v2/benchmarks/sealed_test_3";
    static final String NATURAL_ROOT = "library/benchmark/v2/benchmarks/sealed_test_3_natural";

    static List<SealedTestSetBuilder.Source> sources() {
        List<SealedTestSetBuilder.Source> out = new ArrayList<>();

        out.add(new SealedTestSetBuilder.Source("PHASE", "phase_strack_lysobacter_03",
                "strack_lysobacter_03",
                "v2/supplementary_series/phase_strack_lysobacter_03/source/images",
                "v2/supplementary_series/phase_strack_lysobacter_03/source/images",
                NativeSeriesFrames.Layout.IMAGE_SEQUENCE, 1, 1,
                "CAVEAT: third Lysobacter time-lapse acquisition; every phase record in this "
                        + "project comes from the one Strack deposit"));
        out.add(new SealedTestSetBuilder.Source("PHASE", "phase_strack_pveronii_03",
                "strack_pveronii_03",
                "v2/supplementary_series/phase_strack_pveronii_03/source/images",
                "v2/supplementary_series/phase_strack_pveronii_03/source/images",
                NativeSeriesFrames.Layout.IMAGE_SEQUENCE, 1, 1,
                "CAVEAT: third Pseudomonas veronii time-lapse acquisition; every phase record in "
                        + "this project comes from the one Strack deposit"));

        out.add(new SealedTestSetBuilder.Source("BRIGHTFIELD_DIC",
                "brightfield_microbundle_type3_06", "microbundle_type3_06",
                "v2/supplementary_series/brightfield_microbundle_type3_06/source",
                "v2/supplementary_series/brightfield_microbundle_type3_06/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "sixth and last unused microbundle acquisition; 448 planes of 16-bit raw sensor "
                        + "data. Example_04 is not available: the natural-motion benchmark read it"));
        // No native path: one still image, so there is no time series to profile. The same reason
        // dic_bbbc028_hangar_01 has none in the second sealed set.
        out.add(new SealedTestSetBuilder.Source("BRIGHTFIELD_DIC", "dic_bbbc028_ring_01",
                "bbbc028_ring",
                "v2/supplementary_series/dic_bbbc028_ring_01/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "differential interference contrast shape untouched by every previous set; stored "
                        + "as an RGB container whose three channels are identical, so the "
                        + "blue-channel seed is lossless"));

        // Two channels interleaved across 311 time points, so a stride of one would hand the
        // natural arm alternating fluorophores rather than one channel over time. Stride two selects
        // Venus::BMAL1 throughout, the way the Watabe source in the second set uses a stride of four.
        out.add(new SealedTestSetBuilder.Source("DENSE_FLUOR", "dense_scn_smyllie_bmal1",
                "smyllie_scn_clock",
                "v2/sealed_test_3_series/dense_scn_smyllie_bmal1/source",
                "v2/sealed_test_3_series/dense_scn_smyllie_bmal1/source",
                NativeSeriesFrames.Layout.STACK, 1, 2,
                "organotypic suprachiasmatic nucleus slice, Venus::BMAL1 with CRY1::mRuby3; a "
                        + "wholly separate dense record from every other in this project. CAVEAT "
                        + "signal fades about 40 percent across the 6.5-day recording, so the seed "
                        + "is taken from the first frame"));
        // No native path: the staged file is a single plane rather than a series.
        out.add(new SealedTestSetBuilder.Source("DENSE_FLUOR", "dense_chromalive_hela",
                "chromalive_hela_apoptosis",
                "v2/sealed_test_3_series/dense_chromalive_hela/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "HeLa multiplexed live-cell dye, cytoplasmic channel C02 which fills the field. "
                        + "Channel C01 of the same acquisition is nuclei on a black background and "
                        + "is not dense; the channel, not the dataset, carries the image type"));

        // No native path, and the reason is not frame count. The frame is a dual-view beam-splitter
        // image, donor beside acceptor, so a centred crop straddles the seam between the two halves.
        // That seam is fixed to the camera rather than to the specimen, so it does not drift with
        // the sample and would anchor a stability measurement toward reporting no movement. These
        // two seed controlled motion only.
        out.add(new SealedTestSetBuilder.Source("SPARSE_LOWLIGHT", "sparse_met_smfret_240701",
                "met_smfret_240701",
                "v2/sealed_test_3_series/sparse_met_smfret_240701/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "single-molecule FRET of MET receptor activation, session 240701; 16-bit raw "
                        + "sensor data, the first sparse record in this project that is not "
                        + "distributed at 8 bits. CAVEAT same laboratory and instrument as the "
                        + "240712 record, different day and dish"));
        out.add(new SealedTestSetBuilder.Source("SPARSE_LOWLIGHT", "sparse_met_smfret_240712",
                "met_smfret_240712",
                "v2/sealed_test_3_series/sparse_met_smfret_240712/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "single-molecule FRET of MET receptor activation, session 240712; 16-bit raw "
                        + "sensor data. CAVEAT same laboratory and instrument as the 240701 "
                        + "record, different day and dish"));

        out.add(new SealedTestSetBuilder.Source("FIDUCIAL_STATIC", "fiducial_cage_commercial_d3",
                "cage_commercial_d3",
                "v2/sealed_test_3_series/fiducial_cage_commercial_d3/source",
                "v2/sealed_test_3_series/fiducial_cage_commercial_d3/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: commercial-microscope bead acquisition from the same published record as "
                        + "cage_d1 to cage_d5; different microscope, same laboratory"));
        out.add(new SealedTestSetBuilder.Source("FIDUCIAL_STATIC", "fiducial_cage_commercial_d4",
                "cage_commercial_d4",
                "v2/sealed_test_3_series/fiducial_cage_commercial_d4/source",
                "v2/sealed_test_3_series/fiducial_cage_commercial_d4/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: commercial-microscope bead acquisition from the same published record as "
                        + "cage_d1 to cage_d5; different microscope, same laboratory"));
        return out;
    }

    /** The two benchmark roots this builder writes, and therefore the two it may ignore. */
    static Set<String> ownRoots() {
        Set<String> own = new LinkedHashSet<>();
        own.add(Paths.get(ROOT).getFileName().toString());
        own.add(Paths.get(NATURAL_ROOT).getFileName().toString());
        return own;
    }

    static void checkNotSpent(Path project, List<SealedTestSetBuilder.Source> sources)
            throws IOException {
        Set<String> spent = SealedTestSetBuilder.spentSeriesNames(project, ownRoots());
        List<String> clashes = new ArrayList<>();
        for (SealedTestSetBuilder.Source source : sources) {
            if (spent.contains(source.seriesId)) {
                clashes.add(source.seriesId + " already appears under a benchmark on disk");
            }
        }
        if (!clashes.isEmpty()) {
            throw new IllegalStateException("third sealed set would reuse spent material:\n"
                    + String.join("\n", clashes));
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path benchmark = project.resolve("library/benchmark");
        List<SealedTestSetBuilder.Source> sources = sources();
        SealedTestSetBuilder.checkBalance(sources);
        checkNotSpent(project, sources);

        String previousMethod = System.getProperty("ripr.onlyMethod");
        System.setProperty("ripr.onlyMethod", "__sealed_test_3_input_only__");
        int built = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (SealedTestSetBuilder.Source source : sources) {
                Path seed = representativeImage(benchmark.resolve(source.seedPath).normalize());
                for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                    Path recording = project.resolve(ROOT).resolve(source.imageClass)
                            .resolve(source.seriesId).resolve(motion.name()).resolve("CLEAN");
                    if (Files.isRegularFile(recording.resolve("00_input_uncorrected.tif"))
                            && !Boolean.getBoolean("ripr.rewrite")) {
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
            if (previousMethod == null) System.clearProperty("ripr.onlyMethod");
            else System.setProperty("ripr.onlyMethod", previousMethod);
        }

        int natural = buildNatural(project, benchmark, sources, failures);
        writeManifest(project, sources);
        System.out.printf(Locale.ROOT,
                "third sealed test set: %d controlled recordings, %d natural stacks, %d failures%n",
                built, natural, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("third sealed test set failures:\n" + String.join("\n", failures));
        }
    }

    private static int buildNatural(Path project, Path benchmark,
                                    List<SealedTestSetBuilder.Source> sources,
                                    List<String> failures) throws IOException {
        int built = 0;
        for (SealedTestSetBuilder.Source source : sources) {
            if (source.nativePath.isEmpty()) continue;
            Path output = project.resolve(NATURAL_ROOT).resolve(source.imageClass)
                    .resolve(source.seriesId);
            Path target = output.resolve("00_input_native.tif");
            if (Files.isRegularFile(target) && !Boolean.getBoolean("ripr.rewrite")) {
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

    private static void writeManifest(Path project, List<SealedTestSetBuilder.Source> sources)
            throws IOException {
        Path summary = project.resolve(ROOT).resolve("summaries");
        Files.createDirectories(summary);
        StringBuilder out = new StringBuilder("image_series_class,series_id,independent_group,"
                + "seed_source,native_source,motion_profiles,controlled_recordings,"
                + "input_sha256_curved,note\n");
        for (SealedTestSetBuilder.Source source : sources) {
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
        Files.write(summary.resolve("sealed_test_3_manifest.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> motionNames() {
        List<String> out = new ArrayList<>();
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) out.add(motion.name());
        return out;
    }

    private static Path representativeImage(Path source) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("missing third-set source " + source);
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

    private SealedTestSet3Builder() {
    }
}
