#!/usr/bin/env python3
"""Choose one confidence-gated split-rotation policy per image type for full verification."""

from __future__ import annotations

import csv
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

from summarize_split_rotation_full import SLUG
from summarize_split_rotation_screen import (
    IMAGE_TYPES,
    case_key,
    fmt,
    metrics,
    read_csv,
)


DETAIL = re.compile(
    r"rotation_recipe_id=([^;]+); min_rotation_gain=([^;]+).*?"
    r"accepted_pairs=(\d+); declined_pairs=(\d+)")


def policy_details(row: dict[str, str]) -> tuple[str, float, int, int]:
    match = DETAIL.search(row.get("details", ""))
    if not match:
        raise SystemExit(f"missing split confidence details in {row.get('recipe_id', '')}")
    return match.group(1), float(match.group(2)), int(match.group(3)), int(match.group(4))


def main(project: Path) -> None:
    run_root = project / "library" / "rigid_selector_tuning" / "runs" / "development"
    rows: list[dict[str, str]] = []
    controls_list: list[dict[str, str]] = []
    for image in IMAGE_TYPES:
        slug = SLUG[image]
        rows.extend(read_csv(
            run_root / f"r33_split_rotation_pair_confidence_{slug}" / "results.csv"))
    for shard in "abc":
        controls_list.extend(read_csv(
            run_root / f"r31_automatic_translation_pair_control_{shard}" / "results.csv"))
    if len(rows) != 1152:
        raise SystemExit(f"expected 1152 confidence rows, found {len(rows)}")
    controls = {case_key(row): row for row in controls_list}
    if len(controls) != 45:
        raise SystemExit(f"expected 45 pairwise controls, found {len(controls)}")

    full = read_csv(
        run_root / "r32_split_rotation_full_shortlist_summary" / "by_recipe.csv")
    full_by = {(row["image_series_class"], row["recipe_id"]): row for row in full}
    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        grouped[(row["image_series_class"], row["recipe_id"])].append(row)
    if any(len(part) != 9 for part in grouped.values()):
        raise SystemExit("every confidence policy must contain nine representative-series cases")

    destination = run_root / "r33_split_rotation_pair_confidence_summary"
    destination.mkdir(parents=True, exist_ok=True)
    summaries = {}
    by_base: dict[tuple[str, str], list[tuple[str, dict]]] = defaultdict(list)
    for (image, policy), part in grouped.items():
        base, threshold, _, _ = policy_details(part[0])
        measured, failures, policy_failures = metrics(part, controls)
        condition_counts = {}
        for condition in ("RIGID_CLEAN", "RIGID_GAIN_FADE_0_5", "ZERO_ROTATION_CLEAN"):
            accepted = declined = 0
            for row in part:
                if row["condition"] != condition:
                    continue
                _, _, row_accepted, row_declined = policy_details(row)
                accepted += row_accepted
                declined += row_declined
            condition_counts[condition] = (accepted, declined)
        eligible = not failures and measured["zero_rotation_degradation_px"] <= 0.01
        value = {
            "base_recipe": base,
            "threshold": threshold,
            "measured": measured,
            "failures": failures,
            "policy_failures": policy_failures,
            "eligible": eligible,
            "condition_counts": condition_counts,
        }
        summaries[(image, policy)] = value
        if eligible:
            by_base[(image, base)].append((policy, value))

    best_by_base = {}
    for key, policies in by_base.items():
        policies.sort(key=lambda item: (
            item[1]["measured"]["zero_rotation_degradation_px"],
            item[1]["measured"]["worst_warp_px"],
            item[1]["measured"]["mean_p90_warp_px"],
            item[1]["measured"]["mean_median_warp_px"],
            -item[1]["threshold"]))
        best_by_base[key] = policies[0]

    selected = {}
    for image in IMAGE_TYPES:
        candidates = []
        for (candidate_image, base), (policy, confidence) in best_by_base.items():
            if candidate_image != image:
                continue
            full_row = full_by.get((image, base))
            if full_row is None or full_row["accuracy_passed"].lower() != "true":
                continue
            candidates.append((policy, confidence, full_row))
        candidates.sort(key=lambda item: (
            float(item[2]["worst_warp_px"]), float(item[2]["mean_p90_warp_px"]),
            float(item[2]["mean_median_warp_px"]),
            item[1]["measured"]["zero_rotation_degradation_px"]))
        if not candidates:
            raise SystemExit(f"no eligible confidence policy for {image}")
        selected[image] = candidates[0]

    fields = [
        "image_series_class", "policy_id", "base_recipe", "threshold",
        "eligible", "accuracy_failures", "policy_failures", "selected_for_full_verification",
        "mean_median_warp_px", "mean_p90_warp_px", "worst_warp_px",
        "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
        "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
        "zero_rotation_degradation_px", "clean_accepted_pairs", "clean_declined_pairs",
        "gain_accepted_pairs", "gain_declined_pairs", "zero_accepted_pairs",
        "zero_declined_pairs",
    ]
    with (destination / "by_policy.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for image, policy in sorted(grouped):
            value = summaries[(image, policy)]
            measured = value["measured"]
            clean = value["condition_counts"]["RIGID_CLEAN"]
            gain = value["condition_counts"]["RIGID_GAIN_FADE_0_5"]
            zero = value["condition_counts"]["ZERO_ROTATION_CLEAN"]
            writer.writerow({
                "image_series_class": image,
                "policy_id": policy,
                "base_recipe": value["base_recipe"],
                "threshold": fmt(value["threshold"]),
                "eligible": value["eligible"],
                "accuracy_failures": "; ".join(value["failures"]),
                "policy_failures": "; ".join(value["policy_failures"]),
                "selected_for_full_verification": selected[image][0] == policy,
                "mean_median_warp_px": fmt(measured["mean_median_warp_px"]),
                "mean_p90_warp_px": fmt(measured["mean_p90_warp_px"]),
                "worst_warp_px": fmt(measured["worst_warp_px"]),
                "clean_mean_p90_angle_degrees": fmt(
                    measured["clean_mean_p90_angle_degrees"]),
                "clean_worst_angle_degrees": fmt(measured["clean_worst_angle_degrees"]),
                "gain_mean_p90_angle_degrees": fmt(
                    measured["gain_mean_p90_angle_degrees"]),
                "gain_worst_angle_degrees": fmt(measured["gain_worst_angle_degrees"]),
                "zero_rotation_degradation_px": fmt(
                    measured["zero_rotation_degradation_px"]),
                "clean_accepted_pairs": clean[0], "clean_declined_pairs": clean[1],
                "gain_accepted_pairs": gain[0], "gain_declined_pairs": gain[1],
                "zero_accepted_pairs": zero[0], "zero_declined_pairs": zero[1],
            })

    with (destination / "selected_policies.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow((
            "image_series_class", "policy_id", "base_recipe", "threshold",
            "pair_zero_rotation_degradation_px", "full_raw_mean_median_warp_px",
            "full_raw_mean_p90_warp_px", "full_raw_worst_warp_px"))
        for image in IMAGE_TYPES:
            policy, confidence, full_row = selected[image]
            writer.writerow((
                image, policy, confidence["base_recipe"], fmt(confidence["threshold"]),
                fmt(confidence["measured"]["zero_rotation_degradation_px"]),
                full_row["mean_median_warp_px"], full_row["mean_p90_warp_px"],
                full_row["worst_warp_px"]))

    full_controls_list: list[dict[str, str]] = []
    verified_rows: list[dict[str, str]] = []
    for image in IMAGE_TYPES:
        slug = SLUG[image]
        full_controls_list.extend(read_csv(
            run_root / f"r32_automatic_translation_full_control_{slug}" / "results.csv"))
        verified_rows.extend(read_csv(
            run_root / f"r34_split_rotation_full_confidence_{slug}" / "results.csv"))
    full_controls = {case_key(row): row for row in full_controls_list}
    if len(verified_rows) != 180 or len(full_controls) != 180:
        raise SystemExit("full confidence verification/control matrix is incomplete")
    verification_fields = [
        "image_series_class", "policy_id", "base_recipe", "threshold",
        "accuracy_passed", "accuracy_failures", "zero_guard_passed",
        "runtime_guard_passed", "raw_policy_failures", "rows", "failed_rows",
        "mean_median_warp_px", "control_mean_median_warp_px", "mean_p90_warp_px",
        "control_mean_p90_warp_px", "worst_warp_px", "control_worst_warp_px",
        "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
        "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
        "issue_recordings", "control_issue_recordings", "repaired_frames",
        "control_repaired_frames", "runtime_ratio", "zero_rotation_degradation_px",
    ]
    with (destination / "full_verification.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=verification_fields)
        writer.writeheader()
        for image in IMAGE_TYPES:
            part = [row for row in verified_rows if row["image_series_class"] == image]
            if len(part) != 36:
                raise SystemExit(f"expected 36 full verification rows for {image}")
            base, threshold, _, _ = policy_details(part[0])
            measured, failures, policy_failures = metrics(part, full_controls)
            writer.writerow({
                "image_series_class": image,
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
    print(destination / "selected_policies.csv")


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve())
