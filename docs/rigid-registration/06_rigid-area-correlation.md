# Add bounded rigid estimation to area correlation

## Why this stage exists

Automatic selection prefers Newton area correlation for brightfield/DIC and fiducial/static recordings. The first release can safely fall back to log-ratio, but restoring the preferred estimator requires correlation itself to search and refine rotation. This stage implements that engine capability in Java and Python while leaving Automatic validation false until Stage 07.

## Prerequisites

- `01_bounded-logratio-rotation_COMPLETED.md`
- `02_rotation-trajectory-and-warp_COMPLETED.md`

Stage 06 may be implemented in parallel with Stages 03-05 once Stage 02 is complete, but merge against their final capability API before running the exit gate.

## Read first

- `docs/rigid-registration/00_overview.md`
- `src/main/java/logratio/core/AreaCorrelation.java`, lines 186-250: shared pyramid alignment and refiner selection.
- `src/main/java/logratio/core/AreaCorrelation.java`, lines 272-409: translation sweep and grid refinement.
- `src/main/java/logratio/core/AreaCorrelation.java`, lines 563-620: Newton refinement.
- `src/main/java/logratio/core/AreaCorrelation.java`, lines 755-813: translation-only scorer and clamp.
- `src/main/java/logratio/core/PairEstimator.java`, lines 80-315: all area-correlation estimator kinds and their shared seam.
- `src/test/java/logratio/core/AreaCorrelationRecoveryTest.java`, lines 63-255.
- `src/test/java/logratio/core/AreaCorrelationNewtonTest.java`, lines 45-286.
- `src/test/java/logratio/ThevenazProtocolBenchmark.java`, lines 550-620: estimator seam and rigid fixture.
- `src/test/java/logratio/ThevenazProtocolBenchmarkTest.java`, lines 107-160: rigid gates.
- `src/logratio/core.py`, lines 537-808: Python area windows, score, refinement and reporting.
- `docs/newton_refinement_stage2_diagnosis.md`: sparse-valid-support limitation of Newton correlation.
- `docs/thevenaz_protocol_findings.md`, lines 64-83: rigid comparison from which area correlation was previously absent.

## Scope

- Generalize correlation scoring from `(dx, dy)` to a complete `Transform` about the image centre.
- Add the same maximum angular bound and deterministic coarsest-level angle initialization used by log-ratio fitting.
- For each coarse angle, run the existing translation sweep against the rotated sampling geometry.
- Extend grid refinement to adjust translation and angle with comparable outer-field pixel steps.
- Give the Newton estimator a rotation-aware refinement path; it may combine Newton translation steps with bounded angular refinement if a stable full 3D Hessian is not supported by the valid-pixel geometry.
- Preserve overlap, normalization, valid-pixel and fallback rules under rotated sampling.
- Apply the implementation to the log-pyramid, linear-pyramid, grid, Newton and subsampled correlation variants through their shared seam.
- Mark correlation estimators engine-capable only after their tests pass.
- Keep selector-level rigid validation false so Automatic mode continues its Stage 03 fallback.
- Implement the same transform-aware area score and refinement in Python.
- Add correlation rigid arms to the existing Thevenaz harness and report mean, median and worst error.
- Prove `fitRotation = false` retains current translation-only output and refinement behaviour.

## Out of scope

- Do not remove the Automatic fallback here. Stage 07 requires rotated cross-image evidence first.
- Do not tune on the third sealed set or any other spent locked material described in the Newton findings.
- Do not replace normalized correlation with log-ratio fitting or Fourier-Mellin registration; this stage extends the current estimator family.
- Do not add scale, shear or affine parameters.
- Public controls already belong to Stages 04-05; use the shared movement-model settings.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/core/AreaCorrelation.java` | MODIFY | Generalize sweep, score and refiners to bounded rigid transforms. |
| `src/main/java/logratio/core/PairEstimator.java` | MODIFY | Mark area variants engine-capable only after the implementation passes. |
| `src/test/java/logratio/core/AreaCorrelationRecoveryTest.java` | MODIFY | Test rigid recovery, gain/offset invariance and translation stability. |
| `src/test/java/logratio/core/AreaCorrelationNewtonTest.java` | MODIFY | Test angular derivative/refinement, sparse support and grid handoff. |
| `src/test/java/logratio/ThevenazProtocolBenchmark.java` | MODIFY | Add rigid area-correlation arms using the existing fixed transformations. |
| `src/test/java/logratio/ThevenazProtocolBenchmarkTest.java` | MODIFY | Gate rigid correlation and transformation-class matching. |
| `src/logratio/core.py` | MODIFY | Port transform-aware correlation and bounded refinement. |
| `tests_python/test_core.py` | MODIFY | Test Python correlation rotation and disabled-path stability. |

## Implementation sketch

Change the shared scorer to accept a transform and centre:

```java
private static double correlation(Window a, Window b, Transform p,
                                  double cx, double cy,
                                  PairAligner.Options options, ...) {
    // For every source sample (x, y), evaluate b at p.apply(x, y, cx, cy).
    // Accumulate the same normalized-correlation sums over the resulting valid overlap.
}
```

At the coarsest level, use the same deterministic angular candidate generator as Stage 01. For each angle, seed the current integer translation sweep. At finer levels, retain the angle while doubling only translation:

```java
p = p.scaleTranslation(2.0);
```

Make grid steps comparable by their movement at the field edge:

```text
angular_step_radians * half_diagonal approximately translation_step_pixels
```

Each refinement round can score neighbouring `dx`, `dy` and `theta` candidates, accept only an improving candidate, then shrink both pixel and angular step sizes. Keep the existing valid-overlap minimum.

For `AREA_CORRELATION_NEWTON`, prefer the smallest defensible extension:

1. Use the rotation-aware coarse and pyramid grid initialization.
2. Retain the established Newton `dx,dy` update at a fixed angle.
3. Add a bounded one-dimensional angular line/grid refinement using the same normalized-correlation score.
4. Alternate until outer-field movement is below convergence or no score improves.

A full 3x3 Newton system is acceptable only if its normalized-correlation angle derivative and changing-valid-support behaviour are derived and numerically checked. Do not insert the log-ratio Hessian into the correlation objective.

When `fitRotation` is false, call the existing `(dx,dy)` loops directly so translation-only candidate order and results remain unchanged.

Mirror the same candidate order, overlap rule and refinement in Python. Do not mark Stage 03's generated candidate metadata rigid-validated in this stage:

```text
engine supports rotation: true after gates pass
automatic rigid validation: false until Stage 07
```

## Exit gate

These are proposed engine gates. Passing them proves implementation capability, not permission for Automatic selection.

1. `mvn -Dtest=logratio.core.AreaCorrelationRecoveryTest,logratio.core.AreaCorrelationNewtonTest,logratio.ThevenazProtocolBenchmarkTest test` passes.
2. Both grid and Newton correlation recover a known combined translation and rotation within predeclared tolerances on the existing rigid fixture.
3. Rotation-bound hits are reported correctly and never escape the configured bound.
4. Gain and additive-offset invariance of normalized correlation remains intact under rotation.
5. Newton rigid refinement either improves the initialized score or hands back safely to the grid result; sparse valid support never produces a confident invalid angle.
6. With rotation disabled, existing area-correlation transforms and statuses remain unchanged for every shipped correlation kind.
7. The Thevenaz rigid report includes log-ratio, correlation grid, correlation Newton, TurboReg rigid and do-nothing arms on identical transformations, with mean, median and worst error.
8. `python -m pytest tests_python/test_core.py` passes and Python correlation recovers the same rigid fixture within the declared tolerance.
9. Stage 03 tests still prove rigid Automatic mode falls back; engine capability alone has not changed selector validation.
10. `mvn test` and the full Python suite pass.

## Known risks

- Angle-by-translation scoring is expensive. Reuse prepared windows and sampling strides; measure candidate counts and runtime before adding shortcuts.
- The normalized-correlation surface changes when rotated samples cross a valid boundary. This already affects Newton translation on sparse bead fields and can be worse in angle. Keep a score-based handoff.
- A radially symmetric image cannot localize angle. Return the best bounded translation with a non-converged/low-information status rather than inventing rotation.
- Translation-only behavior is frozen evidence for the selector. A shared-code refactor that changes it invalidates that evidence even if rigid tests pass.
- A good single-image Thevenaz result is not permission to enable Automatic selection. Stage 07 is mandatory.
