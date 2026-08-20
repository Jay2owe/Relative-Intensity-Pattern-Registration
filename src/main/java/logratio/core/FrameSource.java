/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

/**
 * The frames the estimator sees, as plain float planes.
 *
 * <p>This interface is the boundary that keeps {@code logratio.core} free of any ImageJ class. The
 * engine can therefore be unit-tested against synthetic frames with no {@code ij} on the classpath, and
 * embedded by another plugin that has its own idea of where pixels come from. The ImageJ-side
 * implementation lives outside this package and is responsible for the channel, slice and projection
 * choices — by the time a plane arrives here, "which channel drives the estimate" has already been
 * decided.
 *
 * <p>{@link #plane} is called from worker threads, concurrently and possibly more than once for the same
 * index, so an implementation must either return a fresh array or guarantee that the array it returns is
 * never mutated afterwards.
 */
public interface FrameSource {

    /** Number of frames along the registered axis. */
    int count();

    int width();

    int height();

    /**
     * Intensities for one frame, row-major, {@code width() * height()} long.
     *
     * <p>{@link Float#NaN} marks a pixel with no measurement; it will be excluded rather than
     * propagated. Values may be negative — a 32-bit image is allowed to be.
     *
     * @param frame zero-based
     */
    float[] plane(int frame);
}
