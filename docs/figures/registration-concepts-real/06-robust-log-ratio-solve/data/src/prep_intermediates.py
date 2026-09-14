"""Compute every measured intermediate the eleven concept figures draw.

Reads the real Auto-Organotypic test-set recording 1424, drives the real RIPR engine,
and writes one .npz per concept plus a small JSON of the numbers each one used.
Nothing here is simulated: each array is what the engine actually produced on
these pixels.

Run ``run_registration_1424.py`` first - the whole-recording trajectory, pair
estimates and gain trace come from its output.
"""
import json
import math
import sys
import time
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
OUT = Path(__file__).resolve().parent
CHANNELS = 4
BF = 1  # zero-based brightfield; the estimation channel
CHANNEL_NAMES = ("Bioluminescence", "BF", "RFP", "GFP")

import ripr  # noqa: E402
from ripr import ImageType, MotionType, rank_channels, recommendation  # noqa: E402
from ripr.core import (  # noqa: E402
    AlignerOptions,
    LogPlane,
    RegistrationOptions,
    _AreaWindow,
    _area_correlation,
    _evaluate,
    _grid,
    _plan_pairs,
    _refine,
    align_area,
    align_log_ratio,
    warp_plane,
)
from ripr.preprocessing import apply_preprocessing  # noqa: E402
from ripr.registration import (  # noqa: E402
    _source_support,
    _spatial_mask,
    _two_axis_information,
)
from ripr.types import (  # noqa: E402
    Estimator,
    Interpolation,
    Preprocessing,
    Reference,
    Transform,
)


def frames_of(channel, indices=None):
    """Read one channel of the recording, either whole or at chosen timepoints."""
    with tifffile.TiffFile(RECORDING) as handle:
        total = len(handle.pages) // CHANNELS
        if indices is None:
            key = slice(channel, total * CHANNELS, CHANNELS)
            return handle.series[0].asarray(key=key).astype(np.float32)
        pages = [int(t) * CHANNELS + channel for t in indices]
        return np.stack([handle.pages[p].asarray() for p in pages]).astype(np.float32)


def sampled_grid(base, stride, xs, ys, values):
    """The solver samples every `stride` pixels; keep that grid dense, not scattered."""
    columns = len(range(0, base.width, stride))
    rows = len(range(0, base.height, stride))
    canvas = np.full((rows, columns), np.nan, dtype=np.float32)
    canvas[ys // stride, xs // stride] = values
    return canvas


def concept01(run):
    """Four real channels, their real localisability, the real 5x4 recipe matrix."""
    thumbnails = np.stack([frames_of(c, [0])[0] for c in range(CHANNELS)])
    sample = np.stack([frames_of(c, range(0, 24)) for c in range(CHANNELS)], axis=1)
    quality = rank_channels(sample, axes="TCYX")
    rows = []
    for image_type in ImageType:
        for motion_type in MotionType:
            recipe = recommendation(image_type, motion_type)
            rows.append({
                "image_type": str(image_type),
                "motion_type": str(motion_type),
                "name": recipe.name,
                "estimator": str(recipe.estimator),
                "preprocessing": str(recipe.preprocessing),
                "norm": str(recipe.norm),
                "pixel_support": str(recipe.pixel_support),
                "pixel_selection": str(recipe.pixel_selection_strategy),
                "floor_percentile": recipe.floor_percentile,
                "ceiling_percentile": recipe.ceiling_percentile,
                "max_iterations": recipe.max_iterations,
                "max_samples": recipe.max_samples,
            })
    summary = {
        "channel_names": list(CHANNEL_NAMES),
        "quality": [
            {
                "channel_one_based": q.channel,
                "name": CHANNEL_NAMES[q.channel - 1],
                "localisability": q.localisability,
                "frame_correlation": q.frame_correlation,
                "poor": bool(q.poor),
            }
            for q in quality
        ],
        "warn_below": ripr.WARN_BELOW,
        "quality_frames": 24,
        "declared_image_type": str(ImageType.BRIGHTFIELD_DIC),
        "declared_motion_type": str(MotionType.SUBPIXEL_RANDOM_WALK),
        "image_types": [str(t) for t in ImageType],
        "motion_types": [str(t) for t in MotionType],
        "recipes": rows,
    }
    (OUT / "concept01.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    np.savez_compressed(OUT / "concept01.npz", thumbnails=thumbnails.astype(np.uint16))
    print("concept01 done", flush=True)


def concept02():
    """One real brightfield frame through the real estimation-only preparation."""
    raw = frames_of(BF, [0])[0]
    untouched = np.stack([frames_of(c, [0])[0] for c in (0, 2, 3)])
    prepared = apply_preprocessing(raw, Preprocessing.MEDIAN_3X3)
    plane = LogPlane.from_intensity(prepared, epsilon=1.0)
    floor = float(np.percentile(prepared, 25.0))
    ceiling = float(np.percentile(prepared, 99.0))
    banded = LogPlane.from_intensity(prepared, epsilon=1.0, floor=floor, saturation_max=ceiling)
    np.savez_compressed(
        OUT / "concept02.npz",
        raw=raw,
        prepared=prepared,
        log_value=plane.value,
        log_valid=plane.valid,
        banded_valid=banded.valid,
        untouched=untouched.astype(np.uint16),
    )
    (OUT / "concept02.json").write_text(json.dumps({
        "preprocessing": str(Preprocessing.MEDIAN_3X3),
        "epsilon": 1.0,
        "band_floor_percentile": 25.0,
        "band_floor_value": floor,
        "band_ceiling_percentile": 99.0,
        "band_ceiling_value": ceiling,
        "raw_min": float(raw.min()),
        "raw_max": float(raw.max()),
        "raw_median": float(np.median(raw)),
        "log_min": float(plane.value[plane.valid].min()),
        "log_max": float(plane.value[plane.valid].max()),
        "valid_default": int(plane.valid.sum()),
        "valid_banded": int(banded.valid.sum()),
        "pixels": int(raw.size),
    }, indent=1), encoding="utf-8")
    print("concept02 done", flush=True)


def pyramids_for(pair_frames, options):
    a = LogPlane.from_intensity(pair_frames[0], options.epsilon)
    b = LogPlane.from_intensity(pair_frames[1], options.epsilon)
    levels = options.aligner.levels_for(a.width, a.height)
    return a.pyramid(levels), b.pyramid(levels), levels


def concept03(pair_frames, options, run):
    """Real pyramids and the real coarse-to-fine path for the chosen pair."""
    a, b, levels = pyramids_for(pair_frames, options)
    aligner = options.aligner
    top = levels - 1
    radius = math.ceil(aligner.max_shift / (1 << top))
    best, best_cost = Transform(0.0, 0.0, 0.0), math.inf
    coarse = np.full((2 * radius + 1, 2 * radius + 1), np.nan)
    for iy, dy in enumerate(range(-radius, radius + 1)):
        for ix, dx in enumerate(range(-radius, radius + 1)):
            if math.hypot(dx, dy) > aligner.max_shift / (1 << top) + 1e-9:
                continue
            sample = _evaluate(a[top], b[top], Transform(dx, dy, 0.0), aligner)
            coarse[iy, ix] = sample.mean_abs
            if sample.count >= aligner.min_valid_fraction * sample.possible and sample.mean_abs < best_cost:
                best_cost, best = sample.mean_abs, Transform(dx, dy, 0.0)
    path = [(top, best.dx * (1 << top), best.dy * (1 << top))]
    transform = best
    for level in range(top, -1, -1):
        transform, iterations, converged, bailed = _refine(
            a[level], b[level], transform, aligner.max_shift / (1 << level), aligner, None
        )
        path.append((level, transform.dx * (1 << level), transform.dy * (1 << level)))
        if level > 0:
            transform = transform.scale_translation(2.0)
    arrays = {f"a{level}": a[level].value for level in range(levels)}
    arrays.update({f"b{level}": b[level].value for level in range(levels)})
    np.savez_compressed(
        OUT / "concept03.npz", coarse_cost=coarse, path=np.array(path), **arrays
    )
    (OUT / "concept03.json").write_text(json.dumps({
        "levels": levels,
        "coarse_radius": radius,
        "max_shift": aligner.max_shift,
        "max_shift_largest_measured": run["max_shift_largest"],
        "max_shift_suggested": run["max_shift_suggested"],
        "max_shift_resolution": run["max_shift_resolution"],
        "shift_bound_probe_pairs": run["shift_bound_probe_pairs"],
        "coarse_shape": list(coarse.shape),
        "level_sizes": [[a[level].width, a[level].height] for level in range(levels)],
        "path": [{"level": int(p[0]), "dx": float(p[1]), "dy": float(p[2])} for p in path],
    }, indent=1), encoding="utf-8")
    print("concept03 done", flush=True)


def concept04(run):
    """The real frame-pair plan, and real thumbnails for a window of it."""
    frames = run["frames"]
    lags = tuple(run["lags"])
    plan = _plan_pairs(Reference.MULTILAG, frames, 0, lags)
    window = 17
    shown = [(i, j) for i, j in plan if j < window]
    thumbnails = frames_of(BF, range(window))
    np.savez_compressed(
        OUT / "concept04.npz",
        thumbnails=thumbnails[:, ::4, ::4].astype(np.float32),
        window_pairs=np.array(shown),
        plan=np.array(plan),
    )
    (OUT / "concept04.json").write_text(json.dumps({
        "frames": frames,
        "lags": list(lags),
        "pairs_total": len(plan),
        "pairs_per_lag": {str(lag): sum(1 for i, j in plan if j - i == lag) for lag in lags},
        "window": window,
        "window_pairs": len(shown),
    }, indent=1), encoding="utf-8")
    print("concept04 done", flush=True)


def concept05_06(pair_frames, options, chosen):
    """Real gain/movement separation, real robust weights, real per-pixel votes."""
    a, b, levels = pyramids_for(pair_frames, options)
    aligner = options.aligner
    fit = align_log_ratio(a, b, aligner)
    base, target = a[0], b[0]
    x, y, stride = _grid(base, aligner)

    def field(transform):
        ok = base.valid[y, x]
        xs, ys = x[ok], y[ok]
        mx, my = transform.apply(xs, ys, (base.width - 1) / 2, (base.height - 1) / 2)
        values, target_ok = target.sample(mx, my)
        xs, ys = xs[target_ok], ys[target_ok]
        difference = values[target_ok] - base.value[ys, xs].astype(np.float64)
        gain = float(np.median(difference))
        return xs, ys, difference, gain, difference - gain, (mx[target_ok], my[target_ok])

    x0, y0, d0, c0, r0, _ = field(Transform(0.0, 0.0, 0.0))
    x1, y1, d1, c1, r1, mapped = field(fit.transform)

    scale = max(1e-4, 1.4826 * float(np.median(np.abs(r1))))
    weights = np.minimum(1.0, 1.345 * scale / np.maximum(np.abs(r1), 1e-12))

    gx, gx_ok = target.sample(mapped[0], mapped[1], "gx")
    gy, gy_ok = target.sample(mapped[0], mapped[1], "gy")
    strength = gx * gx + gy * gy
    usable = (strength > 0) & gx_ok & gy_ok
    safe = np.where(usable, strength, 1.0)
    vote_x = np.where(usable, -r1 * gx / safe, 0.0)
    vote_y = np.where(usable, -r1 * gy / safe, 0.0)

    np.savez_compressed(
        OUT / "concept05.npz",
        frame_a=pair_frames[0],
        frame_b=pair_frames[1],
        difference_zero=sampled_grid(base, stride, x0, y0, d0),
        residual_zero=sampled_grid(base, stride, x0, y0, r0),
        difference_fit=sampled_grid(base, stride, x1, y1, d1),
        residual_fit=sampled_grid(base, stride, x1, y1, r1),
    )
    (OUT / "concept05.json").write_text(json.dumps({
        "pair": list(chosen),
        "stride": stride,
        "grid_columns": len(range(0, base.width, stride)),
        "grid_rows": len(range(0, base.height, stride)),
        "gain_log2_at_zero": c0,
        "gain_at_zero": 2.0 ** c0,
        "gain_log2_at_fit": c1,
        "gain_at_fit": 2.0 ** c1,
        "mean_abs_residual_zero": float(np.mean(np.abs(r0))),
        "mean_abs_residual_fit": float(np.mean(np.abs(r1))),
        "mean_abs_difference_zero": float(np.mean(np.abs(d0))),
        "dx": fit.transform.dx,
        "dy": fit.transform.dy,
        "residual_before": fit.residual_before,
        "residual_after": fit.residual_after,
        "log_gain": fit.log_gain,
        "valid_fraction": fit.valid_fraction,
        "iterations": fit.iterations,
        "status": str(fit.status),
        "sampled_pixels": int(r1.size),
    }, indent=1), encoding="utf-8")

    np.savez_compressed(
        OUT / "concept06.npz",
        residual=sampled_grid(base, stride, x1, y1, r1),
        weight=sampled_grid(base, stride, x1, y1, weights),
        vote_x=sampled_grid(base, stride, x1, y1, vote_x),
        vote_y=sampled_grid(base, stride, x1, y1, vote_y),
        sample_x=x1,
        sample_y=y1,
        sample_residual=r1,
        sample_weight=weights,
        sample_vote_x=vote_x,
        sample_vote_y=vote_y,
    )
    (OUT / "concept06.json").write_text(json.dumps({
        "pair": list(chosen),
        "stride": stride,
        "grid_columns": len(range(0, base.width, stride)),
        "grid_rows": len(range(0, base.height, stride)),
        "frame_width": base.width,
        "frame_height": base.height,
        "norm": str(aligner.norm),
        "huber_k": 1.345,
        "robust_scale": scale,
        "weighted_below_one": int(np.count_nonzero(weights < 1 - 1e-12)),
        "sampled_pixels": int(r1.size),
        "dx": fit.transform.dx,
        "dy": fit.transform.dy,
        "iterations": fit.iterations,
        "status": str(fit.status),
        "residual_before": fit.residual_before,
        "residual_after": fit.residual_after,
    }, indent=1), encoding="utf-8")
    print("concept05 and concept06 done", flush=True)


def concept07(pair_frames, options, chosen):
    """The real zero-mean normalised area-correlation surface and Newton fit."""
    aligner = options.aligner
    levels = aligner.levels_for(pair_frames.shape[2], pair_frames.shape[1])
    linear_a = LogPlane.from_intensity(pair_frames[0], options.epsilon).pyramid(levels, linear=True)
    linear_b = LogPlane.from_intensity(pair_frames[1], options.epsilon).pyramid(levels, linear=True)
    fit = align_area(linear_a, linear_b, aligner, Estimator.AREA_CORRELATION)
    window_a = _AreaWindow.of(linear_a[0], aligner)
    window_b = _AreaWindow.of(linear_b[0], aligner)
    span, step = 6.0, 0.25
    offsets = np.arange(-span, span + step / 2, step)
    surface = np.empty((offsets.size, offsets.size))
    for iy, dy in enumerate(offsets):
        for ix, dx in enumerate(offsets):
            surface[iy, ix] = _area_correlation(window_a, window_b, float(dx), float(dy), aligner)
    np.savez_compressed(
        OUT / "concept07.npz",
        surface=surface,
        offsets=offsets,
        window_a=linear_a[0].value,
        window_b=linear_b[0].value,
    )
    (OUT / "concept07.json").write_text(json.dumps({
        "pair": list(chosen),
        "span": span,
        "step": step,
        "peak_correlation": float(np.nanmax(surface)),
        "dx": fit.transform.dx,
        "dy": fit.transform.dy,
        "iterations": fit.iterations,
        "status": str(fit.status),
        "residual_before": fit.residual_before,
        "residual_after": fit.residual_after,
        "valid_fraction": fit.valid_fraction,
    }, indent=1), encoding="utf-8")
    print("concept07 done", flush=True)


def concept08(pair_frames, options, chosen, run):
    """Real two-axis information, the real 25% mask, and the real transported mask."""
    plane = LogPlane.from_intensity(pair_frames[0], options.epsilon)
    information = _two_axis_information(plane)
    mask = _spatial_mask(pair_frames[0], options.epsilon, 25.0, False)
    levels = options.aligner.levels_for(plane.width, plane.height)
    pilot = Transform(
        run["cumulative_dx"][chosen[1]] - run["cumulative_dx"][chosen[0]],
        run["cumulative_dy"][chosen[1]] - run["cumulative_dy"][chosen[0]],
        0.0,
    )
    transported = _source_support(mask, levels, pilot)[0]
    gradients = plane.gradient_magnitude[plane.valid]
    threshold = 0.5 * float(np.median(gradients))
    np.savez_compressed(
        OUT / "concept08.npz",
        frame=pair_frames[0],
        other_frame=pair_frames[1],
        information=information.astype(np.float32),
        mask=mask,
        transported=transported,
        gradient_magnitude=plane.gradient_magnitude.astype(np.float32),
    )
    (OUT / "concept08.json").write_text(json.dumps({
        "pair": list(chosen),
        "remove_percent": 25.0,
        "gradient_threshold": threshold,
        "eligible": int(np.count_nonzero(plane.valid & (plane.gradient_magnitude >= threshold))),
        "kept": int(mask.sum()),
        "removed": int(mask.size - mask.sum()),
        "pixels": int(mask.size),
        "pilot_dx": pilot.dx,
        "pilot_dy": pilot.dy,
    }, indent=1), encoding="utf-8")
    print("concept08 done", flush=True)


def concept11(run):
    """The real transform applied to every real channel, and the real common crop."""
    show = [0, run["frames"] // 2, run["frames"] - 1]
    stack = np.stack([frames_of(c, show) for c in range(CHANNELS)])
    warped = np.empty_like(stack)
    for ci in range(CHANNELS):
        for ti, t in enumerate(show):
            transform = Transform(run["cumulative_dx"][t], run["cumulative_dy"][t], 0.0)
            warped[ci, ti] = warp_plane(stack[ci, ti], transform, Interpolation.NONE, np.nan)
    np.savez_compressed(
        OUT / "concept11.npz",
        original=stack.astype(np.float32),
        warped=warped.astype(np.float32),
        shown_frames=np.array(show),
    )
    (OUT / "concept11.json").write_text(json.dumps({
        "shown_frames": show,
        "channel_names": list(CHANNEL_NAMES),
        "margin": run["margin"],
        "cropped_shape": run["cropped_shape"],
        "width": run["width"],
        "height": run["height"],
        "interpolation": "none",
    }, indent=1), encoding="utf-8")
    print("concept11 done", flush=True)


def main():
    started = time.time()
    run = json.loads((OUT / "registration_1424.json").read_text(encoding="utf-8"))
    trajectory = np.load(OUT / "registration_1424.npz")
    run["cumulative_dx"] = trajectory["dx"].tolist()
    run["cumulative_dy"] = trajectory["dy"].tolist()

    longest = max(run["lags"])
    candidates = [p for p in run["pairs"] if p["lag"] == longest and p["usable"]]
    chosen_pair = max(candidates, key=lambda p: math.hypot(p["dx"], p["dy"]))
    chosen = (chosen_pair["from"], chosen_pair["to"])
    print(
        f"chosen pair {chosen} dx {chosen_pair['dx']:.3f} dy {chosen_pair['dy']:.3f} "
        f"log gain {chosen_pair['log_gain']:.4f}",
        flush=True,
    )
    (OUT / "chosen_pair.json").write_text(json.dumps(chosen_pair, indent=1), encoding="utf-8")

    pair_frames = frames_of(BF, chosen)
    options = RegistrationOptions(
        reference=Reference.MULTILAG,
        lags=tuple(run["lags"]),
        estimator=Estimator.LOG_RATIO_FIT,
        aligner=AlignerOptions(max_shift=run["max_shift_suggested"]),
    )

    concept01(run)
    concept02()
    concept03(pair_frames, options, run)
    concept04(run)
    concept05_06(pair_frames, options, chosen)
    concept07(pair_frames, options, chosen)
    concept08(pair_frames, options, chosen, run)
    concept11(run)
    print(f"all intermediates written in {time.time() - started:.0f}s", flush=True)


if __name__ == "__main__":
    main()
