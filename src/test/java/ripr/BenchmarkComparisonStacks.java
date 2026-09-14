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
import ij.process.ImageProcessor;
import ripr.core.Reconciler;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Writes one human-readable folder per benchmark recording, with every comparison method aligned. */
public final class BenchmarkComparisonStacks {

    private static final ThreadMXBean THREAD_TIME = ManagementFactory.getThreadMXBean();
    private static final int RCC_PAIRS = 209;

    private enum Kind { ESTIMATOR, LAG_MOST, LAG_LEAST, MODAL, AUTO }

    private static final class Method {
        final String fileStem;
        final String label;
        final Kind kind;
        final Benchmark.Estimator estimator;

        Method(String fileStem, String label, Benchmark.Estimator estimator) {
            this.fileStem = fileStem;
            this.label = label;
            this.kind = Kind.ESTIMATOR;
            this.estimator = estimator;
        }

        Method(String fileStem, String label, Kind kind) {
            this.fileStem = fileStem;
            this.label = label;
            this.kind = kind;
            this.estimator = null;
        }
    }

    private static final List<Method> METHODS = Arrays.asList(
            new Method("01_log_ratio_tukey_standard_gradient", "Log-ratio, Tukey weighting, standard gradient pixels",
                    Benchmark.Estimator.LOGRATIO_TUKEY),
            new Method("02_exclude_brightest_10_percent", "Exclude brightest 10%",
                    Benchmark.Estimator.LOGRATIO_TUKEY_NO_TOP_10),
            new Method("03_exclude_brightest_25_percent", "Exclude brightest 25%",
                    Benchmark.Estimator.LOGRATIO_TUKEY_NO_TOP_25),
            new Method("04_exclude_dimmest_25_percent", "Exclude dimmest 25%",
                    Benchmark.Estimator.LOGRATIO_TUKEY_NO_BOTTOM_25),
            new Method("05_gradient_threshold_0x_median", "Gradient threshold: 0x frame median",
                    Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_0),
            new Method("06_gradient_threshold_0_25x_median", "Gradient threshold: 0.25x frame median",
                    Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_025),
            new Method("07_gradient_threshold_0_5x_median_control", "Gradient threshold: 0.5x frame median control",
                    Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_05),
            new Method("08_gradient_threshold_1x_median", "Gradient threshold: 1x frame median",
                    Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_1),
            new Method("09_gradient_threshold_2x_median", "Gradient threshold: 2x frame median",
                    Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_2),
            new Method("10_gradient_threshold_4x_median", "Gradient threshold: 4x frame median",
                    Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_4),
            new Method("11_shared_significant_edges", "Require significant edges in both frames",
                    Benchmark.Estimator.LOGRATIO_TUKEY_MUTUAL_NOISE),
            new Method("12_log_ratio_huber_weighting", "Log-ratio, Huber weighting",
                    Benchmark.Estimator.LOGRATIO_HUBER),
            new Method("13_log_ratio_woods_least_squares", "Log-ratio, Woods least squares",
                    Benchmark.Estimator.LOGRATIO_WOODS),
            new Method("14_squared_difference_no_gain", "Squared difference without gain correction",
                    Benchmark.Estimator.SSD),
            new Method("15_phase_correlation", "Phase correlation",
                    Benchmark.Estimator.PHASE_CORRELATION),
            new Method("16_cross_correlation", "Cross-correlation",
                    Benchmark.Estimator.CROSS_CORRELATION),
            new Method("17_lag_remove_most_moving_55_percent",
                    "Lag graph: remove most-moving 55%", Kind.LAG_MOST),
            new Method("18_lag_remove_least_moving_55_percent",
                    "Lag graph: remove least-moving 55%", Kind.LAG_LEAST),
            new Method("19_global_modal_movement", "Global modal movement", Kind.MODAL),
            new Method("20_automatic_selector", "Automatic selector", Kind.AUTO));

    private BenchmarkComparisonStacks() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: BenchmarkComparisonStacks <seed dir> <out dir>");
            return;
        }
        Path seeds = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);
        String onlySeed = System.getProperty("ripr.onlySeed", "");
        String onlyCondition = System.getProperty("ripr.onlyCondition", "");
        List<Path> seedFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(seeds)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".tif"))
                    .sorted().forEach(seedFiles::add);
        }
        for (Path seed : seedFiles) {
            if (!onlySeed.isEmpty() && !seed.getFileName().toString().contains(onlySeed)) continue;
            for (Benchmark.Condition condition : Benchmark.Condition.values()) {
                if (!onlyCondition.isEmpty() && !onlyCondition.equals(condition.name())) continue;
                writeRecording(seed, condition, out);
            }
        }
    }

    private static void writeRecording(Path seed, Benchmark.Condition condition, Path out)
            throws IOException {
        String seedName = seed.getFileName().toString()
                .replaceFirst("(?i)(?:\\.ome)?\\.(?:tif|tiff|png)$", "");
        ControlledMotionProfile profile = ControlledMotionProfile.valueOf(System.getProperty(
                "ripr.onlyMotionProfile", "CURVED_OSCILLATING_DRIFT"));
        writeRecording(seed, seedName, condition, profile,
                out.resolve(seedName + "__" + condition.name()));
    }

    /** Writes one condition directly into a version-2 per-series comparison folder. */
    static void writeRecording(Path seed, String seriesName, Benchmark.Condition condition,
                               Path recording) throws IOException {
        writeRecording(seed, seriesName, condition,
                ControlledMotionProfile.CURVED_OSCILLATING_DRIFT, recording);
    }

    /** Writes one fixed motion profile directly into a version-2 controlled comparison folder. */
    static void writeRecording(Path seed, String seriesName, Benchmark.Condition condition,
                               ControlledMotionProfile motionProfile, Path recording)
            throws IOException {
        ImagePlus image = IJ.openImage(seed.toString());
        if (image == null) throw new IOException("could not open " + seed);
        String sourcePreparation = "native source dimensions";
        int shortestSide = Math.min(image.getWidth(), image.getHeight());
        if (shortestSide < 512) {
            double scale = 512.0 / shortestSide;
            int resizedWidth = (int) Math.ceil(image.getWidth() * scale);
            int resizedHeight = (int) Math.ceil(image.getHeight() * scale);
            ImageProcessor processor = image.getProcessor().duplicate();
            processor.setInterpolationMethod(ImageProcessor.BILINEAR);
            processor = processor.resize(resizedWidth, resizedHeight);
            image.close();
            image = new ImagePlus(seed.getFileName().toString(), processor);
            sourcePreparation = "bilinear source enlargement from "
                    + shortestSide + " px shortest side to 512 px for subpixel injection";
        }
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        float[] fine = Benchmark.seedPlane(image);
        image.close();

        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        motionProfile.fill(fineX, fineY);
        double[] truthX = new double[Benchmark.FRAMES];
        double[] truthY = new double[Benchmark.FRAMES];
        double reach = 0;
        int span = 0;
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            truthX[t] = fineX[t] / (double) Benchmark.FINE;
            truthY[t] = fineY[t] / (double) Benchmark.FINE;
            reach = Math.max(reach, Math.hypot(truthX[t], truthY[t]));
            span = Math.max(span, Math.max(Math.abs(fineX[t]), Math.abs(fineY[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int win = Math.min((sourceWidth - 2 * margin) / Benchmark.FINE,
                (sourceHeight - 2 * margin) / Benchmark.FINE);
        if (win < 64) throw new IOException(seed + ": window would be only " + win + " px");
        double maxShift = 2 * reach + 8;
        double sourceSd = Benchmark.standardDeviation(fine);

        float[][] input = frames(fine, sourceWidth, sourceHeight, win, margin,
                fineX, fineY, condition, sourceSd);
        boolean measureOnly = Boolean.getBoolean("ripr.measureOnly");
        float[][] clean = measureOnly ? null : frames(fine, sourceWidth, sourceHeight, win, margin,
                fineX, fineY, Benchmark.Condition.CLEAN, sourceSd);

        Path diagnostics = recording.resolve("diagnostics");
        Files.createDirectories(diagnostics);
        if (!measureOnly) {
            IJ.saveAsTiff(BenchmarkStacks.stack(input, win, "Uncorrected input"),
                    recording.resolve("00_input_uncorrected.tif").toString());
        }

        StringBuilder readme = new StringBuilder();
        readme.append(seriesName).append(" / ").append(motionProfile.name()).append(" / ")
                .append(condition.name()).append('\n');
        readme.append("Source preparation: ").append(sourcePreparation).append(".\n");
        readme.append("Open 00_input_uncorrected.tif, then each numbered corrected TIFF.\n");
        readme.append("The diagnostics folder contains before/after temporal-motion maps.\n\n");
        readme.append("method\tmedian error (pixels)\tCPU seconds per 48-frame stack")
                .append("\telapsed seconds per stack\tdetails\n");
        StringBuilder csv = new StringBuilder();
        csv.append("method,median_error_px,cpu_seconds_per_stack,cpu_ms_per_pair,details,")
                .append("family,strategy,p90_error_px,max_error_px,elapsed_seconds_per_stack,")
                .append("elapsed_ms_per_pair,timing_status\n");

        String onlyMethod = System.getProperty("ripr.onlyMethod", "");
        for (Method method : METHODS) {
            if (!onlyMethod.isEmpty() && !method.fileStem.contains(onlyMethod)) continue;
            String detail = method.label;
            String fileStem = method.fileStem;
            Reconciler.Solution solution;
            long cpuStart = processCpuTime();
            long wallStart = System.nanoTime();
            switch (method.kind) {
                case ESTIMATOR:
                    solution = Benchmark.solveRccForStacks(input, win, method.estimator, maxShift);
                    break;
                case LAG_MOST:
                    solution = Benchmark.solveLagInstabilityForStacks(
                            input, win, maxShift, true, 55);
                    break;
                case LAG_LEAST:
                    solution = Benchmark.solveLagInstabilityForStacks(
                            input, win, maxShift, false, 55);
                    break;
                case MODAL:
                    solution = Benchmark.solveModalMovementForStacks(input, win, maxShift);
                    break;
                case AUTO:
                    Benchmark.Estimator selected = Benchmark.resolveAutoInformation(input, win);
                    solution = Benchmark.solveRccForStacks(input, win, selected, maxShift);
                    detail += "; selected " + plainEstimator(selected);
                    fileStem += "__selected_" + slug(plainEstimator(selected));
                    break;
                default:
                    throw new IllegalStateException("unknown comparison method");
            }
            double elapsedMs = (System.nanoTime() - wallStart) / 1e6;
            double cpuMs = (processCpuTime() - cpuStart) / 1e6;
            if (!measureOnly) {
                float[][] corrected = corrected(input, win, solution);
                float[][] correctedClean = corrected(clean, win, solution);
                IJ.saveAsTiff(BenchmarkStacks.stack(corrected, win, method.label),
                        recording.resolve(fileStem + "_corrected.tif").toString());
                IJ.saveAsTiff(BenchmarkStacks.sdPanel(clean, correctedClean, win),
                        diagnostics.resolve(fileStem + "_before_after_motion.tif").toString());
                writeTransforms(recording.resolve(fileStem + "_transforms.csv"), solution);
            }
            double[] errors = Benchmark.errors(solution, truthX, truthY);
            double median = Benchmark.quantile(errors, 0.5);
            double p90 = Benchmark.quantile(errors, 0.9);
            double maximum = Benchmark.quantile(errors, 1.0);
            readme.append(fileStem).append('\t')
                    .append(String.format("%.4f\t%.3f\t%.3f\t", median, cpuMs / 1000.0,
                            elapsedMs / 1000.0))
                    .append(detail).append('\n');
            csv.append(fileStem).append(',')
                    .append(String.format("%.4f,%.3f,%.3f,", median, cpuMs / 1000.0,
                            cpuMs / RCC_PAIRS))
                    .append('"').append(detail.replace("\"", "\"\"")).append('"')
                    .append(",,,").append(String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.3f,%.3f,",
                            p90, maximum, elapsedMs / 1000.0, elapsedMs / RCC_PAIRS))
                    .append('"').append("measured").append('"').append('\n');
            System.out.printf("%-42s %.4f px  %8.3f CPU s  %8.3f elapsed s%n",
                    method.fileStem, median, cpuMs / 1000.0, elapsedMs / 1000.0);
        }
        Files.write(recording.resolve("README.txt"),
                readme.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(recording.resolve("comparison.csv"),
                csv.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + recording);
    }

    /** Runs the same internal methods on untouched source frames without inventing accuracy truth. */
    static void writeNativeRecording(String seriesName, float[][] input, int width,
                                     NativeMotionProfiler.Profile profile, Path recording)
            throws IOException {
        Path diagnostics = recording.resolve("diagnostics");
        Files.createDirectories(diagnostics);
        IJ.saveAsTiff(BenchmarkStacks.stack(input, width, "Native source frames"),
                recording.resolve("00_input_native.tif").toString());

        double maxShift = Math.max(8.0, Math.min(width / 3.0, profile.maximumStep * 3.0 + 4.0));
        int pairCount = rccPairs(input.length);
        StringBuilder readme = new StringBuilder();
        readme.append(seriesName).append(" / native source movement\n")
                .append("These are consecutive source frames. No movement was injected.\n")
                .append("Residual movement is a stability measurement, not accuracy against unknown truth.\n\n")
                .append("method\tmedian residual step (pixels)\tp90 residual step (pixels)")
                .append("\tCPU seconds per stack\tdetails\n");
        StringBuilder csv = new StringBuilder(
                "method,median_residual_step_px,p90_residual_step_px,cpu_seconds_per_stack,"
                        + "cpu_ms_per_pair,details,metric_status,family,strategy,"
                        + "maximum_residual_step_px,elapsed_seconds_per_stack,"
                        + "elapsed_ms_per_pair,timing_status\n");

        String onlyMethod = System.getProperty("ripr.onlyMethod", "");
        for (Method method : METHODS) {
            if (!onlyMethod.isEmpty() && !method.fileStem.contains(onlyMethod)) continue;
            String detail = method.label;
            String fileStem = method.fileStem;
            Reconciler.Solution solution;
            long cpuStart = processCpuTime();
            long wallStart = System.nanoTime();
            switch (method.kind) {
                case ESTIMATOR:
                    solution = Benchmark.solveRccForStacks(
                            input, width, method.estimator, maxShift);
                    break;
                case LAG_MOST:
                    solution = Benchmark.solveLagInstabilityForStacks(
                            input, width, maxShift, true, 55);
                    break;
                case LAG_LEAST:
                    solution = Benchmark.solveLagInstabilityForStacks(
                            input, width, maxShift, false, 55);
                    break;
                case MODAL:
                    solution = Benchmark.solveModalMovementForStacks(input, width, maxShift);
                    break;
                case AUTO:
                    Benchmark.Estimator selected = Benchmark.resolveAutoInformation(input, width);
                    solution = Benchmark.solveRccForStacks(input, width, selected, maxShift);
                    detail += "; selected " + plainEstimator(selected);
                    fileStem += "__selected_" + slug(plainEstimator(selected));
                    break;
                default:
                    throw new IllegalStateException("unknown comparison method");
            }
            double elapsedMs = (System.nanoTime() - wallStart) / 1e6;
            double cpuMs = (processCpuTime() - cpuStart) / 1e6;
            float[][] corrected = corrected(input, width, solution);
            NativeMotionProfiler.Residual residual = NativeMotionProfiler.residual(corrected, width);
            IJ.saveAsTiff(BenchmarkStacks.stack(corrected, width, method.label),
                    recording.resolve(fileStem + "_corrected.tif").toString());
            IJ.saveAsTiff(BenchmarkStacks.sdPanel(input, corrected, width),
                    diagnostics.resolve(fileStem + "_before_after_motion.tif").toString());
            writeTransforms(recording.resolve(fileStem + "_transforms.csv"), solution);
            readme.append(fileStem).append('\t')
                    .append(String.format(java.util.Locale.ROOT, "%.4f\t%.4f\t%.3f\t",
                            residual.median, residual.p90, cpuMs / 1000.0))
                    .append(detail).append('\n');
            csv.append(fileStem).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.3f,%.3f,",
                            residual.median, residual.p90, cpuMs / 1000.0,
                            cpuMs / Math.max(1, pairCount)))
                    .append('"').append(detail.replace("\"", "\"\"")).append("\",")
                    .append('"').append("stability only; natural movement has no exact truth")
                    .append('"').append(",,,")
                    .append(String.format(java.util.Locale.ROOT, "%.4f,%.3f,%.3f,",
                            residual.maximum, elapsedMs / 1000.0,
                            elapsedMs / Math.max(1, pairCount)))
                    .append('"').append("measured").append('"').append('\n');
            System.out.printf("%-42s residual %.4f px  %8.3f CPU s  %8.3f elapsed s%n",
                    method.fileStem, residual.median, cpuMs / 1000.0, elapsedMs / 1000.0);
        }
        Files.write(recording.resolve("README.txt"),
                readme.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(recording.resolve("comparison.csv"),
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void writeTransforms(Path path, Reconciler.Solution solution) throws IOException {
        StringBuilder csv = new StringBuilder("frame,x_px,y_px,rotation_radians,status\n");
        for (int frame = 0; frame < solution.cumulative.length; frame++) {
            Transform transform = solution.cumulative[frame];
            if (transform == null) {
                csv.append(frame + 1).append(",NaN,NaN,NaN,missing\n");
            } else {
                csv.append(frame + 1).append(',')
                        .append(String.format("%.9f,%.9f,%.9f,ok%n",
                                transform.dx, transform.dy, transform.theta));
            }
        }
        Files.write(path, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static float[][] frames(float[] fine, int sourceWidth, int sourceHeight, int win,
                                    int margin, int[] fineX, int[] fineY,
                                    Benchmark.Condition condition, double sourceSd) {
        float[][] out = new float[Benchmark.FRAMES][];
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            out[t] = Benchmark.frame(fine, sourceWidth, sourceHeight, win, margin,
                    fineX[t], fineY[t], condition, t, sourceSd);
        }
        return out;
    }

    static float[][] corrected(float[][] frames, int win,
                               Reconciler.Solution solution) {
        float[][] out = new float[frames.length][];
        for (int t = 0; t < frames.length; t++) {
            out[t] = new float[win * win];
            Transform transform = solution.cumulative[t] == null
                    ? Transform.IDENTITY : solution.cumulative[t];
            Warper.warp(frames[t], out[t], win, win, transform,
                    Warper.Interpolation.NONE, Float.NaN);
        }
        return out;
    }

    private static String plainEstimator(Benchmark.Estimator estimator) {
        switch (estimator) {
            case LOGRATIO_TUKEY:
                return "standard gradient pixels";
            case LOGRATIO_TUKEY_NO_TOP_10:
                return "exclude brightest 10%";
            case LOGRATIO_TUKEY_MUTUAL_NOISE:
                return "significant edges shared by both frames";
            default:
                return estimator.label;
        }
    }

    private static String slug(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return THREAD_TIME.isCurrentThreadCpuTimeSupported()
                ? THREAD_TIME.getCurrentThreadCpuTime() : System.nanoTime();
    }

    private static int rccPairs(int frames) {
        int pairs = 0;
        for (int lag : new int[]{1, 2, 4, 8, 16}) {
            pairs += Math.max(0, frames - lag);
        }
        return pairs;
    }
}
