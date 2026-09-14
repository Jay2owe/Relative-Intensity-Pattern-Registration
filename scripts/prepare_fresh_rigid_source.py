#!/usr/bin/env python3
"""Decode a frozen TIFF losslessly for ImageJ's built-in reader and record provenance."""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
from pathlib import Path

import numpy as np
import tifffile


FIELDS = ["source_id", "original_relative_path", "original_sha256",
          "decoded_relative_path", "decoded_sha256", "shape", "dtype", "pixel_sha256"]


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def pixel_digest(array: np.ndarray) -> str:
    contiguous = np.ascontiguousarray(array)
    return hashlib.sha256(memoryview(contiguous).cast("B")).hexdigest()


def relative(project: Path, path: Path) -> str:
    return path.resolve().relative_to(project.resolve()).as_posix()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_id")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    source_manifest = (project / "library" / "rigid_selector_tuning"
                       / "source_candidates" / "source_manifest.csv")
    with source_manifest.open(newline="", encoding="utf-8") as handle:
        matches = [row for row in csv.DictReader(handle) if row["source_id"] == args.source_id]
    if len(matches) != 1:
        raise RuntimeError(f"expected one source {args.source_id}, found {len(matches)}")
    source_row = matches[0]
    source = Path(source_row["local_path"])
    if not source.is_absolute():
        source = project / source
    if digest(source) != source_row["sha256"]:
        raise RuntimeError(f"frozen source hash mismatch: {source}")

    destination_dir = (project / "library" / "rigid_selector_tuning"
                       / "source_candidates" / "decoded")
    destination_dir.mkdir(parents=True, exist_ok=True)
    destination = destination_dir / f"{args.source_id}.tif"
    temporary = destination.with_suffix(".tmp.tif")
    pixels = tifffile.imread(source)
    if pixels.ndim != 3:
        raise RuntimeError(f"expected a TYX stack, found shape {pixels.shape}")
    tifffile.imwrite(temporary, pixels, photometric="minisblack", compression=None,
                     bigtiff=False, metadata={"axes": "TYX"})
    decoded = tifffile.imread(temporary)
    original_pixels = pixel_digest(pixels)
    if decoded.shape != pixels.shape or decoded.dtype != pixels.dtype \
            or pixel_digest(decoded) != original_pixels:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("decoded TIFF changed pixels, shape or dtype")
    os.replace(temporary, destination)

    sidecar = source_manifest.with_name("decoded_source_manifest.csv")
    rows = []
    if sidecar.exists():
        with sidecar.open(newline="", encoding="utf-8") as handle:
            rows = [row for row in csv.DictReader(handle) if row["source_id"] != args.source_id]
    rows.append({
        "source_id": args.source_id,
        "original_relative_path": relative(project, source),
        "original_sha256": source_row["sha256"],
        "decoded_relative_path": relative(project, destination),
        "decoded_sha256": digest(destination),
        "shape": "x".join(str(value) for value in pixels.shape),
        "dtype": str(pixels.dtype),
        "pixel_sha256": original_pixels,
    })
    rows.sort(key=lambda row: row["source_id"])
    with sidecar.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"decoded {args.source_id} losslessly to {destination}")


if __name__ == "__main__":
    main()
