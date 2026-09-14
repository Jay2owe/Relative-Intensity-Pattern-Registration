#!/usr/bin/env python3
"""Freeze development winners and publish default/tuned external comparison tables."""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


ACTIVE_IMAGE_CLASSES = ["BRIGHTFIELD_DIC", "DENSE_FLUOR", "FIDUCIAL_STATIC", "PHASE"]
# The immutable winner freeze predates the four-class scope decision.  Keep its
# five-class shape for audit verification, then filter it before active analysis.
FROZEN_IMAGE_CLASSES = ACTIVE_IMAGE_CLASSES + ["SPARSE_LOWLIGHT"]
ENGINE_METHOD = {
    "turboreg": "21_real_stackreg_turboreg_translation_chain",
    "stabilizer": "24_real_image_stabilizer_lucas_kanade_rolling_template",
    "fast4d": "26_real_fast4dreg_nanoj_first_frame",
    "correct3d": "27_real_correct_3d_drift_phase_correlation_standard",
    "sift": "29_real_linear_stack_alignment_sift_translation_chain",
    "descriptor": "32_real_descriptor_based_series_translation",
}
ENGINE_CONFIGS = {
    "turboreg": ["turboreg_default_rigid", "turboreg_translation"],
    "stabilizer": ["stabilizer_default", "stabilizer_pyramid_2", "stabilizer_pyramid_3",
                   "stabilizer_pyramid_4", "stabilizer_pinned_first"],
    "fast4d": ["fast4d_peak_optimized_default", "fast4d_peak_centroid", "fast4d_peak_pixel"],
    "correct3d": ["correct3d_peaks_5_default", "correct3d_peaks_10"],
    "sift": ["sift_default", "sift_epsilon_1", "sift_epsilon_3", "sift_epsilon_10",
             "sift_min_inliers_4", "sift_min_inliers_8", "sift_finer_octave"],
    "descriptor": ["descriptor_threshold_0_008", "descriptor_threshold_0_03_default",
                   "descriptor_threshold_0_1"],
}
DEFAULT_CONFIG = {
    "turboreg": "turboreg_default_rigid",
    "stabilizer": "stabilizer_default",
    "fast4d": "fast4d_peak_optimized_default",
    "correct3d": "correct3d_peaks_5_default",
    "sift": "sift_default",
    "descriptor": "descriptor_threshold_0_03_default",
}
METHOD_COUNTS = {"all": 12, "turboreg": 1, "stabilizer": 1, "fast4d": 1,
                 "correct3d": 1, "sift": 1, "descriptor": 1}
METHOD_ENGINE = {}
for prefix, engine in [("21_", "turboreg"), ("22_", "turboreg"), ("23_", "turboreg"),
                       ("24_", "stabilizer"), ("25_", "fast4d"), ("26_", "fast4d"),
                       ("27_", "correct3d"), ("28_", "correct3d"), ("29_", "sift"),
                       ("30_", "sift"), ("31_", "sift"), ("32_", "descriptor")]:
    METHOD_ENGINE[prefix] = engine


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise FileNotFoundError(path)
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def recording_key(row: dict[str, str]) -> tuple[str, str, str, str]:
    return (row["image_series_class"], row["series_id"], row["motion_category"], row["condition"])


def ok(row: dict[str, str]) -> bool:
    return row.get("status", "").strip() == "ok" and row.get("median_error_px", "").strip() != ""


def median(values: list[float]) -> float:
    return statistics.median(values) if values else math.nan


def fmt(value: float) -> str:
    if math.isnan(value):
        return "NaN"
    if math.isinf(value):
        return "Inf"
    return f"{value:.6f}"


def engine_for_method(method_id: str) -> str:
    for prefix, engine in METHOD_ENGINE.items():
        if method_id.startswith(prefix):
            return engine
    raise ValueError(f"unknown external method {method_id}")


def validate_development(run_root: Path) -> list[dict]:
    development = run_root / "development"
    configs = ["all_defaults", "all_defaults_replay"] + [c for values in ENGINE_CONFIGS.values() for c in values]
    rows_out = []
    for config in configs:
        rows = read_csv(development / f"{config}.csv")
        engine = "all" if config.startswith("all_defaults") else next(
            e for e, values in ENGINE_CONFIGS.items() if config in values)
        recordings = {recording_key(row) for row in rows}
        expected_rows = 80 * METHOD_COUNTS[engine]
        successes = sum(ok(row) for row in rows)
        result = {
            "config_id": config,
            "engine": engine,
            "expected_recordings": 80,
            "observed_recordings": len(recordings),
            "expected_rows": expected_rows,
            "observed_rows": len(rows),
            "successful_rows": successes,
            "failed_rows": len(rows) - successes,
            "complete": str(len(recordings) == 80 and len(rows) == expected_rows).lower(),
        }
        rows_out.append(result)
        if result["complete"] != "true":
            raise RuntimeError(f"incomplete development config: {result}")
    write_csv(run_root / "sweep_completion.csv", rows_out, list(rows_out[0]))
    return rows_out


def verify_plugin_versions(project: Path, run_root: Path) -> list[dict]:
    fiji = project.parent.parent / "Fiji.app"
    output = []
    for row in read_csv(run_root / "plugin_versions.csv"):
        artifact = fiji / Path(row["installed_artifact"])
        observed = file_hash(artifact) if artifact.is_file() else "missing"
        output.append({
            "component": row["component"],
            "installed_artifact": row["installed_artifact"],
            "expected_sha256": row["sha256"],
            "observed_sha256": observed,
            "exact": str(observed == row["sha256"]).lower(),
        })
    write_csv(run_root / "plugin_version_verification.csv", output, list(output[0]))
    failures = [row for row in output if row["exact"] != "true"]
    if failures:
        raise RuntimeError(f"installed plugin version drift: {failures}")
    return output


def default_replay(run_root: Path) -> list[dict]:
    base = read_csv(run_root / "development" / "all_defaults.csv")
    replay = read_csv(run_root / "development" / "all_defaults_replay.csv")
    key_fields = ["image_series_class", "series_id", "motion_category", "condition", "method_id"]
    keyed_base = {tuple(row[k] for k in key_fields): row for row in base}
    keyed_replay = {tuple(row[k] for k in key_fields): row for row in replay}
    if keyed_base.keys() != keyed_replay.keys():
        raise RuntimeError("default replay keys differ from corrected baseline")
    grouped: dict[str, list[tuple[dict, dict]]] = defaultdict(list)
    for key in sorted(keyed_base):
        grouped[key[-1]].append((keyed_base[key], keyed_replay[key]))
    output = []
    for method, pairs in grouped.items():
        status_mismatches = sum(a["status"] != b["status"] for a, b in pairs)
        deltas = []
        numeric_mismatches = 0
        for a, b in pairs:
            for field in ("median_error_px", "p90_error_px", "max_error_px"):
                if a[field] == b[field]:
                    deltas.append(0.0)
                elif a[field] and b[field]:
                    delta = abs(float(a[field]) - float(b[field]))
                    deltas.append(delta)
                    numeric_mismatches += 1
                else:
                    numeric_mismatches += 1
        output.append({
            "method_id": method,
            "method_label": pairs[0][0]["method_label"],
            "recordings_compared": len(pairs),
            "status_mismatches": status_mismatches,
            "numeric_field_mismatches": numeric_mismatches,
            "max_absolute_metric_delta_px": fmt(max(deltas, default=0.0)),
            "exact_replay": str(status_mismatches == 0 and numeric_mismatches == 0).lower(),
        })
    write_csv(run_root / "default_replay.csv", output, list(output[0]))
    return output


def freeze_winners(run_root: Path) -> tuple[list[dict], list[dict]]:
    development = run_root / "development"
    candidates = []
    winners = []
    for image_class in FROZEN_IMAGE_CLASSES:
        for engine, configs in ENGINE_CONFIGS.items():
            scored = []
            representative = ENGINE_METHOD[engine]
            for config in configs:
                rows = [row for row in read_csv(development / f"{config}.csv")
                        if row["image_series_class"] == image_class and row["method_id"] == representative]
                if len(rows) != 16:
                    raise RuntimeError(f"{config}/{image_class}/{representative}: expected 16, found {len(rows)}")
                values = [float(row["median_error_px"]) if ok(row) else math.inf for row in rows]
                failures = sum(not ok(row) for row in rows)
                score = median(values)
                successful_values = [float(row["median_error_px"]) for row in rows if ok(row)]
                candidate = {
                    "image_series_class": image_class,
                    "engine": engine,
                    "representative_method_id": representative,
                    "config_id": config,
                    "recordings": 16,
                    "successful": 16 - failures,
                    "failures": failures,
                    "selection_median_px_failures_as_inf": fmt(score),
                    "successful_only_median_px": fmt(median(successful_values)),
                    "is_installed_default": str(config == DEFAULT_CONFIG[engine]).lower(),
                }
                candidates.append(candidate)
                rank = (score, failures, 0 if config == DEFAULT_CONFIG[engine] else 1, config)
                scored.append((rank, candidate, rows[0]))
            _, winner, sample = min(scored, key=lambda item: item[0])
            winners.append({
                **winner,
                "config_description": sample["config_description"],
                "settings": sample["settings"],
                "frozen_utc": datetime.now(timezone.utc).isoformat(),
            })
    write_csv(run_root / "candidate_scores.csv", candidates, list(candidates[0]))
    write_csv(run_root / "frozen_winners.csv", winners, list(winners[0]))

    lines = ["# Frozen external parameter winners", "",
             "Frozen from the 80 development recordings before any locked-set external run.", "",
             "Selection rule: for each engine and image type, median of the 16 per-recording median",
             "errors for the representative native/default-strategy row; failed recordings are +Inf.",
             "Exact ties prefer the installed default. Other rows from an engine inherit that setting.", "",
             "| Image type | Engine | Frozen configuration | Selection median (px) | Failures |",
             "|---|---|---|---:|---:|"]
    for row in winners:
        lines.append(f"| {row['image_series_class']} | {row['engine']} | `{row['config_id']}` | "
                     f"{row['selection_median_px_failures_as_inf']} | {row['failures']} |")
    (run_root / "FROZEN_WINNERS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return candidates, winners


def summarize_rows(rows: list[dict[str, str]], expected: int) -> dict:
    successes = [row for row in rows if ok(row)]
    processor_values = [float(row["cpu_seconds"]) for row in successes if row.get("cpu_seconds", "")]
    return {
        "recordings_expected": expected,
        "successful": len(successes),
        "failures": expected - len(successes),
        "median_of_recording_medians_px": fmt(median([float(row["median_error_px"]) for row in successes])),
        "median_of_recording_p90_px": fmt(median([float(row["p90_error_px"]) for row in successes
                                                   if row.get("p90_error_px", "")])),
        "mean_processor_seconds": fmt(statistics.fmean(processor_values)) if processor_values else "NaN",
    }


def internal_rows(project: Path, dataset_root: str, method_id: str) -> list[dict[str, str]]:
    path = project / "library" / "benchmark" / "v2" / "benchmarks" / dataset_root / "summaries" / \
        "external_comparison_v1" / "all_methods_by_recording.csv"
    return [row for row in read_csv(path) if row["method_id"] == method_id]


def external_tuned_rows(run_root: Path, dataset: str, winners: list[dict]) -> list[dict[str, str]]:
    output = []
    for winner in winners:
        config = winner["config_id"]
        image_class = winner["image_series_class"]
        filename = ("all_defaults.csv" if winner["is_installed_default"] == "true"
                    else f"{config}__{image_class}.csv")
        rows = read_csv(run_root / dataset / filename)
        output.extend(row for row in rows if row["image_series_class"] == image_class
                      and row["engine"] == winner["engine"])
    return output


def comparison_table(project: Path, run_root: Path, dataset: str, tuned: bool,
                     winners: list[dict]) -> list[dict]:
    dataset_root = "controlled_motion" if dataset == "development" else "locked_test"
    expected_by_class = 16 if dataset == "development" else 8
    expected_all = expected_by_class * len(ACTIVE_IMAGE_CLASSES)
    own_id = "4_new_full_automatic_selector" if tuned else "6_base_without_automatic_changes"
    own = [row for row in internal_rows(project, dataset_root, own_id)
           if row["image_series_class"] in ACTIVE_IMAGE_CLASSES]
    external = (external_tuned_rows(run_root, dataset, winners) if tuned else
                read_csv(run_root / dataset / "all_defaults.csv"))
    external = [row for row in external
                if row["image_series_class"] in ACTIVE_IMAGE_CLASSES]
    comparison = "tuned_against_tuned" if tuned else "defaults_against_defaults"
    output = []
    groups = ACTIVE_IMAGE_CLASSES + ["ALL"]
    for group in groups:
        expected = expected_all if group == "ALL" else expected_by_class
        own_group = own if group == "ALL" else [r for r in own if r["image_series_class"] == group]
        own_summary = summarize_rows(own_group, expected)
        reference_value = float(own_summary["median_of_recording_medians_px"])
        output.append({
            "dataset": dataset, "comparison": comparison, "image_series_class": group,
            "method_id": own_id, "method_label": own_group[0]["method_label"],
            "origin": "this plugin", "family": "log-ratio engine",
            "config_id": "trained_selector" if tuned else "base_no_automatic_selection",
            **own_summary, "lower_successful_median_than_reference": "reference",
            "beats_our_reference": "reference", "delta_vs_our_reference_px": "0.000000",
        })
        method_ids = sorted({row["method_id"] for row in external})
        for method_id in method_ids:
            method_rows = [row for row in external if row["method_id"] == method_id and
                           (group == "ALL" or row["image_series_class"] == group)]
            summary = summarize_rows(method_rows, expected)
            value = float(summary["median_of_recording_medians_px"])
            beats = summary["failures"] == 0 and value < reference_value
            lower = value < reference_value
            sample = method_rows[0] if method_rows else next(r for r in external if r["method_id"] == method_id)
            configs = sorted({r.get("config_id", "") for r in method_rows})
            output.append({
                "dataset": dataset, "comparison": comparison, "image_series_class": group,
                "method_id": method_id, "method_label": sample["method_label"],
                "origin": sample["origin"], "family": sample["family"],
                "config_id": ";".join(configs), **summary,
                "lower_successful_median_than_reference": str(lower).lower(),
                "beats_our_reference": str(beats).lower(),
                "delta_vs_our_reference_px": fmt(value - reference_value),
            })
    return output


def correction_delta(project: Path, run_root: Path) -> list[dict]:
    old = read_csv(project / "library" / "benchmark" / "v2" / "benchmarks" / "controlled_motion" /
                   "summaries" / "external_comparison_v1" / "all_methods_by_recording.csv")
    new = read_csv(run_root / "development" / "all_defaults.csv")
    old = [row for row in old if row["image_series_class"] in ACTIVE_IMAGE_CLASSES]
    new = [row for row in new if row["image_series_class"] in ACTIVE_IMAGE_CLASSES]
    output = []
    for method_id in sorted({r["method_id"] for r in new}):
        old_rows = [r for r in old if r["method_id"] == method_id]
        new_rows = [r for r in new if r["method_id"] == method_id]
        expected = 16 * len(ACTIVE_IMAGE_CLASSES)
        old_summary = summarize_rows(old_rows, expected)
        new_summary = summarize_rows(new_rows, expected)
        old_value = float(old_summary["median_of_recording_medians_px"])
        new_value = float(new_summary["median_of_recording_medians_px"])
        output.append({
            "method_id": method_id, "old_label": old_rows[0]["method_label"],
            "corrected_label": new_rows[0]["method_label"],
            "old_successful": old_summary["successful"], "old_failures": old_summary["failures"],
            "corrected_successful": new_summary["successful"],
            "corrected_failures": new_summary["failures"],
            "old_median_px": fmt(old_value), "corrected_median_px": fmt(new_value),
            "delta_corrected_minus_old_px": fmt(new_value - old_value),
        })
    write_csv(run_root / "stage0_correction_delta.csv", output, list(output[0]))
    return output


def aggregate_main_hash(project: Path) -> str:
    lines = []
    for path in sorted((project / "src" / "main").rglob("*")):
        if path.is_file():
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            lines.append(f"{digest}  {path.relative_to(project)}".replace("/", "\\"))
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_findings(run_root: Path, replay: list[dict], deltas: list[dict],
                   defaults: list[dict], tuned: list[dict]) -> None:
    deterministic_failures = [r for r in replay if not r["method_id"].startswith("32_")
                              and r["exact_replay"] != "true"]
    descriptor_replay = next(r for r in replay if r["method_id"].startswith("32_"))
    overall = [r for r in defaults + tuned if r["image_series_class"] == "ALL"]
    wins = [r for r in defaults + tuned if r["beats_our_reference"] == "true"]
    conditional = [r for r in defaults + tuned
                   if r["lower_successful_median_than_reference"] == "true"
                   and r["beats_our_reference"] != "true"]
    lines = ["# External parameter sweep findings: four-class scope", "",
             "## Gates", "",
             "- Active scope: brightfield/differential interference contrast, dense fluorescence, "
             "fiducial/static and phase contrast.",
             "- Sparse/low-light is excluded from every active aggregate and class table by the "
             "owner's 2026-08-21 scope decision; its raw files remain only as audit evidence.",
             f"- Raw development configurations: 24/24 complete over 80 recordings each; active "
             f"analysis uses {16 * len(ACTIVE_IMAGE_CLASSES)} recordings.",
             f"- Deterministic default rows failing exact replay: {len(deterministic_failures)}.",
             f"- Descriptor replay maximum metric delta: {descriptor_replay['max_absolute_metric_delta_px']} px; "
             "the installed optimizer remains object-order stochastic and is reported, not suppressed.",
             "- Winners were frozen in `frozen_winners.csv` before locked execution.",
             "- Every installed Fiji artifact still matches the Stage 0 SHA-256 manifest.",
             "- `src/main` and both saved in-house comparison tables match their pre-run SHA-256 controls.",
             "- Protocol deviation D1: pre-existing locked summary/count metadata was inspected before "
             "freezing; no candidate was run or selected there, but the locked set is not described as blinded.",
             "- Protocol deviation D3: the first finalizer invocation rewrote the unchanged frozen-winner "
             "table after locked execution. The 19:22:15 BST freeze time was restored from the observed "
             "pre-locked artifact timestamp, and final mode now requires its SHA-256 manifest.",
             "- No sealed-set path was opened by this workflow.", "",
             "## Stage 0 correction impact (development overall)", "",
             "| Method | Old median px | Corrected median px | Delta px | Corrected failures |",
             "|---|---:|---:|---:|---:|"]
    for row in deltas:
        lines.append(f"| {row['corrected_label']} | {row['old_median_px']} | {row['corrected_median_px']} | "
                     f"{row['delta_corrected_minus_old_px']} | {row['corrected_failures']} |")
    lines += ["", "## Overall headline rows", "",
              "| Dataset | Comparison | Method | Median px | Failures | Versus our reference |",
              "|---|---|---|---:|---:|---:|"]
    for row in overall:
        lines.append(f"| {row['dataset']} | {row['comparison']} | {row['method_label']} | "
                     f"{row['median_of_recording_medians_px']} | {row['failures']} | "
                     f"{row['delta_vs_our_reference_px']} |")
    lines += ["", "## External wins", ""]
    if wins:
        lines += ["These external rows beat the corresponding in-house reference with zero failures:", "",
                  "| Dataset | Comparison | Image type | External method | External px | Delta px |",
                  "|---|---|---|---|---:|---:|"]
        for row in wins:
            lines.append(f"| {row['dataset']} | {row['comparison']} | {row['image_series_class']} | "
                         f"{row['method_label']} | {row['median_of_recording_medians_px']} | "
                         f"{row['delta_vs_our_reference_px']} |")
    else:
        lines.append("No external row beat its corresponding in-house reference with zero failures.")
    lines += ["", "## Lower successful-only medians with failures", ""]
    if conditional:
        lines += ["These rows have lower medians on the recordings they completed, but are not called "
                  "wins because they failed on other recordings:", "",
                  "| Dataset | Comparison | Image type | External method | External px | Failures | Delta px |",
                  "|---|---|---|---|---:|---:|---:|"]
        for row in conditional:
            lines.append(f"| {row['dataset']} | {row['comparison']} | {row['image_series_class']} | "
                         f"{row['method_label']} | {row['median_of_recording_medians_px']} | "
                         f"{row['failures']} | {row['delta_vs_our_reference_px']} |")
    else:
        lines.append("None.")
    lines += ["", "Full by-image-type results, including failures, are in "
              "`defaults_against_defaults.csv` and `tuned_against_tuned.csv`. Both contain only the "
              "four active classes and their four-class aggregate."]
    (run_root / "FINDINGS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--stage", choices=["freeze", "final"], required=True)
    args = parser.parse_args()
    project = args.project.resolve()
    run_root = project / "library" / "benchmark" / "v2" / "runs" / "external_parameter_sweep_v1"
    verify_plugin_versions(project, run_root)
    validate_development(run_root)
    replay = default_replay(run_root)
    # REGRESSION GUARD: final mode once recomputed and rewrote the pre-locked winner table.
    # The fix: only freeze mode may select/write winners; final mode must hash-check and read them.
    if args.stage == "freeze":
        _, winners = freeze_winners(run_root)
        (run_root / "frozen_winners.sha256").write_text(
            file_hash(run_root / "frozen_winners.csv") + "  frozen_winners.csv\n",
            encoding="utf-8")
        print(f"Frozen {len(winners)} winners after validating 24 development configurations")
        return

    frozen_path = run_root / "frozen_winners.csv"
    frozen_hash_path = run_root / "frozen_winners.sha256"
    winners = read_csv(frozen_path)
    if len(winners) != 30:
        raise RuntimeError(f"expected 30 frozen winners, found {len(winners)}")
    expected_frozen_hash = frozen_hash_path.read_text(encoding="utf-8").split()[0]
    observed_frozen_hash = file_hash(frozen_path)
    if observed_frozen_hash != expected_frozen_hash:
        raise RuntimeError("frozen_winners.csv changed after the development freeze")
    winners = [row for row in winners
               if row["image_series_class"] in ACTIVE_IMAGE_CLASSES]
    if len(winners) != 6 * len(ACTIVE_IMAGE_CLASSES):
        raise RuntimeError(f"expected 24 active frozen winners, found {len(winners)}")
    deltas = correction_delta(project, run_root)

    defaults = []
    tuned = []
    for dataset in ("development", "locked"):
        defaults.extend(comparison_table(project, run_root, dataset, False, winners))
        tuned.extend(comparison_table(project, run_root, dataset, True, winners))
    write_csv(run_root / "defaults_against_defaults.csv", defaults, list(defaults[0]))
    write_csv(run_root / "tuned_against_tuned.csv", tuned, list(tuned[0]))

    controlled_internal = project / "library" / "benchmark" / "v2" / "benchmarks" / \
        "controlled_motion" / "summaries" / "external_comparison_v1" / "all_methods_by_recording.csv"
    locked_internal = project / "library" / "benchmark" / "v2" / "benchmarks" / "locked_test" / \
        "summaries" / "external_comparison_v1" / "all_methods_by_recording.csv"
    controls = [
        {"control": "src/main aggregate SHA-256",
         "expected": "aed9be8adf54e5206a78ce0a596cb7c9e549f4002fa45b5a4669672df764354b",
         "observed": aggregate_main_hash(project)},
        {"control": "development in-house comparison rows SHA-256",
         "expected": "7a1701b25d520660c2164aa12da8ce13f3c254bd4439422a5bdc1d4bb10b7f45",
         "observed": file_hash(controlled_internal)},
        {"control": "locked in-house comparison rows SHA-256",
         "expected": "5fbff66501b191c8ab11b77364d1f5fa62d8d0b04649ce8856245a664f9a8821",
         "observed": file_hash(locked_internal)},
    ]
    for control in controls:
        control["exact"] = str(control["expected"] == control["observed"]).lower()
    write_csv(run_root / "control_verification.csv", controls, list(controls[0]))
    failed_controls = [control for control in controls if control["exact"] != "true"]
    if failed_controls:
        raise RuntimeError(f"in-house controls changed: {failed_controls}")
    write_findings(run_root, replay, deltas, defaults, tuned)
    print("Published four-class defaults-against-defaults and tuned-against-tuned tables")


if __name__ == "__main__":
    main()
