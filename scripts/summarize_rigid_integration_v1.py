#!/usr/bin/env python3
"""Gate the frozen rigid policies on the source-separated, previously spent integration set."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from summarize_rigid_selector_v1 import CATEGORY, IMAGE_TYPES, JOINT, ORACLE, evaluate, f, mean, maximum, number, integer


UNIVERSAL = "support_gradient__band_no_top_25__filter_gaussian_1_0__mask_least_informative_25"
TRANSLATION = {
    "BRIGHTFIELD_DIC": "support_gradient__band_full__filter_gaussian_0_7__mask_none",
    "DENSE_FLUOR": "support_gradient__band_full__filter_gaussian_1_0__mask_none",
    "FIDUCIAL_STATIC": "support_all__band_no_bottom_25__filter_gaussian_0_7__mask_none",
    "PHASE": UNIVERSAL,
    "SPARSE_LOWLIGHT": UNIVERSAL,
}


def load(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def has_issue(row: dict[str, str]) -> bool:
    return sum(integer(row, field) for field in (
        "refused_pairs", "non_converged_pairs", "shift_bound_pairs",
        "rotation_bound_pairs", "both_bound_pairs")) > 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--base-run", default="r06_source_separated_integration")
    parser.add_argument("--hybrid-run", default="r06_source_separated_hybrid")
    args = parser.parse_args()
    root = args.project.resolve() / "library" / "rigid_selector_tuning" / "runs" / "integration"
    base = load(root / args.base_run / "results.csv")
    hybrid = load(root / args.hybrid_run / "results.csv")
    joint = [row for row in base if row["arm"] == JOINT]
    oracle = [row for row in base if row["arm"] == ORACLE]
    if len(joint) != 180 or len(oracle) != 450 or len(hybrid) != 90:
        raise RuntimeError(f"expected 180 joint, 450 oracle and 90 hybrid rows; got "
                           f"{len(joint)}, {len(oracle)}, {len(hybrid)}")

    output = root / args.base_run / "summary"
    output.mkdir(parents=True, exist_ok=True)
    fields = ["image_series_class", "policy", "passed", "failures", "rows",
              "mean_median_warp_px", "category_mean_median_warp_px",
              "mean_p90_warp_px", "category_mean_p90_warp_px", "worst_warp_px",
              "clean_mean_p90_angle_degrees", "clean_worst_angle_degrees",
              "gain_mean_p90_angle_degrees", "gain_worst_angle_degrees",
              "issue_recordings", "category_issue_recordings", "repaired_frames",
              "category_repaired_frames", "runtime_ratio", "zero_rotation_degradation_px",
              "per_recording_guard_failures"]
    gate_rows = []
    for image in IMAGE_TYPES:
        category = [r for r in joint if r["image_series_class"] == image
                    and r["recipe_id"] == CATEGORY]
        policies = (
            ("universal_joint",
             [r for r in joint if r["image_series_class"] == image
              and r["recipe_id"] == UNIVERSAL], UNIVERSAL, 1.25),
            ("final_hybrid",
             [r for r in hybrid if r["image_series_class"] == image],
             TRANSLATION[image], 1.50),
        )
        for policy, candidate, oracle_recipe, runtime_limit in policies:
            candidate_oracle = [r for r in oracle if r["image_series_class"] == image
                                and r["recipe_id"] == oracle_recipe]
            failures, metrics = evaluate(candidate, category, candidate_oracle, runtime_limit)
            gate_rows.append({
                "image_series_class": image,
                "policy": policy,
                "passed": not failures,
                "failures": "; ".join(failures),
                **{key: f(value) if isinstance(value, float) else value
                   for key, value in metrics.items()},
            })
    with (output / "integration_gate_results.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(gate_rows)

    source_fields = ["image_series_class", "series_id", "policy", "rows",
                     "mean_median_warp_px", "mean_p90_warp_px", "worst_warp_px",
                     "worst_angle_degrees", "issue_recordings", "repaired_frames",
                     "mean_runtime_seconds"]
    source_rows = []
    for image in IMAGE_TYPES:
        series_ids = sorted({r["series_id"] for r in joint
                             if r["image_series_class"] == image})
        for series in series_ids:
            for policy, rows in (
                ("category", [r for r in joint if r["image_series_class"] == image
                              and r["series_id"] == series and r["recipe_id"] == CATEGORY]),
                ("universal_joint", [r for r in joint if r["image_series_class"] == image
                                     and r["series_id"] == series
                                     and r["recipe_id"] == UNIVERSAL]),
                ("final_hybrid", [r for r in hybrid if r["image_series_class"] == image
                                  and r["series_id"] == series]),
            ):
                source_rows.append({
                    "image_series_class": image,
                    "series_id": series,
                    "policy": policy,
                    "rows": len(rows),
                    "mean_median_warp_px": f(mean(number(r,
                        "median_warp_central50_px") for r in rows)),
                    "mean_p90_warp_px": f(mean(number(r,
                        "p90_warp_central50_px") for r in rows)),
                    "worst_warp_px": f(maximum(number(r,
                        "worst_warp_central50_px") for r in rows)),
                    "worst_angle_degrees": f(maximum(number(r,
                        "worst_angle_error_degrees") for r in rows)),
                    "issue_recordings": sum(has_issue(r) for r in rows),
                    "repaired_frames": sum(integer(r, "repaired_frames") for r in rows),
                    "mean_runtime_seconds": f(mean(number(r, "runtime_seconds") for r in rows)),
                })
    with (output / "integration_by_source.csv").open(
            "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=source_fields)
        writer.writeheader()
        writer.writerows(source_rows)

    passed = sum(row["passed"] for row in gate_rows)
    print(f"integration gates: {passed}/{len(gate_rows)} policy/image-type rows passed")


if __name__ == "__main__":
    main()
