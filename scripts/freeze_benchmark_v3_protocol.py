#!/usr/bin/env python3
"""Validate every prospective gate and create the one-way v3 publication-test seal."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


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


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise FileNotFoundError(path)
    with path.open("r", newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def assert_validation(path: Path, expected: int) -> None:
    rows = read_csv(path)
    if len(rows) != expected or any(row.get("status") != "pass" for row in rows):
        raise RuntimeError(f"validation gate failed: {path} expected {expected} passing rows")


def assert_conformance(path: Path, expected: int) -> None:
    rows = read_csv(path)
    if len(rows) != expected or any(row.get("status") != "pass" for row in rows):
        raise RuntimeError(f"conformance gate failed: {path} expected {expected} passing rows")


def integration_keys(project: Path) -> set[tuple[str, str, str]]:
    manifest = read_csv(project / "library/benchmark/v3/inputs/integration/inputs_manifest.csv")
    if len(manifest) != 5:
        raise RuntimeError(f"integration manifest expected 5 inputs, found {len(manifest)}")
    return {(row["series_id"], row["condition_instance"], row["motion_category"])
            for row in manifest}


def audit_integration(project: Path, included: set[str]) -> None:
    expected_inputs = integration_keys(project)
    root = project / "library/benchmark/v3/runs/integration"
    runner_paths = {
        "internal": root / "internal_results.csv",
        "python": root / "python_results.csv",
        "jnormcorre": root / "jnormcorre_results.csv",
        "moco": root / "moco_results.csv",
    }
    found: dict[str, set[tuple[str, str, str]]] = {method: set() for method in included}
    for path in runner_paths.values():
        for row in read_csv(path):
            method = row["method_id"]
            if method not in found:
                continue
            # REGRESSION GUARD: integration proves route/format coverage, not algorithm success.
            # Explicit algorithm failures are valid outcomes and are penalty-scored on the test set.
            status = row.get("status", "")
            if status != "ok" and not status.startswith("failed"):
                raise RuntimeError(
                    f"malformed integration status in {path}: {method}/{row['series_id']}/{status}")
            found[method].add((row["series_id"], row["condition_instance"],
                               row["motion_category"]))

    external_root = project / "library/benchmark/v3/runs/external_parameter_sweep_v1/v3_integration"
    external_paths = sorted(external_root.glob("*.csv"))
    if not external_paths:
        raise FileNotFoundError(f"no integration external CSVs under {external_root}")
    for path in external_paths:
        for row in read_csv(path):
            if row.get("config_id") != "all_defaults":
                continue
            method = EXTERNAL_MAP.get(row.get("method_id", ""))
            if method not in found:
                continue
            status = row.get("status", "")
            if status != "ok" and not status.startswith("failed"):
                raise RuntimeError(
                    f"malformed integration external status in {path}: "
                    f"{method}/{row['series_id']}/{status}")
            found[method].add((row["series_id"], row["condition"], row["motion_category"]))

    missing = {method: sorted(expected_inputs - observed) for method, observed in found.items()
               if expected_inputs - observed}
    if missing:
        short = {method: len(values) for method, values in missing.items()}
        raise RuntimeError(f"integration routes incomplete: {short}")


def freeze_selector_state(project: Path, output: Path) -> None:
    source = project / "src/main/java/ripr/core/AutomaticInformationSelector.java"
    text = source.read_text(encoding="utf-8")
    constants = {}
    for name in ("SPARSE_BOUNDARY", "MOVING_TAIL_BOUNDARY",
                 "STABLE_BRIGHTEST_CEILING_PERCENTILE"):
        match = re.search(rf"public static final double {name}\s*=\s*([0-9.]+);", text)
        if not match:
            raise RuntimeError(f"could not freeze selector constant {name}")
        constants[name] = float(match.group(1))
    state = {
        "method_id": "20_automatic_information_selector",
        "training_status": "preexisting_v1_rule_retained_without_v3_test_access",
        "development_provenance": "v2 development/pilot sources only",
        "input_features": ["first_frame_sparse_score", "aligned_two_frame_residual_tail_ratio",
                           "explicit_intensity_band_flag"],
        "constants": constants,
        "source_relative_path": source.relative_to(project).as_posix(),
        "source_sha256": sha256(source),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    project = args.project.resolve()
    v3 = project / "library/benchmark/v3"
    protocol = v3 / "protocol"
    gate = protocol / "PROTOCOL_FROZEN.txt"
    if gate.exists():
        print(f"Protocol already frozen: {gate}")
        return

    # A publication result manifest before this seal is a protocol violation, not a resumable state.
    test_manifest = v3 / "inputs/publication_test/inputs_manifest.csv"
    if test_manifest.exists():
        raise RuntimeError("publication-test inputs exist before protocol freeze")

    all_sources = read_csv(protocol / "source_manifest.csv")
    sources = [row for row in all_sources if row["split"] == "publication_test"]
    counts = Counter(row["image_series_class"] for row in sources)
    if len(sources) != 30 or set(counts.values()) != {6} or len(counts) != 5:
        raise RuntimeError(f"publication source gate expected 30 and six/type; found {dict(counts)}")
    if len({row["source_id"] for row in sources}) != 30:
        raise RuntimeError("duplicate publication source IDs")
    acquisition_keys = [(row["image_series_class"], row["independent_group"])
                        for row in sources]
    if any(not group for _, group in acquisition_keys):
        raise RuntimeError("publication source has an empty independent_group")
    if len(set(acquisition_keys)) != 30:
        duplicates = [key for key, count in Counter(acquisition_keys).items() if count > 1]
        raise RuntimeError(f"publication sources are not acquisition-independent: {duplicates}")
    group_counts = Counter(image_type for image_type, _ in set(acquisition_keys))
    if len(group_counts) != 5 or set(group_counts.values()) != {6}:
        raise RuntimeError(f"publication acquisition gate expected six/type; found {dict(group_counts)}")
    publication_groups = {row["independent_group"] for row in sources}
    other_groups = {row["independent_group"] for row in all_sources
                    if row["split"] != "publication_test" and row["independent_group"]}
    split_overlap = publication_groups & other_groups
    if split_overlap:
        raise RuntimeError(f"publication acquisition groups overlap another split: {sorted(split_overlap)}")
    members = read_csv(protocol / "publication_member_selection.csv")
    source_by_id = {row["source_id"]: row for row in sources}
    member_by_id = {row["source_id"]: row for row in members}
    if len(members) != 30 or set(member_by_id) != set(source_by_id):
        raise RuntimeError("publication member-selection ledger does not match source manifest")
    shared_fields = ("image_series_class", "independent_group", "lab_id", "repository",
                     "licence", "source_url", "local_path", "plane_index")
    ledger_drift = [source_id for source_id, row in source_by_id.items()
                    if any(row[field] != member_by_id[source_id][field]
                           for field in shared_fields)]
    if ledger_drift:
        raise RuntimeError(f"publication member-selection ledger drift: {ledger_drift}")
    download_slots: Counter[str] = Counter()
    download_record_slots: Counter[str] = Counter()
    for row in read_csv(protocol / "publication_download_manifest.csv"):
        download_slots[row["planned_class"]] += int(row["source_slots"])
        download_record_slots[row["record_id"]] += int(row["source_slots"])
    if len(download_slots) != 5 or set(download_slots.values()) != {6}:
        raise RuntimeError(f"publication download ledger expected six slots/type: {dict(download_slots)}")
    member_record_slots = Counter(row["record_id"] for row in members)
    if download_record_slots != member_record_slots:
        raise RuntimeError(
            f"publication download/member record slots disagree: "
            f"downloads={dict(download_record_slots)} members={dict(member_record_slots)}")
    for row in sources:
        source_path = project / row["local_path"]
        observed = sha256(source_path)
        if observed.lower() != row["sha256"].lower():
            raise RuntimeError(
                f"publication source changed since selection: {row['source_id']} "
                f"expected={row['sha256']} observed={observed}")
    assert_validation(protocol / "publication_test_source_validation.csv", 30)
    assert_validation(protocol / "integration_source_validation.csv", 5)
    assert_validation(protocol / "failure_handling_validation.csv", 5)

    methods = read_csv(protocol / "method_manifest.csv")
    dispositions = read_csv(protocol / "comparator_dispositions.csv")
    if {row["method_id"] for row in methods} != {row["method_id"] for row in dispositions}:
        raise RuntimeError("method/disposition ledgers disagree")
    if any(row["status"] == "to_acquire" for row in methods):
        raise RuntimeError("unresolved comparator acquisition status")
    included = {row["method_id"] for row in methods if row["status"] == "available"}
    if len(included) != 49:
        raise RuntimeError(f"expected 49 included method/ablation rows, found {len(included)}")

    assert_conformance(v3 / "comparators/python/conformance.csv", 56)
    assert_conformance(v3 / "comparators/python/conformance_suite2p.csv", 8)
    assert_conformance(v3 / "comparators/jnormcorre/conformance.csv", 8)
    assert_conformance(v3 / "comparators/moco/conformance.csv", 6)

    winners = read_csv(protocol / "frozen_config/winners.csv")
    winner_keys = {(row["method_id"], row["preprocessing_arm"]) for row in winners}
    expected_winners = {(method, arm) for method in included
                        for arm in ("native", "common_normalized")}
    if winner_keys != expected_winners:
        missing = sorted(expected_winners - winner_keys)
        extra = sorted(winner_keys - expected_winners)
        raise RuntimeError(f"frozen tuning winner gate failed: missing={missing} extra={extra}")
    if any(row.get("selected") != "true" for row in winners):
        raise RuntimeError("non-selected row found in frozen winners")
    for grid in ("python_tuning_grid.csv", "jnormcorre_tuning_grid.csv", "moco_tuning_grid.csv"):
        counts = Counter(row["method_id"] for row in read_csv(protocol / grid))
        if max(counts.values()) > 128:
            raise RuntimeError(f"tuning budget exceeded in {grid}: {dict(counts)}")

    power = read_csv(protocol / "power_simulation.csv")
    if len(power) != 1 or power[0].get("decision") != "adequate_at_30" \
            or float(power[0]["simulated_power"]) < 0.8:
        raise RuntimeError("power gate did not approve 30 publication sources")

    audit_integration(project, included)
    freeze_selector_state(project, protocol / "frozen_config/automatic_selector_state.json")

    hash_targets = [
        v3 / "PLAN.md", protocol / "README.md", protocol / "hypotheses.md",
        protocol / "amendments.md",
        protocol / "conditions.csv", protocol / "metrics.md",
        protocol / "analysis_plan.md", protocol / "method_manifest.csv",
        protocol / "source_manifest.csv", protocol / "publication_member_selection.csv",
        protocol / "publication_download_manifest.csv",
        protocol / "publication_source_selection.md",
        protocol / "publication_test_source_validation.csv",
        protocol / "integration_source_validation.csv",
        protocol / "comparator_dispositions.csv", protocol / "tuning_subset.csv",
        protocol / "python_tuning_grid.csv", protocol / "jnormcorre_tuning_grid.csv",
        protocol / "moco_tuning_grid.csv", protocol / "frozen_config/candidate_scores.csv",
        protocol / "tuning_spaces.csv",
        protocol / "failure_handling_validation.csv",
        protocol / "frozen_config/winners.csv",
        protocol / "frozen_config/primary_comparator_selection.csv",
        protocol / "frozen_config/selector_baseline_selection.csv",
        protocol / "frozen_config/automatic_selector_state.json",
        protocol / "power_simulation.csv", protocol / "power_simulation.md",
        protocol / "carry_forward.csv",
        project / "scripts/benchmark_v3_python.py",
        project / "scripts/benchmark_v3_jnormcorre.py",
        project / "scripts/analyze_benchmark_v3.py",
        project / "scripts/freeze_benchmark_v3_configs.py",
        project / "scripts/freeze_benchmark_v3_protocol.py",
        project / "scripts/fetch_benchmark_v3_sources.ps1",
        project / "scripts/validate_benchmark_v3_sources.py",
        project / "scripts/validate_benchmark_v3_failure_handling.py",
        project / "scripts/power_simulation_v3.py",
        project / "scripts/run_benchmark_v3.ps1",
        project / "scripts/run_benchmark_v3_tuning.ps1",
        project / "scripts/run_benchmark_v3_frozen_methods.ps1",
        project / "scripts/run_external_parameter_sweep.ps1",
        project / "library/benchmark/run_external_full_no_dialog.groovy",
        project / "scripts/execute_benchmark_v3_publication.ps1",
        project / "scripts/keep_benchmark_awake.ps1",
        v3 / "comparators/python/environment-lock.txt",
        v3 / "comparators/jnormcorre/environment-lock.txt",
        project / "src/test/java/ripr/BenchmarkV3.java",
        project / "src/test/java/ripr/ExternalPluginComparisonStacks.java",
        project / "src/test/java/ripr/MocoBenchmarkV3.java",
    ]
    missing_targets = [str(path) for path in hash_targets if not path.is_file()]
    if missing_targets:
        raise FileNotFoundError("freeze hash inputs missing: " + ", ".join(missing_targets))
    hashes = protocol / "frozen_protocol_hashes.csv"
    with hashes.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=("relative_path", "sha256", "bytes"))
        writer.writeheader()
        for path in sorted(hash_targets):
            writer.writerow({"relative_path": path.relative_to(project).as_posix(),
                             "sha256": sha256(path), "bytes": path.stat().st_size})

    try:
        git_head = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=project, text=True).strip()
        git_dirty = bool(subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=project, text=True).strip())
    except (OSError, subprocess.CalledProcessError):
        git_head = "unavailable"
        git_dirty = True
    environment = protocol / "frozen_environment.txt"
    environment.write_text(
        f"frozen_utc={datetime.now(timezone.utc).isoformat()}\n"
        f"git_head={git_head}\ngit_worktree_dirty={str(git_dirty).lower()}\n"
        f"platform={platform.platform()}\npython={sys.version.replace(os.linesep, ' ')}\n"
        f"processor={platform.processor()}\nlogical_cpus={os.cpu_count()}\n",
        encoding="utf-8",
    )
    environment_digest = sha256(environment)
    gate.write_text(
        "REGISTRATION BENCHMARK V3 PROTOCOL FROZEN\n"
        f"frozen_utc={datetime.now(timezone.utc).isoformat()}\n"
        "publication_sources=30\nimage_types=5\nsources_per_type=6\n"
        f"included_method_rows={len(included)}\nexcluded_method_rows={len(methods) - len(included)}\n"
        f"frozen_configurations={len(winners)}\n"
        f"git_head={git_head}\ngit_worktree_dirty={str(git_dirty).lower()}\n"
        f"hash_manifest_sha256={sha256(hashes)}\n"
        f"environment_sha256={environment_digest}\n"
        "test_access_before_freeze=false\n",
        encoding="utf-8",
    )
    print(f"PROTOCOL FROZEN: {gate}")


if __name__ == "__main__":
    main()
