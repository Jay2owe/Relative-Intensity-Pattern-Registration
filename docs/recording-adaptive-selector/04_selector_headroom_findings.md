# Selector headroom findings

## Decision

`FIXED_POLICY`

The source-balanced category baseline measured 0.0056257960 px and the safe equivalence-aware oracle measured 0.0016059737 px. Absolute headroom was 0.0040198224 px and relative headroom was 71.45%. The frozen overall gate required both 0.005 px and 15%, so the absolute gate failed.

Only phase contrast passed its image-type headroom gate. The protocol did not allow an adaptive fit to proceed when the overall gate failed. `RecordingAdaptiveSelectorTraining fit` therefore performed zero adaptive fitting and emitted the strongest safe fixed image-type policy for reserved validation.

The analyzer class is `RecordingAdaptiveSelectorHeadroom`; its fixture tests pin source balancing, safety filtering, geometry compatibility, diagnostic accounting and the stop/go decision. Full scope values are in `library/recording_adaptive_selector_v1/headroom.csv`; source-group winner dispersion is in `oracle_winner_dispersion.csv`.

Input identities:

- protocol SHA-256: `0de3d2d53285af363fa16468334b50b02f779c91f2cd6afa3ee88fed567c6d6f`
- candidate manifest SHA-256: `2d482270049113410b25ced202f41f852afc0c4a50f4fd684bb20b748438bc3c`
- outcome matrix SHA-256: `5b1aca1222c2e8cd92653761a9e883dc6ca6ab793bafd8317399d15f8b60776c`
