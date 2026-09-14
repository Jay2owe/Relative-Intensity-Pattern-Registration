# Recording-adaptive registration recipe selection

## End goal

Make Automatic mode choose a validated registration recipe from measurements of the recording in front of it, rather than treating every recording in one microscopy category as equivalent. The declared image and motion types remain useful context and safety constraints, but raw-image evidence and motion evidence from a neutral provisional registration determine whether a recording-specific recipe is predicted to improve accuracy.

The resolved recipe remains fully visible, editable and reproducible. When the evidence is unfamiliar, contradictory or insufficiently confident, the selector retains the complete category recommendation instead of forcing an override.

## Why we're doing this

The best preprocessing and pair-comparison method may differ between two recordings that share the same broad image type. Signal-to-noise ratio, sparsity, feature size, repeated texture, saturation, intensity changes and the actual movement path can all change which recipe is safest and most accurate.

The code already contains most of the intended architecture. `AutomaticRegistrationSelector` measures 48 fields, including image structure, a provisional movement path, sparsity, changing-pixel evidence and declared context. It can resolve a complete recipe spanning the pair estimator, pixel support, intensity band, preprocessing and pixel mask. However, the generated production model is currently `image_type_rule`: it retains one area-correlation recipe for brightfield/differential interference contrast and one for fixed-marker imaging, while the other categories fall back. The recording-specific measurements are therefore collected but do not currently change the decision.

Earlier controlled benchmarking also showed why this is worth revisiting carefully. The best recipe chosen retrospectively for each recording was materially better than the category recommendation, but the plugin's own remaining-disagreement score ranked recipes poorly and often preferred inaccurate results. Recording-specific headroom exists in the historical translation data, but movement truth—not an estimator's internal score—must supervise the selector. Rotation changes the candidate capabilities and evidence, so the production work begins only after the rotation engine and rigid estimator set are stable.

## Architecture overview

```text
declared image type + declared motion type
                    |
                    v
     eligibility rules and category fallback
                    |
raw guide frames -> recording-specific image features
                    |
neutral provisional registration -> estimated motion and reliability features
                    |
                    v
predict expected gain/error for every eligible validated recipe
                    |
       confident winner? ---- no ----> category recommendation
                    |
                   yes
                    v
             resolved explicit recipe
                    |
                    v
run final registration from the original guide frames
                    |
                    v
apply the final transforms once to every untouched channel and Z plane
```

The selector acts only on the registration recipe: pair estimator, intensity support, preprocessing and pixel-selection settings that affect how movement is measured. Confidence-weighted reconciliation remains a later post-pair decision, and output interpolation remains a post-transform decision. Those selectors may share declared context but must not be merged into one model because they operate on different evidence at different points in the workflow.

## Stage map

| NN | Name | One-line goal | Rough size | Depends on |
|---:|---|---|---|---|
| 01 | post-rotation-protocol | Freeze the stable rigid baseline, candidate recipe space, source splits, model families, headroom threshold, promotion gates and fallback rules. | 1 day | finalized rotation engine and rigid estimator capabilities |
| 02 | recording-evidence | Audit, test and freeze the recording-specific raw-image and neutral-pilot evidence contract that may be used at run time. | 1-2 days | 01 |
| 03 | recipe-outcome-matrix | Benchmark every eligible recipe on controlled translation and rotation recordings and store one truth-free feature row beside one known-error outcome row per recording. | 1-2 days plus benchmark wall time | 02 |
| 04 | selector-headroom | Measure per-recording oracle headroom over the best category/fixed policy and stop if the advantage is too small or unsafe to justify adaptive selection. | 1 day | 03 |
| 05 | feature-model-training | Source-group cross-validate the frozen candidate model families and confidence fallback without exposing final test evidence. | 1-2 days plus training wall time | 04 |
| 06 | java-selector-integration | Generate and integrate the validated Java model or fixed-policy result with deterministic evidence, capability filtering, fallback and provenance. | 1-2 days | 05 |
| 07 | fiji-api-surfaces | Expose the resolved choice and explanation consistently through Fiji, batch processing, macros and the Java API. | 1-2 days | 06 |
| 08 | python-parity | Match the Java evidence calculations, eligibility rules, prediction, fallback and recorded recipe in Python. | 1-2 days | 06; may run alongside 07 |
| 09 | final-validation-and-promotion | Open untouched evidence once, verify all safety and compatibility gates, and promote only the adaptive selector or fallback that passed. | 1-2 days plus benchmark wall time | 07, 08 |

Stages 01-05 answer the scientific question before production behaviour changes. Stage 04 is a genuine stop gate: if per-recording choice cannot materially beat the best safe non-adaptive policy, Stage 05 emits a fixed-policy result and later stages integrate that result without inventing a selector. Stages 07 and 08 can proceed in parallel after the Java selection contract is frozen.

## House rules

- Do not start Stage 01 against a moving rotation implementation. Freeze transform conventions, rigid candidate capabilities, bounds, failure statuses, reconciliation and warping before freezing selector evidence.
- Treat declared image and motion types as context, priors and safety constraints—not movement truth and not the complete decision.
- Preserve the complete category recommendation as the low-confidence, unsupported and out-of-distribution fallback. A declined override must be an actual no-op on the category recipe.
- Keep candidate eligibility explicit. A recipe may be offered only for transform models and data scopes on which that exact estimator and settings combination has been validated.
- Preserve the current neutral-pilot principle: every pixel, full intensity band, no preprocessing and no pixel mask. A candidate filter must not influence the evidence used to decide whether to select that filter.
- Freeze whether the pilot estimator itself is universal or category-dependent in Stage 01. Do not call the pilot estimator-neutral while it inherits a category-selected comparison method.
- Measure image evidence from raw temporary guide frames. Never alter the original stack to calculate selector features.
- Motion features are estimates from the provisional pass, not known motion. Keep that distinction explicit in names, reports and claims.
- Do not select recipes from remaining image disagreement, sweep score or the estimator's own objective. Historical testing showed that lower internal disagreement can correspond to worse true movement accuracy.
- Supervise and judge recipes using known injected translation and rotation truth. Natural-motion recordings may provide robustness and visual checks but cannot provide movement-accuracy labels.
- Use identical source recordings, injected paths, bounds and scoring for every candidate arm. Only the declared recipe fields may differ.
- Separate selector inputs from outcomes physically and logically. Truth, candidate errors, winner identities and post-hoc oracle fields must never enter the feature table used in production training.
- Split data by independent original source series. All crops, channels, movement paths, noise variants and transformed versions from one acquisition stay in the same fold or evidence partition.
- Do not reopen or retune on the spent locked and sealed selector sets. Historical results establish motivation and regression baselines only; any new model that changes decisions needs fresh untouched evidence.
- Calculate the per-recording oracle before model training. The oracle uses truth and measures only the maximum available headroom; it is never an executable selection method.
- Compare against the strongest safe non-adaptive baseline, including the category recommendation and any validated fixed rule. Beating an obsolete selector is insufficient.
- Freeze model families, feature inclusion rules, regularisation choices, confidence thresholds and promotion gates before per-recording winners are inspected.
- Recompute candidate pruning, feature selection, model parameters and confidence thresholds inside each development fold. Do not select them once on the complete development table and then cross-validate only the final coefficients.
- Keep category-specific safety guards unless held-out evidence supports broader sharing. Earlier training showed that a recipe helpful for brightfield can damage sparse low-light recordings.
- Prefer declining to override over a small predicted gain. The selector must report its prediction, threshold, selected candidate or fallback, and reason.
- Detect non-finite, out-of-range and distribution-shifted evidence. Such recordings fall back deterministically rather than silently replacing invalid values with evidence that appears ordinary.
- Measure the complete workflow runtime, including provisional registration, feature extraction and the final selected registration. Accuracy gains do not excuse an undeclared doubling of cost.
- Keep the existing 48-field layout reproducible until Stage 02 deliberately versions it. Any changed order, definition or scaling requires a new model version and compatibility tests.
- `AutomaticRegistrationSelectorModel.java` is generated by `FullSelectorTraining`. Change the training/generator path and regenerate it; do not hand-edit production coefficients as the final implementation.
- Prevent compile-time constant inlining from reviving stale generated-model values. Preserve the existing runtime-read pattern or replace it only with a tested versioned model-loading design.
- Return resolved settings as ordinary explicit parameters. Automatic mode must show what it chose, allow review, record macro-replayable values and avoid re-running selection when those explicit values are replayed.
- Preserve manual mode and existing serialized recipes. A new adaptive model must not change manually specified preprocessing, estimator, support, band, mask, reference, lags, bounds, interpolation or cropping.
- Preserve translation-only behaviour when rotation is not requested. Use exact regression checks where the existing contract is exact.
- Keep recipe selection separate from confidence-weighted reconciliation and automatic output interpolation. Do not feed post-selection or post-warp evidence backwards into this model.
- Keep preprocessing confined to temporary estimation copies. Apply the final transform path once to the untouched original recording and equally to every channel and Z plane.
- Require Java and Python parity before promoting a shared adaptive default.
- Preserve unrelated working-tree changes. Each stage edits only its declared files and artifacts.
- Follow the session communication rules supplied with the task: concise, plain language, exact paths and commands.

## Known open questions

- The exact post-rotation candidate space is not yet frozen. It must reflect which log-ratio and normalized-area-correlation variants have passed rigid validation, rather than mechanically carrying every translation candidate forward.
- The present 48 fields may be sufficient but poorly modelled, or they may omit the evidence that distinguishes per-recording winners. Stage 02 must separate feature-quality problems from model-family problems.
- The current provisional pass neutralizes support, band, preprocessing and masking but can still inherit other category settings. Stage 01 must decide which inherited settings are legitimate context and which would make the measured motion evidence category-dependent.
- Predicting absolute recipe error and predicting gain over the category fallback are both plausible targets. Stage 01 must freeze the compared targets and their safety interpretation before outcomes are opened.
- Declared image type previously acted as a hard candidate boundary because cross-category pooling damaged sparse recordings. The new evidence may support softer use for some recipes, but this cannot be assumed.
- The user-declared motion type may be inaccurate. Stage 01 must decide whether it remains a model input, an eligibility rule or only a displayed prior once provisional motion evidence exists.
- Sparse low-light recordings historically had the largest oracle headroom and the least stable candidate behaviour. Fresh, genuinely independent sparse material is needed before claiming adaptive improvements there.
- The historical development matrix demonstrated translation headroom but is already heavily used. Rotation-inclusive development, validation and final evidence must be checked for independent source coverage before the plan begins.
- A more complex model may capture nonlinear feature interactions but also overfit the limited number of independent source series. Only model families frozen in Stage 01 may be compared, and an image-type rule remains a valid winner.
- A universal pilot could be slower or less reliable for some categories, while multiple cheap probes could bias runtime toward selection. Stage 01 must freeze the permitted pilot design and its compute budget.
- The interface currently skips evidence measurement for image types with no retained candidate. A recording-adaptive selector may need to measure every recording before knowing that no override is suitable; the runtime and progress behaviour must be redesigned deliberately.
- Stage 04 may find real oracle headroom that Stage 05 cannot predict safely from truth-free evidence. In that case the correct result is the strongest fixed/category policy, not a weaker adaptive claim.

## How to run a stage

After the numbered stage files are approved and written, run `/do-step docs/recording-adaptive-selector/` to execute the lowest-numbered incomplete stage.
