# Pairwise estimator axis — who is doing what

Written 2026-08-18, after two agents were found executing `docs/pairwise_estimator_axis_plan.md`
in this working directory at the same time. The user settled it: **this split is the decision, not a
proposal.**

| Stage | Owner | State |
|---|---|---|
| 1 — seal a test set | the sealed-set agent | Done: 40 recordings under `library/benchmark/v2/benchmarks/sealed_test`, ten series, two per image type. Used as-is. Not read before Stage 5. |
| 2 — estimator seam | this session | Done and gated. |
| 3 — add and measure the area estimator | this session | In progress. |
| 4 — selector decision | this session | Blocked on Stage 3. |
| 5 — sealed run | this session | Blocked on Stage 4. |
| 6 — expose the setting | this session | Blocked on Stage 5. |

## What that means in practice

- `src/main/java/logratio/core/PairEstimator.java`, `AreaCorrelation.java`, the estimator field in
  `LogRatioParameters` and `Registration.Options`, and
  `src/test/java/logratio/PairEstimatorComparisonBenchmark.java` are this session's. Please do not
  edit them; a second hand on the estimator while its accuracy is being measured invalidates the
  measurement rather than improving it.
- Only one agent runs benchmarks at a time. Stage 3 reports elapsed and processor seconds per arm,
  and Stage 4 has to declare a runtime position from those numbers. Two Java runs on one machine make
  both sets of timings fiction.
- `library/benchmark/v2/benchmarks/sealed_test` stays sealed. Nothing in Stages 2 to 4 reads it.

## Three implementation traps already found and fixed in `AreaCorrelation`

Recorded so nobody re-learns them from a failing threshold.

1. **Bilinear interpolation is not good enough for an area estimator.** It low-passes by an amount
   that depends on the fractional offset, so the correlation peak slides toward whole pixels. Measured
   on a fiducial recording: a systematic per-pair bias of 0.015 to 0.032 px, larger than the entire
   error the log-ratio fit makes. Catmull-Rom cubic removes most of it.
2. **The refinement grid must start at one whole pixel, not half.** The true peak usually lies
   diagonally between four integer offsets, so the best integer sits on the peak's shoulder where a
   half-pixel grid is not concave and a quadratic fit refuses it. Measured: a phase-contrast pair
   whose true displacement was (2.500, 9.500) stopped dead at (3.000, 9.000), correlation 0.807 where
   0.913 was half a pixel away. That is the `0.7071 px` error — `hypot(0.5, 0.5)` — that showed up as
   the worst case on phase.
3. **A refusal to fit must shrink the grid, not end the refinement**, and when the fit gives no usable
   curvature but a neighbour scores better, the search should walk to that neighbour. Ending the
   refinement there is how an estimate ends up parked on a whole pixel with a better offset next door.
