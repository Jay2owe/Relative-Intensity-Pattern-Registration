"""Run the production emission refinement from a saved Automatic trajectory."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import time

import numpy as np
import tifffile

from ripr.longitudinal import (
    _apply_rigid_bridges,
    _dual_reference_trajectory,
    _repair_endpoint,
    _terminal_rigid_chain,
    repair_trajectory,
)
from ripr.parameters import LogRatioParameters
from ripr.types import ImageType, MotionType, SelectionMode, Transform


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def automatic_transforms(path: Path, width: int, height: int) -> list[Transform]:
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
    parser.add_argument("automatic_csv", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--preliminary-seconds", type=float, default=0.0)
    args = parser.parse_args()

    frames = tifffile.imread(args.input_tif)
    if frames.ndim != 3:
        raise ValueError(f"expected one TYX channel, found {frames.shape}")
    baseline = automatic_transforms(args.automatic_csv, frames.shape[2], frames.shape[1])
    if len(baseline) != len(frames):
        raise ValueError("frame and Automatic transform counts differ")
    parameters = LogRatioParameters(
        image_type=ImageType.DENSE_FLUORESCENCE,
        motion_type=MotionType.INTERMITTENT_JUMPS,
        selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
        crop=False,
        threads=args.threads,
    )
    started = time.perf_counter()
    trajectory, confidence, bright, dim = _dual_reference_trajectory(
        frames, baseline, parameters
    )
    diagonal = math.hypot(*frames.shape[1:])
    repaired, jumps, _ = repair_trajectory(trajectory, confidence, diagonal)
    radius = 0.5 * diagonal
    transforms = [
        Transform(float(row[0]), float(row[1]), float(row[2] / radius))
        for row in repaired
    ]
    transforms, centroid_endpoint = _repair_endpoint(frames, transforms)
    terminal = _terminal_rigid_chain(frames, baseline, parameters)
    transforms = _apply_rigid_bridges(transforms, terminal)
    refinement_seconds = time.perf_counter() - started

    args.output_dir.mkdir(parents=True, exist_ok=True)
    with (args.output_dir / "transforms.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=(
            "frame_one_based", "dx_px", "dy_px", "theta_degrees"
        ))
        writer.writeheader()
        for frame, value in enumerate(transforms, 1):
            writer.writerow({
                "frame_one_based": frame,
                "dx_px": value.dx,
                "dy_px": value.dy,
                "theta_degrees": value.theta_degrees,
            })
    summary = {
        "source": str(args.input_tif),
        "image_type": "DENSE_FLUORESCENCE",
        "selection_mode": "longitudinal_accuracy",
        "channels_read": 1,
        "artificial_motion": False,
        "frames": len(frames),
        "elapsed_seconds": args.preliminary_seconds + refinement_seconds,
        "preliminary_seconds": args.preliminary_seconds,
        "refinement_seconds": refinement_seconds,
        "bright_reference_frame": bright + 1,
        "dim_reference_frame": dim + 1,
        "persistent_jump_frames": [value + 1 for value in jumps],
        "centroid_endpoint_frame": (
            None if centroid_endpoint is None else centroid_endpoint + 1
        ),
        "terminal_chain_frames": [value + 1 for value, _ in terminal],
        "automatic_checkpoint": str(args.automatic_csv),
    }
    (args.output_dir / "summary.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    main()
