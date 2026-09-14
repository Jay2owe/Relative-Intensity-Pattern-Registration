/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ripr.api.AutomaticFilterSelector;
import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternResult;
import ripr.api.MotionType;
import ripr.api.PixelSelectionStrategy;
import ripr.api.Preprocessing;
import ripr.core.PairScheduler;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the frozen full log-ratio variation matrix on controlled and genuine source series.
 *
 * <p>The matrix is intentionally a list of named implemented arms, not a Cartesian product of every
 * numeric setting. Every arm changes one declared add-on family while preserving the selected base
 * log-ratio model. Controlled motion is scored against exact injected movement. Genuine source
 * motion is reported only as residual stability and is never pooled with controlled accuracy.
 */
public final class FullLogRatioVariationBenchmark {
    static final String RUN_ID = "full_benchmark_2026-08-16";
    private static final String RESULT_HEADER = "experiment,image_series_class,series_id," 
            + "motion_category,condition,arm_id,family,status,frames,median_metric_px," 
            + "p90_metric_px,max_metric_px,total_seconds,cpu_seconds,selected_recipe,details";

    enum Kind { CURRENT, UNFILTERED, AUTOMATIC, SCALE, PREPROCESSING, MASK }

    static final class Arm {
        final String id;
        final String family;
        final Kind kind;
        final double scale;
        final Preprocessing preprocessing;
        final boolean removeOffset;
        final PixelSelectionStrategy strategy;
        final double removePercent;

        Arm(String id, String family, Kind kind) {
            this(id, family, kind, 1.0, Preprocessing.NONE, false,
                    PixelSelectionStrategy.NONE, 25.0);
        }

        Arm(String id, String family, Kind kind, double scale, Preprocessing preprocessing,
            boolean removeOffset, PixelSelectionStrategy strategy, double removePercent) {
            this.id = id;
            this.family = family;
            this.kind = kind;
            this.scale = scale;
            this.preprocessing = preprocessing;
            this.removeOffset = removeOffset;
            this.strategy = strategy;
            this.removePercent = removePercent;
        }

        String details() {
            switch (kind) {
                case CURRENT: return "current category recommendation including declared add-ons";
                case UNFILTERED: return "selected base model without preprocessing or pixel removal";
                case AUTOMATIC: return "frozen automatic preprocessing and pixel-removal selector";
                case SCALE: return "movement estimated at " + format(scale * 100) + "% linear size";
                case PREPROCESSING: return preprocessing.name()
                        + (removeOffset ? " with first-percentile background subtraction" : "");
                case MASK: return strategy.name() + "; scoring filter " + preprocessing.name()
                        + "; remove " + format(removePercent) + "%";
                default: throw new IllegalStateException("unhandled arm " + kind);
            }
        }

        String label() {
            switch (kind) {
                case CURRENT: return "Current category recommendation";
                case UNFILTERED: return "Base log-ratio model without add-ons";
                case AUTOMATIC: return "Automatic preprocessing and pixel-removal selector";
                case SCALE: return "Movement estimation at " + format(scale * 100) + "% size";
                case PREPROCESSING: return "Preprocessing: " + details();
                case MASK: return "Pixel removal: " + details();
                default: throw new IllegalStateException("unhandled arm " + kind);
            }
        }
    }

    private FullLogRatioVariationBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = args.length == 0 ? Paths.get("") : Paths.get(args[0]);
        project = project.toAbsolutePath().normalize();
        List<Arm> arms = arms();
        writeManifest(project, arms);
        if (Boolean.getBoolean("ripr.manifestOnly")) {
            System.out.println("wrote frozen 106-row full benchmark manifest");
            return;
        }

        String experiment = System.getProperty("ripr.onlyExperiment", "both")
                .trim().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("both", "controlled", "natural").contains(experiment)) {
            throw new IllegalArgumentException("ripr.onlyExperiment must be both, controlled or natural");
        }
        Set<String> requestedSeries = requested("ripr.onlySeries");
        Set<String> requestedArms = requested("ripr.onlyArm");
        boolean rewrite = Boolean.getBoolean("ripr.rewrite");
        boolean writeImages = !Boolean.getBoolean("ripr.noImages");
        Totals totals = new Totals();
        if (!"natural".equals(experiment)) {
            runExperiment(project.resolve("library/benchmark/v2/benchmarks/controlled_motion"),
                    true, arms, requestedSeries, requestedArms, rewrite, writeImages, totals);
        }
        if (!"controlled".equals(experiment)) {
            runExperiment(project.resolve("library/benchmark/v2/benchmarks/natural_motion"),
                    false, arms, requestedSeries, requestedArms, rewrite, writeImages, totals);
        }
        System.out.printf(Locale.ROOT,
                "full variation benchmark: attempted %d, completed %d, resumed %d, unavailable %d, failed %d%n",
                totals.attempted, totals.completed, totals.resumed, totals.unavailable,
                totals.failures.size());
        if (!totals.failures.isEmpty()) {
            throw new IOException("full variation failures:\n" + String.join("\n", totals.failures));
        }
    }

    static List<Arm> arms() {
        List<Arm> out = new ArrayList<>();
        out.add(new Arm("033_current_category_recommendation", "recommendation", Kind.CURRENT));
        out.add(new Arm("034_base_model_without_addons", "recommendation", Kind.UNFILTERED));
        out.add(new Arm("035_automatic_filter_selector", "automatic", Kind.AUTOMATIC));

        for (double scale : new double[]{1.0, 0.75, 0.5, 0.25}) {
            out.add(new Arm(String.format(Locale.ROOT, "scale_%03d_percent",
                    Math.round(scale * 100)), "estimation_scale", Kind.SCALE,
                    scale, Preprocessing.NONE, false, PixelSelectionStrategy.NONE, 25));
        }

        addPreprocessing(out, "preprocess_none", Preprocessing.NONE, false);
        addPreprocessing(out, "preprocess_gaussian_0_7", Preprocessing.GAUSSIAN_0_7, false);
        addPreprocessing(out, "preprocess_gaussian_1_0", Preprocessing.GAUSSIAN_1_0, false);
        addPreprocessing(out, "preprocess_gaussian_1_4", Preprocessing.GAUSSIAN_1_4, false);
        addPreprocessing(out, "preprocess_median_3x3", Preprocessing.MEDIAN_3X3, false);
        addPreprocessing(out, "preprocess_background_p1", Preprocessing.NONE, true);
        addPreprocessing(out, "preprocess_background_p1_gaussian_1_0",
                Preprocessing.GAUSSIAN_1_0, true);
        addPreprocessing(out, "preprocess_anscombe", Preprocessing.ANSCOMBE, false);
        addPreprocessing(out, "preprocess_anscombe_gaussian_1_0",
                Preprocessing.ANSCOMBE_GAUSSIAN_1_0, false);
        addPreprocessing(out, "preprocess_unsharp_0_5", Preprocessing.UNSHARP_0_5, false);

        List<PixelSelectionStrategy> rawStrategies = Arrays.asList(
                PixelSelectionStrategy.REMOVE_MOST_UNSTABLE,
                PixelSelectionStrategy.REMOVE_LEAST_UNSTABLE,
                PixelSelectionStrategy.REMOVE_MOST_LAG_GROWTH,
                PixelSelectionStrategy.REMOVE_LEAST_LAG_GROWTH,
                PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                PixelSelectionStrategy.REMOVE_MOST_INFORMATIVE,
                PixelSelectionStrategy.REMOVE_LOWEST_ANCHOR_TRUST,
                PixelSelectionStrategy.REMOVE_HIGHEST_ANCHOR_TRUST,
                PixelSelectionStrategy.STRATIFIED_LOWEST_ANCHOR_TRUST);
        for (PixelSelectionStrategy strategy : rawStrategies) {
            for (double percent : new double[]{25, 50, 75}) {
                addMask(out, Preprocessing.NONE, strategy, percent);
            }
        }

        List<Preprocessing> scoringFilters = Arrays.asList(
                Preprocessing.GAUSSIAN_0_7, Preprocessing.GAUSSIAN_1_0,
                Preprocessing.GAUSSIAN_1_4, Preprocessing.MEDIAN_3X3,
                Preprocessing.ANSCOMBE, Preprocessing.ANSCOMBE_GAUSSIAN_1_0,
                Preprocessing.UNSHARP_0_5);
        List<PixelSelectionStrategy> filteredStrategies = Arrays.asList(
                PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                PixelSelectionStrategy.REMOVE_LOWEST_ANCHOR_TRUST,
                PixelSelectionStrategy.REMOVE_MOST_UNSTABLE,
                PixelSelectionStrategy.STRATIFIED_LOWEST_ANCHOR_TRUST);
        for (Preprocessing filter : scoringFilters) {
            for (PixelSelectionStrategy strategy : filteredStrategies) {
                addMask(out, filter, strategy, 25);
            }
        }
        addMask(out, Preprocessing.GAUSSIAN_1_0,
                PixelSelectionStrategy.REMOVE_MOST_UNSTABLE, 50);

        if (out.size() != 73) throw new IllegalStateException("expected 73 executable arms, got " + out.size());
        Set<String> ids = new LinkedHashSet<>();
        for (Arm arm : out) if (!ids.add(arm.id)) throw new IllegalStateException("duplicate arm " + arm.id);
        return out;
    }

    private static void addPreprocessing(List<Arm> arms, String id,
                                         Preprocessing preprocessing, boolean removeOffset) {
        arms.add(new Arm(id, "preprocessing", Kind.PREPROCESSING, 1.0, preprocessing,
                removeOffset, PixelSelectionStrategy.NONE, 25));
    }

    private static void addMask(List<Arm> arms, Preprocessing filter,
                                PixelSelectionStrategy strategy, double percent) {
        String id = "mask_" + filter.name().toLowerCase(Locale.ROOT) + "__"
                + strategy.name().toLowerCase(Locale.ROOT) + "__remove_"
                + Math.round(percent);
        arms.add(new Arm(id, "pixel_removal", Kind.MASK, 1.0, filter,
                false, strategy, percent));
    }

    private static void runExperiment(Path root, boolean controlled, List<Arm> arms,
                                      Set<String> requestedSeries, Set<String> requestedArms,
                                      boolean rewrite, boolean writeImages, Totals totals)
            throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("missing " + root);
        List<Path> inputs = inputs(root, controlled);
        for (Path inputPath : inputs) {
            Recording recording = Recording.from(root, inputPath, controlled);
            if (!requestedSeries.isEmpty() && !requestedSeries.contains(recording.series)) continue;
            ImagePlus input = IJ.openImage(inputPath.toString());
            if (input == null) {
                totals.failures.add("could not open " + inputPath);
                continue;
            }
            try {
                for (Arm arm : arms) {
                    if (!requestedArms.isEmpty() && !requestedArms.contains(arm.id)) continue;
                    totals.attempted++;
                    Path output = recording.folder.resolve(RUN_ID).resolve(arm.id);
                    if (complete(output, writeImages) && !rewrite) {
                        totals.resumed++;
                        continue;
                    }
                    int scaledWidth = (int) Math.floor(input.getWidth() * arm.scale);
                    int scaledHeight = (int) Math.floor(input.getHeight() * arm.scale);
                    if (scaledWidth < 32 || scaledHeight < 32) {
                        writeUnavailable(recording, arm, output, scaledWidth, scaledHeight);
                        totals.unavailable++;
                        continue;
                    }
                    try {
                        runArm(recording, input, arm, output, writeImages);
                        totals.completed++;
                    } catch (RuntimeException | IOException error) {
                        Files.createDirectories(output);
                        String message = recording + " / " + arm.id + ": " + rootMessage(error);
                        Files.write(output.resolve("run_failure.txt"),
                                (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                        totals.failures.add(message);
                        System.err.println("FAILED " + message);
                    }
                }
            } finally {
                input.close();
            }
        }
        writeSummary(root, controlled);
    }

    private static void runArm(Recording recording, ImagePlus input, Arm arm, Path output,
                               boolean writeImages) throws IOException {
        Files.createDirectories(output);
        RelativeIntensityPatternParameters parameters = parameters(recording, arm);
        long cpuStart = processCpuTime();
        long start = System.nanoTime();
        // This arm is the frozen filter-and-mask selector, not the newer full selector. Resolve it
        // through the compatibility entry point so the 2026-08-16 numbers keep their meaning.
        AutomaticFilterSelector.Result selection = arm.kind == Kind.AUTOMATIC
                ? RelativeIntensityPatternRegistration.resolveAutomaticFilters(input, parameters) : null;
        RelativeIntensityPatternResult result = RelativeIntensityPatternRegistration.register(input,
                selection == null ? parameters : selection.parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double seconds = (System.nanoTime() - start) / 1e9;
        double cpuSeconds = (processCpuTime() - cpuStart) / 1e9;
        try {
            double[] metrics;
            if (recording.controlled) {
                metrics = controlledMetrics(result.registration().cumulative, recording.motion);
            } else {
                NativeMotionProfiler.Residual residual = NativeMotionProfiler.residual(
                        pixels(result.correctedImage()), result.correctedImage().getWidth());
                metrics = new double[]{residual.median, residual.p90, residual.maximum};
            }
            if (writeImages) IJ.saveAsTiff(result.correctedImage(),
                    output.resolve("corrected.tif").toString());
            writeTransforms(output.resolve("transforms.csv"), result.registration().cumulative,
                    recording.controlled ? recording.motion : null);
            String selected = selection == null ? "" : selection.recipe.name();
            String details = arm.details() + "; base " + recording.imageClass + " / "
                    + recording.motion + (selection == null ? "" : "; " + selection.explanation());
            String row = csv(recording.controlled ? "controlled" : "natural") + ','
                    + csv(recording.imageClass) + ',' + csv(recording.series) + ','
                    + csv(recording.motion) + ',' + csv(recording.condition) + ','
                    + csv(arm.id) + ',' + csv(arm.family) + ",ok," + input.getStackSize() + ','
                    + format(metrics[0]) + ',' + format(metrics[1]) + ',' + format(metrics[2]) + ','
                    + format(seconds) + ',' + format(cpuSeconds) + ',' + csv(selected) + ','
                    + csv(details);
            Files.write(output.resolve("comparison.csv"),
                    (RESULT_HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(output.resolve("unavailable.txt"));
            Files.deleteIfExists(output.resolve("run_failure.txt"));
            System.out.printf(Locale.ROOT, "%s %-18s %-34s %-62s %.4f px %.2f s%n",
                    recording.controlled ? "controlled" : "natural", recording.imageClass,
                    recording.series, arm.id, metrics[0], seconds);
        } finally {
            result.correctedImage().changes = false;
            result.close();
        }
    }

    private static RelativeIntensityPatternParameters parameters(Recording recording, Arm arm) {
        RelativeIntensityPatternParameters recommended = RelativeIntensityPatternParameters.builder()
                .recommendation(imageType(recording.imageClass), motionType(recording.motion))
                .autoMaxShift(!recording.controlled)
                .maxShift(recording.controlled ? knownMaxShift(recording.motion) : 30)
                .crop(false).interpolation(Warper.Interpolation.NONE).build();
        if (arm.kind == Kind.CURRENT) return recommended;
        RelativeIntensityPatternParameters.Builder base = recommended.toBuilder().useRecommendation(false)
                .automaticFilterSelection(false)
                .preprocessing(Preprocessing.NONE)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .pixelSelectionPreprocessing(Preprocessing.NONE)
                .pixelRemovalPercent(25).removeOffset(false);
        switch (arm.kind) {
            case UNFILTERED:
                break;
            case AUTOMATIC:
                base.automaticFilterSelection(true);
                break;
            case SCALE:
                base.estimationScale(arm.scale);
                break;
            case PREPROCESSING:
                base.preprocessing(arm.preprocessing).removeOffset(arm.removeOffset)
                        .offsetPercentile(1.0);
                break;
            case MASK:
                base.pixelSelectionStrategy(arm.strategy)
                        .pixelSelectionPreprocessing(arm.preprocessing)
                        .pixelRemovalPercent(arm.removePercent);
                break;
            default:
                throw new IllegalStateException("unhandled arm " + arm.kind);
        }
        return base.build();
    }

    private static double[] controlledMetrics(Transform[] transforms, String motion) {
        int[] x = new int[Benchmark.FRAMES];
        int[] y = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(x, y);
        double[] errors = new double[transforms.length];
        for (int i = 0; i < errors.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            errors[i] = Math.hypot(transform.dx - x[i] / (double) Benchmark.FINE,
                    transform.dy - y[i] / (double) Benchmark.FINE);
        }
        return new double[]{Benchmark.quantile(errors, 0.5), Benchmark.quantile(errors, 0.9),
                Benchmark.quantile(errors, 1.0)};
    }

    private static float[][] pixels(ImagePlus image) {
        ImageStack stack = image.getStack();
        float[][] out = new float[stack.getSize()][];
        for (int i = 0; i < out.length; i++) {
            ImageProcessor processor = stack.getProcessor(i + 1).convertToFloatProcessor();
            out[i] = ((float[]) processor.getPixels()).clone();
        }
        return out;
    }

    private static void writeTransforms(Path path, Transform[] transforms, String motion)
            throws IOException {
        int[] x = null;
        int[] y = null;
        if (motion != null) {
            x = new int[Benchmark.FRAMES];
            y = new int[Benchmark.FRAMES];
            ControlledMotionProfile.valueOf(motion).fill(x, y);
        }
        StringBuilder out = new StringBuilder(
                "frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n");
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            out.append(i + 1).append(',').append(format(transform.dx)).append(',')
                    .append(format(transform.dy)).append(',').append(format(transform.theta)).append(',');
            if (x == null) {
                out.append(",,");
            } else {
                double tx = x[i] / (double) Benchmark.FINE;
                double ty = y[i] / (double) Benchmark.FINE;
                out.append(format(tx)).append(',').append(format(ty)).append(',')
                        .append(format(Math.hypot(transform.dx - tx, transform.dy - ty)));
            }
            out.append(",ok\n");
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeUnavailable(Recording recording, Arm arm, Path output,
                                         int width, int height) throws IOException {
        Files.createDirectories(output);
        String reason = "Unavailable: " + arm.id + " leaves only " + width + " x " + height
                + " estimation pixels; minimum is 32 x 32.";
        Files.write(output.resolve("unavailable.txt"),
                (reason + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        String row = csv(recording.controlled ? "controlled" : "natural") + ','
                + csv(recording.imageClass) + ',' + csv(recording.series) + ','
                + csv(recording.motion) + ',' + csv(recording.condition) + ',' + csv(arm.id) + ','
                + csv(arm.family) + ",unavailable," + recording.frames
                + ",NaN,NaN,NaN,NaN,NaN,," + csv(reason);
        Files.write(output.resolve("comparison.csv"),
                (RESULT_HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(output.resolve("corrected.tif"));
        Files.deleteIfExists(output.resolve("transforms.csv"));
        Files.deleteIfExists(output.resolve("run_failure.txt"));
    }

    private static boolean complete(Path output, boolean writeImages) {
        Path comparison = output.resolve("comparison.csv");
        if (!Files.isRegularFile(comparison)) return false;
        try {
            List<String> lines = Files.readAllLines(comparison, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).contains(",cpu_seconds,")) return false;
        } catch (IOException error) {
            return false;
        }
        if (Files.isRegularFile(output.resolve("unavailable.txt"))) return true;
        return Files.isRegularFile(output.resolve("transforms.csv"))
                && (!writeImages || Files.isRegularFile(output.resolve("corrected.tif")));
    }

    private static void writeSummary(Path root, boolean controlled) throws IOException {
        List<ResultRow> rows = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().equals("comparison.csv"))
                    .filter(path -> path.toString().contains(RUN_ID)).forEach(files::add);
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() >= 2) rows.add(ResultRow.parse(lines.get(1)));
            }
        }
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Files.createDirectories(summary);
        StringBuilder all = new StringBuilder(RESULT_HEADER).append('\n');
        for (ResultRow row : rows) all.append(row.line).append('\n');
        Files.write(summary.resolve("all_recordings.csv"),
                all.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, List<ResultRow>> byArm = new LinkedHashMap<>();
        for (ResultRow row : rows) byArm.computeIfAbsent(row.arm, ignored -> new ArrayList<>()).add(row);
        StringBuilder out = new StringBuilder(
                "experiment,arm_id,recordings,available,mean_median_metric_px,mean_seconds\n");
        for (Map.Entry<String, List<ResultRow>> entry : byArm.entrySet()) {
            int available = 0;
            double metric = 0;
            double seconds = 0;
            for (ResultRow row : entry.getValue()) {
                if (!"ok".equals(row.status)) continue;
                available++;
                metric += row.median;
                seconds += row.seconds;
            }
            out.append(controlled ? "controlled" : "natural").append(',')
                    .append(entry.getKey()).append(',').append(entry.getValue().size()).append(',')
                    .append(available).append(',').append(available == 0 ? "NaN" : format(metric / available))
                    .append(',').append(available == 0 ? "NaN" : format(seconds / available)).append('\n');
        }
        Files.write(summary.resolve("arm_summary.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeManifest(Path project, List<Arm> arms) throws IOException {
        Path benchmark = project.resolve("library/benchmark");
        List<String> core = Files.readAllLines(
                benchmark.resolve("benchmark_v2_method_manifest.csv"), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(
                "run_id,run_method_id,family,display_name,scope,alias_of,output_location,parameters\n");
        for (int i = 1; i < core.size(); i++) {
            if (core.get(i).trim().isEmpty()) continue;
            List<String> fields = fields(core.get(i));
            out.append(RUN_ID).append(",CORE_").append(fields.get(0)).append(',')
                    .append(csv(fields.get(3))).append(',').append(csv(fields.get(2)))
                    .append(",controlled_and_natural,").append(csv(fields.get(5)))
                    .append(',').append(csv("recording folder root"))
                    .append(',').append(csv("existing declared method configuration")).append('\n');
        }
        for (Arm arm : arms) {
            out.append(RUN_ID).append(',').append(csv(arm.id)).append(',').append(csv(arm.family))
                    .append(',').append(csv(arm.label()))
                    .append(",controlled_and_natural,,")
                    .append(csv(RUN_ID + "/" + arm.id)).append(',').append(csv(arm.details())).append('\n');
        }
        out.append(RUN_ID).append(",mask_fast_remove_least_informative_25_alias,pixel_removal,")
                .append(csv("Fast reference-only least-information removal, 25 percent"))
                .append(",controlled_and_natural,")
                .append(csv("mask_none__remove_least_informative__remove_25"))
                .append(',').append(csv(RUN_ID + "/mask_none__remove_least_informative__remove_25"))
                .append(',').append(csv("production route is the same reference-only spatial mask"))
                .append('\n');
        Path manifest = benchmark.resolve(RUN_ID + "_method_manifest.csv");
        Files.write(manifest, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Path> inputs(Path root, boolean controlled) throws IOException {
        String name = controlled ? "00_input_uncorrected.tif" : "00_input_native.tif";
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName().toString().equals(name)).forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static Set<String> requested(String property) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : System.getProperty(property, "").split(",")) {
            if (!value.trim().isEmpty()) out.add(value.trim());
        }
        return out;
    }

    private static ImageType imageType(String value) {
        switch (value) {
            case "PHASE": return ImageType.PHASE_CONTRAST;
            case "BRIGHTFIELD_DIC": return ImageType.BRIGHTFIELD_DIC;
            case "DENSE_FLUOR": return ImageType.DENSE_FLUORESCENCE;
            case "SPARSE_LOWLIGHT": return ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
            case "FIDUCIAL_STATIC": return ImageType.FIDUCIAL_STATIC;
            default: throw new IllegalArgumentException("unknown image class " + value);
        }
    }

    private static MotionType motionType(String value) {
        if (value.contains("INTERMITTENT") || value.contains("JUMP")) {
            return MotionType.INTERMITTENT_JUMPS;
        }
        if (value.contains("STEADY") || value.contains("DIRECTIONAL")) {
            return MotionType.STEADY_DIRECTIONAL_DRIFT;
        }
        if (value.contains("CURVED") || value.contains("OSCILLATING")) {
            return MotionType.CURVED_OSCILLATING_DRIFT;
        }
        return MotionType.SUBPIXEL_RANDOM_WALK;
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

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return System.nanoTime();
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        if (value == null) value = "";
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
        final boolean controlled;
        final String imageClass;
        final String series;
        final String motion;
        final String condition;
        final Path folder;
        final int frames;

        Recording(boolean controlled, String imageClass, String series, String motion,
                  String condition, Path folder, int frames) {
            this.controlled = controlled;
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.condition = condition;
            this.folder = folder;
            this.frames = frames;
        }

        static Recording from(Path root, Path input, boolean controlled) throws IOException {
            Path relative = root.relativize(input);
            if (relative.getNameCount() < 4) throw new IOException("unexpected input path " + input);
            if (controlled) {
                return new Recording(true, relative.getName(0).toString(),
                        relative.getName(1).toString(), relative.getName(2).toString(),
                        relative.getName(3).toString(), input.getParent(), Benchmark.FRAMES);
            }
            ImagePlus image = IJ.openImage(input.toString());
            if (image == null) throw new IOException("could not inspect " + input);
            int frames = image.getStackSize();
            image.close();
            return new Recording(false, relative.getName(0).toString(),
                    relative.getName(2).toString(), relative.getName(1).toString(),
                    "NATIVE", input.getParent(), frames);
        }

        @Override public String toString() {
            return imageClass + '/' + series + '/' + motion + '/' + condition;
        }
    }

    private static final class Totals {
        int attempted;
        int completed;
        int resumed;
        int unavailable;
        final List<String> failures = new ArrayList<>();
    }

    private static final class ResultRow {
        final String line;
        final String arm;
        final String status;
        final double median;
        final double seconds;

        ResultRow(String line, String arm, String status, double median, double seconds) {
            this.line = line;
            this.arm = arm;
            this.status = status;
            this.median = median;
            this.seconds = seconds;
        }

        static ResultRow parse(String line) {
            List<String> fields = fields(line);
            return new ResultRow(line, fields.get(5), fields.get(7),
                    Double.parseDouble(fields.get(9)), Double.parseDouble(fields.get(12)));
        }
    }
}
