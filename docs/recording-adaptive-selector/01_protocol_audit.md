# Stage 01 protocol audit

## Prerequisite

The rigid selector has an equivalent written freeze even though the planned `07_selector-retrain-and-validation_COMPLETED.md` filename is absent:

- `docs/rigid-registration/rotation_selector_v1_validation_freeze.md`
- `docs/rigid-registration/rotation_selector_v1_full_benchmark_summary.md`
- decision A26 in `library/rigid_selector_tuning/protocol.md`

All five image types passed the frozen continuous-rotation validation. The later known-event estimator is implemented but remains experimental and opt-in because fresh remount evidence is unavailable. It is excluded from adaptive eligibility. The exact current rotation source hashes are captured in `rotation_baseline_manifest.csv`.

## Manifest checks

| Check | Result |
|---|---|
| Candidate identifiers unique | PASS: 128/128 |
| Candidates executable by `RegistrationRecipe` | PASS |
| Source-series identifiers unique | PASS: 45/45 |
| Development / validation / final counts | PASS: 25 / 10 / 10 |
| Per-type partition balance | PASS: 5 / 2 / 2 |
| Independent group crosses a partition | PASS: none |
| Source files present | PASS: 45/45 |
| Recorded source SHA-256 values match files | PASS: 45/45 |
| Fresh validation/final exact hashes in prior manifests | PASS: no matches |
| Outcome or winner fields in split manifest | PASS: none |

Frozen hashes:

- candidate manifest: `2d482270049113410b25ced202f41f852afc0c4a50f4fd684bb20b748438bc3c`
- source split manifest: `abedd2db6bb95a0ebc937465580aa6a85dc54fa236760fabfaefd2628b665136`
- rotation baseline manifest: `c663230a02b87c526e69ffff6039130ddc995f465b38c79446712d10ae21f308`

## Independence limits

Validation and final sources are unused exact acquisitions, not sibling crops of a used file. Some acquisitions still share a public repository or originating laboratory across partitions because sufficiently broad fresh microscopy material was not available for every category. Results therefore support acquisition-level generalization, not a claim of independent laboratories or microscopes. This limitation does not justify moving a source after outcomes are visible.

The large Cognet TIFF is losslessly readable with `tifffile` but not with the repository's ImageJ TIFF decoder because it uses Zstandard compression. Input preparation must extract the declared frame losslessly and write a conventional benchmark fixture; it must not rescale intensities.

## Contract completeness

The protocol freezes candidate scope, universal pilot, feature families and validity rules, target, permitted models and grids, grouped splits, fallback, non-adaptive comparators, source balancing, headroom threshold, safety and promotion gates, runtime, run ID, artifact root, provenance and the one-time final opening. Truth and winner fields are explicitly prohibited from production evidence.

No recipe outcome or per-recording winner was generated or inspected while making these decisions.

## Verdict

READY

## Final-run identity frozen before opening

- report runner: `RecordingAdaptiveSelectorFinalReport.java`
- gate fixtures: `RecordingAdaptiveSelectorFinalGatesTest.java`
- commands: `freeze`, then `open` once immediately before final recipe outcomes, then `report`
- final policy order: adaptive, otherwise frozen fixed, otherwise complete category recommendation

## Fold-local candidate-retention implementation

Before recipe results were inspected, the linear-family implementation fixed this fold-local rule:

- reject a recipe if any training-fold case breaches the protocol safety rule;
- require positive source-balanced gain and positive median gain in at least three training-fold independent groups;
- retain at most 12 recipes per image type, ordered by training-fold gain and then recipe ID;
- recompute the retained set independently inside every inner and outer fit.

The cap limits one model with 48 fields and few independent groups from comparing all 128 recipes at prediction time. It is not changed after development outcomes become visible.
