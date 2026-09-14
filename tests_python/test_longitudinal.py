from __future__ import annotations

import numpy as np
import pytest
from scipy import ndimage

from ripr import ImageType, LogRatioParameters, SelectionMode, register
from ripr.longitudinal import (
    _apply_rigid_bridges,
    _coarse_rigid_seed,
    _protect_terminal_events,
    _repair_endpoint,
    _terminal_rigid_chain,
    choose_reference_frames,
    landmark_feature,
    repair_trajectory,
)
from ripr.core import warp_plane
from ripr.types import Interpolation
from ripr.types import Transform


def test_longitudinal_mode_has_an_explicit_public_name() -> None:
    assert SelectionMode.parse("longitudinal_accuracy") is SelectionMode.LONGITUDINAL_ACCURACY
    assert SelectionMode.LONGITUDINAL_ACCURACY.label == "Longitudinal maximum accuracy"
    assert "bioluminescence" in ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE.label.lower()
    with pytest.raises(ValueError, match="complete recording"):
        LogRatioParameters(
            selection_mode=SelectionMode.LONGITUDINAL_ACCURACY
        ).resolve()


def test_reference_choice_ignores_a_structureless_dark_frame() -> None:
    structured = np.zeros((32, 32), dtype=np.float32)
    structured[8:24, 10:22] = 10.0
    frames = np.stack([
        structured * 0.6,
        structured * 2.0,
        np.zeros_like(structured),
        structured * 0.9,
        structured * 1.1,
        structured * 0.8,
    ])

    bright, dim = choose_reference_frames(frames)

    assert bright == 1
    assert dim in (0, 3)


def test_longitudinal_prior_removes_returning_pulse_but_keeps_jump() -> None:
    trajectory = np.zeros((18, 3), dtype=np.float64)
    trajectory[5:9, 0] = (0.0, 7.0, 7.0, 0.0)
    trajectory[12:, 1] = 11.0
    confidence = np.ones(18)

    repaired, jumps, changed = repair_trajectory(trajectory, confidence, 200.0)

    assert np.max(np.abs(repaired[5:9, 0])) < 1e-9
    assert jumps == (12,)
    assert np.allclose(repaired[12:, 1], 11.0)
    assert changed


def test_endpoint_guard_repairs_two_frame_persistent_tissue_jump() -> None:
    yy, xx = np.mgrid[:128, :128]
    base = 100.0 * np.exp(-((xx - 75) ** 2 + (yy - 78) ** 2) / 300.0)
    frames = [base.copy() for _ in range(8)]
    frames.extend([ndimage.shift(base, (-25, -30), order=1)] * 2)

    repaired, boundary = _repair_endpoint(
        np.asarray(frames, dtype=np.float32), [Transform()] * 10
    )

    assert boundary == 8
    assert repaired[-1].dx == pytest.approx(-30.0, abs=1.0)
    assert repaired[-1].dy == pytest.approx(-25.0, abs=1.0)


def test_endpoint_guard_is_identical_with_multiple_threads() -> None:
    yy, xx = np.mgrid[:128, :128]
    base = 100.0 * np.exp(-((xx - 75) ** 2 + (yy - 78) ** 2) / 300.0)
    frames = np.asarray(
        [base.copy() for _ in range(8)]
        + [ndimage.shift(base, (-25, -30), order=1)] * 2,
        dtype=np.float32,
    )

    sequential = _repair_endpoint(frames, [Transform()] * 10, threads=1)
    parallel = _repair_endpoint(frames, [Transform()] * 10, threads=4)

    assert parallel == sequential


def test_coarse_landmark_seed_finds_a_large_rigid_jump() -> None:
    yy, xx = np.mgrid[:96, :96]
    base = (
        80.0 * np.exp(-((xx - 24) ** 2 + (yy - 31) ** 2) / 90.0)
        + 55.0 * np.exp(-((xx - 69) ** 2 + (yy - 62) ** 2) / 55.0)
        - 25.0 * np.exp(-((xx - 58) ** 2 + (yy - 25) ** 2) / 35.0)
        + 100.0
    ).astype(np.float32)
    expected = Transform(-7.0, 4.0, np.deg2rad(-9.0))
    moving = warp_plane(
        base, expected.inverse(), Interpolation.BILINEAR, float(np.median(base))
    )

    seed, peak = _coarse_rigid_seed(
        landmark_feature(base), landmark_feature(moving), 15.0
    )

    assert peak > 0.05
    assert seed.theta_degrees == pytest.approx(-9.0, abs=1.0)
    assert seed.dx == pytest.approx(-7.0, abs=2.0)
    assert seed.dy == pytest.approx(4.0, abs=2.0)


def test_guarded_rigid_bridge_changes_only_frames_after_the_event() -> None:
    transforms = [Transform(0.1 * frame, 0.0, 0.0) for frame in range(8)]
    event = Transform(-8.0, 3.0, np.deg2rad(-7.0))

    repaired = _apply_rigid_bridges(transforms, [(4, event)])

    assert repaired[:4] == transforms[:4]
    relative = repaired[3].inverse().then(repaired[4])
    assert relative.dx == pytest.approx(event.dx)
    assert relative.dy == pytest.approx(event.dy)
    assert relative.theta == pytest.approx(event.theta)


def test_terminal_rigid_chain_keeps_two_consecutive_final_remounts(monkeypatch) -> None:
    frames = np.stack([
        np.full((128, 128), frame, dtype=np.float32) for frame in range(10)
    ])
    baseline = [Transform()] * 7 + [
        Transform(20.0, 0.0), Transform(45.0, 0.0), Transform(46.0, 0.0)
    ]
    pair_events = {
        7: Transform(20.5, 0.5, np.deg2rad(-1.0)),
        8: Transform(25.5, -0.5, np.deg2rad(2.0)),
        9: Transform(1.0, 0.0, 0.0),
    }

    def fake_pair(_reference, moving, _parameters):
        return pair_events[int(moving[0, 0])], 0.20

    monkeypatch.setattr("ripr.longitudinal._refine_rigid_bridge", fake_pair)
    events = _terminal_rigid_chain(
        frames,
        baseline,
        LogRatioParameters(selection_mode=SelectionMode.LONGITUDINAL_ACCURACY),
    )

    assert [boundary for boundary, _ in events] == [7, 8, 9]


def test_terminal_rigid_chain_rejects_a_returning_pulse(monkeypatch) -> None:
    frames = np.stack([
        np.full((128, 128), frame, dtype=np.float32) for frame in range(10)
    ])
    baseline = [Transform()] * 7 + [
        Transform(20.0, 0.0), Transform(), Transform()
    ]

    def should_not_fit(*_args):
        raise AssertionError("returning endpoint movement must be rejected before fitting")

    monkeypatch.setattr("ripr.longitudinal._refine_rigid_bridge", should_not_fit)

    assert _terminal_rigid_chain(
        frames,
        baseline,
        LogRatioParameters(selection_mode=SelectionMode.LONGITUDINAL_ACCURACY),
    ) == []


def test_dark_terminal_event_keeps_the_matching_preliminary_jump() -> None:
    trajectory = np.zeros((40, 3), dtype=np.float64)
    trajectory[-1, 0] = 34.0
    baseline = np.zeros_like(trajectory)
    baseline[-1, 0] = 36.0
    confidence = np.ones(40)
    confidence[-2:] = 0.0
    agreement = np.zeros(40)
    scores = np.full(40, 0.8)
    scores[-1] = 0.08

    _protect_terminal_events(
        trajectory, confidence, agreement, scores, scores, baseline, 724.0
    )
    repaired, jumps, _ = repair_trajectory(trajectory, confidence, 724.0)

    assert jumps == (39,)
    assert repaired[-1, 0] == pytest.approx(36.0)
    assert np.max(np.abs(repaired[:-1, 0])) == 0.0


def test_complete_longitudinal_emission_route_uses_one_declared_stack() -> None:
    yy, xx = np.mgrid[:64, :64]
    base = (
        80.0 * np.exp(-((xx - 20) ** 2 + (yy - 24) ** 2) / 45.0)
        + 55.0 * np.exp(-((xx - 46) ** 2 + (yy - 41) ** 2) / 60.0)
        + 4.0
    ).astype(np.float32)
    shifts = ((0.0, 0.0), (0.5, -0.7), (1.0, -1.4), (1.5, -2.1), (2.0, -2.8), (2.5, -3.5))
    gains = (1.0, 1.8, 0.55, 1.4, 0.7, 1.1)
    frames = np.stack([
        ndimage.shift(base, shift, order=3, mode="constant", cval=0.0) * gain
        for shift, gain in zip(shifts, gains, strict=True)
    ])
    parameters = LogRatioParameters(
        image_type=ImageType.DENSE_FLUORESCENCE,
        selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
        crop=False,
        threads=1,
    )

    result = register(frames, parameters, axes="TYX")

    assert result.corrected.shape == frames.shape
    assert result.parameters.selection_mode is SelectionMode.LONGITUDINAL_ACCURACY
    assert result.longitudinal_diagnostics is not None
    assert result.longitudinal_diagnostics.route == "bright_dim_references"
    assert "selected_channel_only=true" in result.provenance


def test_longitudinal_route_rejects_continuous_rotation() -> None:
    frames = np.stack([np.eye(32, dtype=np.float32)] * 3)
    parameters = LogRatioParameters(
        selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
        fit_rotation=True,
        crop=False,
    )

    with pytest.raises(ValueError, match="continuous rotation"):
        register(frames, parameters, axes="TYX")
