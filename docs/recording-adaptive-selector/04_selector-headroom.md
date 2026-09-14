# Measure adaptive-selector headroom

## Why this stage exists

Different recipes winning individual recordings does not by itself justify a production selector. This stage measures the maximum source-balanced improvement available from perfect per-recording choice and compares it with the strongest safe non-adaptive policy before any predictive model is fitted.

## Prerequisites

- `03_recipe-outcome-matrix_COMPLETED.md`

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/selector_protocol.md`, especially primary metric, safety filters, source balancing and headroom threshold.
- `docs/recording-adaptive-selector/03_recipe_outcome_matrix_findings.md`, entire file.
- The feature and outcome table headers under the protocol's artifact root; do not inspect final-partition outcomes.
- `src/test/java/logratio/FullSelectorSweepSummary.java`, lines 31-181, 307-437 and 447-589: existing equivalence-aware oracle and headroom reporting.
- `docs/full_automatic_selector_sweep_results.md`, lines 430-530: historical oracle interpretation and the internal-score failure.
- `src/test/java/logratio/RigidSelectorFactorialBenchmark.java`, lines 407-515: rigid metrics and structural audit.
- `src/test/java/logratio/FullSelectorTraining.java`, lines 281-369 and 484-535: current source-held-out evaluation and safety pruning, for definitions only.

## Scope

- Implement a run-scoped analyzer for the Stage 03 tables. Because the conversational source supplied no canonical class name, choose a short Java test-runner filename before editing and record it in this stage's findings.
- Identify the strongest eligible non-adaptive comparator exactly as frozen in Stage 01.
- Apply the protocol's reliability and per-recording safety rules before allowing a recipe into the oracle choice.
- Calculate the lowest primary known-movement error available per recording after equivalent candidates are resolved by the frozen tie-break.
- Balance results by independent source group, not by frame, derived condition or number of variants.
- Report headroom overall and separately by image type, declared motion type, actual transform geometry and controlled condition.
- Report how dispersed winners are across recipes and source groups; one source producing many derived wins is still one independent source.
- Run the frozen stop/go headroom gate without fitting any model.
- Emit a machine-readable result that tells Stage 05 either `ADAPTIVE_ALLOWED` or `FIXED_POLICY`.

## Out of scope

- Do not use the oracle at run time; it sees truth and is an unattainable ceiling.
- Do not inspect the relationship between features and winners or fit a predictor; Stage 05 owns predictability.
- Do not change the headroom threshold, tie-break or safety filters after seeing results.
- Do not exclude hard recordings because all candidates perform poorly. Report them and let the safety rules decide.
- Do not open final untouched evidence.
- Do not modify production selector code or generated coefficients.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/<TODO-headroom-analyzer>.java` | NEW | Analyze the frozen Stage 03 matrix without coupling truth to production features. |
| `src/test/java/logratio/<TODO-headroom-analyzer-test>.java` | NEW | Pin source balancing, safety filtering, tie-breaks and stop/go behaviour on small fixtures. |
| `<artifact_root from selector_protocol.md>/headroom.csv` | NEW | Store per-scope baseline, oracle, improvement and independent-source counts. |
| `<artifact_root from selector_protocol.md>/headroom_decision.properties` | NEW | Give Stage 05 one frozen `ADAPTIVE_ALLOWED` or `FIXED_POLICY` decision. |
| `docs/recording-adaptive-selector/04_selector_headroom_findings.md` | NEW | Explain the attainable ceiling, winning-recipe dispersion and stop/go result. |

The two Java filenames remain `TODO` because no canonical headroom class exists in the source plan. Choose them once at the start of this stage, record them in the findings and do not rename them after results are produced.

## Implementation sketch

For each recording `r`, define the safe eligible set using only rules frozen before outcomes were opened:

```text
eligible(r) = candidates compatible with requested geometry and declared scope
              that satisfy the frozen recording-level reliability rules

oracle_error(r) = minimum primary truth error over eligible(r)
baseline_error(r) = primary truth error of the strongest non-adaptive policy
headroom(r) = baseline_error(r) - oracle_error(r)
```

If several recipes fall inside the frozen equivalence width, apply the predeclared preference order rather than claiming numerical noise as a meaningful win. The preference may include fewer automatic changes, lower runtime or simpler processing only if Stage 01 froze that order.

Aggregate in two steps:

```text
1. summarize all derived cases within each independent source group;
2. give each independent group equal weight in the scope summary.
```

At minimum `headroom.csv` should carry:

```text
scope,independent_groups,recordings,baseline_policy,
baseline_primary_error,oracle_primary_error,absolute_headroom,
relative_headroom,oracle_failure_count,gate_limit,gate_pass
```

The decision file must be simple and immutable:

```text
decision=ADAPTIVE_ALLOWED | FIXED_POLICY
protocol_sha256=
candidate_manifest_sha256=
outcome_matrix_sha256=
primary_metric=
measured_headroom=
required_headroom=
fixed_policy_id=
```

If the overall headroom passes but one image type has no safe headroom, `ADAPTIVE_ALLOWED` may be scoped only if the protocol explicitly allowed per-image eligibility. Otherwise use `FIXED_POLICY`.

## Exit gate

1. Fixture tests prove that duplicating derived recordings within one source group does not change its weight.
2. Fixture tests prove that unsafe, failed and geometry-incompatible candidates cannot become oracle winners.
3. The analyzer reads outcomes and split metadata but never writes outcome-derived values into `selector_features.csv`.
4. Overall and per-scope baseline/oracle values are reported with independent-source counts and failure counts.
5. Winner dispersion is reported by recipe and independent source, not only by recording count.
6. The measured result is compared with the unchanged numeric threshold from `selector_protocol.md`.
7. `headroom_decision.properties` contains exactly one unambiguous decision and the hashes of all inputs.
8. If the result is `FIXED_POLICY`, Stage 05 is instructed to emit that policy without fitting an adaptive model.
9. The new analyzer's targeted tests and `mvn -Dtest=logratio.FullSelectorSweepTest,logratio.RigidSelectorFactorialBenchmarkTest test` pass.
10. Final untouched evidence remains unopened.

## Known risks

- An oracle can look impressive because it is allowed to know truth. It is only a ceiling; Stage 05 may still find the advantage unpredictable.
- Averaging derived motion/condition variants as independent observations exaggerates evidence. Balance by original source.
- A pure minimum rewards unstable numerical differences. Use the frozen equivalence width and tie-break.
- Filtering candidates with rules invented after seeing their errors turns the oracle into tuning. Apply only Stage 01 rules.
- Large headroom concentrated in one difficult source does not support a general selector. Report independent-source dispersion.
- If every candidate fails a recording, do not drop it. Count the failure and preserve the fallback interpretation.
