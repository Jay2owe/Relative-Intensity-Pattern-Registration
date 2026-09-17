/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import logratio.api.ImageType;
import logratio.api.LogRatioParameters;
import logratio.api.MotionType;
import logratio.api.Preprocessing;
import logratio.core.FrameSource;
import logratio.core.PairScheduler;
import logratio.core.Registration;
import logratio.core.Transform;
import logratio.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Tests traditional image preparation as a single addition to recommended log-ratio registration. */
public final class TraditionalPreprocessingBenchmark {

    private static final int PAIRS_PER_STACK = 209;

    enum Arm {
        CONTROL("00_no_preprocessing", "No preprocessing control",
                Preprocessing.NONE, false),
        GAUSSIAN_0_7("01_gaussian_sigma_0_7", "Gaussian smoothing, sigma about 0.7 pixels",
                Preprocessing.GAUSSIAN_0_7, false),
        GAUSSIAN_1_0("02_gaussian_sigma_1_0", "Gaussian smoothing, sigma about 1.0 pixel",
                Preprocessing.GAUSSIAN_1_0, false),
        GAUSSIAN_1_4("03_gaussian_sigma_1_4", "Gaussian smoothing, sigma about 1.4 pixels",
                Preprocessing.GAUSSIAN_1_4, false),
        MEDIAN_3X3("04_median_3x3", "Three by three median denoising",
                Preprocessing.MEDIAN_3X3, false),
        BACKGROUND_P1("05_background_subtract_p1", "Subtract first-percentile background",
                Preprocessing.NONE, true),
        BACKGROUND_P1_GAUSSIAN_1_0("06_background_p1_plus_gaussian_sigma_1_0",
                "Subtract first-percentile background, then Gaussian smoothing",
                Preprocessing.GAUSSIAN_1_0, true),
        ANSCOMBE("07_anscombe", "Anscombe photon-noise stabilisation",
                Preprocessing.ANSCOMBE, false),
        ANSCOMBE_GAUSSIAN_1_0("08_anscombe_plus_gaussian_sigma_1_0",
                "Anscombe photon-noise stabilisation, then Gaussian smoothing",
                Preprocessing.ANSCOMBE_GAUSSIAN_1_0, false),
        UNSHARP_0_5("09_unsharp_sigma_1_amount_0_5", "Mild unsharp masking",
                Preprocessing.UNSHARP_0_5, false);

        final String id;
        final String label;
        final Preprocessing filter;
        final boolean removeBackground;

        Arm(String id, String label, Preprocessing filter,
            boolean removeBackground) {
            this.id = id;
            this.label = label;
            this.filter = filter;
            this.removeBackground = removeBackground;
        }
    }

    private TraditionalPreprocessingBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        Path project = args.length == 0 ? Paths.get("") : Paths.get(args[0]);
        project = project.toAbsolutePath().normalize();
        Path controlled = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        if (!Files.isDirectory(controlled)) throw new IOException("missing " + controlled);

        Set<String> requestedClasses = requestedSet("logratio.onlyClass");
        Set<String> requestedSeries = requestedSet("logratio.onlySeries");
        Set<String> requestedMotions = requestedSet("logratio.onlyMotionProfile");
        Set<String> requestedArms = requestedSet("logratio.onlyArm");
        boolean rewrite = Boolean.getBoolean("logratio.rewrite");
        boolean writeImages = !Boolean.getBoolean("logratio.noImages");

        int attempted = 0;
        int completed = 0;
        int resumed = 0;
        List<String> failures = new ArrayList<>();
        for (Path inputPath : inputStacks(controlled)) {
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
                Arm[] arms = rotatedArms(recording);
                for (Arm arm : arms) {
                    if (!requestedArms.isEmpty() && !requestedArms.contains(arm.name())
                            && !requestedArms.contains(arm.id)) continue;
                    attempted++;
                    Path output = recording.conditionFolder.resolve("traditional_preprocessing")
                            .resolve(arm.id);
                    Path resultCsv = output.resolve("comparison.csv");
                    Path transforms = output.resolve("log_ratio_recommended_transforms.csv");
                    Path corrected = output.resolve("log_ratio_recommended_corrected.tif");
                    if (!rewrite && Files.isRegularFile(resultCsv) && Files.isRegularFile(transforms)
                            && (!writeImages || Files.isRegularFile(corrected))) {
                        resumed++;
                        continue;
                    }
                    try {
                        runArm(recording, input, arm, output, writeImages);
                        completed++;
                    } catch (IOException | RuntimeException error) {
                        Files.createDirectories(output);
                        String message = recording + " / " + arm.name() + ": " + error;
                        Files.write(output.resolve("run_failure.txt"),
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
        System.out.printf("traditional preprocessing: attempted %d, completed %d, resumed %d, failed %d%n",
                attempted, completed, resumed, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("traditional preprocessing failures:\n" + String.join("\n", failures));
        }
    }

    private static void runArm(Recording recording, ImagePlus input, Arm arm, Path output,
                               boolean writeImages) throws IOException {
        Files.createDirectories(output);
        ImageType imageType = imageType(recording.imageClass);
        MotionType motionType = MotionType.valueOf(recording.motion);
        LogRatioParameters parameters = LogRatioParameters.builder()
                .recommendation(imageType, motionType)
                // Freeze the original recommendation: this runner supplies its own explicit arm.
                .preprocessing(Preprocessing.NONE)
                .removeOffset(arm.removeBackground)
                .offsetPercentile(1.0)
                .autoMaxShift(false)
                .maxShift(knownMaxShift(recording.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .build();
        FrameSource source = new PreprocessedFrameSource(
                StackFrames.of(input, parameters.channel, parameters.slice), arm.filter);

        long cpuStart = processCpuTime();
        long wallStart = System.nanoTime();
        Registration.Result result = Registration.run(source, parameters.registrationOptions(),
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double wallSeconds = elapsedSeconds(wallStart);
        double cpuSeconds = elapsedProcessCpuSeconds(cpuStart);

        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(recording.motion).fill(fineX, fineY);
        double[] errors = errors(result.cumulative, fineX, fineY);

        long warpStart = System.nanoTime();
        ImagePlus corrected = StackWarper.apply(input, result.cumulative,
                parameters.interpolation, false);
        double warpSeconds = elapsedSeconds(warpStart);
        if (corrected.getWidth() != input.getWidth() || corrected.getHeight() != input.getHeight()
                || corrected.getStackSize() != input.getStackSize()) {
            corrected.close();
            throw new IOException("correction did not preserve the full-resolution stack dimensions");
        }
        if (writeImages) {
            IJ.saveAsTiff(corrected,
                    output.resolve("log_ratio_recommended_corrected.tif").toString());
        }
        corrected.close();
        writeTransforms(output.resolve("log_ratio_recommended_transforms.csv"), result.cumulative,
                fineX, fineY);

        String details = String.format(Locale.ROOT,
                "%s; recommended %s / %s; %s; %s; gradient %.3f; floor %s; ceiling %s; native output",
                arm.label, imageType.label(), motionType.label(), parameters.norm,
                parameters.pixelSupport, parameters.gradientFraction,
                percentile(parameters.floorPercentile), percentile(parameters.ceilingPercentile));
        String line = csv(recording.imageClass) + ',' + csv(recording.series) + ','
                + csv(recording.motion) + ',' + csv(recording.condition) + ',' + csv(arm.name())
                + ',' + csv(arm.id) + ',' + csv(arm.label) + ',' + input.getWidth() + ','
                + input.getHeight() + ',' + format(Benchmark.quantile(errors, 0.5)) + ','
                + format(Benchmark.quantile(errors, 0.9)) + ','
                + format(Benchmark.quantile(errors, 1.0)) + ',' + format(wallSeconds) + ','
                + format(cpuSeconds) + ',' + format(1000.0 * wallSeconds / PAIRS_PER_STACK) + ','
                + format(warpSeconds) + ',' + format(wallSeconds + warpSeconds) + ','
                + csv(details);
        Files.write(output.resolve("comparison.csv"),
                (ResultRow.HEADER + '\n' + line + '\n').getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(output.resolve("run_failure.txt"));
        System.out.printf("%-18s %-34s %-27s %-39s median %.4f px  %.2f s%n",
                recording.imageClass, recording.series, recording.motion, arm.name(),
                Benchmark.quantile(errors, 0.5), wallSeconds);
    }

    private static void writeSummaries(Path controlled) throws IOException {
        List<ResultRow> rows = readResults(controlled);
        if (rows.isEmpty()) return;
        Path summaries = controlled.resolve("summaries/traditional_preprocessing");
        Files.createDirectories(summaries);
        rows.sort(Comparator.comparing((ResultRow row) -> row.imageClass)
                .thenComparing(row -> row.series).thenComparing(row -> row.motion)
                .thenComparing(row -> row.armId));
        StringBuilder raw = new StringBuilder(ResultRow.HEADER).append('\n');
        for (ResultRow row : rows) raw.append(row.line).append('\n');
        Files.write(summaries.resolve("all_recordings.csv"),
                raw.toString().getBytes(StandardCharsets.UTF_8));

        Map<CellKey, List<ResultRow>> cells = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            cells.computeIfAbsent(new CellKey(row.imageClass, row.motion, row.arm),
                    ignored -> new ArrayList<>()).add(row);
        }
        StringBuilder byCell = new StringBuilder("image_series_class,motion_profile,arm,arm_id,"
                + "display_name,series_count,mean_series_median_error_px,mean_p90_error_px,"
                + "mean_estimate_wall_seconds\n");
        for (Map.Entry<CellKey, List<ResultRow>> entry : cells.entrySet()) {
            CellKey key = entry.getKey();
            List<ResultRow> group = entry.getValue();
            ResultRow first = group.get(0);
            byCell.append(key.imageClass).append(',').append(key.motion).append(',')
                    .append(key.arm.name()).append(',').append(first.armId).append(',')
                    .append(csv(first.displayName)).append(',').append(group.size()).append(',')
                    .append(format(mean(group, Metric.MEDIAN_ERROR))).append(',')
                    .append(format(mean(group, Metric.P90_ERROR))).append(',')
                    .append(format(mean(group, Metric.WALL_SECONDS))).append('\n');
        }
        Files.write(summaries.resolve("by_image_type_and_motion.csv"),
                byCell.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder balanced = new StringBuilder("arm,arm_id,display_name,image_types,motion_cells,"
                + "headline_eligible,balanced_mean_median_error_px,accuracy_change_vs_control_percent,"
                + "mean_estimate_wall_seconds,speedup_vs_control,individual_wins_vs_control,"
                + "individual_losses_vs_control,new_failures_vs_control\n");
        List<ResultRow> controls = matching(rows, Arm.CONTROL);
        double controlError = balancedError(controls);
        double controlTime = mean(controls, Metric.WALL_SECONDS);
        for (Arm arm : Arm.values()) {
            List<ResultRow> group = matching(rows, arm);
            if (group.isEmpty()) continue;
            Set<String> imageTypes = new TreeSet<>();
            Set<String> motionCells = new TreeSet<>();
            for (ResultRow row : group) {
                imageTypes.add(row.imageClass);
                motionCells.add(row.imageClass + '/' + row.motion);
            }
            double error = balancedError(group);
            double time = mean(group, Metric.WALL_SECONDS);
            Comparison comparison = compareToControl(group, controls);
            ResultRow first = group.get(0);
            balanced.append(arm.name()).append(',').append(first.armId).append(',')
                    .append(csv(first.displayName)).append(',').append(imageTypes.size()).append(',')
                    .append(motionCells.size()).append(',')
                    .append(imageTypes.size() == 5 && motionCells.size() == 20).append(',')
                    .append(format(error)).append(',')
                    .append(format(100.0 * (error / controlError - 1.0))).append(',')
                    .append(format(time)).append(',').append(format(controlTime / time)).append(',')
                    .append(comparison.wins).append(',').append(comparison.losses).append(',')
                    .append(comparison.newFailures).append('\n');
        }
        Files.write(summaries.resolve("balanced_summary.csv"),
                balanced.toString().getBytes(StandardCharsets.UTF_8));
        writeWinners(summaries.resolve("winner_by_image_type_and_motion.csv"), cells);
        writeRecommendedHybrid(summaries, rows, controls, controlError, controlTime);
        String readme = "# Traditional preprocessing benchmark\n\n"
                + "Each arm adds one conventional image-preparation step before the same recommended "
                + "log-ratio registration. Corrected stacks remain untouched full resolution. The no-preprocessing "
                + "arm is the control. Runtime includes preprocessing and movement estimation but excludes TIFF writing.\n\n"
                + "- `balanced_summary.csv`: equal-weight accuracy, time, wins, and new failures.\n"
                + "- `by_image_type_and_motion.csv`: result separated by signal and movement.\n"
                + "- `winner_by_image_type_and_motion.csv`: lowest-error arm in every cell.\n"
                + "- `recommended_hybrid_summary.csv`: only the four consistent category-specific wins.\n"
                + "- `all_recordings.csv`: one auditable row per source recording and arm.\n";
        Files.write(summaries.resolve("README.md"), readme.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeWinners(Path path, Map<CellKey, List<ResultRow>> cells)
            throws IOException {
        Set<String> imageTypes = new TreeSet<>();
        Set<String> motions = new TreeSet<>();
        for (CellKey key : cells.keySet()) {
            imageTypes.add(key.imageClass);
            motions.add(key.motion);
        }
        StringBuilder out = new StringBuilder("image_series_class,motion_profile,winner_arm,"
                + "winner_arm_id,winner_display_name,winner_mean_median_error_px,"
                + "control_error_px,error_change_vs_control_percent\n");
        for (String imageType : imageTypes) {
            for (String motion : motions) {
                CellKey winner = null;
                double best = Double.POSITIVE_INFINITY;
                double control = Double.NaN;
                for (Map.Entry<CellKey, List<ResultRow>> entry : cells.entrySet()) {
                    CellKey key = entry.getKey();
                    if (!key.imageClass.equals(imageType) || !key.motion.equals(motion)) continue;
                    double error = mean(entry.getValue(), Metric.MEDIAN_ERROR);
                    if (key.arm == Arm.CONTROL) control = error;
                    if (error < best) {
                        best = error;
                        winner = key;
                    }
                }
                if (winner == null) continue;
                ResultRow first = cells.get(winner).get(0);
                out.append(imageType).append(',').append(motion).append(',')
                        .append(winner.arm.name()).append(',').append(first.armId).append(',')
                        .append(csv(first.displayName)).append(',').append(format(best)).append(',')
                        .append(format(control)).append(',')
                        .append(format(100.0 * (best / control - 1.0))).append('\n');
            }
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Comparison compareToControl(List<ResultRow> rows, List<ResultRow> controls) {
        Map<String, ResultRow> byKey = new LinkedHashMap<>();
        for (ResultRow row : controls) byKey.put(row.recordingKey(), row);
        int wins = 0;
        int losses = 0;
        int newFailures = 0;
        for (ResultRow row : rows) {
            ResultRow control = byKey.get(row.recordingKey());
            if (control == null) continue;
            if (row.medianError < control.medianError) wins++;
            if (row.medianError > control.medianError) losses++;
            if (control.medianError < 0.5 && row.medianError > 2.0) newFailures++;
        }
        return new Comparison(wins, losses, newFailures);
    }

    private static void writeRecommendedHybrid(Path summaries, List<ResultRow> rows,
                                               List<ResultRow> controls, double controlError,
                                               double controlTime) throws IOException {
        Map<String, ResultRow> available = new LinkedHashMap<>();
        for (ResultRow row : rows) available.put(row.recordingKey() + '/' + row.arm.name(), row);
        List<ResultRow> selected = new ArrayList<>();
        for (ResultRow control : controls) {
            Arm arm = recommendedArm(control.imageClass, control.motion);
            ResultRow row = available.get(control.recordingKey() + '/' + arm.name());
            if (row == null) throw new IOException("missing recommended preprocessing arm " + arm
                    + " for " + control.recordingKey());
            selected.add(row);
        }
        selected.sort(Comparator.comparing((ResultRow row) -> row.imageClass)
                .thenComparing(row -> row.series).thenComparing(row -> row.motion));
        StringBuilder audit = new StringBuilder(ResultRow.HEADER).append('\n');
        for (ResultRow row : selected) audit.append(row.line).append('\n');
        Files.write(summaries.resolve("recommended_hybrid_all_recordings.csv"),
                audit.toString().getBytes(StandardCharsets.UTF_8));

        double error = balancedError(selected);
        double time = mean(selected, Metric.WALL_SECONDS);
        Comparison comparison = compareToControl(selected, controls);
        String summary = "strategy,balanced_mean_median_error_px,control_error_px,"
                + "accuracy_change_vs_control_percent,mean_estimate_wall_seconds,control_seconds,"
                + "speedup_vs_control,individual_wins,individual_losses,new_failures\n"
                + "category_specific_traditional_preprocessing," + format(error) + ','
                + format(controlError) + ',' + format(100.0 * (error / controlError - 1.0)) + ','
                + format(time) + ',' + format(controlTime) + ',' + format(controlTime / time) + ','
                + comparison.wins + ',' + comparison.losses + ',' + comparison.newFailures + '\n';
        Files.write(summaries.resolve("recommended_hybrid_summary.csv"),
                summary.getBytes(StandardCharsets.UTF_8));

        String choices = "image_series_class,motion_profile,recommended_arm,reason\n"
                + "PHASE,INTERMITTENT_JUMPS,GAUSSIAN_0_7,won all four independent source series\n"
                + "PHASE,SUBPIXEL_RANDOM_WALK,GAUSSIAN_1_0,won all four independent source series\n"
                + "BRIGHTFIELD_DIC,SUBPIXEL_RANDOM_WALK,MEDIAN_3X3,won all four independent source series\n"
                + "SPARSE_LOWLIGHT,STEADY_DIRECTIONAL_DRIFT,MEDIAN_3X3,won three of four series and cut the cell mean by 84 percent\n";
        Files.write(summaries.resolve("recommended_hybrid_choices.csv"),
                choices.getBytes(StandardCharsets.UTF_8));
    }

    private static Arm recommendedArm(String imageClass, String motion) {
        if ("PHASE".equals(imageClass) && "INTERMITTENT_JUMPS".equals(motion)) {
            return Arm.GAUSSIAN_0_7;
        }
        if ("PHASE".equals(imageClass) && "SUBPIXEL_RANDOM_WALK".equals(motion)) {
            return Arm.GAUSSIAN_1_0;
        }
        if ("BRIGHTFIELD_DIC".equals(imageClass) && "SUBPIXEL_RANDOM_WALK".equals(motion)) {
            return Arm.MEDIAN_3X3;
        }
        if ("SPARSE_LOWLIGHT".equals(imageClass) && "STEADY_DIRECTIONAL_DRIFT".equals(motion)) {
            return Arm.MEDIAN_3X3;
        }
        return Arm.CONTROL;
    }

    private static double balancedError(List<ResultRow> rows) {
        Map<String, List<ResultRow>> cells = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            cells.computeIfAbsent(row.imageClass + '/' + row.motion,
                    ignored -> new ArrayList<>()).add(row);
        }
        Map<String, List<Double>> classes = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResultRow>> entry : cells.entrySet()) {
            String imageClass = entry.getKey().substring(0, entry.getKey().indexOf('/'));
            classes.computeIfAbsent(imageClass, ignored -> new ArrayList<>())
                    .add(mean(entry.getValue(), Metric.MEDIAN_ERROR));
        }
        List<Double> classMeans = new ArrayList<>();
        for (List<Double> motionMeans : classes.values()) classMeans.add(meanNumbers(motionMeans));
        return meanNumbers(classMeans);
    }

    private static List<ResultRow> readResults(Path controlled) throws IOException {
        List<ResultRow> rows = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(controlled)) {
            List<Path> files = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().equals("comparison.csv"))
                    .filter(path -> path.toString().contains("traditional_preprocessing"))
                    .filter(path -> !path.toString().contains("summaries"))
                    .forEach(files::add);
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() >= 2) rows.add(ResultRow.parse(lines.get(1)));
            }
        }
        return rows;
    }

    private static List<ResultRow> matching(List<ResultRow> rows, Arm arm) {
        List<ResultRow> out = new ArrayList<>();
        for (ResultRow row : rows) if (row.arm == arm) out.add(row);
        return out;
    }

    private enum Metric { MEDIAN_ERROR, P90_ERROR, WALL_SECONDS }

    private static double mean(List<ResultRow> rows, Metric metric) {
        if (rows.isEmpty()) return Double.NaN;
        double total = 0;
        for (ResultRow row : rows) {
            if (metric == Metric.MEDIAN_ERROR) total += row.medianError;
            else if (metric == Metric.P90_ERROR) total += row.p90Error;
            else total += row.wallSeconds;
        }
        return total / rows.size();
    }

    private static double meanNumbers(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double total = 0;
        for (double value : values) total += value;
        return total / values.size();
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

    private static Arm[] rotatedArms(Recording recording) {
        Arm[] values = Arm.values();
        Arm[] out = new Arm[values.length];
        int offset = Math.floorMod((recording.imageClass + recording.series + recording.motion).hashCode(),
                values.length);
        for (int i = 0; i < values.length; i++) out[i] = values[(i + offset) % values.length];
        return out;
    }

    private static double[] errors(Transform[] transforms, int[] fineX, int[] fineY) {
        double[] errors = new double[transforms.length];
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            errors[i] = Math.hypot(transform.dx - fineX[i] / (double) Benchmark.FINE,
                    transform.dy - fineY[i] / (double) Benchmark.FINE);
        }
        return errors;
    }

    private static void writeTransforms(Path path, Transform[] transforms,
                                        int[] fineX, int[] fineY) throws IOException {
        StringBuilder out = new StringBuilder("frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n");
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i];
            double truthX = fineX[i] / (double) Benchmark.FINE;
            double truthY = fineY[i] / (double) Benchmark.FINE;
            if (transform == null) {
                out.append(i + 1).append(",NaN,NaN,NaN,").append(format(truthX)).append(',')
                        .append(format(truthY)).append(",NaN,missing\n");
            } else {
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

    private static double knownMaxShift(String motion) {
        int[] x = new int[Benchmark.FRAMES];
        int[] y = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(x, y);
        double reach = 0;
        for (int i = 0; i < x.length; i++) {
            reach = Math.max(reach, Math.hypot(x[i], y[i]) / Benchmark.FINE);
        }
        return 2 * reach + 8;
    }

    private static Set<String> requestedSet(String property) {
        Set<String> out = new TreeSet<>();
        for (String item : System.getProperty(property, "").split(",")) {
            if (!item.trim().isEmpty()) out.add(item.trim());
        }
        return out;
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
        return bean instanceof com.sun.management.OperatingSystemMXBean
                ? ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime() : -1;
    }

    private static double elapsedProcessCpuSeconds(long start) {
        return start < 0 ? Double.NaN : (processCpuTime() - start) / 1e9;
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
                    relative.getName(2).toString(), relative.getName(3).toString(), input.getParent());
        }

        @Override public String toString() {
            return imageClass + " / " + series + " / " + motion + " / " + condition;
        }
    }

    private static final class CellKey {
        final String imageClass;
        final String motion;
        final Arm arm;

        CellKey(String imageClass, String motion, Arm arm) {
            this.imageClass = imageClass;
            this.motion = motion;
            this.arm = arm;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof CellKey)) return false;
            CellKey key = (CellKey) other;
            return imageClass.equals(key.imageClass) && motion.equals(key.motion) && arm == key.arm;
        }

        @Override public int hashCode() {
            return 31 * (31 * imageClass.hashCode() + motion.hashCode()) + arm.hashCode();
        }
    }

    private static final class Comparison {
        final int wins;
        final int losses;
        final int newFailures;

        Comparison(int wins, int losses, int newFailures) {
            this.wins = wins;
            this.losses = losses;
            this.newFailures = newFailures;
        }
    }

    private static final class ResultRow {
        static final String HEADER = "image_series_class,series_id,motion_profile,condition,arm,arm_id,"
                + "display_name,native_width_px,native_height_px,median_error_px,p90_error_px,"
                + "max_error_px,estimate_wall_seconds,estimate_cpu_seconds,estimate_wall_ms_per_pair,"
                + "warp_wall_seconds,compute_wall_seconds,details";

        final String imageClass;
        final String series;
        final String motion;
        final Arm arm;
        final String armId;
        final String displayName;
        final double medianError;
        final double p90Error;
        final double wallSeconds;
        final String line;

        ResultRow(String imageClass, String series, String motion, Arm arm, String armId,
                  String displayName, double medianError, double p90Error,
                  double wallSeconds, String line) {
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.arm = arm;
            this.armId = armId;
            this.displayName = displayName;
            this.medianError = medianError;
            this.p90Error = p90Error;
            this.wallSeconds = wallSeconds;
            this.line = line;
        }

        String recordingKey() { return imageClass + '/' + series + '/' + motion; }

        static ResultRow parse(String line) {
            List<String> fields = parseCsv(line);
            return new ResultRow(fields.get(0), fields.get(1), fields.get(2),
                    Arm.valueOf(fields.get(4)), fields.get(5), fields.get(6),
                    Double.parseDouble(fields.get(9)), Double.parseDouble(fields.get(10)),
                    Double.parseDouble(fields.get(12)), line);
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
                    } else quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    fields.add(field.toString());
                    field.setLength(0);
                } else field.append(c);
            }
            fields.add(field.toString());
            return fields;
        }
    }
}
