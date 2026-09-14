/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import ripr.PreprocessedFrameSource;
import ripr.StackFrames;
import ripr.api.Preprocessing;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Dumps the intermediate planes behind one frame so Python can diff them element by element.
 *
 * <p>The whole-recording probe says a run diverged; this one says which array diverged first. Every
 * plane is written as little-endian {@code float32} in row-major order, alongside a text manifest of
 * shapes, so the Python side reads them without needing to agree about any container format.
 */
public final class ComponentParityProbe {
    private ComponentParityProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException("source.tif frame channel levels output-directory [epsilon]");
        }
        ImagePlus image = IJ.openImage(Paths.get(args[0]).toAbsolutePath().toString());
        if (image == null) throw new IllegalArgumentException("could not open " + args[0]);
        int frame = Integer.parseInt(args[1]);
        int channel = Integer.parseInt(args[2]);
        int levels = Integer.parseInt(args[3]);
        Path out = Paths.get(args[4]);
        double epsilon = args.length > 5 ? Double.parseDouble(args[5]) : 1.0;
        Preprocessing preprocessing = args.length > 6
                ? Preprocessing.from(args[6]) : Preprocessing.NONE;
        Files.createDirectories(out);

        try {
            StackFrames raw = StackFrames.of(image, channel, 0);
            // The fit never sees raw pixels when a recipe names a filter, so compare what it does see.
            FrameSource source = preprocessing == Preprocessing.NONE
                    ? raw : new PreprocessedFrameSource(raw, preprocessing);
            float[] intensity = source.plane(frame);
            int width = source.width();
            int height = source.height();

            writeFloats(out.resolve("intensity.f32"), intensity);
            LogPlane plane = LogPlane.of(intensity, width, height, epsilon);
            StringBuilder manifest = new StringBuilder();
            manifest.append("intensity,").append(height).append(',').append(width).append('\n');

            LogPlane[] pyramid = plane.pyramid(levels);
            for (int level = 0; level < pyramid.length; level++) {
                LogPlane p = pyramid[level];
                writeFloats(out.resolve("level" + level + "_v.f32"), p.v);
                writeFloats(out.resolve("level" + level + "_valid.f32"), asFloats(p.valid));
                writeFloats(out.resolve("level" + level + "_gx.f32"), p.gx);
                writeFloats(out.resolve("level" + level + "_gy.f32"), p.gy);
                manifest.append("level").append(level).append(',')
                        .append(p.height).append(',').append(p.width).append('\n');
            }
            Files.write(out.resolve("manifest.csv"), manifest.toString().getBytes("UTF-8"));
            System.out.println("wrote " + pyramid.length + " levels to " + out);
        } finally {
            image.close();
        }
    }

    private static float[] asFloats(boolean[] flags) {
        float[] out = new float[flags.length];
        for (int i = 0; i < flags.length; i++) out[i] = flags[i] ? 1f : 0f;
        return out;
    }

    private static void writeFloats(Path path, float[] values) throws Exception {
        try (OutputStream stream = Files.newOutputStream(path);
             DataOutputStream data = new DataOutputStream(new java.io.BufferedOutputStream(stream))) {
            for (float value : values) {
                // Little-endian, so NumPy reads it with '<f4' and no byte-swapping step.
                int bits = Float.floatToRawIntBits(value);
                data.write(bits & 0xFF);
                data.write((bits >>> 8) & 0xFF);
                data.write((bits >>> 16) & 0xFF);
                data.write((bits >>> 24) & 0xFF);
            }
        }
    }
}
