# Headless Fiji launcher detached before the benchmark
**Date**: 2026-08-17
**Files changed**: `scripts/run_full_benchmark_2026_08_16.ps1`
**Guard**: guard comments in `scripts/run_full_benchmark_2026_08_16.ps1`

## What went wrong
The external-method stage appeared to complete in about 0.04 seconds, but it produced none of the 12 expected Fiji method rows. On Windows, `ImageJ-win64.exe` started `javaw.exe` as a detached process and immediately returned success, so PowerShell recorded the launcher exit instead of waiting for the benchmark or capturing its errors.

## The broken pattern
```powershell
& $fiji --headless --console --run $script $parameters
# ImageJ-win64.exe returns before the detached javaw.exe finishes.
if ($LASTEXITCODE -ne 0) { throw 'external Fiji benchmark failed' }
```

## The fix
The runner now invokes the Java 11 console executable bundled with the same Fiji installation and supplies Fiji's launcher classpath directly. PowerShell therefore blocks until Fiji exits and receives the benchmark's real exit code, standard output, and standard error. Non-fatal Fiji warnings on standard error are captured without letting PowerShell 5 convert them into terminating shell errors; the Java exit code remains authoritative.

## Why it matters
If the detached launcher returns, a missing external comparison can be marked complete and the failure is discovered only much later when summaries count too few methods.
