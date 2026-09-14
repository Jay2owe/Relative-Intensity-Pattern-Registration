#!/usr/bin/env python3
"""Gate and rank the full multi-lag split-rotation development shortlist."""

from __future__ import annotations

import csv
import sys
from collections import defaultdict
from pathlib import Path

from summarize_split_rotation_screen import (
    CATEGORY,
    IMAGE_TYPES,
    case_key,
    fmt,
    metrics,
    read_csv,
)


SLUG = {
    "BRIGHTFIELD_DIC": "brightfield",
    "DENSE_FLUOR": "dense",
    "FIDUCIAL_STATIC": "fiducial",
    "PHASE": "phase",
    "SPARSE_LOWLIGHT": "sparse",
}


def main(project: Path) -> None:
    run_root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    candidates: list[dict[str, str]] = []
    controls_list: list[dict[str, str]] = []
    for image in IMAGE_TYPES:
        slug = SLUG[image]
        candidates.extend(read_csv(
            run_root / f"r32_split_rotation_full_{slug}" / "results.csv"))
        controls_list.extend(read_csv(
            run_root / f"r32_automatic_translation_full_control_{slug}" / "results.csv"))
    if len(candidates) != 756:
        raise SystemExit(f"expected 756 full-shortlist rows, found {len(candidates)}")
    if len(controls_list) != 180:
        raise SystemExit(f"expected 180 translation-control rows, found {len(controls_list)}")
    controls = {case_key(row): row for row in controls_list}
    if len(controls) != 180:
        raise SystemExit(f"expected 180 unique translation controls, found {len(controls)}")

    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in candidates:
        grouped[(row["image_series_class"], row["recipe_id"])].append(row)
    if any(len(rows) != 36 for rows in grouped.values()):
        raise SystemExit("every full-shortlist image/recipe group must contain 36 cases")

    destination = run_root / "r32_split_rotation_full_shortlist_summary"
    destination.mkdir(parents=True, exist_ok=True)
    summaries = {}
    finalists = defaultdict(list)
    for image in IMAGE_TYPES:
        eligible = []
        for (candidate_image, recipe), rows in grouped.items():
            if candidate_image != image:
                continue
            measured, failures, policy_failures = metrics(rows, controls)
            summaries[(image, recipe)] = (
                rows[0]["estimator"], measured, failures, policy_failures)
            if not failures:
                eligible.append((recipe, measured))
        eligible.sort(key=lambda item: (
            item[1]["worst_warp_px"], item[1]["mean_p90_warp_px"],
            item[1]["mean_median_warp_px"], item[1]["runtime_ratio"], item[0]))
        finalists[image] = [recipe for recipe, _ in eligible[:2]]
        if CATEGORY not in finalists[image]:
            finalists[image].append(CATEGORY)

    fields = [
        "image_series_class", "recipe_id", "estimator", "accuracy_passed",
        "accuracy_failures", "raw_policy_passed", "raw_policy_failures", "finalist_rank",
        "rows", "failed_rows", "mean_median_warp_px", "control_mean_median_warp_px",
        "mean_p90_warp_px", "control_mean_p90_warp_px", "worst_warp_px",
        "control_worst_warp_px", "clean_mean_p90_angle_degrees",
        "clean_worst_angle_degrees", "gain_mean_p90_angle_degrees",
        "gain_worst_angle_degrees", "issue_recordings", "control_issue_recordings",
        "repaired_frames", "control_repaired_frames", "runtime_ratio",
        "zero_rotation_degradation_px",
    ]
    ranks = {(image, recipe): rank for image, recipes in finalists.items()
             for rank, recipe in enumerate(recipes, 1)}
    with (destination / "by_recipe.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for image in IMAGE_TYPES:
            for recipe in sorted(recipe for candidate_image, recipe in grouped
                                 if candidate_image == image):
                estimator, measured, failures, policy_failures = summaries[(image, recipe)]
                writer.writerow({
                    "image_series_class": image,
                    "recipe_id": recipe,
                    "estimator": estimator,
                    "accuracy_passed": not failures,
                    "accuracy_failures": "; ".join(failures),
                    "raw_policy_passed": not policy_failures,
                    "raw_policy_failures": "; ".join(policy_failures),
                    "finalist_rank": ranks.get((image, recipe), ""),
                    **{key: fmt(value) for key, value in measured.items()},
                })

    with (destination / "finalists.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow((
            "image_series_class", "finalist_rank", "recipe_id", "estimator",
            "accuracy_passed", "forced_category_comparator", "mean_median_warp_px",
            "mean_p90_warp_px", "worst_warp_px", "clean_mean_p90_angle_degrees",
            "gain_mean_p90_angle_degrees", "runtime_ratio", "zero_rotation_degradation_px"))
        for image in IMAGE_TYPES:
            for rank, recipe in enumerate(finalists[image], 1):
                estimator, measured, failures, _ = summaries[(image, recipe)]
                writer.writerow((
                    image, rank, recipe, estimator, not failures,
                    recipe == CATEGORY and rank > 2,
                    fmt(measured["mean_median_warp_px"]),
                    fmt(measured["mean_p90_warp_px"]),
                    fmt(measured["worst_warp_px"]),
                    fmt(measured["clean_mean_p90_angle_degrees"]),
                    fmt(measured["gain_mean_p90_angle_degrees"]),
                    fmt(measured["runtime_ratio"]),
                    fmt(measured["zero_rotation_degradation_px"])))
    print(destination / "finalists.csv")


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve())
