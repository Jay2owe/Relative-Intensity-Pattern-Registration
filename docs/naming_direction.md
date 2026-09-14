# Naming direction

## Approved positioning statement for the rotation-capable release

> Image-type-configurable, intensity-robust rigid registration for fluorescence, brightfield, phase-contrast, differential interference contrast (DIC), and fixed-marker imaging.

## Naming requirements

The eventual name should foreground:

- tolerance of global intensity changes during matching;
- rigid registration, covering horizontal and vertical translation plus planned rotation;
- configurations for fluorescence, brightfield, phase-contrast, differential interference contrast (DIC), and fixed-marker imaging.

It should not imply that:

- every run uses the log-ratio estimator;
- both estimators are combined in one fit;
- output intensities are normalised or corrected;
- the current selector learns the image type from the recording;
- deformation or axial movement is corrected;
- rotation is already implemented before that capability has been completed and validated.

## Status

**Relative-Intensity Pattern Registration** has been selected to replace **Log-Ratio Registration**. The previous name no longer described the complete workflow because normalised area correlation is an alternative pairwise estimator. The positioning statement above is prospective until rotation has been completed and validated.

## Chosen name

**Relative-Intensity Pattern Registration**

This is the current leading candidate because it describes the principle shared by both pairwise estimators. They retain local intensity information while discounting a frame-wide intensity difference, then search for the transformation under which the relative spatial intensity patterns agree most closely.

The name does not imply that output intensities are normalised or that every run uses the log-ratio estimator.
