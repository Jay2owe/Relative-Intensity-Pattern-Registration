# Expose replayable known-event controls in Java and Fiji

## Why this stage exists

The core feature needs an unambiguous user contract: which frames are events, what each number means, and whether rotation is off, continuous or event-anchored. This stage exposes that contract through the Java API, Fiji dialogs, macro recording and batch replay while keeping every old invocation stable.

## Prerequisites

- `04_java-pipeline-integration_COMPLETED.md`

## Read first

- `docs/event-anchored-rotation/00_overview.md`
- `docs/event-anchored-rotation/BASELINE.md`
- `src/main/java/logratio/api/LogRatioParameters.java`, lines 30-150 and 241-374
- `src/main/java/logratio/LogRatioRegistrationPlugin.java`, lines 150-223 and 265-350
- `src/main/java/logratio/LogRatioDialogModel.java`, lines 12-92
- `src/main/java/logratio/MacroOptionsParser.java`, lines 22-103 and 155-240
- `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java`, parameter and report paths located before editing
- `src/main/java/logratio/LogRatioBatchMacroOptionsParser.java`, entire file
- `src/test/java/logratio/MacroOptionsParserTest.java`, lines 1-120
- `README.md`, current rotation, API, macro and diagnostics sections

## Scope

- Replace the ambiguous main rotation checkbox with a three-choice control while preserving the old stored/macro boolean contract.
- Add a comma-separated field labelled `First frames after remounting (1-based)` and a temporal-window field.
- Explain that frame `e` is the first frame after replacement and the estimated angle persists until the next event.
- Make event fields required only in known-events mode; disable or clearly mark them irrelevant in other modes.
- In the advanced dialog, label the angular bound conditionally as per event for known-events mode and per compared pair for continuous mode.
- Add Java builder fields and getters if Stage 01 left them internal.
- Record known-event macros with explicit new tokens.
- Parse old `fit_rotation` macros exactly as continuous mode; reject contradictory old/new tokens.
- Ensure batch options carry, log and replay event frames, window and mode.
- Log an event table containing event frame, incremental angle, cumulative angle, candidate/usable/inlier pairs, spread and status.
- Include event diagnostics in batch reports without changing existing transform column meanings.
- Update README usage, API and macro examples, limitations and the one-resampling explanation.
- Keep known-events mode opt-in and visibly distinct from ordinary continuous rotation.

## Out of scope

- Do not change estimation mathematics; Stages 02-04 own it.
- Do not add a manual angle override in the first release. It remains a future extension if validation shows a need.
- Do not implement Python flags; Stage 06 owns them.
- Do not claim accuracy or runtime improvements before Stage 07 completes.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/api/LogRatioParameters.java` | MODIFY | Finalize public builders, compatibility and validation. |
| `src/main/java/logratio/LogRatioRegistrationPlugin.java` | MODIFY | Add mode, event list/window controls and event diagnostics. |
| `src/main/java/logratio/LogRatioDialogModel.java` | MODIFY | Record replayable event options. |
| `src/main/java/logratio/MacroOptionsParser.java` | MODIFY | Parse new tokens and preserve old macro meaning. |
| `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java` | MODIFY | Carry and report event settings/results in batch runs. |
| `src/main/java/logratio/LogRatioBatchMacroOptionsParser.java` | MODIFY | Replay event settings in batch macros. |
| `src/test/java/logratio/MacroOptionsParserTest.java` | MODIFY | Pin new and legacy macro contracts. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Pin public builder and result diagnostics. |
| `README.md` | MODIFY | Document usage, semantics, diagnostics and limitations. |

## Implementation sketch

Proposed macro form:

```text
rotation_mode=known_events rotation_events=[25,51] rotation_event_window=3 \
max_rotation_degrees=10
```

Legacy form remains valid and unchanged:

```text
fit_rotation max_rotation_degrees=10
```

It resolves to continuous rotation. The following must be rejected:

```text
fit_rotation rotation_mode=off
rotation_mode=known_events rotation_events=[]
rotation_mode=known_events rotation_events=[1,25]
```

Main-dialog wording:

```text
Rotation handling: Off | Search continuously | Known remount frames
First frames after remounting (1-based): 25,51
Frames used on each side of an event: 3
```

Help text:

```text
At each listed frame, one rotation is estimated from nearby frames. That angle is held
until the next listed frame. Translation is still estimated throughout the recording.
Rotation and translation are composed and the original image is resampled once.
```

Event result table:

```text
event_frame,delta_degrees,cumulative_degrees,candidate_pairs,usable_pairs,
inlier_pairs,spread_degrees,status
```

Keep the ordinary per-frame transform table unchanged so downstream scripts continue to read `dx`, `dy` and `theta` with the same sign and units.

## Exit gate

1. `mvn -Dtest=logratio.MacroOptionsParserTest,logratio.api.LogRatioRegistrationApiTest test` passes.
2. Recording and parsing a known-events dialog round-trips every event setting exactly.
3. A previously recorded `fit_rotation` macro parses to continuous mode and produces the same parameters as before.
4. Contradictory old/new rotation tokens fail with an actionable message rather than call-order-dependent behaviour.
5. Main and advanced dialogs state one-based first-post-remount semantics and conditional angular-bound meaning.
6. Event fields cannot silently affect off or continuous mode.
7. Batch replay retains event list order, window, mode and result diagnostics.
8. Existing per-frame transform CSV/log columns keep their names, units and signs.
9. README examples work when pasted into the Java API and macro parser tests.
10. `mvn test` passes.

## Known risks

- A checkbox cannot express three modes. Retaining it beside a mode choice would create contradictory state; use one authoritative visible control and legacy compatibility only in parsing/building.
- ImageJ macro brackets are used for values containing separators or spaces. Reuse the existing token parser rather than writing an event-only parser.
- Event diagnostics added to an existing CSV can break downstream readers. Prefer a separate event table or append only through a versioned schema; never reorder existing transform columns.
- Users may enter the last frame as an event, leaving only one post-event image. Runtime validation and the estimator's usable-pair report must explain whether the truncated window can support it.

