/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds each frame's log pyramid once and keeps a bounded number of them alive.
 *
 * <p><b>This class is the difference between the multi-lag strategy being practical and being
 * unusable.</b> Reconciling at lags 1, 2, 4, 8 and 16 needs about {@code 5T} pair alignments, and each
 * alignment needs two pyramids. Built on demand per pair that is {@code 10T} pyramid constructions for
 * {@code T} frames — an order of magnitude of pure waste, since each frame appears in ten pairs and its
 * pyramid is identical every time. Cached, it is {@code T} constructions. The alignment count is
 * unchanged; only the redundant work disappears.
 *
 * <p><b>Why bounded, and not simply all of them.</b> A pyramid costs roughly 18 bytes per
 * full-resolution pixel: three float arrays and a boolean mask at each level, summing over the levels to
 * about 1.33 times the base. For a 101-frame 512x512 recording, holding every pyramid is about 480 MB —
 * survivable but wasteful. For 1000 frames at 2048x2048 it is 75 GB, which is not. So capacity is
 * derived from a memory budget and from how far the strategy actually looks ahead: two frames for a
 * consecutive chain, {@code maxLag + 1} for multi-lag, and a constant for fixed-reference, plus slack
 * for the workers in flight.
 *
 * <p>Eviction is least-recently-used. Because the scheduler visits pairs in index order, LRU behaves as
 * a sliding window over the recording and the hit rate is essentially one.
 *
 * <p>Thread-safe. Two workers asking for the same frame at the same time build it once: the second waits
 * on the first rather than duplicating the work, which matters because building a pyramid is the
 * expensive thing this class exists to avoid doing twice.
 */
public final class PyramidCache {

    /** Bytes of pyramid per full-resolution pixel: 3 floats and a boolean per level, 1.33x for the
     *  levels below the base. Measured by counting arrays, not by profiling — treat as indicative. */
    public static final long BYTES_PER_PIXEL = 18;

    /**
     * Extra bytes per full-resolution pixel when an estimator keeps a prepared view on each plane.
     *
     * <p>{@link PairEstimator.Kind#AREA_CORRELATION} caches two float arrays per level — the centred
     * intensities and their B-spline coefficients — so it does not rebuild them on all nine pairs a
     * frame appears in. Two floats is 8 bytes per pixel per level, and the same 1.33x over the levels
     * below the base gives 11. A pyramid held for that estimator therefore costs about
     * {@code BYTES_PER_PIXEL + AREA_WINDOW_BYTES_PER_PIXEL} = 29 bytes per pixel, roughly 1.6x the
     * bare pyramid, and the capacity that memory buys must be computed from the larger figure or the
     * cache trades time for an out-of-memory failure on a long recording. Ask the estimator with
     * {@link PairEstimator#cachedBytesPerPixel()} rather than assuming either value.
     */
    public static final long AREA_WINDOW_BYTES_PER_PIXEL = 11;

    /** Builds the pyramid for one frame. Called at most once per frame per cache. */
    public interface Builder {
        LogPlane[] build(int frame);
    }

    private final Builder builder;
    private final int capacity;
    private final LinkedHashMap<Integer, LogPlane[]> entries;
    private final Map<Integer, Object> locks = new HashMap<>();
    private long hits;
    private long misses;

    public PyramidCache(Builder builder, int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2, was " + capacity
                    + " — a pair alignment needs two pyramids live at once");
        }
        this.builder = builder;
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(capacity * 2, 0.75f, true);
    }

    /**
     * Capacity for a run: enough for the strategy's reach and the workers in flight, then as much more
     * as the memory budget allows, never more than the frame count.
     *
     * @param budgetBytes memory to spend on pyramids; 0 or less takes a quarter of the heap
     */
    public static int capacityFor(int frames, int width, int height, int reach, int workers,
                                  long budgetBytes) {
        return capacityFor(frames, width, height, reach, workers, budgetBytes, BYTES_PER_PIXEL);
    }

    /**
     * The same, for a caller whose pyramids cost more than a bare pyramid because an estimator keeps
     * a prepared view on each plane. Pass {@code BYTES_PER_PIXEL + estimator.cachedBytesPerPixel()}.
     *
     * @param bytesPerPixel bytes one resident pyramid costs per full-resolution pixel
     */
    public static int capacityFor(int frames, int width, int height, int reach, int workers,
                                  long budgetBytes, long bytesPerPixel) {
        int minimum = Math.max(2, reach + 1 + Math.max(1, workers));
        long perFrame = Math.max(1, bytesPerPixel * (long) width * height);
        // Defaulting to the bare minimum was a false economy: the minimum is the strategy's reach,
        // which is exactly the size at which any imperfection in access order thrashes. A quarter of
        // the heap holds every pyramid of a typical recording and still leaves room for the stack
        // itself, the output, and whatever else Fiji has open.
        long available = budgetBytes > 0 ? budgetBytes : Runtime.getRuntime().maxMemory() / 4;
        long affordable = available / perFrame;
        int capacity = (int) Math.max(minimum, Math.min(frames, affordable));
        return Math.max(2, Math.min(capacity, Math.max(2, frames)));
    }

    /**
     * How many frames a strategy needs live at once.
     *
     * <p>Not the same as the pair count: a consecutive chain only ever holds two, however long the
     * recording, whereas multi-lag must keep the whole longest-lag window.
     */
    public static int reachFor(Reconciler.Reference reference, int lag, int[] lags) {
        switch (reference) {
            case MULTILAG:
                int max = 1;
                if (lags != null) {
                    for (int k : lags) max = Math.max(max, k);
                }
                return max;
            case FIXED:
                return 2;                       // the reference frame plus the one being aligned
            case ROLLING:
                return 2;
            case CONSECUTIVE:
            default:
                return Math.max(1, lag);
        }
    }

    /** The pyramid for {@code frame}, building it if it is not resident. */
    public LogPlane[] get(int frame) {
        Object lock;
        synchronized (this) {
            LogPlane[] hit = entries.get(frame);
            if (hit != null) {
                hits++;
                return hit;
            }
            lock = locks.computeIfAbsent(frame, k -> new Object());
        }
        // Built outside the cache lock: construction is the expensive part and holding the lock
        // through it would serialise every worker onto one core.
        synchronized (lock) {
            synchronized (this) {
                LogPlane[] hit = entries.get(frame);
                if (hit != null) {
                    hits++;
                    return hit;
                }
            }
            LogPlane[] built = builder.build(frame);
            synchronized (this) {
                misses++;
                entries.put(frame, built);
                evict();
                locks.remove(frame);
            }
            return built;
        }
    }

    /** Drop everything. Called when a run finishes so a long-lived cache does not pin memory. */
    public synchronized void clear() {
        entries.clear();
        locks.clear();
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Caller must hold the monitor. */
    private void evict() {
        while (entries.size() > capacity) {
            Integer oldest = entries.keySet().iterator().next();
            if (oldest == null) return;
            entries.remove(oldest);
        }
    }
}
