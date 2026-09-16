"""Command-line interface for one stack or a folder batch."""

from __future__ import annotations

import argparse
from dataclasses import replace
from pathlib import Path

from .batch import register_batch
from .io import register_file
from .parameters import LogRatioParameters
from .types import ImageType, Interpolation, MotionType, Recipe, RotationMode, SelectionMode


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ripr",
        description=("Register a TIFF or folder. Only INPUT is required; OUTPUT and all settings "
                     "have safe defaults."),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    required = parser.add_argument_group("required input")
    required.add_argument("input", type=Path, help="input TIFF/OME-TIFF or folder")
    common = parser.add_argument_group("common options")
    common.add_argument("output", type=Path, nargs="?",
                        help="output TIFF or folder (derived from INPUT when omitted)")
    common.add_argument("--recipe", default=Recipe.LANDMARKS.value,
                        choices=[item.value for item in Recipe],
                        help="validated whole-recording recipe")
    common.add_argument("--channel", type=int, default=1, help="one-based estimation channel")
    common.add_argument(
        "--longitudinal", action=argparse.BooleanOptionalAction, default=True,
        help="use evidence from the complete recording",
    )
    common.add_argument(
        "--backend", choices=("java", "auto", "python"),
        help=("engine policy: omitted prefers Java and warns before Python fallback; "
              "java is strict; python is explicit"),
    )
    advanced = parser.add_argument_group("advanced options")
    advanced.add_argument("--image-type", choices=[item.value for item in ImageType],
                          help="override the recipe's image assumption")
    advanced.add_argument("--motion-type", choices=[item.value for item in MotionType],
                          help="override the recipe's motion assumption")
    advanced.add_argument("--selection-mode",
                          choices=[item.value for item in SelectionMode],
                          help="override the recipe's settings source")
    advanced.add_argument("--slice", type=int, default=0,
                          help="one-based Z slice; 0 maximum-projects Z")
    advanced.add_argument("--interpolation", default=Interpolation.NONE.value,
                          choices=[item.value for item in Interpolation],
                          help="pixel resampling used when applying transforms")
    advanced.add_argument("--fit-rotation", action="store_true", default=None,
                        help="legacy alias for --rotation-mode continuous")
    advanced.add_argument("--rotation-mode", choices=[item.value for item in RotationMode],
                        help="off, continuous per-pair search, or known remount events")
    advanced.add_argument("--rotation-events", default="",
                        help="comma-separated one-based first frames after remounting")
    advanced.add_argument("--rotation-event-window", type=int, default=3,
                        help="frames drawn from each side of each known event")
    advanced.add_argument("--max-rotation-degrees", type=float, default=10.0,
                        help="maximum absolute rotation per compared frame, in degrees; reported theta values remain radians")
    advanced.add_argument("--no-crop", action="store_true", help="keep filled registration borders")
    advanced.add_argument("--recursive", action="store_true", help="include subfolders in a folder batch")
    advanced.add_argument("--overwrite", action="store_true", help="replace existing registered stacks")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if args.rotation_mode is not None and args.fit_rotation is not None:
        parser.error("--fit-rotation contradicts --rotation-mode; keep only one control")
    try:
        rotation_events = tuple(
            int(value.strip()) for value in args.rotation_events.split(",") if value.strip()
        )
    except ValueError:
        parser.error("--rotation-events must be comma-separated integers")
    try:
        parameters = LogRatioParameters.for_recipe(
            args.recipe, channel=args.channel, longitudinal=args.longitudinal,
        )
    except ValueError as error:
        parser.error(str(error))
    overrides = dict(
        slice=args.slice,
        interpolation=args.interpolation,
        rotation_event_frames=rotation_events,
        rotation_event_window=args.rotation_event_window,
        max_rotation_degrees=args.max_rotation_degrees,
        crop=not args.no_crop,
    )
    for name in ("image_type", "motion_type", "selection_mode", "rotation_mode", "fit_rotation"):
        value = getattr(args, name)
        if value is not None:
            overrides[name] = value
    try:
        parameters = replace(parameters, **overrides)
    except ValueError as error:
        parser.error(str(error))
    if (parameters.selection_mode is SelectionMode.LONGITUDINAL_ACCURACY
            and parameters.rotation_mode is not RotationMode.OFF):
        parser.error(
            "longitudinal mode does not support continuous or known-event rotation; "
            "use --no-longitudinal"
        )
    if args.input.is_dir():
        output = args.output or args.input.with_name(args.input.name + "_registered")
        result = register_batch(
            args.input, output, parameters, recursive=args.recursive,
            overwrite=args.overwrite, backend=args.backend,
        )
        print(f"completed={result.completed} skipped={result.skipped} errors={result.errors} report={result.report_path}")
        return 1 if result.errors else 0
    output = args.output or args.input.with_name(args.input.stem + "_registered.tif")
    result = register_file(args.input, output, parameters, backend=args.backend)
    print(f"wrote {output}")
    print(f"median residual: {result.median_residual_before:.6g} -> {result.median_residual_after:.6g}")
    print(
        f"rotation mode: {result.parameters.rotation_mode.value}; "
        f"events={','.join(str(value) for value in result.parameters.rotation_event_frames)}; "
        f"window={result.parameters.rotation_event_window}"
    )
    if result.registration.event_rotations is not None:
        for event in result.registration.event_rotations.events:
            print(
                "event\t"
                f"{event.public_frame}\t{event.delta_theta:.10f}\t"
                f"{event.cumulative_theta:.10f}\t{event.candidate_pairs}\t"
                f"{event.usable_pairs}\t{event.inlier_pairs}\t"
                f"{event.circular_mad:.10f}\t{event.status.value}"
            )
    for index, transform in enumerate(result.transforms, 1):
        print(f"{index}\t{transform.dx:.8f}\t{transform.dy:.8f}\t{transform.theta:.10f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
