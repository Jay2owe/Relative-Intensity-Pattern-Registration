from dataclasses import replace

import numpy as np

from ripr import ImageType, LogRatioParameters, MotionType, SelectionMode, Transform
from ripr.recording_selector import (
    FEATURE_CONTRACT_VERSION,
    FEATURE_NAMES,
    measure,
    neutral_pilot,
    select,
)


def test_recording_evidence_v2_is_finite_and_ordered():
    y, x = np.mgrid[:32, :32]
    base = (1000 + 150 * np.sin(x / 3) + 90 * np.cos(y / 5)).astype(np.float32)
    frames = np.stack([np.roll(base, index, axis=1) for index in range(4)])
    transforms = tuple(Transform(float(index), 0.0, 0.0) for index in range(4))
    parameters = LogRatioParameters(
        image_type=ImageType.DENSE_FLUORESCENCE,
        motion_type=MotionType.STEADY_DIRECTIONAL_DRIFT,
        selection_mode=SelectionMode.AUTOMATIC,
    )
    evidence = measure(frames, transforms, parameters)
    assert FEATURE_CONTRACT_VERSION == "recording_evidence_v2"
    assert len(FEATURE_NAMES) == len(evidence.values) == 48
    assert evidence.valid, evidence.reason
    assert np.all(np.isfinite(evidence.values))


def test_invalid_recording_falls_back_before_standardisation():
    frames = np.ones((2, 16, 16), dtype=np.float32)
    evidence = measure(frames, (Transform(), Transform()), LogRatioParameters())
    assert not evidence.valid
    assert "dynamic range" in evidence.reason


def test_neutral_pilot_ignores_category_recipe():
    requested = LogRatioParameters(
        image_type=ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
        motion_type=MotionType.INTERMITTENT_JUMPS,
        selection_mode=SelectionMode.AUTOMATIC,
        estimation_scale=0.5,
        fit_rotation=True,
    )
    pilot = neutral_pilot(requested)
    assert pilot.selection_mode is SelectionMode.MANUAL
    assert pilot.estimation_scale == 1.0
    assert not pilot.fit_rotation
    assert pilot.lags == (1, 2, 4, 8, 16)


def test_rigid_request_does_not_change_translation_recipe():
    translation = LogRatioParameters(
        image_type=ImageType.BRIGHTFIELD_DIC,
        motion_type=MotionType.STEADY_DIRECTIONAL_DRIFT,
        selection_mode=SelectionMode.AUTOMATIC,
    )
    rigid = replace(translation, fit_rotation=True)
    _, translation_selection = select(translation)
    _, rigid_selection = select(rigid)
    assert rigid_selection.recipe == translation_selection.recipe
    assert rigid_selection.fallback == translation_selection.fallback
    assert rigid_selection.predicted_gain == translation_selection.predicted_gain
