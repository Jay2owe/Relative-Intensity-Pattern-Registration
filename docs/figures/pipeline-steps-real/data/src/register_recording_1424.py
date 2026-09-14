"""Register the brightfield channel of test-set recording 1424 under the recipe
this recording's declared image and motion class resolve to, and save the
trajectory, the per-pair fits and the diagnostics the step capture needs.

Recording: Auto-Organotypic test set, 20260710_Tmem_Cry_BSL_leaktest_1713 position
1424.  240 timepoints, 4 channels (Bioluminescence, BF, RFP, GFP), 512x512,
2.0 um pixels, 30 minute cadence.  Registration is estimated on BF (channel
index 1), as the Auto-Organotypic demo does, because estimating on a signal channel
would let the cells register to themselves.

`ripr.core.register_frames` never applies a recipe's preprocessing - it has no
reference to Preprocessing at all.  The ImageJ plugin applies it to the
estimation copy before calling in, so this script does the same, and every
number the figure bundle quotes therefore comes from one configuration:
the declared recipe, executed end to end.
"""
import json
import math
import sys
import time
from dataclasses import replace
from pathlib import Path

import numpy as np
import tifffile

PROJECT = Path(
    r"C:/Users/Owner/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie"
    r"/Experiments/Log-Ratio Registration"
)
sys.path.insert(0, str(PROJECT / "src"))

RECORDING = Path(
    r"C:/Users/Owner/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie"
    r"/Experiments/Auto-Organotypic/test set"
    r"/20260710_Tmem_Cry_BSL_leaktest_1713_Multichannel Time Lapse_20260721_1424.ome.tif"
)
OUT = None  # resolved in main(); see _output_dir
BF_CHANNEL = 1          # zero-based; "channel 2" in one-based ImageJ terms
CHANNELS = 4

from ripr import ImageType, MotionType, recommendation          # noqa: E402
from ripr.core import (                                          # noqa: E402
    AlignerOptions, RegistrationOptions, estimate_shift_bound,
    register_frames, valid_margin,
)
from ripr.preprocessing import apply_preprocessing               # noqa: E402
from ripr.types import Estimator, Interpolation, Reference       # noqa: E402


def _output_dir() -> Path:
    """Where this script reads and writes its intermediates.

    In the figure bundle these scripts sit in `data/src/` and their outputs
    belong in `data/der/`; run loose in a working folder, everything stays put.
    """
    here = Path(__file__).resolve().parent
    derived = here.parent / "der"
    return derived if derived.is_dir() else here


def read_channel(channel: int) -> np.ndarray:
    with tifffile.TiffFile(RECORDING) as handle:
        frames = len(handle.pages) // CHANNELS
        return handle.series[0].asarray(
            key=slice(channel, frames * CHANNELS, CHANNELS)
        )


def main() -> None:
    global OUT
    OUT = _output_dir()
    started = time.time()

    recipe = recommendation(ImageType.BRIGHTFIELD_DIC, MotionType.SUBPIXEL_RANDOM_WALK)
    print(f"declared recipe: {recipe.name}", flush=True)
    print(f"  preprocessing {recipe.preprocessing}  norm {recipe.norm}  "
          f"support {recipe.pixel_support}  estimator {recipe.estimator}", flush=True)

    print("reading brightfield", flush=True)
    raw = read_channel(BF_CHANNEL).astype(np.float32)
    frames, height, width = raw.shape
    print(f"  {frames} frames of {width}x{height}  {time.time()-started:.1f}s", flush=True)

    # The estimation copy only. The recording is never written to, and the
    # channels that get warped at the end are read fresh from the file.
    print(f"applying {recipe.preprocessing} to the estimation copy", flush=True)
    brightfield = np.stack(
        [apply_preprocessing(raw[t], recipe.preprocessing) for t in range(frames)]
    )
    print(f"  filtered  {time.time()-started:.1f}s", flush=True)

    options = RegistrationOptions(
        reference=Reference.MULTILAG,
        lags=(1, 2, 4, 8, 16),
        estimator=Estimator.LOG_RATIO_FIT,
        aligner=AlignerOptions(
            norm=recipe.norm,
            support=recipe.pixel_support,
            gradient_fraction=recipe.gradient_fraction,
            max_iterations=recipe.max_iterations,
            max_samples=recipe.max_samples,
        ),
        auto_max_shift=True,
    )
    largest, suggested, resolution, probe_pairs = estimate_shift_bound(brightfield, options)
    print(f"shift bound: largest {largest:.3f} suggested {suggested:.3f} "
          f"resolution {resolution} from {probe_pairs} probe pairs  "
          f"{time.time()-started:.0f}s", flush=True)
    # register_frames would recompute exactly this bound from the same frames with
    # the same deterministic function; hand it the answer instead of paying twice.
    options = replace(
        options,
        aligner=replace(options.aligner, max_shift=suggested),
        auto_max_shift=False,
    )

    def progress(done: int, total: int) -> None:
        if done % 100 == 0 or done == total:
            print(f"  pair {done}/{total}  {time.time()-started:.0f}s", flush=True)

    result = register_frames(brightfield, options, progress=progress)
    print(f"registration done in {time.time()-started:.0f}s", flush=True)

    margin = valid_margin(result.cumulative, width, height, Interpolation.NONE)

    pairs = [
        {
            "from": pair.from_frame,
            "to": pair.to_frame,
            "lag": pair.lag,
            "dx": pair.fit.transform.dx,
            "dy": pair.fit.transform.dy,
            "log_gain": pair.fit.log_gain,
            "residual_before": pair.fit.residual_before,
            "residual_after": pair.fit.residual_after,
            "valid_fraction": pair.fit.valid_fraction,
            "iterations": pair.fit.iterations,
            "status": str(pair.fit.status),
            "usable": bool(pair.fit.usable),
            "graph_residual_dx": (pair.influence.graph_residual.dx if pair.influence else math.nan),
            "graph_residual_dy": (pair.influence.graph_residual.dy if pair.influence else math.nan),
            "used": bool(pair.influence.used) if pair.influence else False,
        }
        for pair in result.pairs
    ]

    summary = {
        "recording": RECORDING.name,
        "position": "1424",
        "estimation_channel_zero_based": BF_CHANNEL,
        "estimation_channel_name": "BF",
        "declared_image_type": str(ImageType.BRIGHTFIELD_DIC),
        "declared_motion_type": str(MotionType.SUBPIXEL_RANDOM_WALK),
        "recipe_name": recipe.name,
        "recipe_preprocessing": str(recipe.preprocessing),
        "recipe_norm": str(recipe.norm),
        "recipe_pixel_support": str(recipe.pixel_support),
        "recipe_pixel_selection": str(recipe.pixel_selection_strategy),
        "frames": frames,
        "width": width,
        "height": height,
        "pixel_size_um": 2.0,
        "cadence_minutes": 30.0,
        "estimator": str(options.estimator),
        "reference": str(options.reference),
        "lags": list(options.lags),
        "levels": result.levels,
        "max_shift_largest": largest,
        "max_shift_suggested": suggested,
        "max_shift_resolution": resolution,
        "shift_bound_probe_pairs": probe_pairs,
        "median_residual_before": result.median_residual_before,
        "median_residual_after": result.median_residual_after,
        "warnings": list(result.warnings),
        "repairs": [None if r is None else str(r) for r in result.repairs],
        "status": [None if s is None else str(s) for s in result.status],
        "margin": {"top": margin.top, "bottom": margin.bottom,
                   "left": margin.left, "right": margin.right},
        "cropped_shape": list(margin.cropped_shape(height, width)),
        "runtime_seconds": time.time() - started,
        "pairs": pairs,
    }
    (OUT / "registration_1424.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")

    np.savez_compressed(
        OUT / "registration_1424.npz",
        dx=np.array([t.dx for t in result.cumulative]),
        dy=np.array([t.dy for t in result.cumulative]),
        support=result.support,
        log2_gain=result.log2_gain,
        residual_before=result.residual_before,
        residual_after=result.residual_after,
        valid_fraction=result.valid_fraction,
    )
    print("wrote registration_1424.json and .npz", flush=True)


if __name__ == "__main__":
    main()
