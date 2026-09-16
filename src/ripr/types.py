"""Public value types shared by the registration engine and high-level API."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import math
import re
from typing import TypeVar

import numpy as np


E = TypeVar("E", bound="NamedEnum")


class NamedEnum(str, Enum):
    """A string enum with the Java API's forgiving parser."""

    @classmethod
    def parse(cls: type[E], value: E | str) -> E:
        if isinstance(value, cls):
            return value
        if value is None:
            raise ValueError(f"{cls.__name__} is required")
        wanted = re.sub(r"[^A-Z0-9]+", "_", str(value).strip().upper()).strip("_")
        for item in cls:
            label = re.sub(r"[^A-Z0-9]+", "_", item.label.upper()).strip("_")
            if wanted in (item.name, item.value.upper(), label):
                return item
        raise ValueError(f"unknown {cls.__name__}: {value}")

    @property
    def label(self) -> str:
        return self.value.replace("_", " ").title()

    def __str__(self) -> str:
        return self.label


class ImageType(NamedEnum):
    PHASE_CONTRAST = "phase_contrast"
    BRIGHTFIELD_DIC = "brightfield_dic"
    DENSE_FLUORESCENCE = "dense_fluorescence"
    SPARSE_LOW_LIGHT_FLUORESCENCE = "sparse_low_light_fluorescence"
    FIDUCIAL_STATIC = "fiducial_static"

    @property
    def label(self) -> str:
        return {
            self.PHASE_CONTRAST: "Phase contrast",
            self.BRIGHTFIELD_DIC: "Brightfield / differential interference contrast",
            self.DENSE_FLUORESCENCE: "Dense fluorescence",
            self.SPARSE_LOW_LIGHT_FLUORESCENCE: "Sparse / low-light fluorescence or bioluminescence",
            self.FIDUCIAL_STATIC: "Fiducial / nominally static reference",
        }[self]


class MotionType(NamedEnum):
    CURVED_OSCILLATING_DRIFT = "curved_oscillating_drift"
    STEADY_DIRECTIONAL_DRIFT = "steady_directional_drift"
    SUBPIXEL_RANDOM_WALK = "subpixel_random_walk"
    INTERMITTENT_JUMPS = "intermittent_jumps"

    @property
    def label(self) -> str:
        return {
            self.CURVED_OSCILLATING_DRIFT: "Curved / oscillating drift",
            self.STEADY_DIRECTIONAL_DRIFT: "Steady directional drift",
            self.SUBPIXEL_RANDOM_WALK: "Subpixel random walk",
            self.INTERMITTENT_JUMPS: "Intermittent jumps",
        }[self]


class SelectionMode(NamedEnum):
    RECOMMENDED = "recommended"
    AUTOMATIC = "automatic"
    LONGITUDINAL_ACCURACY = "longitudinal_accuracy"
    MANUAL = "manual"

    @property
    def label(self) -> str:
        return {
            self.RECOMMENDED: "Image-and-motion preset",
            self.AUTOMATIC: "Automatic fixed recipe",
            self.LONGITUDINAL_ACCURACY: "Longitudinal maximum accuracy",
            self.MANUAL: "Manual",
        }[self]


class Recipe(NamedEnum):
    """Simple recording-level choices backed by the accepted benchmark routes."""

    LANDMARKS = "landmarks"
    BRIGHT_DIM = "bright_dim"
    MOVING_CELLS = "moving_cells"

    @property
    def label(self) -> str:
        return {
            self.LANDMARKS: "Landmarks (phase contrast / brightfield)",
            self.BRIGHT_DIM: "Bright/dim references (fluorescence / bioluminescence)",
            self.MOVING_CELLS: "Moving cells (biological foreground)",
        }[self]


class RotationMode(NamedEnum):
    OFF = "off"
    CONTINUOUS = "continuous"
    KNOWN_EVENTS = "known_events"

    @property
    def label(self) -> str:
        return {
            self.OFF: "Off",
            self.CONTINUOUS: "Search continuously",
            self.KNOWN_EVENTS: "Known remount frames",
        }[self]


class RotationEventStatus(NamedEnum):
    OK = "ok"
    HIGH_DISAGREEMENT = "high_disagreement"
    INSUFFICIENT_SUPPORT = "insufficient_support"
    CANCELLED = "cancelled"


class Preprocessing(NamedEnum):
    NONE = "none"
    GAUSSIAN_0_7 = "gaussian_0_7"
    GAUSSIAN_1_0 = "gaussian_1_0"
    GAUSSIAN_1_4 = "gaussian_1_4"
    MEDIAN_3X3 = "median_3x3"
    ANSCOMBE = "anscombe"
    ANSCOMBE_GAUSSIAN_1_0 = "anscombe_gaussian_1_0"
    UNSHARP_0_5 = "unsharp_0_5"
    LOCAL_CONTRAST_1_8 = "local_contrast_1_8"

    @property
    def label(self) -> str:
        return {
            self.NONE: "None",
            self.GAUSSIAN_0_7: "Gaussian smoothing (0.7 pixel)",
            self.GAUSSIAN_1_0: "Gaussian smoothing (1.0 pixel)",
            self.GAUSSIAN_1_4: "Gaussian smoothing (1.4 pixels)",
            self.MEDIAN_3X3: "Median denoising (3 by 3)",
            self.ANSCOMBE: "Photon-noise stabilisation",
            self.ANSCOMBE_GAUSSIAN_1_0: "Photon-noise stabilisation plus Gaussian smoothing",
            self.UNSHARP_0_5: "Mild sharpening",
        }[self]


class PixelSelectionStrategy(NamedEnum):
    NONE = "none"
    REMOVE_LEAST_INFORMATIVE = "remove_least_informative"
    REMOVE_MOST_INFORMATIVE = "remove_most_informative"
    REMOVE_MOST_UNSTABLE = "remove_most_unstable"
    REMOVE_LEAST_UNSTABLE = "remove_least_unstable"
    REMOVE_MOST_LAG_GROWTH = "remove_most_lag_growth"
    REMOVE_LEAST_LAG_GROWTH = "remove_least_lag_growth"
    REMOVE_LOWEST_ANCHOR_TRUST = "remove_lowest_anchor_trust"
    REMOVE_HIGHEST_ANCHOR_TRUST = "remove_highest_anchor_trust"
    STRATIFIED_LOWEST_ANCHOR_TRUST = "stratified_lowest_anchor_trust"

    @property
    def uses_temporal_evidence(self) -> bool:
        return self not in (
            self.NONE,
            self.REMOVE_LEAST_INFORMATIVE,
            self.REMOVE_MOST_INFORMATIVE,
        )


class Reference(NamedEnum):
    CONSECUTIVE = "consecutive"
    MULTILAG = "multilag"
    FIXED = "fixed"
    ROLLING = "rolling"


class ReconciliationWeighting(NamedEnum):
    """Experimental post-pair graph strategy; public defaults remain equal."""

    EQUAL = "equal"
    UNCERTAINTY = "uncertainty"
    ROBUST = "robust"
    COMBINED = "combined"

    @property
    def uses_uncertainty(self) -> bool:
        return self in (self.UNCERTAINTY, self.COMBINED)

    @property
    def uses_robust_factors(self) -> bool:
        return self in (self.ROBUST, self.COMBINED)


class RobustNorm(NamedEnum):
    LEAST_SQUARES = "least_squares"
    HUBER = "huber"
    TUKEY = "tukey"

    @property
    def tuning_constant(self) -> float:
        return {self.LEAST_SQUARES: math.inf, self.HUBER: 1.345, self.TUKEY: 4.685}[self]

    def threshold(self, scale: float) -> float:
        value = self.tuning_constant * scale
        return value if value > 0 else np.finfo(float).tiny

    def weights(self, residual: np.ndarray, threshold: float) -> np.ndarray:
        r = np.asarray(residual, dtype=np.float64)
        if self is self.LEAST_SQUARES:
            return np.ones_like(r)
        absolute = np.abs(r)
        if self is self.HUBER:
            out = np.ones_like(r)
            outside = absolute > threshold
            out[outside] = threshold / absolute[outside]
            return out
        u = 1.0 - np.square(r / threshold)
        return np.where(absolute < threshold, np.square(u), 0.0)

    def rho(self, residual: np.ndarray, threshold: float) -> np.ndarray:
        r = np.asarray(residual, dtype=np.float64)
        if self is self.LEAST_SQUARES:
            return np.square(r)
        absolute = np.abs(r)
        if self is self.HUBER:
            return np.where(absolute <= threshold, np.square(r), threshold * (2 * absolute - threshold))
        threshold2 = threshold * threshold
        u = 1.0 - np.square(r / threshold)
        return np.where(absolute < threshold, (threshold2 / 6.0) * (1.0 - u**3), threshold2 / 6.0)


class PixelSupport(NamedEnum):
    ALL = "all"
    GRADIENT = "gradient"
    MUTUAL_NOISE_GRADIENT = "mutual_noise_gradient"


class Estimator(NamedEnum):
    LOG_RATIO_FIT = "log_ratio_fit"
    AREA_CORRELATION = "area_correlation"
    AREA_CORRELATION_LINEAR = "area_correlation_linear"
    AREA_CORRELATION_NEWTON = "area_correlation_newton"
    AREA_CORRELATION_ECC = "area_correlation_ecc"
    AREA_CORRELATION_SUBSAMPLED = "area_correlation_subsampled"


class Interpolation(NamedEnum):
    NONE = "none"
    BILINEAR = "bilinear"
    BICUBIC = "bicubic"
    FOURIER = "fourier"


class Status(NamedEnum):
    OK = "ok"
    REFUSED_LOW_OVERLAP = "refused_low_overlap"
    NOT_CONVERGED = "not_converged"
    AT_SHIFT_BOUND = "at_shift_bound"
    AT_ROTATION_BOUND = "at_rotation_bound"
    AT_SHIFT_AND_ROTATION_BOUND = "at_shift_and_rotation_bound"


class RepairReason(NamedEnum):
    UNSUPPORTED = "unsupported"
    OUTLIER_STEP = "outlier_step"


@dataclass(frozen=True)
class Transform:
    """Rigid 2-D transform: rotate about the image centre, then translate."""

    dx: float = 0.0
    dy: float = 0.0
    theta: float = 0.0

    @classmethod
    def translation(cls, dx: float, dy: float) -> "Transform":
        return cls(float(dx), float(dy), 0.0)

    @property
    def is_pure_translation(self) -> bool:
        return self.theta == 0.0

    @property
    def magnitude(self) -> float:
        return math.hypot(self.dx, self.dy)

    @property
    def theta_degrees(self) -> float:
        return math.degrees(self.theta)

    def then(self, next_transform: "Transform") -> "Transform":
        c = math.cos(next_transform.theta)
        s = math.sin(next_transform.theta)
        return Transform(
            c * self.dx - s * self.dy + next_transform.dx,
            s * self.dx + c * self.dy + next_transform.dy,
            self.theta + next_transform.theta,
        )

    def plus(self, other: "Transform") -> "Transform":
        return Transform(self.dx + other.dx, self.dy + other.dy, self.theta + other.theta)

    def inverse(self) -> "Transform":
        c = math.cos(-self.theta)
        s = math.sin(-self.theta)
        return Transform(-(c * self.dx - s * self.dy), -(s * self.dx + c * self.dy), -self.theta)

    def scale_translation(self, factor: float) -> "Transform":
        return Transform(self.dx * factor, self.dy * factor, self.theta)

    def apply(self, x: np.ndarray | float, y: np.ndarray | float, cx: float, cy: float):
        if self.theta == 0.0:
            return np.asarray(x) + self.dx, np.asarray(y) + self.dy
        c = math.cos(self.theta)
        s = math.sin(self.theta)
        u = np.asarray(x) - cx
        v = np.asarray(y) - cy
        return c * u - s * v + cx + self.dx, s * u + c * v + cy + self.dy


IDENTITY = Transform()


def robust_scale(residual: np.ndarray, floor: float = 1e-4) -> float:
    """Java engine's MAD-about-zero scale, including its sparse-zero fallback."""
    absolute = np.abs(np.asarray(residual, dtype=np.float64).ravel())
    if absolute.size == 0:
        return floor
    middle = absolute.size // 2
    mad = float(np.partition(absolute, middle)[middle])
    if not mad > 0:
        high = int(0.9 * (absolute.size - 1))
        mad = float(np.partition(absolute, high)[high])
    return max(floor, 1.4826 * mad)
