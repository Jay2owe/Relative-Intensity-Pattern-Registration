/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Independent indexed writes only; joins every worker before owners close shared inputs. */
final class LongitudinalOrderedFrames {
    private LongitudinalOrderedFrames() { }

    static void run(int count, int workers, boolean nativeFits, IntConsumer action) {
        if (workers == 1) {
            for (int i = 0; i < count; i++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                action.accept(i);
            }
            return;
        }
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, workers), r -> {
            Thread t = new Thread(r, "ripr-independent-frame"); t.setDaemon(true); return t;
        });
        List<Future<?>> tasks = new ArrayList<>();
        boolean interrupted = false;
        try {
            for (int worker = 0; worker < Math.min(count, workers); worker++) tasks.add(pool.submit(() -> {
                // OpenCL preference is native thread-local, unlike the library thread count.
                boolean previous = nativeFits && useOpenCL();
                try {
                    if (nativeFits) setUseOpenCL(false);
                    for (int i; !Thread.currentThread().isInterrupted() && (i = next.getAndIncrement()) < count;)
                        action.accept(i);
                } finally {
                    if (nativeFits) setUseOpenCL(previous);
                }
            }));
            for (Future<?> task : tasks) task.get();
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new CancellationException("Interrupted while fitting frames");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } finally {
            for (Future<?> task : tasks) if (!task.isDone()) task.cancel(true);
            pool.shutdownNow();
            // Native calls may not honour interruption. Never release reference matrices early.
            for (;;) {
                try { if (pool.awaitTermination(1, TimeUnit.SECONDS)) break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
