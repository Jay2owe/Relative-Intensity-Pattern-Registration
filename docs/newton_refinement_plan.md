# Newton refinement plan

Written 2026-08-19. Serves item 2 of `docs/performance_optimisation_plan.md`, which deferred this
deliberately rather than drifting into it. Items 1 and 6 of that plan are done and recorded in
`docs/performance_optimisation_findings.md`; this is the one that was left.

## Outcome

Make `logratio.core.AreaCorrelation` roughly twice as fast, without making it less accurate, by
replacing the sub-pixel refinement's shrinking grid search with a step computed from the correlation
surface's own derivatives — and prove which of those two things actually happened before any user
gets it.

## What the refinement does today, and what it costs

After the whole-pixel sweep has found the best integer offset, `refine` locates the peak to a fraction
of a pixel like a game of hot-and-cold. It scores the eight neighbours of the current offset at a
spacing of one pixel, fits a two-dimensional quadratic surface through those nine scores, jumps to
that surface's summit, checks the score really improved, then shrinks the spacing by four and repeats.
Five rounds, capped.

That is up to **nine correlations per round and forty-five in total**, and every one of them is a full
pass over the sampled pixels evaluating a sixteen-tap cubic at each. The plan's measurement puts the
refinement at about two thirds of the estimator's cost, and
`docs/performance_optimisation_findings.md` puts the preparation it sits on top of at 5% on 120-pixel
frames and 20% on 512-pixel ones.

## What a Newton step is, in one image

Hot-and-cold tells you where the summit is by walking around and comparing heights. A Newton step
reads the ground under your feet: how steeply it rises, and how fast that steepness is falling off. A
slope that is steep but flattening quickly means the summit is close; steep and not flattening means
it is far. From those two numbers you compute the jump directly, in one place, without walking
anywhere.

Concretely: the correlation score is a function of the offset, and the interpolating cubic B-spline
that the estimator already uses as its image model is differentiable. So in **one** pass over the
sampled pixels it is possible to accumulate the score, its two first derivatives with respect to the
offset, and a Gauss–Newton approximation to its second derivatives — then step straight to the
predicted summit. One pass per iteration instead of nine, and fewer iterations.

**Expected: three to five times off the refinement, so 40-50% off the estimator overall**, taking area
correlation to roughly the log-ratio fit's cost. That is the number this plan exists to test, not to
assume.

## Why this cannot be done as an edit to the shipped estimator

**The estimator implementation is frozen.** The automatic selector's retained candidates were fitted
to measurements of that exact code, so any change to `AreaCorrelation` that moves a number invalidates
the model and requires redoing Stages 3, 4 and 5 of `docs/pairwise_estimator_axis_plan.md` — and the
sealed set that validated it is spent, so a third one would have to be collected first.

**So it ships as a third value on the estimator axis, not as a rewrite of the second.** The axis
already exists: `logratio.core.PairEstimator` is a seam at the `PairAligner.align` boundary, and
adding a value changed no scheduling, no reconciliation and no output code last time. A new
`AREA_CORRELATION_NEWTON` alongside `AREA_CORRELATION` means:

- the shipped model never names the new value, so it stays exactly as validated at every moment;
- the comparison is single-variable, because everything above the seam and the entire integer sweep,
  interpolator and preparation are shared;
- a Stage 3 that goes badly costs nothing but the measurement — there is no rollback to perform.

If the new value wins, the selector is retrained and it becomes what brightfield and fiducial get. If
it loses, it is deleted or kept as a documented dead end in the way `AREA_CORRELATION_LINEAR` is.

## The three things most likely to kill this, named before starting

1. **The correlation surface is only piecewise smooth.** The set of pixels valid in both frames changes
   as the offset moves, and it changes in steps, not smoothly. Every time a pixel enters or leaves the
   overlap the score jumps a little. A grid search does not care. A derivative method computes the
   slope of a function that has a small step in it, and can be confidently wrong. This is the risk that
   most deserves the analytic fixture in Stage 0.
2. **Zero-normalised cross-correlation is a ratio, not a sum of squares.** Its mean and variance terms
   depend on the offset too, because the overlap does. The derivative is therefore not the textbook
   Gauss–Newton one for least squares, and getting it approximately right will look like it works and
   then bias the answer. The derivation has to be written down and checked against a numerical
   derivative before any pipeline work.
3. **Sparse low-light is already the weak case.** `docs/pairwise_estimator_axis_findings.md` records
   the area estimator losing lock on at least one sparse recording at 17 px where the default stays at
   0.22 px. A method that trusts local derivatives has more ways to fall off a peak than one that
   scores a grid. Sparse must be reported separately and must not be averaged away.

## Stage 0 — Derive it and try to kill it cheaply

No pipeline, no benchmark, no recordings. A fixture and a probe.

1. Derive the first and second derivatives of the zero-normalised cross-correlation with respect to the
   offset, including the terms coming from the mean and variance. Write the derivation into the class
   javadoc, not into a notebook.
2. Check every derivative against a central finite difference on a synthetic frame pair. They must
   agree to the accuracy the finite difference itself can offer.
3. Run the analytic fixture the existing estimator is measured on. The interpolating cubic B-spline
   reaches **0.0004 px** there (`docs/pairwise_estimator_axis_findings.md`); bilinear reached 0.0021.
4. Measure per-pair cost directly, the way `WindowCacheProbe` measures preparation: refinement time
   with the grid against refinement time with the step, on 120-pixel and 512-pixel frames.

**Exit gate.** Continue only if **both**: the analytic fixture error is no worse than 0.0006 px — half
again the current 0.0004, which is the width within which the interpolator rather than the search is
the limit — **and** the measured per-pair refinement cost is at most half the grid's. Anything less
than a halving is not worth the stages below.

**Kill criterion, stated so it is not negotiated later.** If the fixture error exceeds 0.0006 px, stop
and record why. A refinement that is fast and slightly worse is not a speed-up; it is a different
estimator, and the axis already has the mechanism for offering one of those — through Stage 3, not
through this plan.

> **Status 2026-08-19: passed, all three checks.** Full record in
> `docs/newton_refinement_stage0_findings.md`.
>
> | Check | Limit | Measured |
> |---|---|---|
> | Derivatives against a numerical derivative | agreement to the difference's own accuracy | **8.3e-06** interior, **1.3e-05** at whole-pixel offsets |
> | Worst error on the analytic fixture | 0.000600 px | **0.000243 px** — the grid is 0.000692 px |
> | Refinement cost | at most half the grid's | **0.452x** |
>
> Three things a reader should carry into Stage 2:
>
> - **The speed-up is 2.21x on the refinement, not the 3-5x predicted above**, which is about **36%**
>   off the estimator rather than 40-50%. Still the largest saving available; the headline number in
>   this plan was optimistic.
> - **Newton is more accurate than the grid, by 2.8x on this fixture**, which is not what a speed-up is
>   meant to do. The grid stops when its spacing falls below tolerance wherever it happens to be; the
>   step stops when the gradient says it has arrived.
> - **Risk 2 is dissolved rather than managed.** Maximising the correlation is exactly minimising a sum
>   of squares, because both normalised vectors are unit length, so the Gauss-Newton derivation applies
>   without approximation. **Risk 1 is real and quantified**: 189 samples of 35,721 leave the overlap at
>   a whole-pixel offset, stepping the score by 1.17e-05, which is worth about 7e-04 px. Harmless on a
>   clean fixture; unproven on sparse low-light, where the validity boundary is ragged.
>
> **Wall clock could not be used and Stage 2 should not try.** Identical repeated runs gave 2.21x,
> 2.42x, 3.59x and 5.92x. The figure above is arithmetic counted with per-pass weights measured from
> the machine, reproducible to 0.01%.

## Stage 1 — Add it as a third estimator value, and prove the other two did not move

1. Add `PairEstimator.Kind.AREA_CORRELATION_NEWTON`. Share the preparation, the interpolator, the
   integer sweep and the pyramid descent with `AREA_CORRELATION`; the refinement is the only
   difference.
2. Keep the grid refinement reachable and unchanged. It is what ships.
3. Re-run the gate in `docs/performance_optimisation_plan.md`, as a **before/after pair of builds** —
   see `docs/performance_optimisation_findings.md` for why diffing against the files on disk measures
   the wrong thing.

**Exit gate:** all 800 arm-runs reproduce at a maximum absolute difference of `0.000e+00`, and no
resolved recipe identifier changes.

> **Status 2026-08-19: passed.** Full record in `docs/newton_refinement_stage1_findings.md`;
> both runs and the scripts that produced them in
> `library/benchmark/v2/benchmarks/controlled_motion/summaries/newton_gate_v1/`.
>
> | Benchmark | Arm-runs | Max absolute difference, any error column | Resolved-recipe differences |
> |---|---|---|---|
> | Selector comparison, six arms | 480 | **0.000e+00** | **0** |
> | Pair estimator comparison, four arms | 320 | **0.000e+00** | **0** |
>
> Four things a reader should carry into Stage 2:
>
> - **The refinement is the only difference between the two area arms.** `AreaCorrelation` gained a
>   `Refiner` switch; the window preparation, the interpolator, the integer sweep, the pyramid
>   descent, the shift clamp and the reporting are the same lines of code for both, so Stage 2's arm
>   3 against arm 4 really is single-variable.
> - **The port is exact, not merely equivalent.** Run through the shipped pipeline, the new code
>   reproduces Stage 0's ten-shift accuracy table to all six decimal places — worst 0.000243 px
>   against the grid's 0.000692 px. Identical to six decimals across ten shifts means the same
>   trajectory, not just the same landing place, so **Stage 0's 2.21x counted-arithmetic figure
>   carries unchanged** and was not re-measured.
> - **The shipped model still cannot choose it.** `AutomaticRegistrationSelectorModel` names only
>   `AREA_CORRELATION`. What the enumeration gives away for free is the advanced dialog's dropdown and
>   `estimator=area_correlation_newton` in macro and batch options — exactly as `AREA_CORRELATION_LINEAR`
>   already does. Stage 5 still decides exposure; nothing selects it without being asked.
> - **No speed claim may be read off this gate.** The before half shared the machine with
>   `mvn -o clean test`, which is why the gate excludes the clock columns by design. Stage 2 measures
>   cost on its own runs.
>
> `mvn -o clean test` is green at 302 tests and the jar builds. Seven new tests in
> `AreaCorrelationNewtonTest` cover the basis derivatives, the analytic gradient against a numerical
> one in both regimes, sub-pixel recovery, the seam, and the unchanged default.

## Stage 2 — Measure it properly, per image type, with the tail beside the median

Four arms over the 80 development recordings, at the category recommendation so that only the
estimator differs:

| Arm | What it is |
|---|---|
| 1 | Shipped default — the full automatic selector as it stands |
| 2 | Category recommendation, log-ratio fit |
| 3 | Category recommendation, area correlation, grid refinement |
| 4 | Category recommendation, area correlation, Newton refinement |

Report, per image type and never pooled: paired median, 90th percentile, **worst recording**, mean
wall seconds and mean processor seconds. Arm 4 against arm 3 is the whole question; arms 1 and 2 are
there so the numbers can be read against the record that already exists.

**Exit gate.** Arm 4 must be **no worse than arm 3 on any image type's median by more than 0.001 px**,
must not have a worse worst-recording on any image type, and must be **at least 30% faster** on mean
processor seconds. Processor seconds rather than wall clock, because
`docs/performance_optimisation_findings.md` measured run-to-run wall drift on this machine at about 4%
and a change this size has to be visible above it.

**Stop here if the gate fails.** Record the numbers, delete or document the value, and leave the
shipped estimator alone. That is a complete and useful outcome.

> **Status 2026-08-19: FAILED. The plan ends here and Stages 3, 4 and 5 do not run.** Full record in
> `docs/newton_refinement_stage2_findings.md`; the run, and a `GATE.md` written by the benchmark class
> from thresholds fixed before it, in
> `library/benchmark/v2/benchmarks/controlled_motion/summaries/newton_refinement_v1/`.
>
> | Condition | Limit | Measured | Verdict |
> |---|---|---|---|
> | Paired median, every image type | no worse by more than 0.001 px | fiducial/static worse by **0.001407 px** | **fail** |
> | Worst recording, every image type | no worse than the grid | fiducial/static **0.321317 px** against **0.034827 px** | **fail** |
> | Mean processor seconds | at least 30% faster | **54.66% saved** | pass |
>
> Four things a reader should carry away:
>
> - **The speed was better than this plan ever predicted, and it did not matter.** 54.7% off the
>   estimator against the 40-50% hoped for here and the 36% Stage 0 corrected it to, taking area
>   correlation to **1.06 times the log-ratio fit's processor cost** where the grid sits at 2.34
>   times. That is the target this whole plan existed to reach. Reaching it did not buy the accuracy.
> - **The median got better, on four image types out of five**, by up to 0.0025 px, exactly as the
>   analytic fixture predicted. The median is not where this died.
> - **It died on the tail, on one image type, on one movement profile.** Fiducial/static under steady
>   directional drift: two recordings 9x worse, a third 1.8x. Three of the four worst are the same
>   profile on the same image type, which is a signature and not a scatter — and it is the signature
>   **risk 1 of this plan named before Stage 0 started**: a derivative method computing the slope of a
>   surface that has a step in it, where the valid-pixel set shrinks monotonically as the frames slide
>   apart. Consistent with the record, and **not confirmed** — confirming it is new work this plan does
>   not authorise.
> - **Nothing shipped and nothing is spent.** The shipped estimator never moved, the selector never
>   named the value, no sealed set was opened, and there is no rollback to perform. That was the
>   reason for making it a third value on the axis rather than an edit to the second, and it is the
>   part of the design that paid.
>
> The value stays as a documented dead end with its numbers in its javadoc, the way
> `AREA_CORRELATION_LINEAR` does — see Stage 5 below, which is the branch that was taken.
>
> **Diagnosis, same day, and it corrects the third bullet above.** The cause was not the frames
> sliding apart. On both failing recordings the refinement **never ran**, on 209 pairs of 209:
> the recipe's intensity band leaves a scattered valid mask through which the Gauss-Newton pass
> admits 15% of the samples the grid's scoring function admits, below `minValidFraction`, so it
> declined to start and returned the integer offset. This stage's open question — stop, or hand
> back to the grid — is therefore answered: **hand back**, and `newtonRefine` now does.
> Fiducial's worst recording fell from 0.321317 px to **0.034827 px, the grid's own number**, at
> no cost in speed (55.02% saved). **The gate still fails** and the verdict is unchanged, now on
> a fiducial median deficit of 0.001407 px against the 0.001 px tolerance — which the probe
> confirms is the method rather than the mask, the step being 1.02x to 1.04x the grid's error on
> real fiducial frames with no band at all where the analytic fixture had it 2.8x better. Full
> record in `docs/newton_refinement_stage2_diagnosis.md`.

## Stage 3 — Only if Stage 2 passes: retrain, and decide whether the selector wants it

The candidate space goes from 112 to 128: sixteen more candidates, area correlation with Newton
refinement over four intensity bands and four estimation filters, with pixel support and the pixel mask
held neutral as before. Training unchanged — leave-one-source-series-out, all four movement versions of
a source withheld together, candidate pruning and the ridge penalty and the confidence threshold all
recomputed inside every fold.

**Exit gate:** held-out mean median no worse than the current model's 0.020654 px, no per-recording
guard breached, and the runtime declaration in
`docs/pairwise_estimator_axis_runtime_declaration.md` met rather than re-declared. If the retrained
model retains no Newton candidate, that is the answer: the speed was not worth what it cost in
accuracy, and the plan ends here with the shipped model untouched.

> **Status 2026-08-19: PASSED, and the retained candidates changed — so Stage 4 is required.** Full
> record in `docs/newton_refinement_stage3_findings.md`.
>
> | Condition | Limit | Measured | |
> |---|---|---|---|
> | Held-out mean median | no worse than 0.020654 px | **0.020258 px** | pass |
> | No per-recording guard breached | — | every image-type regression an improvement | pass |
> | Runtime declaration met | 2.2 s per recording | **1.594 s**, against the shipped model's 2.078 s | pass |
>
> **The retrained selector replaced both retained candidates with `AREA_CORRELATION_NEWTON`**, keeping
> the same two image types, the same `FULL` band, the same Gaussian 0.7 filter and the same neutral
> support and mask. Only the refinement changed, and it is better on every number reported — 0.0004 px
> on the held-out mean and **0.48 seconds per 48-frame recording**.
>
> **Stage 2 failed and this passed, and the reason is not a contradiction.** Stage 2 forced the
> refinement to run at the category recommendation, whose fiducial recipe carries the intensity band
> that `docs/newton_refinement_stage2_diagnosis.md` showed breaks the Gauss-Newton pass. Stage 3 lets
> the selector choose the band and it chose `FULL` — no band. The selector routed around the failure
> mode on its own. The honest reading is that **the refinement is band-sensitive**, that the
> sensitivity is measured, and that the selector avoids it — not that it is unconditionally better.
>
> Two process notes. The frozen 112-recipe sweep was **not** re-run: the extension reports
> `completed 1280, resumed 8960, failed 0`. And `FullSelectorTraining` rewrites the shipped model
> source with no dry-run flag — it was backed up and restored, checksum-verified, so the shipped model
> still names no Newton candidate.
>
> **Nothing has shipped.** Stage 4 is now the only thing between this and a decision, and it is the
> stage the plan already identified as blocked.

## Stage 4 — Only if Stage 3 changes the retained candidates: a third sealed set

The 40-recording sealed set in `library/benchmark/v2/benchmarks/sealed_test/` is spent. It was opened
on 2026-08-18 and its numbers are published. Reusing it would make it a development set.

1. Collect a third set to the same standard: ten source series from records used nowhere in this
   project, two per image type, 40 recordings under the four standard movement paths.
   `SealedTestSetBuilder.java` and `docs/joint_sealed_set_stage1_candidates.md` show how, and how much
   of the available material is left.
2. Write the gates into the report class **before** opening it.
3. Open it once.

> **Status 2026-08-20: FAILED on the third sealed set, and the failure was overridden by the project
> owner. The Newton refinement ships; the gate says it should not.** The set was opened once, on a
> model byte-identical to the one scored below, against gates written before the opening. Mean median ratio **1.000012** against
> a declared limit of 1.000 — 0.415153 px against 0.415148 px, five nanopixels — with every other
> gate passing, including a 24.4% saving in elapsed time against a limit of no slowdown. Ten of the
> sixteen reachable recordings worsened and six improved. Full record in
> `docs/newton_refinement_stage4_third_set_findings.md`; the gate and both runs in
> `library/benchmark/v2/benchmarks/sealed_test_3/summaries/`.
>
> **Nothing was tuned and the set was not re-read.** The rule was executed first:
> `AutomaticRegistrationSelectorModel.java` was restored from
> `AutomaticRegistrationSelectorModel.before_newton_refinement.java.txt` and verified by checksum,
> and the tree was brought into line with the withdrawal. The owner was then told the verdict, the
> size of the loss and the cost of overriding, and judged five nanopixels a fair price for a quarter
> of the running time. The retrained model is installed again, md5
> `a4fea62ffd9b0f11d68227bc80f6c492`.
>
> **This is an override, not a pass, and it is recorded as one wherever the value is described** — in
> `PairEstimator.AREA_CORRELATION_NEWTON`'s javadoc, in `README.md` and in the findings above. The
> accuracy of this refinement is **unvalidated on unseen material**: not validated, not refuted.
> Reverting is one file copy.
>
> ---
>
> **Superseded status 2026-08-19: PASSED, on a second opening of the existing set rather than a third one.**
> Full record in `docs/newton_refinement_stage4_findings.md`; both runs and the gate in
> `library/benchmark/v2/benchmarks/sealed_test/summaries/newton_stage4/`.
>
> **The stage was not run as written, and the deviation is the most important thing on this page.**
> This stage requires a *new* sealed set. The material check below found that blocked — zero unused
> dense-fluorescence sources on disk, zero fetchable — so the existing 40-recording set was opened a
> **second** time, with the gates written into `NewtonSealedReport` before the opening and the
> weakness declared in every document that quotes the number. **It is not an independent validation**,
> it is optimistically biased by an unmeasurable amount, and the project's standard is not met until a
> third set exists.
>
> ---
>
> **Update, later on 2026-08-19: a third sealed set now exists, and this result can be replaced
> with a clean one.** Read `docs/newton_stage4_notice.md` before doing anything else with Stage 4.
>
> The blocker was cleared by a dense fluorescence source hunt, a second blocker in sparse low-light
> was found and cleared, and `SealedTestSet3Builder` built 40 controlled recordings and 6 natural
> stacks with zero failures. Re-running this comparison against
> `library/benchmark/v2/benchmarks/sealed_test_3` with `SealedTest3Report` gives a genuine
> held-out number, **provided the model is byte-identical to the one scored here** — which this
> stage's own rule already requires.
>
> **The material check below has two errors**, reproduced independently before being found, so read
> it with these corrections: sparse low-light has **0** unused groups and not 2, and brightfield has
> **3** and not 4. `ssbd131_worm2`, `ssbd474_root_hair` and `microbundle_type3_04` have all been read
> by the natural-motion benchmark, whose directory layout is one level deeper than every other
> benchmark's and so is invisible to a count that assumes a uniform layout. The caution below about
> putting the sparse pair through the seed-plane check was well placed: both are 8-bit with no 16-bit
> original behind them, and `ssbd131_worm2` ships frames already registered.
>
> | Gate | Limit | Measured | |
> |---|---|---|---|
> | Registration failures | 0 | 0 | pass |
> | Control arm reproduces between runs | exact | exact on all 40 | pass |
> | Paired mean median, retrained over shipped | ≤ 1.000 | 0.023700 over 0.024002 = **0.9874** | pass |
> | Image-type regression | 0.002 px | none | pass |
> | Per-recording regression | 0.050 px | none | pass |
> | Mean elapsed seconds | 2.200 | **1.192**, against 1.573 | pass |
>
> Two things worth carrying:
>
> - **The shipped run reproduces the first opening exactly** — 0.024002 px and 0.040402 px, the
>   published numbers to the last digit. That is the control that says the two builds differ in the
>   model and nothing else.
> - **The speed is the result and the accuracy is not.** 24% off the automatic selector; brightfield
>   gains 0.0015 px, fiducial loses 0.000026 px, and the three image types that retain no candidate
>   are bit-identical. The accuracy change is within noise in both directions and must not be quoted
>   as an improvement.

**Before running it, recompile the main classes.** A stale main build is what scored the first sealed
set against the wrong model. That specific trap is now closed —
`AutomaticRegistrationSelectorModel`'s scalars are read at runtime rather than copied at build time,
and `ModelConstantsAreReadAtRuntimeTest` fails loudly if that regresses — but `mvn -o clean` before a
sealed run costs nothing and removes the whole class of problem.

**This stage is the reason the plan is expensive**, and it should be looked at squarely before Stage 0
starts: if the available unused material will not yield ten more independent series, then a Stage 3
that changes the retained candidates cannot be validated, and the honest choice is to run Stages 0 to 2
for the knowledge and ship nothing.

### The material check, done 2026-08-19, before Stage 0

Counted from `library/benchmark/benchmark_v2_series_manifest.csv` and
`benchmark_v2_download_manifest.csv` against every series actually built under
`library/benchmark/v2/benchmarks/`. The unit is the **independent group**, which is what the sealed-set
standard requires two of per image type, not the series or the download.

| Image type | Unused independent groups | Two available? |
|---|---|---|
| PHASE | **12** — `strack_{lysobacter,pputida,pveronii,rahnella}_{03,04,05}` | yes, on disk |
| BRIGHTFIELD_DIC | **4** — `bbbc028_ring`, `bbbc028_steplike`, `microbundle_type3_04`, `microbundle_type3_06` | yes, on disk |
| SPARSE_LOWLIGHT | **2** — `ssbd131_worm2`, `ssbd474_root_hair` | exactly two, no margin |
| FIDUCIAL_STATIC | **0 on disk, 3 fetchable** — Zenodo 15407010 commercial `d3`, `d4`, `d5` | yes, two downloads of about 1.6 GB each |
| DENSE_FLUOR | **0** | **no** |

**Dense fluorescence is the blocker, and it is the only one.** All three dense groups the project has —
`opencell_leonetti`, `ssbd_197_bannai` and `watabe_pge2_fret` — are used. The four unused Aydin fields
of view and `ssbd197_fig5b` do not help: they sit *inside* groups that are already spent, and using
them would make the third sealed set share an independent group with the second, which is the one thing
the standard forbids. A third set therefore needs **one new dense fluorescence source, CC BY or CC0,
with at least two independent acquisitions**. `benchmark_v2_dataset_manifest.csv` lists three
unexplored leads: `PUBLIC_FLUOR_TIMELAPSE` (candidate, never inventoried), `FMD` (licence check
outstanding) and `CTC_2DT` (needs permission).

Three further things a reader should not have to rediscover:

- **Sparse has exactly two and no spare.** Both are nuclei — `ssbd131_worm2` is *C. elegans* neuronal
  nuclei, `ssbd474_root_hair` is near-infrared root-hair autofluorescence — so the third set would lose
  the single-molecule modality the second set gained, and sparse is the image type where area
  correlation already has a 17 px tail. Neither has been through the seed-plane check that
  `docs/joint_sealed_set_stage1_candidates.md` describes, and that check is what caught the previous
  sparse material being colour renderings rather than sensor data. Do it before counting on them.
- **The four withdrawn sparse panels do not come back.** `ssbd_fig3a`, `ssbd_fig4a`, `ssbd_fig5a` and
  `ssbd_figs4` are still on disk and still seed the development set, but they were withdrawn as
  evidence; they are not spare material.
- **Phase remains single-laboratory.** All twelve unused groups are fields from the same Zenodo
  deposit. That is the project's standing weakness on phase rather than a new one, and the second
  sealed set already accepted it, but a third set does not improve it. `KER_C2C12` is the only route to
  a second phase laboratory and its licence is still unverified.

**What this means for the plan.** Stage 4 is reachable, and one source hunt stands between here and it.
That hunt is not on the critical path: Stages 0, 1 and 2 can all run first, and Stage 2's gate is
where the idea is most likely to die anyway. Start the dense hunt only if Stage 2 passes — and if it
passes and the hunt then fails, the honest outcome is to keep the measurement and ship nothing, because
an unvalidated model is worse than a slow one.

## Stage 5 — Expose it, or do not

If it ships: the setting appears in the advanced dialog, as a macro option value of `estimator=`, in
batch, in the Java API, as a `LogRatioSweepPlan.Parameter.ESTIMATOR` value, and in the run log —
everywhere the existing two already appear, with no new place to learn about.

If it does not ship: it stays as a documented dead end with its measurements in its javadoc, the way
`AREA_CORRELATION_LINEAR` does. A refuted idea with its numbers attached is worth more than a deleted
one.

> **Status 2026-08-20: it ships, over its own gate.** The third sealed set failed it on accuracy by
> five nanopixels and the owner overrode that; see Stage 4 above. Exposure is therefore the first
> branch: it is the automatic choice for brightfield/DIC and fiducial/static, and it appears in the
> advanced dialog, in `estimator=area_correlation_newton` for macro and batch, in the Java API, in
> `LogRatioSweepPlan.Parameter.ESTIMATOR` and in the run log — all driven from `PairEstimator.Kind`.
> **Every one of those places that carries prose carries the override with it**, because a value
> chosen for the user over a failed gate has to say so where the user meets it. `README.md` says it
> in both places it mentions the estimator axis.
>
> ---
>
> **Superseded status 2026-08-19: it ships.** The retrained model is installed and names
> `AREA_CORRELATION_NEWTON` for brightfield/DIC and fiducial/static. The value already appeared in the
> advanced dialog's estimator list, in `estimator=area_correlation_newton` for macro and batch, in the
> Java API and in the run log, because those are all driven from `PairEstimator.Kind` and it has been
> a value there since Stage 1; `LogRatioSweepPlan.Parameter.ESTIMATOR` now names it too. Its javadoc
> carries the measured position rather than the "do not choose this" it held while it was unproven.
>
> The outgoing model is kept at
> `summaries/full_selector_sweep_v1/AutomaticRegistrationSelectorModel.before_newton_refinement.java.txt`,
> so reverting is one file copy.
>
> **`AREA_CORRELATION_SUBSAMPLED` did not ship** and keeps the dead-end treatment described above —
> see `docs/subsample_and_lag_pruning_findings.md`.

## What this plan folds in, and what it does not

**Folded in.** Items 3 and 5 of `docs/performance_optimisation_plan.md` — refining on a subsample, and
pruning long lags from the movement bound before they run — carry the same obligation as this one and
should be measured in Stage 2's round rather than paying for that round twice. Add them as arms, not
as separate plans.

**Not folded in.** Item 4, running the provisional pass at half scale, is a pipeline change rather than
an estimator change: it moves the features the model was trained on, so it needs a feature-drift
measurement first and a retrain regardless of anything here. It stays where it is.

## Completion definition

Complete only when:

- the derivatives are derived, written down, and checked against a numerical derivative;
- the Newton refinement runs inside our own reconciler on all 80 development recordings;
- its accuracy and its cost are stated per image type, with the worst recording beside the median, and
  sparse low-light reported rather than averaged away;
- the shipped estimator is provably unmoved, at `0.000e+00`, by a before/after pair of builds;
- if the selector chooses it, it was trained without leakage and validated once on a **new** sealed set
  against gates written down first;
- the record states plainly whether the refinement was the limiting factor on cost, and by how much.

> **Status 2026-08-20: complete, on every condition including the one that was blocked.** The
> refinement was validated once on a genuinely new sealed set, `sealed_test_3`, against gates written
> down first — and it failed by five nanopixels while saving 24% of the time, a failure the owner
> then overrode with the trade stated plainly. It was the limiting
> factor on cost: 55% of the area estimator's processor time on the development set, and 50% and 37%
> of elapsed time on the two image types that reach it on the sealed set. The plan is closed. What
> ships is the faster refinement, on a decision rather than on a passing gate; what the plan produced
> besides is a diagnosis of that value's band sensitivity, two refuted performance items, and a
> reproducibility gate at `0.000e+00` proving the older estimator never moved by one bit.
