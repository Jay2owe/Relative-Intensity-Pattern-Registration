"""Concept 5 - separate the global gain from the movement."""
import json
import sys
from pathlib import Path

import numpy as np

CODE = Path(__file__).resolve().parent
BUNDLE = CODE.parent
DER = BUNDLE / "data/der"
FIG = BUNDLE / "fig"
sys.path.insert(0, str(CODE))
from concept_style import TABLE_COLUMNS, save, write_table  # noqa: E402
from concept_style import (  # noqa: E402
    ACTIVE, CHANGE, DATA, HEIGHT, IDLE, INK, SECOND, canvas, crop, flow, frame, link,
    node, plane_summary, residual_map, stretch, symmetric_limits, table_row, tile,
)

data = np.load(DER / "concept05.npz")
meta = json.loads((DER / "concept05.json").read_text(encoding="utf-8"))

MIDDLE = HEIGHT / 2
RESIDUAL = residual_map()

fig, ax = canvas()

# --- the two real frames the pair estimator was given -----------------------
TILE, VGAP = 262.0, 26.0
block = 2 * TILE + VGAP
X0 = 52.0
Y0 = MIDDLE + block / 2
tile(ax, stretch(data["frame_a"]), X0, Y0, TILE, border=INK, lw=2.4, zorder=3)
tile(ax, stretch(data["frame_b"]), X0, Y0 - TILE - VGAP, TILE, border=INK, lw=2.4,
     zorder=3)
link(ax, (X0 + TILE / 2, Y0 - TILE - 6), (X0 + TILE / 2, Y0 - TILE - VGAP + 6),
     color=IDLE, lw=2.0, zorder=3)

# --- their log difference, before and after one whole-frame dimmer ----------
difference = data["difference_zero"]
residual = data["residual_zero"]
low, high = symmetric_limits(np.concatenate([
    difference[np.isfinite(difference)], residual[np.isfinite(residual)]]), 92.0)

col2 = X0 + TILE + 74.0
tile(ax, difference, col2, Y0, TILE, cmap=RESIDUAL, vmin=low, vmax=high, border=INK,
     lw=2.4, zorder=3)
tile(ax, residual, col2, Y0 - TILE - VGAP, TILE, cmap=RESIDUAL, vmin=low, vmax=high,
     border=INK, lw=2.4, zorder=3)
flow(ax, (X0 + TILE + 14, MIDDLE), (col2 - 14, MIDDLE), lw=4.2)

# the one number removed between them, drawn against the residual scale it sits on
gain_x = col2 + TILE / 2
gain_top = Y0 - TILE - 6
gain_span = VGAP - 12
offset = meta["gain_log2_at_zero"]
reach = (TILE / 2) * abs(offset) / max(abs(high), 1e-9)
link(ax, (col2, gain_top - gain_span / 2), (col2 + TILE, gain_top - gain_span / 2),
     color=IDLE, lw=1.8, zorder=4)
ax.plot([gain_x, gain_x + np.sign(offset) * max(reach, 8.0)],
        [gain_top - gain_span / 2] * 2, color=ACTIVE, lw=6.0, zorder=5,
        solid_capstyle="butt")
node(ax, (gain_x, gain_top - gain_span / 2), 7.0, color=INK, zorder=6)

# --- and the same pair once the fitted translation is applied ---------------
col3 = col2 + TILE + 74.0
tile(ax, data["difference_fit"], col3, Y0, TILE, cmap=RESIDUAL, vmin=low, vmax=high,
     border=INK, lw=2.4, zorder=3)
tile(ax, data["residual_fit"], col3, Y0 - TILE - VGAP, TILE, cmap=RESIDUAL, vmin=low,
     vmax=high, border=ACTIVE, lw=3.6, zorder=3)
flow(ax, (col2 + TILE + 14, MIDDLE), (col3 - 14, MIDDLE), lw=4.2)

# --- what the fitted translation was, on the real pixels --------------------
ZOOM = 404.0
zoom_x = col3 + TILE + 70.0
zoom_y = MIDDLE + ZOOM / 2
size = 96
# centre the zoom where the frame actually has structure to align on
from scipy import ndimage  # noqa: E402

texture = ndimage.uniform_filter(
    np.abs(ndimage.sobel(data["frame_a"], 0)) + np.abs(ndimage.sobel(data["frame_a"], 1)),
    size=size,
)
margin = size // 2 + 8
inner = texture[margin:-margin, margin:-margin]
cy, cx = np.unravel_index(int(np.argmax(inner)), inner.shape)
cy, cx = int(cy) + margin, int(cx) + margin
patch_a = crop(data["frame_a"], cx, cy, size)
patch_b = crop(data["frame_b"], cx, cy, size)

composite = np.zeros((size, size, 3))
composite[..., 0] = stretch(patch_b)
composite[..., 1] = stretch(patch_a)
composite[..., 2] = stretch(patch_a)
tile(ax, np.clip(composite, 0, 1), zoom_x, zoom_y, ZOOM, border=INK, lw=2.4, zorder=3,
     interpolation="nearest")

# the measured displacement, drawn to the scale of that zoom
pixel = ZOOM / size
arrow_from = (zoom_x + ZOOM / 2, zoom_y - ZOOM / 2)
arrow_to = (arrow_from[0] + meta["dx"] * pixel * 6.0,
            arrow_from[1] - meta["dy"] * pixel * 6.0)   # image rows run downward
flow(ax, arrow_from, arrow_to, lw=5.0, head=18.0, zorder=6)
node(ax, arrow_from, 8.0, color=INK, facecolor="white", lw=2.4, zorder=6)

TABLE = (
    [
        table_row("pair", "from_frame", meta["pair"][0], "frame index", ""),
        table_row("pair", "to_frame", meta["pair"][1], "frame index", ""),
        table_row("gain", "log2_at_zero", meta["gain_log2_at_zero"], "log2",
                  "the offset removed between the two left maps"),
        table_row("gain", "linear_at_zero", meta["gain_at_zero"], "ratio", ""),
        table_row("gain", "log2_at_fit", meta["gain_log2_at_fit"], "log2",
                  "profiled again at the fitted displacement"),
        table_row("gain", "linear_at_fit", meta["gain_at_fit"], "ratio", ""),
        table_row("residual", "mean_abs_difference_zero",
                  meta["mean_abs_difference_zero"], "log2",
                  "before the offset is removed"),
        table_row("residual", "mean_abs_at_zero", meta["mean_abs_residual_zero"], "log2",
                  "after the offset, before the displacement"),
        table_row("residual", "mean_abs_at_fit", meta["mean_abs_residual_fit"], "log2",
                  "after both"),
        table_row("fit", "dx", meta["dx"], "pixels", "arrow drawn at 6x its own size"),
        table_row("fit", "dy", meta["dy"], "pixels", "arrow drawn at 6x its own size"),
        table_row("fit", "valid_fraction", meta["valid_fraction"], "fraction", ""),
        table_row("fit", "iterations", meta["iterations"], "count", meta["status"]),
        table_row("fit", "sampled_pixels", meta["sampled_pixels"], "count", ""),
        table_row("fit", "arrow_exaggeration", 6.0, "times",
                  "the only exaggerated element in this figure"),
    ]
    + plane_summary("map", "difference_zero", data["difference_zero"], "")
    + plane_summary("map", "residual_zero", data["residual_zero"], "")
    + plane_summary("map", "difference_fit", data["difference_fit"], "")
    + plane_summary("map", "residual_fit", data["residual_fit"], "")
)


write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "05-separate-gain-from-movement.png", preview=False,
     claim='One frame-wide number absorbs the brightness change between two frames, and what remains is the spatial disagreement that locates the movement.',
     grammar='residual maps with a scalar offset',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/05-separate-gain-from-movement.png")
