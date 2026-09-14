# Finalizer rewrote frozen winners
**Date**: 2026-08-20
**Files changed**: `scripts/summarize_external_parameter_sweep.py`
**Guard**: guard comments and SHA-256 check in `scripts/summarize_external_parameter_sweep.py`

## What went wrong
The first final-mode summary call invoked the same function used by freeze mode. That function selected
the same 30 winners again and rewrote `frozen_winners.csv` after locked evaluation, weakening the audit
trail even though no winner identity changed.

## The broken pattern

```python
_, winners = freeze_winners(run_root)  # also ran in final mode
if args.stage == "freeze":
    return
```

## The fix

Only freeze mode may call `freeze_winners`. Freeze mode writes a SHA-256 sidecar; final mode reads the
existing winner table and refuses to continue unless its hash matches the sidecar.

## Why it matters
Recomputing a development-derived selection after opening a holdout makes it impossible to prove from
the artifacts that test results did not influence the published configuration.
