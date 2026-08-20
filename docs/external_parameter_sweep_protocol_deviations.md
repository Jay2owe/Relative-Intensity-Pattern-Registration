# External parameter sweep: protocol deviations

Recorded 2026-08-20 while the development sweep was still running and before winners were frozen.

## D1: pre-existing locked summaries were inspected

During Stage 0 orchestration, the agent read the already-existing
`locked_test/summaries/external_comparison_v1/method_summary.csv` and counted the existing locked input
files to understand available arms and expected row counts. This violated the sealed-set declaration's
literal instruction that the workflow "must not read" `locked_test` before freezing winners.

No locked image stack was opened, no corrected external default or candidate configuration was run on
locked data, and no candidate axis or winner is selected from those values. The candidate registry was
already fixed by the written plan and installed-plugin bytecode. `frozen_winners.csv` is computed only
from the 80 development recordings. Nevertheless, the locked evaluation is not claimed to be blinded;
it is an already-open holdout with a development-only parameter selection rule.

Neither `sealed_test` nor `sealed_test_3` data or results were opened. They remain excluded.

## D2: installed Descriptor optimization is not exactly replayable

The plan's Stage 1 gate requires every default row to replay at exact zero. Installed mpicbg 1.6.0 uses
RANSAC and a concurrent tile optimizer whose ordering depends on JVM object identity. Resetting its
documented seed, resetting `Collections.shuffle`, and a one-active-processor probe did not remove the
variation. The main run therefore retains the installed algorithm and reports Descriptor's replay
spread. The other eleven external rows remain subject to the exact-zero gate.

Faking exactness by copying the baseline row, disabling the installed global optimizer, or silently
rounding the difference was rejected because each would make the gate look stronger than the run.
