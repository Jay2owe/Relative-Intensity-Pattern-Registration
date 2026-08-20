# PowerShell summary used a stale native exit code
**Date**: 2026-08-17
**Files changed**: `scripts/run_full_benchmark_2026_08_16.ps1`
**Guard**: guard comments in `scripts/run_full_benchmark_2026_08_16.ps1`

## What went wrong
The first controlled summary completed and wrote all expected rows, then the parent pipeline reported that it had failed. The parent checked `$LASTEXITCODE`, but a called PowerShell script does not set that native-process variable, so it still held an unrelated earlier value of 1.

## The broken pattern
```powershell
& $summaryScript
if ($LASTEXITCODE -ne 0) { throw 'summary failed' } # stale native exit code
```

## The fix
The runner now checks PowerShell's immediate success flag, `$?`, after each called PowerShell summary script. It continues to use `$LASTEXITCODE` after Python, Java, and Maven processes, which do set a native exit code.

## Why it matters
If the stale value is used, valid summary outputs are rejected and the benchmark stops before ranking and audit.
