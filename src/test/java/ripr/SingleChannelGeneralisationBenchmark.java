/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.PixelSelectionStrategy;
import ripr.api.Preprocessing;
import ripr.api.RegistrationRecipe;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.SelectionMode;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.PairScheduler;
import ripr.core.MultiTimeScalePhaseCorrelation;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Round-2 generalisation runner. It accepts only already-separated, one-channel TYX stacks. */
public final class SingleChannelGeneralisationBenchmark {

    enum Attempt {
        A000_ACCEPTED_PREVIOUS_ECC,
        A101_PREVIOUS_ECC_REPAIR_8,
        A102_RELATIVE_SPARSE_ANCHOR_ECC,
        A103_ROLLING_TEMPLATE_ECC,
        A104_FIRST_FRAME_ECC,
        A105_LOCAL_CONTRAST_ECC,
        A106_STRUCTURAL_GRADIENT_ECC,
        A107_JUMP_LOCAL_CONTRAST_1_16_ECC,
        A108_JUMP_LOCAL_CONTRAST_1_16_REPAIR_8_ECC,
        A109_JUMP_LOCAL_CONTRAST_1_8_ECC,
        A110_JUMP_LOCAL_CONTRAST_1_32_ECC,
        A111_JUMP_LOCAL_CONTRAST_1_8_CORRELATION,
        A112_JUMP_LOCAL_CONTRAST_1_8_CORRELATION_NEWTON,
        A113_JUMP_POSITIVE_LOCAL_CONTRAST_1_8_ECC,
        A125_JUMP_FIRST_FRAME_POSITIVE_LOCAL_CONTRAST_ECC,
        A126_JUMP_FIRST_FRAME_POSITIVE_LOCAL_CONTRAST_REPAIR_8_ECC,
        A127_JUMP_A126_DENSE_SLOW_NO_FILTER,
        A129_PRODUCTION_AUTOMATIC,
        A130_PRODUCTION_AUTOMATIC_FINAL,
        A201_JUMP_FIRST_FRAME_LOCAL_CONTRAST_KEEP_JUMPS,
        A202_PLAIN_FIRST_FRAME_ECC,
        A203_HYBRID_FIXED_PREVIOUS_ECC_EQUAL,
        A204_HYBRID_FIXED_PREVIOUS_ECC_COMBINED,
        A205_RECOMMENDED_PRESET,
        A206_DECLARED_TYPE_RESCUE,
        A207_RESTORED_SPARSE_DENSE_PREVIOUS,
        A208_MAX_ACCURACY_DECLARED_TYPE,
        A209_DENSE_GAUSSIAN_0_7_ECC,
        A210_PHASE_MULTITIME_SUBPIXEL,
        A211_PHASE_MULTITIME_INTEGER,
        A212_DENSE_PHASE_MULTITIME_INTEGER,
        A213_DENSE_VERIFIED_PHASE_MULTITIME_INTEGER,
        A214_DENSE_VERIFIED_PHASE_FIRST_INTEGER,
        A215_DENSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER,
        A216_SPARSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER,
        A217_DENSE_BOUNDED_VERIFIED_PHASE_FIRST_JUMPS_INTEGER;

        static Attempt from(String value) {
            String wanted = value.trim().toUpperCase(Locale.ROOT);
            for (Attempt item : values()) {
                if (item.name().equals(wanted) || item.name().startsWith(wanted + "_")) return item;
            }
            throw new IllegalArgumentException("unknown attempt " + value);
        }

        String id() {
            return name().substring(0, 4);
        }
    }

    enum Perturbation {
        NATIVE,
        SCALE_0_75,
        SCALE_1_50,
        AFFINE_INTENSITY;

        static Perturbation from(String value) {
            return value == null ? NATIVE : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private SingleChannelGeneralisationBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException(
                    "usage: <single-channel-input-dir> <cases.csv> <output.csv> <attempt> [perturbation]");
        }
        run(Paths.get(args[0]).toAbsolutePath().normalize(),
                Paths.get(args[1]).toAbsolutePath().normalize(),
                Paths.get(args[2]).toAbsolutePath().normalize(), Attempt.from(args[3]),
                Perturbation.from(args.length == 5 ? args[4] : null));
    }

    static void run(Path inputDir, Path casesFile, Path output, Attempt attempt) throws Exception {
        run(inputDir, casesFile, output, attempt, Perturbation.NATIVE);
    }

    static void run(Path inputDir, Path casesFile, Path output, Attempt attempt,
                    Perturbation perturbation) throws Exception {
        List<Map<String, String>> cases = readCsv(casesFile);
        if (cases.isEmpty()) throw new IOException("the frozen case set is empty");
        if (Files.exists(output)) throw new IOException("refusing to overwrite " + output);
        Files.createDirectories(output.getParent());
        String header = "attempt_id,case_id,role,issue,channel,recording,motion,status,error,"
                + "cpu_seconds,elapsed_seconds,pair_count,repaired_frames,unsupported_frames,"
                + "median_residual_before,median_residual_after,reference,estimator,pixel_selection,"
                + "resolved_recipe,channels_read,transform_x_px,transform_y_px,transform_theta_rad,"
                + "residual_before,residual_after,valid_fraction,fit_status\n";
        Files.write(output, header.getBytes(StandardCharsets.UTF_8));

        boolean warmed = false;
        int done = 0;
        for (Map<String, String> item : cases) {
            String inputFile = item.get("input_file");
            if (inputFile == null || inputFile.trim().isEmpty()) {
                inputFile = item.get("case_id") + ".tif";
            }
            Path input = inputDir.resolve(inputFile);
            ImagePlus source = IJ.openImage(input.toString());
            if (source == null) throw new IOException("could not open " + input);
            ImagePlus image = source;
            try {
                if (source.getNChannels() != 1) {
                    throw new IOException("cross-channel input prohibited: " + input
                            + " has " + source.getNChannels() + " channels");
                }
                image = perturb(source, perturbation);
                if (image.getNChannels() != 1) throw new IOException("perturbation created channels");
                RelativeIntensityPatternParameters requested = requested(item);
                AutomaticRegistrationSelector.Result selection =
                        RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, requested);
                boolean directDeclaredRecipe = attempt == Attempt.A202_PLAIN_FIRST_FRAME_ECC
                        || attempt == Attempt.A203_HYBRID_FIXED_PREVIOUS_ECC_EQUAL
                        || attempt == Attempt.A204_HYBRID_FIXED_PREVIOUS_ECC_COMBINED
                        || attempt == Attempt.A205_RECOMMENDED_PRESET
                        || attempt == Attempt.A206_DECLARED_TYPE_RESCUE
                        || attempt == Attempt.A207_RESTORED_SPARSE_DENSE_PREVIOUS
                        || attempt == Attempt.A208_MAX_ACCURACY_DECLARED_TYPE
                        || attempt == Attempt.A209_DENSE_GAUSSIAN_0_7_ECC;
                boolean multiTime = attempt == Attempt.A210_PHASE_MULTITIME_SUBPIXEL
                        || attempt == Attempt.A211_PHASE_MULTITIME_INTEGER
                        || attempt == Attempt.A212_DENSE_PHASE_MULTITIME_INTEGER
                        || attempt == Attempt.A213_DENSE_VERIFIED_PHASE_MULTITIME_INTEGER
                        || attempt == Attempt.A214_DENSE_VERIFIED_PHASE_FIRST_INTEGER
                        || attempt == Attempt.A215_DENSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                        || attempt == Attempt.A216_SPARSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                        || attempt == Attempt.A217_DENSE_BOUNDED_VERIFIED_PHASE_FIRST_JUMPS_INTEGER;
                RelativeIntensityPatternParameters parameters = apply(
                        directDeclaredRecipe ? requested : selection.parameters,
                        multiTime ? Attempt.A129_PRODUCTION_AUTOMATIC : attempt,
                        image.getNFrames());
                if (!warmed && !Boolean.getBoolean("ripr.benchmark.skipWarmup")) {
                    try {
                        RelativeIntensityPatternRegistration.estimate(image, parameters,
                                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
                    } catch (RuntimeException ignored) {
                        // The excluded warm-up follows the identical route.
                    }
                    warmed = true;
                }
                String measured = multiTime && multiTimeApplies(attempt, requested)
                        ? measureMultiTimePhase(image, item, attempt,
                                attempt == Attempt.A211_PHASE_MULTITIME_INTEGER
                                        || attempt == Attempt.A212_DENSE_PHASE_MULTITIME_INTEGER
                                        || attempt == Attempt.A213_DENSE_VERIFIED_PHASE_MULTITIME_INTEGER
                                        || attempt == Attempt.A214_DENSE_VERIFIED_PHASE_FIRST_INTEGER
                                        || attempt == Attempt.A215_DENSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                                        || attempt == Attempt.A216_SPARSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                                        || attempt == Attempt.A217_DENSE_BOUNDED_VERIFIED_PHASE_FIRST_JUMPS_INTEGER)
                        : attempt == Attempt.A203_HYBRID_FIXED_PREVIOUS_ECC_EQUAL
                        || attempt == Attempt.A204_HYBRID_FIXED_PREVIOUS_ECC_COMBINED
                        ? measureHybrid(image, item, parameters, attempt)
                        : measure(image, item, parameters, attempt);
                Files.write(output, measured
                                .getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.APPEND);
                System.out.printf(Locale.ROOT, "%s %s %d/%d%n", attempt.id(),
                        item.get("case_id"), ++done, cases.size());
            } finally {
                if (image != source) image.close();
                source.close();
            }
        }
    }

    static ImagePlus perturb(ImagePlus source, Perturbation perturbation) {
        if (perturbation == Perturbation.NATIVE) return source;
        int frames = source.getStackSize();
        if (perturbation == Perturbation.AFFINE_INTENSITY) {
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            for (int slice = 1; slice <= frames; slice++) {
                float[] pixels = (float[]) source.getStack().getProcessor(slice)
                        .convertToFloatProcessor().getPixels();
                for (float value : pixels) if (Float.isFinite(value)) {
                    minimum = Math.min(minimum, value);
                    maximum = Math.max(maximum, value);
                }
            }
            float span = Double.isFinite(minimum) && Double.isFinite(maximum)
                    ? (float) Math.max(0, maximum - minimum) : 0f;
            ImageStack output = new ImageStack(source.getWidth(), source.getHeight());
            for (int slice = 1; slice <= frames; slice++) {
                float fraction = frames == 1 ? 0f : (slice - 1f) / (frames - 1f);
                float gain = 0.65f + 0.70f * fraction;
                float offset = 0.10f * span * (1f - fraction);
                float[] input = (float[]) source.getStack().getProcessor(slice)
                        .convertToFloatProcessor().getPixels();
                float[] pixels = input.clone();
                for (int i = 0; i < pixels.length; i++) {
                    if (Float.isFinite(pixels[i])) pixels[i] = gain * pixels[i] + offset;
                }
                output.addSlice(null, pixels);
            }
            return oneChannel(source.getTitle() + " affine", output, frames);
        }
        double scale = perturbation == Perturbation.SCALE_0_75 ? 0.75 : 1.50;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        ImageStack output = new ImageStack(width, height);
        for (int slice = 1; slice <= frames; slice++) {
            ImageProcessor processor = source.getStack().getProcessor(slice).convertToFloatProcessor();
            processor.setInterpolationMethod(ImageProcessor.BILINEAR);
            output.addSlice(null, processor.resize(width, height));
        }
        return oneChannel(source.getTitle() + " " + perturbation.name(), output, frames);
    }

    private static ImagePlus oneChannel(String title, ImageStack stack, int frames) {
        ImagePlus result = new ImagePlus(title, stack);
        result.setDimensions(1, 1, frames);
        result.setOpenAsHyperStack(frames > 1);
        return result;
    }

    static RelativeIntensityPatternParameters apply(
            RelativeIntensityPatternParameters base, Attempt attempt, int frames) {
        if (attempt == Attempt.A129_PRODUCTION_AUTOMATIC
                || attempt == Attempt.A130_PRODUCTION_AUTOMATIC_FINAL) {
            return base.toBuilder().crop(false).threads(benchmarkThreads()).build();
        }
        if (attempt == Attempt.A205_RECOMMENDED_PRESET) {
            return base.toBuilder().selectionMode(SelectionMode.RECOMMENDED)
                    .crop(false).threads(benchmarkThreads()).build();
        }
        if (attempt == Attempt.A206_DECLARED_TYPE_RESCUE) {
            if (base.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE) {
                return base.toBuilder().selectionMode(SelectionMode.RECOMMENDED)
                        .crop(false).threads(benchmarkThreads()).build();
            }
            return base.toBuilder()
                    .selectionMode(SelectionMode.MANUAL)
                    .crop(false)
                    .threads(benchmarkThreads())
                    .reference(Reconciler.Reference.CONSECUTIVE)
                    .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                    .preprocessing(Preprocessing.NONE)
                    .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                    .pixelSelectionPreprocessing(Preprocessing.NONE)
                    .floorPercentile(Double.NaN)
                    .ceilingPercentile(Double.NaN)
                    .outlierMads(0.0)
                    .outlierProtectionResidualGain(Double.NaN)
                    .recipeProvenance("R04 A206 declared-type rescue")
                    .build();
        }
        if (attempt == Attempt.A207_RESTORED_SPARSE_DENSE_PREVIOUS) {
            if (base.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE) {
                return base.toBuilder()
                        .selectionMode(SelectionMode.MANUAL)
                        .crop(false)
                        .threads(benchmarkThreads())
                        .reference(Reconciler.Reference.MULTILAG)
                        .estimator(PairEstimator.Kind.LOG_RATIO_FIT)
                        .pixelSupport(PairAligner.PixelSupport.ALL)
                        .gradientFraction(0.5)
                        .preprocessing(Preprocessing.GAUSSIAN_1_0)
                        .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                        .pixelSelectionPreprocessing(Preprocessing.NONE)
                        .floorPercentile(Double.NaN)
                        .ceilingPercentile(90.0)
                        .outlierProtectionResidualGain(Double.NaN)
                        .maxIterations(25)
                        .maxSamples(200_000)
                        .recipeProvenance("R04 A207 restored sparse tuned recipe")
                        .build();
            }
            return base.toBuilder()
                    .selectionMode(SelectionMode.MANUAL)
                    .crop(false)
                    .threads(benchmarkThreads())
                    .reference(Reconciler.Reference.CONSECUTIVE)
                    .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                    .preprocessing(Preprocessing.NONE)
                    .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                    .pixelSelectionPreprocessing(Preprocessing.NONE)
                    .floorPercentile(Double.NaN)
                    .ceilingPercentile(Double.NaN)
                    .outlierMads(0.0)
                    .outlierProtectionResidualGain(Double.NaN)
                    .recipeProvenance("R04 A207 dense previous-image ECC")
                    .build();
        }
        if (attempt == Attempt.A208_MAX_ACCURACY_DECLARED_TYPE) {
            if (base.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE) {
                return base.toBuilder().selectionMode(SelectionMode.RECOMMENDED)
                        .crop(false).threads(benchmarkThreads()).build();
            }
            return base.toBuilder()
                    .selectionMode(SelectionMode.MANUAL)
                    .crop(false)
                    .threads(benchmarkThreads())
                    .reference(Reconciler.Reference.CONSECUTIVE)
                    .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                    .pixelSupport(PairAligner.PixelSupport.GRADIENT)
                    .gradientFraction(0.5)
                    .preprocessing(Preprocessing.MEDIAN_3X3)
                    .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                    .pixelSelectionPreprocessing(Preprocessing.NONE)
                    .floorPercentile(Double.NaN)
                    .ceilingPercentile(90.0)
                    .outlierMads(0.0)
                    .outlierProtectionResidualGain(Double.NaN)
                    .maxIterations(25)
                    .maxSamples(200_000)
                    .recipeProvenance("R04 A208 maximum-accuracy declared-type rescue")
                    .build();
        }
        if (attempt == Attempt.A209_DENSE_GAUSSIAN_0_7_ECC) {
            if (base.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE) {
                return base.toBuilder().selectionMode(SelectionMode.RECOMMENDED)
                        .crop(false).threads(benchmarkThreads()).build();
            }
            return base.toBuilder()
                    .selectionMode(SelectionMode.MANUAL)
                    .crop(false)
                    .threads(benchmarkThreads())
                    .reference(Reconciler.Reference.CONSECUTIVE)
                    .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                    .pixelSupport(PairAligner.PixelSupport.GRADIENT)
                    .gradientFraction(0.5)
                    .preprocessing(Preprocessing.GAUSSIAN_0_7)
                    .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                    .pixelSelectionPreprocessing(Preprocessing.NONE)
                    .floorPercentile(Double.NaN)
                    .ceilingPercentile(90.0)
                    .outlierMads(0.0)
                    .outlierProtectionResidualGain(Double.NaN)
                    .maxIterations(25)
                    .maxSamples(200_000)
                    .recipeProvenance("R05 A209 dense Gaussian 0.7 ECC")
                    .build();
        }
        RelativeIntensityPatternParameters.Builder builder = base.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .crop(false)
                .threads(benchmarkThreads())
                .reference(Reconciler.Reference.CONSECUTIVE)
                .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE);
        switch (attempt) {
            case A000_ACCEPTED_PREVIOUS_ECC:
                break;
            case A101_PREVIOUS_ECC_REPAIR_8:
                builder.outlierMads(8.0);
                break;
            case A102_RELATIVE_SPARSE_ANCHOR_ECC:
                int anchor = Math.max(2, (int) Math.round((frames - 1) * 0.20));
                builder.reference(Reconciler.Reference.MULTILAG).lags(1, anchor);
                break;
            case A103_ROLLING_TEMPLATE_ECC:
                builder.reference(Reconciler.Reference.ROLLING).templateWindow(5);
                break;
            case A104_FIRST_FRAME_ECC:
                builder.reference(Reconciler.Reference.FIXED).referenceFrame(1);
                break;
            case A105_LOCAL_CONTRAST_ECC:
                builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_16);
                break;
            case A106_STRUCTURAL_GRADIENT_ECC:
                builder.preprocessing(Preprocessing.STRUCTURAL_GRADIENT);
                break;
            case A107_JUMP_LOCAL_CONTRAST_1_16_ECC:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_16);
                }
                break;
            case A108_JUMP_LOCAL_CONTRAST_1_16_REPAIR_8_ECC:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_16).outlierMads(8.0);
                }
                break;
            case A109_JUMP_LOCAL_CONTRAST_1_8_ECC:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_8);
                }
                break;
            case A110_JUMP_LOCAL_CONTRAST_1_32_ECC:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_32);
                }
                break;
            case A111_JUMP_LOCAL_CONTRAST_1_8_CORRELATION:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_8)
                            .estimator(PairEstimator.Kind.AREA_CORRELATION);
                }
                break;
            case A112_JUMP_LOCAL_CONTRAST_1_8_CORRELATION_NEWTON:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_8)
                            .estimator(PairEstimator.Kind.AREA_CORRELATION_NEWTON);
                }
                break;
            case A113_JUMP_POSITIVE_LOCAL_CONTRAST_1_8_ECC:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.preprocessing(Preprocessing.LOCAL_CONTRAST_1_8);
                }
                break;
            case A125_JUMP_FIRST_FRAME_POSITIVE_LOCAL_CONTRAST_ECC:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.reference(Reconciler.Reference.FIXED).referenceFrame(1)
                            .preprocessing(Preprocessing.LOCAL_CONTRAST_1_8);
                }
                break;
            case A126_JUMP_FIRST_FRAME_POSITIVE_LOCAL_CONTRAST_REPAIR_8_ECC:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.reference(Reconciler.Reference.FIXED).referenceFrame(1)
                            .preprocessing(Preprocessing.LOCAL_CONTRAST_1_8).outlierMads(8.0);
                }
                break;
            case A127_JUMP_A126_DENSE_SLOW_NO_FILTER:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.reference(Reconciler.Reference.FIXED).referenceFrame(1)
                            .preprocessing(Preprocessing.LOCAL_CONTRAST_1_8).outlierMads(8.0);
                } else if (base.imageType == ImageType.DENSE_FLUORESCENCE) {
                    builder.preprocessing(Preprocessing.NONE);
                }
                break;
            case A201_JUMP_FIRST_FRAME_LOCAL_CONTRAST_KEEP_JUMPS:
                if (base.motionType == MotionType.INTERMITTENT_JUMPS) {
                    builder.reference(Reconciler.Reference.FIXED).referenceFrame(1)
                            .preprocessing(Preprocessing.LOCAL_CONTRAST_1_8).outlierMads(0.0);
                }
                break;
            case A202_PLAIN_FIRST_FRAME_ECC:
            case A203_HYBRID_FIXED_PREVIOUS_ECC_EQUAL:
            case A204_HYBRID_FIXED_PREVIOUS_ECC_COMBINED:
                builder.reference(Reconciler.Reference.FIXED).referenceFrame(1)
                        .preprocessing(Preprocessing.NONE)
                        .floorPercentile(Double.NaN).ceilingPercentile(Double.NaN)
                        .outlierMads(0.0);
                break;
            default:
                throw new IllegalStateException("unhandled attempt " + attempt);
        }
        return builder.recipeProvenance("R02 " + attempt.name()).build();
    }

    static Reconciler.Weighting hybridWeighting(Attempt attempt) {
        if (attempt == Attempt.A203_HYBRID_FIXED_PREVIOUS_ECC_EQUAL) {
            return Reconciler.Weighting.EQUAL;
        }
        if (attempt == Attempt.A204_HYBRID_FIXED_PREVIOUS_ECC_COMBINED) {
            return Reconciler.Weighting.COMBINED;
        }
        throw new IllegalArgumentException("not a hybrid attempt: " + attempt);
    }

    private static RelativeIntensityPatternParameters requested(Map<String, String> item) {
        String declared = item.get("image_type");
        ImageType image = declared == null || declared.trim().isEmpty()
                ? ("Per2".equals(item.get("channel"))
                    ? ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE : ImageType.DENSE_FLUORESCENCE)
                : ImageType.valueOf(declared.trim().toUpperCase(Locale.ROOT));
        return RelativeIntensityPatternParameters.builder()
                .recommendation(image, MotionType.valueOf(item.get("motion")))
                .selectionMode(SelectionMode.AUTOMATIC)
                .crop(false)
                .threads(benchmarkThreads())
                .build();
    }

    private static boolean multiTimeApplies(
            Attempt attempt, RelativeIntensityPatternParameters requested) {
        if (requested.motionType != MotionType.INTERMITTENT_JUMPS) return false;
        if (attempt == Attempt.A216_SPARSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER) {
            return requested.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
        }
        if (attempt == Attempt.A212_DENSE_PHASE_MULTITIME_INTEGER
                || attempt == Attempt.A213_DENSE_VERIFIED_PHASE_MULTITIME_INTEGER
                || attempt == Attempt.A214_DENSE_VERIFIED_PHASE_FIRST_INTEGER
                || attempt == Attempt.A215_DENSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                || attempt == Attempt.A217_DENSE_BOUNDED_VERIFIED_PHASE_FIRST_JUMPS_INTEGER) {
            return requested.imageType == ImageType.DENSE_FLUORESCENCE;
        }
        return requested.imageType == ImageType.DENSE_FLUORESCENCE
                || requested.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
    }

    /** Test-runner thread cap; production parameters remain controlled by their public builder. */
    private static int benchmarkThreads() {
        return Math.max(1, Integer.getInteger("ripr.benchmark.threads", 4));
    }

    private static String measure(ImagePlus image, Map<String, String> item,
                                  RelativeIntensityPatternParameters parameters, Attempt attempt) {
        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();
        Registration.Result result = null;
        String status = "ok";
        String error = "";
        try {
            result = RelativeIntensityPatternRegistration.estimate(image, parameters,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        } catch (RuntimeException failure) {
            status = "error";
            error = concise(failure);
        }
        double elapsed = (System.nanoTime() - wall0) / 1e9;
        double cpu = (processCpuNanos() - cpu0) / 1e9;
        int repaired = 0;
        int unsupported = 0;
        if (result != null) {
            for (int frame = 0; frame < result.repairs.length; frame++) {
                if (result.repairs[frame] != null) repaired++;
                if (result.support[frame] == 0) unsupported++;
            }
        }
        return csv(attempt.id()) + ',' + csv(item.get("case_id")) + ','
                + csv(item.get("role")) + ',' + csv(item.get("issue")) + ','
                + csv(item.get("channel")) + ',' + csv(item.get("recording")) + ','
                + csv(item.get("motion")) + ',' + csv(status) + ',' + csv(error) + ','
                + number(cpu) + ',' + number(elapsed)
                + ',' + (result == null ? 0 : result.pairs.size()) + ',' + repaired + ','
                + unsupported + ',' + number(result == null ? Double.NaN : median(result.residualBefore))
                + ',' + number(result == null ? Double.NaN : median(result.residualAfter)) + ','
                + csv(parameters.reference.name()) + ',' + csv(parameters.estimator.name()) + ','
                + csv(parameters.pixelSelectionStrategy.name()) + ','
                + csv(RegistrationRecipe.of(parameters).id()) + ",1,"
                + csv(transformSeries(result, 0)) + ',' + csv(transformSeries(result, 1)) + ','
                + csv(transformSeries(result, 2)) + ',' + csv(numberSeries(result == null ? null : result.residualBefore))
                + ',' + csv(numberSeries(result == null ? null : result.residualAfter)) + ','
                + csv(numberSeries(result == null ? null : result.validFraction)) + ','
                + csv(statusSeries(result)) + '\n';
    }

    /** Accuracy candidate: gain-invariant phase correlation corrected over progressively wider gaps. */
    private static String measureMultiTimePhase(ImagePlus image, Map<String, String> item,
                                                Attempt attempt, boolean wholePixels) {
        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();
        MultiTimeScalePhaseCorrelation.Result result = null;
        String status = "ok";
        String error = "";
        try {
            int frames = image.getStackSize();
            float[][] pixels = new float[frames][];
            for (int frame = 0; frame < frames; frame++) {
                pixels[frame] = (float[]) image.getStack().getProcessor(frame + 1)
                        .convertToFloatProcessor().getPixels();
            }
            result = attempt == Attempt.A215_DENSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                    || attempt == Attempt.A216_SPARSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                    || attempt == Attempt.A217_DENSE_BOUNDED_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                    ? MultiTimeScalePhaseCorrelation.registerVerifiedJumpsToFirst(
                            pixels, image.getWidth(), image.getHeight(), wholePixels)
                    : attempt == Attempt.A214_DENSE_VERIFIED_PHASE_FIRST_INTEGER
                    ? MultiTimeScalePhaseCorrelation.registerVerifiedToFirst(
                            pixels, image.getWidth(), image.getHeight(), wholePixels)
                    : attempt == Attempt.A213_DENSE_VERIFIED_PHASE_MULTITIME_INTEGER
                    ? MultiTimeScalePhaseCorrelation.registerVerified(
                            pixels, image.getWidth(), image.getHeight(), wholePixels)
                    : MultiTimeScalePhaseCorrelation.register(
                            pixels, image.getWidth(), image.getHeight(), wholePixels);
        } catch (RuntimeException failure) {
            status = "error";
            error = concise(failure);
        }
        double elapsed = (System.nanoTime() - wall0) / 1e9;
        double cpu = (processCpuNanos() - cpu0) / 1e9;
        Transform[] cumulative = result == null ? null : result.cumulative;
        boolean jumpStable = attempt == Attempt.A215_DENSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                || attempt == Attempt.A216_SPARSE_VERIFIED_PHASE_FIRST_JUMPS_INTEGER
                || attempt == Attempt.A217_DENSE_BOUNDED_VERIFIED_PHASE_FIRST_JUMPS_INTEGER;
        boolean bounded = attempt == Attempt.A217_DENSE_BOUNDED_VERIFIED_PHASE_FIRST_JUMPS_INTEGER;
        boolean directFirst = jumpStable
                || attempt == Attempt.A214_DENSE_VERIFIED_PHASE_FIRST_INTEGER;
        boolean verified = directFirst
                || attempt == Attempt.A213_DENSE_VERIFIED_PHASE_MULTITIME_INTEGER;
        String estimator = verified ? "VERIFIED_PHASE_CORRELATION_INTEGER"
                : wholePixels ? "PHASE_CORRELATION_INTEGER" : "PHASE_CORRELATION_SUBPIXEL";
        String recipe = bounded ? "bounded_verified_phase_first_frame_jump_stable_integer"
                : jumpStable ? "verified_phase_correlation_first_frame_jump_stable_integer"
                : directFirst ? "verified_phase_correlation_first_frame_integer"
                : verified ? "verified_phase_correlation_multitime_integer"
                : wholePixels ? "phase_correlation_multitime_integer"
                : "phase_correlation_multitime_subpixel";
        return csv(attempt.id()) + ',' + csv(item.get("case_id")) + ','
                + csv(item.get("role")) + ',' + csv(item.get("issue")) + ','
                + csv(item.get("channel")) + ',' + csv(item.get("recording")) + ','
                + csv(item.get("motion")) + ',' + csv(status) + ',' + csv(error) + ','
                + number(cpu) + ',' + number(elapsed) + ','
                + (result == null ? 0 : result.pairCount) + ",0,0,,,"
                + csv(directFirst ? "FIRST_FRAME" : "MULTI_TIME_SCALE") + ','
                + csv(estimator) + ',' + csv("NONE") + ','
                + csv(recipe) + ",1," + csv(transformSeries(cumulative, 0)) + ','
                + csv(transformSeries(cumulative, 1)) + ','
                + csv(transformSeries(cumulative, 2)) + ",,,,\n";
    }

    /** Experimental fixed graph: direct image-1 edges prevent drift; previous-image edges bridge change. */
    private static String measureHybrid(
            ImagePlus image, Map<String, String> item,
            RelativeIntensityPatternParameters fixedParameters, Attempt attempt) {
        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();
        Registration.Result fixed = null;
        Registration.Result previous = null;
        Reconciler.Solution hybrid = null;
        String status = "ok";
        String error = "";
        try {
            fixed = RelativeIntensityPatternRegistration.estimate(
                    image, fixedParameters, PairScheduler.Progress.NONE,
                    PairScheduler.Cancellation.NEVER);
            RelativeIntensityPatternParameters previousParameters = fixedParameters.toBuilder()
                    .reference(Reconciler.Reference.CONSECUTIVE).referenceFrame(1).build();
            previous = RelativeIntensityPatternRegistration.estimate(
                    image, previousParameters, PairScheduler.Progress.NONE,
                    PairScheduler.Cancellation.NEVER);
            List<Reconciler.Observation> observations = new ArrayList<>();
            int planIndex = 0;
            for (Registration.PairResult pair : fixed.pairs) {
                if (pair.fit != null && pair.fit.usable()) {
                    observations.add(new Reconciler.Observation(
                            pair.from, pair.to, pair.fit.transform,
                            pair.fit.uncertainty, planIndex++));
                }
            }
            for (Registration.PairResult pair : previous.pairs) {
                if (pair.fit != null && pair.fit.usable()) {
                    observations.add(new Reconciler.Observation(
                            pair.from, pair.to, pair.fit.transform,
                            pair.fit.uncertainty, planIndex++));
                }
            }
            Reconciler.Options options = new Reconciler.Options(
                    hybridWeighting(attempt), 2,
                    Math.sqrt(Math.max(1.0,
                            (image.getWidth() * (double) image.getWidth()
                                    + image.getHeight() * (double) image.getHeight() - 2.0)
                                    / 12.0)));
            hybrid = Reconciler.multiLag(image.getNFrames(), observations, options);
        } catch (RuntimeException failure) {
            status = "error";
            error = concise(failure);
        }
        double elapsed = (System.nanoTime() - wall0) / 1e9;
        double cpu = (processCpuNanos() - cpu0) / 1e9;
        int unsupported = 0;
        if (hybrid != null) {
            for (int support : hybrid.support) if (support == 0) unsupported++;
        }
        int pairCount = (fixed == null ? 0 : fixed.pairs.size())
                + (previous == null ? 0 : previous.pairs.size());
        String recipe = attempt == Attempt.A203_HYBRID_FIXED_PREVIOUS_ECC_EQUAL
                ? "hybrid_image_1_previous_ecc_equal"
                : "hybrid_image_1_previous_ecc_combined";
        return csv(attempt.id()) + ',' + csv(item.get("case_id")) + ','
                + csv(item.get("role")) + ',' + csv(item.get("issue")) + ','
                + csv(item.get("channel")) + ',' + csv(item.get("recording")) + ','
                + csv(item.get("motion")) + ',' + csv(status) + ',' + csv(error) + ','
                + number(cpu) + ',' + number(elapsed) + ',' + pairCount + ",0,"
                + unsupported + ','
                + number(fixed == null ? Double.NaN : median(fixed.residualBefore)) + ','
                + number(fixed == null ? Double.NaN : median(fixed.residualAfter)) + ','
                + csv("HYBRID_FIXED_PREVIOUS") + ',' + csv(fixedParameters.estimator.name()) + ','
                + csv(fixedParameters.pixelSelectionStrategy.name()) + ',' + csv(recipe) + ",1,"
                + csv(transformSeries(hybrid == null ? null : hybrid.cumulative)) + ','
                + csv(transformSeries(hybrid == null ? null : hybrid.cumulative, 1)) + ','
                + csv(transformSeries(hybrid == null ? null : hybrid.cumulative, 2)) + ','
                + csv(numberSeries(fixed == null ? null : fixed.residualBefore)) + ','
                + csv(numberSeries(fixed == null ? null : fixed.residualAfter)) + ','
                + csv(numberSeries(fixed == null ? null : fixed.validFraction)) + ','
                + csv(statusSeries(fixed)) + '\n';
    }

    private static double median(double[] values) {
        double[] finite = java.util.Arrays.stream(values).filter(Double::isFinite).sorted().toArray();
        return finite.length == 0 ? Double.NaN : finite[finite.length / 2];
    }

    private static String transformSeries(Registration.Result result, int component) {
        return transformSeries(result == null ? null : result.cumulative, component);
    }

    private static String transformSeries(Transform[] cumulative) {
        return transformSeries(cumulative, 0);
    }

    private static String transformSeries(Transform[] cumulative, int component) {
        if (cumulative == null) return "";
        StringBuilder out = new StringBuilder();
        for (int frame = 0; frame < cumulative.length; frame++) {
            if (frame > 0) out.append(';');
            Transform transform = cumulative[frame] == null
                    ? Transform.IDENTITY : cumulative[frame];
            out.append(number(component == 0 ? transform.dx
                    : component == 1 ? transform.dy : transform.theta));
        }
        return out.toString();
    }

    private static String numberSeries(double[] values) {
        if (values == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(';');
            out.append(number(values[i]));
        }
        return out.toString();
    }

    private static String statusSeries(Registration.Result result) {
        if (result == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < result.status.length; i++) {
            if (i > 0) out.append(';');
            PairAligner.Status value = result.status[i];
            out.append(value == null ? "" : value.name());
        }
        return out.toString();
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("empty CSV " + path);
        List<String> header = parseCsv(lines.get(0));
        List<Map<String, String>> out = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).trim().isEmpty()) continue;
            List<String> values = parseCsv(lines.get(line));
            if (values.size() != header.size()) throw new IOException("bad CSV row " + (line + 1));
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), values.get(i));
            out.add(row);
        }
        return out;
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(value.toString()); value.setLength(0);
            } else value.append(c);
        }
        out.add(value.toString());
        return out;
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
        return (root.getClass().getSimpleName() + ':'
                + (message == null ? "" : message)).replace('\n', ' ').replace('\r', ' ');
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
