#!/usr/bin/env python3
"""Validate frozen v3 source bytes and selected raster planes without registration."""

from __future__ import annotations

import argparse
import csv
import hashlib
from collections import Counter
from pathlib import Path

import numpy as np
import tifffile
from PIL import Image


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def plane(path: Path, index: int) -> tuple[np.ndarray, str, int]:
    if path.suffix.lower() in {".tif", ".tiff", ".stk"}:
        with tifffile.TiffFile(path) as handle:
            count = len(handle.pages)
            if index < 0 or index >= count:
                raise IndexError(f"plane {index} outside TIFF page range 0..{count - 1}")
            array = handle.pages[index].asarray()
            axes = handle.series[0].axes
    else:
        with Image.open(path) as handle:
            count = getattr(handle, "n_frames", 1)
            if index < 0 or index >= count:
                raise IndexError(f"plane {index} outside image frame range 0..{count - 1}")
            handle.seek(index)
            array = np.asarray(handle.copy())
            axes = "YX" if array.ndim == 2 else "YXS"
    if array.ndim == 3 and array.shape[-1] in (3, 4):
        array = array[..., :3].astype(np.float64).mean(axis=-1)
    if array.ndim != 2:
        raise ValueError(f"selected page is not a 2-D raster: shape={array.shape}")
    return array, axes, count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--split", default="publication_test")
    parser.add_argument("--output")
    args = parser.parse_args()
    project = args.project.resolve()
    manifest = project / "library/benchmark/v3/protocol/source_manifest.csv"
    output = (Path(args.output).resolve() if args.output else
              project / f"library/benchmark/v3/protocol/{args.split}_source_validation.csv")
    with manifest.open("r", newline="", encoding="utf-8-sig") as handle:
        manifest_rows = list(csv.DictReader(handle))
    selected = [row for row in manifest_rows if row["split"] == args.split]
    if args.split == "publication_test":
        acquisition_keys = [(row["image_series_class"], row["independent_group"])
                            for row in selected]
        duplicate_groups = [key for key, count in Counter(acquisition_keys).items() if count > 1]
        group_counts = Counter(image_type for image_type, _ in set(acquisition_keys))
        other_groups = {row["independent_group"] for row in manifest_rows
                        if row["split"] != args.split and row["independent_group"]}
        overlap = {group for _, group in acquisition_keys} & other_groups
        if len(selected) != 30 or len(set(acquisition_keys)) != 30 \
                or len(group_counts) != 5 or set(group_counts.values()) != {6} \
                or duplicate_groups or overlap:
            raise RuntimeError(
                "publication acquisition-independence gate failed: "
                f"sources={len(selected)} groups={len(set(acquisition_keys))} "
                f"per_type={dict(group_counts)} duplicates={duplicate_groups} "
                f"cross_split_overlap={sorted(overlap)}")
    rows = []
    failures = []
    for row in selected:
        path = project / row["local_path"]
        status = "pass"
        details = ""
        observed = ""
        shape_y = shape_x = pages = 0
        dtype = axes = ""
        minimum = maximum = standard_deviation = float("nan")
        try:
            if not path.is_file():
                raise FileNotFoundError(path)
            observed = digest(path)
            if observed.lower() != row["sha256"].lower():
                raise ValueError(f"SHA-256 mismatch expected={row['sha256']} observed={observed}")
            array, axes, pages = plane(path, int(row["plane_index"]))
            shape_y, shape_x = array.shape
            dtype = str(array.dtype)
            finite = np.asarray(array, dtype=np.float64)
            if not np.all(np.isfinite(finite)):
                raise ValueError("selected raster contains nonfinite samples")
            minimum = float(finite.min())
            maximum = float(finite.max())
            standard_deviation = float(finite.std())
            if not maximum > minimum or not standard_deviation > 0:
                raise ValueError("selected raster is constant")
        except Exception as error:
            status = "fail"
            details = f"{type(error).__name__}: {error}"
            failures.append(f"{row['source_id']}: {details}")
        rows.append({
            "source_id": row["source_id"], "split": row["split"],
            "image_series_class": row["image_series_class"],
            "independent_group": row["independent_group"], "lab_id": row["lab_id"],
            "local_path": row["local_path"],
            "plane_index": row["plane_index"], "sha256_expected": row["sha256"],
            "sha256_observed": observed, "shape_y": shape_y, "shape_x": shape_x,
            "pages": pages, "dtype": dtype, "axes": axes, "minimum": minimum,
            "maximum": maximum, "standard_deviation": standard_deviation,
            "scaled_to_512_required": str(min(shape_y, shape_x) < 512).lower(),
            "status": status, "details": details,
        })
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    if failures:
        raise RuntimeError("source validation failures:\n" + "\n".join(failures))
    print(f"Validated {len(rows)} frozen {args.split} source selections; {output}")


if __name__ == "__main__":
    main()
