# Recording-adaptive selector protocol v1

## Frozen identity

| Field | Frozen value |
|---|---|
| `protocol_version` | `recording_adaptive_selector_protocol_v1` |
| `freeze_date` | 2026-08-26 |
| `run_id` | `recording_adaptive_selector_v1` |
| `artifact_root` | `library/recording_adaptive_selector_v1` |
| Git baseline | `1ad87600d9270846076b505613627e1b948793b7` |
| Rotation baseline manifest | `docs/recording-adaptive-selector/rotation_baseline_manifest.csv` |
| Rotation baseline SHA-256 | `c663230a02b87c526e69ffff6039130ddc995f465b38c79446712d10ae21f308` |
| Candidate manifest | `docs/recording-adaptive-selector/candidate_manifest.csv` |
| Candidate manifest SHA-256 | `2d482270049113410b25ced202f41f852afc0c4a50f4fd684bb20b748438bc3c` |
| Source split manifest | `docs/recording-adaptive-selector/source_split_manifest.csv` |
| Source split manifest SHA-256 | `abedd2db6bb95a0ebc937465580aa6a85dc54fa236760fabfaefd2628b665136` |
| Final evidence rule | Open once after the model and all thresholds are frozen; do not retune afterward. |

The rotation manifest, rather than the Git commit alone, is the authoritative baseline because the working tree contains the separately reviewed event-anchored rotation implementation. Continuous rotation is the validated production route. Known-event rotation remains experimental and opt-in; it is not an adaptive recipe dimension.

## Selector contract

- Target: predicted `gain_over_category`, measured against the complete category recommendation.
- Structure: one translation-recipe model is used for rotation OFF, CONTINUOUS and KNOWN_EVENTS. The frozen, separate rotation selector runs after translation-recipe resolution when rotation is requested.
- Category role: hard candidate eligibility and one-hot model input within the eligible pool. It is context, never movement truth.
- Declared motion-type role: model input only. It does not admit an otherwise ineligible recipe.
- Low-confidence, invalid-evidence, unsupported and out-of-distribution result: retain the complete category recommendation exactly.
- Truth, injected paths, candidate outcomes, winner identities, internal residuals and post-warp measurements are forbidden from the production feature table.
- The selected result is an ordinary explicit `RegistrationRecipe`. Manual and explicitly replayed recipes never invoke this selector.

The frozen manifest contains 128 distinct executable recipes: 96 log-ratio, 16 area-correlation and 16 Newton area-correlation recipes. The initial manifest is evaluated for every category; category-specific safety and pruning are learned only from development folds. Rotation is not crossed into this manifest because it is a later, already frozen decision.

## Universal provisional pass

Every recording is measured with the same truth-free pilot:

| Setting | Value |
|---|---|
| Estimator | `LOG_RATIO_FIT` |
| Robust norm | `HUBER` |
| Reference | `MULTILAG` |
| Lags | `1,2,4,8,16` |
| Pixel support | all pixels |
| Intensity band | full range |
| Preprocessing | none |
| Pixel mask | none |
| Rotation | off |
| Estimation scale | `1.0` |
| Automatic maximum-shift survey | false |
| Maximum shift | `30 px` |
| Log epsilon | `1.0` |
| Optimizer limit | 25 iterations |
| Sampling limit | 200,000 pixels |
| Minimum valid fraction | `0.10` |
| Outlier smoothing threshold | disabled (`0`) |

The caller may choose the thread count, but it must not change the numeric result. Guide frames remain unmodified. The pilot is evidence only and is discarded before the final recipe runs on the original guide frames.

## Feature contract

`feature_contract_version=recording_evidence_v2`.

Allowed families are raw intensity/structure, provisional translation motion and reliability, sparsity/change, candidate-support admission, and declared image/motion/norm/reference context. Stage 02 may correct or remove individual fields inside those families and must freeze their order, formula and units. It may not add another family after outcomes are opened.

Evidence is invalid when any of these applies:

- fewer than two guide frames or either dimension is below 16 pixels;
- no finite positive raw-image dynamic range;
- a required raw field or provisional transform is non-finite;
- the pilot has a structural failure;
- required candidate-support fractions are undefined.

Invalid evidence is not mean-imputed. After training, valid evidence is out of distribution when any continuous feature has `abs(z) > 8`, or the root-mean-square z-score over continuous features exceeds 3. Fold-derived training means and scales define z; categorical fields are excluded. A zero-scale field must equal its training mean exactly. These thresholds are frozen now; only the training statistics are fitted later.

## Model families and training grid

Only these model families may compete:

1. `IMAGE_TYPE_RULE`: a category-specific fixed recipe, including the complete category fallback.
2. `LINEAR_GAIN`: ridge-regularized gain prediction with ridge values `{0.1, 1, 10, 100, 1000}`.

All valid v2 fields enter the linear family in frozen order. Candidate pruning, standardization, coefficients and confidence thresholds are refitted inside each source-group training fold. Confidence thresholds are `{0, 0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1}` pixels of predicted gain. No outcome-driven feature selection or new model family may be introduced after Stage 03.

## Controlled evidence generation

The source manifest freezes 25 development, 10 validation and 10 final independent acquisitions, balanced at 5/2/2 per image type. All derivatives of an acquisition stay in its partition. Validation and final files are exact-file unused by the earlier selector manifests. Some partitions necessarily share public repository or laboratory families; this limits population-level independence but not acquisition identity and is reported in every claim.

Each source contributes a central crop no larger than 256 by 256 pixels and 24 synthetic frames. Every frame is warped directly from the declared raw source frame with the verified degree-7 fixture; transforms are not accumulated recursively. Eleven controlled cases are generated per source:

- four exact v3 translation paths with constant intensity;
- the same four paths with the frozen rigid angular paths;
- subpixel random-walk translation alone and with rotation, both with gain fading from 1.0 to 0.5;
- one rigid zero-rotation control.

The translation paths are the exact normalized v3 random-walk, steady-drift, curved and abrupt-jump definitions. Rigid angles are bounded by 10 degrees: random walk up to 2.5 degrees, steady 0 to 8 degrees, curved sinusoid up to 4 degrees and jumps `0,+5,-3` degrees. Source reading must be lossless; compressed TIFF input may use `tifffile` before writing ordinary benchmark fixtures.

The feature table contains exactly one truth-free row per source-case. The physically separate outcome table contains one row per source-case-candidate. Join keys, never row position, link them.

## Metrics and balancing

Primary accuracy is the source-balanced median central-50%-frame general warping index, in pixels. Case results are first summarized within each independent source group; groups have equal weight. Secondary outputs are central and full-sequence p90 and worst error, angular error, centre-translation error, refusal/non-convergence/bound-hit counts, repaired or unsupported frames, crop loss and complete-workflow runtime.

The strongest non-adaptive baseline is chosen from:

- the complete category recommendation;
- the currently shipped automatic image-type rule;
- a category-specific fixed recipe selected only inside each development training fold.

The all-development fixed policy is frozen only after grouped evaluation and is then carried unchanged to validation and final evidence.

## Safety, equivalence and headroom stop gate

A candidate is unsafe if, relative to the strongest non-adaptive baseline, it adds a refusal, non-convergence, bound hit, repaired/unsupported frame, or more than 0.02 absolute crop-fraction loss. It is also unsafe on a recording if it is both more than twice the baseline primary error and more than 0.05 px worse.

Errors are practically equivalent inside `max(0.001 px, 5% of the lower median error)`. Ties are resolved by fewer failures, lower p90, fewer changed settings, lower runtime, then lexicographic recipe ID.

Adaptive training proceeds only if the safe recording oracle beats the strongest safe non-adaptive policy by at least 0.005 px and 15% source-balanced overall. An image type can be adaptive only if its headroom is at least 0.003 px and 10%, its gains occur in at least three independent development groups, and oracle winners include at least two recipes across groups. Otherwise that scope uses the strongest fixed/category policy.

## Promotion gates

On validation and, once, on final evidence, an adaptive scope must meet all of these against the strongest frozen non-adaptive policy:

- improve the source-balanced primary metric by at least `max(0.001 px, 5%)`;
- no image-type median regression greater than 0.002 px;
- p90 regression no greater than 0.005 px and worst-case regression no greater than 0.05 px;
- no recording triggers the two-part `2x and +0.05 px` guard;
- add no failure, refusal, non-convergence, bound hit, repaired or unsupported frame;
- lose no more than 0.02 absolute crop fraction;
- complete pilot, selection and final registration in no more than 1.50 times the baseline workflow runtime.

Failure of an adaptive scope promotes its frozen fixed/category fallback, not a retuned selector. Invalid, out-of-distribution or below-threshold evidence must reproduce the complete category recipe byte-for-byte.

## Required provenance

Java, Python, Fiji reports, batch results and recorded macros must expose the protocol, feature-contract and model versions; candidate/source/model artifact SHA-256 values; selected recipe ID; predicted gain; confidence threshold; whether fallback occurred; and the fallback reason. Java and Python must agree on evidence validity, eligibility, predictions, selected recipe and provenance before promotion.

## Frozen environment

- Windows 11 Home `10.0.26200`
- Oracle Java `25.0.2`
- Maven `3.9.9`
- Python `3.13.14`
- AMD Ryzen 5 5600X, 6 cores / 12 threads
- ImageJ `ij-1.54p.jar`, SHA-256 `2E1A09961DFB41CEE66DDC821B2577A41A072566CE45A49BAE69267099741E20`
