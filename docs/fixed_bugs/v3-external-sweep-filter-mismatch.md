# V3 external sweep ignored the frozen filter set
**Date**: 2026-08-20
**Files changed**: `scripts/run_benchmark_v3_tuning.ps1`, `scripts/run_external_parameter_sweep.ps1`, `library/benchmark/run_external_full_no_dialog.groovy`, `src/test/java/logratio/ExternalPluginComparisonStacks.java`
**Guard**: guard comments in `scripts/run_benchmark_v3_tuning.ps1`, `scripts/run_external_parameter_sweep.ps1`, and `library/benchmark/run_external_full_no_dialog.groovy`

## What went wrong
The v3 external Fiji tuning route scored only the frozen five-source, four-condition, one-motion subset, but its launcher did not forward the source or motion filters and therefore executed the full development tree. In addition, an empty condition filter in sweep mode inherited the legacy v2 label `CLEAN`; v3 uses `U00` through `U11` plus dose labels, so an unfiltered publication sweep would have matched no recordings. A stopped Fiji JVM also left a partial `.csv` that could not be resumed safely.

## The broken pattern
```powershell
$parameters = @{
    Dataset = 'v3_development'
    OnlyCondition = $conditions
    # OnlySeries and OnlyMotion were omitted.
}
```

```groovy
condition?.trim() ? condition.trim() : 'CLEAN' // Wrong namespace for an unfiltered v3 sweep.
```

## The fix
The tuning launcher now forwards the exact source, condition, and motion filters used by all other runners. The Groovy bridge interprets an empty `sweep` condition as all manifest conditions and passes an optional motion filter into the Java input selector. Incomplete external outputs are moved to a timestamped `_partial/*.part` evidence file before the complete configuration is replayed.

## Why it matters
Reintroducing this bug would either waste roughly sixteen times the declared external tuning work or prevent every external publication comparator from running. A partial result could also be mistaken for a complete benchmark input during later discovery.
