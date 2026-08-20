# Performance optimisation, items 1 and 6 — what was measured

Serves `docs/performance_optimisation_plan.md`. Written 2026-08-18, after
`docs/pairwise_estimator_axis_findings.md` closed the estimator axis. Read that first; this document
assumes it.

## The verdict in one line

**Item 1 ships and moved nothing: the prepared window is now built once per frame per pyramid level
instead of once per pair, an 88.3% cache hit rate, worth 4 to 6 percent of an area-correlation
recording and about 20 percent of a pair alignment on 512-pixel frames. Item 6's premise was wrong —
running recordings concurrently is about 5 percent *slower* — so it was built, measured, and then
**reverted; only the measurement is kept.** Item 1 was held to the plan's gate and passes it: 800
arm-runs over the 80 development recordings, maximum absolute difference `0.000e+00`.

## The gate, and why the obvious way to run it would have lied

The plan's gate is: run `logratio.SelectorComparisonBenchmark` and
`logratio.PairEstimatorComparisonBenchmark` with `-Dlogratio.rewrite=true`, then diff
`all_recordings.csv` against a copy taken first.

**Taking that copy from disk would have been wrong**, and the first attempt did exactly that. The
`all_recordings.csv` files sitting in
`library/benchmark/v2/benchmarks/controlled_motion/summaries/` had been written *before* the selector
was retrained: their automatic arms resolve recipes from the previous 40-candidate `linear_gain`
model at a confidence threshold of 0.1 px, not the two-candidate `image_type_rule` model that ships.
Diffing a new run against them measures the retrain, not the change under test, and would have shown
a large difference that had nothing to do with the code.

So the gate was run properly instead: **the current sources with the change reverted were compiled to
a separate directory and run to produce a real before, then the change was compiled and run again.**
Nothing else differed between the two — same test classes, same model, same machine, back to back.

| Benchmark | Arm-runs | Max absolute difference, any error column | Resolved-recipe differences |
|---|---|---|---|
| Selector comparison, six arms | 480 | **0.000e+00** | **0** |
| Pair estimator comparison, four arms | 320 | **0.000e+00** | **0** |

Both runs completed 480 and 320 registrations with zero failures. The comparison ignores
`total_seconds` and `cpu_seconds`, which are clocks rather than results; every other column, including
the resolved recipe identifier and its full description, matches exactly. Evidence — both runs and the
comparison — is in `summaries/performance_gate_v1/`.

## Item 1 — cache the prepared window per frame and level

`AreaCorrelation.Window.of` rebuilt both frames' intensity plane, mean and B-spline coefficients on
every pair. The preparation is a pure function of the plane, so it is now memoised **on the plane**,
in `LogPlane.areaWindow`, rather than in a cache of its own. That choice is the whole design: the
window's lifetime becomes exactly the plane's, so it is evicted when `PyramidCache` evicts the
pyramid, cleared when the cache is cleared, and cannot outlive what it describes.

### It hits

Instrumented on one 48-frame fiducial recording, multi-lag at lags 1, 2, 4, 8, 16:

```
Window.of: 1477 cache hits, 195 builds -> 88.3% hit rate
```

195 builds is 48 frames times 4 pyramid levels, plus 3 — **one build per frame per level**, which is
the floor. Without the cache all 1,672 calls would have built.

### What it is worth

Measured directly, median of nine repetitions after two discarded warm-ups, on the same recording,
two independent rounds:

| | Median | Ratio |
|---|---|---|
| No window cache | 0.5294 s, 0.4954 s | |
| Window cached | 0.4978 s, 0.4748 s | **0.940x, 0.958x** |

So **4 to 6 percent of a whole area-correlation recording** on these roughly 100-pixel frames, inside
the plan's predicted 5 to 10 percent of the estimator's time.

**It is worth much more on larger frames, and the reason is worth knowing.** Preparing both frames of
a pair, at every level, against one whole `AreaCorrelation.align`:

| Frame | Preparation | Share of one pair alignment |
|---|---|---|
| 120 x 120 | 1.85 ms | 5.3% |
| 512 x 512 | 48.0 ms | **20.3%** |

The share nearly quadruples because `maxSamples` is 200,000: above that the correlation subsamples on
a stride and stops reading every pixel, while the B-spline prefilter still runs over all of them. The
larger the frame, the larger the fraction of the estimator this cache removes.

**It does not show up in the benchmark, and that is not a contradiction.** Arm 3 of the pair estimator
comparison moved from 1.703 s to 1.637 s, a ratio of 0.961 — but the pure log-ratio arm 1, which this
change cannot touch, moved 0.966 in the same pair of runs. Run-to-run drift on this machine is about
4 percent, and a 5 percent cut in part of a recording is under it. That is why the effect was measured
in isolation rather than read off the benchmark.

### The memory it costs is declared, not hidden

Two float arrays per level is 8 bytes per pixel, 11 with the 1.33x for the levels below the base, on
top of the pyramid's own 18. A pyramid held for area correlation therefore costs about **29 bytes per
pixel, 1.6 times the bare figure** — and `PyramidCache.capacityFor` now sizes the cache from that
number. Had it kept using 18, a long recording would hold 1.6 times the pyramids the budget was sized
for and fail with an `OutOfMemoryError` instead of merely being slow.
`PairEstimator.cachedBytesPerPixel()` is where an estimator declares this; the log-ratio fit declares
zero and is unaffected.

## Item 6 — parallelise the offline sweep across recordings. The premise was wrong

The plan predicted that running four recordings at once with a quarter of the worker pool each would
cut the 112-recipe sweep's wall clock roughly threefold. **It does not.** Measured on 160
registrations over the 80 development recordings, 16 cores:

| Recordings at once | Worker threads each | Wall clock |
|---|---|---|
| 1 | automatic (15) | **134 s, 145 s** |
| 2 | 7 | 139 s |
| 4 | 3 | 143 s, 151 s |

Four at once is about **5 percent slower**, reproducibly, in both orderings.

**Why the prediction failed.** It assumed a registration parallelises only *within* a pair alignment.
It does not: `Registration` fans out *across* pair alignments, and multi-lag over 48 frames plans 209
of them. One recording already has more independent work than this machine has cores, so a second
level of fan-out on top of a saturated first level adds scheduling and cache pressure and nothing
else. The serial stretches the prediction hoped to hide behind — opening the stack, reconciliation,
the warp, the file writes — are too short to hide anything.

**Nothing shipped. The code was reverted.** The fan-out worked and was proven correct, but working
code that buys nothing is a cost with no return: every future reader of the sweep would have had to
understand a second level of parallelism, a memory-division rule and a concurrency flag in order to
learn that the right setting is the one it already had. `FullSelectorFactorialBenchmark` is back to
one recording at a time, with a five-line comment at the loop recording the measurement so nobody
builds it again. This document is the rest of that record.

If the case the measurement does not cover ever arrives — many more cores than a recording has pairs,
or a lag set small enough that the inner fan-out runs dry — rebuilding it is half a day, and the
design that worked is described above.

**Correctness was proven independently of speed, before it was reverted.** The whole sweep was run
twice on a scratch copy of the 80 inputs, once serial and once four-at-once, and every
`transforms.csv` compared:

```
serial   transform lines: 16000
parallel transform lines: 16000
=== identical transforms? ===
IDENTICAL
```

640 registrations, byte-identical output. So the revert is a judgement about value, not a retreat
from a broken change. After reverting, the same four-recipe sweep was run once more and its
`transforms.csv` files are byte-identical to the pre-revert serial run — the removal changed nothing
either.

## Two defects found on the way, both fixed

**The oracle arm could not rebuild an area-correlation recipe.** `SelectorComparisonBenchmark`'s
`recipeById` searched `RegistrationRecipe.sweptCandidates()`, the 96 log-ratio recipes, while the
sweep has covered 112 candidates since the estimator axis was added. On five of the 80 development
recordings the per-recording best is an area-correlation recipe, so the oracle arm threw on exactly
the recordings where the second axis won. It now searches `allCandidates()`. This is unrelated to the
performance work; it was blocking the gate.

**`mvn -o test` failed on a stale build. The mechanism is now closed.**
`AutomaticRegistrationSelectorModel.MODEL_KIND` is a `public static final String`, which javac inlines
into every class that reads it. `AutomaticRegistrationSelectorTest` had been compiled at 15:34 with
the old value baked in; training rewrote the model source at 15:45; Maven's incremental compilation
saw a test source older than its class and did not recompile it, so the test asserted against a
constant that no longer existed. **This is the same failure mode that scored the sealed set against
the wrong model on its first opening.**

Fixed on 2026-08-19 rather than merely noted. The model's four scalars — `FEATURE_COUNT`,
`MODEL_KIND`, `TRAINED_ON` and `CONFIDENCE_THRESHOLD` — are now declared through an identity method,
`readAtRuntime(...)`, which makes each initialiser a method call rather than a constant expression.
A field initialised that way is not a compile-time constant, so javac compiles every reader to a real
field read and a stale class picks up the current value instead of a remembered one.
`logratio.FullSelectorTraining` emits the wrappers, so the next retrain keeps them.

Both halves were proved by simulating the failure: build everything, change the model, recompile only
the model, run the test that was built before the change.

| Model declares | Stale test class reads | New guard |
|---|---|---|
| bare literal | the **old** value | **fails**, naming the stale build |
| `readAtRuntime(...)` | the **new** value | passes |

`ModelConstantsAreReadAtRuntimeTest` carries both halves: one test compares a direct reference against
a reflective read, which catches a build that has already gone stale and says so in plain words; the
other reads the generated source and fails if any scalar is declared as a bare literal again, which no
runtime check can see. That second test was confirmed to fail on a bare literal.

## What this did to the summaries on disk, stated plainly

Running the gate with `-Dlogratio.rewrite=true` regenerated `summaries/selector_comparison_v1/` and
`summaries/pair_estimator_v1/`, which had held pre-retrain runs. Every arm that names an explicit
recipe reproduces to `0.000e+00`; the arms that ask the shipped model what to do moved, because the
model was retrained between when those files were written and now. In `pair_estimator_v1` that is
arm 1, from 0.016476 px to 0.013411 px median of median.

The consequence to carry forward: **the "vs shipped default" ratios in
`docs/pairwise_estimator_axis_findings.md` Stage 3 were measured against the previous default and are
no longer reproducible from the files.** They are not wrong. The retrained default is simply better,
and on brightfield and fiducial it is better *because it now uses area correlation itself* — so
comparing area correlation against it is no longer a single-variable comparison. Arm 2 against arm 3
is that comparison, and it is unchanged to six decimal places.

What survives of the pre-retrain files is in `summaries/*__pre_retrain_partial/`, with a README saying
exactly which files were saved and which were regenerated before a copy was taken. The per-recording
`comparison.csv` artifacts were rewritten and their pre-retrain versions are gone.

## Item 2 is not started, deliberately

The Newton step on the correlation is the 40-50 percent cut and it changes numbers, so it is a plan of
its own with its own exit gate, not a tidy-up. **That plan is now written and not started:
`docs/newton_refinement_plan.md`.** It ships the Newton refinement as a third value on the estimator
axis rather than as an edit to the shipped one, so the validated model stays valid throughout and a
bad result costs only the measurement. Items 3 and 5 are folded into its Stage 2 as extra arms; item 4
is a pipeline change and stays where it is.

## Where the evidence lives

| What | Where |
|---|---|
| Gate, before and after, both benchmarks | `summaries/performance_gate_v1/` |
| Gate result and per-arm timings | `summaries/performance_gate_v1/gate_result.txt` |
| Pre-retrain files that were overwritten, partial | `summaries/*__pre_retrain_partial/` |
| The plan these items serve | `docs/performance_optimisation_plan.md` |
| The estimator axis this follows | `docs/pairwise_estimator_axis_findings.md` |
