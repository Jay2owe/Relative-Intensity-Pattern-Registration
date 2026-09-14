from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import csv

import numpy as np
import tifffile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "split_ome_channels.py"
SPEC = spec_from_file_location("split_ome_channels", SCRIPT)
splitter = module_from_spec(SPEC)
SPEC.loader.exec_module(splitter)


def test_splits_named_channels_and_keeps_last_frames(tmp_path):
    images = tmp_path / "images"
    images.mkdir()
    source = images / "recording_20260721_1417.ome.tif"
    data = np.arange(5 * 4 * 8 * 8, dtype=np.uint16).reshape(5, 4, 8, 8)
    tifffile.imwrite(
        source,
        data,
        ome=True,
        metadata={"axes": "TCYX", "Channel": {"Name": ["Bioluminescence", "BF", "RFP", "GFP"]}},
    )

    output = tmp_path / "split"
    rows = splitter.execute(images, output, last_frames=2)

    assert len(rows) == 4
    assert [row["channel_name"] for row in rows] == ["Bioluminescence", "BF", "RFP", "GFP"]
    assert all(row["selected_first_frame_one_based"] == 4 for row in rows)
    for channel, row in enumerate(rows):
        split = tifffile.imread(row["output_path"])
        np.testing.assert_array_equal(split, data[-2:, channel])


def test_declared_names_and_motion_windows_support_imagej_hyperstacks(tmp_path):
    images = tmp_path / "images"
    images.mkdir()
    source = images / "A1.tif"
    data = np.arange(6 * 4 * 8 * 8, dtype=np.uint16).reshape(6, 4, 8, 8)
    tifffile.imwrite(source, data, imagej=True, metadata={"axes": "TCYX"})
    windows = tmp_path / "windows.csv"
    with windows.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=[
            "recording", "window_first_frame_one_based", "window_last_frame_one_based",
            "motion_type",
        ])
        writer.writeheader()
        writer.writerow({
            "recording": "A1", "window_first_frame_one_based": 2,
            "window_last_frame_one_based": 4, "motion_type": "INTERMITTENT_JUMPS",
        })

    rows = splitter.execute(
        images, tmp_path / "split", last_frames=0,
        declared_channels=["Per2", "SynRCamp", "SynGABASnFR", "MF"],
        window_table=windows,
    )

    assert [row["channel_name"] for row in rows] == [
        "Per2", "SynRCamp", "SynGABASnFR", "MF"
    ]
    assert all(row["motion_type"] == "INTERMITTENT_JUMPS" for row in rows)
    for channel, row in enumerate(rows):
        assert "INTERMITTENT_JUMPS" in Path(row["output_path"]).parts
        np.testing.assert_array_equal(tifffile.imread(row["output_path"]), data[1:4, channel])
