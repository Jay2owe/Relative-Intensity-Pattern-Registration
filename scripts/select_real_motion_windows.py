"""Choose the most visibly moving real frame window shared by all source channels."""

from __future__ import annotations

import argparse
import csv
import hashlib
from pathlib import Path

import cv2
import numpy as np
import tifffile


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        raise ValueError(f"cannot write an empty table: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def prepared(frame: np.ndarray, scale: float) -> np.ndarray:
    value = cv2.resize(
        frame.astype(np.float32), None, fx=scale, fy=scale,
        interpolation=cv2.INTER_AREA,
    )
    low, high = np.percentile(value, (1, 99))
    if high > low:
        value = np.clip((value - low) / (high - low), 0, 1)
    fine = cv2.GaussianBlur(value, (0, 0), 1.0)
    broad = cv2.GaussianBlur(value, (0, 0), 8.0)
    return (fine - broad).astype(np.float32)


def consensus_shift(candidates: list[tuple[float, float, float, int]],
                    maximum_shift: float) -> tuple[float, float, int, float]:
    valid = [item for item in candidates
             if item[2] >= 0.01 and np.hypot(item[0], item[1]) <= maximum_shift]
    if not valid:
        valid = candidates
    medoid = min(
        valid,
        key=lambda item: np.median([
            np.hypot(item[0] - other[0], item[1] - other[1]) for other in valid
        ]),
    )
    close = [item for item in valid
             if np.hypot(item[0] - medoid[0], item[1] - medoid[1]) <= 2.5]
    if not close:
        close = [medoid]
    weights = np.asarray([max(item[2], 0.001) for item in close])
    dx = float(np.average([item[0] for item in close], weights=weights))
    dy = float(np.average([item[1] for item in close], weights=weights))
    return dx, dy, len(close), float(np.median([item[2] for item in close]))


def motion_type(steps: np.ndarray, positions: np.ndarray) -> str:
    magnitudes = np.linalg.norm(steps, axis=1)
    path = float(magnitudes.sum())
    endpoint = float(np.linalg.norm(positions[-1] - positions[0]))
    typical = float(np.median(magnitudes))
    if float(magnitudes.max(initial=0)) > max(3.0, 4.0 * typical):
        return "INTERMITTENT_JUMPS"
    if path and endpoint / path >= 0.6:
        return "STEADY_DIRECTIONAL_DRIFT"
    if typical < 1.0:
        return "SUBPIXEL_RANDOM_WALK"
    return "CURVED_OSCILLATING_DRIFT"


def inspect_recording(source: Path, window_frames: int, scale: float,
                      maximum_shift: float) -> tuple[dict, list[dict]]:
    with tifffile.TiffFile(source) as tif:
        series = tif.series[0]
        if series.axes != "TCYX" or len(series.shape) != 4:
            raise ValueError(f"{source} is {series.shape} ({series.axes}); expected TCYX")
        frames, channels, height, width = (int(value) for value in series.shape)
        if frames < window_frames:
            raise ValueError(
                f"{source.name} has {frames} frames; cannot select {window_frames}"
            )
        hanning = cv2.createHanningWindow(
            (round(width * scale), round(height * scale)), cv2.CV_32F
        )
        previous = [prepared(tif.pages[channel].asarray(), scale)
                    for channel in range(channels)]
        pairs: list[dict] = []
        shifts = []
        agreeing = []
        responses = []
        for frame in range(1, frames):
            current = [prepared(tif.pages[frame * channels + channel].asarray(), scale)
                       for channel in range(channels)]
            candidates = []
            for channel in range(channels):
                (dx, dy), response = cv2.phaseCorrelate(
                    previous[channel], current[channel], hanning
                )
                candidates.append((dx / scale, dy / scale, float(response), channel))
            dx, dy, count, response = consensus_shift(candidates, maximum_shift)
            shifts.append((dx, dy))
            agreeing.append(count)
            responses.append(response)
            row = {
                "recording": source.stem,
                "from_frame_one_based": frame,
                "to_frame_one_based": frame + 1,
                "consensus_dx_px": dx,
                "consensus_dy_px": dy,
                "agreeing_channels": count,
                "median_phase_response": response,
            }
            for item_dx, item_dy, item_response, channel in candidates:
                row[f"channel_{channel + 1}_dx_px"] = item_dx
                row[f"channel_{channel + 1}_dy_px"] = item_dy
                row[f"channel_{channel + 1}_response"] = item_response
            pairs.append(row)
            previous = current

    shifts_array = np.asarray(shifts)
    positions = np.vstack(([0.0, 0.0], np.cumsum(shifts_array, axis=0)))
    choices = []
    for start in range(frames - window_frames + 1):
        selected = positions[start:start + window_frames]
        span = float(np.hypot(np.ptp(selected[:, 0]), np.ptp(selected[:, 1])))
        endpoint = float(np.linalg.norm(selected[-1] - selected[0]))
        choices.append((
            span, endpoint,
            float(np.median(agreeing[start:start + window_frames - 1])),
            float(np.median(responses[start:start + window_frames - 1])),
            start,
        ))
    span, endpoint, median_agreeing, median_response, start = max(
        choices, key=lambda item: item[:4]
    )
    selected_steps = shifts_array[start:start + window_frames - 1]
    selected_positions = positions[start:start + window_frames]
    lengths = np.linalg.norm(selected_steps, axis=1)
    path = float(lengths.sum())
    result = {
        "recording": source.stem,
        "source_path": str(source.resolve()),
        "source_sha256": sha256(source),
        "source_frames": frames,
        "channels": channels,
        "height": height,
        "width": width,
        "window_first_frame_one_based": start + 1,
        "window_last_frame_one_based": start + window_frames,
        "window_frames": window_frames,
        "motion_type": motion_type(selected_steps, selected_positions),
        "movement_span_px": span,
        "movement_endpoint_px": endpoint,
        "movement_path_px": path,
        "maximum_step_px": float(lengths.max(initial=0)),
        "median_agreeing_channels": median_agreeing,
        "median_phase_response": median_response,
    }
    return result, pairs


def execute(images: Path, output: Path, window_frames: int, scale: float,
            maximum_shift: float) -> list[dict]:
    files = sorted(path for path in images.iterdir()
                   if path.is_file() and path.suffix.lower() in {".tif", ".tiff"})
    if not files:
        raise ValueError(f"no TIFF files found directly under {images}")
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"output folder is not empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    windows = []
    pairs = []
    for source in files:
        print(f"Scanning real movement in {source.name}", flush=True)
        window, recording_pairs = inspect_recording(
            source, window_frames, scale, maximum_shift
        )
        windows.append(window)
        pairs.extend(recording_pairs)
    write_csv(output / "motion_windows.csv", windows)
    write_csv(output / "pairwise_motion_screen.csv", pairs)
    return windows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--window-frames", type=int, default=40)
    parser.add_argument("--scale", type=float, default=0.5)
    parser.add_argument("--maximum-pair-shift", type=float, default=30.0)
    args = parser.parse_args()
    if args.window_frames < 2:
        raise SystemExit("--window-frames must be at least 2")
    if not 0 < args.scale <= 1:
        raise SystemExit("--scale must be greater than zero and no more than one")
    try:
        execute(
            args.images.resolve(), args.output.resolve(), args.window_frames,
            args.scale, args.maximum_pair_shift,
        )
    except (OSError, ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
