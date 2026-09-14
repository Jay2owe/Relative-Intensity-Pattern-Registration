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
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
import ripr.core.FrameSource;
import ripr.core.PairAligner;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

/** A11 exact-truth rigid pairs generated from individual real microscopy frames. */
public final class FreshRigidPairBenchmark {
    static final String JOINT = "JOINT_RIGID";
    static final String TRANSLATION = "TRANSLATION_ONLY";
    static final String CATEGORY = "category_recommendation";
    static final int CROP_SIZE = 256;
    static final String RESULT_HEADER = "split,image_series_class,series_id,independent_group,"
            + "source_frame,motion_category,condition,arm,recipe_id,estimator,status,frames,"
            + "median_warp_central50_px,p90_warp_central50_px,worst_warp_central50_px,"
            + "median_warp_full_px,p90_warp_full_px,worst_warp_full_px,"
            + "median_angle_error_degrees,p90_angle_error_degrees,worst_angle_error_degrees,"
            + "median_translation_error_px,worst_translation_error_px,refused_pairs,"
            + "non_converged_pairs,shift_bound_pairs,rotation_bound_pairs,both_bound_pairs,"
            + "repaired_frames,unsupported_frames,retained_crop_fraction,runtime_seconds,details";
    private static final Map<String, Long> STARTS = new LinkedHashMap<>();

    private FreshRigidPairBenchmark() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: FreshRigidPairBenchmark <project> <generate|run|confidence|audit> "
                    + "<development|validation|locked>");
        }
        Path project = Paths.get(args[0]).toAbsolutePath().normalize();
        String command = args[1].toLowerCase(Locale.ROOT);
        String split = args[2].toLowerCase(Locale.ROOT);
        if (!"development".equals(split) && !"validation".equals(split)
                && !"locked".equals(split)) throw new IllegalArgumentException("bad split " + split);
        if ("generate".equals(command)) generate(project, split);
        else if ("run".equals(command)) run(project, split);
        else if ("confidence".equals(command)) runConfidence(project, split);
        else if ("audit".equals(command)) audit(project, split);
        else throw new IllegalArgumentException("bad command " + command);
    }

    private static void generate(Path project, String split) throws Exception {
        List<Source> sources = sources(project, split);
        Set<String> onlySeries = requested("rigid.onlySeries");
        if (!onlySeries.isEmpty()) {
            sources.removeIf(source -> !onlySeries.contains(source.series));
        }
        int maxFrames = Integer.parseInt(System.getProperty("rigid.pair.maxFrames",
                "development".equals(split) ? "21" : "2147483647"));
        if (maxFrames < 1) throw new IllegalArgumentException("max frames must be positive");
        Map<String, Integer> signs = signs(sources);
        List<Fixture> fixtures = new ArrayList<>();
        int planned = 0;
        for (Source source : sources) planned += Math.min(source.frames, maxFrames) * 9;
        int done = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            Source source = sources.get(sourceIndex);
            verify(source.path, source.sha256);
            ImagePlus image = IJ.openImage(source.path.toString());
            if (image == null) throw new IOException("could not open " + source.path);
            try {
                StackFrames frames = StackFrames.of(image, source.channel, StackFrames.PROJECT_Z);
                if (frames.count() != source.frames || frames.width() != source.width
                        || frames.height() != source.height) {
                    throw new IOException("source metadata mismatch for " + source.path);
                }
                int used = Math.min(source.frames, maxFrames);
                int width = Math.min(CROP_SIZE, source.width);
                int height = Math.min(CROP_SIZE, source.height);
                int left = (source.width - width) / 2;
                int top = (source.height - height) / 2;
                System.out.printf(Locale.ROOT, "hashing source %d/%d: %s (%d frames)%n",
                        sourceIndex + 1, sources.size(), source.series, used);
                for (int frame = 0; frame < used; frame++) {
                    float[] base = crop(frames.plane(frame), source.width,
                            left, top, width, height);
                    for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                        fixtures.add(fixture(split, source, frame, used, motion.name(),
                                "RIGID_CLEAN", signs.get(source.imageClass + "/" + source.series),
                                base, width, height));
                        progress("hashed pair fixtures", ++done, planned,
                                source.series + "/" + (frame + 1) + "/" + motion + "/clean");
                        fixtures.add(fixture(split, source, frame, used, motion.name(),
                                "RIGID_GAIN_FADE_0_5",
                                signs.get(source.imageClass + "/" + source.series),
                                base, width, height));
                        progress("hashed pair fixtures", ++done, planned,
                                source.series + "/" + (frame + 1) + "/" + motion + "/gain");
                        if (motion == ControlledMotionProfile.SUBPIXEL_RANDOM_WALK) {
                            fixtures.add(fixture(split, source, frame, used, motion.name(),
                                    "ZERO_ROTATION_CLEAN",
                                    signs.get(source.imageClass + "/" + source.series),
                                    base, width, height));
                            progress("hashed pair fixtures", ++done, planned,
                                    source.series + "/" + (frame + 1) + "/" + motion + "/zero");
                        }
                    }
                }
            } finally {
                image.close();
            }
        }
        fixtures.sort(Comparator.comparing(Fixture::key));
        writeManifest(manifest(project, split), project, fixtures);
        System.out.printf(Locale.ROOT, "frozen %,d exact pair fixtures from %d sources%n",
                fixtures.size(), sources.size());
    }

    private static Fixture fixture(String split, Source source, int frame, int usedFrames,
                                   String motion, String condition, int sign,
                                   float[] base, int width, int height) throws Exception {
        PairPixels pixels = pixels(base, width, height, motion, condition, sign, frame, usedFrames);
        PairPixels translation = translationPixels(
                base, width, height, motion, condition, frame, usedFrames);
        return new Fixture(split, source, frame, usedFrames, motion, condition,
                pixels.truth, translation.truth, pixels.gain,
                hash(pixels.test), hash(pixels.reference),
                hash(translation.test), hash(translation.reference));
    }

    static PairPixels pixels(float[] base, int width, int height, String motion, String condition,
                            int sign, int frame, int frames) {
        Transform path = RigidSelectorFactorialBenchmark.nativeTranslation(motion, frame, frames);
        boolean zero = "ZERO_ROTATION_CLEAN".equals(condition);
        double degrees = zero ? 0
                : sign * RigidSelectorFactorialBenchmark.rotationDegrees(motion, frame, frames);
        Transform half = new Transform(path.dx / 2.0, path.dy / 2.0,
                Math.toRadians(degrees) / 2.0);
        ThevenazProtocolBenchmark.Frames generated =
                ThevenazProtocolBenchmark.generate(base, width, height, half);
        double gain = "RIGID_GAIN_FADE_0_5".equals(condition) && frames > 1
                ? 1.0 - 0.5 * frame / (frames - 1.0) : 1.0;
        if (gain != 1.0) multiply(generated.reference, gain);
        return new PairPixels(generated.test, generated.reference, generated.truth, gain);
    }

    static PairPixels translationPixels(float[] base, int width, int height, String motion,
                                        String condition, int frame, int frames) {
        Transform path = RigidSelectorFactorialBenchmark.nativeTranslation(motion, frame, frames);
        Transform half = new Transform(path.dx / 2.0, path.dy / 2.0, 0);
        ThevenazProtocolBenchmark.Frames generated =
                ThevenazProtocolBenchmark.generate(base, width, height, half);
        double gain = "RIGID_GAIN_FADE_0_5".equals(condition) && frames > 1
                ? 1.0 - 0.5 * frame / (frames - 1.0) : 1.0;
        if (gain != 1.0) multiply(generated.reference, gain);
        return new PairPixels(generated.test, generated.reference, generated.truth, gain);
    }

    private static void run(Path project, String split) throws Exception {
        List<Fixture> fixtures = readManifest(project, manifest(project, split));
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        Set<String> onlyArms = requested("rigid.onlyArm");
        Set<String> onlyRecipes = requested("rigid.onlyRecipe");
        List<Candidate> candidates = candidates();
        String runId = System.getProperty("rigid.run", "r00_full_factorial");
        Path output = root(project).resolve("runs").resolve(split).resolve(runId)
                .resolve("results.csv");
        Files.createDirectories(output.getParent());
        Set<String> completed = completed(output);
        int planned = 0;
        for (Fixture fixture : fixtures) {
            if (!selected(fixture, onlySeries, onlyMotions, onlyConditions)) continue;
            for (String arm : new String[]{JOINT, TRANSLATION}) {
                if (!onlyArms.isEmpty() && !onlyArms.contains(arm)) continue;
                for (Candidate candidate : candidates) {
                    if (onlyRecipes.isEmpty() || onlyRecipes.contains(candidate.id)) planned++;
                }
            }
        }
        boolean append = Files.isRegularFile(output) && Files.size(output) > 0;
        System.out.printf(Locale.ROOT, "planned %,d pair results; output %s%n", planned, output);
        int done = 0;
        Source active = null;
        ImagePlus sourceImage = null;
        StackFrames sourceFrames = null;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!append) {
                writer.write(RESULT_HEADER);
                writer.newLine();
                writer.flush();
            }
            for (Fixture fixture : fixtures) {
                if (!selected(fixture, onlySeries, onlyMotions, onlyConditions)) continue;
                if (active == null || !active.series.equals(fixture.source.series)) {
                    if (sourceImage != null) sourceImage.close();
                    active = fixture.source;
                    sourceImage = IJ.openImage(active.path.toString());
                    if (sourceImage == null) throw new IOException("could not open " + active.path);
                    sourceFrames = StackFrames.of(sourceImage, active.channel, StackFrames.PROJECT_Z);
                    System.out.printf(Locale.ROOT, "opened pair source %s%n", active.series);
                }
                int width = Math.min(CROP_SIZE, active.width);
                int height = Math.min(CROP_SIZE, active.height);
                float[] base = crop(sourceFrames.plane(fixture.frame), active.width,
                        (active.width - width) / 2, (active.height - height) / 2, width, height);
                PairPixels pixels = pixels(base, width, height, fixture.motion, fixture.condition,
                        fixture.sign(), fixture.frame, fixture.usedFrames);
                if (!fixture.testSha256.equals(hash(pixels.test))
                        || !fixture.referenceSha256.equals(hash(pixels.reference))) {
                    throw new IOException("generated pair hash drifted for " + fixture.key());
                }
                PairPixels translation = translationPixels(base, width, height, fixture.motion,
                        fixture.condition, fixture.frame, fixture.usedFrames);
                if (!fixture.translationTestSha256.equals(hash(translation.test))
                        || !fixture.translationReferenceSha256.equals(
                                hash(translation.reference))) {
                    throw new IOException("generated translation pair hash drifted for "
                            + fixture.key());
                }
                for (String arm : new String[]{JOINT, TRANSLATION}) {
                    PairPixels selectedPixels = JOINT.equals(arm) ? pixels : translation;
                    Transform selectedTruth = JOINT.equals(arm) ? fixture.truth
                            : fixture.translationTruth;
                    ImagePlus pair = pairImage(fixture.key() + "/" + arm,
                            width, height, selectedPixels);
                    try {
                        if (!onlyArms.isEmpty() && !onlyArms.contains(arm)) continue;
                        for (Candidate candidate : candidates) {
                            if (!onlyRecipes.isEmpty() && !onlyRecipes.contains(candidate.id)) {
                                continue;
                            }
                            String key = fixture.key() + "/" + arm + "/" + candidate.id;
                            if (completed.contains(key)) {
                                progress("resumed pair sweep", ++done, planned, key);
                                continue;
                            }
                            try {
                                writer.write(measure(split, fixture, pair, width, height,
                                        arm, candidate, selectedTruth));
                            } catch (RuntimeException error) {
                                writer.write(failure(split, fixture, arm, candidate, error));
                            }
                            writer.newLine();
                            writer.flush();
                            completed.add(key);
                            progress("pair sweep", ++done, planned, key);
                        }
                    } finally {
                        pair.close();
                    }
                }
            }
        } finally {
            if (sourceImage != null) sourceImage.close();
        }
        if (!Boolean.getBoolean("rigid.skipAudit")) audit(project, split);
    }

    /** Apply the frozen A05 residual-gain grid to one already-benchmarked rigid recipe. */
    private static void runConfidence(Path project, String split) throws Exception {
        if (!"development".equals(split) && !Boolean.getBoolean("rigid.allowFrozenPolicy")) {
            throw new IllegalArgumentException("non-development confidence runs require a frozen "
                    + "policy and -Drigid.allowFrozenPolicy=true");
        }
        List<Fixture> fixtures = readManifest(project, manifest(project, split));
        Set<String> onlySeries = requested("rigid.onlySeries");
        Set<String> onlyMotions = requested("rigid.onlyMotion");
        Set<String> onlyConditions = requested("rigid.onlyCondition");
        double[] thresholds = confidenceThresholds();
        Candidate recipe = candidate(System.getProperty(
                "rigid.confidenceRecipe", CATEGORY).trim());
        String runId = System.getProperty("rigid.run", "r03_category_confidence");
        Path output = root(project).resolve("runs").resolve(split).resolve(runId)
                .resolve("results.csv");
        Files.createDirectories(output.getParent());
        Set<String> completed = completed(output);
        int planned = 0;
        for (Fixture fixture : fixtures) {
            if (selected(fixture, onlySeries, onlyMotions, onlyConditions)) {
                planned += thresholds.length;
            }
        }
        boolean append = Files.isRegularFile(output) && Files.size(output) > 0;
        System.out.printf(Locale.ROOT, "planned %,d confidence results for %s; output %s%n",
                planned, recipe.id, output);
        int done = 0;
        Source active = null;
        ImagePlus sourceImage = null;
        StackFrames sourceFrames = null;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!append) {
                writer.write(RESULT_HEADER);
                writer.newLine();
                writer.flush();
            }
            for (Fixture fixture : fixtures) {
                if (!selected(fixture, onlySeries, onlyMotions, onlyConditions)) continue;
                if (active == null || !active.series.equals(fixture.source.series)) {
                    if (sourceImage != null) sourceImage.close();
                    active = fixture.source;
                    sourceImage = IJ.openImage(active.path.toString());
                    if (sourceImage == null) throw new IOException("could not open " + active.path);
                    sourceFrames = StackFrames.of(sourceImage, active.channel, StackFrames.PROJECT_Z);
                    System.out.printf(Locale.ROOT, "opened confidence source %s%n", active.series);
                }
                int width = Math.min(CROP_SIZE, active.width);
                int height = Math.min(CROP_SIZE, active.height);
                float[] base = crop(sourceFrames.plane(fixture.frame), active.width,
                        (active.width - width) / 2, (active.height - height) / 2, width, height);
                PairPixels pixels = pixels(base, width, height, fixture.motion, fixture.condition,
                        fixture.sign(), fixture.frame, fixture.usedFrames);
                if (!fixture.testSha256.equals(hash(pixels.test))
                        || !fixture.referenceSha256.equals(hash(pixels.reference))) {
                    throw new IOException("generated pair hash drifted for " + fixture.key());
                }
                ImagePlus pair = pairImage(fixture.key() + "/confidence", width, height, pixels);
                try {
                    for (double threshold : thresholds) {
                        Candidate policy = new Candidate(confidenceId(threshold), recipe.recipe);
                        String key = fixture.key() + "/" + JOINT + "/" + policy.id;
                        if (completed.contains(key)) {
                            progress("resumed pair confidence", ++done, planned, key);
                            continue;
                        }
                        try {
                            RigidSelectorFactorialBenchmark.Score score = measureConfidence(
                                    fixture, pair, width, height, recipe, threshold, fixture.truth);
                            writer.write(row(split, fixture, JOINT, policy, score, "ok", null));
                        } catch (RuntimeException error) {
                            writer.write(failure(split, fixture, JOINT, policy, error));
                        }
                        writer.newLine();
                        writer.flush();
                        completed.add(key);
                        progress("pair confidence", ++done, planned, key);
                    }
                } finally {
                    pair.close();
                }
            }
        } finally {
            if (sourceImage != null) sourceImage.close();
        }
        if (!Boolean.getBoolean("rigid.skipAudit")) audit(project, split);
    }

    private static RigidSelectorFactorialBenchmark.Score measureConfidence(
            Fixture fixture, ImagePlus pair, int width, int height, Candidate recipe,
            double threshold, Transform truth) {
        RelativeIntensityPatternParameters parameters = parameters(fixture, JOINT, recipe);
        if (parameters.estimationScale != 1.0) {
            throw new IllegalArgumentException("confidence benchmark requires native scale");
        }
        StackFrames nativeSource = StackFrames.of(pair, parameters.channel, parameters.slice);
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
            FrameSource scoring = parameters.pixelSelectionPreprocessing
                    == ripr.api.Preprocessing.NONE
                    ? nativeSource : new PreprocessedFrameSource(nativeSource,
                            parameters.pixelSelectionPreprocessing);
            result = PixelSelectionEngine.run(source, scoring, nativeSource, options,
                    parameters.pixelSelectionStrategy, parameters.pixelRemovalPercent,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        }
        RigidSelectorFactorialBenchmark.Score score = RigidSelectorFactorialBenchmark.score(
                new Transform[]{Transform.IDENTITY, truth}, result, width, height);
        score.runtimeSeconds = (System.nanoTime() - start) / 1e9;
        List<Double> gains = new ArrayList<>();
        int accepted = 0;
        int declined = 0;
        for (Registration.PairResult pairResult : result.pairs) {
            PairAligner.RotationEvidence evidence = pairResult.fit.rotationEvidence;
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

    static double[] confidenceThresholds() {
        String raw = System.getProperty("rigid.rotationGains",
                "0,0.0001,0.00025,0.0005,0.001,0.002,0.005,0.01,0.02,0.03,0.05");
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

    static String confidenceId(double threshold) {
        String prefix = Boolean.getBoolean("rigid.incrementalRotation")
                ? "incremental_rotation_min_gain_" : "global_rigid_min_gain_";
        return prefix + precise(threshold).replace('.', '_');
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return Double.NaN;
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static boolean selected(Fixture fixture, Set<String> series, Set<String> motions,
                                    Set<String> conditions) {
        return (series.isEmpty() || series.contains(fixture.source.series))
                && (motions.isEmpty() || motions.contains(fixture.motion))
                && (conditions.isEmpty() || conditions.contains(fixture.condition));
    }

    private static String measure(String split, Fixture fixture, ImagePlus pair,
                                  int width, int height, String arm, Candidate candidate,
                                  Transform truth) {
        RelativeIntensityPatternParameters parameters = parameters(fixture, arm, candidate);
        long start = System.nanoTime();
        Registration.Result result = RelativeIntensityPatternRegistration.estimate(pair, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double runtime = (System.nanoTime() - start) / 1e9;
        RigidSelectorFactorialBenchmark.Score score = RigidSelectorFactorialBenchmark.score(
                new Transform[]{Transform.IDENTITY, truth}, result, width, height);
        score.runtimeSeconds = runtime;
        score.details = RegistrationRecipe.of(parameters).describe();
        return row(split, fixture, arm, candidate, score, "ok", null);
    }

    private static RelativeIntensityPatternParameters parameters(Fixture fixture, String arm, Candidate candidate) {
        boolean rigid = JOINT.equals(arm);
        RelativeIntensityPatternParameters base = RelativeIntensityPatternParameters.builder()
                .recommendation(FullSelectorFactorialBenchmark.imageType(fixture.source.imageClass),
                        FullSelectorFactorialBenchmark.motionType(fixture.motion))
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(rigid)
                .maxRotationDegrees(10)
                .autoMaxShift(false)
                .maxShift(FullSelectorFactorialBenchmark.knownMaxShift(fixture.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .outlierMads(0)
                .build();
        return candidate.recipe == null ? base : candidate.recipe.applyTo(base);
    }

    private static String failure(String split, Fixture fixture, String arm,
                                  Candidate candidate, RuntimeException error) {
        return row(split, fixture, arm, candidate, null, "failed", rootMessage(error));
    }

    private static String row(String split, Fixture f, String arm, Candidate candidate,
                              RigidSelectorFactorialBenchmark.Score s, String status,
                              String failure) {
        String prefix = csv(split) + ',' + csv(f.source.imageClass) + ',' + csv(f.source.series)
                + ',' + csv(f.source.independentGroup) + ',' + (f.frame + 1) + ','
                + csv(f.motion) + ',' + csv(f.condition) + ',' + csv(arm) + ','
                + csv(candidate.id) + ',' + csv(candidate.estimator()) + ',' + status + ",2,";
        if (s == null) {
            return prefix + "NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,0,0,0,0,0,0,0,"
                    + "NaN,NaN," + csv(failure);
        }
        return prefix + precise(s.medianCentral) + ',' + precise(s.p90Central) + ','
                + precise(s.worstCentral) + ',' + precise(s.medianFull) + ','
                + precise(s.p90Full) + ',' + precise(s.worstFull) + ','
                + precise(s.medianAngle) + ',' + precise(s.p90Angle) + ','
                + precise(s.worstAngle) + ',' + precise(s.medianTranslation) + ','
                + precise(s.worstTranslation) + ',' + s.refused + ',' + s.nonConverged + ','
                + s.shiftBound + ',' + s.rotationBound + ',' + s.bothBound + ',' + s.repaired + ','
                + s.unsupported + ',' + precise(s.retainedCropFraction) + ','
                + precise(s.runtimeSeconds) + ',' + csv(s.details);
    }

    private static void audit(Path project, String split) throws Exception {
        List<Fixture> fixtures = readManifest(project, manifest(project, split));
        Set<String> fixtureKeys = new LinkedHashSet<>();
        Map<Path, String> sourceHashes = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) {
            if (!fixtureKeys.add(fixture.key())) throw new IOException("duplicate " + fixture.key());
            String previous = sourceHashes.put(fixture.source.path, fixture.source.sha256);
            if (previous != null && !previous.equalsIgnoreCase(fixture.source.sha256)) {
                throw new IOException("inconsistent source hash for " + fixture.source.path);
            }
        }
        for (Map.Entry<Path, String> source : sourceHashes.entrySet()) {
            verify(source.getKey(), source.getValue());
        }
        String runId = System.getProperty("rigid.run", "r00_full_factorial");
        Path results = root(project).resolve("runs").resolve(split).resolve(runId)
                .resolve("results.csv");
        int rows = 0;
        if (Files.isRegularFile(results)) {
            List<String> lines = Files.readAllLines(results, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !RESULT_HEADER.equals(lines.get(0))) {
                throw new IOException("result header drifted");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().isEmpty()) continue;
                List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
                if (row.size() != FullSelectorFactorialBenchmark.fields(RESULT_HEADER).size()) {
                    throw new IOException("bad result row " + (i + 1));
                }
                String key = row.get(2) + '/' + row.get(4) + '/' + row.get(5) + '/'
                        + row.get(6) + '/' + row.get(7) + '/' + row.get(8);
                if (!keys.add(key)) throw new IOException("duplicate result " + key);
                rows++;
            }
        }
        System.out.printf(Locale.ROOT, "pair audit passed: %,d fixtures, %,d results%n",
                fixtures.size(), rows);
    }

    private static void writeManifest(Path path, Path project, List<Fixture> fixtures)
            throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder out = new StringBuilder("split,image_series_class,series_id,independent_group,"
                + "acquisition_family,channel_1_based,source_frames,used_frames,width,height,"
                + "source_relative_path,source_sha256,source_frame,motion_category,condition,"
                + "truth_x_px,truth_y_px,truth_rotation_radians,translation_truth_x_px,"
                + "translation_truth_y_px,translation_truth_rotation_radians,gain,test_sha256,"
                + "reference_sha256,translation_test_sha256,translation_reference_sha256\n");
        for (Fixture f : fixtures) {
            out.append(csv(f.split)).append(',').append(csv(f.source.imageClass)).append(',')
                    .append(csv(f.source.series)).append(',')
                    .append(csv(f.source.independentGroup)).append(',')
                    .append(csv(f.source.acquisitionFamily)).append(',').append(f.source.channel)
                    .append(',').append(f.source.frames).append(',').append(f.usedFrames).append(',')
                    .append(f.source.width).append(',').append(f.source.height).append(',')
                    .append(csv(relative(project, f.source.path))).append(',')
                    .append(csv(f.source.sha256)).append(',').append(f.frame + 1).append(',')
                    .append(csv(f.motion)).append(',').append(csv(f.condition)).append(',')
                    .append(precise(f.truth.dx)).append(',').append(precise(f.truth.dy)).append(',')
                    .append(precise(f.truth.theta)).append(',')
                    .append(precise(f.translationTruth.dx)).append(',')
                    .append(precise(f.translationTruth.dy)).append(',')
                    .append(precise(f.translationTruth.theta)).append(',')
                    .append(precise(f.gain)).append(',')
                    .append(csv(f.testSha256)).append(',').append(csv(f.referenceSha256)).append(',')
                    .append(csv(f.translationTestSha256)).append(',')
                    .append(csv(f.translationReferenceSha256)).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Fixture> readManifest(Path project, Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Fixture> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> r = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (r.size() != 26) throw new IOException("bad pair manifest row " + (i + 1));
            Path sourcePath = Paths.get(r.get(10));
            if (!sourcePath.isAbsolute()) sourcePath = project.resolve(sourcePath);
            Source source = new Source(r.get(1), r.get(2), r.get(3), r.get(4),
                    Integer.parseInt(r.get(5)), Integer.parseInt(r.get(6)),
                    Integer.parseInt(r.get(8)), Integer.parseInt(r.get(9)),
                    sourcePath.normalize(), r.get(11));
            Transform truth = new Transform(Double.parseDouble(r.get(15)),
                    Double.parseDouble(r.get(16)), Double.parseDouble(r.get(17)));
            Transform translationTruth = new Transform(Double.parseDouble(r.get(18)),
                    Double.parseDouble(r.get(19)), Double.parseDouble(r.get(20)));
            out.add(new Fixture(r.get(0), source, Integer.parseInt(r.get(12)) - 1,
                    Integer.parseInt(r.get(7)), r.get(13), r.get(14), truth, translationTruth,
                    Double.parseDouble(r.get(21)), r.get(22), r.get(23), r.get(24), r.get(25)));
        }
        out.sort(Comparator.comparing(Fixture::key));
        return out;
    }

    private static List<Source> sources(Path project, String split) throws IOException {
        String requestedImageClass = requestedImageClass();
        Map<String, String[]> decoded = decodedSources(project);
        Set<String> allocations = new LinkedHashSet<>();
        if ("development".equals(split)) {
            allocations.add("development");
            allocations.add("qualification");
        } else {
            allocations.add(split);
        }
        List<String> lines = Files.readAllLines(project.resolve(
                "library/rigid_selector_tuning/source_candidates/source_manifest.csv"),
                StandardCharsets.UTF_8);
        List<Source> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> r = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (!allocations.contains(r.get(1)) || !requestedImageClass.equals(r.get(2))) continue;
            int channel = Integer.parseInt(r.get(6));
            if (channel < 1) continue;
            Path source = Paths.get(r.get(14));
            if (!source.isAbsolute()) source = project.resolve(source);
            String sourceHash = r.get(15);
            String[] override = decoded.get(r.get(0));
            if (override != null) {
                if (!sourceHash.equalsIgnoreCase(override[0])) {
                    throw new IOException("decoded-source provenance drift for " + r.get(0));
                }
                source = Paths.get(override[1]);
                if (!source.isAbsolute()) source = project.resolve(source);
                sourceHash = override[2];
            }
            out.add(new Source(r.get(2), r.get(3), r.get(4), r.get(5), channel,
                    Integer.parseInt(r.get(7)), Integer.parseInt(r.get(8)),
                    Integer.parseInt(r.get(9)), source.normalize(), sourceHash));
        }
        out.sort(Comparator.comparing(source -> source.imageClass + "/" + source.series));
        return out;
    }

    private static Map<String, String[]> decodedSources(Path project) throws IOException {
        Path manifest = project.resolve(
                "library/rigid_selector_tuning/source_candidates/decoded_source_manifest.csv");
        Map<String, String[]> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(manifest)) return out;
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> row = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (row.size() != 8) throw new IOException("bad decoded source row " + (i + 1));
            if (out.put(row.get(0), new String[]{row.get(2), row.get(3), row.get(4)}) != null) {
                throw new IOException("duplicate decoded source " + row.get(0));
            }
        }
        return out;
    }

    private static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        out.add(new Candidate(CATEGORY, null));
        List<RegistrationRecipe> recipes = RegistrationRecipe.allCandidates();
        recipes.sort(Comparator.comparing(RegistrationRecipe::id));
        for (RegistrationRecipe recipe : recipes) out.add(new Candidate(recipe.id(), recipe));
        if (out.size() != 129) throw new IllegalStateException("expected 129 candidates");
        return out;
    }

    private static Candidate candidate(String id) {
        for (Candidate candidate : candidates()) {
            if (candidate.id.equals(id)) return candidate;
        }
        throw new IllegalArgumentException("unknown pair confidence recipe " + id);
    }

    private static Set<String> completed(Path path) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(path)) return out;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !RESULT_HEADER.equals(lines.get(0))) {
            throw new IOException("result header drifted in " + path);
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> r = FullSelectorFactorialBenchmark.fields(lines.get(i));
            out.add(r.get(2) + '/' + r.get(4) + '/' + r.get(5) + '/' + r.get(6) + '/'
                    + r.get(7) + '/' + r.get(8));
        }
        return out;
    }

    private static ImagePlus pairImage(String title, int width, int height, PairPixels pixels) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice("test", pixels.test);
        stack.addSlice("reference", pixels.reference);
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 1, 2);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static float[] crop(float[] pixels, int sourceWidth, int left, int top,
                                int width, int height) {
        float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            System.arraycopy(pixels, (top + y) * sourceWidth + left, out, y * width, width);
        }
        return out;
    }

    private static void multiply(float[] pixels, double gain) {
        for (int i = 0; i < pixels.length; i++) pixels[i] *= gain;
    }

    private static String hash(float[] pixels) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN);
        for (float pixel : pixels) {
            if (buffer.remaining() < 4) {
                digest.update(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            buffer.putInt(Float.floatToIntBits(pixel));
        }
        if (buffer.position() > 0) digest.update(buffer.array(), 0, buffer.position());
        return hex(digest.digest());
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value));
        return out.toString();
    }

    private static void verify(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + path);
        String actual = sha256(path);
        if (!actual.equalsIgnoreCase(expected)) throw new IOException("hash mismatch " + path);
    }

    private static Map<String, Integer> signs(List<Source> sources) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Source source : sources) groups.computeIfAbsent(source.imageClass,
                ignored -> new ArrayList<>()).add(source.series);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            entry.getValue().sort(String::compareTo);
            for (int i = 0; i < entry.getValue().size(); i++) {
                out.put(entry.getKey() + "/" + entry.getValue().get(i),
                        (i & 1) == 0 ? 1 : -1);
            }
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

    private static Path root(Path project) {
        return project.resolve("library/rigid_selector_tuning/fresh_recovery/pair_benchmark");
    }

    private static Path manifest(Path project, String split) {
        String imageClass = requestedImageClass();
        String suffix = "PHASE".equals(imageClass) ? ""
                : "_" + imageClass.toLowerCase(Locale.ROOT);
        return root(project).resolve("input_manifest_" + split + suffix + ".csv");
    }

    private static String requestedImageClass() {
        String imageClass = System.getProperty("rigid.pair.imageClass", "PHASE").trim()
                .toUpperCase(Locale.ROOT);
        FullSelectorFactorialBenchmark.imageType(imageClass);
        return imageClass;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
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
        return root.getClass().getSimpleName() + (root.getMessage() == null ? ""
                : ": " + root.getMessage());
    }

    private static void progress(String phase, int done, int total, String detail) {
        if (done != 1 && done != total && done % 100 != 0) return;
        long now = System.nanoTime();
        Long start = STARTS.get(phase);
        if (start == null || done == 1) {
            start = now;
            STARTS.put(phase, start);
        }
        double elapsed = (now - start) / 1e9;
        double eta = done > 0 && total > done ? elapsed * (total - done) / done : 0;
        System.out.printf(Locale.ROOT, "%s %,d/%,d (%.1f%%), elapsed %s, ETA %s: %s%n",
                phase, done, total, total == 0 ? 100 : 100.0 * done / total,
                duration(elapsed), duration(eta), detail);
    }

    private static String duration(double seconds) {
        long value = Math.max(0, Math.round(seconds));
        long hours = value / 3600;
        long minutes = (value % 3600) / 60;
        long remainder = value % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, remainder);
        if (minutes > 0) return String.format(Locale.ROOT, "%dm%02ds", minutes, remainder);
        return remainder + "s";
    }

    static final class PairPixels {
        final float[] test;
        final float[] reference;
        final Transform truth;
        final double gain;

        PairPixels(float[] test, float[] reference, Transform truth, double gain) {
            this.test = test;
            this.reference = reference;
            this.truth = truth;
            this.gain = gain;
        }
    }

    private static final class Candidate {
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

    private static final class Source {
        final String imageClass;
        final String series;
        final String independentGroup;
        final String acquisitionFamily;
        final int channel;
        final int frames;
        final int width;
        final int height;
        final Path path;
        final String sha256;

        Source(String imageClass, String series, String independentGroup,
               String acquisitionFamily, int channel, int frames, int width, int height,
               Path path, String sha256) {
            this.imageClass = imageClass;
            this.series = series;
            this.independentGroup = independentGroup;
            this.acquisitionFamily = acquisitionFamily;
            this.channel = channel;
            this.frames = frames;
            this.width = width;
            this.height = height;
            this.path = path;
            this.sha256 = sha256;
        }
    }

    private static final class Fixture {
        final String split;
        final Source source;
        final int frame;
        final int usedFrames;
        final String motion;
        final String condition;
        final Transform truth;
        final Transform translationTruth;
        final double gain;
        final String testSha256;
        final String referenceSha256;
        final String translationTestSha256;
        final String translationReferenceSha256;

        Fixture(String split, Source source, int frame, int usedFrames, String motion,
                String condition, Transform truth, Transform translationTruth, double gain,
                String testSha256, String referenceSha256, String translationTestSha256,
                String translationReferenceSha256) {
            this.split = split;
            this.source = source;
            this.frame = frame;
            this.usedFrames = usedFrames;
            this.motion = motion;
            this.condition = condition;
            this.truth = truth;
            this.translationTruth = translationTruth;
            this.gain = gain;
            this.testSha256 = testSha256;
            this.referenceSha256 = referenceSha256;
            this.translationTestSha256 = translationTestSha256;
            this.translationReferenceSha256 = translationReferenceSha256;
        }

        int sign() {
            double expected = RigidSelectorFactorialBenchmark.rotationDegrees(
                    motion, frame, usedFrames);
            if (expected == 0 || "ZERO_ROTATION_CLEAN".equals(condition)) return 1;
            return Math.signum(truth.theta) == Math.signum(expected) ? 1 : -1;
        }

        String key() {
            return source.imageClass + '/' + source.series + '/' + (frame + 1) + '/'
                    + motion + '/' + condition;
        }
    }
}
