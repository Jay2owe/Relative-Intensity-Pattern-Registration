# Relative-Intensity Pattern Registration (RIPR)

This Python package runs the same registration operation as the Java Fiji/ImageJ plugin. Java is the
default engine because it is much faster; a Python/NumPy/SciPy engine remains available as a fallback.
It does not launch ImageJ. On PyPI it is `Relative-Intensity-Pattern-Registration`; the import package
and terminal command are both `ripr`.

## Install

```powershell
python -m pip install Relative-Intensity-Pattern-Registration
```

The install name is the project's full name; everything you type afterwards is `ripr`:

```python
import ripr
```

On Windows, install into a virtual environment whose path is short. OpenCV ships a DLL whose
full path can exceed the 260-character limit from a deeply nested folder, and it fails at
import with `DLL load failed while importing cv2: The filename or extension is too long`,
which names cv2 rather than the real cause. A shorter path, or long paths enabled in Windows,
fixes it.

### From a checkout

From this folder:

```powershell
python -m pip install -e .
```

For development and tests:

```powershell
python -m pip install -e ".[test]"
pytest
```

## Quick start

For a TIFF, only the input filename is required. The output is written beside it as
`recording_registered.tif`:

```python
import ripr

ripr.register_file("recording.tif")
```

For a NumPy array, only the array is required when it is shaped `T, Y, X`:

```python
result = ripr.register(stack)
corrected = result.corrected
```

The normal interface has three choices:

```python
result = ripr.register(
    stack,
    recipe="landmarks",  # or "bright_dim" / "moving_cells"
    channel=1,           # one-based
    longitudinal=True,
)
```

They default to the benchmark-backed Landmarks recipe for phase contrast, channel 1, and
whole-recording longitudinal processing. Bright/dim selects the accepted fluorescence or
bioluminescence route. Moving cells is the separate biological-foreground recipe and is used with
`longitudinal=False`. Java is preferred for execution.

## Expert recording settings

```python
import tifffile
from ripr import LogRatioParameters, register

stack = tifffile.imread("recording.tif")  # shape T, Y, X
parameters = LogRatioParameters.recommended(
    image_type="phase_contrast",
    motion_type="subpixel_random_walk",
)
result = register(stack, parameters, axes="TYX")  # axes is optional for a T,Y,X array

tifffile.imwrite("recording_registered.tif", result.corrected)
print([(t.dx, t.dy, t.theta) for t in result.transforms])  # theta is radians
print(result.registration.log2_gain)       # bleaching/lamp-drift trace
print(result.median_residual_before, result.median_residual_after)
```

Use `ripr.rank_channels(array, axes="TCZYX")` to rank estimation channels by localisability before
a run. Values below `ripr.WARN_BELOW` carry the same poor-localisability warning threshold as the
ImageJ plugin.

The input is never modified. For hyperstacks, pass axes explicitly, for example `TCZYX`. `channel`,
`slice`, and `reference_frame` in `LogRatioParameters` are one-based like ImageJ; `slice=0` maximum-
projects Z for movement estimation. The estimated transform is applied unchanged to every channel and
Z plane.

## Run the estimation in Java, at Java speed

The registration in this package and the registration in the Fiji plugin are the same operation, and
on a preset recipe they produce the same transforms bit for bit. They do not take the same amount of
time. Java aligns frame pairs across a thread pool, which is the one place this problem parallelises
well, and the NumPy engine here runs them one after another.

Java is used automatically when a Java runtime and the plugin jar are both present:

```python
result = register(stack, parameters, axes="TYX")
```

Measured on one 40-frame 448x768 recording, 16 cores, identical settings and identical output:

| Engine | Time |
|---|---|
| `backend="java"` | 16.6 s |
| `backend="python"` | over 900 s |

The optional `backend` override takes:

- omitted — prefer Java; warn clearly before falling back to Python
- `"java"` — require Java and raise if it cannot run
- `"python"` — deliberately use NumPy and do not warn about Java
- `"auto"` — prefer Java; warn clearly before falling back to Python

Set `RIPR_BACKEND` to `java`, `auto`, or `python` to choose the policy for a whole process. An explicit
`java` setting is strict. A fallback warning includes the reason Java could not be used and how to fix
it; it is never silent.

Only transforms cross the process boundary; warping happens here either way, so the choice changes
how long a run takes and not what it gives back. Two consequences worth knowing:

- `result.registration.pairs` is empty under the Java backend. Per-pair fits are not carried across,
  because moving them costs more than a caller asking for a fast path wants to spend. Everything
  reported per frame is present and is the Java engine's own value.
- The Java runner rebuilds the recipe from the image type, motion type and selection mode you name.
  That is exact for a preset recipe and wrong for a customised one, so a recipe that differs from its
  preset in any other field stays on the Python engine. `ripr.registration.java_incompatibilities()`
  lists what is blocking it; `backend="java"` raises rather than silently running something else.
The backend finds its pieces from the environment: `RIPR_JAVA` or `JAVA_HOME` or `java` on `PATH`
for the runtime, and `RIPR_JAR` or a `jars/` directory beside the package or `RIPR_FIJI` for the
plugin. `ripr.java_backend.available()` reports whether it can run at all.

## Register a TIFF or folder

```python
from ripr import register_file, register_batch

register_file("recording.ome.tif")
register_batch("input_folder", "output_folder")
```

Or from a shell:

```powershell
ripr recording.tif

ripr fluorescence.tif --recipe bright_dim --channel 2

ripr rotating_recording.tif --no-longitudinal --fit-rotation --max-rotation-degrees 10

ripr remounted_recording.tif remounted_registered.tif `
  --no-longitudinal --rotation-mode known_events --rotation-events 25,51 `
  --rotation-event-window 3 --max-rotation-degrees 10

ripr input_folder output_folder --recursive
```

Folder batches create `log_ratio_batch_report.csv`, skip existing outputs unless `--overwrite` is set,
and continue after a damaged or incompatible input. The existing report columns are followed by the
resolved rotation mode, one-based event list, window and compact event diagnostics.

## Java-to-Python interface map

| Java plugin/API | Python package |
|---|---|
| `RelativeIntensityPatternRegistration.register(ImagePlus, ...)` | `ripr.register(ndarray, ..., axes=...)` |
| `RelativeIntensityPatternRegistration.estimate(...)` | `ripr.estimate(...)` |
| `RelativeIntensityPatternParameters` | `ripr.LogRatioParameters` |
| `RelativeIntensityPatternRecommendations.forTypes(...)` | `ripr.recommendation(...)` |
| `StackWarper.apply(...)` | `ripr.apply_transforms(...)` |
| batch plugin | `ripr.register_batch(...)` |
| TIFF input/output | `ripr.register_file(...)` |

Set `fit_rotation=True` and `max_rotation_degrees=<bound>` on `LogRatioParameters` to estimate bounded
in-plane rotation as well as translation. The public bound is in degrees; returned `Transform.theta`
values are radians. Both log-ratio and area-correlation estimators support the rigid search. Automatic is
a fixed declared image-and-motion rule and never inspects the recording to choose a recipe. Dense and
low-light fluorescence use `single_channel_emission_max_accuracy_r04_a208`: dense fluorescence
uses median-filtered previous-image Enhanced Correlation Coefficient, while sparse/low-light
fluorescence or bioluminescence uses the tuned log-ratio preset. Other image types retain
`recording_adaptive_selector_v1_user_approved_fixed_policy_v1`.

For long recordings with slow drift, gentle shake, isolated stage movements and major light changes,
choose the separate whole-recording route:

```python
from ripr import ImageType, LogRatioParameters, SelectionMode

parameters = LogRatioParameters(
    image_type=ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
    selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
    channel=1,
)
```

This route uses bright/dim same-channel references for fluorescence or bioluminescence and edge/dark
landmarks for phase contrast or brightfield/DIC. It suppresses returning pulse-linked excursions while
retaining persistent and near-dark final jumps. It never reads another channel. Use Automatic instead
for repeated oscillation or continuous rotation.

For recordings that rotate only when they are removed and replaced, use the experimental event mode:

```python
from ripr import LogRatioParameters, RotationMode

parameters = LogRatioParameters.manual(
    rotation_mode=RotationMode.KNOWN_EVENTS,
    rotation_event_frames=(25, 51),  # one-based first frames after remounting
    rotation_event_window=3,
    max_rotation_degrees=10,
)
```

Each boundary uses all available before/after cross-pairs in the window and needs at least three usable
rigid fits. One robust angular jump is held exactly until the next event while translation remains free.
The final composed transforms are applied to the original pixels once. Event diagnostics are available
as `result.registration.event_rotations`; they include the event frame, incremental and cumulative angle,
candidate/usable/inlier counts, circular spread, contributing ranges and status. Large disagreement is a
warning; insufficient support stops the run. This mode uses the log-ratio estimator, does not support a
rolling reference, and remains opt-in pending validation on independent real remount recordings.

`Interpolation.NONE` remains the default: pure translations are rounded to whole pixels and applied
through a bit-exact block copy. A non-zero rotation cannot use that path, so `NONE` uses nearest-neighbour
sampling; bilinear and Catmull-Rom bicubic interpolation are opt-in for smoother intensity images.
`Interpolation.FOURIER` uses padded Fourier shifts for sharp, band-limited interpolation and represents
rotation as three Fourier shears. It can ring near hard edges.
Cropping defaults to the field containing real pixels in every registered frame.

This is a standalone Python package, separate from the Java plugin. It has no Swing dialogs or ImageJ
macro recorder; its settings are exposed through the Python API and command-line interface. The Java
plugin and Python package can continue to be used independently.
