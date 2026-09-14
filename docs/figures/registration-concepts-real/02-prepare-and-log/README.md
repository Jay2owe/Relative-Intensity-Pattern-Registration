# Prepare the estimation images and take logarithms

Claim: Filtering, the logarithm and the validity band act only on a copy, and the logarithm changes the intensity distribution rather than the picture.

- Grammar: process chain with distributions.
- Master: `fig/02-prepare-and-log.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

The untouched stack on the left is never written to; one copy of the brightfield plane leaves it and everything to the right happens to that copy. The chain is the raw plane, the three-by-three median the resolved recipe asks for, the base-2 logarithm of intensity plus one, and the same plane with an intensity band applied. Underneath: the intensity histogram before the logarithm and after it, the map of exactly which pixels the median filter moved and by how much, and the share of pixels the band keeps against the share it invalidates. The three tiles look alike on purpose - the logarithm does not change what the frame shows, it changes the distribution the estimator works in.

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
