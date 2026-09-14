# Train and validate the recording-specific model

## Why this stage exists

Oracle headroom is useful only if evidence available on a real user recording can predict some of it. This stage tests the model families frozen in Stage 01 using source-grouped validation, chooses confidence and fallback rules without leakage, and produces either a frozen adaptive-model artifact or a fixed-policy result for integration.

## Prerequisites

- `04_selector-headroom_COMPLETED.md`

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/selector_protocol.md`, especially target, model families, hyperparameter grids, partitions and gates.
- `docs/recording-adaptive-selector/02_recording_evidence_findings.md`, final evidence contract.
- `docs/recording-adaptive-selector/04_selector_headroom_findings.md`, entire file.
- `<artifact_root from selector_protocol.md>/headroom_decision.properties`, entire file.
- `src/test/java/logratio/FullSelectorTraining.java`, lines 43-125, 129-280, 281-535, 592-769 and 784-1015: input join, grouped folds, tuning, pruning, reporting and generated source.
- `src/test/java/logratio/FullSelectorSweepTest.java`, lines 140-end: source withholding and candidate-retention tests.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 48-112 and 204-306: production vector and current prediction/fallback semantics.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`, lines 26-200: generated layout and current runtime-read protection.
- `docs/full_automatic_selector_sweep_results.md`, lines 68-111 and 280-345: previous overfitting failure, inner-held-out threshold fix and image-type guard.

## Scope

- Read the frozen truth-free feature table and outcome table through separate code paths and join only by recording/case ID inside the training runner.
- If Stage 04 returned `FIXED_POLICY`, skip adaptive fitting and emit a versioned fixed-policy model artifact plus the evidence that triggered the stop.
- Otherwise compare only the model families and hyperparameter grids frozen in Stage 01.
- Recompute candidate retention, feature selection, scaling, regularisation and confidence thresholds inside every outer source-group fold.
- Use inner source-group folds for model/hyperparameter/threshold selection; do not choose a threshold on the same rows used to fit coefficients.
- Respect candidate geometry capability and the declared role of image/motion categories.
- Predict the exact target frozen in Stage 01 and convert it to one reproducible recipe/fallback decision.
- Include explicit invalid/out-of-distribution fallback before prediction.
- Compare each family against the strongest non-adaptive policy from Stage 04, not just the current category rule.
- Freeze the winning family and threshold, then evaluate once on the reserved validation partition.
- Generate a run-scoped candidate model source/artifact without overwriting production `AutomaticRegistrationSelectorModel.java`.
- Record all folds, retained candidates, predictions, fallbacks, failures, runtime and safety-gate results.

## Out of scope

- Do not add a new feature or candidate after training begins. Return to Stage 01 with a new protocol version instead.
- Do not train on validation or final evidence.
- Do not use recipe winner, true error, oracle error or candidate residual as a production input.
- Do not hand-edit coefficients after generation.
- Do not install the generated artifact into `src/main`; Stage 06 owns integration.
- Do not alter Fiji or Python behaviour.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/FullSelectorTraining.java` | MODIFY | Consume the new run-scoped matrices, support the frozen families and emit a candidate artifact without installing it. |
| `src/test/java/logratio/FullSelectorSweepTest.java` | MODIFY | Pin grouped nesting, feature/outcome separation, stop-gate and no-install behaviour. |
| `<artifact_root from selector_protocol.md>/training/` | NEW | Store folds, predictions, fitted artifacts, hashes and validation results. |
| `docs/recording-adaptive-selector/05_feature_model_findings.md` | NEW | Record model comparisons, source-group performance, fallbacks, validation and the frozen integration decision. |

## Implementation sketch

Refactor the current training entry point so production installation is never an unavoidable side effect:

```text
FullSelectorTraining
    --protocol <selector_protocol.md>
    --features <truth-free table>
    --outcomes <development outcome table>
    --validation-features <reserved truth-free table>
    --validation-outcomes <reserved outcome table>
    --output <run-scoped training folder>
    --no-install
```

The exact argument syntax may follow the project's Java-runner conventions, but `--no-install` behaviour is mandatory. The current unconditional write to `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` must no longer occur during exploratory training.

Before joining tables, reject forbidden feature columns by name and contract metadata:

```text
truth, error, oracle, winner, outcome, candidate_score, final_result
```

Use nested grouped evaluation:

```text
for each outer held-out independent source group:
    training_groups = all other development groups
    within training_groups:
        prune candidates using training groups only
        choose feature/model hyperparameters on inner held-out groups
        choose confidence threshold on inner held-out predictions
    predict every case in the untouched outer group

freeze family and grids from outer-fold aggregate
fit once on all development groups
evaluate once on reserved validation groups
```

The emitted artifact must include at least:

```text
model_kind and model_version
feature_contract_version and ordered feature names
centres/scales or other fitted preprocessing
candidate identities and eligibility metadata
candidate rigid-validation metadata
coefficients/rules
confidence threshold
out-of-distribution/fallback metadata
training protocol and input hashes
source-group cross-validation summary
validation decision
```

If adaptive validation fails, emit the strongest safe fixed/category policy named by the protocol. Do not keep the development winner under an "experimental" production label unless the protocol explicitly created such a non-default output.

## Exit gate

1. A test proves no truth/outcome column can enter the feature matrix.
2. Every derived case from one original source remains in one outer and one inner group at a time.
3. Candidate pruning, feature processing, model hyperparameters and confidence threshold are recomputed inside folds.
4. `FIXED_POLICY` from Stage 04 causes zero adaptive fitting and emits a reproducible fixed result.
5. Every allowed family is compared against the strongest safe non-adaptive policy using the frozen metrics.
6. Recording and image-type safety regressions, tails, failures, repairs and runtime are reported beside mean accuracy.
7. The reserved validation partition is evaluated once after the model and threshold are frozen.
8. The emitted artifact contains all feature, candidate, threshold, fallback and provenance data needed by Java and Python.
9. Running training with `--no-install` leaves `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` byte-for-byte unchanged.
10. Repeated training with identical inputs and seeds produces identical decisions and artifact hashes.
11. `mvn -Dtest=logratio.FullSelectorSweepTest,logratio.api.AutomaticRegistrationSelectorTest,logratio.api.ModelConstantsAreReadAtRuntimeTest test` passes.
12. `05_feature_model_findings.md` ends with exactly one integration decision: adaptive model ID, fixed policy ID or complete category fallback.

## Known risks

- There are far fewer independent source series than generated recordings. Model complexity must be judged against source groups, not row count.
- A nonlinear family may fit real interactions or simply identify sources. Source-held-out validation and frozen families are essential.
- Choosing a confidence threshold on fitted rows recreates the earlier selector failure. Use inner held-out predictions only.
- Candidate pruning outside folds leaks winner information and exaggerates safety. Recompute it inside every fold.
- Standardising non-finite evidence to a mean can make invalid recordings look safe. The Stage 02 validity/fallback state must run before prediction.
- Validation can fail despite attractive cross-validation. Emit the fallback and preserve the failure; do not retune on validation.
- The training class currently writes production source. A failed refactor could silently install an exploratory model, so test file hashes explicitly.
