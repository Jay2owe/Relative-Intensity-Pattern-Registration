"""Replay only RIPR's guarded terminal rigid chain on a completed trajectory."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import time

import tifffile

from ripr.longitudinal import _apply_rigid_bridges, _terminal_rigid_chain
from ripr.parameters import LogRatioParameters
from ripr.types import ImageType, MotionType, SelectionMode, Transform


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def transform_rows(path: Path) -> list[Transform]:
    return [
        Transform(
            float(row["dx_px"]),
            float(row["dy_px"]),
            math.radians(float(row["theta_degrees"])),
        )
        for row in read(path)
    ]


def matrix_rows(path: Path, width: int, height: int) -> list[Transform]:
    cx, cy = (width - 1) / 2.0, (height - 1) / 2.0
    result = []
    for row in read(path):
        m00, m01, m02 = (float(row[name]) for name in ("m00", "m01", "m02"))
        m10, m11, m12 = (float(row[name]) for name in ("m10", "m11", "m12"))
        result.append(Transform(
            m00 * cx + m01 * cy + m02 - cx,
            m10 * cx + m11 * cy + m12 - cy,
            math.atan2(m10, m00),
        ))
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_tif", type=Path)
    parser.add_argument("automatic_transforms", type=Path)
    parser.add_argument("completed_transforms", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--input-summary", type=Path)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()

    frames = tifffile.imread(args.input_tif)
    if frames.ndim != 3:
        raise ValueError(f"expected one TYX channel, found {frames.shape}")
    baseline = matrix_rows(args.automatic_transforms, frames.shape[2], frames.shape[1])
    transforms = transform_rows(args.completed_transforms)
    if len(frames) != len(baseline) or len(frames) != len(transforms):
        raise ValueError("frame and transform counts differ")
    parameters = LogRatioParameters(
        image_type=ImageType.DENSE_FLUORESCENCE,
        motion_type=MotionType.INTERMITTENT_JUMPS,
        selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
        crop=False,
        threads=args.threads,
    )
    started = time.perf_counter()
    events = _terminal_rigid_chain(frames, baseline, parameters)
    corrected = _apply_rigid_bridges(transforms, events)
    correction_seconds = time.perf_counter() - started
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with (args.output_dir / "transforms.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=(
            "frame_one_based", "dx_px", "dy_px", "theta_degrees"
        ))
        writer.writeheader()
        for frame, value in enumerate(corrected, 1):
            writer.writerow({
                "frame_one_based": frame,
                "dx_px": value.dx,
                "dy_px": value.dy,
                "theta_degrees": value.theta_degrees,
            })
    previous_seconds = 0.0
    if args.input_summary:
        previous_seconds = float(json.loads(
            args.input_summary.read_text(encoding="utf-8")
        )["elapsed_seconds"])
    summary = {
        "source": str(args.input_tif),
        "image_type": "DENSE_FLUORESCENCE",
        "selection_mode": "longitudinal_accuracy",
        "channels_read": 1,
        "artificial_motion": False,
        "frames": len(frames),
        "elapsed_seconds": previous_seconds + correction_seconds,
        "terminal_chain_seconds": correction_seconds,
        "terminal_chain_frames": [boundary + 1 for boundary, _ in events],
        "replayed_from": str(args.completed_transforms),
    }
    (args.output_dir / "summary.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    main()
