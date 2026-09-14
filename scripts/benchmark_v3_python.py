#!/usr/bin/env python3
"""Pinned Python comparator runner for Registration Benchmark v3.

The runner reads shared TIFF inputs and their exact truth trajectories, emits transforms rather than
corrected-image copies, and keeps algorithm failures in the output.  Pairwise adapters expose content
motion from the reference frame to the observed frame in (x, y) pixels.  The self-test is therefore a
mandatory sign/axis/subpixel conformance gate, not merely an import check.
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
import time
import traceback
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence

import numpy as np
import tifffile


RESULT_FIELDS = [
    "dataset", "split", "image_series_class", "series_id", "independent_group", "lab_id",
    "motion_category", "condition_id", "condition_instance", "replicate", "method_id",
    "method_label", "family", "strategy", "config_id", "status", "median_error_px",
    "p90_error_px", "max_error_px", "terminal_error_px", "frames_over_1px",
    "frames_over_5px", "cpu_seconds", "elapsed_seconds", "pairs", "preprocessing_arm",
    "normalization_fallback", "peak_rss_mb", "transform_x_px", "transform_y_px", "details",
]

LAGS = (1, 2, 4, 8, 16)
RUNTIME: dict[str, object] = {
    "skimage_upsample": 100, "skimage_normalization": "phase",
    "opencv_window": "none", "ecc_iterations": 500, "ecc_epsilon": 1e-7,
    "ecc_gaussian": 5, "sitk_optimizer": "rsgd", "sitk_iterations": 300,
    "suite_maxregshift": 0.3, "suite_smooth_sigma": 1.15,
    "suite_smooth_sigma_time": 0.0,
}


@dataclass(frozen=True)
class Method:
    method_id: str
    label: str
    family: str
    strategy: str
    adapter: str
    config_id: str


METHODS = {
    "33_skimage_phase_cross_correlation": Method(
        "33_skimage_phase_cross_correlation", "scikit-image phase_cross_correlation",
        "PHASE_CORRELATION", "first_frame", "skimage", "sk_up100_phase_default"),
    "34_opencv_phase_correlate": Method(
        "34_opencv_phase_correlate", "OpenCV phaseCorrelate", "PHASE_CORRELATION",
        "first_frame", "opencv_phase", "cv_phase_none_default"),
    "35_opencv_ecc_translation": Method(
        "35_opencv_ecc_translation", "OpenCV findTransformECC translation", "ECC",
        "first_frame", "opencv_ecc", "ecc_i500_e1e-7_g5_default"),
    "36_simpleitk_meansquares": Method(
        "36_simpleitk_meansquares", "SimpleITK translation Mean Squares", "SIMPLEITK",
        "first_frame", "sitk_meansquares", "sitk_ms_rsgd_i300_default"),
    "37_simpleitk_correlation": Method(
        "37_simpleitk_correlation", "SimpleITK translation Correlation", "SIMPLEITK",
        "first_frame", "sitk_correlation", "sitk_cor_rsgd_i300_default"),
    "38_simpleitk_mattes_mi": Method(
        "38_simpleitk_mattes_mi", "SimpleITK translation Mattes mutual information", "SIMPLEITK",
        "first_frame", "sitk_mattes_mi", "sitk_mi_rsgd_i300_default"),
    "39_suite2p_rigid": Method(
        "39_suite2p_rigid", "Suite2p rigid registration", "SUITE2P", "first_frame_native",
        "suite2p", "suite_m03_s115_t0_default"),
    "43_pystackreg_translation": Method(
        "43_pystackreg_translation", "PyStackReg translation", "TURBOREG",
        "previous_frame_chain", "pystackreg", "pystackreg_translation_previous_default"),
}


def split_values(value: str) -> set[str]:
    return {part.strip() for part in value.split(",") if part.strip()}


def parse_series(value: str) -> np.ndarray:
    result = np.asarray([float(item) for item in value.split(";")], dtype=np.float64)
    if result.ndim != 1 or result.size == 0 or not np.all(np.isfinite(result)):
        raise ValueError("truth trajectory is empty or nonfinite")
    return result


def encode_series(values: Sequence[float]) -> str:
    return ";".join(f"{float(value):.9f}" for value in values)


def percentile_nearest(values: np.ndarray, percentage: float) -> float:
    """Match Benchmark.percentile: sorted value at Java Math.round(q*(n-1))."""
    finite = np.asarray(values, dtype=np.float32).reshape(-1).copy()
    finite.sort()
    index = int(math.floor((percentage / 100.0) * (finite.size - 1) + 0.5))
    return float(finite[index])


def common_normalize(frames: np.ndarray) -> tuple[np.ndarray, bool]:
    reference_low = percentile_nearest(frames[0], 1.0)
    reference_high = percentile_nearest(frames[0], 99.0)
    ref_bad = (not math.isfinite(reference_low) or not math.isfinite(reference_high) or
               reference_high - reference_low < max(1e-12, 1e-12 * abs(reference_high)))
    output = np.empty_like(frames, dtype=np.float32)
    fallback = ref_bad
    for index, frame in enumerate(frames):
        low = percentile_nearest(frame, 1.0)
        high = percentile_nearest(frame, 99.0)
        bad = (ref_bad or not math.isfinite(low) or not math.isfinite(high) or
               high - low < max(1e-12, 1e-12 * abs(high)))
        if bad:
            output[index] = frame
            fallback = True
        else:
            scale = (reference_high - reference_low) / (high - low)
            output[index] = reference_low + (np.clip(frame, low, high) - low) * scale
    return output, fallback


def load_stack(path: Path) -> np.ndarray:
    frames = np.asarray(tifffile.imread(path), dtype=np.float32)
    if frames.ndim == 2:
        frames = frames[np.newaxis, :, :]
    if frames.ndim != 3:
        raise ValueError(f"expected T,Y,X TIFF, found shape {frames.shape}")
    if not np.all(np.isfinite(frames)):
        raise ValueError("input contains nonfinite pixels")
    return np.ascontiguousarray(frames)


def estimate_skimage(reference: np.ndarray, moving: np.ndarray) -> tuple[float, float]:
    from skimage.registration import phase_cross_correlation
    correction_yx, _, _ = phase_cross_correlation(
        reference, moving, upsample_factor=int(RUNTIME["skimage_upsample"]),
        normalization=(None if RUNTIME["skimage_normalization"] == "none" else "phase"))
    return -float(correction_yx[1]), -float(correction_yx[0])


def estimate_opencv_phase(reference: np.ndarray, moving: np.ndarray) -> tuple[float, float]:
    import cv2
    ref = np.asarray(reference, dtype=np.float32)
    mov = np.asarray(moving, dtype=np.float32)
    window = None
    if RUNTIME["opencv_window"] == "hann":
        window = cv2.createHanningWindow((ref.shape[1], ref.shape[0]), cv2.CV_32F)
    (dx, dy), response = cv2.phaseCorrelate(ref, mov, window)
    if not math.isfinite(response):
        raise RuntimeError("OpenCV phase correlation returned nonfinite response")
    return float(dx), float(dy)


def estimate_opencv_ecc(reference: np.ndarray, moving: np.ndarray) -> tuple[float, float]:
    import cv2
    warp = np.eye(2, 3, dtype=np.float32)
    criteria = (cv2.TERM_CRITERIA_COUNT | cv2.TERM_CRITERIA_EPS,
                int(RUNTIME["ecc_iterations"]), float(RUNTIME["ecc_epsilon"]))
    coefficient, warp = cv2.findTransformECC(
        np.asarray(reference, dtype=np.float32), np.asarray(moving, dtype=np.float32), warp,
        cv2.MOTION_TRANSLATION, criteria, None, int(RUNTIME["ecc_gaussian"]))
    if not math.isfinite(float(coefficient)):
        raise RuntimeError("OpenCV ECC returned nonfinite correlation")
    return float(warp[0, 2]), float(warp[1, 2])


def estimate_simpleitk(reference: np.ndarray, moving: np.ndarray,
                       metric: str) -> tuple[float, float]:
    import SimpleITK as sitk
    fixed_image = sitk.GetImageFromArray(np.asarray(reference, dtype=np.float32))
    moving_image = sitk.GetImageFromArray(np.asarray(moving, dtype=np.float32))
    registration = sitk.ImageRegistrationMethod()
    if metric == "meansquares":
        registration.SetMetricAsMeanSquares()
    elif metric == "correlation":
        registration.SetMetricAsCorrelation()
    elif metric == "mattes_mi":
        registration.SetMetricAsMattesMutualInformation(numberOfHistogramBins=50)
    else:
        raise ValueError(metric)
    registration.SetMetricSamplingStrategy(registration.NONE)
    registration.SetInterpolator(sitk.sitkLinear)
    if RUNTIME["sitk_optimizer"] == "powell":
        registration.SetOptimizerAsPowell(numberOfIterations=int(RUNTIME["sitk_iterations"]),
                                          maximumLineIterations=30, stepLength=1.0,
                                          stepTolerance=1e-5, valueTolerance=1e-6)
    else:
        registration.SetOptimizerAsRegularStepGradientDescent(
            learningRate=2.0, minStep=1e-4,
            numberOfIterations=int(RUNTIME["sitk_iterations"]),
            gradientMagnitudeTolerance=1e-8)
    registration.SetOptimizerScalesFromPhysicalShift()
    initial = sitk.TranslationTransform(2)
    registration.SetInitialTransform(initial, inPlace=False)
    result = registration.Execute(fixed_image, moving_image)
    parameters = result.GetParameters()
    return float(parameters[0]), float(parameters[1])


def estimate_pystackreg(reference: np.ndarray, moving: np.ndarray) -> tuple[float, float]:
    from pystackreg import StackReg
    matrix = StackReg(StackReg.TRANSLATION).register(reference, moving)
    return float(matrix[0, 2]), float(matrix[1, 2])


PAIR_ADAPTERS: dict[str, Callable[[np.ndarray, np.ndarray], tuple[float, float]]] = {
    "skimage": estimate_skimage,
    "opencv_phase": estimate_opencv_phase,
    "opencv_ecc": estimate_opencv_ecc,
    "sitk_meansquares": lambda ref, mov: estimate_simpleitk(ref, mov, "meansquares"),
    "sitk_correlation": lambda ref, mov: estimate_simpleitk(ref, mov, "correlation"),
    "sitk_mattes_mi": lambda ref, mov: estimate_simpleitk(ref, mov, "mattes_mi"),
    "pystackreg": estimate_pystackreg,
}


def plan_edges(frame_count: int, strategy: str) -> list[tuple[int, int]]:
    if strategy == "previous_frame_chain":
        return [(index - 1, index) for index in range(1, frame_count)]
    if strategy == "first_frame":
        return [(0, index) for index in range(1, frame_count)]
    if strategy == "multi_lag_rcc":
        return [(index - lag, index) for lag in LAGS for index in range(lag, frame_count)]
    raise ValueError(f"unsupported strategy {strategy}")


def reconcile(frame_count: int, edges: Sequence[tuple[int, int]],
              pair_motion: np.ndarray, strategy: str) -> np.ndarray:
    transforms = np.zeros((frame_count, 2), dtype=np.float64)
    if strategy == "previous_frame_chain":
        for edge, motion in zip(edges, pair_motion):
            transforms[edge[1]] = transforms[edge[0]] + motion
        return transforms
    if strategy == "first_frame":
        for edge, motion in zip(edges, pair_motion):
            transforms[edge[1]] = motion
        return transforms
    design = np.zeros((len(edges), frame_count - 1), dtype=np.float64)
    for row, (source, target) in enumerate(edges):
        if source != 0:
            design[row, source - 1] = -1.0
        if target != 0:
            design[row, target - 1] = 1.0
    for axis in range(2):
        solved, _, rank, _ = np.linalg.lstsq(design, pair_motion[:, axis], rcond=None)
        if rank != frame_count - 1:
            raise RuntimeError(f"multi-lag graph rank {rank}, expected {frame_count - 1}")
        transforms[1:, axis] = solved
    return transforms


def solve_pair_method(frames: np.ndarray, method: Method,
                      strategy_override: str | None) -> tuple[np.ndarray, int, str]:
    strategy = strategy_override or method.strategy
    if strategy == "first_frame_native":
        strategy = "first_frame"
    edges = plan_edges(len(frames), strategy)
    adapter = PAIR_ADAPTERS[method.adapter]
    estimates = np.asarray([adapter(frames[source], frames[target])
                            for source, target in edges], dtype=np.float64)
    if estimates.shape != (len(edges), 2) or not np.all(np.isfinite(estimates)):
        raise RuntimeError("adapter emitted missing or nonfinite pair transform")
    return reconcile(len(frames), edges, estimates, strategy), len(edges), strategy


def solve_suite2p(frames: np.ndarray) -> tuple[np.ndarray, int, str]:
    # Import lazily: Suite2p/Torch startup is substantial and must not tax other methods.
    import torch
    from suite2p.registration.register import register_frames
    movie = np.ascontiguousarray(frames, dtype=np.float32)
    outputs = register_frames(
        movie.copy(), np.ascontiguousarray(movie[0]), batch_size=len(movie), norm_frames=True,
        smooth_sigma=float(RUNTIME["suite_smooth_sigma"]), spatial_taper=3.45,
        nonrigid=False, maxregshift=float(RUNTIME["suite_maxregshift"]),
        smooth_sigma_time=float(RUNTIME["suite_smooth_sigma_time"]), subpixel=10,
        device=torch.device("cpu"), apply_shifts=False)
    offsets = outputs[3]
    # Suite2p calls these registration offsets, but shift_frames applies (-yoff, -xoff): the
    # reported values are therefore content motion, which is already our benchmark convention.
    motion_y = np.asarray(offsets[0], dtype=np.float64)
    motion_x = np.asarray(offsets[1], dtype=np.float64)
    transforms = np.column_stack((motion_x, motion_y))
    if transforms.shape != (len(frames), 2) or not np.all(np.isfinite(transforms)):
        raise RuntimeError(f"Suite2p emitted invalid offsets with shape {transforms.shape}")
    transforms -= transforms[0]
    return transforms, len(frames), "first_frame_native"


def solve(frames: np.ndarray, method: Method,
          strategy_override: str | None) -> tuple[np.ndarray, int, str]:
    if method.adapter == "suite2p":
        if strategy_override:
            raise ValueError("Suite2p native stack runner does not accept scheduler override")
        return solve_suite2p(frames)
    return solve_pair_method(frames, method, strategy_override)


def nearest_quantile(values: np.ndarray, fraction: float) -> float:
    ordered = np.sort(np.asarray(values, dtype=np.float64))
    index = int(math.floor(fraction * (len(ordered) - 1) + 0.5))
    return float(ordered[index])


def rss_mb() -> float:
    try:
        import psutil
        return float(psutil.Process().memory_info().rss) / (1024.0 * 1024.0)
    except Exception:
        return math.nan


def result_row(input_row: dict[str, str], method: Method, arm: str, fallback: bool,
               status: str, transforms: np.ndarray | None, pair_count: int,
               strategy: str, cpu: float, elapsed: float, peak_rss: float,
               details: str, config_id: str) -> dict[str, object]:
    row: dict[str, object] = {field: "" for field in RESULT_FIELDS}
    for field in ("split", "image_series_class", "series_id", "independent_group", "lab_id",
                  "motion_category", "condition_id", "condition_instance", "replicate"):
        row[field] = input_row[field]
    row.update({
        "dataset": "v3", "method_id": method.method_id, "method_label": method.label,
        "family": method.family, "strategy": strategy, "config_id": config_id,
        "status": status, "cpu_seconds": f"{cpu:.9f}", "elapsed_seconds": f"{elapsed:.9f}",
        "pairs": pair_count, "preprocessing_arm": arm,
        "normalization_fallback": str(bool(fallback)).lower(),
        "peak_rss_mb": "" if not math.isfinite(peak_rss) else f"{peak_rss:.9f}",
        "details": details,
    })
    if transforms is None:
        row["frames_over_1px"] = -1
        row["frames_over_5px"] = -1
        return row
    truth_x = parse_series(input_row["truth_x_px"])
    truth_y = parse_series(input_row["truth_y_px"])
    if transforms.shape != (len(truth_x), 2):
        raise ValueError(f"transform shape {transforms.shape} does not match truth")
    errors = np.hypot(transforms[:, 0] - truth_x, transforms[:, 1] - truth_y)
    row.update({
        "median_error_px": f"{nearest_quantile(errors, 0.5):.9f}",
        "p90_error_px": f"{nearest_quantile(errors, 0.9):.9f}",
        "max_error_px": f"{nearest_quantile(errors, 1.0):.9f}",
        "terminal_error_px": f"{float(errors[-1]):.9f}",
        "frames_over_1px": int(np.sum(errors > 1.0)),
        "frames_over_5px": int(np.sum(errors > 5.0)),
        "transform_x_px": encode_series(transforms[:, 0]),
        "transform_y_px": encode_series(transforms[:, 1]),
    })
    return row


def completed_keys(path: Path) -> set[tuple[str, ...]]:
    if not path.is_file():
        return set()
    with path.open("r", newline="", encoding="utf-8") as handle:
        return {(row["series_id"], row["motion_category"], row["condition_instance"],
                 row["method_id"], row["preprocessing_arm"], row.get("config_id", ""))
                for row in csv.DictReader(handle)}


def selected(value: str, choices: set[str]) -> bool:
    return not choices or value in choices


def run(args: argparse.Namespace) -> int:
    project = Path(args.project).resolve()
    root = project / "library" / "benchmark" / "v3" / "inputs" / args.split
    manifest = root / "inputs_manifest.csv"
    if not manifest.is_file():
        raise FileNotFoundError(manifest)
    output = Path(args.output).resolve() if args.output else (
        project / "library" / "benchmark" / "v3" / "runs" / args.split / "python_results.csv")
    output.parent.mkdir(parents=True, exist_ok=True)
    if args.rewrite or not output.exists():
        with output.open("w", newline="", encoding="utf-8") as handle:
            csv.DictWriter(handle, fieldnames=RESULT_FIELDS).writeheader()
    done = set() if args.rewrite else completed_keys(output)
    wanted_methods = split_values(args.methods)
    unknown = wanted_methods - set(METHODS)
    if unknown:
        raise ValueError(f"unknown methods: {sorted(unknown)}")
    methods = [method for method_id, method in METHODS.items()
               if not wanted_methods or method_id in wanted_methods]
    for key in tuple(RUNTIME):
        value = getattr(args, key, None)
        if value is not None:
            RUNTIME[key] = value
    arms = [arm.strip() for arm in args.preprocessing_arms.split(",") if arm.strip()]
    if any(arm not in {"native", "common_normalized"} for arm in arms):
        raise ValueError("preprocessing arms must be native and/or common_normalized")
    only_sources = split_values(args.only_source)
    only_conditions = split_values(args.only_condition)
    only_motions = split_values(args.only_motion)
    with manifest.open("r", newline="", encoding="utf-8") as handle:
        inputs = list(csv.DictReader(handle))
    successes = failures = 0
    with output.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=RESULT_FIELDS)
        for input_row in inputs:
            if not selected(input_row["series_id"], only_sources):
                continue
            if not selected(input_row["condition_id"], only_conditions):
                continue
            if not selected(input_row["motion_category"], only_motions):
                continue
            native = load_stack(root / input_row["input_relative_path"])
            for arm in arms:
                frames, fallback = ((native, False) if arm == "native" else common_normalize(native))
                for method in methods:
                    config_id = args.config_id or method.config_id
                    key = (input_row["series_id"], input_row["motion_category"],
                           input_row["condition_instance"], method.method_id, arm, config_id)
                    if key in done:
                        continue
                    cpu0 = time.process_time()
                    wall0 = time.perf_counter()
                    transforms = None
                    pairs = 0
                    strategy = args.strategy or method.strategy
                    details = ""
                    status = "ok"
                    try:
                        transforms, pairs, strategy = solve(frames, method, args.strategy)
                        if not np.all(np.isfinite(transforms)):
                            raise RuntimeError("nonfinite cumulative transform")
                    except Exception as error:
                        status = f"failed:{type(error).__name__}:{error}"
                        details = traceback.format_exc(limit=8).replace("\r", " ").replace("\n", " | ")
                    cpu = time.process_time() - cpu0
                    elapsed = time.perf_counter() - wall0
                    row = result_row(input_row, method, arm, fallback, status, transforms, pairs,
                                     strategy, cpu, elapsed, rss_mb(), details, config_id)
                    writer.writerow(row)
                    handle.flush()
                    done.add(key)
                    if transforms is None:
                        failures += 1
                        print(f"FAILED {input_row['series_id']} {input_row['motion_category']} "
                              f"{input_row['condition_instance']} {method.method_id} {arm}: {status}",
                              flush=True)
                    else:
                        successes += 1
                        print(f"OK {input_row['series_id']} {input_row['motion_category']} "
                              f"{input_row['condition_instance']} {method.method_id} {arm} "
                              f"median={row['median_error_px']} px", flush=True)
    print(f"v3 Python complete: {successes} successful rows, {failures} failed rows; {output}")
    return 0


def conformance_fixture(dx: float, dy: float) -> tuple[np.ndarray, np.ndarray]:
    from scipy.ndimage import gaussian_filter, shift
    generator = np.random.default_rng(20260820)
    reference = gaussian_filter(generator.normal(size=(128, 128)).astype(np.float32), 1.4)
    yy, xx = np.mgrid[:128, :128]
    reference += (3.0 * np.exp(-((xx - 37.0) ** 2 + (yy - 81.0) ** 2) / 45.0)).astype(np.float32)
    moving = shift(reference, (dy, dx), order=3, mode="constant", cval=0.0,
                   prefilter=True).astype(np.float32)
    return reference, moving


def self_test(method_ids: Iterable[str], report: Path | None) -> int:
    cases = ((0.0, 0.0), (2.0, 0.0), (-2.0, 0.0), (0.0, 2.0), (0.0, -2.0),
             (1.75, -1.25), (-0.25, 0.25), (6.0, -4.0))
    tolerances = {
        "skimage": 1.25, "opencv_phase": 0.80, "opencv_ecc": 0.35,
        "sitk_meansquares": 0.55, "sitk_correlation": 0.55, "sitk_mattes_mi": 1.25,
        "pystackreg": 0.35, "suite2p": 1.25,
    }
    rows: list[dict[str, object]] = []
    failures: list[str] = []
    for method_id in method_ids:
        method = METHODS[method_id]
        for dx, dy in cases:
            reference, moving = conformance_fixture(dx, dy)
            status = "pass"
            estimated_x = estimated_y = error = math.nan
            details = ""
            try:
                if method.adapter == "suite2p":
                    # Suite2p is designed for nonnegative integer-like microscopy intensities.
                    # Exercise its documented input domain rather than the centred unit-normal
                    # fixture used to stress generic mathematical pair estimators.
                    low = min(float(reference.min()), float(moving.min()))
                    high = max(float(reference.max()), float(moving.max()))
                    scale = 4095.0 / max(high - low, 1e-12)
                    reference = ((reference - low) * scale).astype(np.float32)
                    moving = ((moving - low) * scale).astype(np.float32)
                    transforms, _, _ = solve_suite2p(np.stack((reference, moving)))
                    estimated_x, estimated_y = map(float, transforms[1])
                else:
                    estimated_x, estimated_y = PAIR_ADAPTERS[method.adapter](reference, moving)
                error = math.hypot(estimated_x - dx, estimated_y - dy)
                if not math.isfinite(error) or error > tolerances[method.adapter]:
                    status = "fail"
                    failures.append(f"{method_id} ({dx},{dy}) error {error:.4f}")
            except Exception as exc:
                status = "fail"
                details = f"{type(exc).__name__}:{exc}"
                failures.append(f"{method_id} ({dx},{dy}) {details}")
            rows.append({"method_id": method_id, "truth_x_px": dx, "truth_y_px": dy,
                         "estimated_x_px": estimated_x, "estimated_y_px": estimated_y,
                         "error_px": error, "tolerance_px": tolerances[method.adapter],
                         "status": status, "details": details})
            print(f"{status.upper()} {method_id} truth=({dx:.2f},{dy:.2f}) "
                  f"estimated=({estimated_x:.3f},{estimated_y:.3f}) error={error:.3f}", flush=True)
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    if failures:
        print("Conformance failures:\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"Python adapter conformance passed: {len(rows)} cases")
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--project", default=str(Path(__file__).resolve().parents[1]))
    result.add_argument("--split", default="development")
    result.add_argument("--output")
    result.add_argument("--methods", default="")
    result.add_argument("--preprocessing-arms", default="native,common_normalized")
    result.add_argument("--only-source", default="")
    result.add_argument("--only-condition", default="")
    result.add_argument("--only-motion", default="")
    result.add_argument("--strategy", choices=("first_frame", "previous_frame_chain", "multi_lag_rcc"))
    result.add_argument("--config-id")
    result.add_argument("--skimage-upsample", type=int, choices=(1, 10, 100))
    result.add_argument("--skimage-normalization", choices=("phase", "none"))
    result.add_argument("--opencv-window", choices=("none", "hann"))
    result.add_argument("--ecc-iterations", type=int, choices=(100, 500, 1000))
    result.add_argument("--ecc-epsilon", type=float, choices=(1e-5, 1e-7))
    result.add_argument("--ecc-gaussian", type=int, choices=(1, 5, 9))
    result.add_argument("--sitk-optimizer", choices=("rsgd", "powell"))
    result.add_argument("--sitk-iterations", type=int, choices=(100, 300))
    result.add_argument("--suite-maxregshift", type=float, choices=(0.1, 0.2, 0.3))
    result.add_argument("--suite-smooth-sigma", type=float, choices=(1.15, 1.5, 2.0))
    result.add_argument("--suite-smooth-sigma-time", type=float, choices=(0.0, 1.0))
    result.add_argument("--rewrite", action="store_true")
    result.add_argument("--self-test", action="store_true")
    result.add_argument("--conformance-report")
    return result


def main() -> int:
    args = parser().parse_args()
    if args.self_test:
        method_ids = split_values(args.methods) or (set(METHODS) - {"39_suite2p_rigid"})
        unknown = set(method_ids) - set(METHODS)
        if unknown:
            raise ValueError(f"unknown methods: {sorted(unknown)}")
        report = Path(args.conformance_report).resolve() if args.conformance_report else None
        return self_test(sorted(method_ids), report)
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
