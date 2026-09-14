"""Concept 6 - solve the log-ratio fit robustly."""
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
    ACTIVE, CHANGE, DATA, HEIGHT, IDLE, INK, canvas, flow, frame, information_map,
    link, node, plane_summary, residual_map, symmetric_limits, table_row, tile,
)

data = np.load(DER / "concept06.npz")
meta = json.loads((DER / "concept06.json").read_text(encoding="utf-8"))

MIDDLE = HEIGHT / 2
SCALE = meta["robust_scale"]
K = meta["huber_k"]

fig, ax = canvas()

# --- the residual field the solver actually sees ----------------------------
TILE = 320.0
top = MIDDLE + TILE / 2
low, high = symmetric_limits(data["residual"], 92.0)
tile(ax, data["residual"], 52.0, top, TILE, cmap=residual_map(), vmin=low, vmax=high,
     border=INK, lw=2.4, zorder=3)

# --- the weight each of those residuals is given ----------------------------
weight_x = 52.0 + TILE + 66.0
tile(ax, data["weight"], weight_x, top, TILE, cmap=information_map(), vmin=0.0,
     vmax=1.0, border=INK, lw=2.4, zorder=3)
flow(ax, (52.0 + TILE + 12, MIDDLE), (weight_x - 12, MIDDLE), lw=4.2)

# --- the gate itself, at the robust scale this pair produced ----------------
GATE_W, GATE_H = 400.0, 320.0
gate_x = weight_x + TILE + 74.0
gate_y = MIDDLE + GATE_H / 2
frame(ax, gate_x, gate_y, GATE_W, GATE_H, color=IDLE, lw=1.8, zorder=2)
flow(ax, (weight_x + TILE + 12, MIDDLE), (gate_x - 12, MIDDLE), lw=4.2)

span = 5.0 * SCALE
values = np.linspace(-span, span, 601)
weights = np.minimum(1.0, K * SCALE / np.maximum(np.abs(values), 1e-12))
gx = gate_x + GATE_W * (values + span) / (2 * span)
gy = gate_y - GATE_H + 0.62 * GATE_H * weights + 0.20 * GATE_H
ax.plot(gx, gy, color=ACTIVE, lw=5.0, zorder=5, solid_capstyle="round")
for sign in (-1, 1):
    x_knee = gate_x + GATE_W * (sign * K * SCALE + span) / (2 * span)
    link(ax, (x_knee, gate_y - GATE_H + 0.14 * GATE_H), (x_knee, gate_y - 0.06 * GATE_H),
         color=CHANGE, lw=2.4, dashed=True, zorder=4)

sample = data["sample_residual"]
counts, edges = np.histogram(sample, bins=140, range=(-span, span))
counts = counts / counts.max()
centres = (edges[:-1] + edges[1:]) / 2
hx = gate_x + GATE_W * (centres + span) / (2 * span)
hy = gate_y - GATE_H + 0.16 * GATE_H * counts
ax.fill_between(hx, gate_y - GATE_H, hy, color=DATA, alpha=0.85, zorder=3, linewidth=0)
link(ax, (gate_x + 8, gate_y - GATE_H), (gate_x + GATE_W - 8, gate_y - GATE_H),
     color=INK, lw=2.4, zorder=4)

# --- the direction each surviving pixel votes for ---------------------------
VOTE = 380.0
vote_x = gate_x + GATE_W + 74.0
vote_y = MIDDLE + VOTE / 2
frame(ax, vote_x, vote_y, VOTE, VOTE, color=INK, lw=2.4, zorder=2)

step = max(1, data["vote_x"].shape[1] // 20)
vx = data["vote_x"][::step, ::step]
vy = data["vote_y"][::step, ::step]
wt = data["weight"][::step, ::step]
rows, columns = vx.shape
cell = VOTE / max(rows, columns)
limit = np.nanpercentile(np.hypot(vx, vy), 90)
limit = limit if limit > 0 else 1.0
for row in range(rows):
    for column in range(columns):
        if not np.isfinite(vx[row, column]) or not np.isfinite(wt[row, column]):
            continue
        x = vote_x + (column + 0.5) * cell
        y = vote_y - (row + 0.5) * cell
        dx = np.clip(vx[row, column] / limit, -1.6, 1.6) * cell * 0.62
        dy = np.clip(vy[row, column] / limit, -1.6, 1.6) * cell * 0.62
        heavy = wt[row, column] > 0.999
        link(ax, (x, y), (x + dx, y - dy), color=DATA if heavy else CHANGE,
             lw=2.4 if heavy else 1.6, zorder=4, alpha=1.0 if heavy else 0.7)
flow(ax, (gate_x + GATE_W + 12, MIDDLE), (vote_x - 12, MIDDLE), lw=4.2)

# the answer those votes agree on, at a stated exaggeration of the panel's own scale
EXAGGERATION = 56.0
unit = VOTE / float(meta["frame_width"]) * EXAGGERATION
node(ax, (vote_x + VOTE / 2, vote_y - VOTE / 2), 10.0, color=INK, facecolor="white",
     lw=2.4, zorder=6)
flow(ax, (vote_x + VOTE / 2, vote_y - VOTE / 2),
     (vote_x + VOTE / 2 + meta["dx"] * unit, vote_y - VOTE / 2 - meta["dy"] * unit),
     lw=5.0, head=26.0, zorder=7)

TABLE = (
    [
        table_row("pair", "from_frame", meta["pair"][0], "frame index", ""),
        table_row("pair", "to_frame", meta["pair"][1], "frame index", ""),
        table_row("gate", "norm", None, meta["norm"], "the weighting the recipe resolved"),
        table_row("gate", "tuning_constant", meta["huber_k"], "sigma",
                  "the knee, marked dashed on the curve"),
        table_row("gate", "robust_scale", meta["robust_scale"], "log2",
                  "median absolute deviation scale for this pair"),
        table_row("gate", "bounded_pixels", meta["weighted_below_one"], "count",
                  f"of {meta['sampled_pixels']} sampled"),
        table_row("fit", "dx", meta["dx"], "pixels", ""),
        table_row("fit", "dy", meta["dy"], "pixels", ""),
        table_row("fit", "iterations", meta["iterations"], "count", meta["status"]),
        table_row("fit", "residual_before", meta["residual_before"], "log2", ""),
        table_row("fit", "residual_after", meta["residual_after"], "log2", ""),
        table_row("fit", "arrow_exaggeration", EXAGGERATION, "times",
                  "the only exaggerated element in this figure"),
    ]
    + plane_summary("map", "residual", data["residual"], "")
    + plane_summary("map", "weight", data["weight"], "")
    + plane_summary("map", "vote_x", data["vote_x"], "")
    + plane_summary("map", "vote_y", data["vote_y"], "")
)

for _row in range(vx.shape[0]):
    for _column in range(vx.shape[1]):
        if not np.isfinite(vx[_row, _column]):
            continue
        TABLE.append(table_row(
            "vote", f"{_row:02d}_{_column:02d}", float(vx[_row, _column]), "pixels",
            f"dy {float(vy[_row, _column]):.6f}; weight {float(wt[_row, _column]):.6f}"))


write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "06-robust-log-ratio-solve.png", preview=False,
     claim='Most pixels agree about the displacement at full weight while the few large residuals are bounded, so local change cannot drag the fit.',
     grammar='weighted residual field with an influence gate',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/06-robust-log-ratio-solve.png")
