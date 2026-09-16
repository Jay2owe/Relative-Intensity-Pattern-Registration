# Figures

- The simplest RIPR registration call needs only an input path; recipe, channel, and longitudinal mode are optional swaps on `register`.

## Swaps

- `recipe`: a starting category — `landmarks` for phase/brightfield, `bright_dim` for emission, or `moving_cells` for a biological foreground.
- `channel`: one-based image channel (default `1`).
- `longitudinal`: `True` uses the whole recording (default); use `False` for automatic frame-to-frame selection or `moving_cells`.
- In automatic mode, inspect `result.automatic_selection.recipe` for the concrete recipe ID chosen from that category.
- `register_file` remains available as a compatibility alias for older code.

## Sources

See sources.csv for source labels, copied files and SHA256 fingerprints.

## Caveats

- No additional caveats supplied.
