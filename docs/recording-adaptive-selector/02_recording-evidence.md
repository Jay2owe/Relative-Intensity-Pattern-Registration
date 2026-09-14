# Freeze recording-specific evidence

## Why this stage exists

The current selector exposes 48 fields, but the production model does not use them to make recording-specific choices. Before new benchmarking or training, this stage proves that every allowed feature is deterministic, truth-free, available at run time and measured the same way in training and production.

## Prerequisites

- `01_post-rotation-protocol_COMPLETED.md`

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/selector_protocol.md`, entire file.
- `docs/recording-adaptive-selector/source_split_manifest.csv`, header and all development rows.
- `src/main/java/logratio/api/AutomaticFilterSelector.java`, lines 115-130 and 239-end: current 17 image features, 10 motion features and measurement implementation.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 48-112, 177-201 and 288-366: the complete 48-field vector, extra information fields, standardisation and names.
- `src/main/java/logratio/core/AutomaticInformationSelector.java`, lines 1-220: sparsity, moving-tail and temporal-outlier evidence.
- `src/main/java/logratio/api/LogRatioRegistration.java`, lines 67-105 and 145-163: production provisional-pass and feature-measurement route.
- `src/test/java/logratio/api/AutomaticFilterFeatureTable.java`, entire file: current production-feature exporter.
- `src/test/java/logratio/api/AutomaticFilterSelectorTest.java`, entire file.
- `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java`, lines 25-95 and 199-215.
- `src/test/java/logratio/core/AutomaticInformationSelectorTest.java`, entire file.

## Scope

- Inventory all 48 current fields by name, formula, units, sampled frames, required provisional settings, expected range and intended selection role.
- Test whether raw-image fields are stable across repeated runs and correctly handle byte, unsigned-short and float inputs.
- Test whether motion fields use the exact provisional cumulative transforms that production will supply.
- Measure feature redundancy and missing/non-finite rates on development inputs without joining candidate outcomes.
- Verify the protocol's expected invariances or sensitivities to global gain, global offset, image size, scale, frame count and bit depth. Do not assume invariance where the protocol did not require it.
- Implement only the feature corrections, additions or removals permitted by the frozen feature families.
- Add an explicit evidence-contract version and a deterministic validity/out-of-distribution result.
- Stop silently replacing invalid evidence with an apparently ordinary mean value. Invalid or unsupported evidence must remain identifiable so selection can fall back later.
- Freeze feature names and order for Stage 03. Write a human-readable audit with the final count and definitions.

## Out of scope

- Do not inspect recipe errors or winners; Stages 03-05 own outcomes and modelling.
- Do not fit thresholds from outcome labels. Evidence-validity thresholds must come from the protocol or truth-free development distributions declared there.
- Do not change candidate recipes; Stage 01 owns the manifest.
- Do not change which recipe Automatic mode selects; Stage 06 owns production integration.
- Do not expose new controls in Fiji or macros; Stage 07 owns those surfaces.
- Do not implement Python features yet; Stage 08 mirrors the frozen Java contract.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/api/AutomaticFilterSelector.java` | MODIFY | Correct and version raw-image/provisional-motion feature definitions approved by the protocol. |
| `src/main/java/logratio/api/AutomaticRegistrationSelector.java` | MODIFY | Freeze the complete evidence vector, validity and out-of-distribution contract. |
| `src/main/java/logratio/core/AutomaticInformationSelector.java` | MODIFY if required | Correct only approved sparsity/change evidence calculations. |
| `src/test/java/logratio/api/AutomaticFilterFeatureTable.java` | MODIFY | Emit the exact production evidence schema with contract metadata and no truth. |
| `src/test/java/logratio/api/AutomaticFilterSelectorTest.java` | MODIFY | Cover image/motion feature definitions, determinism and approved invariances. |
| `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java` | MODIFY | Pin vector order, version, invalid evidence and fallback-ready state. |
| `src/test/java/logratio/core/AutomaticInformationSelectorTest.java` | MODIFY if required | Pin any corrected information features. |
| `docs/recording-adaptive-selector/02_recording_evidence_findings.md` | NEW | Record the audit, final feature table, removals, missingness and cost. |

## Implementation sketch

Keep one immutable evidence object as the production/training seam. The exact fields depend on the frozen protocol, but the contract should convey at least:

```java
final class Evidence {
    String contractVersion;
    double[] imageFeatures;
    double[] provisionalMotionFeatures;
    double[] supportAndChangeFeatures;
    ImageType declaredImageType;
    MotionType declaredMotionType;
    boolean valid;
    String invalidOrOutOfDistributionReason;

    double[] vector(); // exact frozen order, only when valid
}
```

Do not necessarily introduce those exact Java members if the existing immutable class can express the same contract with less change. The load-bearing requirements are versioning, stable order, explicit invalidity and identical production/training measurement.

The exported truth-free table should separate metadata from model inputs:

```text
recording_id,independent_group,feature_contract_version,evidence_valid,evidence_reason,
feature_001,...,feature_N
```

`recording_id` and `independent_group` are join/split metadata and must never be passed to the predictor. No column may contain injected truth, candidate error, oracle recipe, post-hoc winner or final-test label.

Add deterministic fixtures that isolate feature meanings. Examples:

```text
same pixels twice -> identical feature vector
known translated transforms -> exact motion statistics
constant field -> explicit unsupported/low-information evidence
NaN/Inf pixels -> declared invalid or documented finite handling
global gain/offset variants -> behaviour matches selector_protocol.md
```

Record feature-extraction wall time separately from the provisional registration time.

## Exit gate

1. `02_recording_evidence_findings.md` lists every final field with formula, units, source frames, required pilot input and intended role.
2. Feature names, count and order match between `Evidence.vector()`, the exporter and all tests.
3. Repeated measurement of every deterministic fixture is exact.
4. All approved gain, offset, scale, bit-depth and frame-count tests match the frozen protocol.
5. Constant, nearly blank, non-finite and unsupported recordings produce an explicit invalid/out-of-distribution state rather than an ordinary-looking standardized vector.
6. The exporter contains no truth, outcome, oracle or winner column; a test rejects any forbidden name.
7. One source-derived recording cannot cross partitions through feature-export logic.
8. Training and production call the same public evidence calculation rather than separate implementations.
9. `mvn -Dtest=logratio.api.AutomaticFilterSelectorTest,logratio.api.AutomaticRegistrationSelectorTest,logratio.core.AutomaticInformationSelectorTest test` passes.
10. The full feature audit is complete before Stage 03 writes any new outcome matrix.

## Known risks

- Correcting a feature changes the trained-model schema. Preserve the old model until Stage 06 installs a compatible replacement.
- Many correlated fields can make a linear model unstable without adding information. Record redundancy now; do not delete fields based on candidate outcomes later.
- Motion evidence can reflect provisional-fit failure rather than true motion. Include failure/validity evidence and never label the estimates as truth.
- Replacing non-finite values with training means hides unsupported recordings. Explicit fallback is safer even if it reduces coverage.
- Sampling only first, middle and final frames may miss intermittent changes. Change that policy only if Stage 01 permitted the sampling family and runtime budget.
- Image-size and bit-depth effects can become accidental microscope/source identifiers. Grouped validation later does not excuse a poorly normalized feature.
