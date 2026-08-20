# Newton refinement, Stage 0 — the derivation, and what it is worth

Serves Stage 0 of `docs/newton_refinement_plan.md`. Run 2026-08-19. No pipeline, no recordings, no
benchmark: a prototype, an analytic fixture and three checks, exactly as the stage specifies.

## The gate, first

**PASS on all three, and the accuracy result is the surprise: the Newton refinement is not merely no
worse than the shipping grid search, it is 2.8 times more accurate on the analytic fixture.**

| Check | Limit | Measured |
|---|---|---|
| Derivatives agree with a numerical derivative | agreement to the difference's own accuracy | **8.3e-06** worst relative, interior; **1.3e-05** at whole-pixel offsets |
| Worst error on the analytic fixture | 0.000600 px | **0.000243 px** (grid: 0.000692 px) |
| Refinement cost | at most half the grid's | **0.452x** — 2.21 times less arithmetic |

**One correction to the plan's expectation, and it matters for whether Stage 2 is worth running.** The
plan predicted three to five times off the refinement and 40-50% off the estimator overall. The
measured figure is **2.21 times off the refinement**. With the refinement at about two thirds of the
estimator's cost, that is roughly **36% off the estimator**, not 40-50%. Still the largest single
saving available, and still enough to bring area correlation to about the log-ratio fit's cost, but
the plan's headline number was optimistic and should be read as 36% from here on.

## The derivation

Write `a_i` for the reference samples and `b_i(d)` for the moving frame interpolated at offset
`d = (dx, dy)`. With `n` samples valid in both and centred values `A_i = a_i - mean(a)`,
`B_i = b_i - mean(b)`, the score the estimator maximises is

```
C(d) = sum(A_i B_i) / sqrt( sum(A_i^2) sum(B_i^2) ) = num / (sa sb)
```

**The step that makes this tractable, and it answers the plan's risk 2 outright.** The plan worried
that a zero-normalised cross-correlation is a ratio rather than a sum of squares, so the textbook
Gauss-Newton derivation would not apply and an approximate one would bias the answer. It does apply,
because the normalised vectors `A/sa` and `B/sb` are both unit length, and therefore

```
E(d) = sum( A_i/sa - B_i/sb )^2 = 2 - 2 C(d)
```

Maximising the correlation *is* minimising a sum of squared residuals `r_i = A_i/sa - B_i/sb`. The
normalisation is not an obstacle to approximate around; it is absorbed into the residual, exactly.

Differentiating, with `b'_i` the spatial gradient of the interpolated moving frame and
`u_i = b'_i - mean(b')`:

```
J_i    = dr_i/dd = -u_i/sb + B_i Q/sb^3,     Q = sum(B_i b'_i)
grad C = P/(sa sb) - C Q/Vb,                 P = sum(A_i b'_i)
H      = ( sum(u u') - Q Q'/Vb ) / Vb
step   = H^-1 grad C
```

Both collapse to closed forms in plain sums because `sum(B_i) = 0` and `sum(B_i^2) = Vb`. **So one
pass over the samples accumulating fifteen numbers replaces nine whole correlations**, and the two
derivations — differentiating `C` directly, and differentiating `E` and using `grad E = -2 grad C` —
were checked against each other and agree.

## Check 1 — the derivatives are right, and the one place they look wrong is instructive

Against a central difference at interior offsets, the analytic gradient agrees to **8.3e-06** relative
in the worst case and to **1e-9** in the best. A central difference at `h = 1e-4` cannot do much
better than about 1e-6, so this is agreement to the limit of the test.

**At whole-pixel offsets the central difference disagrees by 80%, and the central difference is what
is wrong.** This is the plan's risk 1 — the piecewise-smooth surface — appearing precisely where it was
predicted:

```
dx        samples          score
1.99999     35721   0.9956234578
2.00000     35532   0.9956352735
2.00001     35532   0.9956354324
```

189 samples of 35,721 leave the overlap exactly at `dx = 2`, because the four-by-four spline
neighbourhood shifts one column and one more column falls outside the frame. The score steps by
1.17e-05 across that point. A central difference straddles the step and measures it; a one-sided
difference does not, and against the one-sided difference on its own side the analytic gradient agrees
to **1.3e-05**.

**Does it matter to the answer?** Every refinement starts at the integer offset the sweep returned, so
the first gradient is *always* evaluated on one of these boundaries. Measured across sub-pixel shifts
from 0.25 to 0.90 px, it does not hurt:

| True dx | Grid error | Newton error |
|---|---|---|
| 0.25 | 0.000479 | 0.000149 |
| 0.50 | 0.000284 | 0.000105 |
| 0.75 | 0.000288 | 0.000021 |
| 0.90 | 0.000289 | 0.000008 |

Newton wins at every one, and improves as the answer moves away from the boundary while the grid stays
flat at about 0.00028. **Carry this forward to Stage 2 anyway.** The step is 1.17e-05 of score against
a gradient of order 1.6e-02 per pixel, so it is worth about 7e-04 px of offset — the same order as the
errors being measured. On a clean analytic fixture it is harmless. On sparse low-light, where the
valid-pixel set is ragged rather than a clean rectangle, it may not be.

## Check 2 — accuracy on the analytic fixture

Ten known shifts, `Synth`'s analytic image, both refinements run inside the same pyramid descent and
the same integer sweep so that the refinement is the only difference.

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

Newton is better on eight of ten and equal on one. That is not what a speed-up is supposed to do, and
the reason is worth stating: the grid stops when its spacing shrinks below the tolerance, wherever it
happens to be, whereas the Newton step stops when the gradient says it has arrived. The grid's floor
is set by its own geometry; the Newton step's floor is set by the interpolator.

## Check 3 — cost, measured rather than timed

**Wall clock could not be used, and saying so is part of the finding.** Repeated identical runs of the
same measurement gave speed-ups of 2.21x, 2.42x, 3.59x and 5.92x, with the grid arm ranging from 47 to
125 ms. That is machine noise of a factor of three, and no honest number can be read off it.

So the comparison is arithmetic, which is deterministic. Both refinements were instrumented to count
the three kinds of pass they ask for, and the relative cost of those three passes was **measured**
rather than assumed — one full pass of each kind over the same window, minimum of 400 repetitions:

| Pass | Cost | Weight |
|---|---|---|
| Whole-pixel score (array index, no interpolation) | 0.1416 ms | 0.088 |
| Interpolated score (sixteen spline taps) | 1.6049 ms | 1.000 |
| Interpolated score with both derivatives | 2.6000 ms | **1.620** |

That last weight is the one a guess gets wrong. Producing both derivatives alongside the value costs
**1.62** interpolated passes, not 3, because the four taps across a row give that row's value and its
x-derivative together and the row sums are then shared. My first guess of 2.0 put the speed-up at
1.95x and failed the gate; the measured weight puts it at 2.21x and passes. The weights reproduce to
three significant figures across runs.

Samples requested per refinement, mean over eight shifts:

| | Whole-pixel | Interpolated | With gradient | Weighted total |
|---|---|---|---|---|
| Grid | 331,776 | 451,584 | 0 | **480,780** |
| Newton | 0 | 82,944 | 82,944 | **217,313** |

**2.21 times less arithmetic**, reproducible to 0.01% between runs.

Two things that comparison is careful about, both of which would have flattered the Newton step if
ignored. The grid is charged at the *shipped* scoring function, whole-pixel fast path included — its
first round scores nine integer offsets and indexes straight into the array rather than interpolating,
and charging it for interpolation it does not do would have inflated the speed-up by about a third.
And the Newton line search is charged as a value-only pass, because that is what a real implementation
would call for a trial it may reject.

## What Stage 0 did not test, and Stage 2 must

- **Real frames.** Everything above is one synthetic analytic image with several incommensurate
  frequencies, which is the best-conditioned input this method will ever see.
- **Sparse low-light.** The image type where area correlation already loses lock at 17 px, and the one
  where the ragged validity boundary in check 1 is most likely to bite.
- **Ill-conditioned peaks.** The fixture's autocorrelation has one clear peak by construction. A
  Hessian that is nearly singular — a repeating structure, a single edge — is where a Newton step
  degrades and a grid search merely gets slow. The prototype falls back to stopping when the Hessian
  is not positive definite; whether that is the right fallback is a Stage 2 question.

## Where the evidence lives

Prototype and probes in the session scratch directory, not in the project: `NewtonRefinementProbe`
(the derivation and both refinements over a shared descent), `NewtonStage0` (the three checks),
`NewtonBoundary` (the whole-pixel discontinuity) and `NewtonCalibrate` (the measured weights). Nothing
was added to `src/`, which is what Stage 0 requires — Stage 1 is where a third estimator value appears.
