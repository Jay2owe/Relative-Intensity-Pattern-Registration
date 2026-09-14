"""Run the estimation step in the Java engine, from Python, at the Java engine's speed.

The pure-Python engine in :mod:`ripr.core` and the Java plugin produce the same transforms; this
module exists because they do not produce them at the same *rate*. Java aligns frame pairs across a
bounded thread pool, which is the one dominant parallel axis in the problem, and the Python engine
runs them one after another. On a sixteen-core machine that difference is most of an order of
magnitude before any per-operation cost is counted.

So: when a Java runtime and the plugin jar are both present, hand the estimation to them and keep
the cheap array work here. Only the transforms cross the process boundary — applying them is fast
and there is no reason to pay to move a whole registered stack back.

The backend is optional in every direction. :func:`available` reports whether it can run, and
``backend="auto"`` falls back to the Python engine rather than failing, so a machine with no Java
keeps working exactly as before.

Locating the pieces, in order:

* the runtime: ``RIPR_JAVA``, then ``JAVA_HOME/bin/java``, then ``java`` on ``PATH``
* the jars: ``RIPR_JAR`` (may be several, separated by the platform path separator), then a
  ``jars`` directory beside this package, then a Fiji installation named by ``RIPR_FIJI``
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .types import ImageType, MotionType, SelectionMode, Status, Transform

#: Element types the Java reader accepts, mapped from NumPy.
_DTYPES = {
    "uint8": "uint8",
    "uint16": "uint16",
    "int16": "int16",
    "float32": "float32",
}

_MAIN_CLASS = "ripr.api.HeadlessRunner"


class JavaBackendUnavailable(RuntimeError):
    """Raised when the Java fast path was asked for explicitly and cannot be assembled."""


@dataclass(frozen=True)
class JavaEnvironment:
    """A runnable Java engine: the launcher plus the classpath that contains the plugin."""

    java: Path
    classpath: tuple[Path, ...]

    def classpath_argument(self) -> str:
        return os.pathsep.join(str(entry) for entry in self.classpath)


def _candidate_javas() -> list[Path]:
    found: list[Path] = []
    explicit = os.environ.get("RIPR_JAVA")
    if explicit:
        found.append(Path(explicit))
    home = os.environ.get("JAVA_HOME")
    if home:
        found.append(Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    on_path = shutil.which("java")
    if on_path:
        found.append(Path(on_path))
    return found


def _candidate_classpaths() -> list[tuple[Path, ...]]:
    """Jar sets that might carry the plugin, most explicit first."""
    found: list[tuple[Path, ...]] = []
    explicit = os.environ.get("RIPR_JAR")
    if explicit:
        entries = tuple(Path(part) for part in explicit.split(os.pathsep) if part)
        if entries:
            found.append(entries)
    bundled = Path(__file__).resolve().parent / "jars"
    if bundled.is_dir():
        jars = tuple(sorted(bundled.glob("*.jar")))
        if jars:
            found.append(jars)
    fiji = os.environ.get("RIPR_FIJI")
    if fiji:
        plugins = Path(fiji) / "plugins"
        jars = tuple(sorted(plugins.glob("RelativeIntensityPatternRegistration*.jar")))
        core = tuple(sorted((Path(fiji) / "jars").glob("ij-*.jar")))
        if jars and core:
            found.append(jars + core)
    # A development tree: compiled classes plus the one runtime dependency Maven resolved.
    root = Path(__file__).resolve().parents[2]
    classes = root / "target" / "classes"
    dependencies = root / "target" / "test-classpath.txt"
    if classes.is_dir() and dependencies.is_file():
        entries = [classes]
        entries.extend(Path(part) for part in
                       dependencies.read_text(encoding="utf-8").strip().split(os.pathsep) if part)
        found.append(tuple(entries))
    return found


def environment() -> JavaEnvironment | None:
    """The first runnable Java engine, or ``None`` when none of the candidates works."""
    javas = [candidate for candidate in _candidate_javas() if candidate.exists()]
    if not javas:
        return None
    for classpath in _candidate_classpaths():
        if not all(entry.exists() for entry in classpath):
            continue
        for java in javas:
            if _responds(java, classpath):
                return JavaEnvironment(java, classpath)
    return None


def _responds(java: Path, classpath: tuple[Path, ...]) -> bool:
    """Does this launcher actually load the entry point? Presence on disk is not the same thing."""
    try:
        completed = subprocess.run(
            [str(java), "-Djava.awt.headless=true", "-cp",
             os.pathsep.join(str(entry) for entry in classpath), _MAIN_CLASS],
            capture_output=True, text=True, timeout=120,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    # With no arguments the runner prints its usage and exits 2. Anything else means the class was
    # not found, the runtime is broken, or the jar on the classpath is not this plugin.
    return completed.returncode == 2 and "dtype" in (completed.stderr or "")


def available() -> bool:
    """Whether the Java fast path can run here."""
    return environment() is not None


@dataclass(frozen=True)
class JavaRun:
    """What the Java engine returned: the chain, its per-frame diagnostics, and its own timing."""

    cumulative: tuple[Transform, ...]
    log2_gain: np.ndarray
    support: np.ndarray
    residual_before: np.ndarray
    residual_after: np.ndarray
    valid_fraction: np.ndarray
    status: tuple[Status | None, ...]
    warnings: tuple[str, ...]
    engine_seconds: float
    workers: int


def estimate(
    frames: np.ndarray,
    *,
    image_type: ImageType | str,
    motion_type: MotionType | str,
    selection_mode: SelectionMode | str = SelectionMode.RECOMMENDED,
    channel: int = 1,
    threads: int = 0,
    java: JavaEnvironment | None = None,
) -> JavaRun:
    """Estimate movement for ``frames`` (T, Y, X) using the Java engine.

    ``threads=0`` lets Java size its own pool, which is what makes this path fast; pass 1 to force
    the serial behaviour when comparing the two engines like for like.
    """
    engine = java or environment()
    if engine is None:
        raise JavaBackendUnavailable(
            "no Java runtime and plugin jar were found. Set RIPR_JAVA and RIPR_JAR, or install "
            "the package with its bundled jars, or use backend='python'."
        )
    stack = np.asarray(frames)
    if stack.ndim != 3:
        raise ValueError(f"expected a (frames, height, width) stack, got shape {stack.shape}")
    name = stack.dtype.name
    if name not in _DTYPES:
        # Anything else is promoted rather than refused: float32 is lossless for the integer types
        # a microscope produces and the engine works in floating point regardless.
        stack = stack.astype(np.float32)
        name = "float32"
    stack = np.ascontiguousarray(stack)

    image_type = ImageType.parse(image_type) if not isinstance(image_type, ImageType) else image_type
    motion_type = (MotionType.parse(motion_type)
                   if not isinstance(motion_type, MotionType) else motion_type)
    selection_mode = (SelectionMode.parse(selection_mode)
                      if not isinstance(selection_mode, SelectionMode) else selection_mode)

    workspace = Path(tempfile.mkdtemp(prefix="ripr_java_"))
    try:
        raw = workspace / "input.raw"
        # tofile writes the buffer straight out with no container, which is the whole point: a TIFF
        # encode of a large recording can cost more than the registration it is feeding.
        stack.tofile(raw)
        output = workspace / "transforms.csv"
        command = [
            str(engine.java), "-Djava.awt.headless=true", "-cp", engine.classpath_argument(),
            _MAIN_CLASS, str(raw),
            str(stack.shape[2]), str(stack.shape[1]), str(stack.shape[0]), name,
            image_type.name, motion_type.name, selection_mode.name,
            str(int(channel)), str(int(threads)), str(output),
        ]
        completed = subprocess.run(command, capture_output=True, text=True)
        if completed.returncode != 0:
            raise RuntimeError(
                f"the Java engine failed (exit {completed.returncode}).\n"
                f"{completed.stdout}\n{completed.stderr}".strip()
            )
        return _parse(output.read_text(encoding="utf-8"))
    finally:
        shutil.rmtree(workspace, ignore_errors=True)


def _parse(text: str) -> JavaRun:
    engine_seconds = float("nan")
    workers = 0
    warnings: list[str] = []
    rows: list[list[str]] = []
    header: list[str] | None = None
    for line in text.splitlines():
        if line.startswith("# elapsed_seconds,"):
            engine_seconds = float(line.split(",", 1)[1])
        elif line.startswith("# workers,"):
            workers = int(line.split(",", 1)[1])
        elif line.startswith("# warning,"):
            warnings.append(line.split(",", 1)[1])
        elif line.startswith("#"):
            continue
        elif header is None:
            header = line.split(",")
        elif line:
            rows.append(line.split(","))
    if header is None:
        raise RuntimeError("the Java engine produced no transform table")
    index = {name: position for position, name in enumerate(header)}

    def column(name: str) -> np.ndarray:
        return np.asarray([float(row[index[name]]) for row in rows], dtype=np.float64)

    transforms = tuple(
        Transform(float(row[index["dx"]]), float(row[index["dy"]]), float(row[index["theta"]]))
        for row in rows
    )
    status = tuple(
        Status[row[index["status"]]] if row[index["status"]] else None for row in rows
    )
    return JavaRun(
        cumulative=transforms,
        log2_gain=column("log2_gain"),
        support=np.asarray([int(row[index["support"]]) for row in rows], dtype=np.int64),
        residual_before=column("residual_before"),
        residual_after=column("residual_after"),
        valid_fraction=column("valid_fraction"),
        status=status,
        warnings=tuple(warnings),
        engine_seconds=engine_seconds,
        workers=workers,
    )
