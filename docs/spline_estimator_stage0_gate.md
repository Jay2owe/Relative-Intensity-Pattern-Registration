# Stage 0 gate — declared before the gain-fade run

Serves `docs/spline_estimator_plan.md`, Stage 0. Written 2026-08-18, **before any gain-fade recording
existed and before any gain-fade number was computed**. Nothing in this file may be edited once the
run starts; the verdict goes in `docs/spline_estimator_stage0_findings.md`.

## The question

Every number that motivates the spline plan was measured on condition `CLEAN`: 80 of 80 controlled
recordings, the third-party comparison, the 96-recipe sweep, the selector training and the locked
test. No external engine in this project has ever been measured under a brightness change.

TurboReg optimises squared intensity difference, which is not invariant to gain. The log-ratio fit is
built to be. If TurboReg's advantage is an artefact of testing only the easy condition, a spline SSD
estimator would look better in the benchmark and fail on real bleaching data, and the rest of the
plan should not be built.

## The material

The same 20 development source series and the same four movement paths as the CLEAN set, same seeds,
same code, one condition changed: `Benchmark.Condition.GAIN_FADE`, a smooth fade to a quarter of the
starting brightness across the 48 frames (`FADE_LOG2 = -2.0`), applied after decimation and before
noise. 80 recordings, in their own tree at
`library/benchmark/v2/benchmarks/controlled_motion_gain_fade/` so nothing can blend into the CLEAN
record.

## The arms

| Arm | What it is | Criterion | Image model |
|---|---|---|---|
| `1_shipped_default_log_ratio` | what a user gets today | log-ratio fit | bilinear samples |
| `2_recommended_log_ratio_fit` | recommendation, no automatic override | log-ratio fit | bilinear samples |
| `3_recommended_area_correlation` | same recipe, estimator swapped | normalised cross-correlation | interpolating cubic B-spline |
| `22_real_turboreg_translation_multilag_rcc` | TurboReg's pair estimate in our multi-lag solver | squared intensity difference | interpolating cubic B-spline |

Arm 3 matters to the reading and is not a formality. It already carries TurboReg's image model — an
interpolating cubic B-spline, `logratio.core.AreaCorrelation` — and does not carry TurboReg's
criterion. So the three arms separate the two candidate causes: if TurboReg degrades under gain fade
and arm 3 does not, the cause is the criterion and not the model.

## The reference values

Paired medians on the CLEAN set, already on disk in
`library/benchmark/v2/benchmarks/controlled_motion/summaries/external_comparison_v1/paired_against_default.csv`,
computed by the same tool that will produce the gain-fade table. `R` is TurboReg's median divided by
the shipped default's median on the recordings where both answered; below 1 means TurboReg is better.

| Scope | TurboReg (px) | Shipped default (px) | R (CLEAN) |
|---|---|---|---|
| ALL | 0.023599 | 0.016476 | 1.432 |
| BRIGHTFIELD_DIC | 0.014714 | 0.015504 | **0.949** |
| DENSE_FLUOR | 0.021825 | 0.016559 | 1.318 |
| FIDUCIAL_STATIC | 0.008670 | 0.013391 | **0.647** |
| PHASE | 0.040800 | 0.013090 | 3.117 |
| SPARSE_LOWLIGHT | 0.134759 | 0.045494 | 2.962 |

TurboReg's advantage on this benchmark is two image types: `FIDUCIAL_STATIC` decisively and
`BRIGHTFIELD_DIC` marginally. Those two are what the gate is about. The plan's own headline table
quotes larger, older figures; they predate the B-spline interpolator recorded in
`docs/pairwise_estimator_axis_findings.md` and are superseded by the row above.

## The gate

Read `R` per image type from the gain-fade run's `paired_against_default.csv`, same file name, same
tool, same metric.

1. **Advantage holds — Stage 1 starts.** `R <= 1.0` under gain fade on **both** `FIDUCIAL_STATIC`
   and `BRIGHTFIELD_DIC`. TurboReg is still at least as accurate as our shipped default where it was
   on CLEAN, so its advantage is not a gain-invariance artefact.
2. **Advantage was conditional on the easy case — stop, build no spline estimator.** `R > 1.0` under
   gain fade on both of those types.
3. **Mixed — treat as not surviving, and say which one held.** `R <= 1.0` on exactly one of them.
   One image type with a marginal advantage is the same evidence base
   `docs/pairwise_estimator_axis_plan.md` already spent three stages on, and it does not justify a
   second registration algorithm.
4. **Our own arms degrade badly — this takes priority over the estimator question entirely.** The
   shipped default's paired median under gain fade exceeds **twice** its CLEAN value on any image
   type. The recordings are identical apart from the gain, so a doubling is the criterion failing on
   the case it was built for, and that is a bigger finding than anything else in the plan.

Gate 4 is checked first and reported whatever the others say.

## What is not a gate but will be reported

- Arm 3 against arm 2 under gain fade: same recipe, same everything, estimator swapped. This is what
  says whether the degradation, if any, is the criterion or the image model.
- Any recording where an arm fails outright rather than answering badly, counted and named.
