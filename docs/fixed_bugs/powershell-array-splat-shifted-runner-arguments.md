# PowerShell array splat shifted runner arguments
**Date**: 2026-08-20
**Files changed**: `scripts/run_frozen_external_winners.ps1`
**Guard**: guard comments in `scripts/run_frozen_external_winners.ps1`

## What went wrong
The frozen runner stored apparent named parameters in an array and splatted that array into another
PowerShell script. PowerShell bound the values positionally, so the project path was passed to the
validated `Dataset` parameter and the run stopped before launching a benchmark.

## The broken pattern

```powershell
$arguments = @('-ProjectRoot', $project, '-Dataset', $oneDataset)
& $runner @arguments  # array splatting did not preserve named binding
```

## The fix

The child-script arguments are now stored in a hashtable and splatted by parameter name, including
explicit Boolean values for switch parameters.

## Why it matters
Reintroducing array splatting makes every frozen-winner evaluation fail at argument validation before
any data are produced.
