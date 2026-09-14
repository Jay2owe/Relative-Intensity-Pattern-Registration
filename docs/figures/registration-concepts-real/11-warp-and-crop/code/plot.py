"""Concept 11 - warp the untouched hyperstack and crop the common field."""
import json
import sys
from pathlib import Path

import numpy as np
from scipy import ndimage

CODE = Path(__file__).resolve().parent
BUNDLE = CODE.parent
DER = BUNDLE / "data/der"
FIG = BUNDLE / "fig"
sys.path.insert(0, str(CODE))
from concept_style import TABLE_COLUMNS, save, write_table  # noqa: E402
from concept_style import (  # noqa: E402
    ACTIVE, CHANGE, DATA, HEIGHT, IDLE, INK, canvas, crop, flow, frame, plane_summary,
    stretch, table_row, tile,
)

data = np.load(DER / "concept11.npz")
meta = json.loads((DER / "concept11.json").read_text(encoding="utf-8"))

original = data["original"]          # C, T, Y, X
warped = data["warped"]
channels, times = original.shape[0], original.shape[1]
margin = meta["margin"]
height, width = meta["height"], meta["width"]
MIDDLE = HEIGHT / 2

fig, ax = canvas()

SMALL, SGAP = 128.0, 7.0
grid_w = times * SMALL + (times - 1) * SGAP
grid_h = channels * SMALL + (channels - 1) * SGAP
TOP = MIDDLE + grid_h / 2


def grid(planes, x0, border=INK, lw=1.6):
    """One channel per row, one timepoint per column, on a shared per-channel stretch."""
    for channel in range(channels):
        levels = stretch(planes[channel])
        for time in range(times):
            tile(ax, levels[time], x0 + time * (SMALL + SGAP),
                 TOP - channel * (SMALL + SGAP), SMALL, border=border, lw=lw, zorder=3)


X0 = 48.0
grid(original, X0)
frame(ax, X0 - 14, TOP + 14, grid_w + 28, grid_h + 28, color=INK, lw=3.0, zorder=2)

# --- one transform per timepoint, applied to every channel and plane -------
X1 = X0 + grid_w + 116.0
grid(warped, X1, border=IDLE, lw=1.4)
frame(ax, X1 - 14, TOP + 14, grid_w + 28, grid_h + 28, color=IDLE, lw=2.0, zorder=2)
flow(ax, (X0 + grid_w + 28, MIDDLE), (X1 - 26, MIDDLE), lw=4.6)

# --- the field that holds real pixels at every timepoint ------------------
BIG = 420.0
big_x = X1 + grid_w + 108.0
big_y = MIDDLE + BIG / 2
red = warped[2]                                        # RFP, the visualised channel
tile(ax, stretch(red[0]), big_x, big_y, BIG, border=INK, lw=2.4, zorder=3)
tile(ax, np.where(np.isfinite(red[0]), np.nan, 1.0), big_x, big_y, BIG, cmap="gray",
     vmin=0.0, vmax=1.7, border=None, zorder=4)

pixel = BIG / width
frame(ax, big_x + margin["left"] * pixel, big_y - margin["top"] * pixel,
      BIG - (margin["left"] + margin["right"]) * pixel,
      BIG - (margin["top"] + margin["bottom"]) * pixel, color=ACTIVE, lw=4.0, zorder=6)
flow(ax, (X1 + grid_w + 26, MIDDLE), (big_x - 24, MIDDLE), lw=4.6)

# --- what the alignment did, at a magnification where a few pixels show ---
CHECK = 214.0
check_x = big_x + BIG + 58.0
gap = 26.0
check_top = MIDDLE + (2 * CHECK + gap) / 2
size = 64
# Pick the window the check is worth doing on: textured enough to see a shift, and
# stable enough across five days that what remains is misalignment, not biology.
texture = ndimage.uniform_filter(
    np.abs(ndimage.sobel(warped[1][0], 0)) + np.abs(ndimage.sobel(warped[1][0], 1)),
    size=size,
)


def window_correlation(first, last, y, x):
    a = first[y - size // 2:y + size // 2, x - size // 2:x + size // 2].astype(float)
    b = last[y - size // 2:y + size // 2, x - size // 2:x + size // 2].astype(float)
    good = np.isfinite(a) & np.isfinite(b)
    if good.sum() < a.size // 2:
        return -1.0
    a, b = a[good] - a[good].mean(), b[good] - b[good].mean()
    scale = np.sqrt((a @ a) * (b @ b))
    return float(a @ b / scale) if scale > 0 else -1.0


edge = size // 2 + 12
best = (-np.inf, edge, edge)
for y in range(edge, height - edge, 16):
    for x in range(edge, width - edge, 16):
        stability = window_correlation(warped[1][0], warped[1][times - 1], y, x)
        if stability < 0.5:
            continue
        score = stability * float(texture[y, x])
        if score > best[0]:
            best = (score, y, x)
_, cy, cx = best
cy, cx = int(cy), int(cx)

for row, planes in enumerate((original, warped)):
    first = crop(planes[1][0], cx, cy, size)
    last = crop(planes[1][times - 1], cx, cy, size)
    composite = np.zeros((size, size, 3))
    composite[..., 0] = np.nan_to_num(stretch(last))
    composite[..., 1] = np.nan_to_num(stretch(first))
    composite[..., 2] = np.nan_to_num(stretch(first))
    tile(ax, np.clip(composite, 0, 1), check_x, check_top - row * (CHECK + gap), CHECK,
         border=IDLE if row == 0 else ACTIVE, lw=2.0 if row == 0 else 3.4, zorder=3)

TABLE = (
    [
        table_row("crop", "left", margin["left"], "pixels", "the orange rectangle"),
        table_row("crop", "right", margin["right"], "pixels", ""),
        table_row("crop", "top", margin["top"], "pixels", ""),
        table_row("crop", "bottom", margin["bottom"], "pixels", ""),
        table_row("crop", "output_width", meta["cropped_shape"][1], "pixels",
                  f"from {width}"),
        table_row("crop", "output_height", meta["cropped_shape"][0], "pixels",
                  f"from {height}"),
        table_row("output", "interpolation", None, meta["interpolation"], ""),
        table_row("check", "crop_size", size, "pixels",
                  f"centred on ({cx}, {cy}), the window scoring highest on texture times "
                  f"first-to-last stability"),
    ]
    + [table_row("shown", f"column_{index}", int(value), "frame index",
                 "one column of both grids")
       for index, value in enumerate(meta["shown_frames"])]
    + [entry
       for channel, name in enumerate(meta["channel_names"])
       for time in range(times)
       for entry in plane_summary("warped", f"{name}_t{int(meta['shown_frames'][time])}",
                                  warped[channel][time], "after the transform")]
)

write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "11-warp-and-crop.png", preview=False,
     claim='One transform per timepoint is applied identically to every channel, and only the field that holds real pixels at every timepoint is kept.',
     grammar='channel-by-time grid with a crop',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/11-warp-and-crop.png")
