"""Folder batches matching the ImageJ batch plugin's naming and failure isolation."""

from __future__ import annotations

import csv
from dataclasses import dataclass
import math
from pathlib import Path
import time
from typing import Callable

from .io import register_file
from .parameters import LogRatioParameters
from .types import Recipe


@dataclass(frozen=True)
class BatchItem:
    input_path: Path
    output_path: Path
    status: str
    elapsed_seconds: float
    residual_before: float | None = None
    residual_after: float | None = None
    recipe: str = ""
    error: str = ""
    rotation_mode: str = ""
    rotation_events: str = ""
    rotation_event_window: int | None = None
    rotation_event_results: str = ""


@dataclass(frozen=True)
class BatchResult:
    items: tuple[BatchItem, ...]
    report_path: Path

    @property
    def completed(self) -> int:
        return sum(item.status == "completed" for item in self.items)

    @property
    def skipped(self) -> int:
        return sum(item.status == "skipped" for item in self.items)

    @property
    def errors(self) -> int:
        return sum(item.status == "error" for item in self.items)


def discover(input_directory: str | Path, recursive: bool = False) -> list[Path]:
    root = Path(input_directory)
    iterator = root.rglob("*") if recursive else root.glob("*")
    return sorted(
        path for path in iterator
        if path.is_file() and path.name.lower().endswith((".tif", ".tiff", ".ome.tif", ".ome.tiff"))
        and not path.stem.lower().endswith("_registered")
    )


def register_batch(
    input_directory: str | Path,
    output_directory: str | Path,
    parameters: LogRatioParameters | None = None,
    *,
    recursive: bool = False,
    overwrite: bool = False,
    progress: Callable[[int, int, Path], None] | None = None,
    backend: str | None = None,
    recipe: Recipe | str = Recipe.LANDMARKS,
    channel: int = 1,
    longitudinal: bool = True,
    **advanced: object,
) -> BatchResult:
    """Register each TIFF independently; expert parameter fields are accepted as keywords."""
    input_root, output_root = Path(input_directory), Path(output_directory)
    files = discover(input_root, recursive)
    items: list[BatchItem] = []
    for index, source in enumerate(files):
        relative = source.relative_to(input_root)
        output_name = source.stem + "_registered.tif"
        target = output_root / relative.parent / output_name
        if progress:
            progress(index, len(files), source)
        started = time.perf_counter()
        if target.exists() and not overwrite:
            items.append(BatchItem(source, target, "skipped", 0.0))
            continue
        try:
            result = register_file(
                source, target, parameters, backend=backend, recipe=recipe,
                channel=channel, longitudinal=longitudinal, **advanced,
            )
            items.append(
                BatchItem(
                    source,
                    target,
                    "completed",
                    time.perf_counter() - started,
                    result.median_residual_before,
                    result.median_residual_after,
                    result.parameters.recipe_provenance,
                    rotation_mode=result.parameters.rotation_mode.value,
                    rotation_events=",".join(
                        str(value) for value in result.parameters.rotation_event_frames
                    ),
                    rotation_event_window=result.parameters.rotation_event_window,
                    rotation_event_results=_event_results(result),
                )
            )
        except Exception as error:  # one bad stack must not abort a folder batch
            items.append(BatchItem(source, target, "error", time.perf_counter() - started, error=str(error)))
    output_root.mkdir(parents=True, exist_ok=True)
    report_path = output_root / "log_ratio_batch_report.csv"
    with report_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow((
            "input", "output", "status", "elapsed_seconds", "residual_before",
            "residual_after", "recipe", "error", "rotation_mode", "rotation_events",
            "rotation_event_window", "rotation_event_results",
        ))
        for item in items:
            writer.writerow((
                item.input_path,
                item.output_path,
                item.status,
                f"{item.elapsed_seconds:.6f}",
                "" if item.residual_before is None else item.residual_before,
                "" if item.residual_after is None else item.residual_after,
                item.recipe,
                item.error,
                item.rotation_mode,
                item.rotation_events,
                "" if item.rotation_event_window is None else item.rotation_event_window,
                item.rotation_event_results,
            ))
    if progress:
        progress(len(files), len(files), input_root)
    return BatchResult(tuple(items), report_path)


def _event_results(result) -> str:
    rotations = result.registration.event_rotations
    if rotations is None:
        return ""
    return ";".join(
        "|".join((
            str(event.public_frame),
            f"{math.degrees(event.delta_theta):.6f}",
            f"{math.degrees(event.cumulative_theta):.6f}",
            str(event.candidate_pairs),
            str(event.usable_pairs),
            str(event.inlier_pairs),
            f"{math.degrees(event.circular_mad):.6f}",
            event.status.name,
        ))
        for event in rotations.events
    )
