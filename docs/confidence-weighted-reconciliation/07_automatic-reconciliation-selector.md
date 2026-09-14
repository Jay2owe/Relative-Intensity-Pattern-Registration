# Stage 07 - Test an automatic reconciliation selector

## Why this stage exists

Different recordings may favour different reconciliation strategies, but variation alone does not justify another automatic model. This stage first measures whether choosing per recording could materially beat the best safe fixed strategy, then trains a separate post-pair selector only if that headroom is both large enough and predictable from evidence available without movement truth.

## Prerequisites

- `06_strategy-benchmark_COMPLETED.md` exists.
- `recording_strategy_matrix.csv`, `selector_features.csv` and the Stage 06 report pass their integrity gates.
- The final source group reserved in Stage 01 remains unopened and its manifest hash matches `FINAL_RESERVED_FOR_STAGE_07.txt`.
- The allowed selector features, candidate model families, headroom threshold, validation gates, confidence fallback and final-test rules were frozen in `weighting_protocol.md` before Stage 06 results existed.

## Read first

- `C:/Users/jamie/AGENTS.md` - session and workspace rules.
- `docs/confidence-weighted-reconciliation/00_overview.md:15-35` and `:51-86` - post-pair selector architecture and evidence rules.
- `docs/confidence-weighted-reconciliation/weighting_protocol.md` - frozen selector features, families, thresholds, source splits and stop conditions.
- `docs/confidence-weighted-reconciliation/06_strategy_benchmark_findings.md` - best fixed strategy and oracle headroom on development/validation.
- `library/benchmark/v2/benchmarks/confidence_weighted_reconciliation_v1/REPORT.md` - authoritative fixed-arm gate table and artifact hashes.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java:20-170` - existing recipe-selector evidence/result/fallback pattern; reconciliation selection must remain a separate decision.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java:14-63` and `:95-160` - generated-model provenance and the runtime-read guard against stale inlined constants.
- `src/test/java/logratio/FullSelectorTraining.java:35-125` - source-grouped cross-validation, confidence thresholding and generated-model workflow to reuse where applicable.
- `src/main/java/logratio/core/Registration.java` - complete Stage 05 pair diagnostics and experimental reconciliation strategies.
- `src/test/java/logratio/core/ConfidenceWeightingBenchmark.java` - Stage 06 recording fixtures, saved pair inputs and known-trajectory scoring helpers.

## Scope

- Verify selector feature/outcome separation and calculate the frozen oracle-headroom gate over the best safe fixed strategy.
- Stop with a documented `FIXED_POLICY` result if oracle headroom is too small; do not train a model with no useful ceiling.
- If headroom passes, train only the candidate families and parameter grids frozen in Stage 01, using development data and original-source-grouped cross-validation.
- Predict improvement for each eligible strategy relative to the best safe fixed fallback, not relative to an artificially weak baseline.
- Use only truth-free evidence available after pair estimation and an equal provisional reconciliation.
- Require a predicted improvement to clear the frozen confidence threshold; uncertainty falls back to the best validated fixed strategy, or equal if no non-equal fixed strategy passed.
- Compare the selector with equal and the best fixed strategy on validation using known trajectory accuracy, failures, bounds, repairs, worst-case recording error and total runtime.
- Freeze the selected family/coefficients/threshold or fixed-only decision, then open the final evidence once and evaluate the frozen policies without retuning.
- Keep this selector separate from the existing 48-feature preprocessing/estimator selector. The first chooses how pairs are measured; this selector acts after those pair measurements exist.
- Produce an inactive Java selector/model and complete decision diagnostics for Stage 08; do not change production defaults here.

## Out of scope

- No new image preprocessing, pair estimator, uncertainty formula, robust loss or graph strategy.
- No use of movement truth, candidate trajectory error, corrected-image residual or post-repair values as selector inputs.
- No modification of `AutomaticRegistrationSelectorModel.java` or its 48-feature recipe-selection coefficients.
- No public setting, main-dialog choice, macro default or production activation; Stage 08 owns promotion and integration.
- No Python model trained independently. Stage 08 mechanically mirrors a validated frozen Java policy and verifies parity.
- No rigid selector claim for a scope without the independent final evidence required by `weighting_protocol.md`.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/logratio/api/AutomaticReconciliationSelector.java` | NEW | Define truth-free post-pair evidence, confidence fallback, strategy decision and provenance. |
| `src/main/java/logratio/api/AutomaticReconciliationSelectorModel.java` | NEW | Hold generated fixed-only or validated selector coefficients with runtime-read guards. |
| `src/test/java/logratio/AutomaticReconciliationSelectorTraining.java` | NEW | Measure headroom, source-group cross-validate frozen families, generate the model and run final evaluation once. |
| `src/test/java/logratio/api/AutomaticReconciliationSelectorTest.java` | NEW | Test feature order, fallback, generated-model integrity, determinism and prohibited-feature isolation. |
| `src/test/java/logratio/core/ConfidenceWeightingBenchmark.java` | MODIFY | Expose the Stage 06 fixture/pair/scoring helpers needed to evaluate the frozen selector on final evidence without duplicating the protocol. |
| `docs/confidence-weighted-reconciliation/07_automatic_selector_findings.md` | NEW | Record oracle headroom, cross-validation, final results, decision and unsupported scopes. |
| `library/benchmark/v2/benchmarks/confidence_weighted_reconciliation_v1/selector/` | NEW | Hold selector manifests, folds, decisions, coefficients, final results and `SELECTOR_REPORT.md`. |

## Implementation sketch

Keep the selector contract distinct from the existing recipe selector:

```java
public final class AutomaticReconciliationSelector {
    public static final String[] FEATURE_NAMES = featureNames();

    public static final class Evidence {
        public final PairEstimator.Kind estimator;
        public final boolean rigid;
        public final double[] pairAndGraphFeatures;
        public double[] vector();
    }

    public static final class Result {
        public final Reconciler.Weighting weighting;
        public final boolean fallback;
        public final double predictedGain;
        public final double confidenceThreshold;
        public final double[] candidateGains;
        public final Evidence evidence;
        public final String explanation;
    }

    public static Evidence measure(
            List<Registration.PairResult> pairs,
            Reconciler.Solution equalProvisional,
            PairEstimator.Kind estimator,
            boolean rigid);

    public static Result select(
            Evidence evidence,
            Reconciler.Weighting safeFixedFallback);
}
```

The exact feature vector is the list frozen in Stage 01. It may include declared estimator/rotation context, usable/failed/bound-hit pair fractions, covariance eigenvalue/anisotropy summaries, unavailable/capped/floored uncertainty fractions, peak-ambiguity summaries, support/connectivity and equal-provisional graph/cycle disagreement by lag. It must not include known movement, arm accuracy, winner labels, corrected output, candidate final residuals or post-repair values.

1. Join `selector_features.csv` to `recording_strategy_matrix.csv` only by recording ID inside the training runner. Assert feature-column names exactly equal the frozen list and reject any truth/outcome column on the feature side.
2. Calculate the per-recording oracle by choosing the lowest known-trajectory error among arms that meet recording-level safety rules. Compare its source-balanced result with the best fixed arm. If the frozen minimum headroom is not met on both development and validation, emit a generated model with `MODEL_KIND = "fixed_only"`, name the safe fixed fallback, skip fitting and continue to the final fixed-policy evaluation.
3. If headroom passes, train only the frozen families, for example an interpretable context rule and a regularised one-versus-rest predicted-gain model if those were predeclared. All hyperparameters and confidence thresholds are chosen inside development/source-grouped folds. Every derived motion variant from one acquisition remains in one fold.
4. Predict gain over the best fixed fallback for `UNCERTAINTY`, `ROBUST` and `COMBINED`. Select an arm only when its predicted gain exceeds the frozen confidence threshold and the arm is validated for that estimator/movement scope. Otherwise return the fixed fallback with `fallback = true`.
5. Compare selector and fixed policies on validation at the independent recording level. The selector must pass every frozen accuracy, worst-case, failure, repair and runtime gate and must materially beat the fixed policy; beating equal alone is insufficient.
6. Generate `AutomaticReconciliationSelectorModel.java` from the winning family or fixed-only result. Include feature count/order hash, model kind, training provenance, safe fallback, eligible scopes, confidence threshold and runtime-read wrapper methods so Java callers cannot retain stale inlined constants.
7. Freeze the generated source hash and write `frozen_selector_policy.properties`. Only then load the final manifest and run equal, safe fixed and selector policies once on the untouched final sources. The selector receives only truth-free evidence; scoring occurs after its decision is logged.
8. If final evidence is unavailable for a declared scope, mark that scope unsupported and force equal there. Do not pool a validated translation scope with an unvalidated rigid scope.
9. Write at least `headroom.csv`, `cross_validation_folds.csv`, `validation_decisions.csv`, `frozen_selector_policy.properties`, `final_decisions.csv`, `final_results.csv` and `SELECTOR_REPORT.md`. Every decision row records evidence hash, predicted gains, threshold, chosen strategy and fallback state before its truth score.
10. Keep the long run behind `-DautomaticReconciliationSelector.run=true`; ordinary unit tests load only small fixtures and the frozen model.

## Exit gate

1. Feature/outcome isolation tests reject truth, arm error, winner, corrected-image and post-repair columns as selector evidence.
2. The oracle-headroom calculation is source-balanced and compares against the best safe fixed strategy. Failure of the frozen headroom gate produces `fixed_only` without fitting a selector.
3. Every trained fold leaves all derivatives of one original acquisition together and selects hyperparameters/thresholds without its held-out source.
4. A learned selector is eligible only if it beats the best fixed strategy and passes every frozen validation safety/runtime gate; otherwise the generated policy is fixed-only.
5. Low-confidence, unsupported-estimator, unsupported-rigid and invalid-evidence cases deterministically return the declared safe fallback.
6. The generated model records its feature-order hash and uses runtime-read initializers; regenerating from identical artifacts is byte-identical.
7. Final evidence is opened only after the policy hash is frozen. `final_decisions.csv` records each choice before `final_results.csv` supplies truth scores.
8. `SELECTOR_REPORT.md` reports equal, best fixed and selector/fixed-policy results by translation/rigid, estimator and declared category; unsupported scopes are not pooled into a promotion claim.
9. `mvn -q -Dtest=AutomaticReconciliationSelectorTest,ConfidenceWeightingBenchmarkTest test` passes.
10. The gated full command completes when final evidence exists:

    ```powershell
    mvn -q -Dtest=AutomaticReconciliationSelectorTest -DautomaticReconciliationSelector.run=true test
    ```

11. No production default, current 48-feature selector model, dialog, macro or recommendation changed.

## Known risks

- Oracle headroom uses movement truth and can greatly overstate what truth-free evidence can predict. It is a stop/go ceiling, never a production strategy.
- A selector can overfit recording derivatives if folds split frames or motion variants rather than original acquisitions. Source grouping is mandatory.
- Pair/graph evidence is available only after pair fitting. Trying to merge this into the existing pre-pair recipe selector would mix two decision times and complicate auditing.
- Running several reconciliation solves is cheap relative to pair estimation, but feature extraction, equal provisional solving and selection still add runtime that must be charged to the selector arm.
- If the final set is missing or already spent, a validated development model is not permission to promote. Record the gap and keep the safe fallback.
- A generated model may be valid for translation but not rigid motion, or for one estimator only. Eligibility flags must enforce those boundaries at runtime.
