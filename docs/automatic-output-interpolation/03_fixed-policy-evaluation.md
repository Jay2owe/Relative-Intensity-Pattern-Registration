# Evaluate fixed output-resampling policies

## Why this stage exists

Before training an adaptive selector, the project needs to know which interpolation is safest when the same rule is applied consistently. This stage compares every fixed candidate and freezes the strongest data-role/geometry policy that any later selector must beat.

## Prerequisites

- `02_fidelity-benchmark_COMPLETED.md`

## Read first

- `docs/automatic-output-interpolation/00_overview.md`, entire file.
- `docs/automatic-output-interpolation/resampling_protocol.md`, metrics, source balancing, hard gates and fixed-policy rules.
- `docs/automatic-output-interpolation/02_fidelity_benchmark_findings.md`, entire file.
- The audited development outcome table and trial manifest under the protocol artifact root.
- `src/main/java/logratio/core/Warper.java`, lines 14-205: exact candidate semantics and block-copy shortcut.
- `src/test/java/logratio/core/WarperTest.java`, lines 32-277: bit-exact and candidate-specific controls.
- `src/test/java/logratio/ValidationRun.java`, lines 55-75 and 217-245: blur-confounded temporal metric interpretation.
- `docs/full_automatic_selector_sweep_results.md`, lines 430-530: distinction between best fixed, per-recording oracle and unsafe internal ranking.
- `src/test/java/logratio/FullSelectorSweepSummary.java`, lines 250-437: source scopes, equivalence widths and fixed aggregation pattern.

## Scope

- Choose and record short fixed-policy analyzer/test filenames before editing.
- Aggregate candidate fidelity by independent source group, data role, image type, transform regime, storage type and controlled condition.
- Apply the role-specific hard gates before ranking accuracy or visual objectives.
- Preserve hard rules that do not require statistical selection: labels/masks never blend; exact whole-pixel translations use block copy.
- Compare `NONE`, `BILINEAR`, `BICUBIC` and `FOURIER` as fixed policies for the remaining fractional-intensity scopes.
- Apply the frozen metric ordering or utility definition separately for quantitative and visual intensity.
- Report mean, median, tail, worst case, per-source failures, overshoot/ringing, sharpness, flux/peak effects and runtime as applicable.
- Determine the strongest safe global, data-role-specific or data-role/geometry-specific fixed policy allowed by Stage 01.
- Freeze equivalence tie-breaks in favour of the simpler/faster/more value-preserving arm exactly as the protocol states.
- Write a machine-readable fixed-policy artifact for Stage 04.
- Do not choose per-recording winners or use source/image features to route candidates.

## Out of scope

- Do not calculate oracle per-recording headroom; Stage 04 owns that stop/go calculation.
- Do not train or fit any selector.
- Do not alter metrics, gates or fixed-policy scope after viewing candidate results.
- Do not drop difficult sources or failed arms from paired comparisons.
- Do not open the final untouched partition.
- Do not modify Java/Fiji/Python production behaviour.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/<TODO-fixed-interpolation-analyzer>.java` | NEW | Aggregate fixed candidate outcomes under the frozen role and source rules. |
| `src/test/java/logratio/<TODO-fixed-interpolation-analyzer-test>.java` | NEW | Pin hard gates, source balancing, equivalence and fixed-policy selection. |
| `<artifact_root from resampling_protocol.md>/development/fixed_policy.csv` | NEW | Store candidate summaries and fixed-policy comparisons by scope. |
| `<artifact_root from resampling_protocol.md>/development/fixed_policy.properties` | NEW | Freeze the strongest safe fixed rule and its protocol/input hashes. |
| `docs/automatic-output-interpolation/03_fixed_policy_findings.md` | NEW | Report complete fixed-candidate evidence and the comparator for Stage 04. |

The Java filenames remain `TODO` because the approved overview did not assign canonical class names. Choose them at the start, record them in the findings and do not rename them after analysis.

## Implementation sketch

Apply hard rules before statistical comparison:

```text
if data_role == LABELS_MASKS:
    fixed policy = NONE
    require zero invalid category values
else if all transforms satisfy frozen whole-pixel/no-rotation rule:
    fixed policy = NONE
    require bit-exact block copy
else:
    compare eligible intensity candidates under the role-specific objective
```

For each remaining candidate and scope:

```text
1. summarize all frames/conditions within a source series;
2. give each independent source series equal weight;
3. fail the candidate if any frozen hard gate is breached;
4. rank passing candidates by the frozen primary objective;
5. apply the frozen equivalence/tie-break rule.
```

The fixed-policy table should retain evidence rather than only a winner:

```text
scope,data_role,geometry,candidate,independent_sources,trials,
primary_metric,primary_value,tail_value,worst_value,
flux_gate,peak_gate,sharpness_gate,ringing_gate,label_gate,runtime_gate,
all_gates_pass,equivalent_to_best,selected_fixed
```

The properties file should be reproducible:

```text
policy_kind=GLOBAL | DATA_ROLE | DATA_ROLE_AND_GEOMETRY
labels_masks=NONE
whole_pixel_translation=NONE
quantitative_fractional_translation=
quantitative_rigid=
visual_fractional_translation=
visual_rigid=
protocol_sha256=
outcome_matrix_sha256=
```

Leave unsupported scopes explicit instead of borrowing a winner from another role or image category.

## Exit gate

1. Label and exact whole-pixel hard rules are applied before candidate ranking and pass their exactness gates.
2. Every development source contributes equal weight regardless of frame/variant count.
3. Failed or gate-breaching candidates remain visible and cannot win through a favourable mean.
4. Quantitative and visual objectives are evaluated separately under the frozen metric order.
5. All four serialized candidates are reported for every eligible scope, including runtime and tails.
6. Equivalence and tie-breaks use unchanged Stage 01 values.
7. `fixed_policy.properties` names one policy or explicit unsupported fallback for every declared scope.
8. The fixed artifact records protocol, candidate, source-manifest and outcome hashes.
9. Targeted analyzer tests and `mvn -Dtest=logratio.core.WarperTest test` pass.
10. `03_fixed_policy_findings.md` names the comparator Stage 04 must beat but contains no per-recording oracle or trained selector result.

## Known risks

- A global mean can hide one image category with severe ringing or intensity loss. Per-source and per-scope gates remain binding.
- A role/geometry table with too many cells can become an unvalidated selector in disguise. Use only scopes frozen in Stage 01.
- `NONE` means exact block copy only for integral pure translations; fractional `NONE` is rounded nearest-neighbour and must be reported separately.
- Quantitative metrics may disagree: flux can be preserved while peaks broaden. Follow the frozen ranking rather than choosing retrospectively.
- Fourier runtime and memory may depend strongly on image size. Report source dimensions and full workflow cost.
- A fixed policy selected on development data is not final evidence. It is the comparator for Stage 04 and Stage 07.
