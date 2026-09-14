#!/usr/bin/env python3
"""Prepare one frozen selector source plane for ImageJ without changing its pixels."""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
from pathlib import Path

import numpy as np
import tifffile


FIELDS = [
    "source_series_id", "original_relative_path", "original_sha256",
    "selection", "prepared_relative_path", "prepared_sha256", "shape",
    "dtype", "pixel_sha256",
]


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def pixel_digest(array: np.ndarray) -> str:
    return hashlib.sha256(memoryview(np.ascontiguousarray(array)).cast("B")).hexdigest()


def relative(project: Path, path: Path) -> str:
    return path.resolve().relative_to(project.resolve()).as_posix()


def selected_plane(source: Path, selection: str) -> np.ndarray:
    if not (selection.startswith("plane=") or selection.startswith("frame=")):
        raise RuntimeError(f"unsupported frozen selection {selection!r}")
    index = int(selection.split("=", 1)[1])
    pixels = np.asarray(tifffile.imread(source, key=index))
    if pixels.ndim != 2:
        raise RuntimeError(f"expected one YX plane, found {pixels.shape}")
    return pixels


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_series_id")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    manifest = project / "docs/recording-adaptive-selector/source_split_manifest.csv"
    with manifest.open(newline="", encoding="utf-8-sig") as handle:
        matches = [row for row in csv.DictReader(handle)
                   if row["source_series_id"] == args.source_series_id]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected one source {args.source_series_id}, found {len(matches)}")
    row = matches[0]
    source = project / row["source_path"]
    if digest(source) != row["source_sha256"]:
        raise RuntimeError(f"frozen source hash mismatch: {source}")

    pixels = selected_plane(source, row["source_frame_selection"])
    pixel_hash = pixel_digest(pixels)
    destination_dir = (project / "library/recording_adaptive_selector_v1"
                       / "sources/prepared")
    destination_dir.mkdir(parents=True, exist_ok=True)
    destination = destination_dir / f"{args.source_series_id}.tif"
    temporary = destination.with_suffix(".tmp.tif")
    tifffile.imwrite(temporary, pixels, photometric="minisblack", compression=None,
                     metadata={"axes": "YX"})
    decoded = np.asarray(tifffile.imread(temporary))
    if decoded.shape != pixels.shape or decoded.dtype != pixels.dtype \
            or pixel_digest(decoded) != pixel_hash:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("prepared TIFF changed pixels, shape or dtype")
    os.replace(temporary, destination)

    ledger = destination_dir / "prepared_source_manifest.csv"
    ledger_rows = []
    if ledger.exists():
        with ledger.open(newline="", encoding="utf-8") as handle:
            ledger_rows = [item for item in csv.DictReader(handle)
                           if item["source_series_id"] != args.source_series_id]
    ledger_rows.append({
        "source_series_id": args.source_series_id,
        "original_relative_path": relative(project, source),
        "original_sha256": row["source_sha256"],
        "selection": row["source_frame_selection"],
        "prepared_relative_path": relative(project, destination),
        "prepared_sha256": digest(destination),
        "shape": "x".join(str(value) for value in pixels.shape),
        "dtype": str(pixels.dtype),
        "pixel_sha256": pixel_hash,
    })
    ledger_rows.sort(key=lambda item: item["source_series_id"])
    with ledger.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(ledger_rows)
    print(f"prepared {args.source_series_id} losslessly at {destination}")


if __name__ == "__main__":
    main()
