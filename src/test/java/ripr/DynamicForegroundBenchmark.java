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
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.SelectionMode;
import ripr.core.PairScheduler;
import ripr.core.PairEstimator;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Runs the unchanged production RIPR selector on the moving-foreground pilot. */
public final class DynamicForegroundBenchmark {

    private DynamicForegroundBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: <case-root> <output.csv> [expected-cases]");
        }
        int expectedCases = args.length == 3 ? Integer.parseInt(args[2]) : 22;
        run(Paths.get(args[0]).toAbsolutePath().normalize(),
                Paths.get(args[1]).toAbsolutePath().normalize(), expectedCases);
    }

    static void run(Path root, Path output) throws Exception {
        run(root, output, 22);
    }

    static void run(Path root, Path output, int expectedCases) throws Exception {
        if (expectedCases < 1) throw new IllegalArgumentException("expected cases must be positive");
        List<Path> inputs = inputs(root);
        if (inputs.size() != expectedCases) {
            throw new IOException("expected " + expectedCases
                    + " frozen pilot cases, found " + inputs.size());
        }
        if (Files.exists(output)) {
            throw new IOException("refusing to overwrite immutable result " + output);
        }
        Files.createDirectories(output.getParent());
        String header = "split,scope,image_series_class,series_id,motion_category,condition,"
                + "method_id,method_label,status,median_error_px,p90_error_px,max_error_px,"
                + "terminal_error_px,frames_over_1px,frames_over_5px,cpu_seconds,"
                + "elapsed_seconds,pairs,repaired_frames,unsupported_frames,transform_x_px,"
                + "transform_y_px,transform_theta_rad,requested_policy\n";
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
                RelativeIntensityPatternParameters parameters = parameters(motion);
                if (!warmed) {
                    try {
                        RelativeIntensityPatternRegistration.estimate(image, parameters,
                                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
                    } catch (RuntimeException ignored) {
                        // Warm-up is excluded even when the first case is intentionally difficult.
                    }
                    warmed = true;
                }
                String row = measure(imageClass, series, motion, condition, image, parameters,
                        input.getParent());
                Files.write(output, row.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND);
            } finally {
                image.close();
            }
            System.out.printf(Locale.ROOT, "RIPR dynamic foreground %s %s (%d/%d)%n",
                    series, condition, ++done, expectedCases);
        }
    }

    static RelativeIntensityPatternParameters parameters(String motion) {
        RelativeIntensityPatternParameters.Builder builder =
                RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE, MotionType.valueOf(motion))
                .rotationMode(RotationMode.OFF)
                .crop(false)
                .interpolation(Warper.Interpolation.NONE);
        String requested = System.getProperty("ripr.estimator", "").trim();
        if (requested.isEmpty()) {
            builder.selectionMode(SelectionMode.AUTOMATIC);
        } else {
            builder.selectionMode(SelectionMode.MANUAL)
                    .estimator(PairEstimator.Kind.of(requested));
        }
        return builder.build();
    }

    private static String measure(String imageClass, String series, String motion,
                                  String condition, ImagePlus image,
                                  RelativeIntensityPatternParameters parameters, Path recording)
            throws IOException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long cpu0 = bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : 0;
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
        double cpu = bean.isCurrentThreadCpuTimeSupported()
                ? (bean.getCurrentThreadCpuTime() - cpu0) / 1e9 : Double.NaN;
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
        boolean automatic = parameters.selectionMode == SelectionMode.AUTOMATIC;
        String methodId = automatic ? "00_ripr_automatic_translation"
                : "ripr_" + parameters.estimator.id() + "_translation";
        String methodLabel = automatic ? "RIPR automatic translation"
                : "RIPR " + parameters.estimator.label() + " translation";
        String requestedPolicy = automatic
                ? "production automatic selector; dense fluorescence; declared motion=" + motion
                    + "; rotation off; selector overhead included"
                : "manual estimator=" + parameters.estimator.id()
                    + "; dense-fluorescence category recommendation otherwise unchanged; declared motion="
                    + motion + "; rotation off";
        return csv("development") + ',' + csv("translation") + ',' + csv(imageClass) + ','
                + csv(series) + ',' + csv(motion) + ',' + csv(condition) + ','
                + csv(methodId) + ',' + csv(methodLabel) + ',' + csv(status) + ','
                + metric(errors, 0.5) + ',' + metric(errors, 0.9) + ','
                + metric(errors, 1.0) + ','
                + (errors == null ? "" : number(errors[errors.length - 1])) + ','
                + (errors == null ? -1 : countAbove(errors, 1)) + ','
                + (errors == null ? -1 : countAbove(errors, 5)) + ','
                + number(cpu) + ',' + number(elapsed) + ','
                + (result == null ? 0 : result.pairs.size()) + ',' + repaired + ','
                + unsupported + ',' + csv(transformSeries(result, 0)) + ','
                + csv(transformSeries(result, 1)) + ',' + csv(transformSeries(result, 2)) + ','
                + csv(requestedPolicy) + '\n';
    }

    static double quantile(double[] input, double probability) {
        if (input == null || input.length == 0) return Double.NaN;
        double[] values = input.clone();
        java.util.Arrays.sort(values);
        if (probability <= 0) return values[0];
        if (probability >= 1) return values[values.length - 1];
        double position = probability * (values.length - 1);
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        double fraction = position - low;
        return values[low] * (1 - fraction) + values[high] * fraction;
    }

    private static String metric(double[] values, double probability) {
        return values == null ? "" : number(quantile(values, probability));
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

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
