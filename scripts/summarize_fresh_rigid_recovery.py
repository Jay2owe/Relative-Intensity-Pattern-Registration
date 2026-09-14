#!/usr/bin/env python3
"""Gate the A09 fresh Phase factorial without opening validation or locked results."""

from __future__ import annotations

import csv
import importlib.util
from pathlib import Path


PHASE = "PHASE"
CATEGORY = "category_recommendation"
JOINT = "JOINT_RIGID"
ORACLE = "TRUTH_ANGLE_REMOVED_TRANSLATION"
EXPECTED_RECIPES = 129
EXPECTED_FRESH_CASES = 72


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


def phase_development(project: Path, common):
    root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    rows = read(root / "r02_full_factorial" / "results.csv")
    merged = {common.result_key(row): row for row in rows}
    for row in read(root / "r03_intermittent_no_repair" / "results.csv"):
        merged[common.result_key(row)] = row
    return [row for row in merged.values() if row["image_series_class"] == PHASE]


def fields():
    return [
        "scope", "heldout", "recipe_id", "passed", "failures", "rows",
        "mean_median_warp_px", "category_mean_median_warp_px", "mean_p90_warp_px",
        "category_mean_p90_warp_px", "worst_warp_px",
        "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
        "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
        "issue_recordings", "category_issue_recordings", "repaired_frames",
        "category_repaired_frames", "runtime_ratio", "zero_rotation_degradation_px",
        "per_recording_guard_failures",
    ]


def evaluate_scope(rows, common, scope: str, heldout: str = ""):
    joint = [row for row in rows if row["arm"] == JOINT]
    oracle = [row for row in rows if row["arm"] == ORACLE]
    recipes = sorted({row["recipe_id"] for row in joint})
    baseline = [row for row in joint if row["recipe_id"] == CATEGORY]
    output = []
    for recipe in recipes:
        candidate = [row for row in joint if row["recipe_id"] == recipe]
        candidate_oracle = [row for row in oracle if row["recipe_id"] == recipe]
        failures, metrics = common.evaluate(candidate, baseline, candidate_oracle)
        output.append({"scope": scope, "heldout": heldout, "recipe_id": recipe,
                       "passed": not failures, "failures": "; ".join(failures), **metrics})
    return output


def write(path: Path, rows, common):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields())
        writer.writeheader()
        for row in rows:
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})


def rank(row):
    return (float(row["worst_warp_px"]), float(row["mean_p90_warp_px"]),
            float(row["mean_median_warp_px"]), float(row["runtime_ratio"]),
            row["recipe_id"])


def main() -> None:
    project = Path(__file__).resolve().parents[1]
    common = common_module(project)
    run = (project / "library" / "rigid_selector_tuning" / "fresh_recovery" / "runs"
           / "development" / "r10_fresh_phase_full")
    fresh = read(run / "results.csv")
    recipes = sorted({row["recipe_id"] for row in fresh})
    if len(recipes) != EXPECTED_RECIPES:
        raise RuntimeError(f"expected {EXPECTED_RECIPES} recipes, found {len(recipes)}")
    for arm in (JOINT, ORACLE):
        expected = EXPECTED_RECIPES * EXPECTED_FRESH_CASES
        found = sum(row["arm"] == arm for row in fresh)
        if found != expected:
            raise RuntimeError(f"incomplete {arm}: expected {expected}, found {found}")

    original = phase_development(project, common)
    combined = original + fresh
    summary = run / "summary"
    fresh_gates = evaluate_scope(fresh, common, "fresh_pooled")
    pooled_gates = evaluate_scope(combined, common, "combined_pooled")
    write(summary / "fresh_gate_results.csv", fresh_gates, common)
    write(summary / "combined_gate_results.csv", pooled_gates, common)

    series_folds = []
    for heldout in sorted({row["series_id"] for row in combined}):
        training = [row for row in combined if row["series_id"] != heldout]
        series_folds.extend(evaluate_scope(training, common, "leave_series_out", heldout))
    write(summary / "series_heldout_gate_results.csv", series_folds, common)

    experiment_folds = []
    for heldout in sorted({row["independent_group"] for row in combined}):
        training = [row for row in combined if row["independent_group"] != heldout]
        experiment_folds.extend(evaluate_scope(
            training, common, "leave_independent_group_out", heldout))
    write(summary / "experiment_heldout_gate_results.csv", experiment_folds, common)

    pooled_by_recipe = {row["recipe_id"]: row for row in pooled_gates}
    fresh_by_recipe = {row["recipe_id"]: row for row in fresh_gates}
    series_groups = {row["heldout"] for row in series_folds}
    experiment_groups = {row["heldout"] for row in experiment_folds}
    finalists = []
    for recipe in recipes:
        if recipe == CATEGORY or not pooled_by_recipe[recipe]["passed"]:
            continue
        series_passes = sum(row["recipe_id"] == recipe and row["passed"]
                            for row in series_folds)
        experiment_passes = sum(row["recipe_id"] == recipe and row["passed"]
                                for row in experiment_folds)
        if series_passes != len(series_groups) or experiment_passes != len(experiment_groups):
            continue
        finalists.append({**pooled_by_recipe[recipe],
                           "fresh_passed": fresh_by_recipe[recipe]["passed"],
                           "series_folds_passed": series_passes,
                           "series_folds": len(series_groups),
                           "experiment_folds_passed": experiment_passes,
                           "experiment_folds": len(experiment_groups)})
    finalists.sort(key=rank)
    finalist_fields = fields() + ["fresh_passed", "series_folds_passed", "series_folds",
                                 "experiment_folds_passed", "experiment_folds"]
    with (summary / "confidence_finalists.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=finalist_fields)
        writer.writeheader()
        for row in finalists[:3]:
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})

    fresh_pass = sum(row["passed"] for row in fresh_gates if row["recipe_id"] != CATEGORY)
    pooled_pass = sum(row["passed"] for row in pooled_gates if row["recipe_id"] != CATEGORY)
    print(f"fresh gates: {fresh_pass}/{EXPECTED_RECIPES - 1}; combined gates: "
          f"{pooled_pass}/{EXPECTED_RECIPES - 1}; fold-stable finalists: "
          f"{len(finalists)}; confidence grid nominees: "
          f"{', '.join(row['recipe_id'] for row in finalists[:3]) or 'none'}")


if __name__ == "__main__":
    main()
