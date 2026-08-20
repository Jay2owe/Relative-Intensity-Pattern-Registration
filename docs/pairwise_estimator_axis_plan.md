# Pairwise estimator axis plan

## Outcome

Decide whether this plugin should be able to estimate frame-pair movement with an area-correlation
method as well as with the log-ratio fit, and if so, whether the automatic selector should choose
between them per recording.

The end state is either a new selectable estimator with measured evidence for when to use it, or a
recorded finding that the log-ratio fit is not the limiting factor and the idea is closed.

## One correction to the framing, made before any work starts

This was raised as "an area-based estimator as a new pixel-support option". It cannot be one.
`PairAligner.PixelSupport` chooses *which pixels the log-ratio fit uses* — `ALL`, `GRADIENT`,
`MUTUAL_NOISE_GRADIENT`. An area-correlation estimator does not run the log-ratio fit at all, so it
cannot be a value of that enumeration without the name meaning two different things.

The correct seam is one level up. `PairAligner.align(LogPlane[] a, LogPlane[] b, Options o)` returns a
`Fit` for one frame pair; `Reconciler.multiLag(frames, observations)` turns many such fits into a
movement path. Everything above that seam — scheduling, lags, reconciliation, the warp, the whole
plugin — is indifferent to how a pair was estimated. So this is a **new axis beside pixel support**,
not a new value inside it:

```text
                    frame pair
                        |
                        v
          +-------------------------------+
          |      pairwise estimator       |   <-- the new axis
          |  log-ratio fit  |  area xcorr |
          +-------------------------------+
                        |
                 Fit (dx, dy, weight)
                        |
                        v
              Reconciler.multiLag              <-- unchanged
                        |
                        v
                cumulative movement            <-- unchanged
```

Pixel support, intensity band, estimation filter and mask remain what they are, and remain meaningful
only for the log-ratio branch.

## What is already measured

From `library/benchmark/v2/benchmarks/*/summaries/external_comparison_v1/paired_against_default.csv`.
The arm labelled `22_real_turboreg_translation_multilag_rcc` is exactly the experiment this plan
proposes, already run: TurboReg's area-registration estimate for each frame pair, reconciled by **our**
multi-lag redundant cross-correlation solver over 209 pairs. It is not a plugin anyone can install; it
is our pipeline with the pair estimator swapped.

Paired median error against our shipped default. Below 1 means the area estimator wins.

| Scope | Development ratio | Locked ratio | Consistent? |
|---|---|---|---|
| FIDUCIAL_STATIC | 0.65x | 0.39x | wins on both |
| BRIGHTFIELD_DIC | 0.95x | 0.74x | wins on both |
| PHASE | 3.12x | 1.41x | loses on both |
| DENSE_FLUOR | 1.32x | 0.48x | disagrees |
| SPARSE_LOWLIGHT | 2.96x | 1.00x | disagrees |
| All recordings | 1.43x | 0.75x | disagrees |

Read that table carefully, because the headline is easy to overstate. The overall figures point in
opposite directions on the two sets. What is consistent across both sets is narrower and more useful:
**the area estimator wins on fiducial and brightfield and loses on phase.** Those are per-image-type
signals repeated on independent material, which is the shape of a selector axis rather than a
replacement.

Two costs are already known and are not small:

- **Time.** 4.47 s per recording on the development set and 3.78 s on the locked set, against our
  default's 1.14 s and 1.11 s. Three to four times the cost.
- **Worst case.** Its worst single recording is `6.101162 px` on development and `8.500000 px` on
  locked, against our default's `0.221383 px` and `8.500000 px`. On development it is an order of
  magnitude less reliable at the tail.

So this is not a swap, and the plan does not treat it as one.

## What this plan does not decide

It does not decide to depend on TurboReg. It does not change the frozen selector, the shipped default
or any recipe before Stage 5. It does not touch `PixelSupport`.

## The licence question comes first

TurboReg is third-party software with its own terms, and this project is BSD 3-Clause. If TurboReg
cannot be redistributed or depended on under terms compatible with that, binding to it is not an
option and the plan continues along an implementation route instead. Establish this **before** writing
code, and record the answer next to the source licences already tracked in
`library/benchmark/benchmark_v2_series_manifest.csv`.

Two routes follow from the answer:

| Licence answer | Route |
|---|---|
| Compatible and redistributable | Bind to it reflectively, as the benchmark already does, keeping it an optional dependency so the ordinary test suite still needs only ImageJ 1.x. |
| Not compatible | Implement an area-correlation estimator in `logratio.core`: pyramidal normalised cross-correlation with sub-pixel peak refinement. Standard, unencumbered, and about the same amount of work as the binding plus a test suite. |

Route B is not a fallback of last resort. It is the better long-term answer if the two estimators
perform comparably, because it removes a dependency from the shipped path entirely.

## The locked test problem, stated once

`library/benchmark/v2/benchmarks/locked_test/` has been spent: it was run once against a frozen model
and its results have been read. The ratios quoted above come from it, which is precisely why they may
not be used as the final independent evidence for anything chosen because of them. Adding third-party
comparators did not spend it further — nothing was tuned against it — but a selector that learns to
pick an estimator needs a **new** sealed set. Stage 1 builds it.

## Stage 1 — Seal a test set before looking any further

1. Identify source series for all five image types from records used nowhere in this project: two per
   type, ten series, 40 recordings under the four standard movement paths.
2. Record licence, checksum and path in the manifest to the existing standard.
3. Seal it. Nothing in Stages 2 to 4 may read it.

**Exit gate:** 40 sealed recordings, balanced across image types, with no `independent_group` shared
with any existing development or locked series.

> **Status 2026-08-18: met.** Built by `SealedTestSetBuilder` into
> `library/benchmark/v2/benchmarks/sealed_test/`; ten series, two per image type, 40 recordings, zero
> failures. Collection, licences, seed-plane checks and three declared caveats are recorded in
> `docs/joint_sealed_set_stage1_candidates.md`. The set is sealed and nothing has been read from it.
>
> This is now the project's only sealed set. The sparse low-light programme was folded into this plan
> once its mechanism evidence pointed at the estimator rather than at pairing or outlier rejection —
> see `docs/sparse_low_light_gap_findings.md`. Two consequences for the stages below:
>
> - Sparse low-light is one of the five image types here, not a separate effort. Its development
>   material is four real independent records; the four figure panels it used before are withdrawn.
> - Stage 3's per-image-type report should carry sparse alongside the rest. The existing
>   `22_real_turboreg_translation_multilag_rcc` sparse rows in the table above read `2.96x` on
>   development and `1.00x` on locked and are marked "disagrees". Both figures are unsafe: the
>   development one rests on the withdrawn panels, and the locked one is a median pinned by four
>   recordings on which every method fails identically. On the one sparse record where anything works,
>   the area estimator is roughly three times better than the shipped default.

## Stage 2 — Establish the seam and prove it changes nothing

Before adding a second estimator, make the first one pluggable without moving any number.

1. Introduce an estimator interface at the `PairAligner.align` seam, taking a frame pair and returning
   a `Fit`.
2. Make the existing log-ratio fit the sole implementation and the default.
3. Re-run the six-arm selector comparison on all 80 development recordings.

**Exit gate:** every arm reproduces its current value to six decimal places. A refactor that moves a
number is a refactor that changed behaviour, and it goes back until it does not.

> **Status 2026-08-18: met, and by a wider margin than the gate asks.**
>
> The seam already existed when this was checked — `logratio.core.PairEstimator`, with
> `Kind.LOG_RATIO_FIT` delegating to `PairAligner.align` and the estimator held on
> `Registration.Options`. What had never been done was the verification, so the gate was run:
> `SelectorComparisonBenchmark` with `-Dlogratio.rewrite=true` over all 80 development recordings,
> six arms, 480 arm-runs, against the recorded baseline preserved in
> `summaries/selector_comparison_v1_baseline_2026-08-18/`.
>
> | Arm | Baseline mean median px | Re-run | Delta |
> |---|---|---|---|
> | current category recommendation | 0.025545 | 0.025545 | 0 |
> | old automatic information selector | 1.969239 | 1.969239 | 0 |
> | current automatic filter selector | 0.025582 | 0.025582 | 0 |
> | new full automatic selector | 0.024215 | 0.024215 | 0 |
> | manual oracle recipe | 0.014326 | 0.014326 | 0 |
> | base without automatic changes | 1.968753 | 1.968753 | 0 |
>
> All 480 per-recording values reproduce exactly, not merely to six decimals: the largest movement
> anywhere is `0.00e+00`. The seam moved no number.
>
> Unit coverage was also missing and is now present:
> `src/test/java/logratio/core/AreaCorrelationRecoveryTest.java`, seven tests. It checks that the area
> estimator recovers a known sub-pixel shift, that it follows the same sign convention as the
> log-ratio fit, that normalised cross-correlation really is invariant to gain and offset across a
> 64-fold range, that a textureless frame does not produce a confident answer at the search bound,
> that `Kind.AREA_CORRELATION` reaches the implementation the tests cover, and that Tukey still
> tolerates sparse change better than raw correlation — the trade-off the estimator's own
> documentation claims. Full suite: 274 tests, all passing.

## Stage 3 — Add the area estimator and measure it properly

By whichever route the licence gate chose.

1. Implement or bind the area-correlation estimator behind the new interface.
2. Run it over the 80 development recordings under our own reconciler, at the same lag set and compute
   budget as the log-ratio arm, so the only difference is the estimator.
3. Report paired against our default per image type, per movement profile, and per recording, using
   the same paired rule as `ExternalComparisonSummary`: recordings where both produced a finite answer,
   with drops stated.
4. Report elapsed and processor time separately, and the worst-case error per arm.

**Exit gate:** a per-image-type statement of where the area estimator wins, loses, and ties, with the
tail behaviour and the cost stated alongside. If the wins do not reproduce the fiducial and brightfield
result already measured, stop and record why the earlier finding did not hold.

> **Status 2026-08-18: run, and the gate has fired. The wins did not reproduce. Stage 4 does not
> start.**
>
> Runner: `src/test/java/logratio/EstimatorAxisBenchmark.java`, output in
> `summaries/estimator_axis_v1/`. Two arms over all 80 development recordings, identical in every
> setting except the estimator: same base recipe, same lag set `{1,2,4,8,16}`, same
> `Reconciler.Reference.MULTILAG` over the same 209 pairs, same `maxShift`, same pyramids.
>
> **The runner is verified, not assumed.** Its log-ratio arm reproduces the existing
> `6_base_without_automatic_changes` arm of `selector_comparison_v1` to a mean of `1.968753` on both —
> identical. Whatever the area arm shows is not a wiring artefact.
>
> Paired per image type, both arms finite on all 80, nothing dropped:
>
> | Image type | Log-ratio fit px | Area correlation px | Ratio |
> |---|---|---|---|
> | BRIGHTFIELD_DIC | 0.023459 | 0.044587 | **1.90x worse** |
> | DENSE_FLUOR | 0.016986 | 0.050789 | **2.99x worse** |
> | FIDUCIAL_STATIC | 0.019057 | 0.025463 | **1.34x worse** |
> | PHASE | 0.018527 | 0.091248 | **4.93x worse** |
> | SPARSE_LOWLIGHT | 1.656517 | 3.171250 | 1.91x worse, but see below |
>
> It wins on 15 of 80 recordings and loses on 65. It costs 2.4 times the wall time (2.105 s against
> 0.883 s) and 2.2 times the processor time (16.2 s against 7.4 s). The fiducial and brightfield wins
> the plan was built on **did not reproduce in either direction of magnitude**: fiducial went from
> `0.65x` better to `1.34x` worse.
>
> The sparse row is uninformative and must not be quoted. At the base recipe both arms lose lock on 8
> of 16 sparse recordings, exactly as `6_base_without_automatic_changes` does; that is the recipe
> failing, not the estimator. Sparse needs the selector's settings, which the area estimator cannot
> take.
>
> ### Why the earlier finding did not hold
>
> The `22_real_turboreg_translation_multilag_rcc` figures measure **TurboReg's** estimator inside our
> solver. This measures **ours**. On the same recordings, the same solver and the same lag set,
> TurboReg's implementation beats ours on every image type:
>
> | Image type | Our area correlation px | TurboReg pairs + our solver px | TurboReg better by |
> |---|---|---|---|
> | BRIGHTFIELD_DIC | 0.044587 | 0.013961 | 3.2x |
> | DENSE_FLUOR | 0.050789 | 0.017853 | 2.8x |
> | FIDUCIAL_STATIC | 0.025463 | 0.008316 | 3.1x |
> | PHASE | 0.091248 | 0.040186 | 2.3x |
>
> So the per-image-type wins belong to **that implementation**, not to area correlation as a family.
> Ours is a working estimator — `AreaCorrelationRecoveryTest` shows it recovers a known sub-pixel
> shift to 0.0009 px and is exactly gain-invariant — but it is roughly three times less accurate than
> TurboReg's on real material, and slower than the fit it was meant to rival.
>
> One concrete, documented candidate for the gap, offered as a hypothesis and not as a conclusion:
> `AreaCorrelation` correlates `2^v` of the **log-domain** pyramid. Its javadoc chooses this
> deliberately, so that switching estimator changes nothing about preprocessing or memory. But the
> pyramid is decimated in the log domain, so its coarse levels are geometric means of intensity where
> TurboReg correlates arithmetic means, and the log-then-exponentiate round trip passes through the
> epsilon offset. That design bought comparability and may have cost accuracy. Testing it means
> building linear-domain pyramids for the area arm and re-measuring — a real piece of work, and a
> decision to take deliberately rather than drift into.
>
> **Nothing was changed in response to this result.** No recipe, no selector, no default, no
> threshold. The result is recorded as measured.

> ### Addendum 2026-08-18 — the linear-pyramid hypothesis, tested and refuted
>
> Stage 3 recorded one concrete candidate for why our area estimator is roughly three times less
> accurate than TurboReg's under identical surroundings: `AreaCorrelation` correlates a pyramid whose
> coarse levels are decimated in the **log** domain, so they are geometric means of intensity, where
> TurboReg decimates in the **linear** domain and gets arithmetic means. That was offered as a
> hypothesis, not a conclusion, and taking it deliberately rather than drifting into it was the stated
> condition. It has now been tested.
>
> **Change.** `LogPlane.linearPyramid(int)` builds coarse levels by blurring and decimating intensity
> and taking the log afterwards; `PairEstimator.prefersLinearPyramid()` decides which pyramid a given
> estimator is handed; `Registration.pyramidFor` honours it. `Kind.AREA_CORRELATION_LINEAR` is the same
> correlation code with that one flag set. Level 0 is bit-identical between the two pyramids, and the
> epsilon passes through an arithmetic mean unchanged, so no other variable moves.
>
> The default estimator never sees a linear pyramid. Its gain invariance argument depends on log-domain
> decimation being exact at every level, and quietly weakening that to run an experiment would have
> been the worst possible way to get an answer.
>
> **Result over all 80 development recordings, three arms, everything but the estimator held fixed:**
>
> | Arm | Median of median px | Mean px | Worst px | Mean s |
> |---|---|---|---|---|
> | Log-ratio fit | 0.022262 | 1.968753 | 26.311190 | 0.883 |
> | Area correlation, log pyramid | 0.064094 | 1.608149 | 22.148463 | 2.105 |
> | Area correlation, **linear** pyramid | **0.255405** | 1.887444 | 30.336264 | 3.528 |
>
> Per image type, against the log-domain area arm:
>
> | Image type | Log pyramid px | Linear pyramid px | Change |
> |---|---|---|---|
> | BRIGHTFIELD_DIC | 0.044587 | 0.202097 | 4.5x worse |
> | DENSE_FLUOR | 0.050789 | 0.250844 | 4.9x worse |
> | FIDUCIAL_STATIC | 0.025463 | 0.211705 | 8.3x worse |
> | PHASE | 0.091248 | 0.249501 | 2.7x worse |
> | SPARSE_LOWLIGHT | 3.171250 | 3.309939 | 1.04x worse |
>
> **Refuted, and in the opposite direction.** The linear pyramid is worse on every image type, 4x worse
> overall than the arm it was meant to improve on and 11x worse than the log-ratio fit, and it is also
> the slowest of the three at 3.53 s against 0.88 s. Whatever gives TurboReg its advantage, it is not
> the decimation domain — and correlating geometric means beats correlating arithmetic ones here.
>
> The sparse row is uninformative for the same reason as before: all three arms lose lock on 8 of 16
> sparse recordings at the base recipe, which is the recipe failing rather than the estimator.
>
> **Consequence.** The area-correlation family closes on our own evidence. Both documented routes to
> the measured TurboReg advantage are now spent: it is not feature matching (ruled out in
> `docs/sparse_low_light_gap_findings.md` section 3) and it is not the pyramid domain. What remains is
> that TurboReg is not really the same algorithm — Thevenaz, Ruttimann and Unser 1998 is a cubic-spline
> image model optimised by Marquardt-Levenberg, not a correlation peak with quadratic refinement — so
> reaching it means building that, which is a different and much larger piece of work than this plan
> scoped. It is not started, and nothing here recommends starting it.
>
> `Kind.AREA_CORRELATION_LINEAR` is retained so the finding stays reproducible, and its javadoc says
> plainly that it is measured worse and should not be chosen. Nothing shipped changed: no recipe, no
> selector, no default, no threshold. The sealed set remains unopened.
>
> Coverage: `src/test/java/logratio/core/LinearPyramidTest.java`, eight tests — level 0 shared, shapes
> equal, a flat field decimating identically in either domain, an uneven field strictly brighter under
> arithmetic decimation, the epsilon passing through the mean, only the linear estimator asking for
> linear levels, the new arm recovering a known sub-pixel shift, and its identifier round-tripping.
> Full suite 282 tests, all passing.

## Stage 4 — Decide whether the selector should choose

Only if Stage 3 confirms image-type-dependent wins. The existing selector chooses support, band, filter
and mask; this would add a fifth thing to choose, and it must earn its place by the same rule the others
did.

1. Extend the candidate space with the estimator axis.
2. Retrain with leave-one-source-series-out, all four movement versions of a source withheld together,
   and the same nested inner cross-validation used for the current model's ridge penalty and confidence
   threshold.
3. Retain an estimator candidate for an image type only if, on that type, it never breaches the
   per-recording guard, improves the mean, and helps at least two independent sources.
4. Declare the runtime position in writing **before** the sealed set is opened. The area estimator costs
   three to four times the log-ratio fit, so a selector that chooses it often is materially slower, and
   what that is worth must be decided while it can still be decided honestly.

**Exit gate:** a trained model, held-out numbers, and a written runtime declaration, all before Stage 5.

## Stage 5 — Run the sealed set once

Frozen model, one run, gates declared beforehand:

- zero failures;
- paired mean median error no worse than the current shipped default;
- no image-type regression greater than `0.002 px`;
- runtime within the declared limit from Stage 4;
- the sparse low-light guard passes.

No changes after reading it. Failure keeps the current default and leaves the area estimator available
as an explicit manual choice, which is a perfectly good outcome for an option that helps two image types
out of five.

## Stage 6 — Expose it honestly

Whatever ships:

- the estimator appears as an ordinary editable setting in the advanced dialog, and in
  `LogRatioSweepPlan.Parameter` as a sweepable axis;
- it round-trips through macro options, batch and the Java interface like every other setting;
- the run log states which estimator produced the movement, since two estimators mean a run log that
  names only a recipe is no longer reproducible;
- the README states the measured per-image-type position, including where it loses.

## Completion definition

Complete only when:

- the estimator seam exists and provably moved no existing number;
- an area-correlation estimator runs inside our own reconciler on all 80 development recordings;
- its wins and losses are stated per image type with cost and tail behaviour beside them;
- the licence position of anything depended on is recorded;
- if a selector chooses between estimators, it was trained without leakage and validated once on a
  sealed set against gates written down first;
- the record states plainly whether the log-ratio fit was the limiting factor, and on which image types.
