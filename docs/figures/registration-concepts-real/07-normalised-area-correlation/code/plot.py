"""Concept 7 - the alternative normalised area-correlation fit."""
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
    ACTIVE, CHANGE, DATA, HEIGHT, IDLE, INK, active_map, canvas, flow, frame, link,
    node, plane_summary, stretch, table_row, tile,
)

data = np.load(DER / "concept07.npz")
meta = json.loads((DER / "concept07.json").read_text(encoding="utf-8"))

MIDDLE = HEIGHT / 2
offsets = data["offsets"]
surface = data["surface"]

fig, ax = canvas()

# --- the two windows, as measured and after centring and scaling ------------
TILE, VGAP = 226.0, 24.0
block = 2 * TILE + VGAP
X0 = 50.0
Y0 = MIDDLE + block / 2
window_a = data["window_a"]
window_b = data["window_b"]
# one shared scale, so any difference in level or contrast between them is visible
both = np.concatenate([window_a[np.isfinite(window_a)].ravel(),
                       window_b[np.isfinite(window_b)].ravel()])
raw_low, raw_high = np.percentile(both, [1.0, 99.5])
tile(ax, window_a, X0, Y0, TILE, vmin=raw_low, vmax=raw_high, border=INK, lw=2.4, zorder=3)
tile(ax, window_b, X0, Y0 - TILE - VGAP, TILE, vmin=raw_low, vmax=raw_high, border=INK,
     lw=2.4, zorder=3)


def normalise(plane):
    values = np.asarray(plane, dtype=float)
    finite = np.isfinite(values)
    centred = values - np.mean(values[finite])
    energy = np.sqrt(np.sum(centred[finite] ** 2))
    return centred / (energy if energy else 1.0)


col2 = X0 + TILE + 74.0
norm_a, norm_b = normalise(window_a), normalise(window_b)
limit = np.nanpercentile(np.abs(np.concatenate([norm_a.ravel(), norm_b.ravel()])), 99.0)
tile(ax, norm_a, col2, Y0, TILE, cmap="gray", vmin=-limit, vmax=limit, border=ACTIVE,
     lw=3.2, zorder=3)
tile(ax, norm_b, col2, Y0 - TILE - VGAP, TILE, cmap="gray", vmin=-limit, vmax=limit,
     border=ACTIVE, lw=3.2, zorder=3)
for row in (0, 1):
    y = MIDDLE + (TILE + VGAP) / 2 - row * (TILE + VGAP)
    flow(ax, (X0 + TILE + 12, y), (col2 - 12, y), lw=4.0)

# --- the similarity field those normalised windows produce ------------------
FIELD = 430.0
field_x = col2 + TILE + 84.0
field_y = MIDDLE + FIELD / 2
tile(ax, surface, field_x, field_y, FIELD, cmap=active_map(), border=INK, lw=2.4,
     zorder=3)
step = FIELD / surface.shape[0]
levels = np.nanpercentile(surface, [70, 85, 94, 98])
grid_x = field_x + (np.arange(surface.shape[1]) + 0.5) * step
grid_y = field_y - (np.arange(surface.shape[0]) + 0.5) * step
ax.contour(grid_x, grid_y, surface, levels=levels, colors=["white"], linewidths=1.4,
           alpha=0.55, zorder=4)
flow(ax, (col2 + TILE + 14, MIDDLE), (field_x - 14, MIDDLE), lw=4.2)


def to_canvas(dx, dy):
    ix = (dx - offsets[0]) / (offsets[-1] - offsets[0]) * (FIELD - step) + step / 2
    iy = (dy - offsets[0]) / (offsets[-1] - offsets[0]) * (FIELD - step) + step / 2
    return field_x + ix, field_y - iy


peak = np.unravel_index(np.nanargmax(surface), surface.shape)
peak_xy = to_canvas(offsets[peak[1]], offsets[peak[0]])
node(ax, peak_xy, 10.0, color="white", facecolor="none", lw=2.6, zorder=6)
start_xy = to_canvas(round(offsets[peak[1]]), round(offsets[peak[0]]))
fit_xy = to_canvas(meta["dx"], meta["dy"])
node(ax, start_xy, 9.0, color=IDLE, facecolor="white", lw=2.4, zorder=6)
flow(ax, start_xy, fit_xy, lw=4.4, head=22.0, zorder=7)

# --- one slice through the peak, so the subpixel step is visible ------------
SLICE_W, SLICE_H = 400.0, 250.0
slice_x = field_x + FIELD + 74.0
slice_y = MIDDLE + SLICE_H / 2
frame(ax, slice_x, slice_y, SLICE_W, SLICE_H, color=IDLE, lw=1.8, zorder=2)
line = surface[peak[0]]
finite = np.isfinite(line)
lo, hi = np.nanmin(line), np.nanmax(line)
px = slice_x + SLICE_W * (offsets - offsets[0]) / (offsets[-1] - offsets[0])
py = slice_y - SLICE_H + 0.86 * SLICE_H * (line - lo) / max(hi - lo, 1e-9) + 0.07 * SLICE_H
ax.plot(px[finite], py[finite], color=DATA, lw=4.0, zorder=4)
link(ax, (slice_x + 10, slice_y - SLICE_H), (slice_x + SLICE_W - 10, slice_y - SLICE_H),
     color=INK, lw=2.4, zorder=4)

fit_px = slice_x + SLICE_W * (meta["dx"] - offsets[0]) / (offsets[-1] - offsets[0])
link(ax, (fit_px, slice_y - SLICE_H), (fit_px, slice_y - 0.06 * SLICE_H), color=ACTIVE,
     lw=3.4, zorder=5)
grid_px = slice_x + SLICE_W * (round(meta["dx"]) - offsets[0]) / (offsets[-1] - offsets[0])
link(ax, (grid_px, slice_y - SLICE_H), (grid_px, slice_y - 0.28 * SLICE_H), color=IDLE,
     lw=3.0, dashed=True, zorder=5)
flow(ax, (field_x + FIELD + 12, MIDDLE), (slice_x - 12, MIDDLE), lw=4.2)

TABLE = (
    [
        table_row("pair", "from_frame", meta["pair"][0], "frame index", ""),
        table_row("pair", "to_frame", meta["pair"][1], "frame index", ""),
        table_row("surface", "span", meta["span"], "pixels", "half-width of the field"),
        table_row("surface", "step", meta["step"], "pixels", "sampling of the field"),
        table_row("surface", "peak_correlation", meta["peak_correlation"], "correlation",
                  "the hollow ring"),
        table_row("fit", "dx", meta["dx"], "pixels", "the orange mark"),
        table_row("fit", "dy", meta["dy"], "pixels", "the orange mark"),
        table_row("fit", "iterations", meta["iterations"], "count", meta["status"]),
        table_row("fit", "residual_before", meta["residual_before"], "log2", ""),
        table_row("fit", "residual_after", meta["residual_after"], "log2", ""),
        table_row("fit", "valid_fraction", meta["valid_fraction"], "fraction", ""),
    ]
    + plane_summary("surface", "correlation", surface, "every displacement tested")
    + [table_row("slice", f"{offsets[index]:+.2f}", float(line[index]), "correlation",
                 "one line through the peak")
       for index in range(offsets.size) if np.isfinite(line[index])]
)


write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "07-normalised-area-correlation.png", preview=False,
     claim="The alternative estimator removes each window's own level and contrast, then takes a subpixel Newton step to the peak of the correlation surface.",
     grammar='similarity surface with a refinement step',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/07-normalised-area-correlation.png")
