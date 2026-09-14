"""Standalone producer for one scrolling registration comparison TIFF."""

from __future__ import annotations

import argparse
import csv
import math
from contextlib import ExitStack
from pathlib import Path

import cv2
import numpy as np
import tifffile


PANEL_WIDTH = 256
PANEL_HEIGHT = 256
PANEL_HEADER = 90
PAGE_HEADER = 28


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def display_frame(frame: np.ndarray, low: float, high: float) -> np.ndarray:
    scaled = np.clip((frame.astype(np.float32) - low) * (255.0 / (high - low)), 0, 255)
    image = scaled.astype(np.uint8)
    ratio = min(PANEL_WIDTH / image.shape[1], PANEL_HEIGHT / image.shape[0])
    width, height = max(1, round(image.shape[1] * ratio)), max(1, round(image.shape[0] * ratio))
    resized = cv2.resize(image, (width, height), interpolation=cv2.INTER_AREA)
    tile = np.zeros((PANEL_HEIGHT, PANEL_WIDTH), dtype=np.uint8)
    y, x = (PANEL_HEIGHT - height) // 2, (PANEL_WIDTH - width) // 2
    tile[y:y + height, x:x + width] = resized
    return tile


def put_text(image: np.ndarray, value: str, origin: tuple[int, int], scale: float,
             thickness: int = 1) -> None:
    cv2.putText(image, value, origin, cv2.FONT_HERSHEY_SIMPLEX, scale, 230, thickness,
                lineType=cv2.LINE_AA)


def checked_label_lines(value: str) -> list[str]:
    lines = value.split(" | ", 1)
    for line in lines:
        width = cv2.getTextSize(line, cv2.FONT_HERSHEY_SIMPLEX, 1.2, 2)[0][0]
        if width > PANEL_WIDTH - 10:
            raise ValueError(f"display label line is too wide: {line!r} ({width} px)")
    return lines


def produce(bundle: Path, target: Path) -> None:
    layout = read_csv(bundle / "figure_data.csv")
    rendered = read_csv(bundle / "src_render_manifest.csv")
    scores = read_csv(bundle / "src_per_image.csv")
    if not layout:
        raise ValueError("figure_data.csv is empty")
    series_id = layout[0]["series_id"]
    frames = int(layout[0]["n_frames"])
    columns = max(int(row["column"]) for row in layout) + 1
    rows_count = max(int(row["row"]) for row in layout) + 1
    low, high = float(layout[0]["contrast_low"]), float(layout[0]["contrast_high"])
    render_by_method = {row["method"]: row for row in rendered if row["series_id"] == series_id}
    score_by_method = {row["method"]: row for row in scores if row["series_id"] == series_id}
    width = columns * PANEL_WIDTH
    height = PAGE_HEADER + rows_count * (PANEL_HEADER + PANEL_HEIGHT)

    def pages():
        with ExitStack() as opened:
            tiffs = {}
            for panel in layout:
                entry = render_by_method[panel["method"]]
                if entry["status"] == "ok" and entry["registered_tif"]:
                    tiffs[panel["method"]] = opened.enter_context(
                        tifffile.TiffFile(entry["registered_tif"]))
            for frame_index in range(frames):
                canvas = np.zeros((height, width), dtype=np.uint8)
                put_text(canvas, f"{series_id} | image {frame_index + 1}", (7, 19), 0.48)
                for panel in layout:
                    method = panel["method"]
                    entry = render_by_method[method]
                    score = score_by_method[method]
                    x0 = int(panel["column"]) * PANEL_WIDTH
                    y0 = PAGE_HEADER + int(panel["row"]) * (PANEL_HEADER + PANEL_HEIGHT)
                    canvas[y0:y0 + PANEL_HEADER, x0:x0 + PANEL_WIDTH] = 16
                    label_lines = checked_label_lines(panel["display_label"])
                    put_text(canvas, label_lines[0], (x0 + 5, y0 + 29), 1.2, 2)
                    if len(label_lines) == 2 and label_lines[1]:
                        put_text(canvas, label_lines[1], (x0 + 5, y0 + 58), 1.2, 2)
                    guide, seconds = score["guide_residual_px"], score["time_seconds"]
                    status = "FAILED" if entry["status"] != "ok" else (
                        "guide unavailable" if guide == "" else f"guide {float(guide):.1f} px")
                    if seconds != "":
                        status += f" | {float(seconds):.1f} s"
                    put_text(canvas, status[:43], (x0 + 5, y0 + 82), 0.48)
                    tif = tiffs.get(method)
                    tile = (np.zeros((PANEL_HEIGHT, PANEL_WIDTH), dtype=np.uint8)
                            if tif is None else display_frame(tif.pages[frame_index].asarray(), low, high))
                    y1 = y0 + PANEL_HEADER
                    canvas[y1:y1 + PANEL_HEIGHT, x0:x0 + PANEL_WIDTH] = tile
                yield canvas

    target.parent.mkdir(parents=True, exist_ok=True)
    tifffile.imwrite(target, data=pages(), shape=(frames, height, width), dtype=np.uint8,
                     imagej=True, metadata={"axes": "TYX"}, photometric="minisblack",
                     compression="deflate", compressionargs={"level": 1})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, default=Path("registration_comparison.tif"))
    args = parser.parse_args()
    produce(args.bundle.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
