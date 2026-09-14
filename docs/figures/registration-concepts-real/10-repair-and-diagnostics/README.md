# Repair unsupported frames and assemble diagnostics

Claim: The gain and residual traces are reported, never applied, and the step test marks which frame positions rest on a measurement rather than interpolation.

- Grammar: diagnostic trace stack.
- Master: `fig/10-repair-and-diagnostics.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

Top, the cumulative frame-wide brightness the estimator profiled out over five days: it is a diagnostic of the estimation channel and is never applied to the output pixels. Middle, how much the estimator's own images disagreed before the fitted movement and after it. Bottom, the frame-to-frame step of the trajectory against the outlier threshold, with the strip beneath showing what each frame's position finally rests on - teal for a direct measurement, red for a repaired one. In this recording nothing needed repairing: every one of the 1169 planned comparisons fitted, no step crosses the threshold, and the strip is unbroken teal. The three tokens repeat the same diagnostics compactly: the brightness left at the end, the share of log disagreement the fit removed, and the share of frames that needed no repair.

## Where the numbers come from

Every value drawn here was measured by the project's own Python engine, `ripr`, on
position 1424 of the Auto-Organotypic demonstration set: 240 timepoints, four channels
(Bioluminescence, BF, RFP, GFP), 512 by 512 pixels at 2.0 um, half-hourly over five
days on an Olympus LV200. Registration is estimated on brightfield, as the
Auto-Organotypic demonstration does, because estimating on a signal channel lets the cells
register to themselves.

`data/src/run_registration_1424.py` ran the whole-recording multi-lag log-ratio
registration; `data/src/prep_intermediates.py` re-entered the engine to capture the
per-step intermediates this figure draws. Both are copied here with the engine
modules they call. The recording is identified by SHA256 in
`data/src/recording_provenance.json` rather than copied.

## Caveat

The set is deliberately text-free, so no axis, tile or marker carries a label; this
README is where each element is named.

Measured drift in this recording is small: about 4.0 pixels horizontally and
2.2 vertically across the whole five days. Where a true-to-scale arrow would
therefore be too small to see, it is drawn at an exaggeration that the caption above
states and `data/der/figure_data.csv` records. No image, mask, surface, trace or fit
is exaggerated - only arrows.
