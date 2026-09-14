"""Capture every 2-D array the RIPR log-ratio registration engine materialises
internally, in execution order, for one real frame pair of recording 1424.

Nothing here is simulated or redrawn.  Each array written is either returned by
an engine function directly, or produced by a verbatim copy of the engine's
coarse-to-fine solver that additionally records its own working state.  That
copy is checked against the real ``align_log_ratio`` on the same inputs: if the
two disagree by more than a thousandth of a pixel the capture aborts, so a
recorded intermediate can never belong to a different fit than the engine's.

Run ``register_recording_1424.py`` first; the chosen pair, the shift bound and the
whole-recording trajectory come from its output.
"""
from __future__ import annotations

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
OUT = None  # resolved in main(); see _output_dir


def _output_dir() -> Path:
    """Where this script reads and writes its intermediates.

    In the figure bundle these scripts sit in `data/src/` and their outputs
    belong in `data/der/`; run loose in a working folder, everything stays put.
    """
    here = Path(__file__).resolve().parent
    derived = here.parent / "der"
    return derived if derived.is_dir() else here

CHANNELS = 4
BF = 1  # zero-based brightfield: the estimation channel
CHANNEL_NAMES = ("Bioluminescence", "BF", "RFP", "GFP")

from ripr import ImageType, MotionType, recommendation  # noqa: E402
from ripr.core import (  # noqa: E402
    IDENTITY,
    PixelSupport,
    AlignerOptions,
    LogPlane,
    RegistrationOptions,
    Status,
    _clamp_transform,
    _evaluate,
    _grid,
    _order_statistic,
    _plan_pairs,
    align_log_ratio,
    robust_scale,
    valid_margin,
    warp_plane,
)
from ripr.preprocessing import apply_preprocessing  # noqa: E402
from ripr.types import (  # noqa: E402
    Estimator,
    Interpolation,
    Reference,
    Transform,
)


# --------------------------------------------------------------------------
# recording access
# --------------------------------------------------------------------------
def frames_of(channel: int, indices) -> np.ndarray:
    with tifffile.TiffFile(RECORDING) as handle:
        pages = [int(t) * CHANNELS + channel for t in indices]
        return np.stack([handle.pages[p].asarray() for p in pages]).astype(np.float32)


# --------------------------------------------------------------------------
# the ordered step list
# --------------------------------------------------------------------------
STEPS: list[dict] = []
ARRAYS: dict[str, np.ndarray] = {}


def step(key: str, array: np.ndarray, kind: str, stage: str, source: str,
         note: str, scale_group: str | None = None) -> None:
    """Record one intermediate array in execution order.

    kind      how the array must be rendered so it is not misread
    stage     which part of the pipeline holds it
    source    the engine expression that produced it
    note      what the array is, in words, for the bundle README
    scale_group arrays that must share one display scale to be comparable
    """
    data = np.asarray(array)
    name = f"{len(STEPS) + 1:02d}_{key}"
    ARRAYS[name] = data.astype(np.float32) if data.dtype == bool else data
    finite = data[np.isfinite(data)] if data.dtype != bool else data
    STEPS.append({
        "index": len(STEPS) + 1,
        "name": name,
        "key": key,
        "kind": kind,
        "stage": stage,
        "source": source,
        "note": note,
        "scale_group": scale_group,
        "dtype": str(data.dtype),
        "height": int(data.shape[0]),
        "width": int(data.shape[1]),
        "finite": int(np.count_nonzero(np.isfinite(data))) if data.dtype != bool else int(data.size),
        "min": (float(np.min(finite)) if finite.size else math.nan) if data.dtype != bool else float(data.min()),
        "max": (float(np.max(finite)) if finite.size else math.nan) if data.dtype != bool else float(data.max()),
        "mean": (float(np.mean(finite)) if finite.size else math.nan) if data.dtype != bool else float(data.mean()),
    })
    print(f"  {name:<44} {data.shape[1]}x{data.shape[0]:<5} {kind}", flush=True)


def scatter(shape_plane: LogPlane, stride: int, xs, ys, values) -> np.ndarray:
    """Put sampled values back on the solver's own sampling grid.

    The solver does not visit every pixel: it walks a grid of every `stride`th
    pixel and drops the ones that fall outside the other frame.  Painting the
    survivors back onto that grid, and leaving the dropped ones blank, is the
    field the solver actually held - not an interpolation of it.
    """
    columns = len(range(0, shape_plane.width, stride))
    rows = len(range(0, shape_plane.height, stride))
    canvas = np.full((rows, columns), np.nan, dtype=np.float32)
    canvas[np.asarray(ys) // stride, np.asarray(xs) // stride] = values
    return canvas


# --------------------------------------------------------------------------
# a verbatim copy of the engine solver that records its own working state
# --------------------------------------------------------------------------
def refine_traced(a: LogPlane, b: LogPlane, start: Transform, max_shift: float,
                  options: AlignerOptions, level: int, record: bool):
    """``ripr.core._refine`` with the fields it computes written out.

    The control flow below is copied line for line from the engine so that the
    recorded fields are the engine's own.  Only the `step(...)` calls are added.
    """
    if options.support is not PixelSupport.ALL or options.fit_rotation:
        raise SystemExit(
            "the traced solver copies only the branches this recipe takes "
            "(all pixels, no rotation); these options need the other branches"
        )
    x_all, y_all, stride = _grid(a, options)
    possible = x_all.size
    transform = start
    converged = bailed = False
    iterations = 0
    cx, cy = (a.width - 1) / 2, (a.height - 1) / 2
    for iterations in range(options.max_iterations):
        source_ok = a.valid[y_all, x_all]
        x, y = x_all[source_ok], y_all[source_ok]
        mapped_x, mapped_y = transform.apply(x, y, cx, cy)
        bv, ok = b.sample(mapped_x, mapped_y)
        gx, gx_ok = b.sample(mapped_x, mapped_y, "gx")
        gy, gy_ok = b.sample(mapped_x, mapped_y, "gy")
        ok &= gx_ok & gy_ok
        x, y, bv, gx, gy = x[ok], y[ok], bv[ok], gx[ok], gy[ok]
        dof = 2
        if bv.size < options.min_valid_fraction * possible or bv.size < dof + 1:
            bailed = True
            break
        difference = bv - a.value[y, x]
        gain = _order_statistic(difference, difference.size // 2) if options.profile_gain else 0.0
        residual = difference - gain
        threshold = options.norm.threshold(robust_scale(residual, options.scale_floor))
        weight = options.norm.weights(residual, threshold)

        if record:
            tag = f"L{level}i{iterations}"
            if iterations == 0:
                step(f"solve_{tag}_difference", scatter(a, stride, x, y, difference),
                     "signed", f"solve level {level}",
                     "bv - a.value[y, x]",
                     f"level {level}, iteration 0: raw log difference between the two "
                     f"frames at the displacement this level started from, before the "
                     f"frame-wide gain is taken out",
                     scale_group=f"level{level}")
            step(f"solve_{tag}_residual", scatter(a, stride, x, y, residual),
                 "signed", f"solve level {level}",
                 "difference - gain",
                 f"level {level}, iteration {iterations}: what is left after the one "
                 f"frame-wide gain number is removed - the field the fit is driven by",
                 scale_group=f"level{level}")
            step(f"solve_{tag}_weight", scatter(a, stride, x, y, weight),
                 "unit", f"solve level {level}",
                 "options.norm.weights(residual, threshold)",
                 f"level {level}, iteration {iterations}: the Huber weight each pixel "
                 f"is given - 1 where it counts in full, less where it is bounded",
                 scale_group="weight")

        jacobian = np.column_stack([gx, gy])
        weighted = jacobian * weight[:, None]
        hessian = jacobian.T @ weighted
        gradient = weighted.T @ residual
        if np.count_nonzero(weight) < dof + 1:
            break
        try:
            solved = np.linalg.solve(hessian, -gradient)
        except np.linalg.LinAlgError:
            break
        cost0 = float(np.mean(options.norm.rho(residual, threshold)))
        accepted = None
        factor = 1.0
        for _ in range(6):
            raw = Transform(transform.dx + factor * solved[0],
                            transform.dy + factor * solved[1], 0.0)
            trial = _clamp_transform(raw, max_shift, options)
            evaluation = _evaluate(a, b, trial, options, threshold, 0.0, None)
            if (evaluation.count >= options.min_valid_fraction * evaluation.possible
                    and evaluation.cost < cost0):
                accepted = trial
                break
            factor *= 0.5
        if accepted is None:
            converged = True
            break
        moved = math.hypot(accepted.dx - transform.dx, accepted.dy - transform.dy)
        transform = accepted
        if moved < options.convergence:
            converged = True
            break
    return transform, iterations, converged, bailed


def align_traced(a, b, options: AlignerOptions, record: bool = True):
    """``ripr.core.align_log_ratio`` with its coarse search and levels recorded."""
    top = len(a) - 1
    top_scale = 1 << top
    radius = math.ceil(options.max_shift / top_scale)
    max_here = options.max_shift / top_scale
    best_transform = IDENTITY
    best_eval = _evaluate(a[top], b[top], IDENTITY, options)
    best = best_eval.mean_abs if best_eval.count >= options.min_valid_fraction * best_eval.possible else math.inf
    surface = np.full((2 * radius + 1, 2 * radius + 1), np.nan)
    surface[radius, radius] = best_eval.mean_abs
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if (dx == 0 and dy == 0) or math.hypot(dx, dy) > max_here + 1e-9:
                continue
            result = _evaluate(a[top], b[top], Transform(dx, dy, 0.0), options)
            surface[dy + radius, dx + radius] = result.mean_abs
            if result.count >= options.min_valid_fraction * result.possible and result.mean_abs < best:
                best, best_transform = result.mean_abs, Transform(dx, dy, 0.0)
    transform = best_transform

    if record:
        step("coarse_search_cost", surface, "cost", "coarse search",
             "_evaluate(a[top], b[top], Transform(dx, dy, 0), options).mean_abs",
             f"the cost of every whole-pixel displacement the engine tried at the "
             f"coarsest level: one number per candidate position, brightest where "
             f"the two frames disagree least. This is the only exhaustive search "
             f"in the method; everything after it is a local step.")

    status = Status.OK
    iterations = 0
    for level in range(top, -1, -1):
        scale = 1 << level
        transform, iterations, converged, bailed = refine_traced(
            a[level], b[level], transform, options.max_shift / scale, options, level, record
        )
        status = Status.REFUSED_LOW_OVERLAP if bailed else (Status.OK if converged else Status.NOT_CONVERGED)
        if level > 0:
            transform = _clamp_transform(
                transform.scale_translation(2.0), options.max_shift / (scale / 2), options
            )
    return transform, iterations, status


# --------------------------------------------------------------------------
def main() -> None:
    global OUT
    OUT = _output_dir()
    started = time.time()
    run = json.loads((OUT / "registration_1424.json").read_text(encoding="utf-8"))
    trajectory = np.load(OUT / "registration_1424.npz")
    dx_all, dy_all = trajectory["dx"], trajectory["dy"]

    # The same pair the concept set uses: the longest lag, largest movement.
    longest = max(run["lags"])
    candidates = [p for p in run["pairs"] if p["lag"] == longest and p["usable"]]
    chosen = max(candidates, key=lambda p: math.hypot(p["dx"], p["dy"]))
    pair = (chosen["from"], chosen["to"])
    print(f"pair {pair}  dx {chosen['dx']:.3f}  dy {chosen['dy']:.3f}", flush=True)

    recipe = recommendation(ImageType.BRIGHTFIELD_DIC, MotionType.SUBPIXEL_RANDOM_WALK)
    if recipe.name != run["recipe_name"]:
        raise SystemExit(
            f"the registration run used recipe {run['recipe_name']!r} but this "
            f"capture resolves {recipe.name!r}; they must be the same run"
        )
    options = RegistrationOptions(
        reference=Reference.MULTILAG,
        lags=tuple(run["lags"]),
        estimator=Estimator.LOG_RATIO_FIT,
        aligner=AlignerOptions(
            max_shift=run["max_shift_suggested"],
            norm=recipe.norm,
            support=recipe.pixel_support,
            gradient_fraction=recipe.gradient_fraction,
            max_iterations=recipe.max_iterations,
            max_samples=recipe.max_samples,
        ),
    )
    aligner = options.aligner

    # ---------------------------------------------------------------- stage A
    print("stage A - read and prepare", flush=True)
    raw = frames_of(BF, pair)
    step("read_frame_a", raw[0], "intensity", "read",
         f"recording page {pair[0]}, channel BF",
         f"frame {pair[0]} of the brightfield channel, exactly as it comes off the "
         f"recording. This is the only thing the estimator is ever shown.")
    step("read_frame_b", raw[1], "intensity", "read",
         f"recording page {pair[1]}, channel BF",
         f"frame {pair[1]}, the other half of the comparison, {longest} timepoints "
         f"later.")

    prepared = np.stack([apply_preprocessing(f, recipe.preprocessing) for f in raw])
    step("prepared_a", prepared[0], "intensity", "prepare",
         "apply_preprocessing(raw, recipe.preprocessing)",
         "the 3 by 3 median the declared recipe asks for, on a copy. The recording "
         "itself is never written to; this frame exists only to be measured.")
    step("prepared_change_a", prepared[0] - raw[0], "signed", "prepare",
         "prepared - raw",
         "what the median filter actually moved, and in which direction. Almost all "
         "of it is single-pixel noise; no structure is removed.")

    planes = [LogPlane.from_intensity(prepared[i], options.epsilon) for i in (0, 1)]
    step("log_a", planes[0].value, "log", "logarithm",
         "LogPlane.from_intensity(prepared, epsilon=1).value",
         "log2(intensity + 1). The picture is unchanged - the logarithm changes the "
         "arithmetic, not the scene: a brightness change that multiplies every pixel "
         "becomes one number added to every pixel, which is what lets a single "
         "subtraction remove it.")
    step("log_b", planes[1].value, "log", "logarithm",
         "LogPlane.from_intensity(prepared, epsilon=1).value",
         "the same for the second frame.")
    step("valid_a", planes[0].valid, "mask", "logarithm",
         "LogPlane.valid",
         "which pixels are usable. With no intensity band declared for this recipe "
         "every finite pixel qualifies, so the mask is full - it is drawn because the "
         "engine still carries and consults it at every step.")
    step("gx_a", planes[0].gx, "signed", "gradients",
         "LogPlane.gx",
         "how fast log intensity changes left-to-right at each pixel. The fit reads "
         "position out of these slopes: a pixel on a flat patch cannot say where it "
         "moved, a pixel on a slope can.")
    step("gy_a", planes[0].gy, "signed", "gradients",
         "LogPlane.gy",
         "the same top-to-bottom.")
    step("gradient_magnitude_a", planes[0].gradient_magnitude, "intensity", "gradients",
         "LogPlane.gradient_magnitude",
         "|gx| + |gy|: total local structure. Bright where the frame has something to "
         "register on. Every LogPlane computes these three the moment it is built, "
         "whether or not the configured recipe goes on to read them - under this "
         "recipe the fit reads the second frame's, below, and not these.")
    step("gx_b", planes[1].gx, "signed", "gradients",
         "LogPlane.gx of the second frame",
         "the left-to-right slope of the second frame. This is the one the solve "
         "actually reads: the fit asks how far it must move the second frame for its "
         "slopes to explain the disagreement, so the Jacobian is built from the "
         "target frame's gradients, not the reference frame's.")
    step("gy_b", planes[1].gy, "signed", "gradients",
         "LogPlane.gy of the second frame",
         "the same top-to-bottom, and the other column of the Jacobian.")
    step("gradient_magnitude_b", planes[1].gradient_magnitude, "intensity", "gradients",
         "LogPlane.gradient_magnitude of the second frame",
         "|gx| + |gy| for the second frame: where the fit can read a position from, "
         "and by contrast the flat interior where it cannot.")

    # ---------------------------------------------------------------- stage B
    print("stage B - pyramid", flush=True)
    levels = aligner.levels_for(planes[0].width, planes[0].height)
    pyramid_a = planes[0].pyramid(levels)
    pyramid_b = planes[1].pyramid(levels)
    for level in range(levels):
        step(f"pyramid_a_L{level}", pyramid_a[level].value, "log", "pyramid",
             f"LogPlane.pyramid({levels})[{level}]",
             f"level {level} of the first frame: {pyramid_a[level].width} by "
             f"{pyramid_a[level].height}, each step blurred with a 5-tap kernel and "
             f"then halved. The coarse levels are where a large displacement is cheap "
             f"to find; the fine ones are where it is made exact.")
    for level in range(levels):
        step(f"pyramid_b_L{level}", pyramid_b[level].value, "log", "pyramid",
             f"LogPlane.pyramid({levels})[{level}]",
             f"level {level} of the second frame.")

    # ------------------------------------------------------- stages C, D, E
    print("stage C/D/E - coarse search and coarse-to-fine solve", flush=True)
    fitted, iterations, status = align_traced(pyramid_a, pyramid_b, aligner, record=True)

    engine = align_log_ratio(pyramid_a, pyramid_b, aligner)
    drift = math.hypot(engine.transform.dx - fitted.dx, engine.transform.dy - fitted.dy)
    if drift > 1e-3:
        raise SystemExit(
            f"traced solver disagrees with the engine by {drift:.6f} px "
            f"(traced {fitted.dx:.5f},{fitted.dy:.5f} vs engine "
            f"{engine.transform.dx:.5f},{engine.transform.dy:.5f})"
        )
    print(f"  traced fit matches the engine to {drift:.2e} px", flush=True)

    # ---------------------------------------------------------------- stage F
    print("stage F - what the fit achieved", flush=True)
    base, target = pyramid_a[0], pyramid_b[0]
    x_all, y_all, stride = _grid(base, aligner)
    cx, cy = (base.width - 1) / 2, (base.height - 1) / 2

    def field(transform: Transform):
        ok = base.valid[y_all, x_all]
        xs, ys = x_all[ok], y_all[ok]
        mx, my = transform.apply(xs, ys, cx, cy)
        values, target_ok = target.sample(mx, my)
        xs, ys = xs[target_ok], ys[target_ok]
        difference = values[target_ok] - base.value[ys, xs].astype(np.float64)
        gain = float(_order_statistic(difference, difference.size // 2))
        return xs, ys, difference, gain, difference - gain

    x0, y0, d0, gain0, r0 = field(IDENTITY)
    x1, y1, d1, gain1, r1 = field(engine.transform)
    step("residual_identity", scatter(base, stride, x0, y0, r0), "signed", "before and after",
         "difference - gain, at the identity transform",
         "the disagreement between the two frames before anything is fitted, with the "
         "frame-wide gain already taken out. The structure left in it is the movement.",
         scale_group="level0")
    step("residual_fitted", scatter(base, stride, x1, y1, r1), "signed", "before and after",
         "difference - gain, at the fitted transform",
         "the same field at the fitted displacement. It flattens because the frames "
         "now overlap - nothing has been brightened, sharpened or filled in.",
         scale_group="level0")

    # ---------------------------------------------------------------- stage G
    print("stage G - the comparison graph", flush=True)
    plan = _plan_pairs(Reference.MULTILAG, run["frames"], 0, tuple(run["lags"]))
    adjacency = np.zeros((run["frames"], run["frames"]), dtype=np.float32)
    for i, j in plan:
        adjacency[i, j] = adjacency[j, i] = j - i
    step("pair_plan", adjacency, "plan", "reconcile",
         "_plan_pairs(MULTILAG, frames, 0, lags)",
         f"every comparison the engine will make, fixed before a single pixel is "
         f"read: {len(plan)} pairs over {run['frames']} frames at lags "
         f"{', '.join(str(l) for l in run['lags'])}. One diagonal per lag. Because "
         f"several routes connect any two timepoints, no single comparison can decide "
         f"where a frame goes.")

    # ---------------------------------------------------------------- stage H
    print("stage H - warp and crop", flush=True)
    last = run["frames"] - 1
    channels = np.stack([frames_of(c, [0, last]) for c in range(CHANNELS)])
    transform_last = Transform(float(dx_all[last]), float(dy_all[last]), 0.0)
    step("warp_input_bf_last", channels[BF, 1], "intensity", "warp",
         f"recording page {last}, channel BF",
         f"the last brightfield frame before the transform is applied - five days "
         f"after the first.")
    warped_bf = warp_plane(channels[BF, 1], transform_last, Interpolation.NONE, np.nan)
    step("warp_output_bf_last", warped_bf, "intensity", "warp",
         "warp_plane(frame, cumulative[last], NONE, nan)",
         f"the same frame after its own transform. The whole picture has moved by "
         f"{transform_last.dx:.2f} across and {transform_last.dy:.2f} down, and the "
         f"strip it vacated has no source pixel at all.")
    step("warp_vacated_bf_last", ~np.isfinite(warped_bf), "mask", "warp",
         "~isfinite(warp_plane(...))",
         "exactly which pixels the warp left with nothing behind them. This is why a "
         "crop is needed: these are not dark pixels, they are absent ones.")
    for ci, cname in enumerate(CHANNEL_NAMES):
        if ci == BF:
            continue
        step(f"warp_output_{cname.lower()}_last",
             warp_plane(channels[ci, 1], transform_last, Interpolation.NONE, np.nan),
             "intensity", "warp",
             "warp_plane(frame, cumulative[last], NONE, nan)",
             f"the identical transform applied to the {cname} channel. The movement "
             f"was measured on brightfield alone and then applied unchanged to every "
             f"channel - the signal channels are never allowed to influence where they "
             f"are put.")

    cumulative = [Transform(float(dx_all[t]), float(dy_all[t]), 0.0) for t in range(run["frames"])]
    margin = valid_margin(cumulative, run["width"], run["height"], Interpolation.NONE)
    keep = np.zeros((run["height"], run["width"]), dtype=bool)
    keep[margin.top:run["height"] - margin.bottom, margin.left:run["width"] - margin.right] = True
    step("common_valid_field", keep, "mask", "crop",
         "valid_margin(cumulative, width, height, NONE)",
         f"the field that holds a real source pixel at every one of the "
         f"{run['frames']} timepoints, over the whole recording rather than this one "
         f"frame: {margin.left} columns off the left, {margin.right} off the right, "
         f"{margin.top} rows off the top, {margin.bottom} off the bottom.")
    step("cropped_bf_last",
         warped_bf[margin.top:run["height"] - margin.bottom,
                   margin.left:run["width"] - margin.right],
         "intensity", "crop",
         "warped[margin.top:-margin.bottom, margin.left:-margin.right]",
         f"the output: {run['width'] - margin.left - margin.right} by "
         f"{run['height'] - margin.top - margin.bottom} of the original "
         f"{run['width']} by {run['height']}, every pixel of it real at every "
         f"timepoint.")

    # ----------------------------------------------------------------- write
    np.savez_compressed(OUT / "pipeline_steps.npz", **ARRAYS)
    manifest = {
        "recording": RECORDING.name,
        "position": "1424",
        "pair": list(pair),
        "pair_lag": longest,
        "pair_dx": chosen["dx"],
        "pair_dy": chosen["dy"],
        "pair_log_gain": chosen["log_gain"],
        "estimation_channel_zero_based": BF,
        "estimation_channel_name": "BF",
        "channel_names": list(CHANNEL_NAMES),
        "frames": run["frames"],
        "width": run["width"],
        "height": run["height"],
        "recipe_name": recipe.name,
        "recipe_image_type": str(ImageType.BRIGHTFIELD_DIC),
        "recipe_motion_type": str(MotionType.SUBPIXEL_RANDOM_WALK),
        "recipe_preprocessing": str(recipe.preprocessing),
        "recipe_norm": str(recipe.norm),
        "recipe_pixel_support": str(recipe.pixel_support),
        "recipe_pixel_selection": str(recipe.pixel_selection_strategy),
        "estimator": str(options.estimator),
        "reference": str(options.reference),
        "lags": list(run["lags"]),
        "levels": levels,
        "level_sizes": [[pyramid_a[l].width, pyramid_a[l].height] for l in range(levels)],
        "solver_stride": int(stride),
        "max_shift": aligner.max_shift,
        "max_shift_largest_measured": run["max_shift_largest"],
        "max_shift_suggested": run["max_shift_suggested"],
        "fitted_dx": engine.transform.dx,
        "fitted_dy": engine.transform.dy,
        "fitted_log_gain": engine.log_gain,
        "fitted_iterations": engine.iterations,
        "fitted_status": str(engine.status),
        "traced_vs_engine_px": drift,
        "gain_log2_at_identity": gain0,
        "gain_log2_at_fit": gain1,
        "mean_abs_residual_identity": float(np.mean(np.abs(r0))),
        "mean_abs_residual_fitted": float(np.mean(np.abs(r1))),
        "pairs_planned": len(plan),
        "trajectory_dx_last": float(dx_all[last]),
        "trajectory_dy_last": float(dy_all[last]),
        "margin": {"top": margin.top, "bottom": margin.bottom,
                   "left": margin.left, "right": margin.right},
        "cropped_shape": list(margin.cropped_shape(run["height"], run["width"])),
        "capture_seconds": time.time() - started,
        "steps": STEPS,
    }
    (OUT / "pipeline_steps.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    print(f"\n{len(STEPS)} steps written in {time.time() - started:.0f}s", flush=True)


if __name__ == "__main__":
    main()
