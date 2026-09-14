#!/usr/bin/env python3
"""Prospective whole-source power simulation for v3 H1 using pilot-only v2 data."""

from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from pathlib import Path

import numpy as np


OURS = "1_current_category_recommendation"
EXTERNAL_CANDIDATES = (
    "21_real_stackreg_turboreg_translation_chain",
    "22_real_turboreg_translation_multilag_rcc",
    "24_real_image_stabilizer_lucas_kanade_rolling_template",
    "25_real_fast4dreg_nanoj_previous_frame",
    "27_real_correct_3d_drift_phase_correlation_standard",
    "29_real_linear_stack_alignment_sift_translation_chain",
    "32_real_descriptor_based_series_translation",
)
EPSILON = 1e-6
FAILURE_PENALTY_PX = 45.0


def rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def error(row: dict[str, str] | None) -> float:
    if row is None or row.get("status") != "ok":
        return FAILURE_PENALTY_PX
    try:
        value = float(row["median_error_px"])
    except (KeyError, TypeError, ValueError):
        return FAILURE_PENALTY_PX
    return value if math.isfinite(value) else FAILURE_PENALTY_PX


def index(values: list[dict[str, str]]) -> dict[tuple[str, str, str], dict[str, str]]:
    return {(row["series_id"], row["motion_category"], row["method_id"]): row
            for row in values}


def source_degradation(clean: dict[tuple[str, str, str], dict[str, str]],
                       stressed: dict[tuple[str, str, str], dict[str, str]],
                       source: str, method: str, motions: list[str]) -> float:
    values = []
    for motion in motions:
        baseline = error(clean.get((source, motion, method)))
        condition = error(stressed.get((source, motion, method)))
        values.append(math.log((condition + EPSILON) / (baseline + EPSILON)))
    return float(np.mean(values))


def stratified_bootstrap_upper(effect: np.ndarray, strata: np.ndarray,
                               generator: np.random.Generator, draws: int) -> float:
    samples = np.empty(draws, dtype=np.float64)
    unique = sorted(set(strata.tolist()))
    for draw in range(draws):
        picked = []
        for stratum in unique:
            pool = effect[strata == stratum]
            picked.extend(generator.choice(pool, size=len(pool), replace=True))
        samples[draw] = np.mean(picked)
    return float(np.quantile(samples, 0.975))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--outer", type=int, default=2000)
    parser.add_argument("--inner", type=int, default=500)
    args = parser.parse_args()
    project = args.project.resolve()
    clean_path = project / ("library/benchmark/v2/benchmarks/controlled_motion/summaries/"
                            "external_comparison_v1/all_methods_by_recording.csv")
    gain_path = project / ("library/benchmark/v2/benchmarks/controlled_motion_gain_fade/"
                           "summaries/external_comparison_v1/all_methods_by_recording.csv")
    clean_rows = rows(clean_path)
    gain_rows = rows(gain_path)
    clean = index(clean_rows)
    gain = index(gain_rows)
    source_type = {row["series_id"]: row["image_series_class"] for row in clean_rows}
    sources = sorted(source_type)
    motions = sorted({row["motion_category"] for row in clean_rows})
    if len(sources) != 20 or len(motions) != 4:
        raise RuntimeError(f"pilot audit expected 20 sources x 4 motions; got {len(sources)} x {len(motions)}")

    ours = {source: source_degradation(clean, gain, source, OURS, motions)
            for source in sources}
    candidates: dict[str, dict[str, float]] = {}
    complete_candidates: list[str] = []
    for method in EXTERNAL_CANDIDATES:
        candidates[method] = {
            source: source_degradation(clean, gain, source, method, motions)
            for source in sources
        }
        expected_keys = [(source, motion, method) for source in sources for motion in motions]
        if all(clean.get(key, {}).get("status") == "ok"
               and gain.get(key, {}).get("status") == "ok" for key in expected_keys):
            complete_candidates.append(method)
    # Select on pilot data only. Equal weighting arises from four sources in every image stratum.
    if not complete_candidates:
        raise RuntimeError("no external pilot comparator completed both clean and gain panels")
    comparator = min(complete_candidates,
                     key=lambda method: np.mean(list(candidates[method].values())))
    observed = np.asarray([ours[source] - candidates[comparator][source] for source in sources])
    strata = np.asarray([source_type[source] for source in sources])
    observed_mean = float(np.mean(observed))
    shrunken = observed - observed_mean + 0.5 * observed_mean

    generator = np.random.default_rng(20260820)
    types = sorted(set(strata.tolist()))
    successes = 0
    study_means = np.empty(args.outer, dtype=np.float64)
    for study in range(args.outer):
        sampled_effects = []
        sampled_strata = []
        for image_type in types:
            pool = shrunken[strata == image_type]
            selected = generator.choice(pool, size=6, replace=True)
            sampled_effects.extend(selected)
            sampled_strata.extend([image_type] * 6)
        sample = np.asarray(sampled_effects)
        sample_strata = np.asarray(sampled_strata)
        study_means[study] = float(np.mean(sample))
        if stratified_bootstrap_upper(sample, sample_strata, generator, args.inner) < 0:
            successes += 1
    power = successes / args.outer

    output = project / "library/benchmark/v3/protocol/power_simulation.csv"
    with output.open("w", newline="", encoding="utf-8") as handle:
        fields = ["hypothesis", "pilot_sources", "target_sources", "sources_per_type",
                  "external_comparator", "observed_mean_log_difference", "shrinkage",
                  "shrunken_mean_log_difference", "outer_simulations", "inner_bootstraps",
                  "simulated_power", "target_power", "decision", "seed",
                  "failure_penalty_px", "clean_input", "stressed_input"]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerow({
            "hypothesis": "H1_multiplicative_gain", "pilot_sources": len(sources),
            "target_sources": 30, "sources_per_type": 6,
            "external_comparator": comparator,
            "observed_mean_log_difference": f"{observed_mean:.9f}", "shrinkage": "0.5",
            "shrunken_mean_log_difference": f"{float(np.mean(shrunken)):.9f}",
            "outer_simulations": args.outer, "inner_bootstraps": args.inner,
            "simulated_power": f"{power:.9f}", "target_power": "0.8",
            "decision": "adequate_at_30" if power >= 0.8 else "add_sources_before_freeze",
            "seed": 20260820, "failure_penalty_px": FAILURE_PENALTY_PX,
            "clean_input": clean_path.relative_to(project).as_posix(),
            "stressed_input": gain_path.relative_to(project).as_posix(),
        })
    report = output.with_suffix(".md")
    report.write_text(
        "# Prospective v3 power simulation\n\n"
        f"Pilot-only v2 source clusters: {len(sources)} (four per image type).  Target: 30 "
        "publication sources (six per type).\n\n"
        f"The best complete independent pilot comparator was `{comparator}`. The observed "
        f"source-level mean log-degradation difference (ours minus comparator) was "
        f"{observed_mean:.4f}; the simulation shrank this effect by 50% to "
        f"{float(np.mean(shrunken)):.4f}.\n\n"
        f"Across {args.outer} stratified whole-source studies, each evaluated with a "
        f"{args.inner}-draw stratified cluster bootstrap, estimated power was {power:.3f}. "
        f"Decision: **{'adequate at n=30' if power >= 0.8 else 'add sources before freeze'}**.\n\n"
        "This is a design calculation, not confirmatory evidence. Failed pilot trials receive "
        f"the frozen {FAILURE_PENALTY_PX:g} px planning penalty.\n",
        encoding="utf-8",
    )
    print(f"v3 power simulation: comparator={comparator} power={power:.3f}; {output}")
    if power < 0.8:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
