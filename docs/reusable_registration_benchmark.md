# Reusable registration benchmark

## Run

```bat
.\scripts\run-registration-benchmark.cmd -Images "C:\path\to\tiff-stacks" -Methods automatic,enhanced_correlation,fast4dreg_first -ImageType DENSE_FLUORESCENCE -MotionType INTERMITTENT_JUMPS -Output "C:\path\to\benchmark-output"
```

Use `longitudinal_accuracy` to include the separate maximum-accuracy route.

Preview the work without writing files:

```bat
.\scripts\run-registration-benchmark.cmd -Images "C:\path\to\tiff-stacks" -Methods all -Preview
```

List every accepted method name:

```bat
.\scripts\run-registration-benchmark.cmd -ListMethods
```

Each input must be one grayscale TIFF stack arranged as images × height × width. External Fiji
methods currently require square images. The folder may contain any number of stacks and subfolders.

## Outputs

| Output | Use |
|---|---|
| `RESULTS.md` | Concise image × method × error × time tables |
| `registered_tiffs/` | Full registered stacks for inspection |
| `montages/` | Synchronous scrolling comparison stacks |
| `results/summary.csv` | One row per method |
| `results/per_image.csv` | One row per image and method |
| `results/transforms.csv` | Every measured movement |
| `run.json` | Inputs, settings, completion state and audit record |

The reported error is movement remaining relative to registered image 1. It is a guide for real
videos without known truth; the TIFF montages remain the deciding evidence. No motion is added.

An exact completed command is safe to repeat: it reports the existing result. A changed or incomplete
run must use a new output folder, so results cannot be silently overwritten.

Continue a stopped matching run without repeating completed registration stages:

```bat
.\scripts\run-registration-benchmark.cmd -Images "C:\path\to\tiff-stacks" -Methods all -ImageType DENSE_FLUORESCENCE -MotionType INTERMITTENT_JUMPS -Output "C:\path\to\benchmark-output" -Resume
```

## Multichannel TIFF recordings

First find a 40-frame section with real movement shared by the channels:

```bat
python scripts\select_real_motion_windows.py --images "C:\path\to\multichannel-tiffs" --output "C:\path\to\benchmark\motion_selection" --window-frames 40
```

Then split those exact frames without changing their pixels. OME channel names are read when present;
plain ImageJ hyperstacks can be named explicitly:

```bat
python scripts\split_ome_channels.py --images "C:\path\to\multichannel-tiffs" --output "C:\path\to\benchmark\selected_inputs" --window-table "C:\path\to\benchmark\motion_selection\motion_windows.csv" --channel-names "Per2,SynRCamp,SynGABASnFR,MF"
```

Run the command above independently on each channel folder, using the matching `-ImageType`.
After a run, package every scrolling montage with its panel layout and source hashes:

```bat
python scripts\audit_registration_montages.py --output "C:\short\completed-run"
```

Combine completed channel runs into the requested channel × method × error × time table:

```bat
python scripts\combine_channel_benchmarks.py --run "Bioluminescence=C:\run\bio" --run "BF=C:\run\bf" --output "C:\run\combined"
```
