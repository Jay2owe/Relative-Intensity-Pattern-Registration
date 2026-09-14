# Integrate event rotation as the first Java geometric pass

## Why this stage exists

The estimator and fixed-angle solver are isolated components until the whole registration pipeline runs them in the right order. This stage estimates event angles once, reuses the same immutable trajectory through every pilot/refit path, and produces ordinary cumulative rigid transforms for the existing single final warp.

## Prerequisites

- `02_robust-event-angle-estimator_COMPLETED.md`
- `03_fixed-angle-translation-and-reconciliation_COMPLETED.md`

## Read first

- `docs/event-anchored-rotation/00_overview.md`
- `docs/event-anchored-rotation/BASELINE.md`
- `src/main/java/logratio/core/Registration.java`, lines 321-407, 500-580, 703-881 and 966-1155
- `src/main/java/logratio/PixelSelectionEngine.java`, lines 19-112
- `src/main/java/logratio/api/LogRatioRegistration.java`, lines 26-51 and 193-235
- `src/main/java/logratio/core/Warper.java`, lines 11-30 and 85-169
- `src/main/java/logratio/StackWarper.java`, complete public apply path
- `src/test/java/logratio/core/RegistrationGuardTest.java`, existing validation and warning cases
- `src/test/java/logratio/PixelSelectionEngineTest.java`, existing two-pass lifecycle cases

## Scope

- Branch `Registration` by the three rotation modes without altering off or continuous execution order.
- In known-events mode, run `EventRotationEstimator` after the movement source is resolved/preprocessed and before any shift-bound or translation fit.
- Treat event estimation as the first geometric pass, not as a writeable image-preprocessing step.
- Store the immutable `RotationEventResult` in the run context and final `Registration.Result`.
- Make automatic shift-bound estimation fit translation at each pair's prescribed relative angle.
- Run the ordinary reference pair plan with `alignAtFixedAngle`, using zero within segments and the accumulated relative angle across events.
- Reconcile and repair translations with exact frame angles and protected event boundaries.
- Reuse the same event-angle result in the selected-pixel pilot and raw refit; do not estimate angles twice from different pixel populations.
- Define rolling-reference event behaviour using the known absolute angle relative to the reference-coordinate template.
- Abort known-events mode with a clear diagnostic if any event has insufficient angular support. Do not silently expand to continuous rotation.
- Carry high-disagreement status as a visible warning and result diagnostic pending Stage 07 threshold evidence.
- Scale translations but never angles when reduced estimation resolution is used.
- Leave `StackWarper` as the only writer of corrected pixels and verify that all channels/Z planes receive the composed final poses once.
- Add progress phases for event-angle estimation, shift-bound translation, pair translation, reconciliation and repair.

## Out of scope

- Do not add or rename user controls and macro tokens; Stage 05 owns public surfaces.
- Do not implement Python behaviour; Stage 06 owns parity.
- Do not select defaults or claim performance improvement from development fixtures; Stage 07 owns promotion evidence.
- Do not introduce an intermediate rotated `ImagePlus`, TIFF or cached corrected stack.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/core/Registration.java` | MODIFY | Orchestrate event estimate, event-aware shift bound, fixed-angle pair plan and result diagnostics. |
| `src/main/java/logratio/PixelSelectionEngine.java` | MODIFY | Reuse one event trajectory through pilot and raw refit. |
| `src/main/java/logratio/api/LogRatioRegistration.java` | MODIFY | Resolve the event plan once at the prepared movement-source boundary if required by the final architecture. |
| `src/main/java/logratio/core/Registration.java` result types | MODIFY | Carry immutable per-event diagnostics in the existing result object. |
| `src/test/java/logratio/core/EventRotationRegistrationTest.java` | NEW | End-to-end core known-event trajectories, shift bound and reference strategies. |
| `src/test/java/logratio/PixelSelectionEngineTest.java` | MODIFY | Prove pilot/refit angle reuse and progress behaviour. |
| `src/test/java/logratio/core/RegistrationGuardTest.java` | MODIFY | Add validation, refusal and warning coverage. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Prove one final warp and every-channel/Z application. |

## Implementation sketch

Known-events pipeline:

```java
RotationEventResult rotations = EventRotationEstimator.estimate(
        source, eventFrames, eventWindow, options, eventProgress, cancellation);
rotations.requireUsable();

if (options.autoMaxShift) {
    options.aligner.maxShift = estimateShiftBoundAtKnownAngles(
            source, options, rotations.frameAngles, progress, cancellation)
            .suggestedMaxShift;
}

List<Observation> plan = Reconciler.planPairs(...);
for (Observation edge : plan) {
    double theta = rotations.frameAngles[edge.to] - rotations.frameAngles[edge.from];
    fit = PairAligner.alignAtFixedAngle(..., theta, ...);
}

Solution solution = reconcileTranslationsAtKnownAngles(...);
Result result = assembleWithKnownAnglesAndProtectedEvents(...);
```

The precise owner of `RotationEventResult` may be an immutable internal run context rather than mutable `Options`. It must be calculated once per logical registration and passed into a second pixel-selection fit unchanged.

Progress names must make the reduced angular work visible:

```text
Estimating rotation at known events
Estimating automatic shift bound at fixed angles
Aligning translations at fixed angles
Reconciling pair measurements
Repairing translation trajectory and calculating diagnostics
```

The final API remains:

```java
ImagePlus corrected = StackWarper.apply(
        image, registration.cumulative, interpolation, crop, progress, cancellation);
```

There is no earlier call that writes rotated pixels.

## Exit gate

1. `mvn -Dtest=logratio.core.EventRotationRegistrationTest,logratio.core.RegistrationGuardTest,logratio.PixelSelectionEngineTest,logratio.api.LogRatioRegistrationApiTest test` passes.
2. Known-events mode performs exactly one event-estimation pass in a two-pass pixel-selection run.
3. No auto-shift pair treats known rotational edge displacement as free translation.
4. All cumulative angles equal the event estimator's exact piecewise trajectory after reconciliation, repair and estimation-scale restoration.
5. A failed event estimate aborts before translation fitting and names the event frame and usable-pair count.
6. A high-disagreement event produces a visible warning and remains identifiable in the result.
7. Continuous and off modes do not enter any new event method; their existing regression outputs remain unchanged.
8. Consecutive, multi-lag, fixed and rolling strategies have deterministic known-event integration tests or are explicitly refused before pixel work with a documented reason. Silent partial support is not allowed.
9. A hyperstack test proves one composed pose is applied to every channel and Z plane at each timepoint.
10. Instrumentation or a test seam proves corrected pixels are produced by one final `StackWarper.apply`, not rotate-then-translate writes.
11. `mvn test` introduces no new failure.

## Known risks

- `PixelSelectionEngine` currently runs a complete pilot and refit. If event estimation is buried inside each `Registration.run`, it will be repeated and may disagree. Lift the immutable result to the logical run context.
- The translation-only auto-shift survey intentionally disabled rotation for speed. Known angles recover that speed without pretending the angle is zero; do not re-enable a global angular search there.
- A high-disagreement event may still produce a finite robust centre. Keep its warning attached through scaling, batching and serialization so it cannot look ordinarily successful.
- Rolling templates are already registered into reference coordinates. Apply the absolute frame angle relative to that template, not merely the last event delta.

