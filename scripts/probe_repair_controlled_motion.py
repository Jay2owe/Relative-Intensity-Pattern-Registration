"""The outlier guard on the benchmark's declared jumps, where the truth is known.

`benchmarks/controlled_motion/*/INTERMITTENT_JUMPS/` is a declared camera path:
slow drift interrupted by three abrupt stage jumps, applied at four times
resolution and box-averaged down, so the movement is exact and belongs to no
method. Twenty folders, four series in each of the five image types.

Three trajectories are compared per folder, all from one run:

    guard off    the pre-repair reconciliation. `_repair` with outlier_mads = 0
                 leaves a fully supported trajectory untouched, so this is what
                 the shipped Java recommendation produces for declared jumps
    guard on     `_repair` at outlier_mads = 6.0, which is what
                 `LogRatioParameters.recommended(...)` produces in Python for
                 the same declaration
    proposed     the pair-agreement gate from
                 `docs/repair_pair_support_findings.md`

The reference is the published arm `01_log_ratio_tukey_standard_gradient`,
whose own median error against the injected truth is recorded per folder in
`comparison.csv` -- around 0.01 px, three orders below the effect measured
here. It is a proxy for truth, and its error is reported alongside so the
proxy cannot be mistaken for truth itself.

    python scripts/probe_repair_controlled_motion.py --all --workers 12

Reads only `benchmarks/controlled_motion/`, which
`docs/external_parameter_sweep_sealed_set_declaration.md` names as the
development set. It does not read, score or probe `locked_test`, `sealed_test`
or `sealed_test_3`.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import tifffile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from ripr.core import (  # noqa: E402
    AlignerOptions,
    RegistrationOptions,
    _repair,
    register_frames,
)
from ripr.types import (  # noqa: E402
    PixelSupport,
    Reference,
    RepairReason,
    RobustNorm,
)
from probe_repair_pair_support import (  # noqa: E402
    RepairArguments,
    corroboration,
    tolerance_for,
)

CONTROLLED = ROOT / "library/benchmark/v2/benchmarks/controlled_motion"
PROFILE = "INTERMITTENT_JUMPS"
CONDITION = "CLEAN"
ARM = "01_log_ratio_tukey_standard_gradient"
#: what `LogRatioParameters.recommended(motion_type="intermittent_jumps")`
#: currently produces in Python; the Java builder produces 0.0 for the same
#: declaration (RelativeIntensityPatternParameters.java:534)
PYTHON_RECOMMENDED_MADS = 6.0
OUT = ROOT / "tmp" / "repair_controlled_motion"


def folders() -> list[str]:
    found = []
    for image_type in sorted(p for p in CONTROLLED.iterdir()
                             if p.is_dir() and p.name != "summaries"):
        for series in sorted(p for p in image_type.iterdir() if p.is_dir()):
            leaf = series / PROFILE / CONDITION
            if (leaf / "00_input_uncorrected.tif").exists():
                found.append(f"{image_type.name}/{series.name}")
    return found


def published(leaf: Path) -> list[tuple[float, float]]:
    rows = csv.DictReader((leaf / f"{ARM}_transforms.csv").open())
    return [(float(r["x_px"]), float(r["y_px"])) for r in rows]


def published_error(leaf: Path) -> float:
    for row in csv.DictReader((leaf / "comparison.csv").open()):
        if row[next(iter(row))] == ARM:
            return float(row["median_error_px"])
    return math.nan


def distances(transforms, reference) -> np.ndarray:
    return np.array([math.hypot(t.dx - x, t.dy - y)
                     for t, (x, y) in zip(transforms, reference)])


def probe(name: str) -> dict:
    leaf = CONTROLLED / name / PROFILE / CONDITION
    stack = tifffile.imread(leaf / "00_input_uncorrected.tif")
    frames = np.asarray(stack if stack.ndim == 3 else stack[:, 0], np.float32)
    del stack

    options = RegistrationOptions(
        aligner=AlignerOptions(norm=RobustNorm.TUKEY,
                               support=PixelSupport.GRADIENT),
        reference=Reference.MULTILAG, lags=(1, 2, 4, 8, 16),
        outlier_mads=PYTHON_RECOMMENDED_MADS)

    started = time.time()
    catcher = RepairArguments()
    with catcher:
        result = register_frames(frames, options)
    caught = catcher.caught
    if caught is None:
        raise SystemExit(f"{name}: _repair never ran")

    raw = list(caught["cumulative"])
    guard_on = list(caught["output"])
    flagged = [t for t, reason in enumerate(caught["reasons"])
               if reason is RepairReason.OUTLIER_STEP]

    tolerance = tolerance_for(result.pairs)
    evidence = {t: corroboration(result.pairs, t, tolerance) for t in flagged}
    protect = [False] * len(frames)
    for t, found in evidence.items():
        protect[t] = bool(found["protect"])
    proposed = list(_repair(raw, caught["support"], caught["anchor"],
                            caught["outlier_mads"], caught["width"],
                            caught["height"], None, protect)[0])

    # with outlier_mads = 0 the outlier branch never runs; every other repair
    # reason is untouched, so this is the guard-off trajectory from the same run
    guard_off = list(_repair(raw, caught["support"], caught["anchor"], 0.0,
                             caught["width"], caught["height"])[0])

    reference = published(leaf)
    mine = {"guard_off": guard_off, "guard_on": guard_on, "proposed": proposed}
    record = {
        "folder": name,
        "image_type": name.split("/")[0],
        "frames": len(frames),
        "shape": list(frames.shape[1:]),
        "seconds": round(time.time() - started, 1),
        "median_step_px": float(caught["median"]),
        "limit_px": float(caught["limit"]),
        "biggest_step_px": float(np.max(caught["magnitudes"])),
        "outlier_mads": PYTHON_RECOMMENDED_MADS,
        "refused_by_guard": flagged,
        "protected_by_proposal": [t for t in flagged if protect[t]],
        "published_arm_median_error_px": published_error(leaf),
        "against_published_arm": {
            key: {"median_px": float(np.median(distances(trace, reference))),
                  "worst_px": float(np.max(distances(trace, reference)))}
            for key, trace in mine.items()},
        "evidence": {str(t): found for t, found in evidence.items()},
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name.replace('/', '__')}.json").write_text(json.dumps(record, indent=2))
    return record


def table(records: list[dict]) -> str:
    lines = [f"{'folder':<50} {'refused':>7} {'kept':>4} "
             f"{'guard off':>10} {'guard on':>9} {'proposed':>9}",
             "-" * 95]
    for record in records:
        against = record["against_published_arm"]
        lines.append(
            f"{record['folder']:<50} {len(record['refused_by_guard']):>7} "
            f"{len(record['protected_by_proposal']):>4} "
            f"{against['guard_off']['worst_px']:>10.4f} "
            f"{against['guard_on']['worst_px']:>9.4f} "
            f"{against['proposed']['worst_px']:>9.4f}")
    worst = lambda key: [r["against_published_arm"][key]["worst_px"] for r in records]  # noqa: E731
    lines.append("-" * 95)
    lines.append(f"{'worst over all folders, px from the published arm':<50} "
                 f"{'':>7} {'':>4} "
                 f"{max(worst('guard_off'), default=0):>10.4f} "
                 f"{max(worst('guard_on'), default=0):>9.4f} "
                 f"{max(worst('proposed'), default=0):>9.4f}")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("folder", nargs="?")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--collect", action="store_true")
    arguments = parser.parse_args()

    if arguments.collect:
        print(table([json.loads(p.read_text())
                     for p in sorted(OUT.glob("*.json"))]))
        return
    if arguments.folder and not arguments.all:
        print(json.dumps(probe(arguments.folder), indent=2))
        return
    if not arguments.all:
        parser.error("name a folder, or pass --all")

    OUT.mkdir(parents=True, exist_ok=True)
    todo = folders()
    print(f"{len(todo)} {PROFILE} folders, {arguments.workers} at a time")
    environment = dict(os.environ, OMP_NUM_THREADS="1", MKL_NUM_THREADS="1",
                       OPENBLAS_NUM_THREADS="1")
    running: list[tuple[str, subprocess.Popen]] = []
    started = time.time()
    while todo or running:
        while todo and len(running) < arguments.workers:
            name = todo.pop(0)
            log = (OUT / f"{name.replace('/', '__')}.log").open("w")
            running.append((name, subprocess.Popen(
                [sys.executable, str(Path(__file__).resolve()), name],
                cwd=str(ROOT), env=environment, stdout=log, stderr=log)))
        time.sleep(2.0)
        for name, process in list(running):
            if process.poll() is not None:
                running.remove((name, process))
                if process.returncode:
                    print(f"  {name}: FAILED ({process.returncode})")
    print(f"  done in {time.time() - started:.0f}s\n")
    print(table([json.loads(p.read_text()) for p in sorted(OUT.glob("*.json"))]))


if __name__ == "__main__":
    main()
