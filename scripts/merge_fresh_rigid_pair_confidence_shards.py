#!/usr/bin/env python3
"""Merge the four A13 confidence shards with exact coverage and uniqueness checks."""

from __future__ import annotations

import csv
import argparse
from pathlib import Path


EXPECTED_FIXTURES = 1701
EXPECTED_THRESHOLDS = 11
SHARDS = 4


def key(row: dict[str, str]) -> tuple[str, ...]:
    return (row["image_series_class"], row["series_id"], row["source_frame"],
            row["motion_category"], row["condition"], row["arm"], row["recipe_id"])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix", default="r03_category_confidence_shard")
    parser.add_argument("--destination", default="r04_merged_category_confidence")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    root = (project / "library" / "rigid_selector_tuning" / "fresh_recovery"
            / "pair_benchmark" / "runs" / "development")
    sources = [root / f"{args.prefix}_{index:02d}" / "results.csv"
               for index in range(1, SHARDS + 1)]
    destination = root / args.destination / "results.csv"
    destination.parent.mkdir(parents=True, exist_ok=True)
    seen: set[tuple[str, ...]] = set()
    policies: set[str] = set()
    header: list[str] | None = None
    rows = 0
    with destination.open("w", newline="", encoding="utf-8") as output:
        writer = None
        for source in sources:
            with source.open(newline="", encoding="utf-8") as handle:
                reader = csv.DictReader(handle)
                if header is None:
                    header = list(reader.fieldnames or [])
                    writer = csv.DictWriter(output, fieldnames=header)
                    writer.writeheader()
                elif list(reader.fieldnames or []) != header:
                    raise RuntimeError(f"header drift in {source}")
                assert writer is not None
                for row in reader:
                    identity = key(row)
                    if identity in seen:
                        raise RuntimeError(f"duplicate result {identity}")
                    if row["arm"] != "JOINT_RIGID":
                        raise RuntimeError(f"unexpected confidence arm {row['arm']}")
                    seen.add(identity)
                    policies.add(row["recipe_id"])
                    writer.writerow(row)
                    rows += 1
    expected = EXPECTED_FIXTURES * EXPECTED_THRESHOLDS
    if rows != expected:
        raise RuntimeError(f"expected {expected} rows, merged {rows}")
    if len(policies) != EXPECTED_THRESHOLDS:
        raise RuntimeError(f"expected {EXPECTED_THRESHOLDS} thresholds, found {len(policies)}")
    print(f"merged {rows:,} unique confidence rows into {destination}")


if __name__ == "__main__":
    main()
