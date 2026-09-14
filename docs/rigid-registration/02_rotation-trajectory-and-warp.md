# Make rigid trajectories, repair and correction geometrically consistent

## Why this stage exists

Pairwise rotation is not enough. Automatic registration normally uses multiple frame lags, and the current multi-lag translation equations treat rigid translations as if angles were zero. Rotation also needs to participate in outlier detection, bound warnings and crop validation before a recovered angle can safely correct a recording.

## Prerequisites

- `01_bounded-logratio-rotation_COMPLETED.md`

## Read first

- `docs/rigid-registration/00_overview.md`
- `src/main/java/logratio/core/Transform.java`, lines 12-125: exact composition and inverse convention.
- `src/main/java/logratio/core/Reconciler.java`, lines 149-313: chain, fixed, rolling, multi-lag and rebasing paths.
- `src/main/java/logratio/core/ChainRepair.java`, lines 72-195: trust, translation-only outlier metric and parameter interpolation.
- `src/main/java/logratio/core/Registration.java`, lines 258-275, 624-650 and 914-950: warning kinds, pair warnings and repair integration.
- `src/main/java/logratio/core/Warper.java`, lines 39-169: interpolation, common margin and inverse warp.
- `src/test/java/logratio/core/ReconcilerTest.java`, lines 1-206.
- `src/test/java/logratio/core/ChainRepairTest.java`, lines 1-156.
- `src/test/java/logratio/core/WarperTest.java`, lines 39-201.
- `src/test/java/logratio/core/RegistrationGuardTest.java`, lines 1-356.

## Scope

- Replace multi-lag parameter addition with exact rigid constraints about the common image centre.
- Preserve the banded, frame-count-scalable solve; do not regress to a dense matrix for long recordings.
- Correctly orient reversed observations with `Transform.inverse()`, not component-wise negation.
- Keep frame zero eliminated exactly as the gauge.
- Test chain, fixed, rolling, rebasing and multi-lag paths with non-zero rotation.
- Make outlier detection use the exact consecutive relative transform rather than differences between stored parameters.
- Convert rotation into an image-size-aware root-mean-square pixel displacement before combining it with translation for outlier decisions.
- Continue interpolating repaired angles consistently, including across the +/-pi wrap if the allowed range can cross it.
- Add a distinct rotation-bound warning and count affected pairs.
- Validate that all `Warper.Interpolation` modes correct a known rigid transform using the existing inverse sign convention.
- Prove the common crop contains real source support for every corrected frame under the allowed angle range.
- Preserve exact whole-pixel copying for pure translations with angle exactly zero.

## Out of scope

- Do not change estimator selection or Automatic fallback; Stage 03 owns that.
- Do not add public fields, dialogs or macro tokens; Stage 04 owns them.
- Do not port these changes to Python; Stage 05 owns parity.
- Do not implement area-correlation rotation; Stage 06 owns it.
- Do not replace nearest-neighbour rotation with bilinear silently. Stage 04 owns the user-facing interpolation warning and choice.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/core/Reconciler.java` | MODIFY | Solve multi-lag rigid constraints exactly instead of adding translations independently. |
| `src/main/java/logratio/core/ChainRepair.java` | MODIFY | Use relative rigid motion and image-size-aware angular displacement for outlier detection. |
| `src/main/java/logratio/core/Registration.java` | MODIFY | Pass image geometry into repair and report rotation-bound warnings. |
| `src/main/java/logratio/core/Warper.java` | MODIFY | Tighten/document the rigid common-margin contract if validation exposes an invalid or unnecessarily destructive bound. |
| `src/test/java/logratio/core/ReconcilerTest.java` | MODIFY | Add exact rigid chain, multi-lag, reverse-observation and rebase tests. |
| `src/test/java/logratio/core/ChainRepairTest.java` | MODIFY | Add angular outlier and rigid interpolation tests. |
| `src/test/java/logratio/core/WarperTest.java` | MODIFY | Add known rotation correction and common-support tests. |
| `src/test/java/logratio/core/RegistrationGuardTest.java` | MODIFY | Test end-to-end warnings, repair and valid output geometry. |

## Implementation sketch

Let `C_t` map frame 0 content coordinates to frame `t`, and let an oriented observation `O_ij` map frame `i` to frame `j`. The exact constraint is:

```text
C_i.then(O_ij) = C_j
```

Given the `Transform.then` convention, this gives linear constraints even though translation and rotation are coupled:

```text
theta_j - theta_i = theta_ij
t_j - R(theta_ij) * t_i = t_ij
```

Build the observation in increasing frame order first:

```java
Transform observed = ob.to > ob.from
        ? ob.displacement
        : ob.displacement.inverse();
int lo = Math.min(ob.from, ob.to);
int hi = Math.max(ob.from, ob.to);
```

Do not use `sign * dx`, `sign * dy` for a reversed rigid observation; the exact inverse rotates its translation. Solve angle with the existing scalar banded structure. Solve `x` and `y` as a coupled two-coordinate banded system because each `R(theta_ij)` introduces off-diagonal x/y terms. Preserve `O(T*b^2)` scaling for `T` frames and maximum lag `b`.

For outlier detection, derive the exact relative step:

```java
Transform step = cumulative[t - 1].inverse().then(cumulative[t]);
```

For a rectangular image centred at the origin, use root-mean-square field displacement:

```text
varX = (width^2 - 1) / 12
varY = (height^2 - 1) / 12
rotationMeanSquare = 2 * (varX + varY) * (1 - cos(step.theta))
rigidMagnitude = sqrt(step.dx^2 + step.dy^2 + rotationMeanSquare)
```

This reduces exactly to translation magnitude at zero angle. Add an overload or explicit geometry object rather than changing the meaning of the existing public `repair(...)` call without warning.

If angles can approach the wrap boundary, interpolate the shortest signed angular difference:

```text
delta = atan2(sin(b.theta - a.theta), cos(b.theta - a.theta))
theta = a.theta + f * delta
```

For cropping, the existing `|theta| * halfDiagonal` margin is conservative. Tests must establish that every retained destination pixel samples valid source support for every cumulative transform and interpolation reach. Improve the calculation only if it is invalid or loses an unacceptable amount of field; do not replace a safe conservative bound merely for elegance.

## Exit gate

These are proposed gates derived from the current geometry contracts; confirm them before accepting the stage.

1. `mvn -Dtest=logratio.core.ReconcilerTest,logratio.core.ChainRepairTest,logratio.core.WarperTest,logratio.core.RegistrationGuardTest test` passes.
2. A consistent set of large-enough rigid multi-lag observations is recovered to numerical tolerance using exact composition, including a reversed observation.
3. The rigid multi-lag test fails under the old component-wise translation equations, proving it covers the defect rather than restating a zero-angle case.
4. A pure angular outlier is repaired and flagged; an equivalent legitimate smooth angular drift is not erased.
5. At `theta = 0`, the outlier metric equals the old translation magnitude and existing translation repair tests remain unchanged.
6. A rotation-bound pair produces a rotation-specific warning containing the bound in degrees.
7. For nearest-neighbour, bilinear and bicubic correction, the known rigid fixture moves in the expected inverse direction and output values obey each interpolation contract.
8. Every pixel inside the reported common crop has valid source support in every frame for the tested transforms.
9. A pure whole-pixel translation still uses the bit-exact copy path.
10. `mvn test` passes without changing unrelated benchmark artifacts.

## Known risks

- A dense 2T-by-2T normal matrix would make long recordings expensive. Retain banded storage and add a long-series test or operation-count assertion.
- Using component-wise negation for reversed observations is almost correct at tiny angles and can evade weak tests. Include a deliberately non-trivial rotation and translation.
- Rotation and translation have different physical scales. The root-mean-square pixel metric requires the actual image dimensions; do not use an arbitrary pixels-per-degree constant.
- Parameter interpolation across the angle wrap can take the long way around. The allowed product range may make this rare, but the helper should still be correct.
- Cropping can be safe but excessively conservative. Record retained dimensions at the maximum supported angle so the cost is visible.
