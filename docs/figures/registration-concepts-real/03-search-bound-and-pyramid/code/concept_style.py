"""Drawing language shared by the eleven log-ratio registration concept figures.

The house palette and rcParams come from plot-that's `plot_style`; everything
here is the diagram vocabulary those eleven figures need on top of it - a flat
white canvas addressed in pixel units, microscopy tiles with a percentile
stretch, flow arrows, cards and the two divergent maps used for residuals and
information.

The set is deliberately text-free, so nothing here writes a label: what each
element means lives in the gallery README beside the figure.
"""
from pathlib import Path
import sys

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import Circle, FancyArrowPatch, FancyBboxPatch, Polygon, Rectangle

# Prefer the copy sitting beside this file inside a bundle; fall back to the skill.
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.append(str(Path.home() / ".claude/skills/plot-that/scripts"))
from plot_style import COLORS, MUTED, apply, save  # noqa: E402

# One role per colour, held across all eleven figures.
INK = COLORS["dark"]        # outlines, frames, the untouched stack
ACTIVE = COLORS["orange"]   # the analysis path currently doing the work
DATA = COLORS["teal"]       # measured image evidence
SECOND = COLORS["blue"]     # a second measured series or the reconciled answer
CHANGE = COLORS["red"]      # local biological change, outliers, discarded pixels
IDLE = MUTED                # inactive, invalid or not-chosen

WIDTH, HEIGHT = 1800.0, 960.0   # canvas units; 1.875:1, as the drawn set was
DPI = 150


def canvas(width=WIDTH, height=HEIGHT, dpi=DPI):
    """A white landscape canvas addressed in pixel units, y increasing upward."""
    apply()
    fig = plt.figure(figsize=(width / dpi, height / dpi), dpi=dpi)
    fig.patch.set_facecolor("white")
    fig.patch.set_alpha(1.0)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, width)
    ax.set_ylim(0, height)
    ax.set_aspect("equal")
    ax.axis("off")
    ax.set_facecolor("white")
    return fig, ax


def stretch(image, low=1.0, high=99.5):
    """Percentile-stretch a plane to 0..1, ignoring non-finite pixels."""
    values = np.asarray(image, dtype=float)
    finite = np.isfinite(values)
    if not finite.any():
        return np.zeros_like(values)
    lo, hi = np.percentile(values[finite], [low, high])
    if not hi > lo:
        hi = lo + 1.0
    out = np.clip((values - lo) / (hi - lo), 0.0, 1.0)
    return np.where(finite, out, np.nan)


def tile(ax, image, x, y, width, height=None, cmap="gray", border=INK, lw=2.0,
         vmin=None, vmax=None, zorder=2, alpha=None, interpolation="nearest"):
    """Draw one image with its top-left corner at (x, y) and an ink frame."""
    array = np.asarray(image)
    if height is None:
        height = width * array.shape[0] / array.shape[1]
    extent = (x, x + width, y - height, y)
    handle = ax.imshow(array, cmap=cmap, extent=extent, origin="upper", zorder=zorder,
                       vmin=vmin, vmax=vmax, alpha=alpha, interpolation=interpolation)
    if border is not None:
        ax.add_patch(Rectangle((x, y - height), width, height, fill=False,
                               edgecolor=border, linewidth=lw, zorder=zorder + 0.1))
    return handle, extent


def frame(ax, x, y, width, height, color=INK, lw=2.0, dashed=False, zorder=3,
          facecolor="none", alpha=1.0):
    """An unfilled rectangle whose top-left corner is at (x, y)."""
    patch = Rectangle((x, y - height), width, height, fill=facecolor != "none",
                      facecolor=facecolor, edgecolor=color, linewidth=lw,
                      linestyle=(0, (6, 5)) if dashed else "solid", zorder=zorder,
                      alpha=alpha)
    ax.add_patch(patch)
    return patch


def card(ax, x, y, width, height, color=IDLE, lw=2.0, facecolor="white",
         radius=14.0, zorder=1, dashed=False, alpha=1.0):
    """A rounded container, as the drawn set used for a grouped mechanism."""
    patch = FancyBboxPatch(
        (x + radius, y - height + radius), width - 2 * radius, height - 2 * radius,
        boxstyle=f"round,pad={radius}", linewidth=lw, edgecolor=color,
        facecolor=facecolor, zorder=zorder, alpha=alpha,
        linestyle=(0, (6, 5)) if dashed else "solid",
    )
    ax.add_patch(patch)
    return patch


def flow(ax, start, end, color=ACTIVE, lw=3.5, head=22.0, zorder=5, dashed=False,
         connectionstyle=None, alpha=1.0):
    """A directed arrow along the analysis path."""
    patch = FancyArrowPatch(
        start, end, arrowstyle=f"-|>,head_length={head * 0.5},head_width={head * 0.34}",
        mutation_scale=1.0, linewidth=lw, color=color, zorder=zorder, alpha=alpha,
        shrinkA=0, shrinkB=0, linestyle=(0, (6, 5)) if dashed else "solid",
        connectionstyle=connectionstyle or "arc3,rad=0",
        joinstyle="miter", capstyle="butt",
    )
    ax.add_patch(patch)
    return patch


def link(ax, start, end, color=IDLE, lw=2.0, zorder=1, rad=0.0, dashed=False, alpha=1.0):
    """An undirected connection, used for the frame-pair graph and mechanisms."""
    patch = FancyArrowPatch(
        start, end, arrowstyle="-", linewidth=lw, color=color, zorder=zorder,
        shrinkA=0, shrinkB=0, alpha=alpha,
        linestyle=(0, (5, 4)) if dashed else "solid",
        connectionstyle=f"arc3,rad={rad}",
    )
    ax.add_patch(patch)
    return patch


def node(ax, xy, radius=9.0, color=INK, facecolor=None, lw=2.0, zorder=6):
    patch = Circle(xy, radius, facecolor=facecolor if facecolor is not None else color,
                   edgecolor=color, linewidth=lw, zorder=zorder)
    ax.add_patch(patch)
    return patch


def bracket(ax, x, y_top, y_bottom, color=IDLE, lw=2.0, tick=12.0, zorder=3):
    ax.plot([x, x], [y_bottom, y_top], color=color, lw=lw, zorder=zorder,
            solid_capstyle="butt")
    for y in (y_top, y_bottom):
        ax.plot([x, x + tick], [y, y], color=color, lw=lw, zorder=zorder,
                solid_capstyle="butt")


def chevron(ax, x, y, size=26.0, color=ACTIVE, lw=4.0, zorder=5):
    """A forward step marker, for a sequence that has no room for a full arrow."""
    ax.plot([x, x + size * 0.55, x], [y + size / 2, y, y - size / 2],
            color=color, lw=lw, zorder=zorder, solid_capstyle="round",
            solid_joinstyle="miter", fillstyle="none")


def wedge(ax, points, color=IDLE, alpha=0.5, zorder=2, edgecolor="none"):
    ax.add_patch(Polygon(points, closed=True, facecolor=color, edgecolor=edgecolor,
                         alpha=alpha, zorder=zorder))


def residual_map():
    """Blue-white-red, for a signed log residual around zero."""
    return LinearSegmentedColormap.from_list(
        "residual", ["#1d3a55", SECOND, "#dce6ee", "#ffffff", "#f2ded9", CHANGE, "#6d1f16"]
    )


def information_map():
    """Dark to teal, for a positive score where high means informative."""
    return LinearSegmentedColormap.from_list(
        "information", ["#101010", "#123c3c", DATA, "#7fd6d6", "#e6f7f7"]
    )


def active_map():
    """Dark to orange, for a score whose peak is the answer being sought."""
    return LinearSegmentedColormap.from_list(
        "active", ["#141414", "#5a3a09", ACTIVE, "#f0c46a", "#fdf1d8"]
    )


def symmetric_limits(values, percentile=99.0):
    finite = np.asarray(values, dtype=float)
    finite = finite[np.isfinite(finite)]
    if finite.size == 0:
        return -1.0, 1.0
    limit = float(np.percentile(np.abs(finite), percentile))
    limit = limit if limit > 0 else 1.0
    return -limit, limit


def crop(image, cx, cy, size):
    """A square crop centred on (cx, cy), clipped to the plane."""
    array = np.asarray(image)
    half = size // 2
    y0 = int(np.clip(cy - half, 0, array.shape[0] - size))
    x0 = int(np.clip(cx - half, 0, array.shape[1] - size))
    return array[y0:y0 + size, x0:x0 + size]


# --- bundle plumbing --------------------------------------------------------

def bundle_paths(producer_file):
    """The four folders a plot-that bundle producer reads and writes."""
    bundle = Path(producer_file).resolve().parents[1]
    return bundle, bundle / "data/src", bundle / "data/der", bundle / "fig"


def write_table(path, rows, columns=None):
    """Write the exact plotted values as a long-form CSV."""
    import csv

    rows = list(rows)
    if columns is None:
        columns = list(rows[0].keys()) if rows else []
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
    return path


TABLE_COLUMNS = ["element", "key", "value_number", "value_text", "note"]


def table_row(element, key, value=None, text="", note=""):
    """One line of the exact plotted table, in the schema every concept shares."""
    return {
        "element": element,
        "key": key,
        "value_number": "" if value is None else float(value),
        "value_text": text,
        "note": note,
    }


def plane_summary(element, key, plane, note=""):
    """Summarise a drawn image plane as rows of checkable numbers."""
    values = np.asarray(plane, dtype=float)
    finite = values[np.isfinite(values)]
    shape_text = f"{values.shape[1]}x{values.shape[0]}"
    if finite.size == 0:
        return [table_row(element, f"{key}.finite_pixels", 0, shape_text, note)]
    return [
        table_row(element, f"{key}.finite_pixels", finite.size, shape_text, note),
        table_row(element, f"{key}.minimum", finite.min(), shape_text, note),
        table_row(element, f"{key}.maximum", finite.max(), shape_text, note),
        table_row(element, f"{key}.mean", finite.mean(), shape_text, note),
        table_row(element, f"{key}.median", np.median(finite), shape_text, note),
    ]


def plane_rows(element, plane, extra=None):
    """Summarise one drawn image plane as checkable numbers."""
    values = np.asarray(plane, dtype=float)
    finite = values[np.isfinite(values)]
    row = {
        "element": element,
        "kind": "image",
        "height": values.shape[0],
        "width": values.shape[1],
        "finite_pixels": int(finite.size),
        "minimum": float(finite.min()) if finite.size else float("nan"),
        "maximum": float(finite.max()) if finite.size else float("nan"),
        "mean": float(finite.mean()) if finite.size else float("nan"),
        "median": float(np.median(finite)) if finite.size else float("nan"),
    }
    if extra:
        row.update(extra)
    return row
