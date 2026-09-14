"""Concept 4 - plan the frame-pair graph."""
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
    ACTIVE, DATA, HEIGHT, IDLE, INK, SECOND, canvas, frame, link, node, stretch,
    table_row, tile,
)

data = np.load(DER / "concept04.npz")
meta = json.loads((DER / "concept04.json").read_text(encoding="utf-8"))

LAGS = meta["lags"]
LAG_COLOUR = {1: DATA, 2: SECOND, 4: INK, 8: IDLE, 16: IDLE}
LAG_WIDTH = {1: 3.4, 2: 3.0, 4: 2.6, 8: 2.2, 16: 1.8}
LAG_ALPHA = {1: 1.0, 2: 1.0, 4: 0.85, 8: 0.75, 16: 0.55}
MIDDLE = HEIGHT / 2

fig, ax = canvas()

# --- the real window of frames, in order ------------------------------------
COUNT = meta["window"]
TILE, GAP = 68.0, 5.0
X0 = 44.0
STRIP_Y = MIDDLE + TILE / 2
centres = []
for index in range(COUNT):
    x = X0 + index * (TILE + GAP)
    tile(ax, stretch(data["thumbnails"][index]), x, STRIP_Y, TILE, border=INK, lw=1.6,
         zorder=4)
    centres.append(x + TILE / 2)
strip_bottom = STRIP_Y - TILE

# --- every planned comparison inside that window ----------------------------
for source, target in data["window_pairs"]:
    lag = int(target - source)
    above = lag <= 4
    x_from, x_to = centres[int(source)], centres[int(target)]
    y = STRIP_Y + 2 if above else strip_bottom - 2
    rad = {1: -0.95, 2: -0.62, 4: -0.42, 8: 0.40, 16: 0.30}[lag]
    link(ax, (x_from, y), (x_to, y), color=LAG_COLOUR[lag], lw=LAG_WIDTH[lag],
         rad=rad, zorder=2, alpha=LAG_ALPHA[lag])
for x in centres:
    node(ax, (x, STRIP_Y + 2), 5.0, color=INK, zorder=5)
    node(ax, (x, strip_bottom - 2), 5.0, color=INK, zorder=5)

# --- the same plan for the whole recording ----------------------------------
FRAMES = meta["frames"]
grid = np.ones((FRAMES, FRAMES, 3), dtype=float)
palette = {1: DATA, 2: SECOND, 4: INK, 8: IDLE, 16: IDLE}
from matplotlib.colors import to_rgb  # noqa: E402

for source, target in data["plan"]:
    lag = int(target - source)
    grid[int(source), int(target)] = to_rgb(palette[lag])
    grid[int(target), int(source)] = to_rgb(palette[lag])

MAP = 396.0
map_x = 1344.0
map_y = MIDDLE + MAP / 2
tile(ax, grid, map_x, map_y, MAP, border=INK, lw=2.4, zorder=4)
window_span = MAP * COUNT / FRAMES
frame(ax, map_x, map_y, window_span, window_span, color=ACTIVE, lw=3.4, zorder=5)
link(ax, (X0 + (COUNT - 1) * (TILE + GAP) + TILE + 16, MIDDLE), (map_x - 12, map_y - window_span / 2),
     color=ACTIVE, lw=2.8, rad=-0.05, zorder=3, dashed=True)

TABLE = (
    [
        table_row("plan", "frames", meta["frames"], "count", "timepoints in the recording"),
        table_row("plan", "pairs_total", meta["pairs_total"], "count",
                  "every planned comparison, drawn in the adjacency map"),
        table_row("plan", "window", meta["window"], "frames",
                  "the filmstrip, marked on the adjacency map"),
        table_row("plan", "window_pairs", meta["window_pairs"], "count",
                  "comparisons drawn as arcs"),
    ]
    + [table_row("lag", str(lag), meta["pairs_per_lag"][str(lag)], "pairs",
                 "one diagonal of the adjacency map")
       for lag in LAGS]
    + [table_row("edge", f"{int(source)}-{int(target)}", int(target - source), "lag",
                 "drawn as an arc")
       for source, target in data["window_pairs"]]
    + [table_row("planned_pair", f"{int(source)}-{int(target)}", int(target - source),
                 "lag", "drawn in the adjacency map")
       for source, target in data["plan"]]
)

write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "04-plan-frame-pair-graph.png", preview=False,
     claim='The comparison set is fixed before any pixel is read: every frame pair at one of the configured lags, giving several independent routes between any two timepoints.',
     grammar='graph over a filmstrip',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/04-plan-frame-pair-graph.png")
