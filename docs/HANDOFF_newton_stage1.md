# Handoff — Newton refinement, Stage 1

Paste this whole file as the opening prompt of a fresh session. It is written to be read cold.

---

You are picking up work in `C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\Log-Ratio Registration`, a Fiji/ImageJ time-series registration plugin. Not a git repository. Read `docs/newton_refinement_plan.md` and `docs/newton_refinement_stage0_findings.md` first — together they are the complete record of the work you are continuing.

## What this plugin does, in one paragraph

It measures how much a microscope stack drifts between frames and corrects it. The default estimator is a **log-ratio fit**: it makes the log-ratio field between two frames flat, which is exactly invariant to a global brightness change, so bleaching does not have to look like movement. A second estimator, **area correlation**, was added in August 2026: plain normalised cross-correlation on a pyramid with sub-pixel refinement over an interpolating cubic B-spline image model. An automatic selector picks between them per image type; it retains area correlation for brightfield/DIC and fiducial/static and nothing else.

## Your job

Stage 1 of `docs/newton_refinement_plan.md`: **add a third estimator value that refines by a Gauss-Newton step instead of by a shrinking grid search, and prove the two existing estimators did not move.**

Nothing ships in this stage. Stage 2 measures it on real recordings; Stage 1 only builds it and proves it is inert.

## What Stage 0 already settled, so you do not redo it

Stage 0 passed on 2026-08-19. The derivation is done, checked, and the prototype exists. **Port it; do not re-derive it.** The prototype is four files in `research/newton_stage0_prototype/` with a README that says how to compile and run them.

The result in one line: maximising the correlation is *exactly* minimising a sum of squares, because both normalised sample vectors are unit length, so Gauss-Newton applies without approximation. Everything then collapses to closed forms in plain sums:

```
C(d)   = sum(A_i B_i) / (sa sb)          A_i = a_i - mean(a), B_i = b_i - mean(b)
grad C = P/(sa sb) - C Q/Vb              P = sum(A_i b'_i),   Q = sum(B_i b'_i)
H      = ( sum(u u') - Q Q'/Vb ) / Vb    u_i = b'_i - mean(b')
step   = H^-1 grad C
```

One pass over the samples accumulating **fifteen numbers** replaces nine whole correlations. Measured: **2.21x less arithmetic than the grid, and 2.8x more accurate** on the analytic fixture (worst error 0.000243 px against the grid's 0.000692 px).

## Six things that will bite you

1. **The existing estimator is frozen and must not move by one bit.** The automatic selector's retained candidates were fitted to measurements of that exact code. Add `PairEstimator.Kind.AREA_CORRELATION_NEWTON` as a *third value*; do not edit how `AREA_CORRELATION` behaves. Share the window preparation, the interpolator, the integer sweep and the pyramid descent — only the refinement differs.

2. **The gate must be a before/after pair of builds, not a diff against the files on disk.** The `all_recordings.csv` files under `library/benchmark/v2/benchmarks/controlled_motion/summaries/` are regenerated artifacts, and diffing a new run against whatever happens to be sitting there measures whatever else changed since, not your change. Compile the current sources *with your change reverted* into one directory, run, save the CSVs; then compile with the change, run, and compare. `docs/performance_optimisation_findings.md` records how this went wrong the first time.

   The two commands, in this order, from a private build:
   ```
   java -Dlogratio.rewrite=true -Dlogratio.noImages=true logratio.SelectorComparisonBenchmark <project>
   java -Dlogratio.rewrite=true                          logratio.PairEstimatorComparisonBenchmark <project>
   ```
   Compare every column except `total_seconds` and `cpu_seconds`, which are clocks rather than results. **Exit gate: 800 arm-runs, maximum absolute difference `0.000e+00`, no resolved recipe identifier changed.** Each pair of runs takes roughly 25 minutes; budget an hour for the four.

3. **`mvn` is not on PATH.** Use `/c/Users/Owner/.m2/wrapper/dists/apache-maven-3.9.9/8e74001100ff70d6af083c5511fcc5ec49282d7017cde82c3698eee8fdf86698/bin/mvn -o`. Use `clean` — `mvn -o test` without it has produced a false failure before.

4. **A hook blocks recursive searches over this Dropbox tree** unless the command has a file-type filter (`--include=`/`-name`) *and* a `timeout <=120` prefix. Prefer the Grep tool with a narrow `path` and `glob`.

5. **Another agent works in this tree and can wipe `target/` mid-run.** Compile to a private directory and run from there. Build the classpath once with `mvn -o dependency:build-classpath -Dmdep.outputFile=... -Dmdep.includeScope=test`.

6. **Do not re-run the 112-recipe sweep.** `library/benchmark/v2/benchmarks/controlled_motion/*/*/*/*/full_selector_sweep_v1/` is frozen evidence the selector model was fitted to. Nothing in Stage 1 needs it.

## Implementation notes the prototype already paid for

- **Do not allocate per sample.** The prototype's first version built four `double[4]` weight arrays per sample — 147,456 allocations per pass on a 192-pixel frame. Write the weights into caller-owned scratch.
- **The line search needs a score, not a surface.** A trial that may be rejected should cost a value-only pass, roughly half a gradient pass. Compute the full surface only at the accepted point.
- **Basis derivatives** are `-g*g/2`, `(-12f + 9f^2)/6`, `(3 + 6f - 9f^2)/6`, `f^2/2` with `g = 1-f`. They must sum to zero; assert it in a test.
- **Fall back when the Hessian is not positive definite.** The prototype stops. Whether stopping is right, or whether it should hand back to the grid, is a Stage 2 question — but it must not step on a saddle.
- **Cap the step at one pixel** so a bad Hessian cannot throw the estimate out of the cell the sweep chose.

## The risk Stage 0 quantified and Stage 2 must watch

The correlation surface is only piecewise smooth. At a whole-pixel offset **189 samples of 35,721 leave the overlap**, because the four-by-four spline neighbourhood shifts a column off the frame edge, stepping the score by 1.17e-05 — worth about 7e-04 px of offset, the same order as the errors being measured. Every refinement starts at the integer offset the sweep returned, so the first gradient is *always* on one of these boundaries. On the clean analytic fixture it is harmless and Newton still wins. On sparse low-light, where the valid-pixel set is ragged rather than a clean rectangle, it is unproven. `NewtonBoundary` in the prototype folder is the measurement.

## Do not measure with a stopwatch

Wall clock on this machine varies by a factor of three between identical runs: the same Stage 0 measurement gave 2.21x, 2.42x, 3.59x and 5.92x. The 2.21x figure is counted arithmetic weighted by per-pass costs measured with `NewtonCalibrate` — 0.088 for a whole-pixel pass, 1.000 interpolated, **1.620** with both derivatives. A guess of 2.0 for that last weight failed the gate; the measured value passed it. If you quote a speed-up, quote counted work.

## Tests to add

- The three basis derivatives sum to zero at several fractional positions.
- The analytic gradient matches a central difference at interior offsets and a one-sided difference at whole-pixel ones.
- The new estimator recovers a known sub-pixel shift on `Synth`'s fixture to at least the grid's accuracy.
- The seam reaches the new implementation, and `PairEstimator.Kind.of` parses its id.
- The default estimator is still `LOG_RATIO_FIT`.

## Definition of done

- `AREA_CORRELATION_NEWTON` exists, is reachable through the seam, and shares everything but the refinement with `AREA_CORRELATION`;
- the gate passes at `0.000e+00` on all 800 arm-runs, from a before/after pair of builds;
- `mvn -o clean test` is green and the jar builds;
- a findings document records the gate result and what changed, and `docs/newton_refinement_plan.md` gets a Stage 1 status block in the style of its Stage 0 one.

## Where the evidence lives

| What | Where |
|---|---|
| The plan and its exit gates | `docs/newton_refinement_plan.md` |
| Stage 0: derivation, checks, numbers | `docs/newton_refinement_stage0_findings.md` |
| Stage 0 prototype, with a README | `research/newton_stage0_prototype/` |
| Why the gate must be before/after builds | `docs/performance_optimisation_findings.md` |
| The estimator axis this extends | `docs/pairwise_estimator_axis_findings.md` |
| A previous gate run, both halves | `library/benchmark/v2/benchmarks/controlled_motion/summaries/performance_gate_v1/` |
