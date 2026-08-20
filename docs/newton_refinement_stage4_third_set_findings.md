# Newton refinement, Stage 4 done properly — the third sealed set, and it fails by five nanopixels

Serves Stage 4 of `docs/newton_refinement_plan.md`, re-run on genuinely unread material after
`docs/newton_stage4_notice.md` reported that a third sealed set had been built. Run 2026-08-20.
Gates written into `src/test/java/logratio/SealedTest3Report.java` before the set was opened, by
someone who had not seen a number from it; result in
`library/benchmark/v2/benchmarks/sealed_test_3/summaries/sealed_test_3_gates.csv`.

## The result

**GATE: FAIL, on one condition of eleven — and the failure was overridden. The Newton refinement
ships in the automatic selector by an explicit decision of the project owner, taken on 2026-08-20
after the gate's verdict was reported to them in full.**

**Nothing in this document may be quoted as a passing sealed-set result, because it is not one.**

The failure is a mean median error of **0.415153 px against a baseline of 0.415148 px** — a ratio of
**1.000012** against a declared limit of 1.000. Five nanopixels on a set whose mean error is four
tenths of a pixel. Every other gate passed, including a **24.4% saving in elapsed time** against a
limit of no slowdown at all.

| Gate | Limit | Measured | |
|---|---|---|---|
| Recordings scored | 40 | 40 | pass |
| Series scored | 10 | 10 | pass |
| Image types scored | 5 | 5 | pass |
| Series per image type | 2 | 2 | pass |
| Registration failures | 0 | 0 | pass |
| Control arm reproduces between runs | exact | exact on all 40 | pass |
| **Mean median ratio** | **≤ 1.000000** | **1.000012** | **FAIL** |
| Image-type regression, all five | 0.002 px | worst +0.000150 px | pass |
| Per-recording regression | 0.050 px | worst +0.005344 px | pass |
| Mean seconds ratio | ≤ 1.000000 | 0.755905 | pass |

## What the rule says, what was done, and what was then decided

`docs/newton_refinement_plan.md` and `SealedTest3Report`'s own javadoc say the same thing in the same
words: **a failure restores the previous model; it does not start a round of tuning.** That is what
happened first, and it happened before the result was discussed with anyone:

- `AutomaticRegistrationSelectorModel.java` was restored from
  `summaries/full_selector_sweep_v1/AutomaticRegistrationSelectorModel.before_newton_refinement.java.txt`
  and verified by checksum — md5 `dd7b3a3b5738dc26b193a056e5b7ea55`, the same file scored as the
  baseline arm in this very comparison — and the whole tree, documents included, was brought into line
  with the withdrawal.
- **Nothing was tuned, no threshold was moved, and the set was read once.** `SealedTest3Report`
  refuses a second reading unless `-Dlogratio.reopenSealedSet=true` is passed; it never has been.
  That remains true and is the part that matters most: **the number above is what the set said, and
  it was not negotiated with.**

**Then the owner overrode it.** Told the verdict, the size of the loss and the cost of overriding,
they judged five nanopixels a fair price for a quarter of the running time and asked for the faster
refinement to be the automatic choice. So:

- `AutomaticRegistrationSelectorModel.java` is the Stage 3 retrained model, md5
  `a4fea62ffd9b0f11d68227bc80f6c492`, naming `AREA_CORRELATION_NEWTON` for brightfield/DIC and
  fiducial/static. Same band, same estimation filter, same support and mask — only the refinement.
- **The override is a decision, not a measurement, and it is recorded as one everywhere the value is
  described.** `PairEstimator.AREA_CORRELATION_NEWTON`'s javadoc leads with it; so does `README.md`
  and the plan's Stage 4 status.
- **Reverting is one file copy**, back to `...before_newton_refinement.java.txt`. Anyone who prefers
  the gate's verdict to the owner's needs nothing from this document but that path.

### Why the override is defensible, and where it is not

**Defensible**: the gate is a strict inequality against exactly 1.000 with no declared noise band, on
a quantity that this change alters by parts per million in a direction decided by which of sixteen
recordings rounds which way. Ten worsened, six improved. The same model measured *better* on the 80
development recordings and *better* on the second sealed set. Three readings, two favourable, one
unfavourable, all three inside the noise — that is a fair description of a neutral change, and
neutral changes that save 24% of the time are worth taking.

**Not defensible**: as evidence. A gate you overrule after seeing the number gives you no protection
at all, and this one now protects nothing. **The honest position is that the Newton refinement's
accuracy is unvalidated on unseen material** — not validated, not refuted. If that matters for a
publication or for a user who cares more about the last digit than the clock, the answer is the
grid refinement, which is still there under `estimator=area_correlation` and is still what every
number published before 2026-08-19 was measured with.

## The comparison, and why the model was byte-identical to the one Stage 4 scored

Two clean private builds differing in one file, each scored over all 40 recordings, and the two
`all_recordings.csv` joined per recording — the same shape `NewtonSealedReport` used on the second
set, so the arms and the join are the ones already reviewed.

The notice set one precondition for the new number to count as genuinely held out: the model scored
here must be byte-identical to the model scored on the spent set. **It is** — md5
`a4fea62ffd9b0f11d68227bc80f6c492`, matching
`newton_stage3_128/AutomaticRegistrationSelectorModel.retrained_128.java.txt` exactly. The only
edits to `src/main` between the two openings were a javadoc block and one dialog help string, neither
of which is executed. So this is a first reading of unseen material, and the earlier spent-set pass
is corroboration of nothing that changed since.

**The control holds.** The category recommendation, which no model touches, reproduces to the last
digit on every one of the 40 recordings, so the two builds differ in the model and in nothing else.

## Where the difference actually is

Twenty-four of the forty recordings are **bit-identical** between the two models — dense
fluorescence, phase and sparse low-light, where the selector retains no candidate and returns the
category recommendation. The change can only reach the sixteen brightfield and fiducial recordings.

| Image type | Baseline (px) | Candidate (px) | Difference | Seconds |
|---|---|---|---|---|
| BRIGHTFIELD_DIC | 0.010268 | **0.010143** | **-0.000125** | 3.561 → 1.778, **50% faster** |
| FIDUCIAL_STATIC | 0.008484 | 0.008634 | **+0.000149** | 1.242 → 0.783, **37% faster** |
| DENSE_FLUOR | 0.021378 | 0.021378 | 0.000000 | unchanged code |
| PHASE | 2.002982 | 2.002982 | 0.000000 | unchanged code |
| SPARSE_LOWLIGHT | 0.032627 | 0.032627 | 0.000000 | unchanged code |

On those sixteen recordings, **ten worsened and six improved**; the largest single move in each
direction is +0.005344 px and -0.006280 px, both on the same brightfield series. Brightfield gains
what fiducial loses, plus five nanopixels. **This is a coin landing on its edge, and the gate said
the edge counts as tails.**

## The honest reading of the timing, which is less precise than it looks

The 24.4% overall saving is carried almost entirely by brightfield/DIC: of the 0.375 s mean
difference, brightfield contributes 0.357 s. **The three image types whose arithmetic did not change
at all still moved in time** — dense fluorescence timed 24.7% *slower* in the candidate run, phase
10.6% faster, sparse 3% slower — which puts the run-to-run noise floor at roughly ±25% on a
per-image-type mean of eight recordings on this machine.

So the correct claim is not "24.4%, measured". It is: **the two image types where the refinement
actually runs came out 50% and 37% faster, in the same direction, on material chosen by nobody**,
and that agrees with the 55% saving Stage 2 measured on the estimator itself over the 80 development
recordings. The saving is real. Its third digit is not.

## A finding about this project that has nothing to do with Newton

**One of the two new phase sources registers catastrophically, on every arm, for both models.**
`phase_strack_pveronii_03` under steady directional drift and under curved oscillating drift comes
out at **8.21 px and 7.70 px** median error. Its two other movement paths are fine at 0.014 and
0.035 px, and the other new phase series is fine on all four.

| Arm | Steady drift | Curved oscillating |
|---|---|---|
| Current category recommendation | 8.2106 | 7.6962 |
| Older automatic information selector | 8.2106 | 7.7872 |
| Current automatic filter-and-mask selector | **2.8215** | **6.2342** |
| New full automatic selector (shipped) | 8.2106 | 7.6962 |
| Base model, no automatic change | 7.6929 | 7.6962 |

Those two recordings are what makes this set's mean error 0.415 px where the second sealed set's was
0.024 px. **Nothing here is caused by the estimator change** — both models produce identical numbers
on all eight phase recordings — but it is the most important thing the set said, and it was said
once, so it is written down here rather than re-measured later. A recording where every arm lands
eight pixels out is a failure of the pipeline, not of a tie-break between two refinements.

**Do not chase this on `sealed_test_3`.** The set is spent. If it is worth investigating, the
material to investigate it on is the third Strack acquisition in
`library/benchmark/v2/supplementary_series/phase_strack_pveronii_03/`, from which these recordings
were seeded, used as a development recording under a new name.

## What this says about the gate, which is a lesson rather than an appeal

`GATE_MEAN_RATIO = 1.0` has **no declared noise band**. For a change whose accuracy is neutral by
construction — a different way of finding the same peak on the same surface — the measured ratio was
always going to land within a few parts per million of one, and which side it lands is decided by
which of sixteen recordings happens to round which way.

**That is not grounds for re-reading the set, and it has not been used as any.** A gate whose verdict
you renegotiate after seeing it is not a gate, and the discipline is worth more than this feature.
But anyone writing the next one should write the band in: *"the ratio must not exceed 1.000, or must
not exceed 1.002 with the per-image-type and per-recording gates both passing"* would have expressed
the same intent — no buying speed with accuracy — without turning a neutral result into a coin flip.
That sentence has to be written **before** a set is opened, by someone who has not seen a number
from it, which is exactly the position the next person will be in.

## If this is ever revisited

A fourth sealed set is the only clean route, and `docs/newton_stage4_notice.md` counts what that
costs: dense fluorescence and sparse low-light are back to zero unused groups, so it needs two more
source hunts. **Spend that on a question worth more than 24% of the area estimator's time on two
image types.** The `phase_strack_pveronii_03` failure above is a better candidate.

What a fourth set would settle is whether the accuracy really is neutral, which is now the only open
question about this value and the one the override left open. Until then the development-set evidence
stands and is unaffected — 0.020258 px against 0.020654 px held-out, at 1.594 s against 2.078 s — and
`estimator=area_correlation` remains available to anyone who wants the refinement every published
number was measured with.

## Where the evidence lives

| What | Where |
|---|---|
| The gate, and the paired rows behind it | `library/benchmark/v2/benchmarks/sealed_test_3/summaries/sealed_test_3_gates.csv`, `..._paired_rows.csv` |
| Both runs, whole | `.../sealed_test_3/summaries/newton_third_set/` |
| The gates, written before the opening | `src/test/java/logratio/SealedTest3Report.java`, pinned by `SealedTest3GatesTest` |
| What the set is made of | `docs/third_sealed_set_material.md`, `.../summaries/sealed_test_3_manifest.csv` |
| The spent-set reading this supersedes | `docs/newton_refinement_stage4_findings.md` |
| The notice that made this run possible | `docs/newton_stage4_notice.md` |
| The retrained model, kept as text and not compiled | `summaries/full_selector_sweep_v1/newton_stage3_128/AutomaticRegistrationSelectorModel.retrained_128.java.txt` |
