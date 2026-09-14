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
import java.util.List;

/** Prints the pair and block evidence behind each real transmitted-light jump candidate. */
public final class LongitudinalRigidProbe {
    private LongitudinalRigidProbe() { }

    public static void main(String[] args) {
        ImagePlus image = IJ.openImage(Paths.get(args[0]).toAbsolutePath().toString());
        if (image == null) throw new IllegalArgumentException("could not open " + args[0]);
        try {
            StackFrames source = StackFrames.of(image, 1, 0);
            float[][] features = new float[source.count()][];
            Transform[] baseline = new Transform[source.count()];
            Arrays.fill(baseline, Transform.IDENTITY);
            for (int frame = 0; frame < source.count(); frame++) {
                features[frame] = LongitudinalRegistration.landmarkFeature(
                        source.plane(frame), source.width(), source.height());
            }
            List<Integer> boundaries = LongitudinalRegistration.lowConfidenceBoundaries(
                    features, baseline, source.width(), source.height());
            System.out.println("boundaries=" + boundaries);
            for (int boundary : boundaries) {
                int width = Math.min(8, Math.min(boundary, source.count() - boundary));
                if (width < 3) continue;
                LongitudinalRegistration.Fit pair = LongitudinalRegistration.rigidBridge(
                        source.plane(boundary - 1), source.plane(boundary),
                        source.width(), source.height(), 10, null);
                float[] before = median(source, boundary - width, boundary);
                float[] after = median(source, boundary, boundary + width);
                LongitudinalRegistration.Fit block = LongitudinalRegistration.rigidBridge(
                        before, after, source.width(), source.height(), 10, null);
                System.out.println("boundary=" + (boundary + 1) + " pair=" + pair.transform
                        + " score=" + pair.score + " block=" + block.transform
                        + " score=" + block.score + " shift_disagreement="
                        + Math.hypot(pair.transform.dx - block.transform.dx,
                        pair.transform.dy - block.transform.dy)
                        + " angle_disagreement="
                        + Math.abs(pair.transform.thetaDegrees() - block.transform.thetaDegrees()));
            }
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
