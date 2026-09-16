"""Render the benchmark recommendation and a small expert override."""

from __future__ import annotations

import csv
import sys
from pathlib import Path


BUNDLE = Path(__file__).resolve().parent
sys.path.insert(0, str(BUNDLE))
from src_code_figure import Panel, line_table, save  # noqa: E402


START = '''\
from ripr import register

result = register(
    "recording.tif",
    output_path="registered/recording.tif",
)
'''

TUNE = '''\
from ripr import register

result = register(
    "recording.tif",
    output_path="registered/recording.tif",
    recipe="landmarks",
    channel=1,                 # one-based channel
    longitudinal=False,
    image_type="phase_contrast",
    motion_type="subpixel_random_walk",
    max_shift=20,              # search bound (pixels)
    max_iterations=50,         # fitting budget
    interpolation="bilinear", # output resampling
    backend="python",
)
'''

PANELS = [
    Panel("SIMPLE FILE OUTPUT", START, accent="gold"),
    Panel("TUNE THE SAME CALL", TUNE, accent="blue", align_comments=True),
]


def main() -> None:
    svg = BUNDLE / "advanced-tuning-api.svg"
    save(PANELS, svg, png=True)
    rows = line_table(PANELS)
    with (BUNDLE / "figure_data.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
