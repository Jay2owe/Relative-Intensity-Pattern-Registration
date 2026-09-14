/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the independent, balanced locked test set: ten new source series, forty recordings.
 *
 * <p>None of these sources influenced any selector. Where spare real material exists, each image
 * type takes two genuinely independent series. Two exceptions are declared rather than buried, and
 * both are repeated in the locked-test report. The reinforced-cage record contains exactly five bead
 * acquisitions and four are already in the development set, so the one remaining acquisition is cut
 * into two non-overlapping fields of view that share an independent group. The dense-fluorescence
 * pair comes from the same published calcium-signalling record as the development dense series,
 * because the one wholly separate dense record on disk is in a TIFF variant ImageJ cannot open.
 */
public final class LockedTestSetBuilder {
    static final String ROOT = "library/benchmark/v2/benchmarks/locked_test";
    static final String NATURAL_ROOT = "library/benchmark/v2/benchmarks/locked_test_natural";
    static final String DERIVED_SERIES = "library/benchmark/v2/locked_test_series";

    /** One locked-test source. {@code nativePath} is empty when no real time series is available. */
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

    private LockedTestSetBuilder() {
    }

    /**
     * Chosen before any locked-test result existed, on independence rather than convenience: two
     * different bacteria, two different brightfield datasets, and two sparse sources from different
     * laboratories than the development sparse set. Every compromise carries its caveat inline.
     */
    static List<Source> sources() {
        List<Source> out = new ArrayList<>();
        out.add(new Source("PHASE", "phase_strack_lysobacter_02", "strack_lysobacter_02",
                "v2/supplementary_series/phase_strack_lysobacter_02/source/images",
                "v2/supplementary_series/phase_strack_lysobacter_02/source/images",
                NativeSeriesFrames.Layout.IMAGE_SEQUENCE, 1, 1,
                "second Lysobacter field of view; never used in development"));
        out.add(new Source("PHASE", "phase_strack_pveronii_02", "strack_pveronii_02",
                "v2/supplementary_series/phase_strack_pveronii_02/source/images",
                "v2/supplementary_series/phase_strack_pveronii_02/source/images",
                NativeSeriesFrames.Layout.IMAGE_SEQUENCE, 1, 1,
                "second Pseudomonas veronii field of view; never used in development"));
        out.add(new Source("BRIGHTFIELD_DIC", "brightfield_microbundle_type3_03",
                "microbundle_type3_03",
                "v2/supplementary_series/brightfield_microbundle_type3_03/source",
                "v2/supplementary_series/brightfield_microbundle_type3_03/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "third microbundle acquisition; never used in development"));
        out.add(new Source("BRIGHTFIELD_DIC", "dic_bbbc028_square_01", "bbbc028_square_01",
                "v2/supplementary_series/dic_bbbc028_square_01/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "differential interference contrast shape absent from the development set"));
        // The separate ZENODO_AYDIN dense record would be more independent still, but its files are
        // a TIFF variant ImageJ cannot open, so they cannot seed a recording this pipeline builds.
        out.add(new Source("DENSE_FLUOR", "dense_ssbd197_fig5a", "ssbd197_fig5a",
                "v2/supplementary_series/dense_ssbd197_fig5a/source",
                "v2/supplementary_series/dense_ssbd197_fig5a/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: a different acquisition from the development dense series, but from the "
                        + "same published calcium-signalling record"));
        out.add(new Source("DENSE_FLUOR", "dense_ssbd197_fig4b_oer", "ssbd197_fig4b_oer",
                "v2/supplementary_series/dense_ssbd197_fig4b_oer/source",
                "v2/supplementary_series/dense_ssbd197_fig4b_oer/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: a different acquisition from the development dense series, but from the "
                        + "same published calcium-signalling record"));
        out.add(new Source("SPARSE_LOWLIGHT", "sparse_figshare_mda231_rfp", "figshare_mda231_rfp",
                "v2/native_sources/SPARSE_LOWLIGHT/sparse_figshare_mda231_rfp/source",
                "v2/native_sources/SPARSE_LOWLIGHT/sparse_figshare_mda231_rfp/source",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "different laboratory from the development sparse set"));
        out.add(new Source("SPARSE_LOWLIGHT", "sparse_ssbd166_branch2_nuclei", "ssbd166_branch2",
                "v2/native_sources/SPARSE_LOWLIGHT/sparse_ssbd166_branch2_nuclei/source",
                "v2/native_sources/SPARSE_LOWLIGHT/sparse_ssbd166_branch2_nuclei/source",
                NativeSeriesFrames.Layout.IMAGE_SEQUENCE, 1, 1,
                "different laboratory from the development sparse set"));
        out.add(new Source("FIDUCIAL_STATIC", "fiducial_cage_d5_view_a", "cage_d5",
                "v2/locked_test_series/fiducial_cage_d5_view_a/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: one of two non-overlapping fields of view cut from the only unused bead "
                        + "acquisition; not independent of view B"));
        out.add(new Source("FIDUCIAL_STATIC", "fiducial_cage_d5_view_b", "cage_d5",
                "v2/locked_test_series/fiducial_cage_d5_view_b/source", "",
                NativeSeriesFrames.Layout.STACK, 1, 1,
                "CAVEAT: one of two non-overlapping fields of view cut from the only unused bead "
                        + "acquisition; not independent of view A"));
        return out;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path benchmark = project.resolve("library/benchmark");
        List<Source> sources = sources();
        checkBalance(sources);
        prepareFiducialViews(project);

        // BenchmarkComparisonStacks writes the injected-motion stack and then runs its own
        // comparison methods. Only the stack is wanted here, so no method name can match.
        String previousMethod = System.getProperty("ripr.onlyMethod");
        System.setProperty("ripr.onlyMethod", "__locked_test_input_only__");
        int built = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (Source source : sources) {
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
                "locked test set: %d controlled recordings, %d natural stacks, %d failures%n",
                built, natural, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("locked test set failures:\n" + String.join("\n", failures));
        }
    }

    /** Ten sources, two per image type, or the set is not balanced and must not be used. */
    static void checkBalance(List<Source> sources) {
        Map<String, Integer> perClass = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Source source : sources) {
            if (!ids.add(source.seriesId)) {
                throw new IllegalStateException("duplicate locked-test series " + source.seriesId);
            }
            perClass.merge(source.imageClass, 1, Integer::sum);
        }
        if (sources.size() != 10) {
            throw new IllegalStateException("expected 10 locked-test sources, got " + sources.size());
        }
        if (perClass.size() != 5) {
            throw new IllegalStateException("expected 5 image types, got " + perClass.size());
        }
        for (Map.Entry<String, Integer> entry : perClass.entrySet()) {
            if (entry.getValue() != 2) {
                throw new IllegalStateException(entry.getKey() + " has " + entry.getValue()
                        + " locked-test sources; the balanced set needs 2");
            }
        }
    }

    /**
     * Cut the one unused bead acquisition into two non-overlapping fields of view. The halves share
     * no pixel, so the two recordings are genuinely different images, but they come from one
     * acquisition and are declared as one independent group everywhere downstream.
     */
    static void prepareFiducialViews(Path project) throws IOException {
        Path viewA = project.resolve(DERIVED_SERIES)
                .resolve("fiducial_cage_d5_view_a/source/fiducial_cage_d5_view_a.tif");
        Path viewB = project.resolve(DERIVED_SERIES)
                .resolve("fiducial_cage_d5_view_b/source/fiducial_cage_d5_view_b.tif");
        if (Files.isRegularFile(viewA) && Files.isRegularFile(viewB)
                && !Boolean.getBoolean("ripr.rewrite")) {
            return;
        }
        Path source = project.resolve(
                "library/benchmark/v2/supplementary_series/fiducial_cage_d5/source"
                        + "/d5_rocs_beads_no_autofocus.ome.tif");
        if (!Files.isRegularFile(source)) throw new IOException("missing " + source);
        ImagePlus image = IJ.openImage(source.toString());
        if (image == null) throw new IOException("could not open " + source);
        try {
            ImageProcessor processor = image.getProcessor();
            int width = processor.getWidth();
            int height = processor.getHeight();
            boolean splitHorizontally = width >= height;
            int halfWidth = splitHorizontally ? width / 2 : width;
            int halfHeight = splitHorizontally ? height : height / 2;
            if (Math.min(halfWidth, halfHeight) < 128) {
                throw new IOException("the bead acquisition is too small to cut into two views: "
                        + width + " x " + height);
            }
            write(viewA, crop(processor, 0, 0, halfWidth, halfHeight), "cage d5 view A");
            write(viewB, crop(processor, splitHorizontally ? halfWidth : 0,
                    splitHorizontally ? 0 : halfHeight, halfWidth, halfHeight), "cage d5 view B");
            System.out.printf(Locale.ROOT, "cut %d x %d bead frame into two %d x %d views%n",
                    width, height, halfWidth, halfHeight);
        } finally {
            image.close();
        }
    }

    private static ImageProcessor crop(ImageProcessor processor, int x, int y,
                                       int width, int height) {
        ImageProcessor copy = processor.duplicate();
        copy.setRoi(x, y, width, height);
        return copy.crop();
    }

    private static void write(Path target, ImageProcessor processor, String title)
            throws IOException {
        Files.createDirectories(target.getParent());
        ImagePlus out = new ImagePlus(title, processor);
        try {
            if (!IJ.saveAsTiff(out, target.toString()) && !Files.isRegularFile(target)) {
                throw new IOException("could not write " + target);
            }
        } finally {
            out.close();
        }
    }

    private static int buildNatural(Path project, Path benchmark, List<Source> sources,
                                    List<String> failures) throws IOException {
        int built = 0;
        for (Source source : sources) {
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
        Files.write(summary.resolve("locked_test_manifest.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> motionNames() {
        List<String> out = new ArrayList<>();
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) out.add(motion.name());
        return out;
    }

    private static Path representativeImage(Path source) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("missing locked-test source " + source);
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
