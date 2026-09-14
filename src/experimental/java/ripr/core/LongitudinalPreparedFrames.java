/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

/** Same per-frame calculations and array order; no cross-frame arithmetic or nested workers. */
final class LongitudinalPreparedFrames {
    private LongitudinalPreparedFrames() { }
    static float[][] prepare(float[][] raw, int width, int height, boolean emission, int workers) {
        return prepare(raw,width,height,emission,workers,false);
    }

    static float[][] prepare(float[][] raw, int width, int height, boolean emission, int workers, boolean selectMedian) {
        float[][] features = new float[raw.length][];
        LongitudinalOrderedFrames.run(raw.length, workers, emission, i -> {
            features[i] = emission
                ? LongitudinalReferenceFeatures.emission(raw[i], width, height, OpenCvLongitudinalOps::gaussian,selectMedian)
                : LongitudinalReferenceFeatures.landmark(raw[i], width, height, LongitudinalStripedGaussian::filter,selectMedian);
        });
        return features;
    }
}
