#!/usr/bin/env python3
"""Gate one frozen Phase exact-pair opening plus its frozen controlled evidence."""

from __future__ import annotations

import argparse
import csv
import importlib.util
from pathlib import Path


CATEGORY = "category_recommendation"
JOINT = "JOINT_RIGID"
ORACLE = "TRUTH_ANGLE_REMOVED_TRANSLATION"


def module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(loaded)
    return loaded


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def evaluate(candidate, baseline, oracle, common, scope, policy):
    failures, metrics = common.evaluate(candidate, baseline, oracle)
    return {"scope": scope, "recipe_id": policy, "passed": not failures,
            "failures": "; ".join(failures), **metrics}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--split", choices=("validation", "locked"), required=True)
    parser.add_argument("--baseline-run", required=True)
    parser.add_argument("--candidate-run", required=True)
    parser.add_argument("--old-run", default="r21_incremental_angular_old_controlled")
    parser.add_argument("--policy",
                        default="incremental_rotation_min_gain_0_00500000000000")
    args = parser.parse_args()

    project = Path(__file__).resolve().parents[1]
    pair = module(project / "scripts" / "summarize_fresh_rigid_pair_benchmark.py",
                  "pair_summary")
    common = module(project / "scripts" / "summarize_rigid_selector_v1.py",
                    "rigid_common")
    pair_root = (project / "library" / "rigid_selector_tuning" / "fresh_recovery"
                 / "pair_benchmark")
    split_root = pair_root / "runs" / args.split
    fixtures = len(read(pair_root / f"input_manifest_{args.split}.csv"))
    baseline_raw = read(split_root / args.baseline_run / "results.csv")
    candidate_raw = read(split_root / args.candidate_run / "results.csv")
    if len(baseline_raw) != 2 * fixtures:
        raise RuntimeError(f"expected {2 * fixtures} baseline rows, found {len(baseline_raw)}")
    if len(candidate_raw) != fixtures:
        raise RuntimeError(f"expected {fixtures} candidate rows, found {len(candidate_raw)}")
    if {row["recipe_id"] for row in candidate_raw} != {args.policy}:
        raise RuntimeError("candidate run contains an unexpected policy")

    recordings = pair.aggregate(baseline_raw + candidate_raw)
    fresh = evaluate(
        [row for row in recordings if row["arm"] == JOINT
         and row["recipe_id"] == args.policy],
        [row for row in recordings if row["arm"] == JOINT
         and row["recipe_id"] == CATEGORY],
        [row for row in recordings if row["arm"] == ORACLE
         and row["recipe_id"] == CATEGORY],
        common, f"phase_{args.split}_exact_pairs", args.policy)

    old_root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    old_candidate = [row for row in read(old_root / args.old_run / "results.csv")
                     if row["recipe_id"] == args.policy]
    old_base = pair.old_phase(project, common)
    old = evaluate(
        old_candidate,
        [row for row in old_base if row["arm"] == JOINT
         and row["recipe_id"] == CATEGORY],
        [row for row in old_base if row["arm"] == ORACLE
         and row["recipe_id"] == CATEGORY],
        common, "phase_old_controlled_development", args.policy)

    fields = [field for field in pair.GATE_FIELDS if field != "heldout"]
    summary = split_root / args.candidate_run / "summary"
    summary.mkdir(parents=True, exist_ok=True)
    with (summary / "phase_gate_results.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in (fresh, old):
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})
    print(f"Phase {args.split}: exact pairs {'PASS' if fresh['passed'] else 'FAIL'}"
          f" ({fresh['failures'] or 'all gates'}); old controlled "
          f"{'PASS' if old['passed'] else 'FAIL'} ({old['failures'] or 'all gates'})")


if __name__ == "__main__":
    main()
