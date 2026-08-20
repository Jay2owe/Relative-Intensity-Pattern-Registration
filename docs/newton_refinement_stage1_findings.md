# Newton refinement, Stage 1 — the third estimator value, and the proof the other two did not move

Serves Stage 1 of `docs/newton_refinement_plan.md`. Run 2026-08-19, directly after Stage 0. Nothing
ships in this stage: it builds the refinement and proves the shipped one is untouched. Stage 2 is
where it is measured on real recordings, and Stage 2 is where the idea is most likely to die.

## The gate, first

**PASS. The shipped estimator is provably unmoved: 800 arm-runs reproduce at a maximum absolute
difference of `0.000e+00`, and not one resolved recipe identifier changed.**

| Benchmark | Arm-runs | Max absolute difference, any error column | Resolved-recipe differences |
|---|---|---|---|
| Selector comparison, six arms | 480 | **0.000e+00** | **0** |
| Pair estimator comparison, four arms | 320 | **0.000e+00** | **0** |

Both halves completed all 480 and all 320 registrations with zero failures. The comparison ignores
`total_seconds` and `cpu_seconds`, which are clocks rather than results; every other column — status,
median, 90th percentile and maximum error, the resolved recipe identifier, its full description and
the details string — matches exactly. Evidence, both runs and the scripts that produced them, in
`library/benchmark/v2/benchmarks/controlled_motion/summaries/newton_gate_v1/`.

**A second, unasked-for confirmation fell out of it.** Every arm's mean median error in the *before*
half matches the corresponding figure in the previous gate's after half — 0.020601, 0.197297,
0.266940 and 0.160602 px — so nothing else in the tree drifted between 2026-08-18 and this run
either.

## What was added

**One switch, and everything else is one piece of code.** `AreaCorrelation` gained a `Refiner`
enumeration with two values. `GRID` is the shrinking three-by-three quadratic search that ships;
`NEWTON` is a step read off the correlation surface's own derivatives. They are reached through a new
overload, `align(a, b, options, refiner)`; the existing three-argument `align` delegates to it with
`GRID` and is otherwise unchanged. The window preparation, the interpolating cubic B-spline, the
exhaustive integer sweep at the coarsest level, the pyramid descent, the shift clamp and the
reporting are the same lines of code for both, so a Stage 2 measurement that differs between the two
arms differs because of the refinement and because of nothing else.

**`PairEstimator.Kind.AREA_CORRELATION_NEWTON`** is the third value on the estimator axis, with id
`area_correlation_newton`. It declares the same cached bytes per pixel as `AREA_CORRELATION`, because
it memoises the same prepared window, and it reads the same log-domain pyramid.

**The derivation lives in the method javadoc**, as Stage 0 required, not in a notebook: `newtonRefine`
carries the unit-length argument that turns the correlation into a sum of squares, the closed forms
for the gradient and the Gauss-Newton Hessian in plain sums, the three guards and the quantified
boundary risk.

Three implementation details the Stage 0 prototype had already paid for, carried across rather than
rediscovered:

- **No allocation per sample.** The basis weights and their derivatives are written into a
  caller-owned sixteen-element scratch array. The prototype's first version built four `double[4]`
  arrays per sample, which is 147,456 allocations per pass on a 192-pixel frame, and that cost would
  have been charged to the method rather than to the code that wrote it.
- **The line search costs a score, not a surface.** `Window.sampleInterior` is a value-only pass at
  exactly the positions `Window.sampleWithGradient` accepts, so a trial that may be rejected is about
  half the arithmetic of a gradient pass — and, more importantly, the trial and the gradient that
  proposed it are evaluated over one sample set. `AreaCorrelation.correlation` could not be reused
  for this: it drops to bilinear at the frame border where the gradient pass refuses the position
  outright, and a line search judged on a different sample set from its own gradient is comparing two
  different functions.
- **Two guards on the step.** It is capped at one pixel, so a badly conditioned Hessian cannot carry
  the estimate out of the cell the integer sweep chose; and when the Hessian is not positive definite
  the refinement stops rather than stepping on a saddle. Whether stopping is the right fallback, or
  whether it should hand back to the grid, is a Stage 2 question and is deliberately left open.

## What was deliberately not touched

- **`AREA_CORRELATION` did not move**, which is the whole reason this is a third value rather than an
  edit to the second. The automatic selector's retained candidates were fitted to measurements of
  that exact code, and moving it by one bit would invalidate the model and spend a sealed test set to
  revalidate it.
- **`AutomaticRegistrationSelectorModel` still names only `AREA_CORRELATION`.** The shipped model
  cannot choose the new value, at any confidence, on any image type, because it does not know it
  exists.
- **The README and the sweep plan's parameter help still list two estimators.** That is the existing
  convention rather than an oversight: `AREA_CORRELATION_LINEAR` is likewise absent from both, and
  Stage 5 of the plan is where exposure is decided. What the enumeration does give away for free, and
  what a reader should know, is that the value now appears in the advanced dialog's estimator
  dropdown and parses from `estimator=area_correlation_newton` in macro and batch options — exactly
  as the linear-pyramid arm does. Nothing selects it without being asked.

## The port check — the new code reproduces Stage 0 exactly

Stage 0's accuracy table was produced by a prototype outside `src/`. Running the same ten shifts
through the shipped pipeline, with the refiner as the only difference, reproduces every figure to all
six decimal places:

| True shift | Grid error (px) | Newton error (px) |
|---|---|---|
| (0.00, 0.00) | 0.000000 | 0.000000 |
| (0.25, 0.00) | 0.000448 | 0.000149 |
| (0.50, 0.50) | 0.000439 | 0.000138 |
| (0.37, -0.62) | 0.000257 | 0.000139 |
| (1.50, -0.50) | 0.000159 | 0.000049 |
| (2.40, -1.70) | 0.000254 | 0.000169 |
| (-3.20, 2.85) | 0.000474 | 0.000229 |
| (4.05, 3.95) | 0.000692 | 0.000110 |
| (0.10, -0.10) | 0.000517 | 0.000113 |
| (2.50, 9.50) | 0.000357 | 0.000243 |
| **mean** | **0.000360** | **0.000134** |
| **worst** | **0.000692** | **0.000243** |

Identical to six decimals across ten shifts means the two implementations take the same trajectory,
not merely land in the same place. **The cost figure from Stage 0 therefore carries unchanged: 2.21
times less arithmetic than the grid, counted rather than timed.** It was not re-measured here, and
Stage 1 does not gate on it — the shipped code is not instrumented with pass counters, and adding
counters to it to reproduce a number that is already established would be a change to the estimator
for no gain.

## How the gate was run, and why it has to be run this way

The plan's gate is: run both comparison benchmarks with `-Dlogratio.rewrite=true` and diff
`all_recordings.csv`. **Diffing against the files on disk measures the wrong thing.** Those files are
regenerated artifacts, so a new run differs from them by whatever else has changed since they were
written — `docs/performance_optimisation_findings.md` records that trap being walked into once
already, where the difference shown was a selector retrain rather than the change under test.

So the current sources were compiled **with the change absent** into a private directory and run to
produce a real before; then the change was compiled into a second private directory and run again.
Private directories because another agent works in this tree and can empty `target/` mid-run. The two
runs used the same machine, the same dependencies and the same benchmark tree, back to back.

```
javac -d <build>/classes      -cp <deps>      src/main/java/**.java
javac -d <build>/test-classes -cp <build>/classes;<deps>  src/test/java/**.java

java -Xmx8g -Dlogratio.rewrite=true -Dlogratio.noImages=true \
     -cp <build>/test-classes;<build>/classes;<deps> logratio.SelectorComparisonBenchmark <project>
java -Xmx8g -Dlogratio.rewrite=true \
     -cp <build>/test-classes;<build>/classes;<deps> logratio.PairEstimatorComparisonBenchmark <project>
```

The comparison joins the two files on the seven identifying columns and compares every remaining
column. `total_seconds` and `cpu_seconds` are excluded because they are clocks rather than results;
numeric `*_px` columns are compared as numbers and everything else — status, resolved recipe
identifier, the full resolved recipe description, details — as text.

**The comparison tool was itself checked before being relied on.** Replayed against the saved
before/after pair from `summaries/performance_gate_v1/`, which is known to have passed, it reproduces
that gate's published result exactly — 800 arm-runs, `0.000e+00`, zero text differences — and it
fails when a single error value is perturbed by hand.

**One thing the timings this time cannot be read for.** The before-half shared the machine with
`mvn -o clean test` and a packaging run, so its elapsed and processor seconds are inflated relative to
the after-half. That is exactly why the gate excludes them, and it is recorded here rather than left
for someone to notice in the CSVs: no speed claim of any kind may be read off this pair of runs.
Stage 2 measures cost properly, on its own runs, with the machine to itself.

## Tests added

`src/test/java/logratio/core/AreaCorrelationNewtonTest.java`, seven tests:

- **The basis derivatives sum to zero** at ten fractional positions, and the basis itself sums to one.
  A derivative set that did not cancel would report a gradient on a constant image, manufactured out
  of the background level and worst where the background is brightest.
- **The basis derivatives are the derivatives of the basis**, against a central difference at twenty
  positions. The sum-to-zero test alone cannot catch two weights whose errors cancel.
- **The analytic gradient matches a numerical derivative**, in two regimes. Central difference at
  interior offsets; **one-sided** at whole-pixel ones, where a central difference straddles the step
  in the sample set and measures that instead — it disagrees by 80% there, and it is the finite
  difference that is wrong.
- **The score and the gradient agree about which positions exist**, swept off all four edges of the
  frame.
- **The refinement recovers ten known sub-pixel shifts** no worse than the grid and inside the plan's
  0.000600 px.
- **The seam reaches it** and `PairEstimator.Kind.of` parses its id, its hyphenated form and its
  constant name.
- **The grid refinement is unchanged and the default is still `LOG_RATIO_FIT`** — the shipped entry
  point, the explicit `GRID` refiner and the seam all return one identical fit.

`mvn -o clean test`: **302 tests, 0 failures, 0 errors**. The jar builds.

## What Stage 1 did not test, and Stage 2 must

Unchanged from Stage 0, and none of it is closed by this stage:

- **Real frames.** Everything asserted about accuracy here is still one synthetic analytic image,
  which is the best-conditioned input this method will ever see. The gate proves the *other* two
  estimators are unmoved; it says nothing about whether the new one is any good.
- **Sparse low-light.** The image type where area correlation already loses lock at 17 px, and where
  the ragged validity boundary that Stage 0 quantified — 189 samples of 35,721 leaving the overlap at
  a whole-pixel offset, worth about 7e-04 px — is most likely to bite. It must be reported separately
  and must not be averaged away.
- **Ill-conditioned peaks**, and whether stopping is the right response to a Hessian that is not
  positive definite, or whether the refinement should hand back to the grid.

## Where the evidence lives

| What | Where |
|---|---|
| Both runs, both halves, and the gate result | `library/benchmark/v2/benchmarks/controlled_motion/summaries/newton_gate_v1/` |
| The scripts that produced them | the same folder: `build.sh`, `run.sh`, `compare.py` |
| The derivation | `AreaCorrelation.newtonRefine` javadoc |
| Stage 0: derivation, checks, numbers | `docs/newton_refinement_stage0_findings.md` |
| Stage 0 prototype | `research/newton_stage0_prototype/` |
| Why the gate must be a before/after pair of builds | `docs/performance_optimisation_findings.md` |
