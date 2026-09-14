"""High-level NumPy API: axis handling, two-pass masks, and output warping."""

from __future__ import annotations

from dataclasses import dataclass, replace
import itertools
import math
from typing import Callable, Sequence

import numpy as np
from scipy import ndimage

from .core import (
    LogPlane,
    RegistrationResult,
    estimate_shift_bound,
    register_frames,
    valid_margin,
    warp_plane,
)
from .parameters import LogRatioParameters
from .recording_selector import (
    AutomaticSelection,
    measure as measure_selector_evidence,
    neutral_pilot,
    requires_evidence as selector_requires_evidence,
    select as select_automatic,
)
from .preprocessing import apply_preprocessing, area_average
from .types import (
    Estimator,
    Interpolation,
    PixelSelectionStrategy,
    Preprocessing,
    Reference,
    SelectionMode,
    Transform,
)


@dataclass(frozen=True)
class LogRatioResult:
    """Corrected pixels, movement diagnostics, and the explicit settings that ran."""

    corrected: np.ndarray
    registration: RegistrationResult
    parameters: LogRatioParameters
    axes: str
    input_shape: tuple[int, ...]
    automatic_selection: AutomaticSelection | None = None

    @property
    def transforms(self) -> tuple[Transform, ...]:
        return self.registration.cumulative

    @property
    def median_residual_before(self) -> float:
        return self.registration.median_residual_before

    @property
    def median_residual_after(self) -> float:
        return self.registration.median_residual_after

    @property
    def provenance(self) -> str:
        return self.parameters.recipe_provenance


def infer_axes(image: np.ndarray, axes: str | None = None) -> str:
    """Infer common array layouts; explicit axes remain preferable for hyperstacks."""
    ndim = np.ndim(image)
    if axes is None:
        choices = {2: "YX", 3: "TYX", 4: "TCYX", 5: "TCZYX"}
        if ndim not in choices:
            raise ValueError("axes are required for arrays outside 2-D through 5-D")
        return choices[ndim]
    axes = axes.upper()
    if len(axes) != ndim or len(set(axes)) != len(axes):
        raise ValueError(f"axes {axes!r} do not describe a {ndim}-D array")
    aliases = {"Q": "T", "I": "T", "S": "C"}
    normalized = "".join(aliases.get(axis, axis) for axis in axes)
    if len(set(normalized)) != len(normalized):
        raise ValueError(f"axes {axes!r} become ambiguous after standardising Q/I to T and S to C")
    if "Y" not in normalized or "X" not in normalized:
        raise ValueError("axes must contain Y and X")
    return normalized


def _estimation_frames(image: np.ndarray, axes: str, parameters: LogRatioParameters) -> np.ndarray:
    array = np.asarray(image)
    selectors = [slice(None)] * array.ndim
    if "C" in axes:
        channel_axis = axes.index("C")
        if parameters.channel > array.shape[channel_axis]:
            raise ValueError(f"channel {parameters.channel} outside 1..{array.shape[channel_axis]}")
        selectors[channel_axis] = parameters.channel - 1
    elif parameters.channel != 1:
        raise ValueError("the input has no channel axis")
    selected = array[tuple(selectors)]
    selected_axes = "".join(axis for i, axis in enumerate(axes) if not isinstance(selectors[i], int))
    if "Z" in selected_axes:
        z_axis = selected_axes.index("Z")
        if parameters.slice == 0:
            selected = np.nanmax(selected, axis=z_axis)
        else:
            if parameters.slice > selected.shape[z_axis]:
                raise ValueError(f"slice {parameters.slice} outside 1..{selected.shape[z_axis]}")
            selected = np.take(selected, parameters.slice - 1, axis=z_axis)
        selected_axes = selected_axes.replace("Z", "")
    elif parameters.slice not in (0, 1):
        raise ValueError("the input has no Z axis")
    extras = [axis for axis in selected_axes if axis not in "TYX"]
    if extras:
        raise ValueError(f"unsupported estimation axes: {extras}")
    if "T" not in selected_axes:
        selected = np.expand_dims(selected, 0)
        selected_axes = "T" + selected_axes
    order = [selected_axes.index(axis) for axis in "TYX"]
    return np.transpose(selected, order).astype(np.float32, copy=True)


def _prepare_frames(frames: np.ndarray, preprocessing: Preprocessing, scale: float) -> np.ndarray:
    output = []
    for frame in frames:
        prepared = apply_preprocessing(frame, preprocessing)
        output.append(area_average(prepared, scale) if scale != 1 else prepared)
    return np.stack(output)


def _two_axis_information(plane: LogPlane, radius: int = 2) -> np.ndarray:
    gx, gy = plane.gx.astype(float), plane.gy.astype(float)
    valid = plane.valid.astype(float)
    size = 2 * radius + 1
    count = ndimage.uniform_filter(valid, size=size, mode="constant") * size * size
    xx = ndimage.uniform_filter(gx * gx * valid, size=size, mode="constant") * size * size
    xy = ndimage.uniform_filter(gx * gy * valid, size=size, mode="constant") * size * size
    yy = ndimage.uniform_filter(gy * gy * valid, size=size, mode="constant") * size * size
    trace = xx + yy
    discriminant = np.sqrt(np.square(xx - yy) + 4 * np.square(xy))
    return np.divide(0.5 * np.maximum(0, trace - discriminant), count, out=np.zeros_like(trace), where=count > 0)


def _spatial_mask(frame: np.ndarray, epsilon: float, remove_percent: float, remove_highest: bool) -> np.ndarray:
    plane = LogPlane.from_intensity(frame, epsilon)
    gradients = plane.gradient_magnitude[plane.valid]
    threshold = 0.5 * (float(np.partition(gradients, gradients.size // 2)[gradients.size // 2]) if gradients.size else 0)
    eligible = plane.valid & (plane.gradient_magnitude >= threshold)
    information = _two_axis_information(plane)
    order = np.flatnonzero(eligible & np.isfinite(information))
    order = order[np.lexsort((order, information.ravel()[order]))]
    remove = min(order.size, int(math.floor(remove_percent * order.size / 100.0 + 0.5)))
    mask = np.ones(frame.shape, dtype=bool)
    chosen = order[-remove:] if remove_highest and remove else order[:remove]
    mask.ravel()[chosen] = False
    return mask


def _source_support(reference_mask: np.ndarray, levels: int, cumulative: Transform) -> list[np.ndarray]:
    height, width = reference_mask.shape
    source_to_reference = cumulative.inverse()
    cx, cy = (width - 1) / 2, (height - 1) / 2
    output = []
    level_width, level_height = width, height
    for level in range(levels):
        y, x = np.mgrid[:level_height, :level_width]
        scale = 1 << level
        rx, ry = source_to_reference.apply(x * scale, y * scale, cx, cy)
        rx, ry = np.floor(rx + 0.5).astype(int), np.floor(ry + 0.5).astype(int)
        inside = (rx >= 0) & (ry >= 0) & (rx < width) & (ry < height)
        support = np.zeros((level_height, level_width), dtype=bool)
        support[inside] = reference_mask[ry[inside], rx[inside]]
        output.append(support)
        level_width //= 2
        level_height //= 2
    return output


def _temporal_mask(
    frames: np.ndarray,
    cumulative: Sequence[Transform],
    parameters: LogRatioParameters,
) -> np.ndarray:
    """Temporal lag scores used by the Java plugin's opt-in mask strategies."""
    planes = [LogPlane.from_intensity(frame, parameters.epsilon) for frame in frames]
    height, width = frames.shape[1:]
    sums = np.zeros((len(parameters.lags), height, width), dtype=float)
    counts = np.zeros_like(sums, dtype=np.int32)
    cx, cy = (width - 1) / 2, (height - 1) / 2
    y, x = np.mgrid[:height, :width]
    for lag_index, lag in enumerate(parameters.lags):
        for source in range(len(frames) - lag):
            target = source + lag
            ax, ay = cumulative[source].apply(x, y, cx, cy)
            bx, by = cumulative[target].apply(x, y, cx, cy)
            av, aok = planes[source].sample(ax.ravel(), ay.ravel())
            bv, bok = planes[target].sample(bx.ravel(), by.ravel())
            ok = aok & bok
            difference = bv[ok] - av[ok]
            if difference.size == 0:
                continue
            centre = float(np.partition(difference, difference.size // 2)[difference.size // 2])
            absolute = np.abs(difference - centre)
            mad = float(np.partition(absolute, absolute.size // 2)[absolute.size // 2])
            if not mad > 0:
                k = int(0.9 * (absolute.size - 1))
                mad = float(np.partition(absolute, k)[k])
            scale = max(1e-4, 1.4826 * mad)
            values = np.zeros(height * width)
            values[np.flatnonzero(ok)] = np.minimum(8, absolute / scale)
            valid_image = ok.reshape(height, width)
            sums[lag_index][valid_image] += values.reshape(height, width)[valid_image]
            counts[lag_index][valid_image] += 1
    means = np.divide(sums, counts, out=np.full_like(sums, np.nan), where=counts > 0)
    instability = np.nanmean(means, axis=0)
    log_lags = np.log2(np.asarray(parameters.lags, dtype=float))
    growth = np.full((height, width), np.nan)
    for index in range(height * width):
        values = means[:, index // width, index % width]
        finite = np.isfinite(values)
        if np.count_nonzero(finite) >= 2:
            growth.ravel()[index] = np.polyfit(log_lags[finite], values[finite], 1)[0]
    instability = ndimage.generic_filter(instability, np.nanmean, size=7, mode="constant", cval=np.nan)
    growth = ndimage.generic_filter(growth, np.nanmean, size=7, mode="constant", cval=np.nan)
    reference_index = parameters.reference_frame - 1 if parameters.reference is Reference.FIXED else 0
    plane = planes[reference_index]
    information = _two_axis_information(plane)
    gradients = plane.gradient_magnitude[plane.valid]
    threshold = 0.5 * np.partition(gradients, gradients.size // 2)[gradients.size // 2] if gradients.size else 0
    eligible = plane.valid & (plane.gradient_magnitude >= threshold) & np.isfinite(instability)
    strategy = parameters.pixel_selection_strategy
    score = instability if strategy in (PixelSelectionStrategy.REMOVE_MOST_UNSTABLE, PixelSelectionStrategy.REMOVE_LEAST_UNSTABLE) else growth
    if strategy in (
        PixelSelectionStrategy.REMOVE_LOWEST_ANCHOR_TRUST,
        PixelSelectionStrategy.REMOVE_HIGHEST_ANCHOR_TRUST,
        PixelSelectionStrategy.STRATIFIED_LOWEST_ANCHOR_TRUST,
    ):
        def ranks(values):
            indices = np.flatnonzero(eligible & np.isfinite(values))
            indices = indices[np.lexsort((indices, values.ravel()[indices]))]
            result = np.full(values.size, np.nan)
            result[indices] = np.arange(indices.size) / max(1, indices.size - 1)
            return result.reshape(values.shape)
        score = ranks(information) - ranks(instability)
    remove_highest = strategy in (
        PixelSelectionStrategy.REMOVE_MOST_UNSTABLE,
        PixelSelectionStrategy.REMOVE_MOST_LAG_GROWTH,
        PixelSelectionStrategy.REMOVE_HIGHEST_ANCHOR_TRUST,
    )
    indices = np.flatnonzero(eligible & np.isfinite(score))
    indices = indices[np.lexsort((indices, score.ravel()[indices]))]
    remove = min(indices.size, int(math.floor(parameters.pixel_removal_percent * indices.size / 100 + 0.5)))
    mask = np.ones((height, width), dtype=bool)
    selected = indices[-remove:] if remove_highest and remove else indices[:remove]
    mask.ravel()[selected] = False
    return mask


def _estimate(frames: np.ndarray, parameters: LogRatioParameters, progress=None) -> RegistrationResult:
    prepared = _prepare_frames(frames, parameters.preprocessing, parameters.estimation_scale)
    options = parameters.registration_options()
    if parameters.estimation_scale < 1 and not options.auto_max_shift:
        options.aligner.max_shift *= parameters.estimation_scale
    strategy = parameters.pixel_selection_strategy
    if strategy is PixelSelectionStrategy.NONE:
        result = register_frames(prepared, options, progress=progress)
    else:
        if parameters.estimator is not Estimator.LOG_RATIO_FIT:
            raise ValueError("pixel selection is only defined for the log-ratio estimator")
        pilot = register_frames(prepared, options, progress=progress)
        scoring = _prepare_frames(frames, parameters.pixel_selection_preprocessing, parameters.estimation_scale)
        reference = options.reference_frame if options.reference is Reference.FIXED else 0
        if strategy in (PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE, PixelSelectionStrategy.REMOVE_MOST_INFORMATIVE):
            mask = _spatial_mask(
                scoring[reference],
                parameters.epsilon,
                parameters.pixel_removal_percent,
                strategy is PixelSelectionStrategy.REMOVE_MOST_INFORMATIVE,
            )
        else:
            mask = _temporal_mask(scoring, pilot.cumulative, parameters)
        raw = _prepare_frames(frames, Preprocessing.NONE, parameters.estimation_scale)
        raw_options = replace(
            options,
            intensity_floor=-math.inf,
            intensity_floor_percentile=math.nan,
            saturation_max=math.inf,
            saturation_percentile=math.nan,
            remove_offset=False,
        )
        if raw_options.auto_max_shift:
            _, suggested, _, _ = estimate_shift_bound(
                raw,
                raw_options,
                known_angles=(
                    pilot.event_rotations.frame_angles
                    if pilot.event_rotations is not None else None
                ),
            )
            raw_options = replace(
                raw_options,
                aligner=replace(raw_options.aligner, max_shift=suggested),
                auto_max_shift=False,
            )
        levels = raw_options.aligner.levels_for(raw.shape[2], raw.shape[1])
        support = [_source_support(mask, levels, transform) for transform in pilot.cumulative]
        result = register_frames(
            raw,
            raw_options,
            progress=progress,
            support_by_frame=support,
            starts=pilot.cumulative,
            event_rotations=pilot.event_rotations,
        )
    return result if parameters.estimation_scale == 1 else result.scale_translations(1 / parameters.estimation_scale)


def _resolve_image_aware(
    source: np.ndarray,
    axes: str,
    requested: LogRatioParameters,
    progress=None,
) -> tuple[LogRatioParameters, AutomaticSelection | None]:
    if requested.selection_mode is SelectionMode.MANUAL:
        return requested, None
    if requested.selection_mode is SelectionMode.RECOMMENDED:
        return requested.resolve(), None
    evidence = None
    # Rotation is a separate policy in Java and cannot change translation eligibility.
    if selector_requires_evidence(requested.image_type, False):
        pilot_parameters = neutral_pilot(requested)
        pilot_frames = _estimation_frames(source, axes, pilot_parameters)
        provisional = _estimate(pilot_frames, pilot_parameters, progress)
        evidence = measure_selector_evidence(
            pilot_frames, provisional.cumulative, requested
        )
    return select_automatic(requested, evidence)


def apply_transforms(
    image: np.ndarray,
    transforms: Sequence[Transform],
    axes: str,
    interpolation: Interpolation | str = Interpolation.NONE,
    crop: bool = True,
) -> np.ndarray:
    """Apply one timepoint transform to every channel and Z plane."""
    interpolation = Interpolation.parse(interpolation)
    source = np.asarray(image)
    y_axis, x_axis = axes.index("Y"), axes.index("X")
    moved = np.moveaxis(source, (y_axis, x_axis), (-2, -1))
    moved_axes = "".join(axis for i, axis in enumerate(axes) if i not in (y_axis, x_axis)) + "YX"
    time_axis = moved_axes.index("T") if "T" in moved_axes else None
    timepoints = moved.shape[time_axis] if time_axis is not None else 1
    if len(transforms) != timepoints:
        raise ValueError(f"got {len(transforms)} transforms for {timepoints} timepoints")
    output = np.empty_like(moved)
    nonspatial_shape = moved.shape[:-2]
    for index in np.ndindex(nonspatial_shape):
        time = index[time_axis] if time_axis is not None else 0
        output[index] = warp_plane(moved[index], transforms[time], interpolation, 0)
    if crop:
        height, width = moved.shape[-2:]
        margin = valid_margin(transforms, width, height, interpolation)
        y1 = height - margin.bottom if margin.bottom else height
        x1 = width - margin.right if margin.right else width
        output = output[..., margin.top:y1, margin.left:x1]
    current_y, current_x = output.ndim - 2, output.ndim - 1
    return np.moveaxis(output, (current_y, current_x), (y_axis, x_axis))


def estimate(
    image: np.ndarray,
    parameters: LogRatioParameters | None = None,
    *,
    axes: str | None = None,
    progress: Callable[[int, int], None] | None = None,
) -> RegistrationResult:
    """Estimate movement without allocating a corrected stack."""
    source = np.asarray(image)
    normalized_axes = infer_axes(source, axes)
    requested = parameters or LogRatioParameters()
    explicit, _ = _resolve_image_aware(source, normalized_axes, requested, progress)
    frames = _estimation_frames(source, normalized_axes, explicit)
    if explicit.reference_frame > frames.shape[0]:
        raise ValueError(f"reference frame {explicit.reference_frame} outside 1..{frames.shape[0]}")
    return _estimate(frames, explicit, progress)


def register(
    image: np.ndarray,
    parameters: LogRatioParameters | None = None,
    *,
    axes: str | None = None,
    progress: Callable[[int, int], None] | None = None,
) -> LogRatioResult:
    """Register an array without modifying it and return corrected pixels plus diagnostics."""
    source = np.asarray(image)
    normalized_axes = infer_axes(source, axes)
    requested = parameters or LogRatioParameters()
    explicit, automatic = _resolve_image_aware(
        source, normalized_axes, requested, progress
    )
    frames = _estimation_frames(source, normalized_axes, explicit)
    if explicit.reference_frame > frames.shape[0]:
        raise ValueError(f"reference frame {explicit.reference_frame} outside 1..{frames.shape[0]}")
    registration = _estimate(frames, explicit, progress)
    corrected = apply_transforms(source, registration.cumulative, normalized_axes, explicit.interpolation, explicit.crop)
    return LogRatioResult(corrected, registration, explicit, normalized_axes, source.shape, automatic)
