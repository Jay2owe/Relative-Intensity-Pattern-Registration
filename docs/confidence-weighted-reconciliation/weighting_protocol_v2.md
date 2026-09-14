# Confidence-weighted reconciliation evidence protocol v2

Frozen before any v2 validation/final registration outcome: 2026-08-25.

## Scope

Evaluate equal, uncertainty, robust and combined reconciliation for translation and bounded rigid registration, separately for log-ratio fit and normalized area correlation, across the five declared microscopy categories.

## Evidence units and counts

The independent original acquisition is the statistical unit. Existing V2 controlled sources are development only. New validation and final cohorts each contain two original acquisitions per category. All derivatives of one acquisition remain in one split.

Each acquisition supplies one declared 256 by 256 or larger field and four seeded 48-frame paths. Translation and rigid paths are generated independently from the same frozen source pixels. Frames and edges are repeated observations, not sample size.

## Strategy and pair freeze

All arms consume byte-identical serialized pair fits for a recording. Pair estimator, preprocessing, support, bounds, lag plan and pixels are fixed within the comparison. Lags are `1, 2, 4, 8, 16`. Equal is A000 and remains the compatibility branch.

## Development tuning lattice

Initial attempts use the v1 values. If no candidate passes development, later immutable rounds may test one attributable change from this predeclared grid:

- pixel-equivalent covariance standard-deviation bounds: minimum `{0.01, 0.02, 0.05}`, maximum `{10, 20, 40}`;
- information scalar normalizer: median or 25% trimmed mean of `trace(Omega)/dimensions`;
- robust loss constant `{1.0, 1.345, 2.0}` times MAD scale;
- robust factor floor `{0.02, 0.05, 0.10}`;
- robust maximum iterations `{8, 12}`.

Only development chooses among these values. A validation failure may motivate a new development round, but the failed validation result is never included in parameter fitting. Every new policy version reruns validation as a distinct immutable evaluation. Final data never tune anything.

## Metrics and gates

Translation uses per-frame Euclidean trajectory error. Rigid uses full-frame warping index from the independent Thevenaz protocol. Summaries balance within original source, then category.

A non-equal fixed policy must meet all of:

- source-balanced median error improves at least 5%;
- worst-recording error increases no more than 10% and 0.10 px;
- pair failures, bound hits and repaired frames do not increase;
- reconciliation time is at most 1.25 times equal and total time at most 1.10 times equal;
- Java/Python transform agreement is `1e-8`, covariance/information `1e-7`, robust factor `1e-6`;
- the same frozen policy passes validation and final in its claimed scope.

## Selector

The v1 18-feature order is unchanged. Oracle headroom over the best safe fixed policy must be at least 10% on development and validation. A trained selector must improve that fixed policy at least 3%, pass every safety/runtime gate and clear a development-selected predicted-gain threshold from `{0, 0.01, 0.02, 0.05}`. Otherwise the policy is fixed-only.

## Stop conditions

Stop on source/license uncertainty, input or protocol hash mismatch, cross-source leakage, truth in selector features, invalid pair-fit identity, Java/Python parity failure that cannot be fixed without changing frozen evidence, or unavailable external infrastructure after safe retries. Downloading, development retuning and ordinary implementation/test failures are not blockers.
