"""Package benchmark montage TIFFs as flat, registered Plot That bundles."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np
import tifffile


PROJECT = Path(__file__).resolve().parents[1]
PRODUCER = PROJECT / "scripts" / "reproduce_registration_montage.py"
REGISTER = Path.home() / ".claude" / "skills" / "plot-that" / "scripts" / "register.py"

DISPLAY_LABELS = {
    "original": "Original | unregistered", "automatic": "RIPR | Automatic",
    "image_motion_preset": "RIPR preset | image+move",
    "correlation_grid": "Correlation | grid", "correlation_newton": "Correlation | Newton",
    "enhanced_correlation": "Enhanced | correlation",
    "correlation_grid_first": "Grid | image 1", "correlation_newton_first": "Newton | image 1",
    "enhanced_correlation_first": "Enhanced | image 1", "stackreg": "StackReg | previous",
    "turboreg_multilag": "TurboReg | multi-gap", "multistackreg": "MultiStack",
    "image_stabilizer": "Image | Stabilizer", "fast4dreg_previous": "Fast4DReg | previous",
    "fast4dreg_first": "Fast4DReg | image 1", "correct_3d_drift": "Correct 3D | Drift",
    "correct_3d_drift_multitime": "3D Drift | multi-time",
    "sift_stack": "Feature | stack align", "sift_multilag": "Feature | multi-gap",
    "virtual_stack_sift": "Virtual | matching", "descriptor_series": "Descriptor | series",
}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def copy_source(source: Path, bundle: Path, copied_name: str) -> dict:
    target = bundle / copied_name
    shutil.copy2(source, target)
    stat = source.stat()
    return {
        "original_path": str(source.resolve()),
        "copied_path": copied_name,
        "file_name": source.name,
        "modification_time": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
        "byte_size": stat.st_size,
        "sha256": sha256(target),
    }


def safe_bundle_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_.-")


def contrast_limits(path: Path, frames: int) -> tuple[float, float]:
    samples = []
    with tifffile.TiffFile(path) as tif:
        step = max(1, frames // 18)
        for index in range(0, frames, step):
            samples.append(tif.pages[index].asarray()[::4, ::4].reshape(-1))
    values = np.concatenate(samples).astype(np.float32, copy=False)
    low, high = np.quantile(values, (0.005, 0.998))
    return float(low), float(high)


def make_bundle(output: Path, montage_row: dict[str, str], method_order: list[str],
                bundle_folder: str) -> dict:
    series_id = montage_row["series_id"]
    bundle = output / bundle_folder / safe_bundle_name(series_id)
    if bundle.exists() and any(bundle.iterdir()):
        raise ValueError(f"bundle is not empty: {bundle}")
    bundle.mkdir(parents=True, exist_ok=True)

    source_files = [
        (output / "run.json", "src_run.json"),
        (output / "prepared" / "image_manifest.csv", "src_image_manifest.csv"),
        (output / "results" / "transforms.csv", "src_transforms.csv"),
        (output / "results" / "render_manifest.csv", "src_render_manifest.csv"),
        (output / "results" / "per_image.csv", "src_per_image.csv"),
        (output / "results" / "montage_manifest.csv", "src_montage_manifest.csv"),
        (PROJECT / "scripts" / "reusable_registration_benchmark.py",
         "src_reusable_registration_benchmark.py"),
    ]
    source_rows = [copy_source(source, bundle, copied) for source, copied in source_files]
    write_csv(bundle / "sources.csv", source_rows)
    (bundle / "sources.md").write_text(
        "# Sources\n\n" + "\n".join(
            f"- `{row['copied_path']}` — `{row['sha256']}`" for row in source_rows
        ) + "\n", encoding="utf-8")
    shutil.copy2(PRODUCER, bundle / "plot.py")

    rendered = [row for row in read_csv(output / "results" / "render_manifest.csv")
                if row["series_id"] == series_id]
    scores = {row["method"]: row for row in read_csv(output / "results" / "per_image.csv")
              if row["series_id"] == series_id}
    by_method = {row["method"]: row for row in rendered}
    order = ["original", *method_order]
    columns = int(montage_row["columns"])
    low, high = montage_row.get("contrast_low", ""), montage_row.get("contrast_high", "")
    if low == "" or high == "":
        original = Path(by_method["original"]["registered_tif"])
        low, high = contrast_limits(original, int(montage_row["frames"]))
    figure_rows = []
    for panel, method in enumerate(order):
        row = by_method[method]
        score = scores[method]
        figure_rows.append({
            "panel": panel + 1,
            "row": panel // columns,
            "column": panel % columns,
            "method": method,
            "method_id": row["method_id"],
            "display_label": DISPLAY_LABELS[method],
            "full_method_label": row["method_label"],
            "series_id": series_id,
            "n_frames": montage_row["frames"],
            "panel_side": 256,
            "contrast_low": low,
            "contrast_high": high,
            "registration_status": row["status"],
            "metric_status": score["guide_status"],
            "median_guide_residual_px": score["guide_residual_px"],
            "registration_time_seconds": score["time_seconds"],
        })
    write_csv(bundle / "figure_data.csv", figure_rows)

    source_montage = Path(montage_row["montage_tif"])
    master_name = f"{safe_bundle_name(series_id)}_registration_comparison.tif"
    master = bundle / master_name
    shutil.copy2(source_montage, master)
    with tifffile.TiffFile(master) as tif:
        frame_index = max(0, len(tif.pages) // 2)
        preview = tif.pages[frame_index].asarray()
        cv2.imwrite(str(bundle / "preview.png"), preview)
        height, width = preview.shape
    write_csv(bundle / "der_montage_outputs.csv", [{
        "series_id": series_id,
        "master": master_name,
        "frames": montage_row["frames"],
        "width": width,
        "height": height,
        "panels": montage_row["panels"],
    }])
    claim = (f"The unregistered {series_id} stack and all {len(method_order)} registration methods "
             f"are shown on the same {montage_row['frames']} frames for side-by-side review.")
    (bundle / "README.md").write_text(
        "# Registration review montage\n\n"
        f"{claim}\n\n"
        "The TIFF is a display copy for visual review, not a measurement input. "
        "All panels use the unregistered stack's fixed contrast. `figure_data.csv` records "
        "the panel layout, status, guide error and registration time. Raw pixels are traced "
        "through the copied run manifests and their SHA256 hashes rather than duplicated here.\n",
        encoding="utf-8")

    command = [
        sys.executable, str(REGISTER), "add", str(bundle),
        "--claim", claim, "--grammar", "review-montage", "--producer", "plot.py",
        "--statistics-status", "not_applicable", "--figure", master_name,
    ]
    completed = subprocess.run(command, cwd=PROJECT, capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"montage registration failed for {series_id}:\n{completed.stdout}\n{completed.stderr}")
    figure_match = re.search(r"\breprofig\s+(rf-[a-f0-9]{32})\b",
                             completed.stdout, flags=re.IGNORECASE)
    return {
        "series_id": series_id,
        "bundle": str(bundle.resolve()),
        "master": str(master.resolve()),
        "preview": str((bundle / "preview.png").resolve()),
        "figure_id": "" if figure_match is None else figure_match.group(1),
        "registration_output": completed.stdout.strip().replace("\n", " | "),
    }


def execute(output: Path, bundle_folder: str = "montage_bundles") -> list[dict]:
    run = json.loads((output / "run.json").read_text(encoding="utf-8"))
    if run.get("status") != "complete":
        raise ValueError(f"benchmark is not complete: {output}")
    montages = read_csv(output / "results" / "montage_manifest.csv")
    rows = [make_bundle(output, montage, run["methods"], bundle_folder) for montage in montages]
    write_csv(output / "results" / "montage_audit_manifest.csv", rows)
    print(f"Registered {len(rows)} montage bundles")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True,
                        help="Completed reusable-registration benchmark output")
    parser.add_argument("--bundle-folder", default="montage_bundles")
    args = parser.parse_args()
    try:
        execute(args.output.resolve(), args.bundle_folder)
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
