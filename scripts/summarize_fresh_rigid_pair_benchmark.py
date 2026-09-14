#!/usr/bin/env python3
"""Aggregate and gate the A12 exact-pair factorial at original-source level."""

from __future__ import annotations

import csv
import importlib.util
import math
from collections import defaultdict
from pathlib import Path


PHASE = "PHASE"
CATEGORY = "category_recommendation"
JOINT = "JOINT_RIGID"
TRANSLATION = "TRANSLATION_ONLY"
OLD_ORACLE = "TRUTH_ANGLE_REMOVED_TRANSLATION"
EXPECTED_RECIPES = 129
EXPECTED_FIXTURES = 1701


def common_module(project: Path):
    path = project / "scripts" / "summarize_rigid_selector_v1.py"
    spec = importlib.util.spec_from_file_location("rigid_summary_common", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def quantile(values, q: float) -> float:
    ordered = sorted(float(value) for value in values if math.isfinite(float(value)))
    if not ordered:
        return math.nan
    position = (len(ordered) - 1) * q
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def aggregate(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    groups: dict[tuple[str, ...], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        groups[(row["split"], row["image_series_class"], row["series_id"],
                row["independent_group"], row["motion_category"], row["condition"],
                row["arm"], row["recipe_id"], row["estimator"])].append(row)
    output = []
    for key, part in sorted(groups.items()):
        ok = [row for row in part if row["status"] == "ok"]
        central = [row["worst_warp_central50_px"] for row in ok]
        full = [row["worst_warp_full_px"] for row in ok]
        angle = [row["worst_angle_error_degrees"] for row in ok]
        translation = [row["worst_translation_error_px"] for row in ok]
        integer_fields = ("refused_pairs", "non_converged_pairs", "shift_bound_pairs",
                          "rotation_bound_pairs", "both_bound_pairs", "repaired_frames",
                          "unsupported_frames")
        aggregated = {
            "split": key[0], "image_series_class": key[1], "series_id": key[2],
            "independent_group": key[3], "motion_category": key[4], "condition": key[5],
            "arm": OLD_ORACLE if key[6] == TRANSLATION else key[6],
            "recipe_id": key[7], "estimator": key[8],
            "status": "ok" if len(ok) == len(part) else "failed", "frames": str(len(part)),
            "median_warp_central50_px": str(quantile(central, 0.5)),
            "p90_warp_central50_px": str(quantile(central, 0.9)),
            "worst_warp_central50_px": str(quantile(central, 1.0)),
            "median_warp_full_px": str(quantile(full, 0.5)),
            "p90_warp_full_px": str(quantile(full, 0.9)),
            "worst_warp_full_px": str(quantile(full, 1.0)),
            "median_angle_error_degrees": str(quantile(angle, 0.5)),
            "p90_angle_error_degrees": str(quantile(angle, 0.9)),
            "worst_angle_error_degrees": str(quantile(angle, 1.0)),
            "median_translation_error_px": str(quantile(translation, 0.5)),
            "worst_translation_error_px": str(quantile(translation, 1.0)),
            "retained_crop_fraction": str(sum(float(row["retained_crop_fraction"])
                                                for row in ok) / len(ok)),
            "runtime_seconds": str(sum(float(row["runtime_seconds"]) for row in ok)),
            "details": f"A12 aggregate of {len(part)} exact matched-frame pairs",
        }
        for field in integer_fields:
            aggregated[field] = str(sum(int(row[field]) for row in ok))
        output.append(aggregated)
    return output


def old_phase(project: Path, common):
    root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    merged = {common.result_key(row): row
              for row in read(root / "r02_full_factorial" / "results.csv")}
    for row in read(root / "r03_intermittent_no_repair" / "results.csv"):
        merged[common.result_key(row)] = row
    return [row for row in merged.values() if row["image_series_class"] == PHASE]


GATE_FIELDS = [
    "scope", "heldout", "recipe_id", "passed", "failures", "rows",
    "mean_median_warp_px", "category_mean_median_warp_px", "mean_p90_warp_px",
    "category_mean_p90_warp_px", "worst_warp_px",
    "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
    "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
    "issue_recordings", "category_issue_recordings", "repaired_frames",
    "category_repaired_frames", "runtime_ratio", "zero_rotation_degradation_px",
    "per_recording_guard_failures",
]


def gates(rows, common, scope: str, heldout: str = ""):
    joint = [row for row in rows if row["arm"] == JOINT]
    oracle = [row for row in rows if row["arm"] == OLD_ORACLE]
    baseline = [row for row in joint if row["recipe_id"] == CATEGORY]
    recipes = sorted({row["recipe_id"] for row in joint})
    output = []
    for recipe in recipes:
        failures, metrics = common.evaluate(
            [row for row in joint if row["recipe_id"] == recipe], baseline,
            [row for row in oracle if row["recipe_id"] == recipe])
        output.append({"scope": scope, "heldout": heldout, "recipe_id": recipe,
                       "passed": not failures, "failures": "; ".join(failures), **metrics})
    return output


def write(path: Path, rows, common):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=GATE_FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})


def all_folds(rows, common, group_field: str, scope: str):
    output = []
    for heldout in sorted({row[group_field] for row in rows}):
        training = [row for row in rows if row[group_field] != heldout]
        output.extend(gates(training, common, scope, heldout))
    return output


def rank(row):
    return (float(row["worst_warp_px"]), float(row["mean_p90_warp_px"]),
            float(row["mean_median_warp_px"]), float(row["runtime_ratio"]),
            row["recipe_id"])


def main() -> None:
    project = Path(__file__).resolve().parents[1]
    common = common_module(project)
    run = (project / "library" / "rigid_selector_tuning" / "fresh_recovery"
           / "pair_benchmark" / "runs" / "development" / "r02_merged_full_factorial")
    pair_rows = read(run / "results.csv")
    expected = EXPECTED_FIXTURES * EXPECTED_RECIPES * 2
    if len(pair_rows) != expected:
        raise RuntimeError(f"expected {expected} rows, found {len(pair_rows)}")
    recording_rows = aggregate(pair_rows)
    summary = run / "summary"
    summary.mkdir(parents=True, exist_ok=True)
    with (summary / "recording_outcomes.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=recording_rows[0].keys())
        writer.writeheader()
        writer.writerows(recording_rows)

    pair_pooled = gates(recording_rows, common, "pair_pooled")
    pair_series = all_folds(recording_rows, common, "series_id", "pair_leave_series_out")
    pair_groups = all_folds(recording_rows, common, "independent_group",
                            "pair_leave_independent_group_out")
    write(summary / "pair_gate_results.csv", pair_pooled, common)
    write(summary / "pair_series_heldout_gate_results.csv", pair_series, common)
    write(summary / "pair_experiment_heldout_gate_results.csv", pair_groups, common)

    old = old_phase(project, common)
    old_pooled = gates(old, common, "old_sequence_pooled")
    old_series = all_folds(old, common, "series_id", "old_sequence_leave_series_out")
    write(summary / "old_sequence_gate_results.csv", old_pooled, common)
    write(summary / "old_sequence_series_heldout_gate_results.csv", old_series, common)

    pair_by = {row["recipe_id"]: row for row in pair_pooled}
    old_by = {row["recipe_id"]: row for row in old_pooled}
    pair_series_n = len({row["heldout"] for row in pair_series})
    pair_group_n = len({row["heldout"] for row in pair_groups})
    old_series_n = len({row["heldout"] for row in old_series})
    finalists = []
    for recipe in sorted(pair_by):
        if recipe == CATEGORY or not pair_by[recipe]["passed"] or not old_by[recipe]["passed"]:
            continue
        ps = sum(row["recipe_id"] == recipe and row["passed"] for row in pair_series)
        pg = sum(row["recipe_id"] == recipe and row["passed"] for row in pair_groups)
        os = sum(row["recipe_id"] == recipe and row["passed"] for row in old_series)
        if ps != pair_series_n or pg != pair_group_n or os != old_series_n:
            continue
        finalists.append({**pair_by[recipe], "pair_series_folds": ps,
                           "pair_experiment_folds": pg, "old_sequence_folds": os,
                           "old_sequence_worst_warp_px": old_by[recipe]["worst_warp_px"],
                           "old_sequence_runtime_ratio": old_by[recipe]["runtime_ratio"]})
    finalists.sort(key=rank)
    extra = ["pair_series_folds", "pair_experiment_folds", "old_sequence_folds",
             "old_sequence_worst_warp_px", "old_sequence_runtime_ratio"]
    with (summary / "confidence_finalists.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=GATE_FIELDS + extra)
        writer.writeheader()
        for row in finalists[:3]:
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})

    pair_pass = sum(row["passed"] for row in pair_pooled if row["recipe_id"] != CATEGORY)
    old_pass = sum(row["passed"] for row in old_pooled if row["recipe_id"] != CATEGORY)
    print(f"pair pooled: {pair_pass}/128; old sequence pooled: {old_pass}/128; "
          f"stable finalists: {len(finalists)}; nominees: "
          f"{', '.join(row['recipe_id'] for row in finalists[:3]) or 'none'}")


if __name__ == "__main__":
    main()
