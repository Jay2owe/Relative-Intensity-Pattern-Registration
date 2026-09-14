# Expose adaptive selection through Fiji and replay surfaces

## Why this stage exists

The Java engine can make a recording-specific decision after Stage 06, but users must be able to understand, review, batch, record and replay that decision consistently. This stage updates the Fiji and macro-facing surfaces without adding a second interpretation of the selector.

## Prerequisites

- `06_java-selector-integration_COMPLETED.md`

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/05_feature_model_findings.md`, final model/fallback description.
- `src/main/java/logratio/LogRatioRegistrationPlugin.java`, lines 140-238 and 248-444: main controls, Automatic review, advanced explanation and resolver.
- `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java`, lines 85-160: batch image/motion/mode inputs and per-stack worker.
- `src/main/java/logratio/LogRatioDialogModel.java`, lines 13-end: macro serialization and explicit-versus-automatic fields.
- `src/main/java/logratio/MacroOptionsParser.java`, lines 26-155: selection-mode parsing, legacy compatibility and forbidden overrides.
- `src/main/java/logratio/LogRatioBatchMacroOptionsParser.java`, entire file.
- `src/test/java/logratio/SelectionModeTest.java`, entire file.
- `src/test/java/logratio/MacroOptionsParserTest.java`, lines 22-150.
- `src/test/java/logratio/LogRatioBatchMacroOptionsParserTest.java`, entire file.

## Scope

- Keep broad image type and motion type as the declared context roles frozen in Stage 01; update wording so users are not told they alone determine the recipe.
- Explain that Automatic mode measures the recording and may retain the category recommendation when confidence or support is insufficient.
- Continue showing the resolved recipe in the editable advanced-review dialog before an interactive run.
- Show selector model/version, chosen recipe or fallback, prediction/confidence summary and reason without dumping all feature values into the main dialog.
- Ensure an Automatic batch resolves each stack independently rather than freezing the first stack's recipe across the folder.
- Preserve macro recording of an Automatic request as `selection_mode=automatic` plus declared context and shared settings.
- Preserve recording/replay of a resolved recipe as complete explicit `selection_mode=manual` settings that do not rerun the selector.
- Preserve legacy `automatic_filters`, `recommended` and `manual` parsing behaviour.
- Reject contradictory explicit recipe fields in Automatic mode rather than guessing whether the user intended manual operation.
- Keep explicit channel, Z choice, rotation request, movement bound, interpolation and crop values honoured in Automatic mode.
- Update tests for wording-independent behaviour, per-stack batch resolution and exact macro round trips.

## Out of scope

- Do not retrain or change selector coefficients, thresholds, features or fallback.
- Do not expose individual feature values or model weights as ordinary user controls.
- Do not automatically infer or replace the declared image type; it remains context under the Stage 01 role.
- Do not change output interpolation or add its future selector here.
- Do not add Python behaviour; Stage 08 owns parity.
- Do not release or publish the plugin; Stage 09 owns the final promotion decision, and public release is a separate request.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/LogRatioRegistrationPlugin.java` | MODIFY | Clarify measured Automatic behaviour and show the resolved decision for review. |
| `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java` | MODIFY | Clarify and preserve independent per-stack selection. |
| `src/main/java/logratio/LogRatioDialogModel.java` | MODIFY | Serialize Automatic requests and explicit resolved recipes without ambiguity. |
| `src/main/java/logratio/MacroOptionsParser.java` | MODIFY if required | Preserve compatibility and reject contradictory Automatic overrides. |
| `src/main/java/logratio/LogRatioBatchMacroOptionsParser.java` | MODIFY if required | Preserve the same rules in batch macros. |
| `src/test/java/logratio/SelectionModeTest.java` | MODIFY | Pin per-stack selection, explicit replay and mode exclusivity. |
| `src/test/java/logratio/MacroOptionsParserTest.java` | MODIFY | Pin interactive macro compatibility and forbidden combinations. |
| `src/test/java/logratio/LogRatioBatchMacroOptionsParserTest.java` | MODIFY | Pin batch round trips and Automatic preservation. |

## Implementation sketch

Use one plain-language description near the mode control, for example:

```text
Automatic measures this recording and chooses among validated registration recipes.
Image and motion type provide context and safety limits. If the evidence is uncertain,
the category recommendation is retained.
```

The resolved review should summarize, not overwhelm:

```text
Automatic decision
Selected: <complete recipe description>
Reason: <predicted improvement/confidence or fallback reason>
Model: <version>
```

Automatic request macro:

```text
image_type=... motion_type=... selection_mode=automatic channel=... slice=...
fit_rotation=... max_rotation_degrees=... interpolation=... crop=...
```

Resolved replay macro:

```text
selection_mode=manual estimator=... support=... floor=... ceiling=...
preprocessing=... pixel_selection=... mask_preprocessing=...
```

The existing serializer decides which explicit fields are meaningful under each estimator. Do not record inert log-ratio support/mask settings as though they affected area correlation.

For batch operation, retain the Automatic request in the batch parameters and call the image-aware resolver separately for each opened stack. Never replace the batch configuration with the first resolved manual recipe.

## Exit gate

1. The main Fiji dialog says that Automatic measures the recording and may fall back; it does not claim image type alone chooses the method.
2. Interactive Automatic resolution always shows the final editable recipe and explanation before registration.
3. An Automatic batch resolves every input independently and records each stack's own provenance.
4. An Automatic macro records `selection_mode=automatic` and does not embed one selected recipe as the batch-wide decision.
5. A resolved manual macro reproduces the explicit recipe without a provisional selector pass.
6. Legacy mode tokens still parse to the same authoritative selection modes.
7. Contradictory Automatic plus explicit-recipe fields fail with a clear error.
8. Explicit channel, Z, rotation, bound, interpolation and crop settings survive Automatic resolution.
9. `mvn -Dtest=logratio.SelectionModeTest,logratio.MacroOptionsParserTest,logratio.LogRatioBatchMacroOptionsParserTest,logratio.api.AutomaticRegistrationSelectorTest test` passes.
10. `mvn test` passes.

## Known risks

- Showing every feature and prediction can make a conservative selector look more certain than it is. Keep the user-facing explanation brief and preserve the detailed run log for audit.
- Converting resolved settings back to `AUTOMATIC` during dialog loading would cause a second, potentially different decision. Resolved values must remain manual/editable.
- Batch code can accidentally resolve once before opening individual images. Test heterogeneous stacks and per-stack provenance.
- Macro compatibility is public behaviour. Keep old tokens and serialized enum names unless a separately tested migration is provided.
- Area-correlation recipes have inert support and mask fields. Avoid misleading macro/report text while retaining parser compatibility.
