# Freeze the output-resampling contract

## Why this stage exists

Interpolation methods preserve different properties, so there is no defensible benchmark until the intended data roles, fidelity objectives, transform regimes and promotion rules are explicit. This stage freezes those decisions before any new comparison results are generated and prevents the selector from being designed around whichever arm happens to win development data.

## Prerequisites

- The rotation warper, transform convention and candidate interpolation implementations are stable. Prefer `docs/rigid-registration/07_selector-retrain-and-validation_COMPLETED.md` or an equivalent written rotation freeze.
- No earlier stage in this folder.

## Read first

- `docs/automatic-output-interpolation/00_overview.md`, entire file.
- `C:\Users\jamie\AGENTS.md`, entire file.
- `src/main/java/logratio/core/Transform.java`, lines 14-116: rigid transform order, sign, composition and inverse.
- `src/main/java/logratio/core/Warper.java`, lines 14-205: interpolation semantics, valid margins, whole-pixel detection and inverse-warp convention.
- `src/main/java/logratio/StackWarper.java`, lines 19-166: application to every channel/Z plane, cropping, output typing and clamping.
- `src/main/java/logratio/api/LogRatioParameters.java`, lines 60-175 and 250-365: current interpolation field, default, validation and builder.
- `src/main/java/logratio/api/LogRatioRegistration.java`, lines 20-55: final-transform estimation followed by one `StackWarper.apply` call.
- `src/test/java/logratio/core/WarperTest.java`, lines 17-290: present bit-exact, local-kernel, Fourier, margin and nearest-neighbour contracts.
- `src/test/java/logratio/ValidationRun.java`, lines 55-75 and 200-245: demonstrated interpolation blur confounding temporal variation.
- `src/test/java/logratio/ThevenazProtocolBenchmark.java`, lines 269-285 and 433-505: general warping metric and independent degree-7 B-spline generator.
- `src/ripr/types.py`, lines 190-205: Python interpolation names.
- `src/ripr/core.py`, lines 1362-1545: Python valid-margin and warp implementations.

## Scope

- Freeze the three data-role definitions and their user-facing meaning: quantitative intensity, visual intensity and labels/masks.
- Decide how an unspecified or mixed-semantics stack is represented and how Automatic output interpolation refuses or falls back.
- Freeze whether output-interpolation selection has a separate manual/automatic mode and confirm that existing API calls/macros remain manual with `NONE` unless they explicitly request the new Automatic behaviour.
- Preserve the serialized candidate names `NONE`, `BILINEAR`, `BICUBIC` and `FOURIER`; freeze clearer display labels without changing their stored values.
- Freeze the exact transform regimes: identity, whole-pixel translation, fractional translation, rotation and combined rigid motion.
- Define the tolerance for “effectively whole-pixel translation” and any negligible-rotation threshold from a bounded spatial error.
- Freeze the independent input generator, source recordings, analytic fixtures, bit depths, transform paths, noise/intensity conditions and source-group partitions.
- Define one common evaluation region per trial that is safe for every candidate.
- Define the quantitative-intensity, visual-intensity and label fidelity metrics, including which are hard gates and how multiple metrics are ranked or combined.
- Freeze pre-clamp and post-clamp evaluation, border-fill handling and crop policy.
- Freeze the truth-free feature families permitted for Stage 04: declared role/context, source-image structure and final-transform geometry only.
- Freeze candidate selector families, oracle-headroom threshold, validation rules, low-confidence/out-of-distribution fallback and final promotion gates.
- Freeze complete-workflow runtime reporting and provenance fields.
- Assign new run identifiers and artifact roots that do not overwrite existing registration or rigid-selector evidence.

## Out of scope

- Do not implement the benchmark; Stage 02 owns the harness.
- Do not compare interpolation candidates or inspect winners; Stage 03 owns fixed-policy evaluation.
- Do not calculate headroom or train a selector; Stage 04 owns that decision.
- Do not modify Java, Fiji, macro or Python production behaviour; Stages 05-06 own integration.
- Do not open final untouched evidence; Stage 07 owns the one final opening.
- Do not change registration estimation, reconciliation, transform repair or candidate interpolation mathematics.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `docs/automatic-output-interpolation/resampling_protocol.md` | NEW | Freeze roles, modes, candidates, transforms, metrics, features, splits, fallbacks and gates. |
| `docs/automatic-output-interpolation/source_split_manifest.csv` | NEW | Record independent development, validation and final source assignments without outcomes. |
| `docs/automatic-output-interpolation/01_contract_audit.md` | NEW | Record rotation prerequisites, implementation hashes, available material and readiness. |

## Implementation sketch

Write `resampling_protocol.md` before generating a new outcome. It must resolve at least:

```text
protocol_version=
freeze_date=
rotation_warper_sha256_or_commit=
transform_convention=
manual_mode_compatibility=
automatic_mode_name=
data_role_values=QUANTITATIVE_INTENSITY,VISUAL_INTENSITY,LABELS_MASKS
unspecified_role_policy=
mixed_semantics_policy=
candidate_names=NONE,BILINEAR,BICUBIC,FOURIER
candidate_display_labels=
whole_pixel_tolerance_px=
negligible_rotation_tolerance_rad=
common_valid_region_rule=
independent_generator=
input_bit_depths=
transform_regimes=
quantitative_primary_metric=
quantitative_hard_gates=
visual_primary_metric=
visual_hard_gates=
label_hard_gates=
pre_clamp_metrics=
post_clamp_metrics=
allowed_selector_features=
allowed_selector_families=
minimum_oracle_headroom=
best_fixed_comparator_rule=
low_confidence_fallback=
out_of_distribution_fallback=
promotion_gates=
run_id=
artifact_root=
```

Define “effectively whole pixel” from maximum allowed placement error, not convenience. For each transform `t`:

```text
fractional_error = hypot(t.dx - round(t.dx), t.dy - round(t.dy))
rotation_edge_error = abs(t.theta) * half_diagonal
```

The hard block-copy rule may apply only when every transform satisfies the frozen translation tolerance and the rotation contribution satisfies the frozen zero/negligible rule. Preserve exact `theta == 0` behaviour in the warper even if selector classification uses a nonzero tolerance.

The source manifest must contain no interpolation outcome:

```text
source_series_id,independent_group,image_type,partition,source_path,source_sha256,data_roles,notes
```

Numeric gates are deliberately not supplied by the conversational source. Justify and freeze them here using existing measurement requirements and prior independent variability, not the new candidate outcomes.

## Exit gate

1. Rotation and warper source/model hashes are recorded and stable.
2. Every data role, unspecified/mixed case and manual/automatic mode has one unambiguous behaviour.
3. Existing manual API/macro calls retain their serialized interpolation and default behaviour.
4. Candidate names and display labels are frozen without a serialization migration.
5. Whole-pixel and rotation thresholds are tied to explicit maximum spatial errors.
6. Each data role has a primary objective, hard fidelity gates and runtime rule.
7. The common valid-region, fill, crop, pre-clamp and post-clamp rules are complete.
8. Development, validation and final partitions are disjoint by independent source group and hashed.
9. Allowed selector inputs, families, headroom threshold, confidence fallback and promotion order are frozen.
10. The protocol explicitly forbids remaining registration disagreement and temporal smoothing alone as selection targets.
11. `01_contract_audit.md` ends in `READY` or an explicit `BLOCKED`; do not mark the stage complete on `BLOCKED`.

## Known risks

- “Quantitative fidelity” has several competing meanings. If the metric ranking is vague, Stage 03 will choose a winner by accident.
- Visual quality is subjective unless the protocol defines a reproducible proxy or blinded rating procedure.
- A mixed channel stack cannot safely receive one learned policy when masks and intensities coexist. Refusal is preferable to silent blending.
- Treating a tiny nonzero angle as exactly zero can create large edge displacement on large frames. Define the threshold from the half-diagonal.
- Existing final/locked registration data may already be spent. Do not relabel it as fresh interpolation evidence.
- Fourier's theoretical band-limited advantage may not survive hard edges, fill boundaries or runtime limits. Keep all claims empirical.
