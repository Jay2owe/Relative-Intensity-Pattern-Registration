"""Concept 2 - prepare the estimation images and take logarithms."""
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
    ACTIVE, CHANGE, DATA, HEIGHT, IDLE, INK, canvas, flow, frame, plane_summary,
    residual_map, stretch, symmetric_limits, table_row, tile,
)

data = np.load(DER / "concept02.npz")
meta = json.loads((DER / "concept02.json").read_text(encoding="utf-8"))

MIDDLE = HEIGHT / 2
TILE = 250.0
LOWER = 208.0
BLOCK = TILE + 74.0 + LOWER
TOP = MIDDLE + BLOCK / 2
LOWER_TOP = TOP - TILE - 74.0

fig, ax = canvas()

# --- the untouched stack, which the estimator only ever copies from ---------
SMALL, SGAP, PAD = 186.0, 14.0, 20.0
stack_block = 2 * SMALL + SGAP + 2 * PAD
SX = 52.0
SY = MIDDLE + stack_block / 2 - PAD
planes = [data["untouched"][0], data["raw"], data["untouched"][1], data["untouched"][2]]
for index, plane in enumerate(planes):
    column, row = index % 2, index // 2
    tile(ax, stretch(plane), SX + column * (SMALL + SGAP), SY - row * (SMALL + SGAP),
         SMALL, border=INK, lw=1.8, zorder=3)
stack_width = 2 * SMALL + SGAP
frame(ax, SX - PAD, SY + PAD, stack_width + 2 * PAD, stack_block, color=INK, lw=3.0,
      zorder=2)
stack_right = SX + stack_width + PAD

# --- one copy leaves, and everything after this point is estimation only ----
xs = [516.0, 828.0, 1140.0, 1452.0]
flow(ax, (stack_right + 16, TOP - TILE / 2), (xs[0] - 14, TOP - TILE / 2), lw=4.6)

log_value = np.where(data["log_valid"], data["log_value"], np.nan)
log_shown = stretch(log_value)
tile(ax, stretch(data["raw"]), xs[0], TOP, TILE, border=ACTIVE, lw=4.0, zorder=3)
tile(ax, stretch(data["prepared"]), xs[1], TOP, TILE, border=ACTIVE, lw=4.0, zorder=3)
tile(ax, log_shown, xs[2], TOP, TILE, border=ACTIVE, lw=4.0, zorder=3)

valid = data["banded_valid"]
tile(ax, np.where(valid, log_shown, np.nan), xs[3], TOP, TILE, border=ACTIVE,
     lw=4.0, zorder=3)
tile(ax, np.where(valid, np.nan, 1.0), xs[3], TOP, TILE, cmap="gray", border=None,
     vmin=0.0, vmax=1.6, zorder=4)

for left, right in zip(xs[:-1], xs[1:]):
    flow(ax, (left + TILE + 12, TOP - TILE / 2), (right - 12, TOP - TILE / 2), lw=4.2)

# --- what each step actually changed ----------------------------------------
def histogram(values, x, width, colour):
    finite = np.asarray(values, dtype=float)
    finite = finite[np.isfinite(finite)]
    counts, edges = np.histogram(finite, bins=110)
    counts = counts / counts.max()
    centres = (edges[:-1] + edges[1:]) / 2
    span = centres[-1] - centres[0]
    px = x + width * (centres - centres[0]) / (span if span else 1.0)
    py = LOWER_TOP - LOWER + LOWER * 0.92 * counts
    ax.fill_between(px, LOWER_TOP - LOWER, py, color=colour, alpha=0.9, zorder=3,
                    linewidth=0)
    ax.plot([x, x + width], [LOWER_TOP - LOWER] * 2, color=INK, lw=2.6, zorder=4,
            solid_capstyle="butt")
    ax.plot([x, x], [LOWER_TOP - LOWER, LOWER_TOP], color=INK, lw=2.6, zorder=4,
            solid_capstyle="butt")


histogram(data["raw"], xs[0], TILE, IDLE)

# the pixels the median filter moved, and by how much
removed = data["raw"] - data["prepared"]
low, high = symmetric_limits(removed, 96.0)
tile(ax, removed, xs[1] + (TILE - LOWER) / 2, LOWER_TOP, LOWER, cmap=residual_map(),
     vmin=low, vmax=high, border=IDLE, lw=1.8, zorder=3)

histogram(log_value, xs[2], TILE, DATA)

kept = meta["valid_banded"] / meta["pixels"]
frame(ax, xs[3], LOWER_TOP, TILE * kept, LOWER, color="none", facecolor=DATA,
      alpha=0.9, lw=0.0, zorder=3)
frame(ax, xs[3] + TILE * kept, LOWER_TOP, TILE * (1 - kept), LOWER, color="none",
      facecolor=IDLE, alpha=0.9, lw=0.0, zorder=3)
frame(ax, xs[3], LOWER_TOP, TILE, LOWER, color=INK, lw=2.6, zorder=4)

TABLE = (
    [
        table_row("setting", "preprocessing", None, meta["preprocessing"],
                  "the resolved recipe for this recording"),
        table_row("setting", "log_offset", meta["epsilon"], "epsilon",
                  "added before the base-2 logarithm"),
        table_row("band", "floor_percentile", meta["band_floor_percentile"], "percent",
                  f"intensity {meta['band_floor_value']}"),
        table_row("band", "ceiling_percentile", meta["band_ceiling_percentile"], "percent",
                  f"intensity {meta['band_ceiling_value']}"),
        table_row("band", "valid_pixels", meta["valid_banded"], "count",
                  f"of {meta['pixels']}; the filled part of the final bar"),
        table_row("band", "kept_fraction", kept, "fraction", "drawn as the filled bar"),
        table_row("raw", "median", meta["raw_median"], "counts", ""),
        table_row("raw", "minimum", meta["raw_min"], "counts", ""),
        table_row("raw", "maximum", meta["raw_max"], "counts", ""),
        table_row("log", "minimum", meta["log_min"], "log2 counts", ""),
        table_row("log", "maximum", meta["log_max"], "log2 counts", ""),
    ]
    + plane_summary("tile", "raw", data["raw"], "estimation copy, as measured")
    + plane_summary("tile", "prepared", data["prepared"], "after the median filter")
    + plane_summary("tile", "log", log_value, "log2(intensity + 1)")
    + plane_summary("map", "removed_by_median", removed,
                    "raw minus prepared, the difference map drawn beneath the chain")
)

for name, values in (("raw", data["raw"]), ("log", log_value)):
    finite = np.asarray(values, dtype=float)
    finite = finite[np.isfinite(finite)]
    counts, edges = np.histogram(finite, bins=110)
    for index, count in enumerate(counts):
        TABLE.append(table_row(f"histogram_{name}", f"bin_{index:03d}", count, "pixels",
                               f"{edges[index]:.6f} to {edges[index + 1]:.6f}"))

write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "02-prepare-and-log.png", preview=False,
     claim='Filtering, the logarithm and the validity band act only on a copy, and the logarithm changes the intensity distribution rather than the picture.',
     grammar='process chain with distributions',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/02-prepare-and-log.png")
