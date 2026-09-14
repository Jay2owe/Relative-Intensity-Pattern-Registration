/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.PixelSelectionStrategy;
import ripr.api.RegistrationRecipe;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.SelectionMode;
import ripr.core.PairEstimator;
import ripr.core.PairScheduler;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Same-channel-only real-stack tuning runner. It never accepts a multi-channel input. */
public final class SingleChannelFailureTuningBenchmark {

    enum Attempt {
        A000_CURRENT_AUTOMATIC,
        A001_PREVIOUS_LOGRATIO,
        A002_MULTILAG_ECC,
        A003_PREVIOUS_ECC,
        A004_PREVIOUS_NORMALISED_CORRELATION,
        A005_PREVIOUS_REMOVE_MOST_UNSTABLE_25,
        A006_PREVIOUS_REMOVE_MOST_LAG_GROWTH_25;

        static Attempt from(String value) {
            String wanted = value.trim().toUpperCase(Locale.ROOT);
            for (Attempt item : values()) {
                if (item.name().equals(wanted) || item.name().startsWith(wanted + "_")) return item;
            }
            throw new IllegalArgumentException("unknown attempt " + value);
        }

        String id() {
            return name().substring(0, 4);
        }
    }

    private SingleChannelFailureTuningBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: <single-channel-input-dir> <cases.csv> <output.csv> <A000..A006>");
        }
        run(Paths.get(args[0]).toAbsolutePath().normalize(),
                Paths.get(args[1]).toAbsolutePath().normalize(),
                Paths.get(args[2]).toAbsolutePath().normalize(), Attempt.from(args[3]));
    }

    static void run(Path inputDir, Path casesFile, Path output, Attempt attempt) throws Exception {
        List<Map<String, String>> cases = readCsv(casesFile);
        if (cases.size() != 24) throw new IOException("expected 24 frozen cases, found " + cases.size());
        if (Files.exists(output)) throw new IOException("refusing to overwrite " + output);
        Files.createDirectories(output.getParent());
        String header = "attempt_id,case_id,role,issue,channel,recording,status,error,"
                + "cpu_seconds,elapsed_seconds,pair_count,repaired_frames,unsupported_frames,"
                + "median_residual_before,median_residual_after,reference,estimator,pixel_selection,"
                + "resolved_recipe,channels_read,transform_x_px,transform_y_px,transform_theta_rad\n";
        Files.write(output, header.getBytes(StandardCharsets.UTF_8));

        boolean warmed = false;
        int done = 0;
        for (Map<String, String> item : cases) {
            Path input = inputDir.resolve(item.get("case_id") + ".tif");
            ImagePlus image = IJ.openImage(input.toString());
            if (image == null) throw new IOException("could not open " + input);
            try {
                if (image.getNChannels() != 1) {
                    throw new IOException("cross-channel input prohibited: " + input
                            + " has " + image.getNChannels() + " channels");
                }
                RelativeIntensityPatternParameters requested = requested(item);
                AutomaticRegistrationSelector.Result selection =
                        RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, requested);
                RelativeIntensityPatternParameters parameters = apply(selection.parameters, attempt);
                if (!warmed) {
                    try {
                        RelativeIntensityPatternRegistration.estimate(image, parameters,
                                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
                    } catch (RuntimeException ignored) {
                        // The excluded warm-up follows the identical route.
                    }
                    warmed = true;
                }
                Files.write(output, measure(image, item, parameters, attempt)
                                .getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.APPEND);
                System.out.printf(Locale.ROOT, "%s %s %d/%d%n", attempt.id(),
                        item.get("case_id"), ++done, cases.size());
            } finally {
                image.close();
            }
        }
    }

    static RelativeIntensityPatternParameters apply(
            RelativeIntensityPatternParameters base, Attempt attempt) {
        RelativeIntensityPatternParameters.Builder builder = base.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .crop(false)
                .threads(4);
        switch (attempt) {
            case A000_CURRENT_AUTOMATIC:
                break;
            case A001_PREVIOUS_LOGRATIO:
                builder.reference(Reconciler.Reference.CONSECUTIVE);
                break;
            case A002_MULTILAG_ECC:
                builder.estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                        .pixelSelectionStrategy(PixelSelectionStrategy.NONE);
                break;
            case A003_PREVIOUS_ECC:
                builder.reference(Reconciler.Reference.CONSECUTIVE)
                        .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                        .pixelSelectionStrategy(PixelSelectionStrategy.NONE);
                break;
            case A004_PREVIOUS_NORMALISED_CORRELATION:
                builder.reference(Reconciler.Reference.CONSECUTIVE)
                        .estimator(PairEstimator.Kind.AREA_CORRELATION)
                        .pixelSelectionStrategy(PixelSelectionStrategy.NONE);
                break;
            case A005_PREVIOUS_REMOVE_MOST_UNSTABLE_25:
                builder.reference(Reconciler.Reference.CONSECUTIVE)
                        .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_MOST_UNSTABLE)
                        .pixelRemovalPercent(25.0);
                break;
            case A006_PREVIOUS_REMOVE_MOST_LAG_GROWTH_25:
                builder.reference(Reconciler.Reference.CONSECUTIVE)
                        .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_MOST_LAG_GROWTH)
                        .pixelRemovalPercent(25.0);
                break;
            default:
                throw new IllegalStateException("unhandled attempt " + attempt);
        }
        return builder.recipeProvenance("R01 " + attempt.name()).build();
    }

    private static RelativeIntensityPatternParameters requested(Map<String, String> item) {
        ImageType image = "Per2".equals(item.get("channel"))
                ? ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE : ImageType.DENSE_FLUORESCENCE;
        return RelativeIntensityPatternParameters.builder()
                .recommendation(image, MotionType.valueOf(item.get("motion")))
                .selectionMode(SelectionMode.AUTOMATIC)
                .crop(false)
                .threads(4)
                .build();
    }

    private static String measure(ImagePlus image, Map<String, String> item,
                                  RelativeIntensityPatternParameters parameters, Attempt attempt) {
        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();
        Registration.Result result = null;
        String status = "ok";
        String error = "";
        try {
            result = RelativeIntensityPatternRegistration.estimate(image, parameters,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        } catch (RuntimeException failure) {
            status = "error";
            error = concise(failure);
        }
        double elapsed = (System.nanoTime() - wall0) / 1e9;
        double cpu = (processCpuNanos() - cpu0) / 1e9;
        int repaired = 0;
        int unsupported = 0;
        if (result != null) {
            for (int frame = 0; frame < result.repairs.length; frame++) {
                if (result.repairs[frame] != null) repaired++;
                if (result.support[frame] == 0) unsupported++;
            }
        }
        return csv(attempt.id()) + ',' + csv(item.get("case_id")) + ','
                + csv(item.get("role")) + ',' + csv(item.get("issue")) + ','
                + csv(item.get("channel")) + ',' + csv(item.get("recording")) + ','
                + csv(status) + ',' + csv(error) + ',' + number(cpu) + ',' + number(elapsed)
                + ',' + (result == null ? 0 : result.pairs.size()) + ',' + repaired + ','
                + unsupported + ',' + number(result == null ? Double.NaN : median(result.residualBefore))
                + ',' + number(result == null ? Double.NaN : median(result.residualAfter)) + ','
                + csv(parameters.reference.name()) + ',' + csv(parameters.estimator.name()) + ','
                + csv(parameters.pixelSelectionStrategy.name()) + ','
                + csv(RegistrationRecipe.of(parameters).id()) + ",1,"
                + csv(transformSeries(result, 0)) + ',' + csv(transformSeries(result, 1)) + ','
                + csv(transformSeries(result, 2)) + '\n';
    }

    private static double median(double[] values) {
        double[] finite = java.util.Arrays.stream(values).filter(Double::isFinite).sorted().toArray();
        return finite.length == 0 ? Double.NaN : finite[finite.length / 2];
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

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("empty CSV " + path);
        List<String> header = parseCsv(lines.get(0));
        List<Map<String, String>> out = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).trim().isEmpty()) continue;
            List<String> values = parseCsv(lines.get(line));
            if (values.size() != header.size()) throw new IOException("bad CSV row " + (line + 1));
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), values.get(i));
            out.add(row);
        }
        return out;
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(value.toString()); value.setLength(0);
            } else value.append(c);
        }
        out.add(value.toString());
        return out;
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

    private static String concise(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return (root.getClass().getSimpleName() + ':'
                + (message == null ? "" : message)).replace('\n', ' ').replace('\r', ' ');
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}

