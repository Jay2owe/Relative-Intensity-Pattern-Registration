# Native correction blank stability score
**Date**: 2026-08-14
**Files changed**: `src/test/java/logratio/ExternalPluginComparisonStacks.java`, `library/benchmark/mark_unscorable_native_outputs.ps1`
**Guard**: guard comments in `src/test/java/logratio/ExternalPluginComparisonStacks.java`

## What went wrong
Some external registration methods completed and wrote corrected image stacks, but moved the frames so far apart that less than a 32 by 32 pixel common field remained. The residual-movement scorer correctly returned no number, but the comparison table left a blank score while calling the run measured. That made the blank indistinguishable from missing or accidentally omitted evidence.

## The broken pattern
```java
NativeMotionProfiler.Residual residual = NativeMotionProfiler.residual(corrected, width);
result = new Result(method, solution, cpuMs, elapsedMs,
        residual.median, residual.p90, residual.maximum); // blank metric had no reason
```

## The fix
```java
String metricFailure = Double.isFinite(residual.median) ? null
        : "correction leaves less than 32 x 32 common finite overlap";
result = new Result(method, solution, cpuMs, elapsedMs,
        residual.median, residual.p90, residual.maximum, null, metricFailure);
```

The output writer now records the reason in both the comparison table and a method-specific `_unscorable.txt` file. Existing benchmark outputs are repaired by `mark_unscorable_native_outputs.ps1`.

## Why it matters
An extreme but completed correction must not look like absent data. If this distinction is lost, method-availability counts and natural-motion stability comparisons become misleading.
