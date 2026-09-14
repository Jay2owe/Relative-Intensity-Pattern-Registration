# Expose rigid registration through Java, Fiji, macros and batches

## Why this stage exists

After Stages 01-03, the engine and Automatic safety policy exist but normal users still cannot request rotation. This stage makes rigid registration a replayable public movement model, carries the request through Automatic selection, and makes interpolation and bound behaviour visible in single-stack and folder-batch workflows.

## Prerequisites

- `01_bounded-logratio-rotation_COMPLETED.md`
- `02_rotation-trajectory-and-warp_COMPLETED.md`
- `03_selector-capability-fallback_COMPLETED.md`

## Read first

- `docs/rigid-registration/00_overview.md`
- `src/main/java/logratio/api/LogRatioParameters.java`, lines 20-140, 145-205 and 210-353: immutable parameters, validation, builder and core mapping.
- `src/main/java/logratio/api/LogRatioRegistration.java`, lines 31-92 and 108-160: Automatic resolution, provisional pass and estimation path.
- `src/main/java/logratio/LogRatioRegistrationPlugin.java`, lines 97-200 and 206-320: main and advanced dialogs.
- `src/main/java/logratio/LogRatioRegistrationPlugin.java`, lines 355-400: settings header and Automatic explanation.
- `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java`, lines 83-136: batch dialog and shared advanced settings.
- `src/main/java/logratio/MacroOptionsParser.java`, lines 20-75: macro parsing and defaults.
- `src/main/java/logratio/LogRatioDialogModel.java`, lines 20-70: replayable macro recording.
- `src/test/java/logratio/MacroOptionsParserTest.java`, lines 21-117.
- `src/test/java/logratio/api/LogRatioRegistrationApiTest.java`, lines 17-229.

## Scope

- Add an immutable public movement-model flag and maximum rotation in degrees to `LogRatioParameters` and its builder.
- Keep rigid registration off by default.
- Validate finite positive angular bounds only when rotation is enabled; document ignored values when disabled.
- Convert public degrees to core radians in `registrationOptions()`.
- Preserve the rotation request and angular bound through recommendations, `toBuilder()`, Automatic selection, explicit resolved settings and batch reuse.
- Pass the rigid request into Stage 03's selector capability overload.
- Ensure the neutral provisional pass used by Automatic selection estimates rotation when rigid mode is requested.
- Add a visible “Detect and correct rotation” control to the main single-stack and batch dialogs.
- Add the maximum rotation control to the advanced settings.
- Record and parse stable macro tokens for the movement model and angular bound.
- Include the movement model, angular bound and any Automatic fallback in run provenance.
- Report rotation-bound warnings and a concise maximum recovered absolute angle.
- Explain that nearest-neighbour rotation is available for label images but bilinear, bicubic or Fourier is recommended for intensity images; never silently rewrite an explicit interpolation setting.
- Keep all channels and Z slices driven by one timepoint transform.

## Out of scope

- Do not implement correlation rotation here; Stage 06 owns it.
- Do not remove the Stage 03 fallback based on core support alone; Stage 07 owns selector validation.
- Do not change recommendation recipes or frozen selector gains.
- Python parameters and command-line options belong to Stage 05.
- A new movement plot or full results-table user interface is outside this plan; use the existing result object, run log and warnings.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/api/LogRatioParameters.java` | MODIFY | Add public rigid fields, builder methods, validation and core mapping. |
| `src/main/java/logratio/api/LogRatioRegistration.java` | MODIFY | Carry rigid requirements through Automatic and provisional estimation. |
| `src/main/java/logratio/LogRatioRegistrationPlugin.java` | MODIFY | Add single-stack controls, help text, warnings and angle summary. |
| `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java` | MODIFY | Add the same movement-model control to folder batches. |
| `src/main/java/logratio/MacroOptionsParser.java` | MODIFY | Parse stable rigid movement and angular-bound tokens. |
| `src/main/java/logratio/LogRatioDialogModel.java` | MODIFY | Record the resolved rigid settings for exact replay. |
| `src/test/java/logratio/MacroOptionsParserTest.java` | MODIFY | Test defaults, explicit round-trip, invalid bounds and unknown tokens. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Test public rigid correction, Automatic fallback and hyperstack application. |

## Implementation sketch

Use degrees only in the public bundle:

```java
public final boolean fitRotation;
public final double maxRotationDegrees;

public static final class Builder {
    private boolean fitRotation = false;
    private double maxRotationDegrees = /* TODO: choose and document public default */;

    public Builder fitRotation(boolean value) { fitRotation = value; return this; }
    public Builder maxRotationDegrees(double value) {
        maxRotationDegrees = value;
        return this;
    }
}
```

Map once at the core boundary:

```java
options.aligner.fitRotation = fitRotation;
options.aligner.maxRotation = Math.toRadians(maxRotationDegrees);
```

Recommended and Automatic recipe application changes estimator/filter settings, not the requested movement class. Confirm `recommendation(...)`, `RegistrationRecipe.applyTo(...)` and `toBuilder()` preserve both rigid fields.

Pass the requirement into Stage 03:

```java
AutomaticRegistrationSelector.select(evidence, category, parameters.fitRotation);
AutomaticRegistrationSelector.servesImageType(category.imageType, parameters.fitRotation);
```

The neutral provisional base must remain log-ratio, all-pixel and unfiltered, but inherit `fitRotation` and the angular bound so motion evidence is not measured with the wrong movement class.

Use stable macro keys:

```text
fit_rotation | no_fit_rotation
max_rotation_degrees=<number>
```

`LogRatioDialogModel.toMacroOptions()` must always record the resolved movement model and bound, including after an Automatic fallback. Old macros without these tokens remain translation-only.

The UI wording should state the consequence, not expose `theta` jargon:

```text
Detect and correct rotation
Maximum rotation per compared frame (degrees)
```

When rigid mode and nearest-neighbour interpolation are both selected, emit a preflight/run-log warning such as:

```text
Rotation will use nearest-neighbour resampling. This preserves label values but can make intensity images jagged; choose bilinear, bicubic or Fourier for intensity data.
```

Do not auto-change `NONE` because nearest-neighbour is a legitimate explicit choice for masks and labels and silent changes break macro replay.

## Exit gate

These are proposed public-interface gates; confirm the unresolved public angular default before accepting the stage.

1. `mvn -Dtest=logratio.MacroOptionsParserTest,logratio.LogRatioBatchMacroOptionsParserTest,logratio.api.LogRatioRegistrationApiTest,logratio.api.AutomaticRegistrationSelectorTest test` passes.
2. A default parameter bundle remains translation-only and maps to `fitRotation = false` in the core.
3. An explicit rigid bundle round-trips through builder, macro recording/parsing and batch parameters with the same degree-valued bound.
4. An old macro without rigid tokens produces the same translation-only settings as before.
5. A public API fixture with combined translation and rotation is corrected in the expected direction without mutating the input.
6. The same rigid transform is applied to every channel and Z slice at a timepoint.
7. Rigid Automatic mode visibly falls back to log-ratio for brightfield/DIC and fiducial/static and records why; translation Automatic mode still chooses correlation for those types.
8. A result at the angular bound emits the rotation-specific warning in degrees.
9. Rigid nearest-neighbour use is warned about but not silently changed; bilinear/bicubic produce no such warning.
10. Run the plugin dialogs manually in Fiji: controls load, Automatic review shows the fallback, macro recording replays, and a corrected copy opens.
11. `mvn test` passes.

## Known risks

- Dialog field order and `getNext...()` order must remain identical. Add controls and parsing together, and run the real dialog once.
- Recommendation/application helpers may accidentally reset movement-model fields. Round-trip the exact object through every mode.
- Automatically changing interpolation would make recorded and actual settings disagree. Warn instead.
- The public default angular bound is still open. Record its rationale in code and documentation before completing this stage.
- Automatic resolution currently avoids a provisional pass for unserved image types. Ensure rigid fallback does not accidentally run two full registrations.
