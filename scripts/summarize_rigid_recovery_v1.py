#!/usr/bin/env python3
"""Gate the Phase confidence recovery against the original frozen development evidence."""

from __future__ import annotations

import argparse
import csv
import importlib.util
from pathlib import Path


PHASE = "PHASE"
CATEGORY = "category_recommendation"
JOINT = "JOINT_RIGID"
ORACLE = "TRUTH_ANGLE_REMOVED_TRANSLATION"
PHASE_RECIPE = "support_all__band_no_top_10__filter_gaussian_0_7__mask_none"


def load_common(project: Path):
    path = project / "scripts" / "summarize_rigid_selector_v1.py"
    spec = importlib.util.spec_from_file_location("rigid_summary_common", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def write_gates(path: Path, candidates, baseline, oracle, common) -> list[dict[str, object]]:
    fields = ["recipe_id", "passed", "failures", "rows", "mean_median_warp_px",
              "category_mean_median_warp_px", "mean_p90_warp_px",
              "category_mean_p90_warp_px", "worst_warp_px",
              "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
              "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
              "issue_recordings", "category_issue_recordings", "repaired_frames",
              "category_repaired_frames", "runtime_ratio",
              "zero_rotation_degradation_px", "per_recording_guard_failures"]
    rows = []
    recipe_ids = sorted({row["recipe_id"] for row in candidates})
    for recipe_id in recipe_ids:
        part = [row for row in candidates if row["recipe_id"] == recipe_id]
        failures, metrics = common.evaluate(part, baseline, oracle)
        rows.append({"recipe_id": recipe_id, "passed": not failures,
                     "failures": "; ".join(failures), **metrics})
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})
    return rows


def ranking(row: dict[str, object]):
    return (float(row["worst_warp_px"]), float(row["mean_p90_warp_px"]),
            float(row["mean_median_warp_px"]), float(row["runtime_ratio"]),
            str(row["recipe_id"]))


def heldout(path: Path, candidates, baseline, oracle, common):
    output = []
    fold_gates = []
    for series in sorted({row["series_id"] for row in candidates}):
        train = [row for row in candidates if row["series_id"] != series]
        train_base = [row for row in baseline if row["series_id"] != series]
        train_oracle = [row for row in oracle if row["series_id"] != series]
        eligible = []
        for recipe_id in sorted({row["recipe_id"] for row in train}):
            failures, metrics = common.evaluate(
                [row for row in train if row["recipe_id"] == recipe_id],
                train_base, train_oracle)
            fold_gates.append({"heldout_series": series, "recipe_id": recipe_id,
                               "passed": not failures, "failures": "; ".join(failures),
                               **metrics})
            if not failures:
                eligible.append({"recipe_id": recipe_id, **metrics})
        eligible.sort(key=ranking)
        selected = str(eligible[0]["recipe_id"]) if eligible else CATEGORY
        test = ([row for row in candidates if row["series_id"] == series
                 and row["recipe_id"] == selected] if eligible else
                [row for row in baseline if row["series_id"] == series])
        test_base = [row for row in baseline if row["series_id"] == series]
        output.append({
            "heldout_series": series,
            "selected_recipe": selected,
            "eligible_training_thresholds": len(eligible),
            "heldout_mean_median_warp_px": common.f(common.mean(common.number(
                row, "median_warp_central50_px") for row in test)),
            "heldout_category_mean_median_warp_px": common.f(common.mean(common.number(
                row, "median_warp_central50_px") for row in test_base)),
            "heldout_mean_p90_warp_px": common.f(common.mean(common.number(
                row, "p90_warp_central50_px") for row in test)),
            "heldout_category_mean_p90_warp_px": common.f(common.mean(common.number(
                row, "p90_warp_central50_px") for row in test_base)),
            "heldout_worst_warp_px": common.f(common.maximum(common.number(
                row, "worst_warp_central50_px") for row in test)),
        })
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=output[0].keys())
        writer.writeheader()
        writer.writerows(output)
    gate_path = path.with_name("source_heldout_gate_results.csv")
    with gate_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fold_gates[0].keys())
        writer.writeheader()
        for row in fold_gates:
            writer.writerow({key: common.f(value) if isinstance(value, float) else value
                             for key, value in row.items()})
    return fold_gates


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--run", default="r10_phase_confidence_grid")
    parser.add_argument("--phase-recipe", default=PHASE_RECIPE)
    args = parser.parse_args()
    project = args.project.resolve()
    common = load_common(project)
    run = project / "library" / "rigid_selector_tuning" / "runs" / "development" / args.run
    candidates = read(run / "results.csv")
    original = read(project / "library" / "rigid_selector_tuning" / "runs" / "development"
                    / "r02_full_factorial" / "results.csv")
    corrected = read(project / "library" / "rigid_selector_tuning" / "runs" / "development"
                     / "r03_intermittent_no_repair" / "results.csv")
    merged = {common.result_key(row): row for row in original}
    for row in corrected:
        merged[common.result_key(row)] = row
    original = list(merged.values())
    baseline = [row for row in original if row["arm"] == JOINT
                and row["image_series_class"] == PHASE and row["recipe_id"] == CATEGORY]
    oracle = [row for row in original if row["arm"] == ORACLE
              and row["image_series_class"] == PHASE
              and row["recipe_id"] == args.phase_recipe]
    expected = len({row["recipe_id"] for row in candidates}) * 36
    if len(candidates) != expected:
        raise RuntimeError(f"incomplete confidence grid: expected {expected}, found {len(candidates)}")
    summary = run / "summary"
    gates = write_gates(summary / "gate_results.csv", candidates, baseline, oracle, common)
    fold_gates = heldout(
        summary / "source_heldout_folds.csv", candidates, baseline, oracle, common)
    fold_count = len({row["heldout_series"] for row in fold_gates})
    stable = {row["recipe_id"] for row in fold_gates if row["passed"]}
    stable = {recipe for recipe in stable if sum(
        row["recipe_id"] == recipe and row["passed"] for row in fold_gates) == fold_count}
    passing = sorted((row for row in gates
                      if row["passed"] and row["recipe_id"] in stable), key=ranking)
    winner = passing[0]["recipe_id"] if passing else "none"
    print(f"{len(gates)} thresholds gated; {len(passing)} passed pooled and all fold-training "
          f"gates; selected {winner}")


if __name__ == "__main__":
    main()
