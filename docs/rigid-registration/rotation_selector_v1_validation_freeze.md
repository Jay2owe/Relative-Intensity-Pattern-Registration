# Automatic rotation selector v1 validation freeze

Frozen before evaluating any `phase_strack_*_05` benchmark outcome.

The consolidated five-image-type accuracy, runtime, pass-status, and selector-choice tables are in
`rotation_selector_v1_full_benchmark_summary.md`.

## Fixed production design

Automatic translation selection remains unchanged. A separate rotation selector chooses the angle
recipe. The translation recipe first fits `dx/dy`, the rotation recipe proposes an angle, and the
translation recipe refits `dx/dy` at that fixed angle. If rotation does not clear its residual-gain
gate, the exact original translation pair is retained.

| Image type | Rotation recipe | Minimum residual gain |
| --- | --- | ---: |
| Brightfield/DIC | Existing category recommendation | 0.001 |
| Dense fluorescence | mutual-noise-gradient support; exclude top 10%; Gaussian 0.7; exclude least-informative 25% | 0.002 |
| Fiducial/static | all pixels; exclude top 10%; Gaussian 0.7; no information mask | 0.005 |
| Phase contrast, provisional step p90 <= 2.0 px | all pixels; exclude top 25%; Gaussian 0.7; exclude least-informative 25%; translation-seeded proposal | 0.010 |
| Phase contrast, provisional step p90 > 2.0 px | all pixels; exclude top 25%; no filter; no information mask; best of translation-seeded and global proposals | 0.005 |
| Sparse/low-light fluorescence | all pixels; exclude bottom 25%; Gaussian 1.0; exclude least-informative 25% | 0.010 |

The Phase provisional statistic is measured by the existing neutral automatic evidence pass. In the
global branch, both local and global fixed-angle translation refits are evaluated and the lower
residual is used. The fixed-angle result is retained only when it is no worse than the exact original
translation result. Intermittent-jump motion disables generic outlier smoothing (`outlierMads=0`),
while unsupported-frame repair remains active.

## Frozen inputs and split

The complete 36-case `_05` input manifest is
`library/rigid_selector_tuning/input_manifest_phase-validation.csv`, SHA-256
`6A90BA9AF6AFAC2423216C7AF6CACF5A18AA92AEAD9485BC76C16D8C90F0844B`.
Each source series contributes nine cases spanning the four controlled motion classes, clean and
gain-fade conditions, plus a zero-rotation case.

- Validation: `phase_strack_lysobacter_05`, `phase_strack_pputida_05` (18 cases).
- Final locked test: `phase_strack_pveronii_05`, `phase_strack_rahnella_05` (18 cases).

The final locked series must not be evaluated unless the validation series pass. No selector,
threshold, solver, repair, or gate may change after validation. A failure on the final locked set
means Phase rotation is not promoted; the result must not be used to retune this version.

## Predeclared gates

The selector and matching automatic-translation control must have identical case sets. Each stage
must satisfy all existing split-rotation gates:

- no failed registrations or control mismatches;
- clean mean p90 angle error <= 0.10 degrees and clean worst angle error <= 0.50 degrees;
- gain-fade mean p90 angle error <= 0.25 degrees and gain-fade worst angle error <= 1.00 degree;
- mean median central-frame warp no more than 0.002 px above translation control;
- mean p90 central-frame warp no more than 0.005 px above translation control;
- worst central-frame warp <= max(1.0 px, control worst + 0.05 px);
- no additional issue recordings or repaired frames relative to translation control;
- runtime <= 1.75 times the existing category-recommendation rotation baseline (see the protocol
  correction below);
- zero-rotation median-warp degradation <= 0.01 px.

The final promotion decision requires both the validation and final locked stages to pass separately.

## Protocol correction before the final locked test

The first validation summary exposed a benchmark-design error in the originally written runtime
line: it compared a full rigid registration with rotation disabled, although the gate was intended
to prevent an angle recipe from being materially slower than the existing angle recipe. This was
already contradicted by the prior held-out audit, where every rotation policy was 3.24-10.95 times
the translation-only control. It therefore could never serve as a promotion gate for adding rotation.

Only the timing denominator is corrected. Accuracy results had already been opened when this error
was noticed; none of their gates changed. The replacement was fixed from the already-spent `_04`
development set before evaluating either final locked series: compare with the
category-recommendation rotation recipe at the same 0.010 residual-gain gate. The observed `_04`
per-series runtime ratios were 1.3986, 1.4438, 1.6079, and 1.6098 (combined 1.5120), so the fixed
ceiling is 1.75. This allows about 9% margin above the worst development ratio. The final locked
series remained untouched at this amendment.

## Validation outcome

Both preassigned validation series passed every corrected gate (18/18 registrations completed):

| Series | Mean median warp (px) | Mean p90 warp (px) | Worst warp (px) | Clean p90 angle (deg) | Clean worst angle (deg) | Runtime ratio | Result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `phase_strack_lysobacter_05` | 0.01388 | 0.02338 | 0.05703 | 0.01496 | 0.03569 | 1.4555 | Pass |
| `phase_strack_pputida_05` | 0.04623 | 0.42256 | 9.90441 | 0.03530 | 0.07309 | 1.6195 | Pass |
| Combined | 0.03005 | 0.22297 | 9.90441 | 0.02513 | 0.07309 | 1.5406 | Pass |

The large P. putida worst warp is an inherited automatic-translation failure in the steady-drift
case: the rotation result remains below its translation-control ceiling (control worst 10.26894 px),
adds no issue recording or repair, and keeps the angle error below 0.074 degrees. This gate was
predeclared specifically so a rotation recipe is judged on errors it adds rather than rejecting it
for an existing translation-selector failure.

Validation candidate SHA-256: `3E4880FA5F733B1C65EE0AC155F9AD41BD01B19679F7F8D16F05A359A85A0446`.
Translation-control SHA-256: `2931296FB65AF1DA32D89B1E1DBB204214E1CE33E6D8DC708AFC68380F1BF31D`.
Category-rotation timing-control SHA-256: `AF9CBA8FCF91D195D99532FC040D2E92D932DD1D249C679C109921F71311F534`.

## Final locked outcome

Both preassigned final locked series passed every gate (18/18 registrations completed):

| Series | Mean median warp (px) | Mean p90 warp (px) | Worst warp (px) | Clean p90 angle (deg) | Clean worst angle (deg) | Runtime ratio | Result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `phase_strack_pveronii_05` | 0.03680 | 0.10102 | 1.57437 | 0.03402 | 0.10451 | 1.4952 | Pass |
| `phase_strack_rahnella_05` | 0.01829 | 0.03117 | 0.09269 | 0.02447 | 0.05295 | 1.3612 | Pass |
| Combined | 0.02754 | 0.06610 | 1.57437 | 0.02925 | 0.10451 | 1.4268 | Pass |

There were no failed registrations, added issue recordings, or added repaired frames. Combined
zero-rotation median-warp degradation was 0.0000033 px. The P. veronii worst warp remained below its
predeclared translation-control ceiling (control worst 5.67640 px).

Final candidate SHA-256: `C5ADF5B5DADCC3EB28640BD5C7B394D8C0A062DDAA2D5B27F6EC438A5F7C1ED8`.
Translation-control SHA-256: `A049AA0DBCF42F469DA86F1E5B37CB3B208BE9447DA7C59029B9DF7EEF37BDC2`.
Category-rotation timing-control SHA-256: `6169D286B98F0FCF67D63BABDA2CDA24BF2860F184B9A44CFDD872890CA775CB`.

Conclusion: automatic Phase-contrast rotation is promoted in selector v1. All five supported image
types now have independently selected translation and rotation recipes.

## Production verification and deployment

The supplied `MCG_04 - 1 - 78.tif` was rerun read-only as the established real-stack smoke: 512 by
512 pixels, four channels, 78 timepoints, estimation channel 2, Brightfield/DIC, subpixel random
walk, Automatic selection, and rotation enabled. Automatic retained the area-correlation/Gaussian
0.7 translation recipe and independently selected the category-recommendation median 3x3 rotation
recipe. It accepted 32 of 359 angle proposals and declined 327. Maximum pair angle was 0.01982
degrees, maximum cumulative angle was 0.00394 degrees, and median residual changed from 0.023407 to
0.021511. The complete run took 80.395 seconds and emitted explicit progress for automatic
translation selection, rotation selection, translation fitting, angle testing, fixed-angle
translation refitting, reconciliation, repair, and output warping.

The full Maven suite passed 399 tests with zero failures or errors. The deployed Fiji JAR is
`Fiji.app/plugins/LogRatioRegistration-0.1.0-SNAPSHOT.jar`, 340,439 bytes, SHA-256
`7205CA6F01BE7DE778436BE0C38FD423D7C7F22A7E846FB1362F66BE4E8010BA`. Its hash matches the packaged
source artifact and it contains the production rotation selector, plugin, and batch classes.

## Frozen implementation hashes

- `AutomaticRotationSelector.java`: `3BFDCADCBF4B803A4F7023101480DF2AD3B992559F92D107A82C82E80B7A388F`
- `Registration.java`: `0F58E3E3E07E024C1FD743D5A4BCB1A4158C0B92A4BD4858E60330F017AEFC7C`
- `LogRatioRegistration.java`: `56B8194F2591D1E911598FECC6089EA5F277A4BFC7AB6A48CD99910CA1CFBBE9`
- `LogRatioParameters.java`: `C51AA1F7804D5E9AD052DBC7617BD753661ED42753B46A3745A0A23EE612B652`
