/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.PixelSelectionStrategy;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.SelectionMode;
import ripr.core.PairEstimator;
import ripr.core.PairScheduler;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.RotationMode;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Five-class, paired publication benchmark for RIPR and installed external registration methods. */
public final class ExternalPublicationBenchmark {
    static final int FRAMES = 48;
    static final String SOURCE_MANIFEST = "library/benchmark/v2/benchmarks/"
            + "confidence_weighted_reconciliation_v1/tuning/rounds/"
            + "R01_full_evidence/sources/source_manifest.csv";

    enum Scope { TRANSLATION, RIGID }
    enum Condition { CLEAN, GAIN_FADE }

    /** Complete internal methods compared on the same frozen publication inputs. */
    enum InternalRecipe {
        AUTOMATIC("automatic", "00_ripr_automatic", "RIPR Automatic fixed recipe"),
        RECOMMENDED("recommended", "01_ripr_recommended", "RIPR image-and-motion preset"),
        AREA_GRID("area_grid", "02_ripr_area_grid", "RIPR correlation grid"),
        AREA_NEWTON("area_newton", "03_ripr_area_newton", "RIPR correlation Newton"),
        AREA_ECC("area_ecc", "04_ripr_area_ecc", "RIPR Enhanced Correlation Coefficient"),
        AREA_GRID_FIRST("area_grid_first", "05_ripr_area_grid_first",
                "RIPR correlation grid, first image"),
        AREA_NEWTON_FIRST("area_newton_first", "06_ripr_area_newton_first",
                "RIPR correlation Newton, first image"),
        AREA_ECC_FIRST("area_ecc_first", "07_ripr_area_ecc_first",
                "RIPR Enhanced Correlation Coefficient, first image"),
        LONGITUDINAL_ACCURACY("longitudinal_accuracy", "08_ripr_longitudinal_accuracy",
                "RIPR Longitudinal maximum accuracy");

        final String token;
        final String methodId;
        final String label;

        InternalRecipe(String token, String methodId, String label) {
            this.token = token;
            this.methodId = methodId;
            this.label = label;
        }

        static InternalRecipe of(String value) {
            String wanted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (InternalRecipe recipe : values()) {
                if (recipe.token.equals(wanted) || recipe.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return recipe;
                }
            }
            throw new IllegalArgumentException("unknown internal recipe " + value);
        }

        String methodId(Scope scope) {
            return methodId + (scope == Scope.RIGID ? "_rigid" : "_translation");
        }

        String label(Scope scope) {
            return label + (scope == Scope.RIGID ? " translation + rotation" : " translation");
        }
    }

    private ExternalPublicationBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) usage();
        String command = args[0].trim().toLowerCase(Locale.ROOT);
        if ("generate".equals(command) && (args.length == 5 || args.length == 6)) {
            Condition requestedCondition = args.length == 6 ? condition(args[4]) : Condition.CLEAN;
            generate(Paths.get(args[1]).toAbsolutePath().normalize(), split(args[2]),
                    scope(args[3]), requestedCondition,
                    Paths.get(args[args.length - 1]).toAbsolutePath().normalize());
        } else if ("ripr".equals(command) && args.length == 6) {
            runRipr(Paths.get(args[1]).toAbsolutePath().normalize(), split(args[2]),
                    scope(args[3]), Paths.get(args[4]).toAbsolutePath().normalize(),
                    Paths.get(args[5]).toAbsolutePath().normalize(), InternalRecipe.AUTOMATIC);
        } else if ("ripr-recipe".equals(command) && args.length == 7) {
            runRipr(Paths.get(args[1]).toAbsolutePath().normalize(), split(args[2]),
                    scope(args[3]), Paths.get(args[4]).toAbsolutePath().normalize(),
                    Paths.get(args[6]).toAbsolutePath().normalize(), InternalRecipe.of(args[5]));
        } else if ("audit".equals(command) && args.length == 3) {
            audit(Paths.get(args[1]).toAbsolutePath().normalize(),
                    Paths.get(args[2]).toAbsolutePath().normalize());
        } else {
            usage();
        }
    }

    private static void usage() {
        throw new IllegalArgumentException("usage: generate <project> <development|final> "
                + "<translation|rigid> [clean|gain_fade] <output-root> | "
                + "ripr <project> <split> <scope> "
                + "<input-root> <output.csv> | ripr-recipe <project> <split> <scope> "
                + "<input-root> <automatic|recommended|area_grid|area_newton|area_ecc|"
                + "area_grid_first|area_newton_first|area_ecc_first|longitudinal_accuracy> "
                + "<output.csv> | audit <project> <input-root>");
    }

    private static String split(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!"development".equals(normalized) && !"final".equals(normalized)) {
            throw new IllegalArgumentException("split must be development or final");
        }
        return normalized;
    }

    private static Scope scope(String value) {
        return Scope.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private static Condition condition(String value) {
        return Condition.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    static final class Source {
        final String id;
        final String split;
        final String category;
        final String acquisition;
        final Path path;
        final String sha256;

        Source(String id, String split, String category, String acquisition, Path path,
               String sha256) {
            this.id = id;
            this.split = split;
            this.category = category;
            this.acquisition = acquisition;
            this.path = path;
            this.sha256 = sha256;
        }
    }

    private static List<Source> sources(Path project, String requestedSplit) throws IOException {
        Path manifest = project.resolve(SOURCE_MANIFEST);
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("empty source manifest " + manifest);
        Map<String, Integer> columns = columns(lines.get(0));
        String sourceSplit = "development".equals(requestedSplit) ? "validation" : "final";
        List<Source> out = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).trim().isEmpty()) continue;
            String[] row = parseCsv(lines.get(line));
            if (!sourceSplit.equals(value(row, columns, "split"))) continue;
            Path path = manifest.getParent().getParent()
                    .resolve(value(row, columns, "prepared_path"))
                    .normalize();
            String expected = value(row, columns, "prepared_sha256");
            String actual = sha256(path);
            if (!expected.equals(actual)) throw new IOException("source hash changed: " + path);
            out.add(new Source(value(row, columns, "case_id"), requestedSplit,
                    value(row, columns, "category"),
                    value(row, columns, "acquisition_group"), path, actual));
        }
        out.sort(Comparator.comparing((Source source) -> source.category)
                .thenComparing(source -> source.id));
        assertFiveClassBalance(out);
        return out;
    }

    private static void assertFiveClassBalance(List<Source> sources) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> acquisitions = new HashSet<>();
        for (Source source : sources) {
            counts.merge(source.category, 1, Integer::sum);
            if (!acquisitions.add(source.acquisition)) {
                throw new IllegalStateException("reused acquisition group " + source.acquisition);
            }
        }
        for (String category : categories()) {
            if (counts.getOrDefault(category, 0) != 2) {
                throw new IllegalStateException(category + " has "
                        + counts.getOrDefault(category, 0) + " sources, expected 2");
            }
        }
        if (sources.size() != 10) throw new IllegalStateException("expected 10 sources");
    }

    private static List<String> categories() {
        return Arrays.asList("PHASE", "BRIGHTFIELD_DIC", "DENSE_FLUOR",
                "SPARSE_LOWLIGHT", "FIDUCIAL_STATIC");
    }

    private static final class Base {
        final float[] pixels;
        final int width;
        final int height;
        final double mean;
        final double std;
        final double nonzeroFraction;

        Base(float[] pixels, int width, int height, double mean, double std,
             double nonzeroFraction) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.mean = mean;
            this.std = std;
            this.nonzeroFraction = nonzeroFraction;
        }
    }

    private static Base loadBase(Source source) throws IOException {
        ImagePlus image = IJ.openImage(source.path.toString());
        if (image == null) throw new IOException("ImageJ could not open " + source.path);
        try {
            ImageProcessor processor = image.getStack().getProcessor(1).convertToFloatProcessor();
            float[] pixels = ((float[]) processor.getPixels()).clone();
            double sum = 0;
            int finite = 0;
            int nonzero = 0;
            for (float pixel : pixels) {
                if (!Float.isFinite(pixel)) continue;
                finite++;
                sum += pixel;
                if (pixel != 0) nonzero++;
            }
            if (finite != pixels.length) throw new IOException("non-finite source " + source.id);
            double mean = sum / finite;
            double squares = 0;
            for (float pixel : pixels) squares += (pixel - mean) * (pixel - mean);
            double std = Math.sqrt(squares / Math.max(1, finite - 1));
            double nonzeroFraction = nonzero / (double) finite;
            if (!(std > 0) || nonzeroFraction < 0.001) {
                throw new IOException("empty or constant source " + source.id + ": std=" + std
                        + ", nonzero fraction=" + nonzeroFraction);
            }
            if (processor.getWidth() != 256 || processor.getHeight() != 256) {
                throw new IOException(source.id + " is " + processor.getWidth() + "x"
                        + processor.getHeight() + ", expected 256x256");
            }
            return new Base(pixels, processor.getWidth(), processor.getHeight(), mean, std,
                    nonzeroFraction);
        } finally {
            image.close();
        }
    }

    private static final class Generated {
        final float[][] planes;
        final Transform[] truth;

        Generated(float[][] planes, Transform[] truth) {
            this.planes = planes;
            this.truth = truth;
        }
    }

    static Generated generate(Base base, ControlledMotionProfile profile, Scope scope) {
        return generate(base, profile, scope, Condition.CLEAN);
    }

    static Generated generate(Base base, ControlledMotionProfile profile, Scope scope,
                              Condition condition) {
        double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                base.pixels, base.width, base.height);
        Transform[] translations = profile.translationTrajectory();
        Transform[] truth = new Transform[FRAMES];
        float[][] planes = new float[FRAMES][];
        for (int frame = 0; frame < FRAMES; frame++) {
            double theta = scope == Scope.RIGID ? rigidAngle(profile, frame) : 0;
            truth[frame] = new Transform(translations[frame].dx, translations[frame].dy, theta);
            planes[frame] = ThevenazProtocolBenchmark.spline7Warp(
                    coefficients, base.width, base.height, truth[frame].inverse());
            double frameGain = gain(condition, frame);
            if (frameGain != 1.0) {
                for (int pixel = 0; pixel < planes[frame].length; pixel++) {
                    planes[frame][pixel] = (float) (planes[frame][pixel] * frameGain);
                }
            }
        }
        return new Generated(planes, truth);
    }

    static double gain(Condition condition, int frame) {
        if (frame < 0 || frame >= FRAMES) {
            throw new IllegalArgumentException("frame outside 0.." + (FRAMES - 1));
        }
        return condition == Condition.GAIN_FADE
                ? Math.pow(2.0, -2.0 * frame / (FRAMES - 1.0)) : 1.0;
    }

    static double rigidAngle(ControlledMotionProfile profile, int frame) {
        double u = frame / (double) (FRAMES - 1);
        switch (profile) {
            case CURVED_OSCILLATING_DRIFT:
                return Math.toRadians(1.2 * Math.sin(2 * Math.PI * u) + 0.5 * u);
            case STEADY_DIRECTIONAL_DRIFT:
                return Math.toRadians(1.6 * u);
            case SUBPIXEL_RANDOM_WALK:
                java.util.Random random = new java.util.Random(2_026_082_5L);
                double angle = 0;
                for (int i = 1; i <= frame; i++) {
                    angle += Math.toRadians(0.08 * random.nextGaussian());
                }
                return angle;
            case INTERMITTENT_JUMPS:
            default:
                double degrees = 0.4 * u;
                if (frame >= 12) degrees += 0.8;
                if (frame >= 27) degrees -= 1.4;
                if (frame >= 39) degrees += 1.0;
                return Math.toRadians(degrees);
        }
    }

    private static void generate(Path project, String split, Scope scope, Condition condition,
                                 Path output)
            throws Exception {
        requireInsideProject(project, output);
        Files.createDirectories(output);
        Path manifest = output.resolve("case_manifest.csv");
        if (Files.exists(manifest) && !Boolean.getBoolean("publication.rewrite")) {
            throw new IOException("refusing to overwrite immutable case manifest " + manifest);
        }
        StringBuilder rows = new StringBuilder("split,scope,image_series_class,series_id,"
                + "acquisition_group,motion_category,condition,frames,width,height,source_path,"
                + "source_sha256,source_mean,source_std,source_nonzero_fraction,input_path,"
                + "input_sha256,truth_path,truth_sha256,generator,generated_utc\n");
        int complete = 0;
        for (Source source : sources(project, split)) {
            Base base = loadBase(source);
            for (ControlledMotionProfile profile : ControlledMotionProfile.values()) {
                Path recording = output.resolve(source.category).resolve(source.id)
                        .resolve(profile.name()).resolve(condition.name());
                Files.createDirectories(recording);
                Path input = recording.resolve("00_input_uncorrected.tif");
                Path truth = recording.resolve("truth.csv");
                if ((Files.exists(input) || Files.exists(truth))
                        && !Boolean.getBoolean("publication.rewrite")) {
                    throw new IOException("refusing to overwrite generated case " + recording);
                }
                Generated generated = generate(base, profile, scope, condition);
                ImageStack stack = new ImageStack(base.width, base.height);
                for (int frame = 0; frame < generated.planes.length; frame++) {
                    stack.addSlice("t" + (frame + 1), new FloatProcessor(base.width, base.height,
                            generated.planes[frame].clone(), null));
                }
                ImagePlus image = new ImagePlus(
                        source.id + " " + profile.name() + " " + condition.name(), stack);
                try {
                    IJ.saveAsTiff(image, input.toString());
                } finally {
                    image.close();
                }
                writeTruth(truth, generated.truth);
                rows.append(csv(split)).append(',').append(csv(scope.name().toLowerCase(Locale.ROOT)))
                        .append(',').append(csv(source.category)).append(',').append(csv(source.id))
                        .append(',').append(csv(source.acquisition)).append(',')
                        .append(csv(profile.name())).append(',').append(condition.name()).append(',')
                        .append(FRAMES).append(',')
                        .append(base.width).append(',').append(base.height).append(',')
                        .append(csv(project.relativize(source.path).toString())).append(',')
                        .append(source.sha256).append(',').append(number(base.mean)).append(',')
                        .append(number(base.std)).append(',').append(number(base.nonzeroFraction))
                        .append(',').append(csv(project.relativize(input).toString())).append(',')
                        .append(sha256(input)).append(',')
                        .append(csv(project.relativize(truth).toString())).append(',')
                        .append(sha256(truth)).append(',').append(csv(
                                condition == Condition.CLEAN ? "thevenaz-spline7-v1"
                                        : "thevenaz-spline7-v1+global-gain-1-to-0.25-v1"))
                        .append(',')
                        .append(Instant.now().toString()).append('\n');
                System.out.printf(Locale.ROOT, "generated %s %s %s %s %s (%d/40)%n", split,
                        scope.name().toLowerCase(Locale.ROOT), condition.name(), source.id,
                        profile.name(), ++complete);
            }
        }
        if (complete != 40) throw new IllegalStateException("generated " + complete + ", expected 40");
        Files.write(manifest, rows.toString().getBytes(StandardCharsets.UTF_8));
        audit(project, output);
    }

    private static void writeTruth(Path path, Transform[] truth) throws IOException {
        StringBuilder out = new StringBuilder("frame,dx,dy,theta_radians\n");
        for (int i = 0; i < truth.length; i++) {
            out.append(i).append(',').append(number(truth[i].dx)).append(',')
                    .append(number(truth[i].dy)).append(',')
                    .append(number(truth[i].theta)).append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void runRipr(Path project, String split, Scope scope, Path inputRoot,
                                Path output, InternalRecipe recipe) throws Exception {
        requireInsideProject(project, inputRoot);
        requireInsideProject(project, output);
        audit(project, inputRoot);
        List<Path> inputs = inputs(inputRoot);
        if (inputs.size() != 40) throw new IOException("expected 40 cases, found " + inputs.size());
        Files.createDirectories(output.getParent());
        String header = "split,scope,image_series_class,series_id,motion_category,condition,"
                + "method_id,method_label,status,median_error_px,p90_error_px,max_error_px,"
                + "terminal_error_px,median_angle_error_deg,p90_angle_error_deg,"
                + "max_angle_error_deg,median_translation_error_px,max_translation_error_px,"
                + "frames_over_1px,frames_over_5px,cpu_seconds,elapsed_seconds,pairs,"
                + "repaired_frames,unsupported_frames,rotation_accepted_pairs,"
                + "rotation_declined_pairs,transform_x_px,transform_y_px,"
                + "transform_theta_rad,requested_policy\n";
        boolean rewrite = Boolean.getBoolean("publication.rewrite");
        if (rewrite || !Files.exists(output)) {
            Files.write(output, header.getBytes(StandardCharsets.UTF_8));
        } else if (!Files.readAllLines(output, StandardCharsets.UTF_8).get(0).equals(header.trim())) {
            throw new IOException("result header drifted in " + output);
        }
        Set<String> completed = rewrite ? Collections.<String>emptySet() : completed(output);
        boolean warmed = false;
        int done = completed.size();
        for (Path input : inputs) {
            Path relative = inputRoot.relativize(input);
            String category = relative.getName(0).toString();
            String series = relative.getName(1).toString();
            String motion = relative.getName(2).toString();
            String condition = relative.getName(3).toString();
            String key = category + "/" + series + "/" + motion + "/" + condition;
            if (completed.contains(key)) continue;
            ImagePlus image = IJ.openImage(input.toString());
            if (image == null) throw new IOException("could not open " + input);
            try {
                RelativeIntensityPatternParameters parameters = parameters(
                        category, motion, scope, recipe);
                if (!warmed) {
                    try {
                        RelativeIntensityPatternRegistration.estimate(image, parameters,
                                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
                    } catch (RuntimeException warmupFailure) {
                        System.err.println("RIPR warm-up unavailable: " + concise(warmupFailure));
                    }
                    warmed = true;
                }
                String row = measureRipr(split, scope, category, series, motion, condition,
                        image, parameters, input.getParent(), recipe);
                Files.write(output, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            } finally {
                image.close();
            }
            completed.add(key);
            System.out.printf(Locale.ROOT, "RIPR %s %s %s %s (%d/40)%n", recipe.token,
                    scope.name(), series, motion, ++done);
        }
    }

    static RelativeIntensityPatternParameters parameters(String category, String motion,
                                                          Scope scope, InternalRecipe recipe) {
        RelativeIntensityPatternParameters recommended = RelativeIntensityPatternParameters.builder()
                .recommendation(imageType(category), MotionType.valueOf(motion))
                .rotationMode(scope == Scope.RIGID ? RotationMode.CONTINUOUS : RotationMode.OFF)
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .build();
        if (recipe == InternalRecipe.AUTOMATIC) {
            return recommended.toBuilder().selectionMode(SelectionMode.AUTOMATIC).build();
        }
        if (recipe == InternalRecipe.LONGITUDINAL_ACCURACY) {
            return recommended.toBuilder()
                    .selectionMode(SelectionMode.LONGITUDINAL_ACCURACY)
                    .rotationMode(RotationMode.OFF)
                    .fitRotation(false)
                    .build();
        }
        if (recipe == InternalRecipe.RECOMMENDED) return recommended;
        RelativeIntensityPatternParameters base = recommended.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .build();
        if (recipe == InternalRecipe.AREA_GRID || recipe == InternalRecipe.AREA_GRID_FIRST) {
            return withReference(base.toBuilder()
                    .estimator(PairEstimator.Kind.AREA_CORRELATION), recipe).build();
        }
        if (recipe == InternalRecipe.AREA_NEWTON || recipe == InternalRecipe.AREA_NEWTON_FIRST) {
            return withReference(base.toBuilder()
                    .estimator(PairEstimator.Kind.AREA_CORRELATION_NEWTON), recipe).build();
        }
        if (recipe == InternalRecipe.AREA_ECC || recipe == InternalRecipe.AREA_ECC_FIRST) {
            return withReference(base.toBuilder()
                    .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC), recipe).build();
        }
        throw new IllegalStateException("unhandled internal recipe " + recipe);
    }

    private static RelativeIntensityPatternParameters.Builder withReference(
            RelativeIntensityPatternParameters.Builder builder, InternalRecipe recipe) {
        if (recipe == InternalRecipe.AREA_GRID_FIRST
                || recipe == InternalRecipe.AREA_NEWTON_FIRST
                || recipe == InternalRecipe.AREA_ECC_FIRST) {
            return builder.reference(Reconciler.Reference.FIXED).referenceFrame(1);
        }
        return builder;
    }

    private static ImageType imageType(String category) {
        if ("PHASE".equals(category)) return ImageType.PHASE_CONTRAST;
        if ("BRIGHTFIELD_DIC".equals(category)) return ImageType.BRIGHTFIELD_DIC;
        if ("DENSE_FLUOR".equals(category)) return ImageType.DENSE_FLUORESCENCE;
        if ("SPARSE_LOWLIGHT".equals(category)) return ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
        if ("FIDUCIAL_STATIC".equals(category)) return ImageType.FIDUCIAL_STATIC;
        throw new IllegalArgumentException("unknown category " + category);
    }

    private static String measureRipr(String split, Scope scope, String category, String series,
                                      String motion, String condition, ImagePlus image,
                                      RelativeIntensityPatternParameters parameters, Path recording,
                                      InternalRecipe recipe)
            throws IOException {
        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();
        Registration.Result result = null;
        String status = "ok";
        try {
            result = RelativeIntensityPatternRegistration.estimate(image, parameters,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        } catch (RuntimeException error) {
            status = "failed:" + concise(error);
        }
        double elapsed = (System.nanoTime() - wall0) / 1e9;
        double cpu = (processCpuNanos() - cpu0) / 1e9;
        ExternalPluginComparisonStacks.Truth truth = ExternalPluginComparisonStacks.readTruth(
                recording, motion, FRAMES);
        double[] errors = result == null ? null : ExternalPluginComparisonStacks.geometricErrors(
                result.cumulative, truth.x, truth.y, truth.theta, image.getWidth());
        double[] angles = result == null ? null : ExternalPluginComparisonStacks.angleErrorsDegrees(
                result.cumulative, truth.theta);
        double[] translations = result == null ? null
                : translationErrors(result.cumulative, truth.x, truth.y);
        String methodId = recipe.methodId(scope);
        String label = recipe.label(scope);
        int repaired = 0;
        int unsupported = 0;
        if (result != null) {
            for (int i = 0; i < result.repairs.length; i++) {
                if (result.repairs[i] != null) repaired++;
                if (result.support[i] == 0) unsupported++;
            }
        }
        return csv(split) + ',' + csv(scope.name().toLowerCase(Locale.ROOT)) + ','
                + csv(category) + ',' + csv(series) + ',' + csv(motion) + ',' + csv(condition)
                + ',' + csv(methodId) + ',' + csv(label) + ',' + csv(status) + ','
                + metric(errors, 0.5) + ',' + metric(errors, 0.9) + ',' + metric(errors, 1.0)
                + ',' + (errors == null ? "" : number(errors[errors.length - 1])) + ','
                + metric(angles, 0.5) + ',' + metric(angles, 0.9) + ',' + metric(angles, 1.0)
                + ',' + metric(translations, 0.5) + ',' + metric(translations, 1.0) + ','
                + (errors == null ? -1 : countAbove(errors, 1)) + ','
                + (errors == null ? -1 : countAbove(errors, 5)) + ',' + number(cpu) + ','
                + number(elapsed) + ',' + (result == null ? 0 : result.pairs.size()) + ','
                + repaired + ',' + unsupported + ','
                + (result == null ? 0 : result.rotationAcceptedPairs()) + ','
                + (result == null ? 0 : result.rotationDeclinedPairs()) + ','
                + csv(transformSeries(result, 0)) + ',' + csv(transformSeries(result, 1)) + ','
                + csv(transformSeries(result, 2)) + ','
                + csv("internal recipe=" + recipe.token + "; image type=" + parameters.imageType.name()
                        + "; motion type=" + parameters.motionType.name() + "; rotation mode="
                        + parameters.rotationMode.name() + "; crop excluded from timing") + '\n';
    }

    private static double[] translationErrors(Transform[] transforms, double[] x, double[] y) {
        double[] out = new double[transforms.length];
        for (int i = 0; i < out.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            out[i] = Math.hypot(transform.dx - x[i], transform.dy - y[i]);
        }
        return out;
    }

    private static List<Path> inputs(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root, 5)) {
            walk.filter(path -> path.getFileName().toString().equals("00_input_uncorrected.tif"))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static Set<String> completed(Path output) throws IOException {
        Set<String> out = new HashSet<>();
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            String[] row = parseCsv(lines.get(i));
            out.add(row[2] + "/" + row[3] + "/" + row[4] + "/" + row[5]);
        }
        return out;
    }

    private static void audit(Path project, Path root) throws Exception {
        requireInsideProject(project, root);
        Path manifest = root.resolve("case_manifest.csv");
        if (!Files.isRegularFile(manifest)) throw new IOException("missing " + manifest);
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() != 41) throw new IOException("expected 40 manifest rows in " + manifest);
        Map<String, Integer> columns = columns(lines.get(0));
        Map<String, Integer> classes = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] row = parseCsv(lines.get(i));
            String key = value(row, columns, "image_series_class") + "/"
                    + value(row, columns, "series_id") + "/"
                    + value(row, columns, "motion_category") + "/"
                    + value(row, columns, "condition");
            if (!keys.add(key)) throw new IOException("duplicate case " + key);
            classes.merge(value(row, columns, "image_series_class"), 1, Integer::sum);
            Path input = project.resolve(value(row, columns, "input_path")).normalize();
            Path truth = project.resolve(value(row, columns, "truth_path")).normalize();
            if (!sha256(input).equals(value(row, columns, "input_sha256"))) {
                throw new IOException("input hash changed: " + input);
            }
            if (!sha256(truth).equals(value(row, columns, "truth_sha256"))) {
                throw new IOException("truth hash changed: " + truth);
            }
            ExternalPluginComparisonStacks.Truth exact =
                    ExternalPluginComparisonStacks.readTruth(truth.getParent(),
                            value(row, columns, "motion_category"), FRAMES);
            if (exact.x.length != FRAMES) throw new IOException("truth frame count changed");
        }
        for (String category : categories()) {
            if (classes.getOrDefault(category, 0) != 8) {
                throw new IOException(category + " has " + classes.getOrDefault(category, 0)
                        + " cases, expected 8");
            }
        }
        System.out.println("audit passed: 40 immutable cases, five balanced classes under " + root);
    }

    private static Map<String, Integer> columns(String header) {
        String[] values = parseCsv(header);
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < values.length; i++) out.put(values[i].trim(), i);
        return out;
    }

    private static String value(String[] row, Map<String, Integer> columns, String name) {
        Integer column = columns.get(name);
        if (column == null || column >= row.length) {
            throw new IllegalArgumentException("missing CSV column " + name);
        }
        return row[column];
    }

    static String[] parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(value);
            }
        }
        if (quoted) throw new IllegalArgumentException("unterminated CSV quote");
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    private static String metric(double[] values, double quantile) {
        return values == null ? "" : number(Benchmark.quantile(values, quantile));
    }

    private static int countAbove(double[] values, double threshold) {
        int count = 0;
        for (double value : values) if (value > threshold) count++;
        return count;
    }

    private static String transformSeries(Registration.Result result, int component) {
        if (result == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < result.cumulative.length; i++) {
            if (i > 0) out.append(';');
            Transform transform = result.cumulative[i];
            if (transform == null) out.append("NaN");
            else out.append(number(component == 0 ? transform.dx
                    : component == 1 ? transform.dy : transform.theta));
        }
        return out.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String sha256(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (java.io.InputStream input = Files.newInputStream(path)) {
                for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        try {
            java.lang.reflect.Method method = bean.getClass().getMethod("getProcessCpuTime");
            method.setAccessible(true);
            return ((Number) method.invoke(bean)).longValue();
        } catch (Exception unavailable) {
            return System.nanoTime();
        }
    }

    private static String concise(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) message = root.getClass().getSimpleName();
        return (root.getClass().getSimpleName() + ":" + message).replace('\n', ' ')
                .replace('\r', ' ');
    }

    private static void requireInsideProject(Path project, Path path) {
        if (!path.normalize().startsWith(project.normalize())) {
            throw new IllegalArgumentException("path leaves project: " + path);
        }
    }
}
