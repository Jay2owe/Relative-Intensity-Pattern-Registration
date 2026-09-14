"""Command-line interface for one stack or a folder batch."""

from __future__ import annotations

import argparse
from pathlib import Path

from .batch import register_batch
from .io import register_file
from .parameters import LogRatioParameters
from .types import ImageType, Interpolation, MotionType, RotationMode, SelectionMode


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ripr", description="Relative-Intensity Pattern Registration for microscopy time series")
    parser.add_argument("input", type=Path, help="input TIFF/OME-TIFF or folder")
    parser.add_argument("output", type=Path, nargs="?", help="output TIFF or folder")
    parser.add_argument("--image-type", default=ImageType.PHASE_CONTRAST.value, choices=[item.value for item in ImageType])
    parser.add_argument("--motion-type", default=MotionType.SUBPIXEL_RANDOM_WALK.value, choices=[item.value for item in MotionType])
    parser.add_argument("--selection-mode", default=SelectionMode.RECOMMENDED.value, choices=[item.value for item in SelectionMode])
    parser.add_argument("--channel", type=int, default=1, help="one-based estimation channel")
    parser.add_argument("--slice", type=int, default=0, help="one-based Z slice; 0 maximum-projects Z")
    parser.add_argument("--interpolation", default=Interpolation.NONE.value, choices=[item.value for item in Interpolation])
    parser.add_argument("--fit-rotation", action="store_true", default=None,
                        help="legacy alias for --rotation-mode continuous")
    parser.add_argument("--rotation-mode", choices=[item.value for item in RotationMode],
                        help="off, continuous per-pair search, or known remount events")
    parser.add_argument("--rotation-events", default="",
                        help="comma-separated one-based first frames after remounting")
    parser.add_argument("--rotation-event-window", type=int, default=3,
                        help="frames drawn from each side of each known event")
    parser.add_argument("--max-rotation-degrees", type=float, default=10.0,
                        help="maximum absolute rotation per compared frame, in degrees; reported theta values remain radians")
    parser.add_argument("--no-crop", action="store_true", help="keep filled registration borders")
    parser.add_argument("--recursive", action="store_true", help="include subfolders in a folder batch")
    parser.add_argument("--overwrite", action="store_true", help="replace existing registered stacks")
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
    parameters = LogRatioParameters(
        image_type=args.image_type,
        motion_type=args.motion_type,
        selection_mode=args.selection_mode,
        channel=args.channel,
        slice=args.slice,
        interpolation=args.interpolation,
        rotation_mode=args.rotation_mode,
        rotation_event_frames=rotation_events,
        rotation_event_window=args.rotation_event_window,
        fit_rotation=args.fit_rotation,
        max_rotation_degrees=args.max_rotation_degrees,
        crop=not args.no_crop,
    )
    if args.input.is_dir():
        output = args.output or args.input.with_name(args.input.name + "_registered")
        result = register_batch(args.input, output, parameters, recursive=args.recursive, overwrite=args.overwrite)
        print(f"completed={result.completed} skipped={result.skipped} errors={result.errors} report={result.report_path}")
        return 1 if result.errors else 0
    output = args.output or args.input.with_name(args.input.stem + "_registered.tif")
    result = register_file(args.input, output, parameters)
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
