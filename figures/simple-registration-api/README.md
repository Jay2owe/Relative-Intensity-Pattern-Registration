# Figures

- The simplest RIPR registration call needs only an input path; recipe, channel, and longitudinal mode are optional swaps.

## Swaps

- `recipe`: `landmarks` for phase/brightfield, `bright_dim` for emission, or `moving_cells` for a biological foreground.
- `channel`: one-based image channel (default `1`).
- `longitudinal`: `True` uses the whole recording (default); use `False` for automatic frame-to-frame selection or `moving_cells`.

## Sources

See sources.csv for source labels, copied files and SHA256 fingerprints.

## Caveats

- No additional caveats supplied.
