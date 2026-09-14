"""Combine completed per-channel registration benchmarks into one concise table."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def shown(value: str) -> str:
    return "NA" if value == "" else f"{float(value):.3f}"


def combine(named_runs: list[tuple[str, Path]], output: Path) -> list[dict]:
    rows = []
    per_image = []
    montages = []
    for channel, folder in named_runs:
        state = json.loads((folder / "run.json").read_text(encoding="utf-8"))
        if state.get("status") != "complete":
            raise ValueError(f"benchmark is not complete: {folder}")
        for summary in read_csv(folder / "results" / "summary.csv"):
            rows.append({
                "channel": channel,
                "image_type": state["image_type"],
                "motion_type": state["motion_type"],
                "method": summary["method"],
                "method_label": summary["method_label"],
                "guide_error_px": summary["guide_residual_px"],
                "median_time_seconds": summary["median_time_seconds"],
                "images_scored": summary["scored_images"],
                "images_total": summary["images"],
                "failed_images": summary["failed_images"],
            })
        for image in read_csv(folder / "results" / "per_image.csv"):
            per_image.append({"channel": channel, **image})
        montage_manifest = folder / "results" / "montage_audit_manifest.csv"
        if montage_manifest.is_file():
            for montage in read_csv(montage_manifest):
                montages.append({
                    "channel": channel,
                    "image_type": state["image_type"],
                    "motion_type": state["motion_type"],
                    **montage,
                })

    groups = list(dict.fromkeys(
        (row["channel"], row["motion_type"]) for row in rows
    ))
    for channel, motion in groups:
        channel_rows = [row for row in rows
                        if row["channel"] == channel and row["motion_type"] == motion]
        measured = [row for row in channel_rows if row["method"] != "original"]
        by_error = sorted((row for row in measured if row["guide_error_px"] != ""),
                          key=lambda row: float(row["guide_error_px"]))
        by_time = sorted((row for row in measured if row["median_time_seconds"] != ""),
                         key=lambda row: float(row["median_time_seconds"]))
        error_rank = {id(row): rank for rank, row in enumerate(by_error, 1)}
        time_rank = {id(row): rank for rank, row in enumerate(by_time, 1)}
        for row in channel_rows:
            row["error_rank_in_channel"] = "" if id(row) not in error_rank else error_rank[id(row)]
            row["time_rank_in_channel"] = "" if id(row) not in time_rank else time_rank[id(row)]

    write_csv(output / "channel_method_summary.csv", rows)
    write_csv(output / "channel_method_per_image.csv", per_image)
    if montages:
        write_csv(output / "montage_index.csv", montages)
    lines = [
        "# Registration by channel", "",
        "No motion was added. Error is remaining movement relative to registered image 1 and is a guide; inspect the scrolling TIFFs.",
    ]
    by_key = {(row["channel"], row["motion_type"], row["method"]): row for row in rows}
    channels = list(dict.fromkeys(channel for channel, _path in named_runs))
    method_order = list(dict.fromkeys(row["method"] for row in rows))
    for channel in channels:
        available = [row for row in rows if row["channel"] == channel]
        image_type = available[0]["image_type"].replace("_", " ").lower()
        motions = [motion for motion in (
            "INTERMITTENT_JUMPS", "STEADY_DIRECTIONAL_DRIFT"
        ) if any(row["motion_type"] == motion for row in available)]
        totals = {
            motion: max(int(row["images_total"]) for row in available
                        if row["motion_type"] == motion)
            for motion in motions
        }
        lines.extend([
            "", f"## {channel} ({image_type})", "",
            "| Method | " + " | ".join(
                f"{'Jumps' if motion == 'INTERMITTENT_JUMPS' else 'Drift'} error (px) | "
                f"{'Jumps' if motion == 'INTERMITTENT_JUMPS' else 'Drift'} time (s) | "
                f"Failed/{totals[motion]}"
                for motion in motions
            ) + " |",
            "|---|" + "---:|---:|---:|" * len(motions),
        ])
        for method in method_order:
            values = [by_key[(channel, motion, method)] for motion in motions]
            cells = " | ".join(
                f"{shown(row['guide_error_px'])} | {shown(row['median_time_seconds'])} | "
                f"{row['failed_images']}" for row in values
            )
            lines.append(f"| {values[0]['method_label']} | {cells} |")
    (output / "RESULTS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return rows


def parse_run(value: str) -> tuple[str, Path]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("use CHANNEL=PATH")
    channel, path = value.split("=", 1)
    return channel, Path(path).resolve()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="append", type=parse_run, required=True,
                        help="Repeat CHANNEL=PATH for every completed channel")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        combine(args.run, args.output.resolve())
    except ValueError as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
