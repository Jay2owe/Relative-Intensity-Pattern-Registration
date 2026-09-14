/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression guard for the measurement defect of 2026-08-10: a benchmark arm that spent three hours
 * suspended was recorded, published and filed as a 3,400x algorithmic stall.
 *
 * <p>A thread that is sleeping is the cheapest available stand-in for a process that Windows has parked
 * in Modern Standby — in both cases wall clock advances and no work is done — and it needs no image, no
 * seed and no power-state manipulation to reproduce. Under the wall-clock timing these tests replace,
 * {@link #sleepingIsNotComputing()} fails: the interval reads as 300 ms of computation.
 */
public class TimingTest {

    /** Long enough to clear the Windows scheduler tick of about 15.6 ms by a wide margin. */
    private static final long SLEEP_MS = 300;

    /**
     * The defect itself. Time that passes while the process is not running must be reported as elapsed
     * time and not as work, and must be visible as such rather than silently folded into the total.
     */
    @Test
    public void sleepingIsNotComputing() throws InterruptedException {
        Timing t = Timing.start();
        Thread.sleep(SLEEP_MS);
        t.stop();

        assumeCpuTime(t);
        assertTrue("elapsed should include the sleep, was " + t.wallMs() + " ms",
                t.wallMs() >= SLEEP_MS * 0.9);
        assertTrue("a sleeping thread burns no CPU, but " + t.cpuMs() + " ms was charged to it",
                t.cpuMs() < SLEEP_MS * 0.5);
        assertTrue("and the difference must be attributed, not lost: " + t.unscheduledMs() + " ms",
                t.unscheduledMs() >= SLEEP_MS * 0.4);
    }

    /**
     * The flag that turns the measurement into a warning. Without this the harness records the right
     * number and still lets a reader quote the wrong one.
     */
    @Test
    public void anIntervalDominatedByNotRunningIsFlagged() throws InterruptedException {
        Timing t = Timing.start();
        Thread.sleep(1_200);            // over the one-second floor and over a quarter of the interval
        t.stop();

        assumeCpuTime(t);
        assertTrue("should be flagged as unscheduled", t.unscheduled());
        assertFalse("and must say so in words for the console", t.note().isEmpty());
    }

    /**
     * The converse, so the guard cannot be satisfied by flagging everything. Real work must read as
     * real work, or the warning becomes noise and gets ignored — which is how the original number got
     * published in the first place.
     */
    @Test
    public void realWorkIsNotFlagged() {
        Timing t = Timing.start();
        double sink = 0;
        for (int i = 1; i < 40_000_000; i++) sink += 1.0 / i;
        t.stop();

        assumeCpuTime(t);
        assertTrue("the loop must not be optimised away: " + sink, sink > 0);
        assertTrue("busy work should charge most of the interval to CPU: "
                        + t.cpuMs() + " ms of " + t.wallMs() + " ms",
                t.cpuMs() > 0.5 * t.wallMs());
        assertFalse("busy work must not be flagged as unscheduled", t.unscheduled());
    }

    /** CPU time is per-thread, so a span closed on another thread would be meaningless, not merely wrong. */
    @Test
    public void refusesToBeStoppedOnAnotherThread() throws InterruptedException {
        final Timing t = Timing.start();
        final boolean[] refused = {false};
        Thread other = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    t.stop();
                } catch (IllegalStateException expected) {
                    refused[0] = true;
                }
            }
        });
        other.start();
        other.join();
        assertTrue("stopping on a foreign thread must be refused", refused[0]);
    }

    @Test
    public void unscheduledTimeIsNeverNegative() {
        Timing t = Timing.start().stop();
        assertTrue(t.unscheduledNs() >= 0);
        assertEquals("a stopped span reports both clocks", t.wallNs() >= 0, true);
    }

    /**
     * Every JVM this project runs on reports thread CPU time; a hypothetical one that does not falls
     * back to wall clock, and these assertions would then be testing nothing. Say so rather than pass.
     */
    private static void assumeCpuTime(Timing t) {
        org.junit.Assume.assumeTrue("this JVM cannot report thread CPU time", t.cpuMeasured());
    }
}
