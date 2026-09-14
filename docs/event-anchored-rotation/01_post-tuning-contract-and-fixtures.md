# Freeze the post-tuning baseline and event-mode contract

## Why this stage exists

Event-anchored rotation must be built on the rotation implementation that survives the current tuning, not on assumptions made while tuning is incomplete. This stage records that accepted baseline, defines the public and core event semantics, and creates deterministic fixtures that later stages can share without changing their meaning.

## Prerequisites

- The blocking prerequisite in `00_overview.md` is satisfied.
- The accepted verdict and promoted implementation under `library/rigid_rotation_tuning/` are identifiable.

## Read first

- `docs/event-anchored-rotation/00_overview.md`
- `library/rigid_rotation_tuning/README.md`, lines 1-21
- `library/rigid_rotation_tuning/rounds/R01_rotation_reliability/plan.md`, lines 1-138
- `library/rigid_rotation_tuning/rounds/R01_rotation_reliability/verdict.md`, entire file as it exists when this stage runs
- `library/rigid_rotation_tuning/s4_score/r00_a000/out/diagnosis.md`, lines 1-3
- `src/main/java/logratio/api/LogRatioParameters.java`, lines 30-150 and 154-210
- `src/main/java/logratio/core/Registration.java`, lines 52-227 and 600-700
- `src/main/java/logratio/core/Transform.java`, lines 12-125
- `src/test/java/logratio/core/LagAwareWarmStartTest.java`, lines 1-72
- `papers/_scout/event-anchored-piecewise-constant-rotation/REPORT.md`

## Scope

- Verify that rotation tuning is complete and record the accepted commit, evidence, solver path, gates and parity command in `BASELINE.md`.
- If tuning is not complete, stop without editing production code and report the unmet prerequisite.
- Add a three-state rotation mode: off, continuous per-pair rotation, and known events.
- Preserve the existing `fitRotation`/`fit_rotation` contract as a compatibility view: `true` continues to mean continuous rotation unless the new event mode is explicitly selected.
- Add event-frame and temporal-window fields to the Java parameter model without activating event execution yet.
- Define one-based public versus zero-based core conversion in one place.
- Validate that event frames are unique, strictly increasing, at least 2, and within the recording once the frame count is known.
- Define deterministic synthetic fixture builders for one event, several events, non-event translation drift, gain change and truncated windows near the final frame.
- Pin the existing no-rotation and continuous-rotation parameter behaviour before later stages touch execution.

## Out of scope

- Do not estimate event angles; Stage 02 owns the estimator.
- Do not add fixed-angle pair fitting or change reconciliation; Stage 03 owns those algorithms.
- Do not branch the production registration pipeline; Stage 04 owns execution.
- Do not add Fiji controls, macro tokens or Python fields; Stages 05 and 06 own those surfaces.
- Do not choose a confidence-spread threshold or promote a default window from the future test set; Stage 07 owns evidence-based promotion.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `docs/event-anchored-rotation/BASELINE.md` | NEW | Record the accepted tuned dependency and commands before implementation begins. |
| `src/main/java/logratio/core/RotationMode.java` | NEW | Shared three-state rotation contract. |
| `src/main/java/logratio/api/LogRatioParameters.java` | MODIFY | Store the mode, one-based event frames and event window while preserving the legacy boolean. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Pin builder compatibility, validation and copying. |
| `src/test/java/logratio/core/EventRotationFixtures.java` | NEW | Shared deterministic event trajectories and images for later core tests. |
| `src/test/java/logratio/core/EventRotationContractTest.java` | NEW | Pin indexing, segment construction and unchanged legacy modes. |

## Implementation sketch

The exact field names may follow the accepted rotation implementation, but preserve this meaning:

```java
public enum RotationMode {
    OFF,
    CONTINUOUS,
    KNOWN_EVENTS
}
```

```java
public final RotationMode rotationMode;
/** One-based first frames after remount events; meaningful only for KNOWN_EVENTS. */
public final int[] rotationEventFrames;
/** Maximum frames drawn from each side of an event. */
public final int rotationEventWindow;
/** Compatibility view retained for existing callers and output tables. */
public final boolean fitRotation;
```

Builder rules:

```text
no rotation call                         -> OFF
fitRotation(false)                       -> OFF
fitRotation(true)                        -> CONTINUOUS
rotationMode(KNOWN_EVENTS) + events      -> KNOWN_EVENTS
explicit contradictory legacy/mode data -> reject, never guess
```

Public example:

```java
LogRatioParameters.builder()
        .rotationMode(RotationMode.KNOWN_EVENTS)
        .rotationEventFrames(25, 51)
        .rotationEventWindow(3)
        .maxRotationDegrees(10)
        .build();
```

The fixture truth is cumulative:

```text
event frames: 25, 51
theta[1..24]  = 0
theta[25..50] = delta25
theta[51..T]  = delta25 + delta51
```

Generate raw frames from a common source with independent per-frame translations and gains, then one segment angle. Do not generate a corrected intermediate image and treat it as input.

## Exit gate

1. `BASELINE.md` names the accepted rotation-tuning verdict, production commit, evidence artifacts and passing parity command.
2. `mvn -Dtest=logratio.api.LogRatioRegistrationApiTest,logratio.core.EventRotationContractTest test` passes.
3. Every prior `fitRotation(true)` API test still resolves to continuous rotation with the same bound.
4. Copying or rebuilding parameters preserves the event list defensively; caller mutation cannot change it.
5. Duplicate, descending, frame-1 and non-positive event indices are rejected with messages that name the offending value.
6. Runtime validation rejects event frames beyond the stack length.
7. The fixtures prove cumulative angles are exactly constant inside segments and accumulate only at supplied boundaries.
8. No registration execution path changes in this stage; the existing full test suite remains green.

## Known risks

- The tuned implementation may already introduce a richer rotation-mode or confidence type. Reuse it instead of creating a parallel abstraction, while retaining the three public meanings above.
- Builder call order can make legacy and explicit settings ambiguous. Track whether the new mode was explicitly supplied and reject contradictions rather than allowing last-call-wins behaviour to change recorded macros.
- Arrays exposed by a supposedly immutable parameter class can be mutated. Clone on input, copy and output.
- Do not copy old tuning thresholds into `BASELINE.md` if the final accepted verdict supersedes them.

