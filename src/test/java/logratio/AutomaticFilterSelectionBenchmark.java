/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import logratio.api.AutomaticFilterSelector;
import logratio.api.ImageType;
import logratio.api.LogRatioParameters;
import logratio.api.LogRatioRegistration;
import logratio.api.LogRatioResult;
import logratio.api.MotionType;
import logratio.core.PairScheduler;
import logratio.core.Transform;
import logratio.core.Warper;

import java.io.IOException;
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

/** Runs the frozen automatic filter selector and writes one auditable correction per recording. */
public final class AutomaticFilterSelectionBenchmark {
    private static final String HEADER = "image_series_class,series_id,motion_profile,condition,"
            + "selected_recipe,expected_recipe,matched_expected,median_error_px,p90_error_px,"
            + "max_error_px,total_seconds,mask_score,median_mask_score,median_score,"
            + "gaussian_0_7_score,gaussian_1_0_score,details";

    private AutomaticFilterSelectionBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = args.length == 0 ? Paths.get("") : Paths.get(args[0]);
        Path controlled = project.toAbsolutePath().normalize()
                .resolve("library/benchmark/v2/benchmarks/controlled_motion");
        boolean rewrite = Boolean.getBoolean("logratio.rewrite");
        boolean writeImages = !Boolean.getBoolean("logratio.noImages");
        int complete = 0;
        int resumed = 0;
        List<String> failures = new ArrayList<>();
        for (Path inputPath : inputStacks(controlled)) {
            Recording recording = Recording.from(controlled, inputPath);
            Path output = recording.folder.resolve(
                    "automatic_filter_selection/01_automatic_filter_selector");
            Path comparison = output.resolve("comparison.csv");
            Path transforms = output.resolve("automatic_filter_transforms.csv");
            Path corrected = output.resolve("automatic_filter_corrected.tif");
            if (!rewrite && Files.isRegularFile(comparison) && Files.isRegularFile(transforms)
                    && (!writeImages || Files.isRegularFile(corrected))) {
                resumed++;
                continue;
            }
            ImagePlus input = IJ.openImage(inputPath.toString());
            if (input == null) {
                failures.add("could not open " + inputPath);
                continue;
            }
            try {
                run(recording, input, output, writeImages);
                complete++;
            } catch (RuntimeException | IOException error) {
                Files.createDirectories(output);
                String message = recording + ": " + error;
                Files.write(output.resolve("run_failure.txt"),
                        (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                failures.add(message);
            } finally {
                input.close();
            }
        }
        writeSummary(controlled);
        System.out.printf("automatic filter selector: completed %d, resumed %d, failed %d%n",
                complete, resumed, failures.size());
        if (!failures.isEmpty()) throw new IOException(String.join("\n", failures));
    }

    private static void run(Recording recording, ImagePlus input, Path output,
                            boolean writeImages) throws IOException {
        Files.createDirectories(output);
        LogRatioParameters parameters = LogRatioParameters.builder()
                .recommendation(imageType(recording.imageClass), MotionType.valueOf(recording.motion))
                .automaticFilterSelection(true)
                .autoMaxShift(false).maxShift(knownMaxShift(recording.motion))
                .crop(false).interpolation(Warper.Interpolation.NONE).build();
        long start = System.nanoTime();
        // This benchmark measures the frozen filter-and-mask selector, not the newer full selector,
        // so it resolves through the compatibility entry point kept for exactly that purpose.
        AutomaticFilterSelector.Result selection =
                LogRatioRegistration.resolveAutomaticFilters(input, parameters);
        LogRatioResult result = LogRatioRegistration.register(input, selection.parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double seconds = (System.nanoTime() - start) / 1e9;
        try {
            if (selection == null) throw new IOException("automatic selection was not reported");
            int[] truthX = new int[Benchmark.FRAMES];
            int[] truthY = new int[Benchmark.FRAMES];
            ControlledMotionProfile.valueOf(recording.motion).fill(truthX, truthY);
            double[] errors = errors(result.registration().cumulative, truthX, truthY);
            if (writeImages) IJ.saveAsTiff(result.correctedImage(),
                    output.resolve("automatic_filter_corrected.tif").toString());
            writeTransforms(output.resolve("automatic_filter_transforms.csv"),
                    result.registration().cumulative, truthX, truthY);
            AutomaticFilterSelector.Recipe expected = expected(recording.imageClass, recording.motion);
            String details = "Base recommendation retained; provisional fit used raw pixels; "
                    + selection.explanation();
            String row = recording.imageClass + ',' + recording.series + ',' + recording.motion + ','
                    + recording.condition + ',' + selection.recipe.name() + ',' + expected.name() + ','
                    + (selection.recipe == expected) + ','
                    + format(Benchmark.quantile(errors, 0.5)) + ','
                    + format(Benchmark.quantile(errors, 0.9)) + ','
                    + format(Benchmark.quantile(errors, 1.0)) + ',' + format(seconds) + ','
                    + format(selection.maskScore) + ',' + format(selection.medianMaskScore) + ','
                    + format(selection.medianScore) + ',' + format(selection.gaussian07Score) + ','
                    + format(selection.gaussian10Score) + ',' + csv(details);
            Files.write(output.resolve("comparison.csv"),
                    (HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(output.resolve("run_failure.txt"));
            System.out.printf("%-18s %-34s %-27s %-36s %.4f px %.2f s%n",
                    recording.imageClass, recording.series, recording.motion,
                    selection.recipe, Benchmark.quantile(errors, 0.5), seconds);
        } finally {
            result.correctedImage().changes = false;
            result.close();
        }
    }

    private static void writeSummary(Path controlled) throws IOException {
        List<Row> rows = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(controlled)) {
            List<Path> files = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().equals("comparison.csv"))
                    .filter(path -> path.toString().contains("automatic_filter_selection"))
                    .filter(path -> !path.toString().contains("summaries"))
                    .forEach(files::add);
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() >= 2) rows.add(Row.parse(lines.get(1)));
            }
        }
        if (rows.isEmpty()) return;
        Path summary = controlled.resolve("summaries/automatic_filter_selection");
        Files.createDirectories(summary);
        StringBuilder all = new StringBuilder(HEADER).append('\n');
        for (Row row : rows) all.append(row.line).append('\n');
        Files.write(summary.resolve("all_recordings.csv"),
                all.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, Baseline> baseline = baselines(controlled.resolve(
                "summaries/traditional_preprocessing/all_recordings.csv"));
        double selectedError = 0;
        double selectedSeconds = 0;
        double baseError = 0;
        int matches = 0;
        int additions = 0;
        int wins = 0;
        int losses = 0;
        int failures = 0;
        for (Row row : rows) {
            Baseline control = baseline.get(row.key());
            if (control == null) throw new IOException("missing base control for " + row.key());
            selectedError += row.medianError;
            selectedSeconds += row.seconds;
            baseError += control.error;
            if (row.matched) matches++;
            if (!"NONE".equals(row.recipe)) additions++;
            if (row.medianError < control.error) wins++;
            if (row.medianError > control.error) losses++;
            if (control.error < 0.5 && row.medianError > 2) failures++;
        }
        int n = rows.size();
        String result = "strategy,recordings,recipe_matches,automatic_additions,"
                + "mean_median_error_px,base_error_px,accuracy_change_percent,mean_seconds,"
                + "individual_wins,individual_losses,new_failures\n"
                + "frozen_automatic_filter_selector," + n + ',' + matches + ',' + additions + ','
                + format(selectedError / n) + ',' + format(baseError / n) + ','
                + format(100 * (selectedError / baseError - 1)) + ','
                + format(selectedSeconds / n) + ',' + wins + ',' + losses + ',' + failures + '\n';
        Files.write(summary.resolve("summary.csv"), result.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Baseline> baselines(Path path) throws IOException {
        Map<String, Baseline> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            List<String> fields = fields(lines.get(i));
            if (!"CONTROL".equals(fields.get(4))) continue;
            String key = fields.get(0) + '/' + fields.get(1) + '/' + fields.get(2) + '/'
                    + fields.get(3);
            out.put(key, new Baseline(Double.parseDouble(fields.get(9))));
        }
        return out;
    }

    private static double[] errors(Transform[] transforms, int[] truthX, int[] truthY) {
        double[] out = new double[transforms.length];
        for (int i = 0; i < out.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            out[i] = Math.hypot(transform.dx - truthX[i] / (double) Benchmark.FINE,
                    transform.dy - truthY[i] / (double) Benchmark.FINE);
        }
        return out;
    }

    private static void writeTransforms(Path path, Transform[] transforms,
                                        int[] truthX, int[] truthY) throws IOException {
        StringBuilder out = new StringBuilder(
                "frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n");
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            double tx = truthX[i] / (double) Benchmark.FINE;
            double ty = truthY[i] / (double) Benchmark.FINE;
            out.append(i + 1).append(',').append(format(transform.dx)).append(',')
                    .append(format(transform.dy)).append(',').append(format(transform.theta))
                    .append(',').append(format(tx)).append(',').append(format(ty)).append(',')
                    .append(format(Math.hypot(transform.dx - tx, transform.dy - ty)))
                    .append(",ok\n");
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Path> inputStacks(Path controlled) throws IOException {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(controlled)) {
            stream.filter(path -> path.getFileName().toString().equals("00_input_uncorrected.tif"))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static AutomaticFilterSelector.Recipe expected(String imageClass, String motion) {
        if ("PHASE".equals(imageClass) && "INTERMITTENT_JUMPS".equals(motion)) {
            return AutomaticFilterSelector.Recipe.GAUSSIAN_0_7;
        }
        if ("PHASE".equals(imageClass) && "SUBPIXEL_RANDOM_WALK".equals(motion)) {
            return AutomaticFilterSelector.Recipe.GAUSSIAN_1_0;
        }
        if ("BRIGHTFIELD_DIC".equals(imageClass) && "SUBPIXEL_RANDOM_WALK".equals(motion)) {
            return AutomaticFilterSelector.Recipe.MEDIAN_3X3;
        }
        if ("SPARSE_LOWLIGHT".equals(imageClass)
                && "STEADY_DIRECTIONAL_DRIFT".equals(motion)) {
            return AutomaticFilterSelector.Recipe.MEDIAN_3X3_AND_SPATIAL_MASK_25;
        }
        if ("SPARSE_LOWLIGHT".equals(imageClass)
                && !"SUBPIXEL_RANDOM_WALK".equals(motion)) {
            return AutomaticFilterSelector.Recipe.SPATIAL_MASK_25;
        }
        return AutomaticFilterSelector.Recipe.NONE;
    }

    private static ImageType imageType(String imageClass) {
        if ("PHASE".equals(imageClass)) return ImageType.PHASE_CONTRAST;
        if ("BRIGHTFIELD_DIC".equals(imageClass)) return ImageType.BRIGHTFIELD_DIC;
        if ("DENSE_FLUOR".equals(imageClass)) return ImageType.DENSE_FLUORESCENCE;
        if ("SPARSE_LOWLIGHT".equals(imageClass)) return ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
        if ("FIDUCIAL_STATIC".equals(imageClass)) return ImageType.FIDUCIAL_STATIC;
        throw new IllegalArgumentException("unknown image class " + imageClass);
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

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(field.toString()); field.setLength(0);
            } else field.append(ch);
        }
        out.add(field.toString());
        return out;
    }

    private static final class Recording {
        final String imageClass;
        final String series;
        final String motion;
        final String condition;
        final Path folder;

        Recording(String imageClass, String series, String motion, String condition, Path folder) {
            this.imageClass = imageClass; this.series = series; this.motion = motion;
            this.condition = condition; this.folder = folder;
        }

        static Recording from(Path root, Path input) {
            Path relative = root.relativize(input);
            return new Recording(relative.getName(0).toString(), relative.getName(1).toString(),
                    relative.getName(2).toString(), relative.getName(3).toString(), input.getParent());
        }

        @Override public String toString() {
            return imageClass + '/' + series + '/' + motion + '/' + condition;
        }
    }

    private static final class Row {
        final String line;
        final String imageClass;
        final String series;
        final String motion;
        final String condition;
        final String recipe;
        final boolean matched;
        final double medianError;
        final double seconds;

        Row(String line, List<String> fields) {
            this.line = line; imageClass = fields.get(0); series = fields.get(1);
            motion = fields.get(2); condition = fields.get(3); recipe = fields.get(4);
            matched = Boolean.parseBoolean(fields.get(6));
            medianError = Double.parseDouble(fields.get(7));
            seconds = Double.parseDouble(fields.get(10));
        }

        static Row parse(String line) { return new Row(line, fields(line)); }
        String key() { return imageClass + '/' + series + '/' + motion + '/' + condition; }
    }

    private static final class Baseline {
        final double error;
        Baseline(double error) { this.error = error; }
    }
}
