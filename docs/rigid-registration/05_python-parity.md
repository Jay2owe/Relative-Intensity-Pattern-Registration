# Match bounded rigid registration in the Python package

## Why this stage exists

The Python port already stores `theta` and contains the hidden joint derivative, but its high-level parameters do not expose a safe bounded movement model. This stage matches Java's algorithm, Automatic fallback and command-line behaviour so users do not get different answers or image-type support depending on entry point.

## Prerequisites

- `01_bounded-logratio-rotation_COMPLETED.md`
- `02_rotation-trajectory-and-warp_COMPLETED.md`
- `03_selector-capability-fallback_COMPLETED.md`

## Read first

- `docs/rigid-registration/00_overview.md`
- `src/logratio/types.py`, lines 217-265: Python rigid transform convention.
- `src/logratio/core.py`, lines 254-530: aligner options, evaluation, joint refinement and log-ratio alignment.
- `src/logratio/core.py`, lines 809-1017: registration options, reconciliation, repair and warnings.
- `src/logratio/core.py`, lines 1150-1223: common margin and warping.
- `src/logratio/parameters.py`, lines 164-327: public immutable parameters, Automatic rule and core mapping.
- `src/logratio/registration.py`, lines 270-405: estimation, correction and Automatic result.
- `src/logratio/cli.py`, lines 1-57: command-line interface and transform output.
- `tests_python/test_core.py`, lines 47-99; `tests_python/test_api.py`, lines 49-141; `tests_python/test_java_parity.py`, lines 1-86.
- `src/test/java/logratio/core/PythonParityProbe.java`, lines 1-end: Java fixture used by parity tests.

## Scope

- Add the same internal angular bound and bounded coarse angular search as Java.
- Port Stage 02's exact multi-lag rigid equations, image-size-aware outlier metric, statuses and warnings.
- Add `fit_rotation` and `max_rotation_degrees` to the high-level immutable parameter bundle.
- Preserve translation-only defaults and old constructor behaviour.
- Map public degrees to internal radians exactly once.
- Mirror the Stage 03 Automatic capability fallback for brightfield/DIC and fiducial/static.
- Preserve the requested rigid fields when applying recommendations and resolved recipes.
- Add command-line flags for rigid fitting and the degree-valued bound.
- Keep transform serialization in radians; the existing `theta` output column remains authoritative.
- Extend Java/Python parity fixtures to include translation, rotation and gain.
- Test warping and crop parity under rotation.
- Keep area-correlation Automatic selection disabled for rigid requests until Stage 07 validation is available.

## Out of scope

- Do not implement area-correlation rotation in this stage; Stage 06 changes both Java and Python correlation paths.
- Do not invent a separate Python-only selector model or different image-type rule.
- Do not retrain selector coefficients; Stage 07 owns evidence.
- Do not change TIFF axis semantics or batch failure isolation.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/logratio/core.py` | MODIFY | Port bounded initialization, exact rigid reconciliation, repair and warnings. |
| `src/logratio/parameters.py` | MODIFY | Add high-level rigid fields and capability-aware Automatic resolution. |
| `src/logratio/registration.py` | MODIFY | Preserve rigid requests, warnings, correction and Automatic provenance. |
| `src/logratio/cli.py` | MODIFY | Add explicit rigid command-line controls while retaining theta output. |
| `tests_python/test_core.py` | MODIFY | Test rigid solve, reconciliation, repair, margin and correction. |
| `tests_python/test_api.py` | MODIFY | Test high-level rigid correction and image-type fallback. |
| `tests_python/test_java_parity.py` | MODIFY | Compare Java and Python rigid transforms and diagnostics. |
| `src/test/java/logratio/core/PythonParityProbe.java` | MODIFY | Emit a deterministic Java rigid fixture for parity. |

## Implementation sketch

Mirror Java names and units at their corresponding layers:

```python
@dataclass
class AlignerOptions:
    fit_rotation: bool = False
    max_rotation: float = ...  # radians

@dataclass(frozen=True)
class LogRatioParameters:
    fit_rotation: bool = False
    max_rotation_degrees: float = ...
```

Map with `math.radians(self.max_rotation_degrees)` inside `registration_options()`. Do not store degrees in `Transform.theta`.

Port the angular-candidate ordering and tie-breaking literally from Java. Java/Python parity is more important than using a shorter NumPy expression that changes equal-score ordering. With `fit_rotation=False`, bypass the new candidate loop and preserve the existing translation path.

Port Stage 02's exact observation orientation:

```python
observed = pair.fit.transform if pair.to_frame > pair.from_frame else pair.fit.transform.inverse()
# C_lo.then(observed) == C_hi
```

Use the same rigid root-mean-square displacement formula for repair and the same angular wrap rule for interpolation.

Mirror the safe Automatic rule:

```python
if parameters.fit_rotation and chosen_estimator is not rigid_validated:
    resolved = recommendation(parameters.image_type, parameters.motion_type).apply(parameters)
    automatic = AutomaticSelection(fallback=True, ..., reason="not validated for rotation")
```

The fallback must preserve `fit_rotation`, `max_rotation_degrees`, reference strategy, lags and user compute limits.

Add command-line controls without changing output units:

```text
--fit-rotation
--max-rotation-degrees <number>
```

The transform table remains `frame, dx, dy, theta`, with `theta` in radians. State the unit in CLI help.

## Exit gate

These are proposed parity gates; confirm any new numeric Java/Python tolerance before accepting the stage.

1. `python -m pytest tests_python/test_core.py tests_python/test_api.py tests_python/test_java_parity.py` passes.
2. Default Python registration remains translation-only and existing tests produce their previous results.
3. Combined translation, rotation and gain are recovered within the same tolerances as Java.
4. Multi-lag rigid output matches Java for every timepoint within the existing parity tolerance or a newly declared tighter rotation tolerance.
5. Brightfield/DIC and fiducial/static rigid Automatic requests visibly fall back to log-ratio; their translation-only Automatic requests still use Newton area correlation.
6. Recommendation and fallback preserve the requested rigid fields and other caller-owned settings.
7. Nearest-neighbour, bilinear and bicubic rigid warps follow Java's sign, crop and fill conventions.
8. CLI help states that the angular bound is degrees and transform output theta is radians.
9. A CLI rigid run writes non-zero theta values and corrects the supplied stack.
10. `mvn test-compile` builds `PythonParityProbe`, and the full Java/Python test suites pass. The probe is a command-line fixture invoked by `test_java_parity.py`, not a JUnit test.

## Known risks

- NumPy vectorization can change tie-breaking and floating-point accumulation order. Preserve the Java candidate order and set realistic parity tolerances.
- Python currently resolves Automatic settings before estimation with a compact hard-coded image-type rule. Ensure the fallback explanation describes the actual resolved estimator.
- Dataclass field additions can break positional callers. Append new fields and prefer keyword use in documentation/tests.
- The Python area-correlation path remains translation-only until Stage 06; keep its capability false even though `Transform` carries theta.
