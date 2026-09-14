# Keep biological Recommended arithmetic separate from longitudinal compatibility

Status: experimentally corrected and verified on 9 September 2026. The actual manual and automatic biological wrapper matches the historical Recommended movement CSV; both longitudinal controls retain exact movement bits. No production change or recipe retuning.

The new three-route wrapper was compiled against the frozen longitudinal engine. That engine has same-package overrides specifically written to reproduce the Round 17 reference: [compatibility image arithmetic](../../src/experimental/java/ripr/core/compat/LogPlane.java) changes smoothing reduction order/intermediate rounding, and [compatibility pair fitting](../../src/experimental/java/ripr/core/compat/PairAligner.java) retains double precision when sampling intensities and gradients. Calling Recommended through this engine therefore does not call the numerical implementation used for its historical benchmark.

The original [image arithmetic](../../src/main/java/ripr/core/LogPlane.java) and [pair fitting](../../src/main/java/ripr/core/PairAligner.java) remain available; their historical Java packages reproduce the saved 180-frame MCG_02 transforms within CSV rounding (maximum 4.981e-10, versus 0.083963675 for the new wrapper). Restoring only those two original class families in front of the current engine also matches, while restoring only the pair fitter does not. An ordinary 180-frame control, MCG_03, matches after the two-component restoration (maximum 4.997e-10). All 540 components per recording were checked. The parameter tables are byte-identical, and the API/scheduling/reconciliation engine remains the current one in the isolated test.

The implementation requirement is:

```text
Biological moving cells -> original Recommended arithmetic
Bright/dim tissue      -> longitudinal compatibility arithmetic
Tissue landmarks       -> longitudinal compatibility arithmetic
```

Do not globally roll back the compatibility classes: their changed arithmetic is intentional and accepted for the other two routes. Do not retune Recommended to compensate for running it through a different numerical implementation. Do not treat an isolated diagnostic match as proof that the actual wrapper is corrected, or a rounded transform match as byte-identical registered TIFFs.

Evidence lives in the existing single_channel_pulsing_lowlight_registration_tuning workspace, round R25_three_route_automation, stage `s3_score_alignment/r25_c006_compatibility_diagnosis`. Its immutable manifest pins every diagnostic run, source snapshot, resolved setting table, loaded-class origin, and per-component comparison. No external registrations were rerun, no Python was executed, and existing review TIFFs/defaults were unchanged. The analysis-tuner workflow constrained this to matched-input execution checks; no new quality benchmark was performed.

## Experimental correction and completed verification

[BiologicalRecommendedRegistration](../../src/experimental/java/ripr/core/BiologicalRecommendedRegistration.java) now loads the original, checksum-pinned Recommended Java engine in a private class loader. All `ripr.*` classes come from that engine exclusively, with no fallback to the longitudinal engine; shared ImageJ image types and plain movement values cross the boundary. The source artifact is the immutable c001 diagnostic engine, SHA256 `56dc20e6a4a6118c88d5c239214a465e6216616181df3de26c9ca84c5acb3d5d`. The existing stage builder bundles it as `/ripr/biological/recommended-engine.jar`. A missing or modified resource fails explicitly. No algorithm was copied, retuned, or globally replaced.

Both manual and automatic biological dispatch use this isolated engine. The other two routes still call the unchanged longitudinal implementation. The experimental result exposes biological movements, support and engine identity rather than pretending an old-engine result is a current-engine Java type. `biologicalParameters` is now documented as an inspection/diagnostic helper, not the execution path.

`code/complete_r25_fix.ps1` completed after mains power returned: full original 180-frame MCG_02 and MCG_03 manual runs, MCG_09 automatic run, and one previously accepted 40-frame control for each longitudinal route. `code/verify_r25_fix.ps1` verified all 1,620 biological movement components against historical CSV precision and all 240 longitudinal control components against their exact double bits. Unit tests passed for class isolation, altered-engine refusal, direct/manual/automatic equality and recipe-label invariants. The immutable tested build is `s1_prepare_review_inputs/r25_a008_build`; verification is `s3_score_alignment/r25_d006_fix_verification` (outputs SHA256 `8fe3a48dac004c35e70a54129914099855bf1dc81a916caf092e4c9e29965aaf`).

| Actual corrected wrapper | Frames | Maximum difference from saved historical movement components |
|---|---:|---:|
| MCG_02, manual biological route | 180 | 4.9807447055627563e-10 |
| MCG_03, manual biological route | 180 | 4.996576485893911e-10 |
| MCG_09, automatic biological route | 180 | 4.98175722896121e-10 |

The residuals are below the historical CSV precision of 1e-9. This fixes the 0.083963675-pixel discrepancy; it does not claim byte-identical TIFFs. The SynRCaMP A1 bright/dim and transmitted-light A1 landmark controls retain every movement bit. Automatic biological execution used the prior frozen confident selector, keeping arithmetic verification independent of the new confidence-rule experiment.

The same continuation tested a recording-distance confidence rule using cached, recording-group-separated training measurements. It improved confident correct choices from 18 to 24 on the 29 grouped development predictions, and from four to five on seven repeat checks, without a wrong confident choice in either partition. However, it made one confident wrong starting-preset choice on the two Incucyte diagnostics (A1: low-light instead of the previously tested dense-signal preset, within the bright/dim branch). That was not a new measured alignment error: this diagnostic recipe was not run. The candidate is rejected for general activation and retained as failed evidence; the prior conservative selector remains the model to use. No post-hoc fitting or recording-name exception was applied to fix this diagnostic.

The complete before/after decision is `s3_score_alignment/r25_d007_selector_decision/out/review.md`; `retained_model.json` points to the unchanged `r25_a003_fit_train/out/selected.properties` and corrected a008 build. Seven previously reserved cases are repeat regression checks, not a new untouched test; Incucyte remains outside fitting and held-out accuracy claims. The queue finished automatically and stopped. It did not inject chat prompts, open ImageJ, rewrite TIFFs, rerun externals, install a plugin, or execute Python.
