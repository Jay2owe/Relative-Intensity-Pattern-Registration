# Fit translations while preserving the known event-angle trajectory

## Why this stage exists

Once event angles are known, the normal registration graph still needs translation measurements across and within segments. This stage adds an explicit two-degree-of-freedom fit at a prescribed angle and makes reconciliation and repair preserve the event trajectory exactly rather than smoothing or re-estimating it.

## Prerequisites

- `01_post-tuning-contract-and-fixtures_COMPLETED.md`
- `02_robust-event-angle-estimator_COMPLETED.md`

## Read first

- `docs/event-anchored-rotation/00_overview.md`
- `docs/event-anchored-rotation/BASELINE.md`
- `src/main/java/logratio/core/PairAligner.java`, lines 294-361, 383-470 and 488-670
- `src/main/java/logratio/core/Reconciler.java`, lines 113-182 and 201-317
- `src/main/java/logratio/core/ChainRepair.java`, lines 72-195
- `src/main/java/logratio/core/Transform.java`, lines 12-125
- `src/test/java/logratio/core/ReconcilerTest.java`, existing rigid cases
- `src/test/java/logratio/core/ChainRepairTest.java`, entire file
- `src/test/java/logratio/core/EventRotationFixtures.java`

## Scope

- Add an explicit pair-alignment entry point that searches/refines translation and gain while holding a caller-supplied angle fixed.
- Run the existing coarse translation search at that prescribed angle; do not rely on a zero-translation local start being close enough.
- Allocate and solve exactly two geometric derivatives in fixed-angle mode; angle must not move during coarse-to-fine refinement.
- Return the prescribed angle in the pair transform so exact rigid composition remains available downstream.
- Add multi-lag reconciliation that solves translation rows using known relative angles while emitting the supplied absolute frame angles without an angle least-squares solve.
- Rebase known angles exactly for a non-zero fixed reference frame.
- Add chain, fixed and rolling helpers as required so every existing reference strategy has defined event semantics.
- Change repair to interpolate/repair translations only in event mode; it must copy the known frame angle back exactly.
- Protect supplied event boundaries from generic step-outlier rejection so genuine remount translation is retained.
- Keep all existing translation-only and continuous-rigid methods untouched for their current callers.

## Out of scope

- Do not decide when event estimation runs; Stage 04 owns orchestration.
- Do not modify automatic shift-bound behaviour yet; Stage 04 owns its event-aware use.
- Do not make area correlation claim fixed-angle rigid support unless the accepted tuning separately validated it. The first event path uses the accepted log-ratio rigid engine.
- Do not expose public controls; Stage 05 owns the API and UI surfaces.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/core/PairAligner.java` | MODIFY | Add bounded coarse translation and two-parameter refinement at a prescribed angle. |
| `src/main/java/logratio/core/Reconciler.java` | MODIFY | Solve translations with exact caller-supplied frame angles. |
| `src/main/java/logratio/core/ChainRepair.java` | MODIFY | Repair translations without altering known angles or rejecting event boundaries. |
| `src/test/java/logratio/core/FixedAnglePairAlignerTest.java` | NEW | Pin fixed-angle recovery and prove theta never changes. |
| `src/test/java/logratio/core/ReconcilerTest.java` | MODIFY | Add piecewise-angle multi-lag and rebasing cases. |
| `src/test/java/logratio/core/ChainRepairTest.java` | MODIFY | Add protected-event and translation-only repair cases. |

## Implementation sketch

```java
public static Fit alignAtFixedAngle(
        LogPlane[] source,
        LogPlane[] target,
        Options options,
        double fixedTheta,
        Transform translationStart,
        boolean[][] solverSupport);
```

At the coarsest level, every translation candidate includes the fixed angle:

```java
Transform candidate = new Transform(dx, dy, fixedTheta);
```

The refinement parameter vector is `(dx, dy)` only. `fixedTheta` participates in coordinate sampling and the translation Jacobian, but no `jt`, angular normal-equation row, angular clamp or angular convergence term is active.

For a pair from frame `i` to `j`:

```text
relativeTheta(i,j) = absoluteTheta[j] - absoluteTheta[i]
```

For multi-lag translation reconciliation, retain the existing exact rigid row geometry but take the angle from the known trajectory. Do not solve a second angle system and accept a ridge-perturbed approximation.

Repair contract:

```java
ChainRepair.Result repairWithKnownAngles(
        Transform[] cumulative,
        int[] support,
        int anchor,
        double outlierMads,
        int width,
        int height,
        double[] knownAngles,
        boolean[] protectedEventFrames);
```

`protectedEventFrames[e]` means the step from `e-1` to `e` is a declared remount boundary. It may carry a large real translation and must not be removed merely because it differs from within-segment motion.

## Exit gate

1. `mvn -Dtest=logratio.core.FixedAnglePairAlignerTest,logratio.core.ReconcilerTest,logratio.core.ChainRepairTest test` passes.
2. Fixed-angle fits recover translation within the accepted translation gate while returning `Double.compare(result.theta, fixedTheta) == 0`.
3. A zero fixed angle follows the original translation objective and matches its recovery tolerance; the old public translation path remains bit-exact.
4. Multi-lag output angles are bit-identical to the supplied trajectory, including inside segments and after rebasing.
5. Translation reconciliation uses all usable planned edges, including edges that span an event.
6. An intentionally large supported remount translation survives outlier repair at the protected event frame.
7. An ordinary within-segment corrupt translation step is still repaired under the existing rule.
8. Continuous rigid reconciliation and repair tests retain their current results.
9. Reversed observations use the exact rigid inverse; component-wise negation is never introduced.
10. The full core test suite passes.

## Known risks

- Starting a two-parameter local refinement at zero translation can fail after a large remount. Preserve a full bounded coarse translation search at the known angle.
- Rotation about the image centre couples the coordinate representation of translation to angle. Use `Transform` composition/inversion; never subtract translations component-wise across rotated frames.
- Generic step repair currently treats unusual rigid motion as suspect. Failing to protect event boundaries would erase the physical remount jump this feature is meant to correct.
- A fixed reference frame inside a later segment changes the angle gauge. Rebase all poses exactly rather than subtracting only scalar angles and leaving translations in the old coordinate system.

