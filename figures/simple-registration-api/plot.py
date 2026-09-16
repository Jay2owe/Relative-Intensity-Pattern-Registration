"""Render the smallest RIPR registration call and its three simple swaps."""

from __future__ import annotations

import csv
import sys
from pathlib import Path


BUNDLE = Path(__file__).resolve().parent
sys.path.insert(0, str(BUNDLE))
from src_code_figure import Panel, line_table, save  # noqa: E402


SMALLEST = '''\
import ripr

result = ripr.register_file("recording.tif")
'''

SWAPS = '''\
result = ripr.register_file(
    "recording.tif",
    recipe="bright_dim",  # landmarks | bright_dim | moving_cells
    channel=2,             # one-based channel number
    longitudinal=True,     # full-recording evidence; False = automatic
)
'''

PANELS = [
    Panel("THE SMALLEST RUN", SMALLEST, accent="gold"),
    Panel("SWAP ONLY WHAT YOU NEED", SWAPS, accent="blue", align_comments=True),
]


def main() -> None:
    svg = BUNDLE / "simple-registration-api.svg"
    save(PANELS, svg, png=True)
    rows = line_table(PANELS)
    with (BUNDLE / "figure_data.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
