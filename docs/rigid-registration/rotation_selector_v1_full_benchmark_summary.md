# Automatic rotation selector v1: full benchmark summary

This is the consolidated final summary for all five supported image types. It uses the last
applicable untouched validation run for Brightfield/DIC, Dense fluorescence, Fiducial/static, and
Sparse/low-light fluorescence, plus the newer four-series Phase validation that replaced the failed
earlier Phase branch. Every case contains 48 frames. Each source contributes clean rotation,
gain-faded rotation, intermittent jumps, steady drift, curved drift, random walk, and zero-rotation
controls for a total of nine cases per source.

## Accuracy and runtime

Warp error is the displacement remaining at the central half of the image after registration. Angle
columns use clean-condition cases. Runtime is mean end-to-end registration time per 48-frame case.

| Image type | Independent sources | Cases | Mean median warp (px) | Mean p90 warp (px) | Worst warp (px) | Clean mean p90 angle (deg) | Clean worst angle (deg) | Mean time/case (s) | Zero-rotation degradation (px) | Failed cases | Result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Brightfield / DIC | 1 | 9 | 0.01959 | 0.03100 | 0.10030 | 0.04192 | 0.12819 | 6.27 | +0.000523 | 0 | Pass |
| Dense fluorescence | 1 | 9 | 0.01227 | 0.01891 | 0.05338 | 0.00292 | 0.00682 | 13.28 | -0.000323 | 0 | Pass |
| Fiducial / static | 1 | 9 | 0.01367 | 0.01866 | 0.03644 | 0.02533 | 0.05610 | 2.41 | +0.000066 | 0 | Pass |
| Phase contrast | 4 | 36 | 0.02880 | 0.14453 | 9.90441 | 0.02719 | 0.10451 | 2.02 | +0.000003 | 0 | Pass |
| Sparse / low-light fluorescence | 1 | 9 | 0.04886 | 0.07410 | 0.15548 | 0.07532 | 0.15723 | 1.52 | 0.000000 | 0 | Pass |
| **Total** | **8 source runs (8 independent series)** | **72** | — | — | — | — | — | — | — | **0** | **Pass** |

The Phase worst warp comes from an inherited automatic-translation failure on the P. putida steady
drift case. Its translation-only control was worse (10.26894 px), while the selected rotation kept
clean angle error below 0.074 degrees. Six Phase repaired frames are from that same translation
failure; rotation added no repairs or issue recordings relative to its control. The Phase selector
therefore passed its predeclared relative safety gate, but this remains a translation-selector
failure worth addressing separately.

## What Automatic selected

`T` is the existing translation selector. `R` is the new independent rotation selector. After R
proposes an accepted angle, T refits `dx/dy` at that fixed angle; a rejected proposal retains the
exact original T result.

| Image type | Translation choice observed in the benchmark | Rotation choice | Angle proposal and acceptance gate |
| --- | --- | --- | --- |
| Brightfield / DIC | Area correlation; full intensity band; Gaussian 0.7 | Existing Brightfield category recipe: area correlation, full band, no filter or median 3x3 according to motion | Translation-seeded; residual gain >= 0.001 |
| Dense fluorescence | Log-ratio; Automatic varied all/gradient/mutual support and lower-intensity exclusion from the recording evidence | Mutual-noise-gradient support; exclude top 10%; Gaussian 0.7; exclude least-informative 25% | Translation-seeded; residual gain >= 0.002 |
| Fiducial / static | Area correlation; full band; Gaussian 0.7 | All pixels; exclude top 10%; Gaussian 0.7; no spatial mask | Translation-seeded; residual gain >= 0.005 |
| Phase contrast | Log-ratio; Automatic varied support and Gaussian 0/0.7/1.0 by motion evidence | If provisional step p90 <= 2 px: all pixels, exclude top 25%, Gaussian 0.7, exclude least-informative 25%. If > 2 px: all pixels, exclude top 25%, no filter or mask | Local translation-seeded proposal for <=2 px; best of local and global proposals for >2 px; residual gain >= 0.010 or 0.005 respectively |
| Sparse / low-light fluorescence | Log-ratio; gradient support with band/filter/mask selected from recording evidence | All pixels; exclude bottom 25%; Gaussian 1.0; exclude least-informative 25% | Translation-seeded; residual gain >= 0.010 |

## Validation provenance

- Brightfield, Dense, Fiducial, and Sparse: `r38_automatic_rotation_selector_validation`, 36 final
  applicable cases. The nine Phase rows from this run are superseded and excluded here.
- Phase: `r42_phase_v1_validation` and `r43_phase_v1_final_locked`, 36 cases across Lysobacter,
  P. putida, P. veronii, and Rahnella `_05` fields.
- Phase validation protocol, thresholds, hashes, and the final locked outcome are recorded in
  `rotation_selector_v1_validation_freeze.md`.
