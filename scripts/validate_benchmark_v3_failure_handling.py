#!/usr/bin/env python3
"""Exercise the frozen failure paths without touching publication-test data."""

from __future__ import annotations

import argparse
import importlib.util
import math
import tempfile
from pathlib import Path

import pandas as pd


def load_analysis(project: Path):
    path = project / "scripts/analyze_benchmark_v3.py"
    spec = importlib.util.spec_from_file_location("benchmark_v3_analysis", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    project = args.project.resolve()
    analysis = load_analysis(project)

    key = ("failure_fixture", "CURVED_OSCILLATING_DRIFT", "U00_r00")
    metadata = {key: {"penalty": 12.5, "frames": 48.0, "width": 128.0,
                      "truth_x": [], "truth_y": []}}
    cases = [
        ("timeout", "timeout", 0.1, ""),
        ("crash", "crash", 0.1, ""),
        ("algorithm_refusal", "algorithm_refusal", 0.1, ""),
        ("missing_output", "missing_output", 0.1, ""),
        ("nonfinite_transform", "ok", 0.1, "NaN;" + ";".join(["0"] * 47)),
    ]
    raw = []
    for mode, status, metric, transform_x in cases:
        raw.append({
            "series_id": key[0], "motion_category": key[1], "condition_instance": key[2],
            "condition_id": "U00", "status": status, "median_error_px": metric,
            "p90_error_px": metric, "max_error_px": metric, "terminal_error_px": metric,
            "frames_over_1px": 0 if mode == "nonfinite_transform" else math.nan,
            "frames_over_5px": 0 if mode == "nonfinite_transform" else math.nan,
            "transform_x_px": transform_x,
            "transform_y_px": ";".join(["0"] * 48) if transform_x else "",
            "transform_theta_rad": "",
            "failure_mode": mode,
        })
    scored = analysis.add_penalties(pd.DataFrame(raw), metadata)

    # A truly absent result must fail the denominator audit, rather than being
    # silently treated as a method that was not applicable.
    inputs = pd.DataFrame([{
        "series_id": key[0], "motion_category": key[1],
        "condition_instance": key[2], "condition_id": "U00",
    }])
    methods = pd.DataFrame([{
        "method_id": "fixture", "status": "available", "universal_panel": "true",
        "dose_panel": "false", "default_arm": "true", "tuned_arm": "true",
    }])
    conditions = pd.DataFrame([{"condition_id": "U00", "panel": "universal"}])
    columns = ["benchmark_view", "series_id", "motion_category", "condition_instance",
               "condition_id", "method_id", "preprocessing_arm"]
    missing_detected = False
    with tempfile.TemporaryDirectory() as temporary:
        try:
            analysis.enforce_completeness(
                pd.DataFrame(columns=columns), inputs, methods, conditions,
                Path(temporary) / "completeness.csv")
        except RuntimeError:
            missing_detected = True

    output = []
    for row in scored.itertuples():
        retained = True
        penalty_applied = row.scored_median_error_px == 12.5 \
            and row.scored_p90_error_px == 12.5
        passed = retained and not row.algorithm_success and penalty_applied
        if row.failure_mode == "missing_output":
            passed = passed and missing_detected
        output.append({
            "failure_mode": row.failure_mode, "input_status": row.status,
            "retained_in_denominator": str(retained).lower(),
            "algorithm_success": str(bool(row.algorithm_success)).lower(),
            "failure_penalty_px": row.failure_penalty_px,
            "penalty_applied": str(penalty_applied).lower(),
            "missing_row_detected": str(missing_detected).lower()
                if row.failure_mode == "missing_output" else "not_applicable",
            "status": "pass" if passed else "fail",
        })
    destination = project / "library/benchmark/v3/protocol/failure_handling_validation.csv"
    pd.DataFrame(output).to_csv(destination, index=False)
    if any(row["status"] != "pass" for row in output):
        raise RuntimeError(f"failure handling validation failed: {destination}")
    print(f"failure handling validation passed: {len(output)} modes; {destination}")


if __name__ == "__main__":
    main()
