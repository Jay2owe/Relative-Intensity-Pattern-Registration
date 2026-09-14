# Stage 02 recording-evidence findings

## Frozen contract

`recording_evidence_v2` contains 48 fields: 17 raw-image, 10 universal-pilot motion, 5 support/change, and 16 declared-context fields. `AutomaticRegistrationSelector.measure` is the only production/training calculation. `AutomaticFilterFeatureTable` emits the same names and order.

Image fields use the median of measurements from the first, middle and final raw guide frames. `R = max(1e-9, p99 - p1)` below. Pilot motion uses cumulative translation estimates in pixels; no injected transform or candidate result enters it.

| # | Field | Frozen definition | Unit / expected range | Role |
|---:|---|---|---|---|
| 1 | median position | `(p50-p1)/R` | ratio, normally 0..1 | field sparsity |
| 2 | 99-to-95 percentile tail | `(p99-p95)/R` | ratio, 0..1 | bright tail |
| 3 | 95-to-median range | `(p95-p50)/R` | ratio, 0..1 | intensity spread |
| 4 | maximum tail | `(max-p99)/R` | ratio, >=0 | extreme pixels |
| 5 | dark-pixel share | share `<= p1+0.05R` | fraction, 0..1 | background dominance |
| 6 | bright-pixel share | share `>= p1+0.95R` | fraction, 0..1 | bright support |
| 7 | skewness | third central moment / variance^1.5 | dimensionless | distribution shape |
| 8 | excess kurtosis | fourth central moment / variance^2 minus 3 | dimensionless | impulses/tails |
| 9 | median gradient | median absolute 4-neighbour difference / `R` | normalized gradient | texture |
| 10 | 90th-percentile gradient | q90 absolute 4-neighbour difference / `R` | normalized gradient | strong texture |
| 11 | median impulse residual | median `abs(raw-median3(raw))/R` | normalized intensity | impulse noise |
| 12 | 90th-percentile impulse residual | q90 of the same residual | normalized intensity | impulse tail |
| 13 | median blur residual | median `abs(raw-binomial5(raw))/R` | normalized intensity | high frequency |
| 14 | 90th-percentile blur residual | q90 of the same residual | normalized intensity | high-frequency tail |
| 15 | histogram entropy | 32-bin entropy / 5 bits over clipped robust range | 0..1 | information spread |
| 16 | horizontal neighbour correlation | Pearson correlation of adjacent finite pixels | -1..1 | repeated/smooth texture |
| 17 | vertical neighbour correlation | same, vertical neighbours | -1..1 | repeated/smooth texture |
| 18 | median step | median `hypot(delta dx,delta dy)` | px/frame | pilot motion |
| 19 | 90th-percentile step | q90 step magnitude | px/frame | pilot motion tail |
| 20 | maximum step | maximum step magnitude | px/frame | pilot jump evidence |
| 21 | jump ratio | maximum step / `(median step+1e-9)` | ratio, >=0 | discontinuity |
| 22 | path efficiency | final displacement / `(path length+1e-9)` | 0..1 | directional persistence |
| 23 | linearity residual | RMS x/y residual from linear time fit / `(p90 reach+1e-9)` | ratio, >=0 | nonlinearity |
| 24 | median acceleration | median magnitude of consecutive step-vector differences | px/frame2 | motion roughness |
| 25 | 90th-percentile acceleration | q90 acceleration magnitude | px/frame2 | roughness tail |
| 26 | maximum acceleration | maximum acceleration magnitude | px/frame2 | abrupt change |
| 27 | 90th-percentile reach | q90 distance from first cumulative position | px | movement extent |
| 28 | sparsity score | first-frame `(p50-p1)/(p99-p1)` | ratio, normally 0..1 | support context |
| 29 | moving-tail ratio | q99/q95 scaled absolute log residual after gain-invariant first/second-frame alignment | ratio, >=0 | local change |
| 30 | temporal outlier fraction | share above Tukey threshold in that residual | fraction, 0..1 | local change |
| 31 | gradient-admitted fraction | first-frame share above `0.5 * median log-gradient` | fraction, 0..1 | candidate admission |
| 32 | mutual-noise-admitted fraction | first-frame share with raw gradient above `0.5 * noise sigma` | fraction, 0..1 | candidate admission |
| 33-37 | declared image type | one-hot in enum order | 0 or 1 | hard pool + predictor |
| 38-41 | declared motion type | one-hot in enum order | 0 or 1 | predictor only |
| 42-44 | declared base robust norm | one-hot in enum order | 0 or 1 | declared context |
| 45-48 | declared base reference | one-hot in enum order | 0 or 1 | declared context |

All raw-image formulas use finite pixels only. A required non-finite result invalidates the complete evidence row. Pilot transforms may not be null or contain non-finite dx, dy or angle.

## Corrections made

- Sparse and explicit-band branches now calculate the two temporal fields instead of emitting intentional `NaN` values. The information-rule decision itself is unchanged.
- Null/non-finite provisional transforms no longer masquerade as identity movement.
- Evidence has a version, `valid` flag and stable reason. Fewer than two frames, dimensions below 16 by 16, absent finite dynamic range, structural mismatch and non-finite required fields are explicit invalid states.
- Standardization rejects non-finite fields; it no longer substitutes a training mean.
- The out-of-distribution calculation is explicit: any continuous `abs(z)>8`, continuous RMS z above 3, or a changed zero-scale field causes fallback. Training means/scales are parameters fitted only inside development folds.
- The production provisional pass is now universal: log-ratio, Huber, multilag `1,2,4,8,16`, all pixels, full band, no preprocessing/mask/rotation, scale 1, fixed 30 px bound, epsilon 1, 25 iterations, 200,000 samples, valid fraction 0.10 and no outlier smoothing.

## Determinism and sensitivity

- Repeated deterministic measurements are bit-exact.
- The 17 normalized raw-image fields are invariant, within `1e-6`, to a positive global gain and offset. Epsilon-based log residual and raw-noise admission fields are intentionally allowed to respond to offset or bit-depth scaling.
- Byte, unsigned-short and float inputs with the same numeric pixels share the float `FrameSource` calculation and therefore the same evidence.
- Resampling/scale may change gradient, high-frequency and pixel-motion fields; image dimensions are not themselves a predictor.
- Frame count affects motion sequence summaries. Raw-image summaries always sample first, middle and final frames, including duplicate indices for a two-frame input.

## Truth-free development audit

`library/recording_adaptive_selector_v1/evidence/development_features.csv` contains 80 historical development cases from 20 source acquisitions and four declared motion classes. Each row was recomputed through the frozen universal pilot; no outcome file was joined.

- valid rows: 80/80
- missing/non-finite model fields: 0/3,840
- pilot wall time: 201.382225300 s
- feature-extraction wall time: 4.786799000 s
- evidence SHA-256: `b6e1561cd70601c113484b51a1aee19d398f1b58dfe4cf66e6309d37f7ab1b924`

Ten continuous-field pairs had absolute Pearson correlation at least 0.95. The strongest were q90 impulse residual versus median blur residual (`r=0.9938`), median gradient versus q90 impulse residual (`0.9933`), median gradient versus median blur residual (`0.9886`), median impulse versus q90 impulse residual (`0.9884`), median position versus sparsity score (`0.9687`), median versus q90 pilot step (`0.9653`), and maximum step versus maximum acceleration (`0.9563`). They remain in v2 because removal based on later recipe outcomes is forbidden and ridge regularization handles collinearity.

The legacy v1 envelope flags 41/80 corrected rows as out of distribution. This is expected after changing the pilot and removing imputation; it proves that the old centring/scales cannot be reused. Stage 05 must generate the v2 envelope inside group folds and for the final model.

The five new development acquisitions were not used to redesign fields. They enter the frozen Stage 03 input matrix, keeping the feature contract fixed before their candidate outcomes exist.

## Verification

`mvn "-Dtest=logratio.api.AutomaticFilterSelectorTest,logratio.api.AutomaticRegistrationSelectorTest,logratio.core.AutomaticInformationSelectorTest" test` passed 29 tests. Tests pin feature order/count, exact repeats, gain/offset behaviour, universal-pilot settings, invalid constants/small inputs/non-finite transforms, OOD bounds, no imputation and a forbidden-column audit of the exporter.
