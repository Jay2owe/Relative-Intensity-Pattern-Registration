"""Reusable real-stack registration benchmark with TIFF and montage outputs."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
from collections import OrderedDict, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np
import tifffile
from skimage.registration import phase_cross_correlation


PROJECT = Path(__file__).resolve().parents[1]
MIN_COMMON_SIDE = 32
DOWNSAMPLE = 2
PANEL_WIDTH = 256
PANEL_HEIGHT = 256
PANEL_HEADER = 90
PAGE_HEADER = 28

DISPLAY_LABELS = {
    "original": ("Original", "unregistered"),
    "automatic": ("RIPR", "Automatic"),
    "image_motion_preset": ("RIPR preset", "image+move"),
    "correlation_grid": ("Correlation", "grid"),
    "correlation_newton": ("Correlation", "Newton"),
    "enhanced_correlation": ("Enhanced", "correlation"),
    "correlation_grid_first": ("Grid", "image 1"),
    "correlation_newton_first": ("Newton", "image 1"),
    "enhanced_correlation_first": ("Enhanced", "image 1"),
    "longitudinal_accuracy": ("Longitudinal", "accuracy"),
    "stackreg": ("StackReg", "previous"),
    "turboreg_multilag": ("TurboReg", "multi-gap"),
    "multistackreg": ("MultiStack", ""),
    "image_stabilizer": ("Image", "Stabilizer"),
    "fast4dreg_previous": ("Fast4DReg", "previous"),
    "fast4dreg_first": ("Fast4DReg", "image 1"),
    "correct_3d_drift": ("Correct 3D", "Drift"),
    "correct_3d_drift_multitime": ("3D Drift", "multi-time"),
    "sift_stack": ("Feature", "stack align"),
    "sift_multilag": ("Feature", "multi-gap"),
    "virtual_stack_sift": ("Virtual", "matching"),
    "descriptor_series": ("Descriptor", "series"),
}

# Public names are intentionally plain and stable. Engine tokens stay private so old result files
# and macro compatibility do not need to be renamed.
METHODS = OrderedDict([
    ("automatic", {
        "kind": "internal", "token": "automatic",
        "id": "00_ripr_automatic_translation",
        "label": "RIPR Automatic fixed recipe",
    }),
    ("image_motion_preset", {
        "kind": "internal", "token": "recommended",
        "id": "01_ripr_recommended_translation",
        "label": "RIPR image-and-motion preset",
    }),
    ("correlation_grid", {
        "kind": "internal", "token": "area_grid",
        "id": "02_ripr_area_grid_translation", "label": "RIPR correlation grid",
    }),
    ("correlation_newton", {
        "kind": "internal", "token": "area_newton",
        "id": "03_ripr_area_newton_translation", "label": "RIPR correlation Newton",
    }),
    ("enhanced_correlation", {
        "kind": "internal", "token": "area_ecc",
        "id": "04_ripr_area_ecc_translation",
        "label": "RIPR Enhanced Correlation Coefficient",
    }),
    ("correlation_grid_first", {
        "kind": "internal", "token": "area_grid_first",
        "id": "05_ripr_area_grid_first_translation",
        "label": "RIPR correlation grid, image 1",
    }),
    ("correlation_newton_first", {
        "kind": "internal", "token": "area_newton_first",
        "id": "06_ripr_area_newton_first_translation",
        "label": "RIPR correlation Newton, image 1",
    }),
    ("enhanced_correlation_first", {
        "kind": "internal", "token": "area_ecc_first",
        "id": "07_ripr_area_ecc_first_translation",
        "label": "RIPR Enhanced Correlation Coefficient, image 1",
    }),
    ("longitudinal_accuracy", {
        "kind": "internal", "token": "longitudinal_accuracy",
        "id": "08_ripr_longitudinal_accuracy_translation",
        "label": "RIPR Longitudinal maximum accuracy",
    }),
    ("stackreg", {
        "kind": "external", "id": "21_real_stackreg_turboreg_translation_chain",
        "label": "StackReg: previous image",
    }),
    ("turboreg_multilag", {
        "kind": "external", "id": "22_real_turboreg_translation_multilag_rcc",
        "label": "TurboReg: multiple gaps",
    }),
    ("multistackreg", {
        "kind": "external", "id": "23_real_multistackreg_translation_same_engine",
        "label": "MultiStackReg",
    }),
    ("image_stabilizer", {
        "kind": "external", "id": "24_real_image_stabilizer_lucas_kanade_rolling_template",
        "label": "Image Stabilizer",
    }),
    ("fast4dreg_previous", {
        "kind": "external", "id": "25_real_fast4dreg_nanoj_previous_frame",
        "label": "Fast4DReg: previous image",
    }),
    ("fast4dreg_first", {
        "kind": "external", "id": "26_real_fast4dreg_nanoj_first_frame",
        "label": "Fast4DReg: image 1",
    }),
    ("correct_3d_drift", {
        "kind": "external", "id": "27_real_correct_3d_drift_phase_correlation_standard",
        "label": "Correct 3D Drift",
    }),
    ("correct_3d_drift_multitime", {
        "kind": "external", "id": "28_real_correct_3d_drift_phase_correlation_multitime",
        "label": "Correct 3D Drift: multiple times",
    }),
    ("sift_stack", {
        "kind": "external", "id": "29_real_linear_stack_alignment_sift_translation_chain",
        "label": "Linear Stack Alignment with Scale-Invariant Feature Transform",
    }),
    ("sift_multilag", {
        "kind": "external", "id": "30_real_sift_translation_multilag_rcc",
        "label": "Scale-Invariant Feature Transform: multiple gaps",
    }),
    ("virtual_stack_sift", {
        "kind": "external", "id": "31_register_virtual_stack_slices_same_sift_engine",
        "label": "Register Virtual Stack Slices",
    }),
    ("descriptor_series", {
        "kind": "external", "id": "32_real_descriptor_based_series_translation",
        "label": "Descriptor-based series registration",
    }),
])

ALIASES = {
    "recommended": "image_motion_preset",
    "area_grid": "correlation_grid",
    "area_newton": "correlation_newton",
    "area_ecc": "enhanced_correlation",
    "area_grid_first": "correlation_grid_first",
    "area_newton_first": "correlation_newton_first",
    "area_ecc_first": "enhanced_correlation_first",
    "longitudinal": "longitudinal_accuracy",
}

IMAGE_TYPES = {
    "PHASE_CONTRAST": "PHASE",
    "BRIGHTFIELD_DIC": "BRIGHTFIELD_DIC",
    "DENSE_FLUORESCENCE": "DENSE_FLUOR",
    "SPARSE_LOW_LIGHT_FLUORESCENCE": "SPARSE_LOWLIGHT",
    "FIDUCIAL_STATIC": "FIDUCIAL_STATIC",
}
MOTION_TYPES = {
    "STEADY_DIRECTIONAL_DRIFT", "CURVED_OSCILLATING_DRIFT",
    "SUBPIXEL_RANDOM_WALK", "INTERMITTENT_JUMPS",
}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        raise ValueError(f"cannot write empty table {path}")
    fields: list[str] = []
    for row in rows:
        for field in row:
            if field not in fields:
                fields.append(field)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_methods(value: str) -> list[str]:
    requested = [part.strip().lower() for part in value.split(",") if part.strip()]
    if not requested:
        raise ValueError("choose at least one method")
    expanded: list[str] = []
    for name in requested:
        name = ALIASES.get(name, name)
        if name == "all":
            choices = list(METHODS)
        elif name == "all_internal":
            choices = [key for key, item in METHODS.items() if item["kind"] == "internal"]
        elif name == "all_external":
            choices = [key for key, item in METHODS.items() if item["kind"] == "external"]
        elif name in METHODS:
            choices = [name]
        else:
            raise ValueError(f"unknown method '{name}'; use -ListMethods")
        for choice in choices:
            if choice not in expanded:
                expanded.append(choice)
    return expanded


def method_table() -> str:
    lines = ["Method\tSource\tDescription"]
    for name, details in METHODS.items():
        source = "RIPR" if details["kind"] == "internal" else "Fiji plugin"
        lines.append(f"{name}\t{source}\t{details['label']}")
    lines.extend([
        "all_internal\tGroup\tAll RIPR methods above",
        "all_external\tGroup\tAll Fiji plugin methods above",
        "all\tGroup\tEvery method above",
    ])
    return "\n".join(lines)


def safe_id(relative: Path, used: set[str]) -> str:
    stem = relative.with_suffix("").as_posix()
    value = re.sub(r"[^A-Za-z0-9_.-]+", "_", stem).strip("_.-") or "stack"
    if value not in used:
        used.add(value)
        return value
    suffix = hashlib.sha256(relative.as_posix().encode("utf-8")).hexdigest()[:8]
    value = f"{value}_{suffix}"
    used.add(value)
    return value


def discover_images(folder: Path, excluded: Path | None = None) -> list[dict]:
    candidates = sorted(path for path in folder.rglob("*")
                        if path.is_file() and path.suffix.lower() in {".tif", ".tiff"})
    rows: list[dict] = []
    used: set[str] = set()
    for path in candidates:
        resolved = path.resolve()
        if excluded is not None and (resolved == excluded or excluded in resolved.parents):
            continue
        with tifffile.TiffFile(path) as tif:
            image = tif.series[0]
            shape = tuple(int(value) for value in image.shape)
            dtype = str(image.dtype)
            axes = image.axes
        if len(shape) != 3 or shape[0] < 2:
            raise ValueError(
                f"{path} is {shape} ({axes}); expected one grayscale time stack shaped frames x height x width"
            )
        rows.append({
            "series_id": safe_id(path.relative_to(folder), used),
            "source_path": str(resolved), "relative_path": path.relative_to(folder).as_posix(),
            "frames": shape[0], "height": shape[1], "width": shape[2],
            "dtype": dtype, "axes_read": axes,
        })
    if not rows:
        raise ValueError(f"no TIFF stacks found under {folder}")
    return rows


def prepare_images(rows: list[dict], prepared: Path, image_type: str,
                   motion_type: str) -> list[dict]:
    category = IMAGE_TYPES[image_type]
    manifest: list[dict] = []
    for source in rows:
        source_path = Path(source["source_path"])
        recording = prepared / category / source["series_id"] / motion_type / "NATIVE_ONLY"
        recording.mkdir(parents=True, exist_ok=False)
        target = recording / "00_input_uncorrected.tif"
        try:
            os.link(source_path, target)
            link_type = "hardlink"
        except OSError:
            shutil.copy2(source_path, target)
            link_type = "copy"
        truth = recording / "truth.csv"
        write_csv(truth, [{"frame": index, "dx": 0.0, "dy": 0.0,
                           "theta_radians": 0.0} for index in range(source["frames"])])
        manifest.append({
            **source, "image_type": image_type, "image_series_class": category,
            "motion_type": motion_type, "condition": "NATIVE_ONLY",
            "source_sha256": sha256(source_path), "prepared_path": str(target.resolve()),
            "prepared_sha256": sha256(target), "link_type": link_type,
        })
    write_csv(prepared / "image_manifest.csv", manifest)
    return manifest


def run_command(command: list[str], cwd: Path, log: Path) -> None:
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as handle:
        process = subprocess.run(command, cwd=cwd, stdout=handle,
                                 stderr=subprocess.STDOUT, text=True, check=False)
    if process.returncode != 0:
        tail = "\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-12:])
        raise RuntimeError(f"command failed ({process.returncode}); see {log}\n{tail}")


def find_maven() -> str:
    direct = shutil.which("mvn.cmd") or shutil.which("mvn")
    if direct:
        return direct
    wrapper = PROJECT / "mvnw.cmd"
    if wrapper.is_file():
        return str(wrapper)
    candidates = sorted(Path.home().glob("apache-maven-*/bin/mvn.cmd"), reverse=True)
    candidates += sorted(Path.home().glob(".m2/wrapper/dists/**/bin/mvn.cmd"), reverse=True)
    if candidates:
        return str(candidates[0])
    raise RuntimeError("Maven was not found; install it or add mvn.cmd to PATH")


def compile_project(output: Path) -> str:
    classpath_file = output / "logs" / "maven_classpath.txt"
    maven = find_maven()
    command = [maven, "-q", "-DskipTests", "test-compile", "dependency:build-classpath",
               f"-Dmdep.outputFile={classpath_file}"]
    run_command(command, PROJECT, output / "logs" / "compile.log")
    dependencies = classpath_file.read_text(encoding="utf-8").strip()
    return os.pathsep.join([
        str(PROJECT / "target" / "test-classes"), str(PROJECT / "target" / "classes"),
        dependencies,
    ])


def run_internal(prepared: Path, methods: list[str], classpath: str,
                 output: Path) -> Path | None:
    selected = [METHODS[name]["token"] for name in methods
                if METHODS[name]["kind"] == "internal"]
    if not selected:
        return None
    result = output / "raw" / "ripr.csv"
    result.parent.mkdir(parents=True, exist_ok=True)
    java = shutil.which("java.exe") or shutil.which("java")
    if not java:
        raise RuntimeError("Java was not found on PATH")
    command = [java, "-Djava.awt.headless=true", "-cp", classpath,
               "ripr.ReusableRegistrationBenchmark", str(prepared), str(result),
               ",".join(selected)]
    run_command(command, PROJECT, output / "logs" / "ripr.log")
    return result


def run_external(prepared: Path, methods: list[str], output: Path,
                 max_heap_gb: int, resume: bool = False) -> Path | None:
    ids = [METHODS[name]["id"] for name in methods if METHODS[name]["kind"] == "external"]
    if not ids:
        return None
    run_root = output / "external_run"
    command = [
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        str(PROJECT / "scripts" / "run_external_parameter_sweep.ps1"),
        "-ProjectRoot", str(PROJECT), "-Dataset", "publication_final_translation",
        "-Config", "all_defaults", "-PreprocessingArm", "native",
        "-RootOverride", str(prepared), "-RunRootOverride", str(run_root),
        "-OnlyMethod", ",".join(ids), "-MaxHeapGb", str(max_heap_gb),
    ]
    if resume:
        command.append("-Resume")
    run_command(command, PROJECT, output / "logs" / "external.log")
    csvs = list((run_root / "publication_final_translation").glob("*.csv"))
    if len(csvs) != 1:
        raise RuntimeError(f"external run produced {len(csvs)} result tables under {run_root}")
    return csvs[0]


def loaded_manifest(path: Path) -> list[dict]:
    rows = read_csv(path)
    for row in rows:
        for field in ("frames", "height", "width"):
            row[field] = int(row[field])
    return rows


def standardise_results(manifest: list[dict], methods: list[str], internal: Path | None,
                        external: Path | None, output: Path) -> list[dict]:
    wanted = {METHODS[name]["id"]: (name, METHODS[name]) for name in methods}
    source_by_id = {row["series_id"]: row for row in manifest}
    rows: list[dict] = []
    raw_rows: list[tuple[str, dict]] = []
    if internal:
        raw_rows.extend(("RIPR", row) for row in read_csv(internal))
    if external:
        raw_rows.extend(("External Fiji plugin", row) for row in read_csv(external)
                        if row["method_id"] in wanted)
    seen: set[tuple[str, str]] = set()
    for origin, row in raw_rows:
        key = (row["series_id"], row["method_id"])
        if key in seen:
            raise RuntimeError(f"duplicate result for {key}")
        seen.add(key)
        source = source_by_id[row["series_id"]]
        public_name, details = wanted[row["method_id"]]
        rows.append({
            "series_id": row["series_id"], "method": public_name,
            "method_id": row["method_id"], "method_label": details["label"],
            "origin": origin, "status": row["status"],
            "elapsed_seconds": row.get("elapsed_seconds", ""),
            "frames": source["frames"], "width": source["width"], "height": source["height"],
            "transform_x_px": row.get("transform_x_px", ""),
            "transform_y_px": row.get("transform_y_px", ""),
            "transform_theta_rad": row.get("transform_theta_rad", ""),
            "resolved_recipe": row.get("resolved_recipe", ""),
        })
    expected = {(series, METHODS[name]["id"])
                for series in source_by_id for name in methods}
    missing = expected - seen
    if missing:
        raise RuntimeError(f"missing {len(missing)} method/image results; first: {sorted(missing)[0]}")
    rows.sort(key=lambda row: (list(source_by_id).index(row["series_id"]),
                               methods.index(row["method"])))
    write_csv(output / "results" / "transforms.csv", rows)
    return rows


def numbers(value: str, count: int) -> np.ndarray:
    values = np.asarray([float(item) for item in value.split(";")], dtype=float)
    if values.shape != (count,) or not np.all(np.isfinite(values)):
        raise ValueError(f"expected {count} finite transform values")
    return values


def write_tiff(path: Path, stack: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tifffile.imwrite(path, stack, imagej=True, metadata={"axes": "TYX"},
                     photometric="minisblack", compression="deflate",
                     compressionargs={"level": 1}, bigtiff=stack.nbytes >= 3_500_000_000)
    with tifffile.TiffFile(path) as tif:
        if tuple(tif.series[0].shape) != tuple(stack.shape):
            raise RuntimeError(f"TIFF verification failed: {path}")


def valid_tiff(path: Path, shape: tuple[int, int, int]) -> bool:
    if not path.is_file():
        return False
    try:
        with tifffile.TiffFile(path) as tif:
            return tuple(int(value) for value in tif.series[0].shape) == shape
    except (OSError, ValueError, tifffile.TiffFileError):
        return False


def render(manifest: list[dict], results: list[dict], output: Path) -> list[dict]:
    sys.path.insert(0, str(PROJECT / "src"))
    from ripr.registration import apply_transforms  # pylint: disable=import-outside-toplevel
    from ripr.types import Transform  # pylint: disable=import-outside-toplevel

    by_series = defaultdict(list)
    for row in results:
        by_series[row["series_id"]].append(row)
    rendered: list[dict] = []
    for source in manifest:
        stack = tifffile.imread(source["source_path"])
        control = output / "registered_tiffs" / "original" / f"{source['series_id']}.tif"
        expected_shape = (source["frames"], source["height"], source["width"])
        if not valid_tiff(control, expected_shape):
            try:
                os.link(source["source_path"], control)
            except OSError:
                control.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source["source_path"], control)
        zero = ";".join("0" for _ in range(source["frames"]))
        rendered.append({
            "series_id": source["series_id"], "method": "original",
            "method_id": "00_unregistered", "method_label": "Original, unregistered",
            "origin": "Control", "status": "ok", "elapsed_seconds": 0.0,
            "frames": source["frames"], "width": source["width"], "height": source["height"],
            "registered_tif": str(control.resolve()), "transform_x_px": zero,
            "transform_y_px": zero, "transform_theta_rad": zero,
        })
        for row in by_series[source["series_id"]]:
            entry = {**row, "registered_tif": ""}
            if row["status"] != "ok":
                rendered.append(entry)
                continue
            try:
                target = output / "registered_tiffs" / row["method"] / f"{source['series_id']}.tif"
                if valid_tiff(target, expected_shape):
                    entry["registered_tif"] = str(target.resolve())
                    rendered.append(entry)
                    continue
                x = numbers(row["transform_x_px"], source["frames"])
                y = numbers(row["transform_y_px"], source["frames"])
                theta = numbers(row["transform_theta_rad"], source["frames"])
                transforms = [Transform(float(dx), float(dy), float(angle))
                              for dx, dy, angle in zip(x, y, theta)]
                corrected = apply_transforms(stack, transforms, "TYX",
                                             interpolation="bilinear", crop=False)
                write_tiff(target, corrected)
                entry["registered_tif"] = str(target.resolve())
            except (RuntimeError, ValueError) as error:
                entry["status"] = f"failed while rendering:{error}"
            rendered.append(entry)
    write_csv(output / "results" / "render_manifest.csv", rendered)
    return rendered


def transforms_from_row(row: dict) -> list:
    sys.path.insert(0, str(PROJECT / "src"))
    from ripr.types import Transform  # pylint: disable=import-outside-toplevel
    count = int(row["frames"])
    x = numbers(row["transform_x_px"], count)
    y = numbers(row["transform_y_px"], count)
    theta = numbers(row["transform_theta_rad"], count)
    return [Transform(float(dx), float(dy), float(angle)) for dx, dy, angle in zip(x, y, theta)]


def common_crop(stack: np.ndarray, row: dict) -> tuple[np.ndarray | None, float]:
    sys.path.insert(0, str(PROJECT / "src"))
    from ripr.core import valid_margin  # pylint: disable=import-outside-toplevel
    transforms = transforms_from_row(row)
    height, width = stack.shape[-2:]
    margin = valid_margin(transforms, width, height, "bilinear")
    y1 = height - margin.bottom if margin.bottom else height
    x1 = width - margin.right if margin.right else width
    crop = stack[:, margin.top:y1, margin.left:x1]
    fraction = float(crop.shape[1] * crop.shape[2] / (height * width))
    return (None, fraction) if min(crop.shape[1:]) < MIN_COMMON_SIDE else (crop, fraction)


def reduce_frame(frame: np.ndarray) -> np.ndarray:
    height = frame.shape[0] - frame.shape[0] % DOWNSAMPLE
    width = frame.shape[1] - frame.shape[1] % DOWNSAMPLE
    values = frame[:height, :width].astype(np.float32)
    values = values.reshape(height // DOWNSAMPLE, DOWNSAMPLE,
                            width // DOWNSAMPLE, DOWNSAMPLE).mean(axis=(1, 3))
    values -= float(np.mean(values))
    scale = float(np.std(values))
    if not math.isfinite(scale) or scale <= 1e-6:
        raise ValueError("common field has no measurable texture")
    window = np.outer(np.hanning(values.shape[0]), np.hanning(values.shape[1])).astype(np.float32)
    return values / scale * window


def score(rendered: list[dict], method_order: list[str], output: Path) -> tuple[list[dict], list[dict]]:
    per_image: list[dict] = []
    for row in rendered:
        base = {
            "series_id": row["series_id"], "method": row["method"],
            "method_label": row["method_label"], "status": row["status"],
            "guide_status": "unavailable", "guide_residual_px": "",
            "retained_image_fraction": "", "time_seconds": row["elapsed_seconds"],
            "registered_tif": row["registered_tif"],
        }
        if row["status"] != "ok" or not row["registered_tif"]:
            per_image.append(base)
            continue
        try:
            stack = tifffile.imread(row["registered_tif"])
            crop, fraction = common_crop(stack, row)
            base["retained_image_fraction"] = fraction
            if crop is None:
                base["guide_status"] = "unavailable:common field below 32 pixels"
                per_image.append(base)
                continue
            reference = reduce_frame(crop[0])
            residuals = []
            for frame in crop[1:]:
                shift, _error, _phase = phase_cross_correlation(
                    reference, reduce_frame(frame), upsample_factor=10, normalization="phase")
                residuals.append(float(np.linalg.norm(shift) * DOWNSAMPLE))
            base["guide_status"] = "guide only"
            base["guide_residual_px"] = float(np.median(residuals)) if residuals else 0.0
        except (RuntimeError, ValueError) as error:
            base["guide_status"] = f"unavailable:{error}"
        per_image.append(base)
    grouped = defaultdict(list)
    for row in per_image:
        grouped[row["method"]].append(row)
    summary: list[dict] = []
    for method in ["original", *method_order]:
        rows = grouped[method]
        scored = [row for row in rows if row["guide_status"] == "guide only"]
        times = [float(row["time_seconds"]) for row in rows
                 if row["status"] == "ok" and row["time_seconds"] != ""]
        summary.append({
            "method": method, "method_label": rows[0]["method_label"],
            "images": len(rows), "scored_images": len(scored),
            "failed_images": sum(row["status"] != "ok" for row in rows),
            "guide_residual_px": "" if not scored else statistics.median(
                float(row["guide_residual_px"]) for row in scored),
            "median_time_seconds": "" if not times else statistics.median(times),
            "interpretation": "guide only; inspect TIFF montage",
        })
    write_csv(output / "results" / "per_image.csv", per_image)
    write_csv(output / "results" / "summary.csv", summary)
    write_readme(output / "RESULTS.md", summary, per_image)
    return summary, per_image


def shown(value) -> str:
    return "NA" if value == "" else f"{float(value):.3f}"


def write_readme(path: Path, summary: list[dict], per_image: list[dict]) -> None:
    lines = [
        "# Registration benchmark", "",
        "No motion was added. Error is a guide from each registered image to registered image 1; inspect the TIFF montages.",
        "", "| Method | Images scored | Failed | Guide error (pixels) | Median time (seconds) |",
        "|---|---:|---:|---:|---:|",
    ]
    for row in summary:
        lines.append(f"| {row['method_label']} | {row['scored_images']}/{row['images']} | "
                     f"{row['failed_images']} | {shown(row['guide_residual_px'])} | "
                     f"{shown(row['median_time_seconds'])} |")
    lines.extend(["", "| Image | Method | Status | Guide error (pixels) | Time (seconds) |",
                  "|---|---|---|---:|---:|"])
    for row in per_image:
        lines.append(f"| {row['series_id']} | {row['method_label']} | {row['guide_status']} | "
                     f"{shown(row['guide_residual_px'])} | {shown(row['time_seconds'])} |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def contrast_limits(path: Path, frames: int) -> tuple[float, float]:
    samples = []
    with tifffile.TiffFile(path) as tif:
        step = max(1, frames // 18)
        for index in range(0, frames, step):
            samples.append(tif.pages[index].asarray()[::4, ::4].reshape(-1))
    values = np.concatenate(samples).astype(np.float32, copy=False)
    low, high = np.quantile(values, (0.005, 0.998))
    if not np.isfinite(low) or not np.isfinite(high) or high <= low:
        raise ValueError(f"invalid display contrast for {path}")
    return float(low), float(high)


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


def make_montages(manifest: list[dict], rendered: list[dict], per_image: list[dict],
                  method_order: list[str], output: Path) -> list[dict]:
    order = ["original", *method_order]
    render_by_key = {(row["series_id"], row["method"]): row for row in rendered}
    score_by_key = {(row["series_id"], row["method"]): row for row in per_image}
    montage_rows = []
    for source in manifest:
        series_id = source["series_id"]
        entries = [render_by_key[(series_id, method)] for method in order]
        columns = min(5, max(1, math.ceil(math.sqrt(len(entries)))))
        rows_count = math.ceil(len(entries) / columns)
        width = columns * PANEL_WIDTH
        height = PAGE_HEADER + rows_count * (PANEL_HEADER + PANEL_HEIGHT)
        original = Path(entries[0]["registered_tif"])
        low, high = contrast_limits(original, source["frames"])

        def pages():
            with __import__("contextlib").ExitStack() as opened:
                tiffs = {}
                for entry in entries:
                    if entry["status"] == "ok" and entry["registered_tif"]:
                        tiffs[entry["method"]] = opened.enter_context(
                            tifffile.TiffFile(entry["registered_tif"]))
                for frame_index in range(source["frames"]):
                    canvas = np.zeros((height, width), dtype=np.uint8)
                    put_text(canvas, f"{series_id} | image {frame_index + 1}", (7, 19), 0.48)
                    for index, entry in enumerate(entries):
                        grid_y, grid_x = divmod(index, columns)
                        x0 = grid_x * PANEL_WIDTH
                        y0 = PAGE_HEADER + grid_y * (PANEL_HEADER + PANEL_HEIGHT)
                        canvas[y0:y0 + PANEL_HEADER, x0:x0 + PANEL_WIDTH] = 16
                        label_one, label_two = DISPLAY_LABELS[entry["method"]]
                        put_text(canvas, label_one, (x0 + 5, y0 + 29), 1.2, 2)
                        if label_two:
                            put_text(canvas, label_two, (x0 + 5, y0 + 58), 1.2, 2)
                        scored = score_by_key[(series_id, entry["method"])]
                        guide = scored["guide_residual_px"]
                        time = scored["time_seconds"]
                        status = "FAILED" if entry["status"] != "ok" else (
                            "guide unavailable" if guide == "" else f"guide {float(guide):.1f} px")
                        if time != "":
                            status += f" | {float(time):.1f} s"
                        put_text(canvas, status[:43], (x0 + 5, y0 + 82), 0.48)
                        tif = tiffs.get(entry["method"])
                        tile = (np.zeros((PANEL_HEIGHT, PANEL_WIDTH), dtype=np.uint8)
                                if tif is None else display_frame(
                                    tif.pages[frame_index].asarray(), low, high))
                        y1 = y0 + PANEL_HEADER
                        canvas[y1:y1 + PANEL_HEIGHT, x0:x0 + PANEL_WIDTH] = tile
                    yield canvas

        target = output / "montages" / f"{series_id}_montage.tif"
        target.parent.mkdir(parents=True, exist_ok=True)
        tifffile.imwrite(target, data=pages(), shape=(source["frames"], height, width),
                         dtype=np.uint8, imagej=True, metadata={"axes": "TYX"},
                         photometric="minisblack", compression="deflate",
                         compressionargs={"level": 1})
        with tifffile.TiffFile(target) as tif:
            if tuple(tif.series[0].shape) != (source["frames"], height, width):
                raise RuntimeError(f"montage verification failed: {target}")
        montage_rows.append({
            "series_id": series_id, "montage_tif": str(target.resolve()),
            "frames": source["frames"], "panels": len(entries),
            "columns": columns, "rows": rows_count,
            "contrast_low": low, "contrast_high": high,
        })
    write_csv(output / "results" / "montage_manifest.csv", montage_rows)
    return montage_rows


def signature(images: Path, rows: list[dict], methods: list[str], image_type: str,
              motion_type: str) -> str:
    material = {
        "images": str(images.resolve()), "methods": methods, "image_type": image_type,
        "motion_type": motion_type,
        "files": [(row["relative_path"], row["frames"], row["height"], row["width"],
                   Path(row["source_path"]).stat().st_size,
                   Path(row["source_path"]).stat().st_mtime_ns) for row in rows],
    }
    return hashlib.sha256(json.dumps(material, sort_keys=True).encode("utf-8")).hexdigest()


def execute(args) -> None:
    images = args.images.resolve()
    output = args.output.resolve()
    if not images.is_dir():
        raise ValueError(f"image folder does not exist: {images}")
    methods = parse_methods(args.methods)
    rows = discover_images(images, output)
    external = any(METHODS[name]["kind"] == "external" for name in methods)
    if external:
        nonsquare = [row["relative_path"] for row in rows if row["width"] != row["height"]]
        if nonsquare:
            raise ValueError(f"external Fiji methods require square images; first non-square stack: {nonsquare[0]}")
    run_signature = signature(images, rows, methods, args.image_type, args.motion_type)
    run_file = output / "run.json"
    resumed = False
    if run_file.is_file():
        existing = json.loads(run_file.read_text(encoding="utf-8"))
        if existing.get("status") == "complete" and existing.get("signature") == run_signature:
            print(f"Already complete: {output / 'RESULTS.md'}")
            return
        if existing.get("signature") != run_signature:
            raise ValueError(f"output belongs to a different run: {output}")
        if not args.resume:
            raise ValueError(
                f"output contains an incomplete run: {output}; repeat with --resume"
            )
        resumed = True
    if not resumed and output.exists() and any(output.iterdir()):
        raise ValueError(f"output folder is not empty: {output}")
    if args.preview:
        print(f"Images: {len(rows)} under {images}")
        print(f"Methods: {', '.join(methods)}")
        print(f"Output: {output}")
        return
    output.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone.utc).isoformat()
    state = ({**existing} if resumed else {})
    state.update({
        "status": "running", "signature": run_signature, "started_utc": started,
        "images": str(images), "output": str(output), "methods": methods,
        "image_type": args.image_type, "motion_type": args.motion_type,
        "metric": "frame-to-image-1 temporal-stability guide; not geometric accuracy",
        "added_motion": False,
        "resumed": resumed,
    })
    atomic_json(run_file, state)
    try:
        prepared = output / "prepared"
        if resumed:
            manifest_path = prepared / "image_manifest.csv"
            if not manifest_path.is_file():
                raise RuntimeError(f"resume is missing {manifest_path}")
            manifest = loaded_manifest(manifest_path)
        else:
            manifest = prepare_images(rows, prepared, args.image_type, args.motion_type)

        wants_internal = any(METHODS[name]["kind"] == "internal" for name in methods)
        existing_internal = output / "raw" / "ripr.csv"
        if resumed and wants_internal and existing_internal.is_file():
            internal = existing_internal
        else:
            classpath = compile_project(output)
            internal = run_internal(prepared, methods, classpath, output)

        external_result = run_external(
            prepared, methods, output, args.max_heap_gb, resume=resumed
        )
        results = standardise_results(manifest, methods, internal, external_result, output)
        rendered = render(manifest, results, output)
        summary, per_image = score(rendered, methods, output)
        montages = make_montages(manifest, rendered, per_image, methods, output)
        state.update({
            "status": "complete", "completed_utc": datetime.now(timezone.utc).isoformat(),
            "images_count": len(manifest), "methods_count": len(methods),
            "registered_tiffs": sum(row["status"] == "ok" for row in rendered),
            "montage_stacks": len(montages), "results": str((output / "RESULTS.md").resolve()),
            "summary": summary,
        })
        atomic_json(run_file, state)
        print(f"Complete: {output / 'RESULTS.md'}")
    except Exception as error:
        state.update({"status": "failed", "failed_utc": datetime.now(timezone.utc).isoformat(),
                      "error": str(error)})
        atomic_json(run_file, state)
        raise


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--images", type=Path)
    result.add_argument("--methods", default="automatic,enhanced_correlation")
    result.add_argument("--output", type=Path)
    result.add_argument("--image-type", choices=sorted(IMAGE_TYPES),
                        default="DENSE_FLUORESCENCE")
    result.add_argument("--motion-type", choices=sorted(MOTION_TYPES),
                        default="INTERMITTENT_JUMPS")
    result.add_argument("--max-heap-gb", type=int, default=8)
    result.add_argument("--preview", action="store_true")
    result.add_argument("--resume", action="store_true",
                        help="Continue an incomplete matching run without repeating completed stages")
    result.add_argument("--list-methods", action="store_true")
    return result


def main() -> None:
    args = parser().parse_args()
    if args.list_methods:
        print(method_table())
        return
    if args.images is None or args.output is None:
        raise SystemExit("--images and --output are required unless --list-methods is used")
    if args.max_heap_gb < 2:
        raise SystemExit("--max-heap-gb must be at least 2")
    try:
        execute(args)
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
