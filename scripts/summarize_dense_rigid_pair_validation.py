#!/usr/bin/env python3
"""Gate one frozen Dense exact-pair opening against category and old development."""

from __future__ import annotations

import argparse
import csv
import importlib.util
from pathlib import Path


CATEGORY = "category_recommendation"
NOMINEE = "support_gradient__band_no_bottom_25__filter_gaussian_1_0__mask_none"
JOINT = "JOINT_RIGID"
ORACLE = "TRUTH_ANGLE_REMOVED_TRANSLATION"
DENSE = "DENSE_FLUOR"
EXPECTED_FIXTURES = 189


def module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(loaded)
    return loaded


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def gate(rows, common, scope):
    baseline = [row for row in rows if row["arm"] == JOINT
                and row["recipe_id"] == CATEGORY]
    candidate = [row for row in rows if row["arm"] == JOINT
                 and row["recipe_id"] == NOMINEE]
    oracle = [row for row in rows if row["arm"] == ORACLE
              and row["recipe_id"] == NOMINEE]
    failures, metrics = common.evaluate(candidate, baseline, oracle)
    return {"scope": scope, "recipe_id": NOMINEE, "passed": not failures,
            "failures": "; ".join(failures), **metrics}


def gate_incremental(rows, common, scope, policy):
    baseline = [row for row in rows if row["arm"] == JOINT
                and row["recipe_id"] == CATEGORY]
    candidate = [row for row in rows if row["arm"] == JOINT
                 and row["recipe_id"] == policy]
    oracle = [row for row in rows if row["arm"] == ORACLE
              and row["recipe_id"] == NOMINEE]
    failures, metrics = common.evaluate(candidate, baseline, oracle)
    return {"scope": scope, "recipe_id": policy, "passed": not failures,
            "failures": "; ".join(failures), **metrics}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--split", choices=("validation", "locked"), required=True)
    parser.add_argument("--run", required=True)
    parser.add_argument("--candidate-run")
    parser.add_argument("--policy",
                        default="incremental_rotation_min_gain_0_00500000000000")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    pair = module(project / "scripts" / "summarize_fresh_rigid_pair_benchmark.py",
                  "pair_summary")
    common = module(project / "scripts" / "summarize_rigid_selector_v1.py", "rigid_common")
    run = (project / "library" / "rigid_selector_tuning" / "fresh_recovery"
           / "pair_benchmark" / "runs" / args.split / args.run)
    raw = read(run / "results.csv")
    expected = EXPECTED_FIXTURES * 2 * 2
    if len(raw) != expected:
        raise RuntimeError(f"expected {expected} exact-pair rows, found {len(raw)}")
    if args.candidate_run:
        candidate_raw = read(run.parent / args.candidate_run / "results.csv")
        if len(candidate_raw) != EXPECTED_FIXTURES:
            raise RuntimeError(
                f"expected {EXPECTED_FIXTURES} candidate rows, found {len(candidate_raw)}")
        if {row["recipe_id"] for row in candidate_raw} != {args.policy}:
            raise RuntimeError("candidate run contains an unexpected policy")
        recordings = pair.aggregate(raw + candidate_raw)
        fresh = gate_incremental(
            recordings, common, f"dense_{args.split}_incremental_exact_pairs", args.policy)
        summary_run = run.parent / args.candidate_run
    else:
        recordings = pair.aggregate(raw)
        fresh = gate(recordings, common, f"dense_{args.split}_exact_pairs")
        summary_run = run

    old_root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    merged = {common.result_key(row): row
              for row in read(old_root / "r02_full_factorial" / "results.csv")}
    for row in read(old_root / "r03_intermittent_no_repair" / "results.csv"):
        merged[common.result_key(row)] = row
    old_rows = [row for row in merged.values() if row["image_series_class"] == DENSE]
    old = gate(old_rows, common, "dense_old_controlled_development")

    fields = [field for field in pair.GATE_FIELDS if field != "heldout"]
    summary = summary_run / "summary"
    summary.mkdir(parents=True, exist_ok=True)
    with (summary / "dense_gate_results.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in (fresh, old):
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})
    print(f"Dense {args.split}: exact pairs {'PASS' if fresh['passed'] else 'FAIL'}"
          f" ({fresh['failures'] or 'all gates'}); old controlled "
          f"{'PASS' if old['passed'] else 'FAIL'} ({old['failures'] or 'all gates'})")


if __name__ == "__main__":
    main()
