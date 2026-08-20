# Spline-model pairwise estimator plan

> **CLOSED at Stage 0, 2026-08-18. Do not build the estimator.**
>
> The gain-fade gate was declared in `docs/spline_estimator_stage0_gate.md` and run before any result
> was seen. TurboReg's advantage does not survive a brightness change: on the two image types where it
> led on clean data its error ratio against our shipped default goes from 0.949x and 0.647x to
> **13 762x and 1 249x**, silently, reporting `ok` on all 80 recordings. Our own arms cost at most
> 1.53x over the same change. Our area correlation already carries the published method's cubic
> B-spline image model and survives the fade, so the failure is the squared-difference criterion and
> not the image model — which is the one part of the method this project did not already have.
>
> Two of this plan's premises were also wrong at the time of writing: **Stage 1 had already been run**
> (the B-spline prefilter in `docs/pairwise_estimator_axis_findings.md`), and the headline table below
> quotes pre-prefilter numbers. Full verdict and evidence: `docs/spline_estimator_stage0_findings.md`.
>
> Everything below is the plan as written before the gate. It is kept unedited as the record of what
> was proposed and on what grounds.

## Outcome

Decide whether to build a cubic-spline image model optimised by Marquardt–Levenberg as a second
pairwise estimator, and only then whether to build it.

The end state is either a working spline estimator with measured evidence for when it should be used,
or a recorded finding that the published method's advantage does not survive the conditions this
plugin exists for.

## This is a new decision, not a continuation

Two plans reached their gates and stopped before this one. What they establish is worth stating
exactly, because the temptation is to treat this as the next step of a programme that has already
failed twice, and inherited momentum is the worst reason to build an estimator.

**Carries over — the measured fact that motivates this at all.** In
`library/benchmark/v2/benchmarks/*/summaries/external_comparison_v1/`, TurboReg's pairwise estimate
reconciled by **our** multi-lag solver, at our lag set and our pair plan, beats our own estimator on
every image type:

| Image type | Our area correlation | TurboReg pairs, our solver | TurboReg better by |
|---|---|---|---|
| BRIGHTFIELD_DIC | 0.044587 px | 0.013961 px | 3.2x |
| DENSE_FLUOR | 0.050789 px | 0.017853 px | 2.8x |
| FIDUCIAL_STATIC | 0.025463 px | 0.008316 px | 3.1x |
| PHASE | 0.091248 px | 0.040186 px | 2.3x |

And against our shipped default it wins on fiducial and brightfield on both the development and the
locked sets — `0.65x` and `0.39x` on fiducial, `0.95x` and `0.74x` on brightfield — while losing on
phase on both. Those are per-image-type signals reproduced on independent material.

**Does not carry over — two eliminated explanations.** The advantage is not feature matching
(`docs/sparse_low_light_gap_findings.md` section 3: SIFT correspondences in our own solver are the
worst arm tested) and it is not the pyramid decimation domain
(`docs/pairwise_estimator_axis_plan.md` Stage 3 addendum: a linear-domain pyramid is 4x worse than the
log-domain one on every image type, and slower). Both were cheap tests of cheap hypotheses and both
came back negative. Neither result argues for this plan; they only remove the alternatives to it.

**Does not carry over — the sparse programme.** `docs/sparse_low_light_gap_plan.md` is superseded and
its remaining question folded into the estimator plan. Nothing in this plan is a sparse plan.

So the honest statement of why this is on the table is narrow: **the only remaining explanation for a
reproduced, three-fold, per-image-type advantage is that TurboReg is a different algorithm.** That is a
reason to scope the work. It is not yet a reason to do it, and Stage 0 exists to decide which.

## What the method actually is

TurboReg implements Thévenaz, Rüttimann and Unser 1998, *A Pyramid Approach to Subpixel Registration
Based on Intensity*, IEEE Transactions on Image Processing 7(1):27–41 — the paper its own credits panel
links to. Three things distinguish it from what we built:

| | Our area correlation | The published spline method |
|---|---|---|
| Image model | samples on a grid, bilinear between them | continuous cubic B-spline through the samples |
| Search | exhaustive integer sweep, then quadratic peak refinement | gradient-based Marquardt–Levenberg descent on the model |
| Criterion | normalised cross-correlation | squared intensity difference |

The image model is the substantive difference and the likeliest source of the accuracy. A spline model
is differentiable everywhere, so the optimiser has exact derivatives of the criterion with respect to
displacement rather than a sampled surface to interpolate a peak on. Sub-pixel accuracy stops being a
post-hoc refinement of a discrete search and becomes a property of the model.

**The criterion is the problem.** Squared intensity difference is not invariant to gain. A frame that
bleaches, or a lamp that drifts, changes the criterion without moving. That is precisely the failure the
log-ratio criterion was built to avoid, and it is the reason this plugin exists at all. Adopting an SSD
estimator would mean shipping, as an option, a method that fails on the very case the plugin's own
front page claims to solve.

That tension is not a reason to stop. It is a reason to measure before building, which is Stage 0.

## Stage 0 — Does the advantage survive gain change?

**Everything above was measured on recordings with no gain change.** Every controlled recording in
`library/benchmark/v2/benchmarks/controlled_motion/` is condition `CLEAN`: 80 of 80. The third-party
comparison ran with `logratio.onlyCondition=CLEAN`. So does the 96-recipe sweep, the selector training,
the locked test and the sealed set.

`Benchmark.Condition` already defines `GAIN_FADE`, `CHANGE_BLOCKS` and `CHANGE_MOVED`, and the older
2026-08-12 work under `library/benchmark/` used them extensively — but on the previous seed set, and
never against a third-party engine. **No external engine in this project has ever been measured under a
brightness change.**

Theory says TurboReg's SSD criterion should degrade under gain fade and ours should not. If theory is
right, the advantage that motivates this whole plan is an artefact of testing only the easy condition,
and a spline SSD estimator would be worse than useless: it would look better in the benchmark and fail
on real bleaching data.

1. Build the `GAIN_FADE` condition of the existing 20 development source series and four movement
   paths: 80 further controlled recordings, same seeds, same paths, same code, one condition changed.
2. Run three arms on them: our shipped default, our log-ratio fit at the base recipe, and TurboReg
   pairs through our solver. All three already exist and need no new code.
3. Report paired per image type, exactly as `ExternalComparisonSummary` does.

**Exit gate, declared now and before any result is seen:**

- If TurboReg's advantage **holds** under gain fade at roughly the CLEAN magnitude, the puzzle is real
  and independent of the criterion, and Stage 1 starts.
- If it **shrinks to nothing or inverts**, the advantage was conditional on the easy case. Record that,
  stop, and do not build a spline estimator. This is the expected outcome on theory, and it is the
  cheapest possible way to find out.
- If our own arms degrade badly too, that is a finding about the log-ratio criterion worth more than
  anything else in this plan, and it takes priority over the estimator question entirely.

This stage costs one build and one benchmark run. Nothing else in this plan should start until it has
been read.

## Stage 1 — Isolate the image model before building the optimiser

Only if Stage 0 says the advantage survives.

The spline method changes three things at once. Before writing a Marquardt–Levenberg optimiser, find
out whether the image model alone explains the gap, because it is much the smaller build.

1. Add cubic B-spline interpolation as a sampling option behind the existing estimator seam, and run
   the existing area-correlation estimator on it in place of bilinear.
2. Measure over the 80 development recordings, paired against the bilinear arm, single variable.

**Exit gate:** if spline sampling alone recovers most of the gap against TurboReg, the optimiser is not
what matters and the remaining work is small. If it recovers little, the gap is the optimiser and
Stage 2 is a full implementation with the cost that implies.

## Stage 2 — Implement the estimator

Only if Stage 1 says the optimiser is needed.

1. Implement the published method behind `PairEstimator`: spline image model, analytic derivatives,
   Marquardt–Levenberg descent, coarse-to-fine over the existing pyramid.
2. Implement it from the paper. TurboReg is GPL and this project is BSD 3-Clause, so the existing
   reflective benchmark binding stays a benchmark binding and no code is taken.
3. Unit coverage to the standard `AreaCorrelationRecoveryTest` and `LinearPyramidTest` set: known
   sub-pixel recovery, sign convention matching the log-ratio fit, honest refusal on a textureless
   frame, the seam reaching the implementation, and the estimator's documented invariance claims tested
   rather than asserted.
4. Measure against TurboReg's own arm on the same recordings. **If our implementation does not reach
   TurboReg's numbers, the finding is about our implementation and not about the method**, and that
   must be stated rather than reported as a method comparison.

**Exit gate:** an implementation that reaches the published method's measured accuracy on the same
recordings, or a recorded account of where it falls short and why.

## Stage 3 — Decide whether it ships, and under what rule

An estimator that is better on some image types and structurally broken on bleaching data is not a
default and probably not an automatic choice either. The options, in increasing order of commitment:

| Option | When it is right |
|---|---|
| Do not ship it | It wins only where the log-ratio fit already does well enough |
| Manual setting only | It wins on some image types but its gain sensitivity makes an automatic choice unsafe |
| Automatic candidate | It wins on some image types **and** the selector has evidence features that separate the safe cases, validated the same way every other candidate was |

Any automatic route requires the full discipline already established: leave-one-source-series-out with
all four movement versions withheld together, nested inner cross-validation for any threshold, per
image type retention only where it never breaches the per-recording guard, improves the mean, and helps
two independent sources — plus a written runtime declaration before the sealed set is opened.

## Stage 4 — Sealed validation

`library/benchmark/v2/benchmarks/sealed_test/` is unopened: ten series, two per image type, 40
recordings. It is the project's only sealed set and it can be spent once.

Frozen implementation, one run, gates declared in writing beforehand:

- zero failures;
- paired mean median error no worse than the current shipped default;
- no image-type regression greater than `0.002 px`;
- runtime within the declared limit;
- the sparse low-light guard passes;
- **and, new to this plan, no regression under gain change** — because an estimator whose weakness is
  brightness change must be tested on brightness change before it ships, and Stage 0 will have built
  that material.

## What makes this expensive, stated plainly

This is not a variation on work already done. It is a second registration algorithm: a continuous image
model, analytic derivatives, a damped least-squares optimiser with its own convergence behaviour and its
own failure modes, plus the test suite that makes any of that trustworthy. The two estimators built or
tested so far each cost days; this is a different order of work, and it is the reason Stage 0 is a hard
gate rather than a formality.

Against that, the honest case for it: a reproduced three-fold accuracy advantage on four image types, on
independent material, under our own solver, is the largest unexplained result this project has, and
every cheap explanation for it has now been eliminated.

## Completion definition

Complete only when:

- the gain-change gate has been built, run and read, and its verdict recorded;
- if it passed, spline sampling has been tested as a single variable before any optimiser was written;
- any implementation is from the published description, with the licence position unchanged;
- an implementation that falls short of the published method is reported as such and not as a verdict
  on the method;
- anything that ships has passed one run against the sealed set, including under gain change, against
  gates written down first;
- the record states plainly whether the log-ratio fit's pairwise accuracy was beatable on this
  benchmark, and at what cost in gain invariance.
