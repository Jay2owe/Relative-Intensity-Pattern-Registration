# Handoff — pairwise estimator axis, after Stage 6

Paste the block below to the next agent. Written 2026-08-18.

---

You are picking up work in `C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\Log-Ratio Registration`,
a Fiji/ImageJ time-series registration plugin. Not a git repository. Read
`docs/pairwise_estimator_axis_findings.md` first: it is the complete record of the work you are
continuing, and everything below assumes it.

## What was just finished

`docs/pairwise_estimator_axis_plan.md` was executed through all six stages. A second pairwise
estimator now ships:

- **`logratio.core.PairEstimator`** is a seam at the `PairAligner.align` boundary — a frame pair in, a
  `Fit` out — with two values: `LOG_RATIO_FIT` (the default) and `AREA_CORRELATION`.
- **`logratio.core.AreaCorrelation`** is normalised cross-correlation on a pyramid with sub-pixel
  refinement over an interpolating cubic B-spline image model. Written rather than bound to TurboReg
  because TurboReg is GPLv3 and this project is BSD 3-Clause.
- **The automatic selector was retrained** over 112 candidates (96 log-ratio + 16 area) and retained
  exactly two, both area correlation with a Gaussian 0.7 filter: brightfield/DIC and fiducial/static.
  `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` is the regenerated model.
- **The sealed set passed every gate**, once: 0 failures, 0.766x the category recommendation,
  brightfield 0.029190 -> 0.023358 px and fiducial 0.017760 -> 0.014802 px against the previously
  shipped model, the other three image types bit-identical, 1.376 s against a declared 2.2 s.
- Exposed as an ordinary setting: advanced dialog, macro option `estimator=`, batch, Java API,
  `LogRatioSweepPlan.Parameter.ESTIMATOR`, and the run log names it via `RegistrationRecipe.describe()`.

291 tests pass.

## Five things that will bite you

1. **The estimator implementation is frozen.** The selector model was fitted to measurements of this
   exact code. Any change to `AreaCorrelation` that moves a number invalidates the model and requires
   redoing Stage 3 and Stage 4. Pure speed-ups that provably move nothing are fine — see item 2.
2. **The reproduction gate is how you prove that.** Re-run
   `logratio.SelectorComparisonBenchmark` and `logratio.PairEstimatorComparisonBenchmark` with
   `-Dlogratio.rewrite=true` and diff `all_recordings.csv` against a copy taken first. The bar is
   exact: when the seam was introduced, all 480 arm-runs came back with a maximum absolute difference
   of `0.000e+00`. Six decimal places is the written gate; a bit is the standard actually met.
3. **The sealed set is spent.** `library/benchmark/v2/benchmarks/sealed_test/` has been read. It may
   not be used to justify any further change. If another sealed validation is needed, a new set has to
   be collected — `library/benchmark/prepare_sealed_test_seeds.py` no longer exists, but
   `src/test/java/logratio/SealedTestSetBuilder.java` and
   `docs/joint_sealed_set_stage1_candidates.md` show how the last one was built, and its
   `prepare_sealed_test_sources.groovy` did the format conversions.
4. **`mvn` is not on PATH.** Use
   `/c/Users/Owner/.m2/wrapper/dists/apache-maven-3.9.9/8e74001100ff70d6af083c5511fcc5ec49282d7017cde82c3698eee8fdf86698/bin/mvn -o`.
   Offline works. A hook blocks recursive searches over this Dropbox tree unless you pass a file-type
   filter and a `timeout <=120` prefix — use the Grep tool with a narrow `path` and `glob` instead.
5. **Another agent has been working in this tree.** It built the sealed set and added
   `LogPlane.linearPyramid`, `PairEstimator.Kind.AREA_CORRELATION_LINEAR` and `LinearPyramidTest`.
   Ownership is recorded in `docs/pairwise_estimator_axis_ownership.md`. If it rebuilds while you run
   Java, your classpath can be wiped mid-run — that happened twice. Compile to a private directory and
   run from that: `javac -d <scratch>/build/main @main_srcs.txt` with
   `C:\Users\Owner\.m2\repository\net\imagej\ij\1.54p\ij-1.54p.jar` on the classpath, then the test
   sources against it. **Whenever you regenerate the model, recompile the *main* classes before
   running anything that reads it** — a stale main build is what caused the sealed set to be scored
   against the wrong model on its first opening.

> **All three items in "Your work, in order" below were done on 2026-08-18.** The build is green
> (293 tests, jar builds), the wording is retired, and performance items 1 and 6 are measured and
> recorded in `docs/performance_optimisation_findings.md`. Two things in that record change what a
> next reader should expect: item 6's threefold speed-up **does not exist** — the fan-out is about 5%
> slower, so it was built, measured and reverted — and the gate must be run as a before/after pair of
> builds,
> because the `all_recordings.csv` files on disk predated the retrain and diffing against them
> measures the retrain rather than the change.

## Your work, in order

### 1. Green the project's own build (small, do first)

Every measurement above was run from a private javac build, so `target/` is stale. Run `mvn -o test`
then `mvn -o -DskipTests package`. Expect green; if a test fails it is almost certainly one the other
agent added after my last snapshot, not a regression in the estimator work.

### 2. Retire the "96 recipes, four dimensions" wording (documentation, but load-bearing)

The candidate space is two axes and 112 candidates now. These still describe the old world and will
mislead the next reader:

- `src/main/java/logratio/api/AutomaticRegistrationSelector.java` — its class javadoc and the
  explanation strings it builds.
- `src/main/java/logratio/api/AutomaticFilterSelector.java` — same.
- `docs/full_automatic_selector_sweep_results.md` — quotes the previous model's held-out numbers as
  current. Add the retrained model's, do not delete the old ones: they are the record of that decision.
- `README.md` line about automatic selection overriding "five things" is already correct; check nothing
  nearby still says four.

Do not touch `docs/pairwise_estimator_axis_*.md`, `LockedTestReport` or anything under
`summaries/full_selector_sweep_v1/` that predates the retrain: those are frozen evidence.

### 3. Start the optimisation plan (the real work)

`docs/performance_optimisation_plan.md` ranks six items by gain per unit of risk. Items 1 and 6 are
safe — they cannot move a number — and should be done first:

- **Item 1, cache the prepared window per frame and level.** `AreaCorrelation.Window.of` currently
  rebuilds both frames' intensity plane, mean and B-spline coefficients on every pair, and multi-lag
  plans 209 pairs over 48 frames, so each frame is prepared about nine times. Memoise on the
  `LogPlane` identity, beside `PyramidCache`, respecting the same memory budget and cleared with it.
  Expect 5-10% of the estimator's time. Prove it with the item-2 gate above.
- **Item 6, parallelise the offline sweep across recordings.** The 112-recipe sweep runs recordings
  sequentially. Four at once with a quarter of the worker pool each cuts wall-clock roughly threefold.
  No user-facing gain; saves hours per sweep.

Item 2 in that plan — replacing the grid refinement with a Newton step on the correlation — is the
40-50% cut, and it is a change of algorithm that will move numbers. It needs its own plan with a
Stage 3-style measurement and possibly a retrain. Decide that explicitly before starting; do not
drift into it.

## Where the evidence lives

| What | Where |
|---|---|
| The complete record, Stages 1-6 | `docs/pairwise_estimator_axis_findings.md` |
| Runtime position and rollback instructions | `docs/pairwise_estimator_axis_runtime_declaration.md` |
| Estimator comparison, four arms, 80 recordings | `library/benchmark/v2/benchmarks/controlled_motion/summaries/pair_estimator_v1/` |
| Extended sweep, training folds, retained candidates | `.../summaries/full_selector_sweep_v1/` |
| Previous model and its validation, for rollback | same folder, `*.before_estimator_axis.*` |
| Sealed run and its gates | `library/benchmark/v2/benchmarks/sealed_test/summaries/selector_comparison_v1/` |
| Previous model's sealed baseline | same folder with the `__previous_model_baseline` suffix |
