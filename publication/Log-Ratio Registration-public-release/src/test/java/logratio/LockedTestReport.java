/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import logratio.api.AutomaticRegistrationSelector;
import logratio.api.AutomaticRegistrationSelectorModel;
import logratio.api.ImageType;
import logratio.api.LogRatioParameters;
import logratio.api.LogRatioRegistration;
import logratio.api.LogRatioResult;
import logratio.api.MotionType;
import logratio.api.SelectionMode;
import logratio.core.PairScheduler;
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
import java.util.TreeMap;

/**
 * Scores the locked test set once and states plainly whether the new selector earned default status.
 *
 * <p>Nothing here may be used to change a coefficient, a threshold, a feature or a candidate recipe.
 * The selector was frozen by {@link FullSelectorTraining} before this ran, and the gates below were
 * written before the numbers existed.
 *
 * <p>Natural-motion versions of the same new sources are also registered, but their residual
 * stability is reported in its own table and never pooled with movement accuracy: there is no known
 * truth in a real recording, so a small residual is a stability measurement and nothing more.
 */
public final class LockedTestReport {
    static final String CATEGORY_ARM = "1_current_category_recommendation";
    static final String SELECTOR_ARM = "4_new_full_automatic_selector";

    /** Declared in the plan before the locked set was scored. */
    static final double GATE_IMAGE_TYPE_REGRESSION = FullSelectorTraining.GATE_IMAGE_TYPE_REGRESSION;
    static final double GATE_MEAN_SECONDS = FullSelectorTraining.GATE_MEAN_SECONDS;

    private LockedTestReport() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path root = project.resolve(LockedTestSetBuilder.ROOT);
        Path summary = root.resolve("summaries").resolve(SelectorComparisonBenchmark.RUN_ID);
        Path rows = summary.resolve("all_recordings.csv");
        if (!Files.isRegularFile(rows)) {
            throw new IOException("missing " + rows + "; run SelectorComparisonBenchmark over "
                    + LockedTestSetBuilder.ROOT + " first");
        }

        Map<String, Map<String, Row>> byRecording = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(rows, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            Row row = Row.parse(FullSelectorFactorialBenchmark.fields(lines.get(i)));
            byRecording.computeIfAbsent(row.key, key -> new LinkedHashMap<>()).put(row.arm, row);
        }

        List<Paired> paired = new ArrayList<>();
        for (Map.Entry<String, Map<String, Row>> entry : byRecording.entrySet()) {
            Row category = entry.getValue().get(CATEGORY_ARM);
            Row selector = entry.getValue().get(SELECTOR_ARM);
            if (category == null || selector == null) continue;
            paired.add(new Paired(category, selector));
        }
        paired.sort(Comparator.comparing(one -> one.category.key));
        if (paired.isEmpty()) throw new IOException("no paired locked-test rows found");

        Gates gates = score(paired);
        List<Natural> natural = runNatural(project);
        writePairedRows(summary, paired);
        writeGates(summary, gates);
        writeReport(project, summary, gates, paired, natural);
        System.out.printf(Locale.ROOT,
                "locked test: %d recordings, selector %.6f px versus category %.6f px, "
                        + "%d failures, gates %s%n",
                gates.count, gates.meanSelectorMedian, gates.meanCategoryMedian, gates.failures,
                gates.passed() ? "PASS" : "FAIL");
        for (String failure : gates.reasons()) System.out.println("  gate failed: " + failure);
    }

    private static Gates score(List<Paired> paired) {
        Gates gates = new Gates();
        Map<String, double[]> perClass = new TreeMap<>();
        for (Paired one : paired) {
            gates.count++;
            if (!one.selector.ok || !one.category.ok) {
                gates.failures++;
                continue;
            }
            gates.selectorMedianTotal += one.selector.median;
            gates.categoryMedianTotal += one.category.median;
            gates.selectorP90Total += one.selector.p90;
            gates.categoryP90Total += one.category.p90;
            gates.selectorSecondsTotal += one.selector.seconds;
            gates.categorySecondsTotal += one.category.seconds;
            if (!one.selector.recipeId.equals(one.category.recipeId)) gates.overrides++;
            double[] totals = perClass.computeIfAbsent(one.selector.imageClass,
                    key -> new double[3]);
            totals[0] += one.selector.median;
            totals[1] += one.category.median;
            totals[2] += 1;
        }
        int scored = gates.count - gates.failures;
        if (scored > 0) {
            gates.meanSelectorMedian = gates.selectorMedianTotal / scored;
            gates.meanCategoryMedian = gates.categoryMedianTotal / scored;
            gates.meanSelectorP90 = gates.selectorP90Total / scored;
            gates.meanCategoryP90 = gates.categoryP90Total / scored;
            gates.meanSelectorSeconds = gates.selectorSecondsTotal / scored;
            gates.meanCategorySeconds = gates.categorySecondsTotal / scored;
        }
        for (Map.Entry<String, double[]> entry : perClass.entrySet()) {
            double[] totals = entry.getValue();
            gates.selectorByClass.put(entry.getKey(), totals[0] / totals[2]);
            gates.categoryByClass.put(entry.getKey(), totals[1] / totals[2]);
        }
        return gates;
    }

    /**
     * Register the natural-motion versions of the same new sources. Residual stability only; the
     * numbers are deliberately kept in their own table so they can never be read as accuracy.
     */
    private static List<Natural> runNatural(Path project) throws IOException {
        Path root = project.resolve(LockedTestSetBuilder.NATURAL_ROOT);
        List<Natural> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        List<Path> inputs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName().toString().equals("00_input_native.tif"))
                    .forEach(inputs::add);
        }
        inputs.sort(Comparator.comparing(Path::toString));
        for (Path input : inputs) {
            Path relative = root.relativize(input);
            String imageClass = relative.getName(0).toString();
            String series = relative.getName(1).toString();
            ImagePlus image = IJ.openImage(input.toString());
            if (image == null) throw new IOException("could not open " + input);
            try {
                // Bound the search from the measured motion profile, exactly as the standing
                // natural-motion benchmark does. Left to estimate its own bound on a low-texture
                // field the fit can run away, and a correction larger than the field is not a
                // registration at all.
                NativeMotionProfiler.Profile profile = NativeMotionProfiler.profile(
                        pixels(image), image.getWidth());
                double bound = Math.max(8.0,
                        Math.min(image.getWidth() / 3.0, profile.maximumStep * 3.0 + 4.0));
                LogRatioParameters category = LogRatioParameters.builder()
                        .recommendation(imageType(imageClass), MotionType.SUBPIXEL_RANDOM_WALK)
                        .autoMaxShift(false).maxShift(bound).crop(false)
                        .interpolation(Warper.Interpolation.NONE).build();
                double categoryResidual = residual(image, category, input.getParent(),
                        "category_recommendation");
                double selectorResidual = residual(image,
                        category.toBuilder().selectionMode(SelectionMode.AUTOMATIC).build(),
                        input.getParent(), "new_full_automatic_selector");
                out.add(new Natural(imageClass, series, categoryResidual, selectorResidual));
                System.out.printf(Locale.ROOT, "natural %-16s %-32s residual %.4f -> %.4f px%n",
                        imageClass, series, categoryResidual, selectorResidual);
            } finally {
                image.close();
            }
        }
        return out;
    }

    private static double residual(ImagePlus image, LogRatioParameters parameters, Path output,
                                   String stem) throws IOException {
        LogRatioResult result = LogRatioRegistration.register(image, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        try {
            // Measure on the interior only. The warp fills vacated border pixels with zero rather
            // than NaN, so a residual taken over the whole frame tracks those blank margins sliding
            // in and out and reports tens of pixels of movement on a well-aligned recording.
            Interior interior = interior(result.correctedImage(), result.registration().cumulative);
            NativeMotionProfiler.Residual residual = interior.width == 0
                    ? new NativeMotionProfiler.Residual(Double.NaN, Double.NaN, Double.NaN)
                    : NativeMotionProfiler.residual(interior.frames, interior.width);
            IJ.saveAsTiff(result.correctedImage(),
                    output.resolve(stem + "_corrected.tif").toString());
            Files.write(output.resolve(stem + "_settings.csv"),
                    ("setting,value\n" + FullSelectorFactorialBenchmark.csv("resolved_recipe") + ','
                            + FullSelectorFactorialBenchmark.csv(
                                    result.resolvedRecipe().describe()) + '\n'
                            + FullSelectorFactorialBenchmark.csv("provenance") + ','
                            + FullSelectorFactorialBenchmark.csv(result.provenance()) + '\n')
                            .getBytes(StandardCharsets.UTF_8));
            return residual.median;
        } finally {
            result.correctedImage().changes = false;
            result.close();
        }
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

    private static final class Interior {
        final float[][] frames;
        final int width;

        Interior(float[][] frames, int width) {
            this.frames = frames;
            this.width = width;
        }
    }

    /**
     * A square centre crop that no frame's correction pulled in from outside the original field.
     * The margin is the largest cumulative shift, so every pixel measured is real sample in every
     * frame. {@code NativeMotionProfiler.residual} requires a square field, hence the square crop.
     */
    private static Interior interior(ImagePlus corrected, logratio.core.Transform[] cumulative) {
        float[][] frames = pixels(corrected);
        int width = corrected.getWidth();
        int height = corrected.getHeight();
        double reach = 0;
        for (logratio.core.Transform transform : cumulative) {
            if (transform == null) continue;
            reach = Math.max(reach, Math.max(Math.abs(transform.dx), Math.abs(transform.dy)));
        }
        int margin = (int) Math.ceil(reach) + 2;
        int side = Math.min(width, height) - 2 * margin;
        if (side < 64) {
            // Too much movement to leave a usable interior; report it rather than inventing a number.
            return new Interior(new float[0][], 0);
        }
        int originX = (width - side) / 2;
        int originY = (height - side) / 2;
        float[][] out = new float[frames.length][side * side];
        for (int frame = 0; frame < frames.length; frame++) {
            for (int y = 0; y < side; y++) {
                System.arraycopy(frames[frame], (originY + y) * width + originX,
                        out[frame], y * side, side);
            }
        }
        return new Interior(out, side);
    }

    private static ImageType imageType(String value) {
        return FullSelectorFactorialBenchmark.imageType(value);
    }

    private static void writePairedRows(Path summary, List<Paired> paired) throws IOException {
        StringBuilder out = new StringBuilder("image_series_class,series_id,motion_category,"
                + "category_median_error_px,selector_median_error_px,improvement_px,"
                + "category_p90_error_px,selector_p90_error_px,category_seconds,selector_seconds,"
                + "category_recipe_id,selector_recipe_id,overridden\n");
        for (Paired one : paired) {
            out.append(csv(one.selector.imageClass)).append(',').append(csv(one.selector.series))
                    .append(',').append(csv(one.selector.motion)).append(',')
                    .append(format(one.category.median)).append(',')
                    .append(format(one.selector.median)).append(',')
                    .append(format(one.category.median - one.selector.median)).append(',')
                    .append(format(one.category.p90)).append(',')
                    .append(format(one.selector.p90)).append(',')
                    .append(format(one.category.seconds)).append(',')
                    .append(format(one.selector.seconds)).append(',')
                    .append(csv(one.category.recipeId)).append(',')
                    .append(csv(one.selector.recipeId)).append(',')
                    .append(!one.selector.recipeId.equals(one.category.recipeId)).append('\n');
        }
        Files.write(summary.resolve("locked_test_paired.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeGates(Path summary, Gates gates) throws IOException {
        StringBuilder out = new StringBuilder("gate,measured,limit,passed\n");
        gate(out, "failures", gates.failures, 0);
        gate(out, "mean_median_error_px", gates.meanSelectorMedian, gates.meanCategoryMedian);
        gate(out, "mean_p90_error_px", gates.meanSelectorP90, gates.meanCategoryP90);
        gate(out, "mean_seconds", gates.meanSelectorSeconds, GATE_MEAN_SECONDS);
        for (String imageClass : gates.selectorByClass.keySet()) {
            gate(out, "image_type_regression_px[" + imageClass + "]",
                    gates.selectorByClass.get(imageClass) - gates.categoryByClass.get(imageClass),
                    GATE_IMAGE_TYPE_REGRESSION);
        }
        gate(out, "sparse_lowlight_mean_error_px",
                gates.selectorByClass.getOrDefault("SPARSE_LOWLIGHT", Double.NaN),
                gates.categoryByClass.getOrDefault("SPARSE_LOWLIGHT", Double.NaN));
        Files.write(summary.resolve("locked_test_gates.csv"),
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void gate(StringBuilder out, String name, double measured, double limit) {
        out.append(csv(name)).append(',').append(format(measured)).append(',').append(format(limit))
                .append(',').append(measured <= limit).append('\n');
    }

    private static void writeReport(Path project, Path summary, Gates gates, List<Paired> paired,
                                    List<Natural> natural) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Locked test: the frozen full automatic selector, run once\n\n");
        md.append("Ten new source series, two per image type, four fixed movement paths each: ")
                .append(paired.size()).append(" controlled recordings. None of these sources ")
                .append("influenced any selector. The model was frozen before this ran and no ")
                .append("coefficient, threshold, feature or candidate recipe changed afterwards.\n\n");
        md.append("Model in force: `").append(AutomaticRegistrationSelectorModel.MODEL_KIND)
                .append("`, trained on ").append(AutomaticRegistrationSelectorModel.TRAINED_ON)
                .append(".\n\n");

        md.append("## Declared caveats\n\n");
        md.append("- The reinforced-cage record contains exactly five bead acquisitions and four ")
                .append("are already in the development set. The two fiducial entries are ")
                .append("non-overlapping fields of view cut from the one remaining acquisition. ")
                .append("They share an independent group and are not two independent sources.\n");
        md.append("- The two dense-fluorescence entries are different acquisitions from the same ")
                .append("published calcium-signalling record as the development dense series. The ")
                .append("one wholly separate dense record on disk is in a TIFF variant ImageJ ")
                .append("cannot open.\n");
        md.append("- Phase, brightfield and sparse entries are genuinely independent of the ")
                .append("development set.\n\n");

        md.append("## Release gates\n\n| Gate | Measured | Limit | Result |\n|---|---|---|---|\n");
        md.append(row("Registration failures", gates.failures, 0, gates.failures == 0));
        md.append(row("Paired mean median error (px)", gates.meanSelectorMedian,
                gates.meanCategoryMedian, gates.meanSelectorMedian <= gates.meanCategoryMedian));
        md.append(row("Paired mean 90th-percentile error (px)", gates.meanSelectorP90,
                gates.meanCategoryP90, gates.meanSelectorP90 <= gates.meanCategoryP90));
        md.append(row("Mean execution time (s)", gates.meanSelectorSeconds, GATE_MEAN_SECONDS,
                gates.meanSelectorSeconds <= GATE_MEAN_SECONDS));
        for (String imageClass : gates.selectorByClass.keySet()) {
            double regression = gates.selectorByClass.get(imageClass)
                    - gates.categoryByClass.get(imageClass);
            md.append(row(imageClass + " regression (px)", regression, GATE_IMAGE_TYPE_REGRESSION,
                    regression <= GATE_IMAGE_TYPE_REGRESSION));
        }
        Double sparse = gates.selectorByClass.get("SPARSE_LOWLIGHT");
        Double sparseCategory = gates.categoryByClass.get("SPARSE_LOWLIGHT");
        if (sparse != null && sparseCategory != null) {
            md.append(row("Sparse low-light mean error (px)", sparse, sparseCategory,
                    sparse <= sparseCategory));
        }
        md.append("\n**Verdict: ").append(gates.passed() ? "every gate passed" : "at least one gate failed")
                .append(".** ");
        md.append(gates.passed()
                ? "The new selector earns default status on this evidence.\n\n"
                : "The category recommendation remains the production default and the combined "
                        + "selector stays available as an optional automatic mode, labelled "
                        + "experimental.\n\n");
        if (!gates.reasons().isEmpty()) {
            md.append("Failed gates: ").append(String.join("; ", gates.reasons())).append("\n\n");
        }

        md.append("## Read the means with care\n\n");
        List<Paired> hard = new ArrayList<>();
        for (Paired one : paired) {
            if (one.category.ok && one.category.median > 1.0) hard.add(one);
        }
        double[] medians = pairedMedians(paired);
        md.append("Four of the forty recordings come from one sparse source on which the category ")
                .append("recommendation itself loses lock, and their errors are whole pixels rather ")
                .append("than hundredths. They dominate every mean in this file. The paired median ")
                .append("is the number to read for a typical recording:\n\n");
        md.append("| Measure | Category | Selector | Change |\n|---|---|---|---|\n");
        md.append("| Paired **median** of median error (px) | ").append(format(medians[0]))
                .append(" | ").append(format(medians[1])).append(" | ")
                .append(format(medians[1] - medians[0])).append(" |\n");
        md.append("| Paired **mean** of median error (px) | ").append(format(gates.meanCategoryMedian))
                .append(" | ").append(format(gates.meanSelectorMedian)).append(" | ")
                .append(format(gates.meanSelectorMedian - gates.meanCategoryMedian)).append(" |\n\n");
        if (!hard.isEmpty()) {
            md.append("Recordings where the category recommendation alone exceeds 1 px:\n\n");
            for (Paired one : hard) {
                md.append("- `").append(one.category.imageClass).append('/')
                        .append(one.category.series).append('/').append(one.category.motion)
                        .append("`: category ").append(format(one.category.median))
                        .append(" px, selector ").append(format(one.selector.median))
                        .append(" px\n");
            }
            md.append("\nThese are kept in the set and in the gates. A locked test that quietly ")
                    .append("dropped its hardest recordings would not be a test.\n\n");
        }

        md.append("## Per image type\n\n");
        md.append("| Image type | Category mean median (px) | Selector mean median (px) | Change |\n");
        md.append("|---|---|---|---|\n");
        for (String imageClass : gates.selectorByClass.keySet()) {
            double selector = gates.selectorByClass.get(imageClass);
            double category = gates.categoryByClass.get(imageClass);
            md.append("| ").append(imageClass).append(" | ").append(format(category)).append(" | ")
                    .append(format(selector)).append(" | ").append(format(selector - category))
                    .append(" |\n");
        }
        md.append("\nThe selector overrode the category recommendation on ").append(gates.overrides)
                .append(" of ").append(gates.count).append(" recordings.\n\n");

        if (!natural.isEmpty()) {
            md.append("## Natural motion, reported separately\n\n");
            md.append("These are real recordings with unknown truth. The numbers below are ")
                    .append("residual stability after registration, not movement accuracy, and ")
                    .append("must never be pooled with the controlled table above.\n\n");
            md.append("| Image type | Series | Category residual (px) | Selector residual (px) |\n");
            md.append("|---|---|---|---|\n");
            for (Natural one : natural) {
                md.append("| ").append(one.imageClass).append(" | ").append(one.series)
                        .append(" | ").append(format(one.category)).append(" | ")
                        .append(format(one.selector)).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Files\n\n");
        md.append("- `locked_test_paired.csv`: one row per recording, both arms side by side.\n");
        md.append("- `locked_test_gates.csv`: the gate table as data.\n");
        md.append("- `all_recordings.csv`: every arm on every locked recording.\n");
        md.append("- `locked_test_manifest.csv`: the ten sources, their checksums and caveats.\n");
        Files.write(summary.resolve("LOCKED_TEST.md"), md.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Paired medians of the per-recording median error: category first, selector second. */
    private static double[] pairedMedians(List<Paired> paired) {
        List<Double> category = new ArrayList<>();
        List<Double> selector = new ArrayList<>();
        for (Paired one : paired) {
            if (!one.category.ok || !one.selector.ok) continue;
            category.add(one.category.median);
            selector.add(one.selector.median);
        }
        return new double[]{median(category), median(selector)};
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2)
                : 0.5 * (sorted.get(size / 2 - 1) + sorted.get(size / 2));
    }

    private static String row(String name, double measured, double limit, boolean passed) {
        return "| " + name + " | " + format(measured) + " | " + format(limit) + " | "
                + (passed ? "pass" : "FAIL") + " |\n";
    }

    static final class Row {
        final String key;
        final String arm;
        final String imageClass;
        final String series;
        final String motion;
        final boolean ok;
        final double median;
        final double p90;
        final double seconds;
        final String recipeId;

        Row(String key, String arm, String imageClass, String series, String motion, boolean ok,
            double median, double p90, double seconds, String recipeId) {
            this.key = key;
            this.arm = arm;
            this.imageClass = imageClass;
            this.series = series;
            this.motion = motion;
            this.ok = ok;
            this.median = median;
            this.p90 = p90;
            this.seconds = seconds;
            this.recipeId = recipeId;
        }

        static Row parse(List<String> fields) {
            String key = fields.get(1) + '/' + fields.get(2) + '/' + fields.get(4) + '/'
                    + fields.get(5);
            return new Row(key, fields.get(6), fields.get(1), fields.get(2), fields.get(4),
                    "ok".equals(fields.get(7)), number(fields.get(8)), number(fields.get(9)),
                    number(fields.get(11)), fields.get(13));
        }
    }

    private static final class Paired {
        final Row category;
        final Row selector;

        Paired(Row category, Row selector) {
            this.category = category;
            this.selector = selector;
        }
    }

    private static final class Natural {
        final String imageClass;
        final String series;
        final double category;
        final double selector;

        Natural(String imageClass, String series, double category, double selector) {
            this.imageClass = imageClass;
            this.series = series;
            this.category = category;
            this.selector = selector;
        }
    }

    static final class Gates {
        int count;
        int failures;
        int overrides;
        double selectorMedianTotal;
        double categoryMedianTotal;
        double selectorP90Total;
        double categoryP90Total;
        double selectorSecondsTotal;
        double categorySecondsTotal;
        double meanSelectorMedian = Double.NaN;
        double meanCategoryMedian = Double.NaN;
        double meanSelectorP90 = Double.NaN;
        double meanCategoryP90 = Double.NaN;
        double meanSelectorSeconds = Double.NaN;
        double meanCategorySeconds = Double.NaN;
        final Map<String, Double> selectorByClass = new TreeMap<>();
        final Map<String, Double> categoryByClass = new TreeMap<>();

        List<String> reasons() {
            List<String> out = new ArrayList<>();
            if (failures > 0) out.add(failures + " registration failures");
            if (!(meanSelectorMedian <= meanCategoryMedian)) {
                out.add("mean median " + format(meanSelectorMedian) + " px worse than the category "
                        + format(meanCategoryMedian) + " px");
            }
            if (!(meanSelectorP90 <= meanCategoryP90)) {
                out.add("mean 90th percentile " + format(meanSelectorP90) + " px worse than the "
                        + "category " + format(meanCategoryP90) + " px");
            }
            if (!(meanSelectorSeconds <= GATE_MEAN_SECONDS)) {
                out.add("mean " + format(meanSelectorSeconds) + " s exceeds "
                        + format(GATE_MEAN_SECONDS) + " s");
            }
            for (String imageClass : selectorByClass.keySet()) {
                double regression = selectorByClass.get(imageClass) - categoryByClass.get(imageClass);
                if (regression > GATE_IMAGE_TYPE_REGRESSION) {
                    out.add(imageClass + " regresses " + format(regression) + " px");
                }
            }
            Double sparse = selectorByClass.get("SPARSE_LOWLIGHT");
            Double sparseCategory = categoryByClass.get("SPARSE_LOWLIGHT");
            if (sparse != null && sparseCategory != null && sparse > sparseCategory) {
                out.add("sparse low-light worse than the category recommendation");
            }
            return out;
        }

        boolean passed() {
            return reasons().isEmpty();
        }
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "NaN";
    }

    private static String csv(String value) {
        return FullSelectorFactorialBenchmark.csv(value);
    }
}
