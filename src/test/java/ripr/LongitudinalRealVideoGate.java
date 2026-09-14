/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.SelectionMode;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Command-line real-video gate for the packaged ImageJ longitudinal route. */
public final class LongitudinalRealVideoGate {
    private LongitudinalRealVideoGate() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: source.tif IMAGE_TYPE output.csv [threads]");
        }
        Path source = Paths.get(args[0]).toAbsolutePath().normalize();
        ImageType type = ImageType.from(args[1]);
        Path output = Paths.get(args[2]).toAbsolutePath().normalize();
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        ImagePlus image = IJ.openImage(source.toString());
        if (image == null) throw new IllegalArgumentException("ImageJ could not open " + source);
        RelativeIntensityPatternParameters parameters =
                RelativeIntensityPatternParameters.builder()
                        .recommendation(type, MotionType.INTERMITTENT_JUMPS)
                        .selectionMode(SelectionMode.LONGITUDINAL_ACCURACY)
                        .threads(threads).crop(false).build();
        long started = System.nanoTime();
        AtomicProgress progress = new AtomicProgress();
        Registration.Result result;
        try {
            result = RelativeIntensityPatternRegistration.estimate(
                    image, parameters, progress, PairScheduler.Cancellation.NEVER);
        } finally {
            image.close();
        }
        Files.createDirectories(output.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {
            writer.write("frame_one_based,dx_px,dy_px,theta_degrees");
            writer.newLine();
            for (int frame = 0; frame < result.cumulative.length; frame++) {
                Transform value = result.cumulative[frame];
                writer.write(String.format(Locale.ROOT, "%d,%.12f,%.12f,%.12f",
                        frame + 1, value.dx, value.dy, value.thetaDegrees()));
                writer.newLine();
            }
        }
        double elapsed = (System.nanoTime() - started) / 1e9;
        double maximumTranslation = 0.0;
        double maximumRotation = 0.0;
        for (Transform value : result.cumulative) {
            maximumTranslation = Math.max(maximumTranslation, value.magnitude());
            maximumRotation = Math.max(maximumRotation, Math.abs(value.thetaDegrees()));
        }
        Path summary = output.resolveSibling("summary.json");
        String summaryText = String.format(Locale.ROOT,
                "{\n"
                + "  \"source\": \"%s\",\n"
                + "  \"image_type\": \"%s\",\n"
                + "  \"selection_mode\": \"longitudinal_accuracy\",\n"
                + "  \"channel_one_based\": 1,\n"
                + "  \"channels_read\": 1,\n"
                + "  \"artificial_motion\": false,\n"
                + "  \"frames\": %d,\n"
                + "  \"elapsed_seconds\": %.6f,\n"
                + "  \"maximum_translation_px\": %.12f,\n"
                + "  \"maximum_rotation_degrees\": %.12f\n"
                + "}\n",
                json(source.toString()), type.name(), result.cumulative.length,
                elapsed, maximumTranslation, maximumRotation);
        Files.write(summary, summaryText.getBytes(StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT, "frames=%d elapsed_seconds=%.3f output=%s%n",
                result.cumulative.length, elapsed, output);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class AtomicProgress implements PairScheduler.Progress {
        private volatile String last = "";
        @Override public void begin(int total, int workers) {
            System.out.println("pairwise fitting 0/" + total + " workers=" + workers);
        }
        @Override public void taskStarted(int index, int total) { }
        @Override public void update(int done, int total) {
            if (done == total || done % 25 == 0) {
                System.out.println("pairwise fitting " + done + "/" + total);
            }
        }
        @Override public void phase(String name, int done, int total) {
            String value = name + " " + done + "/" + total;
            if (!value.equals(last) && (done == total || done == 0 || done % 10 == 0)) {
                last = value;
                System.out.println(value);
            }
        }
    }
}
