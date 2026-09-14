import csv
import json
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "combine_channel_benchmarks.py"
SPEC = spec_from_file_location("combine_channel_benchmarks", SCRIPT)
combiner = module_from_spec(SPEC)
SPEC.loader.exec_module(combiner)


def write_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def test_combines_channels_and_ranks_methods(tmp_path):
    run = tmp_path / "bio"
    run.mkdir()
    (run / "run.json").write_text(json.dumps({
        "status": "complete", "image_type": "SPARSE_LOW_LIGHT_FLUORESCENCE",
        "motion_type": "INTERMITTENT_JUMPS",
    }), encoding="utf-8")
    write_csv(run / "results" / "summary.csv", [
        {"method": "original", "method_label": "Original", "guide_residual_px": "3",
         "median_time_seconds": "0", "scored_images": "1", "images": "1", "failed_images": "0"},
        {"method": "automatic", "method_label": "Automatic", "guide_residual_px": "1",
         "median_time_seconds": "2", "scored_images": "1", "images": "1", "failed_images": "0"},
        {"method": "enhanced_correlation", "method_label": "ECC", "guide_residual_px": "2",
         "median_time_seconds": "1", "scored_images": "1", "images": "1", "failed_images": "0"},
    ])
    write_csv(run / "results" / "per_image.csv", [{"series_id": "one", "method": "automatic"}])
    write_csv(run / "results" / "montage_audit_manifest.csv", [{
        "series_id": "one", "bundle": "bundle", "master": "master.tif",
        "preview": "preview.png", "figure_id": "rf-123",
    }])

    rows = combiner.combine([("Bioluminescence", run)], tmp_path / "combined")

    automatic = next(row for row in rows if row["method"] == "automatic")
    ecc = next(row for row in rows if row["method"] == "enhanced_correlation")
    assert automatic["error_rank_in_channel"] == 1
    assert ecc["time_rank_in_channel"] == 1
    assert (tmp_path / "combined" / "RESULTS.md").is_file()
    montage = list(csv.DictReader(
        (tmp_path / "combined" / "montage_index.csv").open(newline="", encoding="utf-8")
    ))[0]
    assert montage["channel"] == "Bioluminescence"
    assert montage["motion_type"] == "INTERMITTENT_JUMPS"


def test_repeated_channel_is_ranked_separately_for_each_real_motion_type(tmp_path):
    runs = []
    for motion, automatic_error, ecc_error in [
        ("INTERMITTENT_JUMPS", "1", "2"),
        ("STEADY_DIRECTIONAL_DRIFT", "4", "3"),
    ]:
        run = tmp_path / motion
        run.mkdir()
        (run / "run.json").write_text(json.dumps({
            "status": "complete", "image_type": "DENSE_FLUORESCENCE",
            "motion_type": motion,
        }), encoding="utf-8")
        write_csv(run / "results" / "summary.csv", [
            {"method": "automatic", "method_label": "Automatic",
             "guide_residual_px": automatic_error, "median_time_seconds": "2",
             "scored_images": "1", "images": "1", "failed_images": "0"},
            {"method": "enhanced_correlation", "method_label": "ECC",
             "guide_residual_px": ecc_error, "median_time_seconds": "1",
             "scored_images": "1", "images": "1", "failed_images": "0"},
        ])
        write_csv(run / "results" / "per_image.csv", [
            {"series_id": "one", "method": "automatic"}
        ])
        runs.append(("SynRCamp", run))

    rows = combiner.combine(runs, tmp_path / "combined")

    jump_auto = next(row for row in rows if row["motion_type"] == "INTERMITTENT_JUMPS"
                     and row["method"] == "automatic")
    steady_ecc = next(row for row in rows
                      if row["motion_type"] == "STEADY_DIRECTIONAL_DRIFT"
                      and row["method"] == "enhanced_correlation")
    assert jump_auto["error_rank_in_channel"] == 1
    assert steady_ecc["error_rank_in_channel"] == 1
