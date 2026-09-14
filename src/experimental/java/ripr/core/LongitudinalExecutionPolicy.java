/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

/** Implementation-only experimental switches: never change a scientific recipe. */
public final class LongitudinalExecutionPolicy {
    public static final LongitudinalExecutionPolicy BASELINE = new LongitudinalExecutionPolicy(1, false, false);
    public final int frameWorkers;
    public final int preliminaryWorkers, featureWorkers, endpointWorkers;
    public final boolean cacheSpectra, reuseBuffers;
    public final boolean selectMedian;
    public final boolean reuseSmoothing;

    public LongitudinalExecutionPolicy(int frameWorkers, boolean cacheSpectra, boolean reuseBuffers) {
        this(frameWorkers, cacheSpectra, reuseBuffers, 1, 1, 1);
    }

    public LongitudinalExecutionPolicy(int frameWorkers, boolean cacheSpectra, boolean reuseBuffers,
            int preliminaryWorkers, int featureWorkers, int endpointWorkers) {
        this(frameWorkers,cacheSpectra,reuseBuffers,preliminaryWorkers,featureWorkers,endpointWorkers,false);
    }

    public LongitudinalExecutionPolicy(int frameWorkers, boolean cacheSpectra, boolean reuseBuffers,
            int preliminaryWorkers, int featureWorkers, int endpointWorkers, boolean selectMedian) {
        this(frameWorkers,cacheSpectra,reuseBuffers,preliminaryWorkers,featureWorkers,endpointWorkers,selectMedian,false);
    }

    public LongitudinalExecutionPolicy(int frameWorkers, boolean cacheSpectra, boolean reuseBuffers,
            int preliminaryWorkers, int featureWorkers, int endpointWorkers, boolean selectMedian, boolean reuseSmoothing) {
        for (int workers : new int[]{frameWorkers, preliminaryWorkers, featureWorkers, endpointWorkers})
            if (workers < 1 || workers > 16)
                throw new IllegalArgumentException("Expected one to sixteen independent workers");
        this.frameWorkers = frameWorkers;
        this.preliminaryWorkers = preliminaryWorkers;
        this.featureWorkers = featureWorkers;
        this.endpointWorkers = endpointWorkers;
        this.selectMedian = selectMedian;
        if(reuseSmoothing && !reuseBuffers)throw new IllegalArgumentException("Smoothing reuse requires pair-owned buffers");
        this.reuseSmoothing = reuseSmoothing;
        this.cacheSpectra = cacheSpectra;
        this.reuseBuffers = reuseBuffers;
    }
}
