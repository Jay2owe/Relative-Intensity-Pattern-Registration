# Match automatic output interpolation in Python

## Why this stage exists

Python already mirrors the four interpolation implementations and applies one transform path to the complete array. This stage adds the same role-aware post-transform decision as Java so an Automatic request cannot produce different pixel values merely because it was run through the Python API.

## Prerequisites

- `05_java-integration_COMPLETED.md`

## Read first

- `docs/automatic-output-interpolation/00_overview.md`, entire file.
- `docs/automatic-output-interpolation/resampling_protocol.md`, entire file.
- `docs/automatic-output-interpolation/04_selector_findings.md`, final policy/model artifact.
- The Java API/selector types added in Stage 05, entire files.
- `src/main/java/logratio/api/LogRatioRegistration.java`, final Stage 05 register flow.
- `src/main/java/logratio/core/Warper.java`, lines 96-end, and `src/main/java/logratio/StackWarper.java`, lines 35-166: authoritative geometry, warp and typed-output semantics.
- `src/ripr/types.py`, lines 180-210: current enum patterns and interpolation names.
- `src/ripr/parameters.py`, lines 164-345: immutable parameter resolution and interpolation field.
- `src/ripr/core.py`, lines 1362-1545: margins and four warp implementations.
- `src/ripr/registration.py`, lines 22-70 and 317-405: result contract, transform application and final registration.
- `src/ripr/cli.py`, lines 1-55: current image/motion/interpolation arguments.
- `tests_python/test_core.py`, lines 58-120: existing warp and margin tests.
- `tests_python/test_api.py`, entire file.
- `tests_python/test_java_parity.py`, entire file.

## Scope

- Choose and record a Python selector-module filename before editing; no canonical output-interpolation selector module currently exists.
- Add the exact data-role and output-selection mode values frozen in Stage 01.
- Preserve existing Python calls as manual interpolation with the current `Interpolation.NONE` default.
- Mirror the Stage 04 artifact mechanically; do not train or tune a Python-specific policy.
- Resolve output interpolation after `_estimate` returns the complete cumulative transform path and before `apply_transforms` runs.
- Apply the same hard rules, geometry thresholds, source features, confidence/OOD fallback and provenance as Java.
- Apply one selected interpolation across every channel/Z plane of the original array.
- Preserve explicit crop behaviour and calculate margins from the selected candidate.
- Return the output decision alongside the existing registration-recipe decision and explicit replayable parameters.
- Add matching command-line arguments for role and manual/automatic output interpolation without changing existing `--interpolation` values.
- Extend parity fixtures to compare selected candidates, reasons and resulting arrays for manual, labels, whole-pixel, fractional and rigid cases.
- Preserve numerical parity of all four warp implementations and typed/clamped output rules.

## Out of scope

- Do not fit independent Python coefficients or thresholds.
- Do not use SciPy alternatives that change the frozen interpolation kernels or border rules.
- Do not select different policies per channel/Z plane.
- Do not change registration transforms, recipe selection, reconciliation or interpolation mathematics.
- Do not open final untouched evidence.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/ripr/<TODO-output-interpolation-selector>.py` | NEW | Hold the mechanically mirrored policy/model, hard rules and decision result. |
| `src/ripr/types.py` | MODIFY | Add matching data-role and output-mode enums without changing interpolation values. |
| `src/ripr/parameters.py` | MODIFY | Carry role/mode and preserve manual defaults/replay. |
| `src/ripr/registration.py` | MODIFY | Resolve after transforms and before one application to the original array. |
| `src/ripr/cli.py` | MODIFY | Expose role and output-selection mode while preserving `--interpolation`. |
| `src/ripr/__init__.py` | MODIFY if required | Export stable public role/mode/decision types. |
| `tests_python/test_core.py` | MODIFY | Pin hard rules, thresholds, margins and typed output. |
| `tests_python/test_api.py` | MODIFY | Cover Automatic/manual behaviour, provenance and replay. |
| `tests_python/test_java_parity.py` | MODIFY | Compare Java/Python decisions and output arrays. |

The selector-module filename remains `TODO` because the approved overview did not name one. Choose it once before editing and record it in its module docstring and Stage 06 handoff.

## Implementation sketch

Mirror the Java ordering:

```python
registration = _estimate(frames, explicit_registration_parameters, progress)

if requested.output_interpolation_mode is OutputInterpolationMode.MANUAL:
    output_decision = manual_decision(requested.interpolation)
else:
    output_decision = output_selector.select(
        source=source,
        transforms=registration.cumulative,
        data_role=requested.output_data_role,
        image_type=requested.image_type,
        motion_type=requested.motion_type,
    )

corrected = apply_transforms(
    source,
    registration.cumulative,
    normalized_axes,
    output_decision.interpolation,
    requested.crop,
)
```

Do not call `apply_transforms` during candidate selection. The selector uses the frozen truth-free source/transform evidence and returns a policy; the original array is warped once.

Parity fixtures should cover:

```text
manual four-candidate selection
labels/masks with fractional translation and rotation
exact and tolerance-boundary whole-pixel transforms
fractional translation
small and larger rotations
invalid/unspecified/mixed role fallback
low-confidence/OOD fallback
byte, unsigned-short and float storage
multi-channel and multi-Z application
```

Categorical decisions and serialized values should match exactly. Floating output tolerances must be frozen tightly enough that parity differences cannot cross fidelity gates.

## Exit gate

1. Existing Python calls without new fields remain manual and reproduce previous arrays exactly.
2. Python role/mode/interpolation serialized values match Java exactly.
3. Python contains no independently fitted model values; artifact provenance matches Java.
4. Selection runs after cumulative transforms and before a single output warp.
5. Labels, whole-pixel transforms, missing/mixed roles and low-confidence/OOD cases resolve identically to Java.
6. Every image/motion/data-role/geometry fixture selects the same candidate and fallback reason in Java and Python.
7. Manual interpolation remains authoritative and explicit replay performs no selector run.
8. Crop margins and multi-channel/Z output match Java's selected-candidate behaviour.
9. All four existing warp parity/accuracy tests remain within their frozen tolerances.
10. Command-line and batch paths resolve each recording independently through the shared registration implementation.
11. `python -m pytest` passes.
12. `mvn test` still passes after any parity-probe extension.

## Known risks

- NumPy/SciPy border and reduction orders can differ from Java. Compare named features and threshold-boundary decisions, not only easy examples.
- Resolving output mode in `LogRatioParameters.resolve()` before pixels/transforms exist would recreate the same ordering bug avoided in Java.
- Array dtype conversion can hide or introduce overshoot differences. Compare float pre-cast and final stored arrays where the protocol requires both.
- Batch and file APIs may call parameter resolution earlier than `register()`. Ensure there is one image-aware output-decision route.
- Hand-copied constants will drift after regeneration. Prefer a mechanical artifact-to-Java/Python generator or an audited shared source.
