# Registration plugin menu and benchmark version 2

Date: 2026-08-13

## Decisions already made

1. The plugin will have one **Automatic (recommended)** preset and a collapsed **Advanced** section.
2. Automatic will mean a decision from image evidence, never a modality label supplied by the user.
3. The current three benchmark recordings are development data. They cannot be used to support the next
   accuracy claim because the automatic selector and several thresholds were chosen after seeing them.
4. Every competing method gets a parameter search. Parameters are selected on development and validation
   recordings, frozen, and run once on an untouched test set.
5. Accuracy is measured from known injected camera movement on real microscopy pixels. Native unmodified
   time series remain a separate realism check because they normally have no exact camera-motion truth.
6. Alias commands that call the same engine, such as MultiStackReg and Register Virtual Stack Slices, are
   labelled as aliases rather than counted as independent algorithms.
7. The raw library has no fixed size. The headline cohort always contains the same number of independent
   series from each image-series class; additional series remain in the supplementary library until every
   class can grow by the same amount.

The core registration application programming interface exists, but there is not yet a Fiji menu command in
`src/main`. The menu below is therefore the target front end, not a description of a dialog that already ships.

## Menu structure

The dialog has three sections. Only **Input and preset** is expanded initially.

### 1. Input and preset

| Control | Default | Other user choices | Behaviour |
|---|---|---|---|
| Preset | **Automatic (recommended)** | Accuracy; Fast; Manual | Automatic chooses from measured image evidence. Selecting Manual reveals every registration control. |
| Estimate movement from channel | **Best stable channel automatically** | Channel 1…N | Channels are ranked by localisability: how strongly a one-pixel movement changes the image. The chosen channel is reported. |
| Estimate movement from Z | **Maximum-intensity projection** | Current slice; selected slice; mean projection | Only the estimation image changes. The resulting movement is still applied to every Z slice. |
| Estimation region | **Whole image** | Current region of interest; current mask | Lets a user isolate stable tissue or fiducial beads while excluding moving cells. |
| Movement model | **XY translation** | XY translation plus rotation, when implemented and benchmarked | Rotation remains unavailable until it has its own ground-truth benchmark. |
| Expected maximum movement | **Detect automatically** | Manual pixels per frame | Sets the search boundary. A result touching the boundary is flagged, never silently accepted. |
| Time averaging | **1 frame** | 2; 3; 5; 10; custom | Averages adjacent frames only for estimating movement. Useful for sparse or photon-limited images. |

### Presets

| Preset | Reference strategy | Pixel evidence | Robust fit | Compute policy |
|---|---|---|---|---|
| **Automatic (recommended)** | Multiple-lag graph | Automatic information selector | Tukey outlier-resistant fit | Automatic levels, samples, threads and movement bound |
| Accuracy | Multiple-lag graph | Automatic information selector | Tukey outlier-resistant fit | All useful samples; no speed shortcuts |
| Fast | Previous frame | All pixels | Least-squares fit | Automatic pyramid; regular sampling cap |
| Manual | User choice | User choice | User choice | User choice |

The automatic information selector now lives in `src/main`, is called by the benchmark and is tested through
the public application programming interface. It does not become the plugin default until its frozen rule is
revalidated on the untouched benchmark version 2 test set.

### 2. Advanced registration

| Control | Default | Choices to expose | Dependency or warning |
|---|---|---|---|
| Reference strategy | Automatic | Multiple-lag graph; previous frame; first/chosen frame; rolling template | Multiple-lag is the accuracy path. Previous frame is the speed path. |
| Multiple-lag pattern | Automatic | `1,2,4,8,16`; `1,3,9,27`; Fibonacci-style; custom | Visible only for the multiple-lag graph. Lag 1 is mandatory. |
| Fixed reference frame | First | Frame number | Visible only for fixed reference. |
| Rolling-template window | 5 | 3; 5; 9; custom | Visible only for rolling reference. |
| Pixel evidence | Automatic | All; gradient; significant edge in both frames; brightest-band exclusion; dimmest-band exclusion | Automatic is the recommended choice after version 2 validation. |
| Experimental movement-based evidence | Off | Remove most-moving pixels; remove least-moving pixels; keep global modal movement | Hidden under Experimental because none beat Automatic in the first benchmark. |
| Gradient strength | Automatic | 0; 0.25; 0.5; 1; 2; 4 times the frame median; custom | Visible for gradient evidence. |
| Movement-based removal | 55% | 25%; 40%; 55%; 70%; custom | Visible only for an experimental movement-based selector. |
| Bright artefact rejection | **Hard-clipped pixels only** | Off; exclude brightest percentage; use mask | A percentile removes real sample even when there is no artefact, so it is never silently enabled. |
| Dim-background rejection | Off | Exclude dimmest percentage | Modality-dependent and advanced only. |
| Robust fit | Automatic | Least squares; Huber; Tukey | The menu updates the recommendation when the reference strategy changes. |
| Correct global intensity gain | **On** | Off | Turning this off removes the defining gain invariance. Multiple-lag plus Off is refused. |
| Remove additive background | Off | On, with percentile | Useful when camera offset changes; the estimated background is reported. |
| Log offset | Automatic | Manual positive value | Advanced expert control; automatic scales it to the image intensity range. |
| Pyramid levels | Automatic | 1–6 | Automatic derives the level count from image size and movement bound. |
| Iterations per level | 25 | 12; 25; 50; custom | A non-converged result is flagged. |
| Convergence tolerance | 0.001 px | Custom | Expert-only numeric control. |
| Minimum usable pixels | 10% | Custom | The pair is refused below this value rather than guessed. |
| Maximum sampled pixels | 200,000 | 50,000; 200,000; all; custom | Controls the accuracy-and-speed trade-off. |
| Step outlier repair | On | Off; threshold in median absolute deviations | Every repaired frame is listed in the result table. |
| Processing threads | Automatic | 1…N | Automatic respects the memory budget. |
| Memory limit | Automatic | Manual gigabytes | Controls pyramid caching and worker count. |

### 3. Output and quality control

| Control | Default | Other choices | Behaviour |
|---|---|---|---|
| Apply correction | **All channels and Z slices** | Estimation channel only; transforms only | Stage movement affects the acquisition, not one channel. |
| Interpolation | **Whole-pixel, preserve measured values** | Bilinear subpixel; bicubic subpixel | Whole-pixel is bit-exact. The two subpixel choices resample intensities and are labelled accordingly. |
| Crop common valid area | **On** | Off | Prevents the moving empty border entering later measurements. The future output dimensions are shown before Run. |
| Create corrected copy | **On** | Replace current image | Replacement requires a confirmation because it is destructive. |
| Save movement table | **On** | Off | One row per time point with X, Y, gain, support, residual and status. |
| Show movement plot | **On** | Off | X and Y displacement over time. |
| Show quality report | **On** | Off | Pre/post residuals, localisability, refused pairs, repairs and boundary hits. |
| Save settings | **On** | Off | Saves a reusable settings file and a macro/headless command. |
| Failure policy | Repair and flag | Leave failed frame unchanged; abort | Repair never hides the failure flag. |

The Run button first performs a preflight. It shows the selected channel, localisability, estimated movement
bound, planned pair count, output dimensions, estimated memory and any configuration warning. The user can
then Run, change settings, or cancel.

## Benchmark version 2 library

### Balanced, expandable composition

The library is a warehouse; the headline cohort is an equally stocked display shelf. Download and retain
every eligible independent series, but give each of these five image-series classes exactly one fifth of the
headline result:

| Image-series class | Headline share | Includes |
|---|---:|---|
| Phase contrast | 20% | Label-free phase-contrast time series |
| Brightfield and differential interference contrast | 20% | Other transmitted-light time series |
| Dense fluorescence | 20% | Fluorescent structure across much of the field, including suitable XY projections of three-dimensional stacks |
| Sparse or low-light fluorescence | 20% | Dark-background fluorescence, bioluminescence and photon-limited series |
| Fiducial or static reference | 20% | Beads, calibration slides and biologically static textured fields |

Artefacts, focus loss, fading, noise level, dimensionality and moving biological content are test conditions,
not image-series classes. They are distributed evenly within every class so an artefact-rich source cannot
become a sixth class and distort the balance.

For each split, let **N** be the number of eligible independent series available in the scarcest class. The
headline cohort takes exactly **N** series from every class. Surplus series are still processed and reported
as the all-series supplementary result, but they cannot change the headline winner. The headline cohort grows
only in five-series rounds: one new independent series from every class.

Split by original experiment, not by clip, field or frame. Each class uses the same 2:1:1 allocation:

| Split within every class | Share | Permitted use |
|---|---:|---|
| Development | 50% | Broad parameter search and automatic-selector development |
| Validation | 25% | Select one accuracy configuration and one speed configuration per method |
| Locked test | 25% | Run frozen configurations once; this is the only set used for the headline claim |

Round odd counts down for Validation and Locked test, then place the remainder in Development. Before the
first locked run, each class must contain at least four independent experiment groups in both Validation and
Locked test. Series from one experiment never cross splits. The three current series remain in Development
because they have already influenced the algorithm.

Within a class, first average series belonging to the same original experiment, then average experiments.
This stops a repository containing dozens of near-duplicate fields from outweighing several genuinely
independent sources. Report a leave-one-repository-out check to show whether the winner depends on one source.

The class definitions and machine-checkable balance rules are recorded in
`library/benchmark/benchmark_v2_balance_policy.csv`.

### Two complementary evaluations

1. **Exact camera-movement test.** Take real microscopy pixels, create subpixel movement by integrating an
   oversampled source, and inject registered trajectories with exact X/Y truth. Biological change, fading,
   camera offset, noise, blur and artefacts are added independently of movement.
2. **Native-series realism test.** Run the unmodified public time series. Report cyclic consistency, failed
   frames, residual improvement and stable-landmark movement where annotations exist. Do not call this
   accuracy when exact camera truth is absent.

Each locked-test class receives equal numbers of the same six declared conditions: clean, multiplicative fade,
additive offset drift, low signal-to-noise ratio, local moving content and real/injected artefacts. Three
declared movement trajectories cover slow random walk, directional creep and intermittent jumps. Conditions
and trajectories are assigned as a balanced block, with assignments rotated across classes, so every source
is not expanded into an impractical full Cartesian product.

Candidate sources and their licensing/download status are recorded in
`library/benchmark/benchmark_v2_dataset_manifest.csv`.

### Dropbox layout

All benchmark version 2 data and outputs live under `library/benchmark/v2` in Dropbox. Downloads are kept
once under `sources`; every usable recording is then exposed through one clearly named folder under `series`:

```text
v2/
|-- sources/<source_id>/downloaded archive or image
|-- balanced_clean_method_summary.csv   one row per method with balanced accuracy and time
|-- balanced_clean_series_winners.csv   one winner row per series
|-- balanced_clean_run_status.csv       completeness audit
|-- series/<series_id>/                 balanced working cohort
    |-- series.csv
    |-- source/                         original or extracted image series
    `-- comparisons/<condition>/
        |-- 00_input_uncorrected.tif
        |-- 01_<method>_corrected.tif
        |-- 01_<method>_transforms.csv
        |-- ...every independent method and declared submethod...
        |-- comparison.csv              accuracy and time for every row
        `-- diagnostics/<method>_before_after_motion.tif
`-- supplementary_series/<series_id>/  surplus prepared series with the same folder shape
```

Method numbers and names are identical in every series folder. Aliases are present for visual comparison
but are marked as the same engine in `comparison.csv`. No file is labelled merely “automatic winner”; the
actual selected support rule is included in its filename and result row.

## Fair parameter selection

Parameter search follows the pattern of a practice set, a qualifying round and a sealed final:

1. **Coarse search:** all declared configurations on short 24-frame Development clips.
2. **Successive halving:** discard the worst 75% using a predeclared accuracy/failure/runtime score; run the
   survivors on longer and harder Development clips.
3. **Validation:** take the best three configurations per method onto the balanced Validation cohort.
4. **Lock:** choose one accuracy configuration and one speed configuration per independent algorithm family.
5. **Test once:** run the locked configurations on the balanced untouched Test cohort. No parameter, automatic
   threshold or failed run is changed afterwards.

Methods may automatically adapt to an image only when that adaptation is part of the declared algorithm and
uses image evidence available at run time. No method receives a hand-selected configuration per modality or
test recording.

The declared search space is in `library/benchmark/benchmark_v2_parameter_space.csv`.

### Reported outcomes

| Outcome | Definition |
|---|---|
| Median trajectory error | Median Euclidean distance between estimated and true cumulative X/Y movement |
| 90th-percentile trajectory error | The difficult tail, not hidden by the median |
| Failure rate | Fraction of frames above 1 px and 5 px, plus refused and non-converged pairs |
| Endpoint drift | Final cumulative error, which exposes chain accumulation |
| Runtime | Estimation CPU time, estimation elapsed time and full correction elapsed time, normalized per megapixel-frame |
| Memory | Peak working memory |
| Output fidelity | Exact-pixel preservation or the declared interpolation used; never mixed into transform accuracy |

The headline value is the average of the five class-level results, so every class has exactly 20% influence.
Within each class, experiments have equal influence. Confidence intervals are bootstrapped across independent
experiments, not across correlated frames. The headline winner requires lower error without a materially
higher failure rate in any one class. Speed is reported separately; it is not hidden inside one arbitrary
combined score. The all-series supplementary result is always shown beside the headline result but cannot be
used for the winner claim.

## Execution order

1. Add dataset downloaders, checksums, licence files, class labels and a raw/derived cache layout.
2. Generalize the benchmark generator from three hard-coded seeds to the series manifest and refuse an
   unbalanced headline cohort.
3. Generalize every external adapter to accept a parameter object and emit one common result schema.
4. Run the coarse search and successive halving on balanced Development classes.
5. Freeze configurations after balanced Validation and write them to a versioned lock file.
6. Run the locked balanced Test cohort once; run every surplus series as supplementary evidence.
7. Regenerate comparison TIFF folders only for the finalists and build the menu above with macro/headless parity.
