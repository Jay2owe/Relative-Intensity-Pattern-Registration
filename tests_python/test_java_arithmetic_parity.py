"""Arithmetic the Python engine must perform the way Java performs it.

These are not style preferences. Each one was a measured cross-language divergence: the pyramid
blur reduced five float32 products in a library-chosen order and landed one unit in the last place
away from Java's sequential sum, and the bilinear sampler returned full float64 where Java returns a
float. Either alone moved the final transform by roughly 0.002 px on a real recording, because the
solver starts its search from the coarsest pyramid level and a different starting point converges
somewhere else.

A faster reduction or a "why is this cast here" tidy-up would reintroduce both, so the properties
are pinned here rather than left to the end-to-end parity harness, which needs a JVM to run.
"""

from __future__ import annotations

import math

import numpy as np
import pytest

from ripr.core import LogPlane
from ripr.registration import (
    _two_axis_information,
    java_incompatibilities,
    resolve_backend,
)
from ripr.types import ImageType, MotionType
from ripr.parameters import LogRatioParameters


def java_blur5(values: np.ndarray, valid: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Java's blur5, transcribed as scalar loops. Deliberately slow and obviously faithful."""
    height, width = values.shape
    taps = (1.0, 4.0, 6.0, 4.0, 1.0)

    row_value = np.zeros((height, width), dtype=np.float32)
    row_weight = np.zeros((height, width), dtype=np.float32)
    for y in range(height):
        for x in range(width):
            total = np.float32(0.0)
            weight = np.float32(0.0)
            for offset in range(-2, 3):
                index = min(max(x + offset, 0), width - 1)
                if not valid[y, index]:
                    continue
                tap = np.float32(taps[offset + 2])
                total = np.float32(total + np.float32(tap * values[y, index]))
                weight = np.float32(weight + tap)
            row_value[y, x] = np.float32(total / weight) if weight > 0 else np.float32(0.0)
            row_weight[y, x] = np.float32(weight / np.float32(16.0))

    out_value = np.zeros((height, width), dtype=np.float32)
    out_valid = np.zeros((height, width), dtype=bool)
    for x in range(width):
        for y in range(height):
            total = np.float32(0.0)
            weight = np.float32(0.0)
            for offset in range(-2, 3):
                index = min(max(y + offset, 0), height - 1)
                share = row_weight[index, x]
                if share <= 0:
                    continue
                tap = np.float32(np.float32(taps[offset + 2]) * share)
                total = np.float32(total + np.float32(tap * row_value[index, x]))
                weight = np.float32(weight + tap)
            out_value[y, x] = np.float32(total / weight) if weight > 0 else np.float32(0.0)
            out_valid[y, x] = (weight / np.float32(16.0)) >= np.float32(0.5)
    return out_value, out_valid


@pytest.mark.parametrize("invalid_fraction", [0.0, 0.2])
def test_blur5_matches_javas_sequential_float32_accumulation(invalid_fraction):
    generator = np.random.default_rng(20260909)
    values = (2000.0 + 600.0 * generator.standard_normal((23, 29))).astype(np.float32)
    valid = generator.random((23, 29)) >= invalid_fraction
    values[~valid] = 0.0

    mine, mine_valid = LogPlane._blur5(values, valid)
    theirs, theirs_valid = java_blur5(values, valid)

    assert np.array_equal(mine_valid, theirs_valid)
    # Bit-exact, not approximately equal: one ULP here is what the divergence was made of.
    assert np.array_equal(mine, theirs), (
        f"worst difference {np.abs(mine.astype(np.float64) - theirs.astype(np.float64)).max()}"
    )


def test_pyramid_levels_stay_bit_exact_through_every_halving():
    generator = np.random.default_rng(7)
    intensity = (1000.0 + 300.0 * generator.standard_normal((64, 96))).astype(np.float32)
    plane = LogPlane.from_intensity(intensity, epsilon=1.0)

    levels = plane.pyramid(3)
    for level, coarse in enumerate(levels[1:], start=1):
        expected_value, expected_valid = java_blur5(levels[level - 1].value, levels[level - 1].valid)
        height, width = levels[level - 1].height // 2, levels[level - 1].width // 2
        assert np.array_equal(coarse.value, expected_value[: height * 2 : 2, : width * 2 : 2])
        assert np.array_equal(coarse.valid, expected_valid[: height * 2 : 2, : width * 2 : 2])


def test_sample_rounds_to_float32_the_way_java_returns_a_float():
    generator = np.random.default_rng(11)
    intensity = (500.0 + 100.0 * generator.standard_normal((17, 19))).astype(np.float32)
    plane = LogPlane.from_intensity(intensity, epsilon=1.0)

    x = generator.uniform(0, plane.width - 1, size=400)
    y = generator.uniform(0, plane.height - 1, size=400)
    values, ok = plane.sample(x, y)

    sampled = values[ok]
    assert sampled.size > 0
    # Java's sampleOf ends `return (float)(top + fy * (bot - top))`, so every value it can return is
    # exactly representable in float32. Full float64 here silently changes every residual.
    assert np.array_equal(sampled, sampled.astype(np.float32).astype(np.float64))


def test_sample_refuses_positions_outside_the_plane():
    plane = LogPlane.from_intensity(np.ones((8, 8), dtype=np.float32) * 100.0, epsilon=1.0)
    x = np.asarray([-0.001, 0.0, 7.0, 7.001])
    y = np.asarray([4.0, 4.0, 4.0, 4.0])
    _, ok = plane.sample(x, y)
    assert list(ok) == [False, True, True, False]


def test_preset_recipe_is_reproducible_by_the_java_runner():
    parameters = LogRatioParameters.recommended(
        image_type=ImageType.BRIGHTFIELD_DIC, motion_type=MotionType.SUBPIXEL_RANDOM_WALK
    )
    assert java_incompatibilities(parameters) == ()


def test_customised_recipe_is_refused_by_the_java_runner():
    from dataclasses import replace

    parameters = LogRatioParameters.recommended(
        image_type=ImageType.BRIGHTFIELD_DIC, motion_type=MotionType.SUBPIXEL_RANDOM_WALK
    )
    tweaked = replace(parameters, epsilon=2.0)
    differing = java_incompatibilities(tweaked)
    assert any("epsilon" in entry for entry in differing)
    # auto must never silently run a recipe the caller did not ask for.
    assert resolve_backend("auto", tweaked) == "python"


def test_backend_names_are_validated():
    parameters = LogRatioParameters.recommended(
        image_type=ImageType.BRIGHTFIELD_DIC, motion_type=MotionType.SUBPIXEL_RANDOM_WALK
    )
    with pytest.raises(ValueError):
        resolve_backend("jvm", parameters)


def test_default_backend_is_the_python_engine():
    from ripr.registration import DEFAULT_BACKEND

    # Installing this package beside a JDK must not change what an existing call returns.
    assert DEFAULT_BACKEND == "python"


def java_two_axis_information(plane, radius: int = 2) -> np.ndarray:
    """Java's twoAxisInformation, transcribed as scalar loops. Deliberately slow, obviously faithful."""
    height, width = plane.valid.shape
    gx = plane.gx.astype(np.float64)
    gy = plane.gy.astype(np.float64)
    out = np.zeros((height, width), dtype=np.float64)
    for y in range(height):
        y0, y1 = max(0, y - radius), min(height - 1, y + radius)
        for x in range(width):
            x0, x1 = max(0, x - radius), min(width - 1, x + radius)
            xx = yy = xy = 0.0
            n = 0
            for j in range(y0, y1 + 1):
                for i in range(x0, x1 + 1):
                    if not plane.valid[j, i]:
                        continue
                    a = gx[j, i]
                    b = gy[j, i]
                    xx += a * a
                    xy += a * b
                    yy += b * b
                    n += 1
            if n == 0:
                continue
            trace = xx + yy
            discriminant = math.sqrt((xx - yy) * (xx - yy) + 4 * xy * xy)
            out[y, x] = max(0.0, 0.5 * (trace - discriminant) / n)
    return out


def test_two_axis_information_matches_javas_windowed_accumulation():
    """The pixel-selection score must be summed the way Java sums it.

    This was a real cross-language divergence, and an instructive one: the score was computed with
    a box filter, whose result is the window *mean*, so the sum was recovered by dividing by
    twenty-five and multiplying it back. The error was 2.4e-14 and it still mattered, because the
    caller ranks these values and drops the least informative quarter. Four pixels of 2,478,080 sat
    close enough to that cut for the last bit to decide which side they fell on, and those four were
    then in one engine's fit and out of the other's for every pair in the recording -- 2.6e-5 px
    apart on the reconciled frames, from four pixels.

    So bit-exact, not approximately equal. A faster reduction here is not a safe substitution.
    """
    generator = np.random.default_rng(20260910)
    intensity = (400.0 + 90.0 * generator.standard_normal((37, 41))).astype(np.float32)
    plane = LogPlane.from_intensity(intensity, epsilon=1.0)

    mine = _two_axis_information(plane)
    theirs = java_two_axis_information(plane)

    assert np.array_equal(mine, theirs), (
        f"worst difference {np.abs(mine - theirs).max()}"
    )


def test_two_axis_information_is_exact_where_pixels_are_invalid():
    """Skipping a tap must be exactly skipping it, including along the borders."""
    generator = np.random.default_rng(4242)
    intensity = (400.0 + 90.0 * generator.standard_normal((23, 29))).astype(np.float32)
    # NaN is how a pixel with no measurement arrives; it is what makes the plane invalid there.
    intensity[generator.random((23, 29)) < 0.3] = np.nan
    plane = LogPlane.from_intensity(intensity, epsilon=1.0)
    assert plane.valid.sum() < plane.valid.size, "the fixture must actually contain invalid pixels"

    assert np.array_equal(_two_axis_information(plane), java_two_axis_information(plane))


def test_asking_for_python_longitudinal_explicitly_says_what_it_is():
    """Explicitly choosing the non-reference implementation is allowed, and is not done quietly."""
    import warnings
    from dataclasses import replace

    from ripr.types import SelectionMode

    parameters = replace(
        LogRatioParameters.recommended(
            image_type=ImageType.BRIGHTFIELD_DIC, motion_type=MotionType.SUBPIXEL_RANDOM_WALK
        ),
        selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
    )
    with warnings.catch_warnings(record=True) as raised:
        warnings.simplefilter("always")
        assert resolve_backend("python", parameters) == "python"
    assert len(raised) == 1
    assert issubclass(raised[0].category, RuntimeWarning)
    assert "reference" in str(raised[0].message)


def test_ordinary_modes_still_default_to_the_python_engine():
    """The longitudinal exception must not become a general change of default."""
    parameters = LogRatioParameters.recommended(
        image_type=ImageType.BRIGHTFIELD_DIC, motion_type=MotionType.SUBPIXEL_RANDOM_WALK
    )
    assert resolve_backend("python", parameters) == "python"
