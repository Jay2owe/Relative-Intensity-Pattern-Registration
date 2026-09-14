# Validate once and promote the supported policy

## Why this stage exists

Development and reserved validation can still produce a selector that does not generalize to genuinely untouched source material. This stage freezes the candidate implementation, opens the final source-separated evidence once, applies the predeclared gates and leaves production Automatic mode on exactly the adaptive, fixed or category policy that the evidence supports.

## Prerequisites

- `07_fiji-api-surfaces_COMPLETED.md`
- `08_python-parity_COMPLETED.md`

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/selector_protocol.md`, especially final-opening and promotion gates.
- `docs/recording-adaptive-selector/source_split_manifest.csv`, final rows and hashes only before execution.
- `docs/recording-adaptive-selector/05_feature_model_findings.md`, frozen candidate/fallback and validation result.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`, entire file.
- `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java`, entire file.
- `src/test/java/logratio/SealedTestReport.java`, entire file: prior one-opening report pattern; do not reuse its data.
- `src/test/java/logratio/SealedTest3Report.java`, lines 29-120 and 200-end: frozen-gate and paired-report pattern; do not alter or reopen its set.
- `src/test/java/logratio/SealedTest3GatesTest.java`, entire file: gate-fixture pattern.
- `library/rigid_selector_tuning/protocol.md`, held-out evidence and rigid metric rules.
- `tests_python/test_api.py`, current Automatic parity tests.

## Scope

- Choose and record new Java report/gate-test filenames before editing. Do not reuse a spent sealed-test runner or identifier.
- Verify the final manifest, source hashes, independent groups, candidate model hash, feature contract, Java/Python parity and all numeric gates before opening outcome files.
- Snapshot the candidate model, complete category fallback and strongest fixed comparator predictions before the final run.
- Run the final evidence exactly once through the same registration/evidence path users receive.
- Compare the frozen adaptive candidate with the strongest safe non-adaptive policy using the source-balanced primary, tail, failure, repair, bound-hit and runtime gates.
- Report every image type, declared motion type, actual transform geometry and controlled condition; do not hide a scoped failure in an overall mean.
- Apply the frozen decision rule without retuning:
  - promote the adaptive model if every required gate passes;
  - otherwise promote the predeclared fixed policy if its gates pass;
  - otherwise retain the complete category recommendation.
- Preserve the failed candidate artifact and full report for audit.
- Regenerate/mirror only the final policy metadata needed for Java and Python to agree.
- Run the complete Java and Python suites and record exact versions, commands and hashes.
- Write a final findings document stating supported scope and unresolved evidence gaps without broader claims.

## Out of scope

- Do not change features, coefficients, candidate recipes, thresholds, exclusions, gates or model family after final outcomes are visible.
- Do not rerun the final set after a failure to see whether a code or parameter adjustment helps. A genuine harness defect requires a documented invalidation and a new untouched set.
- Do not pool unsupported image types into a passing global average.
- Do not claim natural-motion residual improvement as known movement accuracy.
- Do not publish, push, deploy or upload an ImageJ update site; those require a separate explicit request and release workflow.
- Do not change reconciliation or output-interpolation selectors.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/<TODO-final-selector-report>.java` | NEW | Run the one-time final comparison and write complete paired results. |
| `src/test/java/logratio/<TODO-final-selector-gates-test>.java` | NEW | Pin every predeclared promotion gate on synthetic report fixtures. |
| `<artifact_root from selector_protocol.md>/final/` | NEW | Store immutable final manifest audit, decisions, outcomes, report, logs and hashes. |
| `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` | MODIFY, GENERATED only if required | Encode the final promoted adaptive/fixed/category policy and status. |
| `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java` | MODIFY | Pin the final per-scope production outcomes and fallback. |
| `src/ripr/<selector-module chosen in Stage 08>.py` | MODIFY only if required | Mirror the final promoted policy mechanically. |
| `tests_python/test_api.py` | MODIFY | Pin final Java/Python Automatic outcomes. |
| `docs/recording-adaptive-selector/09_final_validation_findings.md` | NEW | Record the one-time opening, gates, decision, supported scope and limitations. |

The two Java filenames are intentionally `TODO` because the conversational source supplied no canonical final-set runner name. Choose them before the opening, record them in the protocol audit and never reuse `LockedTestReport`, `SealedTestReport` or `SealedTest3Report` as the new run identity.

## Implementation sketch

Before the final run, write a freeze record containing:

```text
protocol_sha256
source_split_manifest_sha256
final_input_manifest_sha256
feature_contract_version
candidate_manifest_sha256
java_model_source_sha256
python_model_source_sha256
candidate_model_version
baseline_policy_id
fixed_policy_id
all numeric gates
planned command
opened=false
```

The report runner then changes `opened=true` once, records the time and appends rather than overwrites the outcome log. Every final case should produce paired rows for:

```text
complete category recommendation
strongest predeclared fixed policy
frozen recording-adaptive candidate
```

Use source-balanced paired comparisons. At minimum report:

```text
mean/median primary warping error
90th percentile and worst error
angular and centre-translation errors where applicable
failed/refused/non-converged/bound-hit pairs
repaired/unsupported frames
per-recording safety breaches
selector coverage and fallback rate
runtime including provisional pass and feature extraction
results per image type and geometry scope
```

Promotion is a pure gate application:

```text
if adaptive passes every required gate:
    final_policy=ADAPTIVE
else if fixed policy passes its frozen gates:
    final_policy=FIXED
else:
    final_policy=CATEGORY_RECOMMENDATION
```

Do not choose whichever arm looks best after opening. The fallback order and gates come from Stage 01.

## Exit gate

1. The freeze record exists before any final outcome is read and all hashes match the frozen artifacts.
2. Final sources are disjoint from development, validation and every spent locked/sealed source by independent group.
3. Gate-fixture tests fail on mean, tail, per-image regression, failure, repair, bound-hit and runtime breaches independently.
4. The final report contains every planned case and paired policy arm with no silent drops.
5. The opening occurs once; logs and the `opened=true` record are preserved immutably.
6. No feature, coefficient, candidate, threshold, gate or exclusion changes after opening.
7. The final policy follows the predeclared adaptive -> fixed -> category fallback rule exactly.
8. Failed or unsupported scopes remain explicit fallbacks and are named in the findings.
9. Java and Python resolve every final fixture to the same recipe, fallback state, model version and reason.
10. `mvn test` passes.
11. `python -m pytest` passes.
12. `09_final_validation_findings.md` records commands, versions, hashes, all gates, the final policy, supported scope and limitations.

## Known risks

- Final data may be insufficiently independent, especially for fixed-marker or sparse low-light imaging. Stop and retain fallback rather than weaken independence.
- A harness defect discovered after opening can invalidate the set. Document the defect, preserve all artifacts and acquire a new set; do not quietly rerun.
- Overall accuracy can pass while one image type regresses. Per-scope gates remain binding.
- Low selector coverage is not necessarily failure if the fallback is safe, but any coverage claim must report the denominator and independent sources.
- Runtime must include the pilot and feature calculations. Comparing only final registration time would understate user cost.
- Installing a fallback after a failed candidate can leave Python or tests on stale coefficients. Re-run full parity and hash checks after the final generation.
