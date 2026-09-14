/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.AutomaticRotationSelector;
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
import ripr.core.ChainRepair;
import ripr.core.FrameSource;
import ripr.core.PairAligner;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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
 * Full 128-recipe rigid Automatic sweep on the five-image-type benchmark library.
 *
 * <p>The generated inputs are frozen before any recipe runs. Each controlled translation recording
 * receives a deterministic angular trajectory, and the exact combined rigid truth is stored beside
 * the pixels. A matching truth-angle-removed stack is also stored so translation quality after
 * rotational resampling can be measured without confounding it with angular recovery.
 */
public final class RigidSelectorFactorialBenchmark {
    static final String RUN_ID = "rigid_selector_v1";
    static final String RESULT_HEADER = "split,image_series_class,series_id,independent_group,"
            + "motion_category,condition,arm,recipe_id,estimator,status,frames,"
            + "median_warp_central50_px,p90_warp_central50_px,worst_warp_central50_px,"
            + "median_warp_full_px,p90_warp_full_px,worst_warp_full_px,"
            + "median_angle_error_degrees,p90_angle_error_degrees,worst_angle_error_degrees,"
            + "median_translation_error_px,worst_translation_error_px,refused_pairs,"
            + "non_converged_pairs,shift_bound_pairs,rotation_bound_pairs,both_bound_pairs,"
            + "repaired_frames,unsupported_frames,retained_crop_fraction,runtime_seconds,details";

    private static final int EXPECTED_RECIPES = 128;
    private static final double MAX_ROTATION_DEGREES = 10.0;
    private static final String JOINT_ARM = "JOINT_RIGID";
    private static final String ORACLE_ARM = "TRUTH_ANGLE_REMOVED_TRANSLATION";
    private static final String HYBRID_ARM = "HYBRID_FIXED_ANGLE";
    private static final String SPLIT_ARM = "AUTOMATIC_TRANSLATION__SELECTED_ROTATION";
    private static final String UNIVERSAL_ANGLE_RECIPE =
            "support_gradient__band_no_top_25__filter_gaussian_1_0__mask_least_informative_25";
    private static final String HYBRID_POLICY = "universal_angle__shortlisted_translation_v1";
    private static final String PHASE_CONFIDENCE_RECIPE =
            "support_all__band_no_top_10__filter_gaussian_0_7__mask_none";
    private static final int FRESH_CROP_SIZE = 256;
    private static final Map<String, Long> PROGRESS_STARTS = new LinkedHashMap<>();

    private RigidSelectorFactorialBenchmark() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("usage: RigidSelectorFactorialBenchmark "
                    + "<project> <generate|run|split|automatic|evidence|hybrid|confidence|audit|fresh-generate|"
                    + "fresh-run|fresh-confidence|fresh-audit> [development]");
        }
        Path project = Paths.get(args[0]).toAbsolutePath().normalize();
        String command = args[1].trim().toLowerCase(Locale.ROOT);
        String split = args.length == 3 ? args[2].trim().toLowerCase(Locale.ROOT) : "development";
        boolean fresh = command.startsWith("fresh-");
        if (fresh) {
            command = command.substring("fresh-".length());
            if (!"development".equals(split) && !"validation".equals(split)
                    && !"locked".equals(split) && !"qualification".equals(split)) {
                throw new IllegalArgumentException(
                        "fresh split must be development, validation, locked or qualification; "
                        + "requested " + split);
            }
            split = "fresh-" + split;
        } else if (!"development".equals(split) && !"integration".equals(split)
                && !"phase-development".equals(split)
                && !"phase-validation".equals(split)) {
            throw new IllegalArgumentException(
                    "unknown split " + split);
        }
        if ("generate".equals(command) && fresh) generateFresh(project, split);
        else if ("generate".equals(command)) generate(project, split);
        else if ("run".equals(command)) run(project, split);
        else if ("split".equals(command)) runSplitRotationSelector(project, split);
        else if ("automatic".equals(command)) runAutomaticRotationSelector(project, split);
        else if ("evidence".equals(command)) runRotationEvidence(project, split);
        else if ("hybrid".equals(command)) runHybrid(project, split);
        else if ("confidence".equals(command)) runConfidence(project, split);
        else if ("audit".equals(command)) audit(project, split);
        else throw new IllegalArgumentException("unknown command " + command);
    }

    // -------------------------------------------------------------------------------- inputs

    private static void generate(Path project, String split) throws Exception {
        Path sourceRoot = sourceRoot(project, split);
        Path tuning = tuningRoot(project);
        Path inputRoot = tuning.resolve("inputs").resolve(split);
        Files.createDirectories(inputRoot);

        List<FullSelectorFactorialBenchmark.Recording> recordings =
                FullSelectorFactorialBenchmark.recordings(sourceRoot);
        Map<String, Integer> signs = alternatingSigns(recordings);
        List<GeneratedCase> cases = new ArrayList<>();
        boolean rewrite = Boolean.getBoolean("rigid.rewrite");
        Set<String> onlySeries = requested("rigid.onlySeries");
        int generated = 0;
        int planned = recordings.size() * 2 + distinctSeries(recordings);
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            if (!onlySeries.isEmpty() && !onlySeries.contains(recording.series)) continue;
            int sign = signs.get(recording.imageClass + "/" + recording.series);
            cases.add(generateCase(inputRoot, recording, "RIGID_CLEAN", sign, false, false,
                    rewrite));
            progress("generated input", ++generated, planned, recording.key() + "/RIGID_CLEAN");
            cases.add(generateCase(inputRoot, recording, "RIGID_GAIN_FADE_0_5", sign, true, false,
                    rewrite));
            progress("generated input", ++generated, planned,
                    recording.key() + "/RIGID_GAIN_FADE_0_5");
            if ("SUBPIXEL_RANDOM_WALK".equals(recording.motion)) {
                cases.add(generateCase(inputRoot, recording, "ZERO_ROTATION_CLEAN", sign, false,
                        true, rewrite));
                progress("generated input", ++generated, planned,
                        recording.key() + "/ZERO_ROTATION_CLEAN");
            }
        }
        cases.sort(Comparator.comparing(c -> c.caseId));
        writeInputManifest(tuning.resolve("input_manifest_" + split + ".csv"), project, split,
                cases);
        writeRecipeManifest(tuning.resolve("recipe_manifest.csv"));
        writeEnvironment(tuning.resolve("environment.txt"), project, split, recordings, cases);
        System.out.printf(Locale.ROOT, "frozen %d generated cases and %d recipes under %s%n",
                cases.size(), candidates().size(), inputRoot);
    }

    /** Generate the A09 native-length benchmark directly from the frozen raw-source manifest. */
    private static void generateFresh(Path project, String split) throws Exception {
        String allocation = split.substring("fresh-".length());
        Path tuning = tuningRoot(project);
        Path freshRoot = tuning.resolve("fresh_recovery");
        Path inputRoot = freshRoot.resolve("inputs").resolve(allocation);
        Files.createDirectories(inputRoot);
        List<FreshSource> sources = readFreshSources(project, allocation);
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyClasses = requested("rigid.onlyImageClass");
        List<FreshSource> selected = new ArrayList<>();
        for (FreshSource source : sources) {
            if (!onlySeries.isEmpty() && !onlySeries.contains(source.series)) continue;
            if (!onlyClasses.isEmpty() && !onlyClasses.contains(source.imageClass)) continue;
            selected.add(source);
        }
        if (selected.isEmpty()) {
            throw new IOException("no frozen sources selected for " + allocation);
        }
        Map<String, Integer> signs = freshAlternatingSigns(selected);
        List<GeneratedCase> cases = new ArrayList<>();
        boolean rewrite = Boolean.getBoolean("rigid.rewrite");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        int frameLimit = Integer.parseInt(System.getProperty(
                "rigid.frameLimit", Integer.toString(Integer.MAX_VALUE)));
        if (frameLimit < 2) throw new IllegalArgumentException("rigid.frameLimit must be >= 2");
        int perSource = 0;
        for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
            if (!onlyMotions.isEmpty() && !onlyMotions.contains(motion.name())) continue;
            if (onlyConditions.isEmpty() || onlyConditions.contains("RIGID_CLEAN")) perSource++;
            if (onlyConditions.isEmpty() || onlyConditions.contains("RIGID_GAIN_FADE_0_5")) {
                perSource++;
            }
            if (motion == ControlledMotionProfile.SUBPIXEL_RANDOM_WALK
                    && (onlyConditions.isEmpty()
                        || onlyConditions.contains("ZERO_ROTATION_CLEAN"))) perSource++;
        }
        int planned = selected.size() * perSource;
        int generated = 0;
        for (int sourceIndex = 0; sourceIndex < selected.size(); sourceIndex++) {
            FreshSource source = selected.get(sourceIndex);
            verify(source.path, source.sha256);
            System.out.printf(Locale.ROOT,
                    "loading fresh source %d/%d: %s, channel %d, %d native frames%n",
                    sourceIndex + 1, selected.size(), source.series, source.channel, source.frames);
            ImagePlus image = IJ.openImage(source.path.toString());
            if (image == null) throw new IOException("could not open " + source.path);
            try {
                if (image.getWidth() != source.width || image.getHeight() != source.height) {
                    throw new IOException("dimension mismatch for " + source.path + ": opened "
                            + image.getWidth() + "x" + image.getHeight() + ", manifest "
                            + source.width + "x" + source.height);
                }
                StackFrames nativeFrames = StackFrames.of(image, source.channel,
                        StackFrames.PROJECT_Z);
                if (nativeFrames.count() != source.frames) {
                    throw new IOException("frame mismatch for " + source.path + ": opened "
                            + nativeFrames.count() + ", manifest " + source.frames);
                }
                int width = Math.min(FRESH_CROP_SIZE, nativeFrames.width());
                int height = Math.min(FRESH_CROP_SIZE, nativeFrames.height());
                int left = (nativeFrames.width() - width) / 2;
                int top = (nativeFrames.height() - height) / 2;
                int usedFrames = Math.min(source.frames, frameLimit);
                float[][] base = new float[usedFrames][];
                for (int frame = 0; frame < usedFrames; frame++) {
                    base[frame] = centralCrop(nativeFrames.plane(frame), nativeFrames.width(),
                            left, top, width, height);
                }
                int sign = signs.get(source.imageClass + "/" + source.series);
                for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                    if (!onlyMotions.isEmpty() && !onlyMotions.contains(motion.name())) continue;
                    FullSelectorFactorialBenchmark.Recording recording =
                            new FullSelectorFactorialBenchmark.Recording(source.imageClass,
                                    source.series, motion.name(), "RAW", source.path.getParent(),
                                    source.path, usedFrames, source.independentGroup,
                                    source.sha256, Files.size(source.path));
                    if (onlyConditions.isEmpty() || onlyConditions.contains("RIGID_CLEAN")) {
                        cases.add(generateFreshCase(inputRoot, recording, base, width, height,
                                "RIGID_CLEAN", sign, false, false, rewrite));
                        progress("generated fresh input", ++generated, planned,
                                recording.key() + "/RIGID_CLEAN");
                    }
                    if (onlyConditions.isEmpty()
                            || onlyConditions.contains("RIGID_GAIN_FADE_0_5")) {
                        cases.add(generateFreshCase(inputRoot, recording, base, width, height,
                                "RIGID_GAIN_FADE_0_5", sign, true, false, rewrite));
                        progress("generated fresh input", ++generated, planned,
                                recording.key() + "/RIGID_GAIN_FADE_0_5");
                    }
                    if (motion == ControlledMotionProfile.SUBPIXEL_RANDOM_WALK
                            && (onlyConditions.isEmpty()
                                || onlyConditions.contains("ZERO_ROTATION_CLEAN"))) {
                        cases.add(generateFreshCase(inputRoot, recording, base, width, height,
                                "ZERO_ROTATION_CLEAN", sign, false, true, rewrite));
                        progress("generated fresh input", ++generated, planned,
                                recording.key() + "/ZERO_ROTATION_CLEAN");
                    }
                }
            } finally {
                image.close();
            }
        }
        cases.sort(Comparator.comparing(c -> c.caseId));
        Path manifest = inputManifest(tuning, split);
        writeFreshInputManifest(manifest, project, split, cases);
        writeRecipeManifest(freshRoot.resolve("recipe_manifest.csv"));
        String environment = "protocol=A09\nallocation=" + allocation + "\n"
                + "crop=central_" + FRESH_CROP_SIZE + "x" + FRESH_CROP_SIZE + "\n"
                + "native_frames=true\nsource_recordings=" + selected.size() + "\n"
                + "frame_limit=" + frameLimit + "\n"
                + "generated_cases=" + cases.size() + "\njava="
                + System.getProperty("java.version") + "\n";
        Files.write(freshRoot.resolve("environment_" + allocation + ".txt"),
                environment.getBytes(StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT, "frozen %d native-length cases from %d sources under %s%n",
                cases.size(), selected.size(), inputRoot);
    }

    private static GeneratedCase generateFreshCase(Path inputRoot,
            FullSelectorFactorialBenchmark.Recording recording, float[][] base,
            int width, int height, String condition, int sign, boolean gainFade,
            boolean zeroRotation, boolean rewrite) throws Exception {
        Path folder = inputRoot.resolve(recording.imageClass).resolve(recording.series)
                .resolve(recording.motion).resolve(condition);
        Files.createDirectories(folder);
        Path input = folder.resolve("00_input_uncorrected.tif");
        Path oracle = folder.resolve("01_truth_angle_removed.tif");
        Path truthFile = folder.resolve("truth.csv");
        if (rewrite || !Files.isRegularFile(input) || !Files.isRegularFile(oracle)
                || !Files.isRegularFile(truthFile)) {
            ImageStack jointStack = new ImageStack(width, height);
            ImageStack oracleStack = new ImageStack(width, height);
            StringBuilder truth = new StringBuilder("frame,truth_x_px,truth_y_px,"
                    + "truth_rotation_radians,oracle_x_px,oracle_y_px,"
                    + "oracle_rotation_radians,added_rotation_degrees\n");
            for (int frame = 0; frame < recording.frames; frame++) {
                Transform translation = nativeTranslation(recording.motion, frame,
                        recording.frames);
                double degrees = zeroRotation ? 0
                        : sign * rotationDegrees(recording.motion, frame, recording.frames);
                Transform rotation = new Transform(0, 0, Math.toRadians(degrees));
                Transform combined = translation.then(rotation);
                double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                        base[frame], width, height);
                float[] joint = combined.equals(Transform.IDENTITY) ? base[frame].clone()
                        : ThevenazProtocolBenchmark.spline7Warp(
                                coefficients, width, height, combined.inverse());
                float[] removed = translation.equals(Transform.IDENTITY) ? base[frame].clone()
                        : ThevenazProtocolBenchmark.spline7Warp(
                                coefficients, width, height, translation.inverse());
                double gain = gainFade && recording.frames > 1
                        ? 1.0 - 0.5 * frame / (recording.frames - 1.0) : 1.0;
                if (gain != 1.0) {
                    multiply(joint, gain);
                    multiply(removed, gain);
                }
                jointStack.addSlice(null, joint);
                oracleStack.addSlice(null, removed);
                truth.append(frame + 1).append(',').append(precise(combined.dx)).append(',')
                        .append(precise(combined.dy)).append(',')
                        .append(precise(combined.theta)).append(',')
                        .append(precise(translation.dx)).append(',')
                        .append(precise(translation.dy)).append(",0,")
                        .append(precise(degrees)).append('\n');
            }
            ImagePlus joint = stack("fresh rigid " + recording.series, jointStack);
            try {
                IJ.saveAsTiff(joint, input.toString());
            } finally {
                joint.close();
            }
            ImagePlus removed = stack("fresh angle removed " + recording.series, oracleStack);
            try {
                IJ.saveAsTiff(removed, oracle.toString());
            } finally {
                removed.close();
            }
            Files.write(truthFile, truth.toString().getBytes(StandardCharsets.UTF_8));
        }
        String caseId = recording.imageClass + "/" + recording.series + "/" + recording.motion
                + "/" + condition;
        return new GeneratedCase(caseId, recording, condition, input, oracle, truthFile,
                sha256(input), sha256(oracle), sha256(truthFile));
    }

    /** Sample the frozen 48-frame path at native normalized time without changing image frames. */
    static Transform nativeTranslation(String motion, int frame, int frames) {
        if (frames < 2 || frame < 0 || frame >= frames) {
            if (frames == 1 && frame == 0) return Transform.IDENTITY;
            throw new IllegalArgumentException("invalid frame " + frame + " of " + frames);
        }
        int[] x = new int[Benchmark.FRAMES];
        int[] y = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(x, y);
        double position = frame * (Benchmark.FRAMES - 1.0) / (frames - 1.0);
        int lower = (int) Math.floor(position);
        int upper = Math.min(Benchmark.FRAMES - 1, lower + 1);
        double weight = position - lower;
        double dx = (x[lower] * (1 - weight) + x[upper] * weight) / Benchmark.FINE;
        double dy = (y[lower] * (1 - weight) + y[upper] * weight) / Benchmark.FINE;
        return new Transform(dx, dy, 0);
    }

    private static float[] centralCrop(float[] pixels, int sourceWidth, int left, int top,
                                       int width, int height) {
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            System.arraycopy(pixels, (top + y) * sourceWidth + left, out, y * width, width);
        }
        return out;
    }

    private static List<FreshSource> readFreshSources(Path project, String allocation)
            throws IOException {
        Path manifest = tuningRoot(project).resolve("source_candidates/source_manifest.csv");
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        List<FreshSource> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (row.size() != 18) throw new IOException("bad source manifest row " + (i + 1));
            if (!allocation.equals(row.get(1))) continue;
            int channel = Integer.parseInt(row.get(6));
            if (channel < 1) continue;
            Path source = Paths.get(row.get(14));
            if (!source.isAbsolute()) source = project.resolve(source);
            out.add(new FreshSource(row.get(2), row.get(3), row.get(4), channel,
                    Integer.parseInt(row.get(7)), Integer.parseInt(row.get(8)),
                    Integer.parseInt(row.get(9)), source.normalize(), row.get(15)));
        }
        out.sort(Comparator.comparing(source -> source.imageClass + "/" + source.series));
        return out;
    }

    private static Map<String, Integer> freshAlternatingSigns(List<FreshSource> sources) {
        Map<String, List<String>> byClass = new LinkedHashMap<>();
        for (FreshSource source : sources) {
            byClass.computeIfAbsent(source.imageClass, ignored -> new ArrayList<>())
                    .add(source.series);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byClass.entrySet()) {
            entry.getValue().sort(String::compareTo);
            for (int i = 0; i < entry.getValue().size(); i++) {
                out.put(entry.getKey() + "/" + entry.getValue().get(i),
                        (i & 1) == 0 ? 1 : -1);
            }
        }
        return out;
    }

    private static GeneratedCase generateCase(Path inputRoot,
            FullSelectorFactorialBenchmark.Recording recording, String condition, int sign,
            boolean gainFade, boolean zeroRotation, boolean rewrite) throws Exception {
        Path folder = inputRoot.resolve(recording.imageClass).resolve(recording.series)
                .resolve(recording.motion).resolve(condition);
        Files.createDirectories(folder);
        Path input = folder.resolve("00_input_uncorrected.tif");
        Path oracle = folder.resolve("01_truth_angle_removed.tif");
        Path truthFile = folder.resolve("truth.csv");
        if (rewrite || !Files.isRegularFile(input) || !Files.isRegularFile(truthFile)
                || !Files.isRegularFile(oracle)) {
            ImagePlus source = IJ.openImage(recording.input.toString());
            if (source == null) throw new IOException("could not open " + recording.input);
            try {
                int frames = source.getStackSize();
                if (frames != Benchmark.FRAMES) {
                    throw new IOException(recording.input + " has " + frames + " frames, expected "
                            + Benchmark.FRAMES);
                }
                int width = source.getWidth();
                int height = source.getHeight();
                int[] fineX = new int[frames];
                int[] fineY = new int[frames];
                ControlledMotionProfile.valueOf(recording.motion).fill(fineX, fineY);
                ImageStack jointStack = new ImageStack(width, height);
                ImageStack oracleStack = new ImageStack(width, height);
                StringBuilder truth = new StringBuilder("frame,truth_x_px,truth_y_px,"
                        + "truth_rotation_radians,oracle_x_px,oracle_y_px,"
                        + "oracle_rotation_radians,added_rotation_degrees\n");
                for (int frame = 0; frame < frames; frame++) {
                    float[] base = (float[]) source.getStack().getProcessor(frame + 1)
                            .convertToFloatProcessor().getPixels();
                    double degrees = zeroRotation ? 0
                            : sign * rotationDegrees(recording.motion, frame, frames);
                    Transform added = new Transform(0, 0, Math.toRadians(degrees));
                    float[] joint;
                    if (added.theta == 0) {
                        joint = base.clone();
                    } else {
                        double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                                base, width, height);
                        joint = ThevenazProtocolBenchmark.spline7Warp(
                                coefficients, width, height, added.inverse());
                    }
                    double gain = gainFade ? 1.0 - 0.5 * frame / (frames - 1.0) : 1.0;
                    if (gain != 1.0) multiply(joint, gain);
                    jointStack.addSlice(null, joint);

                    Transform translation = new Transform(fineX[frame] / (double) Benchmark.FINE,
                            fineY[frame] / (double) Benchmark.FINE, 0);
                    Transform combined = translation.then(added);
                    float[] removed;
                    if (added.theta == 0) {
                        removed = joint.clone();
                    } else {
                        double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                                joint, width, height);
                        removed = ThevenazProtocolBenchmark.spline7Warp(
                                coefficients, width, height, added);
                    }
                    oracleStack.addSlice(null, removed);
                    truth.append(frame + 1).append(',').append(precise(combined.dx)).append(',')
                            .append(precise(combined.dy)).append(',')
                            .append(precise(combined.theta)).append(',')
                            .append(precise(translation.dx)).append(',')
                            .append(precise(translation.dy)).append(",0,")
                            .append(precise(degrees)).append('\n');
                }
                ImagePlus joint = stack("rigid selector " + recording.series, jointStack);
                try {
                    IJ.saveAsTiff(joint, input.toString());
                } finally {
                    joint.close();
                }
                ImagePlus removed = stack("truth angle removed " + recording.series, oracleStack);
                try {
                    IJ.saveAsTiff(removed, oracle.toString());
                } finally {
                    removed.close();
                }
                Files.write(truthFile, truth.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                source.close();
            }
        }
        String caseId = recording.imageClass + "/" + recording.series + "/" + recording.motion
                + "/" + condition;
        return new GeneratedCase(caseId, recording, condition, input, oracle, truthFile,
                sha256(input), sha256(oracle), sha256(truthFile));
    }

    private static ImagePlus stack(String title, ImageStack stack) {
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 1, stack.getSize());
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static void multiply(float[] pixels, double gain) {
        for (int i = 0; i < pixels.length; i++) pixels[i] *= gain;
    }

    static double rotationDegrees(String motion, int frame, int frames) {
        double u = frame / (double) (frames - 1);
        switch (motion) {
            case "SUBPIXEL_RANDOM_WALK":
                return 1.5 * Math.sin(5 * Math.PI * u) + Math.sin(11 * Math.PI * u);
            case "STEADY_DIRECTIONAL_DRIFT":
                return 8.0 * u;
            case "CURVED_OSCILLATING_DRIFT":
                return 4.0 * Math.sin(2 * Math.PI * u);
            case "INTERMITTENT_JUMPS":
                return u < 1.0 / 3.0 ? 0 : (u < 2.0 / 3.0 ? 5.0 : -3.0);
            default:
                throw new IllegalArgumentException("unknown motion " + motion);
        }
    }

    private static Map<String, Integer> alternatingSigns(
            List<FullSelectorFactorialBenchmark.Recording> recordings) {
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            byType.computeIfAbsent(recording.imageClass, ignored -> new LinkedHashSet<>())
                    .add(recording.series);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : byType.entrySet()) {
            List<String> series = new ArrayList<>(entry.getValue());
            series.sort(String::compareTo);
            for (int i = 0; i < series.size(); i++) {
                out.put(entry.getKey() + "/" + series.get(i), (i & 1) == 0 ? 1 : -1);
            }
        }
        return out;
    }

    private static int distinctSeries(List<FullSelectorFactorialBenchmark.Recording> recordings) {
        Set<String> values = new LinkedHashSet<>();
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            values.add(recording.imageClass + "/" + recording.series);
        }
        return values.size();
    }

    // -------------------------------------------------------------------------------- sweep

    private static void run(Path project, String split) throws Exception {
        Path tuning = tuningRoot(project);
        Path manifest = inputManifest(tuning, split);
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("missing frozen input manifest " + manifest
                    + "; run generate first");
        }
        List<GeneratedCase> cases = readInputManifest(project, manifest);
        List<Candidate> candidates = candidates();
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyRecipes = requested("rigid.onlyRecipe");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        Set<String> onlyArms = requested("rigid.onlyArm");
        int maxCases = Integer.parseInt(System.getProperty("rigid.maxCases", "2147483647"));
        String runId = System.getProperty("rigid.run", "r02_full_factorial").trim();
        Path runRoot = runRoot(tuning, split, runId);
        Files.createDirectories(runRoot);
        Path output = runRoot.resolve("results.csv");
        Map<String, Score> completeScores = completedScores(output);
        Set<String> complete = new LinkedHashSet<>(completeScores.keySet());
        boolean append = Files.isRegularFile(output) && Files.size(output) > 0;
        int planned = 0;
        for (GeneratedCase c : cases) {
            if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
            if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
            if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
            for (String arm : arms(c)) {
                if (!onlyArms.isEmpty() && !onlyArms.contains(arm)) continue;
                for (Candidate candidate : candidates) {
                    if (onlyRecipes.isEmpty() || onlyRecipes.contains(candidate.id)) planned++;
                }
            }
        }

        int done = 0;
        int selectedCases = 0;
        System.out.printf(Locale.ROOT, "planned %,d result rows across %,d frozen cases; output %s%n",
                planned, cases.size(), output);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!append) {
                writer.write(RESULT_HEADER);
                writer.newLine();
                writer.flush();
            }
            for (GeneratedCase c : cases) {
                if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
                if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
                if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
                if (selectedCases++ >= maxCases) break;
                System.out.printf(Locale.ROOT, "opening case %d/%d: %s (%d native frames)%n",
                        selectedCases, Math.min(maxCases, cases.size()), c.caseId,
                        c.recording.frames);
                verify(c);
                Truth truth = readTruth(c.truth);
                for (String arm : arms(c)) {
                    if (!onlyArms.isEmpty() && !onlyArms.contains(arm)) continue;
                    Path input = JOINT_ARM.equals(arm) ? c.input : c.oracle;
                    ImagePlus image = IJ.openImage(input.toString());
                    if (image == null) throw new IOException("could not open " + input);
                    try {
                        // The first registration in a fresh JVM pays class loading and JIT compilation.
                        // The pilot measured the category arm at 0.897 s and the byte-identical swept
                        // recipe immediately after it at 0.321 s. Charge that startup once here rather
                        // than systematically to whichever candidate is first in the manifest.
                        warmUp(image, c, arm);
                        for (Candidate candidate : candidates) {
                            if (!onlyRecipes.isEmpty() && !onlyRecipes.contains(candidate.id)) {
                                continue;
                            }
                            String key = key(c, arm, candidate.id);
                            if (complete.contains(key)) {
                                progress("resumed", ++done, planned, key);
                                continue;
                            }
                            try {
                                Score score;
                                String twin = rigidAliasTwin(candidate, arm);
                                if (twin != null && completeScores.containsKey(key(c, arm, twin))) {
                                    score = completeScores.get(key(c, arm, twin)).copy();
                                    score.details = candidate.recipe.describe()
                                            + "; rigid execution alias of area_correlation: "
                                            + "identical production code path";
                                } else {
                                    score = measure(image, c, arm, candidate, truth);
                                }
                                writer.write(resultRow(split, c, arm, candidate, score));
                                completeScores.put(key, score);
                            } catch (RuntimeException error) {
                                writer.write(failureRow(split, c, arm, candidate, rootMessage(error)));
                            }
                            writer.newLine();
                            writer.flush();
                            complete.add(key);
                            progress("rigid sweep", ++done, planned, key);
                        }
                    } finally {
                        image.close();
                    }
                }
            }
        }
        audit(project, split);
    }

    /**
     * Benchmark every angle recipe on top of the unchanged Automatic translation selection.
     *
     * <p>This is a new selector axis, not rigid filtering of the translation selector. Automatic is
     * resolved with rotation disabled, then its explicit recipe is reused for every candidate so
     * only the angle recipe changes between rows. The default zero confidence threshold exposes raw
     * candidate behavior; confidence is tuned only after a development shortlist exists.
     */
    private static void runSplitRotationSelector(Path project, String split) throws Exception {
        if (!"development".equals(split) && !"fresh-development".equals(split)
                && !Boolean.getBoolean("rigid.allowDiagnosticIntegration")) {
            throw new IllegalArgumentException("split rotation recipes may only be tuned on "
                    + "development; diagnostic held-out runs require explicit override");
        }
        Path tuning = tuningRoot(project);
        List<GeneratedCase> cases = readInputManifest(project, inputManifest(tuning, split));
        List<Candidate> angleCandidates = candidates();
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        Set<String> onlyRecipes = requested("rigid.onlyRecipe");
        Set<String> onlyClasses = requested("rigid.onlyImageClass");
        int maxCases = Integer.parseInt(System.getProperty("rigid.maxCases", "2147483647"));
        int screenFrames = Integer.parseInt(System.getProperty("rigid.screenFrames", "0"));
        if (screenFrames != 0 && screenFrames < 2) {
            throw new IllegalArgumentException("rigid.screenFrames must be 0 or at least 2");
        }
        String rotationGainsProperty = System.getProperty("rigid.rotationGains", "").trim();
        double[] rotationGains = rotationGainsProperty.isEmpty()
                ? new double[]{Double.parseDouble(
                        System.getProperty("rigid.rotationGain", "0.0"))}
                : confidenceThresholds();
        boolean identifyGainInRecipe = !rotationGainsProperty.isEmpty();
        boolean translationOnlyControl = Boolean.getBoolean(
                "rigid.translationOnlyControl");
        boolean globalRotationProposal = Boolean.getBoolean(
                "rigid.globalRotationProposal");
        for (double rotationGain : rotationGains) {
            if (!Double.isFinite(rotationGain) || rotationGain < 0) {
                throw new IllegalArgumentException(
                        "rotation gains must be finite and >= 0");
            }
        }
        if (translationOnlyControl && identifyGainInRecipe) {
            throw new IllegalArgumentException(
                    "translation-only control does not accept a rotation-gain sweep");
        }
        String runId = System.getProperty(
                "rigid.run", "r27_split_rotation_factorial").trim();
        Path runRoot = runRoot(tuning, split, runId);
        Files.createDirectories(runRoot);
        Path output = runRoot.resolve("results.csv");
        Set<String> complete = new LinkedHashSet<>(completedScores(output).keySet());
        boolean append = Files.isRegularFile(output) && Files.size(output) > 0;
        int planned = 0;
        for (GeneratedCase c : cases) {
            if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
            if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
            if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
            if (!onlyClasses.isEmpty() && !onlyClasses.contains(c.recording.imageClass)) continue;
            for (Candidate candidate : angleCandidates) {
                if (onlyRecipes.isEmpty() || onlyRecipes.contains(candidate.id)) {
                    planned += rotationGains.length;
                }
            }
        }
        int done = 0;
        int selectedCases = 0;
        System.out.printf(Locale.ROOT,
                "planned %,d split-rotation rows across %,d frozen cases; output %s%n",
                planned, cases.size(), output);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            if (!append) {
                writer.write(RESULT_HEADER);
                writer.newLine();
                writer.flush();
            }
            for (GeneratedCase c : cases) {
                if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
                if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
                if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
                if (!onlyClasses.isEmpty()
                        && !onlyClasses.contains(c.recording.imageClass)) continue;
                if (selectedCases++ >= maxCases) break;
                verify(c);
                Truth fullTruth = readTruth(c.truth);
                ImagePlus image = IJ.openImage(c.input.toString());
                if (image == null) throw new IOException("could not open " + c.input);
                ImagePlus measured = screenFrames > 0
                        ? sampledFrames(image, screenFrames) : image;
                Truth truth = screenFrames > 0
                        ? sampledTruth(fullTruth, image.getStackSize(), screenFrames) : fullTruth;
                try {
                    RelativeIntensityPatternParameters translation = automaticTranslation(measured, c);
                    if (translationOnlyControl) {
                        RelativeIntensityPatternRegistration.estimate(measured, translation,
                                PairScheduler.Progress.NONE,
                                PairScheduler.Cancellation.NEVER);
                    } else {
                        warmUpSplit(measured, c, translation, rotationGains[0]);
                    }
                    for (Candidate candidate : angleCandidates) {
                        if (!onlyRecipes.isEmpty() && !onlyRecipes.contains(candidate.id)) continue;
                        for (double rotationGain : rotationGains) {
                            Candidate policy = identifyGainInRecipe
                                    ? new Candidate(splitConfidenceId(candidate.id, rotationGain),
                                            candidate.recipe)
                                    : candidate;
                            String key = key(c, SPLIT_ARM, policy.id);
                            if (complete.contains(key)) {
                                progress("resumed split rotation", ++done, planned, key);
                                continue;
                            }
                            try {
                                RelativeIntensityPatternParameters rotation = rotationParameters(
                                        c, candidate, rotationGain);
                                long start = System.nanoTime();
                                Registration.Result result = translationOnlyControl
                                        ? RelativeIntensityPatternRegistration.estimate(
                                                measured, translation,
                                                PairScheduler.Progress.NONE,
                                                PairScheduler.Cancellation.NEVER)
                                        : RelativeIntensityPatternRegistration.estimateWithRotationRecipe(
                                                measured, translation, rotation,
                                                globalRotationProposal,
                                                PairScheduler.Progress.NONE,
                                                PairScheduler.Cancellation.NEVER);
                                Score score = Boolean.getBoolean("rigid.consecutiveScreen")
                                        ? scorePairs(truth.joint, result,
                                                measured.getWidth(), measured.getHeight())
                                        : score(truth.joint, result,
                                                measured.getWidth(), measured.getHeight());
                                score.runtimeSeconds = (System.nanoTime() - start) / 1e9;
                                score.details = "translation="
                                        + RegistrationRecipe.of(translation).id()
                                        + (translationOnlyControl
                                            ? "; rotation=disabled_translation_control"
                                            : "; rotation=" + RegistrationRecipe.of(rotation).id())
                                        + "; rotation_recipe_id=" + candidate.id
                                        + "; rotation_proposal="
                                        + (globalRotationProposal
                                            ? "best_of_translation_seeded_and_global"
                                            : "translation_seeded")
                                        + "; min_rotation_gain=" + precise(rotationGain)
                                        + "; screening_frames=" + measured.getStackSize()
                                        + "; scoring="
                                        + (Boolean.getBoolean("rigid.consecutiveScreen")
                                            ? "native_consecutive_pairs"
                                            : "cumulative_trajectory")
                                        + "; accepted_pairs=" + result.rotationAcceptedPairs()
                                        + "; declined_pairs=" + result.rotationDeclinedPairs();
                                writer.write(resultRow(split, c, SPLIT_ARM, policy, score));
                            } catch (RuntimeException error) {
                                writer.write(failureRow(split, c, SPLIT_ARM, policy,
                                        rootMessage(error)));
                            }
                            writer.newLine();
                            writer.flush();
                            complete.add(key);
                            progress("split rotation sweep", ++done, planned, key);
                        }
                    }
                } finally {
                    if (measured != image) measured.close();
                    image.close();
                }
            }
        }
    }

    private static RelativeIntensityPatternParameters automaticTranslation(ImagePlus image, GeneratedCase c) {
        RelativeIntensityPatternParameters automatic = baseParameters(c).toBuilder()
                .selectionMode(SelectionMode.AUTOMATIC)
                .fitRotation(false).incrementalRotation(false).build();
        AutomaticRegistrationSelector.Result selected =
                RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, automatic);
        return selected.parameters.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(false).incrementalRotation(false).build();
    }

    /** Run the frozen production translation and rotation selectors together, one policy per case. */
    private static void runAutomaticRotationSelector(Path project, String split) throws Exception {
        Path tuning = tuningRoot(project);
        List<GeneratedCase> cases = readInputManifest(project, inputManifest(tuning, split));
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        Set<String> onlyClasses = requested("rigid.onlyImageClass");
        int maxCases = Integer.parseInt(System.getProperty("rigid.maxCases", "2147483647"));
        String runId = System.getProperty(
                "rigid.run", "automatic_rotation_selector_v1").trim();
        Path outputRoot = runRoot(tuning, split, runId);
        Files.createDirectories(outputRoot);
        Path output = outputRoot.resolve("results.csv");
        Candidate policy = new Candidate("automatic_rotation_selector_v1", null);
        Set<String> complete = new LinkedHashSet<>(completedScores(output).keySet());
        boolean append = Files.isRegularFile(output) && Files.size(output) > 0;
        int planned = 0;
        for (GeneratedCase c : cases) {
            if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
            if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
            if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
            if (!onlyClasses.isEmpty() && !onlyClasses.contains(c.recording.imageClass)) continue;
            if (planned < maxCases) planned++;
        }
        int done = 0;
        int selected = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            if (!append) {
                writer.write(RESULT_HEADER);
                writer.newLine();
                writer.flush();
            }
            for (GeneratedCase c : cases) {
                if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
                if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
                if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
                if (!onlyClasses.isEmpty()
                        && !onlyClasses.contains(c.recording.imageClass)) continue;
                if (selected++ >= maxCases) break;
                String key = key(c, SPLIT_ARM, policy.id);
                if (complete.contains(key)) {
                    progress("resumed automatic rotation selector", ++done, planned, key);
                    continue;
                }
                verify(c);
                ImagePlus image = IJ.openImage(c.input.toString());
                if (image == null) throw new IOException("could not open " + c.input);
                try {
                    RelativeIntensityPatternParameters requested = baseParameters(c).toBuilder()
                            .selectionMode(SelectionMode.AUTOMATIC)
                            // The production recommendation protects declared discontinuous motion
                            // from generic step smoothing; keep this end-to-end harness identical.
                            .outlierMads("INTERMITTENT_JUMPS".equals(c.recording.motion)
                                    ? 0.0 : baseParameters(c).outlierMads)
                            .fitRotation(true).incrementalRotation(true).build();
                    long start = System.nanoTime();
                    AutomaticRegistrationSelector.Result translation =
                            RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, requested);
                    AutomaticRotationSelector.Result rotation =
                            AutomaticRotationSelector.fromResolved(translation.parameters);
                    Registration.Result result = RelativeIntensityPatternRegistration.estimate(
                            image, translation.parameters, PairScheduler.Progress.NONE,
                            PairScheduler.Cancellation.NEVER);
                    Score score = score(readTruth(c.truth).joint, result,
                            image.getWidth(), image.getHeight());
                    score.runtimeSeconds = (System.nanoTime() - start) / 1e9;
                    score.details = "translation=" + translation.recipe.id()
                            + "; translation_fallback=" + translation.fallback
                            + "; rotation=" + rotation.recipe.id()
                            + "; rotation_proposal=" + (rotation.globalProposalComparison
                                ? "best_of_translation_seeded_and_global"
                                : "translation_seeded")
                            + "; min_rotation_gain="
                            + precise(rotation.minimumResidualGain)
                            + "; phase_provisional_step90="
                            + precise(rotation.provisionalStep90)
                            + "; accepted_pairs=" + result.rotationAcceptedPairs()
                            + "; declined_pairs=" + result.rotationDeclinedPairs();
                    writer.write(resultRow(split, c, SPLIT_ARM, policy, score));
                } catch (RuntimeException error) {
                    writer.write(failureRow(split, c, SPLIT_ARM, policy,
                            rootMessage(error)));
                } finally {
                    image.close();
                }
                writer.newLine();
                writer.flush();
                complete.add(key);
                progress("automatic rotation selector", ++done, planned, key);
            }
        }
        System.out.println(output);
    }

    /** Export the existing Automatic selector's raw evidence for rotation-selector modelling. */
    private static void runRotationEvidence(Path project, String split) throws Exception {
        Path tuning = tuningRoot(project);
        List<GeneratedCase> cases = readInputManifest(project, inputManifest(tuning, split));
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        Set<String> onlyClasses = requested("rigid.onlyImageClass");
        Path outputRoot = runRoot(tuning, split,
                System.getProperty("rigid.run", "rotation_evidence").trim());
        Files.createDirectories(outputRoot);
        Path output = outputRoot.resolve("evidence.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("split,image_series_class,series_id,motion_category,condition");
            for (String feature : AutomaticRegistrationSelector.FEATURE_NAMES) {
                writer.write(',' + csv(feature));
            }
            writer.newLine();
            for (GeneratedCase c : cases) {
                if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
                if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
                if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
                if (!onlyClasses.isEmpty()
                        && !onlyClasses.contains(c.recording.imageClass)) continue;
                verify(c);
                ImagePlus image = IJ.openImage(c.input.toString());
                if (image == null) throw new IOException("could not open " + c.input);
                try {
                    RelativeIntensityPatternParameters context = baseParameters(c).toBuilder()
                            .selectionMode(SelectionMode.MANUAL)
                            .fitRotation(false).incrementalRotation(false).build();
                    RelativeIntensityPatternParameters neutral = context.toBuilder()
                            .useRecommendation(false)
                            .selectionMode(SelectionMode.MANUAL)
                            .pixelSupport(PairAligner.PixelSupport.ALL)
                            .floorPercentile(Double.NaN).ceilingPercentile(Double.NaN)
                            .preprocessing(ripr.api.Preprocessing.NONE)
                            .pixelSelectionStrategy(
                                    ripr.api.PixelSelectionStrategy.NONE)
                            .pixelSelectionPreprocessing(
                                    ripr.api.Preprocessing.NONE)
                            .build();
                    Registration.Result provisional = RelativeIntensityPatternRegistration.estimate(
                            image, neutral, PairScheduler.Progress.NONE,
                            PairScheduler.Cancellation.NEVER);
                    StackFrames raw = StackFrames.of(
                            image, neutral.channel, neutral.slice);
                    AutomaticRegistrationSelector.Evidence evidence =
                            AutomaticRegistrationSelector.measure(
                                    raw, provisional.cumulative, context);
                    writer.write(csv(split) + ',' + csv(c.recording.imageClass) + ','
                            + csv(c.recording.series) + ',' + csv(c.recording.motion) + ','
                            + csv(c.condition));
                    for (double value : evidence.vector()) {
                        writer.write(',' + precise(value));
                    }
                    writer.newLine();
                    writer.flush();
                } finally {
                    image.close();
                }
            }
        }
        System.out.println(output);
    }

    private static RelativeIntensityPatternParameters rotationParameters(
            GeneratedCase c, Candidate candidate, double rotationGain) {
        RelativeIntensityPatternParameters base = baseParameters(c).toBuilder()
                .fitRotation(true).incrementalRotation(false)
                .minimumRotationResidualGain(rotationGain).build();
        return candidate.recipe == null ? base : candidate.recipe.applyTo(base);
    }

    private static RelativeIntensityPatternParameters baseParameters(GeneratedCase c) {
        RelativeIntensityPatternParameters.Builder builder = RelativeIntensityPatternParameters.builder()
                .recommendation(FullSelectorFactorialBenchmark.imageType(c.recording.imageClass),
                        FullSelectorFactorialBenchmark.motionType(c.recording.motion))
                .selectionMode(SelectionMode.MANUAL)
                .maxRotationDegrees(MAX_ROTATION_DEGREES)
                .autoMaxShift(false)
                .maxShift(FullSelectorFactorialBenchmark.knownMaxShift(c.recording.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .outlierMads(outlierMadsOverride(c))
                .threads(Integer.getInteger("rigid.threads", 0));
        // Twelve evenly spaced frames are about four native frames apart. Lags 1, 2 and 4 therefore
        // reproduce the full benchmark's native lags 4, 8 and 16; carrying 8 and 16 into the sampled
        // stack would compare frames much farther apart than any frozen motion-bound guarantee.
        if (Integer.getInteger("rigid.screenFrames", 0) > 0) builder.lags(1, 2, 4);
        if (Boolean.getBoolean("rigid.consecutiveScreen")) {
            builder.reference(ripr.core.Reconciler.Reference.CONSECUTIVE).lags(1);
        }
        return builder.build();
    }

    private static void warmUpSplit(ImagePlus image, GeneratedCase c,
                                    RelativeIntensityPatternParameters translation, double rotationGain) {
        RelativeIntensityPatternParameters rotation = rotationParameters(
                c, new Candidate("warmup", RegistrationRecipe.allCandidates().get(0)),
                rotationGain);
        RelativeIntensityPatternRegistration.estimateWithRotationRecipe(image, translation, rotation,
                Boolean.getBoolean("rigid.globalRotationProposal"),
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
    }

    /** Evenly sample the complete trajectory, always retaining its first and last frames. */
    private static ImagePlus sampledFrames(ImagePlus image, int wanted) {
        int[] indices = sampledIndices(image.getStackSize(), wanted);
        if (indices.length == image.getStackSize()) return image;
        ImageStack stack = new ImageStack(image.getWidth(), image.getHeight());
        for (int index : indices) {
            stack.addSlice(image.getStack().getSliceLabel(index + 1),
                    image.getStack().getProcessor(index + 1).duplicate());
        }
        return new ImagePlus(image.getTitle() + " [" + indices.length
                + "-frame selector screen]", stack);
    }

    private static Truth sampledTruth(Truth truth, int frames, int wanted) {
        int[] indices = sampledIndices(frames, wanted);
        Transform[] joint = new Transform[indices.length];
        Transform[] oracle = new Transform[indices.length];
        for (int i = 0; i < indices.length; i++) {
            joint[i] = truth.joint[indices[i]];
            oracle[i] = truth.oracle[indices[i]];
        }
        return new Truth(joint, oracle);
    }

    static int[] sampledIndices(int frames, int wanted) {
        if (frames < 1 || wanted < 2) {
            throw new IllegalArgumentException("frames must be positive and wanted at least 2");
        }
        if (wanted >= frames) {
            int[] all = new int[frames];
            for (int i = 0; i < frames; i++) all[i] = i;
            return all;
        }
        int[] out = new int[wanted];
        for (int i = 0; i < wanted; i++) {
            out[i] = (int) Math.round(i * (frames - 1.0) / (wanted - 1.0));
            if (i > 0 && out[i] <= out[i - 1]) out[i] = out[i - 1] + 1;
        }
        out[wanted - 1] = frames - 1;
        return out;
    }

    /** Measure the development-nominated fixed-angle refits without opening held-out inputs. */
    private static void runHybrid(Path project, String split) throws Exception {
        Path tuning = tuningRoot(project);
        List<GeneratedCase> cases = readInputManifest(project,
                inputManifest(tuning, split));
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        String runId = System.getProperty("rigid.run", "r04_hybrid_nominees").trim();
        Path runRoot = runRoot(tuning, split, runId);
        Files.createDirectories(runRoot);
        Path output = runRoot.resolve("results.csv");
        Set<String> complete = new LinkedHashSet<>(completedScores(output).keySet());
        boolean append = Files.isRegularFile(output) && Files.size(output) > 0;
        String policyId = System.getProperty("rigid.hybridPolicy", HYBRID_POLICY).trim();
        Candidate policy = new Candidate(policyId, candidate(UNIVERSAL_ANGLE_RECIPE).recipe);
        int planned = 0;
        for (GeneratedCase c : cases) {
            if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
            if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
            if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
            planned++;
        }
        int done = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!append) {
                writer.write(RESULT_HEADER);
                writer.newLine();
                writer.flush();
            }
            for (GeneratedCase c : cases) {
                if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
                if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
                if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
                String key = key(c, HYBRID_ARM, policy.id);
                if (complete.contains(key)) {
                    progress("resumed", ++done, planned, key);
                    continue;
                }
                verify(c);
                ImagePlus image = IJ.openImage(c.input.toString());
                if (image == null) throw new IOException("could not open " + c.input);
                try {
                    warmUp(image, c, JOINT_ARM);
                    Truth truth = readTruth(c.truth);
                    Score score = measureHybrid(image, c, truth);
                    writer.write(resultRow(split, c, HYBRID_ARM, policy, score));
                } catch (RuntimeException error) {
                    writer.write(failureRow(split, c, HYBRID_ARM, policy, rootMessage(error)));
                } finally {
                    image.close();
                }
                writer.newLine();
                writer.flush();
                complete.add(key);
                progress("hybrid sweep", ++done, planned, key);
            }
        }
        audit(project, split);
    }

    private static Score measureHybrid(ImagePlus image, GeneratedCase c, Truth truth) {
        Candidate angleCandidate = candidate(UNIVERSAL_ANGLE_RECIPE);
        long start = System.nanoTime();
        Registration.Result angle = RelativeIntensityPatternRegistration.estimate(image,
                parameters(c, JOINT_ARM, angleCandidate), PairScheduler.Progress.NONE,
                PairScheduler.Cancellation.NEVER);
        String translationId = hybridTranslationRecipe(c.recording.imageClass);
        Registration.Result result = angle;
        if (translationId != null) {
            Candidate translationCandidate = candidate(translationId);
            RelativeIntensityPatternParameters translation = parameters(c, ORACLE_ARM, translationCandidate);
            StackFrames nativeSource = StackFrames.of(image, translation.channel, translation.slice);
            FrameSource source = translation.preprocessing == ripr.api.Preprocessing.NONE
                    ? nativeSource : new PreprocessedFrameSource(nativeSource,
                            translation.preprocessing);
            result = Registration.refitWithSupport(source, translation.registrationOptions(), angle,
                    null, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        }
        long elapsed = System.nanoTime() - start;
        Score score = score(truth.joint, result, image.getWidth(), image.getHeight());
        if (result != angle) addDiagnostics(score, angle);
        score.runtimeSeconds = elapsed / 1e9;
        score.details = "angle=" + UNIVERSAL_ANGLE_RECIPE + "; translation="
                + (translationId == null ? "joint angle pass retained" : translationId)
                + "; intermittent repair="
                + ("INTERMITTENT_JUMPS".equals(c.recording.motion) ? "off" : "6 MADs");
        return score;
    }

    /** Tune a Phase-only rotation confidence rule without opening integration inputs. */
    private static void runConfidence(Path project, String split) throws Exception {
        if (!"development".equals(split) && !"fresh-development".equals(split)
                && !Boolean.getBoolean("rigid.allowDiagnosticIntegration")) {
            throw new IllegalArgumentException("confidence thresholds may only be tuned on development; "
                    + "set rigid.allowDiagnosticIntegration=true only after freezing a diagnostic run");
        }
        Path tuning = tuningRoot(project);
        List<GeneratedCase> cases = readInputManifest(project,
                inputManifest(tuning, split));
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        double[] thresholds = confidenceThresholds();
        String runId = System.getProperty("rigid.run", "r07_phase_confidence").trim();
        Path runRoot = runRoot(tuning, split, runId);
        Files.createDirectories(runRoot);
        Path output = runRoot.resolve("results.csv");
        Set<String> complete = new LinkedHashSet<>(completedScores(output).keySet());
        boolean append = Files.isRegularFile(output) && Files.size(output) > 0;
        String confidenceRecipe = System.getProperty(
                "rigid.confidenceRecipe", PHASE_CONFIDENCE_RECIPE).trim();
        Candidate recipe = candidate(confidenceRecipe);
        int planned = 0;
        for (GeneratedCase c : cases) {
            if (!"PHASE".equals(c.recording.imageClass)) continue;
            if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
            if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
            if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
            planned += thresholds.length;
        }
        int done = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!append) {
                writer.write(RESULT_HEADER);
                writer.newLine();
                writer.flush();
            }
            for (GeneratedCase c : cases) {
                if (!"PHASE".equals(c.recording.imageClass)) continue;
                if (!onlySeries.isEmpty() && !onlySeries.contains(c.recording.series)) continue;
                if (!onlyMotions.isEmpty() && !onlyMotions.contains(c.recording.motion)) continue;
                if (!onlyConditions.isEmpty() && !onlyConditions.contains(c.condition)) continue;
                verify(c);
                ImagePlus image = IJ.openImage(c.input.toString());
                if (image == null) throw new IOException("could not open " + c.input);
                try {
                    warmUp(image, c, JOINT_ARM);
                    Truth truth = readTruth(c.truth);
                    for (double threshold : thresholds) {
                        Candidate policy = new Candidate(confidenceId(threshold), recipe.recipe);
                        String key = key(c, JOINT_ARM, policy.id);
                        if (complete.contains(key)) {
                            progress("resumed confidence", ++done, planned, key);
                            continue;
                        }
                        try {
                            Score measured = measureConfidence(
                                    image, c, truth, recipe, threshold);
                            writer.write(resultRow(split, c, JOINT_ARM, policy, measured));
                        } catch (RuntimeException error) {
                            writer.write(failureRow(split, c, JOINT_ARM, policy,
                                    rootMessage(error)));
                        }
                        writer.newLine();
                        writer.flush();
                        complete.add(key);
                        progress("phase confidence", ++done, planned, key);
                    }
                } finally {
                    image.close();
                }
            }
        }
    }

    private static Score measureConfidence(ImagePlus image, GeneratedCase c, Truth truth,
                                           Candidate recipe, double threshold) {
        RelativeIntensityPatternParameters parameters = parameters(c, JOINT_ARM, recipe);
        if (parameters.estimationScale != 1.0) {
            throw new IllegalArgumentException(
                    "confidence benchmark currently requires a native-scale recipe");
        }
        StackFrames nativeSource = StackFrames.of(image, parameters.channel, parameters.slice);
        FrameSource source = parameters.preprocessing == ripr.api.Preprocessing.NONE
                ? nativeSource : new PreprocessedFrameSource(nativeSource,
                        parameters.preprocessing);
        Registration.Options options = parameters.registrationOptions();
        boolean incremental = Boolean.getBoolean("rigid.incrementalRotation");
        options.incrementalRotation = incremental;
        options.confidenceGatedRotation = !incremental;
        options.minimumRotationResidualGain = threshold;
        long start = System.nanoTime();
        Registration.Result result;
        if (parameters.pixelSelectionStrategy == ripr.api.PixelSelectionStrategy.NONE) {
            result = Registration.run(source, options,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        } else {
            FrameSource scoringPrepared = parameters.pixelSelectionPreprocessing
                    == ripr.api.Preprocessing.NONE
                    ? nativeSource : new PreprocessedFrameSource(nativeSource,
                            parameters.pixelSelectionPreprocessing);
            result = PixelSelectionEngine.run(source, scoringPrepared, nativeSource, options,
                    parameters.pixelSelectionStrategy, parameters.pixelRemovalPercent,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        }
        long elapsed = System.nanoTime() - start;
        Score score = score(truth.joint, result, image.getWidth(), image.getHeight());
        score.runtimeSeconds = elapsed / 1e9;
        List<Double> gains = new ArrayList<>();
        int accepted = 0;
        int declined = 0;
        for (Registration.PairResult pair : result.pairs) {
            PairAligner.RotationEvidence evidence = pair.fit.rotationEvidence;
            if (evidence == null) continue;
            if (Double.isFinite(evidence.residualGain)) gains.add(evidence.residualGain);
            if (evidence.accepted) accepted++;
            else declined++;
        }
        gains.sort(Double::compareTo);
        score.details = RegistrationRecipe.of(parameters).describe()
                + (incremental
                    ? "; selected translation plus incremental rotation"
                    : "; global rigid plus zero-angle comparison")
                + "; min residual gain="
                + precise(threshold) + "; accepted pairs=" + accepted + "; declined pairs="
                + declined + "; median evidence gain=" + precise(percentile(gains, 0.5))
                + "; p10 evidence gain=" + precise(percentile(gains, 0.1));
        return score;
    }

    private static double[] confidenceThresholds() {
        String raw = System.getProperty("rigid.rotationGains",
                "0,0.0001,0.00025,0.0005,0.001,0.002,0.005,0.01");
        String[] fields = raw.split(",");
        double[] out = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            out[i] = Double.parseDouble(fields[i].trim());
            if (!Double.isFinite(out[i]) || out[i] < 0) {
                throw new IllegalArgumentException("rotation gains must be finite and >= 0");
            }
        }
        return out;
    }

    private static String confidenceId(double threshold) {
        String prefix = Boolean.getBoolean("rigid.incrementalRotation")
                ? "incremental_rotation_min_gain_" : "global_rigid_min_gain_";
        return prefix + precise(threshold).replace('.', '_');
    }

    static String splitConfidenceId(String recipe, double threshold) {
        return recipe + "__min_rotation_gain_" + precise(threshold).replace('.', '_');
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return Double.NaN;
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String hybridTranslationRecipe(String imageClass) {
        if ("BRIGHTFIELD_DIC".equals(imageClass)) {
            return "support_gradient__band_full__filter_gaussian_0_7__mask_none";
        }
        if ("DENSE_FLUOR".equals(imageClass)) {
            return System.getProperty("rigid.hybridDenseRecipe",
                    "support_all__band_full__filter_gaussian_1_0__mask_none").trim();
        }
        if ("FIDUCIAL_STATIC".equals(imageClass)) {
            return "support_all__band_no_bottom_25__filter_gaussian_0_7__mask_none";
        }
        return null;
    }

    private static Score measure(ImagePlus image, GeneratedCase c, String arm,
                                 Candidate candidate, Truth truth) {
        RelativeIntensityPatternParameters parameters = parameters(c, arm, candidate);
        long start = System.nanoTime();
        Registration.Result result = RelativeIntensityPatternRegistration.estimate(image, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        long elapsed = System.nanoTime() - start;
        Transform[] expected = JOINT_ARM.equals(arm) ? truth.joint : truth.oracle;
        Score score = score(expected, result, image.getWidth(), image.getHeight());
        score.runtimeSeconds = elapsed / 1e9;
        score.details = RegistrationRecipe.of(parameters).describe();
        return score;
    }

    private static void warmUp(ImagePlus image, GeneratedCase c, String arm) {
        Candidate neutral = new Candidate("warmup", RegistrationRecipe.allCandidates().get(0));
        RelativeIntensityPatternRegistration.estimate(image, parameters(c, arm, neutral),
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
    }

    private static RelativeIntensityPatternParameters parameters(GeneratedCase c, String arm,
                                                  Candidate candidate) {
        boolean rigid = JOINT_ARM.equals(arm);
        RelativeIntensityPatternParameters base = RelativeIntensityPatternParameters.builder()
                .recommendation(FullSelectorFactorialBenchmark.imageType(c.recording.imageClass),
                        FullSelectorFactorialBenchmark.motionType(c.recording.motion))
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(rigid)
                .maxRotationDegrees(MAX_ROTATION_DEGREES)
                .autoMaxShift(false)
                .maxShift(FullSelectorFactorialBenchmark.knownMaxShift(c.recording.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .outlierMads(outlierMadsOverride(c))
                .build();
        return candidate.recipe == null
                ? base : candidate.recipe.applyTo(base);
    }

    private static double outlierMadsOverride(GeneratedCase c) {
        if (Boolean.getBoolean("rigid.motionAwareRepair")
                && "INTERMITTENT_JUMPS".equals(c.recording.motion)) {
            return 0;
        }
        String value = System.getProperty("rigid.outlierMads", "6.0").trim();
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed) || parsed < 0) {
            throw new IllegalArgumentException("rigid.outlierMads must be finite and >= 0");
        }
        return parsed;
    }

    static Score score(Transform[] truth, Registration.Result result, int width, int height) {
        if (truth.length != result.cumulative.length) {
            throw new IllegalArgumentException("truth/result frame mismatch");
        }
        double[] central = new double[truth.length];
        double[] full = new double[truth.length];
        double[] angle = new double[truth.length];
        double[] translation = new double[truth.length];
        for (int i = 0; i < truth.length; i++) {
            ThevenazProtocolBenchmark.Affine2D expected =
                    ThevenazProtocolBenchmark.Affine2D.rigid(truth[i], width, height);
            ThevenazProtocolBenchmark.Affine2D actual =
                    ThevenazProtocolBenchmark.Affine2D.rigid(result.cumulative[i], width, height);
            central[i] = ThevenazProtocolBenchmark.warpingIndex(expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.CENTRAL_50);
            full[i] = ThevenazProtocolBenchmark.warpingIndex(expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.FULL_FRAME);
            angle[i] = Math.abs(Math.toDegrees(result.cumulative[i].theta - truth[i].theta));
            translation[i] = Math.hypot(result.cumulative[i].dx - truth[i].dx,
                    result.cumulative[i].dy - truth[i].dy);
        }
        Score score = new Score();
        score.medianCentral = percentile(central, 0.50);
        score.p90Central = percentile(central, 0.90);
        score.worstCentral = percentile(central, 1.00);
        score.medianFull = percentile(full, 0.50);
        score.p90Full = percentile(full, 0.90);
        score.worstFull = percentile(full, 1.00);
        score.medianAngle = percentile(angle, 0.50);
        score.p90Angle = percentile(angle, 0.90);
        score.worstAngle = percentile(angle, 1.00);
        score.medianTranslation = percentile(translation, 0.50);
        score.worstTranslation = percentile(translation, 1.00);
        addDiagnostics(score, result);
        Warper.Margin margin = Warper.validMargin(result.cumulative, width, height,
                Warper.Interpolation.NONE);
        score.retainedCropFraction = margin.croppedWidth(width) * (double) margin.croppedHeight(height)
                / (width * (double) height);
        return score;
    }

    /**
     * Score the native consecutive-pair screen without allowing chain accumulation to obscure the
     * quality of the angle estimator itself. Full shortlist and held-out runs continue to use
     * {@link #score(Transform[], Registration.Result, int, int)} and therefore judge the reconciled
     * cumulative stack.
     */
    static Score scorePairs(Transform[] truth, Registration.Result result, int width, int height) {
        if (truth.length != result.cumulative.length) {
            throw new IllegalArgumentException("truth/result frame mismatch");
        }
        List<Double> central = new ArrayList<>();
        List<Double> full = new ArrayList<>();
        List<Double> angle = new ArrayList<>();
        List<Double> translation = new ArrayList<>();
        List<Transform> measured = new ArrayList<>();
        for (Registration.PairResult pair : result.pairs) {
            if (pair.from < 0 || pair.from >= truth.length
                    || pair.to < 0 || pair.to >= truth.length) {
                throw new IllegalArgumentException("pair index outside truth trajectory");
            }
            if (pair.fit == null || !pair.fit.usable()) continue;
            Transform expectedTransform = pairTruth(truth, pair.from, pair.to);
            Transform actualTransform = pair.fit.transform;
            ThevenazProtocolBenchmark.Affine2D expected =
                    ThevenazProtocolBenchmark.Affine2D.rigid(
                            expectedTransform, width, height);
            ThevenazProtocolBenchmark.Affine2D actual =
                    ThevenazProtocolBenchmark.Affine2D.rigid(
                            actualTransform, width, height);
            central.add(ThevenazProtocolBenchmark.warpingIndex(
                    expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.CENTRAL_50));
            full.add(ThevenazProtocolBenchmark.warpingIndex(
                    expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.FULL_FRAME));
            angle.add(Math.abs(Math.toDegrees(
                    actualTransform.theta - expectedTransform.theta)));
            translation.add(Math.hypot(
                    actualTransform.dx - expectedTransform.dx,
                    actualTransform.dy - expectedTransform.dy));
            measured.add(actualTransform);
        }
        if (central.isEmpty()) {
            throw new IllegalStateException("consecutive screen produced no usable pair fits");
        }
        Score score = new Score();
        score.medianCentral = percentile(toArray(central), 0.50);
        score.p90Central = percentile(toArray(central), 0.90);
        score.worstCentral = percentile(toArray(central), 1.00);
        score.medianFull = percentile(toArray(full), 0.50);
        score.p90Full = percentile(toArray(full), 0.90);
        score.worstFull = percentile(toArray(full), 1.00);
        score.medianAngle = percentile(toArray(angle), 0.50);
        score.p90Angle = percentile(toArray(angle), 0.90);
        score.worstAngle = percentile(toArray(angle), 1.00);
        score.medianTranslation = percentile(toArray(translation), 0.50);
        score.worstTranslation = percentile(toArray(translation), 1.00);
        addDiagnostics(score, result);
        Transform[] pairTransforms = measured.toArray(new Transform[0]);
        Warper.Margin margin = Warper.validMargin(
                pairTransforms, width, height, Warper.Interpolation.NONE);
        score.retainedCropFraction = margin.croppedWidth(width)
                * (double) margin.croppedHeight(height) / (width * (double) height);
        return score;
    }

    static Transform pairTruth(Transform[] trajectory, int from, int to) {
        return trajectory[from].inverse().then(trajectory[to]);
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static void addDiagnostics(Score score, Registration.Result result) {
        for (Registration.PairResult pair : result.pairs) {
            if (pair.fit == null) {
                score.refused++;
                continue;
            }
            switch (pair.fit.status) {
                case REFUSED_LOW_OVERLAP: score.refused++; break;
                case NOT_CONVERGED: score.nonConverged++; break;
                case AT_SHIFT_BOUND: score.shiftBound++; break;
                case AT_ROTATION_BOUND: score.rotationBound++; break;
                case AT_SHIFT_AND_ROTATION_BOUND: score.bothBound++; break;
                default: break;
            }
        }
        for (int i = 0; i < result.repairs.length; i++) {
            ChainRepair.Reason reason = result.repairs[i];
            if (reason != null) score.repaired++;
            if (result.support[i] == 0) score.unsupported++;
        }
    }

    private static double percentile(double[] values, double fraction) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double position = (sorted.length - 1) * fraction;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double weight = position - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    // -------------------------------------------------------------------------------- audit/manifests

    private static void audit(Path project, String split) throws Exception {
        Path tuning = tuningRoot(project);
        Path manifest = inputManifest(tuning, split);
        List<GeneratedCase> cases = readInputManifest(project, manifest);
        for (GeneratedCase c : cases) verify(c);
        List<Candidate> candidates = candidates();
        if (candidates.size() != EXPECTED_RECIPES + 1) {
            throw new IOException("expected category plus " + EXPECTED_RECIPES + " recipes, got "
                    + candidates.size());
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (!ids.add(candidate.id)) throw new IOException("duplicate recipe " + candidate.id);
        }
        String runId = System.getProperty("rigid.run", "r02_full_factorial").trim();
        Path results = runRoot(tuning, split, runId).resolve("results.csv");
        if (Files.isRegularFile(results)) {
            List<String> lines = Files.readAllLines(results, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !RESULT_HEADER.equals(lines.get(0))) {
                throw new IOException("result header drifted in " + results);
            }
            Set<String> keys = new LinkedHashSet<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().isEmpty()) continue;
                List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
                if (row.size() != FullSelectorFactorialBenchmark.fields(RESULT_HEADER).size()) {
                    throw new IOException("malformed result row " + (i + 1));
                }
                String key = row.get(1) + '/' + row.get(2) + '/' + row.get(4) + '/'
                        + row.get(5) + '/' + row.get(6) + '/' + row.get(7);
                if (!keys.add(key)) throw new IOException("duplicate result " + key);
            }
            System.out.printf(Locale.ROOT, "audit passed: %d inputs, %d recipes, %d results%n",
                    cases.size(), candidates.size(), keys.size());
        } else {
            System.out.printf(Locale.ROOT, "audit passed: %d inputs, %d recipes, no results yet%n",
                    cases.size(), candidates.size());
        }
    }

    private static void writeInputManifest(Path path, Path project, String split,
            List<GeneratedCase> cases)
            throws IOException {
        StringBuilder out = new StringBuilder("case_id,split,image_series_class,series_id,"
                + "independent_group,motion_category,condition,input_relative_path,input_sha256,"
                + "oracle_relative_path,oracle_sha256,truth_relative_path,truth_sha256,"
                + "source_relative_path,source_sha256\n");
        for (GeneratedCase c : cases) {
            out.append(csv(c.caseId)).append(',').append(csv(split)).append(',')
                    .append(csv(c.recording.imageClass)).append(',')
                    .append(csv(c.recording.series)).append(',')
                    .append(csv(c.recording.independentGroup)).append(',')
                    .append(csv(c.recording.motion)).append(',').append(csv(c.condition)).append(',')
                    .append(csv(relative(project, c.input))).append(',').append(csv(c.inputSha256))
                    .append(',').append(csv(relative(project, c.oracle)))
                    .append(',').append(csv(c.oracleSha256)).append(',')
                    .append(csv(relative(project, c.truth))).append(',').append(csv(c.truthSha256))
                    .append(',').append(csv(relative(project, c.recording.input))).append(',')
                    .append(csv(c.recording.inputSha256)).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFreshInputManifest(Path path, Path project, String split,
            List<GeneratedCase> cases) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder out = new StringBuilder("case_id,split,image_series_class,series_id,"
                + "independent_group,motion_category,condition,input_relative_path,input_sha256,"
                + "oracle_relative_path,oracle_sha256,truth_relative_path,truth_sha256,"
                + "source_relative_path,source_sha256,native_frames\n");
        for (GeneratedCase c : cases) {
            out.append(csv(c.caseId)).append(',').append(csv(split)).append(',')
                    .append(csv(c.recording.imageClass)).append(',')
                    .append(csv(c.recording.series)).append(',')
                    .append(csv(c.recording.independentGroup)).append(',')
                    .append(csv(c.recording.motion)).append(',').append(csv(c.condition)).append(',')
                    .append(csv(relative(project, c.input))).append(',').append(csv(c.inputSha256))
                    .append(',').append(csv(relative(project, c.oracle))).append(',')
                    .append(csv(c.oracleSha256)).append(',')
                    .append(csv(relative(project, c.truth))).append(',').append(csv(c.truthSha256))
                    .append(',').append(csv(relative(project, c.recording.input))).append(',')
                    .append(csv(c.recording.inputSha256)).append(',')
                    .append(c.recording.frames).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<GeneratedCase> readInputManifest(Path project, Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("empty " + path);
        List<GeneratedCase> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (row.size() != 15 && row.size() != 16) {
                throw new IOException("bad input manifest row " + (i + 1));
            }
            Path input = project.resolve(row.get(7)).normalize();
            Path oracle = row.get(9).isEmpty() ? null : project.resolve(row.get(9)).normalize();
            Path truth = project.resolve(row.get(11)).normalize();
            Path source = project.resolve(row.get(13)).normalize();
            int frames = row.size() == 16 ? Integer.parseInt(row.get(15)) : Benchmark.FRAMES;
            FullSelectorFactorialBenchmark.Recording recording =
                    new FullSelectorFactorialBenchmark.Recording(row.get(2), row.get(3), row.get(5),
                            "CLEAN", source.getParent(), source, frames, row.get(4),
                            row.get(14), Files.isRegularFile(source) ? Files.size(source) : 0);
            out.add(new GeneratedCase(row.get(0), recording, row.get(6), input, oracle, truth,
                    row.get(8), row.get(10), row.get(12)));
        }
        out.sort(Comparator.comparing(c -> c.caseId));
        return out;
    }

    private static void writeRecipeManifest(Path path) throws IOException {
        StringBuilder out = new StringBuilder("recipe_id,contextual,estimator,description\n");
        for (Candidate candidate : candidates()) {
            out.append(csv(candidate.id)).append(',').append(candidate.recipe == null).append(',')
                    .append(csv(candidate.estimator())).append(',')
                    .append(csv(candidate.recipe == null
                            ? "category recommendation resolved separately for each image and motion type"
                            : candidate.recipe.describe()))
                    .append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEnvironment(Path path, Path project, String split,
            List<FullSelectorFactorialBenchmark.Recording> recordings, List<GeneratedCase> cases)
            throws IOException {
        String content = "run_id=" + RUN_ID + '\n'
                + "split=" + split + '\n'
                + "project=" + project + '\n'
                + "java=" + System.getProperty("java.version") + '\n'
                + "operating_system=" + System.getProperty("os.name") + " "
                    + System.getProperty("os.version") + '\n'
                + "source_recordings=" + recordings.size() + '\n'
                + "generated_cases=" + cases.size() + '\n'
                + "candidate_recipes=" + EXPECTED_RECIPES + '\n'
                + "contextual_category_arms=1\n"
                + "maximum_rotation_degrees=" + MAX_ROTATION_DEGREES + '\n';
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        out.add(new Candidate("category_recommendation", null));
        List<RegistrationRecipe> recipes = RegistrationRecipe.allCandidates();
        if (recipes.size() != EXPECTED_RECIPES) {
            throw new IllegalStateException("expected " + EXPECTED_RECIPES + " recipes, got "
                    + recipes.size());
        }
        for (RegistrationRecipe recipe : recipes) out.add(new Candidate(recipe.id(), recipe));
        return out;
    }

    private static Candidate candidate(String id) {
        for (Candidate candidate : candidates()) {
            if (candidate.id.equals(id)) return candidate;
        }
        throw new IllegalArgumentException("unknown rigid recipe " + id);
    }

    private static String[] arms(GeneratedCase c) {
        return new String[]{JOINT_ARM, ORACLE_ARM};
    }

    private static Map<String, Score> completedScores(Path output) throws IOException {
        Map<String, Score> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(output)) return out;
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !RESULT_HEADER.equals(lines.get(0))) {
            throw new IOException("result header drifted in " + output);
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (row.size() != 32 || !"ok".equals(row.get(9))) continue;
            Score score = new Score();
            score.medianCentral = Double.parseDouble(row.get(11));
            score.p90Central = Double.parseDouble(row.get(12));
            score.worstCentral = Double.parseDouble(row.get(13));
            score.medianFull = Double.parseDouble(row.get(14));
            score.p90Full = Double.parseDouble(row.get(15));
            score.worstFull = Double.parseDouble(row.get(16));
            score.medianAngle = Double.parseDouble(row.get(17));
            score.p90Angle = Double.parseDouble(row.get(18));
            score.worstAngle = Double.parseDouble(row.get(19));
            score.medianTranslation = Double.parseDouble(row.get(20));
            score.worstTranslation = Double.parseDouble(row.get(21));
            score.refused = Integer.parseInt(row.get(22));
            score.nonConverged = Integer.parseInt(row.get(23));
            score.shiftBound = Integer.parseInt(row.get(24));
            score.rotationBound = Integer.parseInt(row.get(25));
            score.bothBound = Integer.parseInt(row.get(26));
            score.repaired = Integer.parseInt(row.get(27));
            score.unsupported = Integer.parseInt(row.get(28));
            score.retainedCropFraction = Double.parseDouble(row.get(29));
            score.runtimeSeconds = Double.parseDouble(row.get(30));
            score.details = row.get(31);
            out.put(row.get(1) + '/' + row.get(2) + '/' + row.get(4) + '/' + row.get(5) + '/'
                    + row.get(6) + '/' + row.get(7), score);
        }
        return out;
    }

    private static String rigidAliasTwin(Candidate candidate, String arm) {
        String prefix = "estimator_area_correlation_newton__";
        if (!JOINT_ARM.equals(arm) || !candidate.id.startsWith(prefix)) return null;
        return "estimator_area_correlation__" + candidate.id.substring(prefix.length());
    }

    private static void verify(GeneratedCase c) throws IOException {
        verify(c.input, c.inputSha256);
        verify(c.oracle, c.oracleSha256);
        verify(c.truth, c.truthSha256);
    }

    private static void verify(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + path);
        String actual = sha256(path);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("hash mismatch for " + path + ": " + actual + " != " + expected);
        }
    }

    private static Truth readTruth(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Transform[] joint = new Transform[lines.size() - 1];
        Transform[] oracle = new Transform[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) {
            String[] row = lines.get(i).split(",", -1);
            joint[i - 1] = new Transform(Double.parseDouble(row[1]), Double.parseDouble(row[2]),
                    Double.parseDouble(row[3]));
            oracle[i - 1] = new Transform(Double.parseDouble(row[4]), Double.parseDouble(row[5]),
                    Double.parseDouble(row[6]));
        }
        return new Truth(joint, oracle);
    }

    // -------------------------------------------------------------------------------- rows/helpers

    private static String resultRow(String split, GeneratedCase c, String arm,
                                    Candidate candidate, Score s) {
        return csv(split) + ',' + csv(c.recording.imageClass) + ',' + csv(c.recording.series) + ','
                + csv(c.recording.independentGroup) + ',' + csv(c.recording.motion) + ','
                + csv(c.condition) + ',' + csv(arm) + ',' + csv(candidate.id) + ','
                + csv(candidate.estimator()) + ",ok," + c.recording.frames + ','
                + precise(s.medianCentral) + ',' + precise(s.p90Central) + ','
                + precise(s.worstCentral) + ',' + precise(s.medianFull) + ','
                + precise(s.p90Full) + ',' + precise(s.worstFull) + ','
                + precise(s.medianAngle) + ',' + precise(s.p90Angle) + ','
                + precise(s.worstAngle) + ',' + precise(s.medianTranslation) + ','
                + precise(s.worstTranslation) + ',' + s.refused + ',' + s.nonConverged + ','
                + s.shiftBound + ',' + s.rotationBound + ',' + s.bothBound + ',' + s.repaired + ','
                + s.unsupported + ',' + precise(s.retainedCropFraction) + ','
                + precise(s.runtimeSeconds) + ',' + csv(s.details);
    }

    private static String failureRow(String split, GeneratedCase c, String arm,
                                     Candidate candidate, String failure) {
        return csv(split) + ',' + csv(c.recording.imageClass) + ',' + csv(c.recording.series) + ','
                + csv(c.recording.independentGroup) + ',' + csv(c.recording.motion) + ','
                + csv(c.condition) + ',' + csv(arm) + ',' + csv(candidate.id) + ','
                + csv(candidate.estimator()) + ",failed," + c.recording.frames
                + ",NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,0,0,0,0,0,0,0,NaN,NaN,"
                + csv(failure);
    }

    private static String key(GeneratedCase c, String arm, String recipe) {
        return c.recording.imageClass + '/' + c.recording.series + '/' + c.recording.motion + '/'
                + c.condition + '/' + arm + '/' + recipe;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + (root.getMessage() == null ? ""
                : ": " + root.getMessage());
    }

    private static void progress(String phase, int done, int total, String detail) {
        if (done == 1 || done == total || done % 10 == 0) {
            long now = System.nanoTime();
            Long start = PROGRESS_STARTS.get(phase);
            if (start == null || done <= 1) {
                start = now;
                PROGRESS_STARTS.put(phase, start);
            }
            double elapsed = Math.max(0, (now - start) / 1e9);
            double eta = done > 0 && total > done ? elapsed * (total - done) / done : 0;
            System.out.printf(Locale.ROOT,
                    "%s %,d/%,d (%.1f%%), elapsed %s, ETA %s: %s%n",
                    phase, done, total, total == 0 ? 100 : 100.0 * done / total,
                    duration(elapsed), duration(eta), detail);
        }
    }

    private static Path sourceRoot(Path project, String split) {
        if ("development".equals(split)) {
            return project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        }
        if ("integration".equals(split)) {
            return project.resolve("library/benchmark/v2/benchmarks/sealed_test_3");
        }
        if ("phase-development".equals(split)) {
            return project.resolve(PhaseRotationFreshSetBuilder.DEVELOPMENT_ROOT);
        }
        if ("phase-validation".equals(split)) {
            return project.resolve(PhaseRotationFreshSetBuilder.VALIDATION_ROOT);
        }
        throw new IllegalArgumentException(split);
    }

    private static Path tuningRoot(Path project) {
        return project.resolve("library/rigid_selector_tuning");
    }

    private static Path inputManifest(Path tuning, String split) {
        if (split.startsWith("fresh-")) {
            return tuning.resolve("fresh_recovery")
                    .resolve("input_manifest_" + split.substring("fresh-".length()) + ".csv");
        }
        return tuning.resolve("input_manifest_" + split + ".csv");
    }

    private static Path runRoot(Path tuning, String split, String runId) {
        if (split.startsWith("fresh-")) {
            return tuning.resolve("fresh_recovery").resolve("runs")
                    .resolve(split.substring("fresh-".length())).resolve(runId);
        }
        return tuning.resolve("runs").resolve(split).resolve(runId);
    }

    private static String duration(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return "unknown";
        long rounded = Math.round(seconds);
        long hours = rounded / 3600;
        long minutes = (rounded % 3600) / 60;
        long remainder = rounded % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, remainder);
        if (minutes > 0) return String.format(Locale.ROOT, "%dm%02ds", minutes, remainder);
        return remainder + "s";
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static Set<String> requested(String property) {
        Set<String> out = new LinkedHashSet<>();
        for (String item : System.getProperty(property, "").split(",")) {
            if (!item.trim().isEmpty()) out.add(item.trim());
        }
        return out;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String csv(String value) {
        if (value == null) value = "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String precise(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "NaN";
    }

    static final class Candidate {
        final String id;
        final RegistrationRecipe recipe;

        Candidate(String id, RegistrationRecipe recipe) {
            this.id = id;
            this.recipe = recipe;
        }

        String estimator() {
            return recipe == null ? "LOG_RATIO_FIT" : recipe.estimator.name();
        }
    }

    private static final class FreshSource {
        final String imageClass;
        final String series;
        final String independentGroup;
        final int channel;
        final int frames;
        final int width;
        final int height;
        final Path path;
        final String sha256;

        FreshSource(String imageClass, String series, String independentGroup, int channel,
                    int frames, int width, int height, Path path, String sha256) {
            this.imageClass = imageClass;
            this.series = series;
            this.independentGroup = independentGroup;
            this.channel = channel;
            this.frames = frames;
            this.width = width;
            this.height = height;
            this.path = path;
            this.sha256 = sha256;
        }
    }

    static final class GeneratedCase {
        final String caseId;
        final FullSelectorFactorialBenchmark.Recording recording;
        final String condition;
        final Path input;
        final Path oracle;
        final Path truth;
        final String inputSha256;
        final String oracleSha256;
        final String truthSha256;

        GeneratedCase(String caseId, FullSelectorFactorialBenchmark.Recording recording,
                      String condition, Path input, Path oracle, Path truth, String inputSha256,
                      String oracleSha256, String truthSha256) {
            this.caseId = caseId;
            this.recording = recording;
            this.condition = condition;
            this.input = input;
            this.oracle = oracle;
            this.truth = truth;
            this.inputSha256 = inputSha256;
            this.oracleSha256 = oracleSha256;
            this.truthSha256 = truthSha256;
        }
    }

    static final class Truth {
        final Transform[] joint;
        final Transform[] oracle;

        Truth(Transform[] joint, Transform[] oracle) {
            this.joint = joint;
            this.oracle = oracle;
        }
    }

    static final class Score {
        double medianCentral;
        double p90Central;
        double worstCentral;
        double medianFull;
        double p90Full;
        double worstFull;
        double medianAngle;
        double p90Angle;
        double worstAngle;
        double medianTranslation;
        double worstTranslation;
        int refused;
        int nonConverged;
        int shiftBound;
        int rotationBound;
        int bothBound;
        int repaired;
        int unsupported;
        double retainedCropFraction;
        double runtimeSeconds;
        String details;

        Score copy() {
            Score out = new Score();
            out.medianCentral = medianCentral;
            out.p90Central = p90Central;
            out.worstCentral = worstCentral;
            out.medianFull = medianFull;
            out.p90Full = p90Full;
            out.worstFull = worstFull;
            out.medianAngle = medianAngle;
            out.p90Angle = p90Angle;
            out.worstAngle = worstAngle;
            out.medianTranslation = medianTranslation;
            out.worstTranslation = worstTranslation;
            out.refused = refused;
            out.nonConverged = nonConverged;
            out.shiftBound = shiftBound;
            out.rotationBound = rotationBound;
            out.bothBound = bothBound;
            out.repaired = repaired;
            out.unsupported = unsupported;
            out.retainedCropFraction = retainedCropFraction;
            out.runtimeSeconds = runtimeSeconds;
            out.details = details;
            return out;
        }
    }
}
