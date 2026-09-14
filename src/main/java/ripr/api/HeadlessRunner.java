/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Estimate movement for one recording from outside the JVM, without ImageJ's user interface.
 *
 * <p>This is the entry point the Python package's Java backend calls. It exists so a caller that is
 * not written in Java can obtain <em>the transforms the plugin would produce</em>, at the plugin's
 * speed, rather than a second implementation's approximation of them.
 *
 * <p>Pixels arrive as a headerless little-endian binary blob and the shape and type are given as
 * arguments. That is deliberate: a TIFF round-trip costs more than the registration on a large
 * recording, and an ImageJ TIFF reader has its own opinions about what a three-slice 8-bit stack
 * means. A flat blob has no such opinions.
 *
 * <p>Only transforms come back. Applying them is cheap array work that the caller can do without
 * paying to move a whole registered stack back across the process boundary.
 *
 * <pre>
 * java -cp ripr.jar:ij.jar ripr.api.HeadlessRunner \
 *      input.raw width height frames dtype imageType motionType selectionMode channel threads out.csv
 * </pre>
 */
public final class HeadlessRunner {
    private HeadlessRunner() { }

    private static final int ARGUMENT_COUNT = 11;

    public static void main(String[] args) throws Exception {
        if (args.length != ARGUMENT_COUNT) {
            System.err.println("usage: input.raw width height frames dtype imageType motionType "
                    + "selectionMode channel threads output.csv");
            System.err.println("  dtype: uint8 | uint16 | int16 | float32");
            System.exit(2);
            return;
        }
        Path input = Paths.get(args[0]);
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        int frames = Integer.parseInt(args[3]);
        String dtype = args[4].toLowerCase(Locale.ROOT);
        ImageType imageType = ImageType.valueOf(args[5].toUpperCase(Locale.ROOT));
        MotionType motionType = MotionType.valueOf(args[6].toUpperCase(Locale.ROOT));
        SelectionMode selectionMode = SelectionMode.valueOf(args[7].toUpperCase(Locale.ROOT));
        int channel = Integer.parseInt(args[8]);
        int threads = Integer.parseInt(args[9]);
        Path output = Paths.get(args[10]);

        ImagePlus image = read(input, width, height, frames, dtype);

        // recommendation() copies the preset's preprocessing, norm, support and percentiles into the
        // parameters; setting the mode alone would leave them at builder defaults and quietly fit a
        // different recipe from the one the caller named.
        RelativeIntensityPatternParameters.Builder builder = RelativeIntensityPatternParameters
                .builder()
                .recommendation(imageType, motionType);
        if (selectionMode != SelectionMode.RECOMMENDED) {
            builder = builder.selectionMode(selectionMode);
        }
        RelativeIntensityPatternParameters parameters = builder
                .channel(channel)
                .threads(threads)
                .build();

        long started = System.nanoTime();
        Registration.Result result = RelativeIntensityPatternRegistration.estimate(
                image, parameters, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        double elapsed = (System.nanoTime() - started) / 1e9;

        try (PrintStream out = new PrintStream(new BufferedOutputStream(
                Files.newOutputStream(output)), false, StandardCharsets.UTF_8.name())) {
            out.printf(Locale.ROOT, "# elapsed_seconds,%.6f%n", elapsed);
            out.printf(Locale.ROOT, "# workers,%d%n", result.workers);
            out.printf(Locale.ROOT, "# levels,%d%n", result.levels);
            for (String warning : warnings(result)) {
                out.printf(Locale.ROOT, "# warning,%s%n", warning.replace('\n', ' ').replace(',', ';'));
            }
            out.println("frame,dx,dy,theta,log2_gain,support,residual_before,residual_after,"
                    + "valid_fraction,status,repair");
            for (int frame = 0; frame < result.cumulative.length; frame++) {
                Transform t = result.cumulative[frame];
                out.printf(Locale.ROOT, "%d,%s,%s,%s,%s,%d,%s,%s,%s,%s,%s%n",
                        frame,
                        repr(t.dx), repr(t.dy), repr(t.theta),
                        repr(result.log2Gain[frame]),
                        result.support[frame],
                        repr(result.residualBefore[frame]),
                        repr(result.residualAfter[frame]),
                        repr(result.validFraction[frame]),
                        result.status[frame] == null ? "" : result.status[frame].name(),
                        result.repairs[frame] == null ? "" : result.repairs[frame].name());
            }
        } finally {
            image.close();
        }
    }

    private static java.util.List<String> warnings(Registration.Result result) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (result.warnings != null) {
            for (Registration.Warning warning : result.warnings) {
                out.add(warning.kind + ": " + warning.message);
            }
        }
        return out;
    }

    /** One recording, read as frames x height x width in the caller's element type. */
    private static ImagePlus read(Path path, int width, int height, int frames, String dtype)
            throws Exception {
        int bytesPerSample = "uint8".equals(dtype) ? 1
                : ("float32".equals(dtype) ? 4 : 2);
        long expected = (long) width * height * frames * bytesPerSample;
        long actual = Files.size(path);
        if (actual != expected) {
            throw new IllegalArgumentException("expected " + expected + " bytes for " + frames + "x"
                    + height + "x" + width + " " + dtype + ", found " + actual);
        }
        ImageStack stack = new ImageStack(width, height);
        int planeBytes = width * height * bytesPerSample;
        byte[] buffer = new byte[planeBytes];
        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 20))) {
            for (int frame = 0; frame < frames; frame++) {
                in.readFully(buffer);
                stack.addSlice(null, new FloatProcessor(width, height,
                        toFloats(buffer, width * height, dtype), null));
            }
        }
        ImagePlus image = new ImagePlus("headless", stack);
        image.setDimensions(1, 1, frames);
        return image;
    }

    private static float[] toFloats(byte[] buffer, int count, String dtype) {
        float[] values = new float[count];
        ByteBuffer bytes = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        if ("uint8".equals(dtype)) {
            for (int i = 0; i < count; i++) values[i] = buffer[i] & 0xFF;
        } else if ("uint16".equals(dtype)) {
            for (int i = 0; i < count; i++) values[i] = bytes.getShort(i * 2) & 0xFFFF;
        } else if ("int16".equals(dtype)) {
            for (int i = 0; i < count; i++) values[i] = bytes.getShort(i * 2);
        } else if ("float32".equals(dtype)) {
            for (int i = 0; i < count; i++) values[i] = bytes.getFloat(i * 4);
        } else {
            throw new IllegalArgumentException("unsupported dtype " + dtype);
        }
        return values;
    }

    private static String repr(double value) {
        if (Double.isNaN(value)) return "nan";
        if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
        return Double.toString(value);
    }
}
