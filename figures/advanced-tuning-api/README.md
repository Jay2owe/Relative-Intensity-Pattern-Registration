# Figures

- An expert RIPR run starts from a benchmark recommendation, then replaces only the parameter fields that need tuning.

## Tuning

- `register` accepts a TIFF path; `output_path=...` chooses the destination, or omit it to write beside the input.
- A second path argument is accepted as a shorter positional form of `output_path`.
- `channel` is one-based, matching Fiji/ImageJ.
- `image_type` and `motion_type` choose the benchmark starting assumptions.
- `recipe` is the starting category; automatic mode records the concrete selected recipe in the result provenance.
- `max_shift` limits the pixel search; `max_iterations` limits fitting work.
- `interpolation` controls output resampling. Custom fields use `backend="python"`; omit it to try Java and receive a fallback warning.

## Sources

See sources.csv for source labels, copied files and SHA256 fingerprints.

## Caveats

- No additional caveats supplied.
