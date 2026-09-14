"""Pre-run channel registrability diagnostics from the Java plugin."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .parameters import LogRatioParameters
from .registration import _estimation_frames, infer_axes


WARN_BELOW = 0.05


def correlation(a: np.ndarray, b: np.ndarray) -> float:
    """Pearson correlation over pixels finite in both planes."""
    a, b = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    valid = np.isfinite(a) & np.isfinite(b)
    if np.count_nonzero(valid) < 2:
        return float("nan")
    av, bv = a[valid], b[valid]
    av, bv = av - np.mean(av), bv - np.mean(bv)
    denominator = np.sqrt(np.dot(av, av) * np.dot(bv, bv))
    return float(np.dot(av, bv) / denominator) if denominator > 0 else float("nan")


def localisability(frames: np.ndarray) -> float:
    """Mean correlation loss caused by a one-pixel displacement on both axes."""
    stack = np.asarray(frames)
    if stack.ndim != 3:
        raise ValueError("frames must have shape (T, Y, X)")
    values = []
    for first, second in zip(stack[:-1], stack[1:]):
        at = correlation(first, second)
        shifted_x = np.pad(second[:, 1:], ((0, 0), (0, 1)), mode="edge")
        shifted_y = np.pad(second[1:, :], ((0, 1), (0, 0)), mode="edge")
        sx, sy = correlation(first, shifted_x), correlation(first, shifted_y)
        if np.isfinite(at) and np.isfinite(sx) and np.isfinite(sy):
            values.append(at - 0.5 * (sx + sy))
    return float(np.mean(values)) if values else float("nan")


def frame_correlation(frames: np.ndarray) -> float:
    stack = np.asarray(frames)
    values = [correlation(a, b) for a, b in zip(stack[:-1], stack[1:])]
    values = [value for value in values if np.isfinite(value)]
    return float(np.mean(values)) if values else float("nan")


@dataclass(frozen=True)
class ChannelQuality:
    channel: int
    localisability: float
    frame_correlation: float

    @property
    def poor(self) -> bool:
        return np.isfinite(self.localisability) and self.localisability < WARN_BELOW


def rank_channels(image: np.ndarray, axes: str | None = None) -> tuple[ChannelQuality, ...]:
    """Score every channel and return best localisability first."""
    source = np.asarray(image)
    normalized_axes = infer_axes(source, axes)
    count = source.shape[normalized_axes.index("C")] if "C" in normalized_axes else 1
    results = []
    for channel in range(1, count + 1):
        frames = _estimation_frames(source, normalized_axes, LogRatioParameters.manual(channel=channel))
        results.append(ChannelQuality(channel, localisability(frames), frame_correlation(frames)))
    return tuple(sorted(results, key=lambda value: (not np.isfinite(value.localisability), -value.localisability if np.isfinite(value.localisability) else 0, value.channel)))
