"""Render every captured intermediate array of the log-ratio registration engine
as a plain image, plus one contact sheet putting them in execution order.

Reads only this bundle's `data/der/`.  Each step image is the array itself: one
image pixel per array element, nearest-neighbour magnified by a whole number so
small arrays are visible, and nothing drawn on top - no axes, arrows, outlines
or text.  What varies between images is only the colour rule, and that is fixed
by the array's kind so that a signed field can never be mistaken for a picture:

    picture   grey, dark to light                intensity and log planes
    signed    blue - white - red, zero is white  gradients, differences, residuals
    unit      orange to white, 1 is white       robust weights, 0 to 1
    mask      near-black / teal                  which pixels are in play
    cost      light to dark                      match cost, light is a better match
    plan      white to teal by lag               which frames get compared

Arrays that must be compared with each other share one display range, listed in
`data/der/display_scales.csv`; every other image is stretched to its own robust
range.  Both are written out per image, so no pixel value here is unrecoverable.
"""
from __future__ import annotations

import csv
import json
from pathlib import Path

import numpy as np
from matplotlib.colors import LinearSegmentedColormap
from PIL import Image

BUNDLE = Path(__file__).resolve().parent.parent
DER = BUNDLE / "data" / "der"
FIG = BUNDLE / "fig"
STEPS_DIR = FIG / "steps"

# House colours, matched to the rest of the project's figures.
RED, BLUE, TEAL, DARK = "#c0392b", "#4878A8", "#0e8f8f", "#303030"
BLANK = (0.62, 0.62, 0.64)          # no value here: outside the sampled grid,
                                    # or a pixel the warp left with no source
SIGNED_PERCENTILE = 98.0            # where a signed field saturates; the last
                                    # 2% of pixels are extreme enough to swamp it
MIN_SIDE = 320                      # smallest edge a step image may be shown at
CONTACT_TILE = 150                  # tile edge on the contact sheet, in pixels

SIGNED = LinearSegmentedColormap.from_list("signed", [BLUE, "#ffffff", RED])
UNIT = LinearSegmentedColormap.from_list("unit", ["#5c3200", "#d98a17", "#ffffff"])
COST = LinearSegmentedColormap.from_list("cost", ["#f2f2f2", TEAL, DARK])
PLAN = LinearSegmentedColormap.from_list("plan", ["#ffffff", TEAL, DARK])
GREY = LinearSegmentedColormap.from_list("grey", ["#050505", "#fafafa"])
MASK = LinearSegmentedColormap.from_list("mask", ["#141414", TEAL])


def limits(values: np.ndarray, kind: str) -> tuple[float, float]:
    """The display range for one array, robust to a handful of extreme pixels."""
    finite = values[np.isfinite(values)]
    if finite.size == 0:
        return 0.0, 1.0
    if kind == "signed":
        high = float(np.percentile(np.abs(finite), SIGNED_PERCENTILE))
        return (-high, high) if high > 0 else (-1.0, 1.0)
    if kind == "unit":
        return 0.0, 1.0
    if kind == "mask":
        return 0.0, 1.0
    low, high = np.percentile(finite, [0.5, 99.5])
    if not high > low:
        low, high = float(finite.min()), float(finite.max())
    return (float(low), float(high)) if high > low else (float(low), float(low) + 1.0)


def colourise(values: np.ndarray, kind: str, low: float, high: float) -> np.ndarray:
    """One array to RGB, with anything unmeasured left flat grey."""
    table = {"intensity": GREY, "log": GREY, "signed": SIGNED, "unit": UNIT,
             "mask": MASK, "cost": COST, "plan": PLAN}[kind]
    finite = np.isfinite(values)
    normalised = np.zeros(values.shape, dtype=np.float64)
    span = high - low if high > low else 1.0
    normalised[finite] = np.clip((values[finite] - low) / span, 0.0, 1.0)
    rgb = table(normalised)[..., :3]
    rgb[~finite] = BLANK
    return rgb


def magnify(rgb: np.ndarray, factor: int) -> np.ndarray:
    """Whole-number nearest-neighbour enlargement: no pixel value is invented."""
    return np.repeat(np.repeat(rgb, factor, axis=0), factor, axis=1) if factor > 1 else rgb


def main() -> None:
    manifest = json.loads((DER / "pipeline_steps.json").read_text(encoding="utf-8"))
    arrays = np.load(DER / "pipeline_steps.npz")
    steps = manifest["steps"]
    STEPS_DIR.mkdir(parents=True, exist_ok=True)

    # Arrays in one scale group share a display range, so the sequence they form
    # is a real comparison rather than a series of independent stretches.
    groups: dict[str, list[float]] = {}
    for entry in steps:
        group = entry["scale_group"]
        if not group:
            continue
        low, high = limits(arrays[entry["name"]].astype(np.float64), entry["kind"])
        span = groups.setdefault(group, [low, high])
        span[0], span[1] = min(span[0], low), max(span[1], high)
    for group, span in groups.items():
        if span[0] < 0 < span[1]:                       # keep zero at the centre
            reach = max(abs(span[0]), abs(span[1]))
            span[0], span[1] = -reach, reach

    rows, tiles = [], []
    for entry in steps:
        values = arrays[entry["name"]].astype(np.float64)
        group = entry["scale_group"]
        low, high = groups[group] if group else limits(values, entry["kind"])
        rgb = colourise(values, entry["kind"], low, high)
        factor = max(1, int(np.ceil(MIN_SIDE / min(values.shape))))
        image = Image.fromarray((magnify(rgb, factor) * 255 + 0.5).astype(np.uint8))
        path = STEPS_DIR / f"{entry['name']}.png"
        image.save(path, optimize=True)
        fit = CONTACT_TILE / max(values.shape)
        thumbnail = Image.fromarray((rgb * 255 + 0.5).astype(np.uint8)).resize(
            (max(1, round(values.shape[1] * fit)), max(1, round(values.shape[0] * fit))),
            Image.NEAREST)
        tile = np.full((CONTACT_TILE, CONTACT_TILE, 3), 255, dtype=np.uint8)
        inset_y = (CONTACT_TILE - thumbnail.height) // 2
        inset_x = (CONTACT_TILE - thumbnail.width) // 2
        tile[inset_y:inset_y + thumbnail.height, inset_x:inset_x + thumbnail.width] = np.asarray(thumbnail)
        tiles.append(tile)
        rows.append({
            "index": entry["index"], "name": entry["name"], "stage": entry["stage"],
            "kind": entry["kind"], "scale_group": group or "",
            "array_width": entry["width"], "array_height": entry["height"],
            "magnification": factor,
            "display_low": low, "display_high": high,
            "value_min": entry["min"], "value_max": entry["max"], "value_mean": entry["mean"],
            "finite_elements": entry["finite"], "elements": entry["width"] * entry["height"],
            "engine_expression": entry["source"], "image": f"fig/steps/{entry['name']}.png",
        })

    # The contact sheet: the same images, in execution order, nothing added.
    columns = 8
    sheet_rows = int(np.ceil(len(tiles) / columns))
    gutter = 6
    sheet = np.full(
        (sheet_rows * CONTACT_TILE + (sheet_rows - 1) * gutter,
         columns * CONTACT_TILE + (columns - 1) * gutter, 3), 255, dtype=np.uint8)
    for position, tile in enumerate(tiles):
        r, c = divmod(position, columns)
        top, left = r * (CONTACT_TILE + gutter), c * (CONTACT_TILE + gutter)
        sheet[top:top + CONTACT_TILE, left:left + CONTACT_TILE] = tile
    Image.fromarray(sheet).save(FIG / "pipeline-steps-real.png", optimize=True)

    with (DER / "figure_data.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    with (DER / "display_scales.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["scale_group", "display_low", "display_high", "steps"])
        for group, span in sorted(groups.items()):
            members = [str(e["index"]) for e in steps if e["scale_group"] == group]
            writer.writerow([group, span[0], span[1], " ".join(members)])

    print(f"{len(steps)} step images -> {STEPS_DIR}")
    print(f"contact sheet -> {FIG / 'pipeline-steps-real.png'}")


if __name__ == "__main__":
    main()
