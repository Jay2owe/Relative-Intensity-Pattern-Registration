"""Concept 10 - repair unsupported frames and assemble the diagnostic traces."""
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
    ACTIVE, CHANGE, DATA, HEIGHT, IDLE, INK, canvas, frame, link, node, table_row,
)

run = json.loads((DER / "registration_1424.json").read_text(encoding="utf-8"))
trajectory = np.load(DER / "registration_1424.npz")

frames = run["frames"]
dx, dy = trajectory["dx"], trajectory["dy"]
gain = np.exp2(trajectory["log2_gain"])
before = trajectory["residual_before"]
after = trajectory["residual_after"]
support = trajectory["support"]
repairs = run["repairs"]
mads = run.get("outlier_mads", 8.0)

MIDDLE = HEIGHT / 2
PANEL_X, PANEL_W = 62.0, 1180.0
PANEL_H, PANEL_GAP = 216.0, 44.0
STRIP_H = 34.0
BLOCK = 3 * PANEL_H + 2 * PANEL_GAP + PANEL_GAP + STRIP_H
TOP = MIDDLE + BLOCK / 2

fig, ax = canvas()


def to_x(index):
    return PANEL_X + PANEL_W * index / max(frames - 1, 1)


def trace(values, panel_top, colour, second=None, second_colour=IDLE, threshold=None):
    finite = np.concatenate([values[np.isfinite(values)],
                             [] if second is None else second[np.isfinite(second)]])
    low, high = float(finite.min()), float(finite.max())
    if threshold is not None:
        high = max(high, threshold)
    pad = 0.10 * max(high - low, 1e-9)
    low, high = low - pad, high + pad

    def to_y(value):
        return panel_top - PANEL_H + PANEL_H * (value - low) / (high - low)

    frame(ax, PANEL_X, panel_top, PANEL_W, PANEL_H, color=IDLE, lw=1.6, zorder=1)
    if second is not None:
        ax.plot([to_x(i) for i in range(frames)], [to_y(v) for v in second],
                color=second_colour, lw=2.6, zorder=3)
    ax.plot([to_x(i) for i in range(frames)], [to_y(v) for v in values], color=colour,
            lw=3.0, zorder=4)
    if threshold is not None:
        link(ax, (PANEL_X, to_y(threshold)), (PANEL_X + PANEL_W, to_y(threshold)),
             color=CHANGE, lw=2.6, dashed=True, zorder=5)
    link(ax, (PANEL_X, panel_top - PANEL_H), (PANEL_X, panel_top), color=INK, lw=2.6,
         zorder=4)
    link(ax, (PANEL_X, panel_top - PANEL_H), (PANEL_X + PANEL_W, panel_top - PANEL_H),
         color=INK, lw=2.6, zorder=4)
    return to_y


# --- the frame-wide brightness the estimator profiled out, never applied ----
trace(gain, TOP, ACTIVE)

# --- whether the fitted movement made the estimator's images agree better ---
trace(after, TOP - PANEL_H - PANEL_GAP, DATA, second=before, second_colour=IDLE)

# --- the step test that decides which positions are replaced ---------------
steps = np.hypot(np.diff(dx, prepend=dx[0]), np.diff(dy, prepend=dy[0]))
median_step = float(np.median(steps))
mad = float(np.median(np.abs(steps - median_step)))
limit = median_step + mads * 1.4826 * mad
third_top = TOP - 2 * (PANEL_H + PANEL_GAP)
to_y = trace(steps, third_top, DATA, threshold=limit)
flagged = np.flatnonzero(steps > limit)
for index in flagged:
    node(ax, (to_x(index), to_y(steps[index])), 9.0, color=CHANGE, facecolor=CHANGE,
         lw=2.0, zorder=6)

# --- and what each frame's position finally rests on ----------------------
strip_top = third_top - PANEL_H - PANEL_GAP
frame(ax, PANEL_X, strip_top, PANEL_W, STRIP_H, color=INK, lw=2.2, zorder=3)
width = PANEL_W / frames
for index in range(frames):
    if repairs[index] is not None:
        colour = CHANGE
    elif support[index] > 0:
        colour = DATA
    else:
        colour = IDLE
    frame(ax, PANEL_X + index * width, strip_top, width, STRIP_H, color="none",
          facecolor=colour, lw=0.0, zorder=2)

# --- the same diagnostics as compact tokens -------------------------------
TOKEN = 190.0
token_x = PANEL_X + PANEL_W + 84.0
token_gap = 40.0
token_top = MIDDLE + (3 * TOKEN + 2 * token_gap) / 2

# how much dimmer the estimation channel ended than it began
frame(ax, token_x, token_top, TOKEN, TOKEN, color=IDLE, lw=1.8, zorder=2)
span = float(gain.max() - gain.min())
level = float(np.clip((gain[-1] - gain.min()) / span, 0.0, 1.0)) if span else 0.0
frame(ax, token_x, token_top - TOKEN * (1 - level), TOKEN, TOKEN * level, color="none",
      facecolor=ACTIVE, lw=0.0, zorder=3)

# how much of the log disagreement the fit removed
removed = float(np.clip(1.0 - run["median_residual_after"] / run["median_residual_before"],
                        0.0, 1.0))
ring_y = token_top - TOKEN - token_gap
centre = (token_x + TOKEN / 2, ring_y - TOKEN / 2)
angles = np.linspace(np.pi / 2, np.pi / 2 - 2 * np.pi * removed, 200)
radius = TOKEN * 0.36
ax.plot(centre[0] + radius * np.cos(np.linspace(0, 2 * np.pi, 240)),
        centre[1] + radius * np.sin(np.linspace(0, 2 * np.pi, 240)), color=IDLE,
        lw=10.0, zorder=3)
ax.plot(centre[0] + radius * np.cos(angles), centre[1] + radius * np.sin(angles),
        color=DATA, lw=10.0, zorder=4, solid_capstyle="butt")
frame(ax, token_x, ring_y, TOKEN, TOKEN, color=IDLE, lw=1.8, zorder=2)

# how many frames rest on a measurement rather than on interpolation
status_y = ring_y - TOKEN - token_gap
frame(ax, token_x, status_y, TOKEN, TOKEN, color=IDLE, lw=1.8, zorder=2)
measured = float(np.count_nonzero([repair is None for repair in repairs]) / frames)
frame(ax, token_x, status_y - TOKEN * (1 - measured), TOKEN, TOKEN * measured,
      color="none", facecolor=DATA, lw=0.0, zorder=3)

TABLE = (
    [
        table_row("recording", "frames", frames, "count", ""),
        table_row("outlier", "mads", mads, "median absolute deviations",
                  "the dashed threshold on the bottom trace"),
        table_row("outlier", "median_step", median_step, "pixels", ""),
        table_row("outlier", "threshold", limit, "pixels", ""),
        table_row("outlier", "flagged", int(flagged.size), "frames",
                  "marked red on the bottom trace"),
        table_row("repair", "repaired_frames",
                  int(sum(repair is not None for repair in repairs)), "frames",
                  "red in the status strip"),
        table_row("repair", "measured_fraction", measured, "fraction",
                  "the third token"),
        table_row("residual", "median_before", run["median_residual_before"], "log2", ""),
        table_row("residual", "median_after", run["median_residual_after"], "log2", ""),
        table_row("residual", "removed_fraction", removed, "fraction", "the ring token"),
        table_row("gain", "final_within_range", level, "fraction",
                  "the first token; where the trace ended between its own "
                  "smallest and largest value"),
    ]
    + [table_row("gain", f"frame_{index:03d}", float(gain[index]), "ratio",
                 "cumulative, reported not applied")
       for index in range(frames)]
    + [table_row("residual_before", f"frame_{index:03d}", float(before[index]), "log2", "")
       for index in range(frames)]
    + [table_row("residual_after", f"frame_{index:03d}", float(after[index]), "log2", "")
       for index in range(frames)]
    + [table_row("step", f"frame_{index:03d}", float(steps[index]), "pixels",
                 f"support {int(support[index])}; repair {repairs[index]}")
       for index in range(frames)]
)


write_table(DER / "figure_data.csv", TABLE, TABLE_COLUMNS)
save(fig, FIG / "10-repair-and-diagnostics.png", preview=False,
     claim='The gain and residual traces are reported, never applied, and the step test marks which frame positions rest on a measurement rather than interpolation.',
     grammar='diagnostic trace stack',
     producer="code/plot.py", statistics_status="not_applicable", dpi=150)
print("wrote fig/10-repair-and-diagnostics.png")
