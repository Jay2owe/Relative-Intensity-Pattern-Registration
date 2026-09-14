# Where the Java time actually goes — 10 September 2026

User question: "is there also a way we can run it in a different language entirely to make it faster?"

The answer is no, and the reason is worth writing down, because the profile that suggests otherwise
is misleading and three separate optimisations were built and measured before that became clear.

## What the profile says

Flight Recorder, 40 frames at 448x768, 15 workers, 3,063 execution samples:

| Method | Share of samples |
|---|---|
| `RobustNorm.select` | 78% |
| `PairAligner.evaluate` | 6% |
| `PairAligner.refine` | 4% |
| `LogPlane.blur5` | 3% |
| everything else | 9% |

Counting the calls directly rather than sampling them: **428,647 selections, on arrays averaging
4,279 residuals, about 15.2 partition passes each**. The residuals are 6.6% exactly zero, so there is
no point mass and no degenerate partitioning — the invalid and background pixels are masked out
before they ever reach the residual array.

## Three exact-output optimisations, all of which work and none of which helps

Each was verified byte-identical on both a 40-frame brightfield stack and a 12-frame 1936x1280
bioluminescence stack, and each passed the full suite.

| Change | In isolation | End to end |
|---|---|---|
| Count the zeros in the pass `scale` already makes, to answer a zero median without selecting | 1.9-2.2x when zeros dominate | no change; the branch never fires on real residuals |
| Ninther pivot instead of the midpoint | 1.31x at the median | **5-13% slower** |
| Branchless counting-and-compacting selection | **2.26x** at the measured size | no change (0.94-1.08x) |
| Fusing the centring and absolute-value passes in `PairAligner.evaluate` | removes two array passes per iteration | no change; see the ordering note below |

The ninther could not have helped: at 15.2 passes the range was already shrinking to 58% per pass
against 50% for a perfect split, so there was almost nothing to win, and the extra comparisons cost
more than they saved on the many small arrays.

The branchless one is the informative result. It genuinely removes most of the work in the method
that holds 78% of the samples, and the wall clock does not move.

The fusion is a lesson in measurement rather than in optimisation. Its first four alternating rounds
on the large recording ran baseline-then-fused and gave 1.036x, 1.025x, 1.026x, 1.090x, which looks
like a real 3-4% win. Running the pair the other way round gave 0.960x, 0.987x, 1.027x, 0.982x,
1.053x, 1.004x. Whichever build goes second in a pair looks faster, so the effect was the running
order. Alternate, and alternate in both directions.

It also refuted the premise it was built on. The scratch arrays are allocated at frame size but only
the first `n` entries are used, and `n` averages 4,279 -- about 34 KB, which sits in cache. Passes
over them were never the expensive part.

## Why: the engine is bound by memory traffic, not arithmetic

Thread scaling on the same recording, 16 logical cores:

| Threads | Elapsed | Speedup | Efficiency |
|---|---|---|---|
| 1 | 88.23 s | 1.00x | 100% |
| 2 | 54.38 s | 1.62x | 81% |
| 4 | 30.15 s | 2.93x | 73% |
| 8 | 20.31 s | 4.34x | 54% |
| 12 | 13.76 s | 6.41x | 53% |
| 15 | 13.97 s | 6.31x | 42% |

Wall time stops improving between 12 and 15 threads and then gets slightly worse. That is saturation
of a shared resource, and together with four optimisations that remove real work and change nothing,
it says the engine is not limited by the arithmetic it performs.

Where the pressure comes from is inference rather than measurement, and is recorded as such. It is
not the scratch arrays, which are cache-resident. The remaining candidate is the resampling itself:
every residual costs three interpolated reads of the target plane -- value, then both gradients --
at a position the transform decides, so each is a scattered read of a frame-sized array with no
useful locality. That is inherent to the algorithm, not to its implementation.

A rewrite in C, Rust or hand-written SIMD moves exactly the same bytes through exactly the same
memory hierarchy. It would not be faster. A GPU would change the arithmetic order and end the
cross-language parity established the day before, for a workload whose bottleneck is bandwidth.

## What was kept

Nothing in `ripr.core` or `ripr` changed; `RobustNorm.java`, `LogPlane.java` and `PairAligner.java`
are byte-identical to before this round, confirmed by diff and by the rebuilt engine reproducing the
earlier per-frame CSV byte for byte.

Three tests were added to `RobustNormTest`, pinning `RobustNorm.scale` against a full-sort reference
across the whole range of zero fractions and at the exact boundary where the zero run meets the
median. They found a real defect during this work: a selection that consumes its input rather than
permuting it silently breaks `scale`, which selects from the same array twice, and `LogPlane` makes
the same assumption. Nothing depends on that today, but nothing tested it either.

## Where headroom does exist, if it is ever wanted

Reduce bytes moved, not instructions executed. `PairAligner.evaluate` makes roughly four full passes
over a frame-sized `double[]` per solver iteration — copy, select, centre, then absolute-value
inside `scale`. Fusing the centring and the absolute value is exact and removes one of them. Whether
that shows up against the bandwidth ceiling is unmeasured, and on the evidence above it may well not.
