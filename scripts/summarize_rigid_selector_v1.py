#!/usr/bin/env python3
"""Summarize and gate the frozen rigid-selector factorial without external packages."""

from __future__ import annotations

import argparse
import csv
import math
import statistics
from collections import defaultdict
from pathlib import Path


CATEGORY = "category_recommendation"
JOINT = "JOINT_RIGID"
ORACLE = "TRUTH_ANGLE_REMOVED_TRANSLATION"
IMAGE_TYPES = (
    "BRIGHTFIELD_DIC",
    "DENSE_FLUOR",
    "FIDUCIAL_STATIC",
    "PHASE",
    "SPARSE_LOWLIGHT",
)
EXPECTED_RECIPES = 129
EXPECTED_CASES_PER_TYPE = 36


def number(row: dict[str, str], key: str) -> float:
    try:
        return float(row[key])
    except (KeyError, ValueError):
        return math.nan


def integer(row: dict[str, str], key: str) -> int:
    try:
        return int(row[key])
    except (KeyError, ValueError):
        return 0


def mean(values) -> float:
    finite = [value for value in values if math.isfinite(value)]
    return statistics.fmean(finite) if finite else math.nan


def case_key(row: dict[str, str]) -> tuple[str, ...]:
    return (
        row["image_series_class"],
        row["series_id"],
        row["motion_category"],
        row["condition"],
    )


def result_key(row: dict[str, str]) -> tuple[str, ...]:
    return (row["arm"], *case_key(row), row["recipe_id"])


def hybrid_result_key(row: dict[str, str]) -> tuple[str, ...]:
    return (row["arm"], *case_key(row))


def summarize(rows: list[dict[str, str]], destination: Path) -> None:
    groups: dict[tuple[str, ...], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        groups[(row["arm"], row["image_series_class"], row["condition"],
                row["recipe_id"])].append(row)
    fields = (
        "arm", "image_series_class", "condition", "recipe_id", "estimator", "rows",
        "failures", "mean_recording_median_warp_px", "mean_recording_p90_warp_px",
        "worst_frame_warp_px", "mean_recording_p90_angle_error_degrees",
        "worst_angle_error_degrees", "issue_recordings", "refused_pairs",
        "non_converged_pairs", "shift_bound_pairs", "rotation_bound_pairs",
        "both_bound_pairs", "repaired_frames", "unsupported_frames",
        "mean_retained_crop_fraction", "mean_runtime_seconds",
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for key, part in sorted(groups.items()):
            ok = [row for row in part if row["status"] == "ok"]
            issue = lambda row: sum(integer(row, field) for field in (
                "refused_pairs", "non_converged_pairs", "shift_bound_pairs",
                "rotation_bound_pairs", "both_bound_pairs")) > 0
            writer.writerow({
                "arm": key[0],
                "image_series_class": key[1],
                "condition": key[2],
                "recipe_id": key[3],
                "estimator": ok[0]["estimator"] if ok else "",
                "rows": len(part),
                "failures": len(part) - len(ok),
                "mean_recording_median_warp_px": f(mean(number(r,
                    "median_warp_central50_px") for r in ok)),
                "mean_recording_p90_warp_px": f(mean(number(r,
                    "p90_warp_central50_px") for r in ok)),
                "worst_frame_warp_px": f(maximum(number(r,
                    "worst_warp_central50_px") for r in ok)),
                "mean_recording_p90_angle_error_degrees": f(mean(number(r,
                    "p90_angle_error_degrees") for r in ok)),
                "worst_angle_error_degrees": f(maximum(number(r,
                    "worst_angle_error_degrees") for r in ok)),
                "issue_recordings": sum(issue(r) for r in ok),
                "refused_pairs": sum(integer(r, "refused_pairs") for r in ok),
                "non_converged_pairs": sum(integer(r, "non_converged_pairs") for r in ok),
                "shift_bound_pairs": sum(integer(r, "shift_bound_pairs") for r in ok),
                "rotation_bound_pairs": sum(integer(r, "rotation_bound_pairs") for r in ok),
                "both_bound_pairs": sum(integer(r, "both_bound_pairs") for r in ok),
                "repaired_frames": sum(integer(r, "repaired_frames") for r in ok),
                "unsupported_frames": sum(integer(r, "unsupported_frames") for r in ok),
                "mean_retained_crop_fraction": f(mean(number(r,
                    "retained_crop_fraction") for r in ok)),
                "mean_runtime_seconds": f(mean(number(r, "runtime_seconds") for r in ok)),
            })


def maximum(values) -> float:
    finite = [value for value in values if math.isfinite(value)]
    return max(finite) if finite else math.nan


def f(value: float) -> str:
    return f"{value:.12g}" if math.isfinite(value) else "NaN"


def evaluate(candidate_rows: list[dict[str, str]],
             category_rows: list[dict[str, str]],
             oracle_rows: list[dict[str, str]],
             runtime_limit: float = 1.25) -> tuple[list[str], dict[str, float]]:
    failures: list[str] = []
    candidate_ok = [row for row in candidate_rows if row["status"] == "ok"]
    category_ok = [row for row in category_rows if row["status"] == "ok"]
    oracle_ok = [row for row in oracle_rows if row["status"] == "ok"]
    if len(candidate_ok) != len(candidate_rows):
        failures.append("registration failure")
    if len(candidate_rows) != len(category_rows):
        failures.append("case mismatch against category")
    clean = [r for r in candidate_ok if r["condition"] == "RIGID_CLEAN"]
    gain = [r for r in candidate_ok if r["condition"] == "RIGID_GAIN_FADE_0_5"]
    zero = [r for r in candidate_ok if r["condition"] == "ZERO_ROTATION_CLEAN"]

    clean_mean_p90_angle = mean(number(r, "p90_angle_error_degrees") for r in clean)
    clean_worst_angle = maximum(number(r, "worst_angle_error_degrees") for r in clean)
    gain_mean_p90_angle = mean(number(r, "p90_angle_error_degrees") for r in gain)
    gain_worst_angle = maximum(number(r, "worst_angle_error_degrees") for r in gain)
    if not clean or clean_mean_p90_angle > 0.10:
        failures.append("clean mean p90 angle >0.10 degrees")
    if not clean or clean_worst_angle > 0.50:
        failures.append("clean worst angle >0.50 degrees")
    if not gain or gain_mean_p90_angle > 0.25:
        failures.append("gain mean p90 angle >0.25 degrees")
    if not gain or gain_worst_angle > 1.00:
        failures.append("gain worst angle >1.00 degree")

    candidate_mean_median = mean(number(r, "median_warp_central50_px") for r in candidate_ok)
    category_mean_median = mean(number(r, "median_warp_central50_px") for r in category_ok)
    candidate_mean_p90 = mean(number(r, "p90_warp_central50_px") for r in candidate_ok)
    category_mean_p90 = mean(number(r, "p90_warp_central50_px") for r in category_ok)
    if candidate_mean_median > category_mean_median + 0.002:
        failures.append("mean median regression >0.002 px")
    if candidate_mean_p90 > category_mean_p90 + 0.005:
        failures.append("mean p90 regression >0.005 px")

    candidate_worst = maximum(number(r, "worst_warp_central50_px") for r in candidate_ok)
    category_worst = maximum(number(r, "worst_warp_central50_px") for r in category_ok)
    if candidate_worst > 5.0:
        failures.append("catastrophic error >5 px")
    if candidate_worst > max(1.0, category_worst + 0.05):
        failures.append("worst error exceeds baseline ceiling")

    def has_issue(row: dict[str, str]) -> bool:
        return sum(integer(row, field) for field in (
            "refused_pairs", "non_converged_pairs", "shift_bound_pairs",
            "rotation_bound_pairs", "both_bound_pairs")) > 0

    candidate_issue = sum(has_issue(r) for r in candidate_ok)
    category_issue = sum(has_issue(r) for r in category_ok)
    if candidate_issue > category_issue:
        failures.append("added issue recording")
    candidate_repairs = sum(integer(r, "repaired_frames") for r in candidate_ok)
    category_repairs = sum(integer(r, "repaired_frames") for r in category_ok)
    if candidate_repairs > category_repairs:
        failures.append("added repaired frames")

    by_category = {case_key(row): row for row in category_ok}
    per_recording = 0
    for row in candidate_ok:
        baseline = by_category.get(case_key(row))
        if baseline is None:
            continue
        value = number(row, "median_warp_central50_px")
        base = number(baseline, "median_warp_central50_px")
        if value > 2 * base and value > base + 0.05:
            per_recording += 1
    if per_recording:
        failures.append("per-recording double-and-0.05px guard")

    runtime_ratio = mean(number(r, "runtime_seconds") for r in candidate_ok) / mean(
        number(r, "runtime_seconds") for r in category_ok)
    if runtime_ratio > runtime_limit:
        failures.append(f"runtime ratio >{runtime_limit:.2f}")

    oracle_by_case = {case_key(row): row for row in oracle_ok}
    zero_degradation = maximum(
        number(row, "median_warp_central50_px")
        - number(oracle_by_case[case_key(row)], "median_warp_central50_px")
        for row in zero
        if case_key(row) in oracle_by_case
    )
    if len([r for r in zero if case_key(r) in oracle_by_case]) != len(zero):
        failures.append("missing zero-rotation oracle")
    elif zero_degradation > 0.01:
        failures.append("zero-rotation degradation >0.01 px")

    metrics = {
        "rows": len(candidate_rows),
        "mean_median_warp_px": candidate_mean_median,
        "category_mean_median_warp_px": category_mean_median,
        "mean_p90_warp_px": candidate_mean_p90,
        "category_mean_p90_warp_px": category_mean_p90,
        "worst_warp_px": candidate_worst,
        "clean_mean_p90_angle_degrees": clean_mean_p90_angle,
        "clean_worst_angle_degrees": clean_worst_angle,
        "gain_mean_p90_angle_degrees": gain_mean_p90_angle,
        "gain_worst_angle_degrees": gain_worst_angle,
        "issue_recordings": candidate_issue,
        "category_issue_recordings": category_issue,
        "repaired_frames": candidate_repairs,
        "category_repaired_frames": category_repairs,
        "runtime_ratio": runtime_ratio,
        "zero_rotation_degradation_px": zero_degradation,
        "per_recording_guard_failures": per_recording,
    }
    return failures, metrics


def gate_hybrid(hybrid: list[dict[str, str]], joint: list[dict[str, str]],
                oracle: list[dict[str, str]], destination: Path) -> None:
    translation_recipe = {
        "BRIGHTFIELD_DIC":
            "support_gradient__band_full__filter_gaussian_0_7__mask_none",
        "DENSE_FLUOR":
            "support_gradient__band_full__filter_gaussian_1_0__mask_none",
        "FIDUCIAL_STATIC":
            "support_all__band_no_bottom_25__filter_gaussian_0_7__mask_none",
        "PHASE":
            "support_gradient__band_no_top_25__filter_gaussian_1_0__mask_least_informative_25",
        "SPARSE_LOWLIGHT":
            "support_gradient__band_no_top_25__filter_gaussian_1_0__mask_least_informative_25",
    }
    fields = ["image_series_class", "policy_id", "passed", "failures", "rows",
              "mean_median_warp_px", "category_mean_median_warp_px",
              "mean_p90_warp_px", "category_mean_p90_warp_px", "worst_warp_px",
              "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
              "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
              "issue_recordings", "category_issue_recordings", "repaired_frames",
              "category_repaired_frames", "runtime_ratio",
              "zero_rotation_degradation_px", "per_recording_guard_failures"]
    with (destination / "hybrid_gate_results.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for image in IMAGE_TYPES:
            candidate = [r for r in hybrid if r["image_series_class"] == image]
            category = [r for r in joint if r["image_series_class"] == image
                        and r["recipe_id"] == CATEGORY]
            candidate_oracle = [r for r in oracle if r["image_series_class"] == image
                                and r["recipe_id"] == translation_recipe[image]]
            failures, metrics = evaluate(candidate, category, candidate_oracle,
                                         runtime_limit=1.50)
            writer.writerow({
                "image_series_class": image,
                "policy_id": candidate[0]["recipe_id"] if candidate else "",
                "passed": not failures,
                "failures": "; ".join(failures),
                **{key: f(value) if isinstance(value, float) else value
                   for key, value in metrics.items()},
            })


def gate_and_cross_validate(rows: list[dict[str, str]], destination: Path) -> None:
    joint = [row for row in rows if row["arm"] == JOINT]
    oracle = [row for row in rows if row["arm"] == ORACLE]
    recipe_ids = sorted({row["recipe_id"] for row in joint})
    if len(recipe_ids) != EXPECTED_RECIPES:
        raise RuntimeError(f"expected {EXPECTED_RECIPES} recipes, found {len(recipe_ids)}")
    if any(sum(1 for row in joint if row["image_series_class"] == image
               and row["recipe_id"] == recipe) != EXPECTED_CASES_PER_TYPE
           for image in IMAGE_TYPES for recipe in recipe_ids):
        raise RuntimeError("joint matrix is incomplete")
    if any(sum(1 for row in oracle if row["image_series_class"] == image
               and row["recipe_id"] == recipe) != EXPECTED_CASES_PER_TYPE
           for image in IMAGE_TYPES for recipe in recipe_ids):
        raise RuntimeError("oracle matrix is incomplete")

    gate_fields = ["image_series_class", "recipe_id", "estimator", "passed", "failures",
                   "rows", "mean_median_warp_px", "category_mean_median_warp_px",
                   "mean_p90_warp_px", "category_mean_p90_warp_px", "worst_warp_px",
                   "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
                   "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
                   "issue_recordings", "category_issue_recordings", "repaired_frames",
                   "category_repaired_frames", "runtime_ratio",
                   "zero_rotation_degradation_px", "per_recording_guard_failures"]
    gates: dict[tuple[str, str], tuple[list[str], dict[str, float]]] = {}
    with (destination / "gate_results.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=gate_fields)
        writer.writeheader()
        for image in IMAGE_TYPES:
            category = [r for r in joint if r["image_series_class"] == image
                        and r["recipe_id"] == CATEGORY]
            for recipe in recipe_ids:
                candidate = [r for r in joint if r["image_series_class"] == image
                             and r["recipe_id"] == recipe]
                candidate_oracle = [r for r in oracle if r["image_series_class"] == image
                                    and r["recipe_id"] == recipe]
                failures, metrics = evaluate(candidate, category, candidate_oracle)
                gates[(image, recipe)] = failures, metrics
                first = candidate[0]
                writer.writerow({
                    "image_series_class": image,
                    "recipe_id": recipe,
                    "estimator": first["estimator"],
                    "passed": not failures,
                    "failures": "; ".join(failures),
                    **{key: f(value) if isinstance(value, float) else value
                       for key, value in metrics.items()},
                })

    winners = []
    for image in IMAGE_TYPES:
        eligible = [(recipe, gates[(image, recipe)][1]) for recipe in recipe_ids
                    if not gates[(image, recipe)][0]]
        eligible.sort(key=lambda item: (item[1]["mean_median_warp_px"],
                                        item[1]["mean_p90_warp_px"],
                                        item[1]["runtime_ratio"], item[0]))
        if eligible:
            recipe, metrics = eligible[0]
            winners.append((image, recipe, metrics))

    with (destination / "development_winners.csv").open("w", newline="", encoding="utf-8") as h:
        writer = csv.writer(h)
        writer.writerow(("image_series_class", "recipe_id", "mean_median_warp_px",
                         "mean_p90_warp_px", "runtime_ratio"))
        for image, recipe, metrics in winners:
            writer.writerow((image, recipe, f(metrics["mean_median_warp_px"]),
                             f(metrics["mean_p90_warp_px"]), f(metrics["runtime_ratio"])))

    fold_rows = []
    for image in IMAGE_TYPES:
        series = sorted({r["series_id"] for r in joint if r["image_series_class"] == image})
        for heldout in series:
            train_joint = [r for r in joint if r["image_series_class"] == image
                           and r["series_id"] != heldout]
            train_oracle = [r for r in oracle if r["image_series_class"] == image
                            and r["series_id"] != heldout]
            category = [r for r in train_joint if r["recipe_id"] == CATEGORY]
            eligible = []
            for recipe in recipe_ids:
                candidate = [r for r in train_joint if r["recipe_id"] == recipe]
                candidate_oracle = [r for r in train_oracle if r["recipe_id"] == recipe]
                failures, metrics = evaluate(candidate, category, candidate_oracle)
                if not failures:
                    eligible.append((recipe, metrics))
            eligible.sort(key=lambda item: (item[1]["mean_median_warp_px"],
                                            item[1]["mean_p90_warp_px"], item[0]))
            selected = eligible[0][0] if eligible else CATEGORY
            test = [r for r in joint if r["image_series_class"] == image
                    and r["series_id"] == heldout and r["recipe_id"] == selected]
            baseline = [r for r in joint if r["image_series_class"] == image
                        and r["series_id"] == heldout and r["recipe_id"] == CATEGORY]
            fold_rows.append({
                "image_series_class": image,
                "heldout_series": heldout,
                "selected_recipe": selected,
                "eligible_training_recipes": len(eligible),
                "heldout_mean_median_warp_px": f(mean(number(r,
                    "median_warp_central50_px") for r in test)),
                "heldout_category_mean_median_warp_px": f(mean(number(r,
                    "median_warp_central50_px") for r in baseline)),
                "heldout_mean_p90_warp_px": f(mean(number(r,
                    "p90_warp_central50_px") for r in test)),
                "heldout_category_mean_p90_warp_px": f(mean(number(r,
                    "p90_warp_central50_px") for r in baseline)),
                "heldout_worst_warp_px": f(maximum(number(r,
                    "worst_warp_central50_px") for r in test)),
            })
    with (destination / "source_heldout_folds.csv").open("w", newline="", encoding="utf-8") as h:
        writer = csv.DictWriter(h, fieldnames=fold_rows[0].keys())
        writer.writeheader()
        writer.writerows(fold_rows)

    universal_fold_rows = []
    for heldout_image in IMAGE_TYPES:
        series = sorted({r["series_id"] for r in joint
                         if r["image_series_class"] == heldout_image})
        for heldout in series:
            train_joint = [r for r in joint if not (
                r["image_series_class"] == heldout_image and r["series_id"] == heldout)]
            train_oracle = [r for r in oracle if not (
                r["image_series_class"] == heldout_image and r["series_id"] == heldout)]
            eligible = []
            for recipe in recipe_ids:
                metrics_by_type = []
                for image in IMAGE_TYPES:
                    category = [r for r in train_joint
                                if r["image_series_class"] == image
                                and r["recipe_id"] == CATEGORY]
                    candidate = [r for r in train_joint
                                 if r["image_series_class"] == image
                                 and r["recipe_id"] == recipe]
                    candidate_oracle = [r for r in train_oracle
                                        if r["image_series_class"] == image
                                        and r["recipe_id"] == recipe]
                    failures, metrics = evaluate(candidate, category, candidate_oracle)
                    if failures:
                        break
                    metrics_by_type.append(metrics)
                if len(metrics_by_type) == len(IMAGE_TYPES):
                    eligible.append((recipe,
                                     mean(m["mean_median_warp_px"]
                                          for m in metrics_by_type),
                                     mean(m["mean_p90_warp_px"]
                                          for m in metrics_by_type),
                                     mean(m["runtime_ratio"] for m in metrics_by_type)))
            eligible.sort(key=lambda item: (item[1], item[2], item[3], item[0]))
            selected = eligible[0][0] if eligible else CATEGORY
            test = [r for r in joint if r["image_series_class"] == heldout_image
                    and r["series_id"] == heldout and r["recipe_id"] == selected]
            baseline = [r for r in joint if r["image_series_class"] == heldout_image
                        and r["series_id"] == heldout and r["recipe_id"] == CATEGORY]
            universal_fold_rows.append({
                "image_series_class": heldout_image,
                "heldout_series": heldout,
                "selected_recipe": selected,
                "eligible_universal_recipes": len(eligible),
                "heldout_mean_median_warp_px": f(mean(number(r,
                    "median_warp_central50_px") for r in test)),
                "heldout_category_mean_median_warp_px": f(mean(number(r,
                    "median_warp_central50_px") for r in baseline)),
                "heldout_mean_p90_warp_px": f(mean(number(r,
                    "p90_warp_central50_px") for r in test)),
                "heldout_category_mean_p90_warp_px": f(mean(number(r,
                    "p90_warp_central50_px") for r in baseline)),
                "heldout_worst_warp_px": f(maximum(number(r,
                    "worst_warp_central50_px") for r in test)),
            })
    with (destination / "universal_source_heldout_folds.csv").open(
            "w", newline="", encoding="utf-8") as h:
        writer = csv.DictWriter(h, fieldnames=universal_fold_rows[0].keys())
        writer.writeheader()
        writer.writerows(universal_fold_rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--run", default="r02_full_factorial")
    parser.add_argument("--overlay-run", action="append", default=[],
                        help="partial run whose rows replace matching rows from --run")
    parser.add_argument("--hybrid-run", default="")
    parser.add_argument("--hybrid-overlay-run", action="append", default=[])
    parser.add_argument("--summary-name", default="summary")
    args = parser.parse_args()
    project = args.project.resolve()
    run = project / "library" / "rigid_selector_tuning" / "runs" / "development" / args.run
    source = run / "results.csv"
    with source.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    merged = {result_key(row): row for row in rows}
    for overlay_name in args.overlay_run:
        overlay = (project / "library" / "rigid_selector_tuning" / "runs"
                   / "development" / overlay_name / "results.csv")
        with overlay.open(newline="", encoding="utf-8") as handle:
            overlay_rows = list(csv.DictReader(handle))
        for row in overlay_rows:
            merged[result_key(row)] = row
    rows = list(merged.values())
    summary = run / args.summary_name
    summary.mkdir(parents=True, exist_ok=True)
    summarize(rows, summary / "by_recipe.csv")
    joint_count = sum(row["arm"] == JOINT for row in rows)
    oracle_count = sum(row["arm"] == ORACLE for row in rows)
    if joint_count == 5 * EXPECTED_CASES_PER_TYPE * EXPECTED_RECIPES \
            and oracle_count == 5 * EXPECTED_CASES_PER_TYPE * EXPECTED_RECIPES:
        gate_and_cross_validate(rows, summary)
        if args.hybrid_run:
            hybrid_path = (project / "library" / "rigid_selector_tuning" / "runs"
                           / "development" / args.hybrid_run / "results.csv")
            with hybrid_path.open(newline="", encoding="utf-8") as handle:
                hybrid = list(csv.DictReader(handle))
            merged_hybrid = {hybrid_result_key(row): row for row in hybrid}
            for overlay_name in args.hybrid_overlay_run:
                overlay_path = (project / "library" / "rigid_selector_tuning" / "runs"
                                / "development" / overlay_name / "results.csv")
                with overlay_path.open(newline="", encoding="utf-8") as handle:
                    for row in csv.DictReader(handle):
                        merged_hybrid[hybrid_result_key(row)] = row
            hybrid = list(merged_hybrid.values())
            if len(hybrid) != len(IMAGE_TYPES) * EXPECTED_CASES_PER_TYPE:
                raise RuntimeError(f"expected 180 hybrid rows, found {len(hybrid)}")
            gate_hybrid(hybrid, [r for r in rows if r["arm"] == JOINT],
                        [r for r in rows if r["arm"] == ORACLE], summary)
        print(f"complete matrix: {joint_count} joint + {oracle_count} oracle rows; gates written")
    else:
        print(f"partial matrix: {joint_count} joint + {oracle_count} oracle rows; summary only")


if __name__ == "__main__":
    main()
