# Performance optimisation plan

Written 2026-08-18, after the estimator axis was adopted and the runtime position declared in
`docs/pairwise_estimator_axis_runtime_declaration.md`. That declaration accepted a mean of 2.078 s
per 48-frame recording against the shipped model's 1.804 s. This plan is how that comes back down —
and how the rest of the pipeline gets faster with it.

**Nothing here may be started before Stage 5 of `docs/pairwise_estimator_axis_plan.md` has run.** The
selector model was fitted to measurements of the current estimator; making the estimator faster
changes what those measurements would have been. Speed-ups that provably do not move a number are
safe afterwards, and the gate for each is stated below.

## The one gate every item shares

Every change here is a speed change, so **every change must reproduce its arm's accuracy exactly**,
to the last decimal place, on the 80 development recordings. That gate already exists and has been
run once: it is what Stage 2 did to the estimator seam, where all 480 arm-runs came back with a
maximum absolute difference of `0.000e+00`. A speed-up that moves a number in the sixth decimal is not
a speed-up, it is an unreviewed change to the estimator, and it goes back.

Two commands, in this order:

```
java -Dlogratio.rewrite=true -Dlogratio.noImages=true logratio.SelectorComparisonBenchmark <project>
java -Dlogratio.rewrite=true                          logratio.PairEstimatorComparisonBenchmark <project>
```

then diff `summaries/selector_comparison_v1/all_recordings.csv` and
`summaries/pair_estimator_v1/all_recordings.csv` against the copies taken before the change.

## Where the time actually goes

Measured on the development set, mean seconds per 48-frame recording, held out:

| Stage | Brightfield | Fiducial | Dense | Phase | Sparse |
|---|---|---|---|---|---|
| Provisional pass (automatic selection only) | 1.242 | 0.359 | — | — | — |
| Resolved registration | 2.745 | 0.853 | 0.446 | 0.388 | 2.011 |

Per pair, from the direct probe (47 lag-1 pairs, 120 px frames): the log-ratio fit takes 0.85 s and
area correlation takes 2.16 s on fiducial; on brightfield, 5.15 s against 9.86 s. **Area correlation
is roughly twice the log-ratio fit**, and inside it the sub-pixel refinement dominates: five rounds of
nine correlations each, every one of them evaluating a sixteen-tap cubic at every sampled pixel,
against the integer sweep's twenty-five whole-pixel passes that need no interpolation at all.

## Ranked by measured gain per unit of risk

### 1. Cache the prepared window per frame and level — safe, mechanical — **DONE, 2026-08-18**

> Shipped. 88.3% hit rate, one build per frame per level, gate passed at `0.000e+00` on 800 arm-runs.
> Worth 4-6% of an area-correlation recording on 100-pixel frames and 20% of a pair alignment on
> 512-pixel ones. Full record in `docs/performance_optimisation_findings.md`.

`AreaCorrelation.Window.of` rebuilds both frames' intensity plane, mean and B-spline coefficients on
every pair. Multi-lag over 48 frames plans 209 pairs, so **each frame is prepared about nine times**.
The preparation is a pure function of the plane, so a cache keyed on the `LogPlane` identity is
memoisation and cannot move a number.

Expected: 5-10% of the estimator's time; more on long recordings and more still if lag sets grow.
Risk: memory. The cache must sit beside `PyramidCache`, respect the same budget, and be cleared with
it, or a long recording will trade time for an out-of-memory failure — which is a worse outcome than
being slow.

### 2. Replace the grid refinement with a Newton step on the correlation — **MEASURED AND REFUSED, 2026-08-19**

> Built as a third value on the estimator axis, measured over the 80 development recordings, and
> refused. **The saving was larger than predicted below — 54.7% off the estimator, taking area
> correlation to 1.06 times the log-ratio fit's processor cost — and it was not enough**, because the
> refinement lost the fiducial/static tail: 0.321 px against the grid's 0.035 px on the worst
> recording, concentrated on steady directional drift. The shipped estimator never moved, the selector
> never named the value, and no sealed set was spent. Full record in
> `docs/newton_refinement_stage2_findings.md`; the value survives as a documented dead end the way
> `AREA_CORRELATION_LINEAR` does. **Do not rebuild this without testing the boundary hypothesis first.**

The refinement currently finds the peak by evaluating a three-by-three grid and fitting a quadratic,
five times over. A correlation surface has analytic first and second derivatives with respect to the
offset, computable from the same interpolated samples in one pass, so **one evaluation per iteration
replaces nine** and convergence takes fewer iterations.

Expected: the refinement is about two thirds of the estimator's cost, and this is a 3-5x cut in that
term — call it a 40-50% cut overall, taking area correlation to roughly the log-ratio fit's cost.
Risk: this is a change of algorithm, not of bookkeeping. It will move numbers. It therefore requires
its own Stage 3-style measurement and, if the selector's candidates change, a retrain — which is a
plan of its own, not a tidy-up. **Do not start it without deciding that up front.**

### 3. Refine on a subsample, confirm on the full frame — **MEASURED AND REFUSED, 2026-08-19**

> Built as `AREA_CORRELATION_SUBSAMPLED`, measured over the 80 development recordings, refused on all
> three gate conditions. **15.2% of processor time, not the 20-30% predicted below**, because the
> first refinement round is already nearly free — it scores nine whole-pixel offsets through the
> fast path that skips interpolation entirely, so subsampling it saves almost nothing. And the risk
> named below was right: fiducial/static, the image type with the fewest informative pixels, is the
> one where accuracy broke, with the worst recording 1.66 times worse. **If revived, subsample rounds
> two onward and leave round one alone** — and it will still have to answer the fiducial loss. Full
> record in `docs/subsample_and_lag_pruning_findings.md`.

The first refinement rounds are locating a peak to a tenth of a pixel; they do not need every pixel to
do it. Running rounds one and two at stride two (a quarter of the pixels) and the last rounds at full
density would cut the refinement's cost by about half.

Expected: 20-30% of the estimator. Risk: moves numbers, so same measurement obligation as item 2, and
the effect is probably image-type dependent — a sparse bead field has few informative pixels to spare,
which is exactly where the estimator currently wins.

### 4. Run the provisional pass at half scale — pipeline-wide, worth measuring first

The automatic selector's provisional pass costs 1.24 s on brightfield, a third of that image type's
total. It exists to measure the recording, not to register it: its transforms feed the movement
features, and its 17 image features come from the frames themselves. `estimationScale` already exists
and reduces the estimation resolution while leaving the output full size.

Expected: a 4x cut in the provisional term, so about 25% of brightfield's total and 10% of fiducial's.
Risk: the features would be measured at a different scale from the ones the model was trained on, so
this needs a retrain, not just a re-measure. **Measure the feature drift before committing**: if the
17 image features and 10 movement features move by less than their own fold-to-fold variation, the
retrain is a formality; if they do not, this item is dead.

### 5. Prune long lags on short recordings — **REFUTED BEFORE BUILDING, 2026-08-19**

> The premise below is measurable and it is false on this benchmark: **0 of 2,090 planned pairs are
> refused**, at any lag, on any image type, with either estimator, on the worst movement profile. The
> saving this item offers is exactly the refused fraction, so here it is zero. The development
> recordings drift about 14 px over 48 frames on 96-120 px frames; a lag-16 pair still overlaps almost
> completely. **A second obstacle if it is revived**: the free ride comes from the movement-bound
> pass, and this benchmark does not run one — `categoryBase` sets `autoMaxShift(false)` and hands a
> known bound per profile, which is what makes it controlled. Reviving this needs recordings that
> actually drift out of overlap, and the refusal rate measured on those *before* any code.
> `research/newton_stage2_diagnosis/LagRefusalProbe.java` is the measurement; findings in
> `docs/subsample_and_lag_pruning_findings.md`.

Multi-lag plans 209 pairs for 48 frames at lags 1, 2, 4, 8, 16. The long lags buy the redundancy that
makes the reconciliation work, but on a recording where the frames have drifted far apart the long-lag
pairs are refused anyway, after paying full cost. Refusing them from the movement bound before they
run costs nothing and saves whatever fraction they are.

Expected: 5-15%, entirely dependent on the recording. Risk: low, but it changes which observations
reach the reconciler, so it changes numbers and needs the gate.

### 6. Parallelise the offline sweep across recordings — no user-facing gain, large operational one — **DONE, 2026-08-18, and the prediction below is refuted**

> Built, proven byte-identical over 640 registrations, measured, and **reverted, because it is about
> 5% slower rather than 3x faster**: 134-145 s serial against 143-151 s at four-at-once, on 16 cores.
> The estimate below assumed a registration parallelises only within a pair alignment; it fans out
> across 209 of them, so the cores are already saturated before a second level of fan-out is added.
> The sweep is back to one recording at a time. **Do not rebuild this without new evidence** — the
> full record is in `docs/performance_optimisation_findings.md`.

The 112-recipe sweep runs recordings sequentially and parallelises within each pair alignment. The
extended sweep took 42 minutes for 1,280 registrations on this machine. Running four recordings at
once, each with a quarter of the worker pool, would cut the wall-clock roughly threefold on an
8-core machine.

Expected: nothing for users; hours for whoever runs the next sweep. Risk: none to correctness — the
outputs are per-recording folders — but memory must be divided as carefully as threads.

## Recommended order

1, then 6 (both are safe and neither can move a number), then measure item 4's feature drift, then
decide whether item 2 is worth a plan of its own. Items 3 and 5 are only worth doing as part of item
2's measurement round, since they carry the same obligation and would otherwise mean paying for that
round twice.

## Item 2 is deferred to a plan of its own — decided 2026-08-18, before items 1 and 6 were started

**Not started, and deliberately not started.** The reason is not that the 40-50% is doubtful; it is
that item 2 is not a performance change at all in the sense the rest of this document uses. Items 1
and 6 are bookkeeping: the same arithmetic, done fewer times, provable by a diff that has to come back
all zeros. Item 2 replaces the thing that produces the number. A Newton step converges to a different
point on the same surface, so every one of the 209 pairs in every recording answers slightly
differently, and *that is the intended behaviour*, not a defect to be tuned out.

What that costs, in order:

1. A Stage 3-style four-arm measurement over the 80 development recordings, per image type, with tail
   and cost beside the median — because a faster estimator that loses the sparse tail is not a
   speed-up, it is a different estimator with a worse failure mode.
2. If the per-image-type position moves at all, the selector's retained candidates were fitted to the
   old numbers and are no longer the fitted answer. That means Stage 4 again: the full 112-candidate
   sweep, 1,280 registrations, leave-one-source-series-out training.
3. If the retained candidates change, the sealed set that validated them is spent
   (`docs/pairwise_estimator_axis_findings.md`, Stage 5) and a third one has to be collected before
   anything ships. `SealedTestSetBuilder.java` and `docs/joint_sealed_set_stage1_candidates.md` are
   how.

So the honest cost of item 2 is a repeat of Stages 3, 4 and 5, not an afternoon's optimisation. It is
worth doing — it takes area correlation to roughly the log-ratio fit's cost, which is what would let
the axis be offered more widely — but it is worth doing **as its own plan, with its own exit gate
written before the first measurement**, exactly as the estimator axis was.

> **That plan is now written: `docs/newton_refinement_plan.md`, 2026-08-19.** It differs from the
> framing above in one way worth knowing before reading it. The Newton refinement is **not** an edit
> to the shipped estimator; it is a third value on the estimator axis, beside `AREA_CORRELATION`. The
> shipped model never names it, so the model stays exactly as validated at every moment, the
> comparison is single-variable, and a measurement that goes badly costs nothing but the measurement.
> A third sealed set is needed only if the retrain actually changes which candidates are retained, and
> the plan says to check that the material for one exists before Stage 0 begins. Items 3 and 5 below
> are folded into its Stage 2 as extra arms; item 4 is not.

**The line between the two kinds of change.** Anything that can be proved by the all-zeros diff above
belongs in this document. Anything that cannot belongs in a plan that says, in advance, what evidence
would make it shippable and what would kill it. Items 3, 4 and 5 are all on the far side of that line
too, and none of them should be started as a tidy-up either.

## What has been done

| Item | State | Outcome |
|---|---|---|
| 1. Cache the prepared window | **done** | 4-6% of a recording, 20% of a pair alignment at 512 px; gate `0.000e+00` |
| 2. Newton step on the correlation | **measured and refused, 2026-08-19** | 54.7% off the estimator, and it lost the fiducial tail: `docs/newton_refinement_stage2_findings.md` |
| 3. Refine on a subsample | **measured and refused, 2026-08-19** | 15.2% saved against a 20% bar, and 1.66x worse on the fiducial tail: `docs/subsample_and_lag_pruning_findings.md` |
| 4. Provisional pass at half scale | not started | needs a feature-drift measurement first |
| 5. Prune long lags | **refuted before building, 2026-08-19** | its premise is false here — 0 of 2,090 planned pairs are refused: `docs/subsample_and_lag_pruning_findings.md` |
| 6. Parallelise the sweep | **done, prediction refuted, reverted** | ~5% slower; measurement kept, code removed |

## What "fast enough" means

The declared limit is 2.2 s mean per 48-frame recording. Items 1 and 6 alone do not need it moved,
and as measured they do not move it far: item 1 is worth a few percent on the two image types that
use area correlation, and item 6 turned out to be worth nothing at all.
Items 2 and 4 together would bring the automatic selector back under the old 1.48 s figure that no
shipped model has met since it was written — which is the point at which the limit is worth
re-declaring rather than merely met.
