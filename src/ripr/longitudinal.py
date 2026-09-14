"""One-channel maximum-accuracy registration for long microscopy recordings.

The route is selected from the declared image type.  It uses fixed bright/dim
references for emission images and a median edge/dark-landmark reference for
transmitted-light images.  A longitudinal trajectory guard removes brief
brightness-coupled excursions but preserves persistent stage jumps.  It is not
the general Automatic route: repeated oscillation and continuous rotation are
outside the evidence that accepted it.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
import math
from typing import Sequence

import cv2
import numpy as np
from scipy import ndimage
from skimage.registration import phase_cross_correlation

from .core import AlignerOptions, RegistrationOptions, RegistrationResult, register_frames, warp_plane
from .parameters import LogRatioParameters
from .types import (
    Estimator,
    ImageType,
    Interpolation,
    PixelSupport,
    Reference,
    RobustNorm,
    Transform,
)


@dataclass(frozen=True)
class LongitudinalDiagnostics:
    """The fixed route and the small number of trajectory changes it made."""

    route: str
    bright_reference_frame: int | None = None
    dim_reference_frame: int | None = None
    weak_frames: tuple[int, ...] = ()
    persistent_jump_frames: tuple[int, ...] = ()
    rigid_jump_frames: tuple[int, ...] = ()
    endpoint_jump_frame: int | None = None


def _percentile_normalise(image: np.ndarray, low: float, high: float) -> np.ndarray:
    value = np.asarray(image, dtype=np.float32)
    lo, hi = np.percentile(value[np.isfinite(value)], (low, high))
    return np.clip((value - lo) / max(float(hi - lo), 1e-6), 0.0, 1.0).astype(np.float32)


def _robust_scale(image: np.ndarray) -> np.ndarray:
    value = np.asarray(image, dtype=np.float32)
    finite = value[np.isfinite(value)]
    centre = float(np.median(finite)) if finite.size else 0.0
    scale = float(1.4826 * np.median(np.abs(finite - centre))) if finite.size else 0.0
    return np.clip((value - centre) / max(scale, 1e-5), -5.0, 5.0).astype(np.float32)


def emission_feature(frame: np.ndarray) -> np.ndarray:
    """Remove smooth light changes while retaining same-channel structure."""

    image = np.asarray(frame, dtype=np.float32)
    low, high = np.percentile(image, (1.0, 99.0))
    image = np.clip(
        (image - float(low)) / max(float(high - low), 1e-6), 0.0, 1.0
    )
    sigma = max(2.0, min(image.shape) / 32.0)
    feature = image - cv2.GaussianBlur(image, (0, 0), sigma)
    scale = 1.4826 * np.median(np.abs(feature - np.median(feature)))
    return np.clip(feature / max(float(scale), 1e-5), -5.0, 5.0).astype(np.float32)


def landmark_feature(frame: np.ndarray) -> np.ndarray:
    """Retain tissue edges and locally dark marks in phase/brightfield images."""

    image = _percentile_normalise(frame, 1.0, 99.0)
    size = float(min(image.shape))
    fine = ndimage.gaussian_filter(image, max(0.8, size / 512.0), mode="nearest")
    middle = ndimage.gaussian_filter(image, max(3.0, size / 64.0), mode="nearest")
    broad = ndimage.gaussian_filter(image, max(8.0, size / 20.0), mode="nearest")
    dark_fine = np.maximum(middle - fine, 0.0)
    dark_broad = np.maximum(broad - middle, 0.0)
    edge_source = ndimage.gaussian_filter(image, max(1.5, size / 256.0), mode="nearest")
    smooth = np.asarray([3.0, 10.0, 3.0], dtype=np.float32)
    derivative = np.asarray([-1.0, 0.0, 1.0], dtype=np.float32)
    gx = ndimage.convolve1d(
        ndimage.convolve1d(edge_source, smooth, axis=0, mode="nearest"),
        derivative, axis=1, mode="nearest",
    )
    gy = ndimage.convolve1d(
        ndimage.convolve1d(edge_source, smooth, axis=1, mode="nearest"),
        derivative, axis=0, mode="nearest",
    )
    edges = np.hypot(gx, gy)
    feature = 0.65 * _robust_scale(edges) + _robust_scale(dark_fine)
    feature += 0.55 * _robust_scale(dark_broad)
    margin_y = max(2, round(0.04 * image.shape[0]))
    margin_x = max(2, round(0.04 * image.shape[1]))
    feature[:margin_y] = feature[-margin_y:] = 0.0
    feature[:, :margin_x] = feature[:, -margin_x:] = 0.0
    return (_robust_scale(feature) + 6.0).astype(np.float32)


def frame_light_and_structure(frames: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    height, width = frames.shape[1:]
    centre = np.asarray(
        frames[:, round(0.12 * height):round(0.88 * height),
               round(0.12 * width):round(0.88 * width)],
        dtype=np.float32,
    )
    light = np.percentile(centre, 75.0, axis=(1, 2))
    structure = np.percentile(centre, 95.0, axis=(1, 2))
    structure -= np.percentile(centre, 5.0, axis=(1, 2))
    return np.asarray(light), np.asarray(structure)


def choose_reference_frames(frames: np.ndarray) -> tuple[int, int]:
    """Choose bright and dim frames that both retain visible structure."""

    light, structure = frame_light_and_structure(frames)
    usable = structure >= max(1e-6, 0.35 * float(np.median(structure)))
    if int(usable.sum()) < 4:
        usable[:] = True
    indices = np.flatnonzero(usable)
    return int(indices[np.argmax(light[indices])]), int(indices[np.argmin(light[indices])])


def _fit_options(
    shape: tuple[int, int], parameters: LogRatioParameters, reference: int,
    *, fit_rotation: bool,
) -> RegistrationOptions:
    diagonal = math.hypot(*shape)
    aligner = AlignerOptions(
        max_levels=9,
        min_coarse_size=24,
        max_shift=max(parameters.max_shift, 0.60 * diagonal),
        coarse_radius_budget=8,
        norm=RobustNorm.LEAST_SQUARES,
        profile_gain=True,
        support=PixelSupport.ALL,
        fit_rotation=fit_rotation,
        max_rotation=math.radians(parameters.max_rotation_degrees),
        max_iterations=max(25, parameters.max_iterations),
        convergence=1e-4,
        min_valid_fraction=parameters.min_valid_fraction,
        max_samples=parameters.max_samples,
    )
    return RegistrationOptions(
        aligner=aligner,
        estimator=Estimator.AREA_CORRELATION_ECC,
        reference=Reference.FIXED,
        reference_frame=reference,
        threads=parameters.threads,
        auto_max_shift=False,
        outlier_mads=0.0,
    )


def _correlation(reference: np.ndarray, moving: np.ndarray, transform: Transform) -> float:
    aligned = warp_plane(moving, transform, Interpolation.BILINEAR, np.nan)
    valid = np.isfinite(reference) & np.isfinite(aligned)
    if int(valid.sum()) < 64:
        return 0.0
    left = reference[valid].astype(np.float64)
    right = aligned[valid].astype(np.float64)
    left -= left.mean()
    right -= right.mean()
    denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
    return float(left @ right / denominator) if denominator > 0 else 0.0


def _normalise_transforms(transforms: Sequence[Transform]) -> list[Transform]:
    inverse_first = transforms[0].inverse()
    return [inverse_first.then(transform) for transform in transforms]


def _protect_terminal_events(
    trajectory: np.ndarray,
    confidence: np.ndarray,
    agreement: np.ndarray,
    bright_scores: np.ndarray,
    dim_scores: np.ndarray,
    baseline_trajectory: np.ndarray,
    diagonal: float,
) -> None:
    """Keep a well-supported late remount from becoming a smooth dark-frame ramp."""

    gap_limit = max(1.5, 0.003 * diagonal)
    threshold = max(2.5, 0.006 * diagonal)
    tail = max(2, min(8, math.ceil(0.04 * len(trajectory))))
    for frame in range(max(1, len(trajectory) - tail), len(trajectory)):
        if confidence[frame] > 0.0 or agreement[frame] > gap_limit:
            continue
        movement = float(np.linalg.norm(trajectory[frame] - trajectory[frame - 1]))
        if movement < threshold:
            continue
        two_reference_evidence = (
            bright_scores[frame] >= 0.18 and dim_scores[frame] >= 0.18
        )
        baseline_movement = float(np.linalg.norm(
            baseline_trajectory[frame] - baseline_trajectory[frame - 1]
        ))
        baseline_match = (
            baseline_movement >= threshold
            and float(np.linalg.norm(
                trajectory[frame] - baseline_trajectory[frame]
            )) <= max(gap_limit, 0.005 * diagonal)
        )
        if not (two_reference_evidence or baseline_match):
            continue
        if baseline_match and not two_reference_evidence:
            # The nearly dark image cannot improve the measured position.  Keep
            # the preliminary jump instead of shaving pixels from it.
            trajectory[frame] = baseline_trajectory[frame]
            if confidence[frame - 1] <= 0.0:
                trajectory[frame - 1] = baseline_trajectory[frame - 1]
        confidence[frame] = 1e-6
        # Give the jump detector one stable left-side anchor so a run of weak
        # dark frames cannot be interpolated into the user's rejected swoosh.
        confidence[frame - 1] = max(confidence[frame - 1], 1e-6)


def _dual_reference_trajectory(
    frames: np.ndarray, baseline: Sequence[Transform], parameters: LogRatioParameters,
) -> tuple[np.ndarray, np.ndarray, int, int]:
    cv2.setNumThreads(1)
    features = [emission_feature(frame) for frame in frames]
    bright, dim = choose_reference_frames(frames)
    height, width = frames.shape[1:]
    radius = 0.5 * math.hypot(width, height)
    cx, cy = (width - 1) / 2.0, (height - 1) / 2.0

    def matrix(value: Transform) -> np.ndarray:
        cosine, sine = math.cos(value.theta), math.sin(value.theta)
        return np.asarray([
            [cosine, -sine,
             cx - cosine * cx + sine * cy + value.dx],
            [sine, cosine,
             cy - sine * cx - cosine * cy + value.dy],
            [0.0, 0.0, 1.0],
        ], dtype=np.float64)

    def transform(value: np.ndarray) -> Transform:
        return Transform(
            float(value[0, 0] * cx + value[0, 1] * cy + value[0, 2] - cx),
            float(value[1, 0] * cx + value[1, 1] * cy + value[1, 2] - cy),
            math.atan2(float(value[1, 0]), float(value[0, 0])),
        )

    def phase_initial(reference: np.ndarray, moving: np.ndarray) -> np.ndarray:
        shift, _, _ = phase_cross_correlation(
            reference, moving, upsample_factor=10, normalization="phase"
        )
        if not np.all(np.isfinite(shift)):
            raise RuntimeError("non-finite phase shift")
        return np.asarray([
            [1.0, 0.0, -float(shift[1])],
            [0.0, 1.0, -float(shift[0])],
            [0.0, 0.0, 1.0],
        ])

    filter_size = max(1, round(5.0 * min(frames.shape[1:]) / 512.0))
    if filter_size % 2 == 0:
        filter_size += 1

    def estimate_pair(
        reference: np.ndarray, moving: np.ndarray, starts: Sequence[np.ndarray]
    ) -> tuple[np.ndarray, float]:
        candidates: list[np.ndarray] = []
        try:
            candidates.append(phase_initial(reference, moving))
        except (RuntimeError, ValueError):
            pass
        candidates.extend(np.asarray(value, dtype=np.float64) for value in starts)
        candidates.append(np.eye(3, dtype=np.float64))
        winner: np.ndarray | None = None
        winner_score = -math.inf
        criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 100, 1e-7)
        seen: set[tuple[float, ...]] = set()
        for initial in candidates:
            key = tuple(np.round(initial[:2].ravel(), 3))
            if key in seen:
                continue
            seen.add(key)
            try:
                score, fitted = cv2.findTransformECC(
                    reference,
                    moving,
                    initial[:2].astype(np.float32),
                    cv2.MOTION_EUCLIDEAN,
                    criteria,
                    None,
                    filter_size,
                )
            except cv2.error:
                continue
            if (np.all(np.isfinite(fitted)) and math.isfinite(float(score))
                    and score > winner_score):
                winner = np.eye(3, dtype=np.float64)
                winner[:2] = fitted
                winner_score = float(score)
        if winner is None:
            raise RuntimeError("all bright/dim Enhanced Correlation Coefficient fits failed")
        return winner, winner_score

    baseline_matrices = [matrix(value) for value in baseline]
    bridge_initial = baseline_matrices[dim] @ np.linalg.inv(baseline_matrices[bright])
    bridge, bridge_score = (
        estimate_pair(features[bright], features[dim], [bridge_initial])
        if dim != bright else (np.eye(3, dtype=np.float64), 1.0)
    )

    def fit_one(frame: int):
        if frame == bright:
            from_bright, bright_score = np.eye(3, dtype=np.float64), 1.0
        else:
            inherited = baseline_matrices[frame] @ np.linalg.inv(baseline_matrices[bright])
            from_bright, bright_score = estimate_pair(
                features[bright], features[frame], [inherited]
            )
        if frame == dim:
            from_dim, dim_score = np.eye(3, dtype=np.float64), 1.0
        else:
            inherited = baseline_matrices[frame] @ np.linalg.inv(baseline_matrices[dim])
            from_dim, dim_score = estimate_pair(
                features[dim], features[frame], [inherited]
            )
        dim_in_bright = from_dim @ bridge
        dim_route_score = min(float(dim_score), float(bridge_score))
        pose_b, pose_d = transform(from_bright), transform(dim_in_bright)
        agreement = math.sqrt(
            (pose_b.dx - pose_d.dx) ** 2
            + (pose_b.dy - pose_d.dy) ** 2
            + (radius * (pose_b.theta - pose_d.theta)) ** 2
        )
        return (
            from_bright if float(bright_score) >= dim_route_score else dim_in_bright,
            float(bright_score), dim_route_score, agreement,
        )

    workers = max(1, min(int(parameters.threads), len(features)))
    if workers == 1:
        fitted = [fit_one(frame) for frame in range(len(features))]
    else:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            fitted = list(pool.map(fit_one, range(len(features))))
    selected = [item[0] for item in fitted]
    bright_scores = np.asarray([item[1] for item in fitted], dtype=np.float64)
    dim_scores = np.asarray([item[2] for item in fitted], dtype=np.float64)
    agreements = np.asarray([item[3] for item in fitted], dtype=np.float64)
    inverse_zero = np.linalg.inv(selected[0])
    selected = [value @ inverse_zero for value in selected]
    selected_transforms = [transform(value) for value in selected]
    trajectory = np.asarray([
        [value.dx, value.dy, radius * value.theta] for value in selected_transforms
    ], dtype=np.float64)
    values = np.maximum(bright_scores, dim_scores)
    median = float(np.median(values))
    mad = float(1.4826 * np.median(np.abs(values - median)))
    floor = max(0.18, min(0.50, median - 2.5 * mad))
    confidence = np.clip((values - floor) / max(median - floor, 0.05), 0.0, 1.0)
    diagonal = math.hypot(*frames.shape[1:])
    gap_limit = max(1.5, 0.003 * diagonal)
    score_gap = np.abs(bright_scores - dim_scores)
    confidence[(agreements > gap_limit) & (score_gap < 0.05)] = 0.0
    # A terminal remount can contain only one or two frames.  Near darkness its
    # absolute correlation may fall below the ordinary floor even though both
    # light-state references find the same new position.  Preserve that event
    # when the two routes agree, or when they also reproduce a matching jump in
    # the accepted preliminary path.  This is confined to the final 4% of the
    # recording and cannot turn an ordinary weak middle frame into a jump.
    baseline_normalised = _normalise_transforms(baseline)
    baseline_trajectory = np.asarray([
        [item.dx, item.dy,
         0.5 * diagonal * item.theta]
        for item in baseline_normalised
    ])
    _protect_terminal_events(
        trajectory, confidence, agreements, bright_scores, dim_scores,
        baseline_trajectory, diagonal,
    )
    return trajectory, confidence, bright, dim


def _landmark_reference(
    features: np.ndarray, baseline: Sequence[Transform],
) -> np.ndarray:
    aligned = []
    valid = []
    for feature, transform in zip(features, baseline, strict=True):
        warped = warp_plane(feature, transform, Interpolation.BILINEAR, np.nan)
        aligned.append(warped)
        valid.append(np.isfinite(warped))
    values = np.stack(aligned)
    with np.errstate(all="ignore"):
        reference = np.nanmedian(values, axis=0).astype(np.float32)
    support = np.mean(np.stack(valid), axis=0) >= 0.80
    salience = np.abs(reference - 6.0)
    if not np.any(support):
        raise RuntimeError("no common landmark support")
    threshold = float(np.percentile(salience[support], 55.0))
    size = max(1, round(7.0 * min(reference.shape) / 512.0))
    mask = ndimage.binary_dilation(support & (salience >= threshold),
                                   structure=np.ones((size, size), dtype=bool))
    mask &= support
    if int(mask.sum()) < max(16, round(0.0009765625 * mask.size)):
        raise RuntimeError("insufficient edge and dark-landmark support")
    return np.where(mask, reference, np.nan).astype(np.float32)


def _landmark_trajectory(
    frames: np.ndarray, baseline: Sequence[Transform], parameters: LogRatioParameters,
) -> tuple[np.ndarray, np.ndarray]:
    features = np.stack([landmark_feature(frame) for frame in frames])
    reference = _landmark_reference(features, baseline)
    fitting = np.concatenate(([reference], features), axis=0)
    starts = (Transform(), *baseline)
    result = register_frames(
        fitting,
        _fit_options(frames.shape[1:], parameters, 0, fit_rotation=False),
        starts=starts,
    )
    transforms = _normalise_transforms(result.cumulative[1:])
    trajectory = np.asarray([[value.dx, value.dy, 0.0] for value in transforms])
    scores = np.asarray([
        _correlation(reference, feature, transform)
        for feature, transform in zip(features, transforms, strict=True)
    ])
    median = float(np.median(scores))
    mad = float(1.4826 * np.median(np.abs(scores - median)))
    floor = max(0.15, min(0.45, median - 3.0 * mad))
    confidence = np.clip((scores - floor) / max(median - floor, 0.05), 0.0, 1.0)
    return trajectory, confidence


def _landmark_thumbnail(feature: np.ndarray, maximum_size: int = 192) -> np.ndarray:
    """Make a proportionally scaled structural thumbnail for event screening."""

    value = np.asarray(feature, dtype=np.float32) - 6.0
    scale = min(1.0, maximum_size / max(value.shape))
    if scale < 1.0:
        value = ndimage.zoom(
            value, scale, order=1, mode="nearest", prefilter=False
        ).astype(np.float32)
    return value


def _phase_translation(
    reference: np.ndarray, moving: np.ndarray,
) -> tuple[float, float, float]:
    """Return the translation from reference to moving and its phase peak."""

    left = np.asarray(reference, dtype=np.float64)
    right = np.asarray(moving, dtype=np.float64)
    if left.shape != right.shape or left.ndim != 2:
        raise ValueError("phase-correlation inputs must be equal-size 2-D images")
    valid = np.isfinite(left) & np.isfinite(right)
    if int(valid.sum()) < max(64, round(0.10 * valid.size)):
        return 0.0, 0.0, 0.0
    left_fill = float(np.median(left[np.isfinite(left)]))
    right_fill = float(np.median(right[np.isfinite(right)]))
    left = np.where(np.isfinite(left), left, left_fill)
    right = np.where(np.isfinite(right), right, right_fill)
    window = np.outer(np.hanning(left.shape[0]), np.hanning(left.shape[1]))
    left = (left - float(np.mean(left[valid]))) * window
    right = (right - float(np.mean(right[valid]))) * window
    cross = np.conj(np.fft.fftn(left)) * np.fft.fftn(right)
    cross /= np.maximum(np.abs(cross), 1e-12)
    correlation = np.fft.ifftn(cross).real
    peak_y, peak_x = np.unravel_index(np.argmax(correlation), correlation.shape)
    dy = float(peak_y if peak_y <= left.shape[0] // 2 else peak_y - left.shape[0])
    dx = float(peak_x if peak_x <= left.shape[1] // 2 else peak_x - left.shape[1])
    return dx, dy, float(correlation[peak_y, peak_x])


def _coarse_rigid_seed(
    reference: np.ndarray, moving: np.ndarray, maximum_rotation_degrees: float,
) -> tuple[Transform, float]:
    """Search rotation on thumbnails, with phase correlation supplying translation."""

    left = _landmark_thumbnail(reference)
    right = _landmark_thumbnail(moving)
    scale_x = left.shape[1] / reference.shape[1]
    scale_y = left.shape[0] / reference.shape[0]
    maximum = max(15.0, float(maximum_rotation_degrees))
    step = max(0.25, math.degrees(0.75 / max(1.0, 0.5 * math.hypot(*left.shape))))
    count = max(1, math.ceil(maximum / step))
    angles = np.linspace(-maximum, maximum, 2 * count + 1)
    winner = Transform()
    winner_peak = -math.inf
    for degrees in angles:
        theta = math.radians(float(degrees))
        rotated = warp_plane(
            right, Transform(theta=theta), Interpolation.BILINEAR, np.nan
        )
        shift_x, shift_y, peak = _phase_translation(left, rotated)
        cosine, sine = math.cos(theta), math.sin(theta)
        dx = cosine * shift_x / scale_x - sine * shift_y / scale_y
        dy = sine * shift_x / scale_x + cosine * shift_y / scale_y
        if peak > winner_peak:
            winner = Transform(dx, dy, theta)
            winner_peak = peak
    return winner, float(winner_peak)


def _refine_correlation(
    reference: np.ndarray,
    moving: np.ndarray,
    seed: Transform,
    *,
    steps: Sequence[float],
) -> tuple[Transform, float]:
    """Maximise gain-invariant correlation near one globally checked seed."""

    radius = max(1.0, 0.5 * math.hypot(*reference.shape))
    accepted = seed
    score = _correlation(reference, moving, accepted)
    if not math.isfinite(score):
        raise RuntimeError("correlation refinement has insufficient support")
    for step in steps:
        for _ in range(4):
            winner, winner_score = accepted, score
            for angle_step in (-1, 0, 1):
                for y_step in (-1, 0, 1):
                    for x_step in (-1, 0, 1):
                        if angle_step == 0 and y_step == 0 and x_step == 0:
                            continue
                        candidate = Transform(
                            accepted.dx + x_step * step,
                            accepted.dy + y_step * step,
                            accepted.theta + angle_step * step / radius,
                        )
                        candidate_score = _correlation(reference, moving, candidate)
                        if candidate_score > winner_score:
                            winner, winner_score = candidate, candidate_score
            if winner == accepted:
                break
            accepted, score = winner, winner_score
    return accepted, score


def _refine_rigid_bridge(
    before: np.ndarray, after: np.ndarray, parameters: LogRatioParameters,
) -> tuple[Transform, float]:
    left, right = landmark_feature(before), landmark_feature(after)
    seed, peak = _coarse_rigid_seed(
        left, right, parameters.max_rotation_degrees
    )
    # Directly optimise the same edge/dark-landmark score used to approve the
    # bridge.  This avoids a log-intensity detour, which under-rotated the known
    # 10.74-degree brightfield remount.
    accepted, _ = _refine_correlation(
        left, right, seed, steps=(2.0, 1.0, 0.5, 0.25, 0.125)
    )
    return accepted, peak


def _low_confidence_boundaries(features: Sequence[np.ndarray]) -> list[int]:
    thumbnails = [_landmark_thumbnail(feature, 128) for feature in features]
    responses = np.asarray([
        _phase_translation(thumbnails[index - 1], thumbnails[index])[2]
        for index in range(1, len(thumbnails))
    ])
    if not len(responses):
        return []
    cutoff = min(0.20, 0.35 * float(np.median(responses)))
    return [index + 1 for index, response in enumerate(responses) if response < cutoff]


def _detect_rigid_bridges(
    frames: np.ndarray, parameters: LogRatioParameters,
) -> list[tuple[int, Transform]]:
    """Accept a rare rigid jump only when a frame pair and two blocks agree."""

    features = [landmark_feature(frame) for frame in frames]
    diagonal = math.hypot(*frames.shape[1:])
    radius = 0.5 * diagonal
    events: list[tuple[int, Transform]] = []
    for boundary in _low_confidence_boundaries(features):
        width = min(8, boundary, len(frames) - boundary)
        if width < 3:
            continue
        try:
            pair, _ = _refine_rigid_bridge(
                frames[boundary - 1], frames[boundary], parameters
            )
            block, _ = _refine_rigid_bridge(
                np.median(frames[boundary - width:boundary], axis=0),
                np.median(frames[boundary:boundary + width], axis=0),
                parameters,
            )
        except RuntimeError:
            continue
        disagreement = math.hypot(pair.dx - block.dx, pair.dy - block.dy)
        angular_disagreement = abs(pair.theta - block.theta)
        movement = max(block.magnitude, radius * abs(block.theta))
        if disagreement > 0.01 * diagonal:
            continue
        if angular_disagreement > math.radians(1.0):
            continue
        if movement < 0.005 * diagonal:
            continue
        events.append((boundary, block))
    return events


def _apply_rigid_bridges(
    transforms: Sequence[Transform], events: Sequence[tuple[int, Transform]],
) -> list[Transform]:
    result = list(transforms)
    for boundary, event in events:
        predicted = result[boundary - 1].inverse().then(result[boundary])
        correction = predicted.inverse().then(event)
        for frame in range(boundary, len(result)):
            result[frame] = result[frame].then(correction)
    return result


def _terminal_rigid_chain(
    frames: np.ndarray,
    baseline: Sequence[Transform],
    parameters: LogRatioParameters,
) -> list[tuple[int, Transform]]:
    """Recover adjacent final remounts without mistaking a returning pulse for drift.

    A block comparison cannot represent two genuine jumps on consecutive frames: its
    before block contains both positions.  At the recording endpoint, use consecutive
    rigid fits instead, but only when the fixed Automatic trajectory independently
    reports the same large, persistent move.  The final gentle pair is included so a
    two-frame endpoint does not become a repeated last transform.
    """

    count = len(frames)
    if count < 6 or len(baseline) != count:
        return []
    diagonal = math.hypot(*frames.shape[1:])
    radius = 0.5 * diagonal
    increments = {
        boundary: baseline[boundary - 1].inverse().then(baseline[boundary])
        for boundary in range(1, count)
    }
    movement = {
        boundary: max(value.magnitude, radius * abs(value.theta))
        for boundary, value in increments.items()
    }
    values = np.asarray(list(movement.values()), dtype=np.float64)
    ordinary_values = values[values <= np.percentile(values, 80.0)]
    ordinary = float(np.median(ordinary_values)) if ordinary_values.size else 0.0
    threshold = max(0.04 * diagonal, 8.0 * max(ordinary, 1.0))
    tail = max(4, min(8, math.ceil(0.04 * count)))
    large = [
        boundary for boundary in range(max(1, count - tail), count - 1)
        if movement[boundary] >= threshold
    ]
    if not large:
        return []

    # Use only the final consecutive group.  A separated earlier movement has its own
    # stable block and belongs to the ordinary persistent-jump route.
    group = [large[-1]]
    for boundary in reversed(large[:-1]):
        if group[0] - boundary != 1:
            break
        group.insert(0, boundary)
    first, last = group[0], group[-1]
    if len(group) > 3 or count - last < 2:
        return []
    persistent = baseline[first - 1].inverse().then(baseline[-1])
    if max(persistent.magnitude, radius * abs(persistent.theta)) < threshold:
        return []

    events: list[tuple[int, Transform]] = []
    for boundary in range(first, count):
        try:
            event, score = _refine_rigid_bridge(
                frames[boundary - 1], frames[boundary], parameters
            )
        except RuntimeError:
            return []
        if score < 0.15:
            return []
        if boundary in group:
            expected = increments[boundary]
            disagreement = math.hypot(event.dx - expected.dx, event.dy - expected.dy)
            dot = event.dx * expected.dx + event.dy * expected.dy
            lengths = max(event.magnitude * expected.magnitude, 1e-9)
            if max(event.magnitude, radius * abs(event.theta)) < 0.70 * threshold:
                return []
            if disagreement > 0.04 * diagonal or dot / lengths < 0.75:
                return []
        elif max(event.magnitude, radius * abs(event.theta)) >= threshold:
            # A large pair absent from the independent baseline is not sufficiently
            # supported to extend the chain.
            return []
        events.append((boundary, event))
    return events


def _persistent_jumps(
    trajectory: np.ndarray, reliable: np.ndarray, diagonal: float,
) -> list[int]:
    threshold = max(2.5, 0.006 * diagonal)
    gentle = max(1.5, 0.004 * diagonal)
    candidates: list[tuple[float, int]] = []
    count = len(trajectory)
    for boundary in range(1, count):
        left = trajectory[max(0, boundary - 6):boundary]
        right = trajectory[boundary:min(count, boundary + 6)]
        left_centre = np.median(left, axis=0)
        right_centre = np.median(right, axis=0)
        displacement = float(np.linalg.norm(right_centre - left_centre))
        instant = float(np.linalg.norm(trajectory[boundary] - trajectory[boundary - 1]))
        if displacement < threshold or instant < 0.55 * threshold:
            continue
        if boundary != count - 1:
            near = trajectory[boundary:min(count, boundary + 3)]
            far = trajectory[min(count, boundary + 3):min(count, boundary + 8)]
            if len(far) >= 2 and float(np.linalg.norm(
                np.median(near, axis=0) - np.median(far, axis=0)
            )) > max(gentle, 0.30 * displacement):
                continue
        left_scatter = float(np.median(np.linalg.norm(left - left_centre, axis=1)))
        right_scatter = float(np.median(np.linalg.norm(right - right_centre, axis=1)))
        if left_scatter > max(gentle, 0.35 * displacement):
            continue
        if len(right) > 1 and right_scatter > max(gentle, 0.35 * displacement):
            continue
        if not (np.any(reliable[max(0, boundary - 3):boundary])
                and np.any(reliable[boundary:min(count, boundary + 3)])):
            continue
        candidates.append((displacement + instant, boundary))
    selected: list[int] = []
    for _, boundary in sorted(candidates, reverse=True):
        if all(abs(boundary - other) > 2 for other in selected):
            selected.append(boundary)
    return sorted(selected)


def _fill_weak(values: np.ndarray, reliable: np.ndarray) -> np.ndarray:
    result = values.copy()
    anchors = np.flatnonzero(reliable)
    if not len(anchors):
        result[:] = np.median(values, axis=0)
        return result
    for frame in np.flatnonzero(~reliable):
        before = anchors[anchors < frame]
        after = anchors[anchors > frame]
        if len(before) and len(after):
            left, right = int(before[-1]), int(after[0])
            fraction = (frame - left) / float(right - left)
            result[frame] = (1.0 - fraction) * result[left] + fraction * result[right]
        elif len(before):
            result[frame] = result[int(before[-1])]
        else:
            result[frame] = result[int(after[0])]
    return result


def _remove_returning_excursions(values: np.ndarray, diagonal: float) -> np.ndarray:
    result = values.copy()
    threshold = max(2.5, 0.006 * diagonal)
    for _ in range(3):
        changed = False
        for span in range(2, min(13, len(result))):
            for start in range(len(result) - span):
                end = start + span
                line = np.linspace(result[start], result[end], span + 1)
                deviation = np.linalg.norm(result[start:end + 1] - line, axis=1)
                path = float(np.linalg.norm(np.diff(result[start:end + 1], axis=0), axis=1).sum())
                endpoint = float(np.linalg.norm(result[end] - result[start]))
                if float(deviation.max()) > threshold and path - endpoint > 2.0 * threshold:
                    result[start + 1:end] = line[1:-1]
                    changed = True
        if not changed:
            break
    return result


def repair_trajectory(
    trajectory: np.ndarray, confidence: np.ndarray, diagonal: float,
) -> tuple[np.ndarray, tuple[int, ...], tuple[int, ...]]:
    """Apply the accepted longitudinal prior without crossing persistent jumps."""

    values = np.asarray(trajectory, dtype=np.float64)
    reliable = np.asarray(confidence, dtype=np.float64) > 0.0
    jumps = _persistent_jumps(values, reliable, diagonal)
    repaired = values.copy()
    for start, end in zip([0, *jumps], [*jumps, len(values)], strict=True):
        repaired[start:end] = _remove_returning_excursions(
            _fill_weak(values[start:end], reliable[start:end]), diagonal
        )
    repaired -= repaired[0]
    changed = tuple(int(value) for value in np.flatnonzero(
        np.linalg.norm(repaired - (values - values[0]), axis=1) > 1e-9
    ))
    return repaired, tuple(jumps), changed


def _bright_tissue_centroid(frame: np.ndarray) -> tuple[np.ndarray, float]:
    height, width = frame.shape
    scale = min(1.0, 512.0 / min(height, width))
    small = ndimage.zoom(np.asarray(frame, dtype=np.float32), scale, order=1,
                         mode="nearest", prefilter=False)
    normalised = _percentile_normalise(small, 20.0, 99.8)
    kernel = max(3, round(9.0 * scale))
    mask = ndimage.binary_opening(normalised > 0.22,
                                  structure=np.ones((kernel, kernel), dtype=bool))
    labels, count = ndimage.label(mask)
    if count < 1:
        raise RuntimeError("no bright tissue component")
    sizes = ndimage.sum(mask, labels, range(1, count + 1))
    component = 1 + int(np.argmax(sizes))
    area = float(sizes[component - 1] / mask.size)
    if area < 0.003:
        raise RuntimeError("bright tissue component is too small")
    centre_y, centre_x = ndimage.center_of_mass(mask, labels, component)
    return np.asarray([centre_x / scale, centre_y / scale]), area


def _repair_endpoint(
    frames: np.ndarray, transforms: list[Transform], *, threads: int = 1,
) -> tuple[list[Transform], int | None]:
    if len(frames) < 6:
        return transforms, None
    try:
        workers = max(1, min(int(threads), len(frames)))
        if workers == 1:
            measured = [_bright_tissue_centroid(frame) for frame in frames]
        else:
            with ThreadPoolExecutor(max_workers=workers) as pool:
                measured = list(pool.map(_bright_tissue_centroid, frames))
    except RuntimeError:
        return transforms, None
    centroids = np.stack([value[0] for value in measured])
    areas = np.asarray([value[1] for value in measured])
    diagonal = math.hypot(*frames.shape[1:])
    raw_steps = np.linalg.norm(np.diff(centroids, axis=0), axis=1)
    ordinary = float(np.median(raw_steps[raw_steps <= np.percentile(raw_steps, 80.0)]))
    cx, cy = (frames.shape[2] - 1) / 2.0, (frames.shape[1] - 1) / 2.0
    aligned = np.stack([
        transform.inverse().apply(point[0], point[1], cx, cy)
        for transform, point in zip(transforms, centroids, strict=True)
    ])
    tail = max(2, min(8, math.ceil(0.04 * len(frames))))
    candidates = []
    for boundary in range(max(2, len(frames) - tail), len(frames) - 1):
        width = min(4, boundary, len(frames) - boundary)
        if width < 2:
            continue
        before = centroids[boundary - width:boundary]
        after = centroids[boundary:boundary + width]
        before_centre, after_centre = np.median(before, axis=0), np.median(after, axis=0)
        delta = after_centre - before_centre
        movement = float(np.linalg.norm(delta))
        residual = float(np.linalg.norm(
            np.median(aligned[boundary:boundary + width], axis=0)
            - np.median(aligned[boundary - width:boundary], axis=0)
        ))
        spread = max(
            float(np.max(np.linalg.norm(before - before_centre, axis=1))),
            float(np.max(np.linalg.norm(after - after_centre, axis=1))),
        )
        area_ratio = float(np.median(areas[boundary:boundary + width])
                           / max(np.median(areas[boundary - width:boundary]), 1e-9))
        if movement < max(0.08 * diagonal, 8.0 * max(ordinary, 1.0)):
            continue
        if residual < 0.02 * diagonal or spread > 0.02 * diagonal:
            continue
        if not 0.40 <= area_ratio <= 2.50:
            continue
        candidates.append((residual, boundary, Transform.translation(*delta)))
    if not candidates:
        return transforms, None
    _, boundary, event = max(candidates)
    predicted = transforms[boundary - 1].inverse().then(transforms[boundary])
    correction = predicted.inverse().then(event)
    result = list(transforms)
    for frame in range(boundary, len(result)):
        result[frame] = result[frame].then(correction)
    return result, boundary


def refine(
    frames: np.ndarray,
    baseline: RegistrationResult,
    parameters: LogRatioParameters,
) -> tuple[RegistrationResult, LongitudinalDiagnostics]:
    """Refine one selected channel using the fixed declared-image-type route."""

    values = np.asarray(frames, dtype=np.float32)
    if values.ndim != 3 or len(values) < 2:
        raise ValueError("longitudinal input must be a time-by-y-by-x stack")
    if len(baseline.cumulative) != len(values):
        raise ValueError("baseline and input have different frame counts")
    if parameters.fit_rotation:
        raise ValueError(
            "Longitudinal maximum accuracy does not support continuous rotation; "
            "use Automatic for continuously rotating recordings"
        )
    transmitted = parameters.image_type in {
        ImageType.PHASE_CONTRAST, ImageType.BRIGHTFIELD_DIC,
    }
    if transmitted:
        trajectory, confidence = _landmark_trajectory(
            values, baseline.cumulative, parameters
        )
        bright = dim = None
        route = "edge_dark_landmarks"
    else:
        trajectory, confidence, bright, dim = _dual_reference_trajectory(
            values, baseline.cumulative, parameters
        )
        route = "bright_dim_references"
    diagonal = math.hypot(*values.shape[1:])
    repaired, jumps, changed = repair_trajectory(trajectory, confidence, diagonal)
    radius = 0.5 * diagonal
    transforms = [Transform(float(row[0]), float(row[1]), float(row[2] / radius))
                  for row in repaired]
    rigid_events: list[tuple[int, Transform]] = []
    if transmitted:
        rigid_events = _detect_rigid_bridges(values, parameters)
        transforms = _apply_rigid_bridges(transforms, rigid_events)
        if rigid_events:
            route = "edge_dark_landmarks_guarded_rigid_jump"
    endpoint = None
    terminal_events: list[tuple[int, Transform]] = []
    if not transmitted:
        transforms, endpoint = _repair_endpoint(
            values, transforms, threads=parameters.threads
        )
        terminal_events = _terminal_rigid_chain(values, baseline.cumulative, parameters)
        transforms = _apply_rigid_bridges(transforms, terminal_events)
        if terminal_events:
            route = "bright_dim_references_guarded_terminal_rigid_chain"
    warnings = baseline.warnings + (
        "Longitudinal maximum accuracy: whole-stack one-channel refinement; "
        "not validated for repeated oscillation or continuous rotation.",
    )
    result = replace(baseline, cumulative=tuple(transforms), warnings=warnings)
    diagnostics = LongitudinalDiagnostics(
        route=route,
        bright_reference_frame=None if bright is None else bright + 1,
        dim_reference_frame=None if dim is None else dim + 1,
        weak_frames=tuple(int(value) + 1 for value in np.flatnonzero(confidence <= 0.0)),
        persistent_jump_frames=tuple(value + 1 for value in jumps),
        rigid_jump_frames=tuple(
            value + 1 for value, _ in (*rigid_events, *terminal_events)
        ),
        endpoint_jump_frame=(
            terminal_events[0][0] + 1 if terminal_events
            else None if endpoint is None else endpoint + 1
        ),
    )
    return result, diagnostics
