/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import ripr.StackFrames;

import java.nio.file.Paths;
import java.util.Arrays;

/** Prints direct pair and median-block rigid evidence at one requested boundary. */
public final class LongitudinalBoundaryProbe {
    private LongitudinalBoundaryProbe() { }

    public static void main(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: input.tif boundary_one_based");
        }
        int boundary = Integer.parseInt(args[1]) - 1;
        ImagePlus image = IJ.openImage(Paths.get(args[0]).toAbsolutePath().toString());
        if (image == null) throw new IllegalArgumentException("could not open " + args[0]);
        try {
            StackFrames source = StackFrames.of(image, 1, 0);
            if (boundary < 1 || boundary >= source.count()) {
                throw new IllegalArgumentException("boundary outside recording");
            }
            LongitudinalRegistration.Fit pair = LongitudinalRegistration.rigidBridge(
                    source.plane(boundary - 1), source.plane(boundary),
                    source.width(), source.height(), 15, null);
            int blockWidth = Math.min(4, Math.min(boundary, source.count() - boundary));
            LongitudinalRegistration.Fit block = LongitudinalRegistration.rigidBridge(
                    median(source, boundary - blockWidth, boundary),
                    median(source, boundary, boundary + blockWidth),
                    source.width(), source.height(), 15, null);
            System.out.println("boundary=" + (boundary + 1)
                    + " pair=" + pair.transform + " pair_score=" + pair.score
                    + " block_width=" + blockWidth
                    + " block=" + block.transform + " block_score=" + block.score
                    + " shift_disagreement=" + Math.hypot(
                    pair.transform.dx - block.transform.dx,
                    pair.transform.dy - block.transform.dy)
                    + " angle_disagreement_degrees=" + Math.abs(
                    pair.transform.thetaDegrees() - block.transform.thetaDegrees()));
        } finally {
            image.close();
        }
    }

    private static float[] median(StackFrames source, int start, int end) {
        float[][] frames = new float[end - start][];
        for (int i = 0; i < frames.length; i++) frames[i] = source.plane(start + i);
        float[] output = new float[source.width() * source.height()];
        float[] values = new float[frames.length];
        for (int pixel = 0; pixel < output.length; pixel++) {
            for (int frame = 0; frame < frames.length; frame++) values[frame] = frames[frame][pixel];
            Arrays.sort(values);
            int middle = values.length / 2;
            output[pixel] = values.length % 2 == 1 ? values[middle]
                    : 0.5f * (values[middle - 1] + values[middle]);
        }
        return output;
    }
}
