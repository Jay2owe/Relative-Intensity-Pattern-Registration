/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * One measured interval, as both wall clock and CPU time. <b>Test scope only.</b>
 *
 * <h2>Why a harness must not time itself with a wall clock</h2>
 *
 * <p>{@link System#nanoTime()} measures how much time passed, not how much work was done. Those are the
 * same number only while the process is actually being scheduled, and on a laptop it frequently is not:
 * Windows Modern Standby, sleep, hibernation and aggressive power throttling all stop the process while
 * the clock carries on.
 *
 * <p><b>This is not hypothetical; it cost this project a fabricated blocking defect.</b> The benchmark
 * run of 2026-08-10 reported that one arm — log-ratio Huber, consecutive chain, gain fade,
 * {@code VID47_D3} — took 11,149.0 s for 47 pairs, against an 81 ms median elsewhere, and that number
 * was written into the CSV, quoted in {@code README.md}, and filed as the top item of
 * {@code IMPROVEMENTS.md}. It was not computation. The machine entered Modern Standby at 17:52:23 and
 * left it at 20:58:13, an interval of 11,150 s, which is the reported figure to within a second. The
 * same run's other anomaly, 244.1 s for the Tukey RCC arm, sits on a 228 s standby window immediately
 * before it. Re-run on the same seeds, in the same arm order, in one JVM, those arms take 3.1 s and
 * 13.6 s.
 *
 * <p>A wall clock cannot tell those two situations apart, and neither could anyone reading the CSV. CPU
 * time can: a process that is not running accrues none. So every published timing in this harness is CPU
 * time, and the difference is reported alongside it rather than discarded, because an arm that lost three
 * hours to standby is a fact about the run worth seeing — just not a fact about the estimator.
 *
 * <p><b>What CPU time is not.</b> It is per-thread, so it excludes garbage collection and JIT
 * compilation, which run elsewhere; expect it to sit a few percent under wall clock on a healthy run.
 * Windows updates it on the scheduler tick, about every 15.6 ms, so a single 81 ms pair is quantised and
 * only an aggregate is worth quoting. Neither limitation matters for the thing this exists to catch,
 * which is a three-thousand-fold discrepancy.
 */
public final class Timing {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /**
     * Below this, the wall-clock/CPU difference is ordinary background: garbage collection, JIT
     * compilation, other processes. Above it and above {@link #SUSPECT_SHARE}, the process was not
     * running.
     */
    private static final long SUSPECT_FLOOR_NS = 1_000_000_000L;
    /**
     * And it must also be this share of the interval. Both conditions, so a legitimately long arm is
     * not flagged for losing a couple of seconds to collection, and a short one is not flagged for
     * noise.
     */
    private static final double SUSPECT_SHARE = 0.25;

    private final long threadId;
    private final long wallStart;
    private final long cpuStart;
    private long wallNs = -1;
    private long cpuNs = -1;

    private Timing() {
        this.threadId = Thread.currentThread().getId();
        this.wallStart = System.nanoTime();
        this.cpuStart = cpuNow();
    }

    /** Begin measuring on the calling thread. {@link #stop()} must be called on that same thread. */
    public static Timing start() {
        return new Timing();
    }

    /** End the interval. Returns {@code this} so a call can be chained onto the measured work. */
    public Timing stop() {
        if (Thread.currentThread().getId() != threadId) {
            throw new IllegalStateException("Timing must be stopped on the thread that started it; "
                    + "CPU time is per-thread and would otherwise be meaningless");
        }
        long cpuEnd = cpuNow();
        wallNs = System.nanoTime() - wallStart;
        // The CPU span is read inside the wall span at both ends, so it can never exceed it and
        // unscheduledNs() can never come out negative from ordering alone.
        cpuNs = cpuStart < 0 || cpuEnd < 0 ? -1 : cpuEnd - cpuStart;
        return this;
    }

    /** True when the JVM reported real CPU time. False means {@link #cpuNs()} is wall clock. */
    public boolean cpuMeasured() {
        return cpuNs >= 0;
    }

    /** Elapsed time, including any interval in which the process was not scheduled. */
    public long wallNs() {
        checkStopped();
        return wallNs;
    }

    /**
     * Time this thread actually spent on a CPU — the quantity that is a property of the code rather
     * than of the machine's power state. Falls back to wall clock on a JVM that cannot report it, which
     * {@link #cpuMeasured()} distinguishes.
     */
    public long cpuNs() {
        checkStopped();
        return cpuNs >= 0 ? cpuNs : wallNs;
    }

    /** Wall clock that was not spent computing. Zero on a healthy run. */
    public long unscheduledNs() {
        long lost = wallNs() - cpuNs();
        return lost > 0 ? lost : 0;
    }

    public double wallMs() {
        return wallNs() / 1e6;
    }

    public double cpuMs() {
        return cpuNs() / 1e6;
    }

    public double unscheduledMs() {
        return unscheduledNs() / 1e6;
    }

    /**
     * True when the interval is dominated by time the process was not running — a suspend, a standby,
     * or contention severe enough that a timing is not worth quoting.
     */
    public boolean unscheduled() {
        return cpuMeasured()
                && unscheduledNs() >= SUSPECT_FLOOR_NS
                && unscheduledNs() > SUSPECT_SHARE * wallNs();
    }

    /** A line to print when {@link #unscheduled()}, or the empty string. Never null. */
    public String note() {
        if (!unscheduled()) return "";
        return String.format("%.1f s of %.1f s elapsed was not spent computing — the process was not "
                        + "scheduled (standby, sleep or contention). The CPU figure of %.1f s is the "
                        + "one to quote.",
                unscheduledNs() / 1e9, wallNs() / 1e9, cpuNs() / 1e9);
    }

    @Override
    public String toString() {
        return String.format("%.1f ms cpu / %.1f ms wall", cpuMs(), wallMs());
    }

    private void checkStopped() {
        if (wallNs < 0) throw new IllegalStateException("Timing was never stopped");
    }

    private static long cpuNow() {
        if (!THREADS.isCurrentThreadCpuTimeSupported()) return -1;
        return THREADS.getCurrentThreadCpuTime();      // -1 when the JVM has it disabled
    }
}
