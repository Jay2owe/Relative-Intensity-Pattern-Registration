"""Split TCYX TIFF recordings into one TYX stack per named channel."""

from __future__ import annotations

import argparse
import csv
import hashlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import tifffile


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_name(value: str) -> str:
    clean = re.sub(r"[^A-Za-z0-9]+", "_", value).strip("_").lower()
    return clean or "channel"


def short_recording_name(path: Path, used: set[str]) -> str:
    matches = re.findall(r"\d{8}_\d{4}", path.name)
    candidate = matches[-1] if matches else safe_name(path.stem)
    if candidate in used:
        candidate = f"{candidate}_{hashlib.sha256(path.name.encode()).hexdigest()[:8]}"
    used.add(candidate)
    return candidate


def channel_names(tif: tifffile.TiffFile, count: int,
                  declared: list[str] | None = None) -> list[str]:
    if declared is not None:
        if len(declared) != count:
            raise ValueError(
                f"declared {len(declared)} channel names, but pixels contain {count} channels"
            )
        return declared
    if not tif.ome_metadata:
        return [f"channel_{index}" for index in range(count)]
    root = ET.fromstring(tif.ome_metadata)
    namespace = {"ome": root.tag.split("}")[0].strip("{")}
    pixels = root.find(".//ome:Pixels", namespace)
    if pixels is None:
        return [f"channel_{index}" for index in range(count)]
    names = [channel.get("Name") or f"channel_{index}"
             for index, channel in enumerate(pixels.findall("ome:Channel", namespace))]
    if len(names) != count:
        raise ValueError(f"OME metadata declares {len(names)} channels, pixels contain {count}")
    return names


def read_windows(path: Path | None) -> dict[str, dict[str, str]]:
    if path is None:
        return {}
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    required = {
        "recording", "window_first_frame_one_based", "window_last_frame_one_based",
        "motion_type",
    }
    if not rows or not required.issubset(rows[0]):
        raise ValueError(f"window table must contain {', '.join(sorted(required))}: {path}")
    result: dict[str, dict[str, str]] = {}
    for row in rows:
        keys = {row["recording"], Path(row["recording"]).stem}
        if row.get("source_path"):
            source = Path(row["source_path"])
            keys.update({source.name, source.stem})
        for key in keys:
            if key in result and result[key] != row:
                raise ValueError(f"duplicate window for recording {key}")
            result[key] = row
    return result


def selected_window(source: Path, frames: int, last_frames: int,
                    windows: dict[str, dict[str, str]]) -> tuple[int, int, str]:
    row = windows.get(source.name) or windows.get(source.stem)
    if row is not None:
        first = int(row["window_first_frame_one_based"])
        last = int(row["window_last_frame_one_based"])
        if first < 1 or last < first or last > frames:
            raise ValueError(f"invalid window {first}-{last} for {source.name} ({frames} frames)")
        return first - 1, last, row["motion_type"]
    if windows:
        raise ValueError(f"no selected window for {source.name}")
    keep = frames if last_frames == 0 else min(frames, last_frames)
    return frames - keep, frames, "UNSPECIFIED"


def split_recording(source: Path, output: Path, last_frames: int,
                    used_names: set[str], declared_channels: list[str] | None,
                    windows: dict[str, dict[str, str]]) -> list[dict]:
    with tifffile.TiffFile(source) as tif:
        series = tif.series[0]
        if series.axes != "TCYX" or len(series.shape) != 4:
            raise ValueError(f"{source} is {series.shape} ({series.axes}); expected TCYX")
        frames, channels, height, width = (int(value) for value in series.shape)
        start, stop, motion_type = selected_window(source, frames, last_frames, windows)
        keep = stop - start
        names = channel_names(tif, channels, declared_channels)
        page_indices = range(start * channels, stop * channels)
        selected = series.asarray(key=page_indices).reshape(keep, channels, height, width)

    recording = short_recording_name(source, used_names)
    source_hash = sha256(source)
    rows = []
    for channel_index, channel_name in enumerate(names):
        channel_id = f"{channel_index:02d}_{safe_name(channel_name)}"
        target = output / channel_id / motion_type / f"{recording}.tif"
        target.parent.mkdir(parents=True, exist_ok=True)
        tifffile.imwrite(
            target,
            selected[:, channel_index],
            imagej=True,
            metadata={"axes": "TYX"},
            photometric="minisblack",
            compression="deflate",
            compressionargs={"level": 1},
        )
        with tifffile.TiffFile(target) as check:
            if check.series[0].axes != "TYX" or check.series[0].shape != (keep, height, width):
                raise RuntimeError(f"split TIFF verification failed: {target}")
        rows.append({
            "recording": recording,
            "source_path": str(source.resolve()),
            "source_sha256": source_hash,
            "source_frames": frames,
            "selected_first_frame_one_based": start + 1,
            "selected_last_frame_one_based": stop,
            "selected_frames": keep,
            "motion_type": motion_type,
            "channel_index_zero_based": channel_index,
            "channel_name": channel_name,
            "channel_id": channel_id,
            "output_path": str(target.resolve()),
            "output_sha256": sha256(target),
            "height": height,
            "width": width,
            "dtype": str(selected.dtype),
        })
    return rows


def write_manifest(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def execute(images: Path, output: Path, last_frames: int,
            declared_channels: list[str] | None = None,
            window_table: Path | None = None) -> list[dict]:
    if not images.is_dir():
        raise ValueError(f"image folder does not exist: {images}")
    if last_frames < 0:
        raise ValueError("last_frames must be zero or positive")
    files = sorted(path for path in images.iterdir()
                   if path.is_file() and path.suffix.lower() in {".tif", ".tiff"})
    if not files:
        raise ValueError(f"no TIFF files found directly under {images}")
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"output folder is not empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    used_names: set[str] = set()
    windows = read_windows(window_table)
    for source in files:
        print(f"Splitting {source.name}", flush=True)
        rows.extend(split_recording(
            source, output, last_frames, used_names, declared_channels, windows
        ))
    write_manifest(output / "channel_manifest.csv", rows)
    print(f"Complete: {len(files)} recordings x {len({row['channel_id'] for row in rows})} channels")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--last-frames", type=int, default=0,
                        help="Keep only the last N frames; zero keeps every frame")
    parser.add_argument("--channel-names", default="",
                        help="Comma-separated names in source channel order")
    parser.add_argument("--window-table", type=Path,
                        help="CSV from select_real_motion_windows.py")
    args = parser.parse_args()
    declared = [item.strip() for item in args.channel_names.split(",") if item.strip()] or None
    try:
        execute(
            args.images.resolve(), args.output.resolve(), args.last_frames, declared,
            None if args.window_table is None else args.window_table.resolve(),
        )
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
