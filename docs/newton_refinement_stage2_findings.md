# Newton refinement, Stage 2 — fast, and not accurate enough. The plan ends here.

> **Superseded in part, 2026-08-19. Read `docs/newton_refinement_stage2_diagnosis.md` with
> this.** The cause proposed below — the frames sliding apart — is **wrong**, and the probe
> that tested it says so. The real cause was an implementation asymmetry: on the two failing
> recordings the Newton refinement never ran at all, on any of 209 pairs, and returned the
> integer offset. Fixing that removed the whole 9x tail, taking fiducial's worst recording from
> 0.321317 px to 0.034827 px — the grid's own number. **The gate still fails**, on a real but
> much smaller fiducial median deficit of 0.001407 px against a 0.001 px tolerance, so the
> verdict and everything about the speed below stand unchanged.

Serves Stage 2 of `docs/newton_refinement_plan.md`. Run 2026-08-19, four arms over the 80 development
recordings, 320 arm-runs, no failures. Machine idle. Full record in
`library/benchmark/v2/benchmarks/controlled_motion/summaries/newton_refinement_v1/`, whose `GATE.md`
was written by the benchmark class itself from thresholds fixed before the run.

## The gate, first

**FAIL on accuracy, and by a long way on the one thing the plan said to watch: the tail. The speed
result is the best news in this document and it does not save it.**

| Condition | Limit | Measured | Verdict |
|---|---|---|---|
| Paired median, every image type | no worse than the grid by more than 0.001 px | **fiducial/static worse by 0.001407 px** | **fail** |
| Worst recording, every image type | no worse than the grid | **fiducial/static 0.321317 px against 0.034827 px — 9.2 times worse** | **fail** |
| Mean processor seconds | at least 30% faster | **54.66% saved** | pass |

**The plan stops here, as it said it would.** The shipped estimator is untouched, the selector is
untouched, no sealed set is spent, and Stages 3, 4 and 5 do not run. That was the point of building
this as a third value on the estimator axis rather than as an edit to the second: a Stage 2 that goes
badly costs the measurement and nothing else. There is no rollback to perform.

## What the speed turned out to be, because it is worth knowing

**The cost half of the idea worked better than Stage 0 predicted, and better than the plan hoped.**

| Arm | Mean elapsed s | Mean processor s | Against the grid |
|---|---|---|---|
| 1 — shipped default, log-ratio fit | 1.808 | 14.958 | |
| 2 — recommendation, log-ratio fit | 0.843 | 7.350 | |
| 3 — recommendation, area correlation, grid | 2.111 | 17.187 | |
| 4 — recommendation, area correlation, Newton | 0.889 | **7.792** | **54.7% less processor time** |

Stage 0 counted 2.21 times less arithmetic in the refinement and predicted about 36% off the
estimator. The measured saving is **54.7%**, which is more than the plan's original optimistic 40-50%
and half again Stage 0's own corrected figure. The reason the estimate was low is that Stage 0 charged
the refinement alone; on real recordings the Newton arm also converges in fewer iterations than the
grid takes rounds, and the saving compounds across all 209 pairs of a recording.

**It reached the target the whole plan existed for.** Arm 4 costs 7.792 processor seconds against the
log-ratio fit's 7.350 — area correlation at 1.06 times the default estimator's cost, where the grid
sits at 2.34 times. If accuracy had held, this is exactly the result that would have let the axis be
offered more widely.

It did not hold.

## Where it broke, precisely

Per image type, arm 4 against arm 3, paired:

| Image type | Paired | Grid median (px) | Newton median (px) | Difference (px) | Grid worst (px) | Newton worst (px) | CPU saved |
|---|---|---|---|---|---|---|---|
| BRIGHTFIELD_DIC | 16 | 0.024703 | 0.024447 | **-0.000256** | 0.044600 | 0.045165 | 60.2% |
| DENSE_FLUOR | 16 | 0.027654 | 0.025126 | **-0.002528** | 0.198180 | 0.193298 | 50.4% |
| FIDUCIAL_STATIC | 16 | 0.011171 | 0.012578 | **+0.001407** | 0.034827 | **0.321317** | 54.5% |
| PHASE | 16 | 0.016115 | 0.014298 | **-0.001817** | 0.025680 | 0.024578 | 52.0% |
| SPARSE_LOWLIGHT | 16 | 0.151016 | 0.148805 | **-0.002211** | 16.992380 | 16.995628 | 52.9% |

**Read the medians first and the failure is invisible.** Newton is *better* on four image types out of
five, by up to 0.0025 px, which is what Stage 0's analytic fixture predicted and is a real result. The
median is not where this dies.

**Two of the three breaches are noise and should be named as such rather than counted.** Brightfield's
worst recording moves by 0.0006 px and sparse's by 0.0032 px on a recording where *both* arms have
already lost lock at 17 px — neither is a real degradation, and a gate written to "no worse on any
image type" with no tolerance catches them because it has no tolerance, not because they matter. They
are recorded here so the next reader does not spend time on them.

**The third breach is real and it is the whole finding.** Fiducial/static, worst recording, 0.321 px
against 0.035 px. It is not spread across the image type; it is two recordings:

| Recording | Movement profile | Grid (px) | Newton (px) | Ratio |
|---|---|---|---|---|
| `fiducial_cage_d4` | STEADY_DIRECTIONAL_DRIFT | 0.034827 | **0.321317** | 9.2x |
| `fiducial_cage_d3` | STEADY_DIRECTIONAL_DRIFT | 0.032761 | **0.284471** | 8.7x |
| `fiducial_cage_d2` | STEADY_DIRECTIONAL_DRIFT | 0.017939 | 0.032506 | 1.8x |
| `fiducial_cage_d2` | CURVED_OSCILLATING_DRIFT | 0.019892 | 0.023056 | 1.2x |
| every other fiducial recording | — | — | — | under 1.2x |

**Three of the four worst are the same movement profile on the same image type**, which is a signature
rather than a scatter, and it is the signature Stage 0 named in advance.

## The most likely cause, stated as a hypothesis and not as a finding

Steady directional drift is the profile where the two frames of a pair slide monotonically apart, so
the set of pixels valid in both shrinks steadily as the lag grows. Fiducial/static is the image type
where that set is beads on a flat background rather than a filled rectangle. Stage 0 quantified
exactly this and said it was unproven off the analytic fixture:

> 189 samples of 35,721 leave the overlap at a whole-pixel offset, stepping the score by 1.17e-05 —
> worth about 7e-04 px of offset, the same order as the errors being measured. Harmless on a clean
> fixture; unproven on a ragged validity boundary.

A grid search does not care about a step in the surface, because it compares heights. A derivative
method computes the slope of a function that has a small step in it and can be confidently wrong. The
measured signature is consistent with that and with nothing else in the record — but it has **not been
confirmed**, because confirming it means instrumenting the refinement on those two recordings, and the
plan says to stop rather than to start diagnosing. Anyone who picks this up should treat the paragraph
above as the first thing to test, not as something already established.

Two things it is *not*. It is not a porting error: Stage 1's before/after gate came back at
`0.000e+00` on 800 arm-runs, and the ported refinement reproduces Stage 0's ten-shift accuracy table
to all six decimal places. And it is not the Hessian guard firing, in the sense that stopping on a
non-positive-definite Hessian returns the integer sweep's own answer, which would be an error of order
0.5 px rather than 0.3 px — though a partially converged step is still on the list.

## What happens to the value

It stays, as a documented dead end with its measurements attached, exactly the way
`AREA_CORRELATION_LINEAR` does. `PairEstimator.Kind.AREA_CORRELATION_NEWTON` keeps its identifier and
its javadoc now carries the numbers above and the instruction not to choose it. A refuted idea with
its evidence attached is worth more than a deleted one, and this one is worth more than most: it is
**the fastest correlation estimator this project has**, and if the boundary hypothesis is ever tested
and fixed, everything needed to re-measure it is in place and takes eight minutes.

The shipped model never named it and still does not. Nothing a user gets has moved.

## What this closes, and what it leaves open

**Closed.** Item 2 of `docs/performance_optimisation_plan.md` — the Newton step on the correlation —
is measured and refused. It was the largest single saving available and it is not available at the
accuracy this project holds.

**Left open, and deliberately not started.** Items 3 and 5 of that plan — refining on a subsample and
pruning long lags before they run — were folded into this stage as extra arms on the assumption that
the round would happen anyway. The round happened and the fold did not, because building two more
behaviour-changing features before knowing whether the refinement survived its own gate would have
been building on an unknown. They now need a round of their own, which is one benchmark run of about
eight minutes plus whatever they cost to build. That is the price of the decision and it is small.

## A note on how the test suite was verified

`mvn -o clean test` could not be used to confirm the tree at the end of this stage, and the reason is
worth recording rather than working around silently. **Another agent was editing this tree while this
stage ran** — `SealedTestSetBuilder` and a new `SpentMaterialGuardTest`, which is Stage 4 material and
does not touch anything here. Its builds empty and refill `target/` , so a surefire run that starts
while that is happening reports `NoClassDefFoundError` on nested classes that are present on disk
seconds later. Two consecutive `mvn -o clean test` runs failed that way with 44 errors and 5 failures,
all of them "could not read bytecode for" or "class not found".

The suite was therefore run from a private build compiled outside `target/`, which is the same
precaution the Stage 1 gate took and for the same reason: **all 307 tests pass**, including the other
agent's five new ones. Anyone confirming this later should compile privately rather than trusting a
`target/` two agents share.

## Where the evidence lives

| What | Where |
|---|---|
| The run, the gate, per-recording pairs | `library/benchmark/v2/benchmarks/controlled_motion/summaries/newton_refinement_v1/` |
| The benchmark, with the gate coded into it | `src/test/java/logratio/NewtonRefinementBenchmark.java` |
| Stage 1: the value, and the proof nothing else moved | `docs/newton_refinement_stage1_findings.md` |
| Stage 0: the derivation and the boundary risk it quantified | `docs/newton_refinement_stage0_findings.md` |
| The implementation and its derivation | `AreaCorrelation.newtonRefine` |
