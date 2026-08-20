# Why the Newton refinement lost the fiducial tail — and what was left after fixing it

Run 2026-08-19, after Stage 2 of `docs/newton_refinement_plan.md` failed. Read
`docs/newton_refinement_stage2_findings.md` first; this is the follow-up that asks *why*, and it
changes the conclusion of that document in one important way and leaves it standing in another.

## The answer in one line

**It was not the method. It was my implementation's validity rule, and on two fiducial recordings the
refinement never ran at all: 209 pairs out of 209 returned the integer offset the sweep had already
found.** Fixing it removed the entire 9x tail — fiducial's worst recording went from 0.321317 px to
**0.034827 px, which is the grid's own number to the last digit**. The gate still fails, on a much
smaller and now genuinely methodological margin.

## What the plan expected, and why that was wrong

The plan's risk 1, named before Stage 0: the correlation surface is only piecewise smooth, and as the
two frames slide apart the set of pixels valid in both changes in steps, so a derivative method
computes the slope of a function that has a step in it.

**That is not what happened, and one number refutes it.** The Newton arm is already 0.17 px wrong at
frame 5 of `fiducial_cage_d4`, where the total drift is 1.25 px on a 96-pixel frame. Nothing has slid
anywhere. The error then fluctuates between 0.17 and 0.58 px rather than growing with the separation,
which is not what an overlap effect looks like.

## What actually happened

`research/newton_stage2_diagnosis/NewtonStage2Diagnosis.java` runs the real frames of the failing
recordings through both refinements, with the recipe's own intensity band on and then off. The whole
finding is in two rows:

```
fiducial_cage_d4, STEADY_DIRECTIONAL_DRIFT, 209 pairs over lags 1..16

  band, exclude above 75  (what the arm ran)     plane valid fraction 0.750
    grid mean 0.0586 px   newton mean 0.4382 px   7.48x   unrefined 209/209
    samples admitted at a half-pixel offset: grid rule 3332, newton rule 502  -> newton keeps 15.1%

  no band, every pixel valid                     plane valid fraction 1.000
    grid mean 0.0137 px   newton mean 0.0140 px   1.02x   unrefined   0/209
    samples admitted at a half-pixel offset: grid rule 8843, newton rule 8560  -> newton keeps 96.8%
```

**`unrefined 209/209`.** Every pair. The refinement reported zero iterations, which means its first
surface pass was never usable and it returned the offset the integer sweep gave it. The worst errors
observed were 0.5590 and 0.7071 px — and 0.7071 is sqrt(2)/2, the greatest distance any point can be
from the nearest whole pixel. Those are not the errors of a refinement that converged badly; they are
the errors of no refinement at all.

**Why the surface was never usable.** The fiducial recipe carries an intensity band that excludes the
brightest 25% of each frame. On a bead field that is not a border, it is a scatter of holes wherever
the beads are. The grid scores through `Window.sample`, which tries the sixteen-tap cubic and **falls
back to bilinear** where the four-by-four neighbourhood is incomplete — four valid pixels are enough.
The Gauss-Newton pass refuses those positions outright, because a gradient assembled from two
different interpolators describes neither. So the two arms do not see the same pixels: the Newton pass
admits **15% of what the grid admits**, which is about 5% of the frame — below the estimator's own
`minValidFraction` of 10%, so `surface` declared itself invalid and the refinement declined to start.

**This is the ragged validity boundary Stage 0 quantified, arriving by a different door.** Stage 0
looked for it at the frame edge as the frames slide apart. It came from the intensity band instead,
which is a setting of the recipe, and it is far more aggressive there than anything the frame edge
does.

**One claim in the Stage 1 record has to be weakened because of this.** That document says arms 3 and
4 differ in the refinement and in nothing else. They also differ in which samples they admit, and on a
scattered mask that difference is large. The refinement is the only thing switched, but it is not the
only thing that changes.

## The fix, and what it is worth

The plan left this open in as many words: *"the prototype falls back to stopping when the Hessian is
not positive definite; whether that is the right fallback is a Stage 2 question."* Stage 2 answered
it. Stopping means handing back a whole-pixel answer and calling it a refinement.

So `newtonRefine` now **hands back to the grid whenever it took no step** — an unusable first surface,
a Hessian that is not positive definite, or a line search that rejected every trial. It never returns
the integer offset.

Re-running Stage 2 against the **unmodified** gate constants:

| | First run (stop) | With the fallback | Grid, for reference |
|---|---|---|---|
| Fiducial worst recording | 0.321317 px | **0.034827 px** | 0.034827 px |
| Pooled mean median | 0.273726 px | **0.266998 px** | 0.266940 px |
| Mean processor seconds | 7.792 | **7.821** | 17.388 |
| Processor time saved | 54.66% | **55.02%** | |

The tail is gone and it cost nothing measurable in speed — the fallback fires only where the Newton
pass could not start, and those pairs were producing an unrefined answer for free before.

**A free extra gate came with the re-run.** Comparing the two Stage 2 runs row by row, arms 1, 2 and 3
reproduce at **`0.000e+00`** on every error column across all 240 arm-runs while arm 4 moves by up to
0.51 px. The change is provably confined to the Newton refinement.

## Where that leaves the gate

**Still failing, but on a different and much smaller thing.**

| Condition | Limit | Measured | |
|---|---|---|---|
| Paired median, every image type | within 0.001 px | fiducial worse by **0.001407 px** | fail |
| Worst recording, every image type | no worse than the grid | brightfield **+0.000565 px**, sparse **+0.003248 px** | fail |
| Mean processor seconds | ≥30% faster | **55.02% saved** | pass |

Two things to be precise about, and neither is a request to move the line.

**The fiducial median deficit is real and it is the method.** 0.001407 px against a 0.001 px
tolerance. The probe's no-band rows say the same thing without any of the mask trouble: on real
fiducial frames the Newton refinement is 1.02x to 1.04x the grid's error at every lag. On the analytic
fixture it was 2.8x *better*. Real frames carry energy above their own Nyquist limit where the fixture
is band-limited, and the step that stops when the gradient says it has arrived arrives at a slightly
different place than the grid that stops when its spacing runs out. That is a genuine finding and it
is the honest reason to refuse the value.

**The two worst-recording breaches are noise and the gate cannot say so.** 0.000565 px on brightfield,
and 0.003248 px on a sparse recording where *both* arms have already lost lock at 17 px. That
condition was written with no tolerance at all, so it fails on any change that moves any number in any
direction — a coin-flip on the last decimal of one recording out of sixteen would fail it. That is a
weakness in how the gate was written, it was written before the run and it stands, and correcting it
is a decision for whoever owns the plan and not something to do while holding the results.

## What was kept

The fix stays, even though the value does not ship. `AREA_CORRELATION_NEWTON` remains a documented
dead end, and a dead end is worth more recorded in its best state than in its worst: anyone who reads
it should see a refinement that is 55% cheaper and within 0.0014 px of the grid on the one image type
where it loses, not one that silently returns whole-pixel answers. The shipped estimator never moved,
the selector still does not name it, and 307 tests pass.

## Where the evidence lives

| What | Where |
|---|---|
| The probe | `research/newton_stage2_diagnosis/NewtonStage2Diagnosis.java` |
| The run with the fallback, and its gate | `library/benchmark/v2/benchmarks/controlled_motion/summaries/newton_refinement_v1/` |
| The first run, kept for the comparison above | `.../summaries/newton_refinement_v1__stop_fallback/` |
| What Stage 2 measured and concluded first | `docs/newton_refinement_stage2_findings.md` |
