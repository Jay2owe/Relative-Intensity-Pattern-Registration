"""Explicit accepted native recipes; existing Automatic and legacy longitudinal modes are unchanged."""
from pathlib import Path
import numpy as np
from .parameters import LogRatioParameters
from .registration import register
from .types import ImageType, MotionType, Recipe, SelectionMode


def register_accepted(image: np.ndarray | str | Path, *, recipe: Recipe | str = Recipe.BRIGHT_DIM,
                      channel: int = 1, slice: int = 0, axes: str | None = None,
                      crop: bool = True, interpolation: str = "none",
                      output_path: str | Path | None = None):
    """Run an accepted explicit longitudinal recipe on 64-bit Windows with Java 25+.

    Bright/dim uses A001 recovery and A013 execution; Moving cells uses A004 guarded
    fallback. Landmarks retains its frozen baseline. There is no automatic router
    or Python fallback. Only the named channel is fitted; all channels are warped.
    Existing files should be handled through ripr.actions for overwrite protection.
    Inspect result.provenance and result.registration.warnings before analysis.
    """
    selected = Recipe.parse(recipe)
    image_type = ImageType.BRIGHTFIELD_DIC if selected is Recipe.LANDMARKS else ImageType.DENSE_FLUORESCENCE
    mode = SelectionMode.ACCEPTED_MOVING_CELLS if selected is Recipe.MOVING_CELLS else SelectionMode.ACCEPTED_LONGITUDINAL
    parameters = LogRatioParameters.recommended(image_type=image_type, motion_type=MotionType.INTERMITTENT_JUMPS,
        channel=channel, slice=slice, crop=crop, interpolation=interpolation)
    # recommended() establishes the fitting preset, then the explicit mode is applied.
    from dataclasses import replace
    parameters = replace(parameters, selection_mode=mode)
    return register(image, parameters, axes=axes, backend="java", output_path=output_path)
