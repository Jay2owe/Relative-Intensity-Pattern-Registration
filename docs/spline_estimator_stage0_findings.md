# Stage 0 — the gain-fade gate, and its verdict

Serves `docs/spline_estimator_plan.md`, Stage 0. The gate was declared in
`docs/spline_estimator_stage0_gate.md` before any gain-fade recording existed. Run 2026-08-18, run id
`gain_fade_gate_v1`, script `scripts/run_gain_fade_gate.ps1`, artefacts under
`library/benchmark/v2/benchmarks/controlled_motion_gain_fade/`.

## Verdict

**Gate outcome 2: the advantage was conditional on the easy case. Stop. Do not build a spline
estimator.**

TurboReg's advantage does not shrink under a brightness change. It inverts by three to four orders of
magnitude, and it does so silently — the engine returns an answer on all 80 recordings and never
reports a failure.

| Image type | R on CLEAN | R under gain fade | What R was on CLEAN |
|---|---|---|---|
| BRIGHTFIELD_DIC | 0.949 | **13 762** | TurboReg marginally better |
| FIDUCIAL_STATIC | 0.647 | **1 249** | TurboReg decisively better |
| DENSE_FLUOR | 1.318 | 20.6 | ours better |
| PHASE | 3.117 | 842 | ours better |
| SPARSE_LOWLIGHT | 2.962 | 1.97 | ours better |
| ALL | 1.432 | 59.9 | ours better |

`R` is TurboReg's median error divided by our shipped default's, on the recordings where both
answered — 80 paired, 0 dropped, from
`controlled_motion_gain_fade/summaries/external_comparison_v1/paired_against_default.csv`, the same
file and the same tool the CLEAN reference came from. Below 1 means TurboReg is better.

The two image types the gate was about are the two where the collapse is worst.

## Gate 4 first, as declared: our own arms do not fail

The gate said to check this before anything else, because a log-ratio criterion failing on a
brightness change would matter more than the estimator question. It did not happen. Our shipped
default's paired median, CLEAN against gain fade, on identical recordings:

| Image type | CLEAN (px) | Gain fade (px) | Ratio |
|---|---|---|---|
| BRIGHTFIELD_DIC | 0.015504 | 0.023440 | 1.51x |
| DENSE_FLUOR | 0.016559 | 0.022686 | 1.37x |
| FIDUCIAL_STATIC | 0.013391 | 0.014996 | 1.12x |
| PHASE | 0.013090 | 0.020077 | 1.53x |
| SPARSE_LOWLIGHT | 0.045494 | 0.063668 | 1.40x |
| ALL | 0.016476 | 0.022948 | 1.39x |

The declared trigger was a doubling on any image type. The worst is 1.53x, and the cost is a fifth of
a hundredth of a pixel. A fade to a quarter brightness costs our default about 40% of its accuracy
and nothing else. Zero failures, 80 of 80, on every one of our arms.

## What the failure looks like, because it is not noise

TurboReg does not lose lock at random. It walks away in one direction, monotonically, tracking the
fade. On `FIDUCIAL_STATIC / fiducial_cage_d1 / SUBPIXEL_RANDOM_WALK`, where the true motion never
leaves a couple of pixels:

| Frame | 1 | 7 | 15 | 23 | 31 | 39 | 47 |
|---|---|---|---|---|---|---|---|
| Reported x (px) | 0.00 | 3.12 | 8.22 | 16.28 | 24.19 | 30.02 | 32.22 |
| Reported y (px) | 0.00 | 3.69 | 9.39 | 17.02 | 24.95 | 30.41 | 31.84 |

This is what a squared-intensity-difference criterion does when the frame dims: sliding the image so
that brighter content covers dimmer content reduces the sum of squared differences, so displacement
buys brightness. The optimiser is working correctly on the wrong objective.

Across the 80 recordings: 64 have a median error worse than 0.1 px, 41 worse than 1 px, 33 worse than
5 px, and 17 worse than 50 px. The worst is 369 px on a field 120 to 250 px wide. The best is
0.012827 px — on the recordings where the fade happens to hurt least it is still excellent, which is
exactly why testing only `CLEAN` hid this.

**Every one of those 80 answers is reported as `ok`.** Nothing in the engine's output says the number
is wrong. An estimator that fails loudly can be guarded against; this one cannot.

## The cause is the criterion, not the image model

This is the part that closes the plan rather than merely pausing it, and it comes from arm 3.

`logratio.core.AreaCorrelation` — our own estimator, added under
`docs/pairwise_estimator_axis_plan.md` — **already carries TurboReg's image model**: an interpolating
cubic B-spline with the Unser prefilter, the same continuous model the published method uses. It does
not carry TurboReg's criterion; it uses normalised cross-correlation. Run on the same recordings,
same recipe, same pyramids, same lag set, same pair plan, same reconciler:

| Image type | Arm 3 on CLEAN (px) | Arm 3 under gain fade (px) | Ratio | TurboReg under gain fade (px) |
|---|---|---|---|---|
| BRIGHTFIELD_DIC | 0.024703 | 0.065830 | 2.66x | 322.57 |
| DENSE_FLUOR | 0.027654 | 0.054611 | 1.97x | 0.47 |
| FIDUCIAL_STATIC | 0.011171 | 0.018652 | 1.67x | 18.73 |
| PHASE | 0.016115 | 0.028485 | 1.77x | 16.91 |
| SPARSE_LOWLIGHT | 0.151016 | 0.219119 | 1.45x | 0.13 |

Same image model, four orders of magnitude apart. The spline model is not what fails under a
brightness change and the criterion is. Since the spline model is the part of the published method
this project does not already have — and it turns out we do already have it — there is nothing left
in the published method that Stage 2 could have built except the part that just failed.

## What this does to the rest of the plan

**Stage 1 was already run, and nobody noticed.** Stage 1 asks to add cubic B-spline interpolation
behind the estimator seam and measure it as a single variable against bilinear. That is exactly what
`docs/pairwise_estimator_axis_findings.md` records: bilinear 0.032 px per pair on fiducial,
Catmull-Rom 0.031, interpolating cubic B-spline 0.0065 — a factor of five from the prefilter alone,
and the change that turned a losing estimator into one that reproduces TurboReg's fiducial result.
Stage 1's exit gate ("if spline sampling alone recovers most of the gap, the optimiser is not what
matters") has therefore already been answered in the affirmative.

**The plan's headline table is stale.** It quotes our area correlation at 0.044587 px on brightfield
and 0.025463 on fiducial against TurboReg's 0.013961 and 0.008316. Those are the pre-prefilter
numbers. The current values on the same recordings are 0.024703 and 0.011171, and the honest CLEAN
statement is that TurboReg leads on three image types by 1.27x to 1.68x, we lead on phase by 2.5x,
and we lead overall at 0.915x. A three-fold unexplained advantage is not what is on the table any
more; a modest one is, and it costs everything above.

**Stages 2, 3 and 4 do not start.** Stage 2 is a Marquardt-Levenberg optimiser on squared intensity
difference. On this evidence it would ship an estimator that beats our default on two image types on
clean data and returns confident three-hundred-pixel answers on a bleaching recording, which is the
condition this plugin's front page claims to solve. There is no selector rule that fixes that,
because the failure carries no signal to select on: the arm reports `ok` throughout.

## What could still be asked, and what it would cost

Only one variant of the question survives, and it is not this plan. TurboReg's model plus a
gain-invariant criterion is not a thing the published method offers, but it is a thing we could
build: keep `AreaCorrelation`'s spline model and replace the exhaustive search with a
Marquardt-Levenberg descent on a normalised criterion. That is the optimiser question with the
criterion problem removed. It is also a smaller claim than this plan's, because the gap it is chasing
is now 1.27x to 1.68x on three image types rather than three-fold on four, and arm 3 already loses to
our shipped default on all five types under gain fade (1.24x to 3.44x). Nothing here recommends it;
it is recorded so the question is not re-derived from scratch.

## Fairness, stated plainly

A user running TurboReg on bleaching data would probably normalise the frames first, and TurboReg is
not being blamed for a use it does not claim. The claim being tested is narrower and is the one the
plan rested on: that the published method's accuracy advantage is a property of the method. Under a
brightness change it is not, and an advantage that requires the user to pre-normalise is not an
advantage the log-ratio criterion needs.

The comparison itself is unchanged from the CLEAN one: identical recordings apart from the gain,
TurboReg's pair estimate inside our own multi-lag solver, all arms scored by the same metric in the
same code path.

## Artefacts

| What | Where |
|---|---|
| 80 gain-fade recordings | `library/benchmark/v2/benchmarks/controlled_motion_gain_fade/` |
| Our six selector arms | `.../summaries/selector_comparison_v1/` |
| Estimator axis, four arms | `.../summaries/pair_estimator_v1/` |
| TurboReg joined to our arms | `.../summaries/external_comparison_v1/` |
| Stage logs and exact commands | `library/benchmark/v2/runs/gain_fade_gate_v1/` |

The CLEAN tree is untouched. The gain-fade recordings live in their own root because every summary
tool walks a root and aggregates whatever it finds, so a second condition beside the first would
blend the two tables and overwrite the CLEAN record on the next re-run.

`library/benchmark/v2/benchmarks/sealed_test/` remains unopened.
