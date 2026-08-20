# Sparse low-light gap — findings

Record against `docs/sparse_low_light_gap_plan.md`. Written 2026-08-18.

Everything below comes from data already on disk. No new material was collected, no code changed, no
recipe or selector touched. Two of the plan's gates turn out to be answerable without new work, and
one of them changes the plan's premise, so they are reported before any collection starts.

## Summary

| Plan gate | State |
|---|---|
| Stage 1 — independent material | **Not met, and the reason is now specific.** The development sparse set seeds from colour print renderings, not sensor pixels. See section 1. |
| Stage 2 — characterise the win | **Answered on existing data.** The win is a median artefact of a bimodal split. Descriptor-based loses lock on 7 of 16 development recordings. |
| Stage 3 — attribute the mechanism | **Two of three mechanisms already have single-variable ablations on disk.** Feature matching is ruled out. The pairwise estimator is implicated. |
| Stage 4 — licence gate | **Resolved: incompatible.** GPL v3+ against this project's BSD 3-Clause. |
| Stage 5 — sealed validation | Not started. Depends on the decision in section 7. |

## 1. The development sparse material seeds from colour print renderings

The 16 development sparse recordings are synthesised from four source files. All four are single
still images, and none contains raw sensor pixels:

| Series | Source file | Frames | Shape | Type | Rendering |
|---|---|---|---|---|---|
| `sparse_ssbd_fig3a` | `[1]_Figure3A.tif` | 1 | 957 x 858 x 3 | uint8 | RGB |
| `sparse_ssbd_fig4a` | `[1]_Figure4A.tif` | 1 | 1173 x 1033 x 3 | uint8 | RGB |
| `sparse_ssbd_fig5a` | `[1]_Figure5A.tif` | 1 | 1181 x 1040 x 3 | uint8 | RGB |
| `sparse_ssbd_figs4` | `[1]_FigureS4-{Red,Green,Blue}.tif` | 1 each | 1181 x 1040 | uint8 | palette-indexed |

They are figure panels lifted from one published record (`ssbd_235_hattori`), rendered to 8-bit colour
for print. The manifests already say so — `Sparse low-light figure-panel pixels for exact injected
motion only` in the series manifest, and `headline_eligibility = controlled experiment only` in the
dataset manifest. The consequence had not been carried into the comparison.

### What is normal here and what is not

Every controlled-motion recording in this benchmark, of every image type, is synthesised the same way:
`BenchmarkV2ComparisonStacks.representativeImage` picks one image file from the source, and
`BenchmarkComparisonStacks.writeRecording` takes a single plane from it, shifts it along the movement
path for `Benchmark.FRAMES` = 48 frames, and adds per-frame noise at `NOISE_FRACTION` = 0.10 of the
frame's standard deviation. So "built from one still frame with synthetic motion and synthetic noise"
is the design of the controlled benchmark, not a defect peculiar to sparse. Real frame-to-frame photon
noise and bleaching are tested in the natural-motion benchmark instead.

What is peculiar to sparse is **what the seed plane contains**. Every other image type seeds from raw
sensor pixels — the microbundle brightfield stacks, the BBBC028 differential interference contrast
fields, the Strack phase frames, the bead acquisitions, the calcium-signalling fluorescence. The four
sparse seeds are 8-bit colour print renderings of published figures.

It is narrower still than that. `Benchmark.seedPlane` handles a colour seed by extracting
`ColorProcessor.getChannel(3, ...)` — the blue channel. `representativeImage` sorts candidate files by
path and takes the first, which for `sparse_ssbd_figs4` is `[1]_FigureS4-Blue.tif`. So all four sparse
recordings are driven by the blue channel alone.

That matters here and does not matter elsewhere, because the sparse seeds are the only genuinely
coloured ones. Measured across the red, green and blue planes of each seed:

| Seed | R = G = B? | Largest red-to-blue difference | Channel means R, G, B |
|---|---|---|---|
| `dic_bbbc028_hangar_01` (brightfield) | **yes, exactly** | 0 | greyscale in an RGB container |
| `dic_bbbc028_circle_01` (brightfield) | **yes, exactly** | 0 | greyscale in an RGB container |
| `sparse_ssbd_fig3a` | no | 63 | 40.2, 40.1, 43.8 |
| `sparse_ssbd_fig4a` | no | 65 | 26.6, 24.9, 30.9 |
| `sparse_ssbd_fig5a` | no | **236** | 26.6, 37.0, **61.9** |

The Broad differential interference contrast fields are greyscale replicated into three identical
channels, so taking the blue one is lossless and the existing brightfield arms are unaffected. The
sparse panels are true colour composites, so taking the blue one discards the other two channels'
signal outright.

No causal claim is made from this: `sparse_ssbd_fig5a` is the most strongly coloured seed and is also
one of the two panels where descriptor-based never fails. The point is only that the sparse seed pixels
have been through more processing before measurement than any other type's, and that no conclusion
about sparse low-light imaging should rest on them.

`sparse_ssbd_fig3a` has a background floor of 12.1 rather than 0 in the recording input, which is what
a print rendering looks like and not what a photon-starved frame looks like.

So the plan's statement that the four development sparse series "are four panels from one published
record" understates the problem in one specific way: the pixels being registered are not measurements.
They have been through colour rendering, 8-bit quantisation and channel separation before the benchmark
ever sees them.

Nothing measured on this material constrains behaviour on sparse low-light recordings, in either
direction.

**Consequence for collection.** Because only one seed plane per source is ever used for a controlled
recording, a source's frame count is close to irrelevant for the sealed set. What matters is that the
seed plane is raw sensor data at the acquisition's native bit depth.

## 2. The descriptor "win" is a median sitting on a bimodal split

`lock lost` below means a median error at or above 1.0 px. The recording crops are 182–228 px across
and the injected paths are sub-pixel to a few pixels, so a whole-pixel median error means the output is
not a registration at all. It is stated separately from the median because the median hides it.

**Development sparse, 16 recordings:**

| Arm | No result | Lock lost | Median px | Mean px | Worst px |
|---|---|---|---|---|---|
| our default | 0 | **0 / 16** | 0.043536 | 0.055424 | 0.221383 |
| our per-recording oracle | 0 | **0 / 16** | 0.030531 | 0.031943 | 0.067015 |
| Descriptor-based, native | 0 | **7 / 16** | 0.027526 | 4.921791 | 29.203765 |
| SIFT pairs + our solver | 0 | 4 / 16 | 0.040862 | 2.651226 | 15.579121 |
| TurboReg pairs + our solver | 0 | 2 / 16 | 0.133921 | 0.587549 | 4.142299 |

Descriptor-based produced a finite number on all 16, which is why the paired rule counted all 16 and
reported `0.62x`. On 7 of them the number is between 4.86 px and 29.20 px. Its mean error is 4.92 px
against our default's 0.055 px — 89 times worse. The median lands below ours only because the failures
are exactly 7 of 16, so the middle of the sorted list still falls in the good half.

Our default never loses lock on this material. That is the fact the headline ratio inverted.

**The split is by source, not by movement:**

| Source | Descriptor lock lost | Descriptor median px | Our default median px |
|---|---|---|---|
| `sparse_ssbd_fig3a` | 4 / 4 | 8.571525 | 0.060390 |
| `sparse_ssbd_fig4a` | 3 / 4 | 6.652216 | 0.041510 |
| `sparse_ssbd_fig5a` | 0 / 4 | 0.010592 | 0.028508 |
| `sparse_ssbd_figs4` | 0 / 4 | 0.008647 | 0.040199 |

By movement profile the failures are 2, 2, 2 and 1 out of 4 — that is just a tally of which sources
failed, and carries no signal. This answers Stage 2 item 2 directly: the movement path does not
separate the cases. The image does. Two of the four panels defeat the difference-of-Gaussian detector
outright and two suit it perfectly.

**Locked sparse, 8 recordings, two independent records:**

| Source | Descriptor | TurboReg pairs + our solver | SIFT pairs + our solver | Our default |
|---|---|---|---|---|
| `sparse_figshare_mda231_rfp` (real 96-frame series) | 0.007066 | 0.010437 | 0.104829 | 0.031516 |
| `sparse_ssbd166_branch2_nuclei` (hard case) | refused all 4 | 6.732991 | 6.732991 | 6.732991 |

On the locked set descriptor-based fails by refusing rather than by returning a wrong answer. On the
one tractable independent record it is genuinely good: 0.0071 px against our 0.0315 px, on all four
movement profiles, with no failures. On the hard record every method including ours fails identically.

The reported locked sparse `0.24x` is therefore a real result on exactly one independent record, and
the reported development `0.62x` is an artefact. The gap has not disappeared, but the evidence base
behind it is a quarter of what the plan assumed, and it points somewhere different.

## 3. Mechanism — two of the three are already ablated

The plan proposes swapping one piece at a time into our own machinery. Two of those swaps exist in the
external comparison already, as single-variable ablations: the pair estimator is replaced, and our lag
set, our `Reconciler.multiLag` and our robust weighting are held fixed.

| Plan's mechanism | Existing evidence | Reading |
|---|---|---|
| Feature matching instead of an intensity fit | `30_real_sift_translation_multilag_rcc`: SIFT correspondences into our solver. **0.104829 px** on the independent record against our default's 0.031516 px, and lock lost on 4 of 16 development recordings. | **Ruled out.** Generic feature matching inside our own machinery is the worst arm tested, three times worse than what it was supposed to explain. |
| The pairwise estimator, not specifically feature matching | `22_real_turboreg_translation_multilag_rcc`: TurboReg's area-correlation estimate into our solver. **0.010437 px** on the independent record against our default's 0.031516 px, all four profiles, no failures. | **Implicated.** An area estimator in our own pairing, with our own outlier rule, recovers most of the descriptor gap on the only independent record available. |
| All-to-all pairing within 5 frames | Not tested. | Prior weakened: the estimator swap alone already closes most of the gap at our standard lag set. |
| RANSAC outlier rejection | Not tested. | Prior weakened, same reason. |

The caveat is stated plainly: the TurboReg result rests on one independent record, four recordings. On
the four figure panels TurboReg is poor (0.133921 px median, lock lost on 2 of 16) — but per section 1
that material does not constrain anything. The two sets disagree, one of them is unfit, and one
independent record cannot settle it. That is exactly the hole Stage 1 exists to fill.

What can be said now is narrower than an attribution and more useful than nothing: **the mechanism is
not feature matching, and the strongest surviving candidate is the pairwise estimator.**

## 4. Licence gate — resolved, and it forecloses one route

Checked against the installed artefacts rather than from memory:

| Component | Version | Licence |
|---|---|---|
| `Descriptor_based_registration` | 2.1.8 | **GNU General Public License v3+** |
| `mpicbg` (its matching backend) | 1.6.0 | **GNU General Public License v2+** |
| This project | — | **BSD 3-Clause** |

Binding to Fiji's descriptor-based registration on the shipped path is not available. The existing
reflective binding in `src/test/java/logratio/ExternalPluginComparisonStacks.java` is a benchmark
harness and is unaffected — it is not distributed as part of the plugin.

The plan states that incompatible terms make the route "document it regardless of what Stage 3 found".
That is stricter than the finding requires, and the distinction matters:

- it forecloses **depending on** the third-party component;
- it does not foreclose **implementing** an estimator, which is what
  `docs/pairwise_estimator_axis_plan.md` already carries as its Route B, and which is the route the
  evidence in section 3 points at anyway.

## 5. What this leaves

The plan asked why descriptor-based feature matching registers sparse low-light recordings better than
anything this plugin can do. On the evidence:

- On average it does not. It is bimodal, it gives no signal about which mode it is in, and on the
  development material it is 89 times worse on the mean while looking better on the median.
- On the one independent record where it works it is genuinely three to four times more accurate than
  our default, and that headroom is real.
- Feature matching is not the reason. An area-correlation pair estimator inside our own solver gets
  most of the way there on the same record, without failing.

The residual question is therefore no longer "should we adopt feature matching for sparse". It is
"is our log-ratio pair fit the limiting factor on real sparse low-light recordings" — which is the
question `docs/pairwise_estimator_axis_plan.md` already exists to answer, for all five image types at
once.

## 6. Material still required

Unchanged in kind, reduced in amount. Current sparse independent records:

| Independent group | Real time series | Used in | Sealed-eligible |
|---|---|---|---|
| `ssbd_235_hattori` | no — four still panels | development (controlled) | no |
| `figshare_mda231_rfp` | yes, 96 frames | locked test, natural motion | no, spent |
| `ssbd166_branch2` | yes | locked test, natural motion | no, spent |
| `ssbd131_worm2` | yes | natural motion | no, read |
| `ssbd474_root_hair` | yes | natural motion | no, read |

Four records of real sparse low-light material exist and are development-eligible. **Zero are
sealed-eligible.** Any new sealed set needs records used nowhere in this project, and the count depends
on the decision in section 7.

Note also that `ssbd131_worm2` and `ssbd474_root_hair` have never had controlled-motion recordings
built, only natural-motion ones. Promoting them to development sparse material costs 8 recordings of
build time and no new collection.

## 7. Decision recorded

**Taken 2026-08-18: the sparse work merges into the estimator work, and only one is funded.**

The mechanism evidence in section 3 points at the pairwise estimator rather than at the pairing or the
outlier rule. Under the standing rule that an estimator verdict makes the sparse work the estimator
work, this plan stops here and hands over to `docs/pairwise_estimator_axis_plan.md`. Sparse low-light
is covered there as one of the five image types rather than as a separate programme.

One sealed set is collected, to the estimator plan's Stage 1 specification: two records per image type
across all five types, ten series, 40 recordings. A sparse-only sealed set is not built, because a
source spent on one sealed set cannot serve another, and building the sparse-only set first would have
cost the estimator plan four sparse records and forced a second collection round.

### Was the gap closed, narrowed or left open

**Narrowed and reattributed, not closed.** Specifically:

- The development half of the gap (`0.62x`) is withdrawn. It is a median artefact on material that
  cannot support the claim, and on the mean the same arm is 89 times worse than our default.
- The locked half (`0.24x`) stands, on one independent record, and is real headroom of roughly three
  to four times our default's accuracy on tractable sparse material.
- The cause is not feature matching. It is provisionally the pairwise estimator, on the strength of a
  single-variable ablation that already exists, on that same one record.
- Confirming or overturning that attribution is now the estimator plan's Stage 3, run across all five
  image types, and no longer needs a sparse-specific programme.

### What the sparse plan's remaining stages become

| Sparse plan stage | Disposition |
|---|---|
| Stage 1 — eight independent sparse records | Superseded. Sparse contributes two records to the joint sealed set. Four real sparse records already exist for development use. |
| Stage 2 — characterise the win | Done, section 2. |
| Stage 3 — ablate the mechanism | Folded into the estimator plan's Stage 3, which tests the same seam on all five image types. |
| Stage 4 — choose the route | Answered: implement, do not bind. Section 4. |
| Stage 5 — sealed validation | Folded into the estimator plan's Stage 5. |
