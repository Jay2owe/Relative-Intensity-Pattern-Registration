"""Whole-recording Java/Python parity harness.

Runs one real TIFF stack through the Java engine (``ripr.core.EndToEndParityProbe``) and through the
Python package under matched settings, then reports the first frame and column where they disagree.

The Java probe and this script write the same per-frame columns, so a divergence names a frame and a
quantity instead of a single summary number. Timings are recorded for both languages on the same
input, but they are only comparable once the parity check for that case passes.

Usage::

    python scripts/cross_language_parity.py --list
    python scripts/cross_language_parity.py --case microglia_brightfield_recommended
    python scripts/cross_language_parity.py --all --frames 12
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import tifffile

ROOT = Path(__file__).resolve().parents[1]
STACKS = ROOT / "RIPR_after_speedup_bundles"

# Absolute tolerances per column. Translations and angles are the decision-bearing quantities and are
# held tightest; residuals and gains accumulate over a whole chain and are allowed the looser bound
# that scalar Java loops and NumPy reductions genuinely differ by.
TOLERANCE = {
    "dx": 1e-6,
    "dy": 1e-6,
    "theta": 1e-9,
    "log2_gain": 1e-6,
    "residual_before": 1e-5,
    "residual_after": 1e-5,
    "valid_fraction": 1e-9,
}
EXACT = ("support", "status", "repair")


@dataclass(frozen=True)
class Case:
    """One parity comparison: a stack plus the settings both languages are given."""

    name: str
    stack: str
    image_type: str
    motion_type: str
    selection_mode: str
    channel: int = 1


CASES: tuple[Case, ...] = (
    Case("microglia_brightfield_recommended", "microglia_1432_brightfield",
         "BRIGHTFIELD_DIC", "SUBPIXEL_RANDOM_WALK", "RECOMMENDED"),
    Case("microglia_brightfield_automatic", "microglia_1432_brightfield",
         "BRIGHTFIELD_DIC", "SUBPIXEL_RANDOM_WALK", "AUTOMATIC"),
    Case("microglia_brightfield_longitudinal", "microglia_1432_brightfield",
         "BRIGHTFIELD_DIC", "SUBPIXEL_RANDOM_WALK", "LONGITUDINAL_ACCURACY"),
    Case("incucyte_green_recommended", "incucyte_vid74_a1_green",
         "DENSE_FLUORESCENCE", "INTERMITTENT_JUMPS", "RECOMMENDED"),
    Case("incucyte_green_automatic", "incucyte_vid74_a1_green",
         "DENSE_FLUORESCENCE", "INTERMITTENT_JUMPS", "AUTOMATIC"),
    Case("per2_biolum_recommended", "per2_a1",
         "SPARSE_LOW_LIGHT_FLUORESCENCE", "CURVED_OSCILLATING_DRIFT", "RECOMMENDED"),
    Case("per2_biolum_longitudinal", "per2_a1",
         "SPARSE_LOW_LIGHT_FLUORESCENCE", "CURVED_OSCILLATING_DRIFT", "LONGITUDINAL_ACCURACY"),
)


def classpath() -> str:
    """Test classes, main classes and the one runtime dependency (ImageJ's ij.jar)."""
    dependencies = ROOT / "target" / "test-classpath.txt"
    if not dependencies.exists():
        raise SystemExit(
            "target/test-classpath.txt is missing. Run:\n"
            "  mvn -o dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt"
        )
    return os.pathsep.join((
        str(ROOT / "target" / "test-classes"),
        str(ROOT / "target" / "classes"),
        dependencies.read_text(encoding="utf-8").strip(),
    ))


def prepared_stack(case: Case, frames: int, workspace: Path) -> tuple[Path, np.ndarray]:
    """The stack both languages read, truncated identically when a frame limit is given."""
    source = STACKS / f"{case.stack}.tif"
    if not source.exists():
        raise SystemExit(f"missing stack {source}")
    array = tifffile.imread(source)
    if frames and array.shape[0] > frames:
        array = array[:frames]
        path = workspace / f"{case.stack}_{frames}.tif"
        if not path.exists():
            # imagej=True and an explicit photometric keep a 3-slice uint8 stack from being read
            # back as one RGB image, which would silently compare a 1-frame run against a 3-frame one.
            tifffile.imwrite(path, array, imagej=True, photometric="minisblack",
                             metadata={"axes": "TYX"})
        return path, array
    return source, array


def run_java(case: Case, stack: Path, workspace: Path) -> tuple[list[dict], float]:
    java = shutil.which("java")
    if not java:
        raise SystemExit("java is not on PATH")
    output = workspace / f"{case.name}_java.csv"
    started = time.perf_counter()
    completed = subprocess.run(
        [java, "-Djava.awt.headless=true", "-cp", classpath(),
         "ripr.core.EndToEndParityProbe", str(stack), case.image_type, case.motion_type,
         case.selection_mode, str(case.channel), str(output), "1"],
        cwd=ROOT, text=True, capture_output=True, check=False,
    )
    wall = time.perf_counter() - started
    if completed.returncode != 0:
        raise SystemExit(f"Java probe failed for {case.name}:\n{completed.stdout}\n{completed.stderr}")
    rows, engine = read_probe_csv(output)
    pairs_path = Path(str(output)[:-4] + "_pairs.csv")
    pairs = (list(csv.DictReader(pairs_path.read_text(encoding="utf-8").splitlines()))
             if pairs_path.exists() else [])
    return rows, pairs, engine if engine is not None else wall


def read_probe_csv(path: Path) -> tuple[list[dict], float | None]:
    engine = None
    lines = path.read_text(encoding="utf-8").splitlines()
    body = []
    for line in lines:
        if line.startswith("# elapsed_seconds,"):
            engine = float(line.split(",", 1)[1])
        elif not line.startswith("#"):
            body.append(line)
    return list(csv.DictReader(body)), engine


def run_python(case: Case, array: np.ndarray) -> tuple[list[dict], float]:
    from ripr import ImageType, LogRatioParameters, MotionType, SelectionMode
    from ripr.registration import estimate

    parameters = LogRatioParameters.recommended(
        image_type=ImageType[case.image_type],
        motion_type=MotionType[case.motion_type],
    )
    parameters = dataclass_replace(parameters, channel=case.channel, threads=1)
    if case.selection_mode != "RECOMMENDED":
        parameters = dataclass_replace(
            parameters, selection_mode=SelectionMode[case.selection_mode])
    started = time.perf_counter()
    result = estimate(array, parameters, axes="TYX")
    elapsed = time.perf_counter() - started
    registration = getattr(result, "registration", result)
    rows = []
    for frame, transform in enumerate(registration.cumulative):
        status = registration.status[frame]
        repair = registration.repairs[frame]
        rows.append({
            "frame": str(frame),
            "dx": repr_double(transform.dx),
            "dy": repr_double(transform.dy),
            "theta": repr_double(transform.theta),
            "log2_gain": repr_double(float(registration.log2_gain[frame])),
            "support": str(int(registration.support[frame])),
            "residual_before": repr_double(float(registration.residual_before[frame])),
            "residual_after": repr_double(float(registration.residual_after[frame])),
            "valid_fraction": repr_double(float(registration.valid_fraction[frame])),
            "status": "" if status is None else status.name,
            "repair": "" if repair is None else repair.name,
        })
    pairs = []
    for pair in registration.pairs:
        fit = pair.fit
        pairs.append({
            "from": str(pair.from_frame),
            "to": str(pair.to_frame),
            "dx": repr_double(fit.transform.dx),
            "dy": repr_double(fit.transform.dy),
            "theta": repr_double(fit.transform.theta),
            "residual_before": repr_double(fit.residual_before),
            "residual_after": repr_double(fit.residual_after),
            "log_gain": repr_double(fit.log_gain),
            "valid_fraction": repr_double(fit.valid_fraction),
            "iterations": str(fit.iterations),
            "status": "" if fit.status is None else fit.status.name,
        })
    return rows, pairs, elapsed


def dataclass_replace(instance, **changes):
    from dataclasses import replace
    return replace(instance, **changes)


def repr_double(value: float) -> str:
    if math.isnan(value):
        return "nan"
    if math.isinf(value):
        return "inf" if value > 0 else "-inf"
    return repr(float(value))


def as_float(text: str) -> float:
    return float(text)


def compare(java_rows: list[dict], python_rows: list[dict]) -> list[str]:
    """Every disagreement, named by frame and column, worst-first within each column."""
    problems: list[str] = []
    if len(java_rows) != len(python_rows):
        return [f"frame count differs: java {len(java_rows)} vs python {len(python_rows)}"]
    worst: dict[str, tuple[float, int, str, str]] = {}
    for frame, (j, p) in enumerate(zip(java_rows, python_rows)):
        for column in EXACT:
            if j[column] != p[column]:
                problems.append(
                    f"frame {frame} {column}: java {j[column]!r} vs python {p[column]!r}")
        for column, tolerance in TOLERANCE.items():
            jv, pv = as_float(j[column]), as_float(p[column])
            if math.isnan(jv) and math.isnan(pv):
                continue
            delta = abs(jv - pv)
            if not (delta <= tolerance):
                if column not in worst or delta > worst[column][0]:
                    worst[column] = (delta, frame, j[column], p[column])
    for column, (delta, frame, jv, pv) in sorted(worst.items(), key=lambda kv: -kv[1][0]):
        problems.append(
            f"{column}: worst |delta|={delta:.6g} > {TOLERANCE[column]:g} at frame {frame} "
            f"(java {jv} vs python {pv})")
    return problems


PAIR_TOLERANCE = {
    "dx": 1e-9,
    "dy": 1e-9,
    "theta": 1e-12,
    "residual_before": 1e-9,
    "residual_after": 1e-9,
    "log_gain": 1e-9,
    "valid_fraction": 1e-12,
}


def compare_pairs(java_pairs: list[dict], python_pairs: list[dict]) -> list[str]:
    """Divergence in one pair fit, before any chaining can spread or mask it."""
    if not java_pairs or not python_pairs:
        return []
    problems: list[str] = []
    java_index = {(row["from"], row["to"]): row for row in java_pairs}
    python_index = {(row["from"], row["to"]): row for row in python_pairs}
    only_java = sorted(set(java_index) - set(python_index))
    only_python = sorted(set(python_index) - set(java_index))
    if only_java:
        problems.append(f"{len(only_java)} pairs only Java scheduled, first {only_java[0]}")
    if only_python:
        problems.append(f"{len(only_python)} pairs only Python scheduled, first {only_python[0]}")
    worst: dict[str, tuple[float, tuple[str, str], str, str]] = {}
    for key in sorted(set(java_index) & set(python_index)):
        j, p = java_index[key], python_index[key]
        if j["status"] != p["status"]:
            problems.append(f"pair {key} status: java {j['status']!r} vs python {p['status']!r}")
        if j["iterations"] != p["iterations"]:
            problems.append(
                f"pair {key} iterations: java {j['iterations']} vs python {p['iterations']}")
        for column, tolerance in PAIR_TOLERANCE.items():
            jv, pv = float(j[column]), float(p[column])
            if math.isnan(jv) and math.isnan(pv):
                continue
            delta = abs(jv - pv)
            if not (delta <= tolerance) and (column not in worst or delta > worst[column][0]):
                worst[column] = (delta, key, j[column], p[column])
    for column, (delta, key, jv, pv) in sorted(worst.items(), key=lambda kv: -kv[1][0]):
        problems.append(f"pair {column}: worst |delta|={delta:.6g} at pair {key} "
                        f"(java {jv} vs python {pv})")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--frames", type=int, default=0,
                        help="truncate both languages to this many frames (0 = whole recording)")
    parser.add_argument("--workspace", type=Path,
                        default=Path(os.environ.get("TEMP", "/tmp")) / "ripr_parity")
    parser.add_argument("--json", type=Path, default=None)
    arguments = parser.parse_args()

    if arguments.list:
        for case in CASES:
            print(f"{case.name:40s} {case.stack:28s} {case.selection_mode}")
        return 0

    selected = [c for c in CASES if c.name in arguments.case] if arguments.case else list(CASES)
    if not arguments.all and not arguments.case:
        selected = list(CASES)
    if arguments.case and not selected:
        raise SystemExit(f"no case matched {arguments.case}")

    arguments.workspace.mkdir(parents=True, exist_ok=True)
    report = []
    failures = 0
    for case in selected:
        stack, array = prepared_stack(case, arguments.frames, arguments.workspace)
        print(f"\n=== {case.name}  ({array.shape[0]} frames, {array.shape[-2]}x{array.shape[-1]}) ===",
              flush=True)
        java_rows, java_pairs, java_seconds = run_java(case, stack, arguments.workspace)
        print(f"  java   {java_seconds:8.2f}s", flush=True)
        python_rows, python_pairs, python_seconds = run_python(case, array)
        print(f"  python {python_seconds:8.2f}s  ({python_seconds / java_seconds:.1f}x java)",
              flush=True)
        pair_problems = compare_pairs(java_pairs, python_pairs)
        if pair_problems:
            print(f"  PAIR-LEVEL divergence ({len(pair_problems)} findings) "
                  f"-- the estimator, not the reconciler")
            for line in pair_problems[:10]:
                print(f"    {line}")
        elif java_pairs:
            print(f"  pair fits bit-exact ({len(java_pairs)} pairs)")
        problems = compare(java_rows, python_rows)
        if problems:
            failures += 1
            print(f"  PARITY FAIL ({len(problems)} findings)")
            for line in problems[:12]:
                print(f"    {line}")
        else:
            print("  PARITY OK")
        report.append({
            "case": case.name,
            "pair_problems": pair_problems,
            "frames": int(array.shape[0]),
            "java_seconds": java_seconds,
            "python_seconds": python_seconds,
            "ratio": python_seconds / java_seconds,
            "parity": not problems,
            "problems": problems,
        })

    print(f"\n{len(selected) - failures}/{len(selected)} cases at parity")
    if arguments.json:
        arguments.json.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"wrote {arguments.json}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
