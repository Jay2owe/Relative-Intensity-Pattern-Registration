# Stage 01 - Freeze the post-rotation baseline and evaluation protocol

## Why this stage exists

Confidence weighting must not be designed or judged against a moving rotation implementation. This stage records the exact equal-weight behaviour after rotation is stable, freezes the uncertainty and robust-weighting calculations that later stages may implement, and declares the development, validation and locked evidence before any weighting result exists.

## Prerequisites

- The rotation engine and fallback behaviour are finalized: angle sign, angular bounds, both pair estimators, multi-lag rigid equations, repair, warping and the explicit rigid log-ratio Automatic fallback all pass their tests.
- `docs/rigid_selector_validation_gap.md` remains authoritative. Missing data for rigid Automatic-selector retraining does not block this stage and must not be worked around by reopening spent evidence.
- The current dirty worktree has been recorded so this stage can distinguish its own files from unrelated rotation and benchmark changes.

## Read first

- `C:/Users/jamie/AGENTS.md` - session communication and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:1-85` - approved goal, stage order and house rules.
- `docs/rigid-registration/00_overview.md:17-79` - rigid transform contract and validation rules.
- `docs/rigid_selector_validation_gap.md:1-45` - evidence that must remain an explicit fallback rather than being silently reused.
- `src/main/java/logratio/core/Reconciler.java:210-340` - current equal-weight rigid equations and banded solver.
- `src/main/java/logratio/core/PairAligner.java:178-213` and `:550-600` - current pair result and log-ratio curvature calculations.
- `src/main/java/logratio/core/AreaCorrelation.java:256-409` and `:641-867` - rigid correlation search and translation surface curvature.
- `library/benchmark/v2/README.md:1-149` - controlled-motion design, source balancing and the distinction between known-truth and natural-motion evidence.
- `docs/thevenaz_protocol_plan.md:20-43` and `:102-156` - rigid warping-index definition and existing controlled rigid fixtures.

## Scope

- Run and record the stable Java and Python translation/rotation baseline before changing production code.
- Add deterministic tests that pin the present equal-weight multi-lag translation and rigid solutions, including reversed observations and missing edges.
- Freeze the physical definition of pair covariance and information in pixels and radians.
- Freeze the candidate calculations for:
  - log-ratio residual noise and final local curvature;
  - normalized-area-correlation curvature and spatially distinct peak ambiguity;
  - covariance eigenvalue floors/caps and invalid-evidence fallback;
  - information normalization across a recording and, if retained, across lag groups;
  - the robust loss, its data-relative scale, weight floor and convergence rule.
- Declare development, validation and locked source groups before later stages inspect strategy results.
- Freeze accuracy, failure, bound-hit, repair, worst-case and runtime gates. Internal image residual is recorded but is not an accuracy gate.
- Freeze the selector question before strategy results exist: allowed evidence fields, candidate model families, minimum oracle headroom over the best fixed strategy, source-grouped validation gates, confidence fallback and the final untouched source group reserved for Stage 07.
- Create a manifest containing input hashes, source-group assignments, test commands, the current commit identifier and an explicit note if no genuinely fresh rigid locked source is available.

## Out of scope

- No production uncertainty fields or calculations; Stage 02 adds them.
- No weighted normal equations; Stage 03 adds them.
- No graph-consistency reweighting; Stage 04 adds it.
- No production default, selector or public control changes; Stage 08 owns promotion.
- No attempt to remove the separate rigid Automatic-selector fallback.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `docs/confidence-weighted-reconciliation/weighting_protocol.md` | NEW | Freeze formulas, candidate values, source splits, gates and stop conditions before implementation results exist. |
| `src/test/java/logratio/core/ReconciliationBaselineTest.java` | NEW | Pin the present equal-weight translation and rigid graph solutions as the compatibility reference. |
| `library/benchmark/v2/benchmarks/confidence_weighted_reconciliation_v1/protocol_manifest.properties` | NEW | Record immutable run identity, source assignments, hashes and baseline commands beside later artifacts. |

## Implementation sketch

1. Record the current state before editing: `git status --short`, `git rev-parse HEAD`, Java version, Maven version and Python package versions. Put reproducibility values in the protocol manifest, not machine-specific absolute paths.
2. Run the stable rotation and parity checks:

   ```powershell
   mvn -q -Dtest=PairAlignerRecoveryTest,AreaCorrelationRecoveryTest,ReconcilerTest,WarperTest,ThevenazProtocolBenchmarkTest,RegistrationTest,RegistrationGuardTest test
   python -m pytest tests_python/test_core.py tests_python/test_api.py tests_python/test_java_parity.py
   ```

3. In `ReconciliationBaselineTest`, build small graphs from literal `Transform` observations rather than fitted images. Cover translation only and rigid movement, several lags, a reversed observation and an unsupported frame. Store expected `double` bit patterns where the current solver is deterministic. The test must call the existing `Reconciler.multiLag(int, List<Observation>)` entry point.
4. In `weighting_protocol.md`, define covariance as uncertainty of the estimated pair transform in `[dx_pixels, dy_pixels]` or `[dx_pixels, dy_pixels, theta_radians]`. Define information as its symmetric positive-definite inverse. State how covariance transforms when a pair is inverted and when translations are rescaled.
5. Freeze one implementable calculation per estimator, or a small predeclared candidate set if calibration evidence is required. Each entry must include its equation, units, evaluation point, finite/positive-definite checks, eigenvalue floor/cap and fallback. Do not label correlation, `residualAfter` or `validFraction` as uncertainty.
6. For robust graph weighting, freeze the exact residual norm, robust scale estimator, loss family, threshold candidates, lower influence floor, maximum iterations and convergence tolerance. For rigid residuals, convert angle to a declared pixel-equivalent scale using the frame's root-mean-square radius when no covariance supplies the scale.
7. Audit every proposed source by original experiment. Put only source identifiers and hashes in the manifest. Assign all derived views or motion variants from one original acquisition to the same split. Mark previously opened locked sets as spent. If there is no defensible fresh rigid locked group, declare that absence now; Stage 08 must then withhold a rigid-default claim.
8. Freeze strategy gates before Stage 06: equal, uncertainty-only, robust-only and combined are compared on the same pair measurements and trajectories. State the minimum improvement, allowed worst-case regression, failure/repair ceilings and runtime ceiling separately for translation and rigid scopes.
9. Freeze the optional Stage 07 selector before Stage 06 reveals per-recording winners. List every allowed truth-free feature, candidate family, regularisation/threshold grid, minimum oracle headroom, improvement required over the best fixed strategy, allowed regressions and low-confidence fallback. Reserve the final untouched evidence for Stage 07 rather than opening it during Stage 06.

## Exit gate

1. All listed stable rotation, registration and Java/Python parity tests pass before any production weighting change.
2. `ReconciliationBaselineTest` passes repeatedly and pins both translation and rigid equal-weight outputs.
3. `weighting_protocol.md` contains complete formulas, units, caps/floors, robust rules, source splits, fixed-strategy gates and optional-selector gates; it contains no result chosen after seeing validation or locked performance.
4. The manifest identifies every source by original experiment and explicitly labels development, validation, locked, spent or unavailable status.
5. The rigid evidence limitation agrees with `docs/rigid_selector_validation_gap.md`; no correlation candidate is enabled in Automatic mode by this work.
6. No file under `src/main/` changed in this stage.
7. Only the three declared paths differ from the stage-start snapshot, apart from unrelated pre-existing changes.

## Known risks

- The current rotation implementation may still be changing. If any baseline test fails because rotation is being tuned, stop and rerun this stage only after that work is finalized.
- Local curvature is not automatically calibrated covariance. The protocol must distinguish a derivation from an empirical calibration and must not tune on locked outcomes.
- Existing controlled-motion images may have influenced earlier plugin choices. They remain useful development evidence, but they are not automatically a fresh locked test for this feature.
- Repeated correlation patterns can produce a sharp wrong peak. Peak ambiguity and later graph consistency are required even if curvature looks strong.
