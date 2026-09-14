#!/usr/bin/env python3
"""Gate the frozen split-rotation policies on validation and locked integration series."""

from __future__ import annotations

import csv
import sys
from pathlib import Path

from summarize_split_rotation_confidence import policy_details
from summarize_split_rotation_full import SLUG
from summarize_split_rotation_screen import IMAGE_TYPES, case_key, fmt, metrics, read_csv


STAGES = {
    "validation": ("r35_split_rotation_validation_", "r35_translation_validation_control_"),
    "locked": ("r36_split_rotation_locked_", "r36_translation_locked_control_"),
}


def summarize_stage(run_root: Path, destination: Path, stage: str,
                    result_prefix: str, control_prefix: str) -> None:
    rows: list[dict[str, str]] = []
    controls_list: list[dict[str, str]] = []
    for image in IMAGE_TYPES:
        slug = SLUG[image]
        result = run_root / f"{result_prefix}{slug}" / "results.csv"
        if stage == "validation" and image == "PHASE":
            result = (run_root / "r35b_split_rotation_validation_phase_fallback"
                      / "results.csv")
        control = run_root / f"{control_prefix}{slug}" / "results.csv"
        if not result.is_file() or not control.is_file():
            if stage == "locked":
                return
            raise SystemExit(f"missing {stage} result/control for {image}")
        rows.extend(read_csv(result))
        controls_list.extend(read_csv(control))
    controls = {case_key(row): row for row in controls_list}
    if len(rows) != 45 or len(controls) != 45:
        raise SystemExit(f"{stage} matrix must contain 45 candidate and control cases")

    fields = [
        "stage", "image_series_class", "series_id", "policy_id", "base_recipe",
        "threshold", "accuracy_passed", "accuracy_failures", "zero_guard_passed",
        "runtime_guard_passed", "raw_policy_failures", "rows", "failed_rows",
        "mean_median_warp_px", "control_mean_median_warp_px", "mean_p90_warp_px",
        "control_mean_p90_warp_px", "worst_warp_px", "control_worst_warp_px",
        "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
        "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
        "issue_recordings", "control_issue_recordings", "repaired_frames",
        "control_repaired_frames", "runtime_ratio", "zero_rotation_degradation_px",
    ]
    with (destination / f"{stage}.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for image in IMAGE_TYPES:
            part = [row for row in rows if row["image_series_class"] == image]
            if len(part) != 9 or len({row["series_id"] for row in part}) != 1:
                raise SystemExit(f"{stage} must contain one nine-case series for {image}")
            base, threshold, _, _ = policy_details(part[0])
            measured, failures, policy_failures = metrics(part, controls)
            writer.writerow({
                "stage": stage,
                "image_series_class": image,
                "series_id": part[0]["series_id"],
                "policy_id": part[0]["recipe_id"],
                "base_recipe": base,
                "threshold": fmt(threshold),
                "accuracy_passed": not failures,
                "accuracy_failures": "; ".join(failures),
                "zero_guard_passed": measured["zero_rotation_degradation_px"] <= 0.01,
                "runtime_guard_passed": measured["runtime_ratio"] <= 1.25,
                "raw_policy_failures": "; ".join(policy_failures),
                **{key: fmt(value) for key, value in measured.items()},
            })


def main(project: Path) -> None:
    run_root = (project / "library" / "rigid_selector_tuning" / "runs"
                / "integration")
    destination = run_root / "split_rotation_heldout_summary"
    destination.mkdir(parents=True, exist_ok=True)
    for stage, prefixes in STAGES.items():
        summarize_stage(run_root, destination, stage, *prefixes)
    print(destination)


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve())
