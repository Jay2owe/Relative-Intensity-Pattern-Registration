# V3 format-integration gate rejected legitimate algorithm failures
**Date**: 2026-08-21
**Files changed**: `scripts/freeze_benchmark_v3_protocol.py`
**Guard**: guard comments in `audit_integration()` and full five-format integration replay

## What went wrong
The pre-freeze integration panel is designed to prove that every adapter can read each format, execute, and serialize a well-formed outcome; its performance is explicitly discarded. Three SIFT comparators executed correctly on the brightfield source but reported that there were too few feature points to estimate a transform. The gate treated any non-`ok` algorithm status as an infrastructure failure, which would incorrectly block protocol freeze even though publication analysis is designed to retain and penalty-score exactly these failures.

## The broken pattern
```python
if row.get("status") != "ok":
    raise RuntimeError("integration failure")
```

## The fix
The integration audit now accepts either `ok` or an explicit `failed...` algorithm outcome while still requiring every included method/input key. Empty, unknown, or malformed statuses remain fatal. Conformance self-tests continue to require all declared passing cases, and publication failures remain unfavorable outcomes.

## Why it matters
Requiring universal algorithm success on a format-smoke panel silently turns the integration gate into an extra performance eligibility screen. That would preferentially exclude less robust comparators instead of comparing them fairly and reporting their failure rates.
