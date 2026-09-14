# Test and train automatic interpolation selection

## Why this stage exists

An adaptive selector is justified only when different recordings have meaningfully different safe winners and truth-free evidence can predict those differences. This stage first measures the unattainable oracle ceiling, stops if headroom is insufficient, and otherwise trains a conservative selector that must beat the frozen fixed policy.

## Prerequisites

- `03_fixed-policy-evaluation_COMPLETED.md`

## Read first

- `docs/automatic-output-interpolation/00_overview.md`, entire file.
- `docs/automatic-output-interpolation/resampling_protocol.md`, selector features, model families, headroom threshold, validation and fallback.
- `docs/automatic-output-interpolation/03_fixed_policy_findings.md`, entire file.
- `<artifact_root from resampling_protocol.md>/development/fixed_policy.properties`, entire file.
- The audited truth-free feature and candidate-outcome tables from Stage 02.
- `src/main/java/logratio/core/Warper.java`, lines 96-135: margins and transform-geometry classification support.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 48-112, 204-306 and 352-end: existing evidence/model/fallback pattern to emulate, not modify.
- `src/test/java/logratio/FullSelectorTraining.java`, lines 43-125, 129-280, 281-535 and 592-769: grouped training, inner tuning, candidate pruning and reports.
- `src/test/java/logratio/FullSelectorSweepTest.java`, lines 140-end: source-group and candidate-retention gates.
- `docs/full_automatic_selector_sweep_results.md`, lines 68-111 and 430-530: earlier overfitting failure, oracle meaning and unsafe internal proxy.

## Scope

- Choose and record short selector-training/test filenames before editing.
- Join truth-free features and fidelity outcomes only by trial/recording ID inside the training runner.
- Enforce `LABELS_MASKS -> NONE` and exact whole-pixel translation `-> NONE` as hard rules, not learned predictions.
- Calculate the safe per-recording oracle over remaining eligible intensity candidates using the frozen role objective and hard gates.
- Compare source-balanced oracle performance with the Stage 03 fixed policy and apply the frozen minimum-headroom stop gate.
- If headroom fails, skip model fitting and emit a `FIXED_POLICY` artifact.
- If headroom passes, compare only the feature families, model families and hyperparameter grids frozen in Stage 01.
- Use only runtime-available inputs: declared data role/context, raw source-image evidence and the already-final transform path/geometry.
- Recompute scaling, feature/model choices and confidence threshold inside grouped development folds.
- Use low confidence, invalid evidence and out-of-distribution detection to fall back to the Stage 03 policy.
- Freeze the model/fixed result, then evaluate once on the reserved validation partition without retuning.
- Generate a run-scoped Java/Python-neutral model artifact; do not install it into production source.
- Record headroom, folds, predictions, fallbacks, per-scope gates, runtime and validation decision.

## Out of scope

- Do not use known unwarped truth, candidate fidelity scores, oracle winner or post-warp disagreement as production features.
- Do not learn whether labels may be blended or whether exact whole-pixel translations should interpolate.
- Do not add features/model families after per-recording winners are visible.
- Do not install Java or Python production code; Stages 05-06 own integration.
- Do not open final untouched evidence.
- Do not modify registration transforms or rerun registration per interpolation candidate.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/<TODO-interpolation-selector-training>.java` | NEW | Calculate headroom, grouped predictions, confidence fallback and validation. |
| `src/test/java/logratio/<TODO-interpolation-selector-training-test>.java` | NEW | Pin truth separation, hard rules, grouped folds, stop gate and deterministic output. |
| `<artifact_root from resampling_protocol.md>/selector/` | NEW | Store headroom, folds, predictions, candidate model/fixed artifact and validation. |
| `docs/automatic-output-interpolation/04_selector_findings.md` | NEW | Record headroom, predictability, validation and the single integration decision. |

The Java filenames are intentionally unresolved because no production interpolation selector/trainer exists. Choose them once before implementation and record them in the findings.

## Implementation sketch

Headroom is a ceiling, never a production decision:

```text
safe_candidates(r) = candidates satisfying role, geometry and frozen hard gates
oracle(r) = lowest frozen role objective among safe_candidates(r)
fixed(r) = Stage 03 policy outcome
headroom(r) = fixed(r) - oracle(r)
```

Aggregate by independent source group. If the frozen gate fails on required scopes:

```text
model_kind=FIXED_POLICY
fixed_policy_sha256=<Stage 03 artifact>
training_skipped=true
```

If it passes, use nested grouped training:

```text
outer fold: hold out every derivative of one independent source group
inner folds: choose feature/model hyperparameters and confidence threshold
fit: development groups only
predict: untouched outer group
aggregate: source-balanced against Stage 03 fixed policy
freeze: winning permitted family and threshold
validate once: reserved validation groups
```

Possible evidence fields are only those frozen in the protocol, such as:

```text
declared data role and image/motion context
image dimensions and storage type if approved
sparsity/noise/dynamic-range/edge/frequency/ringing-risk measurements
rotation present, maximum edge displacement
fractional translation distribution and whole-pixel fraction
```

The emitted artifact must include:

```text
model_kind=ADAPTIVE | FIXED_POLICY
model_version
feature_contract_version and ordered names
data-role hard rules
whole-pixel/rotation thresholds
candidate identities
scaling and coefficients/rules
confidence and OOD fallback
fixed policy fallback
protocol/training/validation hashes
```

## Exit gate

1. A test rejects truth, output-error, candidate-score, oracle and winner columns from production features.
2. Label and exact whole-pixel hard rules bypass model fitting and always resolve to `NONE`.
3. Oracle headroom is source-balanced and compared with the exact Stage 03 fixed policy.
4. Failure of the frozen headroom gate produces `FIXED_POLICY` and performs no adaptive fit.
5. If fitting proceeds, all derivatives of one source remain together in outer and inner folds.
6. Feature processing, hyperparameters and confidence thresholds are recomputed inside folds.
7. Every adaptive comparison reports primary fidelity, hard-gate breaches, tails, fallback coverage and complete runtime.
8. Reserved validation is opened once only after the model/threshold is frozen; no retuning follows.
9. The run-scoped artifact contains everything Java and Python need without reading training outcomes.
10. Repeated training with identical inputs/seeds produces identical decisions and non-timing artifacts.
11. Targeted selector tests and `mvn -Dtest=logratio.core.WarperTest test` pass.
12. `04_selector_findings.md` ends with exactly one integration decision: adaptive model ID, fixed policy ID or unsupported/category fallback.

## Known risks

- Oracle headroom can be large but unpredictable. In that case the fixed policy is the correct result.
- Image dimensions or bit depth can identify a source rather than a physical interpolation need. Freeze and audit such features carefully.
- Multiple derived transform paths are not independent recordings. Keep all source derivatives in one fold.
- A selector can prefer smoother outputs if the objective overweights temporal stability. Preserve sharpness and truth-error gates.
- A complex model can memorize the small number of independent sources. Compare only predeclared conservative families.
- Validation failure cannot be repaired by retuning on validation. Emit the fixed fallback and preserve the result.
