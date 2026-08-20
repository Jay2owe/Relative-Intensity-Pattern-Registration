# Items 3 and 5 of the performance plan — both refused, one of them without being built

Serves items 3 and 5 of `docs/performance_optimisation_plan.md`. Run 2026-08-19, after
`docs/newton_refinement_plan.md` ended at Stage 2. Those two items had been folded into that plan's
Stage 2 as extra arms; the plan stopped before they were built, so they got the round of their own
that the fold was meant to avoid. It cost about ten minutes of machine time.

## Both are refused

| Item | What it is | Verdict |
|---|---|---|
| 3 | Refine on a subsample, then confirm at full density | **Measured and refused.** 15.2% of processor time against a 20% bar, and worse accuracy on three image types |
| 5 | Prune long lags from the movement bound before they run | **Refuted before building.** Its premise is false on this benchmark: 0 pairs of 2,090 are refused |

Nothing shipped. `AREA_CORRELATION` did not move: across the two benchmark runs, all three shared arms
reproduce at **`0.000e+00`** on every error column. 307 tests pass.

## Item 5 — the premise is false, so there is nothing to build

The item rests on one claim, quoted from the plan:

> on a recording where the frames have drifted far apart the long-lag pairs are refused anyway, after
> paying full cost. Refusing them from the movement bound before they run costs nothing and saves
> whatever fraction they are.

**The saving is therefore exactly the fraction of planned pairs that are refused.** That is a number,
and measuring it is much cheaper than building the feature, so it was measured first.
`research/newton_stage2_diagnosis/LagRefusalProbe.java` runs every planned pair of one recording per
image type — the steady-drift profile, which is the worst of the four — through both shipped
estimators and counts refusals by lag:

```
recording                          estimator          lag1  lag2  lag4  lag8 lag16   refused
FIDUCIAL_STATIC/fiducial_cage_d4   log_ratio_fit      0/47  0/46  0/44  0/40  0/32      0.0%
FIDUCIAL_STATIC/fiducial_cage_d4   area_correlation   0/47  0/46  0/44  0/40  0/32      0.0%
SPARSE_LOWLIGHT/sparse_ssbd_fig3a  log_ratio_fit      0/47  0/46  0/44  0/40  0/32      0.0%
SPARSE_LOWLIGHT/sparse_ssbd_fig3a  area_correlation   0/47  0/46  0/44  0/40  0/32      0.0%
BRIGHTFIELD_DIC/..._type3_01       log_ratio_fit      0/47  0/46  0/44  0/40  0/32      0.0%
BRIGHTFIELD_DIC/..._type3_01       area_correlation   0/47  0/46  0/44  0/40  0/32      0.0%
PHASE/phase_strack_lysobacter_01   log_ratio_fit      0/47  0/46  0/44  0/40  0/32      0.0%
PHASE/phase_strack_lysobacter_01   area_correlation   0/47  0/46  0/44  0/40  0/32      0.0%
DENSE_FLUOR/dense_ssbd197_fig3     log_ratio_fit      0/47  0/46  0/44  0/40  0/32      0.0%
DENSE_FLUOR/dense_ssbd197_fig3     area_correlation   0/47  0/46  0/44  0/40  0/32      0.0%

  planned pairs 2090, refused 0  ->  0.00% of the work is thrown away
```

**Zero. Not few — none, at any lag, on any image type, with either estimator.** The reason is plain
once looked at: the development recordings drift about 14 px over 48 frames on 96 to 120 pixel frames,
so a lag-16 pair still overlaps almost completely. The condition the item optimises for does not occur
here.

**A second obstacle, which matters if anyone revisits this.** The item's mechanism is to read the
refusal off the movement-bound pass, which is free because that pass already runs. **It does not run
on this benchmark**: `SelectorComparisonBenchmark.categoryBase` sets `autoMaxShift(false)` and hands a
known bound per movement profile, because that is what makes the benchmark controlled. So even where
the premise held, the free ride would not be there, and the item would have to pay for a coarse pass
of its own — which is the one thing the item claims not to cost.

**What this does and does not say.** It says the item is worth nothing *on the development set*, and
that the development set cannot measure it. It does not say the item is worthless in general: the plan
cites `VID47_C1`, a real recording with a 223 px jump, and on material like that the refusal rate
could be substantial. **Anyone reviving it needs recordings that actually drift out of overlap, and
should measure the refusal rate on those before writing any code.** That is the whole of the work
until such material exists.

## Item 3 — built, measured, refused

`AREA_CORRELATION_SUBSAMPLED` runs the refinement's first two rounds at double stride — a quarter of
the pixels — and the remaining rounds at full density, with the incumbent score re-read on the full
sample set at the crossover so that a full-density candidate is never compared against a subsampled
incumbent. Everything else is the same code as `AREA_CORRELATION`.

Measured over the 80 development recordings, four arms, against the gate written into
`NewtonRefinementBenchmark` before the run:

| Condition | Limit | Measured | |
|---|---|---|---|
| Paired median, every image type | within 0.001 px | fiducial worse by **0.001711 px** | fail |
| Worst recording, every image type | no worse than the grid | fiducial **0.057938** vs **0.034827 px** (1.66x), brightfield +0.001, phase +0.003 | fail |
| Mean processor seconds | ≥20% faster | **15.20% saved** | fail |

| Image type | Grid median | Subsampled median | Difference | Grid worst | Subsampled worst | CPU saved |
|---|---|---|---|---|---|---|
| BRIGHTFIELD_DIC | 0.024703 | 0.024609 | -0.000094 | 0.044600 | 0.045553 | 17.6% |
| DENSE_FLUOR | 0.027654 | 0.026027 | -0.001627 | 0.198180 | 0.175188 | 11.6% |
| FIDUCIAL_STATIC | 0.011171 | **0.012882** | **+0.001711** | 0.034827 | **0.057938** | 10.9% |
| PHASE | 0.016115 | 0.014916 | -0.001199 | 0.025680 | 0.028895 | 15.2% |
| SPARSE_LOWLIGHT | 0.151016 | 0.149431 | -0.001585 | 16.992380 | 16.990367 | 15.5% |

**The plan predicted where this would hurt and it was right.** Its own risk note reads: *"the effect is
probably image-type dependent — a sparse bead field has few informative pixels to spare, which is
exactly where the estimator currently wins."* Fiducial/static is the image type with the fewest
informative pixels and it is the only one where both accuracy conditions break, with the worst
recording 1.66 times worse. Taking three quarters of the samples away costs most where there were
fewest to begin with.

**Why the saving is 15% and not the predicted 20-30%, which is the more useful half of this finding.**
The refinement's **first round is nearly free already**. It starts at the integer offset the sweep
returned with a spacing of one pixel, so all nine of its grid points are whole-pixel offsets, and
`correlation` has a fast path for those that indexes straight into the array instead of running
sixteen spline taps. Subsampling the cheapest round of the five saves almost nothing; only the second
round, at a spacing of a quarter pixel, is interpolated and actually pays. The estimate of 20-30%
assumed every round cost the same. **Any future attempt at this should subsample rounds two onward and
leave round one alone** — though on these numbers it would still have to answer the fiducial accuracy
loss, which is the harder half.

## What was kept

`PairEstimator.Kind.AREA_CORRELATION_SUBSAMPLED` stays as an unshipped measurement arm with its
numbers in its javadoc, alongside `AREA_CORRELATION_LINEAR` and `AREA_CORRELATION_NEWTON`. The
selector names none of the three. The `refine` method now takes a coarse-round count whose default of
zero executes exactly the arithmetic it always did — proven by the three shared arms reproducing at
`0.000e+00`.

`research/newton_stage2_diagnosis/LagRefusalProbe.java` stays too. It is eight lines of real work and
it is the thing that stops item 5 being built twice.

## Where the evidence lives

| What | Where |
|---|---|
| The item 3 run and its gate | `library/benchmark/v2/benchmarks/controlled_motion/summaries/subsampled_refinement_v1/` |
| The benchmark and the gate it computes | `src/test/java/logratio/NewtonRefinementBenchmark.java`, run with `-Dlogratio.candidate=area_correlation_subsampled` |
| The item 5 probe | `research/newton_stage2_diagnosis/LagRefusalProbe.java` |
| The plan these serve | `docs/performance_optimisation_plan.md`, items 3 and 5 |
