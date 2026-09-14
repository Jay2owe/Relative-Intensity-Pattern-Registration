# Bounded rigid registration for Log-Ratio Registration

## End goal

Add an optional rigid movement model that detects and corrects horizontal translation, vertical translation and in-plane rotation. Translation-only registration remains the default. Automatic selection must work for every declared image type: it may use a validated rigid estimator or explicitly fall back to the rigid log-ratio fit, but it must never silently return a translation-only result.

The first release uses the existing log-ratio rotation solver with a bounded angular search. A later release gives the area-correlation estimators the same rigid capability and retrains automatic selection on rotated recordings, restoring the preferred correlation path for brightfield/DIC and fiducial/static images when the evidence supports it.

## Why we're doing this

The current public workflow corrects translation but not stage or specimen rotation. Most of the geometry already exists: `Transform` stores an angle, the log-ratio Gauss-Newton solve has a hidden rotation derivative, reconciliation carries angle values, and `Warper` can rotate pixels. The missing work is robust angular initialization and limits, exact multi-frame handling, public controls, automatic-selector safety, correlation support and validation.

This matters especially in Automatic mode. The frozen selector currently chooses `AREA_CORRELATION_NEWTON` for `BRIGHTFIELD_DIC` and `FIDUCIAL_STATIC`, while the other three image types use their log-ratio category recommendation. Until correlation can estimate rotation, rigid Automatic mode must visibly fall back to the log-ratio recommendation for those two types. Once correlation rotation is implemented, it must be retrained on rotated recordings before Automatic mode may select it.

Existing evidence should be reused. `docs/thevenaz_protocol_findings.md` shows that the hidden log-ratio path passes its small synthetic gate, but has a bimodal failure pattern over the full +/-10 degree experiment. That result motivates the bounded coarse angular search in Stage 01; it is not evidence that the current hidden switch is ready to expose unchanged.

## Architecture overview

The movement model follows the existing pipeline:

```text
image type + movement request
          |
          v
automatic recipe selection -- filters out estimators not validated for rigid use
          |
          v
pair estimator -- returns Transform(dx, dy, theta) for each planned frame pair
          |
          v
reconciler -- builds one cumulative rigid pose per timepoint
          |
          v
repair and diagnostics -- handle unsupported/outlying poses and report bounds
          |
          v
warper -- applies the inverse pose to every channel and Z plane, then crops safely
```

`Transform` remains the shared contract. Angles are radians internally and describe rotation about the image centre; public controls and reports use degrees. Java and Python must preserve the same sign convention and numerical behaviour.

## Stage map

| NN | Name | One-line goal | Rough size | Depends on |
|---:|---|---|---|---|
| 01 | bounded-logratio-rotation | Give the log-ratio estimator a bounded coarse angular search and reliable joint refinement. | 1-2 days | none |
| 02 | rotation-trajectory-and-warp | Make multi-lag reconciliation, repair, warnings and output geometry correctly account for rotation. | 1-2 days | 01 |
| 03 | selector-capability-fallback | Prevent Automatic mode from choosing an estimator not validated for rigid motion and explain its fallback. | 1 day | 01 |
| 04 | java-api-and-ui | Expose the movement model and angular bound through the Java API, Fiji dialogs, macros and batch workflow. | 1-2 days | 01, 02, 03 |
| 05 | python-parity | Match Java's bounded rigid behaviour, automatic fallback and command-line controls in Python. | 1-2 days | 01, 02, 03 |
| 06 | rigid-area-correlation | Add bounded rotation to the area-correlation estimators without changing translation-only results. | 1-2 days | 01, 02 |
| 07 | selector-retrain-and-validation | Train and validate a separate rigid Automatic branch across every image type, then document the result. | 1-2 days plus benchmark wall time | 03, 04, 05, 06 |

Stages 03-05 form the safe first-release path: brightfield/DIC and fiducial/static recordings visibly fall back to rigid log-ratio registration. Stage 06 can be developed after Stage 02 in parallel with Stages 03-05. Stage 07 is the gate that may remove the fallback; implementing correlation rotation alone is not permission for Automatic mode to select it.

## House rules

- Preserve translation-only behaviour by default. When rotation is disabled, existing transforms, statuses, macro defaults and corrected pixels must remain unchanged; use bit-exact tests where the current contract is bit-exact.
- Never silently disable requested rotation. Automatic mode must select a rigid-validated estimator or return an explicit log-ratio fallback with a recorded explanation.
- Do not hand-edit generated selector coefficients as the final solution. `AutomaticRegistrationSelectorModel.java` is emitted by `FullSelectorTraining`; change the generator and regenerate the model.
- Keep the existing translation selector branch and its frozen evidence intact. A rigid branch needs a distinct run identifier, artifacts and validation record.
- Do not reopen, retune on or reinterpret a spent locked test set. Use fresh rotated development/validation/test material, split by independent source series.
- Preserve the existing estimator seam. Rotation fitting is initially supported by `LOG_RATIO_FIT`; correlation is not marked rigid-capable until Stage 06 passes, and is not marked rigid-validated for Automatic use until Stage 07 passes.
- Keep preprocessing confined to temporary estimation pixels. Apply the final transform to untouched, full-resolution source pixels and to every channel and Z slice at a timepoint.
- Angles are radians in core code and serialized transform data; user-facing settings and messages are degrees and must say so.
- A result on either the translation or rotation bound is flagged. A bound hit is never reported as ordinary convergence.
- Rotation necessarily resamples pixels. Preserve explicit nearest-neighbour use for label images, but warn clearly; recommend bilinear, bicubic or Fourier interpolation for intensity images and never change a recorded interpolation silently.
- Keep Java and Python signs, bounds, statuses, fallback rules and transform output aligned. Extend the existing parity probe rather than maintaining two undocumented interpretations.
- Preserve unrelated working-tree changes. This repository is already dirty; each stage edits only its declared files.
- Follow the session communication rules supplied with the task: concise, plain language, exact paths and commands.

## Known open questions

- The public default maximum rotation is not yet chosen. Stage 01 may use the existing +/-10 degree Thevenaz protocol as a test range, but Stage 04 must record the rationale for the public default instead of treating the test range as an automatic product decision.
- The coarse angular step and any evaluation cap must be chosen from edge displacement and measured runtime, not an arbitrary fixed number of degrees.
- Automatic mode currently has no rigid-trained candidate model. Until Stage 07 produces one, rigid selection for brightfield/DIC and fiducial/static must remain an explicit fallback.
- Stage 07 must identify fresh rotated source splits before it runs. If suitable untouched material is unavailable, record the evidence gap and keep the fallback rather than training on spent test data.
- Correlation rotation may pass for the grid refiner but fail for Newton on sparse valid support. Automatic selection uses `AREA_CORRELATION_NEWTON`; that exact path must pass independently before its fallback is removed.

## How to run a stage

Run `/do-step docs/rigid-registration/` to execute the lowest-numbered incomplete stage.
