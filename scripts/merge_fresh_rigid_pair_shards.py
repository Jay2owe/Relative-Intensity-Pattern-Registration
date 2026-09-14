#!/usr/bin/env python3
"""Merge the A12 category control and eight recipe shards with strict uniqueness checks."""

from __future__ import annotations

import csv
from pathlib import Path


EXPECTED_FIXTURES = 1701
EXPECTED_RECIPES = 129
EXPECTED_ARMS = 2


def key(row: dict[str, str]) -> tuple[str, ...]:
    return (row["image_series_class"], row["series_id"], row["source_frame"],
            row["motion_category"], row["condition"], row["arm"], row["recipe_id"])


def main() -> None:
    project = Path(__file__).resolve().parents[1]
    root = (project / "library" / "rigid_selector_tuning" / "fresh_recovery"
            / "pair_benchmark" / "runs" / "development")
    sources = [root / "r01_corrected_full_factorial" / "results.csv"]
    sources.extend(root / f"r01_corrected_full_factorial_shard_{index:02d}" / "results.csv"
                   for index in range(1, 9))
    destination = root / "r02_merged_full_factorial" / "results.csv"
    destination.parent.mkdir(parents=True, exist_ok=True)
    seen: set[tuple[str, ...]] = set()
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
                    seen.add(identity)
                    writer.writerow(row)
                    rows += 1
    expected = EXPECTED_FIXTURES * EXPECTED_RECIPES * EXPECTED_ARMS
    if rows != expected:
        raise RuntimeError(f"expected {expected} rows, merged {rows}")
    print(f"merged {rows:,} unique rows into {destination}")


if __name__ == "__main__":
    main()
