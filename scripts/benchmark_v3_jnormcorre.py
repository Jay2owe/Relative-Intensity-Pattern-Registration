#!/usr/bin/env python3
"""JNormCorre rigid adapter for Registration Benchmark v3.

This runner deliberately lives in JNormCorre's isolated environment.  It imports the shared v3
TIFF, normalization, result-schema, and truth-scoring code without importing any competing
registration package.  JNormCorre's ``shifts_rig`` are row/column correction shifts; the adapter
negates and converts them to the benchmark's x/y content-motion convention, then anchors frame
zero at (0, 0).
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
import time
import traceback
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchmark_v3_python as shared  # noqa: E402

from jnormcorre.motion_correction import MotionCorrect  # noqa: E402
from jnormcorre.utils.lazy_array import lazy_data_loader  # noqa: E402


METHOD = shared.Method(
    "42_jnormcorre_rigid", "JNormCorre rigid", "NORMCORRE",
    "native_batch_template", "jnormcorre", "jn_max6_iter1_default",
)


class ArrayLoader(lazy_data_loader):
    """Minimal, read-only JNormCorre loader backed by one in-memory TYX array."""

    def __init__(self, frames: np.ndarray):
        self._frames = np.ascontiguousarray(frames, dtype=np.float32)

    @property
    def dtype(self) -> str:
        return str(self._frames.dtype)

    @property
    def shape(self) -> tuple[int, int, int]:
        return self._frames.shape

    def _compute_at_indices(self, indices: int | slice) -> np.ndarray:
        return self._frames[indices]


def solve(frames: np.ndarray, max_shift: int, iterations: int,
          frames_per_split: int) -> tuple[np.ndarray, int, str]:
    movie = np.ascontiguousarray(frames, dtype=np.float32)
    correction = MotionCorrect(
        ArrayLoader(movie), max_shifts=(max_shift, max_shift),
        frames_per_split=max(2, min(frames_per_split, len(movie))),
        niter_rig=iterations, pw_rigid=False,
    )
    # A fixed first-frame template gives the comparator the same reference information as all
    # other canonical pair/template methods and prevents hidden test-set template adaptation.
    correction.motion_correct(template=np.ascontiguousarray(movie[0]), save_movie=False)
    shifts_yx = np.asarray(correction.shifts_rig, dtype=np.float64)
    if shifts_yx.shape != (len(movie), 2) or not np.all(np.isfinite(shifts_yx)):
        raise RuntimeError(f"JNormCorre emitted invalid shifts with shape {shifts_yx.shape}")
    transforms = -shifts_yx[:, ::-1].copy()
    transforms -= transforms[0]
    return transforms, len(movie), "native_batch_template"


def completed_keys(path: Path) -> set[tuple[str, ...]]:
    if not path.is_file():
        return set()
    with path.open("r", newline="", encoding="utf-8") as handle:
        return {(row["series_id"], row["motion_category"], row["condition_instance"],
                 row["method_id"], row["preprocessing_arm"], row.get("config_id", ""))
                for row in csv.DictReader(handle)}


def run(args: argparse.Namespace) -> int:
    project = Path(args.project).resolve()
    root = project / "library" / "benchmark" / "v3" / "inputs" / args.split
    manifest = root / "inputs_manifest.csv"
    if not manifest.is_file():
        raise FileNotFoundError(manifest)
    output = (Path(args.output).resolve() if args.output else
              project / "library" / "benchmark" / "v3" / "runs" /
              args.split / "jnormcorre_results.csv")
    output.parent.mkdir(parents=True, exist_ok=True)
    if args.rewrite or not output.exists():
        with output.open("w", newline="", encoding="utf-8") as handle:
            csv.DictWriter(handle, fieldnames=shared.RESULT_FIELDS).writeheader()
    done = set() if args.rewrite else completed_keys(output)
    sources = shared.split_values(args.only_source)
    conditions = shared.split_values(args.only_condition)
    motions = shared.split_values(args.only_motion)
    arms = [item.strip() for item in args.preprocessing_arms.split(",") if item.strip()]
    if not arms or any(arm not in {"native", "common_normalized"} for arm in arms):
        raise ValueError("preprocessing arms must be native and/or common_normalized")
    config_id = args.config_id or METHOD.config_id
    with manifest.open("r", newline="", encoding="utf-8") as handle:
        inputs = list(csv.DictReader(handle))
    successes = failures = 0
    with output.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=shared.RESULT_FIELDS)
        for input_row in inputs:
            if not shared.selected(input_row["series_id"], sources):
                continue
            if not shared.selected(input_row["condition_id"], conditions):
                continue
            if not shared.selected(input_row["motion_category"], motions):
                continue
            native = shared.load_stack(root / input_row["input_relative_path"])
            for arm in arms:
                key = (input_row["series_id"], input_row["motion_category"],
                       input_row["condition_instance"], METHOD.method_id, arm, config_id)
                if key in done:
                    continue
                frames, fallback = ((native, False) if arm == "native" else
                                    shared.common_normalize(native))
                cpu0 = time.process_time()
                wall0 = time.perf_counter()
                transforms = None
                pairs = 0
                status = "ok"
                details = ""
                try:
                    transforms, pairs, strategy = solve(
                        frames, args.max_shift, args.iterations, args.frames_per_split)
                except Exception as error:
                    strategy = METHOD.strategy
                    status = f"failed:{type(error).__name__}:{error}"
                    details = traceback.format_exc(limit=8).replace("\r", " ").replace("\n", " | ")
                row = shared.result_row(
                    input_row, METHOD, arm, fallback, status, transforms, pairs, strategy,
                    time.process_time() - cpu0, time.perf_counter() - wall0,
                    shared.rss_mb(), details, config_id,
                )
                writer.writerow(row)
                handle.flush()
                done.add(key)
                if transforms is None:
                    failures += 1
                    print(f"FAILED {input_row['series_id']} {input_row['condition_instance']} "
                          f"{arm}: {status}", flush=True)
                else:
                    successes += 1
                    print(f"OK {input_row['series_id']} {input_row['condition_instance']} {arm} "
                          f"median={row['median_error_px']} px", flush=True)
    print(f"v3 JNormCorre complete: {successes} successful rows, {failures} failed rows; {output}")
    return 0


def self_test(report: Path | None) -> int:
    cases = ((0.0, 0.0), (2.0, 0.0), (-2.0, 0.0), (0.0, 2.0),
             (0.0, -2.0), (1.75, -1.25), (-0.25, 0.25), (6.0, -4.0))
    tolerance = 1.25
    rows: list[dict[str, object]] = []
    failures: list[str] = []
    for dx, dy in cases:
        reference, moving = shared.conformance_fixture(dx, dy)
        estimated_x = estimated_y = error = math.nan
        status = "pass"
        details = ""
        try:
            transforms, _, _ = solve(np.stack((reference, moving)), 15, 1, 2)
            estimated_x, estimated_y = map(float, transforms[1])
            error = math.hypot(estimated_x - dx, estimated_y - dy)
            if not math.isfinite(error) or error > tolerance:
                status = "fail"
        except Exception as exc:
            status = "fail"
            details = f"{type(exc).__name__}:{exc}"
        if status != "pass":
            failures.append(f"({dx},{dy}) estimated=({estimated_x},{estimated_y}) error={error} {details}")
        rows.append({"method_id": METHOD.method_id, "truth_x_px": dx, "truth_y_px": dy,
                     "estimated_x_px": estimated_x, "estimated_y_px": estimated_y,
                     "error_px": error, "tolerance_px": tolerance,
                     "status": status, "details": details})
        print(f"{status.upper()} truth=({dx:.2f},{dy:.2f}) "
              f"estimated=({estimated_x:.3f},{estimated_y:.3f}) error={error:.3f}", flush=True)
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    if failures:
        print("JNormCorre conformance failures:\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"JNormCorre adapter conformance passed: {len(rows)} cases")
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--project", default=str(Path(__file__).resolve().parents[1]))
    result.add_argument("--split", default="development")
    result.add_argument("--output")
    result.add_argument("--preprocessing-arms", default="native,common_normalized")
    result.add_argument("--only-source", default="")
    result.add_argument("--only-condition", default="")
    result.add_argument("--only-motion", default="")
    result.add_argument("--config-id")
    result.add_argument("--max-shift", type=int, choices=(6, 15, 30), default=6)
    result.add_argument("--iterations", type=int, choices=(1, 2), default=1)
    result.add_argument("--frames-per-split", type=int, default=1000)
    result.add_argument("--rewrite", action="store_true")
    result.add_argument("--self-test", action="store_true")
    result.add_argument("--conformance-report")
    return result


def main() -> int:
    args = parser().parse_args()
    if args.self_test:
        report = Path(args.conformance_report).resolve() if args.conformance_report else None
        return self_test(report)
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
