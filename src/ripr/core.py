"""Native NumPy implementation of the Java plugin's registration core."""

from __future__ import annotations

from dataclasses import dataclass, field, replace
import math
from typing import Callable, Iterable, Sequence

import numpy as np
from scipy import ndimage

from .types import (
    Estimator,
    IDENTITY,
    Interpolation,
    PixelSupport,
    Reference,
    ReconciliationWeighting,
    RepairReason,
    RobustNorm,
    RotationEventStatus,
    RotationMode,
    Status,
    Transform,
    robust_scale,
)


_EXP2_MIN = -16.0
_EXP2_STEPS = 512
_EXP2_TABLE = np.asarray(
    [2.0 ** (_EXP2_MIN + i / _EXP2_STEPS) for i in range(48 * _EXP2_STEPS + 1)],
    dtype=np.float64,
)


def _fast_exp2(value: np.ndarray) -> np.ndarray:
    value = np.asarray(value, dtype=np.float64)
    position = (value - _EXP2_MIN) * _EXP2_STEPS
    lower = position.astype(np.int64)
    inside = (lower >= 0) & (lower + 1 < _EXP2_TABLE.size)
    safe = np.clip(lower, 0, _EXP2_TABLE.size - 2)
    fraction = position - lower
    interpolated = _EXP2_TABLE[safe] + fraction * (_EXP2_TABLE[safe + 1] - _EXP2_TABLE[safe])
    return np.where(inside, interpolated, np.exp2(value))


def _java_round(value):
    """Java Math.round, including its asymmetric negative half handling."""
    return np.floor(np.asarray(value) + 0.5).astype(np.int64)


def _order_statistic(values: np.ndarray, index: int) -> float:
    values = np.asarray(values, dtype=np.float64).ravel()
    return float(np.partition(values, index)[index])


def percentile(values: np.ndarray, q: float) -> float:
    finite = np.asarray(values)[np.isfinite(values)].astype(np.float64)
    if finite.size == 0:
        return math.nan
    k = int(math.floor((q / 100.0) * (finite.size - 1) + 0.5))
    return _order_statistic(finite, min(finite.size - 1, max(0, k)))


class LogPlane:
    """One pyramid plane as log2(intensity + epsilon), gradients, and validity."""

    def __init__(self, value: np.ndarray, valid: np.ndarray):
        self.value = np.asarray(value, dtype=np.float32)
        self.valid = np.asarray(valid, dtype=bool)
        if self.value.ndim != 2 or self.value.shape != self.valid.shape:
            raise ValueError("value and valid must be equal-size 2-D arrays")
        self.height, self.width = self.value.shape
        self.valid_count = int(np.count_nonzero(self.valid))
        self.gx, self.gy = self._gradients()
        self._raw: np.ndarray | None = None
        self._raw_gradient: np.ndarray | None = None
        self._raw_noise_sigma: float | None = None
        self._area_window: _AreaWindow | None = None

    @classmethod
    def from_intensity(
        cls,
        intensity: np.ndarray,
        epsilon: float = 1.0,
        floor: float = -math.inf,
        saturation_max: float = math.inf,
    ) -> "LogPlane":
        if epsilon <= 0:
            raise ValueError(f"epsilon must be > 0, was {epsilon}")
        image = np.asarray(intensity, dtype=np.float32)
        if image.ndim != 2:
            raise ValueError("intensity must be a 2-D plane")
        shifted = image.astype(np.float64) + epsilon
        valid = np.isfinite(image) & (image >= floor) & (image < saturation_max) & (shifted > 0)
        values = np.zeros(image.shape, dtype=np.float32)
        values[valid] = np.log2(shifted[valid]).astype(np.float32)
        return cls(values, valid)

    @staticmethod
    def auto_levels(width: int, height: int, min_size: int = 48, max_levels: int = 4) -> int:
        levels = 1
        while levels < max_levels and width // 2 >= min_size and height // 2 >= min_size:
            width //= 2
            height //= 2
            levels += 1
        return levels

    @staticmethod
    def levels_for_shift(max_shift: float, radius_budget: int) -> int:
        levels, radius = 1, abs(max_shift)
        while radius > radius_budget and levels < 16:
            radius /= 2
            levels += 1
        return levels

    def pyramid(self, levels: int, linear: bool = False) -> list["LogPlane"]:
        if levels < 1:
            raise ValueError("levels must be >= 1")
        result = [self]
        for _ in range(1, levels):
            result.append(result[-1]._halve(linear))
        return result

    def _halve(self, linear: bool) -> "LogPlane":
        if self.width // 2 < 1 or self.height // 2 < 1:
            raise ValueError(f"cannot halve a {self.width}x{self.height} plane")
        source = np.exp2(self.value, dtype=np.float32) if linear else self.value
        blurred, blurred_valid = self._blur5(source, self.valid)
        small = blurred[: (self.height // 2) * 2 : 2, : (self.width // 2) * 2 : 2].copy()
        valid = blurred_valid[: (self.height // 2) * 2 : 2, : (self.width // 2) * 2 : 2].copy()
        if linear:
            valid &= small > 0
            small[valid] = np.log2(small[valid]).astype(np.float32)
            small[~valid] = 0
        return LogPlane(small, valid)

    @staticmethod
    def _blur5(values: np.ndarray, valid: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Separable [1 4 6 4 1]/16 with border replication, renormalised over valid weight.

        The five taps are accumulated one at a time, in the order -2, -1, 0, +1, +2, entirely in
        float32. That is not a stylistic choice: it is the order and the precision Java's blur5
        uses, and a single-precision sum is not associative, so a library convolution that reduces
        the same five products in a different order lands one unit in the last place away. One ULP
        at the coarsest pyramid level is enough to start the solver's search from a different point
        and move the final transform by ~0.002 px, which is what this reproduces exactly.

        Java skips an invalid neighbour rather than adding it. Adding a float32 zero is bit-identical
        to skipping (x + 0.0f == x, and the running sum starts at +0.0), so the masked adds below are
        equivalent to the branch without needing one.
        """
        height, width = values.shape
        values = np.ascontiguousarray(values, dtype=np.float32)
        taps = ((-2, 1.0), (-1, 4.0), (0, 6.0), (1, 4.0), (2, 1.0))
        zero = np.float32(0.0)

        columns = np.arange(width)
        row_sum = np.zeros((height, width), dtype=np.float32)
        row_weight = np.zeros((height, width), dtype=np.float32)
        for offset, weight in taps:
            index = np.clip(columns + offset, 0, width - 1)
            neighbour_ok = valid[:, index]
            tap = np.float32(weight)
            row_sum += np.where(neighbour_ok, tap * values[:, index], zero)
            row_weight += np.where(neighbour_ok, tap, zero)
        horizontal = np.divide(
            row_sum, row_weight, out=np.zeros_like(row_sum), where=row_weight > 0
        ).astype(np.float32)
        share = (row_weight / np.float32(16.0)).astype(np.float32)

        rows = np.arange(height)
        column_sum = np.zeros((height, width), dtype=np.float32)
        column_weight = np.zeros((height, width), dtype=np.float32)
        for offset, weight in taps:
            index = np.clip(rows + offset, 0, height - 1)
            neighbour_share = share[index, :]
            usable = neighbour_share > 0
            tap = (np.float32(weight) * neighbour_share).astype(np.float32)
            column_sum += np.where(usable, tap * horizontal[index, :], zero)
            column_weight += np.where(usable, tap, zero)
        output = np.divide(
            column_sum, column_weight, out=np.zeros_like(column_sum), where=column_weight > 0
        ).astype(np.float32)
        return output, (column_weight / np.float32(16.0)) >= np.float32(0.5)

    def _gradients(self) -> tuple[np.ndarray, np.ndarray]:
        gx = np.zeros_like(self.value)
        gy = np.zeros_like(self.value)
        for y in range(self.height):
            ym, yp = max(0, y - 1), min(self.height - 1, y + 1)
            for x in range(self.width):
                if not self.valid[y, x]:
                    continue
                xm, xp = max(0, x - 1), min(self.width - 1, x + 1)
                minus_ok, plus_ok = self.valid[y, xm] and xm != x, self.valid[y, xp] and xp != x
                if minus_ok and plus_ok:
                    gx[y, x] = np.float32(0.5 * (self.value[y, xp] - self.value[y, xm]))
                elif plus_ok:
                    gx[y, x] = self.value[y, xp] - self.value[y, x]
                elif minus_ok:
                    gx[y, x] = self.value[y, x] - self.value[y, xm]
                minus_ok, plus_ok = self.valid[ym, x] and ym != y, self.valid[yp, x] and yp != y
                if minus_ok and plus_ok:
                    gy[y, x] = np.float32(0.5 * (self.value[yp, x] - self.value[ym, x]))
                elif plus_ok:
                    gy[y, x] = self.value[yp, x] - self.value[y, x]
                elif minus_ok:
                    gy[y, x] = self.value[y, x] - self.value[ym, x]
        return gx, gy

    @property
    def gradient_magnitude(self) -> np.ndarray:
        return np.abs(self.gx) + np.abs(self.gy)

    def _ensure_raw(self) -> None:
        if self._raw is not None:
            return
        self._raw = np.where(self.valid, np.exp2(self.value), 0).astype(np.float32)
        self._raw_gradient = (
            self.gradient_magnitude * self._raw * math.log(2.0)
        ).astype(np.float32)

    @property
    def raw_gradient(self) -> np.ndarray:
        self._ensure_raw()
        assert self._raw_gradient is not None
        return self._raw_gradient

    @property
    def raw_noise_sigma(self) -> float:
        if self._raw_noise_sigma is not None:
            return self._raw_noise_sigma
        self._ensure_raw()
        assert self._raw is not None
        if self.height < 3 or self.width < 3:
            self._raw_noise_sigma = 1e-6
            return self._raw_noise_sigma
        centre_ok = (
            self.valid[1:-1, 1:-1]
            & self.valid[1:-1, :-2]
            & self.valid[1:-1, 2:]
            & self.valid[:-2, 1:-1]
            & self.valid[2:, 1:-1]
        )
        centre = self._raw[1:-1, 1:-1]
        neighbours = 0.25 * (
            self._raw[1:-1, :-2]
            + self._raw[1:-1, 2:]
            + self._raw[:-2, 1:-1]
            + self._raw[2:, 1:-1]
        )
        residual = (centre - neighbours)[centre_ok].astype(np.float64)
        if residual.size == 0:
            sigma = 1e-6
        else:
            median = _order_statistic(residual, residual.size // 2)
            sigma = robust_scale(residual - median, 1e-6) / math.sqrt(1.25)
        self._raw_noise_sigma = float(sigma)
        return self._raw_noise_sigma

    def sample(self, x: np.ndarray, y: np.ndarray, field: str = "value") -> tuple[np.ndarray, np.ndarray]:
        array = {"value": self.value, "gx": self.gx, "gy": self.gy}[field]
        x = np.asarray(x, dtype=np.float64)
        y = np.asarray(y, dtype=np.float64)
        inside = (x >= 0) & (y >= 0) & (x <= self.width - 1) & (y <= self.height - 1)
        safe_x = np.clip(x, 0, self.width - 1)
        safe_y = np.clip(y, 0, self.height - 1)
        x0 = safe_x.astype(np.int64)
        y0 = safe_y.astype(np.int64)
        x1 = np.minimum(x0 + 1, self.width - 1)
        y1 = np.minimum(y0 + 1, self.height - 1)
        ok = inside & self.valid[y0, x0] & self.valid[y0, x1] & self.valid[y1, x0] & self.valid[y1, x1]
        fx, fy = safe_x - x0, safe_y - y0
        top = array[y0, x0] + fx * (array[y0, x1] - array[y0, x0])
        bottom = array[y1, x0] + fx * (array[y1, x1] - array[y1, x0])
        # Java interpolates in double and returns a float: `return (float)(top + fy * (bot - top))`.
        # Keeping full float64 here leaves every sample up to a float32 ULP away from the value the
        # plugin fits against, and a least-squares solve over ~86,000 such samples turns that into a
        # visible shift in the final transform. Round once, exactly where Java rounds.
        interpolated = (top + fy * (bottom - top)).astype(np.float32)
        return interpolated.astype(np.float64), ok


@dataclass
class AlignerOptions:
    levels: int = 0
    max_levels: int = 4
    min_coarse_size: int = 48
    max_shift: float = 30.0
    coarse_radius_budget: int = 8
    norm: RobustNorm = RobustNorm.HUBER
    profile_gain: bool = True
    support: PixelSupport = PixelSupport.ALL
    gradient_fraction: float = 0.5
    fit_rotation: bool = False
    max_rotation: float = math.radians(10.0)
    max_iterations: int = 25
    convergence: float = 1e-3
    min_valid_fraction: float = 0.10
    max_samples: int = 200_000
    scale_floor: float = 1e-4

    def levels_for(self, width: int, height: int) -> int:
        wanted = self.levels if self.levels > 0 else LogPlane.auto_levels(
            width, height, self.min_coarse_size, self.max_levels
        )
        needed = LogPlane.levels_for_shift(self.max_shift, self.coarse_radius_budget)
        capped = LogPlane.auto_levels(width, height, 8, max(wanted, needed))
        return max(1, min(capped, max(wanted, needed)))


@dataclass(frozen=True)
class PairUncertainty:
    """Pair-transform covariance in ``[dx, dy[, theta]]`` physical units."""

    dimensions: int
    _covariance: np.ndarray | None = field(default=None, repr=False)
    peak_ambiguity: float = math.nan
    calibrated: bool = False
    eigenvalue_floored: bool = False
    eigenvalue_capped: bool = False

    def __post_init__(self) -> None:
        if self.dimensions not in (2, 3):
            raise ValueError("uncertainty dimensions must be 2 or 3")
        if self._covariance is None:
            return
        matrix = np.asarray(self._covariance, dtype=np.float64)
        if matrix.shape != (self.dimensions, self.dimensions):
            raise ValueError("covariance shape does not match dimensions")
        matrix = 0.5 * (matrix + matrix.T)
        if not np.all(np.isfinite(matrix)):
            raise ValueError("covariance must be finite")
        try:
            np.linalg.cholesky(matrix)
        except np.linalg.LinAlgError as error:
            raise ValueError("covariance must be positive definite") from error
        matrix = matrix.copy()
        matrix.flags.writeable = False
        object.__setattr__(self, "_covariance", matrix)

    @classmethod
    def unavailable(cls, dimensions: int) -> "PairUncertainty":
        return cls(dimensions)

    @classmethod
    def from_covariance(
        cls,
        covariance: np.ndarray,
        *,
        peak_ambiguity: float = math.nan,
        calibrated: bool = False,
        floored: bool = False,
        capped: bool = False,
    ) -> "PairUncertainty":
        matrix = np.asarray(covariance, dtype=np.float64)
        if matrix.ndim != 2 or matrix.shape[0] != matrix.shape[1]:
            raise ValueError("covariance must be square")
        return cls(matrix.shape[0], matrix, peak_ambiguity, calibrated, floored, capped)

    @property
    def available(self) -> bool:
        return self._covariance is not None

    @property
    def covariance(self) -> np.ndarray | None:
        return None if self._covariance is None else self._covariance.copy()

    @property
    def information(self) -> np.ndarray | None:
        return None if self._covariance is None else np.linalg.inv(self._covariance)

    def inverse_for(self, forward: Transform) -> "PairUncertainty":
        if not self.available:
            return PairUncertainty.unavailable(self.dimensions)
        c, s = math.cos(forward.theta), math.sin(forward.theta)
        if self.dimensions == 2:
            jacobian = np.asarray([[-c, -s], [s, -c]])
        else:
            jacobian = np.asarray([
                [-c, -s, s * forward.dx - c * forward.dy],
                [s, -c, c * forward.dx + s * forward.dy],
                [0, 0, -1],
            ])
        assert self._covariance is not None
        return PairUncertainty.from_covariance(
            jacobian @ self._covariance @ jacobian.T,
            peak_ambiguity=self.peak_ambiguity,
            calibrated=self.calibrated,
            floored=self.eigenvalue_floored,
            capped=self.eigenvalue_capped,
        )

    def scale_translations(self, factor: float) -> "PairUncertainty":
        if not self.available:
            return PairUncertainty.unavailable(self.dimensions)
        if not math.isfinite(factor) or factor <= 0:
            raise ValueError("translation scale must be finite and positive")
        scale = np.diag([factor, factor] + ([1.0] if self.dimensions == 3 else []))
        assert self._covariance is not None
        return PairUncertainty.from_covariance(
            scale @ self._covariance @ scale,
            peak_ambiguity=self.peak_ambiguity,
            calibrated=self.calibrated,
            floored=self.eigenvalue_floored,
            capped=self.eigenvalue_capped,
        )

    @classmethod
    def bounded(
        cls,
        covariance: np.ndarray,
        *,
        radius: float,
        peak_ambiguity: float = math.nan,
        calibrated: bool = False,
    ) -> "PairUncertainty":
        matrix = np.asarray(covariance, dtype=np.float64)
        dimensions = matrix.shape[0]
        if matrix.shape != (dimensions, dimensions) or dimensions not in (2, 3):
            return cls.unavailable(dimensions if dimensions in (2, 3) else 2)
        scale = np.diag([1.0, 1.0] + ([radius] if dimensions == 3 else []))
        equivalent = 0.5 * (scale @ matrix @ scale + (scale @ matrix @ scale).T)
        try:
            values, vectors = np.linalg.eigh(equivalent)
        except np.linalg.LinAlgError:
            return cls.unavailable(dimensions)
        if not np.all(np.isfinite(values)) or np.min(values) < -1e-8:
            return cls.unavailable(dimensions)
        lower, upper = 0.02**2, 20.0**2
        floored, capped = bool(np.any(values < lower)), bool(np.any(values > upper))
        values = np.clip(values, lower, upper)
        bounded_equivalent = (vectors * values) @ vectors.T
        inverse_scale = np.linalg.inv(scale)
        try:
            return cls.from_covariance(
                inverse_scale @ bounded_equivalent @ inverse_scale,
                peak_ambiguity=peak_ambiguity,
                calibrated=calibrated,
                floored=floored,
                capped=capped,
            )
        except ValueError:
            return cls.unavailable(dimensions)


def _rms_radius(width: int, height: int) -> float:
    return math.sqrt(max(1.0, (width * width + height * height - 2) / 12.0))


def _estimate_log_uncertainty(
    a: LogPlane, b: LogPlane, transform: Transform, options: AlignerOptions
) -> PairUncertainty:
    dimensions = 3 if options.fit_rotation else 2
    x, y, _ = _grid(a, options)
    source_ok = a.valid[y, x]
    x, y = x[source_ok], y[source_ok]
    mapped_x, mapped_y = transform.apply(x, y, (a.width - 1) / 2, (a.height - 1) / 2)
    values, ok = b.sample(mapped_x, mapped_y)
    gx, gx_ok = b.sample(mapped_x, mapped_y, "gx")
    gy, gy_ok = b.sample(mapped_x, mapped_y, "gy")
    ok &= gx_ok & gy_ok
    x, y, values, gx, gy = x[ok], y[ok], values[ok], gx[ok], gy[ok]
    if values.size < dimensions + 2:
        return PairUncertainty.unavailable(dimensions)
    residual = values - a.value[y, x]
    gain = _order_statistic(residual, residual.size // 2) if options.profile_gain else 0.0
    residual = residual - gain
    threshold = options.norm.threshold(robust_scale(residual, options.scale_floor))
    weight = options.norm.weights(residual, threshold)
    columns = [gx, gy]
    if dimensions == 3:
        cx, cy = (a.width - 1) / 2, (a.height - 1) / 2
        sin_t, cos_t = math.sin(transform.theta), math.cos(transform.theta)
        u, v = x - cx, y - cy
        columns.append(gx * (-u * sin_t - v * cos_t) + gy * (u * cos_t - v * sin_t))
    jacobian = np.column_stack(columns)
    total = float(np.sum(weight))
    if not total > dimensions + 1:
        return PairUncertainty.unavailable(dimensions)
    centred = jacobian - np.sum(jacobian * weight[:, None], axis=0) / total
    bread = centred.T @ (centred * weight[:, None])
    meat = centred.T @ (centred * np.square(weight * residual)[:, None])
    try:
        inverse = np.linalg.inv(bread)
    except np.linalg.LinAlgError:
        return PairUncertainty.unavailable(dimensions)
    covariance = inverse @ meat @ inverse
    return PairUncertainty.bounded(covariance, radius=_rms_radius(a.width, a.height))


@dataclass(frozen=True)
class PairFit:
    transform: Transform
    residual_before: float
    residual_after: float
    log_gain: float
    valid_fraction: float
    iterations: int
    status: Status
    uncertainty: PairUncertainty = field(default_factory=lambda: PairUncertainty.unavailable(2))

    @property
    def usable(self) -> bool:
        return self.status is not Status.REFUSED_LOW_OVERLAP

    @property
    def residual_removed(self) -> float:
        return 1.0 - self.residual_after / self.residual_before if self.residual_before > 0 else 0.0


@dataclass(frozen=True)
class _Sample:
    count: int
    possible: int
    gain: float
    mean_abs: float
    cost: float


def _stride_for(width: int, height: int, max_samples: int) -> int:
    pixels = width * height
    return 1 if max_samples <= 0 or pixels <= max_samples else max(1, math.ceil(math.sqrt(pixels / max_samples)))


def _grid(plane: LogPlane, options: AlignerOptions):
    stride = _stride_for(plane.width, plane.height, options.max_samples)
    y, x = np.mgrid[0 : plane.height : stride, 0 : plane.width : stride]
    return x.ravel(), y.ravel(), stride


def _evaluate(
    a: LogPlane,
    b: LogPlane,
    transform: Transform,
    options: AlignerOptions,
    threshold: float = 0.0,
    gradient_threshold: float = 0.0,
    solver_support: np.ndarray | None = None,
) -> _Sample:
    x, y, stride = _grid(a, options)
    if solver_support is not None:
        chosen = solver_support[::stride, ::stride].ravel()
        x, y = x[chosen], y[chosen]
    possible = x.size
    source_ok = a.valid[y, x]
    if gradient_threshold > 0:
        source_ok &= a.gradient_magnitude[y, x] >= gradient_threshold
    hard_mutual = threshold > 0 and options.support is PixelSupport.MUTUAL_NOISE_GRADIENT
    if hard_mutual:
        source_ok &= a.raw_gradient[y, x] >= options.gradient_fraction * a.raw_noise_sigma
    x, y = x[source_ok], y[source_ok]
    mapped_x, mapped_y = transform.apply(x, y, (a.width - 1) / 2, (a.height - 1) / 2)
    values, target_ok = b.sample(mapped_x, mapped_y)
    if hard_mutual and values.size:
        gx, gx_ok = b.sample(mapped_x, mapped_y, "gx")
        gy, gy_ok = b.sample(mapped_x, mapped_y, "gy")
        target_gradient = (np.abs(gx) + np.abs(gy)) * np.exp2(values) * math.log(2.0)
        target_ok &= gx_ok & gy_ok & (target_gradient >= options.gradient_fraction * b.raw_noise_sigma)
    values = values[target_ok]
    source_values = a.value[y[target_ok], x[target_ok]].astype(np.float64)
    residual = values - source_values
    if residual.size == 0:
        return _Sample(0, possible, 0.0, math.inf, math.inf)
    gain = _order_statistic(residual, residual.size // 2) if options.profile_gain else 0.0
    centred = residual - gain
    mean_abs = float(np.mean(np.abs(centred)))
    cost = float(np.mean(options.norm.rho(centred, threshold))) if threshold > 0 else mean_abs
    return _Sample(residual.size, possible, gain, mean_abs, cost)


def _clamp_shift(transform: Transform, max_shift: float) -> Transform:
    magnitude = transform.magnitude
    if magnitude <= max_shift or magnitude == 0:
        return transform
    factor = max_shift / magnitude
    return Transform(transform.dx * factor, transform.dy * factor, transform.theta)


def _clamp_transform(transform: Transform, max_shift: float, options: AlignerOptions) -> Transform:
    magnitude = transform.magnitude
    factor = max_shift / magnitude if magnitude > max_shift and magnitude != 0 else 1.0
    theta = transform.theta
    if options.fit_rotation and math.isfinite(options.max_rotation) and options.max_rotation >= 0:
        theta = max(-options.max_rotation, min(options.max_rotation, theta))
    if factor == 1.0 and theta == transform.theta:
        return transform
    return Transform(transform.dx * factor, transform.dy * factor, theta)


def _angular_candidates(max_rotation: float, half_diagonal: float) -> list[float]:
    """Zero first, then symmetric pairs out to both exact endpoints, matching Java."""
    if not max_rotation > 0 or not math.isfinite(max_rotation):
        return [0.0]
    wanted_step = 1.0 / max(1.0, half_diagonal)
    intervals = max(1, math.ceil(max_rotation / wanted_step))
    step = max_rotation / intervals
    output = [0.0]
    for index in range(1, intervals + 1):
        output.extend((-index * step, index * step))
    return output


def _gradient_threshold(plane: LogPlane, options: AlignerOptions) -> float:
    _, _, stride = _grid(plane, options)
    values = plane.gradient_magnitude[::stride, ::stride][plane.valid[::stride, ::stride]]
    if values.size == 0:
        return 0.0
    return _order_statistic(values, values.size // 2) * options.gradient_fraction


def _refine(
    a: LogPlane,
    b: LogPlane,
    start: Transform,
    max_shift: float,
    options: AlignerOptions,
    solver_support: np.ndarray | None = None,
) -> tuple[Transform, int, bool, bool]:
    gradient_threshold = _gradient_threshold(a, options) if options.support is PixelSupport.GRADIENT else 0.0
    hard_mutual = options.support is PixelSupport.MUTUAL_NOISE_GRADIENT
    x_all, y_all, stride = _grid(a, options)
    if solver_support is not None:
        selected_mask = solver_support[::stride, ::stride].ravel()
        x_all, y_all = x_all[selected_mask], y_all[selected_mask]
    possible = x_all.size
    transform = start
    converged = bailed = False
    iterations = 0
    cx, cy = (a.width - 1) / 2, (a.height - 1) / 2
    for iterations in range(options.max_iterations):
        source_ok = a.valid[y_all, x_all]
        if gradient_threshold > 0:
            source_ok &= a.gradient_magnitude[y_all, x_all] >= gradient_threshold
        if hard_mutual:
            source_ok &= a.raw_gradient[y_all, x_all] >= options.gradient_fraction * a.raw_noise_sigma
        x, y = x_all[source_ok], y_all[source_ok]
        mapped_x, mapped_y = transform.apply(x, y, cx, cy)
        bv, ok = b.sample(mapped_x, mapped_y)
        gx, gx_ok = b.sample(mapped_x, mapped_y, "gx")
        gy, gy_ok = b.sample(mapped_x, mapped_y, "gy")
        ok &= gx_ok & gy_ok
        if hard_mutual:
            target_gradient = (np.abs(gx) + np.abs(gy)) * np.exp2(bv) * math.log(2.0)
            ok &= target_gradient >= options.gradient_fraction * b.raw_noise_sigma
        x, y, bv, gx, gy = x[ok], y[ok], bv[ok], gx[ok], gy[ok]
        dof = 3 if options.fit_rotation else 2
        if bv.size < options.min_valid_fraction * possible or bv.size < dof + 1:
            bailed = True
            break
        difference = bv - a.value[y, x]
        gain = _order_statistic(difference, difference.size // 2) if options.profile_gain else 0.0
        residual = difference - gain
        threshold = options.norm.threshold(robust_scale(residual, options.scale_floor))
        weight = options.norm.weights(residual, threshold)
        columns = [gx, gy]
        if options.fit_rotation:
            sin_t, cos_t = math.sin(transform.theta), math.cos(transform.theta)
            u, v = x - cx, y - cy
            columns.append(gx * (-u * sin_t - v * cos_t) + gy * (u * cos_t - v * sin_t))
        jacobian = np.column_stack(columns)
        weighted = jacobian * weight[:, None]
        hessian = jacobian.T @ weighted
        gradient = weighted.T @ residual
        if np.count_nonzero(weight) < dof + 1:
            break
        try:
            step = np.linalg.solve(hessian, -gradient)
        except np.linalg.LinAlgError:
            break
        cost0 = float(np.mean(options.norm.rho(residual, threshold)))
        accepted = None
        factor = 1.0
        for _ in range(6):
            raw = Transform(
                transform.dx + factor * step[0],
                transform.dy + factor * step[1],
                transform.theta + (factor * step[2] if options.fit_rotation else 0),
            )
            trial = _clamp_transform(raw, max_shift, options)
            evaluation = _evaluate(a, b, trial, options, threshold, gradient_threshold, solver_support)
            if evaluation.count >= options.min_valid_fraction * evaluation.possible and evaluation.cost < cost0:
                accepted = trial
                break
            factor *= 0.5
        if accepted is None:
            converged = True
            break
        moved = math.hypot(accepted.dx - transform.dx, accepted.dy - transform.dy)
        moved += abs(accepted.theta - transform.theta) * max(cx, cy)
        transform = accepted
        if moved < options.convergence:
            converged = True
            break
    return transform, iterations, converged, bailed


def _report(
    a: Sequence[LogPlane],
    b: Sequence[LogPlane],
    options: AlignerOptions,
    transform: Transform,
    iterations: int,
    status: Status,
    *,
    area_correlation: bool = False,
) -> PairFit:
    before = _evaluate(a[0], b[0], IDENTITY, options)
    after = _evaluate(a[0], b[0], transform, options)
    valid_fraction = after.count / after.possible if after.possible else 0.0
    if status is Status.REFUSED_LOW_OVERLAP or valid_fraction < options.min_valid_fraction:
        return PairFit(
            IDENTITY, before.mean_abs, before.mean_abs, 0.0, valid_fraction, iterations,
            Status.REFUSED_LOW_OVERLAP,
            PairUncertainty.unavailable(3 if options.fit_rotation else 2),
        )
    uncertainty = (
        _estimate_area_uncertainty(a[0], b[0], transform, options)
        if area_correlation
        else _estimate_log_uncertainty(a[0], b[0], transform, options)
    )
    return PairFit(
        transform, before.mean_abs, after.mean_abs, after.gain, valid_fraction,
        iterations, status, uncertainty,
    )


def align_log_ratio(
    a: Sequence[LogPlane],
    b: Sequence[LogPlane],
    options: AlignerOptions,
    start: Transform | None = None,
    support: Sequence[np.ndarray] | None = None,
) -> PairFit:
    """Align a log-pyramid pair using the plugin's robust coarse-to-fine solver."""
    if len(a) != len(b) or not a:
        raise ValueError("pyramids must have the same positive number of levels")
    top = len(a) - 1
    top_scale = 1 << top
    if start is None:
        radius = math.ceil(options.max_shift / top_scale)
        max_here = options.max_shift / top_scale
        best_transform = IDENTITY
        best_eval = _evaluate(a[top], b[top], IDENTITY, options)
        best = best_eval.mean_abs if best_eval.count >= options.min_valid_fraction * best_eval.possible else math.inf
        angles = _angular_candidates(options.max_rotation, math.hypot(
            (a[top].width - 1) / 2, (a[top].height - 1) / 2
        )) if options.fit_rotation else [0.0]
        for theta in angles:
            for dy in range(-radius, radius + 1):
                for dx in range(-radius, radius + 1):
                    if (theta == 0 and dx == 0 and dy == 0) or math.hypot(dx, dy) > max_here + 1e-9:
                        continue
                    trial = Transform(dx, dy, theta)
                    result = _evaluate(a[top], b[top], trial, options)
                    if result.count >= options.min_valid_fraction * result.possible and result.mean_abs < best:
                        best, best_transform = result.mean_abs, trial
        transform = best_transform
    else:
        transform = _clamp_transform(
            start.scale_translation(1.0 / top_scale), options.max_shift / top_scale, options
        )
    status = Status.OK
    iterations = 0
    for level in range(top, -1, -1):
        scale = 1 << level
        level_support = None if support is None else support[level]
        transform, iterations, converged, bailed = _refine(
            a[level], b[level], transform, options.max_shift / scale, options, level_support
        )
        status = Status.REFUSED_LOW_OVERLAP if bailed else (Status.OK if converged else Status.NOT_CONVERGED)
        if level > 0:
            transform = _clamp_transform(
                transform.scale_translation(2.0), options.max_shift / (scale / 2), options
            )
    if status is Status.OK:
        shift_bound = transform.magnitude >= options.max_shift * (1 - 1e-9)
        rotation_bound = options.fit_rotation and options.max_rotation > 0 and (
            abs(transform.theta) >= options.max_rotation * (1 - 1e-9)
        )
        if shift_bound and rotation_bound:
            status = Status.AT_SHIFT_AND_ROTATION_BOUND
        elif shift_bound:
            status = Status.AT_SHIFT_BOUND
        elif rotation_bound:
            status = Status.AT_ROTATION_BOUND
    return _report(a, b, options, transform, iterations, status)


def align_log_ratio_fixed_angle(
    a: Sequence[LogPlane],
    b: Sequence[LogPlane],
    options: AlignerOptions,
    fixed_theta: float,
    support: Sequence[np.ndarray] | None = None,
) -> PairFit:
    """Exhaustively choose translation while holding one caller-supplied angle exactly."""
    if len(a) != len(b) or not a:
        raise ValueError("pyramids must have the same positive number of levels")
    if not math.isfinite(fixed_theta):
        raise ValueError("fixed angle must be finite")
    fixed = replace(options, fit_rotation=False)
    top = len(a) - 1
    top_scale = 1 << top
    radius = math.ceil(fixed.max_shift / top_scale)
    max_here = fixed.max_shift / top_scale
    transform = Transform(0.0, 0.0, fixed_theta)
    best_evaluation = _evaluate(a[top], b[top], transform, fixed)
    best = (
        best_evaluation.mean_abs
        if best_evaluation.count >= fixed.min_valid_fraction * best_evaluation.possible
        else math.inf
    )
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if (dx == 0 and dy == 0) or math.hypot(dx, dy) > max_here + 1e-9:
                continue
            trial = Transform(dx, dy, fixed_theta)
            evaluation = _evaluate(a[top], b[top], trial, fixed)
            if (
                evaluation.count >= fixed.min_valid_fraction * evaluation.possible
                and evaluation.mean_abs < best
            ):
                best, transform = evaluation.mean_abs, trial
    status = Status.OK
    iterations = 0
    for level in range(top, -1, -1):
        scale = 1 << level
        level_support = None if support is None else support[level]
        transform, iterations, converged, bailed = _refine(
            a[level], b[level], transform, fixed.max_shift / scale, fixed, level_support
        )
        status = (
            Status.REFUSED_LOW_OVERLAP
            if bailed
            else Status.OK if converged else Status.NOT_CONVERGED
        )
        if level > 0:
            transform = _clamp_transform(
                transform.scale_translation(2.0), fixed.max_shift / (scale / 2), fixed
            )
    if status is Status.OK and transform.magnitude >= fixed.max_shift * (1 - 1e-9):
        status = Status.AT_SHIFT_BOUND
    result = _report(a, b, fixed, transform, iterations, status)
    if result.usable and result.transform.theta != fixed_theta:
        result = replace(
            result,
            transform=Transform(result.transform.dx, result.transform.dy, fixed_theta),
        )
    return result


class _AreaWindow:
    """Centred intensity and cubic B-spline coefficients for area correlation."""

    POLE = math.sqrt(3.0) - 2.0

    def __init__(self, plane: LogPlane, stride: int):
        self.plane = plane
        self.height, self.width, self.stride = plane.height, plane.width, stride
        value = np.where(plane.valid, _fast_exp2(plane.value), 0).astype(np.float32)
        mean = float(np.sum(value, dtype=np.float64) / plane.valid_count) if plane.valid_count else 0.0
        value[plane.valid] -= np.float32(mean)
        self.value = value
        self.coefficients = self._prefilter(value)

    @classmethod
    def of(cls, plane: LogPlane, options: AlignerOptions) -> "_AreaWindow":
        stride = _stride_for(plane.width, plane.height, options.max_samples)
        cached = plane._area_window
        if cached is None:
            cached = cls(plane, stride)
            plane._area_window = cached
        if cached.stride == stride:
            return cached
        result = object.__new__(cls)
        result.plane, result.height, result.width = plane, plane.height, plane.width
        result.stride, result.value, result.coefficients = stride, cached.value, cached.coefficients
        return result

    @classmethod
    def _filter_line(cls, line: np.ndarray) -> None:
        count = line.size
        if count < 2:
            return
        horizon = min(count, math.ceil(math.log(1e-7) / math.log(abs(cls.POLE))))
        total, z = float(line[0]), cls.POLE
        for k in range(1, horizon):
            total += z * float(line[k])
            z *= cls.POLE
        line[0] = np.float32(total)
        for k in range(1, count):
            line[k] = np.float32(float(line[k]) + cls.POLE * float(line[k - 1]))
        line[-1] = np.float32(cls.POLE / (cls.POLE**2 - 1.0) * (float(line[-1]) + cls.POLE * float(line[-2])))
        for k in range(count - 2, -1, -1):
            line[k] = np.float32(cls.POLE * (float(line[k + 1]) - float(line[k])))

    @classmethod
    def _prefilter(cls, samples: np.ndarray) -> np.ndarray:
        result = samples.copy()
        for y in range(result.shape[0]):
            result[y] *= np.float32(6.0)
            cls._filter_line(result[y])
        for x in range(result.shape[1]):
            line = (result[:, x] * np.float32(6.0)).copy()
            cls._filter_line(line)
            result[:, x] = line
        return result

    @staticmethod
    def _weights(f: np.ndarray) -> np.ndarray:
        f2, f3, g = f * f, f * f * f, 1.0 - f
        return np.stack((g**3 / 6, (4 - 6 * f2 + 3 * f3) / 6, (1 + 3 * f + 3 * f2 - 3 * f3) / 6, f3 / 6))

    @staticmethod
    def _derivatives(f: np.ndarray) -> np.ndarray:
        f2, g = f * f, 1.0 - f
        return np.stack((-g * g / 2, (-12 * f + 9 * f2) / 6, (3 + 6 * f - 9 * f2) / 6, f2 / 2))

    def sample(self, x: np.ndarray, y: np.ndarray, gradient: bool = False, interior_only: bool = False):
        x, y = np.asarray(x, dtype=float), np.asarray(y, dtype=float)
        inside = (x >= 0) & (y >= 0) & (x <= self.width - 1) & (y <= self.height - 1)
        sx, sy = np.clip(x, 0, self.width - 1), np.clip(y, 0, self.height - 1)
        x0, y0 = sx.astype(int), sy.astype(int)
        interior = inside & (x0 >= 1) & (y0 >= 1) & (x0 + 2 <= self.width - 1) & (y0 + 2 <= self.height - 1)
        out = np.full(x.shape, np.nan, dtype=float)
        gx = np.full(x.shape, np.nan, dtype=float) if gradient else None
        gy = np.full(x.shape, np.nan, dtype=float) if gradient else None
        indices = np.flatnonzero(interior)
        if indices.size:
            xi, yi = x0[indices], y0[indices]
            wx, wy = self._weights(sx[indices] - xi), self._weights(sy[indices] - yi)
            dwx = self._derivatives(sx[indices] - xi) if gradient else None
            dwy = self._derivatives(sy[indices] - yi) if gradient else None
            accum = np.zeros(indices.size)
            accum_x = np.zeros(indices.size) if gradient else None
            accum_y = np.zeros(indices.size) if gradient else None
            valid = np.ones(indices.size, dtype=bool)
            for j in range(4):
                line = np.zeros(indices.size)
                dline = np.zeros(indices.size) if gradient else None
                for i in range(4):
                    yy, xx = yi - 1 + j, xi - 1 + i
                    valid &= self.plane.valid[yy, xx]
                    coefficient = self.coefficients[yy, xx]
                    line += wx[i] * coefficient
                    if gradient:
                        assert dline is not None and dwx is not None
                        dline += dwx[i] * coefficient
                accum += wy[j] * line
                if gradient:
                    assert accum_x is not None and accum_y is not None and dline is not None and dwy is not None
                    accum_x += wy[j] * dline
                    accum_y += dwy[j] * line
            good = indices[valid]
            out[good] = accum[valid]
            if gradient:
                assert gx is not None and gy is not None and accum_x is not None and accum_y is not None
                gx[good], gy[good] = accum_x[valid], accum_y[valid]
        if not interior_only and not gradient:
            border = inside & ~interior
            indices = np.flatnonzero(border)
            if indices.size:
                xi, yi = x0[indices], y0[indices]
                x1, y1 = np.minimum(xi + 1, self.width - 1), np.minimum(yi + 1, self.height - 1)
                valid = self.plane.valid[yi, xi] & self.plane.valid[yi, x1] & self.plane.valid[y1, xi] & self.plane.valid[y1, x1]
                fx, fy = sx[indices] - xi, sy[indices] - yi
                top = self.value[yi, xi] + fx * (self.value[yi, x1] - self.value[yi, xi])
                bottom = self.value[y1, xi] + fx * (self.value[y1, x1] - self.value[y1, xi])
                out[indices[valid]] = (top + fy * (bottom - top))[valid]
        return (out, gx, gy) if gradient else out


def _area_correlation(a: _AreaWindow, b: _AreaWindow, dx: float, dy: float, options: AlignerOptions) -> float:
    y, x = np.mgrid[0 : a.height : a.stride, 0 : a.width : a.stride]
    x, y = x.ravel(), y.ravel()
    possible = x.size
    ok_a = a.plane.valid[y, x]
    x, y = x[ok_a], y[ok_a]
    if dx == np.rint(dx) and dy == np.rint(dy):
        xx, yy = x + int(np.rint(dx)), y + int(np.rint(dy))
        ok = (xx >= 0) & (yy >= 0) & (xx < b.width) & (yy < b.height)
        values = np.full(x.shape, np.nan)
        good = np.flatnonzero(ok)
        if good.size:
            valid = b.plane.valid[yy[good], xx[good]]
            values[good[valid]] = b.value[yy[good[valid]], xx[good[valid]]]
    else:
        values = b.sample(x + dx, y + dy)
    valid = np.isfinite(values)
    if np.count_nonzero(valid) < options.min_valid_fraction * possible:
        return math.nan
    av, bv = a.value[y[valid], x[valid]].astype(float), values[valid]
    av, bv = av - np.mean(av), bv - np.mean(bv)
    denominator = math.sqrt(float(av @ av) * float(bv @ bv))
    return float(av @ bv / denominator) if denominator > 0 else math.nan


def _area_correlation_transform(
    a: _AreaWindow, b: _AreaWindow, transform: Transform, options: AlignerOptions
) -> float:
    if transform.theta == 0:
        return _area_correlation(a, b, transform.dx, transform.dy, options)
    y, x = np.mgrid[0 : a.height : a.stride, 0 : a.width : a.stride]
    x, y = x.ravel(), y.ravel()
    possible = x.size
    ok_a = a.plane.valid[y, x]
    x, y = x[ok_a], y[ok_a]
    mapped_x, mapped_y = transform.apply(
        x, y, (a.width - 1) / 2, (a.height - 1) / 2
    )
    values = b.sample(mapped_x, mapped_y)
    valid = np.isfinite(values)
    if np.count_nonzero(valid) < options.min_valid_fraction * possible:
        return math.nan
    av, bv = a.value[y[valid], x[valid]].astype(float), values[valid]
    av, bv = av - np.mean(av), bv - np.mean(bv)
    denominator = math.sqrt(float(av @ av) * float(bv @ bv))
    return float(av @ bv / denominator) if denominator > 0 else math.nan


def _estimate_area_uncertainty(
    a: LogPlane, b: LogPlane, accepted: Transform, options: AlignerOptions
) -> PairUncertainty:
    dimensions = 3 if options.fit_rotation else 2
    wa, wb = _AreaWindow.of(a, options), _AreaWindow.of(b, options)
    radius = _rms_radius(a.width, a.height)
    centre = _area_correlation_transform(wa, wb, accepted, options)
    if not np.isfinite(centre):
        return PairUncertainty.unavailable(dimensions)

    def offset(transform: Transform, axis: int, amount: float) -> Transform:
        if axis == 0:
            return Transform(transform.dx + amount, transform.dy, transform.theta)
        if axis == 1:
            return Transform(transform.dx, transform.dy + amount, transform.theta)
        return Transform(transform.dx, transform.dy, transform.theta + amount / radius)

    def objective(transform: Transform) -> float:
        score = _area_correlation_transform(wa, wb, transform, options)
        return 1.0 - score if np.isfinite(score) else math.nan

    step = 0.25
    centre_objective = 1.0 - centre
    hessian = np.zeros((dimensions, dimensions), dtype=np.float64)
    for axis in range(dimensions):
        plus, minus = objective(offset(accepted, axis, step)), objective(offset(accepted, axis, -step))
        if not (np.isfinite(plus) and np.isfinite(minus)):
            return PairUncertainty.unavailable(dimensions)
        hessian[axis, axis] = (plus + minus - 2 * centre_objective) / step**2
    for row in range(dimensions):
        for column in range(row + 1, dimensions):
            values = (
                objective(offset(offset(accepted, row, step), column, step)),
                objective(offset(offset(accepted, row, step), column, -step)),
                objective(offset(offset(accepted, row, -step), column, step)),
                objective(offset(offset(accepted, row, -step), column, -step)),
            )
            if not all(np.isfinite(value) for value in values):
                return PairUncertainty.unavailable(dimensions)
            mixed = (values[0] - values[1] - values[2] + values[3]) / (4 * step**2)
            hessian[row, column] = hessian[column, row] = mixed
    try:
        inverse = np.linalg.inv(hessian)
        np.linalg.cholesky(0.5 * (inverse + inverse.T))
    except np.linalg.LinAlgError:
        return PairUncertainty.unavailable(dimensions)
    samples = max(dimensions + 2, min(a.valid_count, b.valid_count) // max(1, wa.stride**2))
    noise = max(1e-9, 2 * max(0.0, 1.0 - centre) / max(1, samples - dimensions))
    covariance = inverse * noise
    if dimensions == 3:
        physical = np.diag([1.0, 1.0, 1.0 / radius])
        covariance = physical @ covariance @ physical

    alternatives = []
    for dx, dy in ((2, 0), (-2, 0), (0, 2), (0, -2),
                   (2, 2), (2, -2), (-2, 2), (-2, -2)):
        score = _area_correlation_transform(
            wa, wb, Transform(accepted.dx + dx, accepted.dy + dy, accepted.theta), options
        )
        if np.isfinite(score):
            alternatives.append(score)
    if dimensions == 3:
        for sign in (-1, 1):
            score = _area_correlation_transform(
                wa, wb,
                Transform(accepted.dx, accepted.dy, accepted.theta + sign * 2 / radius),
                options,
            )
            if np.isfinite(score):
                alternatives.append(score)
    ambiguity = max(0.0, centre - max(alternatives)) if alternatives else math.nan
    return PairUncertainty.bounded(
        covariance, radius=radius, peak_ambiguity=ambiguity, calibrated=False
    )


def _area_score(a: _AreaWindow, b: _AreaWindow, dx: float, dy: float, options: AlignerOptions) -> float:
    """Newton line-search score: cubic interior only, matching AreaCorrelation.score."""
    y, x = np.mgrid[0 : a.height : a.stride, 0 : a.width : a.stride]
    x, y = x.ravel(), y.ravel()
    possible = x.size
    ok_a = a.plane.valid[y, x]
    x, y = x[ok_a], y[ok_a]
    values = b.sample(x + dx, y + dy, interior_only=True)
    valid = np.isfinite(values)
    count = np.count_nonzero(valid)
    if count < 8 or count < options.min_valid_fraction * possible:
        return math.nan
    av, bv = a.value[y[valid], x[valid]].astype(float), values[valid]
    av, bv = av - np.mean(av), bv - np.mean(bv)
    denominator = math.sqrt(float(av @ av) * float(bv @ bv))
    return float(av @ bv / denominator) if denominator > 0 else math.nan


def _area_newton(a: _AreaWindow, b: _AreaWindow, start: Transform, max_shift: float, options: AlignerOptions):
    x_shift, y_shift = start.dx, start.dy
    tolerance = max(options.convergence, 1 / 256)
    move, iterations = math.inf, 0
    for _ in range(max(1, min(options.max_iterations, 8))):
        y, x = np.mgrid[0 : a.height : a.stride, 0 : a.width : a.stride]
        x, y = x.ravel(), y.ravel()
        possible = x.size
        ok_a = a.plane.valid[y, x]
        x, y = x[ok_a], y[ok_a]
        values, gx, gy = b.sample(x + x_shift, y + y_shift, gradient=True, interior_only=True)
        valid = np.isfinite(values) & np.isfinite(gx) & np.isfinite(gy)
        if np.count_nonzero(valid) < max(8, options.min_valid_fraction * possible):
            return None
        av, bv, bx, by = a.value[y[valid], x[valid]].astype(float), values[valid], gx[valid], gy[valid]
        n = av.size
        sa, sb = av.sum(), bv.sum()
        ma, mb = sa / n, sb / n
        ac, bc = av - ma, bv - mb
        va, vb = float(ac @ ac), float(bc @ bc)
        if not (va > 0 and vb > 0):
            return None
        sig = math.sqrt(va * vb)
        corr = float(ac @ bc / sig)
        sbx, sby = bx.sum(), by.sum()
        px, py = float(ac @ bx), float(ac @ by)
        qx, qy = float(bc @ bx), float(bc @ by)
        ux = bx - sbx / n
        uy = by - sby / n
        hxx = (float(ux @ ux) - qx * qx / vb) / vb
        hyy = (float(uy @ uy) - qy * qy / vb) / vb
        hxy = (float(ux @ uy) - qx * qy / vb) / vb
        grad_x = px / sig - corr * qx / vb
        grad_y = py / sig - corr * qy / vb
        determinant = hxx * hyy - hxy * hxy
        if not (determinant > 0 and hxx > 0):
            break
        step_x = (hyy * grad_x - hxy * grad_y) / determinant
        step_y = (hxx * grad_y - hxy * grad_x) / determinant
        length = math.hypot(step_x, step_y)
        if not np.isfinite(length):
            break
        if length > 1:
            step_x, step_y, length = step_x / length, step_y / length, 1.0
        iterations += 1
        accepted = False
        scale = 1.0
        for _ in range(4):
            nx, ny = x_shift + scale * step_x, y_shift + scale * step_y
            if math.hypot(nx, ny) <= max_shift + 1e-9:
                trial = _area_score(a, b, nx, ny, options)
                if np.isfinite(trial) and trial >= corr:
                    x_shift, y_shift, accepted = nx, ny, True
                    break
            scale *= 0.5
        if not accepted:
            break
        move = scale * length
        if move < tolerance:
            break
    return Transform.translation(x_shift, y_shift), iterations, move < tolerance


def _area_ecc(a: _AreaWindow, b: _AreaWindow, start: Transform, max_shift: float, options: AlignerOptions):
    """Guarded Enhanced Correlation Coefficient refinement matching the Java estimator."""
    x_shift, y_shift = start.dx, start.dy
    tolerance = max(options.convergence, 1 / 256)
    move, iterations, accepted_steps = math.inf, 0, 0
    for _ in range(max(1, min(options.max_iterations, 8))):
        y, x = np.mgrid[0 : a.height : a.stride, 0 : a.width : a.stride]
        x, y = x.ravel(), y.ravel()
        possible = x.size
        ok_a = a.plane.valid[y, x]
        x, y = x[ok_a], y[ok_a]
        values, gx, gy = b.sample(x + x_shift, y + y_shift, gradient=True, interior_only=True)
        valid = np.isfinite(values) & np.isfinite(gx) & np.isfinite(gy)
        if np.count_nonzero(valid) < max(8, options.min_valid_fraction * possible):
            break
        av = a.value[y[valid], x[valid]].astype(float)
        bv, bx, by = values[valid], gx[valid], gy[valid]
        ac, bc = av - np.mean(av), bv - np.mean(bv)
        va, vb = float(ac @ ac), float(bc @ bc)
        if not (va > 0 and vb > 0):
            break
        ux, uy = bx - np.mean(bx), by - np.mean(by)
        hxx, hyy, hxy = float(ux @ ux), float(uy @ uy), float(ux @ uy)
        px, py = float(ac @ bx), float(ac @ by)
        qx, qy = float(bc @ bx), float(bc @ by)
        determinant = hxx * hyy - hxy * hxy
        if not (determinant > 0 and hxx > 0):
            break
        hq_x = (hyy * qx - hxy * qy) / determinant
        hq_y = (hxx * qy - hxy * qx) / determinant
        numerator = vb - qx * hq_x - qy * hq_y
        denominator = float(ac @ bc) - px * hq_x - py * hq_y
        if not (numerator > 0 and denominator > 0):
            break
        photometric_scale = numerator / denominator
        ex, ey = photometric_scale * px - qx, photometric_scale * py - qy
        step_x = (hyy * ex - hxy * ey) / determinant
        step_y = (hxx * ey - hxy * ex) / determinant
        length = math.hypot(step_x, step_y)
        if not np.isfinite(length):
            break
        if length > 1:
            step_x, step_y, length = step_x / length, step_y / length, 1.0
        corr = float(ac @ bc / math.sqrt(va * vb))
        iterations += 1
        accepted = False
        scale = 1.0
        for _ in range(4):
            nx, ny = x_shift + scale * step_x, y_shift + scale * step_y
            if math.hypot(nx, ny) <= max_shift + 1e-9:
                trial = _area_score(a, b, nx, ny, options)
                if np.isfinite(trial) and trial >= corr:
                    x_shift, y_shift, accepted = nx, ny, True
                    accepted_steps += 1
                    break
            scale *= 0.5
        if not accepted:
            break
        move = scale * length
        if move < tolerance:
            break
    if accepted_steps == 0:
        return None
    return Transform.translation(x_shift, y_shift), iterations, move < tolerance


def align_area(
    a: Sequence[LogPlane], b: Sequence[LogPlane], options: AlignerOptions,
    estimator: Estimator, start: Transform | None = None,
) -> PairFit:
    if options.fit_rotation:
        return _align_area_rigid(a, b, options, estimator, start)
    top = len(a) - 1
    transform = (
        IDENTITY if start is None
        else _clamp_shift(start.scale_translation(1.0 / (1 << top)),
                          options.max_shift / (1 << top))
    )
    found = start is not None
    for level in range(top, -1, -1):
        scale = 1 << level
        max_here = options.max_shift / scale
        wa, wb = _AreaWindow.of(a[level], options), _AreaWindow.of(b[level], options)
        radius = math.ceil(max_here) if level == top and start is None else 2
        cx, cy = float(np.rint(transform.dx)), float(np.rint(transform.dy))
        candidates = []
        for ddy in range(-radius, radius + 1):
            for ddx in range(-radius, radius + 1):
                dx, dy = cx + ddx, cy + ddy
                if math.hypot(dx, dy) <= max_here + 1e-9:
                    score = _area_correlation(wa, wb, dx, dy, options)
                    if np.isfinite(score):
                        candidates.append((score, dx, dy))
        if candidates:
            _, dx, dy = max(candidates, key=lambda item: item[0])
            transform, found = Transform.translation(dx, dy), True
        if level > 0:
            transform = _clamp_shift(transform.scale_translation(2), options.max_shift / (scale / 2))
    iterations, converged = 0, True
    if found:
        wa, wb = _AreaWindow.of(a[0], options), _AreaWindow.of(b[0], options)
        use_grid = estimator not in {
            Estimator.AREA_CORRELATION_NEWTON, Estimator.AREA_CORRELATION_ECC
        }
        if estimator is Estimator.AREA_CORRELATION_NEWTON:
            refined = _area_newton(wa, wb, transform, options.max_shift, options)
            if refined is not None:
                transform, iterations, converged = refined
        elif estimator is Estimator.AREA_CORRELATION_ECC:
            refined = _area_ecc(wa, wb, transform, options.max_shift, options)
            if refined is not None:
                transform, iterations, converged = refined
            else:
                use_grid = True
        if use_grid:
            step, move = 1.0, math.inf
            centre = _area_correlation(wa, wb, transform.dx, transform.dy, options)
            for iterations in range(max(1, min(options.max_iterations, 5))):
                candidates = []
                for j in (-1, 0, 1):
                    for i in (-1, 0, 1):
                        dx, dy = transform.dx + i * step, transform.dy + j * step
                        if math.hypot(dx, dy) <= options.max_shift + 1e-9:
                            score = _area_correlation(wa, wb, dx, dy, options)
                            if np.isfinite(score):
                                candidates.append((score, dx, dy))
                if not candidates:
                    break
                score, dx, dy = max(candidates, key=lambda item: item[0])
                if score > centre:
                    move = math.hypot(dx - transform.dx, dy - transform.dy)
                    transform, centre = Transform.translation(dx, dy), score
                else:
                    step *= 0.25
                if move < max(options.convergence, 1 / 256):
                    break
            converged = move < max(options.convergence, 1 / 256)
    status = Status.REFUSED_LOW_OVERLAP if not found else (
        Status.AT_SHIFT_BOUND if transform.magnitude >= options.max_shift * (1 - 1e-9)
        else Status.OK if converged else Status.NOT_CONVERGED
    )
    return _report(a, b, options, transform, iterations, status, area_correlation=True)


def _align_area_rigid(
    a: Sequence[LogPlane], b: Sequence[LogPlane], options: AlignerOptions,
    estimator: Estimator, start: Transform | None = None,
) -> PairFit:
    del estimator  # every rigid area variant shares the score-guarded 3-D refinement
    top = len(a) - 1
    transform = (
        IDENTITY if start is None
        else _clamp_transform(
            start.scale_translation(1.0 / (1 << top)),
            options.max_shift / (1 << top), options,
        )
    )
    found = start is not None
    for level in range(top, -1, -1):
        scale = 1 << level
        max_here = options.max_shift / scale
        wa, wb = _AreaWindow.of(a[level], options), _AreaWindow.of(b[level], options)
        best_score = _area_correlation_transform(wa, wb, transform, options)
        level_found = np.isfinite(best_score)
        winner = transform
        if level == top and start is None:
            radius = math.ceil(max_here)
            half_diagonal = math.hypot((wa.width - 1) / 2, (wa.height - 1) / 2)
            angles = _angular_candidates(options.max_rotation, half_diagonal)
            centre_x = centre_y = 0.0
            angular_offsets = angles
        else:
            radius = 2
            centre_x, centre_y = float(np.rint(transform.dx)), float(np.rint(transform.dy))
            angular_step = 1 / max(
                1.0, math.hypot((wa.width - 1) / 2, (wa.height - 1) / 2)
            )
            angular_offsets = [
                max(-options.max_rotation,
                    min(options.max_rotation, transform.theta + k * angular_step))
                for k in range(-2, 3)
            ]
        for theta in angular_offsets:
            for ddy in range(-radius, radius + 1):
                for ddx in range(-radius, radius + 1):
                    dx, dy = centre_x + ddx, centre_y + ddy
                    if level == top and theta == 0 and dx == 0 and dy == 0:
                        continue
                    if math.hypot(dx, dy) > max_here + 1e-9:
                        continue
                    candidate = Transform(dx, dy, theta)
                    score = _area_correlation_transform(wa, wb, candidate, options)
                    if np.isfinite(score) and (not level_found or score > best_score):
                        best_score, winner, level_found = score, candidate, True
        if level_found:
            transform, found = winner, True
        if level > 0:
            transform = _clamp_transform(
                transform.scale_translation(2), options.max_shift / (scale / 2), options
            )

    iterations, converged = 0, True
    if found:
        wa, wb = _AreaWindow.of(a[0], options), _AreaWindow.of(b[0], options)
        pixel_step, last_move = 1.0, math.inf
        half_diagonal = max(1.0, math.hypot((wa.width - 1) / 2, (wa.height - 1) / 2))
        for iterations in range(max(1, min(options.max_iterations, 5))):
            centre = _area_correlation_transform(wa, wb, transform, options)
            if not np.isfinite(centre):
                break
            winner, best_score = transform, centre
            angular_step = pixel_step / half_diagonal
            for kt in (-1, 0, 1):
                for ky in (-1, 0, 1):
                    for kx in (-1, 0, 1):
                        if kx == ky == kt == 0:
                            continue
                        candidate = _clamp_transform(
                            Transform(transform.dx + kx * pixel_step,
                                      transform.dy + ky * pixel_step,
                                      transform.theta + kt * angular_step),
                            options.max_shift, options,
                        )
                        score = _area_correlation_transform(wa, wb, candidate, options)
                        if np.isfinite(score) and score > best_score:
                            best_score, winner = score, candidate
            if winner is not transform:
                last_move = math.hypot(winner.dx - transform.dx, winner.dy - transform.dy)
                last_move += abs(winner.theta - transform.theta) * half_diagonal
                transform = winner
            else:
                pixel_step *= 0.25
        tolerance = max(options.convergence, 1 / 256)
        converged = last_move < tolerance or pixel_step < 1 / 256

    shift_bound = transform.magnitude >= options.max_shift * (1 - 1e-9)
    rotation_bound = options.max_rotation > 0 and (
        abs(transform.theta) >= options.max_rotation * (1 - 1e-9)
    )
    if not found:
        status = Status.REFUSED_LOW_OVERLAP
    elif shift_bound and rotation_bound:
        status = Status.AT_SHIFT_AND_ROTATION_BOUND
    elif shift_bound:
        status = Status.AT_SHIFT_BOUND
    elif rotation_bound:
        status = Status.AT_ROTATION_BOUND
    else:
        status = Status.OK if converged else Status.NOT_CONVERGED
    return _report(a, b, options, transform, iterations, status, area_correlation=True)


@dataclass
class RegistrationOptions:
    aligner: AlignerOptions = field(default_factory=AlignerOptions)
    estimator: Estimator = Estimator.LOG_RATIO_FIT
    rotation_mode: RotationMode = RotationMode.OFF
    rotation_event_frames: tuple[int, ...] = ()
    rotation_event_window: int = 3
    reference: Reference = Reference.CONSECUTIVE
    reference_frame: int = 0
    lags: tuple[int, ...] = (1, 2, 4, 8, 16)
    template_window: int = 5
    epsilon: float = 1.0
    intensity_floor: float = -math.inf
    intensity_floor_percentile: float = math.nan
    saturation_max: float = math.inf
    saturation_percentile: float = math.nan
    remove_offset: bool = False
    offset_percentile: float = 1.0
    outlier_mads: float = 8.0
    outlier_protection_residual_gain: float = math.nan
    threads: int = 0
    memory_budget_bytes: int = 0
    auto_max_shift: bool = False
    nearest_neighbor_rotation: bool = False
    reconciliation_weighting: ReconciliationWeighting = ReconciliationWeighting.EQUAL


@dataclass(frozen=True)
class PairInfluence:
    from_frame: int
    to_frame: int
    plan_index: int
    used: bool = False
    graph_residual: Transform = IDENTITY
    standardized_residual: float = math.nan
    robust_factor: float = 0.0
    final_information_scale: float = 0.0
    factor_floored: bool = False
    uncertainty_fallback: bool = False
    robust_converged: bool = True


@dataclass(frozen=True)
class PairResult:
    from_frame: int
    to_frame: int
    fit: PairFit
    influence: PairInfluence | None = None
    plan_index: int = -1

    @property
    def lag(self) -> int:
        return abs(self.to_frame - self.from_frame)


@dataclass(frozen=True)
class RotationEvent:
    """One zero-based first-post-remount event and its cross-pair evidence."""

    frame: int
    delta_theta: float
    cumulative_theta: float
    candidate_pairs: int
    usable_pairs: int
    inlier_pairs: int
    circular_mad: float
    first_pre_frame: int
    last_pre_frame: int
    first_post_frame: int
    last_post_frame: int
    status: RotationEventStatus

    @property
    def public_frame(self) -> int:
        return self.frame + 1


@dataclass(frozen=True)
class RotationEventResult:
    events: tuple[RotationEvent, ...]
    frame_angles: tuple[float, ...]

    @property
    def usable(self) -> bool:
        return all(event.status not in (
            RotationEventStatus.INSUFFICIENT_SUPPORT,
            RotationEventStatus.CANCELLED,
        ) for event in self.events)

    def require_usable(self) -> None:
        for event in self.events:
            if event.status is RotationEventStatus.INSUFFICIENT_SUPPORT:
                raise ValueError(
                    f"rotation event frame {event.public_frame} has {event.usable_pairs} "
                    "usable pair fits; at least 3 are required"
                )
            if event.status is RotationEventStatus.CANCELLED:
                raise RuntimeError("event rotation cancelled")


def _wrap_angle(angle: float) -> float:
    return math.atan2(math.sin(angle), math.cos(angle))


def _circular_median(values: np.ndarray, weights: np.ndarray) -> float:
    best, best_cost = float(values[0]), math.inf
    for candidate in values:
        cost = float(np.sum(weights * np.abs([
            _wrap_angle(float(value - candidate)) for value in values
        ])))
        if cost < best_cost:
            best, best_cost = float(candidate), cost
    return best


def _estimate_event_rotations(
    count: int,
    get: Callable[[int], Sequence[LogPlane]],
    options: RegistrationOptions,
    progress: Callable[[int, int], None] | None = None,
) -> RotationEventResult:
    events = tuple(int(value) for value in options.rotation_event_frames)
    previous = 0
    for event in events:
        if event < 1 or event >= count:
            raise ValueError(
                f"rotation event frame {event + 1} is outside public frame range 2..{count}"
            )
        if event <= previous:
            raise ValueError(
                f"rotation event frame {event + 1} must be unique and strictly increasing"
            )
        previous = event
    window = options.rotation_event_window
    if window < 1:
        raise ValueError("rotation event window must be at least 1")
    rigid = replace(options.aligner, fit_rotation=True)
    total = sum(min(window, event) * min(window, count - event) for event in events)
    done = 0
    cumulative = 0.0
    results: list[RotationEvent] = []
    for event in events:
        first_pre, last_pre = max(0, event - window), event - 1
        first_post, last_post = event, min(count - 1, event + window - 1)
        candidates: list[tuple[int, int, PairFit]] = []
        for source in range(first_pre, last_pre + 1):
            for target in range(first_post, last_post + 1):
                candidates.append((source, target, align_log_ratio(get(source), get(target), rigid)))
                done += 1
                if progress:
                    progress(done, total)
        usable = [candidate for candidate in candidates if (
            candidate[2].usable
            and math.isfinite(candidate[2].transform.theta)
            and candidate[2].status not in (
                Status.AT_ROTATION_BOUND,
                Status.AT_SHIFT_AND_ROTATION_BOUND,
            )
        )]
        delta = spread = math.nan
        inliers = 0
        status = RotationEventStatus.INSUFFICIENT_SUPPORT
        if len(usable) >= 3:
            angles = np.asarray([_wrap_angle(item[2].transform.theta) for item in usable])
            weights = np.asarray([
                max(1e-6, item[2].valid_fraction)
                / (event - item[0] + item[1] - event + 1)
                for item in usable
            ])
            centre = _circular_median(angles, weights)
            deviations = np.abs([_wrap_angle(float(value - centre)) for value in angles])
            spread = _order_statistic(deviations, deviations.size // 2)
            scale = max(1e-9, 1.4826 * spread)
            for _ in range(20):
                sine = cosine = 0.0
                for angle, weight in zip(angles, weights):
                    residual = _wrap_angle(float(angle - centre))
                    robust = 1.0 if abs(residual) <= 1.345 * scale else (
                        1.345 * scale / abs(residual)
                    )
                    sine += float(weight) * robust * math.sin(float(angle))
                    cosine += float(weight) * robust * math.cos(float(angle))
                next_centre = math.atan2(sine, cosine)
                if abs(_wrap_angle(next_centre - centre)) < 1e-12:
                    centre = next_centre
                    break
                centre = next_centre
            delta = _wrap_angle(centre)
            inliers = sum(
                abs(_wrap_angle(float(angle - delta))) <= 1.345 * scale + 1e-12
                for angle in angles
            )
            status = (
                RotationEventStatus.HIGH_DISAGREEMENT
                if spread > 0.25 * options.aligner.max_rotation
                else RotationEventStatus.OK
            )
            cumulative += delta
        results.append(RotationEvent(
            event, delta, cumulative, len(candidates), len(usable), inliers, spread,
            first_pre, last_pre, first_post, last_post, status,
        ))
    angles: list[float] = []
    cumulative = 0.0
    event_index = 0
    for frame in range(count):
        if event_index < len(results) and frame == results[event_index].frame:
            if math.isfinite(results[event_index].delta_theta):
                cumulative += results[event_index].delta_theta
            event_index += 1
        angles.append(cumulative)
    return RotationEventResult(tuple(results), tuple(angles))


@dataclass(frozen=True)
class RegistrationResult:
    cumulative: tuple[Transform, ...]
    pairs: tuple[PairResult, ...]
    support: np.ndarray
    repairs: tuple[RepairReason | None, ...]
    log2_gain: np.ndarray
    residual_before: np.ndarray
    residual_after: np.ndarray
    valid_fraction: np.ndarray
    status: tuple[Status | None, ...]
    warnings: tuple[str, ...]
    levels: int
    workers: int = 1
    pyramids_built: int = 0
    pyramid_cache_hits: int = 0
    event_rotations: RotationEventResult | None = None

    @property
    def median_residual_before(self) -> float:
        finite = self.residual_before[np.isfinite(self.residual_before)]
        return float(np.partition(finite, finite.size // 2)[finite.size // 2]) if finite.size else math.nan

    @property
    def median_residual_after(self) -> float:
        finite = self.residual_after[np.isfinite(self.residual_after)]
        return float(np.partition(finite, finite.size // 2)[finite.size // 2]) if finite.size else math.nan

    @property
    def unavailable_uncertainty_pairs(self) -> int:
        return sum(not pair.fit.uncertainty.available for pair in self.pairs)

    @property
    def covariance_floored_pairs(self) -> int:
        return sum(pair.fit.uncertainty.eigenvalue_floored for pair in self.pairs)

    @property
    def covariance_capped_pairs(self) -> int:
        return sum(pair.fit.uncertainty.eigenvalue_capped for pair in self.pairs)

    @property
    def downweighted_pairs(self) -> int:
        return sum(
            pair.influence is not None
            and pair.influence.used
            and pair.influence.robust_factor < 1.0 - 1e-12
            for pair in self.pairs
        )

    @property
    def robust_reconciliation_converged(self) -> bool:
        return all(
            pair.influence is None
            or not pair.influence.used
            or pair.influence.robust_converged
            for pair in self.pairs
        )

    def scale_translations(self, factor: float) -> "RegistrationResult":
        if not factor > 0 or factor == 1:
            return self
        cumulative = tuple(item.scale_translation(factor) for item in self.cumulative)
        pairs = tuple(
            PairResult(
                p.from_frame,
                p.to_frame,
                replace(
                    p.fit,
                    transform=p.fit.transform.scale_translation(factor),
                    uncertainty=p.fit.uncertainty.scale_translations(factor),
                ),
                p.influence if p.influence is None or not p.influence.used else replace(
                    p.influence,
                    graph_residual=p.influence.graph_residual.scale_translation(factor),
                ),
                p.plan_index,
            )
            for p in self.pairs
        )
        return replace(self, cumulative=cumulative, pairs=pairs)


def _prepare_plane(image: np.ndarray, options: RegistrationOptions) -> np.ndarray:
    if not options.remove_offset:
        return image
    background = percentile(image, options.offset_percentile)
    return image if not background > 0 else np.where(np.isfinite(image), image - background, np.nan).astype(np.float32)


def _make_log_plane(image: np.ndarray, options: RegistrationOptions) -> LogPlane:
    floor = options.intensity_floor if math.isnan(options.intensity_floor_percentile) else percentile(image, options.intensity_floor_percentile)
    ceiling = options.saturation_max if math.isnan(options.saturation_percentile) else percentile(image, options.saturation_percentile)
    return LogPlane.from_intensity(image, options.epsilon, floor, ceiling)


def _plan_pairs(reference: Reference, frames: int, reference_frame: int, lags: Sequence[int]) -> list[tuple[int, int]]:
    if reference is Reference.CONSECUTIVE:
        return [(t, t + 1) for t in range(frames - 1)]
    if reference is Reference.MULTILAG:
        return [(t, t + lag) for t in range(frames) for lag in lags if lag >= 1 and t + lag < frames]
    if reference is Reference.FIXED:
        return [(reference_frame, t) for t in range(frames) if t != reference_frame]
    return []


def _reconcile(
    reference: Reference,
    frames: int,
    reference_frame: int,
    pairs: Sequence[PairResult],
    weighting: ReconciliationWeighting = ReconciliationWeighting.EQUAL,
    dimensions: int = 2,
    rotation_radius: float = 1.0,
    *,
    return_influences: bool = False,
):
    if reference is Reference.MULTILAG and weighting is not ReconciliationWeighting.EQUAL:
        cumulative, support, influences = _weighted_reconcile(
            frames, pairs, weighting, dimensions, rotation_radius
        )
        return (cumulative, support, influences) if return_influences else (cumulative, support)

    usable = [p for p in pairs if p.fit.usable]
    support = np.zeros(frames, dtype=np.int32)
    if reference is Reference.CONSECUTIVE:
        cumulative = [IDENTITY]
        by_from = {p.from_frame: p for p in usable if p.to_frame == p.from_frame + 1}
        support[0] = 1
        for t in range(frames - 1):
            pair = by_from.get(t)
            cumulative.append(cumulative[-1].then(pair.fit.transform if pair else IDENTITY))
            support[t + 1] = int(pair is not None)
        influences = _not_used_influences(pairs)
        return (cumulative, support, influences) if return_influences else (cumulative, support)
    if reference is Reference.FIXED:
        by_to = {p.to_frame: p for p in usable if p.from_frame == reference_frame}
        cumulative = []
        for t in range(frames):
            if t == reference_frame:
                cumulative.append(IDENTITY)
                support[t] = 1
            elif t in by_to:
                cumulative.append(by_to[t].fit.transform)
                support[t] = 1
            else:
                cumulative.append(IDENTITY)
        influences = _not_used_influences(pairs)
        return (cumulative, support, influences) if return_influences else (cumulative, support)
    translation_rows, translation_targets, angle_rows, angle_targets = [], [], [], []
    for pair in usable:
        lo, hi = sorted((pair.from_frame, pair.to_frame))
        observed = pair.fit.transform if pair.to_frame > pair.from_frame else pair.fit.transform.inverse()
        row_x = np.zeros(2 * (frames - 1))
        row_y = np.zeros(2 * (frames - 1))
        row_t = np.zeros(frames - 1)
        hi_pose = hi - 1
        row_x[2 * hi_pose] = 1
        row_y[2 * hi_pose + 1] = 1
        row_t[hi_pose] = 1
        if lo > 0:
            lo_pose = lo - 1
            c, s = math.cos(observed.theta), math.sin(observed.theta)
            row_x[2 * lo_pose] = -c
            row_x[2 * lo_pose + 1] = s
            row_y[2 * lo_pose] = -s
            row_y[2 * lo_pose + 1] = -c
            row_t[lo_pose] = -1
        translation_rows.extend((row_x, row_y))
        translation_targets.extend((observed.dx, observed.dy))
        angle_rows.append(row_t)
        angle_targets.append(observed.theta)
        support[pair.from_frame] += 1
        support[pair.to_frame] += 1
    support[0] += 1
    if translation_rows:
        def solve(rows, targets):
            matrix = np.vstack(rows)
            normal = matrix.T @ matrix
            mean = float(np.trace(normal) / normal.shape[0])
            ridge = max(1e-9 * (mean if mean > 0 else 1.0), np.finfo(float).tiny)
            normal.flat[:: normal.shape[0] + 1] += ridge
            return np.linalg.solve(normal, matrix.T @ np.asarray(targets))
        translation = solve(translation_rows, translation_targets)
        theta = solve(angle_rows, angle_targets)
    else:
        translation = np.zeros(2 * (frames - 1))
        theta = np.zeros(frames - 1)
    cumulative = [IDENTITY] + [Transform(
        translation[2 * (t - 1)], translation[2 * (t - 1) + 1], theta[t - 1]
    ) for t in range(1, frames)]
    edges = _canonical_edges(pairs, dimensions)
    _prepare_graph_information(edges, ReconciliationWeighting.EQUAL, dimensions, rotation_radius)
    factors = np.ones(len(edges))
    influences = _graph_influences(
        cumulative, edges, factors, dimensions, True, None, pairs
    )
    return (cumulative, support, influences) if return_influences else (cumulative, support)


def _with_known_angles(
    cumulative: Sequence[Transform], known_angles: Sequence[float], anchor: int
) -> list[Transform]:
    if len(cumulative) != len(known_angles):
        raise ValueError("known angles must contain one finite value per frame")
    reference = known_angles[anchor]
    output = []
    for frame, transform in enumerate(cumulative):
        angle = known_angles[frame]
        if not math.isfinite(angle):
            raise ValueError(f"known angle for frame {frame} is not finite")
        output.append(Transform(transform.dx, transform.dy, angle - reference))
    return output


@dataclass
class _GraphEdge:
    pair: PairResult
    input_order: int
    lo: int
    hi: int
    observed: Transform
    uncertainty: PairUncertainty
    base_information: np.ndarray | None = None
    uncertainty_fallback: bool = False


def _canonical_edges(pairs: Sequence[PairResult], dimensions: int) -> list[_GraphEdge]:
    edges = []
    for index, pair in enumerate(pairs):
        if not pair.fit.usable:
            continue
        forward = pair.to_frame > pair.from_frame
        observed = pair.fit.transform if forward else pair.fit.transform.inverse()
        uncertainty = pair.fit.uncertainty
        if not forward and uncertainty.available:
            uncertainty = uncertainty.inverse_for(pair.fit.transform)
        edges.append(_GraphEdge(
            pair, index, min(pair.from_frame, pair.to_frame),
            max(pair.from_frame, pair.to_frame), observed, uncertainty,
        ))
    edges.sort(key=lambda edge: (
        edge.lo,
        edge.hi,
        edge.pair.plan_index if edge.pair.plan_index >= 0 else np.iinfo(np.int32).max,
        np.float64(edge.observed.dx).view(np.int64),
        np.float64(edge.observed.dy).view(np.int64),
        np.float64(edge.observed.theta).view(np.int64),
        edge.input_order,
    ))
    return edges


def _prepare_graph_information(
    edges: Sequence[_GraphEdge],
    weighting: ReconciliationWeighting,
    dimensions: int,
    rotation_radius: float,
) -> None:
    if weighting in (ReconciliationWeighting.EQUAL, ReconciliationWeighting.ROBUST):
        physical = np.eye(dimensions)
        if dimensions == 3:
            physical[2, 2] = rotation_radius**2
        physical /= np.trace(physical) / dimensions
        for edge in edges:
            edge.base_information = physical.copy()
        return
    scales = []
    for edge in edges:
        if edge.uncertainty.available and edge.uncertainty.dimensions == dimensions:
            information = edge.uncertainty.information
            assert information is not None
            scale = float(np.trace(information) / dimensions)
            if np.isfinite(scale) and scale > 0:
                edge.base_information = information
                scales.append(scale)
    normalizer = _order_statistic(np.asarray(scales), len(scales) // 2) if scales else 1.0
    if not np.isfinite(normalizer) or normalizer <= 0:
        normalizer = 1.0
    for edge in edges:
        if edge.base_information is None:
            edge.base_information = np.eye(dimensions) * normalizer
            edge.uncertainty_fallback = True
        edge.base_information = edge.base_information / normalizer


def _solve_graph(
    frames: int,
    edges: Sequence[_GraphEdge],
    factors: np.ndarray,
    dimensions: int,
    skipped: int = -1,
) -> list[Transform]:
    unknowns = dimensions * (frames - 1)
    normal = np.zeros((unknowns, unknowns), dtype=np.float64)
    target = np.zeros(unknowns, dtype=np.float64)
    for edge_index, edge in enumerate(edges):
        if edge_index == skipped:
            continue
        design = np.zeros((dimensions, unknowns), dtype=np.float64)
        hi_base = dimensions * (edge.hi - 1)
        design[:, hi_base : hi_base + dimensions] = np.eye(dimensions)
        if edge.lo > 0:
            lo_base = dimensions * (edge.lo - 1)
            c, s = math.cos(edge.observed.theta), math.sin(edge.observed.theta)
            design[0, lo_base] = -c
            design[0, lo_base + 1] = s
            design[1, lo_base] = -s
            design[1, lo_base + 1] = -c
            if dimensions == 3:
                design[2, lo_base + 2] = -1
        measurement = np.asarray([
            edge.observed.dx,
            edge.observed.dy,
            *([edge.observed.theta] if dimensions == 3 else []),
        ])
        assert edge.base_information is not None
        information = edge.base_information * factors[edge_index]
        normal += design.T @ information @ design
        target += design.T @ information @ measurement
    if unknowns:
        mean = float(np.trace(normal) / unknowns)
        ridge = max(1e-9 * (mean if mean > 0 else 1.0), np.finfo(float).tiny)
        normal.flat[:: unknowns + 1] += ridge
        try:
            solution = np.linalg.solve(normal, target)
        except np.linalg.LinAlgError:
            solution = np.zeros(unknowns)
    else:
        solution = np.zeros(0)
    cumulative = [IDENTITY]
    for frame in range(1, frames):
        base = dimensions * (frame - 1)
        cumulative.append(Transform(
            solution[base], solution[base + 1], solution[base + 2] if dimensions == 3 else 0.0
        ))
    return cumulative


def _graph_residual(cumulative: Sequence[Transform], edge: _GraphEdge, dimensions: int) -> np.ndarray:
    lower, upper = cumulative[edge.lo], cumulative[edge.hi]
    c, s = math.cos(edge.observed.theta), math.sin(edge.observed.theta)
    values = [
        upper.dx - c * lower.dx + s * lower.dy - edge.observed.dx,
        upper.dy - s * lower.dx - c * lower.dy - edge.observed.dy,
    ]
    if dimensions == 3:
        values.append(upper.theta - lower.theta - edge.observed.theta)
    return np.asarray(values)


def _standardized_residual(residual: np.ndarray, information: np.ndarray) -> float:
    return math.sqrt(max(0.0, float(residual @ information @ residual)) / residual.size)


def _has_alternate_path(frames: int, edges: Sequence[_GraphEdge], excluded: int) -> bool:
    wanted = edges[excluded]
    seen, pending = {wanted.lo}, [wanted.lo]
    while pending:
        frame = pending.pop(0)
        if frame == wanted.hi:
            return True
        for index, edge in enumerate(edges):
            if index == excluded:
                continue
            next_frame = edge.hi if edge.lo == frame else edge.lo if edge.hi == frame else -1
            if next_frame >= 0 and next_frame not in seen:
                seen.add(next_frame)
                pending.append(next_frame)
    return False


def _guarded_residuals(
    frames: int,
    cumulative: Sequence[Transform],
    edges: Sequence[_GraphEdge],
    factors: np.ndarray,
    dimensions: int,
) -> np.ndarray:
    output = np.zeros(len(edges))
    for index, edge in enumerate(edges):
        reference = (
            _solve_graph(frames, edges, factors, dimensions, index)
            if _has_alternate_path(frames, edges, index)
            else cumulative
        )
        assert edge.base_information is not None
        output[index] = _standardized_residual(
            _graph_residual(reference, edge, dimensions), edge.base_information
        )
    return output


def _trajectory_change(before: Sequence[Transform], after: Sequence[Transform], radius: float) -> float:
    return max((math.sqrt(
        (b.dx - a.dx) ** 2 + (b.dy - a.dy) ** 2 + (radius * (b.theta - a.theta)) ** 2
    ) for a, b in zip(before, after)), default=0.0)


def _weighted_reconcile(
    frames: int,
    pairs: Sequence[PairResult],
    weighting: ReconciliationWeighting,
    dimensions: int,
    rotation_radius: float,
):
    edges = _canonical_edges(pairs, dimensions)
    _prepare_graph_information(edges, weighting, dimensions, rotation_radius)
    support = np.zeros(frames, dtype=np.int32)
    for edge in edges:
        support[edge.pair.from_frame] += 1
        support[edge.pair.to_frame] += 1
    support[0] += 1
    factors = np.ones(len(edges))
    converged = True
    guarded = None
    if weighting.uses_robust_factors and edges:
        converged, previous = False, None
        for _ in range(8):
            current = _solve_graph(frames, edges, factors, dimensions)
            guarded = _guarded_residuals(frames, current, edges, factors, dimensions)
            median = _order_statistic(guarded, guarded.size // 2)
            deviation = np.abs(guarded - median)
            scale = max(1e-6, 1.4826 * _order_statistic(deviation, deviation.size // 2))
            threshold = 1.345 * scale
            excess = np.maximum(0.0, guarded - median)
            next_factors = np.ones_like(excess)
            np.divide(
                threshold,
                excess,
                out=next_factors,
                where=excess > threshold,
            )
            next_factors = np.clip(next_factors, 0.05, 1.0)
            factor_change = float(np.max(np.abs(next_factors - factors)))
            trajectory_change = math.inf if previous is None else _trajectory_change(
                previous, current, rotation_radius
            )
            factors, previous = next_factors, current
            if factor_change <= 1e-4 and trajectory_change <= 1e-5:
                converged = True
                break
    cumulative = _solve_graph(frames, edges, factors, dimensions)
    if weighting.uses_robust_factors:
        guarded = _guarded_residuals(frames, cumulative, edges, factors, dimensions)
    influences = _graph_influences(
        cumulative, edges, factors, dimensions, converged, guarded, pairs
    )
    return cumulative, support, influences


def _not_used_influences(pairs: Sequence[PairResult]) -> list[PairInfluence]:
    return [
        PairInfluence(
            pair.from_frame,
            pair.to_frame,
            pair.plan_index if pair.plan_index >= 0 else index,
        )
        for index, pair in enumerate(pairs)
    ]


def _graph_influences(
    cumulative: Sequence[Transform],
    edges: Sequence[_GraphEdge],
    factors: np.ndarray,
    dimensions: int,
    converged: bool,
    guarded: np.ndarray | None,
    pairs: Sequence[PairResult],
) -> list[PairInfluence]:
    output: list[PairInfluence | None] = [None] * len(pairs)
    for index, edge in enumerate(edges):
        residual = _graph_residual(cumulative, edge, dimensions)
        assert edge.base_information is not None
        standardized = (
            float(guarded[index]) if guarded is not None
            else _standardized_residual(residual, edge.base_information)
        )
        output[edge.input_order] = PairInfluence(
            edge.pair.from_frame,
            edge.pair.to_frame,
            edge.pair.plan_index if edge.pair.plan_index >= 0 else edge.input_order,
            True,
            Transform(residual[0], residual[1], residual[2] if dimensions == 3 else 0.0),
            standardized,
            float(factors[index]),
            float(factors[index] * np.trace(edge.base_information) / dimensions),
            bool(factors[index] <= 0.05 + 1e-15),
            edge.uncertainty_fallback,
            converged,
        )
    for index, pair in enumerate(pairs):
        if output[index] is None:
            output[index] = PairInfluence(
                pair.from_frame,
                pair.to_frame,
                pair.plan_index if pair.plan_index >= 0 else index,
            )
    return [item for item in output if item is not None]


def _repair(cumulative: Sequence[Transform], support: np.ndarray, anchor: int,
            outlier_mads: float, width: int = 0, height: int = 0,
            known_angles: Sequence[float] | None = None,
            protected_event_frames: Sequence[bool] | None = None):
    n = len(cumulative)
    if known_angles is not None and (
        len(known_angles) != n or not all(math.isfinite(value) for value in known_angles)
    ):
        raise ValueError("known angles must contain one finite value per frame")
    if protected_event_frames is not None and len(protected_event_frames) != n:
        raise ValueError("protected event flags must contain one value per frame")
    trusted = support > 0
    reasons: list[RepairReason | None] = [None if item else RepairReason.UNSUPPORTED for item in trusted]
    trusted[anchor], reasons[anchor] = True, None
    if outlier_mads > 0 and n >= 4:
        radius_square = (width * width + height * height - 2) / 12 if width > 0 and height > 0 else 0
        magnitudes = []
        for t in range(1, n):
            step = cumulative[t - 1].inverse().then(cumulative[t])
            mean_square = step.dx * step.dx + step.dy * step.dy
            mean_square += 2 * radius_square * (1 - math.cos(step.theta))
            magnitudes.append(math.sqrt(max(0, mean_square)))
        magnitudes = np.asarray(magnitudes)
        median = _order_statistic(magnitudes, magnitudes.size // 2)
        scale = robust_scale(magnitudes - median, 1e-6)
        limit = max(median + outlier_mads * scale, 6 * median)
        for t, magnitude in enumerate(magnitudes, 1):
            protected = protected_event_frames is not None and protected_event_frames[t]
            if not protected and magnitude > limit and trusted[t]:
                trusted[t], reasons[t] = False, RepairReason.OUTLIER_STEP
    output = list(cumulative)
    for t in range(n):
        if trusted[t]:
            if known_angles is not None:
                output[t] = Transform(output[t].dx, output[t].dy, known_angles[t])
            continue
        before = next((i for i in range(t - 1, -1, -1) if trusted[i]), None)
        after = next((i for i in range(t + 1, n) if trusted[i]), None)
        if before is None and after is None:
            output[t] = IDENTITY if known_angles is None else Transform(theta=known_angles[t])
        elif before is None:
            held = cumulative[after]
            output[t] = held if known_angles is None else Transform(
                held.dx, held.dy, known_angles[t]
            )
        elif after is None:
            held = cumulative[before]
            output[t] = held if known_angles is None else Transform(
                held.dx, held.dy, known_angles[t]
            )
        else:
            fraction = (t - before) / (after - before)
            a, b = cumulative[before], cumulative[after]
            delta_theta = math.atan2(math.sin(b.theta - a.theta), math.cos(b.theta - a.theta))
            output[t] = Transform(a.dx + fraction * (b.dx - a.dx),
                                  a.dy + fraction * (b.dy - a.dy),
                                  (a.theta + fraction * delta_theta)
                                  if known_angles is None else known_angles[t])
    return output, reasons


def _assemble(options: RegistrationOptions, frames: int, levels: int, pairs: Sequence[PairResult],
              cumulative, support, pyramids_built: int, width: int, height: int,
              event_rotations: RotationEventResult | None = None):
    anchor = options.reference_frame if options.reference is Reference.FIXED else 0
    owner: list[PairResult | None] = [None] * frames
    for pair in pairs:
        held = owner[pair.to_frame]
        if held is None or pair.lag < held.lag:
            owner[pair.to_frame] = pair
    known_angles = None
    protected_events = [False] * frames
    threshold = options.outlier_protection_residual_gain
    if math.isfinite(threshold):
        for frame, pair in enumerate(owner):
            if pair is None or not pair.fit.usable:
                continue
            protected_events[frame] = (
                pair.fit.status is Status.OK or pair.fit.residual_removed >= threshold
            )
    if event_rotations is not None:
        known_angles = [transform.theta for transform in cumulative]
        for event in event_rotations.events:
            protected_events[event.frame] = True
    cumulative, repairs = _repair(
        cumulative, support, anchor, options.outlier_mads, width, height,
        known_angles, protected_events,
    )
    gain = np.zeros(frames)
    before = np.full(frames, np.nan)
    after = np.full(frames, np.nan)
    valid = np.full(frames, np.nan)
    statuses: list[Status | None] = [None] * frames
    statuses[anchor], valid[anchor] = Status.OK, 1.0
    for t, pair in enumerate(owner):
        if pair is None:
            continue
        before[t], after[t], valid[t], statuses[t] = pair.fit.residual_before, pair.fit.residual_after, pair.fit.valid_fraction, pair.fit.status
        if t != anchor:
            gain[t] = (gain[pair.from_frame] if 0 <= pair.from_frame < frames else 0) + pair.fit.log_gain if pair.fit.usable else (gain[t - 1] if t else 0)
    warnings = []
    at_bound = sum(status in (Status.AT_SHIFT_BOUND, Status.AT_SHIFT_AND_ROTATION_BOUND)
                   for status in statuses)
    at_rotation_bound = sum(status in (Status.AT_ROTATION_BOUND,
                                       Status.AT_SHIFT_AND_ROTATION_BOUND)
                            for status in statuses)
    refused = sum(not pair.fit.usable for pair in pairs)
    if at_bound:
        warnings.append(f"{at_bound} frame(s) ended on the {options.aligner.max_shift:.1f} px maximum-shift bound")
    if at_rotation_bound:
        warnings.append(
            f"{at_rotation_bound} frame(s) ended on the "
            f"{math.degrees(options.aligner.max_rotation):.2f} degree maximum-rotation bound"
        )
    if options.nearest_neighbor_rotation and options.rotation_mode is not RotationMode.OFF:
        warnings.append(
            "Rotation will use nearest-neighbour resampling. This preserves label values but can "
            "make intensity images jagged; choose bilinear, bicubic or Fourier for intensity data."
        )
    if refused:
        warnings.append(f"{refused} of {len(pairs)} pairs had too few usable pixels and were refused")
    if event_rotations is not None:
        for event in event_rotations.events:
            if event.status is RotationEventStatus.HIGH_DISAGREEMENT:
                warnings.append(
                    f"Rotation event frame {event.public_frame} has "
                    f"{math.degrees(event.circular_mad):.4f} degree circular MAD across "
                    f"{event.usable_pairs} usable pair fits; the robust consensus was retained "
                    "as a warning-only result."
                )
    return RegistrationResult(tuple(cumulative), tuple(pairs), support, tuple(repairs), gain, before, after, valid, tuple(statuses), tuple(warnings), levels, 1, pyramids_built, 0, event_rotations)


def register_frames(
    frames: np.ndarray,
    options: RegistrationOptions | None = None,
    *,
    progress: Callable[[int, int], None] | None = None,
    support_by_frame: Sequence[Sequence[np.ndarray]] | None = None,
    starts: Sequence[Transform] | None = None,
    event_rotations: RotationEventResult | None = None,
) -> RegistrationResult:
    """Estimate one cumulative transform per frame from a ``(T, Y, X)`` array."""
    options = options or RegistrationOptions()
    data = np.asarray(frames, dtype=np.float32)
    if data.ndim != 3:
        raise ValueError("frames must have shape (T, Y, X)")
    count, height, width = data.shape
    if count < 1:
        raise ValueError("no frames")
    if width < 2 or height < 2:
        raise ValueError("frames must be at least 2x2")
    if not 0 <= options.reference_frame < count:
        raise ValueError(f"reference frame {options.reference_frame} is outside 0..{count - 1}")
    if options.reference is Reference.MULTILAG and 1 not in options.lags:
        raise ValueError("the lag set must contain 1")
    if options.reference is Reference.MULTILAG and not options.aligner.profile_gain:
        raise ValueError("MULTILAG requires profile_gain=True")
    known_events = options.rotation_mode is RotationMode.KNOWN_EVENTS
    if not known_events and options.rotation_event_frames:
        raise ValueError("rotation event frames require rotation mode known_events")
    if known_events:
        if options.reference is Reference.ROLLING:
            raise ValueError("known-events rotation does not support a rolling reference template")
        if options.estimator is not Estimator.LOG_RATIO_FIT:
            raise ValueError("known-events rotation requires the accepted log-ratio estimator")
        if not options.rotation_event_frames:
            raise ValueError("known-events rotation requires at least one rotation event frame")
        options = replace(options, aligner=replace(options.aligner, fit_rotation=False))
    if options.auto_max_shift and count > 1 and not known_events:
        suggested = estimate_shift_bound(data, options)
        options = replace(
            options,
            aligner=replace(options.aligner, max_shift=suggested[1]),
            auto_max_shift=False,
        )
    levels = options.aligner.levels_for(width, height)
    if count == 1:
        return _assemble(options, 1, levels, [], [IDENTITY],
                         np.ones(1, dtype=np.int32), 0, width, height)
    prepared = [_prepare_plane(data[t], options) for t in range(count)]
    pyramids: dict[int, list[LogPlane]] = {}
    def get(frame: int):
        if frame not in pyramids:
            base = _make_log_plane(prepared[frame], options)
            pyramids[frame] = base.pyramid(levels, linear=options.estimator is Estimator.AREA_CORRELATION_LINEAR)
        return pyramids[frame]
    if known_events:
        if event_rotations is None:
            event_rotations = _estimate_event_rotations(count, get, options, progress)
        if len(event_rotations.frame_angles) != count:
            raise ValueError("event-angle trajectory has a different frame count")
        event_rotations.require_usable()
        if options.auto_max_shift and count > 1:
            suggested = estimate_shift_bound(
                data, options, known_angles=event_rotations.frame_angles
            )
            options = replace(
                options,
                aligner=replace(options.aligner, max_shift=suggested[1]),
                auto_max_shift=False,
            )
            new_levels = options.aligner.levels_for(width, height)
            if new_levels != levels:
                levels = new_levels
                pyramids.clear()
    if options.reference is Reference.ROLLING:
        cumulative = [IDENTITY]
        pairs = []
        recent = [prepared[0].copy()]
        for t in range(1, count):
            template = np.nanmean(np.stack(recent), axis=0).astype(np.float32)
            template_pyramid = _make_log_plane(template, options).pyramid(levels)
            fit = _estimate_pair(template_pyramid, get(t), options)
            pairs.append(PairResult(
                0, t, fit, PairInfluence(0, t, t - 1), t - 1
            ))
            cumulative.append(fit.transform if fit.usable else IDENTITY)
            warped = warp_plane(prepared[t] * (2 ** -fit.log_gain if fit.usable else 1), cumulative[-1], Interpolation.BILINEAR, np.nan)
            recent.append(warped)
            recent = recent[-max(1, options.template_window) :]
            if progress:
                progress(t, count - 1)
        support = np.asarray([1] + [int(pair.fit.usable) for pair in pairs], dtype=np.int32)
        return _assemble(options, count, levels, pairs, cumulative, support,
                         len(pyramids), width, height)
    plan = _plan_pairs(options.reference, count, options.reference_frame, options.lags)
    pairs = []
    for index, (source, target) in enumerate(plan):
        start = None if starts is None else starts[source].inverse().then(starts[target])
        support = None if support_by_frame is None else support_by_frame[source]
        if known_events:
            assert event_rotations is not None
            fit = align_log_ratio_fixed_angle(
                get(source), get(target), options.aligner,
                event_rotations.frame_angles[target]
                - event_rotations.frame_angles[source],
                support,
            )
        else:
            fit = _estimate_pair(get(source), get(target), options, start=start, support=support)
        pairs.append(PairResult(source, target, fit, plan_index=index))
        if progress:
            progress(index + 1, len(plan))
    dimensions = 3 if options.aligner.fit_rotation and not known_events else 2
    cumulative, constraint_support, influences = _reconcile(
        options.reference,
        count,
        options.reference_frame,
        pairs,
        options.reconciliation_weighting,
        dimensions,
        _rms_radius(width, height),
        return_influences=True,
    )
    if known_events:
        assert event_rotations is not None
        anchor = options.reference_frame if options.reference is Reference.FIXED else 0
        cumulative = _with_known_angles(
            cumulative, event_rotations.frame_angles, anchor
        )
    pairs = [replace(pair, influence=influences[index]) for index, pair in enumerate(pairs)]
    return _assemble(options, count, levels, pairs, cumulative, constraint_support,
                     len(pyramids), width, height, event_rotations)


def estimate_shift_bound(
    frames: np.ndarray,
    options: RegistrationOptions,
    *,
    known_angles: Sequence[float] | None = None,
) -> tuple[float, float, float, int]:
    """Return ``(largest, suggested, resolution, pairs)`` from the Java coarse probe."""
    data = np.asarray(frames, dtype=np.float32)
    count, height, width = data.shape
    if count < 2:
        return 0.0, options.aligner.max_shift, 1.0, 0
    levels = LogPlane.auto_levels(width, height, 32, 9)
    scale = 1 << (levels - 1)
    coarse_width, coarse_height = width, height
    for _ in range(1, levels):
        coarse_width //= 2
        coarse_height //= 2
    coarse_radius = max(1.0, min(coarse_width, coarse_height) / 2.0)
    probe_aligner = replace(
        options.aligner,
        levels=1,
        max_shift=coarse_radius,
        coarse_radius_budget=math.ceil(coarse_radius),
        support=PixelSupport.ALL,
        fit_rotation=False,
    )
    plan_reference = Reference.CONSECUTIVE if options.reference is Reference.ROLLING else options.reference
    plan = _plan_pairs(plan_reference, count, options.reference_frame, options.lags)
    coarse: dict[int, LogPlane] = {}
    def get(frame: int) -> LogPlane:
        if frame not in coarse:
            image = _prepare_plane(data[frame], options)
            pyramid = _make_log_plane(image, options).pyramid(levels)
            coarse[frame] = pyramid[-1]
        return coarse[frame]
    largest = 0.0
    measured = 0
    for source, target in plan:
        fit = (
            align_log_ratio([get(source)], [get(target)], probe_aligner)
            if known_angles is None
            else align_log_ratio_fixed_angle(
                [get(source)], [get(target)], probe_aligner,
                known_angles[target] - known_angles[source],
            )
        )
        if fit.usable:
            largest = max(largest, fit.transform.magnitude * scale)
            measured += 1
    suggested = max(30.0, 1.5 * largest + scale)
    return largest, suggested, float(scale), measured


def _estimate_pair(a, b, options: RegistrationOptions, start=None, support=None):
    if options.estimator is Estimator.LOG_RATIO_FIT:
        return align_log_ratio(a, b, options.aligner, start, support)
    if support is not None:
        raise ValueError("area-correlation estimators do not accept a log-ratio support mask")
    return align_area(a, b, options.aligner, options.estimator, start)


@dataclass(frozen=True)
class Margin:
    top: int
    bottom: int
    left: int
    right: int

    def cropped_shape(self, height: int, width: int) -> tuple[int, int]:
        return max(0, height - self.top - self.bottom), max(0, width - self.left - self.right)


def valid_margin(cumulative: Sequence[Transform], width: int, height: int, interpolation: Interpolation | str) -> Margin:
    interpolation = Interpolation.parse(interpolation)
    reach = 0 if interpolation is Interpolation.NONE else (2 if interpolation is Interpolation.BICUBIC else 1)
    top = bottom = left = right = 0
    half_diagonal = 0.5 * math.hypot(width, height)
    for transform in cumulative:
        iy, ix = int(_java_round(transform.dy)), int(_java_round(transform.dx))
        pad = reach + math.ceil(abs(transform.theta) * half_diagonal)
        top = max(top, max(0, -iy) + pad)
        bottom = max(bottom, max(0, iy) + pad)
        left = max(left, max(0, -ix) + pad)
        right = max(right, max(0, ix) + pad)
    return Margin(
        min(top, max(0, height // 2 - 1)),
        min(bottom, max(0, height // 2 - 1)),
        min(left, max(0, width // 2 - 1)),
        min(right, max(0, width // 2 - 1)),
    )


def _fourier_shear_horizontal(
    plane: np.ndarray, slope: float, center_y: float
) -> np.ndarray:
    """Sample every row at x + slope * (y - center_y)."""
    shifts = slope * (np.arange(plane.shape[0]) - center_y)
    frequencies = np.fft.fftfreq(plane.shape[1])
    phase = np.exp(2j * np.pi * shifts[:, None] * frequencies[None, :])
    return np.fft.ifft(np.fft.fft(plane, axis=1) * phase, axis=1).real


def _fourier_shear_vertical(
    plane: np.ndarray, slope: float, center_x: float
) -> np.ndarray:
    """Sample every column at y + slope * (x - center_x)."""
    shifts = slope * (np.arange(plane.shape[1]) - center_x)
    frequencies = np.fft.fftfreq(plane.shape[0])
    phase = np.exp(2j * np.pi * frequencies[:, None] * shifts[None, :])
    return np.fft.ifft(np.fft.fft(plane, axis=0) * phase, axis=0).real


def _fourier_shift_axis(plane: np.ndarray, shift: float, axis: int) -> np.ndarray:
    frequencies = np.fft.fftfreq(plane.shape[axis])
    shape = [1] * plane.ndim
    shape[axis] = frequencies.size
    phase = np.exp(2j * np.pi * shift * frequencies).reshape(shape)
    return np.fft.ifft(np.fft.fft(plane, axis=axis) * phase, axis=axis).real


def _fourier_rigid(
    source: np.ndarray, transform: Transform, fill: float
) -> np.ndarray:
    """Rigid Fourier resampling using a three-shear rotation decomposition."""
    height, width = source.shape
    side = 1 << max(1, 2 * max(width, height) - 1).bit_length()
    y_offset = (side - height) // 2
    x_offset = (side - width) // 2
    center_y = y_offset + (height - 1) / 2
    center_x = x_offset + (width - 1) / 2
    baseline = float(fill) if np.isfinite(fill) else 0.0
    plane = np.zeros((side, side), dtype=float)
    plane[y_offset : y_offset + height, x_offset : x_offset + width] = (
        source.astype(float) - baseline
    )

    angle = math.remainder(transform.theta, 2 * math.pi)
    steps = max(1, math.ceil(abs(angle) / (math.pi / 4)))
    step = angle / steps
    horizontal = -math.tan(step / 2)
    vertical = math.sin(step)
    if angle != 0:
        for _ in range(steps):
            plane = _fourier_shear_horizontal(plane, horizontal, center_y)
            plane = _fourier_shear_vertical(plane, vertical, center_x)
            plane = _fourier_shear_horizontal(plane, horizontal, center_y)

    cosine, sine = math.cos(angle), math.sin(angle)
    translated_x = cosine * transform.dx + sine * transform.dy
    translated_y = -sine * transform.dx + cosine * transform.dy
    if translated_x != 0:
        plane = _fourier_shift_axis(plane, translated_x, axis=1)
    if translated_y != 0:
        plane = _fourier_shift_axis(plane, translated_y, axis=0)
    values = plane[
        y_offset : y_offset + height, x_offset : x_offset + width
    ] + baseline

    y, x = np.mgrid[:height, :width]
    source_x, source_y = transform.apply(
        x, y, (width - 1) / 2, (height - 1) / 2
    )
    inside = (
        (source_x >= 0)
        & (source_y >= 0)
        & (source_x <= width - 1)
        & (source_y <= height - 1)
    )
    values[~inside] = fill
    if np.issubdtype(source.dtype, np.integer):
        info = np.iinfo(source.dtype)
        values = np.clip(np.floor(values + 0.5), info.min, info.max)
    return values.astype(source.dtype)


def warp_plane(
    image: np.ndarray,
    transform: Transform,
    interpolation: Interpolation | str = Interpolation.NONE,
    fill: float = 0.0,
) -> np.ndarray:
    """Warp one plane with the Java engine's inverse-warp sign convention."""
    interpolation = Interpolation.parse(interpolation)
    source = np.asarray(image)
    if source.ndim != 2:
        raise ValueError("warp_plane expects a 2-D image")
    height, width = source.shape
    whole_pixel_translation = (
        transform.is_pure_translation
        and abs(transform.dx - _java_round(transform.dx)) < 1e-9
        and abs(transform.dy - _java_round(transform.dy)) < 1e-9
    )
    if transform.is_pure_translation and (
        interpolation is Interpolation.NONE or whole_pixel_translation
    ):
        ix, iy = int(_java_round(transform.dx)), int(_java_round(transform.dy))
        output = np.full(source.shape, fill, dtype=source.dtype)
        if abs(ix) >= width or abs(iy) >= height:
            return output
        y0, y1 = max(0, -iy), min(height, height - iy)
        x0, x1 = max(0, -ix), min(width, width - ix)
        output[y0:y1, x0:x1] = source[y0 + iy : y1 + iy, x0 + ix : x1 + ix]
        return output
    if interpolation is Interpolation.FOURIER:
        if not transform.is_pure_translation:
            return _fourier_rigid(source, transform, fill)
        if abs(transform.dx) > width - 1 or abs(transform.dy) > height - 1:
            return np.full(source.shape, fill, dtype=source.dtype)
        padded_height = 1 << max(1, 2 * height - 1).bit_length()
        padded_width = 1 << max(1, 2 * width - 1).bit_length()
        y_offset = (padded_height - height) // 2
        x_offset = (padded_width - width) // 2
        baseline = float(fill) if np.isfinite(fill) else 0.0
        padded = np.zeros((padded_height, padded_width), dtype=float)
        padded[y_offset : y_offset + height, x_offset : x_offset + width] = (
            source.astype(float) - baseline
        )
        spectrum = np.fft.fftn(padded)
        shifted = np.fft.ifftn(ndimage.fourier_shift(
            spectrum, shift=(-transform.dy, -transform.dx)
        )).real
        values = shifted[
            y_offset : y_offset + height, x_offset : x_offset + width
        ] + baseline
        y, x = np.mgrid[:height, :width]
        inside = (
            (x + transform.dx >= 0)
            & (y + transform.dy >= 0)
            & (x + transform.dx <= width - 1)
            & (y + transform.dy <= height - 1)
        )
        values[~inside] = fill
        if np.issubdtype(source.dtype, np.integer):
            info = np.iinfo(source.dtype)
            values = np.clip(np.floor(values + 0.5), info.min, info.max)
        return values.astype(source.dtype)
    y, x = np.mgrid[:height, :width]
    sx, sy = transform.apply(x, y, (width - 1) / 2, (height - 1) / 2)
    if interpolation is Interpolation.NONE:
        sx, sy = _java_round(sx), _java_round(sy)
        inside = (sx >= 0) & (sy >= 0) & (sx < width) & (sy < height)
        output = np.full(source.shape, fill, dtype=source.dtype)
        output[inside] = source[sy[inside], sx[inside]]
        return output
    if interpolation is Interpolation.BILINEAR:
        order = 1
        values = ndimage.map_coordinates(source.astype(float), [sy, sx], order=order, mode="constant", cval=fill, prefilter=False)
    else:
        # Catmull-Rom cubic convolution, matching Warper rather than scipy's B-spline cubic.
        values = np.full(source.shape, fill, dtype=float)
        inside = (sx >= 0) & (sy >= 0) & (sx <= width - 1) & (sy <= height - 1)
        ix, iy = np.floor(sx).astype(int), np.floor(sy).astype(int)
        fx, fy = sx - ix, sy - iy
        def cubic(t):
            q = np.abs(t)
            return np.where(q < 1, ((1.5 * q - 2.5) * q * q + 1), np.where(q < 2, ((-0.5 * q + 2.5) * q - 4) * q + 2, 0))
        total = np.zeros(source.shape, dtype=float)
        for m in range(-1, 3):
            row = np.zeros(source.shape, dtype=float)
            yy = np.clip(iy + m, 0, height - 1)
            for n in range(-1, 3):
                xx = np.clip(ix + n, 0, width - 1)
                row += cubic(n - fx) * source[yy, xx]
            total += cubic(m - fy) * row
        values[inside] = total[inside]
    if np.issubdtype(source.dtype, np.integer):
        info = np.iinfo(source.dtype)
        values = np.clip(np.floor(values + 0.5), info.min, info.max)
    return values.astype(source.dtype)
