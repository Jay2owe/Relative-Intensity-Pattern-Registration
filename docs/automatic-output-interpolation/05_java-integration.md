# Integrate automatic output interpolation in Java and Fiji

## Why this stage exists

Stage 04 produces either a validated selector or a safe fixed-policy result, but it does not change user output. This stage adds a separate post-transform interpolation decision to the Java workflow while preserving explicit manual interpolation, exact label values, original-stack isolation and complete provenance.

## Prerequisites

- `04_selector-headroom_COMPLETED.md`

## Read first

- `docs/automatic-output-interpolation/00_overview.md`, entire file.
- `docs/automatic-output-interpolation/resampling_protocol.md`, API names, modes, roles, thresholds and fallback.
- `docs/automatic-output-interpolation/04_selector_findings.md`, final integration decision and artifact hashes.
- `src/main/java/logratio/core/Warper.java`, lines 14-205: candidate semantics, exact block copy, margins and transform geometry.
- `src/main/java/logratio/StackWarper.java`, lines 19-166: application to all channels/Z planes, crop and clamping.
- `src/main/java/logratio/api/LogRatioParameters.java`, lines 20-175 and 225-365: immutable parameters, defaults, validation and builder.
- `src/main/java/logratio/api/LogRatioRegistration.java`, lines 20-55 and 193-end: transform estimation and the single final warp call.
- `src/main/java/logratio/api/LogRatioResult.java`, entire file: corrected output, resolved parameters and automatic-recipe result.
- `src/main/java/logratio/LogRatioRegistrationPlugin.java`, lines 140-238 and 248-370: Fiji main/advanced controls and resolved settings.
- `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java`, lines 85-160: batch output controls.
- `src/main/java/logratio/MacroOptionsParser.java`, lines 26-155, and `src/main/java/logratio/LogRatioDialogModel.java`, lines 13-end: macro parse/record contracts.
- `src/test/java/logratio/core/WarperTest.java`, entire file.
- `src/test/java/logratio/api/LogRatioRegistrationApiTest.java`, lines 18-105 and 221-275.
- `src/test/java/logratio/MacroOptionsParserTest.java`, lines 40-130.

## Scope

- Choose and record Java API class/enum filenames from the names frozen in Stage 01; no canonical output-selector types currently exist.
- Add a separate output-interpolation selection mode and declared data role without overloading the existing registration-recipe `SelectionMode`.
- Preserve existing callers/macros as manual interpolation with their explicit `Warper.Interpolation`, including the current `NONE` default.
- Install the exact Stage 04 adaptive or fixed-policy artifact mechanically; do not hand-tune it during integration.
- Resolve output interpolation only after `Registration.Result.cumulative` is complete and immediately before `StackWarper.apply`.
- Apply hard safeguards before model/rule prediction: labels/masks use `NONE`; exact/effectively whole-pixel translations use the frozen `NONE` rule; unspecified/mixed roles follow the frozen refusal/fallback.
- Use actual cumulative transform geometry and only the truth-free source evidence frozen in Stage 01.
- Apply one selected interpolation to every original channel and Z plane at each time point.
- Keep crop enablement separate while using the selected interpolation to calculate the required valid margin.
- Store the final decision, selected interpolation, fallback/override reason, geometry summary and model/policy version in `LogRatioResult` and batch/run provenance.
- Convert the returned parameters to an explicit replayable manual interpolation choice after resolution, without rerunning the selector during replay.
- Add Fiji and batch controls for data role and manual/automatic output interpolation, with clear warnings for labels and mixed semantics.
- Add macro tokens using the names frozen in Stage 01 while preserving existing `interpolation=NONE|BILINEAR|BICUBIC|FOURIER` compatibility.
- Add tests for hard rules, manual authority, after-transform ordering, hyperstack consistency, refusal/fallback, crop margin and replay.

## Out of scope

- Do not change transform estimation, pair selection, reconciliation, repair or the registration-recipe selector.
- Do not change the mathematics of `NONE`, bilinear, bicubic or Fourier warping.
- Do not select a different interpolation per channel or Z plane; mixed-semantics splitting is outside the approved first implementation.
- Do not retrain the Stage 04 artifact.
- Do not implement Python; Stage 06 owns parity.
- Do not open final untouched evidence; Stage 07 owns promotion.
- Do not publish, deploy or upload the plugin.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/api/<API-type-named-in-resampling_protocol.md>.java` | NEW | Hold data role, output-selection mode and/or decision contract using the frozen names. |
| `src/main/java/logratio/api/<TODO-output-interpolation-selector>.java` | NEW | Apply hard rules, fixed/model policy, confidence fallback and provenance. |
| `src/main/java/logratio/api/LogRatioParameters.java` | MODIFY | Carry declared data role, output-selection mode and explicit interpolation compatibly. |
| `src/main/java/logratio/api/LogRatioRegistration.java` | MODIFY | Resolve interpolation after final transforms and before one warp of the original stack. |
| `src/main/java/logratio/api/LogRatioResult.java` | MODIFY | Expose the output-interpolation decision separately from registration-recipe selection. |
| `src/main/java/logratio/LogRatioRegistrationPlugin.java` | MODIFY | Add role/mode controls, warnings and resolved-decision review. |
| `src/main/java/logratio/LogRatioRegistrationBatchPlugin.java` | MODIFY | Add matching per-stack batch controls. |
| `src/main/java/logratio/MacroOptionsParser.java` | MODIFY | Parse new role/mode tokens while preserving explicit interpolation names. |
| `src/main/java/logratio/LogRatioDialogModel.java` | MODIFY | Record Automatic requests and explicit resolved replay correctly. |
| `src/main/java/logratio/api/LogRatioBatch.java` | MODIFY if required | Record per-stack selected interpolation and policy provenance. |
| `src/test/java/logratio/core/WarperTest.java` | MODIFY | Pin threshold boundary, exact block copy and selected-margin behaviour if new helpers are added. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Cover after-transform selection, hard rules, all-layer application and replay. |
| `src/test/java/logratio/MacroOptionsParserTest.java` | MODIFY | Cover compatibility, new tokens and contradictory options. |
| `src/test/java/logratio/SelectionModeTest.java` | MODIFY | Prove registration-recipe and output-interpolation modes remain independent. |

This stage exceeds the usual eight-file guideline because one frozen API decision must propagate mechanically through parameters, result, Fiji, batch and macro surfaces. Do not split scientific decisions across those files; keep production logic centralized in the selector class and make the remaining changes thin adapters.

## Implementation sketch

Keep registration-recipe and output-interpolation modes independent. The exact names come from `resampling_protocol.md`, but the shape is:

```java
enum OutputDataRole {
    QUANTITATIVE_INTENSITY,
    VISUAL_INTENSITY,
    LABELS_MASKS
}

enum OutputInterpolationMode {
    MANUAL,
    AUTOMATIC
}

final class OutputInterpolationDecision {
    Warper.Interpolation selected;
    boolean fallback;
    String reason;
    String policyVersion;
    String geometrySummary;
}
```

Use the final transforms, not the requested motion label:

```java
Registration.Result registration = estimateResolved(image, resolvedRecipe, ...);

OutputInterpolationDecision outputDecision =
        requested.outputInterpolationMode == MANUAL
                ? manualDecision(requested.interpolation)
                : outputSelector.select(image, registration.cumulative, requested);

ImagePlus corrected = StackWarper.apply(
        image, registration.cumulative,
        outputDecision.selected, requested.crop, progress, cancellation);
```

Hard-rule ordering:

```text
1. manual mode -> explicit interpolation exactly
2. automatic + missing/mixed role -> frozen refusal/fallback
3. LABELS_MASKS -> NONE
4. effectively whole-pixel translation with no meaningful rotation -> NONE
5. fixed policy or validated adaptive prediction
6. low confidence/OOD -> frozen fixed fallback or explicit refusal
```

An Automatic request macro should record role/mode, not the selected candidate. A resolved replay should record manual mode plus the selected serialized interpolation and decision provenance, so it does not depend on a later model version.

## Exit gate

1. Existing parameter builders and macros with no new fields remain manual and reproduce their previous interpolation exactly.
2. Manual output interpolation is authoritative in recommended, automatic-recipe and manual registration modes.
3. Output selection runs after cumulative transforms are finalized and never changes or re-estimates them.
4. Labels/masks always use `NONE`, and byte/short category values remain exact on the valid region.
5. Exact/effectively whole-pixel translation follows the frozen `NONE` rule and preserves the bit-exact block-copy path.
6. Missing or mixed data roles follow the explicit frozen refusal/fallback and never infer stack semantics from the guide channel.
7. One decision is applied to every channel and Z plane of the untouched original stack.
8. Crop enablement is unchanged; the selected candidate determines only the valid margin required when crop is enabled.
9. `LogRatioResult`, batch results and run logs report mode, role, selected interpolation, reason, geometry and policy/model version.
10. Automatic request macros resolve per recording; explicit resolved macros replay without selector execution.
11. Serialized `NONE`, `BILINEAR`, `BICUBIC` and `FOURIER` remain compatible.
12. `mvn -Dtest=logratio.core.WarperTest,logratio.api.LogRatioRegistrationApiTest,logratio.MacroOptionsParserTest,logratio.SelectionModeTest test` passes.
13. `mvn test` passes.

## Known risks

- Reusing the existing `SelectionMode` would couple recipe selection to output interpolation and prevent independent manual overrides.
- Resolving interpolation before registration would use requested rather than actual transform geometry.
- Converting the whole parameter bundle to manual after recipe selection can accidentally erase an unresolved output Automatic request. Keep the two modes separate through the transform stage.
- The current warper applies one interpolation across all C/Z planes. A wrong role declaration affects the entire stack, so warnings and refusal must be clear.
- Macro recording can accidentally freeze one batch stack's selected interpolation across every input. Preserve Automatic requests for batch replay.
- Bicubic/Fourier clamping is downstream of float warping. Integration must not claim overshoot-free output merely because stored integers were clipped.
