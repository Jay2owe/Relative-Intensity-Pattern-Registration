# Newton refinement, Stage 3 — the retrained selector picks it, on both image types

Serves Stage 3 of `docs/newton_refinement_plan.md`. Run 2026-08-19. The candidate space went from 112
to 128; training was leave-one-source-series-out over the 80 development recordings, unchanged in
every other respect, and **read no sealed test set** — `FullSelectorTraining` never does.

## The gate, first

**PASS on all three conditions. The retrained model retains a Newton candidate on both image types
that retain anything at all, and it is better than the shipped model on accuracy and on time.**

| Stage 3 exit condition | Limit | Measured | |
|---|---|---|---|
| Held-out mean median | no worse than 0.020654 px | **0.020258 px** | pass |
| No per-recording guard breached | — | every image-type regression an improvement; no recording doubles or worsens by 0.05 px | pass |
| Runtime declaration met rather than re-declared | 2.2 s per 48-frame recording | **1.594 s** | pass |

**The retained candidates changed, which is what triggers Stage 4.**

## Shipped against retrained, under identical folds

| | Shipped, 112 candidates | Retrained, 128 candidates |
|---|---|---|
| Retained candidates | `AREA_CORRELATION` × 2 | **`AREA_CORRELATION_NEWTON` × 2** |
| Band, filter, support, mask | FULL, Gaussian 0.7, ALL, none | **identical** |
| Held-out mean median | 0.020654 px | **0.020258 px** |
| Held-out mean p90 | 0.086827 px | **0.086229 px** |
| Mean seconds | 2.078050 | **1.593737** |
| Brightfield/DIC against the category recommendation | -0.014203 px | **-0.015279 px** |
| Fiducial/static against the category recommendation | -0.010251 px | **-0.011155 px** |
| Model family chosen | IMAGE_TYPE_RULE | IMAGE_TYPE_RULE |
| Overrides | 32 of 80 | 32 of 80 |

**Only the refinement changed.** The retrained model keeps the same two image types, the same band,
the same estimation filter, the same neutral support and mask, and overrides on the same number of
recordings. It swapped the sub-pixel refinement and nothing else, which is exactly what a
single-variable axis is supposed to make possible.

**It is better on every number reported.** Not by much on accuracy — 0.0004 px on the held-out mean —
but by **0.48 seconds per 48-frame recording**, which is 23% of the shipped model's total runtime and
brings the automatic selector under the 1.804 s figure that no shipped model has met since the
estimator axis was adopted.

## Stage 2 failed and Stage 3 passed. That is not a contradiction, and the reason matters

Stage 2 compared the two refinements **at the category recommendation**, which is one fixed setting
per image type, and the Newton arm lost the fiducial median there by 0.001407 px.
`docs/newton_refinement_stage2_diagnosis.md` found why: the fiducial category recipe carries an
intensity band that excludes the brightest 25% of each frame, which on a bead field is a scatter of
holes, and the Gauss-Newton pass admits only 15% of the samples the grid admits through it.

**Stage 3 lets the selector choose the band, and it chose `FULL` — no band at all.** So the retrained
selector independently avoided the exact configuration that broke the refinement in Stage 2, not
because anything was tuned to make it do so, but because band and filter are swept dimensions and the
folds preferred the combination that works. The two stages are measuring different questions: Stage 2
asked "is this refinement better at the settings we happen to recommend", and the answer was no on one
image type; Stage 3 asked "is the best recipe using this refinement better than the best recipe
without it", and the answer is yes on both image types that use an area method at all.

**The honest reading is that the Newton refinement is band-sensitive**, that the sensitivity is now
understood and measured, and that the selector routes around it. That is weaker than "it is
unconditionally better" and it should not be written up as though it were.

## The one gate that reads FAIL, and why it is stale

`FullSelectorTraining` reports `mean_seconds 1.593737` against an internal constant
`GATE_MEAN_SECONDS = 1.479984` and prints `gates FAIL`. **That constant predates the estimator axis
and the currently shipped model fails it by more**: regenerating the 112-candidate baseline under
identical folds gives **2.078050 s** against the same 1.479984 limit.

The gate that actually governs Stage 3 is the one in
`docs/pairwise_estimator_axis_runtime_declaration.md` — *"Declared limit: a mean of 2.2 seconds per
48-frame recording"* — which the plan requires be "met rather than re-declared". 1.594 s meets it with
0.6 s to spare, and improves on the shipped model's 2.078 s. **The stale constant should be raised to
the declared 2.2 s or removed**, but not as part of this stage, and not while holding results.

## Two pieces of process worth recording

**The frozen 112-recipe sweep was not re-run.** Every candidate identifier carries the estimator name,
so the sixteen new Newton candidates are sixteen new folders beside the existing 112. The extension
run reports it exactly: `attempted 10240, completed 1280, resumed 8960, failed 0, structural errors
0`. The 8,960 registrations the frozen sweep already held were skipped, not recomputed.

**Running the training rewrites the shipped model source, and there is no dry-run flag.**
`FullSelectorTraining.writeModelSource` overwrites
`src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` unconditionally. It was backed up
before the run and **restored afterwards**, verified by checksum: the shipped model is the 112-candidate
one and names no Newton candidate. The retrained model is kept as text at
`summaries/full_selector_sweep_v1/newton_stage3_128/AutomaticRegistrationSelectorModel.retrained_128.java.txt`,
where it cannot compile into anything.

**It also overwrote the 112-candidate training record**, which was not anticipated:
`stage4_validation.csv`, `stage4_fold_results.csv`, `SELECTOR_TRAINING.md` and `FINDINGS.md` are
outputs of whichever training ran last. They were regenerated from the untouched per-recording sweep
data by re-running the training over a 112-candidate space, and the regenerated numbers reproduce the
published ones exactly — 0.020654 px and 2.078050 s, matching
`docs/pairwise_estimator_axis_runtime_declaration.md` to the last digit, which is itself a useful
check that nothing in the tree has drifted. The 128-candidate outputs live in
`newton_stage3_128/`. **Anyone running this training again should copy those four files first.**

## What this unblocks and what it does not

**Stage 4 is now required and it is the blocked one.** The retained candidates changed, so by the
plan's own terms the model cannot ship on the evidence of the sealed set that validated its
predecessor. `docs/newton_refinement_plan.md`'s material check found the blocker: a third sealed set
needs one new dense-fluorescence source with at least two independent acquisitions, and there are
zero on disk and zero fetchable.

**Nothing has shipped.** The shipped model is byte-identical to what it was this morning, it names no
Newton candidate, and 307 tests pass.

## Where the evidence lives

| What | Where |
|---|---|
| The 128-candidate training, its folds and its model | `library/benchmark/v2/benchmarks/controlled_motion/summaries/full_selector_sweep_v1/newton_stage3_128/` |
| The restored 112-candidate baseline | the same folder's `stage4_validation.csv` and `stage4_fold_results.csv` |
| The extended sweep, 128 folders per recording | `.../controlled_motion/*/*/*/*/full_selector_sweep_v1/` |
| Why Stage 2 failed where this passed | `docs/newton_refinement_stage2_diagnosis.md` |
| The runtime limit this is held to | `docs/pairwise_estimator_axis_runtime_declaration.md` |
