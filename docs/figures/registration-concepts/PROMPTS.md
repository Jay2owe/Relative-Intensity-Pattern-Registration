# Generation prompt set

Generated with the built-in OpenAI image-generation tool. Each concept used the shared visual block
below plus its numbered content block. Concepts 2-11 used the preceding generated image as a style
reference. Concept 6 was regenerated to remove a plotted weighting curve; only the replacement is kept.

## Shared visual block

```text
Use case: scientific-educational
Asset type: publication concept illustration in an eleven-image microscopy registration series
Scene/backdrop: clean white background with generous whitespace
Style/medium: polished flat scientific illustration, crisp vector-like geometry rendered as a raster image, subtle dimensional layering, accurate microscopy visual language; match the preceding image's line weight, depth, palette and microscopy texture
Composition/framing: landscape left-to-right flow, strong visual hierarchy, few large legible elements
Color palette: restrained publication palette; deep navy outlines, cyan and teal microscopy data, orange for the active analysis path, magenta only for local biological intensity change, pale gray inactive or invalid elements
Constraints: no words, no letters, no numbers, no equations, no logos, no watermark; no human figures; do not imply artificial intelligence or neural networks
```

## Concept content blocks

### 1. Resolve the measurement plane and recipe

```text
A microscopy hyperstack enters from the left. One channel and one focal plane are visibly selected.
Four small modality thumbnails show phase-contrast rings, brightfield tissue texture, dense fluorescent
cells and fiducial dots. A transparent decision mechanism combines declared image appearance and
motion pattern and selects one analysis recipe card. The chosen measurement plane exits ready for
processing.
```

### 2. Prepare estimation images and take logarithms

```text
An untouched multi-channel microscopy stack yields one channel and focal plane. A sequence shows
gentle smoothing, optional spatial downscaling, low-background subtraction, invalid bright and dim
pixels becoming a sparse mask, and nonlinear brightness compression into a log-intensity plane. A
protected copy of the untouched stack stays visibly separate and unchanged. Do not show the filtered
image as final output.
```

### 3. Set the search range and build pyramids

```text
Two displaced microscopy frames enter a preliminary wide search that measures the largest plausible
translation. Each becomes a four-level pyramid of progressively smaller, blurrier images. A broad
orange alignment arrow finds the coarse position at the smallest level, then successive larger levels
refine it to a precise full-resolution target. No axes or plotted charts.
```

### 4. Plan the frame-pair graph

```text
A horizontal filmstrip of eight microscopy frames is connected by short neighboring links, medium
links that skip frames and a few long pale links. The fixed links feed parallel worker lanes and return
to the same ordered slots. Emphasize redundant routes without numerical labels and avoid neural-network
styling.
```

### 5. Separate global gain from movement

```text
Two frames contain the same cell pattern but the second is shifted and globally brighter. A single
large dimmer control equalizes overall intensity without changing spatial positions. Local cyan edges
remain misaligned and an orange translation brings them into register. One genuinely changed magenta
cell remains as a local residual. Do not imply that output brightness is corrected.
```

### 6. Solve the log-ratio fit robustly

```text
A misaligned microscopy edge field produces many small consistent cyan translation-vote arrows and
only three large magenta outlier arrows from local biological change. A physical-looking robust
weighting gate passes the cyan votes at full strength while the magenta arrows emerge shortened and
pale. Three nested image tiles move through coarse pixel-sized steps followed by one fine subpixel
orange adjustment. No plotted curve, chart, gauge, axis or data-like grid.
```

### 7. Alternatively fit normalized area correlation

```text
Two overlapping microscopy windows have different mean brightness and contrast. Each passes through a
centering-and-scaling mechanism, then the normalized windows slide across one another. A soft circular
similarity field has one bright peak without axes; a short orange Newton step reaches it, with a pale
grid-search safety net beneath. Do not show robust pixel weights or a log transform.
```

### 8. Select informative pixels and refit

```text
A filmstrip first follows a pale pilot alignment path. A separate scoring copy becomes a local texture
map where corner-like two-directional structure glows cyan and flat background or single straight edges
fade pale gray. A moving perforated mask is transported across frames. The orange final fit uses those
locations on the original untouched raw intensities, not on the scoring copy.
```

### 9. Reconcile pairwise movements

```text
Seven frame thumbnails form a temporal network with short and long displacement arrows that disagree
slightly. They feed a transparent balancing mechanism made of connected springs. The anchored first
frame stays fixed while all other positions settle into one consistent spatial path. Show disagreement
being distributed rather than copying one edge.
```

### 10. Repair unsupported frames and assemble diagnostics

```text
A microscopy frame sequence follows a smooth path. One card is broken and translucent because no valid
pair supports it; another is implausibly far away. Both are flagged and replaced on the final path by
dashed interpolation between valid neighbors. Beside the repaired sequence, compact non-chart tokens
show a dimmer, before-and-after overlap, a filled circular support gauge and a warning badge. Repaired
positions must differ visibly from direct measurements, and gain must not appear applied to output.
```

### 11. Warp the untouched stack and crop the common field

```text
An untouched multi-channel and multi-focal-plane hyperstack enters. Time-specific orange translation
arrows apply the same motion transform to every channel and focal plane. Displaced cards are
inverse-warped into a stationary stack. Pale invalid border wedges are excluded by one orange common
crop rectangle. The registered hyperstack exits with original colors and textures intact inside the
crop; show no filtering or brightness correction.
```
