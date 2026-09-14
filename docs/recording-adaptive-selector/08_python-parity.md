# Match recording-adaptive selection in Python

## Why this stage exists

Python currently mirrors the frozen image-type rule directly in `LogRatioParameters.resolve()`, before it has access to recording pixels. A recording-adaptive selector requires the same raw-image features, neutral provisional pass, model decision and fallback as Java; this stage implements that parity without independently fitting a Python model.

## Prerequisites

- `06_java-selector-integration_COMPLETED.md`
- Stage 07 may be incomplete; this stage can run alongside it.

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/selector_protocol.md`, entire file.
- `docs/recording-adaptive-selector/02_recording_evidence_findings.md`, final feature definitions.
- `docs/recording-adaptive-selector/05_feature_model_findings.md`, final model/fallback and artifact hash.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 48-306 and 352-end: authoritative Java evidence and decision logic.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`, entire file: authoritative frozen model.
- `src/main/java/logratio/api/LogRatioRegistration.java`, lines 27-105 and 145-163: image-aware two-pass flow.
- `src/ripr/parameters.py`, lines 164-345: current category recommendation and static Automatic rule.
- `src/ripr/registration.py`, lines 22-70, 103-155, 272-315 and 349-405: result objects, guide frames, estimation and current Automatic result.
- `src/ripr/__init__.py`, entire file.
- `tests_python/test_api.py`, lines 1-145: current recommendation and Automatic tests.
- `tests_python/test_java_parity.py`, entire file.
- `src/test/java/logratio/core/PythonParityProbe.java`, entire file.

## Scope

- Choose and record a short Python selector-module filename before editing; no canonical module currently exists in the repository.
- Port the Stage 02 evidence formulas and frozen order exactly, including validity and out-of-distribution handling.
- Port the generated Java model/fixed policy mechanically from the same Stage 05 artifact. Do not fit or tune in Python.
- Move Automatic resolution into the image-aware `estimate`/`register` path so raw frames and a provisional registration are available.
- Define clear behaviour for calling parameter-only `resolve()` on an Automatic request: it must not claim to have made a recording-specific decision without image pixels.
- Run the same neutral provisional settings, candidate eligibility, confidence threshold and fallback as Java.
- Preserve manual and recommended parameter resolution.
- Return the same chosen recipe, fallback flag, predicted target/gain, threshold, model version and reason as Java.
- Extend the Java parity probe to emit evidence and selector decisions for analytic and edge-case fixtures.
- Test numerical feature parity, exact categorical decisions and explicit-recipe replay.
- Verify the command-line and batch APIs inherit the image-aware behaviour through `register()` without requiring a second selector implementation.

## Out of scope

- Do not train separate NumPy/scikit-learn coefficients.
- Do not use SciPy measurements that are merely similar to Java if they change feature values or decisions beyond the frozen tolerance.
- Do not change the Java model to make Python easier to implement.
- Do not add output-interpolation selection or confidence-weighted reconciliation.
- Do not promote the model on final evidence; Stage 09 owns that decision.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/ripr/<TODO-selector-module>.py` | NEW | Hold the exact evidence, generated model and decision logic without overloading parameter definitions. |
| `src/ripr/parameters.py` | MODIFY | Remove the static image-type Automatic decision and preserve parameter-only contracts. |
| `src/ripr/registration.py` | MODIFY | Run neutral provisional measurement and resolve Automatic with recording pixels. |
| `src/ripr/__init__.py` | MODIFY if required | Export only stable public selector result types/functions. |
| `tests_python/test_api.py` | MODIFY | Cover adaptive choice, fallback, replay and parameter-only resolution. |
| `tests_python/test_java_parity.py` | MODIFY | Compare Java/Python evidence and decisions. |
| `src/test/java/logratio/core/PythonParityProbe.java` | MODIFY | Emit authoritative selector evidence/results for parity fixtures. |

The selector-module filename is intentionally unresolved because the conversational plan supplied no canonical path. Choose it once at the beginning of this stage and record it in the module docstring and Stage 08 handoff.

## Implementation sketch

The Python flow should match Java structurally:

```python
requested = parameters or LogRatioParameters()

if requested.selection_mode is SelectionMode.MANUAL:
    explicit = requested
elif requested.selection_mode is SelectionMode.RECOMMENDED:
    explicit = category_recommendation(requested)
else:
    category = category_recommendation(requested)
    eligible = selector.eligible_candidates(category, requested.fit_rotation)
    if selector.requires_evidence(eligible):
        pilot_parameters = selector.neutral_provisional(category)
        pilot_frames = _estimation_frames(source, axes, pilot_parameters)
        pilot = _estimate(pilot_frames, pilot_parameters)
        evidence = selector.measure(pilot_frames, pilot.cumulative, category)
        decision = selector.select(evidence, category, requested.fit_rotation)
    else:
        decision = selector.fixed_or_fallback(category)
    explicit = decision.parameters
```

The actual code should avoid recalculating guide frames unnecessarily and must not modify `source`.

Feature parity should compare each named feature, not only the final decision. Store tolerances per feature family in the frozen protocol or findings; categorical context and fallback reasons should match exactly after normalizing formatting.

For `LogRatioParameters.resolve()` with `SelectionMode.AUTOMATIC`, choose the least surprising contract consistent with the Java API: either require an image-aware resolver and raise a clear error, or return an explicitly labelled unresolved/category base. It must not silently return the adaptive winner because no recording was measured.

## Exit gate

1. Python contains no independently fitted coefficient, threshold or candidate list; generated values match the Java artifact hash/provenance.
2. Every named raw-image, provisional-motion and support/change feature matches Java within its frozen tolerance on parity fixtures.
3. Invalid and out-of-distribution fixtures fall back identically with equivalent reasons.
4. Every image type, declared motion type and translation/rigid request resolves to the same recipe and fallback flag in Java and Python.
5. Python uses the same neutral provisional settings and does not let a candidate preprocessing choice affect its own evidence.
6. `LogRatioParameters.resolve()` no longer pretends to make a recording-specific decision without pixels.
7. Manual and recommended Python behaviour remains unchanged.
8. Explicit replay of a resolved recipe performs no second selector pass.
9. Python batch and command-line runs resolve each input recording independently through the shared registration path.
10. `python -m pytest` passes.
11. `mvn -Dtest=logratio.api.AutomaticRegistrationSelectorTest test` passes, and the Java parity probe is rebuilt before the parity suite.

## Known risks

- Java loops and NumPy reductions may differ in floating-point order. Freeze tolerances tight enough to protect decisions and test cases near the confidence boundary explicitly.
- Leaving the old static rule in `parameters.resolve()` can bypass the new image-aware selector before registration starts.
- Running a provisional pass in both `resolve()` and `register()` would double work and may produce conflicting decisions. There must be one image-aware route.
- SciPy filters and gradients may use different border conventions from Java. Port the exact formulas or document and prove numerical equivalence.
- Copying generated constants manually invites Java/Python drift. Prefer a mechanical generator or one audited artifact source.
- Python callers may rely on `resolve()` returning explicit values. A changed Automatic-only contract needs a clear exception/message and tests while manual/recommended behaviour remains stable.
