# Making the external comparison publishable — what those papers actually measured, and what we would have to run

> Active scope changed 2026-08-21: the external comparison covers brightfield/differential
> interference contrast, dense fluorescence, fiducial/static and phase contrast. Sparse/low-light is
> excluded pending a new prospective test with valid inputs.

Written 2026-08-20, after `library/benchmark/v2/benchmarks/*/summaries/external_comparison_v1/`
raised the question its own caveats invite: our table says it is not a fair fight and not the numbers
those plugins published. This document establishes what they did publish, whether anyone has done
this comparison before, and what it would take to publish ours.

Everything below about other people's work comes from their papers, read on 2026-08-20. Citations are
at the foot.

## The short answer, in three sentences

**You cannot show the same numbers most of these papers report, because most of them do not report a
number.** Of the twelve engines in our table, one has a published head-to-head benchmark, one
publishes a rigorous accuracy protocol on synthetic transforms, one reports fiducial residuals, and
the rest publish no accuracy figure of their own at all. **What makes this publishable is not matching
their numbers — it is that nobody has ever run this comparison, and we are one experiment away from a
number that sits directly beside a published table.**

## What each of them actually did

| Engine | What they measured on | How truth was known | Metric | Compared against |
|---|---|---|---|---|
| **TurboReg / StackReg** | one MRI slice | synthetic transform, applied twice | **warping index**, pixels | only their own variants |
| **Fast4DReg** | one zebrafish 3D stack, duplicated 25x, plus 12 noise levels | synthetic drift, known by construction | image similarity — PCC, mSSIM, PSNR, NRMSE | **Correct3DD and Fijiyama** |
| **Descriptor-based** | fluorescent beads in SPIM views | the beads are the truth | residual bead displacement, microns | — |
| **Correct 3D Drift** | one confocal time-lapse | — | **none — no accuracy figure is reported** | — |
| **Image Stabilizer** | — | — | **no paper of its own** | — |
| **Linear Stack Alignment with SIFT**, **Register Virtual Stack Slices** | — | — | **no accuracy paper**; they cite Lowe 2004 for the feature detector | — |
| **MultiStackReg** | — | — | a wrapper around StackReg | — |

### TurboReg is the one to take seriously, and it is closer to us than it looks

Thévenaz, Rüttimann and Unser generate 100 random transformations — rotation uniform on
±5&deg;, translation uniform on ±2.5 px per axis — and apply each one **twice**: the inverse produces
the test image, the forward produces the reference. Their stated reason is that this way neither
algorithm being compared is favoured by the resampling. The transformation to recover is therefore the
square of the random one, ±10&deg; and ±5 px. Resampling is done with a high-order spline, close to
sinc, deliberately *not* the model being fitted.

They then score with a **warping index**:

> the mean, over the pixels of the region of interest, of the distance between where the true
> transformation sends a pixel and where the estimated transformation sends it

**That is our metric.** `FullSelectorFactorialBenchmark.controlledMetrics` computes
`hypot(dx - true_dx, dy - true_dy)` per frame, and for a translation-only model the distance
`||p(x) - p̃(x)||` is identical for every pixel `x` — so the warping index collapses to exactly the
translation error we already report. We report the median over a recording's frames where they report
a mean over pooled trials, which is one line of arithmetic apart. **We are already speaking their
language and the current write-up does not say so.**

Their comparison is between their own variants: bilinear interpolation against bicubic, and standard
Marquardt–Levenberg against their modified one. Bilinear comes out about five times less accurate than
bicubic. **They never compare against another published method**, and the numeric tables in the paper
are rasterised images rather than text, so the exact figures are not quotable from a text extraction —
read them off the printed Table III if a number is needed.

### Fast4DReg is the model for the paper you would write

It is the only tool in our table whose authors ran a real head-to-head: **Fast4DReg against Correct3DD
against Fijiyama**, on synthetic drift added to a duplicated stack, across twelve signal-to-noise
levels from 30.05 down to 1.07 built by adding Gaussian noise of increasing standard deviation. They
report accuracy, noise robustness, and wall-clock, and they are direct about the speed margin — four
to nine times faster than Correct3DD, twenty to ninety times faster than Fijiyama.

**But their accuracy metric is image similarity, not geometry.** They had the drift by construction and
still scored with Pearson correlation, structural similarity, peak signal-to-noise and normalised RMSE
— measures of whether two pictures look alike, not of how far the estimate was from the answer. On
real data with no truth available they fall back to adjacent-frame similarity and a visual check on a
stress fibre that should not move.

**This is the opening.** A similarity score conflates registration error with everything else that
differs between two frames — bleaching, cell motion, noise. A geometric error does not. Our benchmark
already produces the stronger measurement; theirs is the one currently in the literature.

### Three of the twelve have published no accuracy at all

Correct 3D Drift is a *JoVE* protocol paper. Its representative result is that about 7 µm of drift was
observed and that the output looks aligned. Image Stabilizer is a CMU code page implementing
Lucas–Kanade with a rolling template. Linear Stack Alignment with SIFT and Register Virtual Stack
Slices cite Lowe's 2004 feature detector and publish nothing about registration accuracy.

**Saying so in print is a contribution in itself.** Tools that thousands of papers depend on have never
been measured against a known answer, and our table is the first place several of them have a number.

## Has anyone done this comparison? No

The only head-to-head in this space is Fast4DReg's, and it covers three tools, none of them TurboReg,
SIFT or descriptor-based. The 2024 comparative work on microscopy registration that does exist is
about **stitching** — pairwise alignment of adjacent tiles with feature detectors — not about drift
over a time series, and it concluded that SURF beat SIFT, which is a different question again.

**There is no published benchmark of Fiji's time-lapse registration plugins against geometric ground
truth.** That absence is the paper.

## What is unfair about ours right now

Ranked by how hard a reviewer will hit it.

1. **Our selector is trained on the material; their defaults are not tuned at all.** This is the
   serious one. We swept 128 recipes and fitted a per-image-type model; every external ran once, as
   shipped. Comparing a tuned method to an untuned one and reporting the tuned number as the headline
   is the single most common way a benchmark paper gets rejected.
2. **One configuration each, translation only.** Several of these engines are built for affine or
   elastic registration and are being asked for less than they do. Whether that helps or hurts them is
   not obvious, and it is not currently measured.
3. **The scheduling difference is the thesis, not a control.** Our arms measure many overlapping frame
   pairs and reconcile them; most externals chain neighbours. The borrowed-estimator rows separate the
   two effects, and they must be the centre of the argument rather than a footnote.
4. **Recordings that a tool failed on are dropped from its column but not from ours** unless the
   pairing is done — our `paired_against_default.csv` already does this and reports the count. Keep it
   that way and put the drop count in the main table.
5. **The material is ours.** Real acquisitions with synthetic motion is a standard and defensible
   design — it is what Thévenaz did and what Fast4DReg did — but the sources must be public.

## What to run, in order of value per hour

### 1. The Thévenaz experiment, exactly as published — this is the one that answers the question

**Planned in full: `docs/thevenaz_protocol_plan.md`.**

Reproduce their protocol: a single reference image, 100 random transformations at their stated ranges,
each applied twice about the original, recovered by each method, scored by warping index. Run
**TurboReg** and **our estimators** through it.

**This is the only item that literally produces "the same numbers reported by the paper."** It puts our
estimator into their experimental design, with their metric, on their transformation class, so the
result sits beside their Table III and is read the same way. It needs no new metric code — the warping
index for a general affine transform is a short function, and for the translation-restricted case we
already have it — and no new material, because their design supplies its own.

**It also carries a real risk, which is the point of running it.** Their transformation class includes
rotation and their criterion is squared intensity difference on a spline model. `docs/spline_estimator_plan.md`
already suspects TurboReg wins on this ground. If it beats us in its own experiment, that is a finding
worth having before a reviewer finds it.

### 2. Report untuned against untuned as the headline

Make the primary comparison our **base recipe with no automatic selection** against each external's
defaults, and present the trained selector as a clearly separated second row that says what it cost to
train. We already run that arm — `6_base_without_automatic_changes` — and on the development set it is
0.0223 px against the selector's 0.0165 px, so the honest headline is only slightly weaker and it is
defensible.

### 3. Tune the externals the way we tuned ourselves

**Planned in full: `docs/external_parameter_sweep_plan.md`, whose Stage 0 is not tuning at all — it
checks whether four of our own adapter settings have been handicapping the tools we are grading.**

Sweep each external's exposed parameters — SIFT's octaves and feature limits, Image Stabilizer's
template update rate, Fast4DReg's projection axis and reference mode — on the **development set only**,
pick the best per image type, and carry that to the test sets. Report defaults and swept side by side.
This is the expensive item and the one that turns "we won" into "we won fairly".

### 4. Add Fast4DReg's noise sweep

Twelve noise levels on a subset, scored **both** ways: our geometric error and their four similarity
metrics. That makes one row of our table directly comparable to their published figure, and it lets us
show the two metrics disagreeing, which is an argument for the geometric one.

### 5. Add a fiducial arm with a target registration error

Our bead recordings are already fiducial material. Detecting beads and reporting the residual
displacement of corresponding detections, in microns, gives the metric the descriptor-based literature
uses, and it is the only metric in this document that does not depend on synthetic motion at all.

## What publishing needs beyond the numbers

- **Deposit the benchmark.** Recordings, motion paths, seeds, checksums, and every per-recording result
  — not just the summaries. The manifests already carry sources, licences and SHA-256 per input, which
  is most of the work done.
- **State versions and parameters for every external tool**, including the ones that failed and why.
- **Follow the community checklists** for publishing images and image analyses (Schmied et al. 2024).
  A benchmark paper that fails the checklist for its own figures is an easy desk reject.
- **Lead with the sealed-set discipline.** Gates written before the set is opened, opened once, a
  failure restoring the previous model — and `docs/newton_refinement_stage4_third_set_findings.md`
  showing it actually biting. Almost no bioimage tools paper can say that, and it is the most
  distinctive thing this project has.
- **Publish the losses.** Sparse low light, where descriptor-based registration beats us; the locked
  set, where TurboReg's estimator inside our solver beats our own default; and
  `phase_strack_pveronii_03`, which defeats everything. A benchmark whose author's tool wins every row
  is not believed.

## The claim this evidence supports

Not "our plugin is the most accurate registration tool for microscopy." The defensible claim is
narrower and more interesting:

> **For time-lapse drift correction, how frame pairs are scheduled and reconciled matters more than
> which estimator measures them** — a third-party estimator moved into a multi-lag redundant solver
> improves by roughly sevenfold, while the same solver cannot rescue an estimator whose errors are
> structured. And a log-ratio criterion is what lets that solver keep working when the illumination
> changes, which is the case an intensity-difference criterion cannot handle by construction.

The second half of that needs `docs/spline_estimator_plan.md`'s gain-fade experiment, which is already
planned and not yet run.

## Sources

- Thévenaz P, Rüttimann UE, Unser M (1998). *A Pyramid Approach to Subpixel Registration Based on
  Intensity.* IEEE Transactions on Image Processing 7(1):27–41. Warping index defined in eq. 32;
  protocol and Tables III–IV in section V.
- Pylvänäinen JW et al. (2023). *Fast4DReg — fast registration of 4D microscopy datasets.* Journal of
  Cell Science 136(4):jcs260728.
- Preibisch S, Saalfeld S, Schindelin J, Tomancak P (2010). *Software for bead-based registration of
  selective plane illumination microscopy data.* Nature Methods 7(6):418–419.
- Parslow A, Cardona A, Bryson-Richardson RJ (2014). *Sample drift correction following 4D confocal
  time-lapse imaging.* Journal of Visualized Experiments 86:51086.
- Lowe DG (2004). *Distinctive Image Features from Scale-Invariant Keypoints.* International Journal of
  Computer Vision 60(2):91–110. The detector behind Linear Stack Alignment with SIFT and Register
  Virtual Stack Slices.
- Schmied C et al. (2024). *Community-developed checklists for publishing images and image analyses.*
  Nature Methods 21(2):170–181.
