# Event-anchored rotation validation protocol

Status: protocol frozen on 2026-08-26; execution awaits independent real event-annotated recordings.

## Inputs and split

Every recording must be declared before its results are inspected with: source identifier, acquisition
date, one-based remount frames, event annotation method, development/validation/final-test split, path and
SHA-256. Splits are by independent source recording, never by taking different frames from one recording.
No input used for rigid-rotation tuning may enter final event validation.

Required conditions include zero, one and several events; positive and negative rotation; translation
drift and remount translation; gain/fade; noise; sparse signal; biological change; bad frames; truncated
windows and unidentifiable rotation. At least one independent real recording with a documented remount is
mandatory for promotion.

## Frozen arms

| Arm | Rotation handling |
|---|---|
| `CONTINUOUS_ACCEPTED` | Accepted tuned continuous rotation |
| `EVENT_SINGLE_PAIR` | Last pre-event frame against first post-event frame; diagnostic comparator only |
| `EVENT_CONSENSUS_W3` | Up to 3 × 3 cross-event pairs with the implemented robust consensus |
| `TRANSLATION_ONLY_CONTROL` | Rotation disabled |

Every arm uses identical source pixels, event truth, regions, interpolation and scoring code. The 78-frame
default multi-lag plan has 359 ordinary graph pairs. Two interior window-three events add at most 18
global angular fits; fixed-angle translation, preprocessing, shift-bound estimation and input/output time
are reported separately.

## Measurements and gates

Record incremental event-angle error, cumulative segment-angle error, centre translation error, general
warping index, retained crop, corrected-image similarity, refusals, bound hits, warning status, global
angular-search count, fixed-angle fit count, wall time and peak memory. Report medians, upper tails, worst
cases and failures. Bootstrap frames within each event side, not the correlated pair rows.

Promotion requires all ten gates in `07_validation-and-promotion.md`, including no catastrophic angular or
translation error, exact zero within-segment angular jitter, no worse upper-tail/worst error than continuous
rotation, lower median real-stack run time with no slower hard ceiling, preserved off/continuous regression
results, reproducible controls/diagnostics and at least one independent real remount recording. Worst cases
must have full-resolution overlays or corrected movies reviewed in writing.

## Reproduction commands

Implementation and parity are checked with:

```powershell
mvn test
mvn test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$env:PYTHONPATH='src'
python -m pytest -q
```

The benchmark command, immutable manifest and evidence-store path will be added only after eligible real
recordings are identified. Creating an empty benchmark now would make the protocol look executed when it
is not.
