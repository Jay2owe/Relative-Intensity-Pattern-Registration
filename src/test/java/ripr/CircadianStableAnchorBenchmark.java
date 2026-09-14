/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.Preprocessing;
import ripr.api.RegistrationRecipe;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.SelectionMode;
import ripr.core.FrameSource;
import ripr.core.LogPlane;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.RotationMode;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Development comparison of unchanged automatic RIPR against one circadian stable-anchor mask.
 *
 * <p>The candidate resolves exactly the same automatic dense-fluorescence recipe as the baseline.
 * Its sole analytical change is a common reference-coordinate support mask: spatially informative
 * pixels are ranked down when gain-normalised residuals remain unstable over short lags or the
 * approximately 24-hour lag. The final refit retains the resolved recipe's source, filter, intensity
 * band, graph, estimator and optimiser.
 */
public final class CircadianStableAnchorBenchmark {

    static final int[] TEMPORAL_SCORING_LAGS = {1, 2, 4, 8, 16, 48};
    static final double REMOVE_PERCENT = 25.0;

    enum Arm {
        AUTOMATIC("00_ripr_automatic_translation", "RIPR automatic translation"),
        STABLE_ANCHORS("01_ripr_circadian_stable_anchors",
                "RIPR circadian stable anchors");

        final String methodId;
        final String methodLabel;

        Arm(String methodId, String methodLabel) {
            this.methodId = methodId;
            this.methodLabel = methodLabel;
        }

        static Arm parse(String value) {
            return Arm.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private CircadianStableAnchorBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: <case-root> <output.csv> <expected-cases> <automatic|stable_anchors>");
        }
        run(Paths.get(args[0]).toAbsolutePath().normalize(),
                Paths.get(args[1]).toAbsolutePath().normalize(),
                Integer.parseInt(args[2]), Arm.parse(args[3]));
    }

    static void run(Path root, Path output, int expectedCases, Arm arm) throws Exception {
        if (expectedCases < 1) throw new IllegalArgumentException("expected cases must be positive");
        List<Path> inputs = inputs(root);
        if (inputs.size() != expectedCases) {
            throw new IOException("expected " + expectedCases + " cases, found " + inputs.size());
        }
        if (Files.exists(output)) {
            throw new IOException("refusing to overwrite immutable result " + output);
        }
        Files.createDirectories(output.getParent());
        String header = "split,scope,image_series_class,series_id,motion_category,condition,"
                + "method_id,method_label,status,median_error_px,p90_error_px,max_error_px,"
                + "terminal_error_px,frames_over_1px,frames_over_5px,cpu_seconds,elapsed_seconds,"
                + "pairs,repaired_frames,unsupported_frames,transform_x_px,transform_y_px,"
                + "transform_theta_rad,requested_policy,resolved_recipe,anchor_eligible_pixels,"
                + "anchor_removed_pixels,anchor_scoring_lags\n";
        Files.write(output, header.getBytes(StandardCharsets.UTF_8));

        boolean warmed = false;
        int done = 0;
        for (Path input : inputs) {
            Path relative = root.relativize(input);
            String imageClass = relative.getName(0).toString();
            String series = relative.getName(1).toString();
            String motion = relative.getName(2).toString();
            String condition = relative.getName(3).toString();
            ImagePlus image = IJ.openImage(input.toString());
            if (image == null) throw new IOException("could not open " + input);
            try {
                RelativeIntensityPatternParameters parameters = automaticParameters(motion);
                if (!warmed) {
                    try {
                        estimate(image, parameters, arm);
                    } catch (RuntimeException ignored) {
                        // One full excluded warm-up uses the same arm as every timed case.
                    }
                    warmed = true;
                }
                String row = measure(imageClass, series, motion, condition, image, parameters,
                        input.getParent(), arm);
                Files.write(output, row.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND);
            } finally {
                image.close();
            }
            System.out.printf(Locale.ROOT, "Circadian anchors %s %s %s (%d/%d)%n",
                    arm, series, condition, ++done, expectedCases);
        }
    }

    static int[] temporalScoringLags() {
        return TEMPORAL_SCORING_LAGS.clone();
    }

    static boolean[] stableAnchorMask(LogPlane[] frames, Transform[] cumulative,
                                      int referenceFrame) {
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                frames, cumulative, TEMPORAL_SCORING_LAGS, referenceFrame);
        return LagPixelSelector.mask(scores, LagPixelSelector.Score.ANCHOR_TRUST,
                REMOVE_PERCENT, false);
    }

    static RelativeIntensityPatternParameters automaticParameters(String motion) {
        return RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE, MotionType.valueOf(motion))
                .selectionMode(SelectionMode.AUTOMATIC)
                .rotationMode(RotationMode.OFF)
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .build();
    }

    private static Estimate estimate(ImagePlus image, RelativeIntensityPatternParameters requested,
                                     Arm arm) {
        AutomaticRegistrationSelector.Result selection =
                RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, requested);
        RelativeIntensityPatternParameters resolved = selection.parameters;
        String recipe = RegistrationRecipe.of(resolved).id();
        if (arm == Arm.AUTOMATIC) {
            Registration.Result result = RelativeIntensityPatternRegistration.estimate(
                    image, resolved, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            return new Estimate(result, recipe, 0, 0);
        }
        if (resolved.estimationScale != 1.0) {
            throw new IllegalArgumentException(
                    "circadian stable-anchor development arm requires native estimation scale");
        }

        StackFrames raw = StackFrames.of(image, resolved.channel, resolved.slice);
        FrameSource prepared = resolved.preprocessing == Preprocessing.NONE
                ? raw : new PreprocessedFrameSource(raw, resolved.preprocessing);
        Registration.Options options = resolved.registrationOptions();
        if (options.autoMaxShift) {
            options.aligner.maxShift = Registration.estimateShiftBound(
                    prepared, options, PairScheduler.Progress.NONE,
                    PairScheduler.Cancellation.NEVER).suggestedMaxShift;
            options.autoMaxShift = false;
        }
        Registration.Result pilot = Registration.run(prepared, options,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);

        LogPlane[] scoringFrames = new LogPlane[raw.count()];
        for (int t = 0; t < scoringFrames.length; t++) {
            scoringFrames[t] = LogPlane.of(raw.plane(t), raw.width(), raw.height(),
                    resolved.epsilon);
        }
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                scoringFrames, pilot.cumulative, TEMPORAL_SCORING_LAGS,
                options.referenceFrame);
        boolean[] referenceMask = LagPixelSelector.mask(
                scores, LagPixelSelector.Score.ANCHOR_TRUST, REMOVE_PERCENT, false);
        int removed = LagPixelSelector.removedEligible(referenceMask, scores);
        int levels = options.aligner.levelsFor(prepared.width(), prepared.height());
        boolean[][][] support = new boolean[prepared.count()][][];
        for (int t = 0; t < support.length; t++) {
            support[t] = LagPixelSelector.sourceSupport(referenceMask,
                    prepared.width(), prepared.height(), levels, pilot.cumulative[t]);
        }
        Registration.Result result = Registration.refitWithSupport(
                prepared, options, pilot, support, PairScheduler.Progress.NONE,
                PairScheduler.Cancellation.NEVER);
        return new Estimate(result, recipe, scores.eligibleCount, removed);
    }

    private static String measure(String imageClass, String series, String motion,
                                  String condition, ImagePlus image,
                                  RelativeIntensityPatternParameters parameters, Path recording,
                                  Arm arm) throws IOException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long cpu0 = bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : 0;
        long wall0 = System.nanoTime();
        Estimate estimate = null;
        String status = "ok";
        try {
            estimate = estimate(image, parameters, arm);
        } catch (RuntimeException error) {
            status = "failed:" + concise(error);
        }
        double elapsed = (System.nanoTime() - wall0) / 1e9;
        double cpu = bean.isCurrentThreadCpuTimeSupported()
                ? (bean.getCurrentThreadCpuTime() - cpu0) / 1e9 : Double.NaN;
        Registration.Result result = estimate == null ? null : estimate.result;
        ExternalPluginComparisonStacks.Truth truth = ExternalPluginComparisonStacks.readTruth(
                recording, motion, image.getStackSize());
        double[] errors = result == null ? null : ExternalPluginComparisonStacks.geometricErrors(
                result.cumulative, truth.x, truth.y, truth.theta, image.getWidth());
        int repaired = 0;
        int unsupported = 0;
        if (result != null) {
            for (int frame = 0; frame < result.repairs.length; frame++) {
                if (result.repairs[frame] != null) repaired++;
                if (result.support[frame] == 0) unsupported++;
            }
        }
        String policy = arm == Arm.AUTOMATIC
                ? "production automatic selector; dense fluorescence; rotation off; selector overhead included"
                : "resolved automatic recipe plus 25 percent lowest anchor-trust mask; temporal scoring lags "
                        + lagText() + "; rotation off; pilot, scoring and refit time included";
        return csv("development") + ',' + csv("translation") + ',' + csv(imageClass) + ','
                + csv(series) + ',' + csv(motion) + ',' + csv(condition) + ','
                + csv(arm.methodId) + ',' + csv(arm.methodLabel) + ',' + csv(status) + ','
                + metric(errors, 0.5) + ',' + metric(errors, 0.9) + ','
                + metric(errors, 1.0) + ','
                + (errors == null ? "" : number(errors[errors.length - 1])) + ','
                + (errors == null ? -1 : countAbove(errors, 1)) + ','
                + (errors == null ? -1 : countAbove(errors, 5)) + ','
                + number(cpu) + ',' + number(elapsed) + ','
                + (result == null ? 0 : result.pairs.size()) + ',' + repaired + ','
                + unsupported + ',' + csv(transformSeries(result, 0)) + ','
                + csv(transformSeries(result, 1)) + ',' + csv(transformSeries(result, 2)) + ','
                + csv(policy) + ',' + csv(estimate == null ? "" : estimate.recipe) + ','
                + (estimate == null ? 0 : estimate.eligible) + ','
                + (estimate == null ? 0 : estimate.removed) + ','
                + csv(arm == Arm.STABLE_ANCHORS ? lagText() : "") + '\n';
    }

    private static String lagText() {
        return Arrays.toString(TEMPORAL_SCORING_LAGS).replace(" ", "")
                .replace("[", "").replace("]", "");
    }

    private static List<Path> inputs(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root, 5)) {
            walk.filter(path -> path.getFileName().toString()
                    .equals("00_input_uncorrected.tif")).forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String metric(double[] values, double probability) {
        return values == null ? "" : number(DynamicForegroundBenchmark.quantile(values, probability));
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
            Transform transform = result.cumulative[i] == null
                    ? Transform.IDENTITY : result.cumulative[i];
            out.append(number(component == 0 ? transform.dx
                    : component == 1 ? transform.dy : transform.theta));
        }
        return out.toString();
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final class Estimate {
        final Registration.Result result;
        final String recipe;
        final int eligible;
        final int removed;

        Estimate(Registration.Result result, String recipe, int eligible, int removed) {
            this.result = result;
            this.recipe = recipe;
            this.eligible = eligible;
            this.removed = removed;
        }
    }
}
