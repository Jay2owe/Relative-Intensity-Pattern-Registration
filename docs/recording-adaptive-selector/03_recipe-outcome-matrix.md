# Build the recipe outcome matrix

## Why this stage exists

The selector can learn only from a fair table in which every eligible recipe sees the same recording, movement truth and scoring rules. This stage produces that table and a separately stored truth-free evidence table, without training a model or choosing a winner.

## Prerequisites

- `01_post-rotation-protocol_COMPLETED.md`
- `02_recording-evidence_COMPLETED.md`

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/selector_protocol.md`, entire file, especially run ID, artifact root, candidates, splits and metrics.
- `docs/recording-adaptive-selector/02_recording_evidence_findings.md`, entire file.
- `library/rigid_selector_tuning/protocol.md`, entire file.
- `src/test/java/logratio/RigidSelectorFactorialBenchmark.java`, lines 46-121, 124-215, 270-515 and 520-740: generation, manifests, candidate runs, scoring, auditing and artifact root.
- `src/test/java/logratio/RigidSelectorFactorialBenchmarkTest.java`, entire file.
- `src/test/java/logratio/FullSelectorFactorialBenchmark.java`, lines 48-100, 145-235, 286-473 and 714-840: translation sweep resumability, manifests, audit and known-truth transforms.
- `src/test/java/logratio/FullSelectorSweepTest.java`, lines 29-139: candidate equality, resumability, failures and artifact audit.
- `src/test/java/logratio/FullSelectorTraining.java`, lines 129-280: existing outcome loading, production evidence measurement and feature-cache schema.
- `src/main/java/logratio/api/RegistrationRecipe.java`, lines 219-377: frozen candidate identities and application.

## Scope

- Reuse verified rigid-selector inputs and results when their hashes, source assignments, engine baseline and candidate manifest exactly match Stage 01. Do not rerun work merely to create a new timestamp.
- Generate missing development inputs and outcomes under the new run ID when reuse conditions fail.
- Run every eligible recipe and the complete category fallback on identical controlled translation and rotation cases.
- Use the general warping index and all secondary metrics frozen in Stage 01.
- Measure the Stage 02 production evidence from the raw guide frames and the exact neutral provisional pass frozen in Stage 01.
- Write one truth-free feature row per recording/case and one outcome row per recording/candidate/condition.
- Record source identity, independent group, input/truth hashes, recipe-manifest hash, code digest, Java/ImageJ versions and runtime environment.
- Make the long run resumable by complete recording/candidate keys, and treat recorded failures as outcomes rather than silently retrying until success.
- Audit row counts, duplicate keys, candidate coverage, input hashes and feature/outcome joins.
- Produce a descriptive report of data coverage and failures without ranking or selecting recipes.

## Out of scope

- Do not calculate oracle headroom or identify the best recipe; Stage 04 owns that outcome-derived analysis.
- Do not train, prune or tune a selector; Stage 05 owns modelling.
- Do not alter feature definitions after outcomes begin. Return to Stage 02 with a new protocol version if a defect is found.
- Do not change candidate settings to rescue a failed row. Candidate changes require a new Stage 01 protocol and manifest.
- Do not open validation or final partitions unless the protocol explicitly assigns a structural dry run with truth hidden.
- Do not write `AutomaticRegistrationSelectorModel.java`.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/RigidSelectorFactorialBenchmark.java` | MODIFY | Emit the frozen production feature table beside the complete translation/rigid outcome matrix and strengthen audits. |
| `src/test/java/logratio/RigidSelectorFactorialBenchmarkTest.java` | MODIFY | Pin row keys, feature/outcome separation, hashes and deterministic scoring. |
| `src/test/java/logratio/FullSelectorSweepTest.java` | MODIFY if shared helpers change | Preserve candidate equality, resumption and failure semantics across the new run. |
| `<artifact_root from selector_protocol.md>` | NEW | Store immutable manifests, truth-free features, recipe outcomes, environment and audit artifacts. |
| `docs/recording-adaptive-selector/03_recipe_outcome_matrix_findings.md` | NEW | Record coverage, structural audit, failures and reuse decisions without naming winners. |

## Implementation sketch

Extend the existing benchmark command structure rather than creating a second interpretation of rigid truth:

```text
generate <project> development
run      <project> development
audit    <project> development
```

If an explicit feature-export command is cleaner, it must call the same Stage 02 production method and use the same provisional settings as Fiji:

```text
features <project> development
```

The truth-free table should contain only runtime-available evidence and split metadata:

```text
case_id,source_series_id,independent_group,image_type,declared_motion_type,
feature_contract_version,evidence_valid,evidence_reason,feature_001,...,feature_N
```

The outcome table may contain truth-derived values but no feature columns:

```text
case_id,condition,arm,recipe_id,estimator,status,
median_warping_error_px,p90_warping_error_px,worst_warping_error_px,
median_angle_error_deg,p90_angle_error_deg,worst_angle_error_deg,
refused_pairs,non_converged_pairs,bound_hits,repaired_frames,
unsupported_frames,crop_fraction,wall_seconds
```

Exact headers come from `selector_protocol.md`; do not add an unapproved metric after results are visible. Join the tables only by `case_id` inside later analysis code.

Before a long run, print and record:

```text
number of independent source groups
cases by image type, motion type and condition
candidate count and manifest hash
expected outcome rows
expected feature rows
estimated disk space and runtime
already complete, recorded failures and outstanding rows
```

Use a common score implementation for all candidates. At zero rotation, the general warping index must equal Euclidean translation error within the frozen tolerance.

## Exit gate

1. Input, truth, candidate and source-split manifests match the hashes frozen in Stage 01.
2. Every development case has exactly one feature row and exactly one outcome row for every eligible candidate and required baseline arm.
3. Feature rows contain no truth, error, winner or oracle field; outcome rows contain no production feature values.
4. Every case/candidate key is unique, and every table joins one-to-one or one-to-many exactly as declared.
5. All candidates in a case use identical input and truth hashes.
6. Recorded failures remain explicit rows and are included in later safety calculations.
7. Zero-rotation rigid scoring reduces to the translation metric within the frozen tolerance.
8. Re-running an audited complete subset changes no row; an incomplete subset resumes only missing keys.
9. `mvn -Dtest=logratio.RigidSelectorFactorialBenchmarkTest,logratio.FullSelectorSweepTest test` passes.
10. `03_recipe_outcome_matrix_findings.md` reports structural coverage and failures but contains no per-recording winner analysis.

## Known risks

- The full recipe matrix is expensive. Reuse only hash-identical verified artifacts and keep resumption deterministic.
- A pilot feature row generated after candidate outcomes are visible can create accidental feature redesign. Freeze the feature contract first and hash it into every artifact.
- Rigid grid and Newton candidates may be execution aliases while translation variants are not. Preserve explicit candidate identities and the frozen alias rule rather than silently deleting rows.
- Candidate failures can tempt repeated execution until a finite result appears. A reproducible failure is part of the outcome and must not be censored.
- Gain-fade and zero-rotation variants derived from one acquisition are not independent sources. Keep them in the same group.
- Old development artifacts may match filenames but not the final rotation engine. Require source and code hashes, not directory-name similarity.
