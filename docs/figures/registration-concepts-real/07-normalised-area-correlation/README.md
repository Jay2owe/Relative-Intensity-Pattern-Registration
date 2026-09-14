# Alternatively fit normalised area correlation

Claim: The alternative estimator removes each window's own level and contrast, then takes a subpixel Newton step to the peak of the correlation surface.

- Grammar: similarity surface with a refinement step.
- Master: `fig/07-normalised-area-correlation.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

The two comparison windows as measured, drawn on one shared scale so any difference in level or contrast between them would show, then the same windows with each one's mean removed and its energy divided out. For this pair the two differ in level by well under one percent, so normalisation changes little here - it earns its place on recordings where the lamp or the exposure moves. The field is the real zero-mean normalised correlation at every displacement on a quarter-pixel grid, brightest at the best match, with the integer starting position hollow and the orange arrow the Newton step from it. The slice on the right is one line through that peak: the dashed grey mark is where an integer search would have stopped and the orange mark is where the subpixel step ended.

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
