# Artifact audit rejected valid registration borders
**Date**: 2026-08-17
**Files changed**: `library/benchmark/audit_full_benchmark_2026_08_16.py`, `library/benchmark/test_audit_full_benchmark_2026_08_16.py`
**Guard**: `CorrectedStackValidityTest.test_stack_with_nan_warp_borders_is_valid`, `CorrectedStackValidityTest.test_stack_with_an_entirely_blank_failed_frame_is_valid`

## What went wrong
The final audit rejected 2,758 established-method TIFF stacks even though they had the expected float format, dimensions, and 48 frames. Registration leaves `NaN` pixels at borders where shifted frames no longer overlap, but the auditor used whole-array minimum and maximum operations that become `NaN` when even one legitimate border pixel is blank.

## The broken pattern
```python
if array.ndim != 3 or not math.isfinite(float(array.min())):
    issues.append("invalid core TIFF")  # One blank border pixel rejects the stack.
```

## The fix
The auditor now permits `NaN` border pixels and entirely blank frames when an algorithm catastrophically shifts the content outside the canvas, while requiring a three-dimensional float stack with no positive or negative infinity. Tests cover valid blank borders, a blank failed frame, and infinity. Explicit unavailable marker files are counted separately from unscorable corrections that still produced a TIFF.

## Why it matters
Without the corrected rule, scientifically normal warp borders make valid correction stacks fail the final benchmark gate.
