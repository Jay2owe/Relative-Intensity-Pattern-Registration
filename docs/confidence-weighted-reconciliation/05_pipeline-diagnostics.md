# Stage 05 - Wire weighting through registration and expose diagnostics

## Why this stage exists

Stages 02 to 04 operate at pair and graph level. This stage makes all four strategies runnable through the complete registration pipeline and attaches the evidence behind each final influence to the returned pair result. Equal weighting remains the production default and there is still no main-dialog tuning control.

## Prerequisites

- `04_robust-reweighting_COMPLETED.md` exists.
- Java and Python core tests pass for all four strategies.
- Reconciler solutions retain final per-edge residual and robust state before frame repair.

## Read first

- `C:/Users/jamie/AGENTS.md` - session and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:24-35` and `:55-70` - final pipeline and diagnostic rules.
- `src/main/java/logratio/core/Registration.java:51-220`, `:297-400`, `:540-565`, `:839-895` and `:994-1048` - options, pair/result types, refit path, main reconciliation and assembly.
- `src/main/java/logratio/core/Reconciler.java` - complete Stage 04 observation, options, solution and edge-state contracts.
- `src/main/java/logratio/api/LogRatioResult.java:10-61` - existing public access to the core registration result.
- `src/ripr/core.py:975-1043`, `:1069-1136` and `:1182-1225` - Python options, results, reconciliation and assembly.
- `src/test/java/logratio/core/PythonParityProbe.java` and `tests_python/test_java_parity.py` - established cross-language comparison route.

## Scope

- Add an internal experimental reconciliation strategy to Java and Python registration options, defaulting to equal.
- Pass each pair's uncertainty into every multi-lag observation, including the two-pass pixel-selection refit path.
- Supply translation/rigid dimensionality and the frame root-mean-square rotation radius to the reconciler.
- Attach one final reconciliation diagnostic to every planned pair result in plan order.
- Expose estimator covariance/information, peak ambiguity, graph residual, standardized residual, robust factor, final information scale, cap/floor/fallback flags and convergence state.
- Preserve the distinction between pair failure, pair downweighting and later frame repair.
- Correctly scale diagnostic translations and covariance when estimation ran at reduced resolution.
- Add summary helpers for counts of unavailable uncertainty, capped/floored matrices, downweighted pairs and robust non-convergence.
- Extend Java/Python parity coverage to all four strategies.
- Keep the main Fiji dialog, recommendations, Automatic selector and macro defaults unchanged.

## Out of scope

- No strategy is promoted or selected from recording evidence in this stage; Stages 07 and 08 own selector testing and production decisions.
- No thresholds are tuned here; Stage 01 values are frozen and Stage 06 evaluates them.
- No new claim is made from lower image residual or high correlation.
- No repaired frame is relabelled as directly measured.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/logratio/core/Registration.java` | MODIFY | Route experimental strategies through full registration and attach per-pair influence diagnostics. |
| `src/main/java/logratio/core/Reconciler.java` | MODIFY | Finalize an immutable public diagnostic value returned with each used observation. |
| `src/ripr/core.py` | MODIFY | Mirror full-pipeline strategy options, pair diagnostics, scaling and summary counts. |
| `src/test/java/logratio/core/RegistrationTest.java` | MODIFY | Test end-to-end strategy routing, pair order, scaling and repair separation. |
| `src/test/java/logratio/core/RegistrationGuardTest.java` | MODIFY | Guard invalid evidence, robust non-convergence and unchanged equal defaults. |
| `src/test/java/logratio/core/PythonParityProbe.java` | MODIFY | Emit transforms and pair-influence fields for all four strategies. |
| `tests_python/test_core.py` | MODIFY | Test Python result wiring and diagnostic meanings. |
| `tests_python/test_java_parity.py` | MODIFY | Compare Java/Python transforms, matrices, residuals and factors. |

## Implementation sketch

Finalize an immutable diagnostic contract, for example:

```java
public static final class PairInfluence {
    public final int from;
    public final int to;
    public final Transform graphResidual;
    public final double standardizedResidual;
    public final double robustFactor;
    public final boolean factorFloored;
    public final boolean uncertaintyFallback;
    public final boolean robustConverged;
}
```

The estimator covariance, ambiguity and eigenvalue flags remain in `PairResult.fit.uncertainty`; do not duplicate matrices with a second source of truth. Add `PairInfluence influence` to `Registration.PairResult`, nullable only for reference modes where graph reconciliation does not consume that pair.

1. Add a package-level experimental field such as `Reconciler.Weighting reconciliationWeighting = EQUAL` to `Registration.Options` and copy it in `Options.copy()`. Do not add it to `LogRatioParameters`, the dialog or macro parser yet. Put the benchmark in `logratio.core` so it can configure the experimental field without prematurely widening the public API.
2. In both the normal pair path and the information-mask refit path, construct `Observation(from, to, fit.transform, fit.uncertainty)` only for usable fits. Preserve failed pair results for reporting even though they do not enter the solve.
3. Build reconciliation options from `aligner.fitRotation` and frame geometry. Use the same root-mean-square radius expression already used by rotation-aware repair, avoiding two definitions of pixel-equivalent angle.
4. Match returned influences to pair results by deterministic `(from, to, planIndex)`, not by task completion order and not by lag alone. Failed pairs receive an explicit `notUsed` diagnostic rather than borrowing another edge's values.
5. Keep graph residuals calculated against the unrepaired cumulative trajectory. `Registration.Result.repairs` continues to describe later interpolation. Add summary methods rather than merging these concepts.
6. Update `Result.scaleTranslations(factor)` so pair transforms, graph residual dx/dy and covariance x/y terms scale once; theta and robust factors do not scale.
7. Mirror options, result fields and summary properties in Python. Python's high-level public parameters still produce equal mode until Stage 08.
8. Extend the parity probe with fixed distinctive, anisotropic and conflicting graphs. Compare covariance/information within a declared matrix tolerance, cumulative transforms within the existing transform tolerance, and robust factors within a separate iterative tolerance.

## Exit gate

1. A full multi-lag registration can run equal, uncertainty-only, robust-only and combined strategies from internal Java and Python registration options.
2. Default Java and Python runs still select equal weighting and reproduce Stage 01 transforms and statuses.
3. Every planned pair has a diagnostic whose indices and use/failure state match that exact pair; parallel scheduling cannot reorder the association.
4. Reported final influence can be traced to estimator uncertainty, graph consistency and every cap/floor/fallback applied.
5. Graph residuals describe the unrepaired solve, while `repairs` independently marks interpolated frame positions.
6. Reduced-resolution tests prove dx/dy covariance and residual scaling once, with theta and dimensionless factors unchanged.
7. `mvn -q -Dtest=RegistrationTest,RegistrationGuardTest,ReconciliationBaselineTest,WeightedReconcilerTest,RobustReconcilerTest test` passes.
8. `python -m pytest tests_python/test_core.py tests_python/test_java_parity.py` passes for all four strategies.
9. No Fiji dialog choice, Automatic model coefficient, recommendation or macro default changed.

## Known risks

- Pair diagnostics can be attached to the wrong edge if parallel completion order is used. Plan index must remain the identity.
- Repaired cumulative poses can make a post-repair edge residual look artificially good. Preserve pre-repair graph evidence.
- A matrix has no single universally meaningful "confidence" number. Report directional standard deviations/eigenvalues plus the scalar robust factor rather than inventing one opaque score.
- Adding a core experimental option can accidentally leak into the user interface through generic serialization. Tests must prove current dialog and macro strings are unchanged.
