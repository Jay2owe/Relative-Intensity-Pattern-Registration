#!/usr/bin/env python3
"""Gate the A13 category confidence grid on exact pairs and controlled sequences."""

from __future__ import annotations

import csv
import argparse
import importlib.util
import re
from pathlib import Path


CATEGORY = "category_recommendation"
JOINT = "JOINT_RIGID"
ORACLE = "TRUTH_ANGLE_REMOVED_TRANSLATION"
EXPECTED_THRESHOLDS = 11
EXPECTED_PAIR_ROWS = 1701 * EXPECTED_THRESHOLDS
EXPECTED_OLD_ROWS = 36 * EXPECTED_THRESHOLDS


def module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(loaded)
    return loaded


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def threshold_map(rows: list[dict[str, str]]) -> dict[str, float]:
    output: dict[str, float] = {}
    pattern = re.compile(r"min residual gain=([^;]+)")
    for row in rows:
        match = pattern.search(row["details"])
        if match is None:
            raise RuntimeError(f"missing confidence threshold in {row['recipe_id']}")
        value = float(match.group(1))
        previous = output.setdefault(row["recipe_id"], value)
        if previous != value:
            raise RuntimeError(f"inconsistent threshold for {row['recipe_id']}")
    if len(output) != EXPECTED_THRESHOLDS:
        raise RuntimeError(f"expected {EXPECTED_THRESHOLDS} confidence policies, found {len(output)}")
    return output


def evaluate(candidate_rows, baseline_rows, oracle_rows, common, scope, heldout=""):
    output = []
    policies = sorted({row["recipe_id"] for row in candidate_rows})
    for policy in policies:
        candidate = [row for row in candidate_rows if row["recipe_id"] == policy]
        failures, metrics = common.evaluate(candidate, baseline_rows, oracle_rows)
        output.append({"scope": scope, "heldout": heldout, "recipe_id": policy,
                       "passed": not failures, "failures": "; ".join(failures), **metrics})
    return output


def folds(candidate, baseline, oracle, field, common, scope):
    output = []
    for heldout in sorted({row[field] for row in candidate}):
        output.extend(evaluate(
            [row for row in candidate if row[field] != heldout],
            [row for row in baseline if row[field] != heldout],
            [row for row in oracle if row[field] != heldout],
            common, scope, heldout))
    return output


def write(path: Path, rows, fields, common):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pair-run", default="r04_merged_category_confidence")
    parser.add_argument("--old-run", default="r14_category_confidence_old_controlled")
    parser.add_argument("--summary-run", default="")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    pair_summary = module(project / "scripts" / "summarize_fresh_rigid_pair_benchmark.py",
                          "pair_summary")
    common = module(project / "scripts" / "summarize_rigid_selector_v1.py",
                    "rigid_summary_common")
    pair_root = (project / "library" / "rigid_selector_tuning" / "fresh_recovery"
                 / "pair_benchmark" / "runs" / "development")
    confidence_run = pair_root / args.pair_run
    pair_raw = read(confidence_run / "results.csv")
    if len(pair_raw) != EXPECTED_PAIR_ROWS:
        raise RuntimeError(f"expected {EXPECTED_PAIR_ROWS} pair rows, found {len(pair_raw)}")
    thresholds = threshold_map(pair_raw)
    pair_candidates = pair_summary.aggregate(pair_raw)
    base_recordings = read(pair_root / "r02_merged_full_factorial" / "summary"
                           / "recording_outcomes.csv")
    pair_baseline = [row for row in base_recordings
                     if row["arm"] == JOINT and row["recipe_id"] == CATEGORY]
    pair_oracle = [row for row in base_recordings
                   if row["arm"] == ORACLE and row["recipe_id"] == CATEGORY]

    pair_pooled = evaluate(pair_candidates, pair_baseline, pair_oracle, common,
                           "pair_confidence_pooled")
    pair_series = folds(pair_candidates, pair_baseline, pair_oracle, "series_id", common,
                        "pair_confidence_leave_series_out")
    pair_groups = folds(pair_candidates, pair_baseline, pair_oracle, "independent_group", common,
                        "pair_confidence_leave_independent_group_out")

    old_root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    old_candidates = read(old_root / args.old_run / "results.csv")
    if len(old_candidates) != EXPECTED_OLD_ROWS:
        raise RuntimeError(f"expected {EXPECTED_OLD_ROWS} old rows, found {len(old_candidates)}")
    if threshold_map(old_candidates) != thresholds:
        raise RuntimeError("pair and old confidence threshold identities differ")
    old_base = pair_summary.old_phase(project, common)
    old_baseline = [row for row in old_base
                    if row["arm"] == JOINT and row["recipe_id"] == CATEGORY]
    old_oracle = [row for row in old_base
                  if row["arm"] == ORACLE and row["recipe_id"] == CATEGORY]
    old_pooled = evaluate(old_candidates, old_baseline, old_oracle, common,
                          "old_confidence_pooled")
    old_series = folds(old_candidates, old_baseline, old_oracle, "series_id", common,
                       "old_confidence_leave_series_out")

    summary = (pair_root / args.summary_run if args.summary_run else confidence_run) / "summary"
    fields = pair_summary.GATE_FIELDS
    write(summary / "pair_confidence_gate_results.csv", pair_pooled, fields, common)
    write(summary / "pair_confidence_series_heldout_gate_results.csv",
          pair_series, fields, common)
    write(summary / "pair_confidence_experiment_heldout_gate_results.csv",
          pair_groups, fields, common)
    write(summary / "old_confidence_gate_results.csv", old_pooled, fields, common)
    write(summary / "old_confidence_series_heldout_gate_results.csv",
          old_series, fields, common)

    pair_by = {row["recipe_id"]: row for row in pair_pooled}
    old_by = {row["recipe_id"]: row for row in old_pooled}
    pair_series_n = len({row["heldout"] for row in pair_series})
    pair_group_n = len({row["heldout"] for row in pair_groups})
    old_series_n = len({row["heldout"] for row in old_series})
    eligible = []
    for policy, threshold in sorted(thresholds.items(), key=lambda item: item[1]):
        ps = sum(row["recipe_id"] == policy and row["passed"] for row in pair_series)
        pg = sum(row["recipe_id"] == policy and row["passed"] for row in pair_groups)
        os = sum(row["recipe_id"] == policy and row["passed"] for row in old_series)
        if (pair_by[policy]["passed"] and old_by[policy]["passed"]
                and ps == pair_series_n and pg == pair_group_n and os == old_series_n):
            eligible.append({
                "policy_id": policy,
                "minimum_residual_gain": threshold,
                "pair_series_folds": ps,
                "pair_experiment_folds": pg,
                "old_series_folds": os,
                "pair_worst_warp_px": pair_by[policy]["worst_warp_px"],
                "old_worst_warp_px": old_by[policy]["worst_warp_px"],
                "pair_mean_p90_warp_px": pair_by[policy]["mean_p90_warp_px"],
                "old_mean_p90_warp_px": old_by[policy]["mean_p90_warp_px"],
                "pair_mean_median_warp_px": pair_by[policy]["mean_median_warp_px"],
                "old_mean_median_warp_px": old_by[policy]["mean_median_warp_px"],
                "pair_runtime_ratio": pair_by[policy]["runtime_ratio"],
                "old_runtime_ratio": old_by[policy]["runtime_ratio"],
                "pair_zero_rotation_degradation_px":
                    pair_by[policy]["zero_rotation_degradation_px"],
                "old_zero_rotation_degradation_px":
                    old_by[policy]["zero_rotation_degradation_px"],
            })
    eligible.sort(key=lambda row: (
        max(row["pair_worst_warp_px"], row["old_worst_warp_px"]),
        max(row["pair_mean_p90_warp_px"], row["old_mean_p90_warp_px"]),
        max(row["pair_mean_median_warp_px"], row["old_mean_median_warp_px"]),
        max(row["pair_runtime_ratio"], row["old_runtime_ratio"]),
        row["minimum_residual_gain"]))
    selection_fields = list(eligible[0].keys()) if eligible else [
        "policy_id", "minimum_residual_gain", "pair_series_folds",
        "pair_experiment_folds", "old_series_folds", "pair_worst_warp_px",
        "old_worst_warp_px", "pair_mean_p90_warp_px", "old_mean_p90_warp_px",
        "pair_mean_median_warp_px", "old_mean_median_warp_px", "pair_runtime_ratio",
        "old_runtime_ratio", "pair_zero_rotation_degradation_px",
        "old_zero_rotation_degradation_px"]
    write(summary / "eligible_confidence_policies.csv", eligible, selection_fields, common)
    pair_pass = sum(row["passed"] for row in pair_pooled)
    old_pass = sum(row["passed"] for row in old_pooled)
    print(f"confidence pooled passes: pair {pair_pass}/{EXPECTED_THRESHOLDS}, "
          f"old controlled {old_pass}/{EXPECTED_THRESHOLDS}; stable policies {len(eligible)}; "
          f"nominee {eligible[0]['minimum_residual_gain'] if eligible else 'none'}")


if __name__ == "__main__":
    main()
