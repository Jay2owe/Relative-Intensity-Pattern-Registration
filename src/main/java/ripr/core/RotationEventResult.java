/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.Arrays;

/** Immutable event-angle trajectory and the evidence behind every angular jump. */
public final class RotationEventResult {
    public enum Status {
        OK,
        /** Finite consensus retained, but pair angles occupy a large part of the allowed range. */
        HIGH_DISAGREEMENT,
        /** Fewer than three usable non-bound pair fits. No angle was manufactured. */
        INSUFFICIENT_SUPPORT,
        CANCELLED
    }

    public static final class Event {
        /** Zero-based first frame after the remount. */
        public final int frame;
        public final double deltaTheta;
        public final double cumulativeTheta;
        public final int candidatePairs;
        public final int usablePairs;
        public final int inlierPairs;
        public final double circularMad;
        public final int firstPreFrame;
        public final int lastPreFrame;
        public final int firstPostFrame;
        public final int lastPostFrame;
        public final Status status;

        Event(int frame, double deltaTheta, double cumulativeTheta,
              int candidatePairs, int usablePairs, int inlierPairs, double circularMad,
              int firstPreFrame, int lastPreFrame, int firstPostFrame, int lastPostFrame,
              Status status) {
            this.frame = frame;
            this.deltaTheta = deltaTheta;
            this.cumulativeTheta = cumulativeTheta;
            this.candidatePairs = candidatePairs;
            this.usablePairs = usablePairs;
            this.inlierPairs = inlierPairs;
            this.circularMad = circularMad;
            this.firstPreFrame = firstPreFrame;
            this.lastPreFrame = lastPreFrame;
            this.firstPostFrame = firstPostFrame;
            this.lastPostFrame = lastPostFrame;
            this.status = status;
        }

        /** One-based public first-post-remount frame. */
        public int publicFrame() { return frame + 1; }
    }

    public final Event[] events;
    /** Absolute angle relative to frame zero; bit-identical inside each segment. */
    public final double[] frameAngles;

    RotationEventResult(Event[] events, double[] frameAngles) {
        this.events = events.clone();
        this.frameAngles = frameAngles.clone();
    }

    public RotationEventResult copy() { return new RotationEventResult(events, frameAngles); }

    public boolean usable() {
        for (Event event : events) {
            if (event.status == Status.INSUFFICIENT_SUPPORT || event.status == Status.CANCELLED) {
                return false;
            }
        }
        return true;
    }

    public void requireUsable() {
        for (Event event : events) {
            if (event.status == Status.INSUFFICIENT_SUPPORT) {
                throw new IllegalArgumentException("rotation event frame " + event.publicFrame()
                        + " has " + event.usablePairs
                        + " usable pair fits; at least 3 are required");
            }
            if (event.status == Status.CANCELLED) {
                throw new java.util.concurrent.CancellationException("event rotation cancelled");
            }
        }
    }

    @Override public String toString() {
        return "RotationEventResult[events=" + events.length
                + ", frameAngles=" + Arrays.toString(frameAngles) + "]";
    }
}
