# Notice for whoever is running Newton Stage 4

Written 2026-08-19, for the person or agent holding `docs/newton_refinement_plan.md`.

## Outcome, added 2026-08-20 by the person this was addressed to

**Done, and thank you — the set was opened once, on a byte-identical model, and the Newton
refinement failed its accuracy gate by a ratio of 1.000012 against a limit of 1.000.** Five
nanopixels on a mean of 0.415 px, with every other gate passing including a 24.4% time saving. The
previous model was restored, as the rule requires — and then reinstated the same day by an explicit
decision from the project owner, who judged five nanopixels a fair price for a quarter of the running
time. **That is an override of the gate, recorded as one; it is not a pass.** Nothing was tuned and
the set was not re-read. Full record in `docs/newton_refinement_stage4_third_set_findings.md`, which also writes down
the one thing this set said that matters more than the verdict: `phase_strack_pveronii_03` registers
eight pixels out on two of its four movement paths, on every arm, for both models.

## In one line

**A third sealed test set now exists, built and unread, so the Stage 4 result you already have —
which your own write-up correctly calls "not an independent validation" — can be replaced with a
clean held-out number by re-running the same comparison against different material.**

## Your Stage 4 result stands, and it does not have to be the final one

`docs/newton_refinement_plan.md` records Stage 4 as passed on 2026-08-19, on a second opening of
the spent set, with the deviation declared plainly: optimistically biased by an unmeasurable
amount, and "the project's standard is not met until a third set exists". The justification was
one premise:

> the material check in the plan found zero unused dense-fluorescence sources on disk and zero
> fetchable, so a genuinely third set cannot be built today

**A third set was built later the same day.** Dense fluorescence was cleared by a source hunt, a
second blocker in sparse low-light was found and cleared by another, and
`SealedTestSet3Builder` produced 40 controlled recordings and 6 natural stacks with zero failures.

**Does the earlier spent-set reading contaminate the new one? No — provided nothing changed in
between.** The concern with reading a spent set first is that its result might inform a choice
before the clean set is read. The plan already forbids that: nothing scored at Stage 4 may change
a coefficient, a threshold, a feature, a candidate or the estimator. So long as the model you
score on `sealed_test_3` is byte-identical to the one you scored on `sealed_test`, the new number
is a genuine held-out result and the old one becomes corroboration. **If anything has changed
since, say so, because then it is not.**

## Two errors in the plan's material check — I made the same one

Your table under "The material check, done 2026-08-19" overstates two rows. I reproduced the
error independently before finding it, so this is a correction rather than a criticism.

| Image type | Plan says | Actually |
|---|---|---|
| SPARSE_LOWLIGHT | **2** — `ssbd131_worm2`, `ssbd474_root_hair` | **0** — both already read by the natural-motion benchmark |
| BRIGHTFIELD_DIC | **4** — including `microbundle_type3_04` | **3** — `type3_04` already read by the natural-motion benchmark |

**Why both counts missed it.** Most benchmarks are laid out `benchmark / image class / series`, so
listing one level below the image class gives series names. **The natural-motion benchmark is one
level deeper** — `natural_motion / image class / motion category / series` — so the same listing
returns motion categories like `STATIC__NEAR_STATIC` and never reaches the series. Four series are
invisible to a count that assumes a uniform layout.

`SealedTestSetBuilder.spentSeriesNames` walks to depth 3 and does see them, so a build on that
material would have failed loudly rather than quietly producing an unsealed set. It has also been
rewritten to discover benchmark roots by listing the directory instead of from a hard-coded array,
which had omitted `sealed_test` and would have let a successor re-spend the second set's material.
`SpentMaterialGuardTest` pins all of it.

**Your instinct about the sparse pair was right, too.** The plan says of `ssbd131_worm2` and
`ssbd474_root_hair`: "Neither has been through the seed-plane check … Do it before counting on
them." Done, and they fail it anyway. Both are 8-bit with no 16-bit original behind them — the
SSBD 474 CZI reports `PixelType Gray8` in its own metadata — and `ssbd131_worm2` distributes
frames named `aligned_*`, meaning the series has already been registered and could never support
a natural-motion arm.

## What you now have

- **`library/benchmark/v2/benchmarks/sealed_test_3/`** — 40 controlled recordings, ten series, two
  per image type, four movement paths each. 258 MB.
- **`…/sealed_test_3_natural/`** — 6 natural-motion stacks, 55 MB. Four sources deliberately have
  no natural arm; each one's manifest note says why.
- **`…/summaries/sealed_test_3_manifest.csv`** — ten independent groups, seed paths, caveats, and
  a SHA256 per curved-drift input. All ten differ, which is the check that two series did not
  accidentally seed from one file.
- **`SealedTest3Report`** — gates written before the set was opened, with `SealedTest3GatesTest`
  pinning the limits and proving each one fails when it should.
- **`docs/third_sealed_set_material.md`** — what the set is made of, what was rejected and why.

Its ten sources: two new dense fluorescence records, two new sparse single-molecule records, the
`d3` and `d4` commercial bead acquisitions, the third Strack acquisition of two strains, and
`microbundle_type3_06` with an untouched BBBC028 shape. **No independent group in it appears in
any development, locked or previously sealed series.**

## How to run it

```
java -Dlogratio.comparisonRoot=library/benchmark/v2/benchmarks/sealed_test_3 \
     -Dlogratio.rewrite=true -Dlogratio.noImages=true \
     logratio.SelectorComparisonBenchmark <project>      # once per model, private build
java logratio.SealedTest3Report <project> <baseline.csv> <candidate.csv>
```

Same two-run shipped-versus-retrained shape `NewtonSealedReport` already uses, so the arms and the
join are familiar. **Recompile the main classes first** — your own warning, and it still applies.

Keep `NewtonSealedReport` rather than deleting it: it is the record of a decision that was correct
on the information available. `SealedTest3Report` points here for why it exists alongside.

## Three things about this set that will surprise you if nobody says them

1. **Two of the ten series are much larger than anything in the previous sets.** The ChromaLIVE
   dense record is 1900x1900 and the BBBC028 ring record is 1376x1032, against the 512x512 typical
   elsewhere. **This is why `SealedTest3Report` gates elapsed time as a ratio against the baseline
   rather than as an absolute 2.2 seconds.** Carrying the old absolute across would have failed
   this set on frame size, and would have failed it identically for a model that had not changed
   at all. Your measured 1.192 s does not transfer to this material; the ratio does.

2. **The dense SCN record fades.** 311 frames over 6.5 days of living tissue, signal down about 40
   percent across the recording, seed taken from the first frame. A feature for a gain-invariant
   estimator rather than a defect, but it profiled as `OSCILLATING__MODERATE` where every other
   natural stack in the set came out `STATIC__NEAR_STATIC`.

3. **The sparse pair are dual-view frames** — 512x253 holding donor beside acceptor from a beam
   splitter. They seed controlled motion only: a centred crop straddles the seam, and the seam is
   fixed to the camera rather than the specimen, so it would not drift with the sample and would
   anchor a stability measurement toward reporting no movement. They are, however, **16-bit with a
   real camera offset** — the first sparse records in this project not distributed at 8 bits.

## The rule that has not changed, now enforced

Gates first, open once, and nothing scored may change a coefficient, a threshold, a feature, a
candidate or the estimator. `SealedTest3Report` enforces the "once" as well as stating it: if
`sealed_test_3_gates.csv` already exists it refuses to run, and `-Dlogratio.reopenSealedSet=true`
is the only way past — which leaves a deliberate mark in the command history of whoever reopened
it.

**A fourth set would need two more source hunts.** Dense fluorescence and sparse low-light are
back to zero unused groups now that this set has claimed theirs. Phase has twelve unused Strack
acquisitions and brightfield a handful, but those are the easy two. Spend this set on the question
that matters.
