# PowerShell stripped a Maven property
**Date**: 2026-08-17
**Files changed**: `scripts/run_full_benchmark_2026_08_16.ps1`
**Guard**: guard comments in `scripts/run_full_benchmark_2026_08_16.ps1`

## What went wrong
The artifact audit passed with zero issues, but the full test step stopped before running any tests. PowerShell passed the unquoted Maven property `-Dmaven.buildNumber.skip=true` through the Windows batch wrapper as `.buildNumber.skip=true`, which Maven interpreted as an unknown lifecycle phase.

## The broken pattern
```powershell
& $maven -Dmaven.buildNumber.skip=true test
# The .cmd wrapper receives a damaged property argument.
```

## The fix
The Maven properties are now quoted PowerShell strings for both the test and package calls.

## Why it matters
If the property is damaged, Maven never reaches compilation or tests and the otherwise valid benchmark cannot clear its final build gate.
