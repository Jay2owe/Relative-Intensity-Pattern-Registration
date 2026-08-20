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
import logratio.api.LogRatioRegistration;
import logratio.api.MotionType;
import logratio.api.Preprocessing;
import logratio.core.FrameSource;
import logratio.core.LogPlane;
import logratio.core.PairAligner;
import logratio.core.PairScheduler;
import logratio.core.Reconciler;
import logratio.core.Registration;
import logratio.core.Transform;
import logratio.core.Warper;

import java.io.IOException;
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
import java.util.TreeSet;

/**
 * Controlled benchmark in which a filtered copy chooses support but raw pixels drive the fit.
 *
 * <p>The distinction is deliberate. Filtering may make stable spatial or temporal evidence easier to
 * identify without changing the log-ratio values, gradients or output pixels used by registration.
 * Every selected arm pays for the provisional registration, filtered scoring and raw masked refit.
 */
public final class FilteredMaskPixelSelectionBenchmark {

    private static final int[] LAGS = {1, 2, 4, 8, 16};
    private static final double EPSILON = 1.0;

    enum FilterArm {
        NONE("none", "No scoring filter", Preprocessing.NONE),
        GAUSSIAN_0_7("gaussian_0_7", "Gaussian smoothing, 0.7 pixel", Preprocessing.GAUSSIAN_0_7),
        GAUSSIAN_1_0("gaussian_1_0", "Gaussian smoothing, 1.0 pixel", Preprocessing.GAUSSIAN_1_0),
        GAUSSIAN_1_4("gaussian_1_4", "Gaussian smoothing, 1.4 pixels", Preprocessing.GAUSSIAN_1_4),
        MEDIAN_3X3("median_3x3", "Three by three median denoising", Preprocessing.MEDIAN_3X3),
        ANSCOMBE("anscombe", "Photon-noise stabilisation", Preprocessing.ANSCOMBE),
        ANSCOMBE_GAUSSIAN_1_0("anscombe_gaussian_1_0",
                "Photon-noise stabilisation plus Gaussian smoothing",
                Preprocessing.ANSCOMBE_GAUSSIAN_1_0),
        UNSHARP_0_5("unsharp_0_5", "Mild sharpening", Preprocessing.UNSHARP_0_5);

        final String id;
        final String label;
        final Preprocessing preprocessing;

        FilterArm(String id, String label, Preprocessing preprocessing) {
            this.id = id;
            this.label = label;
            this.preprocessing = preprocessing;
        }
    }

    enum MaskStrategy {
        REMOVE_MOST_UNSTABLE("remove_most_unstable", "Remove most frame-to-frame unstable",
                LagPixelSelector.Score.INSTABILITY, true, false),
        REMOVE_LEAST_UNSTABLE("remove_least_unstable", "Remove least frame-to-frame unstable",
                LagPixelSelector.Score.INSTABILITY, false, false),
        REMOVE_MOST_LAG_GROWTH("remove_most_lag_growth", "Remove strongest growth with frame gap",
                LagPixelSelector.Score.LAG_GROWTH, true, false),
        REMOVE_LEAST_LAG_GROWTH("remove_least_lag_growth", "Remove weakest growth with frame gap",
                LagPixelSelector.Score.LAG_GROWTH, false, false),
        REMOVE_LEAST_INFORMATIVE("remove_least_informative", "Remove weakest two-axis spatial information",
                LagPixelSelector.Score.INFORMATION, false, false),
        REMOVE_MOST_INFORMATIVE("remove_most_informative", "Remove strongest two-axis spatial information",
                LagPixelSelector.Score.INFORMATION, true, false),
        REMOVE_LOWEST_ANCHOR_TRUST("remove_lowest_anchor_trust", "Remove lowest combined anchor trust",
                LagPixelSelector.Score.ANCHOR_TRUST, false, false),
        REMOVE_HIGHEST_ANCHOR_TRUST("remove_highest_anchor_trust", "Remove highest combined anchor trust",
                LagPixelSelector.Score.ANCHOR_TRUST, true, false),
        STRATIFIED_LOWEST_ANCHOR_TRUST("stratified_lowest_anchor_trust",
                "Remove lowest anchor trust within spatial and edge-direction groups",
                LagPixelSelector.Score.ANCHOR_TRUST, false, true, false),
        FAST_REMOVE_LEAST_INFORMATIVE("fast_remove_least_informative",
                "Remove weakest two-axis information from the reference frame only",
                LagPixelSelector.Score.INFORMATION, false, false, true);

        final String id;
        final String label;
        final LagPixelSelector.Score score;
        final boolean removeHighest;
        final boolean stratified;
        final boolean spatialOnly;

        MaskStrategy(String id, String label, LagPixelSelector.Score score,
                     boolean removeHighest, boolean stratified) {
            this(id, label, score, removeHighest, stratified, false);
        }

        MaskStrategy(String id, String label, LagPixelSelector.Score score,
                     boolean removeHighest, boolean stratified, boolean spatialOnly) {
            this.id = id;
            this.label = label;
            this.score = score;
            this.removeHighest = removeHighest;
            this.stratified = stratified;
            this.spatialOnly = spatialOnly;
        }
    }

    private FilteredMaskPixelSelectionBenchmark() {
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
        Set<String> requestedFilters = requestedSet("logratio.onlyFilter");
        Set<String> requestedStrategies = requestedSet("logratio.onlyStrategy");
        double[] percentages = percentages();
        boolean rewrite = Boolean.getBoolean("logratio.rewrite");
        boolean writeImages = !Boolean.getBoolean("logratio.noImages");
        boolean controls = !Boolean.getBoolean("logratio.noControls");

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
                RunContext context = RunContext.create(recording, input);
                if (controls) {
                    for (Control control : Control.values()) {
                        attempted++;
                        Path output = recording.conditionFolder.resolve("filtered_mask_pixel_selection")
                                .resolve(control.id);
                        if (complete(output, writeImages) && !rewrite) {
                            resumed++;
                        } else {
                            try {
                                writeControl(recording, input, context, control, output, writeImages);
                                completed++;
                            } catch (IOException | RuntimeException error) {
                                recordFailure(recording, control.id, output, error, failures);
                            }
                        }
                    }
                }

                for (FilterArm filter : FilterArm.values()) {
                    if (!matches(requestedFilters, filter.name(), filter.id)) continue;
                    FilterScores fullScores = null;
                    FilterScores spatialScores = null;
                    for (MaskStrategy strategy : MaskStrategy.values()) {
                        if (!matches(requestedStrategies, strategy.name(), strategy.id)) continue;
                        FilterScores scores;
                        try {
                            if (strategy.spatialOnly) {
                                if (spatialScores == null) spatialScores = context.scoreSpatial(filter);
                                scores = spatialScores;
                            } else {
                                if (fullScores == null) fullScores = context.score(filter);
                                scores = fullScores;
                            }
                        } catch (RuntimeException error) {
                            failures.add(recording + " / " + filter.name() + " / "
                                    + strategy.name() + " score: " + error);
                            continue;
                        }
                        for (double percentage : percentages) {
                            String armId = armId(filter, strategy, percentage);
                            attempted++;
                            Path output = recording.conditionFolder.resolve("filtered_mask_pixel_selection")
                                    .resolve(armId);
                            if (complete(output, writeImages) && !rewrite) {
                                resumed++;
                                continue;
                            }
                            try {
                                runSelected(recording, input, context, scores, strategy,
                                        percentage, armId, output, writeImages);
                                completed++;
                            } catch (IOException | RuntimeException error) {
                                recordFailure(recording, armId, output, error, failures);
                            }
                        }
                    }
                }
            } finally {
                input.close();
            }
        }
        writeSummaries(controlled);
        System.out.printf("filtered mask benchmark: attempted %d, completed %d, resumed %d, failed %d%n",
                attempted, completed, resumed, failures.size());
        if (!failures.isEmpty()) {
            throw new IOException("filtered mask failures:\n" + String.join("\n", failures));
        }
    }

    private enum Control {
        CURRENT_RECOMMENDED("00_current_recommended", "Current category-specific recommendation"),
        UNFILTERED("01_unfiltered_fit", "Recommended fit settings without preprocessing");

        final String id;
        final String label;

        Control(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final class RunContext {
        final Recording recording;
        final FrameSource rawSource;
        final LogRatioParameters currentParameters;
        final LogRatioParameters rawParameters;
        final Registration.Result pilot;
        final double pilotSeconds;
        final Registration.Result rawControl;
        final double rawControlSeconds;
        final LogPlane[][] rawPyramids;
        final double rawPyramidSeconds;
        final List<Reconciler.Observation> plan;

        RunContext(Recording recording, FrameSource rawSource,
                   LogRatioParameters currentParameters, LogRatioParameters rawParameters,
                   Registration.Result pilot, double pilotSeconds,
                   Registration.Result rawControl, double rawControlSeconds,
                   LogPlane[][] rawPyramids, double rawPyramidSeconds,
                   List<Reconciler.Observation> plan) {
            this.recording = recording;
            this.rawSource = rawSource;
            this.currentParameters = currentParameters;
            this.rawParameters = rawParameters;
            this.pilot = pilot;
            this.pilotSeconds = pilotSeconds;
            this.rawControl = rawControl;
            this.rawControlSeconds = rawControlSeconds;
            this.rawPyramids = rawPyramids;
            this.rawPyramidSeconds = rawPyramidSeconds;
            this.plan = plan;
        }

        static RunContext create(Recording recording, ImagePlus input) {
            ImageType imageType = imageType(recording.imageClass);
            MotionType motionType = MotionType.valueOf(recording.motion);
            LogRatioParameters current = LogRatioParameters.builder()
                    .recommendation(imageType, motionType)
                    .autoMaxShift(false).maxShift(knownMaxShift(recording.motion))
                    .crop(false).interpolation(Warper.Interpolation.NONE).build();
            LogRatioParameters raw = current.toBuilder().useRecommendation(false)
                    .preprocessing(Preprocessing.NONE).build();
            FrameSource rawSource = StackFrames.of(input, raw.channel, raw.slice);

            long pilotStart = System.nanoTime();
            Registration.Result pilot = LogRatioRegistration.estimate(input, current,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            double pilotSeconds = elapsedSeconds(pilotStart);

            long rawControlStart = System.nanoTime();
            Registration.Result rawControl = Registration.run(rawSource, raw.registrationOptions(),
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            double rawControlSeconds = elapsedSeconds(rawControlStart);

            PairAligner.Options options = raw.registrationOptions().aligner;
            int levels = options.levelsFor(rawSource.width(), rawSource.height());
            long pyramidStart = System.nanoTime();
            LogPlane[][] rawPyramids = new LogPlane[rawSource.count()][];
            for (int t = 0; t < rawSource.count(); t++) {
                rawPyramids[t] = LogPlane.of(rawSource.plane(t), rawSource.width(), rawSource.height(),
                        raw.epsilon).pyramid(levels);
            }
            double pyramidSeconds = elapsedSeconds(pyramidStart);
            List<Reconciler.Observation> plan = Reconciler.planPairs(raw.reference,
                    rawSource.count(), raw.referenceFrame - 1, raw.templateWindow, raw.lags);
            return new RunContext(recording, rawSource, current, raw, pilot, pilotSeconds,
                    rawControl, rawControlSeconds, rawPyramids, pyramidSeconds, plan);
        }

        FilterScores score(FilterArm filter) {
            long start = System.nanoTime();
            FrameSource scoringSource = filter.preprocessing == Preprocessing.NONE
                    ? rawSource : new PreprocessedFrameSource(rawSource, filter.preprocessing);
            LogPlane[] full = new LogPlane[scoringSource.count()];
            for (int t = 0; t < full.length; t++) {
                full[t] = LogPlane.of(scoringSource.plane(t), scoringSource.width(),
                        scoringSource.height(), rawParameters.epsilon);
            }
            LagPixelSelector.Scores scores = LagPixelSelector.score(full, pilot.cumulative, LAGS);
            return new FilterScores(filter, scores, elapsedSeconds(start));
        }

        FilterScores scoreSpatial(FilterArm filter) {
            long start = System.nanoTime();
            FrameSource scoringSource = filter.preprocessing == Preprocessing.NONE
                    ? rawSource : new PreprocessedFrameSource(rawSource, filter.preprocessing);
            LogPlane reference = LogPlane.of(scoringSource.plane(0), scoringSource.width(),
                    scoringSource.height(), rawParameters.epsilon);
            LagPixelSelector.Scores scores = LagPixelSelector.spatialInformationScore(reference);
            return new FilterScores(filter, scores, elapsedSeconds(start));
        }
    }

    private static final class FilterScores {
        final FilterArm filter;
        final LagPixelSelector.Scores scores;
        final double seconds;

        FilterScores(FilterArm filter, LagPixelSelector.Scores scores, double seconds) {
            this.filter = filter;
            this.scores = scores;
            this.seconds = seconds;
        }
    }

    private static void writeControl(Recording recording, ImagePlus input, RunContext context,
                                     Control control, Path output, boolean writeImages)
            throws IOException {
        Transform[] transforms = control == Control.CURRENT_RECOMMENDED
                ? context.pilot.cumulative : context.rawControl.cumulative;
        double seconds = control == Control.CURRENT_RECOMMENDED
                ? context.pilotSeconds : context.rawControlSeconds;
        String filter = control == Control.CURRENT_RECOMMENDED
                ? context.currentParameters.preprocessing.name() : Preprocessing.NONE.name();
        writeResult(recording, input, transforms, control.id, filter, "NONE",
                0, 0, seconds, 0, 0, seconds, output, writeImages,
                control.label + "; correction applied to native full-resolution pixels");
    }

    private static void runSelected(Recording recording, ImagePlus input, RunContext context,
                                    FilterScores filterScores, MaskStrategy strategy,
                                    double percentage, String armId, Path output,
                                    boolean writeImages) throws IOException {
        long selectStart = System.nanoTime();
        boolean[] referenceMask = strategy.stratified
                ? LagPixelSelector.stratifiedMask(filterScores.scores, strategy.score,
                        percentage, strategy.removeHighest, 32, 4)
                : LagPixelSelector.mask(filterScores.scores, strategy.score,
                        percentage, strategy.removeHighest);
        int removed = LagPixelSelector.removedEligible(referenceMask, filterScores.scores);
        double effective = filterScores.scores.eligibleCount == 0 ? 0
                : 100.0 * removed / filterScores.scores.eligibleCount;
        boolean[][][] supportByFrame = new boolean[context.rawPyramids.length][][];
        for (int t = 0; t < supportByFrame.length; t++) {
            supportByFrame[t] = LagPixelSelector.sourceSupport(referenceMask,
                    context.rawSource.width(), context.rawSource.height(), context.rawPyramids[t],
                    context.pilot.cumulative[t]);
        }
        double selectionSeconds = elapsedSeconds(selectStart);

        long refitStart = System.nanoTime();
        Transform[] measured = new Transform[context.plan.size()];
        PairAligner.Options options = context.rawParameters.registrationOptions().aligner;
        for (int i = 0; i < context.plan.size(); i++) {
            Reconciler.Observation edge = context.plan.get(i);
            Transform start = context.pilot.cumulative[edge.from].inverse()
                    .then(context.pilot.cumulative[edge.to]);
            PairAligner.Fit fit = PairAligner.alignFrom(context.rawPyramids[edge.from],
                    context.rawPyramids[edge.to], options, start, supportByFrame[edge.from]);
            measured[i] = fit.transform;
        }
        List<Reconciler.Observation> observations = new ArrayList<>(context.plan.size());
        for (int i = 0; i < context.plan.size(); i++) {
            Reconciler.Observation edge = context.plan.get(i);
            observations.add(new Reconciler.Observation(edge.from, edge.to, measured[i]));
        }
        Reconciler.Solution solution = Reconciler.multiLag(context.rawPyramids.length, observations);
        double refitSeconds = elapsedSeconds(refitStart);
        double totalSeconds = context.pilotSeconds + filterScores.seconds
                + context.rawPyramidSeconds + selectionSeconds + refitSeconds;
        String details = filterScores.filter.label + " selected pixels; " + strategy.label
                + "; original unfiltered intensities and gradients drove the masked refit; "
                + "current recommendation supplied the provisional transform; native output";
        writeResult(recording, input, solution.cumulative, armId, filterScores.filter.name(),
                strategy.name(), percentage, effective, totalSeconds, refitSeconds,
                filterScores.seconds + selectionSeconds, context.pilotSeconds,
                output, writeImages, details);
    }

    private static void writeResult(Recording recording, ImagePlus input, Transform[] transforms,
                                    String armId, String filter, String strategy,
                                    double requestedPercent, double effectivePercent,
                                    double totalSeconds, double refitSeconds, double scoreSeconds,
                                    double pilotSeconds, Path output, boolean writeImages,
                                    String details) throws IOException {
        Files.createDirectories(output);
        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(recording.motion).fill(fineX, fineY);
        double[] errors = errors(transforms, fineX, fineY);

        long warpStart = System.nanoTime();
        ImagePlus corrected = StackWarper.apply(input, transforms, Warper.Interpolation.NONE, false);
        double warpSeconds = elapsedSeconds(warpStart);
        if (corrected.getWidth() != input.getWidth() || corrected.getHeight() != input.getHeight()
                || corrected.getStackSize() != input.getStackSize()) {
            corrected.close();
            throw new IOException("correction did not preserve native stack dimensions");
        }
        if (writeImages) {
            IJ.saveAsTiff(corrected,
                    output.resolve("log_ratio_filtered_mask_corrected.tif").toString());
        }
        corrected.close();
        writeTransforms(output.resolve("log_ratio_filtered_mask_transforms.csv"), transforms,
                fineX, fineY);

        String line = csv(recording.imageClass) + ',' + csv(recording.series) + ','
                + csv(recording.motion) + ',' + csv(recording.condition) + ',' + csv(armId) + ','
                + csv(filter) + ',' + csv(strategy) + ',' + format(requestedPercent) + ','
                + format(effectivePercent) + ',' + input.getWidth() + ',' + input.getHeight() + ','
                + format(Benchmark.quantile(errors, 0.5)) + ','
                + format(Benchmark.quantile(errors, 0.9)) + ','
                + format(Benchmark.quantile(errors, 1.0)) + ',' + format(totalSeconds) + ','
                + format(refitSeconds) + ',' + format(scoreSeconds) + ',' + format(pilotSeconds) + ','
                + format(warpSeconds) + ',' + csv(details);
        Files.write(output.resolve("comparison.csv"),
                (ResultRow.HEADER + '\n' + line + '\n').getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(output.resolve("run_failure.txt"));
        System.out.printf("%-18s %-32s %-27s %-62s median %.4f px  %.1f%% removed  %.2f s%n",
                recording.imageClass, recording.series, recording.motion, armId,
                Benchmark.quantile(errors, 0.5), effectivePercent, totalSeconds);
    }

    private static void writeSummaries(Path controlled) throws IOException {
        List<ResultRow> rows = readResults(controlled);
        if (rows.isEmpty()) return;
        Path summaries = controlled.resolve("summaries/filtered_mask_pixel_selection");
        Files.createDirectories(summaries);
        rows.sort(Comparator.comparing((ResultRow row) -> row.imageClass)
                .thenComparing(row -> row.series).thenComparing(row -> row.motion)
                .thenComparing(row -> row.armId));
        StringBuilder raw = new StringBuilder(ResultRow.HEADER).append('\n');
        for (ResultRow row : rows) raw.append(row.line).append('\n');
        Files.write(summaries.resolve("all_recordings.csv"),
                raw.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, List<ResultRow>> byArm = new LinkedHashMap<>();
        Map<String, List<ResultRow>> byCellArm = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            byArm.computeIfAbsent(row.armId, ignored -> new ArrayList<>()).add(row);
            byCellArm.computeIfAbsent(row.imageClass + '/' + row.motion + '/' + row.armId,
                    ignored -> new ArrayList<>()).add(row);
        }
        Map<String, ResultRow> controls = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            if (Control.CURRENT_RECOMMENDED.id.equals(row.armId)) {
                controls.put(row.recordingKey(), row);
            }
        }

        StringBuilder arms = new StringBuilder("arm_id,filter,mask_strategy,requested_remove_percent,"
                + "recordings,image_types,motion_cells,full_balanced_coverage,"
                + "balanced_mean_median_error_px,mean_total_seconds,individual_wins_vs_current,"
                + "individual_losses_vs_current,new_failures_vs_current\n");
        for (Map.Entry<String, List<ResultRow>> entry : byArm.entrySet()) {
            List<ResultRow> group = entry.getValue();
            ResultRow first = group.get(0);
            Set<String> classes = new TreeSet<>();
            Set<String> cells = new TreeSet<>();
            for (ResultRow row : group) {
                classes.add(row.imageClass);
                cells.add(row.imageClass + '/' + row.motion);
            }
            Comparison comparison = compare(group, controls);
            arms.append(csv(entry.getKey())).append(',').append(csv(first.filter)).append(',')
                    .append(csv(first.strategy)).append(',').append(format(first.requestedPercent))
                    .append(',').append(group.size()).append(',').append(classes.size()).append(',')
                    .append(cells.size()).append(',').append(group.size() == 80).append(',')
                    .append(format(balancedError(group))).append(',')
                    .append(format(mean(group, Metric.TOTAL_SECONDS))).append(',')
                    .append(comparison.wins).append(',').append(comparison.losses).append(',')
                    .append(comparison.newFailures).append('\n');
        }
        Files.write(summaries.resolve("arm_summary.csv"),
                arms.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder cells = new StringBuilder("image_series_class,motion_profile,arm_id,filter,"
                + "mask_strategy,requested_remove_percent,series_count,mean_median_error_px,"
                + "mean_total_seconds\n");
        for (Map.Entry<String, List<ResultRow>> entry : byCellArm.entrySet()) {
            List<ResultRow> group = entry.getValue();
            ResultRow first = group.get(0);
            cells.append(csv(first.imageClass)).append(',').append(csv(first.motion)).append(',')
                    .append(csv(first.armId)).append(',').append(csv(first.filter)).append(',')
                    .append(csv(first.strategy)).append(',').append(format(first.requestedPercent))
                    .append(',').append(group.size()).append(',')
                    .append(format(mean(group, Metric.MEDIAN_ERROR))).append(',')
                    .append(format(mean(group, Metric.TOTAL_SECONDS))).append('\n');
        }
        Files.write(summaries.resolve("by_image_type_and_motion.csv"),
                cells.toString().getBytes(StandardCharsets.UTF_8));
        writeRecommendedHybrid(summaries, rows);

        String readme = "# Filter-derived pixel-mask benchmark\n\n"
                + "A conventional filter is applied only to a scoring copy. The resulting mask is "
                + "mapped through the provisional movement trajectory, then the original unfiltered "
                + "intensities and gradients drive the masked log-ratio refit. Corrected stacks use "
                + "native full-resolution pixels.\n\n"
                + "- `arm_summary.csv`: coverage, balanced error, time and safety against the current recommendation.\n"
                + "- `by_image_type_and_motion.csv`: results separated by signal and movement.\n"
                + "- `all_recordings.csv`: one auditable row per source recording and arm.\n";
        Files.write(summaries.resolve("README.md"), readme.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeRecommendedHybrid(Path summaries, List<ResultRow> rows)
            throws IOException {
        Map<String, ResultRow> available = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            available.put(row.recordingKey() + '/' + row.armId, row);
        }
        List<ResultRow> controls = new ArrayList<>();
        for (ResultRow row : rows) {
            if (Control.CURRENT_RECOMMENDED.id.equals(row.armId)) controls.add(row);
        }
        List<ResultRow> selected = new ArrayList<>();
        for (ResultRow control : controls) {
            String arm = "SPARSE_LOWLIGHT".equals(control.imageClass)
                    && !"SUBPIXEL_RANDOM_WALK".equals(control.motion)
                    ? armId(FilterArm.NONE, MaskStrategy.FAST_REMOVE_LEAST_INFORMATIVE, 25)
                    : Control.CURRENT_RECOMMENDED.id;
            ResultRow row = available.get(control.recordingKey() + '/' + arm);
            if (row == null) throw new IOException("missing recommended mask arm " + arm
                    + " for " + control.recordingKey());
            selected.add(row);
        }
        selected.sort(Comparator.comparing((ResultRow row) -> row.imageClass)
                .thenComparing(row -> row.series).thenComparing(row -> row.motion));
        StringBuilder audit = new StringBuilder(ResultRow.HEADER).append('\n');
        for (ResultRow row : selected) audit.append(row.line).append('\n');
        Files.write(summaries.resolve("recommended_hybrid_all_recordings.csv"),
                audit.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, ResultRow> controlMap = new LinkedHashMap<>();
        for (ResultRow control : controls) controlMap.put(control.recordingKey(), control);
        Comparison comparison = compare(selected, controlMap);
        String summary = "strategy,balanced_mean_median_error_px,control_error_px,"
                + "accuracy_change_vs_control_percent,mean_total_seconds,control_seconds,"
                + "speed_ratio_vs_control,individual_wins,individual_losses,unchanged,new_failures\n"
                + "category_specific_spatial_information_mask," + format(balancedError(selected))
                + ',' + format(balancedError(controls)) + ','
                + format(100.0 * (balancedError(selected) / balancedError(controls) - 1.0))
                + ',' + format(mean(selected, Metric.TOTAL_SECONDS)) + ','
                + format(mean(controls, Metric.TOTAL_SECONDS)) + ','
                + format(mean(selected, Metric.TOTAL_SECONDS)
                        / mean(controls, Metric.TOTAL_SECONDS)) + ','
                + comparison.wins + ',' + comparison.losses + ','
                + (selected.size() - comparison.wins - comparison.losses) + ','
                + comparison.newFailures + '\n';
        Files.write(summaries.resolve("recommended_hybrid_summary.csv"),
                summary.getBytes(StandardCharsets.UTF_8));

        String choices = "image_series_class,motion_profile,pixel_selection,"
                + "scoring_filter,remove_percent,reason\n"
                + "SPARSE_LOWLIGHT,CURVED_OSCILLATING_DRIFT,REMOVE_LEAST_INFORMATIVE,NONE,25,won all four independent source series\n"
                + "SPARSE_LOWLIGHT,INTERMITTENT_JUMPS,REMOVE_LEAST_INFORMATIVE,NONE,25,won three of four source series and reduced the category mean\n"
                + "SPARSE_LOWLIGHT,STEADY_DIRECTIONAL_DRIFT,REMOVE_LEAST_INFORMATIVE,NONE,25,won all four independent source series\n";
        Files.write(summaries.resolve("recommended_hybrid_choices.csv"),
                choices.getBytes(StandardCharsets.UTF_8));
    }

    private static Comparison compare(List<ResultRow> rows, Map<String, ResultRow> controls) {
        int wins = 0;
        int losses = 0;
        int newFailures = 0;
        for (ResultRow row : rows) {
            ResultRow control = controls.get(row.recordingKey());
            if (control == null || row.armId.equals(control.armId)) continue;
            if (row.medianError < control.medianError) wins++;
            if (row.medianError > control.medianError) losses++;
            if (control.medianError < 0.5 && row.medianError > 2.0) newFailures++;
        }
        return new Comparison(wins, losses, newFailures);
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

    private enum Metric { MEDIAN_ERROR, TOTAL_SECONDS }

    private static double mean(List<ResultRow> rows, Metric metric) {
        if (rows.isEmpty()) return Double.NaN;
        double sum = 0;
        for (ResultRow row : rows) {
            sum += metric == Metric.MEDIAN_ERROR ? row.medianError : row.totalSeconds;
        }
        return sum / rows.size();
    }

    private static double meanNumbers(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    private static boolean complete(Path output, boolean writeImages) {
        return Files.isRegularFile(output.resolve("comparison.csv"))
                && Files.isRegularFile(output.resolve("log_ratio_filtered_mask_transforms.csv"))
                && (!writeImages || Files.isRegularFile(
                        output.resolve("log_ratio_filtered_mask_corrected.tif")));
    }

    private static void recordFailure(Recording recording, String arm, Path output,
                                      Exception error, List<String> failures) {
        String message = recording + " / " + arm + ": " + error;
        try {
            Files.createDirectories(output);
            Files.write(output.resolve("run_failure.txt"),
                    (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The original failure remains the useful one.
        }
        failures.add(message);
        System.err.println("FAILED " + message);
    }

    private static String armId(FilterArm filter, MaskStrategy strategy, double percentage) {
        return "02_" + filter.id + "__" + strategy.id + "__remove_" + compact(percentage);
    }

    private static double[] percentages() {
        String property = System.getProperty("logratio.maskPercentages", "25,50,75");
        String[] fields = property.split(",");
        double[] values = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = Double.parseDouble(fields[i].trim());
            if (!(values[i] > 0 && values[i] < 100)) {
                throw new IllegalArgumentException("mask removal percentage must be in (0,100)");
            }
        }
        return values;
    }

    private static boolean matches(Set<String> requested, String name, String id) {
        return requested.isEmpty() || requested.contains(name) || requested.contains(id);
    }

    private static Set<String> requestedSet(String property) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : System.getProperty(property, "").split(",")) {
            if (!value.trim().isEmpty()) values.add(value.trim());
        }
        return values;
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

    private static List<ResultRow> readResults(Path controlled) throws IOException {
        List<ResultRow> rows = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(controlled)) {
            List<Path> files = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().equals("comparison.csv"))
                    .filter(path -> path.toString().contains("filtered_mask_pixel_selection"))
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
        StringBuilder out = new StringBuilder(
                "frame,x_px,y_px,rotation_radians,truth_x_px,truth_y_px,error_px,status\n");
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

    private static double elapsedSeconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "NaN" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String compact(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value))
                : Double.toString(value).replace('.', '_');
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
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
        static final String HEADER = "image_series_class,series_id,motion_profile,condition,arm_id,"
                + "scoring_filter,mask_strategy,requested_remove_percent,effective_remove_percent,"
                + "native_width_px,native_height_px,median_error_px,p90_error_px,max_error_px,"
                + "total_estimate_seconds,masked_refit_seconds,filter_score_seconds,pilot_seconds,"
                + "warp_seconds,details";

        final String imageClass;
        final String series;
        final String motion;
        final String armId;
        final String filter;
        final String strategy;
        final double requestedPercent;
        final double medianError;
        final double totalSeconds;
        final String line;

        ResultRow(String imageClass, String series, String motion, String armId, String filter,
                  String strategy, double requestedPercent, double medianError,
                  double totalSeconds, String line) {
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.armId = armId;
            this.filter = filter;
            this.strategy = strategy;
            this.requestedPercent = requestedPercent;
            this.medianError = medianError;
            this.totalSeconds = totalSeconds;
            this.line = line;
        }

        String recordingKey() { return imageClass + '/' + series + '/' + motion; }

        static ResultRow parse(String line) {
            List<String> fields = parseCsv(line);
            return new ResultRow(fields.get(0), fields.get(1), fields.get(2), fields.get(4),
                    fields.get(5), fields.get(6), Double.parseDouble(fields.get(7)),
                    Double.parseDouble(fields.get(11)), Double.parseDouble(fields.get(14)), line);
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
