#!/usr/bin/env python3
"""Source code as a figure: one syntax-highlighted panel per idea.

The grammar is ``code-panel``. It answers a claim of the form "this is the
whole call", "these two libraries take the same call", or "the shown plan is
the plan that runs" -- a claim about *what the code says*, which a screenshot
of an editor makes unreadable at print size and a fenced block in a README
cannot carry into a slide or a paper.

Why a hand-built SVG and not Matplotlib. The figure is text on a rounded
rectangle. Matplotlib would give a raster of it, or an SVG whose every glyph is
a path, and both lose the thing that makes a code figure worth having: real,
selectable, editable text at any zoom. So this writes the SVG directly, one
``<tspan>`` per run of same-coloured characters, and matches the house
aesthetic by hand -- which is what the skill says to do outside Matplotlib.

Geometry and palette are lifted verbatim from PyLV200's
``docs/figures/code-*.svg``, so a figure made here sits beside those without a
seam.

    import sys; sys.path.insert(0, str(Path.home() / ".claude/skills/plot-that/scripts"))
    from code_figure import Panel, save

    save([Panel(label="THE WHOLE CALL", code=SNIPPET)],
         bundle / "code-run-pipeline.svg")

Two panels, each with its own accent stripe, is how "the same call, two
libraries" is shown:

    save([Panel("PYLV200 - ONE CALL", a, accent="gold"),
          Panel("PYINCUCYTE - THE SAME CALL", b, accent="blue")],
         bundle / "code-parity.svg")

From the command line, with a JSON spec:

    python code_figure.py spec.json --svg out.svg

Run it with no arguments to write a self-demonstrating sample to ./code-figure-sample.svg.
"""

from __future__ import annotations

import argparse
import builtins
import io
import json
import keyword
import math
import re
import shutil
import subprocess
import sys
import tokenize
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

__all__ = ["Panel", "render", "save", "line_table", "PALETTE", "ACCENTS",
           "GEOMETRY"]

# --------------------------------------------------------------------------
# Palette
# --------------------------------------------------------------------------

#: One colour per token class. Five is the whole vocabulary on purpose: a code
#: figure that colours nine kinds of token reads as an editor screenshot, and
#: the point of drawing it as a figure is that it does not.
PALETTE = {
    "plain":       "#151A21",   # names, and anything unclassified
    "punctuation": "#4C5769",   # operators, brackets, commas, the gaps between
    "keyword":     "#2C6597",   # language keywords and builtins
    "literal":     "#8A5E12",   # strings and numbers
    "comment":     "#79849A",   # comments, rendered italic
}

#: The stripe down the left edge of a panel. Its job is to tell two panels
#: apart at a glance, so the names are the distinction and not the hue.
ACCENTS = {
    "gold": "#8A5E12",
    "blue": "#2C6597",
    "teal": "#0e8f8f",
    "red":  "#c0392b",
    "grey": "#4C5769",
}

CANVAS_FILL = "#FFFFFF"
PANEL_FILL = "#EAEEF4"
PANEL_STROKE = "#D8DEE7"
EYEBROW_FILL = PALETTE["comment"]

#: First in the stack is the one the geometry below was measured against.
#: Consolas is the Windows fallback that is always present; its advance is
#: narrower, so a line that fits IBM Plex Mono fits Consolas too.
MONO_STACK = "'IBM Plex Mono',Consolas,Menlo,monospace"
SANS_STACK = "Segoe UI, sans-serif"

# --------------------------------------------------------------------------
# Geometry -- every number here is measured from PyLV200's code-*.svg
# --------------------------------------------------------------------------

GEOMETRY = {
    "margin": 12.0,          # canvas edge -> panel edge, left and right
    "first_top": 26.0,       # canvas top -> first panel top
    "bottom_margin": 16.0,   # last panel bottom -> canvas bottom
    "panel_gap": 36.0,       # panel bottom -> next panel top
    "eyebrow_rise": 8.0,     # panel top -> eyebrow baseline, upwards
    "eyebrow_size": 9.5,
    "eyebrow_tracking": 1.1,
    "pad_x": 14.0,           # panel left edge -> first glyph
    "pad_top": 17.0,         # panel top -> first baseline
    "pad_bottom": 21.5,      # last baseline -> panel bottom
    "line_height": 16.5,
    "font_size": 11.5,
    "accent_width": 3.0,
    "corner": 4.0,
    "min_width": 520.0,      # the width every PyLV200 code figure uses
    "advance": 0.6,          # mono advance as a fraction of the em
}


@dataclass
class Panel:
    """One rounded block of code with a label above it.

    ``label`` is the eyebrow: small, letter-spaced, upper case, and the one
    line a reader takes away if they read nothing else. It is set in the figure
    exactly as given, so capitalise it yourself.

    ``accent`` is a key of :data:`ACCENTS` or a literal ``#rrggbb``.

    ``lang`` chooses the lexer: ``python`` tokenises properly, ``shell`` and
    ``text`` use a light regex pass, ``none`` colours nothing.

    ``align_comments`` pushes every trailing comment in the panel out to one
    column, which is the difference between a figure that reads as a column of
    annotations and one that reads as ragged noise. ``True`` picks the column
    from the longest annotated line; an int names it outright. Counting the
    padding by hand instead is how a snippet acquires a one-character
    misalignment that nobody sees until it is printed.
    """

    label: str
    code: str
    accent: str = "gold"
    lang: str = "python"
    align_comments: bool | int = False

    def accent_hex(self) -> str:
        return ACCENTS.get(self.accent, self.accent)

    def lines(self) -> list[str]:
        # Trailing blank lines would draw an empty row and pad the panel;
        # leading ones would push the first line off the measured baseline.
        return self.code.replace("\t", "    ").strip("\n").split("\n")


# --------------------------------------------------------------------------
# Lexing -- text in, (text, token-class) spans out
# --------------------------------------------------------------------------

_BUILTINS = frozenset(dir(builtins))


def _classify_name(text: str) -> str:
    if keyword.iskeyword(text) or keyword.issoftkeyword(text):
        return "keyword"
    if text in _BUILTINS:
        return "keyword"
    return "plain"


def _python_rows(lines: Sequence[str]) -> list[list[tuple[str, str]]] | None:
    """Tokenise with the stdlib, or return None if the snippet is a fragment.

    Using ``tokenize`` rather than a regex is what makes a triple-quoted string
    that spans four lines come out as one literal instead of four broken ones,
    and what keeps ``class`` inside a string from turning blue. It only works on
    a parseable snippet, hence the fallback.
    """
    source = "\n".join(lines) + "\n"
    try:
        tokens = list(tokenize.generate_tokens(io.StringIO(source).readline))
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return None

    rows: list[list[tuple[str, str]]] = [[] for _ in lines]
    # Where the last token on each line ended, so the gap before the next one
    # can be emitted as spacing rather than dropped. The SVG carries
    # xml:space="preserve", so this is what indentation is made of.
    cursor = [0] * len(lines)

    for token in tokens:
        kind = token.type
        if kind == tokenize.COMMENT:
            cls = "comment"
        elif kind in (tokenize.STRING, tokenize.NUMBER):
            cls = "literal"
        elif kind == tokenize.NAME:
            cls = _classify_name(token.string)
        elif kind == tokenize.OP:
            cls = "punctuation"
        elif getattr(tokenize, "FSTRING_START", None) is not None and kind in (
                tokenize.FSTRING_START, tokenize.FSTRING_MIDDLE,
                tokenize.FSTRING_END):
            cls = "literal"
        else:
            # NEWLINE, NL, INDENT, DEDENT, ENDMARKER: carry no ink. The gap
            # filling below reproduces whatever whitespace they stood for.
            continue

        (srow, scol), (erow, ecol) = token.start, token.end
        pieces = token.string.split("\n")
        for offset, piece in enumerate(pieces):
            row = srow - 1 + offset
            if row >= len(lines):
                break
            start = scol if offset == 0 else 0
            if start > cursor[row]:
                rows[row].append((lines[row][cursor[row]:start], "punctuation"))
            if piece:
                rows[row].append((piece, cls))
            cursor[row] = start + len(piece)

    # Anything after the last token on a line -- trailing spaces, mostly, which
    # matter when a comment is aligned by hand.
    for index, line in enumerate(lines):
        if cursor[index] < len(line):
            rows[index].append((line[cursor[index]:], "punctuation"))
    return rows


_SHELL = re.compile(
    r"(?P<comment>\#.*$)"
    r"|(?P<literal>\"[^\"]*\"|'[^']*'|\b\d+(?:\.\d+)?\b)"
    r"|(?P<keyword>\b(?:if|then|else|fi|for|do|done|while|case|esac|function|"
    r"export|source|return|cd|echo|set|local)\b)"
    r"|(?P<plain>[A-Za-z_][\w.-]*)")


def _regex_rows(lines: Sequence[str], pattern: re.Pattern) -> list[list[tuple[str, str]]]:
    """The fallback: one pass per line, everything unmatched is punctuation."""
    rows = []
    for line in lines:
        spans: list[tuple[str, str]] = []
        at = 0
        for match in pattern.finditer(line):
            if match.start() > at:
                spans.append((line[at:match.start()], "punctuation"))
            cls = match.lastgroup or "plain"
            text = match.group()
            if cls == "plain" and pattern is _PYTHON_FALLBACK:
                cls = _classify_name(text)
            spans.append((text, cls))
            at = match.end()
        if at < len(line):
            spans.append((line[at:], "punctuation"))
        rows.append(spans)
    return rows


_PYTHON_FALLBACK = re.compile(
    r"(?P<comment>\#.*$)"
    r"|(?P<literal>[frbFRB]{0,2}\"\"\".*?\"\"\"|[frbFRB]{0,2}'''.*?'''"
    r"|[frbFRB]{0,2}\"(?:\\.|[^\"\\])*\"|[frbFRB]{0,2}'(?:\\.|[^'\\])*'"
    r"|\b\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?\b)"
    r"|(?P<plain>[A-Za-z_]\w*)")


def _lex(lines: Sequence[str], lang: str) -> list[list[tuple[str, str]]]:
    lang = (lang or "text").lower()
    if lang in ("python", "py"):
        rows = _python_rows(lines)
        if rows is None:
            rows = _regex_rows(lines, _PYTHON_FALLBACK)
        return [_merge(row) for row in rows]
    if lang in ("shell", "bash", "sh", "console"):
        return [_merge(row) for row in _regex_rows(lines, _SHELL)]
    return [[(line, "plain")] if line else [] for line in lines]


def _aligned(lines: Sequence[str], rows: Sequence[list[tuple[str, str]]],
             column: bool | int) -> list[str] | None:
    """Re-pad trailing comments onto one column, using the lexer's answer for
    where each comment starts -- so a ``#`` inside a string is never mistaken
    for one.

    A blank line ends a group, so each block of code gets its own column. One
    column for the whole panel would drag a short closing line out to meet a
    long argument list twenty lines above it, widening the canvas for a
    relationship the reader was never asked to see.

    Returns None when no line carries a trailing comment, so the caller can
    skip the second lexing pass.
    """
    groups: list[dict[int, int]] = [{}]
    for index, row in enumerate(rows):
        if not row:
            if groups[-1]:
                groups.append({})
            continue
        if row[-1][1] != "comment":
            continue
        code = "".join(text for text, _ in row[:-1])
        if not code.strip():
            continue  # a whole-line comment aligns with the code, not with these
        groups[-1][index] = len(code.rstrip())
    groups = [group for group in groups if group]
    if not groups:
        return None

    out = list(lines)
    for group in groups:
        target = int(column) if column is not True else max(group.values()) + 2
        for index, width in group.items():
            row = rows[index]
            code = "".join(text for text, _ in row[:-1]).rstrip()
            # At least one space, even when a line is longer than the target;
            # the alternative is a comment welded to a comma.
            out[index] = code + " " * max(target - width, 1) + row[-1][0]
    return out


def _rows_for(panel: Panel) -> list[list[tuple[str, str]]]:
    lines = panel.lines()
    rows = _lex(lines, panel.lang)
    if panel.align_comments:
        repadded = _aligned(lines, rows, panel.align_comments)
        if repadded is not None:
            rows = _lex(repadded, panel.lang)
    return rows


def _merge(spans: Iterable[tuple[str, str]]) -> list[tuple[str, str]]:
    """Fold neighbours of the same class into one span, so the SVG has one
    ``<tspan>`` per run of colour rather than one per token."""
    out: list[tuple[str, str]] = []
    for text, cls in spans:
        if not text:
            continue
        if out and out[-1][1] == cls:
            out[-1] = (out[-1][0] + text, cls)
        else:
            out.append((text, cls))
    return out


# --------------------------------------------------------------------------
# Layout and rendering
# --------------------------------------------------------------------------

def _escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _layout(panels: Sequence[Panel], geometry: dict) -> dict:
    """Where every panel and baseline goes, before a single glyph is written."""
    blocks = []
    top = geometry["first_top"]
    longest_code = 0
    longest_label = 0
    for panel in panels:
        rows = _rows_for(panel)
        count = max(len(rows), 1)
        height = (geometry["pad_top"] + (count - 1) * geometry["line_height"]
                  + geometry["pad_bottom"])
        blocks.append({"panel": panel, "rows": rows, "top": top,
                       "height": height})
        longest_code = max(longest_code,
                           max((sum(len(t) for t, _ in row) for row in rows),
                               default=0))
        longest_label = max(longest_label, len(panel.label))
        top += height + geometry["panel_gap"]

    # Two things can overflow the canvas: a long line of code, and a long
    # eyebrow. The eyebrow is smaller but letter-spaced, so it is measured
    # separately rather than assumed to be the shorter of the two.
    code_width = (2 * geometry["margin"] + 2 * geometry["pad_x"]
                  + longest_code * geometry["font_size"] * geometry["advance"])
    label_width = (2 * geometry["margin"] + 2
                   + longest_label * (geometry["eyebrow_size"] * geometry["advance"]
                                      + geometry["eyebrow_tracking"]))
    width = max(geometry["min_width"], math.ceil(max(code_width, label_width)))
    last = blocks[-1]
    height = last["top"] + last["height"] + geometry["bottom_margin"]
    return {"blocks": blocks, "width": float(width), "height": float(height),
            "longest_line": longest_code}


def render(panels: Sequence[Panel], *, geometry: dict | None = None) -> str:
    """The whole SVG as a string. Nothing is written to disk."""
    if not panels:
        raise ValueError("a code figure needs at least one panel")
    g = dict(GEOMETRY, **(geometry or {}))
    plan = _layout(panels, g)
    w, h = plan["width"], plan["height"]
    panel_w = w - 2 * g["margin"]

    def num(value: float) -> str:
        # Integers without a trailing ".0", so the output matches the
        # hand-written references character for character where it can.
        return f"{value:g}"

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{num(w)}" '
        f'height="{num(h)}" viewBox="0 0 {num(w)} {num(h)}" '
        f'font-family="{SANS_STACK}">',
        f'<rect width="{num(w)}" height="{num(h)}" fill="{CANVAS_FILL}"/>',
    ]

    for block in plan["blocks"]:
        panel, top, height = block["panel"], block["top"], block["height"]
        parts.append(
            f'<text x="{num(g["margin"] + 2)}" '
            f'y="{num(top - g["eyebrow_rise"])}" font-family="{MONO_STACK}" '
            f'font-size="{num(g["eyebrow_size"])}" fill="{EYEBROW_FILL}" '
            f'letter-spacing="{num(g["eyebrow_tracking"])}">'
            f'{_escape(panel.label)}</text>')
        parts.append(
            f'<rect x="{num(g["margin"])}" y="{num(top)}" '
            f'width="{num(panel_w)}" height="{num(height)}" '
            f'rx="{num(g["corner"])}" fill="{PANEL_FILL}" '
            f'stroke="{PANEL_STROKE}"/>')
        parts.append(
            f'<rect x="{num(g["margin"])}" y="{num(top)}" '
            f'width="{num(g["accent_width"])}" height="{num(height)}" '
            f'fill="{panel.accent_hex()}"/>')

        x = g["margin"] + g["pad_x"]
        for index, row in enumerate(block["rows"]):
            if not row:
                continue  # a blank line is a skipped baseline, not an empty tag
            y = top + g["pad_top"] + index * g["line_height"]
            spans = "".join(
                f'<tspan fill="{PALETTE[cls]}"'
                + (' font-style="italic"' if cls == "comment" else "")
                + f'>{_escape(text)}</tspan>'
                for text, cls in row)
            parts.append(
                f'<text x="{num(x)}" y="{num(y)}" font-family="{MONO_STACK}" '
                f'font-size="{num(g["font_size"])}" '
                f'xml:space="preserve">{spans}</text>')

    parts.append("</svg>")
    return "".join(parts)


def line_table(panels: Sequence[Panel], *, geometry: dict | None = None) -> list[dict]:
    """One row per rendered line -- the figure's exact plotted data.

    A code figure's "data" is the text and where each line landed, so this is
    what goes in ``figure_data.csv``. It makes the figure checkable
    without re-rendering it: the table says line 7 of panel 1 reads
    ``    workers=8,`` and sits at y=141.5, and the SVG either agrees or does
    not.
    """
    g = dict(GEOMETRY, **(geometry or {}))
    plan = _layout(panels, g)
    rows = []
    for panel_index, block in enumerate(plan["blocks"], start=1):
        panel = block["panel"]
        for index, row in enumerate(block["rows"]):
            if not row:
                # A blank line advances the baseline and draws nothing, so it
                # gets no row -- the table would otherwise claim a text element
                # at a y the SVG has no element at, and the whole reason for
                # the table is that it can be checked against the SVG. The
                # ``line`` numbers stay one-based on the source, so the gap is
                # visible as a missing number rather than hidden.
                continue
            text = "".join(t for t, _ in row)
            rows.append({
                "panel": panel_index,
                "panel_label": panel.label,
                "accent": panel.accent_hex(),
                "line": index + 1,
                "x": round(g["margin"] + g["pad_x"], 2),
                "y": round(block["top"] + g["pad_top"] + index * g["line_height"], 2),
                "characters": len(text),
                "n_spans": len(row),
                "classes": "|".join(dict.fromkeys(cls for _, cls in row)),
                "text": text,
            })
    return rows


# --------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------

_INKSCAPE_HINTS = (
    "inkscape",
    r"C:\Program Files\Inkscape\bin\inkscape.exe",
    r"C:\Program Files (x86)\Inkscape\bin\inkscape.exe",
    "/Applications/Inkscape.app/Contents/MacOS/inkscape",
)


def _rasterise(svg_path: Path, png_path: Path, width: float, scale: float) -> str:
    """PNG companion, by whichever rasteriser this machine has.

    Ordered by fidelity to the SVG's text: Inkscape and rsvg honour the font
    stack and the letter-spacing; cairosvg is the last resort because on
    Windows it usually cannot find libcairo at all.
    """
    target = int(round(width * scale))
    for hint in _INKSCAPE_HINTS:
        exe = shutil.which(hint) or (hint if Path(hint).exists() else None)
        if exe:
            subprocess.run([exe, str(svg_path), "--export-type=png",
                            f"--export-filename={png_path}",
                            f"--export-width={target}"],
                           check=True, capture_output=True)
            return f"inkscape --export-width={target}"
    exe = shutil.which("rsvg-convert")
    if exe:
        subprocess.run([exe, "-w", str(target), "-o", str(png_path),
                        str(svg_path)], check=True, capture_output=True)
        return f"rsvg-convert -w {target}"
    try:
        import cairosvg  # noqa: PLC0415  -- optional, and often broken on Windows
        cairosvg.svg2png(url=str(svg_path), write_to=str(png_path),
                         output_width=target)
        return f"cairosvg output_width={target}"
    except Exception as exc:  # pragma: no cover - depends on the machine
        raise RuntimeError(
            "no SVG rasteriser found: install Inkscape or librsvg, or call "
            f"save(..., png=False). Last error: {exc}") from exc


def save(panels: Sequence[Panel], svg_path, *, png: bool | str = True,
         png_scale: float = 2.0, geometry: dict | None = None) -> dict:
    """Write the SVG, and by default a 2x PNG beside it.

    2x is what PyLV200's ``code-*.png`` are, and it is the smallest scale at
    which 11.5px code stays legible when a document shrinks the image to fit a
    column. Returns the geometry and the rasteriser command, both of which
    belong in the bundle README.
    """
    svg_path = Path(svg_path)
    svg_path.parent.mkdir(parents=True, exist_ok=True)
    svg = render(panels, geometry=geometry)
    svg_path.write_text(svg, encoding="utf-8")

    g = dict(GEOMETRY, **(geometry or {}))
    plan = _layout(panels, g)
    result = {"svg": str(svg_path), "width": plan["width"],
              "height": plan["height"], "panels": len(panels),
              "lines": sum(len(b["rows"]) for b in plan["blocks"]),
              "longest_line": plan["longest_line"], "png": None,
              "rasteriser": None}

    if png:
        png_path = Path(png) if isinstance(png, str) else svg_path.with_suffix(".png")
        result["rasteriser"] = _rasterise(svg_path, png_path, plan["width"],
                                          png_scale)
        result["png"] = str(png_path)
    return result


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

SAMPLE = '''\
plan = lv200.plan("SCN-Cry1-dLuc-03", start_from="-48h")
print(plan.summary())
# SCN-Cry1-dLuc-03: 11 positions, 4 of 111 timepoints, 38.4 MB

if input("go? ") == "y":
    result = lv200.download(plan)   # the very plan shown
'''


def _panels_from_spec(spec: dict, base: Path) -> list[Panel]:
    panels = []
    for entry in spec.get("panels", []):
        code = entry.get("code")
        if code is None:
            source = base / entry["code_file"]
            code = source.read_text(encoding="utf-8")
            lines = entry.get("lines")
            if lines:  # "12-40", one-based and inclusive, the way an editor counts
                first, last = (int(part) for part in str(lines).split("-"))
                code = "\n".join(code.split("\n")[first - 1:last])
        panels.append(Panel(label=entry["label"], code=code,
                            accent=entry.get("accent", "gold"),
                            lang=entry.get("lang", "python"),
                            align_comments=entry.get("align_comments", False)))
    return panels


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Render source code as an SVG figure panel.")
    parser.add_argument("spec", nargs="?",
                        help="JSON: {\"panels\": [{\"label\", \"code\"|"
                             "\"code_file\", \"lines\", \"accent\", \"lang\"}]}")
    parser.add_argument("--svg", default="code-figure-sample.svg",
                        help="where the master goes")
    parser.add_argument("--png", default=None,
                        help="PNG companion; defaults to the SVG's name")
    parser.add_argument("--no-png", action="store_true")
    parser.add_argument("--table", default=None,
                        help="write the per-line table here as CSV")
    args = parser.parse_args(argv)

    if args.spec:
        spec_path = Path(args.spec)
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
        panels = _panels_from_spec(spec, spec_path.parent)
        geometry = spec.get("geometry")
    else:
        panels = [Panel(label="SHOW IT BEFORE YOU COMMIT TO IT", code=SAMPLE)]
        geometry = None

    result = save(panels, args.svg,
                  png=False if args.no_png else (args.png or True),
                  geometry=geometry)

    if args.table:
        import csv  # noqa: PLC0415  -- only the CLI writes a table
        rows = line_table(panels, geometry=geometry)
        with open(args.table, "w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        result["table"] = args.table

    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
