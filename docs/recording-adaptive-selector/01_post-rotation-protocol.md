# Freeze the post-rotation selector protocol

## Why this stage exists

The adaptive-selector question cannot be answered against moving rotation code, an undefined candidate set or gates chosen after results are visible. This stage freezes the scientific and engineering contract that Stages 02-09 must follow, including what the selector predicts, which evidence it may use, what counts as useful headroom and when it must fall back.

## Prerequisites

- `docs/rigid-registration/07_selector-retrain-and-validation_COMPLETED.md`, or an equivalent written rotation freeze showing the final rigid estimator capabilities, transform conventions and fallback behaviour.
- No earlier stage in this folder.

If the rigid plan is not complete, this stage may inventory prospective source material but must not freeze candidates, gates or a production baseline.

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `C:\Users\jamie\AGENTS.md`, entire file.
- `docs/rigid-registration/00_overview.md`, entire file.
- `docs/rigid-registration/07_selector-retrain-and-validation.md`, entire file, plus its completed replacement if present.
- `library/rigid_selector_tuning/protocol.md`, entire file: frozen rigid candidates, inputs, metrics and gates.
- `docs/full_automatic_selector_sweep_plan.md`, lines 1-220: original translation selector design and split discipline.
- `docs/full_automatic_selector_sweep_results.md`, lines 50-111, 280-345 and 430-530: fixed-recipe result, trained selectors, oracle headroom and failure of the internal residual as a ranking score.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 20-230: current evidence, result, measurement and selection contracts.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`, lines 26-200: current generated `image_type_rule` model.
- `src/main/java/logratio/api/RegistrationRecipe.java`, lines 219-377: candidate enumeration, application, identifiers and descriptions.
- `src/test/java/logratio/RigidSelectorFactorialBenchmark.java`, lines 46-121, 270-515 and 595-740: current rigid data, candidate and scoring routes.
- `src/test/java/logratio/FullSelectorTraining.java`, lines 43-125 and 281-535: present families, grouped cross-validation, tuning and pruning.

## Scope

- Record the exact rotation build/commit or source-tree digest that defines the baseline.
- Freeze the eligible registration-recipe manifest after rigid validation. Include estimator, support, intensity band, preprocessing, pixel mask and rigid-validation state.
- Freeze whether translation-only and rigid requests use one selector model, separate branches or a shared model with explicit geometry features.
- Choose the production target from the conversational alternatives: predicted absolute known-movement error, or predicted gain over the complete category fallback.
- Freeze the raw-image, provisional-motion and declared-context feature families Stage 02 may audit. Individual feature definitions may be removed or corrected in Stage 02, but no new family may be added after Stage 03 outcomes are opened.
- Decide which settings the neutral provisional pass must hold fixed, including its pair estimator, robust norm, reference strategy, lag set and compute budget.
- Declare the permitted model families and every hyperparameter/threshold grid before inspecting per-recording winners.
- Define development, validation and final untouched source groups. Keep all derivatives of an acquisition together.
- Declare primary accuracy, tail, failure, bound-hit, repair, runtime and per-image-type safety metrics.
- Declare the minimum source-balanced oracle headroom over the strongest safe non-adaptive policy needed to proceed past Stage 04.
- Declare selector promotion gates over the best non-adaptive policy, including low-confidence and out-of-distribution fallback behaviour.
- Name the run identifier and artifact root. They must be new and must not overwrite `full_selector_sweep_v1`, the spent locked/sealed translation sets or a frozen rigid run.
- Record the model/version provenance required in Java, Python, Fiji reports and macros.

## Out of scope

- Do not calculate new features; Stage 02 owns evidence implementation and audit.
- Do not run any candidate recipe or inspect per-recording winners; Stage 03 owns measurement and Stage 04 owns headroom.
- Do not train a selector; Stage 05 owns training.
- Do not change `AutomaticRegistrationSelectorModel.java`; Stage 06 owns generated-model integration.
- Do not change Fiji, macro, batch or Python behaviour; Stages 07-08 own those surfaces.
- Do not open final untouched evidence; Stage 09 owns the one permitted opening.
- Do not change confidence-weighted reconciliation or output interpolation. They are separate post-pair and post-transform decisions.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `docs/recording-adaptive-selector/selector_protocol.md` | NEW | Freeze the complete scientific, data, model, fallback and promotion contract. |
| `docs/recording-adaptive-selector/source_split_manifest.csv` | NEW | Record source-level development, validation and final assignments without outcome values. |
| `docs/recording-adaptive-selector/01_protocol_audit.md` | NEW | Record prerequisite checks, frozen hashes, unavailable material and the decision to proceed or stop. |

## Implementation sketch

Write `selector_protocol.md` before any new outcome is generated. It should contain explicit values or a declared `BLOCKED` status for every field below:

```text
protocol_version=
freeze_date=
rotation_baseline_sha256_or_commit=
candidate_manifest_path=
candidate_manifest_sha256=
selector_target=absolute_error | gain_over_category
translation_rigid_model_structure=
pilot_estimator=
pilot_norm=
pilot_reference=
pilot_lags=
pilot_budget=
feature_contract_version=
allowed_feature_families=
allowed_model_families=
hyperparameter_grids=
category_role=hard_eligibility | model_input | prior_only
motion_type_role=hard_eligibility | model_input | prior_only
low_confidence_fallback=complete_category_recommendation
out_of_distribution_fallback=complete_category_recommendation
headroom_primary_metric=
minimum_oracle_headroom=
promotion_primary_gate=
promotion_tail_gates=
promotion_failure_gates=
promotion_runtime_gate=
run_id=
artifact_root=
final_evidence_opening_rule=once_after_freeze
```

The split manifest must contain metadata only, for example:

```text
source_series_id,independent_group,image_type,partition,source_path,source_sha256,notes
```

It must not contain candidate errors, winners, truth-derived difficulty or other outcome information. Hash the manifest at freeze and record that hash in the protocol.

The strongest non-adaptive comparator must be identified before headroom is inspected. At minimum compare:

```text
complete category recommendation
current shipped Automatic image-type rule
best eligible fixed recipe or predeclared category-specific fixed table
```

Numeric gates are intentionally not supplied by the conversational source. Choose and justify them here, before Stage 03 produces the new outcome matrix. If independent final material is missing, record the gap and stop rather than silently assigning development material to the final partition.

## Exit gate

1. The rotation prerequisite is complete and its exact source/model digest is recorded.
2. `selector_protocol.md` has no unresolved value for candidate scope, pilot design, feature families, target, model families, splits, fallback, headroom, accuracy, tails, failures, runtime, run ID or artifact root.
3. Candidate and source manifests exist, have unique identifiers and have recorded SHA-256 hashes.
4. Every derivative of one independent acquisition is assigned to exactly one partition.
5. No spent locked/sealed source is labelled as fresh validation or final evidence.
6. The category and motion inputs each have one explicit role; no later stage has to infer whether they are hard rules or predictors.
7. The strongest non-adaptive baseline and source-balancing rule are defined.
8. The permitted provisional-pass settings are complete enough for Stage 02 to reproduce them without choosing new settings.
9. The protocol states that truth and winner columns are forbidden from the production feature table.
10. `01_protocol_audit.md` ends in `READY` or an explicit `BLOCKED` result. Do not mark this stage complete on `BLOCKED`.

## Known risks

- Rotation work may still be changing candidate capabilities. Wait for the real freeze; a nominal date is not enough.
- Historical data have been inspected repeatedly. Treat them as motivation and regression material, not fresh confirmation of a new selector.
- A permissive candidate space multiplies runtime and false discoveries. Freeze only settings that are real, distinct and executable.
- A category-dependent pilot can bake the current rule into the evidence. If retained, its role and bias must be explicit.
- Numeric gates chosen without checking achievable measurement noise can be meaningless. Use prior independent error variability, not the unseen new outcomes, to justify them.
- Sparse and dim material may not contain enough independent sources. Missing evidence requires a scoped fallback, not pooled sibling crops presented as independence.
