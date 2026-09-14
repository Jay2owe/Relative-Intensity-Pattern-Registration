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
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.MotionType;
import ripr.api.Preprocessing;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Measures reduced-resolution motion estimation on the existing balanced controlled fixtures.
 *
 * <p>Only the estimator input is rescaled. Translations are converted back to native pixels, the
 * correction is applied to the untouched full-resolution stack, and accuracy is scored against the
 * original exact movement. Each scale has its own visibly labelled TIFF and transform table beside
 * the other methods for that recording.
 */
public final class DownscaleControlledBenchmark {

    private static final int PAIRS_PER_48_FRAME_STACK = 209;
    private static final double[] DEFAULT_SCALES = {1.0, 0.75, 0.5, 0.25};

    private DownscaleControlledBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        Path project = args.length == 0 ? Paths.get("") : Paths.get(args[0]);
        project = project.toAbsolutePath().normalize();
        Path controlled = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        if (!Files.isDirectory(controlled)) throw new IOException("missing " + controlled);

        double[] scales = requestedScales();
        Set<String> requestedClasses = requestedSet("ripr.onlyClass");
        Set<String> requestedSeries = requestedSet("ripr.onlySeries");
        Set<String> requestedMotions = requestedSet("ripr.onlyMotionProfile");
        boolean rewrite = Boolean.getBoolean("ripr.rewrite");
        boolean writeImages = !Boolean.getBoolean("ripr.noImages");

        List<Path> inputs = inputStacks(controlled);
        int attempted = 0;
        int completed = 0;
        int skipped = 0;
        int unavailable = 0;
        List<String> failures = new ArrayList<>();
        for (Path inputPath : inputs) {
            Recording recording = Recording.from(controlled, inputPath);
            if (!requestedClasses.isEmpty() && !requestedClasses.contains(recording.imageClass)) continue;
            if (!requestedSeries.isEmpty() && !requestedSeries.contains(recording.series)) continue;
            if (!requestedMotions.isEmpty() && !requestedMotions.contains(recording.motion)) continue;

            ImagePlus input = IJ.openImage(inputPath.toString());
            if (input == null) {
                failures.add("could not open " + inputPath);
                continue;
            }
            try {
                double[] orderedScales = rotated(scales,
                        Math.floorMod((recording.imageClass + recording.series + recording.motion).hashCode(),
                                scales.length));
                for (double scale : orderedScales) {
                    attempted++;
                    Path arm = recording.conditionFolder.resolve("downscale_sweep")
                            .resolve(scaleFolder(scale));
                    int scaledWidth = (int) Math.floor(input.getWidth() * scale);
                    int scaledHeight = (int) Math.floor(input.getHeight() * scale);
                    if (scaledWidth < 32 || scaledHeight < 32) {
                        Files.createDirectories(arm);
                        Files.deleteIfExists(arm.resolve("run_failure.txt"));
                        Files.write(arm.resolve("unavailable.txt"), String.format(Locale.ROOT,
                                "Unavailable: %.0f%% leaves only %d x %d estimation pixels; the plugin's minimum is 32 x 32.%n",
                                scale * 100, scaledWidth, scaledHeight)
                                .getBytes(StandardCharsets.UTF_8));
                        unavailable++;
                        continue;
                    }
                    Path resultCsv = arm.resolve("comparison.csv");
                    Path transforms = arm.resolve("log_ratio_recommended_transforms.csv");
                    Path corrected = arm.resolve("log_ratio_recommended_corrected.tif");
                    if (!rewrite && Files.isRegularFile(resultCsv) && Files.isRegularFile(transforms)
                            && (!writeImages || Files.isRegularFile(corrected))) {
                        skipped++;
                        continue;
                    }
                    try {
                        runArm(recording, input, scale, arm, writeImages);
                        completed++;
                    } catch (RuntimeException | IOException error) {
                        Files.createDirectories(arm);
                        String message = recording + " / " + scale + ": " + error;
                        Files.write(arm.resolve("run_failure.txt"),
                                (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                        failures.add(message);
                        System.err.println("FAILED " + message);
                    }
                }
            } finally {
                input.close();
            }
        }

        writeSummaries(controlled);
        System.out.printf("downscale sweep: attempted %d, completed %d, resumed %d, unavailable %d, failed %d%n",
                attempted, completed, skipped, unavailable, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("downscale benchmark failures:\n" + String.join("\n", failures));
        }
    }

    private static void runArm(Recording recording, ImagePlus input, double scale, Path arm,
                               boolean writeImages) throws IOException {
        Files.createDirectories(arm);
        ImageType imageType = imageType(recording.imageClass);
        MotionType motionType = MotionType.valueOf(recording.motion);
        RelativeIntensityPatternParameters parameters = RelativeIntensityPatternParameters.builder()
                .recommendation(imageType, motionType)
                // Freeze the scale experiment to the pre-preprocessing recommendation it measured.
                .preprocessing(Preprocessing.NONE)
                .estimationScale(scale)
                // The controlled fixture declares its exact movement envelope. Holding this bound
                // fixed isolates resolution and matches the existing controlled-method benchmark.
                .autoMaxShift(false)
                .maxShift(knownMaxShift(recording.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .build();

        long cpuStart = processCpuTime();
        long wallStart = System.nanoTime();
        Registration.Result result = RelativeIntensityPatternRegistration.estimate(input, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double estimateWallSeconds = elapsedSeconds(wallStart);
        double estimateCpuSeconds = elapsedProcessCpuSeconds(cpuStart);

        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(recording.motion).fill(fineX, fineY);
        double[] errors = errors(result.cumulative, fineX, fineY);

        long warpStart = System.nanoTime();
        ImagePlus corrected = StackWarper.apply(input, result.cumulative,
                parameters.interpolation, false);
        double warpWallSeconds = elapsedSeconds(warpStart);
        if (writeImages) {
            IJ.saveAsTiff(corrected,
                    arm.resolve("log_ratio_recommended_corrected.tif").toString());
        }
        corrected.close();

        writeTransforms(arm.resolve("log_ratio_recommended_transforms.csv"), result.cumulative,
                fineX, fineY);
        String details = String.format(Locale.ROOT,
                "recommended %s / %s; %s; %s; gradient %.3f; floor %s; ceiling %s; fixed native max shift %.3f px; native output",
                imageType.label(), motionType.label(), parameters.norm, parameters.pixelSupport,
                parameters.gradientFraction, percentile(parameters.floorPercentile),
                percentile(parameters.ceilingPercentile), parameters.maxShift);
        String csv = "image_series_class,series_id,motion_profile,condition,estimation_scale,"
                + "estimation_width_px,estimation_height_px,native_width_px,native_height_px,"
                + "median_error_px,p90_error_px,max_error_px,estimate_wall_seconds,"
                + "estimate_cpu_seconds,estimate_wall_ms_per_pair,warp_wall_seconds,"
                + "compute_wall_seconds,details\n"
                + csv(recording.imageClass) + ',' + csv(recording.series) + ','
                + csv(recording.motion) + ',' + csv(recording.condition) + ','
                + format(scale) + ',' + scaledSize(input.getWidth(), scale) + ','
                + scaledSize(input.getHeight(), scale) + ',' + input.getWidth() + ','
                + input.getHeight() + ',' + format(Benchmark.quantile(errors, 0.5)) + ','
                + format(Benchmark.quantile(errors, 0.9)) + ','
                + format(Benchmark.quantile(errors, 1.0)) + ','
                + format(estimateWallSeconds) + ',' + format(estimateCpuSeconds) + ','
                + format(1000.0 * estimateWallSeconds / PAIRS_PER_48_FRAME_STACK) + ','
                + format(warpWallSeconds) + ',' + format(estimateWallSeconds + warpWallSeconds) + ','
                + csv(details) + '\n';
        Files.write(arm.resolve("comparison.csv"), csv.getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(arm.resolve("run_failure.txt"));
        System.out.printf("%-18s %-34s %-27s %3d%%  median %.4f px  %.2f s%n",
                recording.imageClass, recording.series, recording.motion,
                Math.round(scale * 100), Benchmark.quantile(errors, 0.5), estimateWallSeconds);
    }

    private static void writeSummaries(Path controlled) throws IOException {
        List<ResultRow> rows = readResults(controlled);
        if (rows.isEmpty()) return;
        Path summaries = controlled.resolve("summaries/downscale_sweep");
        Files.createDirectories(summaries);

        String header = ResultRow.HEADER + '\n';
        StringBuilder raw = new StringBuilder(header);
        rows.sort(Comparator.comparing((ResultRow row) -> row.imageClass)
                .thenComparing(row -> row.series).thenComparing(row -> row.motion)
                .thenComparingDouble(row -> -row.scale));
        for (ResultRow row : rows) raw.append(row.line).append('\n');
        Files.write(summaries.resolve("all_recordings.csv"),
                raw.toString().getBytes(StandardCharsets.UTF_8));

        Map<CellKey, List<ResultRow>> cells = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            CellKey key = new CellKey(row.imageClass, row.motion, row.scale);
            cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        StringBuilder byCell = new StringBuilder("image_series_class,motion_profile,estimation_scale,"
                + "series_count,mean_series_median_error_px,mean_p90_error_px,"
                + "mean_estimate_wall_seconds,mean_wall_ms_per_pair\n");
        for (Map.Entry<CellKey, List<ResultRow>> entry : cells.entrySet()) {
            CellKey key = entry.getKey();
            List<ResultRow> group = entry.getValue();
            byCell.append(key.imageClass).append(',').append(key.motion).append(',')
                    .append(format(key.scale)).append(',').append(group.size()).append(',')
                    .append(format(mean(group, Metric.MEDIAN_ERROR))).append(',')
                    .append(format(mean(group, Metric.P90_ERROR))).append(',')
                    .append(format(mean(group, Metric.WALL_SECONDS))).append(',')
                    .append(format(mean(group, Metric.WALL_MS_PER_PAIR))).append('\n');
        }
        Files.write(summaries.resolve("by_image_type_and_motion.csv"),
                byCell.toString().getBytes(StandardCharsets.UTF_8));

        Set<Double> scales = new TreeSet<>(Comparator.reverseOrder());
        for (ResultRow row : rows) scales.add(row.scale);
        Set<String> classes = new TreeSet<>();
        for (ResultRow row : rows) classes.add(row.imageClass);
        StringBuilder byType = new StringBuilder("image_series_class,estimation_scale,recording_count,"
                + "headline_eligible,mean_median_error_px,mean_p90_error_px,"
                + "mean_estimate_wall_seconds,accuracy_change_vs_full_percent,"
                + "speedup_vs_full_resolution\n");
        for (String imageClass : classes) {
            List<ResultRow> fullRows = matching(rows, imageClass, 1.0);
            double fullError = mean(fullRows, Metric.MEDIAN_ERROR);
            double fullTime = mean(fullRows, Metric.WALL_SECONDS);
            for (double scale : scales) {
                List<ResultRow> group = matching(rows, imageClass, scale);
                if (group.isEmpty()) continue;
                double error = mean(group, Metric.MEDIAN_ERROR);
                double time = mean(group, Metric.WALL_SECONDS);
                byType.append(imageClass).append(',').append(format(scale)).append(',')
                        .append(group.size()).append(',').append(group.size() == 16).append(',')
                        .append(format(error)).append(',')
                        .append(format(mean(group, Metric.P90_ERROR))).append(',')
                        .append(format(time)).append(',')
                        .append(format(100.0 * (error / fullError - 1.0))).append(',')
                        .append(format(fullTime / time)).append('\n');
            }
        }
        Files.write(summaries.resolve("by_image_type.csv"),
                byType.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder balanced = new StringBuilder("estimation_scale,image_types,motion_cells,headline_eligible,"
                + "balanced_mean_median_error_px,mean_estimate_wall_seconds,"
                + "mean_wall_ms_per_pair,speedup_vs_full_resolution\n");
        Map<Double, Double> meanTime = new LinkedHashMap<>();
        for (double scale : scales) {
            List<Double> classErrors = new ArrayList<>();
            List<ResultRow> atScale = new ArrayList<>();
            int motionCells = 0;
            for (String imageClass : classes) {
                List<Double> motionMeans = new ArrayList<>();
                for (MotionType motion : MotionType.values()) {
                    List<ResultRow> group = cells.get(new CellKey(imageClass, motion.name(), scale));
                    if (group != null && !group.isEmpty()) {
                        motionMeans.add(mean(group, Metric.MEDIAN_ERROR));
                        atScale.addAll(group);
                        motionCells++;
                    }
                }
                if (!motionMeans.isEmpty()) classErrors.add(meanNumbers(motionMeans));
            }
            double time = mean(atScale, Metric.WALL_SECONDS);
            meanTime.put(scale, time);
            balanced.append(format(scale)).append(',').append(classErrors.size()).append(',')
                    .append(motionCells).append(',')
                    .append(classErrors.size() == 5 && motionCells == 20).append(',')
                    .append(format(meanNumbers(classErrors))).append(',')
                    .append(format(time)).append(',')
                    .append(format(mean(atScale, Metric.WALL_MS_PER_PAIR))).append(',');
            Double fullTime = meanTime.get(1.0);
            balanced.append(fullTime == null ? "" : format(fullTime / time)).append('\n');
        }
        Files.write(summaries.resolve("balanced_summary.csv"),
                balanced.toString().getBytes(StandardCharsets.UTF_8));

        writeDecisionTable(summaries.resolve("winner_by_image_type_and_motion.csv"), cells);
        writeReadme(summaries, rows, cells, scales, classes);
    }

    private static void writeDecisionTable(Path path, Map<CellKey, List<ResultRow>> cells)
            throws IOException {
        Set<String> imageClasses = new TreeSet<>();
        Set<String> motions = new TreeSet<>();
        for (CellKey key : cells.keySet()) {
            imageClasses.add(key.imageClass);
            motions.add(key.motion);
        }
        StringBuilder out = new StringBuilder("image_series_class,motion_profile,winner_scale,"
                + "winner_mean_median_error_px,full_resolution_error_px,error_change_vs_full_percent,"
                + "winner_mean_wall_seconds,speedup_vs_full_resolution\n");
        for (String imageClass : imageClasses) {
            for (String motion : motions) {
                CellKey best = null;
                double bestError = Double.POSITIVE_INFINITY;
                double fullError = Double.NaN;
                double fullTime = Double.NaN;
                for (Map.Entry<CellKey, List<ResultRow>> entry : cells.entrySet()) {
                    CellKey key = entry.getKey();
                    if (!key.imageClass.equals(imageClass) || !key.motion.equals(motion)) continue;
                    double error = mean(entry.getValue(), Metric.MEDIAN_ERROR);
                    if (key.scale == 1.0) {
                        fullError = error;
                        fullTime = mean(entry.getValue(), Metric.WALL_SECONDS);
                    }
                    if (error < bestError) {
                        bestError = error;
                        best = key;
                    }
                }
                if (best == null) continue;
                double bestTime = mean(cells.get(best), Metric.WALL_SECONDS);
                out.append(imageClass).append(',').append(motion).append(',')
                        .append(format(best.scale)).append(',').append(format(bestError)).append(',')
                        .append(format(fullError)).append(',')
                        .append(format(100.0 * (bestError / fullError - 1.0))).append(',')
                        .append(format(bestTime)).append(',').append(format(fullTime / bestTime))
                        .append('\n');
            }
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeReadme(Path summaries, List<ResultRow> rows,
                                    Map<CellKey, List<ResultRow>> cells, Set<Double> scales,
                                    Set<String> classes) throws IOException {
        StringBuilder out = new StringBuilder("# Reduced-resolution registration benchmark\n\n")
                .append("Movement was estimated at 100%, 75%, 50%, or 25% of native width and height. ")
                .append("Every translation was converted back to native pixels, applied to the untouched ")
                .append("full-resolution stack, and scored against the same exact injected movement.\n\n")
                .append("The configuration at every scale is the plugin's fixed recommendation for that ")
                .append("image type and motion type. Scale is the only swept parameter. Runtime excludes ")
                .append("TIFF writing.\n\n")
                .append("A scale is headline-eligible only when all five image types and all 20 ")
                .append("image-type-and-motion cells are present. A 25% arm smaller than the plugin's ")
                .append("32 by 32 pixel minimum is recorded as unavailable, not silently omitted.\n\n")
                .append("Rows currently present: ").append(rows.size()).append(". Image types: ")
                .append(classes.size()).append(". Scales: ").append(scales.size())
                .append(". Complete image-type-and-motion cells: ").append(cells.size()).append(".\n\n")
                .append("- `balanced_summary.csv`: equal-weight result across image types and motions.\n")
                .append("- `by_image_type.csv`: scale trade-off within each signal type.\n")
                .append("- `by_image_type_and_motion.csv`: keeps each signal and movement type separate.\n")
                .append("- `winner_by_image_type_and_motion.csv`: best scale in each signal/motion cell.\n")
                .append("- `all_recordings.csv`: one auditable row per real source image and scale.\n");
        Files.write(summaries.resolve("README.md"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<ResultRow> readResults(Path controlled) throws IOException {
        List<ResultRow> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(controlled)) {
            List<Path> files = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().equals("comparison.csv"))
                    .filter(path -> path.toString().contains("downscale_sweep"))
                    .filter(path -> !path.toString().contains("summaries"))
                    .forEach(files::add);
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() < 2) continue;
                out.add(ResultRow.parse(lines.get(1)));
            }
        }
        return out;
    }

    private static List<Path> inputStacks(Path controlled) throws IOException {
        List<Path> inputs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(controlled)) {
            stream.filter(path -> path.getFileName().toString().equals("00_input_uncorrected.tif"))
                    .forEach(inputs::add);
        }
        inputs.sort(Comparator.comparing(Path::toString));
        return inputs;
    }

    private static double[] errors(Transform[] transforms, int[] fineX, int[] fineY) {
        if (transforms.length != fineX.length) {
            throw new IllegalArgumentException("expected " + fineX.length + " transforms, got "
                    + transforms.length);
        }
        double[] errors = new double[transforms.length];
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            double truthX = fineX[i] / (double) Benchmark.FINE;
            double truthY = fineY[i] / (double) Benchmark.FINE;
            errors[i] = Math.hypot(transform.dx - truthX, transform.dy - truthY);
        }
        return errors;
    }

    private static void writeTransforms(Path path, Transform[] transforms,
                                        int[] fineX, int[] fineY) throws IOException {
        StringBuilder out = new StringBuilder("frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n");
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i];
            if (transform == null) {
                out.append(i + 1).append(",NaN,NaN,NaN,")
                        .append(format(fineX[i] / (double) Benchmark.FINE)).append(',')
                        .append(format(fineY[i] / (double) Benchmark.FINE))
                        .append(",NaN,missing\n");
            } else {
                double truthX = fineX[i] / (double) Benchmark.FINE;
                double truthY = fineY[i] / (double) Benchmark.FINE;
                out.append(i + 1).append(',').append(format(transform.dx)).append(',')
                        .append(format(transform.dy)).append(',').append(format(transform.theta))
                        .append(',').append(format(truthX)).append(',').append(format(truthY))
                        .append(',').append(format(Math.hypot(transform.dx - truthX,
                                transform.dy - truthY))).append(",ok\n");
            }
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ImageType imageType(String imageClass) {
        switch (imageClass) {
            case "PHASE": return ImageType.PHASE_CONTRAST;
            case "BRIGHTFIELD_DIC": return ImageType.BRIGHTFIELD_DIC;
            case "DENSE_FLUOR": return ImageType.DENSE_FLUORESCENCE;
            case "SPARSE_LOWLIGHT": return ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
            case "FIDUCIAL_STATIC": return ImageType.FIDUCIAL_STATIC;
            default: throw new IllegalArgumentException("unknown image class " + imageClass);
        }
    }

    private static Set<String> requestedSet(String property) {
        Set<String> out = new TreeSet<>();
        for (String item : System.getProperty(property, "").split(",")) {
            if (!item.trim().isEmpty()) out.add(item.trim());
        }
        return out;
    }

    private static double[] requestedScales() {
        String property = System.getProperty("ripr.scales", "");
        if (property.trim().isEmpty()) return DEFAULT_SCALES.clone();
        String[] values = property.split(",");
        double[] scales = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            scales[i] = Double.parseDouble(values[i].trim());
            if (!(scales[i] > 0 && scales[i] <= 1)) {
                throw new IllegalArgumentException("scale must be in (0, 1]: " + scales[i]);
            }
        }
        return scales;
    }

    private static double[] rotated(double[] values, int offset) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[(i + offset) % values.length];
        return out;
    }

    private static int scaledSize(int nativeSize, double scale) {
        return Math.max(32, (int) Math.floor(nativeSize * scale));
    }

    private static double knownMaxShift(String motion) {
        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(fineX, fineY);
        double reach = 0;
        for (int i = 0; i < fineX.length; i++) {
            reach = Math.max(reach, Math.hypot(fineX[i], fineY[i]) / Benchmark.FINE);
        }
        return 2 * reach + 8;
    }

    private static String scaleFolder(double scale) {
        return String.format(Locale.ROOT, "%03d_percent_log_ratio_recommended",
                Math.round(scale * 100));
    }

    private static String percentile(double value) {
        return Double.isNaN(value) ? "disabled" : format(value);
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "NaN" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static double elapsedSeconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return -1;
    }

    private static double elapsedProcessCpuSeconds(long start) {
        if (start < 0) return Double.NaN;
        return (processCpuTime() - start) / 1e9;
    }

    private enum Metric { MEDIAN_ERROR, P90_ERROR, WALL_SECONDS, WALL_MS_PER_PAIR }

    private static double mean(List<ResultRow> rows, Metric metric) {
        if (rows == null || rows.isEmpty()) return Double.NaN;
        double total = 0;
        for (ResultRow row : rows) {
            switch (metric) {
                case MEDIAN_ERROR: total += row.medianError; break;
                case P90_ERROR: total += row.p90Error; break;
                case WALL_SECONDS: total += row.wallSeconds; break;
                case WALL_MS_PER_PAIR: total += row.wallMsPerPair; break;
                default: throw new IllegalStateException("unknown metric");
            }
        }
        return total / rows.size();
    }

    private static double meanNumbers(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double total = 0;
        for (double value : values) total += value;
        return total / values.size();
    }

    private static List<ResultRow> matching(List<ResultRow> rows, String imageClass,
                                            double scale) {
        List<ResultRow> out = new ArrayList<>();
        for (ResultRow row : rows) {
            if (row.imageClass.equals(imageClass) && Double.compare(row.scale, scale) == 0) {
                out.add(row);
            }
        }
        return out;
    }

    private static final class Recording {
        final String imageClass;
        final String series;
        final String motion;
        final String condition;
        final Path conditionFolder;

        Recording(String imageClass, String series, String motion, String condition,
                  Path conditionFolder) {
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.condition = condition;
            this.conditionFolder = conditionFolder;
        }

        static Recording from(Path controlled, Path input) {
            Path relative = controlled.relativize(input);
            if (relative.getNameCount() != 5) {
                throw new IllegalArgumentException("unexpected controlled path " + input);
            }
            return new Recording(relative.getName(0).toString(), relative.getName(1).toString(),
                    relative.getName(2).toString(), relative.getName(3).toString(),
                    input.getParent());
        }

        @Override
        public String toString() {
            return imageClass + " / " + series + " / " + motion + " / " + condition;
        }
    }

    private static final class CellKey {
        final String imageClass;
        final String motion;
        final double scale;

        CellKey(String imageClass, String motion, double scale) {
            this.imageClass = imageClass;
            this.motion = motion;
            this.scale = scale;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CellKey)) return false;
            CellKey that = (CellKey) other;
            return imageClass.equals(that.imageClass) && motion.equals(that.motion)
                    && Double.compare(scale, that.scale) == 0;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[]{imageClass, motion, scale});
        }
    }

    private static final class ResultRow {
        static final String HEADER = "image_series_class,series_id,motion_profile,condition,"
                + "estimation_scale,estimation_width_px,estimation_height_px,native_width_px,"
                + "native_height_px,median_error_px,p90_error_px,max_error_px,estimate_wall_seconds,"
                + "estimate_cpu_seconds,estimate_wall_ms_per_pair,warp_wall_seconds,"
                + "compute_wall_seconds,details";

        final String imageClass;
        final String series;
        final String motion;
        final double scale;
        final double medianError;
        final double p90Error;
        final double wallSeconds;
        final double wallMsPerPair;
        final String line;

        ResultRow(String imageClass, String series, String motion, double scale,
                  double medianError, double p90Error, double wallSeconds,
                  double wallMsPerPair, String line) {
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.scale = scale;
            this.medianError = medianError;
            this.p90Error = p90Error;
            this.wallSeconds = wallSeconds;
            this.wallMsPerPair = wallMsPerPair;
            this.line = line;
        }

        static ResultRow parse(String line) {
            List<String> fields = parseCsv(line);
            return new ResultRow(fields.get(0), fields.get(1), fields.get(2),
                    Double.parseDouble(fields.get(4)), Double.parseDouble(fields.get(9)),
                    Double.parseDouble(fields.get(10)), Double.parseDouble(fields.get(12)),
                    Double.parseDouble(fields.get(14)), line);
        }

        private static List<String> parseCsv(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (c == ',' && !quoted) {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
            fields.add(field.toString());
            return fields;
        }
    }
}
