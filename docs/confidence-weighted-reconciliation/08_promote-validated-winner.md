# Stage 08 - Promote only a validated reconciliation policy

## Why this stage exists

The fixed-strategy benchmark and selector test must be converted into one explicit product decision. A non-equal fixed strategy or automatic reconciliation selector becomes a default only in the scopes where it passes every frozen accuracy, safety, evidence and runtime gate. If neither passes, equal weighting remains the default and the completed work is retained as diagnostics and documented negative evidence.

## Prerequisites

- `07_automatic-reconciliation-selector_COMPLETED.md` exists.
- `REPORT.md` and `selector/SELECTOR_REPORT.md` are complete and their protocol, policy, configuration and input hashes match the frozen files.
- No validation or final result has been used to change a threshold or implementation.
- All pre-promotion Java and Python tests pass with equal weighting still the default.

## Read first

- `C:/Users/jamie/AGENTS.md` - session and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:51-81` - rollback, evidence and parity rules.
- `docs/confidence-weighted-reconciliation/weighting_protocol.md` - frozen gates and eligible scopes.
- `docs/confidence-weighted-reconciliation/06_strategy_benchmark_findings.md` - concise benchmark interpretation.
- `library/benchmark/v2/benchmarks/confidence_weighted_reconciliation_v1/REPORT.md` - authoritative gate table.
- `docs/confidence-weighted-reconciliation/07_automatic_selector_findings.md` - selector headroom, validation, final result and supported scopes.
- `library/benchmark/v2/benchmarks/confidence_weighted_reconciliation_v1/selector/SELECTOR_REPORT.md` - authoritative fixed-versus-selector final gate table.
- `docs/rigid_selector_validation_gap.md:9-45` - separate selector evidence limitation that this decision cannot erase.
- `src/main/java/logratio/api/LogRatioParameters.java:16-220` - Java public parameter, builder and core-option mapping contract.
- `src/main/java/logratio/api/LogRatioRecommendations.java` - complete category recommendation construction.
- `src/main/java/logratio/api/LogRatioResult.java:26-57` - current reproducibility provenance.
- `src/main/java/logratio/LogRatioDialogModel.java` and `src/main/java/logratio/MacroOptionsParser.java` - macro serialization and replay.
- `src/ripr/parameters.py` and `src/ripr/registration.py` - Python public parameter mapping and result provenance.

## Scope

- Verify the benchmark artifact hashes and reproduce its pass/fail table without rerunning or reinterpreting final data.
- Write one decision for each eligible scope: global, translation/rigid, estimator and image category only where the protocol predeclared such a distinction.
- Allow exactly four outcomes:
  - `PROMOTE_GLOBAL`: one strategy passed every required scope and becomes the shared default;
  - `PROMOTE_SCOPED`: a strategy becomes default only for the independently validated scopes, with equal weighting elsewhere;
  - `PROMOTE_SELECTOR`: the post-pair selector beat the best fixed strategy and becomes automatic only in its validated scopes, with its frozen safe fallback elsewhere;
  - `NO_PROMOTION`: equal weighting remains default everywhere.
- If promotion occurs, expose the resolved strategy in Java/Python parameters and provenance so runs can be replayed, while preserving explicit equal mode as compatibility and rollback.
- Keep strategy selection automatic from validated policy. Do not add a main-dialog tuning choice unless Stages 06-07 specifically demonstrated a scientifically useful manual decision.
- Preserve the rigid Automatic estimator fallback independently of reconciliation weighting.
- Require Java/Python parity before a non-equal shared default ships.
- Update the concepts explanation with what weighting does, what diagnostics mean and what it cannot prove.

## Out of scope

- No new tuning, formula, candidate, data split or benchmark rerun.
- No enabling of rigid area correlation in Automatic mode without its separate fresh-data validation.
- No claim that confidence proves biological correspondence.
- No renaming or broad public release work; this stage changes reconciliation behaviour and documentation only.
- No main-dialog control merely because the internal enum exists.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `docs/confidence-weighted-reconciliation/08_promotion_decision.md` | NEW | Record artifact hashes, scope-by-scope gates, fixed/selector decision and limitations. |
| `src/main/java/logratio/api/ReconciliationMode.java` | NEW IF PROMOTED | Represent explicit fixed strategies and validated automatic post-pair selection without overloading the core weighting enum. |
| `src/main/java/logratio/core/Registration.java` | MODIFY IF SELECTOR PROMOTED | Run an equal provisional reconciliation, measure truth-free evidence and apply the selector's chosen final strategy. |
| `src/main/java/logratio/api/LogRatioRegistration.java` | MODIFY IF SELECTOR PROMOTED | Supply declared estimator/movement context and retain the selector decision in the public result. |
| `src/main/java/logratio/api/LogRatioParameters.java` | MODIFY IF PROMOTED | Store an explicit reconciliation mode, builder override and core mapping. |
| `src/main/java/logratio/api/LogRatioRecommendations.java` | MODIFY IF SCOPED PROMOTION | Apply only the benchmark-supported estimator/image/movement policy; retain equal elsewhere. |
| `src/main/java/logratio/api/LogRatioResult.java` | MODIFY IF PROMOTED | Include the actual reconciliation strategy in reproducibility provenance. |
| `src/main/java/logratio/LogRatioDialogModel.java` | MODIFY IF PROMOTED | Record the resolved strategy in macro options without adding an ordinary dialog control. |
| `src/main/java/logratio/MacroOptionsParser.java` | MODIFY IF PROMOTED | Replay an explicit strategy, including equal compatibility mode. |
| `src/ripr/parameters.py` | MODIFY IF PROMOTED | Mirror the explicit public strategy and defaults in Python. |
| `src/ripr/registration.py` | MODIFY IF PROMOTED | Pass and report the resolved strategy in high-level Python runs. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Test the chosen/no-promotion default, explicit equal rollback and provenance. |
| `src/test/java/logratio/MacroOptionsParserTest.java` | MODIFY IF PROMOTED | Test macro round-trip of promoted and equal strategies. |
| `tests_python/test_api.py` | MODIFY | Test the equivalent Python decision and explicit equal mode. |
| `tests_python/test_java_parity.py` | MODIFY | Gate shared-default Java/Python transforms and pair diagnostics. |
| `docs/log_ratio_registration_concepts_explained.md` | MODIFY | Explain the validated weighting behaviour and its limits in plain language. |

## Implementation sketch

1. Recalculate every pass/fail cell in both `REPORT.md` files from the frozen CSV outputs and compare them with the recorded decision tables. Verify code, protocol, selected-configuration, selector-policy and input hashes. If any differs, stop with `NO_PROMOTION_INVALID_ARTIFACT` and document the mismatch.
2. Write `08_promotion_decision.md` before changing defaults. For each predeclared scope, list evidence sufficiency, fixed-strategy gates, selector gates, final result and decision. Lack of fresh rigid final evidence forces equal weighting in that rigid scope even if development results look favorable.
3. If neither a non-equal fixed policy nor the selector passes every required gate, do not modify default-selection source. Record `NO_PROMOTION`, keep equal mode everywhere, run regression tests and complete the stage. This is a valid completed outcome.
4. If promotion is supported, add an explicit `ReconciliationMode` value to `LogRatioParameters` and its builder. Fixed modes map to the Stage 05 core weighting option; `AUTOMATIC` invokes the frozen post-pair selector. Existing callers that explicitly request equal must reproduce the Stage 01 baseline.
5. Set the default from the benchmark decision:
   - global promotion uses one validated non-equal value;
   - scoped promotion uses a small frozen policy table in `LogRatioRecommendations`, keyed only by scopes tested in Stage 06;
   - selector promotion uses `AUTOMATIC` only in the scopes that passed Stage 07 and uses its frozen safe fallback elsewhere;
   - every unsupported, failed or data-limited scope returns equal.
6. If the selector is promoted, the registration engine first performs the cheap equal provisional reconciliation, measures the exact Stage 07 truth-free pair/graph evidence, records the selector decision, then performs the chosen final reconciliation. Pair estimation is not repeated. Do not substitute the existing 48 recipe-selector features: pair-estimator selection and post-pair reconciliation selection remain separate decisions.
7. Add the resolved strategy to `LogRatioResult.provenance()` and Python provenance. Serialize it in macro options so a future version can replay the exact run. Keep the existing main dialog unchanged unless the benchmark report contains the predeclared evidence for a useful manual choice.
8. Mirror the promoted fixed/scoped/selector policy and explicit equal override in Python. Do not promote Java alone: if parity fails, revert the default-selection edits while retaining the implemented experimental modes and record `NO_PROMOTION_PARITY_FAILED`.
9. Update the concepts document in plain language: precise directions can influence the graph more; inconsistent pair routes can be reduced but not silently deleted; confidence is not proof of the correct biological object; and equal mode remains available.
10. Run focused tests, then the full suites:

   ```powershell
   mvn -q -Dtest=LogRatioRegistrationApiTest,MacroOptionsParserTest,RegistrationTest,RegistrationGuardTest,ReconciliationBaselineTest test
   mvn -q test
   python -m pytest tests_python/test_api.py tests_python/test_core.py tests_python/test_java_parity.py
   ```

## Exit gate

1. `08_promotion_decision.md` independently reproduces both frozen reports' gates and names one of the four allowed outcomes.
2. No non-equal default is used in a scope lacking a passing validation/final evidence gate.
3. If `NO_PROMOTION`, Java and Python defaults remain equal and Stage 01 baseline outputs still match exactly.
4. If promoted, default Java and Python runs select the same validated strategy and agree within the established transform, matrix and robust-factor parity tolerances.
5. Explicit equal mode remains callable, macro/API replayable and bit-identical to the compatibility baseline.
6. Automatic rigid estimator selection still follows `docs/rigid_selector_validation_gap.md`; weighting does not enable an unvalidated correlation estimator.
7. Provenance states the requested reconciliation mode, the actual strategy used, selector predicted gain/threshold/fallback when applicable, and the per-pair uncertainty, graph adjustment and caps/floors.
8. No ordinary main-dialog tuning choice was added unless its exact manual-use gate appears as passed in the benchmark report.
9. Focused tests, full `mvn test` and the listed Python suite all pass.
10. The concepts explanation states both the validated benefit and the failure/data scopes where equal weighting was retained.

## Known risks

- A pooled winner may fail one estimator or microscopy category. Promotion must follow the predeclared scope table, not the headline aggregate.
- A new default changes scientific output even when it improves accuracy. Explicit equal replay and provenance are mandatory.
- Java/Python numerical differences in iterative robust weighting can cross a threshold. If parity cannot meet the frozen tolerance without retuning, do not promote a shared default.
- Users may interpret a reported small covariance or factor of one as proof of correct correspondence. Documentation must keep uncertainty, graph agreement and biological truth distinct.
- The rigid Automatic selector and reconciliation weighting solve different decisions. Success here cannot fill the independent-source gap for estimator selection.
