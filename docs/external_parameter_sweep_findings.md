# External parameter sweep: active four-class findings

Completed 2026-08-20 and rescoped 2026-08-21. The active analysis contains 64 development recordings
and 32 locked recordings across brightfield/differential interference contrast, dense fluorescence,
fiducial/static and phase contrast. Sparse/low-light is excluded from every class table and aggregate.

The complete generated report is in
[`library/benchmark/v2/runs/external_parameter_sweep_v1/FINDINGS.md`](../library/benchmark/v2/runs/external_parameter_sweep_v1/FINDINGS.md).

## Headline result

- Defaults against defaults: our base arm had an overall median error of 0.018687 px on development
  and 0.025005 px on locked data, with zero failures.
- Tuned against tuned: our selector had an overall median error of 0.014520 px on development and
  0.021103 px on locked data, with zero failures.
- No tuned external row beat the corresponding selector row with zero failures.
- At installed defaults, Descriptor-based series registration beat our base arm on the locked
  fiducial/static class: 0.023669 px versus 0.031238 px.

## Scope decision

The original raw run included sparse/low-light recordings, but the locked stacks were invalid: the
red-green-blue source contained signal in green while the version 2 generator selected blue, producing
all-zero inputs. Identity outputs were consequently counted as successful registrations. The class was
removed after result review, so the active paper is explicitly a four-class comparison rather than a
claim that our model wins every microscopy class.

The raw files, original 30-entry parameter freeze and five-class outputs remain only as audit evidence.
If sparse/low-light is reintroduced, it requires correctly generated inputs and a new prospective
comparison. Details are recorded as protocol deviation D4 in
[`external_parameter_sweep_protocol_deviations.md`](external_parameter_sweep_protocol_deviations.md).

## Reproducibility gates

- All 24 raw development configurations have their complete expected row counts.
- The active tables contain only four image classes plus a four-class aggregate.
- Eleven of twelve installed-default adapters replay exactly. Descriptor remains stochastic under the
  installed concurrent mpicbg optimizer and is reported as the declared exception.
- All 12 installed Fiji artifact hashes match the Stage 0 manifest.
- `src/main` and the two saved in-house comparison tables match their pre-run SHA-256 controls.
- The complete Maven suite passed: 329 tests, 0 failures, 0 errors, 0 skipped.

The active result tables are
[`defaults_against_defaults.csv`](../library/benchmark/v2/runs/external_parameter_sweep_v1/defaults_against_defaults.csv)
and
[`tuned_against_tuned.csv`](../library/benchmark/v2/runs/external_parameter_sweep_v1/tuned_against_tuned.csv).
