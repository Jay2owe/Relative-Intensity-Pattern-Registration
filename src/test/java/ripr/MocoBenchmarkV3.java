/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.process.ByteProcessor;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Headless, transform-only adapter for the original Moco ImageJ plugin. */
public final class MocoBenchmarkV3 {
    private static final String METHOD_ID = "44_moco_translation";
    private static final String METHOD_LABEL = "Moco global 2D translation";
    private static final String HEADER = "dataset,split,image_series_class,series_id,"
            + "independent_group,lab_id,motion_category,condition_id,condition_instance,replicate,"
            + "method_id,method_label,family,strategy,config_id,status,median_error_px,p90_error_px,"
            + "max_error_px,terminal_error_px,frames_over_1px,frames_over_5px,cpu_seconds,"
            + "elapsed_seconds,pairs,preprocessing_arm,normalization_fallback,peak_rss_mb,"
            + "transform_x_px,transform_y_px,details\n";

    private MocoBenchmarkV3() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length >= 1 && "--self-test".equals(args[0])) {
            selfTest(args.length >= 2 ? Paths.get(args[1]) : null);
            return;
        }
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: MocoBenchmarkV3 <project> <split> <output.csv>");
        }
        run(Paths.get(args[0]).toAbsolutePath().normalize(), args[1],
                Paths.get(args[2]).toAbsolutePath().normalize());
    }

    private static void run(Path project, String split, Path output) throws Exception {
        Path root = project.resolve("library/benchmark/v3/inputs").resolve(split);
        Path manifest = root.resolve("inputs_manifest.csv");
        if (!Files.isRegularFile(manifest)) throw new IOException("missing " + manifest);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        boolean rewrite = Boolean.getBoolean("moco.rewrite");
        if (rewrite || !Files.exists(output)) Files.write(output, HEADER.getBytes(StandardCharsets.UTF_8));
        Set<String> done = rewrite ? new HashSet<String>() : completedKeys(output);
        Set<String> sources = requested("moco.onlySource");
        Set<String> conditions = requested("moco.onlyCondition");
        Set<String> motions = requested("moco.onlyMotion");
        Set<String> arms = requested("moco.preprocessingArms");
        if (arms.isEmpty()) arms.addAll(Arrays.asList("native", "common_normalized"));
        int downsample = Integer.getInteger("moco.downsample", 1);
        double windowFraction = Double.parseDouble(System.getProperty("moco.windowFraction", "0.2"));
        String config = System.getProperty("moco.configId", "moco_ds1_w0.2_default");
        MocoApi api = new MocoApi();
        int successes = 0;
        int failures = 0;
        for (Map<String, String> input : BenchmarkV3.readCsv(manifest)) {
            if (!selected(input.get("series_id"), sources)
                    || !selected(input.get("condition_id"), conditions)
                    || !selected(input.get("motion_category"), motions)) continue;
            Path stack = root.resolve(input.get("input_relative_path")).normalize();
            float[][] nativeFrames = ExternalPluginComparisonStacks.readFrames(stack);
            int width = Integer.parseInt(input.get("width"));
            for (String arm : arms) {
                String key = key(input, arm, config);
                if (done.contains(key)) continue;
                float[][] frames = nativeFrames;
                boolean fallback = false;
                if ("common_normalized".equals(arm)) {
                    ExternalPluginComparisonStacks.NormalizedFrames normalized =
                            ExternalPluginComparisonStacks.commonNormalize(nativeFrames);
                    frames = normalized.frames;
                    fallback = normalized.fallback;
                } else if (!"native".equals(arm)) {
                    throw new IllegalArgumentException("unknown preprocessing arm " + arm);
                }
                long cpu0 = processCpuNanos();
                long wall0 = System.nanoTime();
                double[][] transforms = null;
                String status = "ok";
                String details = "stack_global_minmax_to_uint8;downsample=" + downsample
                        + ";window_fraction=" + windowFraction;
                try {
                    transforms = solve(api, frames, width, downsample, windowFraction);
                } catch (Throwable error) {
                    status = "failed:" + concise(error);
                    details += ";" + concise(error);
                }
                double cpu = (processCpuNanos() - cpu0) / 1e9;
                double elapsed = (System.nanoTime() - wall0) / 1e9;
                String row = resultRow(input, arm, fallback, config, status, transforms,
                        cpu, elapsed, frames.length, details);
                Files.write(output, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                done.add(key);
                if (transforms == null) failures++; else successes++;
                System.out.printf(Locale.ROOT, "%s %s %s %s median=%.4f px%n",
                        transforms == null ? "FAILED" : "OK", input.get("series_id"),
                        input.get("condition_instance"), arm,
                        transforms == null ? Double.NaN : metrics(input, transforms)[0]);
            }
        }
        System.out.printf("v3 Moco complete: %d successful rows, %d failed rows; %s%n",
                successes, failures, output);
    }

    static double[][] solve(MocoApi api, float[][] frames, int width, int downsample,
                            double windowFraction) throws Exception {
        if (frames.length == 0 || frames[0].length != width * width) {
            throw new IllegalArgumentException("invalid square movie");
        }
        ByteProcessor[] quantized = quantize(frames, width);
        int levels = Math.max(0, downsample) + 1;
        ByteProcessor[] templates = api.downsample(quantized[0], width, width, levels);
        ByteProcessor coarseTemplate = templates[levels - 1];
        int rows = coarseTemplate.getHeight();
        int cols = coarseTemplate.getWidth();
        double[][] tValues = api.computeTVals(coarseTemplate, rows, cols);
        List<double[][]> integrals = api.computeT(tValues, cols, rows);
        int requestedWindow = Math.max(1, (int) Math.round(Math.min(width, width) * windowFraction));
        int window = (int) Math.floor(Math.min(Math.min(requestedWindow + 1, rows), cols)
                / Math.pow(2, levels - 1)) - 1;
        if (window < 1) window = 1;
        double[][] templateFft = api.templateFFT(tValues, rows, cols, window);
        Object fft = api.newFft(cols + window, rows + window);
        double[][] output = new double[frames.length][2];
        for (int frame = 0; frame < frames.length; frame++) {
            ByteProcessor[] pyramid = api.downsample(quantized[frame], width, width, levels);
            int[] correction = api.templateTranslation(pyramid[levels - 1],
                    pyramid[levels - 1].getHeight(), pyramid[levels - 1].getWidth(),
                    templateFft, window, fft, integrals);
            for (int level = levels - 2; level >= 0; level--) {
                correction[0] *= 2;
                correction[1] *= 2;
                correction = api.moveByOne(correction, pyramid[level], templates[level]);
            }
            // applyAffine moves the observed pixels by correction; benchmark transforms describe
            // the content motion from template to observation, hence the minus sign.
            output[frame][0] = -correction[0];
            output[frame][1] = -correction[1];
        }
        double anchorX = output[0][0];
        double anchorY = output[0][1];
        for (double[] transform : output) {
            transform[0] -= anchorX;
            transform[1] -= anchorY;
        }
        return output;
    }

    private static ByteProcessor[] quantize(float[][] frames, int width) {
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (float[] frame : frames) for (float value : frame) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("nonfinite input pixel");
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        if (!(maximum > minimum)) throw new IllegalArgumentException("constant input stack");
        double scale = 255.0 / (maximum - minimum);
        ByteProcessor[] output = new ByteProcessor[frames.length];
        for (int frame = 0; frame < frames.length; frame++) {
            ByteProcessor processor = new ByteProcessor(width, width);
            for (int pixel = 0; pixel < frames[frame].length; pixel++) {
                int value = (int) Math.round((frames[frame][pixel] - minimum) * scale);
                processor.set(pixel, Math.max(0, Math.min(255, value)));
            }
            output[frame] = processor;
        }
        return output;
    }

    static final class MocoApi {
        private final Method downsample;
        private final Method computeTVals;
        private final Method computeT;
        private final Method templateFFT;
        private final Method templateTranslation;
        private final Method moveByOne;
        private final Constructor<?> fftConstructor;

        MocoApi() throws Exception {
            Class<?> moco = Class.forName("moco_");
            Class<?> fft = Class.forName("org.jtransforms.fft.DoubleFFT_2D");
            downsample = moco.getMethod("downsample", ByteProcessor.class,
                    int.class, int.class, int.class);
            computeTVals = moco.getMethod("computeTVals", ByteProcessor.class,
                    int.class, int.class);
            computeT = moco.getMethod("computeT", double[][].class, int.class, int.class);
            templateFFT = moco.getMethod("templateFFT", double[][].class,
                    int.class, int.class, int.class);
            templateTranslation = moco.getMethod("templateTranslation", ByteProcessor.class,
                    int.class, int.class, double[][].class, int.class, fft,
                    double[][].class, double[][].class, double[][].class, double[][].class);
            moveByOne = moco.getMethod("moveByOne2", int[].class,
                    ByteProcessor.class, ByteProcessor.class);
            fftConstructor = fft.getConstructor(long.class, long.class);
        }

        ByteProcessor[] downsample(ByteProcessor image, int rows, int cols, int levels)
                throws Exception {
            return (ByteProcessor[]) downsample.invoke(null, image, rows, cols, levels);
        }

        double[][] computeTVals(ByteProcessor image, int rows, int cols) throws Exception {
            return (double[][]) computeTVals.invoke(null, image, rows, cols);
        }

        @SuppressWarnings("unchecked")
        List<double[][]> computeT(double[][] values, int cols, int rows) throws Exception {
            return (List<double[][]>) computeT.invoke(null, values, cols, rows);
        }

        double[][] templateFFT(double[][] values, int rows, int cols, int window) throws Exception {
            return (double[][]) templateFFT.invoke(null, values, rows, cols, window);
        }

        Object newFft(int cols, int rows) throws Exception {
            return fftConstructor.newInstance((long) cols, (long) rows);
        }

        int[] templateTranslation(ByteProcessor image, int rows, int cols, double[][] fftValues,
                                  int window, Object fft, List<double[][]> integrals) throws Exception {
            return (int[]) templateTranslation.invoke(null, image, rows, cols, fftValues, window,
                    fft, integrals.get(0), integrals.get(1), integrals.get(2), integrals.get(3));
        }

        int[] moveByOne(int[] correction, ByteProcessor image, ByteProcessor template)
                throws Exception {
            return (int[]) moveByOne.invoke(null, correction, image, template);
        }
    }

    private static void selfTest(Path report) throws Exception {
        MocoApi api = new MocoApi();
        int[][] cases = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {6, -4}};
        List<String> rows = new ArrayList<>();
        rows.add("method_id,truth_x_px,truth_y_px,estimated_x_px,estimated_y_px,error_px,tolerance_px,status,details");
        List<String> failures = new ArrayList<>();
        for (int[] one : cases) {
            float[] reference = fixture(128);
            float[] moving = translate(reference, 128, one[0], one[1]);
            double[][] transforms = solve(api, new float[][]{reference, moving}, 128, 0, 0.2);
            double error = Math.hypot(transforms[1][0] - one[0], transforms[1][1] - one[1]);
            String status = error <= 0.01 ? "pass" : "fail";
            if (!"pass".equals(status)) failures.add(Arrays.toString(one) + " error=" + error);
            rows.add(csv(METHOD_ID) + ',' + one[0] + ',' + one[1] + ','
                    + number(transforms[1][0]) + ',' + number(transforms[1][1]) + ','
                    + number(error) + ",0.01," + status + ',');
            System.out.printf(Locale.ROOT, "%s truth=(%d,%d) estimated=(%.3f,%.3f) error=%.3f%n",
                    status.toUpperCase(Locale.ROOT), one[0], one[1], transforms[1][0],
                    transforms[1][1], error);
        }
        if (report != null) {
            if (report.getParent() != null) Files.createDirectories(report.getParent());
            Files.write(report, (String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!failures.isEmpty()) throw new AssertionError("Moco conformance failures: " + failures);
        System.out.println("Moco adapter conformance passed: " + cases.length + " integer cases");
    }

    private static float[] fixture(int width) {
        Random random = new Random(20260820L);
        float[] values = new float[width * width];
        for (int i = 0; i < values.length; i++) values[i] = (float) (100 + 20 * random.nextGaussian());
        for (int pass = 0; pass < 3; pass++) {
            float[] smooth = values.clone();
            for (int y = 1; y < width - 1; y++) for (int x = 1; x < width - 1; x++) {
                float sum = 0;
                for (int yy = -1; yy <= 1; yy++) for (int xx = -1; xx <= 1; xx++) {
                    sum += values[(y + yy) * width + x + xx];
                }
                smooth[y * width + x] = sum / 9f;
            }
            values = smooth;
        }
        for (int y = 70; y < 83; y++) for (int x = 29; x < 45; x++) values[y * width + x] += 90;
        return values;
    }

    private static float[] translate(float[] source, int width, int dx, int dy) {
        float[] output = new float[source.length];
        for (int y = 0; y < width; y++) for (int x = 0; x < width; x++) {
            int fromX = x - dx;
            int fromY = y - dy;
            if (fromX >= 0 && fromX < width && fromY >= 0 && fromY < width) {
                output[y * width + x] = source[fromY * width + fromX];
            }
        }
        return output;
    }

    private static String resultRow(Map<String, String> input, String arm, boolean fallback,
                                    String config, String status, double[][] transforms,
                                    double cpu, double elapsed, int pairs, String details) {
        double[] metric = transforms == null ? null : metrics(input, transforms);
        return csv("v3") + ',' + csv(input.get("split")) + ','
                + csv(input.get("image_series_class")) + ',' + csv(input.get("series_id")) + ','
                + csv(input.get("independent_group")) + ',' + csv(input.get("lab_id")) + ','
                + csv(input.get("motion_category")) + ',' + csv(input.get("condition_id")) + ','
                + csv(input.get("condition_instance")) + ',' + input.get("replicate") + ','
                + csv(METHOD_ID) + ',' + csv(METHOD_LABEL) + ',' + csv("MOCO") + ','
                + csv("first_frame_native") + ',' + csv(config) + ',' + csv(status) + ','
                + number(metric == null ? Double.NaN : metric[0]) + ','
                + number(metric == null ? Double.NaN : metric[1]) + ','
                + number(metric == null ? Double.NaN : metric[2]) + ','
                + number(metric == null ? Double.NaN : metric[3]) + ','
                + (metric == null ? -1 : (int) metric[4]) + ','
                + (metric == null ? -1 : (int) metric[5]) + ',' + number(cpu) + ','
                + number(elapsed) + ',' + pairs + ',' + csv(arm) + ',' + fallback + ','
                + number((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                / (1024.0 * 1024.0)) + ',' + csv(series(transforms, 0)) + ','
                + csv(series(transforms, 1)) + ',' + csv(details) + '\n';
    }

    private static double[] metrics(Map<String, String> input, double[][] transforms) {
        double[] truthX = parseSeries(input.get("truth_x_px"));
        double[] truthY = parseSeries(input.get("truth_y_px"));
        double[] errors = new double[truthX.length];
        int overOne = 0;
        int overFive = 0;
        for (int i = 0; i < errors.length; i++) {
            errors[i] = Math.hypot(transforms[i][0] - truthX[i], transforms[i][1] - truthY[i]);
            if (errors[i] > 1) overOne++;
            if (errors[i] > 5) overFive++;
        }
        return new double[]{quantile(errors, 0.5), quantile(errors, 0.9),
                quantile(errors, 1.0), errors[errors.length - 1], overOne, overFive};
    }

    private static double[] parseSeries(String encoded) {
        String[] parts = encoded.split(";");
        double[] output = new double[parts.length];
        for (int i = 0; i < parts.length; i++) output[i] = Double.parseDouble(parts[i]);
        return output;
    }

    private static double quantile(double[] values, double fraction) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[(int) Math.round(fraction * (copy.length - 1))];
    }

    private static String series(double[][] values, int component) {
        if (values == null) return "";
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) output.append(';');
            output.append(String.format(Locale.ROOT, "%.9f", values[i][component]));
        }
        return output.toString();
    }

    private static Set<String> completedKeys(Path output) throws IOException {
        Set<String> keys = new HashSet<>();
        if (!Files.isRegularFile(output)) return keys;
        for (Map<String, String> row : BenchmarkV3.readCsv(output)) {
            keys.add(row.get("series_id") + '|' + row.get("motion_category") + '|'
                    + row.get("condition_instance") + '|' + row.get("preprocessing_arm") + '|'
                    + row.get("config_id"));
        }
        return keys;
    }

    private static String key(Map<String, String> row, String arm, String config) {
        return row.get("series_id") + '|' + row.get("motion_category") + '|'
                + row.get("condition_instance") + '|' + arm + '|' + config;
    }

    private static Set<String> requested(String property) {
        Set<String> output = new HashSet<>();
        for (String value : System.getProperty(property, "").split(",")) {
            if (!value.trim().isEmpty()) output.add(value.trim());
        }
        return output;
    }

    private static boolean selected(String value, Set<String> choices) {
        return choices.isEmpty() || choices.contains(value);
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return 0;
    }

    private static String concise(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ":" + message)
                .replace('\n', ' ').replace('\r', ' ');
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String csv(String value) {
        if (value == null) value = "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
