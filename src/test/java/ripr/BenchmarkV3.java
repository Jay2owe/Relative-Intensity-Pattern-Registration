/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ripr.core.LogPlane;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.PhaseCorrelation;
import ripr.core.Reconciler;
import ripr.core.Transform;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Generator and transform-only internal runner for the prospective v3 benchmark.
 *
 * <p>Unlike the visual v2 writer, this class writes one shared input stack and compact transform
 * rows. It never writes one corrected TIFF per method, which would multiply the publication test
 * into hundreds of gigabytes without adding evidence.
 */
public final class BenchmarkV3 {

    static final String GENERATOR_VERSION = "v3_sensor_integrated_1";
    static final int[] LAGS = {1, 2, 4, 8, 16};
    static final int FINE_PATCH = 32;
    static final int MOVE_FINE = 24;

    private BenchmarkV3() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 1 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: BenchmarkV3 generate|internal "
                    + "<project> <development|integration|publication_test> [output csv]");
        }
        Path project = Paths.get(args[1]).toAbsolutePath().normalize();
        String split = args[2];
        if ("generate".equals(args[0])) {
            generate(project, split);
        } else if ("internal".equals(args[0])) {
            Path output = args.length >= 4 ? Paths.get(args[3]).toAbsolutePath().normalize()
                    : project.resolve("library/benchmark/v3/runs/").resolve(split)
                            .resolve("internal_results.csv");
            runInternal(project, split, output);
        } else {
            throw new IllegalArgumentException("first argument must be generate or internal");
        }
    }

    // ---- protocol records ---------------------------------------------------------------- //

    static final class Source {
        final String id;
        final String split;
        final String imageClass;
        final String independentGroup;
        final String labId;
        final Path path;
        final String declaredSha256;
        final int plane;

        Source(Map<String, String> row, Path project) {
            id = required(row, "source_id");
            split = required(row, "split");
            imageClass = required(row, "image_series_class");
            independentGroup = required(row, "independent_group");
            labId = required(row, "lab_id");
            Path declared = Paths.get(required(row, "local_path"));
            path = (declared.isAbsolute() ? declared : project.resolve(declared)).normalize();
            declaredSha256 = required(row, "sha256");
            plane = Integer.parseInt(row.containsKey("plane_index")
                    && !row.get("plane_index").isEmpty() ? row.get("plane_index") : "0");
        }
    }

    static final class Condition {
        final String id;
        final String panel;
        final String gainModel;
        final double gainStart;
        final double gainEnd;
        final double gainMin;
        final double gainMax;
        final String changeModel;
        final double changeFraction;
        final double noiseDb;
        final int replicates;

        Condition(Map<String, String> row) {
            id = required(row, "condition_id");
            panel = required(row, "panel");
            gainModel = required(row, "gain_model");
            gainStart = Double.parseDouble(required(row, "gain_start"));
            gainEnd = Double.parseDouble(required(row, "gain_end"));
            gainMin = Double.parseDouble(required(row, "gain_min"));
            gainMax = Double.parseDouble(required(row, "gain_max"));
            changeModel = required(row, "change_model");
            changeFraction = Double.parseDouble(required(row, "change_fraction"));
            String db = required(row, "noise_db");
            noiseDb = "inf".equalsIgnoreCase(db) ? Double.POSITIVE_INFINITY
                    : Double.parseDouble(db);
            replicates = Integer.parseInt(required(row, "replicates"));
        }

        double gain(int frame) {
            double u = frame / (double) (Benchmark.FRAMES - 1);
            if ("constant".equals(gainModel)) return gainStart;
            if ("log_linear".equals(gainModel)) {
                return gainStart * Math.exp(Math.log(gainEnd / gainStart) * u);
            }
            if ("step_half".equals(gainModel)) {
                return frame < Benchmark.FRAMES / 2 ? gainStart : gainEnd;
            }
            if ("sine".equals(gainModel)) {
                double centre = (gainMin + gainMax) / 2.0;
                double amplitude = (gainMax - gainMin) / 2.0;
                return centre + amplitude * Math.sin(2.0 * Math.PI * u);
            }
            throw new IllegalArgumentException("unknown gain model " + gainModel);
        }
    }

    // ---- generation ---------------------------------------------------------------------- //

    private static void generate(Path project, String split) throws Exception {
        Path v3 = project.resolve("library/benchmark/v3");
        Path sourceManifest = v3.resolve("protocol/source_manifest.csv");
        Path conditionManifest = v3.resolve("protocol/conditions.csv");
        if (!Files.isRegularFile(sourceManifest)) throw new IOException("missing " + sourceManifest);
        List<Source> sources = new ArrayList<>();
        for (Map<String, String> row : readCsv(sourceManifest)) {
            Source source = new Source(row, project);
            if (split.equals(source.split)) sources.add(source);
        }
        if (sources.isEmpty()) throw new IOException("no sources declared for split " + split);
        List<Condition> conditions = new ArrayList<>();
        String requestedPanel = System.getProperty("v3.conditionPanel", "universal");
        Set<String> requestedConditions = requested("v3.onlyCondition");
        for (Map<String, String> row : readCsv(conditionManifest)) {
            Condition condition = new Condition(row);
            if (!requestedConditions.isEmpty() && !requestedConditions.contains(condition.id)) continue;
            if (requestedConditions.isEmpty() && !"all".equals(requestedPanel)
                    && !requestedPanel.equals(condition.panel)) continue;
            conditions.add(condition);
        }
        if (conditions.isEmpty()) throw new IOException("no conditions selected");

        Set<String> requestedSources = requested("v3.onlySource");
        String onlyMotion = System.getProperty("v3.onlyMotion", "").trim();
        int maxSources = Integer.parseInt(System.getProperty("v3.maxSources", "2147483647"));
        Path root = v3.resolve("inputs").resolve(split);
        Files.createDirectories(root);
        String manifestHeader = "split,image_series_class,series_id,"
                + "independent_group,lab_id,motion_category,condition_id,condition_instance,"
                + "replicate,input_relative_path,input_sha256,width,height,frames,source_sha256,"
                + "generator_version,truth_x_px,truth_y_px";
        Path inputManifest = root.resolve("inputs_manifest.csv");
        LinkedHashMap<String, String> manifestRows = new LinkedHashMap<>();
        if (Files.isRegularFile(inputManifest) && !Boolean.getBoolean("v3.resetManifest")) {
            List<String> existing = Files.readAllLines(inputManifest, StandardCharsets.UTF_8);
            if (!existing.isEmpty() && manifestHeader.equals(existing.get(0))) {
                for (int line = 1; line < existing.size(); line++) {
                    if (existing.get(line).trim().isEmpty()) continue;
                    List<String> values = fields(existing.get(line));
                    List<String> headers = fields(manifestHeader);
                    int pathColumn = headers.indexOf("input_relative_path");
                    if (values.size() == headers.size() && pathColumn >= 0) {
                        manifestRows.put(values.get(pathColumn), existing.get(line));
                    }
                }
            }
        }
        int sourceCount = 0;
        int recordingCount = 0;
        for (Source source : sources) {
            if (!requestedSources.isEmpty() && !requestedSources.contains(source.id)) continue;
            if (sourceCount >= maxSources) break;
            verifySource(source);
            FineSource fine = loadFineSource(source);
            sourceCount++;
            try {
                for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                    if (!onlyMotion.isEmpty() && !onlyMotion.equals(motion.name())) continue;
                    int[] fineX = new int[Benchmark.FRAMES];
                    int[] fineY = new int[Benchmark.FRAMES];
                    motion.fill(fineX, fineY);
                    double[] truthX = new double[Benchmark.FRAMES];
                    double[] truthY = new double[Benchmark.FRAMES];
                    for (int t = 0; t < Benchmark.FRAMES; t++) {
                        truthX[t] = fineX[t] / (double) Benchmark.FINE;
                        truthY[t] = fineY[t] / (double) Benchmark.FINE;
                    }
                    Geometry geometry = geometry(fine.width, fine.height, fineX, fineY);
                    for (Condition condition : conditions) {
                        int replicateCount = Integer.parseInt(System.getProperty("v3.replicates",
                                String.valueOf(condition.replicates)));
                        for (int replicate = 0; replicate < replicateCount; replicate++) {
                            String instance = condition.id + "_r" + String.format("%02d", replicate);
                            Path folder = root.resolve(source.imageClass).resolve(source.id)
                                    .resolve(motion.name()).resolve(instance);
                            Path input = folder.resolve("00_input_uncorrected.tif");
                            if (!Files.isRegularFile(input) || Boolean.getBoolean("v3.rewrite")) {
                                Files.createDirectories(folder);
                                float[][] frames = frames(source, fine, geometry, fineX, fineY,
                                        motion, condition, replicate);
                                IJ.saveAsTiff(BenchmarkStacks.stack(frames, geometry.width,
                                        source.id + " " + motion.name() + " " + instance),
                                        input.toString());
                            }
                            String relative = root.relativize(input).toString().replace('\\', '/');
                            StringBuilder row = new StringBuilder();
                            row.append(csv(split)).append(',').append(csv(source.imageClass))
                                    .append(',').append(csv(source.id)).append(',')
                                    .append(csv(source.independentGroup)).append(',')
                                    .append(csv(source.labId)).append(',').append(csv(motion.name()))
                                    .append(',').append(csv(condition.id)).append(',')
                                    .append(csv(instance)).append(',').append(replicate).append(',')
                                    .append(csv(relative)).append(',').append(sha256(input)).append(',')
                                    .append(geometry.width).append(',').append(geometry.width).append(',')
                                    .append(Benchmark.FRAMES).append(',').append(source.declaredSha256)
                                    .append(',').append(GENERATOR_VERSION).append(',')
                                    .append(csv(transforms(truthX))).append(',')
                                    .append(csv(transforms(truthY)));
                            manifestRows.put(relative, row.toString());
                            recordingCount++;
                            System.out.printf(Locale.ROOT, "generated %s/%s/%s/%s%n",
                                    source.imageClass, source.id, motion.name(), instance);
                        }
                    }
                }
            } finally {
                fine.close();
            }
        }
        StringBuilder manifest = new StringBuilder(manifestHeader).append('\n');
        for (String row : manifestRows.values()) manifest.append(row).append('\n');
        Files.write(inputManifest, manifest.toString().getBytes(StandardCharsets.UTF_8));
        System.out.printf("v3 generated %d sources and %d recordings; wrote %s%n",
                sourceCount, recordingCount, inputManifest);
    }

    static final class FineSource {
        final ImagePlus owner;
        final float[] pixels;
        final int width;
        final int height;

        FineSource(ImagePlus owner, float[] pixels, int width, int height) {
            this.owner = owner;
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }

        void close() {
            owner.close();
        }
    }

    private static FineSource loadFineSource(Source source) throws IOException {
        ImagePlus image = IJ.openImage(source.path.toString());
        if (image == null) throw new IOException("could not open " + source.path);
        if (source.plane < 0 || source.plane >= image.getStackSize()) {
            image.close();
            throw new IOException(source.id + " plane " + source.plane + " outside stack of "
                    + image.getStackSize());
        }
        image.setSlice(source.plane + 1);
        int shortest = Math.min(image.getWidth(), image.getHeight());
        if (shortest < 512) {
            double scale = 512.0 / shortest;
            ImageProcessor processor = image.getProcessor().duplicate();
            processor.setInterpolationMethod(ImageProcessor.BILINEAR);
            processor = processor.resize((int) Math.ceil(image.getWidth() * scale),
                    (int) Math.ceil(image.getHeight() * scale));
            image.close();
            image = new ImagePlus(source.id, processor);
        }
        return new FineSource(image, Benchmark.seedPlane(image), image.getWidth(), image.getHeight());
    }

    static final class Geometry {
        final int margin;
        final int width;

        Geometry(int margin, int width) {
            this.margin = margin;
            this.width = width;
        }
    }

    private static Geometry geometry(int sourceWidth, int sourceHeight, int[] fineX, int[] fineY)
            throws IOException {
        int span = 0;
        for (int t = 0; t < fineX.length; t++) {
            span = Math.max(span, Math.max(Math.abs(fineX[t]), Math.abs(fineY[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int width = Math.min((sourceWidth - 2 * margin) / Benchmark.FINE,
                (sourceHeight - 2 * margin) / Benchmark.FINE);
        if (width < 64) throw new IOException("source yields only " + width + " output pixels");
        return new Geometry(margin, width);
    }

    private static float[][] frames(Source source, FineSource fine, Geometry geometry,
                                    int[] fineX, int[] fineY, ControlledMotionProfile motion,
                                    Condition condition, int replicate) {
        float[][] out = new float[Benchmark.FRAMES][];
        double sourceSd = Benchmark.standardDeviation(fine.pixels);
        double sigma = Double.isInfinite(condition.noiseDb) ? 0
                : sourceSd * Math.pow(10.0, -condition.noiseDb / 20.0);
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            float[] scene = changedScene(source, fine, condition, replicate, t);
            float[] frame = integrate(scene, fine.width, geometry.width, geometry.margin,
                    fineX[t], fineY[t]);
            double gain = condition.gain(t);
            Random noise = new Random(seed(source.id, motion.name(), "noise", replicate, t,
                    String.valueOf(condition.noiseDb)));
            for (int i = 0; i < frame.length; i++) {
                double value = frame[i] * gain + sigma * noise.nextGaussian();
                frame[i] = (float) Math.max(0, value);
            }
            out[t] = frame;
        }
        return out;
    }

    private static float[] changedScene(Source source, FineSource fine, Condition condition,
                                        int replicate, int frame) {
        if ("none".equals(condition.changeModel) || condition.changeFraction <= 0) {
            return fine.pixels;
        }
        float[] changed = fine.pixels.clone();
        Random where = new Random(seed(source.id, "change_where", replicate, frame));
        Random what = new Random(seed(source.id, "change_what", replicate, frame));
        int patches = (int) Math.ceil(condition.changeFraction * fine.width * fine.height
                / (double) (FINE_PATCH * FINE_PATCH));
        int room = FINE_PATCH + MOVE_FINE;
        float bright = (float) (Benchmark.percentile(fine.pixels, 99.5) * 2.0 + 8.0);
        for (int patch = 0; patch < patches; patch++) {
            int px = MOVE_FINE + where.nextInt(Math.max(1, fine.width - room - MOVE_FINE));
            int py = MOVE_FINE + where.nextInt(Math.max(1, fine.height - room - MOVE_FINE));
            if ("bright_blocks".equals(condition.changeModel)) {
                float value = bright * (0.75f + 0.5f * what.nextFloat());
                for (int y = 0; y < FINE_PATCH; y++) {
                    int offset = (py + y) * fine.width + px;
                    Arrays.fill(changed, offset, offset + FINE_PATCH, value);
                }
            } else if ("moved_content".equals(condition.changeModel)) {
                int ox = what.nextInt(2 * MOVE_FINE + 1) - MOVE_FINE;
                int oy = what.nextInt(2 * MOVE_FINE + 1) - MOVE_FINE;
                for (int y = 0; y < FINE_PATCH; y++) {
                    int destination = (py + y) * fine.width + px;
                    int origin = (py + y + oy) * fine.width + px + ox;
                    System.arraycopy(fine.pixels, origin, changed, destination, FINE_PATCH);
                }
            } else {
                throw new IllegalArgumentException("unknown change model " + condition.changeModel);
            }
        }
        return changed;
    }

    private static float[] integrate(float[] source, int sourceWidth, int width, int margin,
                                     int dxFine, int dyFine) {
        int originX = margin - dxFine;
        int originY = margin - dyFine;
        float[] out = new float[width * width];
        double norm = 1.0 / (Benchmark.FINE * Benchmark.FINE);
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                double total = 0;
                for (int yy = 0; yy < Benchmark.FINE; yy++) {
                    int row = (originY + y * Benchmark.FINE + yy) * sourceWidth
                            + originX + x * Benchmark.FINE;
                    for (int xx = 0; xx < Benchmark.FINE; xx++) total += source[row + xx];
                }
                out[y * width + x] = (float) (total * norm);
            }
        }
        return out;
    }

    // ---- internal methods ---------------------------------------------------------------- //

    enum MethodKind { ESTIMATOR, CORE_ESTIMATOR, LAG_MOST, LAG_LEAST, MODAL, AUTO }

    static final class MethodSpec {
        final String id;
        final String label;
        final String family;
        final String strategy;
        final MethodKind kind;
        final Benchmark.Estimator estimator;
        final PairEstimator.Kind coreEstimator;
        final Reconciler.Reference reference;

        MethodSpec(String id, String label, String family, Benchmark.Estimator estimator) {
            this(id, label, family, "multi_lag_rcc", MethodKind.ESTIMATOR, estimator, null,
                    Reconciler.Reference.MULTILAG);
        }

        MethodSpec(String id, String label, String family, MethodKind kind) {
            this(id, label, family, "multi_lag_rcc", kind, null, null,
                    Reconciler.Reference.MULTILAG);
        }

        MethodSpec(String id, String label, String family, String strategy,
                   Benchmark.Estimator estimator, Reconciler.Reference reference) {
            this(id, label, family, strategy, MethodKind.ESTIMATOR, estimator, null, reference);
        }

        MethodSpec(String id, String label, String family, String strategy,
                   PairEstimator.Kind estimator, Reconciler.Reference reference) {
            this(id, label, family, strategy, MethodKind.CORE_ESTIMATOR, null, estimator, reference);
        }

        MethodSpec(String id, String label, String family, String strategy, MethodKind kind,
                   Benchmark.Estimator estimator, PairEstimator.Kind coreEstimator,
                   Reconciler.Reference reference) {
            this.id = id;
            this.label = label;
            this.family = family;
            this.strategy = strategy;
            this.kind = kind;
            this.estimator = estimator;
            this.coreEstimator = coreEstimator;
            this.reference = reference;
        }
    }

    private static List<MethodSpec> methods() {
        List<MethodSpec> methods = new ArrayList<>(Arrays.asList(
                new MethodSpec("01_log_ratio_tukey_standard_gradient", "Log-ratio Tukey standard gradient", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY),
                new MethodSpec("02_exclude_brightest_10_percent", "Exclude brightest 10 percent", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_NO_TOP_10),
                new MethodSpec("03_exclude_brightest_25_percent", "Exclude brightest 25 percent", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_NO_TOP_25),
                new MethodSpec("04_exclude_dimmest_25_percent", "Exclude dimmest 25 percent", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_NO_BOTTOM_25),
                new MethodSpec("05_gradient_threshold_0x_median", "Gradient threshold 0x median", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_0),
                new MethodSpec("06_gradient_threshold_0_25x_median", "Gradient threshold 0.25x median", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_025),
                new MethodSpec("07_gradient_threshold_0_5x_median", "Gradient threshold 0.5x median", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_05),
                new MethodSpec("08_gradient_threshold_1x_median", "Gradient threshold 1x median", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_1),
                new MethodSpec("09_gradient_threshold_2x_median", "Gradient threshold 2x median", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_2),
                new MethodSpec("10_gradient_threshold_4x_median", "Gradient threshold 4x median", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_GRAD_4),
                new MethodSpec("11_shared_significant_edges", "Shared significant edges", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_TUKEY_MUTUAL_NOISE),
                new MethodSpec("12_log_ratio_huber_weighting", "Log-ratio Huber", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_HUBER),
                new MethodSpec("13_log_ratio_woods_least_squares", "Log-ratio Woods least squares", "LOG_RATIO", Benchmark.Estimator.LOGRATIO_WOODS),
                new MethodSpec("14_squared_difference_no_gain", "Squared difference without gain", "SQUARED_DIFFERENCE", Benchmark.Estimator.SSD),
                new MethodSpec("15_phase_correlation", "Internal phase correlation", "PHASE_CORRELATION", Benchmark.Estimator.PHASE_CORRELATION),
                new MethodSpec("16_cross_correlation", "Internal cross-correlation", "CROSS_CORRELATION", Benchmark.Estimator.CROSS_CORRELATION),
                new MethodSpec("17_lag_remove_most_moving_55_percent", "Remove most-moving 55 percent", "LAG_PIXEL_SELECTION", MethodKind.LAG_MOST),
                new MethodSpec("18_lag_remove_least_moving_55_percent", "Remove least-moving 55 percent", "LAG_PIXEL_SELECTION", MethodKind.LAG_LEAST),
                new MethodSpec("19_global_modal_movement", "Global modal movement", "LAG_PIXEL_SELECTION", MethodKind.MODAL),
                new MethodSpec("20_automatic_information_selector", "Automatic information selector", "LOG_RATIO", MethodKind.AUTO),
                new MethodSpec("48_area_correlation_newton", "Area correlation Newton", "AREA_CORRELATION", "multi_lag_rcc", PairEstimator.Kind.AREA_CORRELATION_NEWTON, Reconciler.Reference.MULTILAG)
        ));
        if (Boolean.getBoolean("v3.includeScheduler")) {
            methods.add(new MethodSpec("A01_logratio_chain", "Log-ratio previous-frame chain", "LOG_RATIO", "previous_frame_chain", Benchmark.Estimator.LOGRATIO_TUKEY, Reconciler.Reference.CONSECUTIVE));
            methods.add(new MethodSpec("A02_logratio_first", "Log-ratio first-frame", "LOG_RATIO", "first_frame", Benchmark.Estimator.LOGRATIO_TUKEY, Reconciler.Reference.FIXED));
            methods.add(new MethodSpec("A03_area_newton_chain", "Area Newton previous-frame chain", "AREA_CORRELATION", "previous_frame_chain", PairEstimator.Kind.AREA_CORRELATION_NEWTON, Reconciler.Reference.CONSECUTIVE));
            methods.add(new MethodSpec("A04_area_newton_first", "Area Newton first-frame", "AREA_CORRELATION", "first_frame", PairEstimator.Kind.AREA_CORRELATION_NEWTON, Reconciler.Reference.FIXED));
            methods.add(new MethodSpec("A05_phase_chain", "Phase correlation previous-frame chain", "PHASE_CORRELATION", "previous_frame_chain", Benchmark.Estimator.PHASE_CORRELATION, Reconciler.Reference.CONSECUTIVE));
            methods.add(new MethodSpec("A06_phase_first", "Phase correlation first-frame", "PHASE_CORRELATION", "first_frame", Benchmark.Estimator.PHASE_CORRELATION, Reconciler.Reference.FIXED));
        }
        return methods;
    }

    private static void runInternal(Path project, String split, Path output) throws Exception {
        Path root = project.resolve("library/benchmark/v3/inputs").resolve(split);
        Path inputManifest = root.resolve("inputs_manifest.csv");
        if (!Files.isRegularFile(inputManifest)) throw new IOException("missing " + inputManifest);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        String header = "dataset,split,image_series_class,series_id,independent_group,lab_id,"
                + "motion_category,condition_id,condition_instance,replicate,method_id,method_label,"
                + "family,strategy,config_id,status,median_error_px,p90_error_px,max_error_px,"
                + "terminal_error_px,frames_over_1px,frames_over_5px,cpu_seconds,elapsed_seconds,"
                + "pairs,preprocessing_arm,normalization_fallback,peak_heap_mb,transform_x_px,"
                + "transform_y_px,details\n";
        boolean rewrite = Boolean.getBoolean("v3.rewrite");
        if (rewrite || !Files.exists(output)) {
            Files.write(output, header.getBytes(StandardCharsets.UTF_8));
        }
        Set<String> completed = rewrite ? Collections.<String>emptySet() : completedKeys(output);
        Set<String> onlyMethods = requested("v3.onlyMethod");
        Set<String> onlyConditions = requested("v3.onlyCondition");
        Set<String> onlySources = requested("v3.onlySource");
        Set<String> preprocessing = requestedWithDefault("v3.preprocessingArms",
                "native,common_normalized");
        List<MethodSpec> methods = methods();
        int successes = 0;
        int failures = 0;
        for (Map<String, String> inputRow : readCsv(inputManifest)) {
            String source = required(inputRow, "series_id");
            String condition = required(inputRow, "condition_id");
            if (!onlySources.isEmpty() && !onlySources.contains(source)) continue;
            if (!onlyConditions.isEmpty() && !onlyConditions.contains(condition)) continue;
            Path input = root.resolve(required(inputRow, "input_relative_path")).normalize();
            float[][] nativeFrames = ExternalPluginComparisonStacks.readFrames(input);
            int width = Integer.parseInt(required(inputRow, "width"));
            String motionName = required(inputRow, "motion_category");
            ControlledMotionProfile motion = ControlledMotionProfile.valueOf(motionName);
            double[][] truth = truth(motion);
            double maxShift = maxShift(truth[0], truth[1]);
            for (String preprocessingArm : preprocessing) {
                float[][] frames = nativeFrames;
                boolean normalizationFallback = false;
                if ("common_normalized".equals(preprocessingArm)) {
                    ExternalPluginComparisonStacks.NormalizedFrames normalized =
                            ExternalPluginComparisonStacks.commonNormalize(nativeFrames);
                    frames = normalized.frames;
                    normalizationFallback = normalized.fallback;
                } else if (!"native".equals(preprocessingArm)) {
                    throw new IllegalArgumentException("unknown preprocessing arm "
                            + preprocessingArm);
                }
                for (MethodSpec method : methods) {
                    if (!onlyMethods.isEmpty() && !matches(onlyMethods, method.id)) continue;
                    String key = key(inputRow, method.id, preprocessingArm);
                    if (completed.contains(key)) continue;
                    long cpu0 = processCpuNanos();
                    long wall0 = System.nanoTime();
                    Reconciler.Solution solution = null;
                    String status = "ok";
                    String details = "";
                    try {
                        solution = solve(frames, width, maxShift, method);
                    } catch (RuntimeException error) {
                        status = "failed:" + concise(error);
                        details = concise(error);
                    }
                    double elapsed = (System.nanoTime() - wall0) / 1e9;
                    double cpu = (processCpuNanos() - cpu0) / 1e9;
                    double[] errors = solution == null ? null
                            : errors(solution, truth[0], truth[1]);
                    int pairs = pairCount(method.reference, frames.length);
                    double heapMb = (Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0);
                    String row = resultRow(inputRow, method, status, errors, cpu, elapsed, pairs,
                            preprocessingArm, normalizationFallback, heapMb, solution, details);
                    Files.write(output, row.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.APPEND);
                    if (solution == null) failures++; else successes++;
                    System.out.printf(Locale.ROOT, "%s %s %s %s %s %.4f px%n", source,
                            motionName, required(inputRow, "condition_instance"), method.id,
                            preprocessingArm, errors == null ? Double.NaN : quantile(errors, 0.5));
                }
            }
        }
        System.out.printf("v3 internal complete: %d successful rows; %d failed rows; %s%n",
                successes, failures, output);
    }

    private static Reconciler.Solution solve(float[][] frames, int width, double maxShift,
                                              MethodSpec method) {
        switch (method.kind) {
            case LAG_MOST:
                return Benchmark.solveLagInstabilityForStacks(frames, width, maxShift, true, 55);
            case LAG_LEAST:
                return Benchmark.solveLagInstabilityForStacks(frames, width, maxShift, false, 55);
            case MODAL:
                return Benchmark.solveModalMovementForStacks(frames, width, maxShift);
            case AUTO:
                Benchmark.Estimator selected = Benchmark.resolveAutoInformation(frames, width);
                return scheduled(frames, width, maxShift, selected, null,
                        Reconciler.Reference.MULTILAG);
            case CORE_ESTIMATOR:
                return scheduled(frames, width, maxShift, null, method.coreEstimator,
                        method.reference);
            case ESTIMATOR:
            default:
                return scheduled(frames, width, maxShift, method.estimator, null,
                        method.reference);
        }
    }

    private static Reconciler.Solution scheduled(float[][] frames, int width, double maxShift,
                                                  Benchmark.Estimator benchmarkEstimator,
                                                  PairEstimator.Kind coreEstimator,
                                                  Reconciler.Reference reference) {
        List<Reconciler.Observation> plan = Reconciler.planPairs(reference, frames.length,
                0, 1, LAGS);
        PairAligner.Options options = benchmarkEstimator != null
                ? benchmarkEstimator.options(maxShift)
                : Benchmark.Estimator.LOGRATIO_TUKEY.options(maxShift);
        options.maxShift = maxShift;
        boolean pyramid = coreEstimator != null || benchmarkEstimator.pyramid;
        LogPlane[][] pyramids = null;
        if (pyramid) {
            int levels = options.levelsFor(width, width);
            pyramids = new LogPlane[frames.length][];
            for (int t = 0; t < frames.length; t++) {
                double ceiling = LogPlane.NO_SATURATION;
                double floor = LogPlane.NO_FLOOR;
                if (benchmarkEstimator != null) {
                    double ceilingPercentile = benchmarkEstimator.ceiling();
                    double floorPercentile = benchmarkEstimator.floor();
                    if (!Double.isNaN(ceilingPercentile)) {
                        ceiling = Benchmark.percentile(frames[t], ceilingPercentile);
                    }
                    if (!Double.isNaN(floorPercentile)) {
                        floor = Benchmark.percentile(frames[t], floorPercentile);
                    }
                }
                LogPlane base = LogPlane.of(frames[t], width, width, Benchmark.EPSILON,
                        floor, ceiling);
                pyramids[t] = coreEstimator != null && coreEstimator.prefersLinearPyramid()
                        ? base.linearPyramid(levels) : base.pyramid(levels);
            }
        }
        Transform[] measured = new Transform[plan.size()];
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            if (coreEstimator != null) {
                measured[i] = coreEstimator.estimate(pyramids[edge.from], pyramids[edge.to],
                        options).transform;
            } else if (benchmarkEstimator.pyramid) {
                measured[i] = PairAligner.align(pyramids[edge.from], pyramids[edge.to],
                        options).transform;
            } else {
                double[] shift = PhaseCorrelation.shift(frames[edge.from], frames[edge.to],
                        width, width, benchmarkEstimator == Benchmark.Estimator.PHASE_CORRELATION);
                measured[i] = Transform.translation(shift[0], shift[1]);
            }
        }
        if (reference == Reconciler.Reference.CONSECUTIVE) return Reconciler.chain(measured);
        if (reference == Reconciler.Reference.FIXED) {
            Transform[] toReference = new Transform[frames.length];
            toReference[0] = Transform.IDENTITY;
            for (int i = 0; i < plan.size(); i++) toReference[plan.get(i).to] = measured[i];
            return Reconciler.fixed(toReference, 0);
        }
        List<Reconciler.Observation> observations = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            observations.add(new Reconciler.Observation(edge.from, edge.to, measured[i]));
        }
        return Reconciler.multiLag(frames.length, observations);
    }

    private static String resultRow(Map<String, String> input, MethodSpec method, String status,
                                    double[] errors, double cpu, double elapsed, int pairs,
                                    String preprocessing, boolean fallback, double heapMb,
                                    Reconciler.Solution solution, String details) {
        double median = errors == null ? Double.NaN : quantile(errors, 0.5);
        double p90 = errors == null ? Double.NaN : quantile(errors, 0.9);
        double maximum = errors == null ? Double.NaN : quantile(errors, 1.0);
        double terminal = errors == null ? Double.NaN : errors[errors.length - 1];
        int aboveOne = errors == null ? -1 : countAbove(errors, 1.0);
        int aboveFive = errors == null ? -1 : countAbove(errors, 5.0);
        return csv("v3") + ',' + csv(required(input, "split")) + ','
                + csv(required(input, "image_series_class")) + ','
                + csv(required(input, "series_id")) + ','
                + csv(required(input, "independent_group")) + ','
                + csv(required(input, "lab_id")) + ','
                + csv(required(input, "motion_category")) + ','
                + csv(required(input, "condition_id")) + ','
                + csv(required(input, "condition_instance")) + ','
                + required(input, "replicate") + ',' + csv(method.id) + ',' + csv(method.label)
                + ',' + csv(method.family) + ',' + csv(method.strategy) + ',' + csv(method.id) + ','
                + csv(status) + ','
                + number(median) + ',' + number(p90) + ',' + number(maximum) + ','
                + number(terminal) + ',' + aboveOne + ',' + aboveFive + ',' + number(cpu) + ','
                + number(elapsed) + ',' + pairs + ',' + csv(preprocessing) + ',' + fallback + ','
                + number(heapMb) + ',' + csv(transforms(solution, true)) + ','
                + csv(transforms(solution, false)) + ',' + csv(details) + '\n';
    }

    // ---- shared utilities ---------------------------------------------------------------- //

    private static double[][] truth(ControlledMotionProfile motion) {
        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        motion.fill(fineX, fineY);
        double[][] out = new double[2][Benchmark.FRAMES];
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            out[0][t] = fineX[t] / (double) Benchmark.FINE;
            out[1][t] = fineY[t] / (double) Benchmark.FINE;
        }
        return out;
    }

    private static double maxShift(double[] x, double[] y) {
        double reach = 0;
        for (int i = 0; i < x.length; i++) reach = Math.max(reach, Math.hypot(x[i], y[i]));
        return 2 * reach + 8;
    }

    private static double[] errors(Reconciler.Solution solution, double[] x, double[] y) {
        double[] errors = new double[x.length];
        for (int t = 0; t < errors.length; t++) {
            Transform transform = solution.cumulative[t] == null
                    ? Transform.IDENTITY : solution.cumulative[t];
            errors[t] = Math.hypot(transform.dx - x[t], transform.dy - y[t]);
        }
        return errors;
    }

    private static double quantile(double[] values, double q) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[(int) Math.round(q * (copy.length - 1))];
    }

    private static int countAbove(double[] values, double threshold) {
        int count = 0;
        for (double value : values) if (value > threshold) count++;
        return count;
    }

    private static int pairCount(Reconciler.Reference reference, int frames) {
        return Reconciler.planPairs(reference, frames, 0, 1, LAGS).size();
    }

    private static String transforms(Reconciler.Solution solution, boolean x) {
        if (solution == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < solution.cumulative.length; i++) {
            if (i > 0) out.append(';');
            Transform transform = solution.cumulative[i];
            if (transform == null) out.append("NaN");
            else out.append(String.format(Locale.ROOT, "%.9f", x ? transform.dx : transform.dy));
        }
        return out.toString();
    }

    private static String transforms(double[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(';');
            out.append(String.format(Locale.ROOT, "%.9f", values[i]));
        }
        return out.toString();
    }

    private static long seed(String... parts) {
        long value = 0xcbf29ce484222325L;
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                value ^= part.charAt(i);
                value *= 0x100000001b3L;
            }
            value ^= 0xff;
            value *= 0x100000001b3L;
        }
        return value;
    }

    private static long seed(String source, String motion, String kind, int replicate, int frame,
                             String level) {
        return seed(source, motion, kind, String.valueOf(replicate), String.valueOf(frame), level);
    }

    private static long seed(String source, String kind, int replicate, int frame) {
        return seed(source, kind, String.valueOf(replicate), String.valueOf(frame));
    }

    private static void verifySource(Source source) throws Exception {
        if (!Files.isRegularFile(source.path)) throw new IOException("missing " + source.path);
        String actual = sha256(source.path);
        if (!actual.equalsIgnoreCase(source.declaredSha256)) {
            throw new IOException(source.id + " SHA-256 mismatch: declared "
                    + source.declaredSha256 + " actual " + actual);
        }
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (java.io.InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return Collections.emptyList();
        List<String> header = fields(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).trim().isEmpty()) continue;
            List<String> values = fields(lines.get(line));
            if (values.size() != header.size()) {
                throw new IOException(path + " line " + (line + 1) + " has " + values.size()
                        + " fields; expected " + header.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), values.get(i));
            rows.add(row);
        }
        return rows;
    }

    static List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else quote = !quote;
            } else if (c == ',' && !quote) {
                fields.add(field.toString());
                field.setLength(0);
            } else field.append(c);
        }
        fields.add(field.toString());
        return fields;
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing required field " + key);
        }
        return value.trim();
    }

    private static Set<String> requested(String property) {
        Set<String> values = new HashSet<>();
        for (String value : System.getProperty(property, "").split(",")) {
            if (!value.trim().isEmpty()) values.add(value.trim());
        }
        return values;
    }

    private static Set<String> requestedWithDefault(String property, String fallback) {
        String value = System.getProperty(property, fallback);
        Set<String> values = new HashSet<>();
        for (String one : value.split(",")) if (!one.trim().isEmpty()) values.add(one.trim());
        return values;
    }

    private static boolean matches(Set<String> requested, String id) {
        for (String value : requested) if (id.contains(value)) return true;
        return false;
    }

    private static String key(Map<String, String> row, String method, String preprocessing) {
        return required(row, "series_id") + '|' + required(row, "motion_category") + '|'
                + required(row, "condition_instance") + '|' + method + '|' + preprocessing;
    }

    private static Set<String> completedKeys(Path output) throws IOException {
        Set<String> out = new HashSet<>();
        if (!Files.isRegularFile(output)) return out;
        for (Map<String, String> row : readCsv(output)) {
            out.add(required(row, "series_id") + '|' + required(row, "motion_category") + '|'
                    + required(row, "condition_instance") + '|' + required(row, "method_id") + '|'
                    + required(row, "preprocessing_arm"));
        }
        return out;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String concise(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) message = root.getClass().getSimpleName();
        return (root.getClass().getSimpleName() + ":" + message).replace('\n', ' ')
                .replace('\r', ' ');
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

    private static void selfTest() throws Exception {
        Map<String, String> row = new HashMap<>();
        row.put("condition_id", "test");
        row.put("panel", "test");
        row.put("gain_model", "log_linear");
        row.put("gain_start", "1");
        row.put("gain_end", "0.25");
        row.put("gain_min", "0.25");
        row.put("gain_max", "1");
        row.put("change_model", "none");
        row.put("change_fraction", "0");
        row.put("noise_db", "inf");
        row.put("replicates", "1");
        Condition condition = new Condition(row);
        assertClose(1.0, condition.gain(0), 0);
        assertClose(0.25, condition.gain(Benchmark.FRAMES - 1), 1e-12);
        List<String> parsed = fields("a,\"b,c\",\"d\"\"e\"");
        if (!parsed.equals(Arrays.asList("a", "b,c", "d\"e"))) {
            throw new AssertionError("CSV parser " + parsed);
        }
        float[][] frames = {{0, 1, 2, 3}, {10, 12, 14, 16}};
        ExternalPluginComparisonStacks.NormalizedFrames normalized =
                ExternalPluginComparisonStacks.commonNormalize(frames);
        if (normalized.fallback) throw new AssertionError("unexpected normalization fallback");
        assertClose(normalized.frames[0][0], normalized.frames[1][0], 1e-5);
        assertClose(normalized.frames[0][3], normalized.frames[1][3], 1e-5);
        if (methods().size() != 21) throw new AssertionError("expected 21 base internal methods");
        System.out.println("BenchmarkV3 self-test passed");
    }

    private static void assertClose(double expected, double actual, double tolerance) {
        if (!(Math.abs(expected - actual) <= tolerance)) {
            throw new AssertionError("expected " + expected + " found " + actual);
        }
    }
}
