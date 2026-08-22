# Relative-Intensity Pattern Registration

A Fiji/ImageJ time-series registration plugin that estimates movement by making the log-ratio between
frames spatially uniform. Intensity gain is fitted separately, so bleaching or global brightness change
does not have to look like movement.

Image type and motion type load evidence-based parameter recommendations. Every recommended value can
be replaced in the advanced controls.

**Two estimators, and the default is the log-ratio fit.** A second estimator — plain normalised
cross-correlation on a pyramid, the classical area method — is selectable, and the automatic selector
chooses it for brightfield/DIC and fiducial/static, where it measures better. It is not better
everywhere; see [Which estimator, and where each one wins](#which-estimator-and-where-each-one-wins)
for the numbers, losses included. Since 2026-08-19 the area method locates its sub-pixel peak with a
Gauss–Newton step read off the correlation surface's own derivatives rather than a shrinking grid
search, which is **24% faster over a whole recording** at the same accuracy; the grid remains
selectable as `area_correlation`. On the sealed test set the faster refinement measured five
nanopixels worse, against a gate that allowed none, and was adopted anyway as a deliberate trade —
see `docs/newton_refinement_stage4_third_set_findings.md`.

## Interactive use

Open a time series and choose **Plugins > Registration > Log-Ratio Registration...**.

1. Select the closest image type and motion type.
2. Leave **Settings source** on **Automatic full selection**, the default.
3. Select **Review and edit all settings before running** when you want to inspect or change values
   before any work starts, then press **Register**.

### Which estimator, and where each one wins

The **pairwise estimator** decides how one frame pair is measured. Everything above it — the pair
plan, the reconciliation, the repair, the warp — is the same either way, so this is a genuine choice
between two methods rather than two pipelines.

| Estimator | What it does | Where it wins |
|---|---|---|
| **Log-ratio fit** (default) | Levels the log-ratio field between two frames, fitting the intensity gain separately and rejecting genuine change with a robust norm | Phase contrast, dense fluorescence, sparse low-light |
| **Area correlation** | Normalised cross-correlation on a pyramid with sub-pixel peak refinement, on an interpolating cubic B-spline image model | Brightfield/DIC, fiducial/static |

Measured on 40 sealed recordings that took no part in choosing anything — ten sources, two per image
type, none of them used elsewhere in this project. Lower is better; the automatic selector chooses the
estimator per image type, so the last column is what a user actually gets:

| Image type | Log-ratio fit | Area correlation | Automatic selection |
|---|---|---|---|
| BRIGHTFIELD_DIC | 0.029190 px | **0.023358 px** | area correlation |
| FIDUCIAL_STATIC | 0.017760 px | **0.014802 px** | area correlation |
| DENSE_FLUOR | **0.030909 px** | worse | log-ratio fit |
| PHASE | **0.016669 px** | worse | log-ratio fit |
| SPARSE_LOWLIGHT | **0.034270 px** | worse | log-ratio fit |

**Where area correlation loses, it loses badly enough to matter.** On the development set it is 1.2x
worse than the shipped default on phase contrast, 1.7x on dense fluorescence and 3.3x on sparse
low-light — and its worst single sparse recording reaches 17 px where the default stays at 0.22 px. It
has no robust norm, so a region that appears or disappears enters the correlation at full weight
instead of being outvoted. That is why it is offered per image type rather than as a default, and why
the settings that belong to the log-ratio fit — pixel support, robust weighting, gradient multiplier,
spatial mask — do nothing when it is selected.

It also costs roughly twice the log-ratio fit for the same recipe. Full evidence, including the
comparison against TurboReg's own estimator inside our reconciler, is in
`docs/pairwise_estimator_axis_findings.md`.

**Settings source** is one exclusive choice, so nothing can be half-automatic:

| Choice | What it does |
|---|---|
| Automatic full selection (default) | Measures this recording, then resolves the pairwise estimator, pixel support, brightness limits, estimation filter and pixel mask. Always shows what it chose in the editable settings window before running. |
| Recommended | Loads the measured recipe for the chosen image and motion type and changes nothing else. |
| Manual | Uses exactly the values in the settings window. |

Automatic selection does not replace the image-type and motion-type base model; it starts from it and may
override five things on top of it, the estimator included. It first performs a neutral provisional fit — every pixel, no
brightness limit, no filter, no mask — then measures 17 image features and 10 movement features and scores
its candidate recipes against them.

It can only differ from **Recommended** for the image types where an override was measured as both safe
and better, presently brightfield/DIC and fiducial/static. For phase contrast, dense fluorescence and
sparse low-light it holds no candidate, skips the provisional fit entirely, and returns the recommended
recipe at no extra cost. On the two types it does serve it registers the stack twice, so allow roughly
double the time.

Whatever it resolves arrives in the settings window as ordinary editable values. Editing any of them
re-labels the run as manual, and the run log always states the complete recipe rather than the word
"automatic". See `docs/full_automatic_selector_sweep_results.md` for the evidence behind the default.

### Parameter sweep on one stack

Choose **Sweep parameters on this stack...** in the main registration dialog to compare up to three
settings at once. Each setting accepts a comma-separated value list, and the product is capped at 24
combinations. The available axes are:

- pairwise estimator;
- preprocessing before movement estimation;
- pixel-removal strategy, scoring-copy filter and removal percentage;
- estimation scale;
- robust weighting and pixel evidence;
- gradient multiplier;
- brightest or dimmest pixel exclusion;
- reference strategy;
- maximum iterations, sampled pixels or movement bound.

Combinations run sequentially so only one fit occupies memory at a time. The source stack is never
modified. Every result tile shows a red-cyan overlay between the reference and corrected preview frame,
a full-resolution log-ratio residual, and run time. Grey agreement and a lower residual mean the result
is more internally consistent. Neither is ground-truth accuracy, and the residual in particular is a
poor guide to it — see the warning below.

Select a completed tile and choose **Use selected settings**. The sweep closes, the main dialog remains
open, **Settings source** switches to **Manual**, and every selected value is loaded. The settings can be
reviewed under **Review and edit all settings before running** before registration. Starting a sweep first
resolves automatic selection, so each sweep arm changes the declared parameter rather than silently
rerunning the selector.

**Do not use the residual to choose between recipes that all work.** It is a measure of internal
consistency, and it has been tested directly against known movement across 7,680 controlled runs. Ranking
recipes by it is anti-correlated with true accuracy (mean rank correlation -0.23), the recipe it prefers
averages rank 76 of 96 by true error where chance would average 48, and the exact known-correct shifts are
themselves beaten by about 61 of the 96 recipes. The reason is structural: the residual restates the
quantity the fit already minimises, so a recipe that pushes that objective lower scores better whether or
not its shifts are closer to the truth.

What the residual is good for is the question the tile caption asks — did registration work at all. Doing
nothing scores near the bottom, so a gross failure is visible. Use the red-cyan overlay and your own
judgement of the corrected stack to choose between arms that all look reasonable, not the number.

For the same reason, the per-recording ceiling is not currently reachable by hand. Choosing the best of the
96 candidate recipes for each recording individually would reach 0.0143 px mean median error against
0.0255 px for the fixed category recommendation, but nothing available on a real recording — automatic or
manual — identifies which recipe that is. See `docs/full_automatic_selector_sweep_results.md`.

The input image is not modified. The corrected stack is a new image. By default, the output is cropped
to the field containing real pixels in every frame and uses whole-pixel, bit-exact shifts.

Preprocessing changes only the temporary channel copy used to estimate movement. Like putting a filter
over a viewfinder, it can make movement easier to see without changing the recorded pixels. The final
transform is always applied to the original, full-resolution stack.

Pixel removal is a separate two-pass option. The first pass supplies a provisional movement path; a
scoring copy then chooses a mask; and the final log-ratio fit uses the original, unfiltered intensities
and gradients only where that mask permits. Filtering the scoring copy therefore cannot blur the final
fit or the corrected image.

For a multi-channel hyperstack, **Channel used to estimate movement** chooses the signal that drives
registration. One transform is estimated per timepoint and then applied identically to every channel
and every Z slice. Channel count, Z count, time count, calibration and hyperstack layout are preserved.

## Folder batches

Choose **Plugins > Registration > Log-Ratio Registration Batch...** to apply one setup to a folder of
TIFF or OME-TIFF stacks. Select the input and output folders, whether to include subfolders, and the
shared image type, movement type, channel, Z slice and registration settings.

**Settings source** defaults to **Automatic full selection**, which resolves a complete recipe separately
for each stack from that stack's own pixels and provisional movement, and records the chosen recipe for
every stack in the batch report. Choose **Manual** to replay one explicit recipe over the whole folder, or
**Recommended** to use the measured recipe for the declared image and motion type unchanged.

The batch holds one stack in memory at a time. A modeless progress window shows the current file,
completed stacks, pair progress, elapsed time, estimated time remaining and estimated finish time. The
estimate appears after the first completed stack and is updated from the measured average stack time.
**Cancel** stops safely when the current registration work yields.

Output stacks are named `<source>_registered.tif` and retain the input subfolder layout. Existing output
stacks are skipped unless **Overwrite existing corrected stacks** is selected. A damaged or incompatible
file is recorded as an error and the remaining files continue. Every run writes
`log_ratio_batch_report.csv` with the input, output, status, elapsed time, before/after residual and error
for each file. It also records the automatic recipe and its evidence scores when automatic selection is
enabled.

## Recommendations

The recommendation matrix comes from the balanced controlled benchmark: five image types, four source
series per type and the same four known motion paths. Each recommendation is the lowest-error tested
log-ratio configuration for that image-type and motion-type pair. The benchmark and full output stacks
are in `library/benchmark/v2/`.

The automatic add-on selector was tested with all four movement versions of each source held out
together. Across the 20 held-out source groups it reproduced 74 of 80 complete recipes (92.5%), made
20 useful additions and made no unsupported additions. After freezing the decision thresholds, the
production run over all 80 controlled recordings chose 79 of 80 declared recipes, improved 22
recordings, worsened one, introduced no failures, and reduced mean median error from 0.3688 to 0.0256
pixels. Mean time was 1.62 seconds per 48-frame stack. This establishes controlled-library behaviour;
natural-motion and external-source validation remain separate tests.

The full automatic selector that ships as the default goes further: it chooses the pairwise estimator,
base pixel support and brightness exclusions as well as filter and mask, from **112 candidates on two
axes** — 96 log-ratio recipes over support, band, filter and mask, plus 16 area-correlation candidates
over band and filter. It is trained with all four movement versions of each source held out together,
then tested once against source series that had never been used for anything, with every pass/fail limit
written down beforehand.

It has been through that once-only test twice, on two separate sealed sets, because the candidate space
changed between them.

| | 96-recipe model | Two-axis model (ships now) |
|---|---|---|
| Sealed set | 10 series, 40 recordings | a second 10 series, 40 recordings |
| Paired median error | 0.0286 to 0.0223 px | 0.0313 to 0.0240 px |
| Fiducial/static | 0.0296 to 0.0180 px | 0.0435 to 0.0148 px |
| Brightfield/DIC | 0.0319 to 0.0239 px | 0.0314 to 0.0234 px |
| Other three image types | unchanged by construction | unchanged by construction |
| Failures | 0 | 0 |
| Mean per 48-frame stack | 1.11 s | 1.38 s |

The second set is independent of the first: the first was spent, and reusing it would have made it a
development set. Both sets pass every declared gate. Improvement stays concentrated where candidates
are retained, which is now brightfield/DIC and fiducial/static only — on both of those the retained
candidate is area correlation, which is what the second axis bought. Full record in
`docs/full_automatic_selector_sweep_results.md` and `docs/pairwise_estimator_axis_findings.md`.

Full-resolution movement estimation remains the recommended default. In the balanced scale benchmark,
75% estimation was 1.71 times faster but 12.8% less accurate overall; 50% was 2.67 times faster but
196.4% less accurate. Individual sparse low-light stacks sometimes improved at 75%, so use the
per-stack sweep when speed or weak sparse signal makes that trade-off useful.

Conventional preprocessing is also category-specific. The controlled benchmark found four reliable
additions: 0.7-pixel Gaussian smoothing for phase contrast with intermittent jumps; 1.0-pixel Gaussian
smoothing for phase contrast with subpixel random movement; 3 by 3 median denoising for brightfield or
differential interference contrast with subpixel random movement; and the same median denoising for
sparse or low-light fluorescence with steady drift. All other recommendations use no preprocessing.
Together these choices reduced the balanced median error from 0.3688 to 0.1973 pixels, a 46.5%
improvement, with effectively unchanged run time.

For sparse or low-light fluorescence with curved drift, intermittent jumps or steady drift, the
recommendation now also removes the least spatially informative 25% of eligible pixels before a raw
second-pass fit. This reduced the final balanced error from 0.1973 to 0.0252 pixels, an 87.2%
improvement, with no new failures. It is not recommended for sparse subpixel random movement, where
the four independent source series were mixed. The category-specific second pass increased mean time
from 0.75 to 2.39 seconds per benchmark stack.

## ImageJ macros

Resolve a complete recipe automatically for phase contrast with subpixel random movement:

```ijm
run("Log-Ratio Registration...",
    "image_type=PHASE_CONTRAST motion_type=SUBPIXEL_RANDOM_WALK selection_mode=automatic");
```

`estimator` takes `log_ratio_fit` (the default), `area_correlation_newton` (what the automatic
selector chooses where it chooses an area method) or `area_correlation` (the same correlation with the
older grid refinement), and is accepted in every mode
because it is a choice about which fit runs rather than one of the settings a mode resolves. It is
recorded in every options string a run produces, so a macro replays the estimator that actually ran.

`selection_mode` takes `automatic`, `recommended` or `manual`. The older `recommended`,
`automatic_filters` and `manual` tokens still work and are rejected only when they contradict an explicit
`selection_mode`, so existing macros keep their meaning. A macro with no mode token at all means `manual`.
Automatic mode rejects an explicit support, brightness limit, filter, mask or compute budget rather than
silently discarding it.

An explicit batch-safe run that never opens the dialog:

```ijm
run("Log-Ratio Registration...",
    "image_type=DENSE_FLUORESCENCE motion_type=STEADY_DIRECTIONAL_DRIFT manual " +
    "channel=1 slice=0 reference=MULTILAG reference_frame=1 lags=1,2,4,8,16 " +
    "preprocessing=NONE " +
    "pixel_selection=NONE mask_preprocessing=NONE pixel_removal=25 " +
    "template_window=5 estimator=log_ratio_fit norm=HUBER support=ALL gradient=0.5 epsilon=1.0 " +
    "floor=off ceiling=off no_remove_offset offset_percentile=1.0 auto_max_shift " +
    "max_shift=30 outlier_mads=6 iterations=25 max_samples=200000 min_valid=0.1 " +
    "threads=0 interpolation=NONE crop");
```

Interactive runs are recorded as complete `manual` option strings after automatic selection has been
resolved. That freezes the exact chosen recipe, so replay does not classify the stack again or change
when the model is improved in a later version.

A folder batch can also run without dialogs. Brackets preserve folder paths containing spaces:

```ijm
run("Log-Ratio Registration Batch...",
    "input=[D:/recordings/day 1] output=[D:/recordings/day 1 corrected] recursive no_overwrite " +
    "image_type=DENSE_FLUORESCENCE motion_type=STEADY_DIRECTIONAL_DRIFT selection_mode=automatic " +
    "channel=2 slice=0 interpolation=NONE crop");
```

An automatic folder batch records `automatic_filters`, intentionally making a separate choice for each
replayed stack. A batch with automatic selection cleared records the explicit shared settings. Batch-only
options are `input`, `output`, `recursive` / `no_recursive`, and `overwrite` / `no_overwrite`.

| Option | Default | Meaning |
|---|---|---|
| `image_type` | `PHASE_CONTRAST` | Image signal used for advice |
| `motion_type` | `SUBPIXEL_RANDOM_WALK` | Movement pattern used for advice |
| `recommended` / `manual` | `manual` | Load advice or use explicit fit settings |
| `automatic_filters` / `no_automatic_filters` | off in macros | Choose a validated preprocessing and pixel-removal addition from each stack |
| `channel` | `1` | One-based estimation channel |
| `slice` | `0` | Z slice; zero uses a maximum-intensity projection |
| `preprocessing` | `NONE` | `NONE`, Gaussian smoothing, `MEDIAN_3X3`, photon-noise stabilisation or mild sharpening |
| `pixel_selection` | `NONE` | Optional second-pass removal rule; `REMOVE_LEAST_INFORMATIVE` is the validated sparse-signal choice |
| `mask_preprocessing` | `NONE` | Filter used only to choose the mask; raw pixels still drive the final fit |
| `pixel_removal` | `25` | Percentage of eligible pixels removed by the second pass |
| `estimation_scale` | `1.0` | Fraction of width and height used to estimate movement; output stays full size |
| `reference` | `MULTILAG` | `CONSECUTIVE`, `MULTILAG`, `FIXED` or `ROLLING` |
| `reference_frame` | `1` | One-based fixed reference frame |
| `lags` | `1,2,4,8,16` | Frame gaps for multi-lag reconciliation |
| `norm` | `HUBER` | `LEAST_SQUARES`, `HUBER` or `TUKEY` weighting |
| `support` | `ALL` | `ALL`, `GRADIENT` or `MUTUAL_NOISE_GRADIENT` pixels |
| `gradient` | `0.5` | Gradient threshold multiplier |
| `floor`, `ceiling` | `off` | Per-frame intensity percentiles to exclude |
| `auto_max_shift` | on | Measure the search bound from the recording |
| `interpolation` | `NONE` | `NONE`, `BILINEAR` or `BICUBIC` |
| `crop` | on | Keep only the common real field |

`recommended` cannot be combined with `preprocessing`, `pixel_selection`, `mask_preprocessing`,
`pixel_removal`, `norm`, `support`, `gradient`, `floor` or `ceiling`. Use `manual` when supplying those
settings explicitly.

`automatic_filters` can be combined with the recommended base model or a manually specified base
model, but not with explicit `preprocessing`, `pixel_selection`, `mask_preprocessing` or
`pixel_removal` values.

## Groovy or Jython without dialogs

```groovy
import logratio.api.*

def parameters = LogRatioParameters.builder()
    .recommendation(ImageType.DENSE_FLUORESCENCE, MotionType.STEADY_DIRECTIONAL_DRIFT)
    .automaticFilterSelection(true)
    .channel(1)
    .build()
def result = LogRatioRegistration.register(imp, parameters)
result.correctedImage().show()
```

The public API neither shows a dialog nor modifies the input. The caller owns the returned corrected
`ImagePlus` and should close it when finished.

Folder batches also have a no-dialog Java application programming interface:

```groovy
import logratio.api.*
import logratio.core.PairScheduler

def batch = LogRatioBatchParameters.builder(
    new File("D:/recordings"),
    new File("D:/recordings_corrected"),
    parameters)
    .recursive(true)
    .overwrite(false)
    .build()

def summary = LogRatioBatch.run(batch, { status ->
    println "${status.completedFiles}/${status.totalFiles}: ${status.currentFile}"
} as LogRatioBatch.ProgressListener, { false } as PairScheduler.Cancellation)
println summary.reportFile
```

## Build

```text
mvn test
mvn package
```
