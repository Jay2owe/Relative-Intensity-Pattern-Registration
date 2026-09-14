# Single-channel fluorescence rescue: Round 2

Superseded by `single_channel_fluorescence_rescue_r03.md`; this file records the rejected Round-2
route and its original guide values.

The Automatic fixed recipe now uses Enhanced Correlation Coefficient for declared dense and low-light
fluorescence. Ordinary motion compares adjacent frames. Declared intermittent jumps compare every
frame with frame 1 after removing glow broader than one eighth of the field, then repair only steps
beyond eight median absolute deviations. The rule uses only the declared image and motion types; it
does not inspect the recording to choose a recipe and never reads another channel.

| Route | Problem median | Across-stack 90th percentile | Worst case | Control median | Control worst | Time per 40-frame stack |
|---|---:|---:|---:|---:|---:|---:|
| Previous-frame Enhanced Correlation Coefficient | 0.40 px | 5.21 px | 23.91 px | 1.62 px | 2.53 px | 5.01 s |
| Automatic fixed fluorescence recipe | 0 px | 1.69 px | 15.53 px | 1.62 px | 2.53 px | 4.61 s |

The frozen evaluation used 24 real one-channel stacks and 40 frames per stack. All completed with
finite transforms and zero cross-channel reads. Affine brightness changes passed. At 0.75 and 1.50
times size, restored transforms remained stable and retained the gain, but the inherited absolute
alignment limits at those sizes did not pass. The Image-and-motion preset was not changed.

The immutable evidence and full-resolution scrolling TIFF grids are stored in:

`C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\Auto-Organotypic\test set\single_channel_pulsing_lowlight_registration_tuning`
