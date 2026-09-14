# Estimate one robust angular jump at each known event

## Why this stage exists

The event model is useful only if each event angle is more reliable than a single frame-before/frame-after fit. This stage reuses the accepted rigid pair solver over a small temporal window, combines its independent-looking but correlated evidence robustly, and returns diagnostics without yet changing whole-stack registration.

## Prerequisites

- `01_post-tuning-contract-and-fixtures_COMPLETED.md`

## Read first

- `docs/event-anchored-rotation/00_overview.md`
- `docs/event-anchored-rotation/BASELINE.md`
- `src/main/java/logratio/core/PairAligner.java`, lines 161-291 and 383-470
- `src/main/java/logratio/core/PairEstimator.java`, entire file
- `src/main/java/logratio/core/PyramidCache.java`, entire file
- `src/main/java/logratio/core/RobustNorm.java`, entire file
- `src/main/java/logratio/core/Registration.java`, lines 703-845
- `src/test/java/logratio/core/EventRotationFixtures.java`
- `src/test/java/logratio/core/PairAlignerRecoveryTest.java`, existing rigid-recovery cases

## Scope

- Add a standalone event-angle estimator that can be tested without running translation reconciliation or warping.
- For event `e` and window `w`, use every available pre-event frame in `[e-w,e-1]` and post-event frame in `[e,e+w-1]`, after converting to zero-based indexing.
- Run the accepted bounded log-ratio rigid estimator on each cross-product pair.
- Treat the maximum rotation as a bound per event comparison, not per arbitrary multi-lag pair.
- Exclude refused fits, non-finite fits and rotation-bound fits from angle consensus.
- Combine retained angles with a circular median followed by a robust Huber or accepted post-tuning scalar estimator.
- Weight or qualify evidence using geometric support and temporal distance, but never choose an angle from post-fit residual alone.
- Require at least three usable pair fits for an automatic event estimate.
- Return incremental event angles, cumulative segment angles, pair counts, inlier counts, circular spread, contributing frame ranges and explicit status.
- Warn on large disagreement, but do not invent a hard spread-refusal threshold before Stage 07 has fresh evidence.
- Reuse pyramids across all pairs around all events and expose progress/cancellation.

## Out of scope

- Do not fit translation with a prescribed angle; Stage 03 owns that operation.
- Do not run the estimator automatically from `Registration.run`; Stage 04 owns pipeline order and reuse.
- Do not build Fourier/log-polar templates in the first implementation. They remain a future candidate only if the all-pairs benchmark fails.
- Do not use a single averaged image as the only evidence; individual-pair disagreement must remain visible.
- Do not add UI or macro controls; Stage 05 owns them.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/core/EventRotationEstimator.java` | NEW | Plan pairs, run accepted rigid fits and build robust event/segment angles. |
| `src/main/java/logratio/core/RotationEventResult.java` | NEW | Immutable event estimates, trajectory and diagnostics. |
| `src/test/java/logratio/core/EventRotationEstimatorTest.java` | NEW | Synthetic recovery, consensus, boundaries, gain and failure tests. |
| `src/test/java/logratio/core/EventRotationFixtures.java` | MODIFY | Add outlier, low-support and several-event fixtures as needed. |

## Implementation sketch

```java
public final class EventRotationEstimator {
    public static RotationEventResult estimate(
            FrameSource source,
            int[] zeroBasedEventFrames,
            int window,
            Registration.Options options,
            PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation);
}
```

```java
public final class RotationEventResult {
    public enum Status {
        OK,
        HIGH_DISAGREEMENT,
        INSUFFICIENT_SUPPORT,
        CANCELLED
    }

    public final Event[] events;
    /** Absolute angle relative to frame 0; identical values within each segment. */
    public final double[] frameAngles;

    public static final class Event {
        public final int frame;              // zero-based core index
        public final double deltaTheta;      // radians
        public final double cumulativeTheta; // radians
        public final int candidatePairs;
        public final int usablePairs;
        public final int inlierPairs;
        public final double circularMad;
        public final Status status;
    }
}
```

For a default window of three, an interior event supplies up to nine pairs:

```text
pre  = e-3, e-2, e-1
post = e,   e+1, e+2
pairs = pre x post
```

Consensus objective:

```text
wrap(a) = atan2(sin(a), cos(a))
delta*  = argmin_delta sum q_ij * Huber(wrap(theta_ij - delta) / scale)
```

Initialize `delta` with the circular median. Estimate scale from the circular median absolute deviation. If the accepted tuning produces a proven scalar robust routine, reuse it rather than adding a second implementation.

The cross-product estimates share frames and are therefore not independent. Diagnostics must describe supporting frames as well as pair count; later confidence resampling must bootstrap frames, not pair rows.

## Exit gate

1. `mvn -Dtest=logratio.core.EventRotationEstimatorTest test` passes.
2. One-event and two-event clean fixtures recover each accepted angular jump within the tuned clean rigid-angle gate recorded in `BASELINE.md`.
3. Gain-scaled fixtures recover the same event geometry within that gate.
4. One deliberately corrupted cross-event pair does not move the consensus outside the clean gate.
5. With fewer than three usable fits, the event returns `INSUFFICIENT_SUPPORT` and no finite angle is manufactured.
6. A fit on the angular bound is counted and diagnosed but excluded from consensus.
7. `frameAngles` changes only at supplied event indices and is bit-identical within each segment.
8. Candidate-pair count is at most `events * window * window`, with edge truncation recorded deterministically.
9. Cancelling during an event fit exits promptly and leaves no live worker or cached pyramid.
10. Existing pair-alignment tests remain unchanged and green.

## Known risks

- More temporal pairs also span more biological change. Keep the window short and record distance so Stage 07 can test weighting without losing provenance.
- A round or symmetric sample can make angle unidentifiable while translation still looks plausible. Surface angular spread and support rather than returning false confidence.
- A remount may cause a rotation outside the public bound. Bound hits are evidence that the requested search was too narrow, not usable angle votes.
- All-pairs work is correlated. Never report `sqrt(pair count)` confidence as though nine pairs were nine independent acquisitions.
- The accepted tuned estimator may expose a better ambiguity metric than angle spread. Include it in diagnostics if available, but do not change the event contract.

