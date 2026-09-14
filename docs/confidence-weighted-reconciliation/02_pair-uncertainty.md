# Stage 02 - Expose direction-aware pair uncertainty

## Why this stage exists

The graph cannot weight measurements scientifically until both pair estimators return uncertainty with the same physical meaning. This stage converts each estimator's final local evidence into covariance for horizontal movement, vertical movement and, when enabled, rotation. It does not yet let that covariance change the trajectory.

## Prerequisites

- `01_post-rotation-baseline_COMPLETED.md` exists.
- `weighting_protocol.md` and its protocol manifest contain frozen uncertainty calculations and evidence splits.
- The equal-weight baseline tests pass without modification.

## Read first

- `C:/Users/jamie/AGENTS.md` - session and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:15-35` and `:51-81` - information-matrix design and constraints.
- `docs/confidence-weighted-reconciliation/weighting_protocol.md` - the formulas and bounds frozen in Stage 01; this stage implements them exactly.
- `src/main/java/logratio/core/PairEstimator.java:33-110` - estimator-neutral return seam.
- `src/main/java/logratio/core/PairAligner.java:178-213`, `:260-292` and `:550-600` - pair result, common reporting and log-ratio Hessian accumulation.
- `src/main/java/logratio/core/AreaCorrelation.java:256-409`, `:641-781` and `:793-867` - rigid refinement and normalized-correlation curvature.
- `src/main/java/logratio/core/Registration.java:297-400` - pair/result containers and translation rescaling.
- `src/ripr/core.py:281-297`, `:685-841` and `:997-1043` - Python pair fits, area correlation and result scaling.

## Scope

- Add one immutable estimator-neutral covariance contract in Java and an equivalent Python value type.
- Calculate uncertainty at the final full-resolution transform from the same temporary guide pixels used by the estimator.
- Preserve covariance direction and cross-terms as a `2 x 2` translation matrix or `3 x 3` rigid matrix.
- Attach uncertainty to every usable `PairAligner.Fit`/`PairFit`; refused or numerically invalid fits carry explicit unavailable evidence rather than fabricated precision.
- Add the frozen spatially distinct peak-ambiguity diagnostic to normalized area correlation.
- Preserve units through pair inversion and reduced-resolution translation scaling.
- Keep estimated transforms, residuals, status decisions and final corrected pixels unchanged.
- Keep Java and Python contracts numerically aligned.

## Out of scope

- Pair uncertainty does not affect reconciliation in this stage; Stage 03 consumes it.
- Graph disagreement and robust factors are Stage 04.
- Registration-level reporting and user-visible summaries are Stage 05.
- No public weighting setting or default changes before Stage 08.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/logratio/core/PairUncertainty.java` | NEW | Hold covariance, derived information and audit flags in physical transform units. |
| `src/main/java/logratio/core/PairAligner.java` | MODIFY | Attach final log-ratio curvature/noise uncertainty to each fit. |
| `src/main/java/logratio/core/AreaCorrelation.java` | MODIFY | Attach normalized-correlation curvature and spatial peak ambiguity to each fit. |
| `src/main/java/logratio/core/Registration.java` | MODIFY | Scale pair covariance correctly when reduced-resolution translations are restored to native pixels. |
| `src/ripr/core.py` | MODIFY | Mirror the uncertainty type, estimator calculations and scaling behaviour in Python. |
| `src/test/java/logratio/core/PairUncertaintyTest.java` | NEW | Test matrix validity, inversion, scaling, anisotropy and unavailable evidence. |
| `src/test/java/logratio/core/PairAlignerRecoveryTest.java` | MODIFY | Assert log-ratio uncertainty without changing recovered transforms. |
| `src/test/java/logratio/core/AreaCorrelationRecoveryTest.java` | MODIFY | Assert correlation curvature and ambiguity for distinctive and repeated fixtures. |
| `tests_python/test_core.py` | MODIFY | Cover the equivalent Python contract and calculations. |

## Implementation sketch

Implement the contract with defensive copies and no estimator-specific score hidden inside the matrix:

```java
public final class PairUncertainty {
    public final int dimensions;          // 2 or 3
    public final double peakAmbiguity;    // NaN when not applicable
    public final boolean calibrated;
    public final boolean eigenvalueFloored;
    public final boolean eigenvalueCapped;

    public static PairUncertainty unavailable(int dimensions);
    public static PairUncertainty fromCovariance(
            int dimensions, double[] rowMajorCovariance,
            double peakAmbiguity, boolean calibrated,
            boolean floored, boolean capped);
    public boolean available();
    public double[] covariance();
    public double[] information();
    public PairUncertainty inverseFor(Transform forward);
    public PairUncertainty scaleTranslations(double factor);
}
```

The precise constructor names may be shortened, but the semantics and audit fields must remain.

1. Add `public final PairUncertainty uncertainty` to `PairAligner.Fit` and update every constructor call. Keep an overload or factory that supplies unavailable uncertainty while tests and non-refining failure paths are migrated.
2. For log-ratio fitting, evaluate the frozen residual-noise and robust Hessian calculation at the accepted full-resolution transform. Do not reuse a stale Hessian from an earlier pyramid level or a rejected step. Convert the frozen covariance calculation into `[pixels, pixels, radians]`, symmetrize it, apply the declared eigenvalue bounds and record which bound was applied.
3. For translation correlation, reuse the final valid `Surface` Hessian only after verifying that it describes the accepted transform and sample set. For rigid correlation, evaluate the frozen three-dimensional local objective sampling at the final transform; express angular derivatives per radian before constructing covariance.
4. Extend correlation peak search to retain the best and the best spatially distinct alternative under the separation rule frozen in Stage 01. Adjacent samples on the same peak are not an alternative peak. Store the declared gap/ratio as `peakAmbiguity`; keep it separate from covariance so Stage 06 can audit both.
5. Invalid, singular or non-finite curvature must yield `PairUncertainty.unavailable(d)` or the exact conservative fallback frozen in the protocol. It must never turn a usable transform into a refused fit unless Stage 01 explicitly froze that rule.
6. Implement covariance propagation for an inverse rigid transform using the Jacobian of `Transform.inverse()`. Implement native-resolution scaling with `S = diag(factor, factor[, 1])` and `C_native = S C_small S^T`.
7. Mirror the same field meanings and calculations in Python. Use NumPy arrays internally but return copies or read-only arrays so callers cannot mutate a completed fit.
8. Add fixtures for a corner-like pattern, a single straight edge, a repeated pattern, a flat image, a known rigid transform and a scaled transform. The straight edge must show greater variance along its poorly constrained direction; the repeated pattern must show worse ambiguity than the distinctive fixture.

## Exit gate

1. Every usable Java and Python pair fit has either finite symmetric positive-definite covariance in the correct units or an explicit unavailable/conservative fallback state.
2. Translation covariance is `2 x 2`; rigid covariance is `3 x 3`; rotation is stored in radians, never degrees.
3. Pair inversion and translation scaling tests agree with analytic Jacobian propagation.
4. The straight-edge fixture is measurably anisotropic and the repeated-pattern fixture reports worse peak ambiguity than the distinctive fixture.
5. Refused, flat and singular fixtures do not emit extreme or non-finite information matrices.
6. Existing recovered transforms, fit statuses, residuals and the Stage 01 equal-weight bit patterns remain unchanged.
7. `mvn -q -Dtest=PairUncertaintyTest,PairAlignerRecoveryTest,AreaCorrelationRecoveryTest,ReconciliationBaselineTest test` passes.
8. `python -m pytest tests_python/test_core.py` passes with equivalent covariance and scaling assertions.

## Known risks

- A Hessian describes local sharpness, not whether the selected peak is the correct repeated object. The ambiguity field and Stage 04 graph consistency remain necessary.
- Robust log-ratio weights make a naive least-squares noise formula optimistic. Implement the exact robust/sandwich or calibrated calculation frozen in Stage 01.
- Correlation's valid sample set changes at interpolation boundaries, which can destabilize finite differences. Use the frozen step rule and return unavailable evidence when the local matrix is not trustworthy.
- Covariance propagation under inverse rigid transforms is not component-wise sign reversal. A wrong Jacobian can create plausible but directionally wrong weights.
