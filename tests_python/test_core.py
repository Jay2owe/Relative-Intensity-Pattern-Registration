import math

import numpy as np
import pytest

from ripr import (
    Estimator,
    Interpolation,
    LogPlane,
    LogRatioParameters,
    PixelSupport,
    Reference,
    RegistrationOptions,
    RotationEventStatus,
    RotationMode,
    RobustNorm,
    Transform,
    register,
    valid_margin,
    warp_plane,
)
from ripr.core import PairFit, PairResult, _reconcile, _repair, register_frames
from ripr.types import RepairReason, Status


def analytic_frame(width, height, dx=0.0, dy=0.0, gain=1.0, offset=0.0):
    y, x = np.mgrid[:height, :width]
    u = 2 * np.pi * (x - dx) / width
    v = 2 * np.pi * (y - dy) / height
    signal = np.sin(3 * u + 0.4) * np.cos(2 * v - 0.7)
    signal += 0.70 * np.sin(5 * u - 1.1) * np.cos(7 * v + 0.2)
    signal += 0.45 * np.cos(11 * u + 0.9) * np.sin(6 * v + 1.3)
    signal += 0.30 * np.sin(13 * u + 2.1)
    signal += 0.30 * np.cos(9 * v - 0.5)
    return (gain * (2000.0 + 600.0 * signal) + offset).astype(np.float32)


def manual(**changes):
    values = dict(
        reference=Reference.CONSECUTIVE,
        auto_max_shift=False,
        max_shift=8,
        crop=False,
        norm=RobustNorm.HUBER,
        pixel_support=PixelSupport.ALL,
        threads=1,
    )
    values.update(changes)
    return LogRatioParameters.manual(**values)


def test_transform_composition_and_inverse():
    transform = Transform(4.5, -2.25, 0.17)
    round_trip = transform.then(transform.inverse())
    assert round_trip.dx == pytest.approx(0, abs=1e-12)
    assert round_trip.dy == pytest.approx(0, abs=1e-12)
    assert round_trip.theta == pytest.approx(0, abs=1e-15)
    assert Transform.translation(3, -1).then(Transform.translation(-1, 5)) == Transform.translation(2, 4)


def test_whole_pixel_warp_is_bit_exact_and_fractional_none_rounds():
    source = np.arange(24 * 32, dtype=np.uint16).reshape(24, 32)
    shifted = warp_plane(source, Transform.translation(2.4, -1.6), Interpolation.NONE, 65535)
    whole = warp_plane(source, Transform.translation(2, -2), Interpolation.NONE, 65535)
    assert np.array_equal(shifted, whole)
    assert set(np.unique(shifted)).issubset(set(np.unique(source)) | {65535})


def test_fourier_interpolates_a_fractional_translation():
    height, width = 24, 32
    cy, cx = (height - 1) / 2, (width - 1) / 2
    y, x = np.mgrid[:height, :width]
    sigma = 3.0
    source = np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * sigma**2)).astype(np.float32)
    dx, dy = 0.375, -0.625
    shifted = warp_plane(source, Transform.translation(dx, dy), Interpolation.FOURIER, 0)
    expected = np.exp(-(((x + dx) - cx) ** 2 + ((y + dy) - cy) ** 2) / (2 * sigma**2))
    inside = (x + dx >= 0) & (x + dx <= width - 1) & (y + dy >= 0) & (y + dy <= height - 1)
    assert np.max(np.abs(shifted[inside] - expected[inside])) < 5e-5


def test_fourier_whole_pixel_translation_is_bit_exact():
    source = np.arange(24 * 32, dtype=np.uint16).reshape(24, 32)
    none = warp_plane(source, Transform.translation(3, -2), Interpolation.NONE, 65535)
    fourier = warp_plane(source, Transform.translation(3, -2), Interpolation.FOURIER, 65535)
    assert np.array_equal(fourier, none)


def test_fourier_interpolates_a_rigid_transform():
    height, width = 48, 64
    image_cy, image_cx = (height - 1) / 2, (width - 1) / 2
    peak_y, peak_x = image_cy + 2, image_cx - 3
    sigma_y, sigma_x = 2.5, 5.0
    y, x = np.mgrid[:height, :width]
    source = np.exp(-0.5 * (
        ((x - peak_x) / sigma_x) ** 2 + ((y - peak_y) / sigma_y) ** 2
    )).astype(np.float32)
    for angle in (0.22, math.radians(100)):
        transform = Transform(0.7, -0.45, angle)
        shifted = warp_plane(source, transform, Interpolation.FOURIER, 0)
        source_x, source_y = transform.apply(x, y, image_cx, image_cy)
        expected = np.exp(-0.5 * (
            ((source_x - peak_x) / sigma_x) ** 2
            + ((source_y - peak_y) / sigma_y) ** 2
        ))
        inside = (
            (source_x >= 0) & (source_x <= width - 1)
            & (source_y >= 0) & (source_y <= height - 1)
        )
        assert np.max(np.abs(shifted[inside] - expected[inside])) < 1e-5
    parameters = LogRatioParameters.manual(
        fit_rotation=True, interpolation=Interpolation.FOURIER
    )
    assert parameters.fit_rotation


def test_valid_margin_matches_java_translation_contract():
    transforms = (Transform(), Transform.translation(3, -2), Transform.translation(-5, 4))
    margin = valid_margin(transforms, 100, 100, Interpolation.NONE)
    assert (margin.top, margin.bottom, margin.left, margin.right) == (2, 4, 5, 3)


def test_log_plane_keeps_nan_separate_and_builds_pyramid():
    image = analytic_frame(96, 80)
    image[5, 7] = np.nan
    plane = LogPlane.from_intensity(image, epsilon=1)
    pyramid = plane.pyramid(2)
    assert not plane.valid[5, 7]
    assert pyramid[1].value.shape == (40, 48)
    assert np.all(np.isfinite(plane.gx))


@pytest.mark.parametrize("estimator", [Estimator.LOG_RATIO_FIT,
                                        Estimator.AREA_CORRELATION_NEWTON,
                                        Estimator.AREA_CORRELATION_ECC])
def test_estimators_recover_same_subpixel_drift_and_gain(estimator):
    frames = np.stack(
        [analytic_frame(96, 96, 1.25 * t, -0.7 * t, 2 ** (-t / 8)) for t in range(5)]
    )
    result = register(frames, manual(estimator=estimator))
    for t, transform in enumerate(result.transforms):
        assert transform.dx == pytest.approx(1.25 * t, abs=0.04)
        assert transform.dy == pytest.approx(-0.7 * t, abs=0.04)
    assert result.registration.log2_gain[-1] == pytest.approx(-0.5, abs=0.02)
    assert result.median_residual_after < result.median_residual_before / 10


def test_gain_fade_does_not_change_geometry():
    steady = np.stack([analytic_frame(80, 80, 0.9 * t, 0) for t in range(4)])
    fading = np.stack([analytic_frame(80, 80, 0.9 * t, 0, 0.4 ** (t / 3)) for t in range(4)])
    a = register(steady, manual())
    b = register(fading, manual())
    assert [item.dx for item in a.transforms] == pytest.approx([item.dx for item in b.transforms], abs=0.02)


def test_bounded_rigid_fit_recovers_translation_rotation_and_gain():
    base = analytic_frame(128, 128)
    truth = (
        Transform(),
        Transform(1.25, -0.75, math.radians(1.5)),
        Transform(2.0, -1.2, math.radians(2.4)),
    )
    frames = np.stack([
        warp_plane(base, transform.inverse(), Interpolation.BILINEAR, 2000)
        * 2 ** (-index / 8)
        for index, transform in enumerate(truth)
    ])
    result = register(frames, manual(
        reference=Reference.FIXED,
        reference_frame=1,
        fit_rotation=True,
        max_rotation_degrees=5,
        interpolation=Interpolation.BILINEAR,
    ))
    for recovered, expected in zip(result.transforms, truth):
        assert recovered.dx == pytest.approx(expected.dx, abs=0.02)
        assert recovered.dy == pytest.approx(expected.dy, abs=0.02)
        assert recovered.theta == pytest.approx(expected.theta, abs=math.radians(0.03))
    assert result.registration.log2_gain[-1] == pytest.approx(-0.25, abs=0.02)


@pytest.mark.parametrize("estimator", [Estimator.AREA_CORRELATION,
                                        Estimator.AREA_CORRELATION_NEWTON,
                                        Estimator.AREA_CORRELATION_ECC])
def test_area_correlation_recovers_bounded_rigid_motion(estimator):
    base = analytic_frame(128, 128)
    truth = Transform(1.25, -0.75, math.radians(1.5))
    frames = np.stack((base, warp_plane(
        base, truth.inverse(), Interpolation.BILINEAR, 2000
    )))
    result = register(frames, manual(
        estimator=estimator,
        fit_rotation=True,
        max_rotation_degrees=5,
        interpolation=Interpolation.BILINEAR,
    ))
    recovered = result.transforms[1]
    assert recovered.dx == pytest.approx(truth.dx, abs=0.10)
    assert recovered.dy == pytest.approx(truth.dy, abs=0.10)
    assert recovered.theta == pytest.approx(truth.theta, abs=math.radians(0.10))


def test_area_correlation_honours_an_inherited_rigid_start():
    base = analytic_frame(96, 96)
    truth = Transform(11.0, -7.0, math.radians(4.0))
    frames = np.stack((base, warp_plane(
        base, truth.inverse(), Interpolation.BILINEAR, 2000
    )))
    options = manual(
        estimator=Estimator.AREA_CORRELATION_ECC,
        reference=Reference.FIXED,
        reference_frame=1,
        fit_rotation=True,
        max_shift=20,
        max_rotation_degrees=8,
    ).registration_options()

    result = register_frames(frames, options, starts=(Transform(), truth))

    recovered = result.cumulative[1]
    assert recovered.dx == pytest.approx(truth.dx, abs=0.20)
    assert recovered.dy == pytest.approx(truth.dy, abs=0.20)
    assert recovered.theta == pytest.approx(truth.theta, abs=math.radians(0.20))


def test_exact_rigid_multilag_reconciliation_and_rotation_aware_repair():
    truth = (
        Transform(),
        Transform(3, -2, math.radians(15)),
        Transform(7, 1, math.radians(-8)),
        Transform(10, 4, math.radians(22)),
    )
    pairs = []
    for source, target in ((0, 1), (1, 2), (2, 3), (0, 2), (1, 3), (3, 0)):
        relative = truth[source].inverse().then(truth[target])
        fit = PairFit(relative, 1, 0, 0, 1, 1, Status.OK)
        pairs.append(PairResult(source, target, fit))
    cumulative, support = _reconcile(Reference.MULTILAG, len(truth), 0, pairs)
    for recovered, expected in zip(cumulative, truth):
        assert recovered.dx == pytest.approx(expected.dx, abs=1e-7)
        assert recovered.dy == pytest.approx(expected.dy, abs=1e-7)
        assert recovered.theta == pytest.approx(expected.theta, abs=1e-7)

    angular = [Transform(theta=math.radians(0.1 * t)) for t in range(20)]
    angular[10] = Transform(theta=math.radians(60))
    repaired, reasons = _repair(angular, np.ones(20, dtype=np.int32), 0, 8, 512, 512)
    assert reasons[10] is RepairReason.OUTLIER_STEP
    assert repaired[10].theta == pytest.approx(math.radians(1.0), abs=1e-12)


def test_known_event_consensus_and_fixed_angle_translation_are_piecewise_exact():
    base = analytic_frame(96, 96)
    event = 4
    delta = math.radians(2.0)
    truth = [
        Transform(0.35 * frame, -0.22 * frame, 0 if frame < event else delta)
        for frame in range(8)
    ]
    frames = np.stack([
        warp_plane(base, transform.inverse(), Interpolation.BILINEAR, 2000)
        * 2 ** (-frame / 20)
        for frame, transform in enumerate(truth)
    ])
    result = register(frames, manual(
        reference=Reference.MULTILAG,
        lags=(1, 2, 4),
        rotation_mode=RotationMode.KNOWN_EVENTS,
        rotation_event_frames=(event + 1,),
        rotation_event_window=2,
        max_rotation_degrees=5,
        interpolation=Interpolation.BILINEAR,
    ))

    events = result.registration.event_rotations
    assert events is not None
    estimate = events.events[0]
    assert estimate.public_frame == 5
    assert estimate.candidate_pairs == 4
    assert estimate.usable_pairs >= 3
    assert estimate.status is RotationEventStatus.OK
    assert estimate.delta_theta == pytest.approx(delta, abs=math.radians(0.03))
    assert len(set(events.frame_angles[:event])) == 1
    assert len(set(events.frame_angles[event:])) == 1
    for frame, (recovered, expected) in enumerate(zip(result.transforms, truth)):
        assert recovered.theta == events.frame_angles[frame]
        assert recovered.dx == pytest.approx(expected.dx, abs=0.04)
        assert recovered.dy == pytest.approx(expected.dy, abs=0.04)


def test_known_event_refuses_insufficient_boundary_support():
    base = analytic_frame(64, 64)
    frames = np.stack((base, base, base))
    with pytest.raises(ValueError, match="at least 3"):
        register(frames, manual(
            rotation_mode=RotationMode.KNOWN_EVENTS,
            rotation_event_frames=(3,),
            rotation_event_window=1,
        ))


def test_core_event_fields_cannot_silently_affect_another_mode():
    frames = np.stack((analytic_frame(32, 32),) * 3)
    with pytest.raises(ValueError, match="require rotation mode known_events"):
        register_frames(frames, RegistrationOptions(rotation_event_frames=(1,)))


def test_known_event_repair_protects_boundary_and_never_changes_angles():
    cumulative = [Transform(frame, 0, 99 + frame) for frame in range(20)]
    cumulative[10] = Transform(500, 0, 109)
    angles = [0.0] * 10 + [math.radians(2)] * 10
    protected = [False] * 20
    protected[10] = True
    repaired, reasons = _repair(
        cumulative, np.ones(20, dtype=np.int32), 0, 8, 512, 512,
        known_angles=angles, protected_event_frames=protected,
    )
    assert reasons[10] is None
    assert [item.theta for item in repaired] == angles


def test_pair_evidence_can_protect_a_gross_step_from_generic_repair():
    cumulative = [Transform.translation(frame, 0) for frame in range(20)]
    for frame in range(10, 20):
        cumulative[frame] = Transform.translation(500 + frame, 0)
    protected = [False] * 20
    protected[10] = True
    repaired, reasons = _repair(
        cumulative, np.ones(20, dtype=np.int32), 0, 8, 512, 512,
        protected_event_frames=protected,
    )
    assert repaired[10] == cumulative[10]
    assert reasons[10] is None
