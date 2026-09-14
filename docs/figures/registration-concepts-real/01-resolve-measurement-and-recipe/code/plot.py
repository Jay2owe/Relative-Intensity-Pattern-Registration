"""Concept 1 - resolve the measurement plane and the registration recipe."""
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
    ACTIVE, CHANGE, DATA, IDLE, INK, SECOND, HEIGHT, canvas, flow, frame, node,
    plane_summary, stretch, table_row, tile,
)

data = np.load(DER / "concept01.npz")
meta = json.loads((DER / "concept01.json").read_text(encoding="utf-8"))

CHANNELS = meta["channel_names"]
QUALITY = {q["name"]: q for q in meta["quality"]}
BF_INDEX = CHANNELS.index("BF")
IMAGE_TYPES = meta["image_types"]
MOTION_TYPES = meta["motion_types"]
CHOSEN = (IMAGE_TYPES.index(meta["declared_image_type"]),
          MOTION_TYPES.index(meta["declared_motion_type"]))
RECIPES = {(r["image_type"], r["motion_type"]): r for r in meta["recipes"]}
RESOLVED = RECIPES[(meta["declared_image_type"], meta["declared_motion_type"])]

NORM_FILL = {"Huber": DATA, "Tukey": SECOND, "Least Squares": IDLE}
MIDDLE = HEIGHT / 2


def top_of(height):
    """Top edge of a block of this height, centred on the canvas."""
    return MIDDLE + height / 2


def glyphs(x, y, size, recipe, chosen):
    """The three marks that distinguish one recipe cell from another."""
    if recipe["preprocessing"] != "None":
        node(ax, (x + size - 0.21 * size, y - 0.21 * size), 0.095 * size, color=INK,
             facecolor=INK if chosen else "white", lw=2.2, zorder=5)
    if recipe["pixel_support"] != "All":
        ax.plot([x + 0.16 * size, x + 0.84 * size], [y - size + 0.18 * size] * 2,
                color=INK, lw=3.6, zorder=5, alpha=1.0 if chosen else 0.55,
                solid_capstyle="butt")
    if recipe["pixel_selection"] != "None":
        node(ax, (x + 0.21 * size, y - 0.21 * size), 0.095 * size, color=CHANGE,
             facecolor="white", lw=2.6, zorder=5)


fig, ax = canvas()

# --- the untouched hyperstack: four real channels of one timepoint -----------
TILE, GAP = 232.0, 18.0
BAR_H, BAR_GAP = 22.0, 14.0
PAD = 18.0
ROW = TILE + BAR_GAP + BAR_H
block = 2 * ROW + GAP + 2 * PAD
X0 = 58.0
Y0 = top_of(block) - PAD
threshold = meta["warn_below"]
for index, name in enumerate(CHANNELS):
    column, row = index % 2, index // 2
    x = X0 + column * (TILE + GAP)
    y = Y0 - row * (ROW + GAP)
    chosen = index == BF_INDEX
    tile(ax, stretch(data["thumbnails"][index]), x, y, TILE,
         border=ACTIVE if chosen else INK, lw=5.0 if chosen else 1.8, zorder=3)
    bar_y = y - TILE - BAR_GAP
    frame(ax, x, bar_y, TILE, BAR_H, color=IDLE, lw=1.6, zorder=3)
    length = TILE * QUALITY[name]["localisability"] / threshold
    if length > 1.0:
        frame(ax, x, bar_y, length, BAR_H, color="none",
              facecolor=ACTIVE if chosen else IDLE, lw=0.0, zorder=4)

stack_width = 2 * TILE + GAP
frame(ax, X0 - PAD, Y0 + PAD, stack_width + 2 * PAD, block, color=IDLE, lw=1.8, zorder=1)
stack_right = X0 + stack_width

# --- the declared classes choose one cell of the real recipe matrix ---------
CELL, CELL_GAP = 122.0, 12.0
matrix_height = 5 * CELL + 4 * CELL_GAP
MX = 700.0
MY = top_of(matrix_height)
for row, image_type in enumerate(IMAGE_TYPES):
    for column, motion_type in enumerate(MOTION_TYPES):
        recipe = RECIPES[(image_type, motion_type)]
        x = MX + column * (CELL + CELL_GAP)
        y = MY - row * (CELL + CELL_GAP)
        chosen = (row, column) == CHOSEN
        frame(ax, x, y, CELL, CELL, color=ACTIVE if chosen else IDLE,
              lw=5.0 if chosen else 1.4, facecolor=NORM_FILL[recipe["norm"]],
              alpha=1.0 if chosen else 0.30, zorder=3 if chosen else 2)
        glyphs(x, y, CELL, recipe, chosen)

chosen_x = MX + CHOSEN[1] * (CELL + CELL_GAP)
chosen_y = MY - CHOSEN[0] * (CELL + CELL_GAP)

# the declared image class picks the row, the declared motion class the column
flow(ax, (MX - 80, chosen_y - CELL / 2), (MX - 14, chosen_y - CELL / 2),
     color=INK, lw=3.2, head=22.0)
flow(ax, (chosen_x + CELL / 2, MY + 78), (chosen_x + CELL / 2, MY + 14),
     color=INK, lw=3.2, head=22.0)

# the measured channel evidence enters the same decision
flow(ax, (stack_right + 20, MIDDLE + 30), (MX - 92, chosen_y - CELL / 2 - 34),
     lw=3.4, connectionstyle="arc3,rad=-0.18")

# --- the resolved measurement plane and its resolved recipe -----------------
OUT = 362.0
CHIP_H = 150.0
right_block = OUT + 44.0 + CHIP_H
out_x = 1352.0
out_y = top_of(right_block)
tile(ax, stretch(data["thumbnails"][BF_INDEX]), out_x, out_y, OUT, border=ACTIVE,
     lw=5.0, zorder=3)
flow(ax, (chosen_x + CELL + 18, chosen_y - CELL / 2), (out_x - 28, out_y - OUT / 2),
     lw=4.4, connectionstyle="arc3,rad=-0.16")

chip_y = out_y - OUT - 44.0
chip_x = out_x + (OUT - CHIP_H) / 2
frame(ax, chip_x, chip_y, CHIP_H, CHIP_H, color=ACTIVE, lw=4.0,
      facecolor=NORM_FILL[RESOLVED["norm"]], zorder=3)
glyphs(chip_x, chip_y, CHIP_H, RESOLVED, True)

TABLE = (
    [table_row("threshold", "warn_below", meta["warn_below"], "",
         "bar length at which a channel reaches the poor-localisability line")]
    + [table_row("channel", name, QUALITY[name]["localisability"], "localisability",
           f"frame correlation {QUALITY[name]['frame_correlation']:.6f}"
           + ("; drawn in the active colour" if name == "BF" else ""))
       for name in CHANNELS]
    + [table_row("recipe", f"{recipe['image_type']} | {recipe['motion_type']}", None,
           recipe["norm"],
           f"{recipe['preprocessing']} | {recipe['pixel_support']} | "
           f"{recipe['pixel_selection']} | {recipe['estimator']}"
           + ("; the resolved cell" if (recipe["image_type"], recipe["motion_type"])
              == (meta["declared_image_type"], meta["declared_motion_type"]) else ""))
       for recipe in meta["recipes"]]
    + [entry for index, name in enumerate(CHANNELS)
       for entry in plane_summary("tile", name, data["thumbnails"][index],
                                  "channel thumbnail, timepoint 1")]
)

write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "01-resolve-measurement-and-recipe.png", preview=False,
     claim='Of the four recorded channels only brightfield localises movement well enough to estimate on, and the declared image and motion classes select one cell of the fixed recipe matrix.',
     grammar='decision matrix with image evidence',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/01-resolve-measurement-and-recipe.png")
