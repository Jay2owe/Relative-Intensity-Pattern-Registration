# Dropbox status lock aborted benchmark orchestration
**Date**: 2026-08-20
**Files changed**: `scripts/run_external_parameter_sweep.ps1`
**Guard**: guard comments in `scripts/run_external_parameter_sweep.ps1`

## What went wrong
A completed benchmark sequence aborted between jobs when Dropbox held the shared `stage_status.log`
open. The status file was only bookkeeping, but an `Add-Content` exception was treated like a failed
scientific computation.

## The broken pattern

```powershell
"COMPLETE $Dataset $stem" | Add-Content -LiteralPath $status
# Any transient file lock terminated the whole runner.
```

## The fix

Status logs now live inside each dataset output directory. Writes retry briefly and degrade to a
warning if the file remains locked; JVM exit failures and result-CSV errors are still fatal.

## Why it matters
Without the guard, an unrelated sync lock can discard orchestration progress or force expensive,
unnecessary reruns even when the benchmark results themselves are valid.
