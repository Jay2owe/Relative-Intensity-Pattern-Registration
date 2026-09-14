# Stage 03 - Add information-weighted graph reconciliation

## Why this stage exists

Pair covariance is useful only if the global trajectory can give precise directions more influence than uncertain ones. This stage adds generalized least squares for translation and rigid multi-lag graphs while retaining the current equal-weight solver as an exact compatibility branch.

## Prerequisites

- `02_pair-uncertainty_COMPLETED.md` exists.
- Both estimators return tested `PairUncertainty` values in Java and Python.
- The Stage 01 equal-weight bit-pattern tests still pass.

## Read first

- `C:/Users/jamie/AGENTS.md` - session and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:15-35` and `:51-70` - objective and weighting rules.
- `docs/confidence-weighted-reconciliation/weighting_protocol.md` - frozen information normalization and fallback rules.
- `src/main/java/logratio/core/Reconciler.java:55-103` and `:210-340` - observation/result contracts and existing banded normal equations.
- `src/main/java/logratio/core/Transform.java` - transform composition and inverse sign convention; read the complete file before deriving rows.
- `src/main/java/logratio/core/PairUncertainty.java` - Stage 02 covariance/information contract.
- `src/test/java/logratio/core/ReconciliationBaselineTest.java` - compatibility outputs that must not move.
- `src/ripr/core.py:1069-1136` - Python's current dense equal-weight reconciliation.

## Scope

- Add equal and uncertainty-weighted reconciliation strategies at the core level.
- Extend observations to carry pair uncertainty without breaking the existing transform-only constructor.
- Implement the full matrix objective `r^T information r`; do not collapse a `2 x 2` or `3 x 3` matrix to one scalar edge weight.
- Support translation and bounded rigid poses with frame 0 fixed as the gauge.
- Propagate off-diagonal information, including translation/rotation coupling, into one joint normal system for rigid weighting.
- Preserve the existing banded scaling with maximum lag rather than allocating a dense Java matrix for long recordings.
- Apply the normalization and invalid-uncertainty fallback frozen in Stage 01.
- Mirror the weighted equations in Python.
- Leave the production registration pipeline on equal weighting until Stage 05 wires experimental selection through it.

## Out of scope

- Robust graph residual weighting is Stage 04.
- Registration results and per-pair influence diagnostics are Stage 05.
- Fixed-strategy benchmarking, selector testing and default promotion are Stages 06, 07 and 08.
- Consecutive, fixed and rolling reference behaviour is not redesigned; weighting initially affects the redundant `MULTILAG` solve where conflicting routes exist.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/logratio/core/Reconciler.java` | MODIFY | Add uncertainty-bearing observations, strategy options and a banded block-information solve. |
| `src/ripr/core.py` | MODIFY | Mirror the generalized least-squares equations and strategy selection. |
| `src/test/java/logratio/core/WeightedReconcilerTest.java` | NEW | Prove analytic translation/rigid solutions, anisotropy, inversion and equal-mode compatibility. |
| `src/test/java/logratio/core/ReconciliationBaselineTest.java` | MODIFY | Exercise the new equal strategy entry point against the frozen outputs. |
| `tests_python/test_core.py` | MODIFY | Test the same weighted graph cases in Python. |

## Implementation sketch

Add an internal strategy/options seam without changing the existing public call:

```java
public enum Weighting {
    EQUAL,
    UNCERTAINTY
}

static final class Options {
    Weighting weighting = Weighting.EQUAL;
    int dimensions = 2;
    double rotationRadiusPixels = 1.0;
}

public static Solution multiLag(int frames, List<Observation> observations);
static Solution multiLag(int frames, List<Observation> observations, Options options);
```

The existing two-argument method must execute the original equal solver unchanged. Add an overloaded `Observation(from, to, displacement, uncertainty)`; the old constructor remains and supplies unavailable uncertainty.

For a canonical observation from lower frame `lo` to higher frame `hi`, use the current exact constraints:

```text
x_hi - cos(theta_obs) x_lo + sin(theta_obs) y_lo = dx_obs
y_hi - sin(theta_obs) x_lo - cos(theta_obs) y_lo = dy_obs
theta_hi - theta_lo                              = theta_obs
```

Frame 0 variables are omitted because they are the gauge. Translation uses the first two rows; rigid uses all three. Let `A` be these rows, `d` the observed transform vector and `Omega` the pair information matrix. Accumulate:

```text
normal += A^T Omega A
target += A^T Omega d
```

1. Canonicalize reversed observations by applying both `Transform.inverse()` and `PairUncertainty.inverseFor(...)`. Never invert only the transform.
2. Use a `2 * (frames - 1)` translation system or a `3 * (frames - 1)` interleaved rigid system. Choose the rigid bandwidth from `3 * maximumLag + 2`; retain lower-triangular band storage and Cholesky.
3. Add a block accumulator that accepts the small design block and information matrix. Do not implement weighted rigid fitting as three independently scaled scalar equations: off-diagonal covariance must be able to couple x, y and theta.
4. Apply the information normalization frozen in Stage 01 before normal-equation accumulation. A global multiplication of every information matrix must not change the fitted trajectory. Diagonal loading remains relative to the mean diagonal.
5. For unavailable uncertainty, use the declared conservative isotropic fallback and mark the occurrence for Stage 05; do not drop the observation. Ensure long-lag observations are not weakened merely because their lag is larger.
6. Preserve `support` as the count of usable observations touching each frame, not a sum of floating weights. Preserve the existing chain fallback on numerical failure.
7. Mirror the equations in Python using assembled block rows or direct normal accumulation. Python may use a dense solve at its present scale, but it must use the same canonicalization, normalization, gauge and ridge.
8. Test hand-solvable graphs: two agreeing precise edges against one broad conflicting edge; uncertainty only in x or y; rigid x/theta covariance; reversed edges; all covariances multiplied by one constant; unavailable evidence; and a numerically singular graph.

## Exit gate

1. The old `multiLag(frames, observations)` and explicit `Weighting.EQUAL` return identical `double` bits on every Stage 01 baseline fixture.
2. On hand-solvable conflicts, the weighted result moves toward the more precise measurement in the precise direction only.
3. Off-diagonal rigid information changes the joint x/y/theta solution as predicted by an independently calculated dense reference.
4. Forward and reversed representations of the same observation produce the same cumulative trajectory.
5. Multiplying all information matrices by one positive constant changes no fitted transform beyond `1e-12`.
6. Unavailable or ill-conditioned uncertainty follows the frozen fallback and does not remove graph support.
7. Java's banded result agrees with a test-only dense reference to `1e-10` on seeded translation and rigid graphs.
8. `mvn -q -Dtest=WeightedReconcilerTest,ReconciliationBaselineTest,ReconcilerTest test` passes.
9. `python -m pytest tests_python/test_core.py` passes on the same analytic cases.

## Known risks

- The present rigid equal solver separates translation and angle systems. Weighted off-diagonal covariance requires a joint pose system; multiplying the old rows by scalar weights would silently lose the intended information.
- Translation pixels and angle radians differ greatly in magnitude. Unit mistakes may appear numerically stable while suppressing rotation.
- Extremely large information ratios can dominate the ridge and harm conditioning. The Stage 01 eigenvalue and normalization bounds are mandatory.
- Weighted least squares can give a confidently wrong repeated-pattern edge more power. Stage 04 must add graph-consistency protection before this is benchmarked as a candidate default.
