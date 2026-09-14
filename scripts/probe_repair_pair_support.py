"""Would asking the pairs before refusing a step change any library trace?

`_repair` refuses a cumulative step whose size exceeds
``max(median + 8 * robust_scale, 6 * median)`` and replaces that frame's
position with the midpoint of its neighbours. It reads step magnitudes only. On
`library/06_knock` that rejects a real 35.2 px knock which nine independent pair
fits all measured, leaving one frame about 17.6 px out.

This probe measures what happens if a flagged step is protected when the pairs
that cross it corroborate the raw solution. It changes no engine code. Both
rules are executed by the shipped `_repair`; the only difference is the
`protected_event_frames` argument, which `_repair` already accepts:

    rule A (shipped)   _repair(raw, support, anchor, mads, w, h)
    rule B (proposed)  _repair(raw, support, anchor, mads, w, h,
                               protected_event_frames=corroborated)

so rule A's answer is the run's own output, not a re-implementation of it.

Corroboration uses evidence the reconciliation already produces. Every pair
carries `influence.standardized_residual`: the root-mean-square disagreement,
in pixels, between that pair's own measured transform and the reconciled
trajectory before any repair. A step is corroborated when enough of the pairs
spanning it sit inside a tolerance set by the recording's own median.

The verdict is the library's own quality figure -- the mean per-pixel temporal
standard deviation inside the valid margin, after dividing each frame by its own
median so a brightness change cannot flatter it -- computed for both rules on
the same margin, so lower really is better.

    python scripts/probe_repair_pair_support.py 06_knock
    python scripts/probe_repair_pair_support.py --all --workers 12

One entry writes `tmp/repair_probe/<entry>.json`. `--all` fans the entries out
over separate processes and prints the table when they finish.
"""

from __future__ import annotations

import argparse
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

from ripr.core import (  # noqa: E402
    AlignerOptions,
    RegistrationOptions,
    _repair,
    register_frames,
    valid_margin,
    warp_plane,
)
from ripr.types import (  # noqa: E402
    Interpolation,
    PixelSupport,
    Reference,
    RepairReason,
    RobustNorm,
    Transform,
)

OUT = ROOT / "tmp" / "repair_probe"

#: a crossing pair counts as agreeing when its standardized residual is inside
#: this many times the recording's own median standardized residual, with a
#: floor of one pixel so a near-perfect recording does not set an impossible bar
AGREEMENT_FACTOR = 3.0
AGREEMENT_FLOOR = 1.0
#: how many pairs must span the step, and what share of them must agree
MINIMUM_CROSSING = 3
MINIMUM_SHARE = 0.75


# --------------------------------------------------------------------------
# the run
# --------------------------------------------------------------------------

def settings(entry: Path) -> tuple[int, RegistrationOptions]:
    """The options `ValidationRun.java` used to write this entry's shifts.csv."""
    text = {}
    for line in (entry / "dataset.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            text[key.strip()] = value.strip()
    options = RegistrationOptions(
        aligner=AlignerOptions(norm=RobustNorm.TUKEY,
                               support=PixelSupport.GRADIENT,
                               max_shift=float(text.get("maxShift", "30"))),
        reference=Reference.MULTILAG,
        lags=(1, 2, 4, 8, 16),
    )
    return int(text.get("channel", "1")), options


class RepairArguments:
    """The arguments the run handed `_repair`, caught on the way past.

    A return tracer, not a patch: `_repair` is called once, and its own frame
    holds both what it was given and what it decided.
    """

    WANTED = ("cumulative", "support", "anchor", "outlier_mads", "width",
              "height", "output", "reasons", "magnitudes", "median", "scale",
              "limit")

    def __init__(self) -> None:
        self.caught: dict | None = None
        self._previous = None

    def _local(self, frame, event, arg):
        if event == "return" and frame.f_code.co_name == "_repair":
            held = frame.f_locals
            self.caught = {name: held[name] for name in self.WANTED
                           if name in held}
            self.caught["output"], self.caught["reasons"] = arg
        return self._local

    def _global(self, frame, event, arg):
        if frame.f_code.co_name == "_repair":
            frame.f_trace_lines = False
            return self._local
        return None

    def __enter__(self) -> "RepairArguments":
        self._previous = sys.gettrace()
        sys.settrace(self._global)
        return self

    def __exit__(self, *exc) -> None:
        sys.settrace(self._previous)


# --------------------------------------------------------------------------
# the proposed gate
# --------------------------------------------------------------------------

def corroboration(pairs, frame: int, tolerance: float) -> dict:
    """What the pairs spanning the step into `frame` say about it."""
    crossing = [p for p in pairs
                if p.fit.usable and p.influence is not None
                and min(p.from_frame, p.to_frame) <= frame - 1
                and max(p.from_frame, p.to_frame) >= frame]
    residuals = [p.influence.standardized_residual for p in crossing]
    agreeing = [p for p, r in zip(crossing, residuals)
                if math.isfinite(r) and r <= tolerance]
    return {
        "crossing": len(crossing),
        "agreeing": len(agreeing),
        "tolerance_px": tolerance,
        "median_residual_px": (
            float(np.median([r for r in residuals if math.isfinite(r)]))
            if any(math.isfinite(r) for r in residuals) else math.nan),
        "measured_dx": sorted(round(p.fit.transform.dx, 2) for p in crossing),
        "protect": (len(crossing) >= MINIMUM_CROSSING
                    and len(agreeing) >= MINIMUM_SHARE * len(crossing)),
    }


def tolerance_for(pairs) -> float:
    residuals = [p.influence.standardized_residual for p in pairs
                 if p.fit.usable and p.influence is not None
                 and math.isfinite(p.influence.standardized_residual)]
    if not residuals:
        return AGREEMENT_FLOOR
    return max(AGREEMENT_FLOOR, AGREEMENT_FACTOR * float(np.median(residuals)))


# --------------------------------------------------------------------------
# the verdict
# --------------------------------------------------------------------------

def widest(*margins):
    return type(margins[0])(max(m.top for m in margins),
                            max(m.bottom for m in margins),
                            max(m.left for m in margins),
                            max(m.right for m in margins))


def mean_temporal_sd(planes: np.ndarray, margin) -> float:
    """The library's own score: mean per-pixel temporal SD in the valid margin.

    Each frame is divided by its own median first, so a brightness change
    cannot be mistaken for movement removed, exactly as `ValidationRun.meanSd`
    does it.
    """
    height, width = planes.shape[1:]
    view = planes[:, margin.top:height - margin.bottom,
                  margin.left:width - margin.right]
    medians = np.array([np.median(frame[np.isfinite(frame)])
                        if np.isfinite(frame).any() else 0.0 for frame in view])
    scale = np.where(medians > 0, medians[0] / np.where(medians > 0, medians, 1),
                     1.0)
    scaled = view * scale[:, None, None]
    return float(np.nanmean(np.sqrt(np.nanmean(
        (scaled - np.nanmean(scaled, axis=0)) ** 2, axis=0))))


def warped(frames: np.ndarray, transforms) -> np.ndarray:
    return np.stack([warp_plane(frame, transform, Interpolation.BILINEAR, np.nan)
                     for frame, transform in zip(frames, transforms)])


# --------------------------------------------------------------------------
# one entry
# --------------------------------------------------------------------------

def probe(name: str) -> dict:
    entry = ROOT / "library" / name
    channel, options = settings(entry)
    stack = tifffile.imread(entry / "original.tif")
    frames = np.asarray(stack[:, channel - 1] if stack.ndim == 4 else stack,
                        np.float32)
    del stack
    count, height, width = frames.shape

    started = time.time()
    catcher = RepairArguments()
    with catcher:
        result = register_frames(frames, options)
    seconds = time.time() - started
    caught = catcher.caught
    if caught is None:
        raise SystemExit(f"{name}: _repair never ran")

    raw = list(caught["cumulative"])
    shipped = list(caught["output"])
    flagged = [t for t, reason in enumerate(caught["reasons"])
               if reason is RepairReason.OUTLIER_STEP]

    tolerance = tolerance_for(result.pairs)
    evidence = {t: corroboration(result.pairs, t, tolerance) for t in flagged}
    protect = [False] * count
    for t, found in evidence.items():
        protect[t] = bool(found["protect"])

    proposed, reasons_b = _repair(
        raw, caught["support"], caught["anchor"], caught["outlier_mads"],
        caught["width"], caught["height"], None, protect)
    proposed = list(proposed)

    moved = [t for t in range(count)
             if math.hypot(shipped[t].dx - proposed[t].dx,
                           shipped[t].dy - proposed[t].dy) > 1e-9]
    record: dict = {
        "entry": name,
        "frames": count,
        "shape": [height, width],
        "max_shift_px": options.aligner.max_shift,
        "seconds": round(seconds, 1),
        "pairs": len(result.pairs),
        "median_step_px": float(caught["median"]),
        "limit_px": float(caught["limit"]),
        "biggest_step_px": float(np.max(caught["magnitudes"])),
        "agreement_tolerance_px": tolerance,
        "refused_by_shipped": flagged,
        "protected_by_proposal": [t for t in flagged if protect[t]],
        "frames_moved": moved,
        "largest_move_px": max(
            (math.hypot(shipped[t].dx - proposed[t].dx,
                        shipped[t].dy - proposed[t].dy) for t in moved),
            default=0.0),
        "evidence": {str(t): found for t, found in evidence.items()},
    }

    if moved:
        margin = widest(
            valid_margin(shipped, width, height, Interpolation.BILINEAR),
            valid_margin(proposed, width, height, Interpolation.BILINEAR))
        fractional = [Transform(t.dx - round(t.dx), t.dy - round(t.dy))
                      for t in shipped]
        record["score"] = {
            "margin": [margin.top, margin.bottom, margin.left, margin.right],
            "raw": mean_temporal_sd(frames, margin),
            "control_fractional_shift_only": mean_temporal_sd(
                warped(frames, fractional), margin),
            "shipped": mean_temporal_sd(warped(frames, shipped), margin),
            "proposed": mean_temporal_sd(warped(frames, proposed), margin),
        }
        record["score"]["proposed_vs_shipped_percent"] = 100.0 * (
            record["score"]["proposed"] / record["score"]["shipped"] - 1.0)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(record, indent=2))
    return record


# --------------------------------------------------------------------------
# all of them
# --------------------------------------------------------------------------

def entries() -> list[str]:
    return sorted(p.name for p in (ROOT / "library").iterdir()
                  if (p / "original.tif").exists() and (p / "shifts.csv").exists())


def table(records: list[dict]) -> str:
    lines = [f"{'entry':<32} {'refused':>8} {'kept':>5} {'moved':>6} "
             f"{'by px':>7} {'shipped':>9} {'proposed':>9} {'change':>8}",
             "-" * 92]
    for record in records:
        score = record.get("score")
        lines.append(
            f"{record['entry']:<32} "
            f"{len(record['refused_by_shipped']):>8} "
            f"{len(record['protected_by_proposal']):>5} "
            f"{len(record['frames_moved']):>6} "
            f"{record['largest_move_px']:>7.2f} "
            + (f"{score['shipped']:>9.2f} {score['proposed']:>9.2f} "
               f"{score['proposed_vs_shipped_percent']:>+7.2f}%"
               if score else f"{'-':>9} {'-':>9} {'-':>8}"))
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("entry", nargs="?", help="one library entry")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--collect", action="store_true",
                        help="print the table from whatever is already written")
    arguments = parser.parse_args()

    if arguments.collect:
        records = [json.loads(p.read_text())
                   for p in sorted(OUT.glob("*.json"))]
        print(table(records))
        return
    if arguments.entry and not arguments.all:
        record = probe(arguments.entry)
        print(json.dumps(record, indent=2))
        return
    if not arguments.all:
        parser.error("name an entry, or pass --all")

    OUT.mkdir(parents=True, exist_ok=True)
    todo = entries()
    print(f"{len(todo)} entries, {arguments.workers} at a time")
    environment = dict(os.environ, OMP_NUM_THREADS="1", MKL_NUM_THREADS="1",
                       OPENBLAS_NUM_THREADS="1")
    running: list[tuple[str, subprocess.Popen]] = []
    started = time.time()
    while todo or running:
        while todo and len(running) < arguments.workers:
            name = todo.pop(0)
            log = (OUT / f"{name}.log").open("w")
            running.append((name, subprocess.Popen(
                [sys.executable, str(Path(__file__).resolve()), name],
                cwd=str(ROOT), env=environment, stdout=log, stderr=log)))
            print(f"  started {name}")
        time.sleep(2.0)
        for name, process in list(running):
            if process.poll() is not None:
                running.remove((name, process))
                mark = "ok" if process.returncode == 0 else \
                    f"FAILED ({process.returncode}), see {name}.log"
                print(f"  {name}: {mark}  [{time.time() - started:.0f}s]")
    records = [json.loads(p.read_text()) for p in sorted(OUT.glob("*.json"))]
    print()
    print(table(records))


if __name__ == "__main__":
    main()
