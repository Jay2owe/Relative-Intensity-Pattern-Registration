/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The parallel contract: deterministic order, bounded workers, clean cancellation and failure. */
public class PairSchedulerTest {

    /**
     * Results must land at their planned index whatever the completion order.
     *
     * <p>Forced here by making early tasks slow and late tasks fast, so completion order is close to
     * reversed. A table whose row order depended on this would be a different scientific output on
     * every run.
     */
    @Test
    public void resultsAreInIndexOrderUnderReversedCompletion() {
        int n = 32;
        List<Integer> out = PairScheduler.map(n, 8, index -> {
            Thread.sleep((n - index) % 8);
            return index * index;
        }, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        for (int i = 0; i < n; i++) {
            assertEquals("index " + i, Integer.valueOf(i * i), out.get(i));
        }
    }

    @Test
    public void serialAndParallelProduceTheSameList() {
        int n = 40;
        PairScheduler.Task<Integer> task = index -> index * 7 + 1;
        List<Integer> serial = PairScheduler.map(n, 1, task,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        List<Integer> parallel = PairScheduler.map(n, 6, task,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        assertEquals(serial, parallel);
    }

    @Test
    public void progressReachesTheTotal() {
        int n = 20;
        AtomicInteger last = new AtomicInteger();
        PairScheduler.map(n, 4, index -> index,
                (done, total) -> {
                    assertEquals(n, total);
                    last.accumulateAndGet(done, Math::max);
                },
                PairScheduler.Cancellation.NEVER);
        assertEquals(n, last.get());
    }

    @Test
    public void cancellationBeforeAnyWorkThrows() {
        try {
            PairScheduler.map(50, 4, index -> index,
                    PairScheduler.Progress.NONE, () -> true);
            fail("expected cancellation");
        } catch (CancellationException expected) {
            // the contract
        }
    }

    /** Cancelling part-way must stop the run rather than quietly returning a half-filled list. */
    @Test
    public void cancellationPartWayThroughThrows() {
        AtomicInteger started = new AtomicInteger();
        try {
            PairScheduler.map(200, 4, index -> {
                started.incrementAndGet();
                Thread.sleep(1);
                return index;
            }, PairScheduler.Progress.NONE, () -> started.get() > 20);
            fail("expected cancellation");
        } catch (CancellationException expected) {
            assertTrue("should have stopped well short of 200: " + started.get(),
                    started.get() < 200);
        }
    }

    /** The first failure propagates, and nothing is left running. */
    @Test
    public void firstFailurePropagates() {
        try {
            PairScheduler.map(30, 4, index -> {
                if (index == 7) throw new IllegalStateException("boom at 7");
                return index;
            }, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            fail("expected the failure to propagate");
        } catch (IllegalStateException expected) {
            assertEquals("boom at 7", expected.getMessage());
        }
    }

    /** A checked exception is wrapped with the index that caused it, not swallowed. */
    @Test
    public void checkedExceptionsAreWrappedWithTheirIndex() {
        try {
            PairScheduler.map(10, 1, index -> {
                if (index == 3) throw new Exception("checked");
                return index;
            }, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            fail("expected a wrapped failure");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("task 3"));
        }
    }

    /**
     * The worker cap must respect memory, not only cores.
     *
     * <p>A pair of 2048x2048 pyramids is around 150 MB live. Sixteen workers would ask for 2.4 GB of
     * pyramid alone, which is how a fast plugin becomes an {@code OutOfMemoryError} on a default Fiji
     * heap.
     */
    @Test
    public void workersAreCappedByMemory() {
        long pairOf2048 = 2L * PyramidCache.BYTES_PER_PIXEL * 2048 * 2048;
        assertEquals("a 300 MB budget affords two of these",
                2, PairScheduler.workersFor(1000, 16, pairOf2048, 300L * 1024 * 1024));
        assertEquals("never fewer than one", 1,
                PairScheduler.workersFor(1000, 16, pairOf2048, 1024));
        assertEquals("never more than there is work", 3,
                PairScheduler.workersFor(3, 16, 1, Long.MAX_VALUE / 4));
        assertEquals("one task is always serial", 1,
                PairScheduler.workersFor(1, 16, 1, Long.MAX_VALUE / 4));
        assertEquals("an explicit 1 forces serial", 1,
                PairScheduler.workersFor(1000, 1, 1, Long.MAX_VALUE / 4));
    }

    @Test
    public void zeroTasksIsNotAnError() {
        assertTrue(PairScheduler.map(0, 4, index -> index,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER).isEmpty());
    }

    /** Cache capacity must cover the strategy's reach, and must never fall below a working pair. */
    @Test
    public void cacheCapacityCoversTheStrategyReach() {
        assertEquals("consecutive looks one frame ahead", 1,
                PyramidCache.reachFor(Reconciler.Reference.CONSECUTIVE, 1, null));
        assertEquals("multi-lag must hold the longest lag", 16,
                PyramidCache.reachFor(Reconciler.Reference.MULTILAG, 1, new int[]{1, 2, 4, 8, 16}));
        assertEquals("fixed holds the reference plus one", 2,
                PyramidCache.reachFor(Reconciler.Reference.FIXED, 1, null));
        assertTrue("a tiny budget must still afford a pair",
                PyramidCache.capacityFor(100, 2048, 2048, 16, 8, 1) >= 2);
        assertTrue("a generous budget should hold the whole recording",
                PyramidCache.capacityFor(20, 64, 64, 1, 1, 1L << 30) >= 20);
    }

    /**
     * An estimator that keeps a prepared view on each plane must buy fewer pyramids with the same
     * memory.
     *
     * <p>Area correlation caches the centred intensities and their B-spline coefficients so it does
     * not rebuild them on all nine pairs a frame appears in. That is 11 more bytes per pixel on top
     * of the pyramid's 18, so a fixed budget affords fewer of them — and if the capacity calculation
     * kept using the bare figure, a long recording would hold 1.6 times the pyramids the budget was
     * sized for and fail with an {@code OutOfMemoryError} instead of merely being slow.
     */
    @Test
    public void capacityShrinksWhenTheEstimatorKeepsAPreparedView() {
        long bare = PyramidCache.BYTES_PER_PIXEL;
        long withWindow = bare + PyramidCache.AREA_WINDOW_BYTES_PER_PIXEL;
        long budget = 512L * 1024 * 1024;
        int plain = PyramidCache.capacityFor(1000, 1024, 1024, 16, 4, budget, bare);
        int prepared = PyramidCache.capacityFor(1000, 1024, 1024, 16, 4, budget, withWindow);
        assertTrue("a prepared view must cost capacity, not be free: " + plain + " vs " + prepared,
                prepared < plain);
        assertEquals("the six-argument form must still mean the bare pyramid",
                plain, PyramidCache.capacityFor(1000, 1024, 1024, 16, 4, budget));
        assertTrue("even a prepared view never drops below a working pair", prepared >= 2);
        assertEquals("area correlation is what declares the extra cost",
                PyramidCache.AREA_WINDOW_BYTES_PER_PIXEL,
                PairEstimator.Kind.AREA_CORRELATION.cachedBytesPerPixel());
    }

    /** Two threads asking for the same frame must build it once. */
    @Test
    public void cacheBuildsEachFrameOnce() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        PyramidCache cache = new PyramidCache(frame -> {
            builds.incrementAndGet();
            return Synth.pyramid(Synth.frame(64, 64, frame, 0), 64, 64, 2);
        }, 8);
        PairScheduler.map(64, 8, index -> cache.get(index % 4),
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        assertEquals("four distinct frames requested sixteen times each", 4, builds.get());
        assertTrue("and the rest were hits", cache.hits() > 50);
    }

    @Test(expected = IllegalArgumentException.class)
    public void cacheRefusesACapacityBelowOnePair() {
        new PyramidCache(frame -> null, 1);
    }
}
