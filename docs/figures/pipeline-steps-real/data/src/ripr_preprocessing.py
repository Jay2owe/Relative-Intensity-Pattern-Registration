"""Estimation-only preprocessing and area-preserving downscaling."""

from __future__ import annotations

import numpy as np
from scipy import ndimage

from .types import Preprocessing


def _separable(image: np.ndarray, kernel: tuple[float, ...]) -> np.ndarray:
    values = np.nan_to_num(image, nan=0.0).astype(np.float64, copy=False)
    valid = np.isfinite(image).astype(np.float64)
    weights = np.asarray(kernel, dtype=np.float64)
    for axis in (1, 0):
        values = ndimage.convolve1d(values, weights, axis=axis, mode="nearest")
        valid = ndimage.convolve1d(valid, weights, axis=axis, mode="nearest")
        values = np.divide(values, valid, out=np.zeros_like(values), where=valid > 0)
        valid = (valid > 0).astype(np.float64)
    return np.where(valid > 0, values, np.nan).astype(np.float32)


def apply_preprocessing(image: np.ndarray, choice: Preprocessing | str) -> np.ndarray:
    """Apply the same temporary estimation filters as the ImageJ plugin."""
    choice = Preprocessing.parse(choice)
    source = np.asarray(image, dtype=np.float32)
    if source.ndim != 2:
        raise ValueError("preprocessing expects one 2-D plane")
    if choice is Preprocessing.NONE:
        return source.copy()
    if choice is Preprocessing.GAUSSIAN_0_7:
        return _separable(source, (1, 2, 1))
    if choice is Preprocessing.GAUSSIAN_1_0:
        return _separable(source, (1, 4, 6, 4, 1))
    if choice is Preprocessing.GAUSSIAN_1_4:
        return _separable(_separable(source, (1, 4, 6, 4, 1)), (1, 4, 6, 4, 1))
    if choice is Preprocessing.MEDIAN_3X3:
        padded = np.pad(source, 1, mode="edge")
        windows = np.lib.stride_tricks.sliding_window_view(padded, (3, 3))
        return np.nanmedian(windows, axis=(-2, -1)).astype(np.float32)
    if choice is Preprocessing.ANSCOMBE:
        return (2.0 * np.sqrt(np.maximum(0, source) + 3.0 / 8.0)).astype(np.float32)
    if choice is Preprocessing.ANSCOMBE_GAUSSIAN_1_0:
        stabilized = 2.0 * np.sqrt(np.maximum(0, source) + 3.0 / 8.0)
        return _separable(stabilized.astype(np.float32), (1, 4, 6, 4, 1))
    if choice is Preprocessing.UNSHARP_0_5:
        blurred = _separable(source, (1, 4, 6, 4, 1))
        return np.maximum(0, source + 0.5 * (source - blurred)).astype(np.float32)
    raise AssertionError(choice)


def area_average(image: np.ndarray, scale: float) -> np.ndarray:
    """Exact covered-area average used by the Java reduced-resolution adapter."""
    source = np.asarray(image, dtype=np.float32)
    if source.ndim != 2:
        raise ValueError("area_average expects one 2-D plane")
    if not 0 < scale <= 1:
        raise ValueError("scale must be in (0, 1]")
    if scale == 1:
        return source.copy()
    source_height, source_width = source.shape
    output_width = max(1, int(np.floor(source_width * scale)))
    output_height = max(1, int(np.floor(source_height * scale)))
    if output_width < 32 or output_height < 32:
        raise ValueError(
            f"estimation scale {scale} leaves only {output_width} x {output_height} pixels; "
            "use a scale leaving at least 32 x 32"
        )
    output = np.empty((output_height, output_width), dtype=np.float32)
    for y in range(output_height):
        top, bottom = y / scale, min(source_height, (y + 1) / scale)
        y0, y1 = int(np.floor(top)), min(source_height - 1, int(np.ceil(bottom)) - 1)
        for x in range(output_width):
            left, right = x / scale, min(source_width, (x + 1) / scale)
            x0, x1 = int(np.floor(left)), min(source_width - 1, int(np.ceil(right)) - 1)
            total = weight = 0.0
            for sy in range(y0, y1 + 1):
                wy = min(bottom, sy + 1) - max(top, sy)
                for sx in range(x0, x1 + 1):
                    value = float(source[sy, sx])
                    if not np.isfinite(value):
                        continue
                    w = wy * (min(right, sx + 1) - max(left, sx))
                    total += w * value
                    weight += w
            output[y, x] = total / weight if weight > 0 else np.nan
    return output
