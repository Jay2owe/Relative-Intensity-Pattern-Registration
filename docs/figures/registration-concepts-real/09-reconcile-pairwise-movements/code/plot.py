"""Concept 9 - reconcile the pairwise movements into one trajectory."""
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
    ACTIVE, CHANGE, DATA, HEIGHT, IDLE, INK, SECOND, canvas, frame, link, node,
    table_row,
)

run = json.loads((DER / "registration_1424.json").read_text(encoding="utf-8"))
trajectory = np.load(DER / "registration_1424.npz")

dx = trajectory["dx"]
dy = trajectory["dy"]
frames = run["frames"]
pairs = [p for p in run["pairs"] if p["usable"]]
MIDDLE = HEIGHT / 2

fig, ax = canvas()

PANEL_W, PANEL_H, PANEL_GAP = 1090.0, 300.0, 60.0
PANEL_X = 60.0
TOP = MIDDLE + (2 * PANEL_H + PANEL_GAP) / 2

LAG_COLOUR = {1: DATA, 2: DATA, 4: SECOND, 8: IDLE, 16: IDLE}
LAG_ALPHA = {1: 0.55, 2: 0.45, 4: 0.4, 8: 0.35, 16: 0.3}


def panel(values, edge_component, panel_top):
    """One axis: every measured edge, then the trajectory they were reconciled into."""
    everything = [values.min(), values.max()]
    for pair in pairs:
        everything.append(values[pair["from"]] + pair[edge_component])
    low, high = min(everything), max(everything)
    pad = 0.08 * max(high - low, 1e-6)
    low, high = low - pad, high + pad

    def to_y(value):
        return panel_top - PANEL_H + PANEL_H * (value - low) / (high - low)

    def to_x(index):
        return PANEL_X + PANEL_W * index / max(frames - 1, 1)

    frame(ax, PANEL_X, panel_top, PANEL_W, PANEL_H, color=IDLE, lw=1.6, zorder=1)
    zero = to_y(0.0)
    if panel_top - PANEL_H <= zero <= panel_top:
        link(ax, (PANEL_X, zero), (PANEL_X + PANEL_W, zero), color=IDLE, lw=1.6,
             dashed=True, zorder=2)

    for pair in pairs:
        source, target = pair["from"], pair["to"]
        implied = values[source] + pair[edge_component]
        link(ax, (to_x(source), to_y(values[source])), (to_x(target), to_y(implied)),
             color=LAG_COLOUR[pair["lag"]], lw=1.4, zorder=3,
             alpha=LAG_ALPHA[pair["lag"]])

    ax.plot([to_x(index) for index in range(frames)], [to_y(value) for value in values],
            color=ACTIVE, lw=3.4, zorder=5, solid_capstyle="round")
    node(ax, (to_x(0), to_y(values[0])), 11.0, color=INK, facecolor="white", lw=3.0,
         zorder=6)
    link(ax, (PANEL_X, panel_top - PANEL_H), (PANEL_X, panel_top), color=INK, lw=2.6,
         zorder=4)
    link(ax, (PANEL_X, panel_top - PANEL_H), (PANEL_X + PANEL_W, panel_top - PANEL_H),
         color=INK, lw=2.6, zorder=4)


panel(dx, "dx", TOP)
panel(dy, "dy", TOP - PANEL_H - PANEL_GAP)

# --- how far each edge ended up from the trajectory it helped choose -------
HIST_W, HIST_H = 480.0, 380.0
hist_x = PANEL_X + PANEL_W + 90.0
hist_y = MIDDLE + HIST_H / 2
residuals = np.hypot(
    [p["graph_residual_dx"] for p in pairs], [p["graph_residual_dy"] for p in pairs]
)
residuals = residuals[np.isfinite(residuals)]
counts, edges = np.histogram(residuals, bins=42)
counts = counts / counts.max()
centres = (edges[:-1] + edges[1:]) / 2
px = hist_x + HIST_W * (centres - edges[0]) / (edges[-1] - edges[0])
py = hist_y - HIST_H + 0.9 * HIST_H * counts
ax.fill_between(px, hist_y - HIST_H, py, color=DATA, alpha=0.9, zorder=3, linewidth=0)
frame(ax, hist_x, hist_y, HIST_W, HIST_H, color=IDLE, lw=1.6, zorder=1)
link(ax, (hist_x, hist_y - HIST_H), (hist_x + HIST_W, hist_y - HIST_H), color=INK,
     lw=2.6, zorder=4)
link(ax, (hist_x, hist_y - HIST_H), (hist_x, hist_y), color=INK, lw=2.6, zorder=4)
median = float(np.median(residuals))
median_x = hist_x + HIST_W * (median - edges[0]) / (edges[-1] - edges[0])
link(ax, (median_x, hist_y - HIST_H), (median_x, hist_y - 0.08 * HIST_H), color=ACTIVE,
     lw=3.4, zorder=5)

TABLE = (
    [
        table_row("recording", "frames", frames, "count", ""),
        table_row("recording", "usable_pairs", len(pairs), "count",
                  f"of {len(run['pairs'])} planned"),
        table_row("residual", "median", float(np.median(residuals)), "pixels",
                  "the orange mark on the histogram"),
        table_row("residual", "p95", float(np.percentile(residuals, 95)), "pixels", ""),
        table_row("residual", "maximum", float(residuals.max()), "pixels", ""),
    ]
    + [table_row("trajectory", f"frame_{index:03d}_dx", float(dx[index]), "pixels",
                 "the reconciled path")
       for index in range(frames)]
    + [table_row("trajectory", f"frame_{index:03d}_dy", float(dy[index]), "pixels",
                 "the reconciled path")
       for index in range(frames)]
    + [table_row("edge", f"{pair['from']}-{pair['to']}", pair["dx"], "pixels",
                 f"dy {pair['dy']:.6f}; lag {pair['lag']}; graph residual "
                 f"{pair['graph_residual_dx']:.6f}, {pair['graph_residual_dy']:.6f}")
       for pair in pairs]
)


write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "09-reconcile-pairwise-movements.png", preview=False,
     claim='Slightly inconsistent pairwise displacements are solved together into one trajectory with the first frame fixed, so no single edge sets the answer.',
     grammar='trajectory with constraint overlay',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/09-reconcile-pairwise-movements.png")
