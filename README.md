# Relative-Intensity Pattern Registration

A Fiji/ImageJ time-series registration plugin that estimates movement by matching relative spatial
intensity patterns between frames. Global brightness change is handled separately, so bleaching or
illumination changes do not have to look like movement.

The repository also contains **Relative-Intensity Pattern Registration (RIPR)**, a native, installable
Python package with the same registration engine, recommendations, hyperstack behavior, TIFF support and folder batches. See
[README_PYTHON.md](README_PYTHON.md) for installation and examples; it does not require ImageJ or Java.

The three recipe categories load evidence-based parameter recommendations. Every recommended value can
be replaced in **Advanced settings**.

The plugin has three rotation choices: **Off**, **Search continuously**, and **Known remount frames**.
Continuous mode estimates bounded in-plane rotation for every compared pair. Set **Maximum rotation**
in degrees; transforms store `theta` in radians internally and in the Java/Python result objects. Both
log-ratio and area-correlation estimators implement the continuous rigid (translation plus rotation)
search. Automatic mode first resolves the same estimator, filter,
brightness exclusions, strong-area support and pixel mask it would use for translation. It then tests
rotation from that exact translation result and retains it only when the full-frame residual improves
by at least 0.005. A declined rotation leaves the selected translation numerically unchanged. The run
log and batch report state how many pairwise proposals were accepted and declined. Incremental rotation
does not support a rolling reference template. `NONE` interpolation uses nearest-neighbour sampling
when rotation is non-zero, so choose
bilinear, bicubic or Fourier interpolation for smoother intensity images; pure integer translations
retain their bit-exact block-copy path. `FOURIER` applies padded Fourier shifts and represents rotation
as three Fourier shears; it is sharp but can ring near hard edges.

**Known remount frames is an experimental, opt-in mode.** Enter the one-based first frame after each
remount, such as `25,51`. Around each boundary it runs the accepted log-ratio rigid fit over every
available before/after pair in the selected window, requires at least three usable fits, and forms one
robust angular jump. The cumulative angle is held exactly constant until the next event. Ordinary
registration then fits translation with those relative angles fixed, protects declared remount steps
from generic outlier repair, and applies the composed transform to the untouched input once. A large
cross-pair spread is reported as a warning; insufficient evidence stops the run. Rolling references and
area-correlation event angles are not supported. This mode has synthetic and Java/Python regression
coverage but awaits independent real remount recordings before promotion; it is not an automatic or
global default.

**Two estimators, and the default is the log-ratio fit.** A second estimator — plain normalised
cross-correlation on a pyramid, the classical area method — is manually selectable and measured better
for brightfield/DIC and fiducial/static in the estimator benchmark. The finalized automatic selector did
not promote those overrides because they failed later crop and runtime safety gates. A subsequent explicit
user decision accepted those isolated trade-offs and enabled the measured fixed image-type policy. It is not better
everywhere; see [Which estimator, and where each one wins](#which-estimator-and-where-each-one-wins)
for the numbers, losses included. Since 2026-08-19 the area method locates its sub-pixel peak with a
Gauss–Newton step read off the correlation surface's own derivatives rather than a shrinking grid
search, which is **24% faster over a whole recording** at the same accuracy; the grid remains
selectable as `area_correlation`. On the sealed test set the faster refinement measured five
nanopixels worse, against a gate that allowed none, and was adopted anyway as a deliberate trade —
see `docs/newton_refinement_stage4_third_set_findings.md`.

## Interactive use

Open a time series and choose **Plugins > Registration > Relative-Intensity Pattern Registration...**.

1. Choose a **Recipe**: **Landmarks** (phase contrast/brightfield), **Bright/dim references**
   (fluorescence/bioluminescence), or **Moving cells** (biological foreground).
2. Choose the one-based **Channel used to estimate movement**.
3. Leave **Use longitudinal mode (whole recording)** on for the fixed whole-recording route (the
   default). Moving cells uses a benchmark-backed frame-to-frame recipe, so leave it off for that
   recipe.
4. Select **Show advanced settings before running** only when you need to change fitting, rotation,
   interpolation, or other expert controls. Then press **Register**.

### Which estimator, and where each one wins

The **pairwise estimator** decides how one frame pair is measured. Everything above it — the pair
plan, the reconciliation, the repair, the warp — is the same either way, so this is a genuine choice
between estimators rather than separate pipelines.

| Estimator | What it does | Where it wins |
|---|---|---|
| **Log-ratio fit** (default) | Levels the log-ratio field between two frames, fitting the intensity gain separately and rejecting genuine change with a robust norm | Phase contrast, dense fluorescence, sparse low-light |
| **Area correlation** | Normalised cross-correlation on a pyramid with sub-pixel peak refinement, on an interpolating cubic B-spline image model | Brightfield/DIC, fiducial/static |
| **Enhanced Correlation Coefficient** | Iteratively aligns the whole image while allowing its brightness to change | Real dense and low-light fluorescence time-lapses |

Enhanced Correlation Coefficient is an existing optimizer from Evangelidis and Psarakis
([2008, DOI 10.1109/TPAMI.2008.113](https://doi.org/10.1109/TPAMI.2008.113)); RIPR does not claim to
have invented it. The RIPR method claim is the complete registration system: its log-ratio route,
fixed image-type recipes, same-channel longitudinal references, motion guards, reconciliation and
validated routing. Enhanced Correlation Coefficient is one clearly named optimizer inside that system.

Automatic uses the frozen declared-type policy. Dense fluorescence uses median-filtered Enhanced
Correlation Coefficient against the previous image; sparse/low-light fluorescence or bioluminescence
uses its tuned log-ratio image-and-motion recipe. Only the selected channel is read.

Measured on 40 sealed recordings that took no part in choosing anything — ten sources, two per image
type, none of them used elsewhere in this project. Lower is better; the last column shows the best tested
estimator in that historical estimator-only comparison:

| Image type | Log-ratio fit | Area correlation | Best tested estimator |
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

The simple dialog maps those choices to one exclusive settings mode:

| Choice | What it does |
|---|---|
| Automatic fixed recipe | Applies the installed validated fixed recipe for the Landmarks or Bright/dim category. It does not inspect the recording. |
| Longitudinal maximum accuracy (simple-dialog default) | Adds whole-recording bright/dim or tissue-landmark references and protects isolated stage jumps and light pulses. |
| Image-and-motion preset | Used by the Moving cells category, which keeps frame-to-frame fitting and does not use the longitudinal route. |
| Manual | Used after editing values in Advanced settings. |

Non-fluorescence keeps `recording_adaptive_selector_v1_user_approved_fixed_policy_v1`. Fluorescence
uses `single_channel_emission_max_accuracy_r04_a208` for fluorescence and bioluminescence. Both choose the recipe without inspecting
the recording. A chosen recipe may still contain more than one fitting pass; those passes execute the
already chosen recipe and do not select a new one.

### Longitudinal maximum accuracy

Use **Longitudinal maximum accuracy** for long Incucyte or LV200 recordings dominated by slow drift,
gentle shake, isolated stage movements and large light changes. It first runs the unchanged Automatic
fixed recipe, then uses the complete selected channel as a second source of position evidence:

- fluorescence and bioluminescence use bright and dim same-channel references;
- phase contrast and brightfield/DIC use tissue edges and locally dark landmarks;
- brief returning movements coupled to a light pulse are removed;
- persistent jumps, near-dark final jumps and rare rigid remounts are retained only with independent
  agreement checks.

It does not inspect another channel. The resulting timepoint transform is still applied to every
channel and Z slice. Keep **Automatic fixed recipe** for repeated oscillation or continuous rotation;
the artificial-motion benchmark showed that the longitudinal prior is not universal.

Automatic resolves to visible settings; editing them re-labels the run as Manual. Longitudinal
maximum accuracy stays a separate fixed choice and records that exact mode in the run log. See
`docs/recording-adaptive-selector/09_final_validation_findings.md` for the sealed decision
and `docs/recording-adaptive-selector/10_user_approved_fixed_policy_override.md` for the explicit override.

### Parameter sweep on one stack

Choose **Advanced parameter sweep...** in the registration dialog to compare up to three
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
open, and every selected value is loaded as an explicit manual recipe. The settings can be reviewed under
**Show advanced settings before running** before registration. Starting a sweep first
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
and every Z slice. A new dialog ranks the channels by localisable movement evidence and recommends the
best one; choosing a poor channel deliberately produces a warning before registration starts. Channel
count, Z count, time count, calibration and hyperstack layout are preserved.

## Folder batches

Choose **Plugins > Registration > Relative-Intensity Pattern Registration Batch...** to apply one setup to a folder of
TIFF or OME-TIFF stacks. Select the input and output folders, whether to include subfolders, and the
shared recipe, estimation channel and longitudinal mode. The remaining registration settings are under
**Show advanced settings before starting**.

Landmarks and Bright/dim references use the installed automatic fixed recipe when longitudinal mode is
off. Moving cells uses the benchmark-backed image-and-motion preset and keeps longitudinal mode off.

The batch holds one stack in memory at a time. A modeless progress window shows the current file,
completed stacks, pair progress, elapsed time, estimated time remaining and estimated finish time. The
estimate appears after the first completed stack and is updated from the measured average stack time.
**Cancel** stops safely when the current registration work yields.

Output stacks are named `<source>_registered.tif` and retain the input subfolder layout. Existing output
stacks are skipped unless **Overwrite existing corrected stacks** is selected. A damaged or incompatible
file is recorded as an error and the remaining files continue. Every run writes
`log_ratio_batch_report.csv` with the input, output, status, elapsed time, before/after residual and error
for each file. It also records the automatic recipe, model provenance, fallback and any available evidence
scores when automatic selection is enabled. The existing columns are preserved, with rotation mode, one-based event frames, event window
and compact event diagnostics appended at the end.

## Reusable method benchmark

Run `scripts/run-registration-benchmark.cmd` with any TIFF folder and any listed subset of RIPR or
installed Fiji methods. It saves full registered TIFFs, scrolling montage stacks, transforms, times and
the frame-to-image-1 alignment guide. See `docs/reusable_registration_benchmark.md` for the one-command
example and `-ListMethods` for accepted method names.

## Recommendations

The recommendation matrix comes from the balanced controlled benchmark: five image types, four source
series per type and the same four known motion paths. Each recommendation is the lowest-error tested
log-ratio configuration for that image-type and motion-type pair. The benchmark and full output stacks
are in `library/benchmark/v2/`.

The recording-adaptive selector study froze 128 estimator/support/band/filter/mask recipes, 48 truth-free
recording features, source-grouped splits and every gate before outcomes were opened. Development used
275 recordings from 19 independent groups. The source-balanced oracle ceiling improved the category
policy by 0.00402 px (71.5%), below the required absolute 0.005 px, so recording-level model fitting
stopped and a fixed image-type policy alone advanced to reserved validation.

That fixed policy improved reserved-validation median error by 0.01120 px but failed the retained-crop
gate. On the once-opened final set of 110 recordings from 10 untouched sources, it again improved median
error, from 0.01081 to 0.00544 px, but failed both retained-crop and runtime gates. The candidate selector
also failed its frozen accuracy and safety gates. The mechanically promoted production result is therefore
`recording_adaptive_selector_v1_final_category_recommendation`. That sealed result remains unchanged.
A subsequent user-approved production decision accepted the isolated crop/runtime misses and installed
`recording_adaptive_selector_v1_user_approved_fixed_policy_v1`. It uses one deterministic recipe per image
type, no provisional selector pass, and complete Java/Python provenance. The later fluorescence-only
route `single_channel_emission_max_accuracy_r04_a208` adds the fixed fluorescence and bioluminescence policies
described above. Historical selector and
estimator-only studies remain in `docs/full_automatic_selector_sweep_results.md` and
`docs/pairwise_estimator_axis_findings.md`; the controlling result is
`docs/recording-adaptive-selector/09_final_validation_findings.md`; the later trade-off decision is in
`docs/recording-adaptive-selector/10_user_approved_fixed_policy_override.md`; and the fluorescence
rescue result is in `docs/single_channel_fluorescence_rescue_r03.md`.

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
run("Relative-Intensity Pattern Registration...",
    "image_type=PHASE_CONTRAST motion_type=SUBPIXEL_RANDOM_WALK selection_mode=automatic");
```

`estimator` takes `log_ratio_fit` (the default), `area_correlation_ecc`, `area_correlation_newton` or `area_correlation` (the
same correlation with the older grid refinement). Supply it in **Manual** mode. Automatic and Image-and-motion preset
resolve the estimator as part of their complete recipe and reject an explicit contradictory value. A
recorded run is emitted as Manual with the estimator that actually ran, so replay is stable.

`selection_mode` takes `automatic`, `longitudinal_accuracy`, `recommended` or `manual`. The older `recommended`,
`automatic_filters` and `manual` tokens still work and are rejected only when they contradict an explicit
`selection_mode`, so existing macros keep their meaning. A macro with no mode token at all means `manual`.
Automatic mode rejects an explicit support, brightness limit, filter, mask or compute budget rather than
silently discarding it.

An explicit batch-safe run that never opens the dialog:

```ijm
run("Relative-Intensity Pattern Registration...",
    "image_type=DENSE_FLUORESCENCE motion_type=STEADY_DIRECTIONAL_DRIFT manual " +
    "fit_rotation max_rotation_degrees=10 incremental_rotation " +
    "minimum_rotation_residual_gain=0.005 " +
    "channel=1 slice=0 reference=MULTILAG reference_frame=1 lags=1,2,4,8,16 " +
    "preprocessing=NONE " +
    "pixel_selection=NONE mask_preprocessing=NONE pixel_removal=25 " +
    "template_window=5 estimator=log_ratio_fit norm=HUBER support=ALL gradient=0.5 epsilon=1.0 " +
    "floor=off ceiling=off no_remove_offset offset_percentile=1.0 auto_max_shift " +
    "max_shift=30 outlier_mads=6 iterations=25 max_samples=200000 min_valid=0.1 " +
    "threads=0 interpolation=NONE crop");
```

An event-anchored run whose remounts begin at frames 25 and 51:

```ijm
run("Relative-Intensity Pattern Registration...",
    "selection_mode=manual rotation_mode=known_events rotation_events=25,51 " +
    "rotation_event_window=3 max_rotation_degrees=10 reference=MULTILAG " +
    "estimator=log_ratio_fit interpolation=BILINEAR");
```

`fit_rotation` remains the backward-compatible spelling for continuous rotation. Do not combine it
with `rotation_mode`; contradictory old and new controls are rejected.

Interactive runs are recorded as complete `manual` option strings after automatic selection has been
resolved. That freezes the exact chosen recipe, so replay does not classify the stack again or change
when the model is improved in a later version.

A folder batch can also run without dialogs. Brackets preserve folder paths containing spaces:

```ijm
run("Relative-Intensity Pattern Registration Batch...",
    "input=[D:/recordings/day 1] output=[D:/recordings/day 1 corrected] recursive no_overwrite " +
    "image_type=DENSE_FLUORESCENCE motion_type=STEADY_DIRECTIONAL_DRIFT selection_mode=automatic " +
    "channel=2 slice=0 interpolation=NONE crop");
```

An automatic folder batch records `selection_mode=automatic`, intentionally resolving the installed
model for each replayed stack. A manual batch records the explicit shared settings. Batch-only
options are `input`, `output`, `recursive` / `no_recursive`, and `overwrite` / `no_overwrite`.

| Option | Default | Meaning |
|---|---|---|
| `image_type` | `PHASE_CONTRAST` | Image signal used for advice |
| `motion_type` | `SUBPIXEL_RANDOM_WALK` | Movement pattern used for advice |
| `selection_mode` | `manual` in macros | `automatic`, `longitudinal_accuracy`, `recommended` or `manual`; the dialog defaults to Automatic |
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
| `fit_rotation` / `no_fit_rotation` | off | Estimate bounded in-plane rotation as well as translation |
| `rotation_mode` | `off` | `off`, `continuous`, or experimental `known_events`; legacy `fit_rotation` means `continuous` |
| `rotation_events` | empty | Comma-separated one-based first frames after remounting; only for `known_events` |
| `rotation_event_window` | `3` | Provisional number of frames drawn from each side of an event |
| `max_rotation_degrees` | `10` | Symmetric rotation bound in degrees |
| `incremental_rotation` / `no_incremental_rotation` | on | Fit translation first and retain rotation only when it improves the full-frame residual |
| `minimum_rotation_residual_gain` | `0.005` | Fractional full-frame residual improvement required to retain rotation |
| `interpolation` | `NONE` | `NONE`, `BILINEAR`, `BICUBIC` or `FOURIER` |
| `crop` | on | Keep only the common real field |

`recommended` cannot be combined with `preprocessing`, `pixel_selection`, `mask_preprocessing`,
`pixel_removal`, `norm`, `support`, `gradient`, `floor` or `ceiling`. Use `manual` when supplying those
settings explicitly.

Legacy `automatic_filters` remains accepted as an Automatic-mode alias. Like explicit
`selection_mode=automatic`, it cannot be combined with a manually supplied estimator or recipe setting.

## Groovy or Jython without dialogs

```groovy
import ripr.api.*

def parameters = RelativeIntensityPatternParameters.builder()
    .recommendation(ImageType.DENSE_FLUORESCENCE, MotionType.STEADY_DIRECTIONAL_DRIFT)
    .selectionMode(SelectionMode.AUTOMATIC)
    .channel(1)
    .build()
def result = RelativeIntensityPatternRegistration.register(imp, parameters)
result.correctedImage().show()
```

The public API neither shows a dialog nor modifies the input. The caller owns the returned corrected
`ImagePlus` and should close it when finished.

Folder batches also have a no-dialog Java application programming interface:

```groovy
import ripr.api.*
import ripr.core.PairScheduler

def batch = RelativeIntensityPatternBatchParameters.builder(
    new File("D:/recordings"),
    new File("D:/recordings_corrected"),
    parameters)
    .recursive(true)
    .overwrite(false)
    .build()

def summary = RelativeIntensityPatternBatch.run(batch, { status ->
    println "${status.completedFiles}/${status.totalFiles}: ${status.currentFile}"
} as RelativeIntensityPatternBatch.ProgressListener, { false } as PairScheduler.Cancellation)
println summary.reportFile
```

## Build

```text
mvn test
mvn package
```
