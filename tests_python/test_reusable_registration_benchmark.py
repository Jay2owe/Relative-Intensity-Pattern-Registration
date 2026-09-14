from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

import numpy as np
import pytest
import tifffile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "reusable_registration_benchmark.py"
SPEC = spec_from_file_location("reusable_registration_benchmark", SCRIPT)
benchmark = module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


def test_method_groups_and_old_aliases_resolve_to_public_names():
    assert benchmark.parse_methods("automatic,area_ecc,recommended") == [
        "automatic", "enhanced_correlation", "image_motion_preset"
    ]
    assert benchmark.parse_methods("longitudinal") == ["longitudinal_accuracy"]
    assert len(benchmark.parse_methods("all_internal")) == 9
    assert len(benchmark.parse_methods("all_external")) == 12


def test_unknown_method_fails_before_a_run_is_created():
    with pytest.raises(ValueError, match="unknown method"):
        benchmark.parse_methods("automatic,not_a_method")


def test_arbitrary_tiff_folder_prepares_a_verified_benchmark_tree(tmp_path):
    images = tmp_path / "images"
    images.mkdir()
    tifffile.imwrite(images / "one.tif", np.zeros((3, 16, 20), dtype=np.uint16),
                     imagej=True, metadata={"axes": "TYX"})
    nested = images / "nested"
    nested.mkdir()
    tifffile.imwrite(nested / "two.tiff", np.ones((4, 16, 20), dtype=np.uint16),
                     imagej=True, metadata={"axes": "TYX"})

    found = benchmark.discover_images(images)
    assert [row["frames"] for row in found] == [4, 3]
    assert len({row["series_id"] for row in found}) == 2

    prepared = tmp_path / "prepared"
    manifest = benchmark.prepare_images(
        found, prepared, "DENSE_FLUORESCENCE", "INTERMITTENT_JUMPS"
    )
    assert len(manifest) == 2
    for row in manifest:
        target = Path(row["prepared_path"])
        assert target.is_file()
        assert target.parent.joinpath("truth.csv").is_file()
        assert row["prepared_sha256"] == row["source_sha256"]


def test_single_image_or_colour_data_is_rejected(tmp_path):
    tifffile.imwrite(tmp_path / "single.tif", np.zeros((16, 20), dtype=np.uint16))
    with pytest.raises(ValueError, match="expected one grayscale time stack"):
        benchmark.discover_images(tmp_path)


def test_review_grid_labels_fit_their_panels_at_large_text_size():
    import cv2

    for lines in benchmark.DISPLAY_LABELS.values():
        for line in lines:
            width = cv2.getTextSize(
                line, cv2.FONT_HERSHEY_SIMPLEX, 1.2, 2
            )[0][0]
            assert width <= benchmark.PANEL_WIDTH - 10, (line, width)


def test_resume_accepts_only_complete_tiff_stacks(tmp_path):
    complete = tmp_path / "complete.tif"
    wrong = tmp_path / "wrong.tif"
    tifffile.imwrite(complete, np.zeros((3, 8, 8), dtype=np.uint16),
                     imagej=True, metadata={"axes": "TYX"})
    tifffile.imwrite(wrong, np.zeros((2, 8, 8), dtype=np.uint16),
                     imagej=True, metadata={"axes": "TYX"})

    assert benchmark.valid_tiff(complete, (3, 8, 8))
    assert not benchmark.valid_tiff(wrong, (3, 8, 8))
    assert not benchmark.valid_tiff(tmp_path / "missing.tif", (3, 8, 8))
