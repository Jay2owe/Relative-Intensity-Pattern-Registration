# Stage 04 - Add robust graph-consistency reweighting

## Why this stage exists

Estimator uncertainty can be confidently wrong when a repeated structure or changed object produces a sharp false match. Multi-lag registration contains alternate routes between frames, so this stage uses disagreement with the wider graph to reduce an inconsistent pair's influence without silently deleting it.

## Prerequisites

- `03_weighted-reconciliation_COMPLETED.md` exists.
- Equal and uncertainty-weighted Java/Python solves pass their analytic and dense-reference tests.
- The robust loss, scale, influence floor and convergence rules are frozen in `weighting_protocol.md`.

## Read first

- `C:/Users/jamie/AGENTS.md` - session and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:15-35` and `:56-68` - combined objective and reporting requirements.
- `docs/confidence-weighted-reconciliation/weighting_protocol.md` - exact robust residual, scale, threshold, normalization and stop rules.
- `src/main/java/logratio/core/Reconciler.java:73-103` and the complete Stage 03 `multiLag` implementation - observations, solution and weighted block solve.
- `src/main/java/logratio/core/ChainRepair.java` - current robust step scale and unsupported/outlier semantics; read the complete file.
- `src/ripr/core.py:1069-1165` - Python reconciliation and repair behaviour.
- `src/test/java/logratio/core/WeightedReconcilerTest.java` - weighted analytic reference cases that robust modes must preserve when all edges agree.

## Scope

- Add robust-only and combined uncertainty-plus-robust strategies.
- Compute each edge's graph residual from the fitted cumulative poses using the same rigid constraint equations as the solve.
- Convert residuals to the dimensionless or pixel-equivalent norm frozen in Stage 01.
- Iteratively solve, recompute graph consistency and update one bounded robust factor per pair.
- Keep every usable edge at or above the declared influence floor; never silently delete an observation.
- Preserve graph topology and deterministic results regardless of pair task completion order.
- Do not penalize an edge solely because it has a longer lag.
- Retain final graph residuals, standardized residuals, robust factors and floor flags in the reconciliation solution for Stage 05 to expose.
- Mirror the algorithm in Python.

## Out of scope

- Estimator covariance calculation is frozen in Stage 02.
- Registration-pipeline wiring and public diagnostics are Stage 05.
- Strategy benchmarking, selector testing and promotion are Stages 06, 07 and 08.
- Repair remains a later frame-level safeguard; robust edge weighting does not replace `ChainRepair`.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/logratio/core/Reconciler.java` | MODIFY | Add robust-only/combined strategies, iterative reweighting and retained edge state. |
| `src/ripr/core.py` | MODIFY | Mirror robust graph residuals, factors and convergence in Python. |
| `src/test/java/logratio/core/RobustReconcilerTest.java` | NEW | Test outlier containment, floors, convergence, lag neutrality and determinism. |
| `src/test/java/logratio/core/WeightedReconcilerTest.java` | MODIFY | Prove robust modes reduce to the weighted solution when all constraints agree. |
| `tests_python/test_core.py` | MODIFY | Exercise equivalent Python robust and combined graph cases. |

## Implementation sketch

Extend the Stage 03 strategy enum:

```java
public enum Weighting {
    EQUAL,
    UNCERTAINTY,
    ROBUST,
    COMBINED
}
```

Retain one state record per used observation in deterministic plan order:

```java
static final class EdgeState {
    final int from;
    final int to;
    final Transform residual;
    final double standardizedResidual;
    final double robustFactor;
    final boolean factorFloored;
}
```

Stage 05 may make the final diagnostic type public, but this stage must retain all values rather than recomputing them from repaired poses.

1. Define base information by strategy:
   - `EQUAL`: execute the untouched equal branch and do not iterate.
   - `UNCERTAINTY`: execute the Stage 03 information solve once.
   - `ROBUST`: use the frozen isotropic pixel-equivalent base information; for rigid movement scale angle with the frame root-mean-square radius supplied in reconciliation options.
   - `COMBINED`: use estimator information from Stage 02.
2. Start every robust factor at `1.0`. Solve the graph, calculate each pair residual in the same canonical direction as its design block, then calculate the frozen standardized residual. For uncertainty-aware mode this will normally be a normalized Mahalanobis distance. For robust-only rigid mode it must include pixel-equivalent rotation rather than comparing raw radians with pixels.
3. Apply the exact leverage correction, alternate-route residual or other self-influence guard frozen in Stage 01. A high-information edge must not be allowed to declare itself consistent merely because it pulled the solution onto itself.
4. Estimate robust scale only from the declared comparison group. If the protocol groups by lag, normalize within lag without changing the total intended contribution of long-lag checks. Do not introduce an unrecorded lag penalty.
5. Convert the standardized residual to the frozen Huber or other declared factor. Clamp to `[minimumFactor, 1]`, retain whether the floor was reached, and multiply the complete information block by that scalar.
6. Repeat until both trajectory change and factor change meet the frozen tolerances, or until the maximum iteration count. Use deterministic order and stable summation. A maximum-iteration result is returned with an explicit non-convergence flag for Stage 05.
7. Check graph connectivity before and after reweighting. Because factors never reach zero, the usable-edge topology must be unchanged. If the original usable graph is disconnected from the gauge, preserve existing support/repair behaviour and report it; do not manufacture a bridge.
8. Return final edge states calculated against the unrepaired solution. Repair happens after reconciliation and must not make a guessed frame appear to validate an edge.
9. Mirror the same iterations and stopping rules in Python. Allow small floating-point tolerance differences, but factors and cumulative poses must agree within parity gates.

## Exit gate

1. A graph with one gross conflicting edge is closer to the known trajectory under robust-only and combined strategies than under the matching non-robust base strategy.
2. A graph whose observations agree exactly returns factors of `1.0` and the same trajectory as its non-robust counterpart.
3. No usable observation receives a zero, negative or non-finite factor; a floored edge remains present in the normal equations.
4. Reordering input observations cannot change transforms, factors or convergence status beyond `1e-12` after results are restored to deterministic plan order.
5. Identical residual patterns at different lags receive the same treatment unless the frozen protocol explicitly defines a data-relative group scale; lag alone is never a penalty.
6. Rigid tests show that a rotational inconsistency is detected in pixel-equivalent or Mahalanobis units rather than being hidden by radians.
7. A high-information false edge is downweighted by the frozen self-influence guard when alternate routes disagree.
8. `mvn -q -Dtest=RobustReconcilerTest,WeightedReconcilerTest,ReconciliationBaselineTest test` passes.
9. `python -m pytest tests_python/test_core.py` passes with equivalent robust factors and trajectories.

## Known risks

- Ordinary fitted residuals have leverage: a highly weighted bad edge can pull the solution toward itself and then look less inconsistent. The Stage 01 self-influence rule is essential.
- Too low an influence floor leaves the graph numerically connected but practically unsupported; too high a floor fails to contain a bad edge. Both are benchmarked, not adjusted on locked data.
- If several wrong edges agree with one another, graph consistency may reinforce the wrong route. Estimator uncertainty, peak ambiguity and worst-case benchmark gates remain necessary.
- Robust iterations add runtime and can oscillate near the threshold. The frozen convergence and maximum-iteration rules must be reported rather than silently extended.
