# V3 configuration freeze used an undefined digest name
**Date**: 2026-08-21
**Files changed**: `scripts/freeze_benchmark_v3_configs.py`
**Guard**: guard comments in `scripts/freeze_benchmark_v3_configs.py` and full configuration-freeze replay

## What went wrong
The development tuning sweep completed and the configuration freezer successfully scored every candidate and wrote `winners.csv`. It then crashed while creating the primary-comparator audit because `main()` had calculated the result-set digest as `digest`, but the later output dictionaries referred to the nonexistent local name `input_digest`. The unattended supervisor therefore stopped safely before integration, protocol freeze, or publication-test generation.

## The broken pattern
```python
digest = hashlib.sha256(...).hexdigest()

comparator_rows.append({
    "input_sha256": input_digest,  # This name exists only as a parameter of score().
})
```

## The fix
```python
comparator_rows.append({
    "input_sha256": digest,
})
```
Both selection audit files now reuse the result-set digest calculated in `main()`.

## Why it matters
If this typo returns, a complete multi-hour tuning run cannot advance to the protocol-freeze gate even though its selected winners are valid. Failing before protocol freeze is safe, but it unnecessarily stops the unattended publication pipeline.
