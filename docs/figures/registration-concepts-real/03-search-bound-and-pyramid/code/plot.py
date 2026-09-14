"""Concept 3 - set the search range and build coarse-to-fine pyramids."""
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

data = np.load(DER / "concept03.npz")
meta = json.loads((DER / "concept03.json").read_text(encoding="utf-8"))

LEVELS = meta["levels"]
MIDDLE = HEIGHT / 2

fig, ax = canvas()

# --- the coarse pass that sets how far the search may look ------------------
COST = 344.0
TRACE_W, TRACE_H = 344.0, 224.0
left_block = COST + 48.0 + TRACE_H
cost_x = 58.0
cost_y = MIDDLE + left_block / 2

surface = data["coarse_cost"]
tile(ax, surface, cost_x, cost_y, COST, cmap=active_map().reversed(), border=INK,
     lw=2.4, zorder=3)

scale = 1 << (LEVELS - 1)
step = COST / surface.shape[0]
centre = (cost_x + COST / 2, cost_y - COST / 2)
circle = np.linspace(0, 2 * np.pi, 240)

# the bound the engine kept, and the largest displacement it actually measured
bound = meta["max_shift"] / scale * step
ax.plot(centre[0] + bound * np.cos(circle), centre[1] + bound * np.sin(circle),
        color=CHANGE, lw=3.0, ls=(0, (7, 6)), zorder=5)
measured = meta["max_shift_largest_measured"] / scale * step
ax.plot(centre[0] + measured * np.cos(circle), centre[1] + measured * np.sin(circle),
        color=DATA, lw=3.4, zorder=7)

flat = np.where(np.isfinite(surface), surface, np.inf)
best = np.unravel_index(np.argmin(flat), flat.shape)
node(ax, (cost_x + (best[1] + 0.5) * step, cost_y - (best[0] + 0.5) * step), 7.0,
     color=ACTIVE, facecolor=ACTIVE, lw=2.0, zorder=6)

# --- where each level actually put the pair ---------------------------------
path = meta["path"]
trace_y = cost_y - COST - 48.0
dxs = [point["dx"] for point in path]
dys = [point["dy"] for point in path]
span = max(1e-3, max(max(dxs) - min(dxs), max(dys) - min(dys)))
cx = cost_x + TRACE_W / 2
cy = trace_y - TRACE_H / 2
scale_px = 0.44 * min(TRACE_W, TRACE_H) / span
px = [cx + (value - dxs[-1]) * scale_px for value in dxs]
py = [cy - (value - dys[-1]) * scale_px for value in dys]
frame(ax, cost_x, trace_y, TRACE_W, TRACE_H, color=IDLE, lw=1.8, zorder=2)
link(ax, (cost_x + 16, cy), (cost_x + TRACE_W - 16, cy), color=IDLE, lw=1.6, zorder=3)
link(ax, (cx, trace_y - 16), (cx, trace_y - TRACE_H + 16), color=IDLE, lw=1.6, zorder=3)
for start in range(len(px) - 1):
    flow(ax, (px[start], py[start]), (px[start + 1], py[start + 1]), lw=2.8, head=13.0,
         zorder=5)
for index, (x_value, y_value) in enumerate(zip(px, py)):
    node(ax, (x_value, y_value), 7.0, color=ACTIVE if index else INK,
         facecolor=ACTIVE if index == len(px) - 1 else "white", lw=2.4, zorder=6)

# --- the pyramid the refinement walks up ------------------------------------
sizes = [112.0, 188.0, 300.0, 440.0]
gap = 42.0
xs, x = [], 470.0
for size in sizes:
    xs.append(x)
    x += size + gap

for index, size in enumerate(sizes):
    level = LEVELS - 1 - index
    tile(ax, stretch(data[f"a{level}"]), xs[index], MIDDLE + size / 2, size,
         border=ACTIVE if index else INK, lw=3.6 if index else 2.4, zorder=4)
    if index:
        flow(ax, (xs[index - 1] + sizes[index - 1] + 10, MIDDLE),
             (xs[index] - 10, MIDDLE), lw=4.4, head=20.0)

flow(ax, (cost_x + COST + 18, MIDDLE + 108), (xs[0] - 12, MIDDLE), lw=4.4,
     connectionstyle="arc3,rad=-0.16")

TABLE = (
    [
        table_row("bound", "largest_measured", meta["max_shift_largest_measured"],
                  "pixels", "the teal circle; largest displacement the pilot pass found"),
        table_row("bound", "suggested", meta["max_shift_suggested"], "pixels",
                  "the dashed red circle; the bound the engine kept"),
        table_row("bound", "resolution", meta["max_shift_resolution"], "pixels",
                  "one coarse cell of the pilot pass"),
        table_row("bound", "probe_pairs", meta["shift_bound_probe_pairs"], "pairs",
                  "comparisons the pilot pass measured"),
        table_row("pyramid", "levels", meta["levels"], "count", ""),
        table_row("search", "coarse_radius", meta["coarse_radius"], "cells",
                  "half-width of the cost surface drawn on the left"),
    ]
    + [table_row("pyramid_level", str(index), size[0], "pixels",
                 f"level {index} is {size[0]} by {size[1]}")
       for index, size in enumerate(meta["level_sizes"])]
    + [table_row("refinement", f"level_{point['level']}_dx", point["dx"], "pixels",
                 "position after this level, in full-resolution pixels")
       for point in meta["path"]]
    + [table_row("refinement", f"level_{point['level']}_dy", point["dy"], "pixels",
                 "position after this level, in full-resolution pixels")
       for point in meta["path"]]
    + plane_summary("surface", "coarse_cost", data["coarse_cost"],
                    "mean absolute profiled residual at each integer displacement")
)

write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "03-search-bound-and-pyramid.png", preview=False,
     claim='A coarse pass measures the largest displacement present and the engine still keeps its public lower bound, then refines that bound coarse to fine.',
     grammar='cost surface with pyramid chain',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/03-search-bound-and-pyramid.png")
