/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.LogPlane;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The reference-frame pixel-selection mask, and the information score behind it, for one frame.
 *
 * <p>The whole-recording harness said a recording using pixel selection diverges while one not using
 * it is bit-exact. Pixel selection is decided on a single frame, so dumping that decision is far
 * cheaper than running two registrations, and it names the array rather than the symptom.
 */
public final class SpatialMaskProbe {

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("usage: raw width height epsilon removePercent outputDirectory");
            System.exit(2);
        }
        Path raw = Paths.get(args[0]).toAbsolutePath();
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        double epsilon = Double.parseDouble(args[3]);
        double removePercent = Double.parseDouble(args[4]);
        Path out = Paths.get(args[5]).toAbsolutePath();
        Files.createDirectories(out);

        byte[] bytes = Files.readAllBytes(raw);
        int pixels = width * height;
        if (bytes.length < pixels) {
            throw new IllegalArgumentException("blob holds " + bytes.length + " bytes, need " + pixels);
        }
        float[] intensity = new float[pixels];
        for (int i = 0; i < pixels; i++) intensity[i] = bytes[i] & 0xFF;

        LogPlane reference = LogPlane.of(intensity, width, height, epsilon);
        LagPixelSelector.Scores scores = LagPixelSelector.spatialInformationScore(reference);
        boolean[] keep = LagPixelSelector.mask(
                scores, LagPixelSelector.Score.INFORMATION, removePercent, false);

        double[] information = scores.values(LagPixelSelector.Score.INFORMATION);
        writeBytes(out.resolve("keep.u8"), keep);
        writeBytes(out.resolve("eligible.u8"), scores.eligible);
        writeDoubles(out.resolve("information.f64"), information);

        int removed = 0;
        for (boolean k : keep) if (!k) removed++;
        System.out.printf("eligible=%d removed=%d of %d pixels%n",
                scores.eligibleCount, removed, pixels);
    }

    private static void writeBytes(Path path, boolean[] values) throws Exception {
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16)) {
            for (boolean value : values) stream.write(value ? 1 : 0);
        }
    }

    private static void writeDoubles(Path path, double[] values) throws Exception {
        try (DataOutputStream data = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
            for (double value : values) {
                long bits = Double.doubleToRawLongBits(value);
                for (int shift = 0; shift < 64; shift += 8) data.write((int) (bits >>> shift) & 0xFF);
            }
        }
    }

    private SpatialMaskProbe() {
    }
}
