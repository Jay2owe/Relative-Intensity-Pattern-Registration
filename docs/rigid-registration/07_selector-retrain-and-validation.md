# Train and validate rigid Automatic selection across every image type

## Why this stage exists

Stages 01-06 make both estimator families capable of rigid registration, but the current Automatic model was trained on translation-only recordings. This stage creates a separate rigid evidence branch, measures log-ratio and correlation on rotated material from all five image types, and enables correlation only where fresh validation shows it is safe. It is the only stage allowed to remove the brightfield/DIC and fiducial/static fallback.

## Prerequisites

- `01_bounded-logratio-rotation_COMPLETED.md`
- `02_rotation-trajectory-and-warp_COMPLETED.md`
- `03_selector-capability-fallback_COMPLETED.md`
- `04_java-api-and-ui_COMPLETED.md`
- `05_python-parity_COMPLETED.md`
- `06_rigid-area-correlation_COMPLETED.md`

## Read first

- `docs/rigid-registration/00_overview.md`
- `docs/full_automatic_selector_sweep_plan.md` and `docs/full_automatic_selector_sweep_results.md`: current candidate design, split discipline and spent evidence.
- `docs/PLUGIN_MENU_AND_BENCHMARK_V2.md`, lines 5-56 and the “Fair parameter selection” section: automatic-selection and locked-test rules.
- `docs/thevenaz_protocol_plan.md` and `docs/thevenaz_protocol_findings.md`: general warping-index metric and existing rigid evidence.
- `src/test/java/logratio/FullSelectorFactorialBenchmark.java`, lines 1-end: current controlled candidate sweep.
- `src/test/java/logratio/FullSelectorTraining.java`, lines 78-125, 285-535 and 784-990: training, source-series holdout, pruning and model generation.
- `src/test/java/logratio/FullSelectorSweepTest.java`, lines 1-234: training data and retention gates.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`, lines 95-190: translation candidate model that must remain intact.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 191-249: capability-aware selection from Stage 03.
- `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java`, lines 96-292.
- `src/logratio/parameters.py`, lines 164-327, and `tests_python/test_api.py`, lines 96-126: Python Automatic rule and tests.
- `scripts/run_full_selector_sweep_v1.ps1`, lines 1-end: frozen translation runner to copy structurally, not overwrite.
- `README.md`, lines 41-59: current public per-image estimator claim.

## Scope

- Declare a new rigid selector run identifier, artifact root and protocol before generating results.
- Use controlled known rigid movement injected into real microscopy pixels for all five `ImageType` values.
- Include combined translation, rotation and multiplicative gain; distribute other declared conditions consistently rather than tuning a different problem per image type.
- Split by independent source series into development, validation and a fresh locked rigid test set.
- Sweep rigid log-ratio and every relevant rigid area-correlation candidate using the same truths and bounds.
- Use the general per-pixel warping index for rigid error, not translation-only Euclidean error.
- Retain mean/median, 90th percentile, worst case, boundary hits, non-convergence, refused pairs, runtime and retained crop.
- Train a rigid-only selector branch or rigid candidate-validation table without changing translation-only model outputs.
- Require engine capability and rigid validation before a candidate can be selected.
- Freeze the rigid branch after validation, then open the fresh locked rigid set once.
- Enable area correlation for rigid Automatic mode only for image types where the predeclared gate passes.
- Keep explicit log-ratio fallback for any image type/estimator that fails; report the failure plainly.
- Regenerate Java model source through `FullSelectorTraining` and mirror the final rigid rule in Python.
- Extend Java/Python parity to every final Automatic image-type outcome.
- Update README and Python documentation with supported movement models, units, fallback behaviour and measured limitations.

## Out of scope

- Do not reuse the current translation locked sets as new rigid test evidence; they have already influenced algorithm and selector choices.
- Do not change translation-only coefficients, retained candidates or public outcomes while fitting the rigid branch.
- Do not tune after opening the fresh rigid locked set. A failed gate leaves the Stage 03 fallback in place.
- Do not claim affine, scale, shear, axial or non-rigid correction.
- Do not hide a bimodal failure behind a median; worst and failure-rate gates are mandatory.
- Do not create a per-recording oracle that tries every recipe on user data; Automatic decisions may use only evidence available at run time.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/<TODO-rigid-selector-benchmark>.java` | NEW | Add a companion rigid controlled-motion sweep without modifying frozen translation artifacts. |
| `src/test/java/logratio/FullSelectorTraining.java` | MODIFY | Fit and emit a rigid branch/validation metadata while preserving translation output. |
| `src/test/java/logratio/FullSelectorSweepTest.java` | MODIFY | Gate rigid split isolation, candidate retention, failure rules and model generation. |
| `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` | MODIFY | GENERATED: contain the validated rigid branch beside unchanged translation data. |
| `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java` | MODIFY | Test final per-image rigid outcomes and safe fallback on unvalidated candidates. |
| `src/logratio/parameters.py` | MODIFY | Mirror the frozen rigid selector branch without independently refitting it. |
| `tests_python/test_api.py` | MODIFY | Mirror final rigid Automatic outcomes and provenance in Python. |
| `scripts/<TODO-rigid-selector-runner>.ps1` | NEW | Add a new runner and run ID beside, not inside, the frozen `run_full_selector_sweep_v1.ps1` workflow. |
| `docs/<TODO-rigid-selector-findings>.md` | NEW | Record protocol, source splits, gates, complete outcomes and retained fallbacks. |
| `README.md` | MODIFY | Document measured Java/Fiji rigid support, units, interpolation and Automatic behaviour. |
| `README_PYTHON.md` | MODIFY | Document the matching Python behaviour and units. |

The three `TODO` filenames are intentionally unresolved because this plan was derived from conversation and no canonical rigid benchmark name exists. Choose short names before editing and record them in the findings document; do not reuse `full_selector_sweep_v1`. Although this stage lists more than eight paths, the generated model, Python mirror and documentation are mechanical outputs of one selector freeze; do not split their decisions across separate runs.

## Implementation sketch

Add a new movement class to the controlled generator, not just a non-zero column in a translation metric:

```text
truth per frame = Transform(dx, dy, theta)
pair truth = cumulative[from].inverse().then(cumulative[to])
error = mean over the declared region of || truth.apply(x) - estimate.apply(x) ||
```

Use the already tested general warping-index implementation from `ThevenazProtocolBenchmark`. Verify it reduces exactly to Euclidean translation error at zero angle.

The rigid development design must cover:

```text
five image types
independent source series
combined translation and bounded rotation
clean and multiplicative-gain conditions at minimum
identical truth across candidate estimators
recorded seeds, bounds, interpolation and source hashes
```

Before running, write explicit gates for:

```text
no increase in refused/non-converged/bound-hit failures beyond the declared limit
no material image-type regression against rigid log-ratio fallback
mean/median and 90th-percentile improvement or equivalence
worst-case ceiling that catches bimodal wrong-basin failures
no unacceptable runtime increase
```

These numeric limits are proposed but not supplied by the conversational source. Declare them in the new protocol/findings file before opening validation data; do not infer them after seeing results.

Keep translation and rigid selection separate in generated source:

```java
if (!rigidRequested) {
    // Existing frozen candidates and predictions, unchanged.
} else {
    // Rigid-trained candidates/validation metadata only.
}
```

After Stage 06, `supportsRotation()` is true for correlation, but Stage 03's rigid-validation flag remains false until the generator emits a passing candidate. If the Newton correlation gate fails for one image type, retain the log-ratio fallback for that type and state it in the public table.

Mirror only the frozen final rule in Python. Do not independently refit Python coefficients. Extend Java/Python parity so each `ImageType` resolves to the same estimator, fallback flag and explanation in rigid and translation modes.

## Exit gate

The structural checks below are proposed. Numeric accuracy, failure and runtime limits are deliberately not invented here: declare and confirm them in the new protocol before validation data are opened.

1. A dated rigid protocol exists before validation/locked results and records source-series splits, seeds, movement bounds, candidate list, metrics and numeric gates.
2. Development, validation and fresh locked rigid source sets are disjoint by original experiment; a machine-checkable test proves it.
3. Every candidate receives identical rigid truth and conditions for each recording.
4. The general warping metric equals the old Euclidean metric exactly at zero rotation.
5. Training and regeneration leave all translation-only candidate arrays, gains and per-image outcomes unchanged.
6. The rigid selector never emits an estimator whose engine capability or rigid-validation flag is false.
7. Every image type completes rigid Automatic registration: using a validated selected estimator where gates pass, otherwise the explicit rigid log-ratio fallback.
8. The fresh locked rigid set is run once after freeze. No parameter or threshold changes afterward.
9. Mean, median, 90th percentile, worst case, failures, bound hits and runtime are reported per image type and estimator; bimodal failures cannot be hidden.
10. Java and Python resolve every image type identically in translation and rigid modes.
11. `mvn test` and `python -m pytest` pass.
12. README files state the exact supported movement class, public degree units, serialized radian units, interpolation consequence and any remaining per-image fallback.

## Known risks

- Suitable fresh rotated source splits may not exist. If so, stop at the Stage 03 fallback and record the evidence gap; do not spend old locked data twice.
- Rotation error grows with distance from the centre. Use the general warping index over a declared region and never compare it directly to a centre-only angular error.
- The current log-ratio result is bimodal at wide angles. Include failure and worst-case gates even if its median is excellent.
- Newton correlation is sensitive to changing valid support on sparse bead fields. Validate the exact `AREA_CORRELATION_NEWTON` path selected for fiducial/static, not only the grid variant.
- A rigid retrain can accidentally rewrite the translation model because both are generated by one class. Snapshot and compare the translation arrays byte-for-byte before accepting regeneration.
- Benchmark runtime can be substantial. A long run is not permission to reduce independent sources or inspect locked outcomes early.
