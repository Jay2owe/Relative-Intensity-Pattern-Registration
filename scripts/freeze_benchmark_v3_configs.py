#!/usr/bin/env python3
"""Score the frozen v3 development screen and freeze one configuration per method/arm."""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from statistics import median
from typing import Iterable


IMAGE_TYPES = (
    "BRIGHTFIELD_DIC", "DENSE_FLUOR", "FIDUCIAL_STATIC", "PHASE", "SPARSE_LOWLIGHT"
)
CONDITIONS = ("U00", "U02", "U07", "U10")
EXTERNAL_METHOD_MAP = {
    "21_real_stackreg_turboreg_translation_chain": "21_stackreg_turboreg_chain",
    "22_real_turboreg_translation_multilag_rcc": "22_turboreg_multilag",
    "23_real_multistackreg_translation_same_engine": "23_multistackreg",
    "24_real_image_stabilizer_lucas_kanade_rolling_template": "24_image_stabilizer_lk",
    "25_real_fast4dreg_nanoj_previous_frame": "25_fast4dreg_previous",
    "26_real_fast4dreg_nanoj_first_frame": "26_fast4dreg_first",
    "27_real_correct_3d_drift_phase_correlation_standard": "27_correct3d_standard",
    "28_real_correct_3d_drift_phase_correlation_multitime": "28_correct3d_multitime",
    "29_real_linear_stack_alignment_sift_translation_chain": "29_sift_chain",
    "30_real_sift_translation_multilag_rcc": "30_sift_multilag",
    "31_register_virtual_stack_slices_same_sift_engine": "31_rvss",
    "32_real_descriptor_based_series_translation": "32_descriptor_series",
}
DEFAULT_CONFIGS = {
    "21_stackreg_turboreg_chain": "all_defaults",
    "22_turboreg_multilag": "all_defaults",
    "23_multistackreg": "all_defaults",
    "24_image_stabilizer_lk": "all_defaults",
    "25_fast4dreg_previous": "all_defaults",
    "26_fast4dreg_first": "all_defaults",
    "27_correct3d_standard": "all_defaults",
    "28_correct3d_multitime": "all_defaults",
    "29_sift_chain": "all_defaults",
    "30_sift_multilag": "all_defaults",
    "31_rvss": "all_defaults",
    "32_descriptor_series": "all_defaults",
    "33_skimage_phase_cross_correlation": "sk_up100_phase_default",
    "34_opencv_phase_correlate": "cv_phase_none_default",
    "35_opencv_ecc_translation": "ecc_i500_e1e-7_g5_default",
    "36_simpleitk_meansquares": "sitk_ms_rsgd_i300_default",
    "37_simpleitk_correlation": "sitk_cor_rsgd_i300_default",
    "38_simpleitk_mattes_mi": "sitk_mi_rsgd_i300_default",
    "39_suite2p_rigid": "suite_m03_s115_t0_default",
    "42_jnormcorre_rigid": "jn_max6_iter1_default",
    "43_pystackreg_translation": "pystackreg_translation_previous_default",
    "44_moco_translation": "moco_ds1_w0.2_default",
}
FIELDS = (
    "runner", "method_id", "method_label", "preprocessing_arm", "config_id",
    "expected", "observed", "successful", "failures", "failure_rate",
    "balanced_median_error_px", "balanced_p90_error_px", "balanced_elapsed_seconds",
    "is_declared_default", "selection_rank", "selected", "input_sha256"
)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def quantile(values: Iterable[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return math.inf
    index = int(math.floor(fraction * (len(ordered) - 1) + 0.5))
    return ordered[index]


def finite(value: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return math.inf
    return result if math.isfinite(result) else math.inf


def condition_id(row: dict[str, str]) -> str:
    value = row.get("condition_id") or row.get("condition", "")
    return value.split("_r", 1)[0]


def normalize_external(row: dict[str, str]) -> dict[str, str] | None:
    method_id = EXTERNAL_METHOD_MAP.get(row.get("method_id", ""))
    if method_id is None:
        return None
    copied = dict(row)
    copied["method_id"] = method_id
    copied["condition_id"] = condition_id(row)
    copied["preprocessing_arm"] = row.get("preprocessing_arm") or "native"
    return copied


def load_inputs(project: Path) -> tuple[set[tuple[str, str, str]], dict[str, str]]:
    subset = read_csv(project / "library/benchmark/v3/protocol/tuning_subset.csv")
    classes = {row["source_id"]: row["image_series_class"] for row in subset}
    expected = {(source, condition, "CURVED_OSCILLATING_DRIFT")
                for source in classes for condition in CONDITIONS}
    if len(expected) != 20 or set(classes.values()) != set(IMAGE_TYPES):
        raise RuntimeError("frozen tuning subset is not the declared 5 x 4 screen")
    return expected, classes


def load_results(project: Path) -> tuple[list[dict[str, str]], list[Path]]:
    tuning = project / "library/benchmark/v3/runs/development/tuning"
    paths = [tuning / "internal_tuning_results.csv", tuning / "python_tuning_results.csv",
             tuning / "jnormcorre_tuning_results.csv", tuning / "moco_tuning_results.csv"]
    rows: list[dict[str, str]] = []
    for runner, path in zip(("internal", "python", "jnormcorre", "moco"), paths):
        if not path.exists():
            raise FileNotFoundError(path)
        for row in read_csv(path):
            copied = dict(row)
            copied["runner"] = runner
            copied["condition_id"] = condition_id(row)
            copied["config_id"] = row.get("config_id") or row["method_id"]
            rows.append(copied)

    external_root = project / "library/benchmark/v3/runs/external_parameter_sweep_v1/v3_development"
    external_paths = sorted(external_root.glob("*.csv"))
    if not external_paths:
        raise FileNotFoundError(f"no external tuning CSVs under {external_root}")
    for path in external_paths:
        for raw in read_csv(path):
            row = normalize_external(raw)
            if row is None:
                continue
            row["runner"] = "external"
            rows.append(row)
    return rows, paths + external_paths


def score(rows: list[dict[str, str]], expected: set[tuple[str, str, str]],
          classes: dict[str, str], input_digest: str) -> list[dict[str, object]]:
    grouped: dict[tuple[str, str, str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        source = row.get("series_id", "")
        key = (source, condition_id(row), row.get("motion_category", ""))
        if key not in expected:
            continue
        grouped[(row["runner"], row["method_id"], row["preprocessing_arm"],
                 row["config_id"])].append(row)

    scored: list[dict[str, object]] = []
    duplicate_replays = 0
    substantive = ("status", "median_error_px", "p90_error_px", "max_error_px",
                   "terminal_error_px", "transform_x_px", "transform_y_px")
    for (runner, method_id, arm, config_id), candidates in grouped.items():
        by_recording: dict[tuple[str, str, str], dict[str, str]] = {}
        for row in candidates:
            key = (row["series_id"], condition_id(row), row["motion_category"])
            if key in by_recording:
                previous = by_recording[key]
                if any(previous.get(field, "") != row.get(field, "")
                       for field in substantive):
                    raise RuntimeError(
                        f"conflicting duplicate tuning result: "
                        f"{runner}/{method_id}/{arm}/{config_id}/{key}")
                # A detached runner can be restarted while an older process is
                # finishing.  Identical replayed measurements count once; wall
                # time and heap readings are deliberately not pooled.
                duplicate_replays += 1
                continue
            by_recording[key] = row
        successful_rows = [row for key, row in by_recording.items()
                           if key in expected and row.get("status") == "ok" and
                           math.isfinite(finite(row.get("median_error_px", "")))]
        failures = len(expected) - len(successful_rows)
        type_medians: list[float] = []
        type_p90s: list[float] = []
        type_times: list[float] = []
        for image_type in IMAGE_TYPES:
            source = next(source for source, value in classes.items() if value == image_type)
            type_rows = [by_recording.get((source, condition, "CURVED_OSCILLATING_DRIFT"))
                         for condition in CONDITIONS]
            type_medians.append(quantile([
                finite(row.get("median_error_px", "")) if row and row.get("status") == "ok"
                else math.inf for row in type_rows], 0.5))
            type_p90s.append(quantile([
                finite(row.get("p90_error_px", "")) if row and row.get("status") == "ok"
                else math.inf for row in type_rows], 0.9))
            type_times.append(quantile([
                finite(row.get("elapsed_seconds", "")) if row and row.get("status") == "ok"
                else math.inf for row in type_rows], 0.5))
        label = candidates[0].get("method_label", method_id)
        scored.append({
            "runner": runner, "method_id": method_id, "method_label": label,
            "preprocessing_arm": arm, "config_id": config_id,
            "expected": len(expected), "observed": len(by_recording),
            "successful": len(successful_rows), "failures": failures,
            "failure_rate": failures / len(expected),
            "balanced_median_error_px": sum(type_medians) / len(type_medians),
            "balanced_p90_error_px": sum(type_p90s) / len(type_p90s),
            "balanced_elapsed_seconds": sum(type_times) / len(type_times),
            "is_declared_default": str(DEFAULT_CONFIGS.get(method_id) == config_id).lower(),
            "selection_rank": 0, "selected": "false", "input_sha256": input_digest,
        })
    if duplicate_replays:
        print(f"Ignored {duplicate_replays} substantively identical tuning replays")
    return scored


def objective(row: dict[str, object]) -> tuple[float, float, float, float, str]:
    return (float(row["failure_rate"]), float(row["balanced_median_error_px"]),
            float(row["balanced_p90_error_px"]), float(row["balanced_elapsed_seconds"]),
            str(row["config_id"]))


def format_float(value: object) -> object:
    if isinstance(value, float):
        return "inf" if not math.isfinite(value) else f"{value:.9f}"
    return value


def write_csv(path: Path, rows: list[dict[str, object]], fields: Iterable[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(fields), extrasaction="ignore")
        writer.writeheader()
        writer.writerows({key: format_float(value) for key, value in row.items()} for row in rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    project = args.project.resolve()
    expected, classes = load_inputs(project)
    rows, paths = load_results(project)
    digest = hashlib.sha256("".join(f"{path}:{sha256(path)}\n" for path in paths).encode()).hexdigest()
    scored = score(rows, expected, classes, digest)

    grouped: dict[tuple[str, str], list[dict[str, object]]] = defaultdict(list)
    for row in scored:
        grouped[(str(row["method_id"]), str(row["preprocessing_arm"]))].append(row)
    winners: list[dict[str, object]] = []
    for candidates in grouped.values():
        for rank, row in enumerate(sorted(candidates, key=objective), 1):
            row["selection_rank"] = rank
            if rank == 1:
                row["selected"] = "true"
                winners.append(dict(row))

    # Every declared Python/external default must have completed, even if it loses tuning.
    available = {(str(row["method_id"]), str(row["preprocessing_arm"]), str(row["config_id"]))
                 for row in scored}
    missing_defaults = [(method, arm, config) for method, config in DEFAULT_CONFIGS.items()
                        for arm in ("native", "common_normalized")
                        if (method, arm, config) not in available]
    if missing_defaults:
        raise RuntimeError(f"missing declared default screens: {missing_defaults}")
    incomplete = [row for row in scored if int(row["observed"]) != len(expected)]
    if incomplete:
        raise RuntimeError(f"incomplete candidate screens: {len(incomplete)}")

    frozen = project / "library/benchmark/v3/protocol/frozen_config"
    scored.sort(key=lambda row: (str(row["runner"]), str(row["method_id"]),
                                 str(row["preprocessing_arm"]), int(row["selection_rank"])))
    winners.sort(key=lambda row: (str(row["runner"]), str(row["method_id"]),
                                  str(row["preprocessing_arm"])))
    write_csv(frozen / "candidate_scores.csv", scored, FIELDS)
    write_csv(frozen / "winners.csv", winners, FIELDS)

    # Freeze the confirmatory external comparator without reading publication-test labels.  First
    # pick the best method within each independent family, then the best family representative.
    manifest = {row["method_id"]: row for row in read_csv(
        project / "library/benchmark/v3/protocol/method_manifest.csv")}
    eligible_roles = {"independent", "independent_metric", "implementation_parity"}
    comparator_rows: list[dict[str, object]] = []
    for arm in ("native", "common_normalized"):
        arm_candidates = [row for row in winners
                          if row["preprocessing_arm"] == arm
                          and manifest.get(str(row["method_id"]), {}).get("status") == "available"
                          and manifest.get(str(row["method_id"]), {}).get("role") in eligible_roles
                          and str(row["method_id"])[:2].isdigit()
                          and int(str(row["method_id"])[:2]) >= 21]
        family_best: dict[str, dict[str, object]] = {}
        for row in arm_candidates:
            family = manifest[str(row["method_id"])]["family"]
            if family not in family_best or objective(row) < objective(family_best[family]):
                family_best[family] = row
        if not family_best:
            raise RuntimeError(f"no independent external comparator candidates for {arm}")
        winner = min(family_best.values(), key=objective)
        comparator_rows.append({
            "preprocessing_arm": arm, "method_id": winner["method_id"],
            "method_label": winner["method_label"],
            "family": manifest[str(winner["method_id"])]["family"],
            "config_id": winner["config_id"],
            "failure_rate": winner["failure_rate"],
            "balanced_median_error_px": winner["balanced_median_error_px"],
            "balanced_p90_error_px": winner["balanced_p90_error_px"],
            "selection_rule": "best family representative by frozen lexicographic tuning objective",
            # REGRESSION GUARD: main() names the frozen result-set digest ``digest``;
            # using the score() parameter name here crashes only after winners.csv is written.
            "input_sha256": digest,
        })
    write_csv(frozen / "primary_comparator_selection.csv", comparator_rows,
              ("preprocessing_arm", "method_id", "method_label", "family", "config_id",
               "failure_rate", "balanced_median_error_px", "balanced_p90_error_px",
               "selection_rule", "input_sha256"))
    selector_baselines = []
    for arm in ("native", "common_normalized"):
        options = [row for row in winners if row["preprocessing_arm"] == arm
                   and row["method_id"] in {"01_log_ratio_tukey_standard_gradient",
                                            "48_area_correlation_newton"}]
        if len(options) != 2:
            raise RuntimeError(f"selector baseline candidates incomplete for {arm}")
        selected = min(options, key=objective)
        selector_baselines.append({
            "preprocessing_arm": arm, "method_id": selected["method_id"],
            "method_label": selected["method_label"], "config_id": selected["config_id"],
            "failure_rate": selected["failure_rate"],
            "balanced_median_error_px": selected["balanced_median_error_px"],
            "balanced_p90_error_px": selected["balanced_p90_error_px"],
            "selection_rule": "best prespecified fixed plugin mode on frozen development objective",
            "input_sha256": digest,
        })
    write_csv(frozen / "selector_baseline_selection.csv", selector_baselines,
              ("preprocessing_arm", "method_id", "method_label", "config_id",
               "failure_rate", "balanced_median_error_px", "balanced_p90_error_px",
               "selection_rule", "input_sha256"))

    lines = ["# Frozen v3 configuration selection", "",
             f"Frozen UTC: {datetime.now(timezone.utc).isoformat()}", "",
             "Objective: failure rate, image-type-balanced median error, balanced p90 error, then runtime.",
             "Every candidate used the same five-source by four-condition screen in both preprocessing arms.", "",
             "| Method | Arm | Winner | Failure | Balanced median (px) | Balanced p90 (px) |",
             "|---|---|---|---:|---:|---:|"]
    for row in winners:
        lines.append(f"| {row['method_id']} | {row['preprocessing_arm']} | {row['config_id']} | "
                     f"{float(row['failure_rate']):.3f} | {float(row['balanced_median_error_px']):.4f} | "
                     f"{float(row['balanced_p90_error_px']):.4f} |")
    lines += ["", f"Input result-set digest: `{digest}`", ""]
    (frozen / "TUNING_REPORT.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Frozen {len(winners)} method/arm configurations from {len(scored)} candidate screens")


if __name__ == "__main__":
    main()
