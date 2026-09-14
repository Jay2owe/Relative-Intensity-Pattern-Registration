# Match known-event rotation in Python

## Why this stage exists

The project promises the same transform signs, bounds and corrected data from Java and Python. This stage reproduces the event model and exposes it on the Python command line only after the Java contract and diagnostics are stable.

## Prerequisites

- `04_java-pipeline-integration_COMPLETED.md`
- `05_java-api-fiji-ui-and-macros_COMPLETED.md`

## Read first

- `docs/event-anchored-rotation/00_overview.md`
- `docs/event-anchored-rotation/BASELINE.md`
- `src/ripr/types.py`, lines 134-152 and the transform/status enums
- `src/ripr/parameters.py`, lines 164-252 and 274-344
- `src/ripr/core.py`, lines 254-560, 976-1136 and 1229-1300
- `src/ripr/registration.py`, lines 272-314 and 317 onward
- `src/ripr/cli.py`, lines 14-57
- `tests_python/test_api.py`, existing rigid-parameter tests around lines 130-147
- `tests_python/test_core.py`, existing rigid cases around lines 160-190
- `tests_python/test_java_parity.py`, existing rotation parity cases around lines 115-135
- `src/test/java/logratio/core/PythonParityProbe.java`, current probe contract
- `README_PYTHON.md`

## Scope

- Add the same three rotation modes, one-based public event frames and window validation to Python.
- Preserve `fit_rotation=True` as continuous mode for existing callers.
- Implement the same cross-event pair plan, exclusions, circular robust consensus, minimum support, spread and statuses.
- Implement fixed-angle coarse translation/refinement and known-angle reconciliation/repair with the same transform order.
- Protect event boundaries from translation outlier repair.
- Run event estimation once and reuse it through Python pilot/refit paths.
- Make Python's auto-shift estimate use prescribed relative angles.
- Add command-line flags matching Java macro semantics.
- Apply composed poses to raw input once through the existing final `apply_transforms` path.
- Extend the Java parity probe and Python tests to compare incremental events, absolute frame angles, pair diagnostics and final transforms.
- Update Python documentation and examples.

## Out of scope

- Do not introduce a Python-only estimator, confidence rule or fallback.
- Do not use SciPy/skimage shortcuts if they change the accepted Java objective or interpolation contract.
- Do not select defaults from parity fixtures; Stage 07 owns validation and promotion.
- Do not alter existing Python off/continuous defaults.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/ripr/types.py` | MODIFY | Add rotation mode and event statuses. |
| `src/ripr/parameters.py` | MODIFY | Add compatible event fields, validation and core conversion. |
| `src/ripr/core.py` | MODIFY | Implement event consensus, fixed-angle fits, known-angle reconciliation and repair. |
| `src/ripr/registration.py` | MODIFY | Reuse one event trajectory and preserve one final warp. |
| `src/ripr/cli.py` | MODIFY | Add known-event command-line controls. |
| `tests_python/test_api.py` | MODIFY | Pin public compatibility and validation. |
| `tests_python/test_core.py` | MODIFY | Add event estimator and end-to-end known-event fixtures. |
| `tests_python/test_java_parity.py` | MODIFY | Compare Java/Python events, transforms and diagnostics. |
| `src/test/java/logratio/core/PythonParityProbe.java` | MODIFY | Emit deterministic event-mode reference output. |
| `README_PYTHON.md` | MODIFY | Document Python API/CLI examples and limitations. |

## Implementation sketch

```python
class RotationMode(NamedEnum):
    OFF = "off"
    CONTINUOUS = "continuous"
    KNOWN_EVENTS = "known_events"
```

```python
@dataclass(frozen=True)
class LogRatioParameters:
    rotation_mode: RotationMode = RotationMode.OFF
    rotation_event_frames: tuple[int, ...] = ()  # one-based public values
    rotation_event_window: int = 3
    fit_rotation: bool = False  # compatibility view
```

Command line:

```powershell
ripr input.ome.tif output.tif --rotation-mode known_events `
  --rotation-events 25,51 --rotation-event-window 3 `
  --max-rotation-degrees 10
```

The final NumPy path remains conceptually:

```python
registration = estimate_event_angles_then_translations(frames, parameters)
corrected = apply_transforms(
    source, registration.cumulative, axes, parameters.interpolation, parameters.crop
)
```

Do not materialize and then re-register an intermediate rotated array.

Parity rows should include:

```text
event_frame,delta_theta,cumulative_theta,candidate_pairs,usable_pairs,
inlier_pairs,circular_mad,status
frame,dx,dy,theta
```

## Exit gate

1. `pytest -q tests_python/test_api.py tests_python/test_core.py tests_python/test_java_parity.py` passes.
2. Old `fit_rotation=True` construction still means continuous rotation and produces the same options.
3. Java and Python generate identical event pair indices from every fixture, including truncated windows.
4. Event deltas, cumulative angles and spread agree within the existing rigid parity tolerance recorded in `BASELINE.md`.
5. Per-frame translations and corrected pixels agree within the existing estimator/interpolation parity tolerances.
6. Segment angles are bit-identical within each Python segment and numerically equal to Java's trajectory.
7. Both implementations refuse the same low-support event and warn on the same high-disagreement event.
8. CLI parsing round-trips `25,51` as one-based frames and prints the resolved mode/window.
9. Python performs one final image resampling and applies each pose to every channel/Z plane.
10. The complete Java and Python test suites pass.

## Known risks

- NumPy reductions and Java loop order can differ slightly. Freeze deterministic pair order and robust-selection tie-breaking before relaxing parity tolerances.
- Python dataclass compatibility becomes ambiguous if `fit_rotation` and `rotation_mode` are independently writable. Use the same explicit-mode versus legacy-resolution rule as Java.
- SciPy circular statistics may define spread or NaN handling differently. Implement the small accepted formula directly when necessary for parity.
- Axes and one-based metadata conversion occur above the core array. Test OME-TIFF `TCZYX` cases, not only `(T,Y,X)` arrays.

