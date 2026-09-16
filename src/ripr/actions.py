"""Stable, JSON-friendly capabilities for AI and automation clients.

The normal Python API remains the source of truth.  This module adds a small
registry of workflow-level actions without exposing large pixel arrays over a
JSON boundary.  Each action returns metadata and diagnostics; generated
scripts show the equivalent direct Python call.
"""

from __future__ import annotations

from dataclasses import dataclass, fields, is_dataclass
from enum import Enum
import math
import os
from pathlib import Path
from typing import Any, Callable, Mapping
import warnings

import numpy as np

from .batch import discover as discover_tiff_files
from .batch import register_batch as _register_batch
from .diagnostics import rank_channels as _rank_channels
from .io import read_tiff
from .parameters import LogRatioParameters, recommendation
from .registration import BackendFallbackWarning, estimate as _estimate, register as _register
from .types import ImageType, MotionType, Recipe


class ActionError(ValueError):
    """An invalid capability request, reported as a structured runner error."""


@dataclass(frozen=True)
class ActionSpec:
    """Description and invocation metadata for one public capability."""

    name: str
    description: str
    handler: Callable[..., Any]
    read_only: bool = True
    mutates: bool = False
    destructive: bool = False
    coercion: Mapping[str, str] | None = None
    covers: tuple[str, ...] = ()

    def coerce(self, values: Mapping[str, Any]) -> dict[str, Any]:
        converters = self.coercion or {}
        unknown = sorted(set(values) - set(converters))
        if unknown:
            raise ActionError(
                f"unknown argument(s) for {self.name}: {', '.join(unknown)}"
            )
        return {key: _coerce(converters[key], value) for key, value in values.items()}


def _bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, np.integer)) and value in (0, 1):
        return bool(value)
    if isinstance(value, str):
        wanted = value.strip().lower()
        if wanted in {"true", "yes", "on", "1"}:
            return True
        if wanted in {"false", "no", "off", "0"}:
            return False
    raise ActionError(f"expected a boolean, got {value!r}")


def _path(value: Any) -> Path:
    if not isinstance(value, (str, Path)):
        raise ActionError(f"expected a filesystem path, got {type(value).__name__}")
    return Path(value)


def _image(value: Any) -> Any:
    if isinstance(value, (str, Path)):
        return Path(value)
    if isinstance(value, (list, tuple)):
        return np.asarray(value)
    if isinstance(value, np.ndarray):
        return value
    raise ActionError("input must be a TIFF path or a JSON array")


def _int(value: Any) -> int:
    if isinstance(value, bool):
        raise ActionError(f"expected an integer, got {value!r}")
    try:
        result = int(value)
    except (TypeError, ValueError) as error:
        raise ActionError(f"expected an integer, got {value!r}") from error
    if isinstance(value, float) and result != value:
        raise ActionError(f"expected an integer, got {value!r}")
    return result


def _float(value: Any) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as error:
        raise ActionError(f"expected a number, got {value!r}") from error
    if not math.isfinite(result):
        raise ActionError(f"expected a finite number, got {value!r}")
    return result


def _str(value: Any) -> str:
    if not isinstance(value, str):
        raise ActionError(f"expected text, got {type(value).__name__}")
    return value


def _enum(enum_type: type[Enum]) -> Callable[[Any], Any]:
    return lambda value: enum_type.parse(value)  # type: ignore[attr-defined]


def _tuple_int(value: Any) -> tuple[int, ...]:
    if isinstance(value, str):
        value = [part.strip() for part in value.split(",") if part.strip()]
    if not isinstance(value, (list, tuple)):
        raise ActionError("expected a list of integers")
    return tuple(_int(item) for item in value)


def _identity(value: Any) -> Any:
    return value


def _coerce(kind: str, value: Any) -> Any:
    converters: dict[str, Callable[[Any], Any]] = {
        "bool": _bool,
        "float": _float,
        "image": _image,
        "int": _int,
        "path": _path,
        "str": _str,
        "tuple_int": _tuple_int,
        "identity": _identity,
        "recipe": _enum(Recipe),
        "image_type": _enum(ImageType),
        "motion_type": _enum(MotionType),
    }
    if kind == "nan_float":
        return math.nan if value is None else _float(value)
    if kind.startswith("optional_"):
        return None if value is None else _coerce(kind[len("optional_"):], value)
    try:
        return converters[kind](value)
    except KeyError as error:  # registry authoring error, not a user error
        raise RuntimeError(f"unknown action coercion {kind!r}") from error


_ADVANCED_COERCION: dict[str, str] = {
    "image_type": "image_type", "motion_type": "motion_type", "recipe_provenance": "str",
    "estimation_scale": "float", "preprocessing": "identity",
    "pixel_selection_strategy": "identity", "pixel_selection_preprocessing": "identity",
    "pixel_removal_percent": "float", "slice": "int", "reference": "identity",
    "reference_frame": "int", "lags": "tuple_int", "template_window": "int",
    "norm": "identity", "estimator": "identity", "pixel_support": "identity",
    "gradient_fraction": "float", "epsilon": "float", "floor_percentile": "nan_float",
    "ceiling_percentile": "nan_float", "remove_offset": "bool", "offset_percentile": "float",
    "auto_max_shift": "bool", "max_shift": "float", "rotation_mode": "optional_identity",
    "rotation_event_frames": "tuple_int", "rotation_event_window": "int",
    "fit_rotation": "optional_bool", "max_rotation_degrees": "float", "outlier_mads": "float",
    "outlier_protection_residual_gain": "nan_float", "max_iterations": "int",
    "max_samples": "int", "min_valid_fraction": "float", "threads": "int",
    "interpolation": "identity", "crop": "bool", "selection_mode": "identity",
}


def _keys(*extra: tuple[str, str]) -> dict[str, str]:
    output = dict(_ADVANCED_COERCION)
    output.update(extra)
    return output


def _load_input(values: dict[str, Any]) -> tuple[Any, str | None]:
    names = [name for name in ("input", "path", "image") if name in values]
    if len(names) != 1:
        raise ActionError("provide exactly one of 'input', 'path', or 'image'")
    value = values.pop(names[0])
    if isinstance(value, Path):
        image, axes = read_tiff(value)
        return image, axes
    return np.asarray(value), values.pop("axes", None)


def _parameter_bundle(values: dict[str, Any]) -> LogRatioParameters | None:
    raw = values.pop("parameters", None)
    if raw is None:
        return None
    if isinstance(raw, LogRatioParameters):
        return raw
    if not isinstance(raw, Mapping):
        raise ActionError("parameters must be an object")
    allowed = {field.name for field in fields(LogRatioParameters)}
    unknown = sorted(set(raw) - allowed)
    if unknown:
        raise ActionError(f"unknown parameter field(s): {', '.join(unknown)}")
    values = dict(raw)
    for name in ("floor_percentile", "ceiling_percentile", "outlier_protection_residual_gain"):
        if values.get(name) is None:
            values[name] = math.nan
    return LogRatioParameters(**values)


def _simple(values: dict[str, Any]) -> dict[str, Any]:
    values.setdefault("recipe", Recipe.LANDMARKS)
    values.setdefault("channel", 1)
    values.setdefault("longitudinal", True)
    return values


def _summary_value(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, np.ndarray):
        finite = value[np.isfinite(value)] if np.issubdtype(value.dtype, np.number) else np.asarray([])
        summary: dict[str, Any] = {"shape": list(value.shape), "dtype": str(value.dtype)}
        if finite.size:
            summary.update({"min": float(np.min(finite)), "max": float(np.max(finite)), "mean": float(np.mean(finite))})
        return summary
    if is_dataclass(value):
        return {field.name: _summary_value(getattr(value, field.name)) for field in fields(value)}
    if isinstance(value, Mapping):
        return {str(key): _summary_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_summary_value(item) for item in value]
    if isinstance(value, np.generic):
        return _summary_value(value.item())
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def serialize(value: Any) -> Any:
    """Return a JSON-clean representation, replacing non-finite numbers with null."""
    return _summary_value(value)


def _transform_summary(result: Any) -> list[dict[str, Any]]:
    transforms = getattr(result, "transforms", getattr(result, "cumulative", ()))
    return [
        {"frame": index + 1, "dx": float(item.dx), "dy": float(item.dy), "theta": float(item.theta)}
        for index, item in enumerate(transforms)
    ]


def _registration_summary(result: Any, *, output_path: Path | None = None) -> dict[str, Any]:
    registration = result.registration if hasattr(result, "registration") else result
    output: dict[str, Any] = {
        "axes": getattr(result, "axes", None),
        "input_shape": list(getattr(result, "input_shape", ())),
        "transforms": _transform_summary(result) if hasattr(result, "transforms") else _transform_summary(registration),
        "median_residual_before": float(registration.median_residual_before),
        "median_residual_after": float(registration.median_residual_after),
        "warnings": list(registration.warnings),
        "levels": registration.levels,
        "workers": registration.workers,
        "status": [item.value if isinstance(item, Enum) else item for item in registration.status],
    }
    if output_path is not None:
        output["output_path"] = str(output_path)
    if hasattr(result, "parameters"):
        output["parameters"] = serialize(result.parameters)
        output["recipe"] = result.parameters.recipe_provenance
    if getattr(result, "automatic_selection", None) is not None:
        output["automatic_selection"] = serialize(result.automatic_selection)
    if getattr(result, "longitudinal_diagnostics", None) is not None:
        output["longitudinal_diagnostics"] = serialize(result.longitudinal_diagnostics)
    return serialize(output)


def _inspect_tiff(**raw: Any) -> dict[str, Any]:
    path = raw["path"]
    image, axes = read_tiff(path)
    return {"path": str(path), "shape": list(image.shape), "axes": axes, "dtype": str(image.dtype)}


def _discover_tiffs(**raw: Any) -> dict[str, Any]:
    paths = discover_tiff_files(raw["input_directory"], raw.get("recursive", False))
    return {"input_directory": str(raw["input_directory"]), "recursive": raw.get("recursive", False), "paths": [str(path) for path in paths], "count": len(paths)}


def _rank_channels_action(**raw: Any) -> dict[str, Any]:
    values = dict(raw)
    image, axes = _load_input(values)
    qualities = _rank_channels(image, axes=values.pop("axes", axes))
    return {"axes": axes, "channels": serialize(qualities)}


def _recommend_action(**raw: Any) -> dict[str, Any]:
    recipe = raw.pop("recipe", None)
    if recipe is not None:
        params = LogRatioParameters.for_recipe(recipe, channel=raw.pop("channel", 1), longitudinal=raw.pop("longitudinal", True))
        return {"recipe": recipe.value, "parameters": serialize(params), "provenance": params.recipe_provenance}
    image_type = raw.pop("image_type", ImageType.PHASE_CONTRAST)
    motion_type = raw.pop("motion_type", MotionType.SUBPIXEL_RANDOM_WALK)
    recommendation_result = recommendation(image_type, motion_type)
    return {"image_type": image_type.value, "motion_type": motion_type.value, "recommendation": serialize(recommendation_result)}


def _estimate_action(**raw: Any) -> dict[str, Any]:
    values = dict(raw)
    image, inferred_axes = _load_input(values)
    axes = values.pop("axes", inferred_axes)
    parameters = _parameter_bundle(values)
    values = _simple(values)
    result = _estimate(image, parameters, axes=axes, **values)
    return _registration_summary(result)


def _register_action(**raw: Any) -> dict[str, Any]:
    values = dict(raw)
    source_keys = [name for name in ("input", "path", "image") if name in values]
    if len(source_keys) != 1:
        raise ActionError("provide exactly one of 'input', 'path', or 'image'")
    image = values.pop(source_keys[0])
    output_path = values.get("output_path")
    if isinstance(image, Path) and output_path is None:
        output_path = image.with_name(image.stem + "_registered.tif")
    if values.get("overwrite", False) and not values.get("confirm_overwrite", False):
        raise ActionError("overwrite requires confirm_overwrite=true")
    if isinstance(output_path, Path) and output_path.exists() and not values.get("confirm_overwrite", False):
        raise ActionError(f"output already exists; set confirm_overwrite=true: {output_path}")
    if isinstance(image, Path):
        # The TIFF wrapper reads and infers axes itself; passing axes through would duplicate
        # that keyword on the compatibility path.
        values.pop("axes", None)
    values.pop("confirm_overwrite", None)
    values.pop("overwrite", None)
    values.pop("output_path", None)
    parameters = _parameter_bundle(values)
    values = _simple(values)
    result = _register(image, parameters, output_path=output_path, **values)
    return _registration_summary(result, output_path=output_path if isinstance(output_path, Path) else None)


def _register_batch_action(**raw: Any) -> dict[str, Any]:
    if raw.get("overwrite", False) and not raw.get("confirm_overwrite", False):
        raise ActionError("overwrite requires confirm_overwrite=true")
    values = dict(raw)
    values.pop("confirm_overwrite", None)
    values.pop("axes", None)
    parameters = _parameter_bundle(values)
    values = _simple(values)
    result = _register_batch(parameters=parameters, **values)
    return {
        "report_path": str(result.report_path),
        "completed": result.completed,
        "skipped": result.skipped,
        "errors": result.errors,
        "items": serialize(result.items),
    }


_COMMON_ADVANCED = _keys(("axes", "optional_str"), ("backend", "optional_str"), ("recipe", "recipe"), ("channel", "int"), ("longitudinal", "bool"), ("parameters", "identity"))
ACTION_REGISTRY: dict[str, ActionSpec] = {
    "inspect_tiff": ActionSpec("inspect_tiff", "Read TIFF shape, axes, and dtype.", _inspect_tiff, coercion={"path": "path"}, covers=("read_tiff",)),
    "discover_tiffs": ActionSpec("discover_tiffs", "Find TIFF files in a folder.", _discover_tiffs, coercion={"input_directory": "path", "recursive": "bool"}, covers=("batch.discover",)),
    "rank_channels": ActionSpec("rank_channels", "Rank channels by localisability and frame correlation.", _rank_channels_action, coercion={"input": "image", "path": "image", "image": "image", "axes": "str"}, covers=("rank_channels",)),
    "recommend": ActionSpec("recommend", "Return a benchmark-backed recipe recommendation.", _recommend_action, coercion={"recipe": "recipe", "image_type": "image_type", "motion_type": "motion_type", "channel": "int", "longitudinal": "bool"}, covers=("recommendation",)),
    "estimate": ActionSpec("estimate", "Estimate movement and diagnostics without writing pixels.", _estimate_action, coercion={**_COMMON_ADVANCED, "input": "image", "path": "image", "image": "image"}, covers=("estimate",)),
    "register": ActionSpec("register", "Register one TIFF or array and optionally write corrected pixels.", _register_action, read_only=False, mutates=True, destructive=True, coercion={**_COMMON_ADVANCED, "input": "image", "path": "image", "image": "image", "output_path": "optional_path", "confirm_overwrite": "bool", "overwrite": "bool"}, covers=("register", "register_file")),
    "register_batch": ActionSpec("register_batch", "Register every TIFF in a folder with isolated failures.", _register_batch_action, read_only=False, mutates=True, destructive=True, coercion={**_COMMON_ADVANCED, "input_directory": "path", "output_directory": "path", "recursive": "bool", "overwrite": "bool", "confirm_overwrite": "bool"}, covers=("register_batch",)),
}


_BACKEND_SURFACE = ("read_tiff", "batch.discover", "rank_channels", "recommendation", "estimate", "register", "register_file", "register_batch")


def _resolve_symbol(name: str) -> Any:
    import ripr

    target: Any = ripr
    for part in name.split("."):
        target = getattr(target, part)
    return target


def discover() -> dict[str, Any]:
    """Describe the registry and verify the curated live backend surface."""
    missing = []
    for name in _BACKEND_SURFACE:
        try:
            target = _resolve_symbol(name)
        except AttributeError:
            missing.append(name)
            continue
        if not callable(target):
            missing.append(name)
    covered = sorted({name for spec in ACTION_REGISTRY.values() for name in spec.covers})
    registered = sorted(set(_BACKEND_SURFACE) & set(covered))
    unregistered = sorted(set(_BACKEND_SURFACE) - set(covered))
    return {
        "actions": describe(),
        "backend_surface": list(_BACKEND_SURFACE),
        "registered": registered,
        "unregistered": unregistered,
        "covered": covered,
        "missing": missing,
    }


def describe(name: str | None = None) -> Any:
    """Return action metadata, optionally for one named action."""
    specs = [ACTION_REGISTRY[name]] if name else list(ACTION_REGISTRY.values())
    if name and name not in ACTION_REGISTRY:
        raise ActionError(f"unknown action {name!r}; use discover to list actions")
    return [
        {
            "name": spec.name,
            "description": spec.description,
            "read_only": spec.read_only,
            "mutates": spec.mutates,
            "destructive": spec.destructive,
            "arguments": dict(spec.coercion or {}),
            "covers": list(spec.covers),
        }
        for spec in specs
    ] if name is None else {
        "name": specs[0].name,
        "description": specs[0].description,
        "read_only": specs[0].read_only,
        "mutates": specs[0].mutates,
        "destructive": specs[0].destructive,
        "arguments": dict(specs[0].coercion or {}),
        "covers": list(specs[0].covers),
    }


def _literal(value: Any) -> str:
    if isinstance(value, Path):
        return f"Path({str(value)!r})"
    if isinstance(value, Enum):
        return repr(value.value)
    if isinstance(value, float) and not math.isfinite(value):
        return "float('nan')" if math.isnan(value) else ("float('inf')" if value > 0 else "-float('inf')")
    if isinstance(value, np.generic):
        return _literal(value.item())
    if isinstance(value, np.ndarray):
        return repr(value.tolist())
    if isinstance(value, tuple):
        return repr(tuple(value))
    if isinstance(value, Mapping):
        return "{" + ", ".join(f"{_literal(str(key))}: {_literal(item)}" for key, item in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ", ".join(_literal(item) for item in value) + "]"
    return repr(value)


def _parameter_script_value(value: Any) -> Any:
    if isinstance(value, LogRatioParameters):
        return {field.name: getattr(value, field.name) for field in fields(value)}
    return value


def build_equivalent_script(name: str, args: Mapping[str, Any]) -> str:
    """Build a short direct-Python call equivalent to an action request."""
    if name not in ACTION_REGISTRY:
        raise ActionError(f"unknown action {name!r}")
    values = dict(args)
    lines = ["from pathlib import Path", "import ripr", ""]
    if name == "inspect_tiff":
        lines.append(f"image, axes = ripr.read_tiff({_literal(values['path'])})")
        lines.append("print({'shape': image.shape, 'axes': axes, 'dtype': str(image.dtype)})")
    elif name == "discover_tiffs":
        lines.append("from ripr.batch import discover")
        lines.append(f"print(discover({_literal(values['input_directory'])}, recursive={_literal(values.get('recursive', False))}))")
    elif name == "rank_channels":
        source = values.get("input", values.get("path", values.get("image")))
        if isinstance(source, Path):
            lines.append(f"image, axes = ripr.read_tiff({_literal(source)})")
        else:
            lines.append(f"image = {_literal(source)}")
            lines.append("axes = None")
        lines.append(f"print(ripr.rank_channels(image, axes={_literal(values.get('axes'))}))")
    elif name == "recommend":
        if "recipe" in values:
            lines.append(f"parameters = ripr.LogRatioParameters.for_recipe({_literal(values['recipe'])}, channel={_literal(values.get('channel', 1))}, longitudinal={_literal(values.get('longitudinal', True))})")
            lines.append("print(parameters)")
        else:
            lines.append(f"print(ripr.recommendation({_literal(values.get('image_type', ImageType.PHASE_CONTRAST))}, {_literal(values.get('motion_type', MotionType.SUBPIXEL_RANDOM_WALK))}))")
    elif name in {"estimate", "register"}:
        source = values.get("input", values.get("path", values.get("image")))
        call = "ripr.estimate" if name == "estimate" else "ripr.register"
        positional = _literal(source)
        kwargs = []
        if "parameters" in values:
            lines.append(f"parameters = ripr.LogRatioParameters(**{_literal(_parameter_script_value(values['parameters']))})")
        if name == "register" and isinstance(source, Path):
            target = values.get("output_path", source.with_name(source.stem + "_registered.tif"))
            lines.append(f"output_path = {_literal(target)}")
            confirmed = bool(values.get("confirm_overwrite", False))
            lines.append(f"if output_path.exists() and not {confirmed!r}: raise RuntimeError('output exists; set confirm_overwrite=true')")
        for key, value in values.items():
            if key in {"input", "path", "image", "confirm_overwrite", "overwrite"}:
                continue
            if name == "register" and isinstance(source, Path) and key == "axes":
                continue
            if name == "register" and key == "output_path" and isinstance(source, Path):
                kwargs.append("output_path=output_path")
                continue
            if key == "parameters":
                kwargs.append("parameters=parameters")
                continue
            kwargs.append(f"{key}={_literal(value)}")
        if name == "register" and isinstance(source, Path) and "output_path" not in values:
            kwargs.append("output_path=output_path")
        lines.append(f"result = {call}({positional}{', ' if kwargs else ''}{', '.join(kwargs)})")
        lines.append("print(result)")
    elif name == "register_batch":
        kwargs = []
        if "parameters" in values:
            lines.append(f"parameters = ripr.LogRatioParameters(**{_literal(_parameter_script_value(values['parameters']))})")
        for key, value in values.items():
            if key in {"confirm_overwrite"}:
                continue
            if key == "parameters":
                kwargs.append("parameters=parameters")
                continue
            kwargs.append(f"{key}={_literal(value)}")
        lines.append(f"result = ripr.register_batch({', '.join(kwargs)})")
        lines.append("print(result.report_path)")
    return "\n".join(lines) + "\n"


def dispatch(name: str, args: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """Coerce and invoke one action, returning a JSON-clean response envelope."""
    try:
        if name not in ACTION_REGISTRY:
            raise ActionError(f"unknown action {name!r}; use discover to list actions")
        if args is not None and not isinstance(args, Mapping):
            raise ActionError("action args must be a JSON object")
        spec = ACTION_REGISTRY[name]
        supplied = dict(args or {})
        coerced = spec.coerce(supplied)
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            result = spec.handler(**coerced)
    except Exception as error:
        return {
            "ok": False,
            "action": name,
            "error": {"type": type(error).__name__, "message": str(error)},
        }
    warning_items = [
        {"category": warning.category.__name__, "message": str(warning.message)}
        for warning in caught
    ]
    payload = {"ok": True, "action": name, "result": serialize(result), "warnings": warning_items}
    if name in {"estimate", "register", "register_batch"}:
        fallback = any(item["category"] == BackendFallbackWarning.__name__ for item in warning_items)
        requested = coerced.get("backend") or os.environ.get("RIPR_BACKEND") or "java"
        payload["backend"] = "python" if fallback or requested == "python" else "java"
    payload["equivalent_script"] = build_equivalent_script(name, coerced)
    return serialize(payload)
