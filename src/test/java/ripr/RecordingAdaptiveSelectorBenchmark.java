/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.AutomaticRegistrationSelectorModel;
import ripr.api.AutomaticRotationSelector;
import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.MotionType;
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Frozen, resumable recording-adaptive recipe benchmark. */
public final class RecordingAdaptiveSelectorBenchmark {
    static final String RUN_ID = "recording_adaptive_selector_v1";
    static final int FRAMES = 24;
    static final int CROP_LIMIT = 128;
    static final String[] MOTIONS = {
            "CURVED_OSCILLATING_DRIFT", "STEADY_DIRECTIONAL_DRIFT",
            "SUBPIXEL_RANDOM_WALK", "INTERMITTENT_JUMPS"};
    static final String TRANSLATION_CLEAN = "TRANSLATION_CLEAN";
    static final String RIGID_CLEAN = "RIGID_CLEAN";
    static final String TRANSLATION_GAIN = "TRANSLATION_GAIN_FADE_0_5";
    static final String RIGID_GAIN = "RIGID_GAIN_FADE_0_5";
    static final String RIGID_ZERO = "RIGID_ZERO_ROTATION_CLEAN";
    static final String CATEGORY = "category_recommendation";

    static final String OUTCOME_HEADER = "case_id,partition,image_type,source_series_id,"
            + "independent_group,declared_motion_type,condition,recipe_id,estimator,status,frames,"
            + "median_warp_central50_px,p90_warp_central50_px,worst_warp_central50_px,"
            + "median_warp_full_px,p90_warp_full_px,worst_warp_full_px,"
            + "median_angle_error_degrees,p90_angle_error_degrees,worst_angle_error_degrees,"
            + "median_translation_error_px,worst_translation_error_px,refused_pairs,"
            + "non_converged_pairs,shift_bound_pairs,rotation_bound_pairs,both_bound_pairs,"
            + "repaired_frames,unsupported_frames,retained_crop_fraction,runtime_seconds,details";
    static final String TIMING_HEADER = "case_id,pilot_seconds,evidence_seconds,selector_seconds,"
            + "timing_contract_version";
    static final String TIMING_CONTRACT_VERSION = "complete_selector_timing_v2";

    private RecordingAdaptiveSelectorBenchmark() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException(
                    "usage: <generate|features|run|merge|audit> [project] [partition]");
        }
        String command = args[0];
        Path project = (args.length > 1 ? Paths.get(args[1]) : Paths.get(""))
                .toAbsolutePath().normalize();
        String partition = args.length > 2 ? args[2] : "development";
        if ("generate".equals(command)) generate(project, partition);
        else if ("features".equals(command)) features(project, partition);
        else if ("run".equals(command)) run(project, partition);
        else if ("merge".equals(command)) merge(project, partition);
        else if ("audit".equals(command)) audit(project, partition);
        else throw new IllegalArgumentException("unknown command " + command);
    }

    // ---------------------------------------------------------------- generation

    static void generate(Path project, String partition) throws Exception {
        Path root = artifactRoot(project);
        List<Source> sources = sources(project, partition);
        List<Case> cases = new ArrayList<>();
        Map<String, Integer> signs = alternatingSigns(sources);
        for (Source source : sources) {
            Texture texture = texture(project, source);
            double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                    texture.pixels, texture.width, texture.height);
            for (String motion : MOTIONS) {
                cases.add(generateCase(root, source, texture, coefficients, motion,
                        TRANSLATION_CLEAN, false, false, false));
                cases.add(generateCase(root, source, texture, coefficients, motion,
                        RIGID_CLEAN, true, false, false));
                if ("SUBPIXEL_RANDOM_WALK".equals(motion)) {
                    cases.add(generateCase(root, source, texture, coefficients, motion,
                            TRANSLATION_GAIN, false, true, false));
                    cases.add(generateCase(root, source, texture, coefficients, motion,
                            RIGID_GAIN, true, true, false));
                    cases.add(generateCase(root, source, texture, coefficients, motion,
                            RIGID_ZERO, true, false, true));
                }
            }
            System.out.println("generated " + source.id);
        }
        cases.sort(Comparator.comparing(c -> c.id));
        Path manifest = inputManifest(root, partition);
        StringBuilder csv = new StringBuilder("case_id,partition,image_type,source_series_id,"
                + "independent_group,declared_motion_type,condition,input_relative_path,"
                + "input_sha256,truth_relative_path,truth_sha256,source_path,source_sha256\n");
        for (Case c : cases) csv.append(c.manifestRow(project)).append('\n');
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, csv.toString().getBytes(StandardCharsets.UTF_8));
        writeEnvironment(root, "generated_partition=" + partition + "\nsource_count="
                + sources.size() + "\ncase_count=" + cases.size() + "\nframes=" + FRAMES
                + "\ncrop_limit=" + CROP_LIMIT + "\n");
        System.out.printf(Locale.ROOT, "frozen %d cases from %d sources in %s%n",
                cases.size(), sources.size(), manifest);
    }

    private static Case generateCase(Path root, Source source, Texture texture,
            double[] coefficients, String motion, String condition, boolean rigid,
            boolean gainFade, boolean zeroRotation) throws Exception {
        Path folder = root.resolve("inputs").resolve(source.partition).resolve(source.imageType)
                .resolve(source.id).resolve(motion).resolve(condition);
        Files.createDirectories(folder);
        Path input = folder.resolve("00_input_uncorrected.tif");
        Path truthPath = folder.resolve("truth.csv");
        if (!Files.isRegularFile(input) || !Files.isRegularFile(truthPath)) {
            ImageStack stack = new ImageStack(texture.width, texture.height);
            StringBuilder truth = new StringBuilder("frame,truth_x_px,truth_y_px,"
                    + "truth_rotation_radians\n");
            int sign = alternatingSign(source);
            for (int frame = 0; frame < FRAMES; frame++) {
                Transform translation = RigidSelectorFactorialBenchmark.nativeTranslation(
                        motion, frame, FRAMES);
                double degrees = !rigid || zeroRotation ? 0 : sign
                        * RigidSelectorFactorialBenchmark.rotationDegrees(motion, frame, FRAMES);
                Transform combined = translation.then(
                        new Transform(0, 0, Math.toRadians(degrees)));
                float[] pixels = combined.equals(Transform.IDENTITY) ? texture.pixels.clone()
                        : ThevenazProtocolBenchmark.spline7Warp(coefficients, texture.width,
                                texture.height, combined.inverse());
                if (gainFade) {
                    double gain = 1.0 - 0.5 * frame / (FRAMES - 1.0);
                    for (int i = 0; i < pixels.length; i++) pixels[i] *= gain;
                }
                stack.addSlice(null, pixels);
                truth.append(frame + 1).append(',').append(precise(combined.dx)).append(',')
                        .append(precise(combined.dy)).append(',')
                        .append(precise(combined.theta)).append('\n');
            }
            ImagePlus image = new ImagePlus("adaptive selector " + source.id,
                    stack);
            try {
                image.setDimensions(1, 1, FRAMES);
                image.setOpenAsHyperStack(true);
                IJ.saveAsTiff(image, input.toString());
            } finally {
                image.close();
            }
            Files.write(truthPath, truth.toString().getBytes(StandardCharsets.UTF_8));
        }
        String id = source.imageType + "/" + source.id + "/" + motion + "/" + condition;
        return new Case(id, source.partition, source.imageType, source.id,
                source.group, motion, condition, input, sha256(input), truthPath,
                sha256(truthPath), source.path, source.sha256);
    }

    private static Texture texture(Path project, Source source) throws IOException {
        Path path = source.path.isAbsolute() ? source.path : project.resolve(source.path);
        ImagePlus image = IJ.openImage(path.toString());
        if (image == null) {
            Path prepared = artifactRoot(project).resolve("sources").resolve("prepared")
                    .resolve(source.id + ".tif");
            image = IJ.openImage(prepared.toString());
            if (image == null) {
                throw new IOException("could not open frozen source " + path
                        + " or its lossless prepared raster " + prepared);
            }
        }
        try {
            int index = source.frameIndex + 1;
            if (index < 1 || index > image.getStackSize()) {
                throw new IOException("source selection " + source.selection + " outside " + path);
            }
            float[] all = (float[]) image.getStack().getProcessor(index)
                    .convertToFloatProcessor().getPixels();
            int width = Math.min(CROP_LIMIT, image.getWidth());
            int height = Math.min(CROP_LIMIT, image.getHeight());
            if (width < 32 || height < 32) throw new IOException("source too small: " + path);
            int left = (image.getWidth() - width) / 2;
            int top = (image.getHeight() - height) / 2;
            float[] pixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                System.arraycopy(all, (top + y) * image.getWidth() + left,
                        pixels, y * width, width);
            }
            return new Texture(width, height, pixels);
        } finally {
            image.close();
        }
    }

    // ---------------------------------------------------------------- execution

    static void run(Path project, String partition) throws Exception {
        Path root = artifactRoot(project);
        verifyCandidateManifest(project);
        List<Case> cases = readCases(project, inputManifest(root, partition));
        String onlyType = System.getProperty("adaptive.onlyImageType", "").trim();
        int maxCases = Integer.parseInt(System.getProperty(
                "adaptive.maxCases", "2147483647"));
        int threads = Integer.parseInt(System.getProperty("adaptive.threads", "1"));
        Set<String> onlyRecipes = requested("adaptive.onlyRecipe");
        String shard = onlyType.isEmpty() ? partition : partition + "__" + onlyType;
        Path outcomes = root.resolve("outcomes").resolve(shard + ".csv");
        Path features = root.resolve("features").resolve(shard + ".csv");
        Path timings = root.resolve("timings").resolve(shard + ".csv");
        Files.createDirectories(outcomes.getParent());
        Files.createDirectories(features.getParent());
        Files.createDirectories(timings.getParent());
        Set<String> done = completed(outcomes);
        Set<String> featured = featured(features);
        Set<String> timed = featured(timings);
        List<RegistrationRecipe> recipes = RegistrationRecipe.allCandidates();
        int selectedCases = 0;
        int planned = 0;
        for (Case c : cases) {
            if (!onlyType.isEmpty() && !onlyType.equals(c.imageType)) continue;
            if (selectedCases++ >= maxCases) break;
            planned++;
            for (RegistrationRecipe recipe : recipes) {
                if (onlyRecipes.isEmpty() || onlyRecipes.contains(recipe.id())) planned++;
            }
        }
        selectedCases = 0;
        int progress = 0;
        try (BufferedWriter outcome = append(outcomes, OUTCOME_HEADER);
             BufferedWriter feature = append(features, featureHeader());
             BufferedWriter timing = append(timings, TIMING_HEADER)) {
            for (Case c : cases) {
                if (!onlyType.isEmpty() && !onlyType.equals(c.imageType)) continue;
                if (selectedCases++ >= maxCases) break;
                verify(c.input, c.inputHash);
                verify(c.truth, c.truthHash);
                ImagePlus image = IJ.openImage(c.input.toString());
                if (image == null) throw new IOException("could not open " + c.input);
                try {
                    RelativeIntensityPatternParameters base = base(c, threads);
                    if (!featured.contains(c.id) || !timed.contains(c.id)) {
                        EvidenceMeasurement measured = measureEvidence(image, base, c.rigid());
                        if (!featured.contains(c.id)) {
                            feature.write(featureRow(c, measured.evidence));
                            feature.newLine();
                            feature.flush();
                            featured.add(c.id);
                        }
                        if (!timed.contains(c.id)) {
                            timing.write(timingRow(c, measured));
                            timing.newLine();
                            timing.flush();
                            timed.add(c.id);
                        }
                    }
                    AutomaticRotationSelector.Result rotation = c.rigid()
                            ? RelativeIntensityPatternRegistration.resolveAutomaticRotationSettings(image,
                                    base.toBuilder().fitRotation(true).build()) : null;
                    progress = runOne(outcome, done, c, image, base, rotation, null,
                            progress, planned);
                    for (RegistrationRecipe recipe : recipes) {
                        if (!onlyRecipes.isEmpty() && !onlyRecipes.contains(recipe.id())) continue;
                        progress = runOne(outcome, done, c, image, base, rotation, recipe,
                                progress, planned);
                    }
                } finally {
                    image.close();
                }
            }
        }
        if (onlyType.isEmpty() && maxCases == Integer.MAX_VALUE) audit(project, partition);
    }

    /** Measure the frozen production evidence without opening any recipe outcome. */
    static void features(Path project, String partition) throws Exception {
        Path root = artifactRoot(project);
        List<Case> cases = readCases(project, inputManifest(root, partition));
        String onlyType = System.getProperty("adaptive.onlyImageType", "").trim();
        int threads = Integer.parseInt(System.getProperty("adaptive.threads", "1"));
        String shard = onlyType.isEmpty() ? partition : partition + "__" + onlyType;
        Path features = root.resolve("features").resolve(shard + ".csv");
        Path timings = root.resolve("timings").resolve(shard + ".csv");
        Files.createDirectories(features.getParent());
        Files.createDirectories(timings.getParent());
        Set<String> featured = featured(features);
        Set<String> timed = featured(timings);
        try (BufferedWriter writer = append(features, featureHeader());
             BufferedWriter timing = append(timings, TIMING_HEADER)) {
            int done = 0;
            for (Case c : cases) {
                if (!onlyType.isEmpty() && !onlyType.equals(c.imageType)) continue;
                if (featured.contains(c.id) && timed.contains(c.id)) continue;
                ImagePlus image = IJ.openImage(c.input.toString());
                if (image == null) throw new IOException("could not open " + c.input);
                try {
                    EvidenceMeasurement measured = measureEvidence(image, base(c, threads),
                            c.rigid());
                    if (!featured.contains(c.id)) {
                        writer.write(featureRow(c, measured.evidence));
                        writer.newLine();
                        writer.flush();
                        featured.add(c.id);
                    }
                    if (!timed.contains(c.id)) {
                        timing.write(timingRow(c, measured));
                        timing.newLine();
                        timing.flush();
                        timed.add(c.id);
                    }
                    if (++done % 10 == 0) System.out.println("features " + done + ": " + c.id);
                } finally {
                    image.close();
                }
            }
        }
    }

    private static int runOne(BufferedWriter writer, Set<String> done, Case c, ImagePlus image,
            RelativeIntensityPatternParameters base, AutomaticRotationSelector.Result rotation,
            RegistrationRecipe recipe, int progress, int planned) throws IOException {
        String id = recipe == null ? CATEGORY : recipe.id();
        String key = c.id + "/" + id;
        if (done.contains(key)) return progress + 1;
        String estimator = recipe == null ? base.estimator.name() : recipe.estimator.name();
        try {
            RelativeIntensityPatternParameters translation = recipe == null ? base : recipe.applyTo(base);
            long started = System.nanoTime();
            Registration.Result result = rotation == null
                    ? RelativeIntensityPatternRegistration.estimate(image, translation,
                            PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER)
                    : RelativeIntensityPatternRegistration.estimateWithRotationRecipe(image, translation,
                            rotation.parameters, rotation.globalProposalComparison,
                            PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            RigidSelectorFactorialBenchmark.Score score =
                    RigidSelectorFactorialBenchmark.score(readTruth(c.truth), result,
                            image.getWidth(), image.getHeight());
            score.runtimeSeconds = (System.nanoTime() - started) / 1e9;
            score.details = "translation=" + RegistrationRecipe.of(translation).id()
                    + (rotation == null ? "; rotation=off" : "; rotation="
                    + RegistrationRecipe.of(rotation.parameters).id());
            writer.write(outcomeRow(c, id, estimator, score));
        } catch (RuntimeException error) {
            writer.write(failureRow(c, id, estimator, rootMessage(error)));
        }
        writer.newLine();
        writer.flush();
        done.add(key);
        int next = progress + 1;
        if (next == 1 || next == planned || next % 25 == 0) {
            System.out.printf(Locale.ROOT, "outcomes %,d/%,d: %s%n", next, planned, key);
        }
        return next;
    }

    private static EvidenceMeasurement measureEvidence(
            ImagePlus image, RelativeIntensityPatternParameters base, boolean rotationRequested) {
        RelativeIntensityPatternParameters pilot = RelativeIntensityPatternRegistration.automaticSelectorPilot(base);
        long pilotStarted = System.nanoTime();
        Registration.Result provisional = RelativeIntensityPatternRegistration.estimate(image, pilot,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double pilotSeconds = (System.nanoTime() - pilotStarted) / 1e9;
        long evidenceStarted = System.nanoTime();
        AutomaticRegistrationSelector.Evidence evidence = AutomaticRegistrationSelector.measure(
                StackFrames.of(image, base.channel, base.slice), provisional.cumulative, base);
        double evidenceSeconds = (System.nanoTime() - evidenceStarted) / 1e9;
        long selectorStarted = System.nanoTime();
        AutomaticRegistrationSelector.select(evidence, base, rotationRequested);
        double selectorSeconds = (System.nanoTime() - selectorStarted) / 1e9;
        return new EvidenceMeasurement(evidence, pilotSeconds, evidenceSeconds, selectorSeconds);
    }

    // ---------------------------------------------------------------- audit

    static void audit(Path project, String partition) throws Exception {
        Path root = artifactRoot(project);
        mergeShards(root, partition);
        List<Case> cases = readCases(project, inputManifest(root, partition));
        Path outcomes = root.resolve("outcomes").resolve(partition + ".csv");
        Path features = root.resolve("features").resolve(partition + ".csv");
        Path timings = root.resolve("timings").resolve(partition + ".csv");
        int expectedOutcomes = cases.size() * (RegistrationRecipe.allCandidates().size() + 1);
        int outcomeRows = dataRows(outcomes);
        int featureRows = dataRows(features);
        int timingRows = dataRows(timings);
        Set<String> outcomeKeys = completed(outcomes);
        Set<String> featureKeys = featured(features);
        List<String> failures = new ArrayList<>();
        if (outcomeRows != expectedOutcomes) failures.add("outcome rows " + outcomeRows
                + " != " + expectedOutcomes);
        if (outcomeKeys.size() != outcomeRows) failures.add("duplicate outcome keys");
        if (featureRows != cases.size()) failures.add("feature rows " + featureRows
                + " != " + cases.size());
        if (timingRows != cases.size()) failures.add("timing rows " + timingRows
                + " != " + cases.size());
        if (featureKeys.size() != featureRows) failures.add("duplicate feature keys");
        List<String> timingLines = Files.readAllLines(timings, StandardCharsets.UTF_8);
        if (timingLines.isEmpty() || !TIMING_HEADER.equals(timingLines.get(0))) {
            failures.add("timing contract header mismatch");
        } else {
            for (int i = 1; i < timingLines.size(); i++) {
                List<String> row = FullSelectorFactorialBenchmark.fields(timingLines.get(i));
                if (row.size() != 5 || !TIMING_CONTRACT_VERSION.equals(row.get(4))) {
                    failures.add("timing contract row mismatch at " + (i + 1));
                    break;
                }
            }
        }
        String lowerHeader = Files.readAllLines(features, StandardCharsets.UTF_8).get(0)
                .toLowerCase(Locale.ROOT);
        for (String forbidden : new String[]{"truth", "winner", "oracle", "error", "recipe"}) {
            if (lowerHeader.contains(forbidden)) failures.add("forbidden feature column " + forbidden);
        }
        String audit = "partition=" + partition + '\n' + "cases=" + cases.size() + '\n'
                + "features=" + featureRows + '\n' + "timings=" + timingRows + '\n'
                + "outcomes=" + outcomeRows + '\n'
                + "expected_outcomes=" + expectedOutcomes + '\n'
                + "candidate_count=" + RegistrationRecipe.allCandidates().size() + '\n'
                + "feature_contract_version="
                + AutomaticRegistrationSelector.FEATURE_CONTRACT_VERSION + '\n'
                + "candidate_manifest_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/candidate_manifest.csv")) + '\n'
                + "source_manifest_sha256=" + sha256(project.resolve(
                        "docs/recording-adaptive-selector/source_split_manifest.csv")) + '\n'
                + "input_manifest_sha256=" + sha256(inputManifest(root, partition)) + '\n'
                + "feature_table_sha256=" + (Files.isRegularFile(features) ? sha256(features) : "") + '\n'
                + "timing_table_sha256=" + (Files.isRegularFile(timings) ? sha256(timings) : "") + '\n'
                + "outcome_table_sha256=" + (Files.isRegularFile(outcomes) ? sha256(outcomes) : "") + '\n'
                + "verdict=" + (failures.isEmpty() ? "PASS" : "FAIL") + '\n'
                + "failures=" + String.join(" | ", failures) + '\n';
        Path path = root.resolve("audits").resolve(partition + ".properties");
        Files.createDirectories(path.getParent());
        Files.write(path, audit.getBytes(StandardCharsets.UTF_8));
        if (!failures.isEmpty()) throw new IllegalStateException(String.join("; ", failures));
        System.out.println(audit);
    }

    /** Merge type-isolated rows without requiring full-candidate coverage. */
    static void merge(Path project, String partition) throws Exception {
        Path root = artifactRoot(project);
        mergeShards(root, partition);
        System.out.printf(Locale.ROOT, "merged %s: features %,d; timings %,d; outcomes %,d%n",
                partition,
                dataRows(root.resolve("features").resolve(partition + ".csv")),
                dataRows(root.resolve("timings").resolve(partition + ".csv")),
                dataRows(root.resolve("outcomes").resolve(partition + ".csv")));
    }

    /** Merge type-isolated parallel runs in enum order; source shards remain immutable. */
    private static void mergeShards(Path root, String partition) throws IOException {
        List<Path> outcomeShards = new ArrayList<>();
        List<Path> featureShards = new ArrayList<>();
        List<Path> timingShards = new ArrayList<>();
        boolean allOutcomes = true;
        boolean allFeatures = true;
        boolean allTimings = true;
        for (ImageType type : ImageType.values()) {
            Path outcome = root.resolve("outcomes").resolve(
                    partition + "__" + type.name() + ".csv");
            Path feature = root.resolve("features").resolve(
                    partition + "__" + type.name() + ".csv");
            Path timing = root.resolve("timings").resolve(
                    partition + "__" + type.name() + ".csv");
            if (Files.isRegularFile(outcome)) outcomeShards.add(outcome);
            else allOutcomes = false;
            if (Files.isRegularFile(feature)) featureShards.add(feature);
            else allFeatures = false;
            if (Files.isRegularFile(timing)) timingShards.add(timing);
            else allTimings = false;
        }
        if (allOutcomes) {
            mergeCsv(outcomeShards, root.resolve("outcomes").resolve(partition + ".csv"));
        }
        if (allFeatures) {
            mergeCsv(featureShards, root.resolve("features").resolve(partition + ".csv"));
        }
        if (allTimings) {
            mergeCsv(timingShards, root.resolve("timings").resolve(partition + ".csv"));
        }
    }

    private static void mergeCsv(List<Path> shards, Path output) throws IOException {
        StringBuilder merged = new StringBuilder();
        String header = null;
        for (Path shard : shards) {
            List<String> lines = Files.readAllLines(shard, StandardCharsets.UTF_8);
            if (lines.isEmpty()) throw new IOException("empty shard " + shard);
            if (header == null) {
                header = lines.get(0);
                merged.append(header).append('\n');
            } else if (!header.equals(lines.get(0))) {
                throw new IOException("shard header mismatch " + shard);
            }
            for (int i = 1; i < lines.size(); i++) {
                if (!lines.get(i).trim().isEmpty()) merged.append(lines.get(i)).append('\n');
            }
        }
        Files.write(output, merged.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- rows and parsing

    static String featureHeader() {
        StringBuilder out = new StringBuilder("case_id,source_series_id,independent_group,"
                + "image_type,declared_motion_type,feature_contract_version,evidence_valid,"
                + "evidence_out_of_distribution,evidence_reason");
        for (String name : AutomaticRegistrationSelector.FEATURE_NAMES) {
            out.append(',').append(csv(name));
        }
        return out.toString();
    }

    private static String featureRow(Case c, AutomaticRegistrationSelector.Evidence evidence) {
        AutomaticRegistrationSelector.Distribution distribution = evidence.distribution(
                AutomaticRegistrationSelectorModel.FEATURE_MEAN,
                AutomaticRegistrationSelectorModel.FEATURE_SCALE);
        String reason = evidence.valid ? distribution.reason : evidence.validityReason;
        StringBuilder out = new StringBuilder(csv(c.id)).append(',').append(csv(c.series))
                .append(',').append(csv(c.group)).append(',').append(c.imageType).append(',')
                .append(c.motion).append(',').append(evidence.contractVersion).append(',')
                .append(evidence.valid).append(',').append(distribution.outOfDistribution)
                .append(',').append(csv(reason));
        for (double value : evidence.vector()) out.append(',').append(precise(value));
        return out.toString();
    }

    private static String timingRow(Case c, EvidenceMeasurement measured) {
        return csv(c.id) + ',' + precise(measured.pilotSeconds) + ','
                + precise(measured.evidenceSeconds) + ','
                + precise(measured.selectorSeconds) + ',' + TIMING_CONTRACT_VERSION;
    }

    private static String outcomeRow(Case c, String recipe, String estimator,
                                     RigidSelectorFactorialBenchmark.Score s) {
        return common(c, recipe, estimator) + "ok," + FRAMES + ','
                + precise(s.medianCentral) + ',' + precise(s.p90Central) + ','
                + precise(s.worstCentral) + ',' + precise(s.medianFull) + ','
                + precise(s.p90Full) + ',' + precise(s.worstFull) + ','
                + precise(s.medianAngle) + ',' + precise(s.p90Angle) + ','
                + precise(s.worstAngle) + ',' + precise(s.medianTranslation) + ','
                + precise(s.worstTranslation) + ',' + s.refused + ',' + s.nonConverged + ','
                + s.shiftBound + ',' + s.rotationBound + ',' + s.bothBound + ',' + s.repaired
                + ',' + s.unsupported + ',' + precise(s.retainedCropFraction) + ','
                + precise(s.runtimeSeconds) + ',' + csv(s.details);
    }

    private static String failureRow(Case c, String recipe, String estimator, String reason) {
        return common(c, recipe, estimator) + "failed," + FRAMES
                + ",NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,0,0,0,0,0,0,0,NaN,NaN,"
                + csv(reason);
    }

    private static String common(Case c, String recipe, String estimator) {
        return csv(c.id) + ',' + csv(c.partition) + ',' + csv(c.imageType) + ','
                + csv(c.series) + ',' + csv(c.group) + ',' + csv(c.motion) + ','
                + csv(c.condition) + ',' + csv(recipe) + ',' + csv(estimator) + ',';
    }

    private static RelativeIntensityPatternParameters base(Case c, int threads) {
        return RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.valueOf(c.imageType), MotionType.valueOf(c.motion))
                .selectionMode(SelectionMode.MANUAL).autoMaxShift(false).maxShift(30)
                .threads(threads).crop(false).interpolation(Warper.Interpolation.NONE).build();
    }

    private static Transform[] readTruth(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Transform[] out = new Transform[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out[i - 1] = new Transform(Double.parseDouble(row.get(1)),
                    Double.parseDouble(row.get(2)), Double.parseDouble(row.get(3)));
        }
        return out;
    }

    static List<Source> sources(Path project, String partition) throws IOException {
        Path manifest = project.resolve("docs/recording-adaptive-selector/source_split_manifest.csv");
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        List<Source> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (!partition.equals(row.get(3))) continue;
            String selection = row.get(6);
            int equals = selection.indexOf('=');
            if (equals < 0) throw new IOException("invalid source frame selection " + selection);
            out.add(new Source(row.get(0), row.get(1), row.get(2), row.get(3),
                    Paths.get(row.get(4)), row.get(5), selection,
                    Integer.parseInt(selection.substring(equals + 1))));
        }
        out.sort(Comparator.comparing(s -> s.imageType + "/" + s.id));
        return out;
    }

    private static List<Case> readCases(Path project, Path manifest) throws IOException {
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        List<Case> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            Path input = project.resolve(row.get(7)).normalize();
            Path truth = project.resolve(row.get(9)).normalize();
            out.add(new Case(row.get(0), row.get(1), row.get(2), row.get(3), row.get(4),
                    row.get(5), row.get(6), input, row.get(8), truth, row.get(10),
                    Paths.get(row.get(11)), row.get(12)));
        }
        return out;
    }

    private static void verifyCandidateManifest(Path project) throws IOException {
        Path manifest = project.resolve("docs/recording-adaptive-selector/candidate_manifest.csv");
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        List<RegistrationRecipe> candidates = RegistrationRecipe.allCandidates();
        if (lines.size() - 1 != candidates.size()) throw new IOException("candidate count drifted");
        for (int i = 1; i < lines.size(); i++) {
            String id = FullSelectorFactorialBenchmark.fields(lines.get(i)).get(2);
            if (!id.equals(candidates.get(i - 1).id())) {
                throw new IOException("candidate order drifted at " + i + ": " + id);
            }
        }
    }

    private static Set<String> completed(Path path) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(path)) return out;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (row.size() >= 8) out.add(row.get(0) + "/" + row.get(7));
        }
        return out;
    }

    private static Set<String> featured(Path path) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(path)) return out;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (!row.isEmpty()) out.add(row.get(0));
        }
        return out;
    }

    private static Set<String> requested(String property) {
        Set<String> out = new LinkedHashSet<>();
        for (String item : System.getProperty(property, "").split(",")) {
            if (!item.trim().isEmpty()) out.add(item.trim());
        }
        return out;
    }

    private static BufferedWriter append(Path path, String header) throws IOException {
        boolean exists = Files.isRegularFile(path) && Files.size(path) > 0;
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        if (!exists) {
            writer.write(header);
            writer.newLine();
            writer.flush();
        }
        return writer;
    }

    private static int dataRows(Path path) throws IOException {
        return Files.isRegularFile(path)
                ? Math.max(0, Files.readAllLines(path, StandardCharsets.UTF_8).size() - 1) : 0;
    }

    private static Map<String, Integer> alternatingSigns(List<Source> sources) {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (Source source : sources) byType.computeIfAbsent(source.imageType,
                ignored -> new ArrayList<>()).add(source.id);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byType.entrySet()) {
            entry.getValue().sort(String::compareTo);
            for (int i = 0; i < entry.getValue().size(); i++) {
                out.put(entry.getKey() + "/" + entry.getValue().get(i), (i & 1) == 0 ? 1 : -1);
            }
        }
        SIGN_CACHE.clear();
        SIGN_CACHE.putAll(out);
        return out;
    }

    private static final Map<String, Integer> SIGN_CACHE = new LinkedHashMap<>();

    private static int alternatingSign(Source source) {
        Integer sign = SIGN_CACHE.get(source.imageType + "/" + source.id);
        if (sign == null) throw new IllegalStateException("source sign was not frozen");
        return sign;
    }

    private static Path artifactRoot(Path project) {
        return project.resolve("library/recording_adaptive_selector_v1");
    }

    private static Path inputManifest(Path root, String partition) {
        return root.resolve("manifests").resolve("input_manifest_" + partition + ".csv");
    }

    private static void writeEnvironment(Path root, String extra) throws IOException {
        String text = "run_id=" + RUN_ID + "\njava=" + System.getProperty("java.version")
                + "\nos=" + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + "\nprocessors="
                + Runtime.getRuntime().availableProcessors() + "\n" + extra;
        Files.write(root.resolve("environment.txt"), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void verify(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + path);
        String actual = sha256(path);
        if (!actual.equalsIgnoreCase(expected)) throw new IOException("hash mismatch for " + path);
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
            for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String relative(Path project, Path path) {
        return project.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String csv(String value) {
        if (value == null) value = "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String precise(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "NaN";
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + (root.getMessage() == null
                ? "" : ": " + root.getMessage());
    }

    static final class Source {
        final String id, group, imageType, partition, sha256, selection;
        final Path path;
        final int frameIndex;

        Source(String id, String group, String imageType, String partition, Path path,
               String sha256, String selection, int frameIndex) {
            this.id = id; this.group = group; this.imageType = imageType;
            this.partition = partition; this.path = path; this.sha256 = sha256;
            this.selection = selection; this.frameIndex = frameIndex;
        }
    }

    static final class Texture {
        final int width, height;
        final float[] pixels;
        Texture(int width, int height, float[] pixels) {
            this.width = width; this.height = height; this.pixels = pixels;
        }
    }

    static final class EvidenceMeasurement {
        final AutomaticRegistrationSelector.Evidence evidence;
        final double pilotSeconds, evidenceSeconds, selectorSeconds;
        EvidenceMeasurement(AutomaticRegistrationSelector.Evidence evidence,
                            double pilotSeconds, double evidenceSeconds,
                            double selectorSeconds) {
            this.evidence = evidence; this.pilotSeconds = pilotSeconds;
            this.evidenceSeconds = evidenceSeconds;
            this.selectorSeconds = selectorSeconds;
        }
    }

    static final class Case {
        final String id, partition, imageType, series, group, motion, condition;
        final Path input, truth, source;
        final String inputHash, truthHash, sourceHash;

        Case(String id, String partition, String imageType, String series, String group,
             String motion, String condition, Path input, String inputHash, Path truth,
             String truthHash, Path source, String sourceHash) {
            this.id = id; this.partition = partition; this.imageType = imageType;
            this.series = series; this.group = group; this.motion = motion;
            this.condition = condition; this.input = input; this.inputHash = inputHash;
            this.truth = truth; this.truthHash = truthHash; this.source = source;
            this.sourceHash = sourceHash;
        }

        boolean rigid() { return condition.startsWith("RIGID_"); }

        String manifestRow(Path project) {
            return csv(id) + ',' + csv(partition) + ',' + csv(imageType) + ',' + csv(series)
                    + ',' + csv(group) + ',' + csv(motion) + ',' + csv(condition) + ','
                    + csv(relative(project, input)) + ',' + csv(inputHash) + ','
                    + csv(relative(project, truth)) + ',' + csv(truthHash) + ','
                    + csv(source.toString().replace('\\', '/')) + ',' + csv(sourceHash);
        }
    }
}
