/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import logratio.api.ImageType;
import logratio.api.LogRatioParameters;
import logratio.api.LogRatioRegistration;
import logratio.api.LogRatioResult;
import logratio.api.MotionType;
import logratio.api.PixelSelectionStrategy;
import logratio.api.Preprocessing;
import logratio.core.PairAligner;
import logratio.core.PairEstimator;
import logratio.core.PairScheduler;
import logratio.core.Transform;
import logratio.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Complete factorial sweep of the four dimensions the full automatic selector may choose.
 *
 * <p>This is a dedicated runner rather than an extension of the frozen 73-arm variation benchmark,
 * so {@code full_benchmark_2026-08-16} is never touched. Every recipe is a complete, explicit and
 * manually reproducible configuration: base pixel support, intensity restriction, estimation filter
 * and spatial pixel removal. Everything else comes from the category recommendation for the declared
 * image and motion types, with the sweep constants frozen before any result was inspected.
 */
public final class FullSelectorFactorialBenchmark {
    static final String RUN_ID = "full_selector_sweep_v1";

    /** Frozen for the accuracy sweep; tuned only after the accuracy winner is chosen. */
    static final double GRADIENT_MULTIPLIER = 0.5;
    static final double REMOVAL_PERCENT = 25.0;
    static final Preprocessing MASK_SCORING_FILTER = Preprocessing.NONE;
    static final int MAX_ITERATIONS = 25;
    static final int MAX_SAMPLES = 200_000;

    /** 96 log-ratio recipes on four dimensions, plus 16 estimator-axis candidates. */
    static final int EXPECTED_RECIPES = 128;

    static final String RESULT_HEADER = "experiment,image_series_class,series_id,motion_category,"
            + "condition,recipe_id,support,intensity_band,estimation_filter,spatial_mask,status,"
            + "frames,median_error_px,p90_error_px,max_error_px,total_seconds,cpu_seconds,details";

    /** Intensity restriction expressed as the two editable percentile fields. */
    enum Band {
        FULL("full", Double.NaN, Double.NaN, "no intensity restriction"),
        NO_TOP_10("no_top_10", Double.NaN, 90.0, "exclude the brightest 10 percent"),
        NO_TOP_25("no_top_25", Double.NaN, 75.0, "exclude the brightest 25 percent"),
        NO_BOTTOM_25("no_bottom_25", 25.0, Double.NaN, "exclude the dimmest 25 percent");

        final String id;
        final double floor;
        final double ceiling;
        final String description;

        Band(String id, double floor, double ceiling, String description) {
            this.id = id;
            this.floor = floor;
            this.ceiling = ceiling;
            this.description = description;
        }
    }

    /** Spatial removal expressed as the editable strategy and percentage fields. */
    enum Mask {
        NONE("none", PixelSelectionStrategy.NONE, "no spatial pixel removal"),
        LEAST_INFORMATIVE_25("least_informative_25", PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                "remove the least spatially informative 25 percent");

        final String id;
        final PixelSelectionStrategy strategy;
        final String description;

        Mask(String id, PixelSelectionStrategy strategy, String description) {
            this.id = id;
            this.strategy = strategy;
            this.description = description;
        }
    }

    /** One complete candidate recipe. The identifier names all four swept dimensions. */
    static final class Recipe {
        final String id;
        final PairEstimator.Kind estimator;
        final PairAligner.PixelSupport support;
        final Band band;
        final Preprocessing filter;
        final Mask mask;

        Recipe(PairAligner.PixelSupport support, Band band, Preprocessing filter, Mask mask) {
            this(PairEstimator.Kind.LOG_RATIO_FIT, support, band, filter, mask);
        }

        Recipe(PairEstimator.Kind estimator, PairAligner.PixelSupport support, Band band,
               Preprocessing filter, Mask mask) {
            this.estimator = estimator;
            this.support = support;
            this.band = band;
            this.filter = filter;
            this.mask = mask;
            // Identical to RegistrationRecipe.id(), and deliberately so: the sweep's folder names
            // and the selector's recipe identifiers have to be the same strings or the model cannot
            // name what it chose. The estimator appears only when it is not the default, which
            // leaves every folder the 96-recipe sweep already wrote exactly where it was.
            String prefix = estimator == PairEstimator.Kind.LOG_RATIO_FIT
                    ? "" : "estimator_" + estimator.id() + "__";
            this.id = prefix + "support_" + support.name().toLowerCase(Locale.ROOT)
                    + "__band_" + band.id
                    + "__filter_" + filter.name().toLowerCase(Locale.ROOT)
                    + "__mask_" + mask.id;
        }

        String description() {
            return "estimator " + estimator.id() + "; base pixel support " + support.name()
                    + " at gradient multiplier " + format(GRADIENT_MULTIPLIER) + "; "
                    + band.description + "; estimation filter " + filter.name() + "; "
                    + mask.description;
        }
    }

    private FullSelectorFactorialBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path root = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        if (!Files.isDirectory(root)) throw new IOException("missing " + root);

        List<Recipe> recipes = recipes();
        List<Recording> recordings = recordings(root);
        Path summary = root.resolve("summaries").resolve(RUN_ID);
        Files.createDirectories(summary);

        writeRecipeManifest(summary, recipes);
        writeManifest(project, summary, recordings, recipes);
        checkStageOneGates(recordings, recipes);
        if (Boolean.getBoolean("logratio.manifestOnly")) {
            System.out.printf(Locale.ROOT, "stage 1 complete: %d recordings, %d recipes, "
                    + "manifest written to %s%n", recordings.size(), recipes.size(), summary);
            return;
        }

        Set<String> onlySeries = requested("logratio.onlySeries");
        Set<String> onlyRecipes = requested("logratio.onlyRecipe");
        boolean rewrite = Boolean.getBoolean("logratio.rewrite");
        boolean writeImages = !Boolean.getBoolean("logratio.noImages");

        List<Recording> selected = new ArrayList<>();
        for (Recording recording : recordings) {
            if (onlySeries.isEmpty() || onlySeries.contains(recording.series)) selected.add(recording);
        }
        List<Recipe> selectedRecipes = new ArrayList<>();
        for (Recipe recipe : recipes) {
            if (onlyRecipes.isEmpty() || onlyRecipes.contains(recipe.id)) selectedRecipes.add(recipe);
        }

        rejectInvalidConfigurations(selected, selectedRecipes);
        Totals totals = new Totals();
        totals.planned = selected.size() * selectedRecipes.size();
        checkDiskSpace(root, summary, selected, selectedRecipes, writeImages, rewrite);

        // One recording at a time, deliberately. Fanning out across recordings was built and
        // measured on 2026-08-18 and made the sweep about 5 percent slower, because a single
        // registration already spreads 209 independent pair alignments over every core; a second
        // level of fan-out on top of a saturated first one only adds scheduling and cache pressure.
        // The measurement is in docs/performance_optimisation_findings.md so nobody rebuilds it.
        long start = System.nanoTime();
        for (Recording recording : selected) {
            ImagePlus input = IJ.openImage(recording.input.toString());
            if (input == null) {
                for (Recipe recipe : selectedRecipes) {
                    totals.attempted++;
                    Path output = recording.folder.resolve(RUN_ID).resolve(recipe.id);
                    recordFailure(recording, recipe, output, "could not open " + recording.input,
                            totals);
                }
                continue;
            }
            try {
                for (Recipe recipe : selectedRecipes) {
                    totals.attempted++;
                    Path output = recording.folder.resolve(RUN_ID).resolve(recipe.id);
                    if (!rewrite && complete(output, writeImages)) {
                        totals.resumed++;
                        continue;
                    }
                    try {
                        runRecipe(recording, input, recipe, output, writeImages);
                        totals.completed++;
                    } catch (RuntimeException | IOException error) {
                        recordFailure(recording, recipe, output,
                                recording + " / " + recipe.id + ": " + rootMessage(error), totals);
                    }
                    progress(totals, start);
                }
            } finally {
                input.close();
            }
        }

        List<String> structural = audit(root, summary, selected, selectedRecipes, writeImages);
        System.out.printf(Locale.ROOT, "full selector sweep: attempted %d, completed %d, resumed %d,"
                        + " failed %d, structural errors %d%n",
                totals.attempted, totals.completed, totals.resumed, totals.failures.size(),
                structural.size());
        if (!structural.isEmpty()) {
            throw new IOException("artifact audit found structural errors:\n"
                    + String.join("\n", structural));
        }
    }

    /** The complete three by four by four by two candidate set. */
    static List<Recipe> recipes() {
        List<Recipe> out = new ArrayList<>();
        PairAligner.PixelSupport[] supports = {
                PairAligner.PixelSupport.ALL,
                PairAligner.PixelSupport.GRADIENT,
                PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT};
        Preprocessing[] filters = {
                Preprocessing.NONE, Preprocessing.GAUSSIAN_0_7,
                Preprocessing.GAUSSIAN_1_0, Preprocessing.MEDIAN_3X3};
        for (PairAligner.PixelSupport support : supports) {
            for (Band band : Band.values()) {
                for (Preprocessing filter : filters) {
                    for (Mask mask : Mask.values()) {
                        out.add(new Recipe(support, band, filter, mask));
                    }
                }
            }
        }
        // The estimator axis: area correlation over every band and filter. Pixel support and the
        // spatial mask are settings of the log-ratio fit, so they are held neutral rather than
        // swept -- sweeping a setting the estimator ignores would multiply the run time by six and
        // measure nothing.
        for (Band band : Band.values()) {
            for (Preprocessing filter : filters) {
                out.add(new Recipe(PairEstimator.Kind.AREA_CORRELATION,
                        PairAligner.PixelSupport.ALL, band, filter, Mask.NONE));
            }
        }
        // Sixteen more for Stage 3 of docs/newton_refinement_plan.md: the same grid with the
        // Gauss-Newton refinement. Additive -- every identifier carries the estimator name, so the
        // 112 folders the frozen sweep already wrote keep their names and their contents, and a
        // resumed run costs only the sixteen that are missing.
        for (Band band : Band.values()) {
            for (Preprocessing filter : filters) {
                out.add(new Recipe(PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                        PairAligner.PixelSupport.ALL, band, filter, Mask.NONE));
            }
        }
        if (out.size() != EXPECTED_RECIPES) {
            throw new IllegalStateException("expected " + EXPECTED_RECIPES + " recipes, got "
                    + out.size());
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Recipe recipe : out) {
            if (!ids.add(recipe.id)) throw new IllegalStateException("duplicate recipe " + recipe.id);
        }
        return out;
    }

    /** Build the complete explicit parameter bundle for one recording and recipe. */
    static LogRatioParameters parameters(Recording recording, Recipe recipe) {
        LogRatioParameters recommended = LogRatioParameters.builder()
                .recommendation(imageType(recording.imageClass), motionType(recording.motion))
                .autoMaxShift(false)
                .maxShift(knownMaxShift(recording.motion))
                .crop(false)
                .interpolation(Warper.Interpolation.NONE)
                .build();
        return recommended.toBuilder()
                .useRecommendation(false)
                .automaticFilterSelection(false)
                .estimator(recipe.estimator)
                .pixelSupport(recipe.support)
                .gradientFraction(GRADIENT_MULTIPLIER)
                .floorPercentile(recipe.band.floor)
                .ceilingPercentile(recipe.band.ceiling)
                .preprocessing(recipe.filter)
                .pixelSelectionStrategy(recipe.mask.strategy)
                .pixelSelectionPreprocessing(MASK_SCORING_FILTER)
                .pixelRemovalPercent(REMOVAL_PERCENT)
                .removeOffset(false)
                .maxIterations(MAX_ITERATIONS)
                .maxSamples(MAX_SAMPLES)
                .build();
    }

    private static void runRecipe(Recording recording, ImagePlus input, Recipe recipe, Path output,
                                  boolean writeImages) throws IOException {
        Files.createDirectories(output);
        LogRatioParameters parameters = parameters(recording, recipe);
        long cpuStart = processCpuTime();
        long start = System.nanoTime();
        LogRatioResult result = LogRatioRegistration.register(input, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double seconds = (System.nanoTime() - start) / 1e9;
        double cpuSeconds = (processCpuTime() - cpuStart) / 1e9;
        try {
            double[] metrics = controlledMetrics(result.registration().cumulative, recording.motion);
            if (writeImages) {
                IJ.saveAsTiff(result.correctedImage(), output.resolve("corrected.tif").toString());
            }
            writeTransforms(output.resolve("transforms.csv"), result.registration().cumulative,
                    recording.motion);
            writeSettings(output.resolve("settings.csv"), recording, recipe, parameters);
            String row = csv("controlled") + ',' + csv(recording.imageClass) + ','
                    + csv(recording.series) + ',' + csv(recording.motion) + ','
                    + csv(recording.condition) + ',' + csv(recipe.id) + ','
                    + csv(recipe.support.name()) + ',' + csv(recipe.band.id) + ','
                    + csv(recipe.filter.name()) + ',' + csv(recipe.mask.id) + ",ok,"
                    + input.getStackSize() + ',' + format(metrics[0]) + ',' + format(metrics[1])
                    + ',' + format(metrics[2]) + ',' + format(seconds) + ',' + format(cpuSeconds)
                    + ',' + csv(recipe.description());
            Files.write(output.resolve("comparison.csv"),
                    (RESULT_HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(output.resolve("run_failure.txt"));
        } finally {
            result.correctedImage().changes = false;
            result.close();
        }
    }

    private static void recordFailure(Recording recording, Recipe recipe, Path output,
                                      String message, Totals totals) throws IOException {
        Files.createDirectories(output);
        Files.write(output.resolve("run_failure.txt"),
                (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        String row = csv("controlled") + ',' + csv(recording.imageClass) + ','
                + csv(recording.series) + ',' + csv(recording.motion) + ','
                + csv(recording.condition) + ',' + csv(recipe.id) + ','
                + csv(recipe.support.name()) + ',' + csv(recipe.band.id) + ','
                + csv(recipe.filter.name()) + ',' + csv(recipe.mask.id) + ",failed,"
                + recording.frames + ",NaN,NaN,NaN,NaN,NaN," + csv(message);
        Files.write(output.resolve("comparison.csv"),
                (RESULT_HEADER + '\n' + row + '\n').getBytes(StandardCharsets.UTF_8));
        totals.failures.add(message);
        System.err.println("FAILED " + message);
    }

    private static void writeSettings(Path path, Recording recording, Recipe recipe,
                                      LogRatioParameters p) throws IOException {
        StringBuilder out = new StringBuilder("setting,value\n");
        setting(out, "run_id", RUN_ID);
        setting(out, "recipe_id", recipe.id);
        setting(out, "image_series_class", recording.imageClass);
        setting(out, "series_id", recording.series);
        setting(out, "motion_category", recording.motion);
        setting(out, "condition", recording.condition);
        setting(out, "image_type", p.imageType.name());
        setting(out, "motion_type", p.motionType.name());
        setting(out, "use_recommendation", Boolean.toString(p.useRecommendation));
        setting(out, "automatic_filter_selection", Boolean.toString(p.automaticFilterSelection));
        setting(out, "pixel_support", p.pixelSupport.name());
        setting(out, "gradient_multiplier", format(p.gradientFraction));
        setting(out, "floor_percentile", optional(p.floorPercentile));
        setting(out, "ceiling_percentile", optional(p.ceilingPercentile));
        setting(out, "preprocessing", p.preprocessing.name());
        setting(out, "pixel_selection_strategy", p.pixelSelectionStrategy.name());
        setting(out, "pixel_selection_preprocessing", p.pixelSelectionPreprocessing.name());
        setting(out, "pixel_removal_percent", format(p.pixelRemovalPercent));
        setting(out, "norm", p.norm.name());
        setting(out, "reference", p.reference.name());
        setting(out, "reference_frame", Integer.toString(p.referenceFrame));
        setting(out, "lags", join(p.lags));
        setting(out, "template_window", Integer.toString(p.templateWindow));
        setting(out, "estimation_scale", format(p.estimationScale));
        setting(out, "epsilon", format(p.epsilon));
        setting(out, "remove_offset", Boolean.toString(p.removeOffset));
        setting(out, "offset_percentile", format(p.offsetPercentile));
        setting(out, "auto_max_shift", Boolean.toString(p.autoMaxShift));
        setting(out, "max_shift", format(p.maxShift));
        setting(out, "outlier_mads", format(p.outlierMads));
        setting(out, "max_iterations", Integer.toString(p.maxIterations));
        setting(out, "max_samples", Integer.toString(p.maxSamples));
        setting(out, "min_valid_fraction", format(p.minValidFraction));
        setting(out, "channel", Integer.toString(p.channel));
        setting(out, "slice", Integer.toString(p.slice));
        setting(out, "interpolation", p.interpolation.name());
        setting(out, "crop", Boolean.toString(p.crop));
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void setting(StringBuilder out, String key, String value) {
        out.append(csv(key)).append(',').append(csv(value)).append('\n');
    }

    // ---------------------------------------------------------------- stage 1

    private static void writeRecipeManifest(Path summary, List<Recipe> recipes) throws IOException {
        StringBuilder out = new StringBuilder("run_id,recipe_index,recipe_id,base_pixel_support,"
                + "gradient_multiplier,intensity_band,floor_percentile,ceiling_percentile,"
                + "estimation_filter,spatial_removal_strategy,removal_percent,mask_scoring_filter,"
                + "max_iterations,max_samples,description\n");
        for (int i = 0; i < recipes.size(); i++) {
            Recipe recipe = recipes.get(i);
            out.append(csv(RUN_ID)).append(',').append(i + 1).append(',').append(csv(recipe.id))
                    .append(',').append(csv(recipe.support.name())).append(',')
                    .append(format(GRADIENT_MULTIPLIER)).append(',').append(csv(recipe.band.id))
                    .append(',').append(csv(optional(recipe.band.floor))).append(',')
                    .append(csv(optional(recipe.band.ceiling))).append(',')
                    .append(csv(recipe.filter.name())).append(',')
                    .append(csv(recipe.mask.strategy.name())).append(',')
                    .append(format(REMOVAL_PERCENT)).append(',')
                    .append(csv(MASK_SCORING_FILTER.name())).append(',').append(MAX_ITERATIONS)
                    .append(',').append(MAX_SAMPLES).append(',').append(csv(recipe.description()))
                    .append('\n');
        }
        Files.write(summary.resolve("recipe_manifest.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeManifest(Path project, Path summary, List<Recording> recordings,
                                      List<Recipe> recipes) throws IOException {
        StringBuilder out = new StringBuilder(
                "record_type,key,image_series_class,series_id,motion_category,condition,value,detail\n");

        for (Map.Entry<String, String> entry : environment(project, recipes).entrySet()) {
            out.append(csv("environment")).append(',').append(csv(entry.getKey()))
                    .append(",,,,,").append(csv(entry.getValue())).append(',').append(csv("")).append('\n');
        }

        Set<String> series = new LinkedHashSet<>();
        for (Recording recording : recordings) {
            if (series.add(recording.imageClass + '/' + recording.series)) {
                out.append(csv("source_series")).append(',').append(csv(recording.series))
                        .append(',').append(csv(recording.imageClass)).append(',')
                        .append(csv(recording.series)).append(",,,")
                        .append(csv(recording.independentGroup)).append(',')
                        .append(csv("independent group used for held-out folds")).append('\n');
            }
        }

        for (Recording recording : recordings) {
            out.append(csv("controlled_recording")).append(',').append(csv(recording.key()))
                    .append(',').append(csv(recording.imageClass)).append(',')
                    .append(csv(recording.series)).append(',').append(csv(recording.motion))
                    .append(',').append(csv(recording.condition)).append(',')
                    .append(csv(recording.inputSha256)).append(',')
                    .append(csv(recording.frames + " frames; " + recording.inputBytes
                            + " bytes; " + project.relativize(recording.input).toString()
                            .replace('\\', '/'))).append('\n');
        }

        for (String[] baseline : BASELINES) {
            out.append(csv("baseline")).append(',').append(csv(baseline[0])).append(",,,,,")
                    .append(csv(baseline[1])).append(',').append(csv(baseline[2])).append('\n');
        }

        Files.write(summary.resolve("manifest.csv"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Copied verbatim from the frozen 2026-08-16 arm summary. Nothing here is rerun or overwritten;
     * these are the standing comparison points the new selector has to beat or match.
     */
    private static final String[][] BASELINES = {
            {"current_category_recommendation", "0.025545 px mean median; 0.811376 s mean",
                    "full_benchmark_2026-08-16 arm 033_current_category_recommendation"},
            {"current_automatic_filter_selector", "0.025582 px mean median; 1.479984 s mean",
                    "full_benchmark_2026-08-16 arm 035_automatic_filter_selector"},
            {"base_without_addons", "0.368773 px mean median; 0.620267 s mean",
                    "full_benchmark_2026-08-16 arm 034_base_model_without_addons"},
            {"older_automatic_information_selector", "1.991650 px mean median; 6.789563 s mean",
                    "automatic_information_summary_2026-08-12 frozen information selector"}};

    private static Map<String, String> environment(Path project, List<Recipe> recipes)
            throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("run_id", RUN_ID);
        out.put("project_root", project.toString());
        out.put("recipe_count", Integer.toString(recipes.size()));
        out.put("recipe_manifest", "recipe_manifest.csv");
        out.put("recipe_list_sha256", sha256(recipeDigestInput(recipes)));
        out.put("code_commit", codeCommit(project));
        out.put("source_tree_sha256", sourceTreeSha256(project));
        out.put("java_version", System.getProperty("java.version", "unknown"));
        out.put("java_vendor", System.getProperty("java.vendor", "unknown"));
        out.put("java_vm", System.getProperty("java.vm.name", "unknown") + " "
                + System.getProperty("java.vm.version", ""));
        out.put("imagej_version", imageJVersion());
        out.put("fiji_installation", fijiInstallation());
        out.put("operating_system", System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", ""));
        out.put("os_architecture", System.getProperty("os.arch", "unknown"));
        out.put("processor", processorName());
        out.put("available_processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        out.put("max_heap_bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        out.put("gradient_multiplier_fixed", format(GRADIENT_MULTIPLIER));
        out.put("removal_percent_fixed", format(REMOVAL_PERCENT));
        out.put("mask_scoring_filter_fixed", MASK_SCORING_FILTER.name());
        out.put("max_iterations_fixed", Integer.toString(MAX_ITERATIONS));
        out.put("max_samples_fixed", Integer.toString(MAX_SAMPLES));
        return out;
    }

    private static byte[] recipeDigestInput(List<Recipe> recipes) {
        StringBuilder out = new StringBuilder();
        for (Recipe recipe : recipes) out.append(recipe.id).append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String codeCommit(Path project) {
        Path head = project.resolve(".git/HEAD");
        if (!Files.isRegularFile(head)) {
            return "not a git repository; identity carried by source_tree_sha256";
        }
        try {
            String text = new String(Files.readAllBytes(head), StandardCharsets.UTF_8).trim();
            if (!text.startsWith("ref:")) return text;
            Path ref = project.resolve(".git").resolve(text.substring(4).trim());
            return Files.isRegularFile(ref)
                    ? new String(Files.readAllBytes(ref), StandardCharsets.UTF_8).trim() : text;
        } catch (IOException error) {
            return "unreadable git HEAD";
        }
    }

    private static String sourceTreeSha256(Path project) throws IOException {
        List<Path> files = new ArrayList<>();
        Path source = project.resolve("src");
        if (Files.isDirectory(source)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(files::add);
            }
        }
        Path pom = project.resolve("pom.xml");
        if (Files.isRegularFile(pom)) files.add(pom);
        files.sort(Comparator.comparing(path -> project.relativize(path).toString().replace('\\', '/')));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                digest.update(project.relativize(file).toString().replace('\\', '/')
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String imageJVersion() {
        try {
            return "ImageJ " + IJ.getFullVersion();
        } catch (RuntimeException | LinkageError error) {
            return "ImageJ version unavailable";
        }
    }

    private static String fijiInstallation() {
        String[] candidates = {
                System.getProperty("fiji.dir", ""),
                System.getProperty("imagej.dir", ""),
                Paths.get(System.getProperty("user.home", ""), "Fiji.app").toString()};
        for (String candidate : candidates) {
            if (candidate.isEmpty()) continue;
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path.toString() + " (present; this sweep runs the plugin classes directly)";
            }
        }
        return "no Fiji installation detected; this sweep runs the plugin classes directly";
    }

    private static String processorName() {
        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        String architecture = System.getenv("PROCESSOR_ARCHITECTURE");
        if (identifier == null && architecture == null) return "unknown";
        return (identifier == null ? "" : identifier) + (architecture == null ? "" : " / " + architecture);
    }

    private static void checkStageOneGates(List<Recording> recordings, List<Recipe> recipes) {
        Set<String> series = new LinkedHashSet<>();
        Map<String, Set<String>> seriesByClass = new LinkedHashMap<>();
        Map<String, Set<String>> motionsBySeries = new LinkedHashMap<>();
        for (Recording recording : recordings) {
            series.add(recording.series);
            seriesByClass.computeIfAbsent(recording.imageClass, key -> new LinkedHashSet<>())
                    .add(recording.series);
            motionsBySeries.computeIfAbsent(recording.series, key -> new LinkedHashSet<>())
                    .add(recording.motion);
        }
        if (series.size() != 20) {
            throw new IllegalStateException("expected 20 source series, found " + series.size());
        }
        if (seriesByClass.size() != 5) {
            throw new IllegalStateException("expected 5 image types, found " + seriesByClass.size());
        }
        for (Map.Entry<String, Set<String>> entry : seriesByClass.entrySet()) {
            if (entry.getValue().size() != 4) {
                throw new IllegalStateException("image type " + entry.getKey() + " has "
                        + entry.getValue().size() + " series; balanced coverage needs 4");
            }
        }
        for (Map.Entry<String, Set<String>> entry : motionsBySeries.entrySet()) {
            if (entry.getValue().size() != 4) {
                throw new IllegalStateException("series " + entry.getKey() + " has "
                        + entry.getValue().size() + " movement profiles; expected 4");
            }
        }
        if (recordings.size() != 80) {
            throw new IllegalStateException("expected 80 controlled recordings, found "
                    + recordings.size());
        }
        if (recipes.size() != EXPECTED_RECIPES) {
            throw new IllegalStateException("expected " + EXPECTED_RECIPES + " recipes, found "
                    + recipes.size());
        }
    }

    // ---------------------------------------------------------------- stage 2 support

    private static void rejectInvalidConfigurations(List<Recording> recordings, List<Recipe> recipes) {
        List<String> invalid = new ArrayList<>();
        for (Recording recording : recordings) {
            for (Recipe recipe : recipes) {
                try {
                    parameters(recording, recipe);
                } catch (RuntimeException error) {
                    invalid.add(recording + " / " + recipe.id + ": " + rootMessage(error));
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("rejected invalid configurations before execution:\n"
                    + String.join("\n", invalid));
        }
    }

    private static void checkDiskSpace(Path root, Path summary, List<Recording> recordings,
                                       List<Recipe> recipes, boolean writeImages, boolean rewrite)
            throws IOException {
        long perRecipe = 0;
        for (Recording recording : recordings) {
            perRecipe = Math.max(perRecipe, recording.inputBytes);
        }
        // corrected.tif matches the input stack geometry in 32-bit float; the three text artifacts
        // are a few kilobytes. Two times the input size is a deliberately generous per-recipe cost.
        long perFolder = (writeImages ? 2 * perRecipe : 0) + 64L * 1024;
        int outstanding = 0;
        for (Recording recording : recordings) {
            for (Recipe recipe : recipes) {
                Path output = recording.folder.resolve(RUN_ID).resolve(recipe.id);
                if (rewrite || !complete(output, writeImages)) outstanding++;
            }
        }
        long required = perFolder * outstanding;
        FileStore store = Files.getFileStore(root);
        long usable = store.getUsableSpace();
        StringBuilder out = new StringBuilder("measure,value\n");
        out.append(csv("outstanding_recipe_folders")).append(',').append(outstanding).append('\n');
        out.append(csv("estimated_bytes_per_folder")).append(',').append(perFolder).append('\n');
        out.append(csv("estimated_bytes_required")).append(',').append(required).append('\n');
        out.append(csv("estimated_gigabytes_required")).append(',')
                .append(format(required / 1073741824.0)).append('\n');
        out.append(csv("usable_bytes_on_volume")).append(',').append(usable).append('\n');
        out.append(csv("usable_gigabytes_on_volume")).append(',')
                .append(format(usable / 1073741824.0)).append('\n');
        out.append(csv("volume")).append(',').append(csv(store.toString())).append('\n');
        Files.write(summary.resolve("disk_space_estimate.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT,
                "disk estimate: %d outstanding folders need about %.1f GB; %.1f GB usable%n",
                outstanding, required / 1073741824.0, usable / 1073741824.0);
        if (usable < required + (2L << 30)) {
            throw new IOException("refusing to start: the complete artifact set needs about "
                    + format(required / 1073741824.0) + " GB plus a 2 GB margin, but only "
                    + format(usable / 1073741824.0) + " GB is usable on " + store);
        }
    }

    private static void progress(Totals totals, long start) {
        int done = totals.completed + totals.resumed + totals.failures.size();
        if (done == 0 || done % 48 != 0) return;
        double elapsed = (System.nanoTime() - start) / 1e9;
        // Resumed folders cost almost nothing, so pace the estimate on work actually executed.
        int executed = totals.completed + totals.failures.size();
        double remaining = executed == 0 ? 0
                : (elapsed / executed) * Math.max(0, totals.planned - done);
        System.out.printf(Locale.ROOT,
                "progress %d / %d (completed %d, resumed %d, failed %d); elapsed %.0f s; "
                        + "estimated %.0f s remaining%n",
                done, totals.planned, totals.completed, totals.resumed, totals.failures.size(),
                elapsed, remaining);
    }

    static List<String> audit(Path root, Path summary, List<Recording> recordings,
                                      List<Recipe> recipes, boolean writeImages) throws IOException {
        List<String> errors = new ArrayList<>();
        StringBuilder out = new StringBuilder("image_series_class,series_id,motion_category,"
                + "condition,recipe_id,status,settings_csv,transforms_csv,comparison_csv,"
                + "corrected_tif,problem\n");
        for (Recording recording : recordings) {
            for (Recipe recipe : recipes) {
                Path folder = recording.folder.resolve(RUN_ID).resolve(recipe.id);
                boolean failed = Files.isRegularFile(folder.resolve("run_failure.txt"));
                boolean settings = Files.isRegularFile(folder.resolve("settings.csv"));
                boolean transforms = Files.isRegularFile(folder.resolve("transforms.csv"));
                boolean comparison = Files.isRegularFile(folder.resolve("comparison.csv"));
                boolean corrected = Files.isRegularFile(folder.resolve("corrected.tif"));
                String problem = "";
                if (!comparison) {
                    problem = "missing comparison.csv";
                } else if (!failed && !(settings && transforms && (!writeImages || corrected))) {
                    problem = "successful recipe is missing one or more artifacts";
                } else {
                    List<String> lines = Files.readAllLines(folder.resolve("comparison.csv"),
                            StandardCharsets.UTF_8);
                    if (lines.size() < 2 || !lines.get(0).equals(RESULT_HEADER)) {
                        problem = "comparison.csv is corrupt";
                    }
                }
                if (!problem.isEmpty()) errors.add(recording + " / " + recipe.id + ": " + problem);
                out.append(csv(recording.imageClass)).append(',').append(csv(recording.series))
                        .append(',').append(csv(recording.motion)).append(',')
                        .append(csv(recording.condition)).append(',').append(csv(recipe.id))
                        .append(',').append(csv(failed ? "failed" : "ok")).append(',')
                        .append(settings).append(',').append(transforms).append(',')
                        .append(comparison).append(',').append(corrected).append(',')
                        .append(csv(problem)).append('\n');
            }
        }
        Files.write(summary.resolve("artifact_audit.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
        return errors;
    }

    static boolean complete(Path output, boolean writeImages) {
        Path comparison = output.resolve("comparison.csv");
        if (!Files.isRegularFile(comparison)) return false;
        try {
            List<String> lines = Files.readAllLines(comparison, StandardCharsets.UTF_8);
            if (lines.size() < 2 || !lines.get(0).equals(RESULT_HEADER)) return false;
            // A recorded failure is a complete, explicit result; rerunning it on resume would hide
            // an unstable configuration behind a lucky repeat.
            if (Files.isRegularFile(output.resolve("run_failure.txt"))) return true;
        } catch (IOException error) {
            return false;
        }
        return Files.isRegularFile(output.resolve("settings.csv"))
                && Files.isRegularFile(output.resolve("transforms.csv"))
                && (!writeImages || Files.isRegularFile(output.resolve("corrected.tif")));
    }

    // ---------------------------------------------------------------- shared helpers

    static List<Recording> recordings(Path root) throws IOException {
        Map<String, String> groups = new LinkedHashMap<>();
        Path manifest = root.getParent().getParent().getParent()
                .resolve("benchmark_v2_series_manifest.csv");
        if (Files.isRegularFile(manifest)) {
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().isEmpty()) continue;
                String[] row = lines.get(i).split(",", -1);
                groups.put(row[0], row[2]);
            }
        }
        List<Path> inputs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName().toString().equals("00_input_uncorrected.tif"))
                    .forEach(inputs::add);
        }
        inputs.sort(Comparator.comparing(Path::toString));
        List<Recording> out = new ArrayList<>();
        for (Path input : inputs) {
            Path relative = root.relativize(input);
            if (relative.getNameCount() < 4) throw new IOException("unexpected input path " + input);
            String series = relative.getName(1).toString();
            out.add(new Recording(relative.getName(0).toString(), series,
                    relative.getName(2).toString(), relative.getName(3).toString(),
                    input.getParent(), input, Benchmark.FRAMES,
                    groups.getOrDefault(series, series),
                    sha256(Files.readAllBytes(input)), Files.size(input)));
        }
        return out;
    }

    static double[] controlledMetrics(Transform[] transforms, String motion) {
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

    private static void writeTransforms(Path path, Transform[] transforms, String motion)
            throws IOException {
        int[] x = new int[Benchmark.FRAMES];
        int[] y = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(x, y);
        StringBuilder out = new StringBuilder(
                "frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n");
        for (int i = 0; i < transforms.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            double tx = x[i] / (double) Benchmark.FINE;
            double ty = y[i] / (double) Benchmark.FINE;
            out.append(i + 1).append(',').append(format(transform.dx)).append(',')
                    .append(format(transform.dy)).append(',').append(format(transform.theta))
                    .append(',').append(format(tx)).append(',').append(format(ty)).append(',')
                    .append(format(Math.hypot(transform.dx - tx, transform.dy - ty)))
                    .append(",ok\n");
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    static ImageType imageType(String value) {
        switch (value) {
            case "PHASE": return ImageType.PHASE_CONTRAST;
            case "BRIGHTFIELD_DIC": return ImageType.BRIGHTFIELD_DIC;
            case "DENSE_FLUOR": return ImageType.DENSE_FLUORESCENCE;
            case "SPARSE_LOWLIGHT": return ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
            case "FIDUCIAL_STATIC": return ImageType.FIDUCIAL_STATIC;
            default: throw new IllegalArgumentException("unknown image class " + value);
        }
    }

    static MotionType motionType(String value) {
        if (value.contains("INTERMITTENT") || value.contains("JUMP")) return MotionType.INTERMITTENT_JUMPS;
        if (value.contains("STEADY") || value.contains("DIRECTIONAL")) return MotionType.STEADY_DIRECTIONAL_DRIFT;
        if (value.contains("CURVED") || value.contains("OSCILLATING")) return MotionType.CURVED_OSCILLATING_DRIFT;
        return MotionType.SUBPIXEL_RANDOM_WALK;
    }

    static double knownMaxShift(String motion) {
        int[] x = new int[Benchmark.FRAMES];
        int[] y = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(x, y);
        double reach = 0;
        for (int i = 0; i < x.length; i++) {
            reach = Math.max(reach, Math.hypot(x[i], y[i]) / Benchmark.FINE);
        }
        return 2 * reach + 8;
    }

    private static Set<String> requested(String property) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : System.getProperty(property, "").split(",")) {
            if (!value.trim().isEmpty()) out.add(value.trim());
        }
        return out;
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return System.nanoTime();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    static String sha256(byte[] content) throws IOException {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value));
        return out.toString();
    }

    static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String optional(double value) {
        return Double.isNaN(value) ? "off" : format(value);
    }

    private static String join(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(';');
            out.append(values[i]);
        }
        return out.toString();
    }

    static String csv(String value) {
        if (value == null) value = "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        out.add(field.toString());
        return out;
    }

    /** One controlled recording: a source series moved along one fixed camera path. */
    static final class Recording {
        final String imageClass;
        final String series;
        final String motion;
        final String condition;
        final Path folder;
        final Path input;
        final int frames;
        final String independentGroup;
        final String inputSha256;
        final long inputBytes;

        Recording(String imageClass, String series, String motion, String condition, Path folder,
                  Path input, int frames, String independentGroup, String inputSha256,
                  long inputBytes) {
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.condition = condition;
            this.folder = folder;
            this.input = input;
            this.frames = frames;
            this.independentGroup = independentGroup;
            this.inputSha256 = inputSha256;
            this.inputBytes = inputBytes;
        }

        String key() {
            return imageClass + '/' + series + '/' + motion + '/' + condition;
        }

        @Override public String toString() {
            return key();
        }
    }

    private static final class Totals {
        int planned;
        int attempted;
        int completed;
        int resumed;
        final List<String> failures = new ArrayList<>();
    }

}
