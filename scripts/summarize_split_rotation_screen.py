#!/usr/bin/env python3
"""Summarize the all-recipe split-rotation screen and freeze its full-length shortlist."""

from __future__ import annotations

import csv
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path


IMAGE_TYPES = (
    "BRIGHTFIELD_DIC", "DENSE_FLUOR", "FIDUCIAL_STATIC", "PHASE", "SPARSE_LOWLIGHT"
)
CATEGORY = "category_recommendation"


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def number(row: dict[str, str], field: str) -> float:
    try:
        return float(row[field])
    except (KeyError, TypeError, ValueError):
        return math.nan


def integer(row: dict[str, str], field: str) -> int:
    try:
        return int(row[field])
    except (KeyError, TypeError, ValueError):
        return 0


def mean(values) -> float:
    finite = [value for value in values if math.isfinite(value)]
    return statistics.fmean(finite) if finite else math.nan


def maximum(values) -> float:
    finite = [value for value in values if math.isfinite(value)]
    return max(finite) if finite else math.nan


def case_key(row: dict[str, str]) -> tuple[str, str, str, str]:
    return (row["image_series_class"], row["series_id"], row["motion_category"],
            row["condition"])


def issue(row: dict[str, str]) -> bool:
    return any(integer(row, field) > 0 for field in (
        "refused_pairs", "non_converged_pairs", "shift_bound_pairs",
        "rotation_bound_pairs", "both_bound_pairs", "unsupported_frames"
    ))


def fmt(value) -> str:
    if isinstance(value, float):
        return f"{value:.12g}" if math.isfinite(value) else "NaN"
    return str(value)


def metrics(rows: list[dict[str, str]], controls: dict[tuple[str, str, str, str], dict[str, str]]):
    ok = [row for row in rows if row["status"] == "ok"]
    matched = [(row, controls[case_key(row)]) for row in ok if case_key(row) in controls]
    clean = [row for row in ok if row["condition"] == "RIGID_CLEAN"]
    gain = [row for row in ok if row["condition"] == "RIGID_GAIN_FADE_0_5"]
    zero = [row for row in ok if row["condition"] == "ZERO_ROTATION_CLEAN"]
    control_rows = [control for _, control in matched]
    out = {
        "rows": len(rows),
        "failed_rows": len(rows) - len(ok),
        "mean_median_warp_px": mean(number(row, "median_warp_central50_px") for row in ok),
        "control_mean_median_warp_px": mean(
            number(row, "median_warp_central50_px") for row in control_rows),
        "mean_p90_warp_px": mean(number(row, "p90_warp_central50_px") for row in ok),
        "control_mean_p90_warp_px": mean(
            number(row, "p90_warp_central50_px") for row in control_rows),
        "worst_warp_px": maximum(number(row, "worst_warp_central50_px") for row in ok),
        "control_worst_warp_px": maximum(
            number(row, "worst_warp_central50_px") for row in control_rows),
        "clean_mean_p90_angle_degrees": mean(
            number(row, "p90_angle_error_degrees") for row in clean),
        "clean_worst_angle_degrees": maximum(
            number(row, "worst_angle_error_degrees") for row in clean),
        "gain_mean_p90_angle_degrees": mean(
            number(row, "p90_angle_error_degrees") for row in gain),
        "gain_worst_angle_degrees": maximum(
            number(row, "worst_angle_error_degrees") for row in gain),
        "issue_recordings": sum(issue(row) for row in ok),
        "control_issue_recordings": sum(issue(row) for row in control_rows),
        "repaired_frames": sum(integer(row, "repaired_frames") for row in ok),
        "control_repaired_frames": sum(integer(row, "repaired_frames") for row in control_rows),
        "runtime_ratio": mean(number(row, "runtime_seconds") for row in ok)
            / mean(number(row, "runtime_seconds") for row in control_rows),
        "zero_rotation_degradation_px": maximum(
            number(row, "median_warp_central50_px")
            - number(controls[case_key(row)], "median_warp_central50_px")
            for row in zero if case_key(row) in controls),
    }
    failures = []
    if out["failed_rows"]:
        failures.append("registration failure")
    if len(matched) != len(rows):
        failures.append("control case mismatch")
    if out["clean_mean_p90_angle_degrees"] > 0.10:
        failures.append("clean mean p90 angle >0.10 degrees")
    if out["clean_worst_angle_degrees"] > 0.50:
        failures.append("clean worst angle >0.50 degrees")
    if out["gain_mean_p90_angle_degrees"] > 0.25:
        failures.append("gain mean p90 angle >0.25 degrees")
    if out["gain_worst_angle_degrees"] > 1.00:
        failures.append("gain worst angle >1.00 degree")
    if out["mean_median_warp_px"] > out["control_mean_median_warp_px"] + 0.002:
        failures.append("mean median regression >0.002 px")
    if out["mean_p90_warp_px"] > out["control_mean_p90_warp_px"] + 0.005:
        failures.append("mean p90 regression >0.005 px")
    # This screen isolates pairwise angle quality on top of the existing Automatic translation.
    # Do not reject an angle recipe for a large error already present in that translation control;
    # the relative control ceiling below still rejects any added catastrophe. Full cumulative-stack
    # benchmarking applies the absolute safety ceiling to the shortlisted combinations.
    if out["worst_warp_px"] > max(1.0, out["control_worst_warp_px"] + 0.05):
        failures.append("worst error exceeds control ceiling")
    if out["issue_recordings"] > out["control_issue_recordings"]:
        failures.append("added issue recording")
    if out["repaired_frames"] > out["control_repaired_frames"]:
        failures.append("added repaired frames")
    # Raw threshold-zero runs are allowed to fail these two policy gates. They are recorded now and
    # tuned only for the candidates that survive the accuracy screen.
    policy_failures = []
    if out["runtime_ratio"] > 1.25:
        policy_failures.append("runtime ratio >1.25")
    if out["zero_rotation_degradation_px"] > 0.01:
        policy_failures.append("zero-rotation degradation >0.01 px")
    return out, failures, policy_failures


def main(project: Path) -> None:
    run_root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    rows = []
    for shard in "abc":
        rows.extend(read_csv(
            run_root / f"r31_split_rotation_pair_screen_{shard}" / "results.csv"))
    controls_list = []
    for shard in "abc":
        controls_list.extend(read_csv(
            run_root / f"r31_automatic_translation_pair_control_{shard}" / "results.csv"))
    controls = {case_key(row): row for row in controls_list}
    if len(rows) != 5805:
        raise SystemExit(f"expected 5805 screen rows, found {len(rows)}")
    if len(controls) != 45:
        raise SystemExit(f"expected 45 control rows, found {len(controls)}")

    grouped = defaultdict(list)
    for row in rows:
        grouped[(row["image_series_class"], row["recipe_id"])].append(row)
    destination = run_root / "r31_split_rotation_pair_screen_summary"
    destination.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "image_series_class", "recipe_id", "estimator", "accuracy_screen_passed",
        "accuracy_failures", "raw_policy_passed", "raw_policy_failures", "shortlist_rank",
        "rows", "failed_rows", "mean_median_warp_px", "control_mean_median_warp_px",
        "mean_p90_warp_px", "control_mean_p90_warp_px", "worst_warp_px",
        "control_worst_warp_px", "clean_mean_p90_angle_degrees",
        "clean_worst_angle_degrees", "gain_mean_p90_angle_degrees",
        "gain_worst_angle_degrees", "issue_recordings", "control_issue_recordings",
        "repaired_frames", "control_repaired_frames", "runtime_ratio",
        "zero_rotation_degradation_px"
    ]
    summaries = {}
    shortlist = defaultdict(list)
    for image in IMAGE_TYPES:
        candidates = []
        for (candidate_image, recipe), candidate_rows in grouped.items():
            if candidate_image != image:
                continue
            measured, failures, policy_failures = metrics(candidate_rows, controls)
            summaries[(image, recipe)] = (candidate_rows[0]["estimator"], measured,
                                          failures, policy_failures)
            if not failures:
                candidates.append((recipe, measured))
        candidates.sort(key=lambda item: (
            item[1]["worst_warp_px"], item[1]["mean_p90_warp_px"],
            item[1]["mean_median_warp_px"], item[1]["runtime_ratio"], item[0]))
        shortlist[image] = [recipe for recipe, _ in candidates[:3]]
        if CATEGORY not in shortlist[image]:
            shortlist[image].append(CATEGORY)

    ranks = {(image, recipe): index + 1 for image, recipes in shortlist.items()
             for index, recipe in enumerate(recipes)}
    with (destination / "by_recipe.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for image in IMAGE_TYPES:
            recipes = sorted(recipe for candidate_image, recipe in grouped if candidate_image == image)
            for recipe in recipes:
                estimator, measured, failures, policy_failures = summaries[(image, recipe)]
                writer.writerow({
                    "image_series_class": image,
                    "recipe_id": recipe,
                    "estimator": estimator,
                    "accuracy_screen_passed": not failures,
                    "accuracy_failures": "; ".join(failures),
                    "raw_policy_passed": not policy_failures,
                    "raw_policy_failures": "; ".join(policy_failures),
                    "shortlist_rank": ranks.get((image, recipe), ""),
                    **{key: fmt(value) for key, value in measured.items()},
                })

    with (destination / "shortlist.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("image_series_class", "shortlist_rank", "recipe_id", "estimator",
                         "accuracy_screen_passed", "forced_category_fallback",
                         "mean_median_warp_px", "mean_p90_warp_px", "worst_warp_px",
                         "runtime_ratio", "zero_rotation_degradation_px"))
        for image in IMAGE_TYPES:
            for rank, recipe in enumerate(shortlist[image], 1):
                estimator, measured, failures, _ = summaries[(image, recipe)]
                writer.writerow((image, rank, recipe, estimator, not failures,
                                 recipe == CATEGORY and rank > 3,
                                 fmt(measured["mean_median_warp_px"]),
                                 fmt(measured["mean_p90_warp_px"]),
                                 fmt(measured["worst_warp_px"]),
                                 fmt(measured["runtime_ratio"]),
                                 fmt(measured["zero_rotation_degradation_px"])))
    print(destination / "shortlist.csv")


if __name__ == "__main__":
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    main(root)
