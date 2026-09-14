# V3 benchmark toolchain was stored under disposable build output
**Date**: 2026-08-21
**Files changed**: `scripts/run_benchmark_v3.ps1`, `scripts/run_benchmark_v3_frozen_methods.ps1`, `scripts/run_benchmark_v3_tuning.ps1`
**Guard**: guard comments in all three benchmark launchers and integration replay with the durable toolchain

## What went wrong
Development tuning completed successfully, but the integration JNormCorre route stopped before launch because every benchmark runner validated a Maven executable and dependency-classpath file below `target/thevenaz-protocol`. Maven's `target` directory is disposable build output; a later clean removed that directory, including the bootstrapped toolchain. The failure was environmental and occurred before protocol freeze or publication-test generation.

## The broken pattern
```powershell
$maven = Join-Path $project 'target\thevenaz-protocol\tools\apache-maven-3.9.9\bin\mvn.cmd'
$classpathFile = Join-Path $project 'target\thevenaz-protocol\test-classpath.txt'
# Both dependencies disappear when target/ is cleaned.
```

## The fix
All v3 launchers now resolve Maven and the generated test dependency classpath under `%LOCALAPPDATA%\LogRatioBenchmarkV3\tools`, beside the benchmark's isolated Python environments. The existing verified Maven distribution was copied there and the classpath was regenerated from `pom.xml`.

## Why it matters
If runtime dependencies are placed under `target`, any normal clean build can stop a multi-day resumable benchmark between stages. A durable tool cache lets later integration and frozen publication runs reuse the exact same environment.
