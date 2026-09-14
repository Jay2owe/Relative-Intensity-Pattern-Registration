"""The Java fast path must return the Python engine's answer, not merely a fast one.

Skipped wherever a Java runtime and the plugin classes are not both present, which is most machines
that install this package. The point of the backend is that it is optional; a test suite that fails
without a JVM would contradict that.
"""

from __future__ import annotations

import numpy as np
import pytest

from ripr import ImageType, LogRatioParameters, MotionType, SelectionMode, register
from ripr import java_backend
from ripr.registration import estimate

pytestmark = pytest.mark.skipif(
    not java_backend.available(),
    reason="no Java runtime and plugin classes on this machine",
)


def drifting_stack(frames: int = 6, height: int = 96, width: int = 112) -> np.ndarray:
    """A stack with structure worth aligning and a known whole-pixel drift."""
    generator = np.random.default_rng(1234)
    y, x = np.mgrid[:height, :width]
    base = (
        900.0
        + 260.0 * np.sin(2 * np.pi * x / 21.0) * np.cos(2 * np.pi * y / 17.0)
        + 120.0 * np.sin(2 * np.pi * (x + y) / 13.0)
        + 30.0 * generator.standard_normal((height, width))
    ).astype(np.float32)
    return np.stack([np.roll(np.roll(base, i, axis=0), 2 * i, axis=1) for i in range(frames)])


def preset() -> LogRatioParameters:
    return LogRatioParameters.recommended(
        image_type=ImageType.BRIGHTFIELD_DIC, motion_type=MotionType.SUBPIXEL_RANDOM_WALK
    )


def test_java_backend_matches_the_python_engine_on_the_same_stack():
    stack = drifting_stack()
    parameters = preset()

    mine = estimate(stack, parameters, axes="TYX", backend="python")
    theirs = estimate(stack, parameters, axes="TYX", backend="java")

    assert len(mine.cumulative) == len(theirs.cumulative)
    # The engines agree far inside the solver's own 1e-3 px stopping tolerance. The bound here is
    # loose enough to survive the decimal round-trip the transforms take between processes and
    # tight enough that a genuine behavioural divergence fails it.
    for frame, (a, b) in enumerate(zip(mine.cumulative, theirs.cumulative)):
        assert a.dx == pytest.approx(b.dx, abs=1e-6), f"dx at frame {frame}"
        assert a.dy == pytest.approx(b.dy, abs=1e-6), f"dy at frame {frame}"
        assert a.theta == pytest.approx(b.theta, abs=1e-9), f"theta at frame {frame}"


def test_register_returns_identical_pixels_through_either_engine():
    stack = drifting_stack()
    parameters = preset()

    mine = register(stack, parameters, axes="TYX", backend="python")
    theirs = register(stack, parameters, axes="TYX", backend="java")

    assert mine.corrected.shape == theirs.corrected.shape
    assert np.array_equal(mine.corrected, theirs.corrected)


def test_java_backend_reports_its_own_timing_and_worker_count():
    run = java_backend.estimate(
        drifting_stack(),
        image_type=ImageType.BRIGHTFIELD_DIC,
        motion_type=MotionType.SUBPIXEL_RANDOM_WALK,
        threads=1,
    )
    assert run.engine_seconds > 0
    assert run.workers == 1
    assert len(run.cumulative) == 6


def test_longitudinal_prefers_the_java_engine_even_by_default():
    """Longitudinal is the one mode where the engine choice is not merely about speed.

    Every other mode is one algorithm in two languages agreeing to the last bit. This one is two
    implementations of the same idea -- OpenCV here, its own code there -- disagreeing by up to
    0.43 px, so the reference implementation is the one that should run, and it is also the faster.
    """
    from dataclasses import replace

    from ripr.registration import resolve_backend

    parameters = replace(preset(), selection_mode=SelectionMode.LONGITUDINAL_ACCURACY)
    assert resolve_backend(None, parameters) == "java", "the default must not be the divergent one"
    assert resolve_backend("auto", parameters) == "java"
    assert resolve_backend("java", parameters) == "java"


def test_longitudinal_through_java_matches_the_java_runner_directly():
    stack = drifting_stack(frames=8)
    parameters = replace_mode(preset(), SelectionMode.LONGITUDINAL_ACCURACY)

    routed = estimate(stack, parameters, axes="TYX")
    direct = java_backend.estimate(
        stack,
        image_type=ImageType.BRIGHTFIELD_DIC,
        motion_type=MotionType.SUBPIXEL_RANDOM_WALK,
        selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
    )

    assert len(routed.cumulative) == len(direct.cumulative)
    for frame, (a, b) in enumerate(zip(routed.cumulative, direct.cumulative)):
        assert a.dx == pytest.approx(b.dx, abs=1e-9), f"dx at frame {frame}"
        assert a.dy == pytest.approx(b.dy, abs=1e-9), f"dy at frame {frame}"


def replace_mode(parameters, mode):
    from dataclasses import replace

    return replace(parameters, selection_mode=mode)


def test_a_non_three_dimensional_stack_is_refused():
    with pytest.raises(ValueError):
        java_backend.estimate(
            np.zeros((4, 4), dtype=np.float32),
            image_type=ImageType.BRIGHTFIELD_DIC,
            motion_type=MotionType.SUBPIXEL_RANDOM_WALK,
        )
