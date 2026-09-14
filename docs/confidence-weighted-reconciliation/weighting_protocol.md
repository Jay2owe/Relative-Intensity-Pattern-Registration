# Confidence-weighted reconciliation protocol

Frozen before implementation results were inspected: 2026-08-25.

## Physical contract

For a pair from frame `i` to frame `j`, covariance describes uncertainty of the
estimated transform in `[dx_pixels, dy_pixels]` or
`[dx_pixels, dy_pixels, theta_radians]`. Information is the symmetric
positive-definite inverse covariance. Correlation, `residualAfter` and
`validFraction` are quality diagnostics, not confidence by themselves.

For an inverse rigid transform, covariance is propagated as `J C J^T`, where
`J` is the analytic Jacobian of `Transform.inverse()`. Translation rescaling by
`f` uses `S = diag(f, f[, 1])` and `C_native = S C_small S^T`.

## Pair uncertainty

All calculations are evaluated at the accepted full-resolution transform on
the temporary estimation images. The gain is profiled as the median log-ratio.

- Log-ratio fitting uses the final robust Jacobian. The covariance is the
  Huber/Tukey sandwich `H^-1 B H^-1`, with `H = sum(w Jc Jc^T)`,
  `B = sum((w r)^2 Jc Jc^T)`, and `Jc` the weight-centred movement Jacobian.
- Area correlation uses the local normalized-correlation objective
  `1 - NCC`. Its Hessian is evaluated by symmetric finite differences at
  `0.25` pixel-equivalent steps; its residual scale is
  `max(1e-9, 2(1-NCC)/(n-d))`. A spatially distinct alternative is at least
  two pixel-equivalent units from the accepted peak. `peakAmbiguity` is the
  accepted NCC minus the best such alternative, so a larger value is better.
- Translation/rotation matrices are bounded after mapping angle to pixels with
  the frame root-mean-square radius
  `sqrt((width^2 + height^2 - 2) / 12)`. Pixel-equivalent standard deviations
  are clamped to `[0.02, 20]` pixels. Flags record either clamp.
- Non-finite, singular or under-supported calculations are explicitly
  unavailable. They never change pair status or fabricate extreme precision.
- These analytic matrices are marked uncalibrated until external calibration
  passes. The flag is retained in every fit and diagnostic.

## Weighted graph solve

The four frozen strategies are `EQUAL`, `UNCERTAINTY`, `ROBUST` and `COMBINED`.
Equal executes the compatibility branch. Uncertainty and combined use full
matrix information, including cross-terms. Within a recording, available raw
information matrices are divided by the median of `trace(Omega)/dimensions`.
Unavailable matrices receive that median isotropic information before the same
normalization. This preserves support and does not penalize lag.

Rigid rows use pixels and radians exactly. No angle is converted to degrees.
The equal branch retains the established separate translation/angle arithmetic
so its floating-point output remains reproducible.

## Robust graph consistency

Robust modes start at factor `1`. Each factor update uses a leave-one-edge-out
trajectory so a high-information edge cannot validate itself by pulling the
fit toward its own measurement. The residual is Mahalanobis for combined mode;
robust-only uses `(dx, dy, radius * theta)` pixel-equivalent coordinates.

The comparison value is `sqrt(quadratic_form / dimensions)`. Its scale is
`1.4826 * MAD` about the median with a `1e-6` floor. The Huber threshold is
`1.345 * scale`. Factors are clamped to `[0.05, 1]`. Iteration stops after at
most eight solves, or when maximum factor change is below `1e-4` and maximum
pixel-equivalent trajectory change is below `1e-5`. Pair order is canonicalized
by `(minFrame, maxFrame, inputIndex)`. Robust residuals are computed before
frame repair. No lag-group normalization or lag penalty is used.

## Evidence assignments

All derived motion paths and views of one original acquisition stay together.

| Evidence | Assignment | Status |
|---|---|---|
| V2 controlled-motion sources | Development | Previously inspected; suitable for engineering and calibration only |
| V2 natural-motion sources | Supporting stability | No movement truth; never an accuracy gate |
| `sealed_test_3` | Spent integration | Previously opened for selector/Newton/rigid work; never final evidence here |
| Fresh Phase and Dense sources in `library/rigid_selector_tuning/source_candidates` | Reserved for their declared rigid-selector work | Must not be borrowed |
| Balanced fresh rigid final group | Final | Unavailable |

There is no defensible untouched balanced rigid final group. Therefore this
implementation cannot promote a non-equal rigid default. The fixed-strategy and
selector harnesses may run on development data, but Stage 08 must record
`NO_PROMOTION_INSUFFICIENT_FINAL_EVIDENCE` unless a new protocol and fresh
source manifest are frozen first.

## Fixed-strategy gates

The independent source recording is the statistical unit. Translation and
rigid scopes are judged separately. A non-equal strategy must meet every gate:

- source-balanced median trajectory error improves by at least 5%;
- worst-recording error increases by no more than 10% and no more than 0.10 px;
- pair failures, bound hits and repaired frames do not increase;
- reconciliation time is at most 1.25 times equal and total time at most 1.10
  times equal;
- Java/Python transform agreement is `1e-8`, covariance/information agreement
  is `1e-7`, and robust-factor agreement is `1e-6`;
- the scope has untouched final evidence. Missing evidence is an automatic
  failure, not a neutral result.

Internal image disagreement is recorded but is not an accuracy gate.

## Optional selector freeze

The selector may use only these truth-free columns, in this order:

1. estimator kind;
2. rigid flag;
3. usable pair fraction;
4. failed pair fraction;
5. bound-hit pair fraction;
6. unavailable uncertainty fraction;
7. calibrated uncertainty fraction;
8. covariance-floor fraction;
9. covariance-cap fraction;
10. median translation standard deviation;
11. median rotation standard deviation;
12. median covariance anisotropy;
13. median peak ambiguity;
14. median equal-graph residual;
15. 90th-percentile equal-graph residual;
16. maximum equal-graph residual;
17. minimum frame support;
18. disconnected frame fraction.

Known movement, strategy errors, winner labels, corrected pixels, candidate
final residuals and post-repair values are prohibited. Frozen model families
are a context-only rule and ridge-regularized one-versus-rest linear predicted
gain with penalties `{0.01, 0.1, 1, 10}`. Original acquisitions define folds.
Oracle headroom over the best safe fixed strategy must be at least 10% on both
development and validation before fitting. A selector must then improve the
best fixed policy by at least 3%, pass every fixed safety/runtime gate and clear
an inner-held-out predicted-gain threshold from `{0, 0.01, 0.02, 0.05}`.
Otherwise the generated policy is `fixed_only`. Low confidence, invalid or
unsupported scope falls back to the best validated fixed arm, or `EQUAL` when
none passed. Final evidence is opened only after the model/source hash is
frozen.

## Stop conditions

Stop promotion on a protocol/configuration hash mismatch, cross-source leakage,
truth in selector features, Java/Python parity failure, any safety-gate failure,
or missing untouched final evidence. Experimental strategies and diagnostics
remain available internally; equal remains the production default and replay
baseline.
