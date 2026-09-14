# Resolve the measurement plane and the recipe

Claim: Of the four recorded channels only brightfield localises movement well enough to estimate on, and the declared image and motion classes select one cell of the fixed recipe matrix.

- Grammar: decision matrix with image evidence.
- Master: `fig/01-resolve-measurement-and-recipe.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

The four recorded channels of one timepoint enter as one hyperstack. The bar under each channel is how much correlation a one-pixel displacement costs it, drawn against the poor-localisability threshold at the bar's full width: brightfield is the best of the four and every channel still falls short of that threshold, which is why the recipe is declared rather than inferred. The declared image class picks a row of the fixed five-by-four recipe matrix and the declared motion class picks a column; fill colour is the robust norm, the dark dot marks a recipe that filters before estimating, the bar marks one that restricts which pixels count, and the red ring marks one that runs the second-pass pixel selection. The resolved cell and the brightfield plane leave together.

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
