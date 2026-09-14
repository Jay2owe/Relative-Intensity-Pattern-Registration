# Deliver a bounded rotation solver for the log-ratio estimator

## Why this stage exists

The hidden `fitRotation` derivative can refine an angle near the right solution, but the coarse search initializes every pair at zero rotation and no angular limit exists. The existing Thevenaz experiment found excellent median accuracy but severe wrong-basin failures across +/-10 degrees. This stage makes the log-ratio estimator robust enough to become the fallback rigid engine used by every image type downstream.

## Prerequisites

None.

## Read first

- `docs/rigid-registration/00_overview.md`
- `src/main/java/logratio/core/Transform.java`, lines 12-125: rigid transform convention, exact composition and image-centre application.
- `src/main/java/logratio/core/PairAligner.java`, lines 79-163: options and statuses.
- `src/main/java/logratio/core/PairAligner.java`, lines 207-241: coarse-to-fine alignment entry point.
- `src/main/java/logratio/core/PairAligner.java`, lines 366-397: translation-only coarse search.
- `src/main/java/logratio/core/PairAligner.java`, lines 419-590: joint derivative, backtracking and translation clamp.
- `src/main/java/logratio/core/PairAligner.java`, lines 739-763: current three-parameter linear solve.
- `src/test/java/logratio/core/PairAlignerRecoveryTest.java`, lines 1-192: recovery fixtures and current translation gates.
- `src/test/java/logratio/ThevenazProtocolBenchmarkTest.java`, lines 107-140: existing rotation and zero-rotation gates.
- `docs/thevenaz_protocol_plan.md`, lines 140-191, and `docs/thevenaz_protocol_findings.md`, lines 64-83: predeclared rigid test and measured bimodal result.

## Scope

- Keep `fitRotation = false` as the default and preserve the exact translation-only search path.
- Add an internal maximum absolute rotation in radians to `PairAligner.Options`; copying options must preserve it.
- Replace zero-angle initialization with a bounded angular sweep at the coarsest pyramid level when rotation fitting is enabled.
- For each angular candidate, use the existing bounded translation sweep and the same log-ratio objective so the initializer chooses one joint basin.
- Derive the angular spacing from the coarsest frame radius so adjacent candidates move the outer field by no more than approximately one coarse pixel.
- Include zero and both requested angular endpoints deterministically; use stable tie-breaking.
- Clamp trial and starting transforms in translation and angle at every pyramid level and in `alignFrom`.
- Distinguish a final rotation-bound hit from an ordinary converged result.
- Retain joint `dx`, `dy`, `theta` Gauss-Newton refinement and backtracking after initialization.
- Add deterministic synthetic tests for combined translation, rotation and global gain.
- Pin the zero-rotation regression: enabling rotation must stay within the already declared Thevenaz degradation allowance, while disabling it must preserve current translation results.
- Measure the added coarse-search cost and add a documented evaluation cap only if the uncapped, radius-derived grid is impractical.

## Out of scope

- Multi-frame reconciliation, outlier repair, crop margins and user warnings belong to Stage 02.
- Automatic-selector filtering belongs to Stage 03.
- Public degree-valued parameters, dialogs and macros belong to Stage 04.
- Python parity belongs to Stage 05.
- Area-correlation rotation belongs to Stage 06.
- Do not tune the solver against locked automatic-selector test recordings; Stage 07 owns fresh cross-image validation.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/core/PairAligner.java` | MODIFY | Add the angular bound, coarse angular initialization, combined clamp and bound status. |
| `src/test/java/logratio/core/PairAlignerRecoveryTest.java` | MODIFY | Add combined rigid recovery, gain invariance, bound and disabled-path regression tests. |
| `src/test/java/logratio/core/PairAlignerCostTest.java` | MODIFY | Pin objective/backtracking behaviour when the third parameter is active. |
| `src/test/java/logratio/ThevenazProtocolBenchmarkTest.java` | MODIFY | Extend the existing declared rotation gate to exercise the bounded initializer. |

## Implementation sketch

Keep radians in the core:

```java
public static final class Options {
    public boolean fitRotation = false;
    /** Maximum absolute in-plane rotation, radians; ignored when fitRotation is false. */
    public double maxRotation = /* proposed: test at Math.toRadians(10), document product default later */;
}
```

Do not run a separate translation search followed by an unrelated angle guess. At the coarsest level, score complete rigid candidates:

```java
Transform best = Transform.IDENTITY;
for (double theta : angularCandidates(maxRotationHere, coarseHalfDiagonal)) {
    for (int dy = -radius; dy <= radius; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
            if (Math.hypot(dx, dy) > maxShiftHere) continue;
            Transform candidate = new Transform(dx, dy, theta);
            Sample score = evaluate(a, b, candidate, cx, cy, options, scratch, 0, 0);
            // Existing valid-fraction gate and deterministic tie-breaking.
        }
    }
}
```

Use an automatically derived angular step:

```text
coarse_edge_motion approximately coarse_half_diagonal * delta_theta <= 1 coarse pixel
delta_theta <= 1 / max(coarse_half_diagonal, 1) radians
```

Always include `0`, `-maxRotation` and `+maxRotation` even when the regular spacing does not land on them. Preserve the original loop and evaluation order byte-for-byte when `fitRotation` is false.

Replace `clampShift` in rigid paths with a helper that independently bounds the translation magnitude and angle. Do not infer a bound hit from whether one intermediate trial was clamped; classify the final transform, as the existing translation code already does:

```java
if (fit.statusIsUsable() && Math.abs(p.theta) >= o.maxRotation * (1.0 - 1e-9)) {
    status = Status.AT_ROTATION_BOUND;
}
```

If both bounds are hit, retain both facts internally or choose a single general transform-bound status; Stage 02 must be able to produce an accurate warning. Do not collapse a rotation-bound hit into `AT_SHIFT_BOUND` text.

The existing synthetic acceptance limits are evidence, not a new tuning target:

```text
known rigid fixture: <= 0.10 degrees and <= 0.10 pixels
zero-rotation degradation from enabling rotation: <= 0.01 pixels
```

Also test several angles across the bounded interval and at least one gain-scaled target. Report median and worst recovery, because the existing failure was bimodal and a median alone hid it.

## Exit gate

The inherited Thevenaz limits are existing gates. The remaining checks are proposed and should be confirmed before implementation changes are accepted.

1. `mvn -Dtest=logratio.core.PairAlignerRecoveryTest,logratio.core.PairAlignerCostTest,logratio.ThevenazProtocolBenchmarkTest test` passes.
2. The known rigid fixture remains within 0.10 degrees and 0.10 pixels.
3. A test near the angular limit either recovers inside the limit or returns the explicit rotation-bound status; it never reports ordinary `OK` on the boundary.
4. The combined rotation-and-gain fixture recovers the same geometry within the declared tolerance.
5. With `fitRotation = false`, existing translation fixtures produce the same transform and status as before this stage.
6. With rotation enabled and zero true rotation, error degradation remains no more than 0.01 pixels on the existing Thevenaz gate.
7. Record angular candidate count and elapsed time for the +/-10 degree protocol. If a cap is added, a test proves it is deterministic and still includes zero and both bounds.
8. `mvn test` introduces no new failure.

## Known risks

- A full angle-by-translation grid can multiply coarse-search time. First reduce samples through the existing `maxSamples` stride and pyramid radius; do not silently widen angular spacing until the outer-field displacement exceeds a pixel.
- A smooth synthetic fixture may overstate convergence. Keep worst-case reporting and the published Thevenaz generator in the gate.
- The 3x3 normal matrix can become ill-conditioned on radially symmetric or textureless images. Preserve the current singular-solve behaviour and never manufacture an angle without information.
- The public default maximum rotation remains an open product decision. Do not silently equate the +/-10 degree benchmark range with that default.
