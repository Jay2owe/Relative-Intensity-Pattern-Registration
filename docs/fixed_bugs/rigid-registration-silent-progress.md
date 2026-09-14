# Rigid registration looked frozen during pair planning

- Date fixed: 2026-08-22
- Symptom: with rotation detection enabled, Fiji displayed `planning pairs` and then appeared to do nothing, without completing or reporting an error.
- Actual state: the process remained responsive and used the available processors, so it was computing rather than deadlocked.

## What went wrong

Progress was reported only after a complete frame-pair alignment. A rigid fit evaluates many angular candidates, so the first callback could take long enough for the plugin to look frozen. The automatic movement-bound survey also inherited rigid fitting even though it estimates only a translation radius; that multiplied a cheap serial planning pass by the angular search. Automatic pixel-selection wrappers reduced the richer progress interface back to completion-only callbacks.

The broken patterns were effectively:

```java
result = alignPair();
progress.update(done, total);
```

and:

```java
PairAligner.Options planning = requested.copy(); // retained fitRotation=true
```

## Fix

- Pair scheduling now reports batch start, worker count, and each active pair before the pair completes.
- The automatic shift-bound survey reports each surveyed pair and is explicitly translation-only.
- Registration reports selection, shift planning, pair fitting, reconciliation, trajectory repair, and output warping as named phases.
- Fiji shows elapsed time, active work, first-result status, estimated remaining time after the first result, and a 15-second log heartbeat.
- Escape cancellation is polled inside both log-ratio and area-correlation translation and rotation searches.
- Rich progress events are retained through automatic selection and two-pass pixel selection.

## Regression guards

- `PairSchedulerTest.lifecycleEventsArriveBeforeTheFirstCompletion`
- `RegistrationGuardTest.shiftBoundPlanningReportsVisibleProgress`
- `RegistrationGuardTest.rigidModeKeepsShiftBoundPlanningTranslationOnly`
- `PairAlignerRecoveryTest.rigidFitCanBeCancelledBeforeItCompletes`
- `PixelSelectionEngineTest.twoPassProgressReportsActivityBeforeEitherPassCompletes`

The full Java suite passed with 348 tests, 0 failures, and 0 errors.

## Files changed

- `src/main/java/logratio/core/PairScheduler.java`
- `src/main/java/logratio/core/PairAligner.java`
- `src/main/java/logratio/core/AreaCorrelation.java`
- `src/main/java/logratio/core/Registration.java`
- `src/main/java/logratio/PixelSelectionEngine.java`
- `src/main/java/logratio/StackWarper.java`
- `src/main/java/logratio/api/LogRatioRegistration.java`
- `src/main/java/logratio/LogRatioRegistrationPlugin.java`
- the five regression-test classes named above
