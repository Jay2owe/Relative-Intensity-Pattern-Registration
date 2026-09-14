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

## D3: first finalization rewrote the unchanged freeze table

The development winners were first frozen at 2026-08-20 19:22:15 BST, before the corrected locked
default run began at 19:22:38 BST. The first invocation of the final summarizer later recomputed the
same winner identities and settings and overwrote `frozen_winners.csv`, changing its timestamp and
`frozen_utc` fields after locked execution. No winner changed, but this weakened the file-level audit
trail.

The observed original freeze time was restored to the winner rows, and a SHA-256 sidecar was added.
Final mode now reads the frozen table without recomputing it and refuses to proceed unless it matches
that sidecar. This deviation is reported in the generated findings.

## D4: sparse/low-light removed after result review

Status: owner-directed scope change on 2026-08-21, after the version 2 development and locked results
had been reviewed.

The locked sparse/low-light inputs were invalid. Their red-green-blue source contained signal in the
green channel, while the version 2 generator selected the blue channel and produced all-zero stacks.
Some methods failed explicitly; identity transforms from the in-house method were instead counted as
successful. Those rows cannot support either a win or a loss.

The owner removed the entire sparse/low-light class from the active comparison and publication scope.
The canonical defaults-against-defaults and tuned-against-tuned tables now contain four classes and an
aggregate calculated only from those classes. This decision was made after results were visible and is
therefore disclosed rather than described as prospective. Valid version 2 sparse development data had
also favoured Descriptor-based registration, so no claim is made that the in-house method wins this
excluded class.

Raw runs and the immutable five-class winner freeze are retained for audit. Reintroduction requires a
new prospective protocol, correct channel selection and regenerated inputs; it must not reuse the
invalid locked rows.
