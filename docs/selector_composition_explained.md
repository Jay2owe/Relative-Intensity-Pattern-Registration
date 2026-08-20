# How the registration selectors fit together

The current system has a label-based base preset, an older automatic pixel-support selector, and a newer automatic filter/mask selector; only the last of these is exposed in the plugin, so the two automatic selectors are not yet combined there.

## For a methods section

Registration settings were initialized either manually or from a benchmark-derived lookup indexed by user-declared image and motion classes. An optional recording-level pixel-support rule classified the field as sparse, dense with local motion, or dense and stable, then selected the pixels eligible to contribute to alignment. Separately, an optional add-on selector measured image features and provisional motion features and selected one validated preprocessing or spatial-removal recipe. Preprocessing affected movement estimation only, while a spatial-removal recipe used its scoring image to construct a mask and then fitted the log-ratio model to the unfiltered intensities retained by that mask. The estimated transformations were applied to the original full image stack.

## Overview

Think of the process as hiring a survey team. The base preset supplies the measuring method, the pixel-support selector decides which surveyors may vote, and the filter/mask selector decides whether to clean their view or exclude unreliable locations.

```text
User-declared image and motion labels, or manual settings
                         |
                         v
                  Base registration settings
                         |
          +--------------+--------------+
          |                             |
          v                             v
Older pixel-support selector      Newer add-on selector
chooses eligible pixel type       chooses filter or spatial mask
          |                             |
          +--------------+--------------+
                         |
                         v
              Final eligible raw pixels
                         |
                         v
             Log-ratio movement estimate
                         |
                         v
          Transform original full image stack
```

The diagram describes the coherent combined design. The current public plugin implements the base-settings path and newer add-on selector, but does not expose the older pixel-support selector.

## The analysis in order

1. Load either manual base settings or a label-based preset.
2. If enabled at the registration-core level, use the older selector to choose the type of pixel eligible to support alignment.
3. If enabled through the public plugin or application programming interface, run an unfiltered provisional fit and let the newer selector choose one add-on recipe.
4. Estimate movement using the resolved base settings and add-on, then apply the transforms to the untouched full stack.

## Step 1 — Resolve the base settings

The label-based preset is a lookup, not an automatic classifier. The user supplies an image type and a motion type. Their combination loads a complete base bundle including robust weighting, initial pixel support, brightness limits, reference strategy, iteration limits, preprocessing, and any fixed spatial-removal rule. “Complete image/motion recipe” means this bundle of settings; it does not mean that the software has automatically inferred the labels or explored every possible setting.

No arithmetic is performed in this step: it is a table lookup.

**Engine:** `LogRatioRecommendations.forTypes` selects the preset, and `LogRatioParameters.Builder.recommendation` copies it into the public parameter bundle.

## Step 2 — Optionally choose pixel support from the recording

Pixel support means the rule defining which pixels are allowed to contribute evidence to the movement estimate. The older `AutomaticInformationSelector` first tests whether the first frame is background-dominated. If it is sparse, it requires an edge above the estimated noise level in both mapped frames. Otherwise it provisionally aligns the first two frames and uses the tail of the log-intensity residual distribution to distinguish local movement from a stable field. A stable dense field additionally excludes the brightest tenth.

In words: the sparsity score is the median minus the first percentile, divided by the ninety-ninth percentile minus the first percentile.

Plain text:

```text
sparse_score = (P50 - P1) / (P99 - P1)
```

LaTeX:

\[
s = \frac{P_{50}-P_1}{P_{99}-P_1}
\]

Values below `0.50` select shared noise-significant edges. For denser fields, a residual-tail ratio above `1.55` selects ordinary gradient pixels; otherwise the selector uses gradient pixels and excludes the brightest ten percent.

**Engine:** `AutomaticInformationSelector.select` measures the evidence, and `AutomaticInformationSelector.Result.applyTo` changes pixel support, the gradient fraction, an optional brightness ceiling, and the sparse-branch compute budget.

## Step 3 — Optionally choose a filter or spatial mask

The newer `AutomaticFilterSelector` begins with an unfiltered provisional registration using the chosen base settings. It measures image appearance and provisional motion, standardizes each feature, scores the validated add-on recipes, and chooses exactly one of: no add-on, Gaussian filtering at standard deviation 0.7 or 1.0 pixels, a 3 by 3 median filter, removal of the least spatially informative 25 percent, or the median filter combined with that removal.

In words: each feature is centered on its training mean and divided by its training scale; each recipe score is an intercept plus the sum of its feature weights multiplied by the standardized features.

Plain text:

```text
z[i] = (feature[i] - mean[i]) / scale[i]
recipe_score = intercept + sum(weight[i] * z[i])
```

LaTeX:

\[
z_i = \frac{x_i-\mu_i}{\sigma_i}, \qquad
S_r = b_r + \sum_i w_{r,i} z_i
\]

The selected recipe replaces only the preprocessing and spatial-removal add-ons. It preserves the remaining base settings.

**Engine:** `LogRatioRegistration.resolveAutomaticFilters` performs the provisional fit, `AutomaticFilterSelector.measure` creates the evidence, and `AutomaticFilterSelector.select` resolves explicit final parameters.

## Step 4 — Combine the decisions in the final fit

Conceptually, the selectors fit together: the older selector supplies a base eligibility set, and the newer spatial-removal recipe supplies an additional mask. A pixel contributes only if it passes both. A selected filter can instead prepare the estimation view without changing the corrected output pixels.

In words: final valid pixels are the intersection of the older support set and the newer spatial mask.

Plain text:

```text
final_pixels = support_pixels AND spatial_mask_pixels
```

LaTeX:

\[
V = E \cap M
\]

where \(E\) is the base pixel-support set and \(M\) is the optional second-pass spatial mask. If there is no spatial mask, \(M\) is the whole image.

This combination is not currently available through the plugin. The public `LogRatioParameters` bundle contains `pixelSupport` and `automaticFilterSelection`, but not the core flag `automaticInformationSupport`. Therefore the plugin can combine a manual or label-based base with the newer selector, but it cannot yet ask the older selector to resolve pixel support automatically.

**Engine:** `LogRatioRegistration.estimateResolved` prepares the estimation image, runs `PixelSelectionEngine` when a spatial mask is active, fits raw pixels under that mask, rescales any downsampled translation, and `StackWarper.apply` transforms the original full stack.

## What can be combined now

| Combination | Current status |
|---|---|
| Label-based preset with its fixed preprocessing or mask | Supported |
| Label-based base settings with the newer automatic add-on selector | Supported |
| Manual base settings with the newer automatic add-on selector | Supported |
| Older automatic pixel-support selector alone | Registration core and tests only |
| Older automatic pixel-support selector with the newer automatic add-on selector | Not wired or benchmarked |

The sensible implementation order for a combined automatic route is base settings, then automatic pixel support, then provisional motion measurement, then automatic add-on selection, then the final fit. Precedence must be explicit because the older selector can overwrite pixel support and a brightness ceiling that a manual or label-based base already supplied.

## Failure cases and limits

- A label-based preset is only as appropriate as the image and motion labels supplied by the user.
- The older selector examines the first frame and, for dense data, the first two frames; they may not represent a recording whose character changes later.
- The older selector performed poorly on sparse low-light recordings in the balanced controlled benchmark, so exposing it as a default or composing it unchanged with the newer selector is not justified.
- The newer selector chooses one frozen recipe rather than stacking arbitrary filters.
- Rolling reference registration is incompatible with the motion evidence used by the newer selector and is rejected by parameter validation.
- Filters affect estimation only. They do not alter the pixels written to the corrected image stack.

## Worked examples

### Dense stable image

The older selector may choose gradient pixels and exclude the brightest ten percent. The newer selector may choose no add-on. The final fit then uses the older eligibility rule alone.

### Noisy image with unreliable regions

The older selector may choose ordinary gradient support. The newer selector may choose a median filter plus removal of the least informative 25 percent. The median-filtered view helps define the recipe and mask, while the final movement fit uses unfiltered intensities that pass both eligibility rules.

### Label-based recommendation only

The user declares sparse low-light fluorescence with steady directional drift. The lookup loads its tested robust norm, pixel support, preprocessing, brightness settings, reference strategy, and fixed pixel-removal rule. Neither automatic selector is needed.

## Interpretation

“Pixel-evidence mode” should be renamed **automatic pixel support**: it decides which type of pixels can vote for movement. “Complete image/motion recipe” should be renamed **label-based preset**: it is the full settings bundle selected from user-supplied image and motion labels. The newer control should be named **automatic filter and mask add-ons**.

The two selectors can form a useful two-stage system, but that composition is presently an unimplemented experimental route rather than the current automatic mode.

## Symbols

| Symbol | Meaning |
|---|---|
| \(P_1, P_{50}, P_{99}\) | First, median, and ninety-ninth intensity percentiles |
| \(s\) | Sparsity score |
| \(x_i\) | Measured image or motion feature |
| \(\mu_i\) | Stored feature mean |
| \(\sigma_i\) | Stored feature scale |
| \(z_i\) | Standardized feature |
| \(S_r\) | Score for add-on recipe \(r\) |
| \(E\) | Pixels admitted by the base pixel-support rule |
| \(M\) | Pixels retained by the optional spatial mask |
| \(V\) | Pixels used by the final fit |

## Technical reference

### Data flow

```text
Image stack
  -> manual settings or label-based preset
  -> optional older automatic pixel-support resolution [core only]
  -> optional unfiltered provisional fit
  -> newer automatic filter/mask selection
  -> filtered estimation view and/or raw-pixel spatial mask
  -> final log-ratio registration
  -> transforms applied to original stack
```

### Principal files

- `src/main/java/logratio/api/LogRatioRecommendations.java`: label-based preset lookup.
- `src/main/java/logratio/api/LogRatioParameters.java`: public plugin, macro, and Java application programming interface settings.
- `src/main/java/logratio/core/AutomaticInformationSelector.java`: older recording-derived pixel-support selector.
- `src/main/java/logratio/core/Registration.java`: core-only automatic pixel-support flag and invocation.
- `src/main/java/logratio/api/AutomaticFilterSelector.java`: newer automatic preprocessing/spatial-mask selector.
- `src/main/java/logratio/api/LogRatioRegistration.java`: provisional selection, final estimation, and stack transformation pipeline.

### Measured status

In the completed balanced controlled benchmark, the label-based recommendation had a pooled median error of `0.025545` pixels, the newer automatic filter selector had `0.025582` pixels, and the base without add-ons had `0.368773` pixels. The older automatic pixel-support selector had `1.991650` pixels pooled median error and reached `9.810019` pixels on sparse low-light data. These results compare separate routes; they do not measure the two selectors combined.

### Verification

The selector boundaries and public integration are covered by `AutomaticInformationSelectorTest`, `AutomaticFilterSelectorTest`, `LogRatioRegistrationApiTest`, and `LogRatioRecommendationsTest`. A combined-selector test does not exist because the public combination has not been implemented.

## References

- The label-based lookup and both selector rules are original project methods frozen from the project's development and validation benchmarks.
- Phase correlation is used internally only to create temporal evidence for the older selector; its literature citation is not recorded in the inspected selector source.
