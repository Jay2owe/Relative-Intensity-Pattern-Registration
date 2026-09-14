/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs independent indexed work across a bounded pool, deterministically.
 *
 * <p>Pair alignments are the plugin's one dominant parallel axis, and they are genuinely independent:
 * each reads two cached pyramids and writes one result. So this is a plain indexed map, and everything
 * interesting about it is a constraint rather than a feature.
 *
 * <p><b>Results are stored by index, never by completion order.</b> A table whose row order depended on
 * which thread finished first would be a different scientific output on every run.
 *
 * <p><b>Workers are capped by memory as well as by cores.</b> A pair of 2048x2048 pyramids is around
 * 150 MB live, so sixteen workers on a sixteen-core machine would ask for 2.4 GB of pyramid alone and
 * fail on a default Fiji heap. Deriving the cap from cores only is the standard way to turn a fast
 * plugin into an {@code OutOfMemoryError}.
 *
 * <p><b>One pool, never nested.</b> The inner loops of {@link PairAligner} are deliberately serial: the
 * outer axis already saturates the pool, and a nested pool would oversubscribe the machine while making
 * the worker budget meaningless.
 *
 * <p>Cancellation is checked before each task starts and is propagated by the task itself for long
 * work. The first failure cancels and drains the rest, restores the interrupt flag if it was set, bounds
 * the shutdown wait, and leaves no threads behind.
 */
public final class PairScheduler {

    private PairScheduler() {
    }

    /** Work for one index. Must not touch ImageJ UI, tables or global state. */
    public interface Task<T> {
        T run(int index) throws Exception;
    }

    /** Polled between tasks. */
    public interface Cancellation {
        boolean cancelled();

        Cancellation NEVER = () -> false;
    }

    /**
     * Receives both the stable completion count and optional richer lifecycle events.
     *
     * <p>{@link #update} stays the only abstract method so existing lambdas remain source and binary
     * compatible. A user interface can override the default methods to show activity before the
     * first expensive pair completes instead of appearing frozen at zero percent.
     */
    public interface Progress {
        void update(int done, int total);

        /** A task batch has been sized and its worker count is known. */
        default void begin(int total, int workers) { }

        /** Task {@code index} has started. May be called concurrently by several workers. */
        default void taskStarted(int index, int total) { }

        /** A non-task phase or serial pre-pass has advanced. */
        default void phase(String name, int done, int total) { }

        Progress NONE = (done, total) -> {
        };
    }

    /**
     * Workers to use.
     *
     * @param tasks     how many units of work there are
     * @param requested user's choice; 0 or less means decide automatically, 1 forces serial
     * @param memPerTask bytes a single task needs live
     * @param budget    bytes available; 0 or less asks for a fraction of {@link Runtime#maxMemory}
     */
    public static int workersFor(int tasks, int requested, long memPerTask, long budget) {
        if (tasks <= 1) return 1;
        if (requested == 1) return 1;
        int cores = requested > 1
                ? requested
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        long available = budget > 0 ? budget : Runtime.getRuntime().maxMemory() / 2;
        // Clamp to int range before narrowing. A generous budget divided by a small per-task cost is
        // a long that overflows int, and the truncated value comes out negative — which then wins
        // every min() and silently forces the whole run serial. Found by the worker-budget test.
        long byMemoryLong = memPerTask > 0 ? Math.max(1, available / memPerTask) : cores;
        int byMemory = (int) Math.min(Integer.MAX_VALUE, byMemoryLong);
        return Math.max(1, Math.min(Math.min(cores, byMemory), tasks));
    }

    /**
     * Run {@code count} tasks and return their results in index order.
     *
     * @throws CancellationException if {@code cancellation} fired before the work finished
     * @throws RuntimeException      wrapping the first task failure, after the rest are drained
     */
    public static <T> List<T> map(int count, int workers, Task<T> task, Progress progress,
                                  Cancellation cancellation) {
        List<T> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) results.add(null);
        if (count == 0) return results;
        progress.begin(count, Math.max(1, Math.min(workers, count)));

        if (workers <= 1) {
            for (int i = 0; i < count; i++) {
                if (cancellation.cancelled()) throw new CancellationException("cancelled");
                try {
                    progress.taskStarted(i, count);
                    results.set(i, task.run(i));
                } catch (Exception e) {
                    throw wrap(e, i);
                }
                progress.update(i + 1, count);
            }
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "log-ratio-registration");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        List<Future<?>> futures = new ArrayList<>(count);
        boolean interrupted = false;
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    if (stop.get() || cancellation.cancelled()) return null;
                    progress.taskStarted(index, count);
                    T value = task.run(index);
                    synchronized (results) {
                        results.set(index, value);
                    }
                    progress.update(done.incrementAndGet(), count);
                    return null;
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (InterruptedException e) {
                    interrupted = true;
                    stop.set(true);
                    throw new CancellationException("interrupted");
                } catch (ExecutionException e) {
                    stop.set(true);
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw wrap(cause, i);
                }
            }
            if (cancellation.cancelled()) throw new CancellationException("cancelled");
            return results;
        } finally {
            stop.set(true);
            for (Future<?> f : futures) f.cancel(false);
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException wrap(Throwable cause, int index) {
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        return new RuntimeException("task " + index + " failed: " + cause.getMessage(), cause);
    }
}
