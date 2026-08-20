# Full automatic registration selector sweep plan

## Outcome

Replace the current automatic filter-and-mask selector with a selector that can also choose base pixel support and brightness exclusions, while retaining explicit manual control of every setting in the plugin.

The selector will choose only Log-Ratio Registration settings. It will not choose a competing registration method.

## Scope fixed for this sweep

The category recommendation continues to supply robust weighting, reference strategy, lags, movement bound, estimation scale, and the normal compute budget. The new selector may override these four dimensions:

| Dimension | Automatic candidates |
|---|---|
| Base pixel support | `ALL`, `GRADIENT`, `MUTUAL_NOISE_GRADIENT` |
| Intensity restriction | none, exclude brightest 10%, exclude brightest 25%, exclude dimmest 25% |
| Estimation filter | none, Gaussian 0.7 pixels, Gaussian 1.0 pixels, median 3 by 3 |
| Spatial removal | none, remove least informative 25% |

This produces `3 × 4 × 4 × 2 = 96` complete candidate recipes per controlled recording.

The accuracy sweep fixes the gradient multiplier at `0.5`, spatial removal at `25%`, mask scoring on the raw image, maximum iterations at `25`, and maximum samples at `200,000`. These are tuned only after the accuracy winner is frozen:

- Gradient multiplier sensitivity: `0.25`, `0.5`, `1.0` for winning gradient-based recipes.
- Compute sensitivity: `12 iterations / 50,000 samples` versus `25 / 200,000` for winning mutual-noise recipes.

All existing preprocessing, removal strategies, percentages, gradient multipliers, and intensity percentiles remain available manually even when they are not automatic candidates.

## Execution flow being built

```text
User chooses image type and motion type
                  |
                  v
       Category base recommendation
    (norm, reference, lags, movement bound)
                  |
                  v
        Neutral provisional registration
  (all pixels, no band, no filter, no mask)
                  |
                  v
       Measure image and motion evidence
                  |
                  v
       Full automatic selector chooses
 support + intensity band + filter + mask
                  |
                  v
       Show the resolved editable settings
                  |
                  v
             Final registration
```

If the selector is uncertain, it falls back to the complete current category recommendation, not the weak base-without-add-ons control.

## Stage 1 — Freeze inputs, candidates, and baselines

1. Create a versioned experiment manifest containing:
   - the 20 existing real source series;
   - their four controlled movement paths;
   - the 96 recipe definitions;
   - source checksums;
   - code commit;
   - Java, Fiji, operating-system, and processor details.
2. Copy the existing benchmark winner and controls into the summary without rerunning or overwriting them:
   - current category recommendation: `0.025545 px`, `0.811376 s` mean per recording;
   - current automatic filter-and-mask selector: `0.025582 px`, `1.479984 s`;
   - base without add-ons: `0.368773 px`, `0.620267 s`;
   - older automatic information selector: `1.991650 px`, `6.789563 s`.
3. Give every recipe a complete identifier containing support, intensity band, filter, and mask.
4. Reject invalid configurations before execution.

**Output:** `library/benchmark/v2/benchmarks/controlled_motion/summaries/full_selector_sweep_v1/manifest.csv` and `recipe_manifest.csv`.

**Exit gate:** exactly 20 source series, five balanced image types, four movement profiles per source, 80 controlled recordings, and 96 unique recipes.

## Stage 2 — Build the resumable factorial benchmark

Add a dedicated benchmark runner rather than extending the already frozen 73-arm benchmark.

Each recording gets this structure:

```text
<image type>/<source>/<motion>/CLEAN/full_selector_sweep_v1/
  <recipe identifier>/
    settings.csv
    transforms.csv
    comparison.csv
    corrected.tif
```

The runner must:

- use the existing full-resolution controlled truth and metrics;
- apply estimated transformations to the original stack;
- preserve one folder per recipe;
- resume from complete folders;
- rerun incomplete or corrupt folders only;
- record elapsed and processor time separately;
- record failures instead of silently omitting them;
- write progress counts and an estimated completion time;
- verify all expected artifacts after completion.

The run contains `80 × 96 = 7,680` candidate registration folders. A disk-space estimate is written before execution; the run stops before writing if the complete artifact set will not fit.

**Primary source changes:**

- Add `src/test/java/logratio/FullSelectorFactorialBenchmark.java`.
- Reuse the input discovery, resume checks, timing, transform writing, and comparison metrics from `FullLogRatioVariationBenchmark`.
- Do not change or overwrite `full_benchmark_2026-08-16`.

**Exit gate:** 7,680 attempted recipes, zero missing comparison rows, every failure explicitly represented, and an artifact audit with zero structural errors.

## Stage 3 — Run and summarize the development sweep

Run the 96 recipes on all 80 existing controlled recordings. Produce:

- one row per recording and recipe;
- balanced summaries by image type, motion type, and image-by-motion cell;
- failure counts;
- median, 90th-percentile, and maximum movement error;
- elapsed and processor time;
- a per-recording oracle recipe;
- the improvement or regression relative to the current category recommendation.

Define equivalent accuracy before examining the results:

```text
equivalence width = max(0.001 px, 5% of the lowest median error)
```

Among recipes inside that width, prefer in order:

1. no failure;
2. lower 90th-percentile error;
3. fewer automatic changes from the category recommendation;
4. shorter processor time.

Recipe pruning occurs inside each training fold only. A recipe may remain an automatic output only if it wins on at least two independent source series or is the current category fallback. This prevents one recording from creating a production branch.

**Outputs:** `all_recipes.csv`, `recipe_summary.csv`, `cell_summary.csv`, `oracle_by_recording.csv`, and `FINDINGS.md` under the versioned summary folder.

**Exit gate:** balanced coverage is retained and every oracle decision can be reconstructed from the raw recipe rows.

## Stage 4 — Train the combined selector without test leakage

Build a new `AutomaticRegistrationSelector`; keep `AutomaticFilterSelector` as a compatibility wrapper until macros, batches, and saved settings have migrated.

The selector evidence contains:

- the existing 17 raw-image features;
- the existing 10 provisional-motion features;
- the older selector's sparsity score, moving-tail ratio, and outlier fraction;
- the fractions of pixels admitted by `GRADIENT` and `MUTUAL_NOISE_GRADIENT` support;
- the user-declared image and motion types;
- the retained category base norm and reference strategy.

The selector output is one explicit recipe containing:

- pixel support;
- gradient multiplier;
- lower and upper intensity percentiles;
- estimation preprocessing;
- spatial-removal strategy;
- mask-scoring preprocessing;
- removal percentage;
- iteration and sample budgets.

Use leave-one-source-series-out validation: all four movement versions of one source are withheld together. Candidate pruning, feature selection, coefficients, and confidence thresholds are recomputed using the other 19 sources in every fold.

The decision rule is conservative:

1. Score only recipes retained inside that fold.
2. Select an override only when its predicted gain clears the frozen confidence threshold.
3. Otherwise return the complete category recommendation.
4. Never construct a combination that was absent from the 96-recipe sweep.

Compare an interpretable rule tree with conservative one-versus-rest linear rules. Choose between them using only source-held-out results. Do not add an external machine-learning dependency.

**Primary source changes:**

- Add `src/main/java/logratio/api/AutomaticRegistrationSelector.java`.
- Add a complete recipe/result value class under `logratio.api`.
- Extend `LogRatioResult` to report the resolved recipe and evidence.
- Replace `resolveAutomaticFilters` with `resolveAutomaticSettings` while retaining a compatibility entry point.
- Add a training/validation runner under `src/test/java/logratio`.

**Validation gates across the 20 held-out source folds:**

- zero missing selections and zero registration failures;
- mean median error no worse than `0.025545 px`;
- mean 90th-percentile error no worse than `0.095121 px`;
- no image type regresses by more than `0.002 px` in mean median error;
- no recording is both more than twice its category-recommendation error and more than `0.05 px` worse in absolute terms;
- sparse low-light mean error is no worse than the category recommendation;
- mean execution time no slower than the current automatic selector's `1.479984 s` unless the accuracy improvement is declared worth the cost before the locked test.

If these gates fail, revise the candidate recipes or features using development folds and rerun Stage 4. Do not inspect the locked test.

## Stage 5 — Add an independent balanced test set

The existing 20 sources have already influenced the current selectors and cannot provide a final independent claim.

Add at least two new real source series for each of the five image types: ten new source series total. Apply the same four controlled movement paths to produce 40 locked controlled recordings. Keep the image-type counts equal.

The frozen selector is run once on this set. No coefficient, threshold, feature, or recipe changes are allowed after viewing its results.

Natural-motion versions of the new source series are also processed, but their residual stability is reported separately and is not called movement accuracy.

**Locked-test release gates:**

- zero failures;
- paired mean median error no worse than the category recommendation;
- no image-type regression greater than `0.002 px`;
- the sparse low-light guard from Stage 4 passes;
- the runtime gate from Stage 4 passes.

Failure retains the category recommendation as the production default and labels the combined selector experimental.

## Stage 6 — Expose three clear plugin modes

Replace the ambiguous combination of “recommended” and “automatic filters” controls with one mode choice:

| Mode | Behaviour |
|---|---|
| Recommended | Load the current image-type and motion-type recipe |
| Automatic full selection | Resolve pixel support, intensity band, filter, and mask, then show the result |
| Manual | Use the explicit values shown in the settings dialog |

The current advanced dialog already contains all required manual fields. Retain and regroup them as:

1. **Base pixel support:** all pixels, gradient pixels, or mutual noise-significant edges; gradient multiplier.
2. **Intensity restrictions:** dimmest and brightest percentiles excluded.
3. **Estimation filter:** every `Preprocessing` value.
4. **Spatial pixel removal:** every `PixelSelectionStrategy` value, mask-scoring filter, and removal percentage.
5. **Remaining fit and performance settings:** robust weighting, reference strategy, scale, shift, iterations, and samples.

Automatic mode must show the resolved values in this same editable dialog. Editing any resolved value changes the run to Manual so the selector cannot silently overwrite the edit.

The confirmation and run log must state the complete resolved recipe, not only a recipe label.

**Primary source changes:**

- `LogRatioRegistrationPlugin.java`: mode control, resolved-settings review, and clear grouping.
- `LogRatioParameters.java`: explicit selection-mode field and resolved recipe provenance.
- `LogRatioDialogModel.java`: record the selected mode and all explicit fields.
- `MacroOptionsParser.java`: add `selection_mode=recommended|automatic|manual` and keep old macro syntax working.
- `LogRatioRegistrationBatchPlugin.java`: allow automatic resolution per stack or replay one explicit manual recipe; include selector passes in its time estimate.
- `LogRatioSweepPlan.java`: retain all current manual sweep axes and add any missing complete-recipe serialization.

**Plugin exit gates:**

- every automatic output can be reproduced exactly in Manual mode;
- every manual field survives macro recording and replay;
- batch replay applies the same explicit recipe when requested;
- automatic batch mode reports the recipe chosen for every stack;
- registration-channel transforms still apply to every channel and slice of the original hyperstack;
- no dialog is required for macro, batch, Groovy, Jython, or Java application programming interface use.

## Stage 7 — Tests and final benchmark

Add tests for:

- all 96 recipe identifiers and parameter mappings;
- selector fallback and every retained output recipe;
- strict confidence boundaries;
- support, brightness, filter, and mask changes appearing in resolved parameters;
- automatic-to-manual replay equality;
- macro and batch round trips;
- legacy `automatic_filters` compatibility;
- invalid recipe rejection;
- source-group separation in validation;
- resume and artifact audits;
- hyperstack transformation and untouched non-registration channels;
- full plugin build and the existing test suite.

After the selector passes the locked test, rerun the complete comparison benchmark with these distinct arms:

1. current category recommendation;
2. old automatic information selector;
3. current automatic filter-and-mask selector;
4. new full automatic selector;
5. manual oracle recipe per controlled recording, clearly labelled as an unattainable upper bound;
6. base without automatic changes.

Store each arm inside every image-series folder with `corrected.tif`, `transforms.csv`, `comparison.csv`, and explicit settings.

The new selector becomes the plugin default only if it passes every accuracy, failure, balance, sparse-image, and runtime gate. Otherwise it remains available as an optional automatic mode and the category recommendation remains the default.

## Completion definition

The work is complete only when:

- the 96-recipe development sweep and artifact audit are complete;
- source-held-out validation is complete;
- the frozen selector has been run once on a new balanced locked test set;
- manual, recommended, and automatic modes are unambiguous in the plugin;
- every chosen automatic recipe is editable and replayable manually;
- batch, macro, Groovy, Jython, and Java application programming interface routes reproduce the same parameters;
- the final benchmark contains all comparison arms in every image-series folder;
- the evidence record states whether the new selector earned default status.
