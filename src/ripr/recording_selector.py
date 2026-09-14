"""Recording-adaptive selector matching Java ``recording_evidence_v2``.

The model block is generated from the Java training artifact. Evidence calculations are a direct
port of ``AutomaticRegistrationSelector`` and ``AutomaticFilterSelector``; this module never fits.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
import math
from typing import Sequence

import numpy as np
from scipy import ndimage

from .core import LogPlane
from .parameters import LogRatioParameters, recommendation
from .recording_selector_model import (
    CANDIDATE_MANIFEST_SHA256, CONFIDENCE_THRESHOLD,
    FEATURE_CONTRACT_VERSION, FEATURE_MEAN as _FEATURE_MEAN,
    FEATURE_SCALE as _FEATURE_SCALE, MODEL_ARTIFACT_SHA256, MODEL_CANDIDATES,
    MODEL_KIND, MODEL_VERSION, PROTOCOL_SHA256, VALIDATION_STATUS,
)
from .types import (
    Estimator, ImageType, MotionType, PixelSelectionStrategy, PixelSupport,
    Preprocessing, Reference, RobustNorm, RotationMode, SelectionMode, Transform,
)


FEATURE_NAMES = (
    "median position", "99-to-95 percentile tail", "95-to-median range",
    "maximum tail", "dark-pixel share", "bright-pixel share", "skewness",
    "excess kurtosis", "median gradient", "90th-percentile gradient",
    "median impulse residual", "90th-percentile impulse residual",
    "median blur residual", "90th-percentile blur residual", "histogram entropy",
    "horizontal neighbour correlation", "vertical neighbour correlation",
    "median step", "90th-percentile step", "maximum step", "jump ratio",
    "path efficiency", "linearity residual", "median acceleration",
    "90th-percentile acceleration", "maximum acceleration", "90th-percentile reach",
    "sparsity score", "moving-tail ratio", "temporal outlier fraction",
    "gradient-admitted fraction", "mutual-noise-admitted fraction",
    *(f"declared image type {item.name}" for item in ImageType),
    *(f"declared motion type {item.name}" for item in MotionType),
    *(f"base robust weighting {item.name}" for item in RobustNorm),
    *(f"base reference strategy {item.name}" for item in Reference),
)
CONTINUOUS_FEATURE_COUNT = 32
MAX_ABSOLUTE_Z = 8.0
MAX_RMS_Z = 3.0

EMISSION_POLICY_VERSION = "single_channel_emission_max_accuracy_r04_a208"
FLUORESCENCE_POLICY_KIND = "declared_image_and_motion_rule"
FLUORESCENCE_POLICY_VALIDATION = "REAL_24_SINGLE_CHANNEL_NATIVE_PASS"
FLUORESCENCE_POLICY_CONTRACT = "declared_inputs_v2"
FLUORESCENCE_POLICY_PROTOCOL_SHA256 = (
    "dfb454716dd831a0c17221756dd5ed167a080d8cedc8ff287ec4a326016c86d1"
)
FLUORESCENCE_POLICY_CANDIDATE_SHA256 = (
    "4af4e55141e34c5ee8670fa248aebd025ba718f1648cf34b4c21aedb729ea139"
)
FLUORESCENCE_POLICY_ARTIFACT_SHA256 = (
    "681bc10ff5edec137cc9977f49a329ac9ea2016766520b79c67d1a10e181e32d"
)

FEATURE_MEAN = np.asarray(_FEATURE_MEAN, dtype=np.float64)
FEATURE_SCALE = np.asarray(_FEATURE_SCALE, dtype=np.float64)


@dataclass(frozen=True)
class Candidate:
    image_type: ImageType
    recipe_id: str
    estimator: Estimator
    support: PixelSupport
    band: str
    preprocessing: Preprocessing
    mask: str
    training_gain: float
    intercept: float = 0.0
    weights: tuple[float, ...] = ()
    rigid_validated: bool = True


CANDIDATES = tuple(Candidate(
    ImageType[image_type], recipe_id, Estimator[estimator], PixelSupport[support],
    band, Preprocessing[preprocessing], mask, training_gain, intercept,
    tuple(weights), rigid_validated,
) for (image_type, recipe_id, estimator, support, band, preprocessing, mask,
       training_gain, intercept, weights, rigid_validated) in MODEL_CANDIDATES)


@dataclass(frozen=True)
class Evidence:
    values: tuple[float, ...]
    valid: bool
    reason: str
    image_type: ImageType
    motion_type: MotionType

    def distribution(self) -> tuple[bool, str]:
        if not self.valid:
            return True, self.reason
        raw = np.asarray(self.values, dtype=np.float64)
        squared = 0.0
        count = 0
        for index in range(CONTINUOUS_FEATURE_COUNT):
            if FEATURE_SCALE[index] == 0:
                if raw[index] != FEATURE_MEAN[index]:
                    return True, f"constant training feature changed: {FEATURE_NAMES[index]}"
                continue
            z = (raw[index] - FEATURE_MEAN[index]) / FEATURE_SCALE[index]
            if abs(z) > MAX_ABSOLUTE_Z:
                return True, f"feature exceeds 8 SD: {FEATURE_NAMES[index]}"
            squared += z * z
            count += 1
        rms = math.sqrt(squared / count) if count else 0.0
        return (True, f"continuous-feature RMS z {rms:.4f} exceeds 3") if rms > 3 else (False, "")


@dataclass(frozen=True)
class AutomaticSelection:
    fallback: bool
    predicted_gain: float
    confidence_threshold: float
    recipe: str
    reason: str
    model_version: str = MODEL_VERSION
    evidence: Evidence | None = None

    @property
    def explanation(self) -> str:
        if self.fallback:
            return f"category recommendation retained; {self.reason} ({self.recipe})"
        kind = "fixed automatic policy" if MODEL_KIND == "image_type_rule" else "adaptive recipe selected"
        return (f"{kind}: {self.recipe}; predicted gain {self.predicted_gain:.4f} px "
                f"clears {self.confidence_threshold:.4f} px; model {self.model_version}")


def requires_evidence(image_type: ImageType, rotation_requested: bool = False) -> bool:
    if MODEL_KIND != "linear_gain":
        return False
    return any(candidate.image_type is image_type and
               (not rotation_requested or candidate.rigid_validated)
               for candidate in CANDIDATES)


def neutral_pilot(parameters: LogRatioParameters) -> LogRatioParameters:
    return replace(
        parameters, selection_mode=SelectionMode.MANUAL,
        estimator=Estimator.LOG_RATIO_FIT, preprocessing=Preprocessing.NONE,
        pixel_selection_strategy=PixelSelectionStrategy.NONE,
        pixel_selection_preprocessing=Preprocessing.NONE, pixel_removal_percent=25.0,
        norm=RobustNorm.HUBER, reference=Reference.MULTILAG, lags=(1, 2, 4, 8, 16),
        pixel_support=PixelSupport.ALL, gradient_fraction=0.5,
        floor_percentile=math.nan, ceiling_percentile=math.nan,
        estimation_scale=1.0, auto_max_shift=False, max_shift=30.0, epsilon=1.0,
        max_iterations=25, max_samples=200_000, min_valid_fraction=0.10,
        outlier_mads=0.0, rotation_mode=RotationMode.OFF, fit_rotation=False,
        rotation_event_frames=(),
    )


def _quantile(values: np.ndarray, fraction: float) -> float:
    finite = np.sort(np.asarray(values, dtype=np.float64)[np.isfinite(values)])
    if not finite.size:
        return math.nan
    position = min(1.0, max(0.0, fraction)) * (finite.size - 1)
    low = int(math.floor(position))
    high = min(finite.size - 1, low + 1)
    part = position - low
    return float(finite[low] * (1 - part) + finite[high] * part)


def _image_features_one(frame: np.ndarray) -> tuple[float, ...]:
    image = np.asarray(frame, dtype=np.float32)
    finite = np.sort(image[np.isfinite(image)].astype(np.float64))
    if finite.size < 2:
        return (math.nan,) * 17
    p1, p50, p95, p99 = (_quantile(finite, q) for q in (0.01, 0.5, 0.95, 0.99))
    span = max(1e-9, p99 - p1)
    mean = float(np.sum(finite) / finite.size)
    centred = finite - mean
    m2 = float(np.sum(centred ** 2) / finite.size)
    m3 = float(np.sum(centred ** 3) / finite.size)
    m4 = float(np.sum(centred ** 4) / finite.size)
    skew = m3 / m2 ** 1.5 if m2 > 0 else 0.0
    kurtosis = m4 / (m2 * m2) - 3 if m2 > 0 else 0.0
    horizontal = np.abs(image[:, 1:].astype(float) - image[:, :-1].astype(float)).ravel()
    vertical = np.abs(image[1:, :].astype(float) - image[:-1, :].astype(float)).ravel()
    gradients = np.concatenate((horizontal, vertical))
    median = ndimage.median_filter(image, size=3, mode="nearest")
    kernel = np.asarray([1, 4, 6, 4, 1], dtype=np.float32)
    blur = ndimage.convolve1d(image, kernel, axis=1, mode="nearest") / 16.0
    blur = ndimage.convolve1d(blur, kernel, axis=0, mode="nearest") / 16.0
    impulse = np.abs(image.astype(float) - median.astype(float)).ravel()
    blur_residual = np.abs(image.astype(float) - blur.astype(float)).ravel()
    normalized = np.clip((finite - p1) / span, 0, 1)
    bins = np.bincount(np.minimum(31, np.floor(32 * normalized).astype(int)), minlength=32)
    probability = bins[bins > 0] / finite.size
    entropy = float(-np.sum(probability * np.log2(probability)) / 5.0)
    return (
        (p50 - p1) / span, (p99 - p95) / span, (p95 - p50) / span,
        (finite[-1] - p99) / span,
        float(np.count_nonzero(finite <= p1 + 0.05 * span) / finite.size),
        float(np.count_nonzero(finite >= p1 + 0.95 * span) / finite.size),
        skew, kurtosis, _quantile(gradients, 0.5) / span,
        _quantile(gradients, 0.9) / span, _quantile(impulse, 0.5) / span,
        _quantile(impulse, 0.9) / span, _quantile(blur_residual, 0.5) / span,
        _quantile(blur_residual, 0.9) / span, entropy,
        _neighbour_correlation(image, True), _neighbour_correlation(image, False),
    )


def _neighbour_correlation(frame: np.ndarray, horizontal: bool) -> float:
    a, b = (frame[:, :-1], frame[:, 1:]) if horizontal else (frame[:-1], frame[1:])
    valid = np.isfinite(a) & np.isfinite(b)
    if np.count_nonzero(valid) < 2:
        return math.nan
    av, bv = a[valid].astype(float), b[valid].astype(float)
    av -= float(np.sum(av) / av.size)
    bv -= float(np.sum(bv) / bv.size)
    denominator = math.sqrt(float(np.sum(av * av) * np.sum(bv * bv)))
    return float(np.sum(av * bv) / denominator) if denominator > 0 else math.nan


def _image_features(frames: np.ndarray) -> tuple[float, ...]:
    indices = (0, len(frames) // 2, len(frames) - 1)
    measured = np.asarray([_image_features_one(frames[index]) for index in indices])
    return tuple(_quantile(measured[:, index], 0.5) for index in range(17))


def _motion_features(cumulative: Sequence[Transform]) -> tuple[float, ...]:
    if len(cumulative) < 2 or any(not all(math.isfinite(value) for value in
            (item.dx, item.dy, item.theta)) for item in cumulative):
        return (math.nan,) * 10
    x = np.asarray([item.dx for item in cumulative], dtype=float)
    y = np.asarray([item.dy for item in cumulative], dtype=float)
    dx, dy = np.diff(x), np.diff(y)
    steps = np.hypot(dx, dy)
    acceleration = np.hypot(np.diff(dx), np.diff(dy))
    median_step = _quantile(steps, 0.5)
    total = float(np.sum(steps))
    displacement = math.hypot(x[-1] - x[0], y[-1] - y[0])
    reach = np.hypot(x - x[0], y - y[0])
    p90_reach = _quantile(reach, 0.9)
    t = np.arange(len(x), dtype=float)
    mt = 0.5 * (len(x) - 1)
    mx, my = float(np.sum(x) / len(x)), float(np.sum(y) / len(y))
    tt = float(np.sum((t - mt) ** 2))
    bx = float(np.sum((t - mt) * (x - mx)) / tt) if tt > 0 else 0.0
    by = float(np.sum((t - mt) * (y - my)) / tt) if tt > 0 else 0.0
    residual = math.sqrt(float(np.sum((x - mx - bx * (t - mt)) ** 2
                                      + (y - my - by * (t - mt)) ** 2) / len(x)))
    return (median_step, _quantile(steps, 0.9), _quantile(steps, 1.0),
            _quantile(steps, 1.0) / (median_step + 1e-9),
            displacement / (total + 1e-9), residual / (p90_reach + 1e-9),
            _quantile(acceleration, 0.5), _quantile(acceleration, 0.9),
            _quantile(acceleration, 1.0), p90_reach)


def _phase_shift(first: np.ndarray, second: np.ndarray) -> tuple[float, float]:
    height, width = first.shape
    size = 1 << (max(width, height) - 1).bit_length()
    def taper(length: int) -> np.ndarray:
        edge = max(1, int(math.floor(0.25 * length / 2 + 0.5)))
        distance = np.minimum(np.arange(length), np.arange(length)[::-1])
        return np.where(distance >= edge, 1.0,
                        0.5 - 0.5 * np.cos(np.pi * (distance + 0.5) / edge))
    window = taper(height)[:, None] * taper(width)[None, :]
    a = np.zeros((size, size), dtype=float)
    b = np.zeros_like(a)
    a[:height, :width] = (first.astype(float) - float(np.sum(first) / first.size)) * window
    b[:height, :width] = (second.astype(float) - float(np.sum(second) / second.size)) * window
    fa, fb = np.fft.fft2(a), np.fft.fft2(b)
    valid = (np.abs(fa) >= 1e-6 * np.max(np.abs(fa))) & (np.abs(fb) >= 1e-6 * np.max(np.abs(fb)))
    cross = fb * np.conj(fa)
    normalized = np.divide(cross, np.abs(cross), out=np.zeros_like(cross), where=valid & (np.abs(cross) > 0))
    surface = np.fft.ifft2(normalized).real
    py, px = np.unravel_index(int(np.argmax(surface)), surface.shape)
    def parabola(axis: int) -> float:
        before = surface[py, (px - 1) % size] if axis == 1 else surface[(py - 1) % size, px]
        at = surface[py, px]
        after = surface[py, (px + 1) % size] if axis == 1 else surface[(py + 1) % size, px]
        denominator = before - 2 * at + after
        return float(np.clip(0.5 * (before - after) / denominator, -0.5, 0.5)) if abs(denominator) >= 1e-20 else 0.0
    dx, dy = px + parabola(1), py + parabola(0)
    return (dx - size if dx > size / 2 else dx,
            dy - size if dy > size / 2 else dy)


def _information_features(frames: np.ndarray, epsilon: float) -> tuple[float, float, float]:
    first, second = frames[0], frames[1]
    finite = np.sort(first[np.isfinite(first)].astype(float))
    if not finite.size:
        return math.nan, math.nan, math.nan
    def rounded(fraction: float) -> float:
        return float(finite[int(math.floor(fraction * (finite.size - 1) + 0.5))])
    sparse = (finite[finite.size // 2] - rounded(0.01)) / max(1e-9, rounded(0.99) - rounded(0.01))
    dx, dy = _phase_shift(first, second)
    y, x = np.mgrid[:first.shape[0], :first.shape[1]]
    tx, ty = x + dx, y + dy
    inside = (tx >= 0) & (ty >= 0) & (tx <= first.shape[1] - 1) & (ty <= first.shape[0] - 1)
    sampled = ndimage.map_coordinates(second.astype(float), (ty, tx), order=1, mode="nearest")
    valid = inside & np.isfinite(first) & np.isfinite(sampled) & (first + epsilon > 0) & (sampled + epsilon > 0)
    residual = np.log2(sampled[valid] + epsilon) - np.log2(first[valid] + epsilon)
    if not residual.size:
        return sparse, math.nan, math.nan
    residual = np.sort(residual)
    gain = residual[residual.size // 2]
    absolute = np.sort(np.abs(residual - gain))
    scale = max(1e-4, 1.4826 * absolute[absolute.size // 2])
    outliers = float(np.count_nonzero(absolute > 4.685 * scale) / absolute.size)
    q95 = absolute[int(0.95 * (absolute.size - 1))] / scale
    q99 = absolute[int(0.99 * (absolute.size - 1))] / scale
    return sparse, q99 / max(1e-9, q95), outliers


def measure(frames: np.ndarray, cumulative: Sequence[Transform],
            parameters: LogRatioParameters) -> Evidence:
    frames = np.asarray(frames, dtype=np.float32)
    reason = ""
    if len(frames) < 2:
        reason = "fewer than two guide frames"
    elif frames.shape[1] < 16 or frames.shape[2] < 16:
        reason = "guide plane smaller than 16x16"
    else:
        sample = frames[[0, len(frames) // 2, len(frames) - 1]]
        finite = sample[np.isfinite(sample)]
        if not finite.size or not float(np.max(finite)) > float(np.min(finite)):
            reason = "no finite positive raw-image dynamic range"
    if reason:
        return Evidence((math.nan,) * len(FEATURE_NAMES), False, reason,
                        parameters.image_type, parameters.motion_type)
    image = _image_features(frames)
    motion = _motion_features(cumulative)
    sparse, tail, outliers = _information_features(frames, parameters.epsilon)
    plane = LogPlane.from_intensity(frames[0], parameters.epsilon)
    magnitudes = plane.gradient_magnitude[plane.valid]
    if not magnitudes.size:
        admitted = (math.nan, math.nan)
    else:
        threshold = np.sort(magnitudes.astype(float))[magnitudes.size // 2] * 0.5
        admitted = (float(np.count_nonzero(magnitudes >= threshold) / magnitudes.size),
                    float(np.count_nonzero(plane.raw_gradient[plane.valid]
                                           >= 0.5 * plane.raw_noise_sigma) / magnitudes.size))
    values = (*image, *motion, sparse, tail, outliers, *admitted,
              *(1.0 if parameters.image_type is item else 0.0 for item in ImageType),
              *(1.0 if parameters.motion_type is item else 0.0 for item in MotionType),
              *(1.0 if parameters.norm is item else 0.0 for item in RobustNorm),
              *(1.0 if parameters.reference is item else 0.0 for item in Reference))
    for index, value in enumerate(values):
        if not math.isfinite(value):
            return Evidence(tuple(values), False, f"non-finite required feature: {FEATURE_NAMES[index]}",
                            parameters.image_type, parameters.motion_type)
    return Evidence(tuple(values), True, "", parameters.image_type, parameters.motion_type)


def _apply_candidate(base: LogRatioParameters, candidate: Candidate) -> LogRatioParameters:
    floors = {"FULL": (math.nan, math.nan), "NO_TOP_10": (math.nan, 90.0),
              "NO_TOP_25": (math.nan, 75.0), "NO_BOTTOM_25": (25.0, math.nan)}
    floor, ceiling = floors[candidate.band]
    mask = (PixelSelectionStrategy.NONE if candidate.mask == "NONE"
            else PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE)
    return replace(base, selection_mode=SelectionMode.MANUAL,
                   estimator=candidate.estimator, pixel_support=candidate.support,
                   gradient_fraction=0.5, floor_percentile=floor,
                   ceiling_percentile=ceiling, preprocessing=candidate.preprocessing,
                   pixel_selection_strategy=mask,
                   pixel_selection_preprocessing=Preprocessing.NONE,
                   pixel_removal_percent=25.0, max_iterations=25, max_samples=200_000)


def _category(requested: LogRatioParameters) -> LogRatioParameters:
    return recommendation(requested.image_type, requested.motion_type).apply(requested)


def select(requested: LogRatioParameters, evidence: Evidence | None = None
           ) -> tuple[LogRatioParameters, AutomaticSelection]:
    base = _category(requested)
    if requested.image_type in {
        ImageType.DENSE_FLUORESCENCE,
        ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
    }:
        if requested.image_type is ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE:
            # This is the exact declared image-and-motion preset, including the
            # motion-specific Log-ratio settings already resolved in ``base``.
            explicit = replace(base, selection_mode=SelectionMode.MANUAL)
            reason = "fixed_route=sparse_lowlight_image_and_motion_logratio_preset"
        else:
            explicit = replace(
                base,
                selection_mode=SelectionMode.MANUAL,
                estimator=Estimator.AREA_CORRELATION_ECC,
                reference=Reference.CONSECUTIVE,
                preprocessing=Preprocessing.MEDIAN_3X3,
                pixel_support=PixelSupport.GRADIENT,
                gradient_fraction=0.5,
                pixel_selection_strategy=PixelSelectionStrategy.NONE,
                pixel_selection_preprocessing=Preprocessing.NONE,
                floor_percentile=math.nan,
                ceiling_percentile=90.0,
                outlier_mads=0.0,
                outlier_protection_residual_gain=math.nan,
                max_iterations=25,
                max_samples=200_000,
            )
            reason = "fixed_route=dense_filtered_previous_image_ecc"
        recipe_id = _recipe_id(explicit)
        provenance = (
            f"selector_model={EMISSION_POLICY_VERSION}; "
            f"model_kind={FLUORESCENCE_POLICY_KIND}; "
            f"validation={FLUORESCENCE_POLICY_VALIDATION}; "
            f"feature_contract={FLUORESCENCE_POLICY_CONTRACT}; "
            f"protocol_sha256={FLUORESCENCE_POLICY_PROTOCOL_SHA256}; "
            f"candidate_manifest_sha256={FLUORESCENCE_POLICY_CANDIDATE_SHA256}; "
            f"model_artifact_sha256={FLUORESCENCE_POLICY_ARTIFACT_SHA256}; "
            f"selected_recipe={recipe_id}; predicted_gain_px=0; "
            f"confidence_threshold_px=0; fallback=false; reason={reason}"
        )
        explicit = replace(explicit, recipe_provenance=provenance)
        return explicit, AutomaticSelection(
            False, 0.0, 0.0, recipe_id, reason,
            model_version=EMISSION_POLICY_VERSION, evidence=None,
        )
    # Java selects translation independently, then applies its separate frozen rotation layer.
    eligible = [item for item in CANDIDATES if item.image_type is requested.image_type]
    fallback_reason = "no validated candidate serves this scope"
    if MODEL_KIND == "linear_gain" and eligible:
        if evidence is None or not evidence.valid:
            fallback_reason = "invalid selector evidence: " + (evidence.reason if evidence else "missing evidence")
            eligible = []
        elif evidence.distribution()[0]:
            fallback_reason = "out-of-distribution selector evidence: " + evidence.distribution()[1]
            eligible = []
    standardised = ((np.asarray(evidence.values) - FEATURE_MEAN) /
                    np.where(FEATURE_SCALE > 0, FEATURE_SCALE, 1)) if evidence else np.zeros(len(FEATURE_NAMES))
    scored = []
    for candidate in eligible:
        gain = candidate.training_gain if MODEL_KIND == "image_type_rule" else (
            candidate.intercept + float(np.dot(candidate.weights, standardised)))
        scored.append((gain, candidate))
    gain, chosen = max(scored, default=(0.0, None), key=lambda item: item[0])
    fallback = chosen is None or gain < CONFIDENCE_THRESHOLD
    if fallback and chosen is not None:
        fallback_reason = (f"best predicted gain {gain:.4f} px did not clear "
                           f"{CONFIDENCE_THRESHOLD:.4f} px")
    recipe_id = _recipe_id(base) if fallback else chosen.recipe_id
    provenance = (f"selector_model={MODEL_VERSION}; model_kind={MODEL_KIND}; validation={VALIDATION_STATUS}; "
                  f"feature_contract={FEATURE_CONTRACT_VERSION}; protocol_sha256={PROTOCOL_SHA256}; "
                  f"candidate_manifest_sha256={CANDIDATE_MANIFEST_SHA256}; "
                  f"model_artifact_sha256={MODEL_ARTIFACT_SHA256}; selected_recipe={recipe_id}; "
                  f"predicted_gain_px={gain:.17g}; confidence_threshold_px={CONFIDENCE_THRESHOLD:.17g}; "
                  f"fallback={str(fallback).lower()}; reason={fallback_reason if fallback else ('fixed automatic policy' if MODEL_KIND == 'image_type_rule' else '')}")
    explicit = replace(base if fallback else _apply_candidate(base, chosen),
                       selection_mode=SelectionMode.MANUAL, recipe_provenance=provenance)
    reason = (fallback_reason if fallback else
              "fixed automatic policy" if MODEL_KIND == "image_type_rule" else "")
    return explicit, AutomaticSelection(fallback, gain, CONFIDENCE_THRESHOLD, recipe_id,
                                        reason, evidence=evidence)


def _recipe_id(parameters: LogRatioParameters) -> str:
    band = ("full" if math.isnan(parameters.floor_percentile) and math.isnan(parameters.ceiling_percentile)
            else "no_top_10" if parameters.ceiling_percentile == 90 else
            "no_top_25" if parameters.ceiling_percentile == 75 else
            "no_bottom_25" if parameters.floor_percentile == 25 else "custom")
    mask = "none" if parameters.pixel_selection_strategy is PixelSelectionStrategy.NONE else "least_informative_25"
    prefix = "" if parameters.estimator is Estimator.LOG_RATIO_FIT else f"estimator_{parameters.estimator.value}__"
    recipe_id = (f"{prefix}support_{parameters.pixel_support.value}__band_{band}__"
                 f"filter_{parameters.preprocessing.value}__mask_{mask}")
    swept_filters = {
        Preprocessing.NONE, Preprocessing.GAUSSIAN_0_7,
        Preprocessing.GAUSSIAN_1_0, Preprocessing.MEDIAN_3X3,
    }
    known_mask = parameters.pixel_selection_strategy in {
        PixelSelectionStrategy.NONE,
        PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
    }
    if parameters.estimator is Estimator.LOG_RATIO_FIT:
        swept = (
            band != "custom"
            and parameters.gradient_fraction == 0.5
            and known_mask
            and parameters.pixel_selection_preprocessing is Preprocessing.NONE
            and (parameters.pixel_selection_strategy is PixelSelectionStrategy.NONE
                 or parameters.pixel_removal_percent == 25.0)
            and parameters.max_iterations == 25
            and parameters.max_samples == 200_000
            and parameters.preprocessing in swept_filters
        )
    else:
        swept = (
            band != "custom"
            and parameters.pixel_support is PixelSupport.ALL
            and parameters.pixel_selection_strategy is PixelSelectionStrategy.NONE
            and parameters.max_iterations == 25
            and parameters.max_samples == 200_000
            and parameters.preprocessing in swept_filters
        )
    return recipe_id if swept else recipe_id + "__offgrid"
