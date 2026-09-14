# Validate and promote the supported interpolation policy

## Why this stage exists

Development and reserved validation may still overstate the safety of a fixed rule or selector on genuinely new images and movement. This stage freezes both implementations, opens untouched evidence once, applies the predeclared gates and leaves Automatic output interpolation on only the adaptive, fixed or conservative fallback supported by that evidence.

## Prerequisites

- `06_python-parity_COMPLETED.md`

## Read first

- `docs/automatic-output-interpolation/00_overview.md`, entire file.
- `docs/automatic-output-interpolation/resampling_protocol.md`, final split, promotion order and gates.
- `docs/automatic-output-interpolation/source_split_manifest.csv`, final rows and hashes only before opening.
- `docs/automatic-output-interpolation/03_fixed_policy_findings.md`, frozen fixed comparator.
- `docs/automatic-output-interpolation/04_selector_findings.md`, frozen adaptive/fixed integration decision.
- `docs/automatic-output-interpolation/05_java-integration.md` and `06_python-parity.md`, exit gates and final handoffs.
- The Stage 02 benchmark runner and audit implementation, entire files.
- `src/test/java/logratio/SealedTestReport.java`, entire file: prior one-opening pattern; do not reuse its data or identity.
- `src/test/java/logratio/SealedTest3Report.java`, lines 29-120 and 200-end: frozen gate and paired-report pattern.
- `src/test/java/logratio/SealedTest3GatesTest.java`, entire file.
- `src/test/java/logratio/core/WarperTest.java`, entire file.
- `tests_python/test_java_parity.py`, entire file.

## Scope

- Choose and record new final-report/gate-test filenames before editing; do not reuse a spent registration test identity.
- Verify final-source independence, input hashes, generator, transforms, protocol, candidates and Java/Python model/policy hashes before opening outcomes.
- Snapshot the Stage 03 fixed policy, Stage 04 adaptive candidate if any, and conservative fallback decisions for every declared scope.
- Run the untouched final interpolation benchmark once using identical known transforms across policy arms.
- Compare the frozen Automatic policy with the strongest fixed rule and conservative/manual baseline under all role-specific accuracy, tail, ringing, intensity, label and runtime gates.
- Report by independent source, data role, image type, transform geometry, condition and storage type.
- Apply the frozen promotion order without retuning: adaptive if every required gate passes; otherwise fixed if its gates pass; otherwise conservative/manual fallback.
- Preserve failed candidate artifacts and full results for audit.
- Regenerate/mirror only the final policy status needed for Java and Python to agree.
- Run full Java and Python suites and record commands, versions and hashes.
- Write final findings with exact supported scope, fallback behaviour and evidence gaps.

## Out of scope

- Do not change objectives, features, thresholds, candidates, model family, gates or exclusions after final outcomes are visible.
- Do not rerun final evidence after a failure to test a revised policy. A genuine harness defect requires documented invalidation and fresh untouched evidence.
- Do not infer mixed-stack semantics or relax the label hard rule.
- Do not claim lower temporal variation or smoother appearance proves quantitative fidelity.
- Do not publish, push, deploy or upload an ImageJ update site.
- Do not alter registration transforms, reconciliation or recipe selection.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/<TODO-final-interpolation-report>.java` | NEW | Run the one-time paired final comparison and write complete results. |
| `src/test/java/logratio/<TODO-final-interpolation-gates-test>.java` | NEW | Pin every promotion gate on synthetic report fixtures. |
| `<artifact_root from resampling_protocol.md>/final/` | NEW | Store immutable opening record, manifest audit, outcomes, logs, decisions and hashes. |
| `src/main/java/logratio/api/<output-selector/model file from Stage 05>.java` | MODIFY only if required | Encode final promoted adaptive/fixed/fallback status mechanically. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Pin final per-scope Java outcomes. |
| `src/ripr/<output-selector module from Stage 06>.py` | MODIFY only if required | Mirror final promoted policy. |
| `tests_python/test_api.py` | MODIFY | Pin final Python outcomes and provenance. |
| `docs/automatic-output-interpolation/07_final_validation_findings.md` | NEW | Record one-time opening, all gates, final policy, supported scope and limitations. |

The Java report/test filenames remain `TODO` because the conversational source supplied no canonical final-set runner. Choose them before the opening and never reuse the old locked/sealed registration run identifiers.

## Implementation sketch

Write a freeze/opening record before reading final outcomes:

```text
protocol_sha256=
source_split_manifest_sha256=
final_input_manifest_sha256=
generator_source_sha256=
candidate_implementation_sha256=
fixed_policy_sha256=
adaptive_model_sha256=
java_policy_source_sha256=
python_policy_source_sha256=
all_numeric_gates=
planned_command=
opened=false
```

Set `opened=true` once when execution begins and append logs. Every final trial should produce paired rows for:

```text
manual/conservative baseline required by protocol
strongest frozen fixed policy
frozen Automatic adaptive policy, if one exists
```

Promotion is a gate application, not a new ranking:

```text
if adaptive exists and passes every required final gate:
    final_policy=ADAPTIVE
else if frozen fixed policy passes every required final gate:
    final_policy=FIXED
else:
    final_policy=CONSERVATIVE_FALLBACK
```

For `LABELS_MASKS` and exact whole-pixel translation, the hard `NONE` decisions remain binding regardless of aggregate results.

## Exit gate

1. The freeze record exists before any final outcome is read and every hash matches.
2. Final independent groups are disjoint from development, validation and spent registration/selector sources.
3. Synthetic gate tests fail independently on pixel fidelity, flux/peak, sharpness, ringing/overshoot, label validity, tail/worst case and runtime breaches.
4. Every final trial has complete paired policy arms with identical input and transform hashes.
5. The opening occurs once and the immutable record/logs are preserved.
6. No objective, feature, coefficient, candidate, threshold, gate or exclusion changes after opening.
7. The final decision follows the predeclared adaptive -> fixed -> conservative promotion order exactly.
8. Labels/masks and exact whole-pixel cases remain on the hard `NONE` path.
9. Unsupported or failed scopes are explicit and use the frozen fallback.
10. Java and Python resolve every final fixture to the same serialized interpolation, fallback state, version and reason.
11. `mvn test` passes.
12. `python -m pytest` passes.
13. `07_final_validation_findings.md` records commands, versions, hashes, complete gates, final policy and limitations.

## Known risks

- Fresh rigid and fixed-marker material may be limited. Retain scoped fallback rather than weaken independence.
- A final set can be invalidated by a genuine harness defect, but not by an unfavourable result. Document and acquire new evidence rather than rerunning.
- Overall metrics can hide ringing or intensity loss in one scope. Role/geometry gates remain binding.
- Selector coverage can be low while fallback remains safe. Report both coverage and source counts.
- Runtime must include feature calculation and policy resolution, even though transforms are already known.
- A failed adaptive model can leave Java/Python on stale policy files. Re-run parity and source-hash checks after final generation.
