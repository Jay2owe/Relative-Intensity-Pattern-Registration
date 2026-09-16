# Python/Java cross-language parity — 9 September 2026

User request: "get parity first and then you can do an actual fair speed comparison", after
"the whole codebase in java has been changed and how it runs".

## What was measured

`scripts/cross_language_parity.py` runs one real TIFF through the Java engine
(`ripr.core.EndToEndParityProbe`, test sources) and through the Python package under matched
settings, and compares every per-frame column plus every per-pair fit. `scripts/component_parity.py`
compares the intermediate planes behind a single frame — preprocessed intensity, log plane, validity
mask, gradients, every pyramid level — as raw float32, so a divergence names an array rather than a
summary number.

Both languages are given the same recipe through the same door. That mattered: the first readings
were invalid because the probe set `selectionMode(RECOMMENDED)` on the Java builder, which marks the
mode and leaves preprocessing at the builder default, while the Python side had populated the whole
preset. Java must be given `recommendation(image, motion)`; anything less compares two recipes.

## Result, 12-frame slices of real recordings

| Case | Selection mode | Outcome |
|---|---|---|
| microglia brightfield | RECOMMENDED | parity; 33 pair fits bit-exact |
| microglia brightfield | AUTOMATIC | parity; 33 pair fits bit-exact |
| microglia brightfield | LONGITUDINAL_ACCURACY | **fails**; 0.18 px |
| incucyte green | RECOMMENDED | parity; 33 pair fits bit-exact |
| incucyte green | AUTOMATIC | parity; pair fits agree to 2e-8 px |
| per2 bioluminescence | RECOMMENDED | fixed; now at parity, pair fits agree to 4.4e-9 px (see below) |
| per2 bioluminescence | LONGITUDINAL_ACCURACY | **fails**; 0.22-0.43 px, same cause as the microglia case |

The solver's own stopping tolerance is `convergence = 1e-3` px, so agreement at 2e-8 px is five
orders of magnitude inside the algorithm's resolution. Bit-exact means bit-exact.

Five of the seven cases are at parity. The two that are not are both `LONGITUDINAL_ACCURACY`, and
they compare an implementation the package no longer runs by default -- see *What is not at parity*
below. Every case that a default call can reach is bit-exact or within 5e-9 px.

## Two Python changes were needed to reach bit-exactness

Both were measured, not guessed, and both are pinned by `tests_python/test_java_arithmetic_parity.py`.

1. `LogPlane._blur5` reduced its five taps with `scipy.ndimage.convolve1d`. Java accumulates them
   sequentially in float32, and single-precision addition is not associative, so the two landed one
   ULP apart. Level 0 was already bit-exact; every coarser pyramid level was not. Rewritten as five
   ordered float32 masked adds, which is also no slower.
2. `LogPlane.sample` returned full float64. Java's `sampleOf` ends `return (float)(...)`, so every
   value it can return is representable in float32. Rounding is now done where Java rounds it.

Each alone moved the final transform by roughly 0.002 px on a real recording, because the search
starts at the coarsest pyramid level and a different starting point converges elsewhere.

Not divergences, though they were suspected and checked: `MEDIAN_3X3` preprocessing is bit-identical;
`LogPlane.of` is bit-identical; the landmark reference features are bit-identical; `profileGain` is
true in both, and the zero gain seen on 8-bit data is genuine rather than a disabled option.

## The per2 divergence: four pixels

The two bioluminescence cases finished after the first report and were not covered by it. The
RECOMMENDED one missed parity by 2.6e-5 px on the reconciled frames, with 1.1e-4 px on its worst
pair -- far inside the solver's own 1e-3 px tolerance, so the same answer scientifically, but not
bit-exact the way the smaller recordings are.

Every pyramid plane behind the divergent pair is bit-exact through all four levels, and refitting
that pair on its own, as a two-frame stack, is bit-exact too. So the estimator was never the
problem: something computed across the whole recording was handing it different inputs.

This is the only tested recipe that uses pixel selection, and pixel selection is decided once, on
one frame, by ranking every eligible pixel by an information score and dropping the least
informative quarter. Dumping that decision from both engines:

```
eligible   : java 1389870   python 1389870   differing 0
information: differing 1519355 / 2478080 (61.31%)   max|d| = 2.4e-14
removed    : java  347468   python  347468
mask       : differing pixels 4
```

The eligibility test and the removal count agree exactly. The score does not, and it does not have
to be wrong to do damage: it is *ranked*, so a pixel within 1e-14 of the quarter-way cut lands on
whichever side the last bit puts it. Four pixels of 2,478,080 crossed over, and those four are then
inside one engine's fit and outside the other's for every pair in the recording.

The cause was `scipy.ndimage.uniform_filter(..., size=5) * 5 * 5` in `_two_axis_information`. A box
filter returns the window mean, so recovering the sum divides by twenty-five and multiplies it back
-- two roundings the Java loop's direct sum never performs, on top of a different accumulation
order. Rewritten as twenty-five ordered masked adds, in the order Java visits the taps, which is the
same idiom `LogPlane._blur5` already uses for the same reason. The information score is now
bit-exact across all 2,478,080 pixels and the masks are identical, and the recording is at parity:

| per2 RECOMMENDED, 12 frames | worst pair difference | frames |
|---|---|---|
| before | 1.1e-4 px | fails at 2.6e-5 px |
| after | 4.4e-9 px | **at parity** |

Two tests in `tests_python/test_java_arithmetic_parity.py` pin the score against a scalar
transcription of the Java loop, bit for bit, including the border and invalid-pixel handling.

Worth noting for anyone tempted to vectorise something here: this is the second time a library
reduction chosen for speed has silently changed a result, and the first (`convolve1d` in the pyramid
blur) cost 0.002 px. A library call that computes the right quantity in the wrong order is not a
safe substitution when the output is compared, ranked, or thresholded.

## What is not at parity

`SelectionMode.LONGITUDINAL_ACCURACY` diverges by 0.18 px on microglia and 0.22-0.43 px on per2, and
its pair fits are bit-exact, so the
pairwise estimator is not the cause. The cause is structural: `src/ripr/longitudinal.py` builds its
trajectory with OpenCV (`cv2.findTransformECC`, `cv2.GaussianBlur`), and
`ripr/core/LongitudinalRegistration.java` imports no OpenCV and implements its own. These are two
implementations of one idea, not one algorithm in two languages, and no tolerance change reconciles
them. The same cause shows in the emission reference features, which differ by 1.6e-6 relative while
the landmark features are bit-exact.

Closing it meant choosing which implementation is authoritative. Java is, on both counts that
matter: it is the reference the plugin ships, and it is the faster of the two. Porting it into
Python would have meant transcribing 1460 lines whose blur is three passes of a sliding-window box
sum -- a sequential recurrence that carries its own rounding along each row -- and the reward would
have been a second implementation that is slower and must then be kept in step forever.

So the second implementation is no longer the one that runs. `SelectionMode.LONGITUDINAL_ACCURACY`
now resolves to the Java engine by default. It is deliberate: here the engines do not agree, so the
default must be the reference implementation.

The Python implementation is still there and still reachable with `backend="python"`, which warns
that it is not the reference. It is also what a recipe the Java runner cannot rebuild falls back to,
and what runs when no JDK can be found -- with a warning in both cases rather than silently. The one
thing the Java route does not carry back is `longitudinal_diagnostics`, which is absent rather than
approximated.

The parity sweep still reports these two cases as divergent, and should: it compares the two
implementations directly. What changed is which one the package uses.

## Speed, now that the comparison is fair

Same recording, same settings, same output, 16 cores. Java sizes its own pool at 15 workers; the
Python engine has no parallelism at all, and `threads` is accepted and ignored.

| Run | 40 frames, 448x768 |
|---|---|
| Java, auto threads | 15.2 s |
| Java, one thread | 118.7 s |
| Python | over 900 s |

Single-threaded Java is ~13x the Python engine, and Java's thread pool contributes ~7.8x on top.
Pair alignment is the one dominant parallel axis and Python does not use it.

## The fast path

`ripr.api.HeadlessRunner` (main sources, so it ships in the jar) estimates one recording from outside
the JVM. `ripr.java_backend` drives it, and `register(..., backend="java")` returns the Java engine's
transforms. On a 40-frame stack that is 16.6 s against over 900 s, with **bit-identical corrected
pixels**.

Pixels cross as a headerless binary blob rather than a TIFF: a TIFF round-trip can cost more than the
registration, and ImageJ's reader has opinions about what a three-slice 8-bit stack means. Only
transforms come back; warping stays in Python.

The default backend prefers Java. If Java or an exactly matching recipe is unavailable, the package
emits `BackendFallbackWarning` with the reason before using Python. An explicit `backend="java"`
request is strict and raises; `backend="python"` deliberately selects the in-process implementation.

The runner rebuilds the recipe from the image type, motion type and selection mode it is given. That
is exact for a preset and wrong for a customised recipe, so `java_incompatibilities()` refuses any
recipe differing from its preset in any other field. `backend="java"` raises rather than silently
running something else; `backend="auto"` warns before falling back to Python.
