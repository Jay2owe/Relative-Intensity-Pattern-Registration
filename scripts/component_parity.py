"""Element-by-element Java/Python comparison of the planes behind one frame.

The whole-recording harness says a run diverged; this says which array diverged first, which is the
difference between "the numbers drift" and "one named step is computed differently".
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import tifffile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))


def classpath() -> str:
    dependencies = ROOT / "target" / "test-classpath.txt"
    return os.pathsep.join((
        str(ROOT / "target" / "test-classes"),
        str(ROOT / "target" / "classes"),
        dependencies.read_text(encoding="utf-8").strip(),
    ))


def report(name: str, java: np.ndarray, python: np.ndarray) -> bool:
    if java.shape != python.shape:
        print(f"  {name:16s} SHAPE java {java.shape} python {python.shape}")
        return False
    delta = np.abs(java.astype(np.float64) - python.astype(np.float64))
    worst = float(delta.max()) if delta.size else 0.0
    differing = int((delta > 0).sum())
    scale = float(np.abs(java).max()) or 1.0
    flag = "OK  " if worst == 0 else ("ulp " if worst < 1e-6 * scale else "DIFF")
    print(f"  {name:16s} {flag} max|d|={worst:.6g}  differing={differing}/{delta.size}"
          f"  ({100.0 * differing / max(delta.size, 1):.2f}%)")
    return worst == 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stack", default="RIPR_after_speedup_bundles/microglia_1432_brightfield.tif")
    parser.add_argument("--frame", type=int, default=0)
    parser.add_argument("--channel", type=int, default=1)
    parser.add_argument("--levels", type=int, default=4)
    parser.add_argument("--epsilon", type=float, default=1.0)
    parser.add_argument("--preprocessing", default="NONE")
    parser.add_argument("--workspace", type=Path,
                        default=Path(os.environ.get("TEMP", "/tmp")) / "ripr_component")
    arguments = parser.parse_args()

    arguments.workspace.mkdir(parents=True, exist_ok=True)
    java = shutil.which("java")
    completed = subprocess.run(
        [java, "-Djava.awt.headless=true", "-cp", classpath(), "ripr.core.ComponentParityProbe",
         str(ROOT / arguments.stack), str(arguments.frame), str(arguments.channel),
         str(arguments.levels), str(arguments.workspace), str(arguments.epsilon),
         arguments.preprocessing],
        cwd=ROOT, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        print(completed.stdout, completed.stderr)
        return 2

    shapes = {}
    for line in (arguments.workspace / "manifest.csv").read_text().splitlines():
        name, h, w = line.split(",")
        shapes[name] = (int(h), int(w))

    def load(name: str, key: str) -> np.ndarray:
        raw = np.fromfile(arguments.workspace / f"{name}.f32", dtype="<f4")
        return raw.reshape(shapes[key])

    from ripr.core import LogPlane

    intensity = load("intensity", "intensity")
    stack = tifffile.imread(ROOT / arguments.stack)
    print(f"stack {stack.shape} frame {arguments.frame}")
    print("\n--- level 0 input ---")
    from ripr.preprocessing import apply_preprocessing

    raw = np.asarray(stack[arguments.frame], dtype=np.float32)
    # Compare what the fit consumes. When a recipe names a filter, the raw frame is not it, and
    # comparing raw against filtered reports a difference that is entirely this script's fault.
    report("intensity", intensity, apply_preprocessing(raw, arguments.preprocessing))

    plane = LogPlane.from_intensity(intensity, epsilon=arguments.epsilon)
    pyramid = plane.pyramid(arguments.levels)
    all_exact = True
    for level, p in enumerate(pyramid):
        key = f"level{level}"
        print(f"\n--- {key} ({shapes[key][0]}x{shapes[key][1]}) ---")
        all_exact &= report("v", load(f"{key}_v", key), p.value)
        all_exact &= report("valid", load(f"{key}_valid", key), p.valid.astype(np.float32))
        all_exact &= report("gx", load(f"{key}_gx", key), p.gx)
        all_exact &= report("gy", load(f"{key}_gy", key), p.gy)

    print("\nbit-exact across every plane" if all_exact else "\ndivergence present (see DIFF rows)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
