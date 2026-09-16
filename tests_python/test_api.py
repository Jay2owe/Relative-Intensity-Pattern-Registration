from dataclasses import replace
import csv

import numpy as np
import pytest
import tifffile
from ripr.cli import _parser

from ripr import (
    Estimator,
    ImageType,
    LogRatioParameters,
    MotionType,
    PixelSelectionStrategy,
    PixelSupport,
    Preprocessing,
    Reference,
    Recipe,
    RotationMode,
    RobustNorm,
    SelectionMode,
    apply_transforms,
    read_tiff,
    recommendation,
    register,
    register_batch,
    register_file,
)


def translated_texture(frames=3, width=64, step=2, seed=123):
    rng = np.random.default_rng(seed)
    texture = (20 + 180 * rng.random((width, width))).astype(np.float32)
    output = []
    for t in range(frames):
        frame = np.full_like(texture, 20)
        frame[:, step * t :] = texture[:, : width - step * t]
        output.append(frame)
    return np.stack(output)


def manual(**changes):
    values = dict(
        reference=Reference.CONSECUTIVE,
        auto_max_shift=False,
        max_shift=6,
        crop=False,
        threads=1,
    )
    values.update(changes)
    return LogRatioParameters.manual(**values)


def test_simple_defaults_match_the_accepted_phase_landmarks_recipe():
    simple = LogRatioParameters.for_recipe()
    explicit = replace(
        LogRatioParameters.recommended(
            image_type=ImageType.PHASE_CONTRAST,
            motion_type=MotionType.INTERMITTENT_JUMPS,
            channel=1,
        ),
        selection_mode=SelectionMode.LONGITUDINAL_ACCURACY,
    )
    assert simple == explicit


def test_simple_bright_dim_recipe_keeps_the_benchmark_emission_mapping():
    parameters = LogRatioParameters.for_recipe(Recipe.BRIGHT_DIM, channel=3)
    assert parameters.image_type is ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE
    assert parameters.motion_type is MotionType.INTERMITTENT_JUMPS
    assert parameters.selection_mode is SelectionMode.LONGITUDINAL_ACCURACY
    assert parameters.channel == 3


def test_simple_moving_cells_recipe_is_not_mislabeled_as_longitudinal():
    with pytest.raises(ValueError, match="not a longitudinal reference route"):
        LogRatioParameters.for_recipe(Recipe.MOVING_CELLS)
    parameters = LogRatioParameters.for_recipe(
        Recipe.MOVING_CELLS, longitudinal=False
    )
    assert parameters.selection_mode is SelectionMode.RECOMMENDED


def test_landmarks_category_reports_the_concrete_automatic_recipe():
    result = register(
        translated_texture(), recipe=Recipe.LANDMARKS, longitudinal=False, backend="python"
    )
    assert result.parameters.image_type is ImageType.PHASE_CONTRAST
    assert result.automatic_selection is not None
    assert result.automatic_selection.recipe != Recipe.LANDMARKS.value
    assert "selected_recipe=" in result.provenance


def test_advanced_settings_can_be_passed_alongside_simple_choices():
    stack = translated_texture()
    result = register(
        stack,
        recipe=Recipe.LANDMARKS,
        longitudinal=False,
        image_type=ImageType.PHASE_CONTRAST,
        motion_type=MotionType.INTERMITTENT_JUMPS,
        reference=Reference.CONSECUTIVE,
        auto_max_shift=False,
        max_shift=6,
        crop=False,
        threads=1,
        backend="python",
    )
    assert result.parameters.selection_mode is SelectionMode.MANUAL
    assert result.parameters.reference is Reference.CONSECUTIVE
    assert result.parameters.max_shift == 6
    assert result.transforms[-1].dx == pytest.approx(4, abs=0.1)


def test_unknown_direct_advanced_setting_is_named_clearly():
    with pytest.raises(ValueError, match="unknown advanced setting.*not_a_setting"):
        register(np.zeros((2, 16, 16), dtype=np.float32), not_a_setting=True)


def test_file_api_accepts_direct_tuning_and_output_path(tmp_path):
    source = tmp_path / "input.tif"
    target = tmp_path / "nested" / "registered.tif"
    tifffile.imwrite(source, translated_texture().astype(np.float32))
    result = register_file(
        source,
        target,
        recipe="landmarks",
        longitudinal=False,
        reference="consecutive",
        auto_max_shift=False,
        max_shift=6,
        crop=False,
        threads=1,
        backend="python",
    )
    assert target.exists()
    assert result.parameters.reference is Reference.CONSECUTIVE


def test_register_accepts_tiff_path_channel_and_output_path(tmp_path):
    source = tmp_path / "input.tif"
    target = tmp_path / "nested" / "registered.tif"
    tifffile.imwrite(source, translated_texture().astype(np.float32))
    result = register(
        source,
        output_path=target,
        recipe="landmarks",
        channel=1,
        longitudinal=False,
        reference="consecutive",
        auto_max_shift=False,
        max_shift=6,
        crop=False,
        threads=1,
        backend="python",
    )
    assert target.exists()
    assert result.parameters.channel == 1
    assert result.parameters.reference is Reference.CONSECUTIVE


def test_register_rejects_output_path_for_array_input(tmp_path):
    with pytest.raises(ValueError, match="output_path is only supported when image is a TIFF path"):
        register(np.zeros((2, 16, 16), dtype=np.float32), output_path=tmp_path / "out.tif")


def test_register_accepts_positional_tiff_output_for_path_call(tmp_path):
    source = tmp_path / "input.tif"
    target = tmp_path / "registered.tif"
    tifffile.imwrite(source, translated_texture().astype(np.float32))
    result = register(
        source,
        target,
        recipe="landmarks",
        longitudinal=False,
        reference="consecutive",
        auto_max_shift=False,
        max_shift=6,
        crop=False,
        threads=1,
        backend="python",
    )
    assert target.exists()
    assert result.parameters.reference is Reference.CONSECUTIVE


def test_registration_does_not_mutate_input():
    source = translated_texture()
    before = source.copy()
    result = register(source, manual())
    assert np.array_equal(source, before)
    assert result.corrected is not source
    assert result.corrected.shape == source.shape
    assert result.transforms[-1].dx == pytest.approx(4, abs=0.1)


def test_selected_hyperstack_channel_drives_every_channel_and_z_plane():
    frames, channels, slices, width = 3, 2, 2, 64
    rng = np.random.default_rng(20260814)
    selected = (20 + 180 * rng.random((width, width))).astype(np.float32)
    static = (20 + 180 * rng.random((width, width))).astype(np.float32)
    stack = np.empty((frames, channels, slices, width, width), dtype=np.float32)
    for t in range(frames):
        for z in range(slices):
            stack[t, 0, z] = static + z * 3
            stack[t, 1, z] = 20 + z * 3
            stack[t, 1, z, :, 2 * t :] = selected[:, : width - 2 * t] + z * 3
    result = register(stack, manual(channel=2, slice=0), axes="TCZYX")
    assert result.corrected.shape == stack.shape
    assert result.transforms[-1].dx == pytest.approx(4, abs=0.2)
    assert not np.array_equal(result.corrected[2, 0, 0], stack[2, 0, 0])


def test_reduced_resolution_returns_native_pixel_coordinates():
    stack = translated_texture(width=96, step=4)
    result = register(stack, manual(estimation_scale=0.5, max_shift=12))
    assert result.transforms[-1].dx == pytest.approx(8, abs=0.8)
    assert result.corrected.shape == stack.shape


def test_sparse_spatial_second_pass_runs_on_raw_pixels():
    stack = translated_texture(width=64, step=1)
    parameters = manual(
        pixel_selection_strategy=PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
        pixel_selection_preprocessing=Preprocessing.ANSCOMBE,
        pixel_removal_percent=25,
    )
    result = register(stack, parameters)
    assert len(result.transforms) == 3
    assert result.corrected.shape == stack.shape
    assert result.transforms[-1].dx == pytest.approx(2, abs=0.3)


def test_recommendation_matrix_and_automatic_estimator_rule():
    for image_type in ImageType:
        for motion_type in MotionType:
            assert recommendation(image_type, motion_type).name
    sparse = recommendation(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE, MotionType.STEADY_DIRECTIONAL_DRIFT)
    assert sparse.preprocessing is Preprocessing.MEDIAN_3X3
    assert sparse.pixel_selection_strategy is PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE
    fixed = LogRatioParameters(
        image_type=ImageType.BRIGHTFIELD_DIC,
        motion_type=MotionType.SUBPIXEL_RANDOM_WALK,
        selection_mode=SelectionMode.AUTOMATIC,
    ).resolve()
    assert fixed.selection_mode is SelectionMode.MANUAL
    assert fixed.preprocessing is Preprocessing.GAUSSIAN_0_7
    assert fixed.pixel_support.name == "GRADIENT"
    stack = translated_texture()
    automatic_result = register(
        stack,
        LogRatioParameters(
            image_type=ImageType.BRIGHTFIELD_DIC,
            motion_type=MotionType.SUBPIXEL_RANDOM_WALK,
            selection_mode=SelectionMode.AUTOMATIC,
            auto_max_shift=False,
            max_shift=6,
            crop=False,
        ),
    )
    assert automatic_result.automatic_selection is not None
    assert not automatic_result.automatic_selection.fallback
    assert automatic_result.automatic_selection.evidence is None
    assert automatic_result.automatic_selection.reason == "fixed automatic policy"
    assert automatic_result.parameters.estimator is Estimator.LOG_RATIO_FIT
    assert automatic_result.parameters.preprocessing is Preprocessing.GAUSSIAN_0_7
    assert automatic_result.parameters.pixel_support.name == "GRADIENT"
    assert np.isnan(automatic_result.parameters.floor_percentile)
    assert np.isnan(automatic_result.parameters.ceiling_percentile)
    assert automatic_result.parameters.selection_mode is SelectionMode.MANUAL

    rigid_fixed = LogRatioParameters(
        image_type=ImageType.BRIGHTFIELD_DIC,
        motion_type=MotionType.SUBPIXEL_RANDOM_WALK,
        selection_mode=SelectionMode.AUTOMATIC,
        fit_rotation=True,
        max_rotation_degrees=6.5,
    ).resolve()
    assert rigid_fixed.fit_rotation
    assert rigid_fixed.preprocessing is Preprocessing.GAUSSIAN_0_7


def test_automatic_fluorescence_uses_the_validated_maximum_accuracy_routes():
    jump = LogRatioParameters(
        image_type=ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
        motion_type=MotionType.INTERMITTENT_JUMPS,
        selection_mode=SelectionMode.AUTOMATIC,
    ).resolve()
    assert jump.estimator is Estimator.LOG_RATIO_FIT
    assert jump.reference is Reference.MULTILAG
    assert jump.preprocessing is Preprocessing.NONE
    assert jump.pixel_support is PixelSupport.GRADIENT
    assert jump.pixel_selection_strategy is PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE
    assert jump.outlier_mads == 0.0
    assert np.isnan(jump.outlier_protection_residual_gain)
    assert "single_channel_emission_max_accuracy_r04_a208" in jump.recipe_provenance
    assert "fixed_route=sparse_lowlight_image_and_motion_logratio_preset" in jump.recipe_provenance

    dense_jump = LogRatioParameters(
        image_type=ImageType.DENSE_FLUORESCENCE,
        motion_type=MotionType.INTERMITTENT_JUMPS,
        selection_mode=SelectionMode.AUTOMATIC,
    ).resolve()
    assert dense_jump.estimator is Estimator.AREA_CORRELATION_ECC
    assert dense_jump.reference is Reference.CONSECUTIVE
    assert dense_jump.preprocessing is Preprocessing.MEDIAN_3X3
    assert "fixed_route=dense_filtered_previous_image_ecc" in dense_jump.recipe_provenance

    ordinary = LogRatioParameters(
        image_type=ImageType.DENSE_FLUORESCENCE,
        motion_type=MotionType.STEADY_DIRECTIONAL_DRIFT,
        selection_mode=SelectionMode.AUTOMATIC,
    ).resolve()
    assert ordinary.estimator is Estimator.AREA_CORRELATION_ECC
    assert ordinary.reference is Reference.CONSECUTIVE
    assert ordinary.preprocessing is Preprocessing.MEDIAN_3X3
    assert ordinary.ceiling_percentile == 90.0
    assert ordinary.outlier_mads == 0.0
    assert np.isnan(ordinary.outlier_protection_residual_gain)
    assert "fixed_route=dense_filtered_previous_image_ecc" in ordinary.recipe_provenance


def test_automatic_fluorescence_estimation_never_uses_another_channel():
    frames, width = 3, 64
    moving = translated_texture(frames=frames, width=width, step=2, seed=8)
    first_other = translated_texture(frames=frames, width=width, step=0, seed=9)
    second_other = translated_texture(frames=frames, width=width, step=7, seed=10)
    left = np.stack((first_other, moving), axis=1)
    right = np.stack((second_other, moving), axis=1)
    parameters = LogRatioParameters(
        image_type=ImageType.DENSE_FLUORESCENCE,
        motion_type=MotionType.INTERMITTENT_JUMPS,
        selection_mode=SelectionMode.AUTOMATIC,
        channel=2,
        auto_max_shift=False,
        max_shift=8,
        crop=False,
        threads=1,
    )
    left_result = register(left, parameters, axes="TCYX")
    right_result = register(right, parameters, axes="TCYX")
    assert left_result.transforms == right_result.transforms


def test_rigid_settings_validate_and_map_to_radians():
    defaults = LogRatioParameters.manual()
    assert not defaults.fit_rotation
    assert not defaults.registration_options().aligner.fit_rotation
    rigid = replace(defaults, fit_rotation=True, max_rotation_degrees=7.5)
    assert rigid.registration_options().aligner.fit_rotation
    assert rigid.registration_options().aligner.max_rotation == pytest.approx(np.deg2rad(7.5))
    with pytest.raises(ValueError, match="maximum rotation"):
        replace(defaults, fit_rotation=True, max_rotation_degrees=0)


def test_known_event_settings_are_one_based_defensive_and_legacy_compatible():
    events = [5, 9]
    parameters = LogRatioParameters.manual(
        rotation_mode=RotationMode.KNOWN_EVENTS,
        rotation_event_frames=events,
        rotation_event_window=2,
        max_rotation_degrees=7,
    )
    events[0] = 99
    assert parameters.rotation_mode is RotationMode.KNOWN_EVENTS
    assert parameters.fit_rotation
    assert parameters.rotation_event_frames == (5, 9)
    core = parameters.registration_options()
    assert core.rotation_event_frames == (4, 8)
    assert not core.aligner.fit_rotation
    assert LogRatioParameters.manual(fit_rotation=True).rotation_mode is RotationMode.CONTINUOUS


@pytest.mark.parametrize("events", [(1,), (5, 5), (6, 4)])
def test_known_event_validation_rejects_invalid_public_frames(events):
    with pytest.raises(ValueError, match=str(events[-1])):
        LogRatioParameters.manual(
            rotation_mode=RotationMode.KNOWN_EVENTS,
            rotation_event_frames=events,
        )


def test_event_fields_cannot_affect_other_rotation_modes():
    with pytest.raises(ValueError, match="require rotation mode known_events"):
        LogRatioParameters.manual(rotation_event_frames=(5,))


def test_cli_parses_one_based_known_event_controls():
    parsed = _parser().parse_args([
        "input.tif", "output.tif",
        "--rotation-mode", "known_events",
        "--rotation-events", "25,51",
        "--rotation-event-window", "4",
    ])
    assert parsed.rotation_mode == "known_events"
    assert parsed.rotation_events == "25,51"
    assert parsed.rotation_event_window == 4


def test_cli_defaults_to_landmarks_channel_one_and_longitudinal():
    parsed = _parser().parse_args(["input.tif"])
    assert parsed.recipe == Recipe.LANDMARKS.value
    assert parsed.channel == 1
    assert parsed.longitudinal is True


def test_tiff_round_trip_and_batch_failure_isolation(tmp_path):
    input_dir = tmp_path / "input"
    output_dir = tmp_path / "output"
    input_dir.mkdir()
    stack = translated_texture().astype(np.uint16)
    tifffile.imwrite(input_dir / "good.tif", stack, photometric="minisblack")
    (input_dir / "bad.tif").write_bytes(b"not a TIFF")
    parameters = manual()
    result = register_batch(input_dir, output_dir, parameters)
    assert result.completed == 1
    assert result.errors == 1
    with result.report_path.open(newline="", encoding="utf-8") as handle:
        header = next(csv.reader(handle))
    assert header[-4:] == [
        "rotation_mode", "rotation_events", "rotation_event_window",
        "rotation_event_results",
    ]
    assert result.report_path.exists()
    output, axes = read_tiff(output_dir / "good_registered.tif")
    assert axes == "TYX"
    assert output.shape == stack.shape
