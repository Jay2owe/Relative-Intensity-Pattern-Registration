"""Concept 8 - select informative pixels and refit on the raw intensities."""
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
    link, plane_summary, stretch, table_row, tile,
)

data = np.load(DER / "concept08.npz")
meta = json.loads((DER / "concept08.json").read_text(encoding="utf-8"))

MIDDLE = HEIGHT / 2

fig, ax = canvas()

TILE = 292.0
BAR_H = 30.0
BLOCK = TILE + 34.0 + BAR_H
TOP = MIDDLE + BLOCK / 2
xs = [46.0, 396.0, 746.0, 1096.0, 1478.0]

frame_plane = data["frame"]
information = data["information"]
mask = data["mask"]
transported = data["transported"]

# --- the plane the pilot registration already aligned ----------------------
tile(ax, stretch(frame_plane), xs[0], TOP, TILE, border=INK, lw=2.4, zorder=3)

# --- how much two-directional structure each pixel has --------------------
limit = float(np.nanpercentile(information, 99.5))
tile(ax, information, xs[1], TOP, TILE, cmap=information_map(), vmin=0.0, vmax=limit,
     border=INK, lw=2.4, zorder=3)

# --- the pixels that survive the gradient floor and the removal quantile ---
tile(ax, stretch(frame_plane), xs[2], TOP, TILE, border=INK, lw=2.4, zorder=3)
tile(ax, np.where(mask, np.nan, 1.0), xs[2], TOP, TILE, cmap="gray", vmin=0.0, vmax=1.7,
     border=None, zorder=4)

# --- and the same mask carried to the other frame by the pilot trajectory --
tile(ax, stretch(data["other_frame"]), xs[3], TOP, TILE, border=ACTIVE, lw=3.4, zorder=3)
tile(ax, np.where(transported, np.nan, 1.0), xs[3], TOP, TILE, cmap="gray", vmin=0.0,
     vmax=1.7, border=None, zorder=4)

for left, right in zip(xs[:3], xs[1:4]):
    flow(ax, (left + TILE + 12, MIDDLE + 20), (right - 12, MIDDLE + 20), lw=4.2)

# --- how much of the frame each stage kept --------------------------------
bar_y = TOP - TILE - 34.0
kept = meta["kept"] / meta["pixels"]
eligible = meta["eligible"] / meta["pixels"]
for x, fraction, colour in ((xs[2], eligible, IDLE), (xs[3], kept, DATA)):
    frame(ax, x, bar_y, TILE, BAR_H, color=INK, lw=2.2, zorder=4)
    frame(ax, x, bar_y, TILE * fraction, BAR_H, color="none", facecolor=colour,
          lw=0.0, zorder=3)

# --- the refit returns to the untouched intensities -----------------------
OUT = 292.0
out_x = xs[4]
tile(ax, stretch(data["other_frame"]), out_x, TOP, OUT, border=ACTIVE, lw=4.2, zorder=3)
flow(ax, (xs[3] + TILE + 14, MIDDLE + 20), (out_x - 14, MIDDLE + 20), lw=4.6)
link(ax, (xs[0] + TILE / 2, TOP + 16), (out_x + OUT / 2, TOP + 16), color=IDLE, lw=2.2,
     rad=-0.07, dashed=True, zorder=2)

TABLE = (
    [
        table_row("pair", "from_frame", meta["pair"][0], "frame index", ""),
        table_row("pair", "to_frame", meta["pair"][1], "frame index", ""),
        table_row("selection", "remove_percent", meta["remove_percent"], "percent",
                  "of the eligible pixels, lowest scoring first"),
        table_row("selection", "gradient_threshold", meta["gradient_threshold"],
                  "log2 per pixel", "half the median gradient magnitude"),
        table_row("selection", "eligible", meta["eligible"], "pixels",
                  f"of {meta['pixels']}; the grey bar"),
        table_row("selection", "kept", meta["kept"], "pixels", "the teal bar"),
        table_row("selection", "removed", meta["removed"], "pixels", ""),
        table_row("selection", "eligible_fraction", eligible, "fraction", ""),
        table_row("selection", "kept_fraction", kept, "fraction", ""),
        table_row("pilot", "dx", meta["pilot_dx"], "pixels",
                  "displacement the mask was carried by"),
        table_row("pilot", "dy", meta["pilot_dy"], "pixels", ""),
    ]
    + plane_summary("map", "information", information,
                    "smaller structure-tensor eigenvalue")
    + plane_summary("map", "gradient_magnitude", data["gradient_magnitude"], "")
    + plane_summary("tile", "frame", frame_plane, "the plane the pilot aligned")
)


write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "08-informative-pixel-refit.png", preview=False,
     claim='Scoring where intensity changes in two directions keeps the locations that can fix a position, and the refit then returns to the untouched intensities.',
     grammar='mask chain over a measured plane',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/08-informative-pixel-refit.png")
