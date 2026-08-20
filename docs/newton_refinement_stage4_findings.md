# Newton refinement, Stage 4 — the sealed set, opened a second time. It passes, and the caveat is the finding

Serves Stage 4 of `docs/newton_refinement_plan.md`. Run 2026-08-19. Gates written into
`src/test/java/logratio/NewtonSealedReport.java` before the set was opened; result in
`library/benchmark/v2/benchmarks/sealed_test/summaries/newton_stage4/GATE.md`.

## Superseded on 2026-08-20, and the replacement disagrees

**A third sealed test set was built later on 2026-08-19 and opened on 2026-08-20. The Newton
refinement failed its accuracy gate by five nanopixels; the model was withdrawn, and then reinstated
by an explicit decision of the project owner who judged the loss worth a quarter of the running
time.** Read `docs/newton_refinement_stage4_third_set_findings.md` instead of this page for the
shipping decision and for what the override does and does not entitle anyone to claim.

This page is kept because it is the record of a decision that was correct on the information
available, and because its own caveat — that a second reading of a spent set is optimistically biased
by an unmeasurable amount — turned out to be the whole story. On unseen material the same model
measured **0.000005 px worse** rather than 0.000302 px better. The direction of a difference this
small is noise, which is what the caveat said and what the clean set confirmed.

## Read this before the numbers

**This is not an independent validation, and no summary of it may say that it is.** The 40-recording
sealed set was opened once on 2026-08-18 and its numbers are published in
`docs/pairwise_estimator_axis_findings.md`. It validated the model that this one replaces. Reading it
again is a **second opening of a spent set**, and the result is optimistically biased by an amount
nobody can measure.

**How large is the bias, honestly?** Probably small. The set was opened once, its result accepted, and
nothing was tuned against it — this is not a case of a test set being iterated on. But "probably
small" is not "zero", and the difference this run is judging is 0.0003 px, which is smaller than most
things.

**Why it was read anyway rather than waiting.** The plan's own material check found the alternative
blocked: a third sealed set needs one new dense-fluorescence source, CC BY or CC0, with at least two
independent acquisitions, and there are **zero on disk and zero fetchable**. The choice was between
re-reading this set with the weakness declared, and leaving a finished, measured, retrained model
unvalidated indefinitely. Re-reading preserves the option of building a third set later and validating
properly then; folding the set into training, which was the other route considered, would have
destroyed that option permanently.

## The gate

| Gate | Limit | Measured | |
|---|---|---|---|
| Registration failures | 0 | 0 | pass |
| Control arm reproduces between runs | exact | **exact on every recording** | pass |
| Paired mean median, retrained over shipped | ≤ 1.000 | 0.023700 px over 0.024002 px = **0.9874** | pass |
| Image-type regression | 0.002 px | none | pass |
| Per-recording regression | 0.050 px | none | pass |
| Mean elapsed seconds, automatic arm | 2.200 | **1.192** | pass |

**GATE: PASS, all six.**

## What was run, and the control that makes it mean something

The question is *retrained against shipped*, so the sealed comparison was run **twice**, once with
each model compiled into its own clean private build, and the two `all_recordings.csv` files joined.
Comparing the automatic arm against the category recommendation instead would have measured the
selector rather than the change to it.

**The shipped run reproduces the first opening exactly**: mean median 0.024002 px and mean p90
0.040402 px, identical to the numbers published on 2026-08-18, with only the clock differing. That is
the control that says the two builds differ in the model and in nothing else — and it also confirms
that four months of changes to this tree have not moved the shipped selector by one digit.

The category recommendation, which no model touches, reproduces **exactly** between the two runs on
every one of the 40 recordings.

## Per image type, automatic arm

| Image type | Recordings | Shipped (px) | Retrained (px) | Difference |
|---|---|---|---|---|
| BRIGHTFIELD_DIC | 8 | 0.023358 | **0.021822** | **-0.001536** |
| DENSE_FLUOR | 8 | 0.030909 | 0.030909 | 0.000000 |
| FIDUCIAL_STATIC | 8 | 0.014802 | 0.014827 | +0.000026 |
| PHASE | 8 | 0.016669 | 0.016669 | 0.000000 |
| SPARSE_LOWLIGHT | 8 | 0.034270 | 0.034270 | 0.000000 |

**Three image types are bit-identical**, which is exactly right: the selector retains no candidate for
dense fluorescence, phase or sparse low-light and returns the category recommendation there, so
changing the estimator behind a candidate it does not hold cannot move them. That they are identical
to the last digit rather than nearly identical is a check that the model swap did what it was supposed
to and nothing else.

**Brightfield gains 0.0015 px. Fiducial loses 0.000026 px** — 26 nanopixels, seventy times inside the
tolerance, and the same order as the last bit of the arithmetic. The overall gain is small and it is a
gain.

**The runtime is where the result actually lives**: 1.192 s against 1.573 s per recording, a **24%
saving**, against a declared limit of 2.2 s.

## What was adopted

The retrained model is installed. `AutomaticRegistrationSelectorModel` now names
`AREA_CORRELATION_NEWTON` for brightfield/DIC and fiducial/static, keeping the same `FULL` band, the
same Gaussian 0.7 estimation filter and the same neutral support and mask as before — **only the
refinement changed**.

The outgoing model is kept at
`summaries/full_selector_sweep_v1/AutomaticRegistrationSelectorModel.before_newton_refinement.java.txt`,
so reverting is one file copy.

## What a reader should carry away, in order

1. **The speed is the result.** 24% off the automatic selector on the sealed set, 23% on the
   development set, and 55% off the area estimator itself. The accuracy change is within noise in
   both directions and should not be quoted as an improvement.
2. **The validation is weaker than the project's standard**, by construction, and the standard is not
   met until a third sealed set exists. If one is ever built, this model should be scored on it
   before anything is claimed about accuracy.
3. **The refinement is band-sensitive.** It fails outright when made to run under an intensity band
   that leaves a scattered valid mask — see `docs/newton_refinement_stage2_diagnosis.md`. The selector
   chose `FULL` and never meets that case, but anyone changing the retained band must re-measure.

## Where the evidence lives

| What | Where |
|---|---|
| Both runs and the gate | `library/benchmark/v2/benchmarks/sealed_test/summaries/newton_stage4/` |
| The gates, written before the opening | `src/test/java/logratio/NewtonSealedReport.java` |
| The first opening, untouched | `.../sealed_test/summaries/selector_comparison_v1/` and `..._first_opening_2026-08-18/` |
| The retrain that produced the model | `docs/newton_refinement_stage3_findings.md` |
| The outgoing model | `summaries/full_selector_sweep_v1/AutomaticRegistrationSelectorModel.before_newton_refinement.java.txt` |
