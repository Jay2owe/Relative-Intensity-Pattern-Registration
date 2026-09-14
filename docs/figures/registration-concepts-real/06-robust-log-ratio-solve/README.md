# Solve the log-ratio fit robustly

Claim: Most pixels agree about the displacement at full weight while the few large residuals are bounded, so local change cannot drag the fit.

- Grammar: weighted residual field with an influence gate.
- Master: `fig/06-robust-log-ratio-solve.png`.
- Producer: `python code/plot.py`.
- Exact plotted values: `data/der/figure_data.csv`.
- Source provenance: `data/sources.csv` and `data/sources.md`.
- Statistics: none reported; this figure states no inferential result.

The residual field the solver sees, then the weight it gives every one of those residuals: bright where the residual is small enough to count in full, dark where it has been bounded. The gate itself is drawn at the robust scale this pair produced, with the residual distribution underneath it and the knee marked where bounding begins. Right, the displacement each surviving pixel votes for, on a sampled grid, teal at full weight and red where the weight was cut; the arrow from the centre is the displacement they agreed on.

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
