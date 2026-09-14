"""A movie of the log-ratio fit measuring a real knock, made out of the fit itself.

Nothing here re-implements anything. The engine is imported and called the way
`ValidationRun` calls it, and a return tracer photographs its own local
variables on the way past, so every mask on screen is an array the function
held and every number in a caption was computed by the method. The run is then
checked against the accepted `shifts.csv` this library entry ships, and the
movie is refused if it does not reproduce it.

The subject is `library/06_knock`: 48 frames of IncuCyte phase contrast, half an
hour apart, in which something knocked the stage between frames 46 and 47.

    1/7  Something knocked the microscope     the pair, and how far apart it is
    2/7  What the method actually compares    the log-ratio field, and its median
    3/7  Which pixels are allowed to vote     the contrast threshold
    4/7  A coarse guess first                 the pyramid, and 176 candidates
    5/7  Then the fit, four times over        the robust solve at every level
    6/7  Every pair, then one trace           209 pair measurements, reconciled
    7/7  And then the guard steps in          the outlier repair, and what it did

Two runs of the engine are watched. The first fits the one pair the movie
follows in depth; the second registers the whole recording. They are tied
together by a gate: the pair fit from the first must equal, exactly, the fit the
whole-recording run produced for that same pair.

    python docs/figures/method-movie/make_method_movie.py
    python docs/figures/method-movie/make_method_movie.py --entry 04_drift --pair 4 20

Requires numpy, scipy, tifffile, Pillow and imageio-ffmpeg. `frames.py` beside
this file draws; it is vendored from the method-movie skill and imports nothing
from the package.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

import numpy as np
import tifffile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(HERE))

from ripr.core import (  # noqa: E402
    AlignerOptions,
    LogPlane,
    RegistrationOptions,
    align_log_ratio,
    register_frames,
)
from ripr.types import PixelSupport, Reference, RobustNorm  # noqa: E402

from frames import (  # noqa: E402
    AMBER,
    BLUE,
    GREEN,
    GREY,
    PURPLE,
    RED,
    WHITE,
    Canvas,
    Movie,
    ease,
    levels,
    to_grid,
)

FPS = 25


# --------------------------------------------------------------------------
# 1. watching a real run
# --------------------------------------------------------------------------
# A return tracer, kept here rather than imported so that a clone of this
# repository can rebuild the movie on its own. It patches nothing: Python calls
# back on every function return and `frame.f_locals` at that moment is what the
# function actually held. `parent` reads the calling frame as well, which is
# how a helper called inside a loop can say which iteration it belongs to.

@dataclass(frozen=True)
class Watch:
    name: str
    file: str | None = None
    keep: Sequence[str] | None = None
    parent: Sequence[str] = ()


@dataclass
class Event:
    name: str
    locals: dict[str, Any]
    result: Any
    index: int

    def __getitem__(self, key: str) -> Any:
        return self.locals[key]

    def get(self, key: str, default: Any = None) -> Any:
        return self.locals.get(key, default)

    def has(self, key: str) -> bool:
        return key in self.locals


class Watcher:
    """Photograph named functions as they return, during one ordinary run."""

    def __init__(self, watches: Iterable[Watch]) -> None:
        self.by_name: dict[str, list[Watch]] = {}
        for watch in watches:
            self.by_name.setdefault(watch.name, []).append(watch)
        self.events: list[Event] = []
        self._previous: Callable | None = None

    def _match(self, code) -> Watch | None:
        here = Path(code.co_filename).name
        for watch in self.by_name.get(code.co_name, ()):
            if watch.file is None or watch.file == here:
                return watch
        return None

    def _local(self, frame, event, arg):
        if event != "return":
            return self._local
        watch = self._match(frame.f_code)
        if watch is None:
            return self._local
        held = frame.f_locals
        kept = (dict(held) if watch.keep is None
                else {name: held[name] for name in watch.keep if name in held})
        if watch.parent and frame.f_back is not None:
            above = frame.f_back.f_locals
            kept.update({name: above[name] for name in watch.parent
                         if name in above})
        self.events.append(
            Event(frame.f_code.co_name, kept, arg, len(self.events)))
        return self._local

    def _global(self, frame, event, arg):
        if frame.f_code.co_name in self.by_name and self._match(frame.f_code):
            frame.f_trace_lines = False           # returns only, no line events
            return self._local
        return None

    def __enter__(self) -> "Watcher":
        self._previous = sys.gettrace()
        sys.settrace(self._global)
        return self

    def __exit__(self, *exc) -> None:
        sys.settrace(self._previous)
        self._previous = None

    def all(self, name: str) -> list[Event]:
        return [event for event in self.events if event.name == name]

    def at(self, name: str, index: int = 0) -> Event:
        found = self.all(name)
        if not found:
            raise LookupError(
                f"{name!r} never returned during the watched run; ran "
                f"{sorted({event.name for event in self.events})}")
        return found[index]


PAIR_WATCHES = [
    Watch("from_intensity", "core.py", keep=("image", "values", "valid")),
    Watch("_halve", "core.py", keep=("small", "valid")),
    Watch("_gradient_threshold", "core.py", keep=("values", "stride")),
    Watch("_evaluate", "core.py",
          keep=("x", "y", "stride", "possible", "source_ok", "target_ok",
                "residual", "centred", "gain", "mean_abs", "cost"),
          parent=("dx", "dy", "radius", "best", "max_here", "area_correlation")),
    Watch("weights", "types.py", keep=(),
          parent=("x", "y", "x_all", "y_all", "source_ok", "stride", "possible",
                  "difference", "residual", "gain", "threshold", "transform",
                  "iterations", "gradient_threshold", "cx", "cy")),
    Watch("rho", "types.py", keep=(), parent=("step", "weight", "iterations")),
    Watch("_refine", "core.py",
          keep=("transform", "iterations", "converged", "bailed", "stride",
                "possible", "gradient_threshold"),
          parent=("level", "scale")),
    Watch("align_log_ratio", "core.py",
          keep=("top", "top_scale", "radius", "max_here", "best",
                "best_transform", "status")),
]

RECORDING_WATCHES = [
    Watch("_estimate_pair", "core.py", keep=(),
          parent=("source", "target", "index")),
    Watch("_repair", "core.py",
          keep=("cumulative", "output", "reasons", "magnitudes", "median",
                "scale", "limit")),
]


def settings(entry: Path) -> tuple[int, RegistrationOptions]:
    """The options `ValidationRun.java` used to write this entry's shifts.csv.

    Read rather than restated, because reproducing the call means reproducing
    its arguments: the entry's own `dataset.properties` names the estimation
    channel and the shift bound, and the rest are that run's fixed choices.
    """
    text = {}
    for line in (entry / "dataset.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            text[key.strip()] = value.strip()
    channel = int(text.get("channel", "1"))
    options = RegistrationOptions(
        aligner=AlignerOptions(norm=RobustNorm.TUKEY,
                               support=PixelSupport.GRADIENT,
                               max_shift=float(text.get("maxShift", "30"))),
        reference=Reference.MULTILAG,
        lags=(1, 2, 4, 8, 16),
    )
    return channel, options


def run_watched(frames: np.ndarray, options: RegistrationOptions,
                pair: tuple[int, int]):
    """The ordinary calls, with the tracer on. Nothing else happens here."""
    aligner = options.aligner
    depth = aligner.levels_for(frames.shape[2], frames.shape[1])

    close = Watcher(PAIR_WATCHES)
    started = time.time()
    with close:
        a = LogPlane.from_intensity(frames[pair[0]], options.epsilon).pyramid(depth)
        b = LogPlane.from_intensity(frames[pair[1]], options.epsilon).pyramid(depth)
        fit = align_log_ratio(a, b, aligner)
    print(f"  one pair watched in {time.time() - started:.1f}s, "
          f"{len(close.events)} intermediates")

    wide = Watcher(RECORDING_WATCHES)
    started = time.time()
    with wide:
        result = register_frames(frames, options)
    print(f"  whole recording watched in {time.time() - started:.0f}s, "
          f"{len(result.pairs)} pairs over {result.levels} levels")
    return close, fit, wide, result


# --------------------------------------------------------------------------
# 2. the gate
# --------------------------------------------------------------------------

def check(fit, result, pair: tuple[int, int], accepted: Path) -> float:
    """Refuse to film a run that is not the run this entry ships.

    Two comparisons. The recording's trace against the accepted `shifts.csv`,
    which the Java plugin wrote -- a different implementation, so a tolerance
    rather than equality, and the measured agreement goes on screen. Then the
    pair the movie follows in depth against the same pair inside the whole
    recording run, which must be identical to the last bit.
    """
    rows = list(csv.DictReader(accepted.open()))
    if len(rows) != len(result.cumulative):
        raise SystemExit(
            f"{accepted.name} has {len(rows)} frames, the run produced "
            f"{len(result.cumulative)}. Refusing to write a movie of a "
            "different recording.")
    accepted_trace = np.array([[float(row["cum_dx"]), float(row["cum_dy"])]
                               for row in rows])
    our_trace = np.array([[t.dx, t.dy] for t in result.cumulative])
    worst = float(np.abs(our_trace - accepted_trace).max())
    if not worst < 1e-3:
        raise SystemExit(
            f"the watched run's trace differs from {accepted.name} by "
            f"{worst:.6f} px. Refusing to write a movie of a method this "
            "is not.")

    same = [p for p in result.pairs
            if (p.from_frame, p.to_frame) == tuple(pair)]
    if not same:
        raise SystemExit(
            f"the recording never measured the pair {tuple(pair)} directly, "
            "so the acts that follow it in depth would be showing a fit the "
            "recording did not use. Choose a pair whose lag is in the plan.")
    inside = same[0].fit
    for name in ("dx", "dy", "theta"):
        if getattr(inside.transform, name) != getattr(fit.transform, name):
            raise SystemExit(
                f"the pair filmed in depth measured {name} "
                f"{getattr(fit.transform, name)!r} on its own and "
                f"{getattr(inside.transform, name)!r} inside the recording. "
                "Refusing to film two different runs as one.")
    if inside.log_gain != fit.log_gain:
        raise SystemExit("the pair's fitted gain differs between the two runs.")
    print(f"  parity: trace matches {accepted.name} to {worst:.6f} px; "
          f"the filmed pair is bit-identical to the recording's own")
    return worst


# --------------------------------------------------------------------------
# 3. the frames
# --------------------------------------------------------------------------

def spread(shape, x, y, values, stride: int = 1, fill: float = 0.0):
    """Per-sample numbers put back where the run read them.

    Display only, and the one place this file touches the geometry itself: the
    values are the run's, the positions are the run's, and a sample read every
    `stride` pixels is painted over the block it stood for.
    """
    field = np.full(shape, float(fill), np.float32)
    field[np.asarray(y, int), np.asarray(x, int)] = np.asarray(values, float)
    if stride > 1:
        coarse = field[::stride, ::stride]
        field = np.repeat(np.repeat(coarse, stride, 0), stride, 1)
        field = field[:shape[0], :shape[1]]
    return field


def by_level(watching: Watcher, name: str, guard: str) -> list[list[Event]]:
    """Events grouped into the `_refine` call they happened inside.

    A helper called from a loop returns before the loop does, so everything
    between two `_refine` returns belongs to the second of them. That is the
    only way to say which pyramid level an iteration was on: the level is a
    local of `align_log_ratio`, two frames up.
    """
    groups, current = [], []
    for event in watching.events:
        if event.name == name and event.has(guard):
            current.append(event)
        elif event.name == "_refine":
            groups.append(current)
            current = []
    return groups


def storyboard(close: Watcher, fit, wide: Watcher, result, frames: np.ndarray,
               pair: tuple[int, int], entry: str, agreement: float,
               movie: Movie) -> None:
    height, width = frames.shape[1:]
    cx, cy = (width - 1) / 2.0, (height - 1) / 2.0
    first, second = pair
    black, white = levels(frames[first], 1.0, 99.5)

    planes = close.all("from_intensity")
    log_black, log_white = levels(planes[0]["values"], 1.0, 99.5)
    log_a = planes[0]["values"]

    start = close.at("align_log_ratio")
    refines = close.all("_refine")
    iterations = by_level(close, "weights", "iterations")
    stepping = by_level(close, "rho", "step")

    reports = [e for e in close.all("_evaluate") if e.has("area_correlation")]
    identity, aligned = reports[0], reports[1]
    scale = float(np.percentile(np.abs(identity["centred"]), 98))

    def residual_field(event):
        keep = event["target_ok"]
        return spread((height, width), event["x"][keep], event["y"][keep],
                      np.abs(event["centred"]) / scale, event["stride"])

    shift = math.hypot(fit.transform.dx, fit.transform.dy)

    # -- 1 / 7 ------------------------------------------------------------
    title = "1 / 7   Something knocked the microscope"
    caption = (f"the stage moved {shift:.2f} px between these two frames "
               f"- {100 * shift / width:.0f}% of the frame's width")
    note = (f"{entry}, frames {first + 1} and {second + 1} of {len(frames)}, "
            "half an hour apart - the cells did not move, the stage did")
    for turn in range(4):
        which = frames[second if turn % 2 else first]
        label = f"frame {second + 1 if turn % 2 else first + 1}"
        canvas = Canvas(which, black, white)
        canvas.text(cx, 22, label, AMBER if turn % 2 else BLUE, 22)
        movie.add(canvas.finish(title, caption, note))
        movie.hold(0.75)
    canvas = Canvas(frames[first], black, white)
    canvas.ray(cx, cy, (fit.transform.dx / shift, fit.transform.dy / shift),
               shift, WHITE, 2.0, head=9.0)
    canvas.text(cx, cy - 28, f"{shift:.2f} px", WHITE, 20)
    movie.add(canvas.finish(title, caption,
                            "the arrow is this run's own answer, drawn at the "
                            "length and direction it measured"))
    movie.hold(2.4)

    # -- 2 / 7 ------------------------------------------------------------
    title = "2 / 7   What the method actually compares"
    movie.add(Canvas(log_a, log_black, log_white).finish(
        title,
        f"every pixel as log2(intensity + 1): {int(planes[0]['valid'].sum()):,} "
        f"of {log_a.size:,} valid, spanning {log_a.min():.2f} to {log_a.max():.2f}",
        "dividing one frame by another becomes subtracting them, which is why "
        "a brightness change can be one number here"))
    movie.hold(2.6)

    canvas = Canvas(log_a, log_black, log_white, dim=0.55)
    canvas.wash(residual_field(identity), AMBER, 0.95)
    movie.add(canvas.finish(
        title,
        f"the log-ratio field between the two frames: mean "
        f"{identity['mean_abs']:.3f} over {identity['residual'].size:,} "
        f"sampled points",
        f"amber is disagreement, full strength at {scale:.2f} in log2; an "
        "unmeasured shift lights up every edge in the picture"))
    movie.hold(2.8)

    movie.add(canvas.finish(
        title,
        f"its median is {identity['gain']:+.4f} in log2, and that median is "
        f"the brightness change - subtracted, not chased",
        f"only {100 * (2 ** identity['gain'] - 1):+.1f}% on this pair; the same "
        "single number would absorb a global fade of any size"))
    movie.hold(2.8)

    # -- 3 / 7 ------------------------------------------------------------
    title = "3 / 7   Which pixels are allowed to vote"
    finest = iterations[-1][0]
    stride = finest["stride"]
    support = spread((height, width), finest["x_all"], finest["y_all"],
                     finest["source_ok"].astype(float), stride) > 0.5
    sampled = spread((height, width), finest["x_all"], finest["y_all"],
                     np.ones(finest["x_all"].size), stride) > 0.5
    canvas = Canvas(log_a, log_black, log_white, dim=0.75)
    canvas.fill(sampled & ~support, GREY, 0.92)
    canvas.fill(support, GREEN, 0.30)
    movie.add(canvas.finish(
        title,
        f"{int(finest['source_ok'].sum()):,} of {finest['possible']:,} sample "
        f"points clear a contrast threshold of "
        f"{finest['gradient_threshold']:.3f} in log2 per pixel",
        "green votes, flat grey does not - the threshold is half this "
        "frame's own median gradient"))
    movie.hold(3.2)

    # -- 4 / 7 ------------------------------------------------------------
    title = "4 / 7   A coarse guess first"
    coarse = close.all("_halve")[:len(refines) - 1]
    ladder = [log_a] + [event["small"] for event in coarse]
    for index, plane in enumerate(ladder):
        movie.add(Canvas(to_grid(plane, (height, width)),
                         log_black, log_white).finish(
            title,
            f"level {index}: {plane.shape[1]} x {plane.shape[0]} pixels, "
            f"{'the frame as it came' if not index else 'blurred and halved'}",
            f"{len(ladder)} levels, each a 5-tap blur and every second pixel; "
            f"at 1 in {2 ** index} a {shift:.0f} px knock is "
            f"{shift / 2 ** index:.1f} px"))
        movie.hold(0.85)
    movie.hold(1.1)

    top = ladder[-1]
    top_scale = start["top_scale"]
    coarse_picture = to_grid(top, (height, width))
    candidates = [e for e in close.all("_evaluate") if e.has("dx")]
    seen: list[tuple[float, float]] = []
    best_value = float(candidates[0]["best"])       # the score before any of them
    best_at = (0.0, 0.0)                            # which is the identity
    for index, event in enumerate(candidates):
        seen.append((event["dx"] * top_scale, event["dy"] * top_scale))
        if event["mean_abs"] < best_value:
            best_value = float(event["mean_abs"])
            best_at = (event["dx"] * top_scale, event["dy"] * top_scale)
        if index % 3 and index != len(candidates) - 1:
            continue
        canvas = Canvas(coarse_picture, log_black, log_white, dim=0.62)
        for dx, dy in seen:
            canvas.dot(cx + dx, cy + dy, 3.0, PURPLE)
        canvas.dot(cx + best_at[0], cy + best_at[1], 6.0, WHITE)
        movie.add(canvas.finish(
            title,
            f"trying dx {event['dx']:+.0f}, dy {event['dy']:+.0f} at this "
            f"level: mean |log-ratio| {event['mean_abs']:.4f}",
            f"best so far {best_value:.4f} - {len(candidates)} candidates, "
            f"out to {start['max_here']:.1f} px here and "
            f"{start['max_here'] * top_scale:.0f} px in the full frame"))
    canvas = Canvas(coarse_picture, log_black, log_white, dim=0.62)
    for dx, dy in seen:
        canvas.dot(cx + dx, cy + dy, 3.0, PURPLE)
    winner = start["best_transform"]
    canvas.dot(cx + winner.dx * top_scale, cy + winner.dy * top_scale, 7.0, WHITE)
    canvas.text(cx + winner.dx * top_scale, cy + winner.dy * top_scale - 34,
                f"dx {winner.dx:+.0f}, dy {winner.dy:+.0f}", WHITE, 19)
    movie.add(canvas.finish(
        title,
        f"the best of them is dx {winner.dx:+.0f}, dy {winner.dy:+.0f}, "
        f"scoring {start['best']:.4f}",
        f"one pixel here is {top_scale:.0f} in the full frame, so a whole-pixel "
        f"guess is already worth {abs(winner.dx) * top_scale:.0f} px"))
    movie.hold(2.8)

    # -- 5 / 7 ------------------------------------------------------------
    title = "5 / 7   Then the fit, four times over"
    for index, refine in enumerate(refines):
        level = refine["level"]
        plane = ladder[level]
        picture = to_grid(plane, (height, width))
        rounds, steps = iterations[index], stepping[index]
        residual = thrown = None
        for event in rounds:
            residuals = np.abs(event["residual"])
            cut = event["threshold"]
            refused = int((residuals >= cut).sum())
            residual = to_grid(
                spread(plane.shape, event["x"], event["y"], residuals / cut,
                       event["stride"]), (height, width))
            thrown = to_grid(
                spread(plane.shape, event["x"], event["y"],
                       (residuals >= cut).astype(float), event["stride"]),
                (height, width)) > 0.5
            canvas = Canvas(picture, log_black, log_white, dim=0.6)
            canvas.wash(residual, AMBER, 0.9)
            canvas.fill(thrown, RED, 0.75)
            movie.add(canvas.finish(
                title,
                f"level {level}, {plane.shape[1]} px wide: pass "
                f"{event['iterations'] + 1} at dx {event['transform'].dx:+.3f}, "
                f"dy {event['transform'].dy:+.3f}",
                f"{residuals.size:,} points weighted; {refused:,} past the "
                f"Tukey cut of {cut:.3f} get no vote at all"))
            movie.hold(0.42)
        last = steps[-1] if steps else None
        canvas = Canvas(picture, log_black, log_white, dim=0.6)
        canvas.wash(residual, AMBER, 0.9)
        canvas.fill(thrown, RED, 0.75)
        movie.add(canvas.finish(
            title,
            f"level {level} settles at dx {refine['transform'].dx:+.3f}, "
            f"dy {refine['transform'].dy:+.3f} after "
            f"{refine['iterations'] + 1} passes",
            (f"its last step moved {math.hypot(*last['step'][:2]):.4f} px; "
             if last is not None else "")
            + ("doubled and handed down to the next level"
               if level else "and this is the answer for this pair")))
        movie.hold(1.6)

    canvas = Canvas(log_a, log_black, log_white, dim=0.55)
    canvas.wash(residual_field(aligned), AMBER, 0.95)
    movie.add(canvas.finish(
        title,
        f"the field from act 2 again, now with the fit applied: mean "
        f"{aligned['mean_abs']:.3f}, down from {identity['mean_abs']:.3f}",
        f"{100 * (1 - aligned['mean_abs'] / identity['mean_abs']):.0f}% of the "
        f"disagreement gone, on the same colour scale as before"))
    movie.hold(3.2)

    # -- 6 / 7 ------------------------------------------------------------
    title = "6 / 7   Every pair, then one trace"
    anchor = frames[0]
    window, view = 128.0, (cx - 16.0, cy)
    left, top_edge = int(view[0] - window / 2), int(view[1] - window / 2)
    near_black, near_white = levels(
        anchor[top_edge:top_edge + int(window), left:left + int(window)],
        2.0, 99.0)
    measured = wide.all("_estimate_pair")
    straddling = [e for e in measured if e["source"] <= first < e["target"]]

    drawn = []
    for index, event in enumerate(measured):
        drawn.append(event)
        if index % 4 and index != len(measured) - 1:
            continue
        canvas = Canvas(anchor, near_black, near_white, dim=0.5)
        for done in drawn:
            canvas.dot(cx + done.result.transform.dx,
                       cy + done.result.transform.dy, 0.7, PURPLE)
        movie.add(canvas.finish(
            title,
            f"{len(drawn)} of {len(measured)} pairs measured, at gaps of "
            f"1, 2, 4, 8 and 16 frames",
            "one dot per pair, where that pair alone says the later frame "
            "sits; no pair is told about any other",
            window=window, centre=view))
    canvas = Canvas(anchor, near_black, near_white, dim=0.5)
    for done in measured:
        canvas.dot(cx + done.result.transform.dx,
                   cy + done.result.transform.dy, 0.7, PURPLE)
    for done in straddling:
        canvas.dot(cx + done.result.transform.dx,
                   cy + done.result.transform.dy, 1.3, AMBER)
    reach = [e.result.transform.dx for e in straddling]
    movie.add(canvas.finish(
        title,
        f"{len(straddling)} of them straddle the knock, and they agree: "
        f"{max(reach):.1f} to {min(reach):.1f} px",
        "the crowd at zero is every pair that does not cross it - "
        f"{window:.0f} px of {width} shown",
        window=window, centre=view))
    movie.hold(3.2)

    repair = wide.at("_repair")
    raw = [(t.dx, t.dy) for t in repair["cumulative"]]
    canvas = Canvas(anchor, near_black, near_white, dim=0.5)
    canvas.path([(cx + dx, cy + dy) for dx, dy in raw], WHITE, 0.28)
    for dx, dy in raw:
        canvas.dot(cx + dx, cy + dy, 0.7, WHITE)
    movie.add(canvas.finish(
        title,
        f"one position per frame, solved from all {len(measured)}: median "
        f"residual {result.median_residual_before:.3f} before the fit, "
        f"{result.median_residual_after:.3f} after",
        f"the path the stage took across {len(frames)} frames, drawn in this "
        f"frame's own pixels", window=window, centre=view))
    movie.hold(3.2)

    # -- 7 / 7 ------------------------------------------------------------
    title = "7 / 7   And then the guard steps in"
    fixed = [(t.dx, t.dy) for t in repair["output"]]
    changed = [t for t, reason in enumerate(repair["reasons"])
               if reason is not None]
    canvas = Canvas(anchor, near_black, near_white, dim=0.5)
    canvas.path([(cx + dx, cy + dy) for dx, dy in raw], WHITE, 0.28)
    for t in changed:
        canvas.dot(cx + raw[t][0], cy + raw[t][1], 1.5, RED)
    movie.add(canvas.finish(
        title,
        f"the biggest step in that path is {repair['magnitudes'].max():.1f} px, "
        f"against a limit of {repair['limit']:.1f} px",
        f"six times the median step of {repair['median']:.2f} px, or the "
        f"median plus 8 robust deviations - whichever is larger",
        window=window, centre=view))
    movie.hold(3.0)

    for turn in range(8):
        mix = ease(turn, 8)
        blend = [(a[0] + mix * (b[0] - a[0]), a[1] + mix * (b[1] - a[1]))
                 for a, b in zip(raw, fixed)]
        canvas = Canvas(anchor, near_black, near_white, dim=0.5)
        canvas.path([(cx + dx, cy + dy) for dx, dy in blend], WHITE, 0.28)
        for t in changed:
            canvas.dot(cx + blend[t][0], cy + blend[t][1], 1.5, RED)
        movie.add(canvas.finish(
            title,
            f"so frame {changed[0] + 1} is refused and replaced by the "
            f"midpoint of its neighbours",
            "camera work: the run moved it in one go; this slides it so "
            "you can see which point moved", window=window, centre=view))
    movie.hold(3.4)

    canvas = Canvas(anchor, near_black, near_white, dim=0.5)
    canvas.path([(cx + dx, cy + dy) for dx, dy in fixed], WHITE, 0.28)
    for t in changed:
        canvas.dot(cx + fixed[t][0], cy + fixed[t][1], 1.5, RED)
    movie.add(canvas.finish(
        title,
        f"{len(changed)} of {len(frames)} frames repaired, reason: "
        f"{repair['reasons'][changed[0]].value.replace('_', ' ')}",
        f"{len(straddling)} pairs measured that knock; the guard sees step "
        "sizes, not the fits behind them",
        window=window, centre=view))
    movie.hold(3.6)

    # -- the closing card --------------------------------------------------
    movie.add(Canvas(anchor, black, white).finish(
        "One real run, photographed from inside",
        f"the same engine call the plugin makes; this run's trace matches the "
        f"accepted shifts.csv to {agreement:.6f} px",
        f"{entry}, phase contrast, {len(frames)} frames 30 minutes apart, "
        f"{len(measured)} pair fits, and no code changed to make this"))
    movie.hold(4.0)


# --------------------------------------------------------------------------
# 4. read, run, check, render, record
# --------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--entry", default="06_knock",
                        help="a folder under library/ with original.tif and shifts.csv")
    parser.add_argument("--pair", type=int, nargs=2, default=(45, 46),
                        metavar=("FROM", "TO"),
                        help="the zero-based frame pair to follow in depth")
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--fps", type=int, default=FPS)
    arguments = parser.parse_args()

    entry = ROOT / "library" / arguments.entry
    source = entry / "original.tif"
    accepted = entry / "shifts.csv"
    if not source.exists() or not accepted.exists():
        raise SystemExit(f"{entry} has no original.tif and shifts.csv to work from")

    channel, options = settings(entry)
    stack = tifffile.imread(source)
    frames = np.asarray(stack[:, channel - 1] if stack.ndim == 4 else stack,
                        np.float32)
    print(f"{arguments.entry}: {frames.shape[0]} frames of "
          f"{frames.shape[2]}x{frames.shape[1]}, channel {channel}, "
          f"max shift {options.aligner.max_shift:.0f} px")

    close, fit, wide, result = run_watched(frames, options, tuple(arguments.pair))
    agreement = check(fit, result, tuple(arguments.pair), accepted)

    target = arguments.out or (HERE / f"{arguments.entry}_log_ratio_fit.mp4")
    movie = Movie(target, fps=arguments.fps, window_px=frames.shape[1],
                  zoom=1.5, crf=18, preset="slow")
    storyboard(close, fit, wide, result, frames, tuple(arguments.pair),
               arguments.entry, agreement, movie)
    written = movie.write()
    print(f"  {written}  {len(movie)} frames, {movie.seconds:.0f}s")

    record = {
        "movie": written.name,
        "display_only": True,
        "made_by": Path(__file__).name,
        "input": {
            "file": str(source.relative_to(ROOT)).replace("\\", "/"),
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "channel": channel,
            "frames": int(frames.shape[0]),
            "shape": [int(frames.shape[1]), int(frames.shape[2])],
        },
        "call": {
            "estimator": "log_ratio_fit",
            "norm": options.aligner.norm.value,
            "support": options.aligner.support.value,
            "max_shift_px": options.aligner.max_shift,
            "reference": options.reference.value,
            "lags": list(options.lags),
            "levels": int(result.levels),
        },
        "parity": {
            "against": str(accepted.relative_to(ROOT)).replace("\\", "/"),
            "worst_cumulative_difference_px": agreement,
            "pair_filmed_matches_recording": True,
            "pair": list(arguments.pair),
        },
        "pair_fit": {
            "dx": fit.transform.dx, "dy": fit.transform.dy,
            "log2_gain": fit.log_gain,
            "residual_before": fit.residual_before,
            "residual_after": fit.residual_after,
            "iterations": int(fit.iterations),
            "status": fit.status.value,
        },
        "encoding": {"fps": arguments.fps, "frames": len(movie),
                     "seconds": round(movie.seconds, 1), "codec": "libx264",
                     "crf": 18, "preset": "slow"},
    }
    (target.with_suffix(".json")).write_text(json.dumps(record, indent=2))
    print(f"  {target.with_suffix('.json').name}")


if __name__ == "__main__":
    main()
