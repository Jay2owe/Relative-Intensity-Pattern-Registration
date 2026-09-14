# Log-Ratio Registration concept illustrations

Eleven text-free raster illustrations matching the execution order in
[`log_ratio_registration_concepts_explained.md`](../../log_ratio_registration_concepts_explained.md).
They explain the method rather than the current Fiji interface, so the same assets can accompany a
Java, Python or R implementation.

## 1. Resolve the measurement plane and recipe

![Microscopy hyperstack, declared image appearances, recipe decision and selected measurement plane](01-resolve-measurement-and-recipe.png)

One channel and focal plane are selected, then declared image appearance and motion determine the
registration recipe.

## 2. Prepare estimation images and take logarithms

![Estimation-only preparation, validity mask and log-intensity transformation beside an untouched stack](02-prepare-and-log.png)

Filtering, scaling, background handling, masking and logarithms affect the estimation copy while the
source stack remains untouched.

## 3. Set the search range and build pyramids

![Preliminary displacement search followed by coarse-to-fine image pyramids](03-search-bound-and-pyramid.png)

A coarse pilot supplies a safe movement bound; progressively finer pyramid levels refine the
translation.

## 4. Plan the frame-pair graph

![Microscopy filmstrip connected by redundant short and long temporal links](04-plan-frame-pair-graph.png)

Short- and long-lag comparisons provide redundant routes through the time-lapse and are processed in a
fixed order.

## 5. Separate global gain from movement

![Global dimmer component separated before translating two microscopy frames, with a local changed cell retained](05-separate-gain-from-movement.png)

One frame-wide brightness factor is removed from the alignment score; local biological change remains
local evidence rather than being mistaken for global gain.

## 6. Solve the log-ratio fit robustly

![Many consistent translation votes pass a robust weighting gate while large local outliers are weakened](06-robust-log-ratio-solve.png)

Many consistent spatial votes retain their influence while a few large change-driven votes are
downweighted before coarse-to-fine refinement.

## 7. Alternatively fit normalized area correlation

![Two microscopy windows are centered and scaled before an orange refinement step reaches a similarity peak](07-normalised-area-correlation.png)

The alternative estimator normalizes whole windows, searches their overlap and refines the selected
correlation peak.

## 8. Select informative pixels and refit

![Pilot registration, local texture scoring, moving mask and final refit on raw microscopy images](08-informative-pixel-refit.png)

A pilot trajectory transports a localisability mask through time; the final fit uses selected locations
but returns to the original unfiltered intensities.

## 9. Reconcile pairwise movements

![Redundant pairwise frame movements enter a spring-like balancing system and emerge as one consistent trajectory](09-reconcile-pairwise-movements.png)

Slightly inconsistent pairwise translations are balanced together while the first frame fixes the
trajectory origin.

## 10. Repair unsupported frames and assemble diagnostics

![Missing and outlying frame positions are flagged, interpolated and accompanied by diagnostic tokens](10-repair-and-diagnostics.png)

Unsupported and outlying positions are visibly distinguished from direct measurements, then filled by
temporal interpolation; gain, residual support and status remain diagnostics only.

## 11. Warp the untouched stack and crop the common field

![One time-specific transform is applied across all channels and focal planes before a common valid crop](11-warp-and-crop.png)

The repaired native-scale trajectory is applied identically across the untouched hyperstack, and only
the field containing source data at every time point is retained.

The reproducible generation brief is in [`PROMPTS.md`](PROMPTS.md).
