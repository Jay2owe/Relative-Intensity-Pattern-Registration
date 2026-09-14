/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.RegistrationRecipe;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Runs any chosen internal recipe over an arbitrary prepared TIFF tree. */
public final class ReusableRegistrationBenchmark {

    private ReusableRegistrationBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: <prepared-root> <output.csv> <comma-separated recipes>");
        }
        run(Paths.get(args[0]).toAbsolutePath().normalize(),
                Paths.get(args[1]).toAbsolutePath().normalize(), recipes(args[2]));
    }

    static void run(Path root, Path output,
                    List<ExternalPublicationBenchmark.InternalRecipe> recipes) throws Exception {
        List<Path> inputs = inputs(root);
        if (inputs.isEmpty()) throw new IOException("no prepared TIFF stacks under " + root);
        if (recipes.isEmpty()) throw new IllegalArgumentException("no internal methods requested");
        if (Files.exists(output)) throw new IOException("refusing to overwrite " + output);
        Files.createDirectories(output.getParent());
        String header = "image_series_class,series_id,motion_category,condition,method_id,"
                + "method_label,status,cpu_seconds,elapsed_seconds,pairs,repaired_frames,"
                + "unsupported_frames,frames,width,height,transform_x_px,transform_y_px,"
                + "transform_theta_rad,requested_policy,resolved_recipe\n";
        Files.write(output, header.getBytes(StandardCharsets.UTF_8));
        Set<ExternalPublicationBenchmark.InternalRecipe> warmed =
                EnumSet.noneOf(ExternalPublicationBenchmark.InternalRecipe.class);
        int total = inputs.size() * recipes.size();
        int done = 0;
        for (Path input : inputs) {
            Path relative = root.relativize(input);
            if (relative.getNameCount() < 5) {
                throw new IOException("unexpected prepared layout " + input);
            }
            String imageClass = relative.getName(0).toString();
            String series = relative.getName(1).toString();
            String motion = relative.getName(2).toString();
            String condition = relative.getName(3).toString();
            ImagePlus image = IJ.openImage(input.toString());
            if (image == null) throw new IOException("could not open " + input);
            try {
                for (ExternalPublicationBenchmark.InternalRecipe recipe : recipes) {
                    RelativeIntensityPatternParameters requested =
                            ExternalPublicationBenchmark.parameters(imageClass, motion,
                                    ExternalPublicationBenchmark.Scope.TRANSLATION, recipe);
                    if (!warmed.contains(recipe)) {
                        if (recipe != ExternalPublicationBenchmark.InternalRecipe
                                .LONGITUDINAL_ACCURACY) {
                            try {
                                estimate(image, requested, recipe);
                            } catch (RuntimeException ignored) {
                                // One excluded full-stack warm-up follows the same route.
                            }
                        }
                        warmed.add(recipe);
                    }
                    Files.write(output, measure(imageClass, series, motion, condition, image,
                                    requested, recipe).getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.APPEND);
                    System.out.printf(Locale.ROOT, "%s %s (%d/%d)%n",
                            recipe.token, series, ++done, total);
                }
            } finally {
                image.close();
            }
        }
    }

    private static List<ExternalPublicationBenchmark.InternalRecipe> recipes(String value) {
        List<ExternalPublicationBenchmark.InternalRecipe> out = new ArrayList<>();
        for (String token : value.split(",")) {
            ExternalPublicationBenchmark.InternalRecipe recipe =
                    ExternalPublicationBenchmark.InternalRecipe.of(token);
            if (!out.contains(recipe)) out.add(recipe);
        }
        return out;
    }

    private static Estimate estimate(ImagePlus image, RelativeIntensityPatternParameters requested,
                                     ExternalPublicationBenchmark.InternalRecipe recipe) {
        RelativeIntensityPatternParameters resolved = requested;
        if (recipe == ExternalPublicationBenchmark.InternalRecipe.AUTOMATIC) {
            AutomaticRegistrationSelector.Result selection =
                    RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, requested);
            resolved = selection.parameters;
        }
        Registration.Result result = RelativeIntensityPatternRegistration.estimate(
                image, resolved, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        String recipeId = recipe == ExternalPublicationBenchmark.InternalRecipe
                .LONGITUDINAL_ACCURACY
                ? "selection=longitudinal_maximum_accuracy; declared_image_type="
                    + requested.imageType.name() + "; selected_channel_only=true"
                : RegistrationRecipe.of(resolved).id();
        return new Estimate(result, recipeId);
    }

    private static String measure(String imageClass, String series, String motion,
                                  String condition, ImagePlus image,
                                  RelativeIntensityPatternParameters requested,
                                  ExternalPublicationBenchmark.InternalRecipe recipe) {
        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();
        Estimate estimate = null;
        String status = "ok";
        try {
            estimate = estimate(image, requested, recipe);
        } catch (RuntimeException error) {
            status = "failed:" + concise(error);
        }
        double elapsed = (System.nanoTime() - wall0) / 1e9;
        double cpu = (processCpuNanos() - cpu0) / 1e9;
        Registration.Result result = estimate == null ? null : estimate.result;
        int repaired = 0;
        int unsupported = 0;
        if (result != null) {
            for (int frame = 0; frame < result.repairs.length; frame++) {
                if (result.repairs[frame] != null) repaired++;
                if (result.support[frame] == 0) unsupported++;
            }
        }
        String policy = recipe == ExternalPublicationBenchmark.InternalRecipe.AUTOMATIC
                ? "fixed recipe selected by entered image type"
                : recipe == ExternalPublicationBenchmark.InternalRecipe.LONGITUDINAL_ACCURACY
                ? "whole-recording accuracy route selected by entered image type"
                : recipe == ExternalPublicationBenchmark.InternalRecipe.RECOMMENDED
                ? "older preset selected by entered image and motion types"
                : "explicit benchmark method";
        return csv(imageClass) + ',' + csv(series) + ',' + csv(motion) + ',' + csv(condition)
                + ',' + csv(recipe.methodId(ExternalPublicationBenchmark.Scope.TRANSLATION))
                + ',' + csv(recipe.label(ExternalPublicationBenchmark.Scope.TRANSLATION))
                + ',' + csv(status) + ',' + number(cpu) + ',' + number(elapsed) + ','
                + (result == null ? 0 : result.pairs.size()) + ',' + repaired + ',' + unsupported
                + ',' + image.getStackSize() + ',' + image.getWidth() + ',' + image.getHeight()
                + ',' + csv(transformSeries(result, 0)) + ',' + csv(transformSeries(result, 1))
                + ',' + csv(transformSeries(result, 2)) + ',' + csv(policy) + ','
                + csv(estimate == null ? "" : estimate.recipe) + '\n';
    }

    private static List<Path> inputs(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root, 5)) {
            walk.filter(path -> path.getFileName().toString()
                    .equals("00_input_uncorrected.tif")).forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static String transformSeries(Registration.Result result, int component) {
        if (result == null) return "";
        StringBuilder out = new StringBuilder();
        for (int frame = 0; frame < result.cumulative.length; frame++) {
            if (frame > 0) out.append(';');
            Transform transform = result.cumulative[frame] == null
                    ? Transform.IDENTITY : result.cumulative[frame];
            out.append(number(component == 0 ? transform.dx
                    : component == 1 ? transform.dy : transform.theta));
        }
        return out.toString();
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        try {
            java.lang.reflect.Method method = bean.getClass().getMethod("getProcessCpuTime");
            method.setAccessible(true);
            return ((Number) method.invoke(bean)).longValue();
        } catch (Exception unavailable) {
            return System.nanoTime();
        }
    }

    private static String concise(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) message = root.getClass().getSimpleName();
        return (root.getClass().getSimpleName() + ':' + message)
                .replace('\n', ' ').replace('\r', ' ');
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static final class Estimate {
        final Registration.Result result;
        final String recipe;

        Estimate(Registration.Result result, String recipe) {
            this.result = result;
            this.recipe = recipe;
        }
    }
}
