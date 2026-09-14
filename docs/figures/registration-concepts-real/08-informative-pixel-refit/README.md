# Select informative pixels and refit

Claim: Scoring where intensity changes in two directions keeps the locations that can fix a position, and the refit then returns to the untouched intensities.

- Grammar: mask chain over a measured plane.
- Master: `fig/08-informative-pixel-refit.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

The plane a pilot registration has already aligned, then its two-directional structure score, bright only where intensity changes along both axes rather than along a single edge. The third tile greys out the pixels the gradient floor and the removal quantile discard; the fourth shows the same mask carried onto the second frame by the pilot trajectory - only 1.7 pixels of transport here, so the two masks sit almost on top of one another. The bars are the share of the frame each stage keeps. The final fit runs on the original unfiltered intensities - the score decides where to look, not what is fitted.

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
