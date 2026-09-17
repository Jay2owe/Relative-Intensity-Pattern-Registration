# Log-Ratio Registration

[![Build](https://github.com/Jay2owe/Log-Ratio-Registration/actions/workflows/build-main.yml/badge.svg)](https://github.com/Jay2owe/Log-Ratio-Registration/actions/workflows/build-main.yml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

A Fiji/ImageJ plugin for registering microscopy time-lapses with a gain-invariant robust log-ratio estimator.

Log-Ratio Registration estimates frame-to-frame translation while fitting global intensity gain separately, so bleaching and uniform brightness changes do not have to look like motion. It works without feature detection or segmentation, supports multi-channel and Z-stack time series, and offers interactive, batch, macro, and Java APIs.

![Log-Ratio Registration dialog](docs/screenshots/main-dialog.png)

## Features

- **Gain-invariant registration** — estimates movement from spatial variation in the log ratio between frames while fitting global gain separately.
- **Robust change rejection** — Huber and Tukey losses reduce the influence of structures that appear, disappear, or change locally.
- **Two estimators** — the default log-ratio fit and an optional normalized area-correlation estimator share the same scheduling, reconciliation, repair, and warping pipeline.
- **Automatic recommendations** — image and motion categories load validated parameter recipes; supported categories can select additional settings from the recording itself.
- **Hyperstack support** — estimates one transform per timepoint from a selected channel and Z slice or projection, then applies it to every channel and Z slice.
- **Parameter sweeps** — compares up to 24 combinations on one stack without modifying the source image.
- **Folder batches** — processes TIFF and OME-TIFF stacks one at a time, preserving subfolders and writing a CSV report.
- **Automation APIs** — records ImageJ macros, accepts headless macro options, and exposes a no-dialog Java API.

## Installation

### GitHub release

The Fiji update site is being provisioned. Until it is publicly listed and verified, install the release JAR manually:

1. Close Fiji.
2. Download `LogRatioRegistration-0.1.0.jar` from the [v0.1.0 GitHub release](https://github.com/Jay2owe/Log-Ratio-Registration/releases/tag/v0.1.0).
3. Remove any older `LogRatioRegistration-*.jar` from `Fiji.app/plugins/`.
4. Copy the downloaded JAR into `Fiji.app/plugins/`.
5. Restart Fiji.

The commands appear under:

- **Plugins > Registration > Log-Ratio Registration...**
- **Plugins > Registration > Log-Ratio Registration Batch...**

### Requirements

- Fiji or ImageJ 1.54p or newer.
- Java 8 or newer at runtime.
- No optional plugin dependency is required for the registration engine.

## Usage

### Register one time series

1. Open a time series in Fiji.
2. Choose **Plugins > Registration > Log-Ratio Registration...**.
3. Select the closest image type and motion type.
4. Leave **Settings source** on **Automatic full selection** for the default workflow, or choose **Recommended** or **Manual**.
5. Select **Review and edit all settings before running** when you want to inspect the resolved recipe.
6. Choose **Register**.

The input is never modified. The plugin creates a corrected image and writes the complete resolved settings and fit diagnostics to the ImageJ log.

### Settings sources

| Source | Behaviour |
|---|---|
| **Automatic full selection** | Starts from the chosen image/motion recommendation and, for supported categories, measures the recording to resolve the estimator and other validated choices. |
| **Recommended** | Uses the fixed validated recipe for the chosen image and motion categories. |
| **Manual** | Uses the explicit values in the settings window without classification. |

Automatic choices are shown as ordinary editable values before execution when review is enabled. Editing one turns the run into an explicit manual recipe, making the recorded macro reproducible.

### Parameter sweep

Choose **Sweep parameters on this stack...** to compare up to three settings and 24 combinations. Runs are sequential to bound memory use. Preview overlays and the residual are intended to reveal gross failures; the residual is an internal-consistency measure, not ground-truth accuracy, so visually inspect plausible candidates.

### Folder batch

Choose **Plugins > Registration > Log-Ratio Registration Batch...** and select input/output folders plus a shared setup. Each stack is held in memory separately. Outputs retain the input subfolder layout and are named `<source>_registered.tif`. Every run writes `log_ratio_batch_report.csv` with status, elapsed time, diagnostics, errors, and the resolved recipe.

## Output

- **Interactive:** a new registered `ImagePlus`; the source remains open and unchanged.
- **Batch:** one `<source>_registered.tif` per successful input plus `log_ratio_batch_report.csv`.
- **Interpolation:** `NONE` preserves whole-pixel values exactly; `BILINEAR` and `BICUBIC` provide subpixel resampling.
- **Cropping:** when enabled, output is restricted to the field containing real source pixels in every frame.

## ImageJ macros

Automatic registration of a phase-contrast time series:

```ijm
run("Log-Ratio Registration...",
    "image_type=PHASE_CONTRAST motion_type=SUBPIXEL_RANDOM_WALK selection_mode=automatic");
```

An explicit no-dialog run:

```ijm
run("Log-Ratio Registration...",
    "image_type=DENSE_FLUORESCENCE motion_type=STEADY_DIRECTIONAL_DRIFT manual " +
    "channel=1 slice=0 estimator=log_ratio_fit preprocessing=NONE " +
    "pixel_selection=NONE mask_preprocessing=NONE reference=MULTILAG " +
    "lags=1,2,4,8,16 norm=HUBER support=ALL gradient=0.5 epsilon=1.0 " +
    "auto_max_shift iterations=25 max_samples=200000 min_valid=0.1 " +
    "threads=0 interpolation=NONE crop");
```

A folder batch:

```ijm
run("Log-Ratio Registration Batch...",
    "input=[D:/recordings] output=[D:/recordings corrected] recursive no_overwrite " +
    "image_type=DENSE_FLUORESCENCE motion_type=STEADY_DIRECTIONAL_DRIFT " +
    "selection_mode=automatic channel=1 slice=0 interpolation=NONE crop");
```

Important option groups:

| Option | Representative values |
|---|---|
| `selection_mode` | `automatic`, `recommended`, `manual` |
| `estimator` | `log_ratio_fit`, `area_correlation_newton`, `area_correlation` |
| `reference` | `CONSECUTIVE`, `MULTILAG`, `FIXED`, `ROLLING` |
| `preprocessing` | `NONE`, Gaussian smoothing, `MEDIAN_3X3`, photon-noise stabilization, mild sharpening |
| `pixel_selection` | `NONE`, `REMOVE_LEAST_INFORMATIVE`, other advanced strategies |
| `norm` | `LEAST_SQUARES`, `HUBER`, `TUKEY` |
| `support` | `ALL`, `GRADIENT`, `MUTUAL_NOISE_GRADIENT` |
| `interpolation` | `NONE`, `BILINEAR`, `BICUBIC` |

Interactive runs record a complete resolved manual option string, so replay does not reclassify the image when a later release changes the recommendation model.

## Java and scripting API

The public API neither shows a dialog nor modifies the input:

```groovy
import logratio.api.*

def parameters = LogRatioParameters.builder()
    .recommendation(ImageType.DENSE_FLUORESCENCE,
                    MotionType.STEADY_DIRECTIONAL_DRIFT)
    .automaticFilterSelection(true)
    .channel(1)
    .build()

def result = LogRatioRegistration.register(imp, parameters)
result.correctedImage().show()
```

The caller owns the returned corrected `ImagePlus` and should close it when finished.

## How It Works

For a candidate translation, the primary estimator forms a log-ratio field between the reference and moved frames. A spatially uniform field indicates that the two images differ mainly by a global multiplicative gain. The implementation fits this gain independently and minimizes the remaining spatial variation. This is related to the ratio-image-uniformity registration criterion introduced by Woods, Cherry, and Mazziotta (1992), while adding robust losses, pixel-support controls, multi-scale search, and microscopy-specific scheduling.

Pair estimates are connected across time with consecutive, fixed, rolling, or multi-lag references. Reconciliation finds one consistent trajectory; an outlier-repair stage can replace isolated implausible steps. The resulting transform is applied identically across channels and Z slices.

Preprocessing affects only a temporary scoring copy used to estimate motion. The final transform is always applied to the original full-resolution pixels. Optional spatial pixel removal uses a provisional pass to choose a mask and then refits on unfiltered intensities within that mask.

The alternative area-correlation estimator uses normalized cross-correlation on a multi-resolution pyramid with subpixel peak refinement. It is implemented locally and does not bundle or depend on TurboReg or StackReg.

## Validation and limitations

The included portable test suite covers estimator recovery, transforms, warping, scheduling, reconciliation, automatic selection, macro parsing, batch behaviour, and API isolation. Development also used controlled synthetic motion and held-out microscopy series; research datasets and machine-specific benchmark harnesses are intentionally not part of the public software repository.

This is a pre-1.0 research release. Registration quality depends on usable shared spatial structure between frames. Inspect corrected stacks, retain the recorded settings/provenance, and report reproducible failures. Automatic selection is deliberately conservative and may return the fixed recommendation without an additional measurement pass.

## Citing Log-Ratio Registration

If you use the plugin in published work, cite the software release:

> Malcolm, J. (2026). *Log-Ratio Registration* (Version 0.1.0) [Computer software]. https://github.com/Jay2owe/Log-Ratio-Registration

Machine-readable metadata is available in [`CITATION.cff`](CITATION.cff), and GitHub exposes it through **Cite this repository**. No Zenodo DOI or methods-paper DOI is claimed for this release.

Please also cite the upstream platform and the ratio-image registration method where relevant:

- Schindelin J. et al. (2012). Fiji: an open-source platform for biological-image analysis. *Nature Methods*, 9, 676–682. [doi:10.1038/nmeth.2019](https://doi.org/10.1038/nmeth.2019)
- Schneider C.A., Rasband W.S., Eliceiri K.W. (2012). NIH Image to ImageJ: 25 years of image analysis. *Nature Methods*, 9, 671–675. [doi:10.1038/nmeth.2089](https://doi.org/10.1038/nmeth.2089)
- Woods R.P., Cherry S.R., Mazziotta J.C. (1992). Rapid automated algorithm for aligning and reslicing PET images. *Journal of Computer Assisted Tomography*, 16, 620–633. [doi:10.1097/00004728-199207000-00024](https://doi.org/10.1097/00004728-199207000-00024)

## Building From Source

Requirements: a JDK 11 installation. Verify that both `java -version` and `javac -version` resolve to that JDK.

```sh
git clone https://github.com/Jay2owe/Log-Ratio-Registration.git
cd Log-Ratio-Registration
./mvnw clean verify -Denforcer.skip=true
```

On Windows Command Prompt or PowerShell, use `mvnw.cmd` instead of `./mvnw`.

The runtime JAR is `target/LogRatioRegistration-0.1.0.jar`. The project compiles Java 8-compatible bytecode for Fiji while using JDK 11 for the build.

## Contributing

Bug reports and pull requests are welcome through [GitHub Issues](https://github.com/Jay2owe/Log-Ratio-Registration/issues). See [`CONTRIBUTING.md`](CONTRIBUTING.md) before sharing logs or image data.

## Acknowledgements

Developed by Jamie Malcolm in the [Brancaccio Lab](https://www.ukdri.ac.uk/labs/brancaccio-lab) at the [UK Dementia Research Institute](https://ukdri.ac.uk/centres/imperial), Imperial College London.

This work was supported by the UK Dementia Research Institute, which receives its core funding from the UK Medical Research Council, the Alzheimer's Society, and Alzheimer's Research UK.

Built on the [Fiji](https://fiji.sc/) and [ImageJ](https://imagej.net/) ecosystem; we thank the SciJava community for the platform.

## License

BSD-3-Clause — see [`LICENSE`](LICENSE).
