# Vendored from the "method-movie" skill (scripts/frames.py), unchanged, so
# that this folder rebuilds its movie from a clone of this repository
# alone. Nothing in the package imports it; it draws frames, nothing else.
"""Turn watched intermediates into frames, and frames into a movie.

Everything a spatial process needs on screen: a greyscale picture of the data,
masks laid over it, lines and arrows and words in the data's own coordinates,
two caption bands that never change the frame size, and a streaming encoder.

Depends on numpy, Pillow and imageio-ffmpeg only. Nothing here knows what is
being filmed, and nothing here computes anything about it: every array it draws
came out of the run through `watch.py`.

    from frames import Canvas, Movie, boundary, reveal_order

    movie = Movie("method.mp4", fps=25, window_px=560, zoom=1.4)
    canvas = Canvas(image, black, white)
    canvas.fill(mask, GREEN, 0.3).edge(mask, WHITE, 2)
    movie.add(canvas.finish("2 / 7  The region", "grown to 65,352 px",
                            "flood from the core, low = field + 0.50 x range"))
    movie.hold(2.0)
    movie.write()

**The frame size never changes.** Every intermediate in a process is a
different shape -- a rotated canvas, a cropped stack, a padded raster -- and an
encoder fed changing sizes either fails or silently rescales. `Canvas.finish`
takes a fixed square window on the array's own centre, so shapes may change
underneath while the shot does not.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np
from PIL import Image, ImageDraw, ImageFont

__all__ = ["Canvas", "Movie", "screen", "levels", "grey", "tint", "boundary",
           "contours", "to_grid", "reveal_order", "ease", "band",
           "fitted_size", "font_for", "pad_to_even", "sample_frames",
           "WHITE", "BLACK", "GREY", "RED", "AMBER", "GREEN", "BLUE",
           "PURPLE", "TEAL"]


# A palette that stays apart on a grey picture and in the two common kinds of
# colour blindness. Blue and amber carry the two halves of a pair; red is only
# ever "this is being taken away"; green is "this is being kept".
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
GREY = (150, 150, 150)
RED = (238, 82, 78)
AMBER = (255, 214, 82)
GREEN = (118, 199, 134)
BLUE = (79, 190, 247)
PURPLE = (198, 122, 224)
TEAL = (96, 172, 176)

FONTS = ("DejaVuSans-Bold.ttf", "DejaVuSans.ttf", "arialbd.ttf", "arial.ttf")


# --------------------------------------------------------------------------
# turning data into a picture
# --------------------------------------------------------------------------

def screen(image: Any, black: float, white: float) -> np.ndarray:
    """Data to 0..1 screen levels, clipped at both ends."""
    values = (np.asarray(image, np.float32) - float(black))
    return np.clip(values / max(float(white) - float(black), 1e-9), 0.0, 1.0)


def levels(image: Any, low_pct: float = 35.0,
           high_pct: float = 99.7) -> tuple[float, float]:
    """Black and white points from percentiles of the data.

    Fix these **once**, from the first array, and pass the same pair to every
    frame. Re-measuring per frame makes the picture brighten and dim as the
    process runs, which reads as the data changing when only the display did.
    """
    finite = np.asarray(image, np.float32)
    finite = finite[np.isfinite(finite)]
    return (float(np.percentile(finite, low_pct)),
            float(np.percentile(finite, high_pct)))


def grey(values: np.ndarray) -> np.ndarray:
    """Screen levels to 8-bit greyscale RGB."""
    eight = np.asarray(np.clip(values, 0, 1) * 255.0 + 0.5, np.uint8)
    return np.repeat(eight[..., None], 3, axis=-1)


def tint(values: np.ndarray, colour: Sequence[float]) -> np.ndarray:
    """Screen levels through a single-colour ramp, e.g. a red channel."""
    weights = np.asarray(colour, np.float32) / 255.0
    return np.asarray(np.clip(values, 0, 1)[..., None] * weights * 255.0 + 0.5,
                      np.uint8)


# --------------------------------------------------------------------------
# masks
# --------------------------------------------------------------------------

def boundary(mask: Any, width_px: int = 2) -> np.ndarray:
    """The pixels of `mask` that sit beside a different value.

    Works on a boolean mask and on a label image: on labels it draws the line
    between two touching regions as well as the outer edge, which is what
    makes two lobes read as two.
    """
    values = np.asarray(mask)
    active = values > 0
    edge = np.zeros(values.shape, bool)
    changed = values[1:, :] != values[:-1, :]
    edge[1:, :] |= active[1:, :] & changed
    edge[:-1, :] |= active[:-1, :] & changed
    changed = values[:, 1:] != values[:, :-1]
    edge[:, 1:] |= active[:, 1:] & changed
    edge[:, :-1] |= active[:, :-1] & changed
    edge[0, :] |= active[0, :]          # the frame edge counts as an edge
    edge[-1, :] |= active[-1, :]
    edge[:, 0] |= active[:, 0]
    edge[:, -1] |= active[:, -1]
    for _ in range(max(0, int(width_px) - 1)):
        padded = np.pad(edge, 1)
        edge = np.logical_or.reduce([
            padded[y:y + values.shape[0], x:x + values.shape[1]]
            for y in range(3) for x in range(3)])
    return edge


def contours(mask: Any) -> list[list[tuple[float, float]]]:
    """Ordered (x, y) paths round `mask`, longest first, for tracing a pen.

    scikit-image draws them properly where it is installed. Without it the
    boundary pixels are ordered by angle about the centroid, which draws a
    convex shape correctly and a horseshoe with one jump.
    """
    mask = np.asarray(mask, bool)
    try:
        from skimage import measure
    except ImportError:
        ys, xs = np.nonzero(boundary(mask, 1))
        if not len(xs):
            return []
        cx, cy = xs.mean(), ys.mean()
        order = np.argsort(np.arctan2(ys - cy, xs - cx))
        return [[(float(xs[i]), float(ys[i])) for i in order]]
    found = measure.find_contours(np.pad(mask.astype(float), 1), 0.5)
    paths = [[(float(x) - 1.0, float(y) - 1.0) for y, x in path]
             for path in found]
    return sorted(paths, key=len, reverse=True)


def to_grid(array: Any, shape: Sequence[int]) -> np.ndarray:
    """A coarse intermediate put back on the full grid, nearest-neighbour.

    Processes that work on a downsampled copy hold masks at that copy's shape.
    Drawing one straight onto the full picture puts it in the wrong place and
    at the wrong size; drawing it interpolated invents an edge the process
    never had. Nearest-neighbour keeps the block structure visible, which is
    the truth about that step: it decided in blocks. Say the factor on screen.
    """
    values = np.asarray(array)
    height, width = int(shape[0]), int(shape[1])
    rows = np.minimum((np.arange(height) * values.shape[0]) // height,
                      values.shape[0] - 1)
    cols = np.minimum((np.arange(width) * values.shape[1]) // width,
                      values.shape[1] - 1)
    return values[rows[:, None], cols[None, :]]


def reveal_order(target: Any, seed: Any, limit: int = 4000) -> np.ndarray:
    """Steps from `seed` through `target`, for filling a mask outward.

    Camera work, and it must be captioned as such: the mask is the run's, the
    order it appears in is this function's. It is worth it where the process
    itself is a flood or a growth, because the reveal then says something true
    about how the region was reached.
    """
    from scipy import ndimage

    target = np.asarray(target, bool)
    frontier = np.asarray(seed, bool) & target
    distance = np.full(target.shape, np.inf)
    distance[frontier] = 0.0
    for step in range(1, int(limit) + 1):
        grown = ndimage.binary_dilation(frontier) & target
        new = grown & (distance == np.inf)
        if not new.any():
            break
        distance[new] = step
        frontier = grown
    return distance


def ease(step: int, count: int) -> float:
    """0 to 1 with the ends slowed, for a tween that does not start hard."""
    if count <= 1:
        return 1.0
    return 0.5 - 0.5 * math.cos(math.pi * min(1.0, (step + 1) / count))


# --------------------------------------------------------------------------
# words
# --------------------------------------------------------------------------

def font_for(size: int):
    for name in FONTS:
        try:
            return ImageFont.truetype(name, int(size))
        except OSError:
            pass
        found = _bundled(name)
        if found is not None:
            try:
                return ImageFont.truetype(str(found), int(size))
            except OSError:
                pass
    return ImageFont.load_default()


def _bundled(name: str) -> Path | None:
    try:
        import matplotlib
    except ImportError:
        return None
    found = (Path(matplotlib.__file__).parent / "mpl-data" / "fonts" / "ttf"
             / name)
    return found if found.exists() else None


def fitted_size(text: str, width: int, size: int, floor: int = 9) -> int:
    """The largest size at or under `size` that keeps `text` inside `width`.

    A caption running off both edges of the frame says less than a smaller one
    that fits, and the numbers in these captions are the whole point of them.
    """
    for trying in range(int(size), int(floor) - 1, -1):
        if font_for(trying).getlength(text) <= width - 16:
            return trying
    return int(floor)


def band(rgb: np.ndarray, text: str, *, height: int, size: int,
         top: bool = False) -> np.ndarray:
    """One line of text in a black band added above or below the frame.

    An empty line keeps its band. Every frame in a stream must be the same
    size, and a caption that only sometimes appears would otherwise change it.
    """
    frame = np.asarray(rgb, np.uint8)
    height = int(height)
    if height <= 0:
        return frame
    text = text or " "
    canvas = np.zeros((frame.shape[0] + height, frame.shape[1], 3), np.uint8)
    if top:
        canvas[height:] = frame
        band_top = 0
    else:
        canvas[:frame.shape[0]] = frame
        band_top = frame.shape[0]
    picture = Image.fromarray(canvas)
    pen = ImageDraw.Draw(picture)
    font = font_for(fitted_size(text, frame.shape[1], size))
    bounds = pen.textbbox((0, 0), text, font=font)
    x = max(0, (canvas.shape[1] - (bounds[2] - bounds[0])) // 2)
    y = band_top + max(0, (height - (bounds[3] - bounds[1])) // 2 - bounds[1])
    pen.text((x, y), text, font=font, fill=WHITE)
    return np.asarray(picture)


def pad_to_even(rgb: np.ndarray) -> np.ndarray:
    frame = np.asarray(rgb, np.uint8)
    height = frame.shape[0] + frame.shape[0] % 2
    width = frame.shape[1] + frame.shape[1] % 2
    if (height, width) == frame.shape[:2]:
        return frame
    canvas = np.zeros((height, width, 3), np.uint8)
    canvas[:frame.shape[0], :frame.shape[1]] = frame
    return canvas


# --------------------------------------------------------------------------
# the frame
# --------------------------------------------------------------------------

class Canvas:
    """One frame under construction, in the data's own pixels until `finish`.

    Masks are composited at data resolution; lines, boxes and words are drawn
    after the zoom so they stay crisp. Both take coordinates in data pixels,
    so a point measured by the process can be pointed at without arithmetic.
    """

    #: the fixed shot, shared by every Canvas unless `finish` is told otherwise
    window_px = 560
    zoom = 1.4
    title_band = 46
    caption_band = 40

    def __init__(self, image: Any, black: float = 0.0, white: float = 1.0, *,
                 dim: float = 1.0, colour: Sequence[float] | None = None
                 ) -> None:
        painted = (grey(screen(image, black, white)) if colour is None
                   else tint(screen(image, black, white), colour))
        self.rgb = np.asarray(painted, np.float32) * float(dim)
        self.shape = self.rgb.shape[:2]
        self.vectors: list[tuple] = []

    # -- masks -------------------------------------------------------------
    def fill(self, mask: Any, colour, alpha: float = 0.35) -> "Canvas":
        mask = np.asarray(mask, bool)
        if mask.any():
            patch = self.rgb[mask]
            self.rgb[mask] = (patch * (1.0 - alpha)
                              + np.asarray(colour, float) * alpha)
        return self

    def edge(self, mask: Any, colour, width: int = 2,
             alpha: float = 1.0) -> "Canvas":
        values = np.asarray(mask)
        if values.any():
            return self.fill(boundary(values, width), colour, alpha)
        return self

    def wash(self, field: Any, colour, alpha: float = 0.85) -> "Canvas":
        """One colour laid on at a strength the data sets, pixel by pixel.

        For a continuous quantity the process measured -- a residual, a
        weight, a score -- where a threshold would throw away the thing worth
        seeing. `field` is already scaled to 0..1; do that with the run's own
        numbers and say the scale in the note, or the picture is a claim about
        magnitude that nothing supports.
        """
        strength = np.clip(np.asarray(field, np.float32), 0.0, 1.0) * float(alpha)
        if not strength.any():
            return self
        weight = strength[..., None]
        self.rgb = (self.rgb * (1.0 - weight)
                    + np.asarray(colour, np.float32) * weight)
        return self

    def labels(self, labels: Any, colours=(BLUE, AMBER), alpha: float = 0.26,
               rest=GREEN) -> "Canvas":
        """A label image, each value in its own colour. 0 is left alone."""
        values = np.asarray(labels)
        for index, value in enumerate(v for v in np.unique(values) if v > 0):
            colour = (colours[index] if index < len(colours) else rest)
            self.fill(values == value, colour, alpha)
        return self

    # -- vectors, in data coordinates --------------------------------------
    def line(self, x0, y0, x1, y1, colour, width: float = 2.0) -> "Canvas":
        self.vectors.append(("line", (x0, y0, x1, y1), colour, width))
        return self

    def infinite(self, angle_deg: float, cut: float, colour,
                 width: float = 2.0, reach: float = 4000.0) -> "Canvas":
        """The line ``x cos a + y sin a == cut``, drawn across the frame.

        The commonest way a process names a line, and the commonest thing to
        get wrong by eye: take the angle and the offset out of the run and
        draw them, rather than fitting something that looks similar.
        """
        theta = math.radians(float(angle_deg))
        px, py = cut * math.cos(theta), cut * math.sin(theta)
        dx, dy = -math.sin(theta), math.cos(theta)
        return self.line(px - reach * dx, py - reach * dy,
                         px + reach * dx, py + reach * dy, colour, width)

    def ray(self, x, y, vector, length, colour, width: float = 2.0,
            head: float = 0.0) -> "Canvas":
        vx, vy = float(vector[0]), float(vector[1])
        x1, y1 = x + vx * length, y + vy * length
        self.line(x, y, x1, y1, colour, width)
        if head:
            for sign in (+1, -1):
                angle = math.atan2(vy, vx) + sign * math.radians(150.0)
                self.line(x1, y1, x1 + head * math.cos(angle),
                          y1 + head * math.sin(angle), colour, width)
        return self

    def box(self, x0, y0, x1, y1, colour, width: float = 2.0) -> "Canvas":
        self.vectors.append(("box", (x0, y0, x1, y1), colour, width))
        return self

    def path(self, points, colour, width: float = 2.0) -> "Canvas":
        points = list(points)
        if len(points) > 1:
            self.vectors.append(("path", points, colour, width))
        return self

    def trace(self, mask: Any, share: float, colour, width: float = 2.5,
              paths: int = 4) -> "Canvas":
        """Draw `share` of the way round a mask's outline, as a pen would.

        Camera work: the outline is the run's, the pen is not. Caption it.
        """
        for found in contours(mask)[:paths]:
            self.path(found[:max(2, int(len(found) * share))], colour, width)
        return self

    def dot(self, x, y, radius, colour) -> "Canvas":
        self.vectors.append(("dot", (x, y, radius), colour, 0))
        return self

    def text(self, x, y, words: str, colour, size: float = 15.0) -> "Canvas":
        """A word in the picture, centred on (x, y) in data pixels."""
        self.vectors.append(("text", (x, y, str(words)), colour, size))
        return self

    # -- finishing ---------------------------------------------------------
    def finish(self, title: str = "", caption: str = "", note: str = "", *,
               window: float | None = None,
               centre: tuple[float, float] | None = None) -> np.ndarray:
        """The finished frame: fixed window, fixed size, three lines of words.

        `window` and `centre` move the shot without moving anything in it.
        That is camera work and belongs to the last act, not to the middle of
        a comparison.
        """
        height, width = self.shape
        side = int(round(window if window else self.window_px))
        middle = centre if centre else ((width - 1) / 2.0, (height - 1) / 2.0)
        left = int(round(middle[0] - (side - 1) / 2.0))
        top = int(round(middle[1] - (side - 1) / 2.0))

        source = np.clip(self.rgb, 0, 255).astype(np.uint8)
        frame = np.zeros((side, side, 3), np.uint8)
        y0, x0 = max(0, top), max(0, left)
        y1, x1 = min(height, top + side), min(width, left + side)
        if y1 > y0 and x1 > x0:
            frame[y0 - top:y1 - top, x0 - left:x1 - left] = source[y0:y1, x0:x1]

        display = int(self.window_px * self.zoom)
        scale = display / side
        picture = Image.fromarray(frame).resize((display, display),
                                                Image.BILINEAR)
        pen = ImageDraw.Draw(picture)

        def to_display(x, y):
            return ((float(x) - left) * scale, (float(y) - top) * scale)

        for kind, data, colour, size in self.vectors:
            colour = tuple(int(value) for value in colour)
            thickness = max(1, int(round(size * scale)))
            if kind == "line":
                ax, ay, bx, by = data
                pen.line([to_display(ax, ay), to_display(bx, by)],
                         fill=colour, width=thickness)
            elif kind == "box":
                ax, ay, bx, by = data
                pen.rectangle([to_display(ax, ay), to_display(bx, by)],
                              outline=colour, width=thickness)
            elif kind == "path":
                pen.line([to_display(x, y) for x, y in data], fill=colour,
                         width=thickness, joint="curve")
            elif kind == "dot":
                x, y, radius = data
                cx, cy = to_display(x, y)
                r = radius * scale
                pen.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colour)
            elif kind == "text":
                x, y, words = data
                font = font_for(max(10, int(round(size))))
                cx, cy = to_display(x, y)
                pen.text((cx - font.getlength(words) / 2, cy), words,
                         font=font, fill=colour, stroke_width=2,
                         stroke_fill=BLACK)

        out = band(np.asarray(picture), title, height=self.title_band,
                   size=25, top=True)
        out = band(out, caption, height=self.caption_band, size=19)
        out = band(out, note, height=self.caption_band - 6, size=16)
        return pad_to_even(out)


# --------------------------------------------------------------------------
# the movie
# --------------------------------------------------------------------------

class Movie:
    """Frames in, one file out, streamed so no stack is ever held whole.

    Frames are buffered as a list of finished uint8 arrays by default, which
    is fine for the thousand-odd frames a method movie runs to. Pass a
    generator to :meth:`write` instead for anything longer.
    """

    def __init__(self, path: Any, *, fps: int = 25, window_px: int = 560,
                 zoom: float = 1.4, crf: int = 17, preset: str = "slow"
                 ) -> None:
        self.path = Path(path)
        self.fps = int(fps)
        self.crf = int(crf)
        self.preset = str(preset)
        self.frames: list[np.ndarray] = []
        Canvas.window_px = int(window_px)
        Canvas.zoom = float(zoom)

    # -- collecting --------------------------------------------------------
    def add(self, frame: np.ndarray) -> np.ndarray:
        self.frames.append(np.asarray(frame, np.uint8))
        return self.frames[-1]

    def hold(self, seconds: float, frame: np.ndarray | None = None) -> None:
        """Keep the last frame on screen. Every act needs one before the cut.

        A step that appears and vanishes inside a fifth of a second has not
        been shown, whatever the caption says.
        """
        held = self.frames[-1] if frame is None else np.asarray(frame, np.uint8)
        for _ in range(max(1, int(round(float(seconds) * self.fps)))):
            self.frames.append(held)

    def __len__(self) -> int:
        return len(self.frames)

    @property
    def seconds(self) -> float:
        return len(self.frames) / self.fps

    # -- writing -----------------------------------------------------------
    def write(self, frames: Iterable[np.ndarray] | None = None, *,
              overwrite: bool = True) -> Path:
        import imageio.v2 as imageio

        stream = self.frames if frames is None else frames
        if self.path.exists() and not overwrite:
            raise FileExistsError(f"refusing to replace {self.path}")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(f".{self.path.stem}.tmp{self.path.suffix}")
        writer = imageio.get_writer(
            str(temporary), format="FFMPEG", fps=self.fps, codec="libx264",
            quality=None, macro_block_size=1, pixelformat="yuv420p",
            ffmpeg_params=["-crf", str(self.crf), "-preset", self.preset,
                           "-movflags", "+faststart"])
        shape = None
        count = 0
        try:
            for frame in stream:
                frame = np.asarray(frame, np.uint8)
                if shape is None:
                    shape = frame.shape
                elif frame.shape != shape:
                    raise ValueError(
                        f"frame {count} is {frame.shape}, the stream is "
                        f"{shape}. Every frame must be one size -- let "
                        f"Canvas.finish fix the window rather than drawing "
                        f"each intermediate at its own shape.")
                writer.append_data(frame)
                count += 1
        finally:
            writer.close()
        if not count:
            temporary.unlink(missing_ok=True)
            raise ValueError("no frames; refusing to leave an empty movie")
        temporary.replace(self.path)
        return self.path


def sample_frames(path: Any, indices: Sequence[int], out_dir: Any) -> list[Path]:
    """Pull frames back out of a finished movie, to look at them.

    Step 7 of the skill is not optional and this is what it runs on.
    """
    import imageio.v2 as imageio

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    reader = imageio.get_reader(str(path))
    total = reader.count_frames()
    written = []
    for index in indices:
        frame = reader.get_data(min(int(index), total - 1))
        target = out / f"f{int(index):05d}.png"
        Image.fromarray(frame).save(target)
        written.append(target)
    reader.close()
    return written
