/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Arrays;

/** Small native-library contract checks, independent of the microscopy fixtures. */
final class OpenCvLongitudinalContractTest {
    static void run() {
        int width = 48, height = 40;
        float[] flat = new float[width * height];
        Arrays.fill(flat, 1f);
        for (float value : OpenCvLongitudinalOps.gaussian(flat, width, height, 2))
            require(Math.abs(value - 1) < 1e-6, "Gaussian must preserve a constant image");
        float[] frame = new float[flat.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            frame[y * width + x] = (float) (Math.sin(x * .31) + Math.cos(y * .27)
                    + .3 * Math.sin((x + y) * .49));
        float[] original = frame.clone(), seed = {1, 0, 0, 0, 1, 0}, originalSeed = seed.clone();
        float[] shifted = new float[frame.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            shifted[y*width+x] = frame[Math.floorMod(y+2,height)*width+Math.floorMod(x-3,width)];
        float[] phase = OpenCvLongitudinalOps.phase(frame, shifted, width, height);
        require(phase[2] == 3 && phase[5] == -2,
                "Phase seed must recover a wrapped translation with the sampling-matrix sign");
        OpenCvLongitudinalOps.Fit first = OpenCvLongitudinalOps.fit(frame, frame, width, height, seed, 3);
        require(first.score > .99999, "Identical structured frames must correlate");
        require(Math.abs(first.matrix[2]) < .001 && Math.abs(first.matrix[5]) < .001,
                "Identical frames must not translate");
        for (int repeat = 0; repeat < 12; repeat++) {
            OpenCvLongitudinalOps.Fit next = OpenCvLongitudinalOps.fit(frame, frame, width, height, seed, 3);
            require(Arrays.equals(first.matrix, next.matrix)
                    && Double.doubleToLongBits(first.score) == Double.doubleToLongBits(next.score),
                    "Repeated calls must preserve output bits");
        }
        require(Arrays.equals(frame, original) && Arrays.equals(seed, originalSeed),
                "Native operations must not modify caller-owned inputs");
        reject(() -> OpenCvLongitudinalOps.fit(frame, frame, width, height, seed, 2));
        reject(() -> OpenCvLongitudinalOps.gaussian(new float[3], width, height, 2));
        float[] invalid = frame.clone(); invalid[0] = Float.NaN;
        reject(() -> OpenCvLongitudinalOps.gaussian(invalid, width, height, 2));
        boolean failed = false;
        try { OpenCvLongitudinalOps.fit(flat, flat, width, height, seed, 3); }
        catch (RuntimeException expected) { failed = true; }
        require(failed, "Flat images must fail explicitly, not manufacture an alignment");
    }

    private static void reject(Runnable operation) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Malformed input was accepted");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
