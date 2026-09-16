"""TIFF/OME-TIFF entry points."""

from __future__ import annotations

from pathlib import Path
import json

import numpy as np
import tifffile

from .parameters import LogRatioParameters
from .registration import LogRatioResult, infer_axes, register
from .types import Recipe


def read_tiff(path: str | Path) -> tuple[np.ndarray, str]:
    """Read the first TIFF series and return pixels with its axis declaration."""
    path = Path(path)
    with tifffile.TiffFile(path) as tif:
        series = tif.series[0]
        image = series.asarray()
        axes = infer_axes(image, series.axes)
    return image, axes


def write_tiff(path: str | Path, image: np.ndarray, axes: str, *, metadata: dict | None = None) -> None:
    """Write a TIFF whose axis order can be read back unambiguously."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    description = {"axes": axes, "log_ratio_registration": metadata or {}}
    # Plain TYX output is broadly compatible; higher-dimensional data gets OME axis metadata.
    ome = set(axes).issubset(set("TCZYX")) and len(axes) > 3
    tifffile.imwrite(
        path,
        image,
        ome=ome,
        metadata={"axes": axes} if ome else None,
        description=None if ome else json.dumps(description),
        photometric="minisblack",
    )


def register_file(
    input_path: str | Path,
    output_path: str | Path | None = None,
    parameters: LogRatioParameters | None = None,
    *,
    backend: str | None = None,
    recipe: Recipe | str = Recipe.LANDMARKS,
    channel: int = 1,
    longitudinal: bool = True,
    **advanced: object,
) -> LogRatioResult:
    """Register one TIFF/OME-TIFF; only ``input_path`` is required.

    Prefer :func:`ripr.register` for new code; this name remains as a compatibility alias.
    ``output_path`` chooses where the corrected TIFF is written; when omitted it is derived beside
    the input. Expert :class:`LogRatioParameters` fields may be supplied directly as keyword
    arguments.
    """
    input_path = Path(input_path)
    image, axes = read_tiff(input_path)
    result = register(
        image, parameters, axes=axes, backend=backend, recipe=recipe,
        channel=channel, longitudinal=longitudinal, **advanced,
    )
    if output_path is None:
        output_path = input_path.with_name(input_path.stem + "_registered.tif")
    transforms = [
        {"frame": index + 1, "dx": item.dx, "dy": item.dy, "theta": item.theta}
        for index, item in enumerate(result.transforms)
    ]
    write_tiff(
        output_path,
        result.corrected,
        result.axes,
        metadata={
            "recipe": result.parameters.recipe_provenance,
            "median_residual_before": result.median_residual_before,
            "median_residual_after": result.median_residual_after,
            "transforms": transforms,
        },
    )
    return result
