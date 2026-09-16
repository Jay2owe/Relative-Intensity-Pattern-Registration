"""Complete parameter bundles and the plugin's evidence-based recommendations."""

from __future__ import annotations

from dataclasses import dataclass, replace
import math

from .core import AlignerOptions, RegistrationOptions
from .types import (
    Estimator,
    ImageType,
    Interpolation,
    MotionType,
    PixelSelectionStrategy,
    PixelSupport,
    Preprocessing,
    Reference,
    Recipe,
    RobustNorm,
    RotationMode,
    SelectionMode,
)


#: Step-magnitude outlier repair, as a recommendation resolves it.
#:
#: A discontinuous-motion declaration means large steps are expected evidence,
#: not corrupt observations. Keep repair of unsupported frames, but do not
#: smooth genuine remount or stage jumps merely because they differ from the
#: quiet parts of the recording: a real 6 px jump in a recording that otherwise
#: moves a quarter of a pixel is many times the median step, and the guard
#: cannot tell it from a bad fit without asking the pairs.
#:
#: Mirrors `RelativeIntensityPatternParameters.Builder.recommendation`, which is
#: the authority for this mapping. Both sides are pinned by tests; change them
#: together or the two engines disagree on any jump recording.
JUMP_OUTLIER_MADS = 0.0
DEFAULT_RECOMMENDED_OUTLIER_MADS = 6.0


def outlier_mads_for(motion_type: MotionType | str) -> float:
    """The outlier-repair setting a recommendation resolves to for this motion."""
    motion = MotionType.parse(motion_type)
    return (JUMP_OUTLIER_MADS if motion is MotionType.INTERMITTENT_JUMPS
            else DEFAULT_RECOMMENDED_OUTLIER_MADS)


@dataclass(frozen=True)
class RegistrationRecipe:
    name: str
    evidence: str
    preprocessing: Preprocessing
    pixel_selection_strategy: PixelSelectionStrategy
    pixel_selection_preprocessing: Preprocessing
    pixel_removal_percent: float
    norm: RobustNorm
    pixel_support: PixelSupport
    gradient_fraction: float
    floor_percentile: float
    ceiling_percentile: float
    max_iterations: int
    max_samples: int
    estimator: Estimator = Estimator.LOG_RATIO_FIT

    def apply(self, parameters: "LogRatioParameters", provenance: str = "") -> "LogRatioParameters":
        return replace(
            parameters,
            selection_mode=SelectionMode.MANUAL,
            recipe_provenance=provenance,
            outlier_mads=outlier_mads_for(parameters.motion_type),
            outlier_protection_residual_gain=math.nan,
            estimator=self.estimator,
            preprocessing=self.preprocessing,
            pixel_selection_strategy=self.pixel_selection_strategy,
            pixel_selection_preprocessing=self.pixel_selection_preprocessing,
            pixel_removal_percent=self.pixel_removal_percent,
            norm=self.norm,
            pixel_support=self.pixel_support,
            gradient_fraction=self.gradient_fraction,
            floor_percentile=self.floor_percentile,
            ceiling_percentile=self.ceiling_percentile,
            max_iterations=self.max_iterations,
            max_samples=self.max_samples,
        )


def _preset(
    name: str,
    evidence: str,
    norm: RobustNorm,
    support: PixelSupport,
    floor: float = math.nan,
    ceiling: float = math.nan,
    iterations: int = 25,
    samples: int = 200_000,
) -> RegistrationRecipe:
    return RegistrationRecipe(
        name,
        evidence,
        Preprocessing.NONE,
        PixelSelectionStrategy.NONE,
        Preprocessing.NONE,
        25.0,
        norm,
        support,
        0.5,
        floor,
        ceiling,
        iterations,
        samples,
    )


def recommendation(image_type: ImageType | str, motion_type: MotionType | str) -> RegistrationRecipe:
    """Return the same 5 by 4 recommendation matrix as the ImageJ plugin."""
    image = ImageType.parse(image_type)
    motion = MotionType.parse(motion_type)
    evidence = f"Best log-ratio configuration for {image.label} with {motion.label} in the balanced controlled benchmark."
    huber = lambda: _preset("Huber weighting", evidence, RobustNorm.HUBER, PixelSupport.ALL)
    woods = lambda: _preset("Woods least squares", evidence, RobustNorm.LEAST_SQUARES, PixelSupport.ALL)
    mutual = lambda: _preset(
        "Tukey weighting with shared significant edges",
        evidence,
        RobustNorm.TUKEY,
        PixelSupport.MUTUAL_NOISE_GRADIENT,
        iterations=12,
        samples=50_000,
    )
    tukey_gradient = lambda: _preset(
        "Tukey weighting with gradient pixels", evidence, RobustNorm.TUKEY, PixelSupport.GRADIENT
    )
    ceiling10 = lambda: _preset(
        "Tukey weighting excluding brightest 10 percent",
        evidence,
        RobustNorm.TUKEY,
        PixelSupport.GRADIENT,
        ceiling=90.0,
    )
    ceiling25 = lambda: _preset(
        "Tukey weighting excluding brightest 25 percent",
        evidence,
        RobustNorm.TUKEY,
        PixelSupport.GRADIENT,
        ceiling=75.0,
    )
    floor25 = lambda: _preset(
        "Tukey weighting excluding dimmest 25 percent",
        evidence,
        RobustNorm.TUKEY,
        PixelSupport.GRADIENT,
        floor=25.0,
    )
    if image is ImageType.PHASE_CONTRAST:
        base = mutual() if motion is MotionType.STEADY_DIRECTIONAL_DRIFT else (
            woods() if motion is MotionType.CURVED_OSCILLATING_DRIFT else huber()
        )
    elif image is ImageType.BRIGHTFIELD_DIC:
        base = huber() if motion in (MotionType.CURVED_OSCILLATING_DRIFT, MotionType.SUBPIXEL_RANDOM_WALK) else woods()
    elif image is ImageType.DENSE_FLUORESCENCE:
        base = floor25() if motion is MotionType.CURVED_OSCILLATING_DRIFT else (
            huber() if motion is MotionType.STEADY_DIRECTIONAL_DRIFT else mutual()
        )
    elif image is ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE:
        base = tukey_gradient() if motion is MotionType.INTERMITTENT_JUMPS else (
            ceiling10() if motion is MotionType.SUBPIXEL_RANDOM_WALK else ceiling25()
        )
    else:
        base = ceiling25() if motion is MotionType.STEADY_DIRECTIONAL_DRIFT else (
            huber() if motion is MotionType.SUBPIXEL_RANDOM_WALK else woods()
        )
    preprocessing = Preprocessing.NONE
    if image is ImageType.PHASE_CONTRAST and motion is MotionType.INTERMITTENT_JUMPS:
        preprocessing = Preprocessing.GAUSSIAN_0_7
    elif image is ImageType.PHASE_CONTRAST and motion is MotionType.SUBPIXEL_RANDOM_WALK:
        preprocessing = Preprocessing.GAUSSIAN_1_0
    elif image is ImageType.BRIGHTFIELD_DIC and motion is MotionType.SUBPIXEL_RANDOM_WALK:
        preprocessing = Preprocessing.MEDIAN_3X3
    elif image is ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE and motion is MotionType.STEADY_DIRECTIONAL_DRIFT:
        preprocessing = Preprocessing.MEDIAN_3X3
    base = replace(base, preprocessing=preprocessing)
    if image is ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE and motion is not MotionType.SUBPIXEL_RANDOM_WALK:
        base = replace(
            base,
            pixel_selection_strategy=PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
            pixel_selection_preprocessing=Preprocessing.NONE,
            pixel_removal_percent=25.0,
        )
    return base


@dataclass(frozen=True)
class LogRatioParameters:
    """Inputs shared by array, TIFF, command-line, and batch entry points.

    Most callers do not need this class: :func:`ripr.register` and
    :func:`ripr.register_file` expose recipe, channel and longitudinal mode directly. Use
    :meth:`for_recipe` to build the same simple choices explicitly. The remaining fields are expert
    controls.

    ``channel``, ``slice``, and ``reference_frame`` are one-based, matching ImageJ. A
    ``slice`` of zero means maximum-project Z before estimating movement.
    """

    image_type: ImageType = ImageType.PHASE_CONTRAST
    motion_type: MotionType = MotionType.SUBPIXEL_RANDOM_WALK
    selection_mode: SelectionMode = SelectionMode.RECOMMENDED
    recipe_provenance: str = ""
    estimation_scale: float = 1.0
    preprocessing: Preprocessing = Preprocessing.NONE
    pixel_selection_strategy: PixelSelectionStrategy = PixelSelectionStrategy.NONE
    pixel_selection_preprocessing: Preprocessing = Preprocessing.NONE
    pixel_removal_percent: float = 25.0
    channel: int = 1
    slice: int = 0
    reference: Reference = Reference.MULTILAG
    reference_frame: int = 1
    lags: tuple[int, ...] = (1, 2, 4, 8, 16)
    template_window: int = 5
    norm: RobustNorm = RobustNorm.HUBER
    estimator: Estimator = Estimator.LOG_RATIO_FIT
    pixel_support: PixelSupport = PixelSupport.ALL
    gradient_fraction: float = 0.5
    epsilon: float = 1.0
    floor_percentile: float = math.nan
    ceiling_percentile: float = math.nan
    remove_offset: bool = False
    offset_percentile: float = 1.0
    auto_max_shift: bool = True
    max_shift: float = 30.0
    rotation_mode: RotationMode | None = None
    rotation_event_frames: tuple[int, ...] = ()
    rotation_event_window: int = 3
    fit_rotation: bool | None = None
    max_rotation_degrees: float = 10.0
    outlier_mads: float = 6.0
    outlier_protection_residual_gain: float = math.nan
    max_iterations: int = 25
    max_samples: int = 200_000
    min_valid_fraction: float = 0.10
    threads: int = 0
    interpolation: Interpolation = Interpolation.NONE
    crop: bool = True

    def __post_init__(self):
        enum_fields = {
            "image_type": ImageType,
            "motion_type": MotionType,
            "selection_mode": SelectionMode,
            "preprocessing": Preprocessing,
            "pixel_selection_strategy": PixelSelectionStrategy,
            "pixel_selection_preprocessing": Preprocessing,
            "reference": Reference,
            "norm": RobustNorm,
            "estimator": Estimator,
            "pixel_support": PixelSupport,
            "interpolation": Interpolation,
        }
        for name, enum_type in enum_fields.items():
            object.__setattr__(self, name, enum_type.parse(getattr(self, name)))
        object.__setattr__(self, "lags", tuple(int(value) for value in self.lags))
        raw_mode = self.rotation_mode
        mode = None if raw_mode is None else RotationMode.parse(raw_mode)
        if mode is None:
            mode = RotationMode.CONTINUOUS if self.fit_rotation is True else RotationMode.OFF
        elif self.fit_rotation is not None:
            legacy = RotationMode.CONTINUOUS if self.fit_rotation else RotationMode.OFF
            # A frozen known-events bundle stores fit_rotation=True as its compatibility view, so
            # dataclasses.replace must be allowed to reconstruct that state. It also copies the
            # resolved OFF/CONTINUOUS mode, so a changed legacy flag must win in that case; command
            # line and macro parsers reject genuinely simultaneous old/new controls before here.
            compatible_event_view = mode is RotationMode.KNOWN_EVENTS and self.fit_rotation is True
            if mode is not legacy and not compatible_event_view:
                mode = legacy
        object.__setattr__(self, "rotation_mode", mode)
        object.__setattr__(self, "fit_rotation", mode is not RotationMode.OFF)
        object.__setattr__(self, "rotation_event_frames", tuple(
            int(value) for value in self.rotation_event_frames
        ))
        self.validate()

    def validate(self) -> None:
        if not 0 < self.pixel_removal_percent < 100:
            raise ValueError("pixel removal must be in (0, 100)")
        if not 0 < self.estimation_scale <= 1:
            raise ValueError("estimation scale must be in (0, 1]")
        if self.channel < 1 or self.slice < 0 or self.reference_frame < 1:
            raise ValueError("channel/reference frame must be one-based and slice must be zero or one-based")
        if not self.lags or (self.reference is Reference.MULTILAG and 1 not in self.lags):
            raise ValueError("multi-lag registration requires lag 1")
        if self.reference is Reference.ROLLING and self.pixel_selection_strategy is not PixelSelectionStrategy.NONE:
            raise ValueError("pixel removal cannot be combined with a rolling reference template")
        if self.estimator is not Estimator.LOG_RATIO_FIT and self.pixel_selection_strategy is not PixelSelectionStrategy.NONE:
            raise ValueError(f"{self.estimator.value} has no per-pixel support to mask")
        if not self.epsilon > 0 or not self.max_shift > 0:
            raise ValueError("epsilon and maximum shift must be greater than zero")
        if self.rotation_event_window < 1:
            raise ValueError("rotation event window must be at least 1")
        previous = 1
        for event in self.rotation_event_frames:
            if event < 2:
                raise ValueError(
                    f"rotation event frame {event} must be at least 2 "
                    "(the first frame after a remount)"
                )
            if event <= previous:
                raise ValueError(
                    f"rotation event frame {event} must be unique and strictly increasing"
                )
            previous = event
        if self.rotation_mode is RotationMode.KNOWN_EVENTS and not self.rotation_event_frames:
            raise ValueError("known-events rotation requires at least one rotation event frame")
        if self.rotation_mode is not RotationMode.KNOWN_EVENTS and self.rotation_event_frames:
            raise ValueError("rotation event frames require rotation mode known_events")
        if self.fit_rotation and (
            not self.max_rotation_degrees > 0 or not math.isfinite(self.max_rotation_degrees)
        ):
            raise ValueError("maximum rotation must be a finite number greater than zero")
        for value, name in ((self.floor_percentile, "floor"), (self.ceiling_percentile, "ceiling")):
            if not math.isnan(value) and not 0 < value < 100:
                raise ValueError(f"{name} percentile must be in (0, 100), or NaN")
        if not math.isnan(self.floor_percentile) and not math.isnan(self.ceiling_percentile) and self.floor_percentile >= self.ceiling_percentile:
            raise ValueError("floor percentile must be below ceiling percentile")
        if self.max_iterations < 1 or self.max_samples < 1 or not 0 < self.min_valid_fraction <= 1:
            raise ValueError("iteration/sample counts and valid fraction are outside their allowed range")
        if not math.isnan(self.outlier_protection_residual_gain) and not (
            math.isfinite(self.outlier_protection_residual_gain)
            and 0 <= self.outlier_protection_residual_gain <= 1
        ):
            raise ValueError("outlier protection residual gain must be in [0, 1], or NaN")

    @classmethod
    def manual(cls, **values) -> "LogRatioParameters":
        return cls(selection_mode=SelectionMode.MANUAL, **values)

    @classmethod
    def for_recipe(
        cls,
        recipe: Recipe | str = Recipe.LANDMARKS,
        *,
        channel: int = 1,
        longitudinal: bool = True,
        **values,
    ) -> "LogRatioParameters":
        """Build the benchmark-backed recipe using only the three normal user choices.

        Landmarks maps to phase contrast; Bright/dim maps to sparse/low-light fluorescence or
        bioluminescence. Both use intermittent-jump protection and default to the whole-recording
        longitudinal route. Moving cells is the separate biological-foreground Recommended recipe
        and therefore requires ``longitudinal=False``.
        """
        chosen = Recipe.parse(recipe)
        if chosen is Recipe.LANDMARKS:
            image_type = ImageType.PHASE_CONTRAST
            motion_type = MotionType.INTERMITTENT_JUMPS
        elif chosen is Recipe.BRIGHT_DIM:
            image_type = ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE
            motion_type = MotionType.INTERMITTENT_JUMPS
        else:
            if longitudinal:
                raise ValueError(
                    "the Moving cells recipe is not a longitudinal reference route; "
                    "set longitudinal=False"
                )
            image_type = ImageType.DENSE_FLUORESCENCE
            motion_type = MotionType.INTERMITTENT_JUMPS
        selection_mode = (
            SelectionMode.LONGITUDINAL_ACCURACY if longitudinal
            else SelectionMode.RECOMMENDED if chosen is Recipe.MOVING_CELLS
            else SelectionMode.AUTOMATIC
        )
        base = cls.recommended(
            image_type=image_type,
            motion_type=motion_type,
            channel=channel,
            **values,
        )
        return replace(base, selection_mode=selection_mode)

    @classmethod
    def recommended(
        cls,
        image_type: ImageType | str = ImageType.PHASE_CONTRAST,
        motion_type: MotionType | str = MotionType.SUBPIXEL_RANDOM_WALK,
        **values,
    ) -> "LogRatioParameters":
        base = cls(
            image_type=ImageType.parse(image_type),
            motion_type=MotionType.parse(motion_type),
            selection_mode=SelectionMode.MANUAL,
            **values,
        )
        recipe = recommendation(base.image_type, base.motion_type)
        return recipe.apply(base, f"category recommendation: {recipe.name}")

    def resolve(self) -> "LogRatioParameters":
        """Resolve modes that do not require recording pixels into explicit settings."""
        if self.selection_mode is SelectionMode.MANUAL:
            return self
        if self.selection_mode is SelectionMode.AUTOMATIC:
            from .recording_selector import requires_evidence, select
            if requires_evidence(self.image_type, False):
                raise ValueError(
                    "Automatic selection requires recording pixels; use ripr.estimate() or ripr.register()"
                )
            return select(self)[0]
        if self.selection_mode is SelectionMode.LONGITUDINAL_ACCURACY:
            raise ValueError(
                "Longitudinal maximum accuracy requires the complete recording; "
                "use ripr.estimate() or ripr.register()"
            )
        recipe = recommendation(self.image_type, self.motion_type)
        return recipe.apply(self, f"category recommendation: {recipe.name}")

    def registration_options(self) -> RegistrationOptions:
        return RegistrationOptions(
            aligner=AlignerOptions(
                max_shift=self.max_shift,
                fit_rotation=self.rotation_mode is RotationMode.CONTINUOUS,
                max_rotation=math.radians(self.max_rotation_degrees),
                norm=self.norm,
                support=self.pixel_support,
                gradient_fraction=self.gradient_fraction,
                max_iterations=self.max_iterations,
                min_valid_fraction=self.min_valid_fraction,
                max_samples=self.max_samples,
                profile_gain=True,
            ),
            estimator=self.estimator,
            rotation_mode=self.rotation_mode,
            rotation_event_frames=tuple(value - 1 for value in self.rotation_event_frames),
            rotation_event_window=self.rotation_event_window,
            reference=self.reference,
            reference_frame=self.reference_frame - 1,
            lags=self.lags,
            template_window=self.template_window,
            epsilon=self.epsilon,
            intensity_floor_percentile=self.floor_percentile,
            saturation_percentile=self.ceiling_percentile,
            remove_offset=self.remove_offset,
            offset_percentile=self.offset_percentile,
            outlier_mads=self.outlier_mads,
            outlier_protection_residual_gain=self.outlier_protection_residual_gain,
            threads=self.threads,
            auto_max_shift=self.auto_max_shift,
            nearest_neighbor_rotation=(
                self.rotation_mode is not RotationMode.OFF
                and self.interpolation is Interpolation.NONE
            ),
        )
