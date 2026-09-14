# Rigid Automatic selector validation gap

Date: 2026-08-23
Run ID: `rigid_selector_v1`

## Decision

Do not promote the tuned rigid policy into Automatic mode. Keep the existing explicit rigid
log-ratio fallback for all five image types. Translation-only Automatic outputs remain unchanged.

The engine limitation is solved: log-ratio and area correlation can fit bounded rotation, and a
fixed-angle second method can refine translation without changing the fitted angle. The remaining
block is evidence and generalisation.

## Completed evidence

The frozen development library contains 180 generated recordings and 129 complete recipes. The run
completed 23,220 joint-rigid rows and 23,220 truth-angle translation rows across all preprocessing,
pixel-support, intensity-band, pixel-removal and estimator choices.

The baseline exposed a repair defect: six-median-deviation step repair replaced five valid frames in
every intermittent-jump recording. Disabling repair only for that declared motion type left the other
motion policies unchanged and produced 167 recipe/image-type combinations passing every frozen gate.
One joint recipe passed all five image types:

`support_gradient__band_no_top_25__filter_gaussian_1_0__mask_least_informative_25`

A frozen hybrid kept that angle trajectory and refined translation separately for brightfield, dense
fluorescence and fiducial/static inputs. Phase and sparse low-light retained the joint result. After
one bounded dense retry declared before execution, all five development rows passed every gate.

## Source-separated integration result

`sealed_test_3` supplies two sources per image type that are separate from development, but it had
already been opened for translation-selector and Newton-refinement work. It was declared
integration-only before this rigid run and cannot be a fresh locked result. No recipe or threshold may
be changed from its outcome.

The frozen joint and hybrid policies were run once on 90 rigid integration recordings. Six of ten
policy/image-type gate rows passed:

| Image type | Universal joint | Final hybrid | Blocking result |
|---|---:|---:|---|
| Brightfield/DIC | pass | pass | none |
| Dense fluorescence | fail | fail | runtime 1.66x joint and 2.11x hybrid; limits 1.25x and 1.50x |
| Fiducial/static | pass | pass | none |
| Phase contrast | fail | fail | 0.895 px mean median and 11.32 px worst on `pveronii_03` |
| Sparse low-light | pass | pass | none |

Dense accuracy improved under the hybrid, from 0.01794 px to 0.00908 px mean median, so this is a
performance failure rather than an accuracy failure. The phase failure is concentrated in
`phase_strack_pveronii_03`; `phase_strack_lysobacter_03` remained below 0.032 px mean median.

## Recovery work after the integration failure

Dense fluorescence does not require the slow three-pass hybrid. The original complete development
factorial already contains a fast nominee that passed every gate:
`support_gradient__band_no_bottom_25__filter_gaussian_1_0__mask_none`. Its development mean median,
p90, worst and runtime ratio are 0.01250 px, 0.01948 px, 0.0362 px and 0.689. It remains frozen for
future fresh validation; it was not selected or altered using the spent integration set.

Phase received an opt-in confidence seam. Every pair compares the established global rigid fit with
a locally refined zero-angle alternative and retains rotation only when the rigid residual gain
clears a threshold. The comparison also supports selected-pixel refits and leaves an audit record on
every pair. It is off by default and does not change current registration.

Two frozen 11-threshold development grids were completed, 396 registrations each:

- the no-mask Phase recipe produced three pooled passes but no threshold passing all four
  source-held-out training folds;
- the original universal selected-pixel recipe produced eight pooled passes, but again no threshold
  passing all four source-held-out training folds. The best passed three folds.

The selected-pixel candidate improves `phase_strack_pveronii_01` but modestly worsens the other three
development sources. Pair-level evidence can suppress unsupported zero-angle corrections; the
remaining decision is recording-level: which Phase sources should use the candidate rather than the
category fallback. Four sources are not enough to train that decision defensibly.

## Blocking evidence

The source-separated result cannot be tuned without converting the only available integration set
into development data. The workspace has no further genuinely unused, balanced five-image-type set
on which to validate the resulting change and then obtain a fresh locked result.

Fiducial/static remains the narrowest inventory:

- reinforced-cage acquisitions `d1` to `d4` were used for development;
- reinforced-cage `d5` supplied the opened locked test;
- commercial-cage `d1` and `d2` supplied the opened sealed test;
- commercial-cage `d3` and `d4` supplied the opened third sealed test;
- ConfocalCheck can supply one new independent source group, not both validation and locked groups.

Phase needs more source diversity to learn a recording-level fallback without tuning on
`pveronii_03`. Dense fluorescence still needs separate large-field validation material even though a
development-passing fast recipe is now available.

## Unblocking requirement

Acquire a new source-separated library before changing the policy:

1. at least four new independent Phase development series spanning at least two acquisition/sample
   families, followed by two further independent groups for validation and locked evidence;
2. separate large-field Dense validation and locked groups for the frozen fast nominee;
3. at least two new independent fiducial/static groups beyond ConfocalCheck; and
4. fresh validation and locked groups for brightfield and sparse low-light so the final claim remains
   balanced across all five declared image types.

Then freeze a second protocol, tune only on development, validate once, lock the policy, and open the
fresh final set once. Until then, an unvalidated Automatic promotion would contradict the measured
integration failure.

## Evidence locations

- Protocol and amendments: `library/rigid_selector_tuning/protocol.md`
- Development gates: `library/rigid_selector_tuning/runs/development/r02_full_factorial/summary_r05_hybrid_final/`
- Integration gates: `library/rigid_selector_tuning/runs/integration/r06_source_separated_integration/summary/`
- No-mask confidence gates: `library/rigid_selector_tuning/runs/development/r10_phase_confidence_grid/summary/`
- Selected-pixel confidence gates: `library/rigid_selector_tuning/runs/development/r12_phase_selected_confidence_grid/summary/`
- Fixed-angle regression test: `src/test/java/logratio/core/RegistrationTest.java`

## Source-search update: 2026-08-23

The Phase and Dense portions of this evidence block have now been removed. Fresh CC BY 4.0 public
recordings were found, downloaded and hash-frozen before any registration outcome was generated:

- eight CellTracksColab Phase videos from repeats R1/R2 for development;
- two CellTracksColab Phase videos from R3 for validation;
- one unrelated HeLa Kyoto digital-phase channel for the locked Phase opening;
- one unused OpenCell/Leonetti ARHGAP21 recording for Dense validation; and
- the HeLa Kyoto EGFP-alpha-tubulin channel for the locked Dense opening.

See `library/rigid_selector_tuning/source_candidates/README.md` and `source_manifest.csv` for the
exact allocation and hashes. This update does not change the historical A07 decision and does not
promote rotation. The remaining work is to generate native-length controlled cases, tune the Phase
recording-level rule on development only, then open validation and locked data in order. A balanced
five-image-type publication claim still also needs the missing fresh fiducial/static groups described
above.
