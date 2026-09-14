# Separate global gain from movement

Claim: One frame-wide number absorbs the brightness change between two frames, and what remains is the spatial disagreement that locates the movement.

- Grammar: residual maps with a scalar offset.
- Master: `fig/05-separate-gain-from-movement.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

The two real frames of the chosen comparison, then their log difference at zero displacement and the same difference once the median frame-wide offset - the one number the orange marker stands for - has been subtracted. The right pair repeats that at the fitted displacement: the field flattens because the frames now overlap, not because anything was brightened. Far right, a zoomed crop with the first frame in cyan and the second in red, and the measured displacement drawn at six times its own size so a movement of a few pixels is visible at all.

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
