# Stage 06 - Benchmark the four reconciliation strategies

## Why this stage exists

Information weighting and robust graph weighting are plausible improvements, not assumed improvements. This stage measures equal, uncertainty-only, robust-only and combined reconciliation on identical pair estimates and known translation/rigid trajectories, using the gates and source splits frozen before implementation.

## Prerequisites

- `05_pipeline-diagnostics_COMPLETED.md` exists.
- All four strategies run through the full Java registration pipeline while equal remains the default.
- Java/Python parity passes, but the benchmark decision is based on the frozen Java/Fiji implementation and is not adjusted to make Python agree.
- `weighting_protocol.md` and `protocol_manifest.properties` contain the frozen fixed-strategy and optional-selector rules and have not been edited after validation results were viewed.

## Read first

- `C:/Users/jamie/AGENTS.md` - session and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:51-81` - benchmark, evidence and promotion rules.
- `docs/confidence-weighted-reconciliation/weighting_protocol.md` - frozen configurations, source splits, metrics and numerical gates.
- `library/benchmark/v2/README.md:1-67` and `:116-149` - known-truth controlled motion, natural-motion limitations and source structure.
- `docs/thevenaz_protocol_plan.md:20-43`, `:102-156` and `:187-205` - independent resampling, warping index and transformation-class matching.
- `src/test/java/logratio/ThevenazProtocolBenchmark.java:42-100` - seeded transformations, high-order generator and existing artifact conventions.
- `src/main/java/logratio/core/Registration.java` - complete Stage 05 experimental strategy route and returned diagnostics.
- `src/test/java/logratio/FullSelectorFactorialBenchmark.java` - read the complete controlled-metric and source-balancing helpers before duplicating any benchmark code.

## Scope

- Build a seeded, auditable benchmark that changes reconciliation strategy and nothing else within each comparison.
- Compare all four strategies on the same saved pair measurements, pair plan, preprocessing, estimator, bounds and source pixels.
- Use existing V2 known-translation trajectories and a source-balanced known-rigid multi-frame extension generated with the frozen high-order resampling protocol.
- Cover the declared microscopy categories and both estimator families in every scope for which Stage 01 assigned defensible evidence.
- Separate development, validation and the still-sealed final evidence by original source recording. This stage must not open the final evidence reserved for Stage 07.
- Select any remaining predeclared candidate formula/cap/threshold on development only, write that selection, then prevent later phases from changing it.
- Judge per-frame trajectory error, warping index for rigid movement, failures, bound hits, repaired positions, worst-case recording error and runtime.
- Record natural-motion stability only as supporting evidence, never as accuracy where movement truth is unknown.
- Produce per-pair audit tables linking uncertainty, ambiguity, graph residual and final influence to known pair error.
- Produce one truth-free selector-feature row and one four-arm outcome row per recording so Stage 07 can test predictable headroom without rerunning pair estimation.
- Quantify the unattainable per-recording oracle and its improvement over the best fixed strategy, but do not train or validate a selector here.
- Apply the predeclared gates without changing them after results are opened.

## Out of scope

- No production source, default, recommendation, selector or public control changes; Stage 07 tests the selector and Stage 08 owns promotion.
- No selector training; Stage 07 owns it.
- No retuning on validation results and no opening of the final untouched evidence.
- No claim that a lower image residual proves accuracy.
- No removal of the rigid Automatic log-ratio fallback.
- No comparison against external registration plugins; this experiment isolates reconciliation inside this workflow.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/test/java/logratio/core/ConfidenceWeightingBenchmark.java` | NEW | Generate/load controlled trajectories, reuse pair fits across strategies, score and write auditable results. |
| `src/test/java/logratio/core/ConfidenceWeightingBenchmarkTest.java` | NEW | Unit-test split isolation, metric identities, identical pair inputs and gated full-run entry point. |
| `docs/confidence-weighted-reconciliation/06_strategy_benchmark_findings.md` | NEW | Summarize development/validation results, the best fixed arm and oracle selector headroom without opening final evidence. |
| `library/benchmark/v2/benchmarks/confidence_weighted_reconciliation_v1/` | NEW | Hold run manifest, selected development configuration, trial tables, summaries and `REPORT.md`. |

## Implementation sketch

Use this fixed strategy set and stable identifiers:

```java
enum Arm {
    EQUAL,
    UNCERTAINTY_ONLY,
    ROBUST_ONLY,
    UNCERTAINTY_AND_ROBUST
}
```

1. Build a recording fixture once, run each planned pair estimator once, and retain the resulting `PairResult` list. Feed the same immutable fits to all four reconciliation arms. Assert transform, status, uncertainty and pair order hashes are identical across arms before scoring trajectories.
2. Translation evidence reuses the known paths under `library/benchmark/v2/benchmarks/controlled_motion/` according to the source split frozen in Stage 01. Do not overwrite existing correction folders or summaries; write this experiment only under `confidence_weighted_reconciliation_v1`.
3. For rigid evidence, generate 48-frame paths containing x, y and in-plane angle from one source image per declared source group. Reuse the existing high-order spline generator/warping-index code rather than using an estimator's interpolation model. Apply the same path to every arm and record every transform in `rigid_truth.csv`.
4. Use lags `1, 2, 4, 8, 16` unless Stage 01 froze a different existing recipe. Do not create a special lag plan for a preferred strategy.
5. Treat the independent source recording as the statistical unit. Summarize within recording first, then balance across source groups and image categories. Frames and pair edges are repeated observations, not independent sample size.
6. Run phases separately:
   - `DEVELOPMENT`: evaluate only the predeclared candidate set and write `selected_configuration.properties`.
   - `VALIDATION`: load that file read-only; stop if its hash or protocol hash differs.
   - `FINAL`: do not run it here. Write `FINAL_RESERVED_FOR_STAGE_07.txt` naming the sealed manifest and its hash. Stage 07 opens it only after the fixed and selector policies are frozen.
7. Write at least:
   - `run_manifest.properties` with code/input/protocol hashes and environment;
   - `selected_configuration.properties` with development-only choices;
   - `pair_trials.csv` with pair truth error and all influence evidence;
   - `recording_summary.csv` with known trajectory metrics and failure counts;
   - `strategy_summary.csv` balanced by source and category;
   - `recording_strategy_matrix.csv` with one row per recording and the four known-truth outcomes;
   - `selector_features.csv` with the Stage 01 truth-free feature columns only, keyed to the outcome table by recording ID;
   - `REPORT.md` with every frozen gate shown as threshold, observed value and pass/fail.
8. Calculate the best fixed arm on development and validation under the frozen safety gates. Separately calculate the oracle arm that retrospectively chooses the lowest known trajectory error per recording. Oracle results measure possible selector headroom only; they are not an executable method and cannot be promoted.
9. Record pair-estimation time separately from reconciliation time. Strategy runtime comparison must use reconciliation and full-pipeline totals without charging pair estimation four times.
10. Add harness tests for: rigid warping index reducing exactly to Euclidean translation error at zero angle; source variants never crossing splits; truth columns never entering `selector_features.csv`; pair-fit hashes matching across arms; deterministic repeated output; and the long full run being disabled unless `confidenceWeighting.run=true`.
11. Use a gated Maven entry point so the benchmark command is reproducible:

   ```powershell
   mvn -q -Dtest=logratio.core.ConfidenceWeightingBenchmarkTest -DconfidenceWeighting.run=true test
   ```

## Exit gate

1. Harness unit tests pass before the full run, including exact translation/warping-index identity and source-split isolation.
2. All four arms consume byte-identical serialized pair results within each recording; only reconciliation strategy differs.
3. Every included category/source has the planned recording count or an explicit unavailable row and reason.
4. Development choices are frozen to `selected_configuration.properties` before validation starts; validation code refuses a changed protocol/configuration hash.
5. `REPORT.md` includes median and worst-case known trajectory error, failure rate, bound hits, repairs and runtime for each arm, separated for translation and rigid movement and by estimator/category where declared.
6. Any natural-motion table is labelled stability, not accuracy.
7. `selector_features.csv` contains only the feature list frozen in Stage 01 and no truth, arm error, winner label or post-repair value; its recording keys join one-to-one to `recording_strategy_matrix.csv`.
8. The report names the best safe fixed strategy and the oracle's headroom over it on development and validation. It does not train a selector, promote a strategy or adjust a gate.
9. Final evidence remains unopened and is recorded by manifest/hash for Stage 07; no spent rigid source is relabelled as final evidence.
10. `mvn -q -Dtest=ConfidenceWeightingBenchmarkTest test` passes in its normal short mode after artifacts are written.
11. Production files under `src/main/` are unchanged in this stage.

## Known risks

- Existing V2 material has been examined extensively. Its controlled truth remains valuable, but Stage 01 may rule it development or validation rather than fresh locked evidence.
- A weighting method can improve the median while worsening one recording severely. Worst-case and failure gates must have veto power.
- Reusing pair fits is necessary to isolate reconciliation, but serialization must preserve full matrix precision and flags.
- Generated rigid trajectories can commit the inverse crime if they use the same interpolation model as the estimator. Reuse the independent high-order generator and record its version.
- Correlation and log-ratio uncertainty may calibrate differently. Report results separately as well as pooled; do not let one estimator hide failure in the other.
- The oracle can look impressive by choosing with movement truth that production never has. It is only a ceiling; Stage 07 must beat the best fixed arm using truth-free evidence.
