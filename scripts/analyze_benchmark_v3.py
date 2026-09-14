#!/usr/bin/env python3
"""Failure-aware, acquisition-clustered publication analysis for Registration Benchmark v3."""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


EXTERNAL_MAP = {
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
PANEL_GROUPS = {
    "U00": "clean", "U01": "gain", "U02": "gain", "U03": "gain", "U04": "gain",
    "U05": "gain", "U06": "local_change", "U07": "local_change",
    "U08": "adversarial", "U09": "additive_noise", "U10": "additive_noise",
    "U11": "combined", "D01": "dose", "G075": "dose", "C025": "dose",
    "C200": "dose", "NINF": "dose", "N025": "dose", "N010": "dose", "N000": "dose",
}
ALIASES_OR_PORTS = {"23_multistackreg", "31_rvss", "43_pystackreg_translation",
                    "42_jnormcorre_rigid"}
BOOTSTRAPS = 20000
SEED = 20260820


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_frozen_protocol(project: Path) -> None:
    protocol = project / "library/benchmark/v3/protocol"
    gate_path = protocol / "PROTOCOL_FROZEN.txt"
    gate = {}
    for line in gate_path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1); gate[key] = value
    manifest_path = protocol / "frozen_protocol_hashes.csv"
    observed_manifest = sha256_file(manifest_path)
    if gate.get("hash_manifest_sha256") != observed_manifest:
        raise RuntimeError("frozen protocol hash manifest changed after the seal")
    drift = []
    for row in read_csv(manifest_path):
        path = project / row["relative_path"]
        observed = sha256_file(path) if path.is_file() else "missing"
        if observed != row["sha256"]:
            drift.append(f"{row['relative_path']} expected={row['sha256']} observed={observed}")
    environment = protocol / "frozen_environment.txt"
    if gate.get("environment_sha256") != sha256_file(environment):
        drift.append("library/benchmark/v3/protocol/frozen_environment.txt")
    if drift:
        raise RuntimeError("frozen protocol drift:\n" + "\n".join(drift[:20]))


def finite(value: object) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return math.nan
    return result if math.isfinite(result) else math.nan


def stable_offset(parts: object) -> int:
    encoded = "|".join(map(str, parts if isinstance(parts, tuple) else (parts,)))
    return sum((index + 1) * ord(character) for index, character in enumerate(encoded)) % 100000


def condition_id(value: str) -> str:
    return value.split("_r", 1)[0]


def balanced_bootstrap(values: pd.DataFrame, value: str, fraction: float = 0.95,
                       seed_offset: int = 0) -> tuple[float, float]:
    generator = np.random.default_rng(SEED + seed_offset)
    types = sorted(values["image_series_class"].unique())
    type_draws = []
    for image_type in types:
        pool = values.loc[values["image_series_class"] == image_type, value].to_numpy(float)
        pool = pool[np.isfinite(pool)]
        if not len(pool):
            return math.nan, math.nan
        type_draws.append(generator.choice(pool, size=(BOOTSTRAPS, len(pool)),
                                           replace=True).mean(axis=1))
    draws = np.mean(np.vstack(type_draws), axis=0)
    alpha = 1.0 - fraction
    return float(np.quantile(draws, alpha / 2)), float(np.quantile(draws, 1 - alpha / 2))


def paired_bootstrap(values: pd.DataFrame, value: str, upper_quantile: float = 0.975,
                     seed_offset: int = 0, null_threshold: float = 0.0
                     ) -> tuple[float, float, float, float]:
    generator = np.random.default_rng(SEED + seed_offset)
    types = sorted(values["image_series_class"].unique())
    type_draws = []
    type_estimates = []
    for image_type in types:
        pool = values.loc[values["image_series_class"] == image_type, value].to_numpy(float)
        pool = pool[np.isfinite(pool)]
        if not len(pool):
            raise RuntimeError(f"empty paired bootstrap stratum: {image_type}/{value}")
        type_estimates.append(float(np.mean(pool)))
        type_draws.append(generator.choice(pool, size=(BOOTSTRAPS, len(pool)),
                                           replace=True).mean(axis=1))
    draws = np.mean(np.vstack(type_draws), axis=0)
    # All confirmatory alternatives are lower-is-better.  The plus-one correction
    # prevents a zero Monte Carlo p-value and makes the deterministic bootstrap
    # test conservative at its finite resampling resolution.
    raw_p = float((1 + np.count_nonzero(draws >= null_threshold)) / (BOOTSTRAPS + 1))
    return (float(np.mean(type_estimates)),
            float(np.quantile(draws, 0.025)), float(np.quantile(draws, upper_quantile)),
            raw_p)


def apply_holm(gates: pd.DataFrame) -> pd.DataFrame:
    """Apply one Holm correction over the five preregistered hypotheses.

    The native arm is the prospective confirmatory arm; common normalization is
    a scope/sensitivity analysis that determines whether a native-input advantage
    survives equal preprocessing.  H3 already has only its native crossing.
    """
    output = gates.copy()
    output["inference_role"] = np.where(
        output.preprocessing_arm == "native", "holm_confirmatory", "normalized_sensitivity")
    primary = output[output.inference_role == "holm_confirmatory"]
    hypothesis_p = primary.set_index("hypothesis")["raw_p"].to_dict()
    ordered = sorted(hypothesis_p, key=hypothesis_p.get)
    adjusted: dict[str, float] = {}
    running = 0.0
    count = len(ordered)
    for rank, hypothesis in enumerate(ordered):
        running = max(running, (count - rank) * hypothesis_p[hypothesis])
        adjusted[hypothesis] = min(1.0, running)
    output["hypothesis_raw_p"] = np.where(
        output.inference_role == "holm_confirmatory", output.hypothesis.map(hypothesis_p),
        output.raw_p)
    output["holm_adjusted_p"] = np.where(
        output.inference_role == "holm_confirmatory", output.hypothesis.map(adjusted), math.nan)
    output["interval_pass"] = output["pass"].astype(bool)
    output["pass"] = np.where(
        output.inference_role == "holm_confirmatory",
        output.interval_pass & (output.holm_adjusted_p < 0.05), output.interval_pass)
    return output


def canonical_row(raw: dict[str, str], runner: str) -> dict[str, object]:
    condition_instance = raw.get("condition_instance") or raw.get("condition", "")
    return {
        "runner": runner, "image_series_class": raw.get("image_series_class", ""),
        "series_id": raw.get("series_id", ""),
        "independent_group": raw.get("independent_group", ""),
        "lab_id": raw.get("lab_id", ""), "motion_category": raw.get("motion_category", ""),
        "condition_id": raw.get("condition_id") or condition_id(condition_instance),
        "condition_instance": condition_instance, "replicate": raw.get("replicate", ""),
        "method_id": EXTERNAL_MAP.get(raw.get("method_id", ""), raw.get("method_id", "")),
        "method_label": raw.get("method_label", ""), "family": raw.get("family", ""),
        "strategy": raw.get("strategy", ""), "config_id": raw.get("config_id", ""),
        "status": raw.get("status", ""), "median_error_px": finite(raw.get("median_error_px")),
        "p90_error_px": finite(raw.get("p90_error_px")),
        "max_error_px": finite(raw.get("max_error_px")),
        "terminal_error_px": finite(raw.get("terminal_error_px")),
        "frames_over_1px": finite(raw.get("frames_over_1px")),
        "frames_over_5px": finite(raw.get("frames_over_5px")),
        "cpu_seconds": finite(raw.get("cpu_seconds")),
        "elapsed_seconds": finite(raw.get("elapsed_seconds")), "pairs": finite(raw.get("pairs")),
        "peak_memory_mb": finite(raw.get("peak_rss_mb") or raw.get("peak_heap_mb")),
        "preprocessing_arm": raw.get("preprocessing_arm", "native"),
        "normalization_fallback": raw.get("normalization_fallback", "false"),
        "transform_x_px": raw.get("transform_x_px", ""),
        "transform_y_px": raw.get("transform_y_px", ""),
        "transform_theta_rad": raw.get("transform_theta_rad", ""),
        "details": raw.get("details", ""),
    }


def load_results(project: Path, winners: pd.DataFrame) -> pd.DataFrame:
    root = project / "library/benchmark/v3/runs/publication_test"
    rows: list[dict[str, object]] = []

    def add_file(path: Path, runner: str, views: tuple[str, ...]) -> None:
        if not path.is_file():
            raise FileNotFoundError(path)
        for raw in read_csv(path):
            row = canonical_row(raw, runner)
            for view in views:
                copied = dict(row); copied["benchmark_view"] = view; rows.append(copied)

    add_file(root / "internal_results.csv", "internal", ("defaults", "tuned"))
    add_file(root / "python_default_results.csv", "python", ("defaults",))
    add_file(root / "python_tuned_results.csv", "python", ("tuned",))
    add_file(root / "jnormcorre_default_results.csv", "jnormcorre", ("defaults",))
    add_file(root / "jnormcorre_tuned_results.csv", "jnormcorre", ("tuned",))
    add_file(root / "moco_default_results.csv", "moco", ("defaults",))
    add_file(root / "moco_tuned_results.csv", "moco", ("tuned",))

    winner_keys = {(row.method_id, row.preprocessing_arm, row.config_id)
                   for row in winners.itertuples() if row.runner == "external"}
    external_root = project / "library/benchmark/v3/runs/external_parameter_sweep_v1/v3_publication_test"
    paths = sorted(external_root.glob("*.csv"))
    if not paths:
        raise FileNotFoundError(f"no external results under {external_root}")
    external_unique: dict[tuple[str, ...], dict[str, object]] = {}
    for path in paths:
        for raw in read_csv(path):
            row = canonical_row(raw, "external")
            views = []
            if row["config_id"] == "all_defaults":
                views.append("defaults")
            if (row["method_id"], row["preprocessing_arm"], row["config_id"]) in winner_keys:
                views.append("tuned")
            for view in views:
                copied = dict(row); copied["benchmark_view"] = view
                key = tuple(str(copied[field]) for field in
                            ("benchmark_view", "series_id", "motion_category",
                             "condition_instance", "method_id", "preprocessing_arm", "config_id"))
                previous = external_unique.get(key)
                if previous is not None:
                    check = ("status", "median_error_px", "p90_error_px", "transform_x_px", "transform_y_px")
                    if any(str(previous[field]) != str(copied[field]) for field in check):
                        raise RuntimeError(f"conflicting duplicate external row {key}")
                else:
                    external_unique[key] = copied
    rows.extend(external_unique.values())
    return pd.DataFrame(rows)


def input_tables(project: Path) -> tuple[pd.DataFrame, dict[tuple[str, str, str], dict[str, object]]]:
    path = project / "library/benchmark/v3/inputs/publication_test/inputs_manifest.csv"
    inputs = pd.read_csv(path, dtype=str).fillna("")
    metadata = {}
    for row in inputs.itertuples():
        x = np.asarray([float(value) for value in row.truth_x_px.split(";")])
        y = np.asarray([float(value) for value in row.truth_y_px.split(";")])
        metadata[(row.series_id, row.motion_category, row.condition_instance)] = {
            "penalty": float(np.max(np.hypot(x, y)) + 5.0),
            "frames": float(len(x)),
            "width": float(row.width),
            "independent_group": row.independent_group,
            "truth_x": x,
            "truth_y": y,
        }
    return inputs, metadata


def enforce_completeness(results: pd.DataFrame, inputs: pd.DataFrame,
                         methods: pd.DataFrame, conditions: pd.DataFrame,
                         output: Path) -> pd.DataFrame:
    panel = dict(zip(conditions.condition_id, conditions.panel))
    method_rows = methods.set_index("method_id").to_dict("index")
    allowed = []
    for row in results.itertuples():
        declaration = method_rows.get(row.method_id)
        if declaration is None or declaration["status"] != "available":
            continue
        declared = declaration["universal_panel"] if panel[row.condition_id] == "universal" \
            else declaration["dose_panel"]
        if str(declared).lower() == "true":
            allowed.append(row.Index)
    results = results.loc[allowed].copy()
    key_fields = ["benchmark_view", "series_id", "motion_category", "condition_instance",
                  "method_id", "preprocessing_arm"]
    duplicate = results.duplicated(key_fields, keep=False)
    if duplicate.any():
        raise RuntimeError(f"duplicate canonical result rows: {int(duplicate.sum())}")
    actual = {tuple(str(getattr(row, field)) for field in key_fields)
              for row in results.itertuples()}
    expected = set()
    for trial in inputs.itertuples():
        trial_panel = panel[trial.condition_id]
        for method in methods.itertuples():
            if method.status != "available":
                continue
            applicable = method.universal_panel if trial_panel == "universal" else method.dose_panel
            if str(applicable).lower() != "true":
                continue
            for view, view_flag in (("defaults", method.default_arm), ("tuned", method.tuned_arm)):
                if str(view_flag).lower() != "true":
                    continue
                for arm in ("native", "common_normalized"):
                    expected.add((view, trial.series_id, trial.motion_category,
                                  trial.condition_instance, method.method_id, arm))
    missing = expected - actual
    extra = actual - expected
    output.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame([{"expected_rows": len(expected), "actual_rows": len(actual),
                   "missing_rows": len(missing), "extra_rows": len(extra),
                   "status": "pass" if not missing and not extra else "fail"}]).to_csv(output, index=False)
    if missing or extra:
        sample = list(sorted(missing))[:10]
        raise RuntimeError(f"publication completeness failed: missing={len(missing)} extra={len(extra)} sample={sample}")
    return results


def add_penalties(results: pd.DataFrame,
                  metadata: dict[tuple[str, str, str], dict[str, object]]) -> pd.DataFrame:
    output = results.copy()
    recording = [metadata[(row.series_id, row.motion_category, row.condition_instance)]
                 for row in output.itertuples()]
    penalty = np.asarray([row["penalty"] for row in recording])
    frames = np.asarray([row["frames"] for row in recording])
    # The generated input manifest is authoritative.  This also fills the field
    # for adapters whose native output schema does not repeat acquisition IDs.
    existing_groups = (output["independent_group"].astype(str).tolist()
                       if "independent_group" in output else output["series_id"].astype(str).tolist())
    output["independent_group"] = [
        row.get("independent_group") or existing_groups[index] or str(output.iloc[index]["series_id"])
        for index, row in enumerate(recording)
    ]
    finite_metrics = np.logical_and.reduce([
        np.isfinite(output[field].to_numpy(float)) for field in
        ("median_error_px", "p90_error_px", "max_error_px", "terminal_error_px",
         "frames_over_1px", "frames_over_5px")
    ])
    count_metrics = ((output.frames_over_1px >= 0) & (output.frames_over_1px <= frames)
                     & (output.frames_over_5px >= 0) & (output.frames_over_5px <= frames))
    def text_column(field: str) -> pd.Series:
        if field in output:
            return output[field].fillna("").astype(str)
        return pd.Series([""] * len(output), index=output.index, dtype=str)

    x_text = text_column("transform_x_px")
    y_text = text_column("transform_y_px")
    theta_text = text_column("transform_theta_rad")
    x_length = x_text.str.count(";").to_numpy() + 1
    y_length = y_text.str.count(";").to_numpy() + 1
    theta_length = theta_text.str.count(";").to_numpy() + 1
    nonfinite_token = r"(?i)(?:^|;)[+-]?(?:nan|inf(?:inity)?)(?:;|$)"
    transform_finite = (~x_text.str.contains(nonfinite_token, regex=True)
                        & ~y_text.str.contains(nonfinite_token, regex=True)
                        & ~theta_text.str.contains(nonfinite_token, regex=True))
    transform_complete = ((x_text != "") & (y_text != "")
                          & (x_length == frames) & (y_length == frames)
                          & ((theta_text == "") | (theta_length == frames))
                          & transform_finite)
    ok = (output.status == "ok") & finite_metrics & count_metrics & transform_complete
    output["algorithm_success"] = ok
    output["analysis_status"] = np.where(
        ok, "ok", np.where(output.status == "ok", "invalid_result", output.status))
    output["failure_penalty_px"] = penalty
    output["scored_median_error_px"] = np.where(ok, output.median_error_px, penalty)
    output["scored_p90_error_px"] = np.where(
        ok & np.isfinite(output.p90_error_px), output.p90_error_px, penalty)
    output["scored_max_error_px"] = np.where(
        ok & np.isfinite(output.max_error_px), output.max_error_px, penalty)
    output["scored_terminal_error_px"] = np.where(
        ok & np.isfinite(output.terminal_error_px), output.terminal_error_px, penalty)
    output["frames_total"] = frames
    output["scored_frames_over_1_rate"] = np.where(
        ok & np.isfinite(output.frames_over_1px), output.frames_over_1px / frames, 1.0)
    output["scored_frames_over_5_rate"] = np.where(
        ok & np.isfinite(output.frames_over_5px), output.frames_over_5px / frames, 1.0)
    output["panel_group"] = output.condition_id.map(PANEL_GROUPS)
    output.loc[output.condition_id.str.startswith("U"), "universal_group"] = "overall_universal"
    return output


def nearest_quantile(values: np.ndarray, fraction: float) -> float:
    ordered = np.sort(np.asarray(values, dtype=float))
    index = int(math.floor(fraction * (len(ordered) - 1) + 0.5))
    return float(ordered[index])


def audit_transform_recalculation(results: pd.DataFrame,
                                  metadata: dict[tuple[str, str, str], dict[str, object]],
                                  output: Path, per_runner: int = 50) -> None:
    successful = results[results.algorithm_success & (results.transform_x_px != "")].copy()
    successful["audit_order"] = successful.apply(
        lambda row: hashlib.sha256(
            f"{row.runner}|{row.benchmark_view}|{row.series_id}|{row.motion_category}|"
            f"{row.condition_instance}|{row.method_id}|{row.preprocessing_arm}".encode()).hexdigest(),
        axis=1)
    sample = successful.sort_values("audit_order").groupby("runner", as_index=False).head(per_runner)
    rows = []
    for row in sample.itertuples():
        record = metadata[(row.series_id, row.motion_category, row.condition_instance)]
        truth_x = np.asarray(record["truth_x"]); truth_y = np.asarray(record["truth_y"])
        x = np.asarray([float(value) for value in row.transform_x_px.split(";")])
        y = np.asarray([float(value) for value in row.transform_y_px.split(";")])
        theta = (np.asarray([float(value) for value in row.transform_theta_rad.split(";")])
                 if row.transform_theta_rad else np.zeros_like(x))
        length_ok = len(x) == len(truth_x) and len(y) == len(truth_y) \
            and len(theta) == len(truth_x)
        if length_ok:
            coordinate_variance = (float(record["width"]) ** 2 - 1.0) / 12.0
            errors = np.sqrt(np.maximum(
                0.0, (x - truth_x) ** 2 + (y - truth_y) ** 2
                + 4.0 * coordinate_variance * (1.0 - np.cos(theta))))
            recalculated = {
                "median": nearest_quantile(errors, .5), "p90": nearest_quantile(errors, .9),
                "maximum": nearest_quantile(errors, 1.0), "terminal": float(errors[-1]),
                "over_1": int(np.sum(errors > 1.0)), "over_5": int(np.sum(errors > 5.0)),
            }
            differences = [
                abs(recalculated["median"] - row.median_error_px),
                abs(recalculated["p90"] - row.p90_error_px),
                abs(recalculated["maximum"] - row.max_error_px),
                abs(recalculated["terminal"] - row.terminal_error_px),
            ]
            exact_counts = recalculated["over_1"] == int(row.frames_over_1px) \
                and recalculated["over_5"] == int(row.frames_over_5px)
            passed = max(differences) <= 2e-7 and exact_counts
        else:
            recalculated = {key: math.nan for key in
                            ("median", "p90", "maximum", "terminal", "over_1", "over_5")}
            differences = [math.inf]; exact_counts = False; passed = False
        rows.append({
            "runner": row.runner, "benchmark_view": row.benchmark_view,
            "series_id": row.series_id, "motion_category": row.motion_category,
            "condition_instance": row.condition_instance, "method_id": row.method_id,
            "preprocessing_arm": row.preprocessing_arm, "length_ok": length_ok,
            "reported_median_error_px": row.median_error_px,
            "recalculated_median_error_px": recalculated["median"],
            "maximum_metric_delta_px": max(differences), "threshold_counts_exact": exact_counts,
            "status": "pass" if passed else "fail",
        })
    audit = pd.DataFrame(rows)
    output.parent.mkdir(parents=True, exist_ok=True)
    audit.to_csv(output, index=False)
    if audit.empty or (audit.status != "pass").any():
        raise RuntimeError(f"independent transform recalculation failed: {output}")


def make_summaries(results: pd.DataFrame, methods: pd.DataFrame) -> pd.DataFrame:
    expanded = [results]
    universal = results[results.condition_id.str.startswith("U")].copy()
    universal["panel_group"] = "overall_universal"
    expanded.append(universal)
    by_condition = results.copy()
    by_condition["panel_group"] = "condition_" + by_condition.condition_id
    expanded.append(by_condition)
    combined = pd.concat(expanded, ignore_index=True)
    source = combined.groupby(
        ["benchmark_view", "method_id", "preprocessing_arm", "image_series_class",
         "independent_group", "series_id", "panel_group"], as_index=False).agg(
        source_mean_median_error_px=("scored_median_error_px", "mean"),
        source_mean_p90_error_px=("scored_p90_error_px", "mean"),
        source_mean_max_error_px=("scored_max_error_px", "mean"),
        source_mean_terminal_error_px=("scored_terminal_error_px", "mean"),
        source_mean_frames_over_1_rate=("scored_frames_over_1_rate", "mean"),
        source_mean_frames_over_5_rate=("scored_frames_over_5_rate", "mean"),
        source_mean_cpu_seconds=("cpu_seconds", "mean"),
        source_mean_elapsed_seconds=("elapsed_seconds", "mean"),
        source_mean_peak_memory_mb=("peak_memory_mb", "mean"),
        source_failure_rate=("algorithm_success", lambda value: 1.0 - float(np.mean(value))),
        recordings=("algorithm_success", "size"),
    )
    # A future extension may contribute more than one selected raster per
    # acquisition.  Collapse those raster rows before any CI or hypothesis so
    # an acquisition can never receive extra inferential weight.
    acquisition = source.groupby(
        ["benchmark_view", "method_id", "preprocessing_arm", "image_series_class",
         "independent_group", "panel_group"], as_index=False).agg(
        source_mean_median_error_px=("source_mean_median_error_px", "mean"),
        source_mean_p90_error_px=("source_mean_p90_error_px", "mean"),
        source_mean_max_error_px=("source_mean_max_error_px", "mean"),
        source_mean_terminal_error_px=("source_mean_terminal_error_px", "mean"),
        source_mean_frames_over_1_rate=("source_mean_frames_over_1_rate", "mean"),
        source_mean_frames_over_5_rate=("source_mean_frames_over_5_rate", "mean"),
        source_mean_cpu_seconds=("source_mean_cpu_seconds", "mean"),
        source_mean_elapsed_seconds=("source_mean_elapsed_seconds", "mean"),
        source_mean_peak_memory_mb=("source_mean_peak_memory_mb", "mean"),
        source_failure_rate=("source_failure_rate", "mean"),
        recordings=("recordings", "sum"),
        sources=("series_id", "nunique"),
    )
    labels = methods.set_index("method_id")
    rows = []
    for key, group in acquisition.groupby(["benchmark_view", "method_id", "preprocessing_arm", "panel_group"]):
        view, method, arm, panel_group = key
        metric_columns = {
            "balanced_mean_median_error_px": "source_mean_median_error_px",
            "balanced_mean_p90_error_px": "source_mean_p90_error_px",
            "balanced_mean_max_error_px": "source_mean_max_error_px",
            "balanced_mean_terminal_error_px": "source_mean_terminal_error_px",
            "balanced_frames_over_1_rate": "source_mean_frames_over_1_rate",
            "balanced_frames_over_5_rate": "source_mean_frames_over_5_rate",
            "balanced_cpu_seconds": "source_mean_cpu_seconds",
            "balanced_elapsed_seconds": "source_mean_elapsed_seconds",
            "balanced_peak_memory_mb": "source_mean_peak_memory_mb",
            "balanced_failure_rate": "source_failure_rate",
        }
        summary_metrics: dict[str, float] = {}
        for metric_index, (output_name, source_name) in enumerate(metric_columns.items()):
            type_values = group.groupby("image_series_class")[source_name].mean()
            summary_metrics[output_name] = float(type_values.mean())
            ci_required = view == "tuned" and arm == "native" and (
                (source_name == "source_mean_median_error_px"
                 and not str(panel_group).startswith("condition_"))
                or panel_group in {"overall_universal", "dose"})
            if ci_required:
                low, high = balanced_bootstrap(
                    group, source_name, seed_offset=stable_offset(key) + 1009 * metric_index)
            else:
                low, high = math.nan, math.nan
            summary_metrics[f"{output_name}_ci95_low"] = low
            summary_metrics[f"{output_name}_ci95_high"] = high
        row = {
            "benchmark_view": view, "method_id": method,
            "method_label": labels.loc[method, "display_name"],
            "family": labels.loc[method, "family"], "role": labels.loc[method, "role"],
            "preprocessing_arm": arm, "panel_group": panel_group,
            "sources": int(group.sources.sum()),
            "clusters": group.independent_group.nunique(),
            "recordings": int(group.recordings.sum()),
            "independent_family_rank_eligible": method not in ALIASES_OR_PORTS
                and labels.loc[method, "role"] not in {"ablation", "negative_ablation",
                                                        "scheduler_ablation", "trained_system"},
        }
        row.update(summary_metrics)
        # Backward-compatible names consumed by figure and table code.
        row["bootstrap_ci95_low_px"] = summary_metrics["balanced_mean_median_error_px_ci95_low"]
        row["bootstrap_ci95_high_px"] = summary_metrics["balanced_mean_median_error_px_ci95_high"]
        rows.append(row)
    return pd.DataFrame(rows)


def degradation_by_source(results: pd.DataFrame, method: str, arm: str,
                          stressed: set[str]) -> pd.DataFrame:
    chosen = results[(results.benchmark_view == "tuned") & (results.method_id == method)
                     & (results.preprocessing_arm == arm)]
    cell = chosen.groupby(["independent_group", "series_id", "image_series_class",
                           "motion_category", "condition_id"],
                          as_index=False).scored_median_error_px.mean()
    clean = cell[cell.condition_id == "U00"][["independent_group", "series_id", "motion_category",
                                                "scored_median_error_px"]].rename(
        columns={"scored_median_error_px": "clean"})
    stress = cell[cell.condition_id.isin(stressed)].merge(
        clean, on=["independent_group", "series_id", "motion_category"])
    stress["degradation"] = np.log((stress.scored_median_error_px + 1e-6) / (stress.clean + 1e-6))
    source = stress.groupby(
        ["independent_group", "series_id", "image_series_class"], as_index=False).degradation.mean()
    return source.groupby(
        ["independent_group", "image_series_class"], as_index=False).degradation.mean()


def paired_metric(results: pd.DataFrame, first: str, second: str, arm: str,
                  metric: str, condition_filter: pd.Series) -> pd.DataFrame:
    chosen = results[(results.benchmark_view == "tuned")
                     & (results.preprocessing_arm == arm) & condition_filter
                     & results.method_id.isin([first, second])]
    source = chosen.groupby(["independent_group", "series_id", "image_series_class", "method_id"],
                            as_index=False)[metric].mean()
    pivot = source.pivot(index=["independent_group", "series_id", "image_series_class"],
                         columns="method_id", values=metric).reset_index()
    pivot["difference"] = pivot[first] - pivot[second]
    return pivot.groupby(["independent_group", "image_series_class"], as_index=False).agg(
        **{first: (first, "mean"), second: (second, "mean"), "difference": ("difference", "mean")})


def claim_gates(results: pd.DataFrame, project: Path
                ) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    comparator = pd.read_csv(project / "library/benchmark/v3/protocol/frozen_config/primary_comparator_selection.csv")
    selector_baseline = pd.read_csv(project / "library/benchmark/v3/protocol/frozen_config/selector_baseline_selection.csv")
    gates = []
    condition_rows = []
    seed_offset = 1000
    for arm in ("native", "common_normalized"):
        external = comparator.loc[comparator.preprocessing_arm == arm, "method_id"].iloc[0]
        for hypothesis, stressed in (("H1", {"U01", "U02", "U03", "U04", "U05"}),
                                     ("H2", {"U06", "U07", "C025", "C200"})):
            ours = degradation_by_source(results, "01_log_ratio_tukey_standard_gradient", arm, stressed)
            theirs = degradation_by_source(results, external, arm, stressed)
            paired = ours.merge(theirs, on=["independent_group", "image_series_class"],
                                suffixes=("_ours", "_external"))
            paired["difference"] = paired.degradation_ours - paired.degradation_external
            estimate, low, high, raw_p = paired_bootstrap(
                paired, "difference", seed_offset=seed_offset)
            seed_offset += 1
            gates.append({"hypothesis": hypothesis, "preprocessing_arm": arm,
                          "ours_method": "01_log_ratio_tukey_standard_gradient",
                          "comparator_method": external, "effect": "ours_minus_comparator_log_degradation",
                          "estimate": estimate, "ci95_low": low, "ci95_high": high,
                          "margin": 0.0, "raw_p": raw_p,
                          "pass": high < 0, "clusters": len(paired)})

        clean_filter = results.condition_id == "U00"
        paired = paired_metric(results, "01_log_ratio_tukey_standard_gradient", external, arm,
                               "scored_median_error_px", clean_filter)
        comparator_balanced = float(np.mean([
            paired.loc[paired.image_series_class == image_type, external].mean()
            for image_type in paired.image_series_class.unique()]))
        margin = max(0.02, 0.25 * comparator_balanced)
        # Recompute the one-sided non-inferiority p-value against the frozen
        # margin.  The first draw above was needed only before margin creation.
        estimate, low, upper, raw_p = paired_bootstrap(
            paired, "difference", upper_quantile=0.95, seed_offset=seed_offset,
            null_threshold=margin)
        seed_offset += 1
        gates.append({"hypothesis": "H4", "preprocessing_arm": arm,
                      "ours_method": "01_log_ratio_tukey_standard_gradient",
                      "comparator_method": external, "effect": "ours_minus_comparator_clean_error",
                      "estimate": estimate, "ci95_low": low, "ci95_high": upper,
                      "margin": margin, "raw_p": raw_p,
                      "pass": upper <= margin, "clusters": len(paired)})

        baseline = selector_baseline.loc[selector_baseline.preprocessing_arm == arm, "method_id"].iloc[0]
        universal_filter = results.condition_id.str.startswith("U")
        selector = paired_metric(results, "20_automatic_information_selector", baseline, arm,
                                 "scored_median_error_px", universal_filter)
        estimate, low, high, selector_error_p = paired_bootstrap(
            selector, "difference", seed_offset=seed_offset)
        seed_offset += 1
        selector_fail = paired_metric(results.assign(failure=(~results.algorithm_success).astype(float)),
                                      "20_automatic_information_selector", baseline, arm,
                                      "failure", universal_filter)
        failure_difference = float(selector_fail.difference.mean())
        _, _, failure_high, selector_failure_p = paired_bootstrap(
            selector_fail, "difference", seed_offset=seed_offset)
        seed_offset += 1
        selector_clean = paired_metric(results, "20_automatic_information_selector", external, arm,
                                       "scored_median_error_px", clean_filter)
        _, _, selector_clean_upper, selector_clean_p = paired_bootstrap(
            selector_clean, "difference", upper_quantile=0.95,
            seed_offset=seed_offset, null_threshold=margin)
        seed_offset += 1
        # Either lower error or lower failure may establish improvement, with a
        # Bonferroni correction across those two routes.  The clean safeguard is
        # conjunctive, hence the larger p-value is the H5 arm-level p-value.
        improvement_p = min(1.0, 2.0 * min(selector_error_p, selector_failure_p))
        raw_p = max(improvement_p, selector_clean_p)
        gates.append({"hypothesis": "H5", "preprocessing_arm": arm,
                      "ours_method": "20_automatic_information_selector",
                      "comparator_method": baseline,
                      "effect": "selector_minus_fixed_overall_error",
                      "estimate": estimate, "ci95_low": low, "ci95_high": high,
                      "margin": margin, "raw_p": raw_p,
                      "pass": (high < 0 or failure_high < 0) and selector_clean_upper <= margin,
                      "clusters": len(selector), "failure_rate_difference": failure_difference,
                      "failure_ci95_high": failure_high,
                      "clean_upper_vs_external": selector_clean_upper,
                      "selector_error_raw_p": selector_error_p,
                      "selector_failure_raw_p": selector_failure_p,
                      "clean_safeguard_raw_p": selector_clean_p})

        # Exact condition degradation data behind the robustness figure.
        for condition in ("U01", "U02", "U03", "U04", "U05", "U06", "U07", "U08", "U09", "U10", "U11"):
            for method, label in (("01_log_ratio_tukey_standard_gradient", "Log-ratio/Tukey"),
                                  (external, "Frozen external comparator")):
                values = degradation_by_source(results, method, arm, {condition})
                low, high = balanced_bootstrap(values, "degradation", seed_offset=seed_offset)
                seed_offset += 1
                means = values.groupby("image_series_class").degradation.mean()
                condition_rows.append({"preprocessing_arm": arm, "condition_id": condition,
                                       "method_id": method, "label": label,
                                       "balanced_mean_log_degradation": float(means.mean()),
                                       "ci95_low": low, "ci95_high": high})

    scheduler = {
        "log_ratio": ("01_log_ratio_tukey_standard_gradient", "A01_logratio_chain"),
        "area_newton": ("48_area_correlation_newton", "A03_area_newton_chain"),
        "phase": ("15_phase_correlation", "A05_phase_chain"),
        "turboreg": ("22_turboreg_multilag", "21_stackreg_turboreg_chain"),
        "sift": ("30_sift_multilag", "29_sift_chain"),
    }
    favourable = 0
    scheduler_rows = []
    scheduler_raw_p = []
    dose_filter = ~results.condition_id.str.startswith("U")
    for family, (multi, chain) in scheduler.items():
        paired = paired_metric(results, multi, chain, "native", "scored_p90_error_px", dose_filter)
        estimate, low, high, raw_p = paired_bootstrap(
            paired, "difference", seed_offset=seed_offset)
        seed_offset += 1
        chosen = results[(results.benchmark_view == "tuned") & (results.preprocessing_arm == "native")
                         & dose_filter & results.method_id.isin([multi, chain])]
        failures = chosen.groupby("method_id").algorithm_success.apply(lambda x: 1 - x.mean())
        no_higher_failure = float(failures.get(multi, 1)) <= float(failures.get(chain, 1))
        secondary = {}
        for label, metric in (("max", "scored_max_error_px"),
                              ("terminal", "scored_terminal_error_px"),
                              ("frames_over_1", "scored_frames_over_1_rate")):
            direction = paired_metric(results, multi, chain, "native", metric, dose_filter)
            secondary[label] = float(direction.groupby("image_series_class").difference.mean().mean())
        secondary_favourable = all(value <= 0 for value in secondary.values())
        passed = high < 0 and no_higher_failure and secondary_favourable
        family_raw_p = raw_p if no_higher_failure and secondary_favourable else 1.0
        scheduler_raw_p.append(family_raw_p)
        favourable += int(passed)
        scheduler_rows.append({"family": family, "multilag_method": multi, "chain_method": chain,
                               "estimate_p90_difference_px": estimate, "ci95_low": low,
                               "ci95_high": high,
                               "multilag_failure_rate": failures.get(multi, math.nan),
                               "chain_failure_rate": failures.get(chain, math.nan),
                               "max_error_difference_px": secondary["max"],
                               "terminal_error_difference_px": secondary["terminal"],
                               "frames_over_1_rate_difference": secondary["frames_over_1"],
                               "raw_p": family_raw_p, "pass": passed})
    # At least three of five families must be favourable.  A Bonferroni partial-
    # conjunction test uses (m-r+1) * p_(r): here, 3 times the third-smallest
    # valid family p-value.
    h3_raw_p = min(1.0, 3.0 * sorted(scheduler_raw_p)[2])
    gates.append({"hypothesis": "H3", "preprocessing_arm": "native",
                  "ours_method": "multi_lag_schedulers", "comparator_method": "previous_frame_chains",
                  "effect": "families_with_favourable_p90_ci_and_no_higher_failure",
                  "estimate": favourable, "ci95_low": math.nan, "ci95_high": math.nan,
                  "margin": 3, "raw_p": h3_raw_p,
                  "pass": favourable >= 3,
                  "clusters": results.independent_group.nunique()})
    return apply_holm(pd.DataFrame(gates)), pd.DataFrame(condition_rows), pd.DataFrame(scheduler_rows)


def figures(summaries: pd.DataFrame, condition: pd.DataFrame, scheduler: pd.DataFrame,
            results: Path) -> None:
    figure_dir = results / "figures"; figure_dir.mkdir(parents=True, exist_ok=True)
    table_dir = results / "summaries"
    tuned = summaries[(summaries.benchmark_view == "tuned")
                      & (summaries.preprocessing_arm == "native")
                      & (summaries.panel_group == "overall_universal")].sort_values(
                          "balanced_mean_median_error_px")
    plot = tuned.head(25).sort_values("balanced_mean_median_error_px", ascending=False)
    fig, ax = plt.subplots(figsize=(8.2, 8.0))
    x = plot.balanced_mean_median_error_px.to_numpy()
    ax.errorbar(x, np.arange(len(plot)),
                xerr=np.vstack((x - plot.bootstrap_ci95_low_px,
                                plot.bootstrap_ci95_high_px - x)), fmt="o", color="#235789")
    ax.set_yticks(np.arange(len(plot)), plot.method_label)
    ax.set_xscale("log"); ax.set_xlabel("Acquisition-balanced median displacement error (px, log scale)")
    ax.set_title("Tuned methods, native input, universal panel")
    ax.grid(axis="x", alpha=.25); fig.tight_layout()
    for extension in ("svg", "png", "pdf"):
        fig.savefig(figure_dir / f"figureS1_overall_method_ranking.{extension}", dpi=220)
    plt.close(fig)

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), sharey=True)
    order = [f"U{i:02d}" for i in range(1, 12)]
    for ax, arm in zip(axes, ("native", "common_normalized")):
        subset = condition[condition.preprocessing_arm == arm]
        for label, group in subset.groupby("label"):
            group = group.set_index("condition_id").loc[order]
            ax.plot(order, group.balanced_mean_log_degradation, marker="o", label=label)
            ax.fill_between(order, group.ci95_low, group.ci95_high, alpha=.16)
        ax.axhline(0, color="black", lw=.8); ax.set_title(arm.replace("_", " ").title())
        ax.tick_params(axis="x", rotation=55); ax.grid(axis="y", alpha=.25)
    axes[0].set_ylabel("log(condition error / clean error)")
    axes[1].legend(frameon=False, fontsize=8); fig.tight_layout()
    for extension in ("svg", "png", "pdf"):
        fig.savefig(figure_dir / f"figureS2_primary_pair_robustness.{extension}", dpi=220)
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(7, 4.5))
    colors = ["#1b998b" if passed else "#d1495b" for passed in scheduler["pass"]]
    ax.bar(scheduler.family, scheduler.estimate_p90_difference_px, color=colors)
    ax.errorbar(np.arange(len(scheduler)), scheduler.estimate_p90_difference_px,
                yerr=np.vstack((scheduler.estimate_p90_difference_px - scheduler.ci95_low,
                                scheduler.ci95_high - scheduler.estimate_p90_difference_px)),
                fmt="none", ecolor="black", capsize=3)
    ax.axhline(0, color="black", lw=.8); ax.set_ylabel("Multi-lag minus chain p90 error (px)")
    ax.tick_params(axis="x", rotation=30); ax.grid(axis="y", alpha=.25); fig.tight_layout()
    for extension in ("svg", "png", "pdf"):
        fig.savefig(figure_dir / f"figureS3_scheduler_crossing.{extension}", dpi=220)
    plt.close(fig)

    # Copy exact plotted tables beside figures.
    tuned.to_csv(table_dir / "figure1_data.csv", index=False)
    condition.to_csv(table_dir / "figure2_data.csv", index=False)
    scheduler.to_csv(table_dir / "figure3_data.csv", index=False)


def family_representatives(methods: pd.DataFrame) -> pd.DataFrame:
    ineligible_roles = {"ablation", "negative_ablation", "scheduler_ablation",
                        "trained_system", "alias", "port_parity", "canonical_unavailable",
                        "supplementary_smoke"}
    order = {method_id: index for index, method_id in enumerate(methods.method_id)}
    eligible = methods[(methods.status == "available") & ~methods.role.isin(ineligible_roles)
                       & ~methods.method_id.isin(ALIASES_OR_PORTS)].copy()
    eligible["manifest_order"] = eligible.method_id.map(order)
    return eligible.sort_values("manifest_order").groupby("family", as_index=False).first()


def publication_figures(summaries: pd.DataFrame, results: pd.DataFrame, methods: pd.DataFrame,
                        metadata: dict[tuple[str, str, str], dict[str, object]],
                        output: Path) -> None:
    figure_dir = output / "figures"
    table_dir = output / "summaries"
    figure_dir.mkdir(parents=True, exist_ok=True)
    table_dir.mkdir(parents=True, exist_ok=True)
    representatives = family_representatives(methods)
    representative_ids = set(representatives.method_id)
    labels = representatives.set_index("method_id").display_name.to_dict()

    axes_spec = [
        ("Gain endpoint", [("U03", "4x"), ("U00", "1x"), ("G075", "0.75x"),
                           ("U01", "0.5x"), ("U02", "0.25x")]),
        ("Moved content", [("U00", "0%"), ("C025", "2.5%"), ("U06", "5%"),
                           ("U07", "10%"), ("C200", "20%")]),
        ("Additive SNR", [("NINF", "none"), ("N025", "25 dB"), ("U00", "20 dB"),
                          ("U09", "15 dB"), ("N010", "10 dB"), ("U10", "5 dB"),
                          ("N000", "0 dB")]),
    ]
    curve_rows = []
    seed_offset = 70000
    for method_id in representative_ids:
        for axis_label, conditions in axes_spec:
            for position, (condition, dose_label) in enumerate(conditions):
                if condition == "U00":
                    values = degradation_by_source(results, method_id, "native", {"U00"})
                    # U00/U00 is exactly zero; constructing it explicitly avoids
                    # numerical epsilon noise in the denominator path.
                    values["degradation"] = 0.0
                else:
                    values = degradation_by_source(results, method_id, "native", {condition})
                low, high = balanced_bootstrap(values, "degradation", seed_offset=seed_offset)
                seed_offset += 1
                estimate = float(values.groupby("image_series_class").degradation.mean().mean())
                curve_rows.append({
                    "axis": axis_label, "position": position, "condition_id": condition,
                    "dose_label": dose_label, "method_id": method_id,
                    "method_label": labels[method_id], "balanced_mean_log_degradation": estimate,
                    "ci95_low": low, "ci95_high": high,
                })
    curves = pd.DataFrame(curve_rows)
    curves.to_csv(table_dir / "figure1_robustness_curves_data.csv", index=False)
    fig, axes = plt.subplots(1, 3, figsize=(15.5, 5.4), sharey=True)
    palette = plt.get_cmap("tab20")
    ids = sorted(representative_ids)
    for axis, (axis_label, conditions) in zip(axes, axes_spec):
        subset = curves[curves.axis == axis_label]
        for method_index, method_id in enumerate(ids):
            group = subset[subset.method_id == method_id].sort_values("position")
            emphasized = method_id == "01_log_ratio_tukey_standard_gradient"
            axis.plot(group.position, group.balanced_mean_log_degradation,
                      marker="o" if emphasized else None, linewidth=2.4 if emphasized else 0.9,
                      alpha=1.0 if emphasized else 0.58, color="#d1495b" if emphasized
                      else palette(method_index % 20), label=labels[method_id])
        axis.axhline(0, color="black", linewidth=.8)
        axis.set_xticks(range(len(conditions)), [label for _, label in conditions], rotation=35)
        axis.set_title(axis_label); axis.grid(axis="y", alpha=.22)
    axes[0].set_ylabel("log(condition error / clean error)")
    handles, legend_labels = axes[-1].get_legend_handles_labels()
    fig.legend(handles, legend_labels, loc="center left", bbox_to_anchor=(1.0, .5),
               frameon=False, fontsize=7)
    fig.tight_layout(rect=(0, 0, .84, 1))
    for extension in ("svg", "png", "pdf"):
        fig.savefig(figure_dir / f"figure1_robustness_curves.{extension}", dpi=220,
                    bbox_inches="tight")
    plt.close(fig)

    scatter_rows = []
    clean = summaries[(summaries.benchmark_view == "tuned")
                      & (summaries.preprocessing_arm == "native")
                      & (summaries.panel_group == "clean")].set_index("method_id")
    for method_id in representative_ids:
        for stress, conditions in (("Gain", {"U01", "U02", "U03", "U04", "U05"}),
                                   ("Local change", {"U06", "U07", "C025", "C200"})):
            values = degradation_by_source(results, method_id, "native", conditions)
            scatter_rows.append({
                "stress": stress, "method_id": method_id, "method_label": labels[method_id],
                "clean_error_px": clean.loc[method_id].balanced_mean_median_error_px,
                "robustness_log_degradation": float(
                    values.groupby("image_series_class").degradation.mean().mean()),
            })
    scatter = pd.DataFrame(scatter_rows)
    scatter.to_csv(table_dir / "figure2_clean_vs_robustness_data.csv", index=False)
    fig, axes = plt.subplots(1, 2, figsize=(12.5, 5.3), sharex=True, sharey=True)
    for axis, stress in zip(axes, ("Gain", "Local change")):
        subset = scatter[scatter.stress == stress]
        axis.scatter(subset.clean_error_px, subset.robustness_log_degradation,
                     color="#235789", alpha=.78)
        for row in subset.itertuples():
            axis.annotate(row.method_id.split("_", 1)[0],
                          (row.clean_error_px, row.robustness_log_degradation), fontsize=7,
                          xytext=(3, 2), textcoords="offset points")
        axis.set_xscale("log"); axis.axhline(0, color="black", linewidth=.8)
        axis.set_title(stress); axis.set_xlabel("Clean median error (px, log scale)")
        axis.grid(alpha=.22)
    axes[0].set_ylabel("Mean log(condition error / clean error)")
    fig.tight_layout()
    for extension in ("svg", "png", "pdf"):
        fig.savefig(figure_dir / f"figure2_clean_accuracy_vs_robustness.{extension}", dpi=220)
    plt.close(fig)

    schedules = {
        "Previous-frame chain": "A01_logratio_chain",
        "First-frame reference": "A02_logratio_first",
        "Redundant multi-lag": "01_log_ratio_tukey_standard_gradient",
    }
    motion_labels = {
        "STEADY_DIRECTIONAL_DRIFT": "Smooth directional drift",
        "INTERMITTENT_JUMPS": "Intermittent jumps",
    }
    chosen = results[(results.benchmark_view == "tuned")
                     & (results.preprocessing_arm == "native")
                     & (results.condition_id == "U00")
                     & results.method_id.isin(schedules.values())
                     & results.motion_category.isin(motion_labels)]
    frame_source_rows = []
    for row in chosen.itertuples():
        record = metadata[(row.series_id, row.motion_category, row.condition_instance)]
        frames = int(record["frames"])
        if row.algorithm_success:
            x = np.asarray([float(value) for value in row.transform_x_px.split(";")])
            y = np.asarray([float(value) for value in row.transform_y_px.split(";")])
            if len(x) != frames or len(y) != frames:
                raise RuntimeError(f"transform length mismatch for {row.method_id}/{row.series_id}")
            theta = (np.asarray([float(value) for value in row.transform_theta_rad.split(";")])
                     if row.transform_theta_rad else np.zeros_like(x))
            coordinate_variance = (float(record["width"]) ** 2 - 1.0) / 12.0
            error = np.sqrt(np.maximum(
                0.0, (x - np.asarray(record["truth_x"])) ** 2
                + (y - np.asarray(record["truth_y"])) ** 2
                + 4.0 * coordinate_variance * (1.0 - np.cos(theta))))
        else:
            error = np.full(frames, float(record["penalty"]))
        for frame, value in enumerate(error):
            frame_source_rows.append({
                "method_id": row.method_id, "motion_category": row.motion_category,
                "image_series_class": row.image_series_class,
                "independent_group": row.independent_group, "series_id": row.series_id,
                "frame": frame, "error_px": float(value),
            })
    frame_source = pd.DataFrame(frame_source_rows).groupby(
        ["method_id", "motion_category", "image_series_class", "independent_group",
         "series_id", "frame"],
        as_index=False).error_px.mean()
    frame_cluster = frame_source.groupby(
        ["method_id", "motion_category", "image_series_class", "independent_group", "frame"],
        as_index=False).error_px.mean()
    frame_rows = []
    for key, group in frame_cluster.groupby(["method_id", "motion_category", "frame"]):
        method_id, motion, frame = key
        low, high = balanced_bootstrap(group, "error_px", seed_offset=seed_offset)
        seed_offset += 1
        frame_rows.append({
            "method_id": method_id,
            "strategy": next(label for label, method in schedules.items() if method == method_id),
            "motion_category": motion, "frame": frame,
            "balanced_mean_error_px": float(
                group.groupby("image_series_class").error_px.mean().mean()),
            "ci95_low": low, "ci95_high": high,
        })
    frame_data = pd.DataFrame(frame_rows)
    frame_data.to_csv(table_dir / "figure3_error_accumulation_data.csv", index=False)
    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.8), sharey=True)
    colors = {"Previous-frame chain": "#d1495b", "First-frame reference": "#edae49",
              "Redundant multi-lag": "#00798c"}
    for axis, (motion, title) in zip(axes, motion_labels.items()):
        subset = frame_data[frame_data.motion_category == motion]
        for strategy, group in subset.groupby("strategy"):
            group = group.sort_values("frame")
            axis.plot(group.frame, group.balanced_mean_error_px, label=strategy,
                      color=colors[strategy])
            axis.fill_between(group.frame, group.ci95_low, group.ci95_high,
                              color=colors[strategy], alpha=.14)
        axis.set_title(title); axis.set_xlabel("Frame distance from reference")
        axis.grid(alpha=.22)
    axes[0].set_ylabel("Acquisition-balanced displacement error (px)")
    axes[1].legend(frameon=False); fig.tight_layout()
    for extension in ("svg", "png", "pdf"):
        fig.savefig(figure_dir / f"figure3_error_accumulation.{extension}", dpi=220)
    plt.close(fig)


def write_benchmark_tables(summaries: pd.DataFrame, methods: pd.DataFrame, output: Path) -> None:
    panels = ["overall_universal", "clean", "gain", "local_change", "adversarial",
              "additive_noise", "combined", "dose"]
    index = ["benchmark_view", "method_id", "method_label", "family", "role",
             "preprocessing_arm", "independent_family_rank_eligible"]
    wide_median = summaries.pivot_table(index=index, columns="panel_group",
                                        values="balanced_mean_median_error_px",
                                        aggfunc="first").reset_index()
    overall_fields = [
        "balanced_mean_median_error_px", "balanced_mean_median_error_px_ci95_low",
        "balanced_mean_median_error_px_ci95_high",
        "balanced_failure_rate", "balanced_failure_rate_ci95_low",
        "balanced_failure_rate_ci95_high", "balanced_mean_p90_error_px",
        "balanced_mean_p90_error_px_ci95_low", "balanced_mean_p90_error_px_ci95_high",
        "balanced_mean_max_error_px", "balanced_mean_max_error_px_ci95_low",
        "balanced_mean_max_error_px_ci95_high", "balanced_mean_terminal_error_px",
        "balanced_mean_terminal_error_px_ci95_low", "balanced_mean_terminal_error_px_ci95_high",
        "balanced_frames_over_1_rate", "balanced_frames_over_1_rate_ci95_low",
        "balanced_frames_over_1_rate_ci95_high", "balanced_frames_over_5_rate",
        "balanced_frames_over_5_rate_ci95_low", "balanced_frames_over_5_rate_ci95_high",
        "balanced_cpu_seconds", "balanced_cpu_seconds_ci95_low", "balanced_cpu_seconds_ci95_high",
        "balanced_elapsed_seconds", "balanced_elapsed_seconds_ci95_low",
        "balanced_elapsed_seconds_ci95_high", "balanced_peak_memory_mb",
        "balanced_peak_memory_mb_ci95_low", "balanced_peak_memory_mb_ci95_high",
        "sources", "clusters", "recordings",
    ]
    overall = summaries[summaries.panel_group == "overall_universal"][
        ["benchmark_view", "method_id", "preprocessing_arm"] + overall_fields]
    metric_basis = summaries[summaries.panel_group.isin(["overall_universal", "dose"])].copy()
    metric_basis["basis_order"] = metric_basis.panel_group.map(
        {"overall_universal": 0, "dose": 1})
    metric_basis = metric_basis.sort_values("basis_order").drop_duplicates(
        ["benchmark_view", "method_id", "preprocessing_arm"])
    metric_basis = metric_basis[
        ["benchmark_view", "method_id", "preprocessing_arm", "panel_group"] + overall_fields]
    metric_basis = metric_basis.rename(columns={"panel_group": "metric_panel"})
    pivot = wide_median.merge(
        metric_basis, on=["benchmark_view", "method_id", "preprocessing_arm"], how="left")
    for panel in panels:
        if panel not in pivot: pivot[panel] = math.nan
    pivot = pivot[index + ["metric_panel"] + overall_fields + panels]
    pivot.to_csv(output / "benchmark_table_all.csv", index=False)
    for view in ("defaults", "tuned"):
        table = pivot[pivot.benchmark_view == view].sort_values(
            ["preprocessing_arm", "overall_universal"])
        table.to_csv(output / f"benchmark_table_{view}.csv", index=False)
        lines = [f"# Registration Benchmark v3: {view}", "",
                 "| Method | Arm | Overall px | Clean px | Gain px | Change px | Noise px | Failure |",
                 "|---|---|---:|---:|---:|---:|---:|---:|"]
        for row in table.itertuples():
            lines.append(f"| {row.method_label} | {row.preprocessing_arm} | "
                         f"{row.overall_universal:.4f} | {row.clean:.4f} | {row.gain:.4f} | "
                         f"{row.local_change:.4f} | {row.additive_noise:.4f} | "
                         f"{row.balanced_failure_rate:.3f} |")
        (output / f"benchmark_table_{view}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # One row per method, with all four fairness views side by side.  This makes
    # the cost of tuning and of common normalization explicit rather than
    # allowing either to be switched opportunistically after seeing test data.
    fairness_basis = summaries[summaries.panel_group.isin(["overall_universal", "dose"])].copy()
    fairness_basis["basis_order"] = fairness_basis.panel_group.map(
        {"overall_universal": 0, "dose": 1})
    fairness_basis = fairness_basis.sort_values("basis_order").drop_duplicates(
        ["benchmark_view", "method_id", "preprocessing_arm"])
    fairness_rows = []
    for method_id, group in fairness_basis.groupby("method_id"):
        manifest = methods.loc[methods.method_id == method_id].iloc[0]
        row: dict[str, object] = {
            "method_id": method_id, "method_label": manifest.display_name,
            "family": manifest.family, "role": manifest.role,
            "comparison_panel": group.iloc[0].panel_group,
        }
        for view in ("defaults", "tuned"):
            for arm in ("native", "common_normalized"):
                cell = group[(group.benchmark_view == view)
                             & (group.preprocessing_arm == arm)]
                prefix = f"{view}_{arm}"
                if len(cell):
                    one = cell.iloc[0]
                    row[f"{prefix}_median_error_px"] = one.balanced_mean_median_error_px
                    row[f"{prefix}_p90_error_px"] = one.balanced_mean_p90_error_px
                    row[f"{prefix}_failure_rate"] = one.balanced_failure_rate
                else:
                    row[f"{prefix}_median_error_px"] = math.nan
                    row[f"{prefix}_p90_error_px"] = math.nan
                    row[f"{prefix}_failure_rate"] = math.nan
        fairness_rows.append(row)
    fairness = pd.DataFrame(fairness_rows).sort_values("method_id")
    fairness.to_csv(output / "fairness_four_arm_table.csv", index=False)
    fairness_lines = ["# Default, tuned and preprocessing fairness matrix", "",
                      "| Method | Default native | Default normalized | Tuned native | Tuned normalized |",
                      "|---|---:|---:|---:|---:|"]
    for row in fairness.itertuples():
        fairness_lines.append(
            f"| {row.method_label} | {row.defaults_native_median_error_px:.4f} | "
            f"{row.defaults_common_normalized_median_error_px:.4f} | "
            f"{row.tuned_native_median_error_px:.4f} | "
            f"{row.tuned_common_normalized_median_error_px:.4f} |")
    (output / "fairness_four_arm_table.md").write_text(
        "\n".join(fairness_lines) + "\n", encoding="utf-8")

    # Prospectively select the first manifest-declared eligible method in each
    # independent family; this cannot exploit publication-test performance.
    manifest_order = {method: index for index, method in enumerate(methods.method_id)}
    eligible = overall[(overall.benchmark_view == "tuned")
                       & (overall.preprocessing_arm == "native")].merge(
        methods[["method_id", "display_name", "family", "role"]], on="method_id")
    eligible = eligible[eligible.method_id.map(
        summaries.drop_duplicates("method_id").set_index("method_id")
        .independent_family_rank_eligible).fillna(False)]
    eligible["manifest_order"] = eligible.method_id.map(manifest_order)
    representatives = eligible.sort_values("manifest_order").groupby("family", as_index=False).first()
    summary_lookup = summaries.set_index(
        ["benchmark_view", "method_id", "preprocessing_arm", "panel_group"])
    primary_rows = []
    for representative in representatives.itertuples():
        broad = pivot[(pivot.benchmark_view == "tuned")
                      & (pivot.preprocessing_arm == "native")
                      & (pivot.method_id == representative.method_id)].iloc[0]
        specific = wide_median[(wide_median.benchmark_view == "tuned")
                               & (wide_median.preprocessing_arm == "native")
                               & (wide_median.method_id == representative.method_id)].iloc[0]
        panel_ci = {
            panel: summary_lookup.loc[("tuned", representative.method_id, "native", panel)]
            for panel in ("clean", "gain", "local_change")
        }
        primary_rows.append({
            "family": representative.family, "method_id": representative.method_id,
            "method_label": representative.display_name,
            "selection_rule": "first eligible method in frozen manifest order",
            "sources": int(representative.sources),
            "acquisition_clusters": int(representative.clusters),
            "overall_median_error_px": broad.overall_universal,
            "overall_ci95_low_px": representative.balanced_mean_median_error_px_ci95_low,
            "overall_ci95_high_px": representative.balanced_mean_median_error_px_ci95_high,
            "clean_median_error_px": broad.clean, "gain_median_error_px": broad.gain,
            "local_change_median_error_px": broad.local_change,
            "clean_ci95_low_px": panel_ci["clean"].balanced_mean_median_error_px_ci95_low,
            "clean_ci95_high_px": panel_ci["clean"].balanced_mean_median_error_px_ci95_high,
            "gain_ci95_low_px": panel_ci["gain"].balanced_mean_median_error_px_ci95_low,
            "gain_ci95_high_px": panel_ci["gain"].balanced_mean_median_error_px_ci95_high,
            "local_change_ci95_low_px": panel_ci["local_change"].balanced_mean_median_error_px_ci95_low,
            "local_change_ci95_high_px": panel_ci["local_change"].balanced_mean_median_error_px_ci95_high,
            "u08_adversarial_median_error_px": specific.get("condition_U08", math.nan),
            "u10_low_snr_median_error_px": specific.get("condition_U10", math.nan),
            "u11_combined_median_error_px": specific.get("condition_U11", math.nan),
            "overall_p90_error_px": representative.balanced_mean_p90_error_px,
            "overall_p90_ci95_low_px": representative.balanced_mean_p90_error_px_ci95_low,
            "overall_p90_ci95_high_px": representative.balanced_mean_p90_error_px_ci95_high,
            "frames_over_1_rate": representative.balanced_frames_over_1_rate,
            "frames_over_1_ci95_low": representative.balanced_frames_over_1_rate_ci95_low,
            "frames_over_1_ci95_high": representative.balanced_frames_over_1_rate_ci95_high,
            "failure_rate": representative.balanced_failure_rate,
            "failure_ci95_low": representative.balanced_failure_rate_ci95_low,
            "failure_ci95_high": representative.balanced_failure_rate_ci95_high,
            "elapsed_seconds": representative.balanced_elapsed_seconds,
            "elapsed_ci95_low": representative.balanced_elapsed_seconds_ci95_low,
            "elapsed_ci95_high": representative.balanced_elapsed_seconds_ci95_high,
            "peak_memory_mb": representative.balanced_peak_memory_mb,
            "peak_memory_ci95_low": representative.balanced_peak_memory_mb_ci95_low,
            "peak_memory_ci95_high": representative.balanced_peak_memory_mb_ci95_high,
        })
    primary = pd.DataFrame(primary_rows).sort_values("overall_median_error_px")
    primary.to_csv(output / "primary_independent_family_table.csv", index=False)
    primary_lines = ["# Primary independent-family benchmark", "",
                     "All values are acquisition-balanced; geometric errors are in pixels.", "",
                     "| Family representative | n | Overall (95% CI) | Clean | Gain | Local change | U08 adversarial | U10 severe noise | U11 combined | p90 | >1 px | Failures | Time (s) | Peak MB |",
                     "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for row in primary.itertuples():
        primary_lines.append(
            f"| {row.method_label} | {row.acquisition_clusters} | "
            f"{row.overall_median_error_px:.4f} "
            f"[{row.overall_ci95_low_px:.4f}, {row.overall_ci95_high_px:.4f}] | "
            f"{row.clean_median_error_px:.4f} | "
            f"{row.gain_median_error_px:.4f} | {row.local_change_median_error_px:.4f} | "
            f"{row.u08_adversarial_median_error_px:.4f} | {row.u10_low_snr_median_error_px:.4f} | "
            f"{row.u11_combined_median_error_px:.4f} | {row.overall_p90_error_px:.4f} | "
            f"{row.frames_over_1_rate:.3f} | {row.failure_rate:.3f} | "
            f"{row.elapsed_seconds:.3f} | {row.peak_memory_mb:.1f} |")
    (output / "primary_independent_family_table.md").write_text(
        "\n".join(primary_lines) + "\n", encoding="utf-8")

    scheduler_map = {
        "LOG_RATIO": {"chain": "A01_logratio_chain", "first": "A02_logratio_first",
                      "multilag": "01_log_ratio_tukey_standard_gradient"},
        "AREA_CORRELATION": {"chain": "A03_area_newton_chain", "first": "A04_area_newton_first",
                             "multilag": "48_area_correlation_newton"},
        "PHASE_CORRELATION": {"chain": "A05_phase_chain", "first": "A06_phase_first",
                              "multilag": "15_phase_correlation"},
        "TURBOREG": {"chain": "21_stackreg_turboreg_chain", "multilag": "22_turboreg_multilag"},
        "SIFT": {"chain": "29_sift_chain", "multilag": "30_sift_multilag"},
    }
    scheduler_table = []
    tuned_native = summaries[(summaries.benchmark_view == "tuned")
                             & (summaries.preprocessing_arm == "native")
                             & (summaries.panel_group == "dose")].set_index("method_id")
    for family, strategies in scheduler_map.items():
        for strategy in ("chain", "first", "multilag"):
            method_id = strategies.get(strategy)
            if method_id is None:
                scheduler_table.append({"family": family, "strategy": strategy,
                                        "method_id": "not_available", "median_error_px": math.nan,
                                        "p90_error_px": math.nan, "failure_rate": math.nan})
                continue
            one = tuned_native.loc[method_id]
            scheduler_table.append({"family": family, "strategy": strategy,
                                    "method_id": method_id,
                                    "median_error_px": one.balanced_mean_median_error_px,
                                    "p90_error_px": one.balanced_mean_p90_error_px,
                                    "failure_rate": one.balanced_failure_rate,
                                    "terminal_error_px": one.balanced_mean_terminal_error_px,
                                    "frames_over_1_rate": one.balanced_frames_over_1_rate})
    scheduler_frame = pd.DataFrame(scheduler_table)
    scheduler_frame.to_csv(output / "scheduler_strategy_table.csv", index=False)
    scheduler_lines = ["# Estimator-by-scheduler crossing", "",
                       "| Family | Strategy | Method | Median px | p90 px | Failure |",
                       "|---|---|---|---:|---:|---:|"]
    for row in scheduler_frame.itertuples():
        scheduler_lines.append(
            f"| {row.family} | {row.strategy} | {row.method_id} | {row.median_error_px:.4f} | "
            f"{row.p90_error_px:.4f} | {row.failure_rate:.3f} |")
    (output / "scheduler_strategy_table.md").write_text(
        "\n".join(scheduler_lines) + "\n", encoding="utf-8")

    legacy_ids = set(methods.loc[methods.legacy_v2_method_id != "", "method_id"])
    legacy_matrix = pivot[pivot.method_id.isin(legacy_ids)].copy()
    legacy_matrix.to_csv(output / "legacy_v2_method_matrix.csv", index=False)
    legacy_default = legacy_matrix[(legacy_matrix.benchmark_view == "defaults")
                                   & (legacy_matrix.preprocessing_arm == "native")]
    if legacy_default.method_id.nunique() != 32 or len(legacy_default) != 32:
        raise RuntimeError(f"legacy table requires exactly 32 rows, found {len(legacy_default)}")
    legacy_default.sort_values("method_id").to_csv(
        output / "legacy_v2_32_default_native.csv", index=False)


def write_supplementary_tables(results: pd.DataFrame, summaries: pd.DataFrame,
                               methods: pd.DataFrame, project: Path, output: Path) -> None:
    dispositions = pd.read_csv(
        project / "library/benchmark/v3/protocol/comparator_dispositions.csv",
        dtype=str).fillna("")
    coverage = methods.merge(dispositions, on="method_id", how="left", validate="one_to_one")
    coverage[["method_id", "display_name", "family", "role", "status", "decision",
              "decision_timepoint", "reason", "evidence"]].to_csv(
                  output / "comparator_inclusion_exclusion.csv", index=False)
    available_count = int((methods.status == "available").sum())
    excluded = coverage[coverage.status != "available"]
    coverage_lines = ["# Comparator coverage", "",
                      f"Runnable method, alias, port and ablation rows: **{available_count}**.",
                      f"Prospectively excluded rows: **{len(excluded)}**.", "",
                      "All runnable rows are retained in the complete benchmark table; aliases and ports are excluded only from independent-family win counts.", "",
                      "| Excluded candidate | Family | Pre-test reason |",
                      "|---|---|---|"]
    for row in excluded.itertuples():
        coverage_lines.append(f"| {row.display_name} | {row.family} | {row.reason} |")
    (output / "COMPARATOR_COVERAGE.md").write_text(
        "\n".join(coverage_lines) + "\n", encoding="utf-8")

    universal = results[results.condition_id.str.startswith("U")].copy()
    source = universal.groupby(
        ["benchmark_view", "method_id", "preprocessing_arm", "image_series_class",
         "independent_group", "series_id"],
        as_index=False).agg(
        source_median_error_px=("scored_median_error_px", "mean"),
        source_p90_error_px=("scored_p90_error_px", "mean"),
        source_frames_over_1_rate=("scored_frames_over_1_rate", "mean"),
        source_failure_rate=("algorithm_success", lambda value: 1.0 - float(np.mean(value))),
        source_elapsed_seconds=("elapsed_seconds", "mean"),
        source_peak_memory_mb=("peak_memory_mb", "mean"),
    )
    acquisition = source.groupby(
        ["benchmark_view", "method_id", "preprocessing_arm", "image_series_class",
         "independent_group"], as_index=False).agg(
        source_median_error_px=("source_median_error_px", "mean"),
        source_p90_error_px=("source_p90_error_px", "mean"),
        source_frames_over_1_rate=("source_frames_over_1_rate", "mean"),
        source_failure_rate=("source_failure_rate", "mean"),
        source_elapsed_seconds=("source_elapsed_seconds", "mean"),
        source_peak_memory_mb=("source_peak_memory_mb", "mean"),
        sources=("series_id", "nunique"),
    )
    per_type = acquisition.groupby(
        ["benchmark_view", "method_id", "preprocessing_arm", "image_series_class"],
        as_index=False).agg(
        sources=("sources", "sum"),
        clusters=("independent_group", "nunique"),
        mean_median_error_px=("source_median_error_px", "mean"),
        mean_p90_error_px=("source_p90_error_px", "mean"),
        frames_over_1_rate=("source_frames_over_1_rate", "mean"),
        failure_rate=("source_failure_rate", "mean"),
        elapsed_seconds=("source_elapsed_seconds", "mean"),
        peak_memory_mb=("source_peak_memory_mb", "mean"),
    ).merge(methods[["method_id", "display_name", "family", "role", "intended_use"]],
            on="method_id", how="left")
    ci_rows = []
    tuned_native_source = acquisition[(acquisition.benchmark_view == "tuned")
                                      & (acquisition.preprocessing_arm == "native")]
    for key, group in tuned_native_source.groupby(["method_id", "image_series_class"]):
        method_id, image_type = key
        ci = {"method_id": method_id, "image_series_class": image_type,
              "benchmark_view": "tuned", "preprocessing_arm": "native"}
        for index, (name, field) in enumerate((
                ("median_error", "source_median_error_px"),
                ("p90_error", "source_p90_error_px"),
                ("failure_rate", "source_failure_rate"))):
            low, high = balanced_bootstrap(
                group, field, seed_offset=90000 + stable_offset(key) + index * 1009)
            ci[f"{name}_ci95_low"] = low; ci[f"{name}_ci95_high"] = high
        ci_rows.append(ci)
    per_type = per_type.merge(pd.DataFrame(ci_rows), on=[
        "method_id", "image_series_class", "benchmark_view", "preprocessing_arm"], how="left")
    per_type.to_csv(output / "supplementary_by_image_type.csv", index=False)
    representative_ids = set(family_representatives(methods).method_id)
    per_type[(per_type.benchmark_view == "tuned")
             & (per_type.preprocessing_arm == "native")
             & per_type.method_id.isin(representative_ids)].to_csv(
                 output / "primary_independent_family_by_image_type.csv", index=False)

    intended_ids = set(methods.loc[
        (methods.status == "available") & (methods.intended_use != "all"), "method_id"])
    intended = per_type[(per_type.benchmark_view == "tuned")
                        & per_type.method_id.isin(intended_ids)
                        & per_type.image_series_class.isin(["DENSE_FLUOR", "SPARSE_LOWLIGHT"])]
    all_type = summaries[(summaries.benchmark_view == "tuned")
                         & (summaries.panel_group == "overall_universal")
                         & summaries.method_id.isin(intended_ids)][
        ["method_id", "preprocessing_arm", "balanced_mean_median_error_px",
         "balanced_mean_p90_error_px", "balanced_failure_rate"]]
    intended = intended.merge(all_type, on=["method_id", "preprocessing_arm"], how="left")
    intended.to_csv(output / "intended_use_fluorescence_and_all_type_stress.csv", index=False)

    # Preserve the completed v2 native-motion screen as an explicitly
    # non-geometric supplement.  It is never merged into controlled accuracy.
    natural_source = project / "library/benchmark/v2/benchmarks/natural_motion/summaries" \
        / "full_benchmark_2026-08-16/method_summary.csv"
    natural = pd.read_csv(natural_source, dtype=str).fillna("")
    natural.insert(0, "evidence_scope", "legacy_v2_native_motion_temporal_stability")
    natural.insert(1, "accuracy_interpretation",
                   "not geometric accuracy; no independent camera-motion truth")
    natural.insert(2, "source_sha256", hashlib.sha256(natural_source.read_bytes()).hexdigest())
    natural.to_csv(output / "natural_motion_operational_supplement.csv", index=False)


def write_claim_report(gates: pd.DataFrame, output: Path) -> None:
    primary = gates[gates.inference_role == "holm_confirmatory"]
    gate_pass = {hypothesis: bool(group["pass"].all())
                 for hypothesis, group in primary.groupby("hypothesis")}
    normalized = gates[gates.inference_role == "normalized_sensitivity"]
    normalized_pass = {hypothesis: bool(group["pass"].all())
                       for hypothesis, group in normalized.groupby("hypothesis")}
    if gate_pass.get("H1") and gate_pass.get("H2") and gate_pass.get("H3") and gate_pass.get("H4"):
        level = 3
        claim = ("Across five microscopy image strata on native inputs, robust log-ratio translation with redundant "
                 "temporal reconciliation improved resistance to multiplicative intensity and local "
                 "scene change while preserving the clean-data safeguard against a comparator family "
                 "selected prospectively on development data.")
    elif gate_pass.get("H1") and gate_pass.get("H4"):
        level = 2
        claim = ("On native inputs, robust log-ratio translation improved multiplicative-intensity robustness while "
                 "meeting the prespecified clean-data safeguard; broader local-change or scheduler "
                 "claims were not supported.")
    elif gate_pass.get("H1"):
        level = 1
        claim = "On native inputs, the controlled benchmark supports a multiplicative-gain robustness mechanism only."
    else:
        level = 0
        claim = "The confirmatory benchmark does not support the prespecified robustness claim."
    lines = ["# Registration Benchmark v3 claim decision", "",
             f"Strongest supported computational claim level: **{level}**.", "", claim, "",
             "Physical-validation claim level 4 is not available from this computational benchmark.", "",
             ("The gain, local-change and clean-safeguard gates also passed after common normalization."
              if all(normalized_pass.get(hypothesis, False) for hypothesis in ("H1", "H2", "H4"))
              else "Common-normalized results are reported as a prespecified scope analysis and do not all pass."),
             ("The frozen automatic selector passed its deployment-utility gate."
              if gate_pass.get("H5") else
              "The frozen automatic selector did not pass its deployment-utility gate."),
             "",
             "| Gate | Arm | Estimate | 95% interval/upper | Margin | Holm p | Pass |",
             "|---|---|---:|---:|---:|---:|---:|"]
    for row in gates.itertuples():
        interval = (f"[{row.ci95_low:.4f}, {row.ci95_high:.4f}]"
                    if math.isfinite(row.ci95_low) else "n/a")
        holm = f"{row.holm_adjusted_p:.4g}" if math.isfinite(row.holm_adjusted_p) else "sensitivity"
        lines.append(f"| {row.hypothesis} | {row.preprocessing_arm} | {row.estimate:.4f} | "
                     f"{interval} | {row.margin:.4f} | {holm} | "
                     f"{bool(getattr(row, 'pass'))} |")
    lines += ["", "Aliases and ports were retained in supplementary tables but excluded from "
              "independent-family win counts. Failures remained in every denominator and received "
              "the frozen per-recording maximum-motion-plus-5-px penalty.", ""]
    (output / "CLAIM_DECISION.md").write_text("\n".join(lines), encoding="utf-8")


def write_experiment_scope(inputs: pd.DataFrame, results: pd.DataFrame,
                           methods: pd.DataFrame, output: Path) -> None:
    available = methods[methods.status == "available"]
    representatives = family_representatives(methods)
    values = [
        ("publication_source_rows", inputs.series_id.nunique()),
        ("independent_acquisition_clusters", inputs.independent_group.nunique()),
        ("image_series_classes", inputs.image_series_class.nunique()),
        ("motion_paths", inputs.motion_category.nunique()),
        ("condition_ids", inputs.condition_id.nunique()),
        ("generated_recordings", len(inputs)),
        ("available_method_alias_port_ablation_rows", len(available)),
        ("independent_family_representatives", len(representatives)),
        ("preprocessing_arms", results.preprocessing_arm.nunique()),
        ("fairness_views", results.benchmark_view.nunique()),
        ("canonical_method_view_arm_recording_rows", len(results)),
        ("algorithm_failure_rows", int((~results.algorithm_success).sum())),
    ]
    pd.DataFrame(values, columns=["scope_item", "value"]).to_csv(
        output / "experiment_scope.csv", index=False)
    lines = ["# Publication benchmark scope", "",
             f"The controlled test contains **{inputs.independent_group.nunique()} independent acquisitions** "
             f"across **{inputs.image_series_class.nunique()} image types**, with "
             f"**{inputs.motion_category.nunique()} motion paths** and "
             f"**{inputs.condition_id.nunique()} declared condition IDs**.", "",
             f"It generated **{len(inputs):,} paired input recordings**. "
             f"All **{len(available)} runnable method/alias/port/ablation rows** were evaluated in "
             f"default and equally tuned views on native and common-normalized inputs, yielding "
             f"**{len(results):,} canonical result rows**.", "",
             f"Independent-family ranking uses **{len(representatives)} prospectively selected family representatives**; "
             "aliases and ports remain in the complete table but cannot create additional wins.", "",
             f"Observed algorithm-failure rows retained in the denominator: **{int((~results.algorithm_success).sum()):,}**.", "",
             "## Acquisitions by image type", "",
             "| Image type | Sources | Acquisition clusters | Labs |",
             "|---|---:|---:|---:|"]
    source_rows = inputs[["image_series_class", "series_id", "independent_group", "lab_id"]].drop_duplicates()
    for image_type, group in source_rows.groupby("image_series_class"):
        lines.append(f"| {image_type} | {group.series_id.nunique()} | "
                     f"{group.independent_group.nunique()} | {group.lab_id.nunique()} |")
    (output / "EXPERIMENT_SCOPE.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_results_index(v3: Path) -> None:
    lines = ["# Registration Benchmark v3 results", "",
             "Start with these audited outputs:", "",
             "- [Experiment scope](summaries/EXPERIMENT_SCOPE.md)",
             "- [Primary independent-family benchmark](summaries/primary_independent_family_table.md)",
             "- [Complete tuned table](summaries/benchmark_table_tuned.md)",
             "- [Default/tuned and preprocessing fairness matrix](summaries/fairness_four_arm_table.md)",
             "- [Comparator coverage and prospective exclusions](summaries/COMPARATOR_COVERAGE.md)",
             "- [Confirmatory claim decision](summaries/CLAIM_DECISION.md)",
             "- [Artifact hashes and completeness](../audit/publication_artifact_manifest.csv)", "",
             "Main figures are under `figures/`; exact plotted values are under `summaries/`. "
             "The complete failure-aware recording table is under `trial_rows/`.", ""]
    results = v3 / "results"
    results.mkdir(parents=True, exist_ok=True)
    (results / "README.md").write_text("\n".join(lines), encoding="utf-8")


def audit_publication_artifacts(v3: Path) -> None:
    summary_names = [
        "acquisition_clustered_summaries.csv", "benchmark_table_all.csv",
        "benchmark_table_defaults.csv", "benchmark_table_defaults.md",
        "benchmark_table_tuned.csv", "benchmark_table_tuned.md",
        "fairness_four_arm_table.csv", "fairness_four_arm_table.md",
        "primary_independent_family_table.csv", "primary_independent_family_table.md",
        "scheduler_strategy_table.csv", "scheduler_strategy_table.md",
        "legacy_v2_method_matrix.csv",
        "legacy_v2_32_default_native.csv", "supplementary_by_image_type.csv",
        "primary_independent_family_by_image_type.csv",
        "intended_use_fluorescence_and_all_type_stress.csv",
        "comparator_inclusion_exclusion.csv", "COMPARATOR_COVERAGE.md",
        "experiment_scope.csv", "EXPERIMENT_SCOPE.md",
        "natural_motion_operational_supplement.csv", "claim_gates.csv",
        "scheduler_crossing.csv", "CLAIM_DECISION.md", "figure1_data.csv",
        "figure2_data.csv", "figure3_data.csv", "figure1_robustness_curves_data.csv",
        "figure2_clean_vs_robustness_data.csv", "figure3_error_accumulation_data.csv",
    ]
    figure_stems = [
        "figure1_robustness_curves", "figure2_clean_accuracy_vs_robustness",
        "figure3_error_accumulation", "figureS1_overall_method_ranking",
        "figureS2_primary_pair_robustness", "figureS3_scheduler_crossing",
    ]
    required = [v3 / "results/summaries" / name for name in summary_names]
    required += [v3 / "results/figures" / f"{stem}.{extension}"
                 for stem in figure_stems for extension in ("svg", "png", "pdf")]
    required += [v3 / "results/trial_rows/publication_recording_rows.csv",
                 v3 / "results/README.md",
                 v3 / "audit/publication_result_completeness.csv",
                 v3 / "audit/transform_recalculation_sample.csv"]
    rows = []
    missing = []
    for path in required:
        exists = path.is_file() and path.stat().st_size > 0
        rows.append({
            "relative_path": path.relative_to(v3.parent.parent.parent).as_posix(),
            "required": "true", "status": "present" if exists else "missing",
            "bytes": path.stat().st_size if exists else 0,
            "sha256": sha256_file(path) if exists else "",
        })
        if not exists:
            missing.append(path)
    rows.append({
        "relative_path": "library/benchmark/v3/results/figures/figure4_physical_validation",
        "required": "false", "status": "not_run_no_hidden_fiducial_input",
        "bytes": 0, "sha256": "",
    })
    destination = v3 / "audit/publication_artifact_manifest.csv"
    pd.DataFrame(rows).to_csv(destination, index=False)
    if missing:
        raise RuntimeError("publication artifacts missing: " + ", ".join(map(str, missing[:10])))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    project = args.project.resolve()
    v3 = project / "library/benchmark/v3"
    if not (v3 / "protocol/PROTOCOL_FROZEN.txt").is_file():
        raise RuntimeError("analysis is locked until the v3 protocol is frozen")
    verify_frozen_protocol(project)
    winners = pd.read_csv(v3 / "protocol/frozen_config/winners.csv", dtype=str).fillna("")
    methods = pd.read_csv(v3 / "protocol/method_manifest.csv", dtype=str).fillna("")
    conditions = pd.read_csv(v3 / "protocol/conditions.csv", dtype=str).fillna("")
    inputs, recording_metadata = input_tables(project)
    raw = load_results(project, winners)
    audit = v3 / "audit"; summaries_dir = v3 / "results/summaries"
    summaries_dir.mkdir(parents=True, exist_ok=True)
    raw = enforce_completeness(raw, inputs, methods, conditions,
                               audit / "publication_result_completeness.csv")
    scored = add_penalties(raw, recording_metadata)
    write_experiment_scope(inputs, scored, methods, summaries_dir)
    audit_transform_recalculation(
        scored, recording_metadata, audit / "transform_recalculation_sample.csv")
    trial_rows = v3 / "results/trial_rows"
    trial_rows.mkdir(parents=True, exist_ok=True)
    scored.to_csv(trial_rows / "publication_recording_rows.csv", index=False)
    summaries = make_summaries(scored, methods)
    summaries.to_csv(summaries_dir / "acquisition_clustered_summaries.csv", index=False)
    write_benchmark_tables(summaries, methods, summaries_dir)
    write_supplementary_tables(scored, summaries, methods, project, summaries_dir)
    gates, condition, scheduler = claim_gates(scored, project)
    gates.to_csv(summaries_dir / "claim_gates.csv", index=False)
    scheduler.to_csv(summaries_dir / "scheduler_crossing.csv", index=False)
    write_claim_report(gates, summaries_dir)
    figures(summaries, condition, scheduler, v3 / "results")
    publication_figures(summaries, scored, methods, recording_metadata, v3 / "results")
    write_results_index(v3)
    audit_publication_artifacts(v3)
    print(f"v3 analysis complete: {len(scored)} canonical rows; {summaries_dir}")


if __name__ == "__main__":
    main()
