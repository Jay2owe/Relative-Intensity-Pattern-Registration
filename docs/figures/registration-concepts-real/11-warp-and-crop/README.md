# Warp the untouched stack and crop the common field

Claim: One transform per timepoint is applied identically to every channel, and only the field that holds real pixels at every timepoint is kept.

- Grammar: channel-by-time grid with a crop.
- Master: `fig/11-warp-and-crop.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

The untouched hyperstack on the left, four channels down and first, middle and last timepoint across. The same grid in the middle after each timepoint's transform has been applied identically to every channel, with the grey edge each displacement leaves behind. The large tile is one of those warped frames with the common valid field marked: the rectangle that holds real source pixels at every timepoint. That rectangle sits close to the frame edge because the drift is small - four columns are lost on the right and one row top and bottom, leaving 508 by 510 of 512 by 512. The two small tiles are the check: a 64-pixel window on the most textured part of the brightfield frame, first timepoint in cyan against last in red, before the transform and after it.

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
