# Relative-Intensity Pattern Registration: A Plain-Language Explanation

This is the simple companion to [the full explanation](log_ratio_registration_concepts_explained.md). Both use the same 19 steps, in the same order, and the same three recipe names.

Think of lining up transparent photographs taken while someone changes the lighting. Some pictures show a whole glowing slice; others show dark tissue landmarks; others mostly show individual moving cells. Those need different ways of deciding what should stay aligned.

## Which recipe does what?

| Recipe label | What it uses to find movement | What it does not do |
|---|---|---|
| **Moving cells** | The original image-and-motion Recommended recipe: shared edges and relative intensity changes, checked across several frame separations | No bright/dim references, tissue-landmark reference or later tissue-motion cleanup |
| **Bright/dim** | Two actual images from the same channel, representing bright and dim states; chooses the better match for each frame | Does not average the two movement estimates; does not run the Moving cells recipe afterwards |
| **Landmarks** | Tissue edges and dark internal regions, combined into one reference from the recording | Does not use bright/dim reference selection or the Moving cells final recipe |

Bright/dim covers both fluorescence and bioluminescence. Landmarks covers brightfield and phase contrast. Moving cells describes the kind of visible signal, not the imaging technology: microglial bioluminescence can belong here.

For these recordings, SynRCaMP, SynGABASnFR and Incucyte are fluorescence; Per2::Luc is bioluminescence; transmitted-light and brightfield images belong with brightfield/phase contrast. “MF” is a person's initials, not a channel type. Legacy filenames are not reliable evidence of channel identity.

These are the **experimental three-recipe routes used to describe the review work**. They are not all installed under the existing plugin's “Longitudinal maximum accuracy” menu option. That older entry point is separate.

## The analysis in order

1. Choose the guide image.
2. Choose the recipe.
3. Prepare temporary working copies.
4. Choose which frame pairs to compare.
5. Search from coarse to fine.
6. Separate overall brightness change from movement.
7. Give reliable, agreeing pixels more influence.
8. Use correlation instead where the recipe calls for it.
9. Optionally select informative regions and refit.
10. Combine pair movements and fill unsupported positions.
11. Build tissue features.
12. Build or choose tissue references.
13. Match tissue frames to their references.
14. Check for weak fits and returning excursions.
15. Check exceptional jumps.
16. Keep fit diagnostics separate from accuracy.
17. Move the original images.
18. Handle empty borders.
19. Make the review grid and its guide score.

These are a map of the alternatives, **not 19 operations applied to every video**.

~~~text
One channel -> recipe choice
                |
                +-- Moving cells -> original Recommended fit -> final movements
                |
                +-- Bright/dim -> preliminary fit -> bright/dim references --+
                |                                                           |
                +-- Landmarks -> preliminary fit -> landmark reference ------+
                                                                            |
                                                       tissue movement checks
                                                                            |
                                      original pixels + final movements -> output
~~~

## Step 1 - Choose the guide image

**All recipes.** Supply one channel as a two-dimensional time stack. Only that channel provides evidence. A dark frame never causes another channel to be substituted.

The general plugin can also use one declared depth plane or a maximum projection through depth, but the experimental three-recipe entry point expects the channel and time stack to have already been separated. A projection combines depth planes, not channels.

## Step 2 - Choose the recipe

**All recipes.** There are three different choices that must not share a vague “automatic” label.

| Choice | What happens |
|---|---|
| **Image-and-motion preset**, historically Recommended | Uses the image type and movement type supplied by the user to look up tuned settings |
| **Automatic fixed recipe**, existing plugin | Uses a fixed rule for the declared image type and movement; its normal translation path does not examine the recording to discover its type |
| **Automatic video detector**, experimental | Examines the supplied channel, compares it with stored examples and chooses one of the three routes only when its checks pass |

The three routes can also be chosen directly. This skips the detector.

The detector currently bases its choice on four descriptions of the image's brightness distribution and spatial structure. It requires support from different recordings and refuses uncertain cases. It does not identify a fluorescent protein, prove that objects are living cells, or discover the movement type.

“Manual needed” means **choose a recipe**, not manually align the frames.

## Step 3 - Prepare temporary working copies

**All recipes, with different settings.** The originals remain available for the final output. Filters, smaller working images and brightness transformations are used only to estimate movement.

Moving cells uses the original unfiltered Recommended fitting path. Its historical internal setting is called “dense fluorescence + jumps”, even when the real recording is bioluminescence. That is a recipe lookup name, not a new label for the data.

The tissue recipes start with a fixed-recipe preliminary alignment. Depending on the declared image subtype, this can use log-ratio matching or correlation. It is incorrect to say every tissue recording starts with log-ratio matching.

## Step 4 - Choose which frame pairs to compare

**Moving cells and the tissue preliminary fit.** Moving cells compares frames separated by 1, 2, 4, 8 and 16 frame intervals, wherever those pairs exist.

This is like checking a journey using both short local directions and longer-distance landmarks. Longer comparisons help constrain mistakes that would otherwise accumulate.

The tissue preliminary recipe may instead compare each frame with the previous frame. Its later reference matching is described in steps 12–13.

## Step 5 - Search from coarse to fine

**Moving cells and the tissue preliminary fit.** Small, blurred copies make a large movement easier to find. Larger copies then refine it.

An initial survey estimates how large the search region needs to be. The main fit does not assume that every jump fits inside a small fixed window. This survey is still an estimate, not knowledge of the true motion.

## Step 6 - Separate overall brightness change from movement

**Log-ratio matching: Moving cells, and any preliminary fit using that matcher.** Imagine a dimmer switch that makes the whole correctly aligned picture brighter. The method allows one overall brightness change before judging whether the spatial pattern agrees.

It adds a small offset, takes logarithms and subtracts the middle brightness difference for each proposed movement. A logarithm turns a multiplication into an addition, so the overall change can be removed with one number.

This does not remove arbitrary local waves of light. It is also less exact when the signal is near zero. Correlation-based fits skip this objective.

## Step 7 - Give reliable, agreeing pixels more influence

**Log-ratio matching.** Moving cells gives a vote to an edge only when it reaches a fixed threshold based on the estimated noise in both matched images. Large remaining disagreements receive little or no influence.

This can reduce the pull of a changing cell or patch, provided enough common structure remains. It does not segment cells, follow individual cells or guarantee that all biological movement is ignored.

Moving cells uses its own fixed iteration and sampling limits. The generic plugin defaults are not the recipe settings.

## Step 8 - Use correlation instead where the recipe calls for it

**Alternative preliminary matcher; also the basis of later tissue fitting.** Correlation asks whether the arrangement of bright and dark features agrees after allowing for overall brightness and contrast.

The project contains ordinary normalised correlation, a derivative-based refinement and enhanced correlation coefficient fitting, abbreviated **ECC**. ECC is an established published method, not a new name for the project's log-ratio calculation.

The current dense-emission fixed recipe uses correlation with ECC refinement. Moving cells does not. Steps 11–13 explain how the two tissue routes use their different features and reference fits.

## Step 9 - Optionally select informative regions and refit

**Only a preliminary or manual recipe that enables this option. Moving cells does not.** A first fit can identify useful regions, such as features with detail in more than one direction. A mask marks the selected regions, is moved back into each original frame and is used for a second fit.

This optional mask is separate from the Landmarks reference mask in step 12. They are not one shared operation applied to every recipe.

## Step 10 - Combine pair movements and fill unsupported positions

**Moving cells and the tissue preliminary fit.** When several pairs describe the same journey, the method finds frame positions that agree with those measurements as closely as possible. Moving cells gives usable pairs equal weight.

If a frame has no supported position, the method fills between supported neighbours or holds the nearest supported position at an end. That is an inference, not a measured correction.

Moving cells does not reject a supported movement simply because it is a large rare jump. Its movements are now final; it skips the tissue-specific steps 11–15.

## Step 11 - Build tissue features

**Bright/dim and Landmarks only.** Both make new temporary pictures from the same raw channel.

Bright/dim reduces broad glow and limits unusually strong detail. Landmarks emphasises edges and dark structures at several sizes. Their formulas are different: they do not use one interchangeable “normalised image”.

The dark-landmark feature is also used inside rare-jump checks for Bright/dim. That does not turn its main reference fitting into the Landmarks recipe.

## Step 12 - Build or choose tissue references

**Bright/dim:** chooses a bright and a dim actual frame, using the central part of each raw image. It avoids frames with too little contrast when enough alternatives exist. “Dim” therefore does not always mean the darkest frame.

**Landmarks:** uses the preliminary movements to line up the feature pictures, then takes their typical appearance at each position. It keeps persistent, distinctive regions to create one masked reference.

All references come from the channel being registered.

## Step 13 - Match tissue frames to their references

**Bright/dim:** first aligns the two reference images with each other. Each frame is then matched to both references from several starting guesses. It keeps the better-scoring path, including the quality of the link between references. **It does not blend the movements.** Its OpenCV fit can estimate an in-plane turn as well as a shift.

**Landmarks:** matches each feature image to its one reference, using the project's normalised-correlation search and ECC refinement. This part fits shifts only and does not call OpenCV's ECC fitting function.

Both attach an internal confidence value to each frame. Neither confidence nor correlation is a known error in pixels.

## Step 14 - Check for weak fits and returning excursions

**Both tissue recipes; never Moving cells.** The method first identifies supported jumps that appear to persist, and separates the movement record at those points.

Within each part, it fills weak positions and checks short movements that go out and return. Certain large returning excursions are replaced by a straight connection between their endpoints.

This is targeted cleanup, not smoothing every frame. It follows the assumption of slow drift, gentle shaking and occasional persistent jumps. Genuine out-and-back motion can also satisfy the rule.

## Step 15 - Check exceptional jumps

**Bright/dim:** checks some large late movements using the centre of the bright tissue. It also checks certain late chains of abrupt movements using tissue landmarks.

**Landmarks:** checks suspicious jumps throughout the recording, asking whether an adjacent-frame fit agrees with the change between groups of frames on either side.

These checks can add an in-plane angular correction. They require specific supporting evidence and do not guarantee that an isolated jump in the very last frame will be recovered.

## Step 16 - Keep fit diagnostics separate from accuracy

**All recipes, with different available records.** Keep the actual recipe name, movement record, failures and relevant fit checks.

A log-ratio residual measures remaining intensity disagreement. A correlation measures pattern similarity. Confidence marks how a particular recipe treats a fit. None is the true registration error.

The separate review guide in step 19 estimates remaining apparent movement in pixels. It too can be wrong when the biology or illumination changes.

## Step 17 - Move the original images

**All recipes, after movement estimation.** Apply the final movements to the original image values, not the filtered or brightness-scaled working pictures.

A fractional-pixel movement needs interpolation: estimating a value between neighbouring pixels. The reviewed native outputs use bilinear interpolation, which combines four neighbouring values. That changes sampled values; “the original intensities are never changed” would be too strong.

The generic plugin also offers other output interpolation choices. Matching movement numbers alone does not prove two TIFF files contain identical pixels.

## Step 18 - Handle empty borders

**All recipes, according to the exporter.** Moving an image can expose an area outside the original picture. That empty area is not a measurement.

An optional crop retains a shared valid region. The native comparison exports keep their full frame, while the review score excludes invalid borders. Cropping and movement estimation are different operations.

## Step 19 - Make the review grid and its guide score

**Review only; not part of recipe selection.** Show the original, the selected recipe and the external comparison methods together, with small labels, registration time and the available guide score. Failed external methods must remain visibly marked as failed.

For the historical biological comparison, the guide asks how far each corrected frame still appears displaced from the corrected first frame, within the common valid image area. The recording summary takes the middle score across the later frames.

A low score is useful evidence, not proof of perfect alignment. Moving cells, pulses or disappearing signal can fool the guide too. The montage is contrast-adjusted and reduced for visual inspection; use native registered images for measurements.

The latest assembled grids reuse existing registered outputs and timings. They are not a new test of the automatic video detector.

## What the name means now

**Relative-Intensity Pattern Registration** names the overall method and its recipes. It should not imply that every recipe uses the original log-ratio objective throughout.

The project's contribution is its particular recipe construction, selection and checks. Established ingredients such as correlation, enhanced correlation coefficient fitting, robust fitting and interpolation must still be credited. The [full explanation](log_ratio_registration_concepts_explained.md#references) separates those ingredients from project-specific rules.
