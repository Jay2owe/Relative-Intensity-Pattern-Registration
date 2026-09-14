# Integrate the frozen selector in Java

## Why this stage exists

Stage 05 produces a validated decision, but users need it expressed through the real Java registration path with deterministic pilot measurement, safe eligibility checks, explicit fallback and reproducible settings. This stage installs exactly that frozen artifact without changing manual or recommended registration.

## Prerequisites

- `05_feature-model-training_COMPLETED.md`

## Read first

- `docs/recording-adaptive-selector/00_overview.md`, entire file.
- `docs/recording-adaptive-selector/selector_protocol.md`, entire file.
- `docs/recording-adaptive-selector/05_feature_model_findings.md`, entire file and its named artifact hashes.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 20-306 and 352-end: current result, prediction, fallback and feature-name contract.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`, entire file: generated model layout and current category rule.
- `src/main/java/logratio/api/LogRatioRegistration.java`, lines 27-105, 131-163 and 193-end: automatic resolution, neutral pilot, stamping and final estimation.
- `src/main/java/logratio/api/RegistrationRecipe.java`, lines 22-200 and 219-377: candidate contract and explicit parameter application.
- `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java`, entire file.
- `src/test/java/logratio/api/LogRatioRegistrationApiTest.java`, lines 221-275 plus rotation and untouched-input tests.
- `src/test/java/logratio/api/ModelConstantsAreReadAtRuntimeTest.java`, entire file.

## Scope

- Regenerate `AutomaticRegistrationSelectorModel.java` from the exact Stage 05 artifact; do not transcribe coefficients.
- Support the frozen adaptive, fixed or category-only model kind and its model/version metadata.
- Filter candidates by declared scope, transform request, estimator capability and separate rigid validation before scoring.
- Apply Stage 02 invalid/out-of-distribution fallback before standardisation or prediction.
- Update `servesImageType`/early-skip logic so it remains correct for a recording-adaptive model. A broad category may require evidence measurement even if the final result often falls back.
- Run the exact neutral provisional pass frozen in Stage 01 and expose its progress separately from final registration.
- Preserve every explicit non-recipe setting the API caller is allowed to retain, including channel, Z selection, reference, lags, movement bounds, threads, interpolation and crop.
- Return the chosen recipe as ordinary explicit `MANUAL` parameters with complete provenance and prediction/fallback explanation.
- Ensure final registration starts from the raw guide frames, not provisional processed pixels or provisional transforms unless the frozen protocol explicitly permits a warm start.
- Preserve manual and recommended results, translation-only compatibility and existing exact pixel-output contracts.
- Add deterministic unit/API tests for adaptive choice, confidence fallback, invalid evidence, out-of-distribution evidence, rigid eligibility and replay.

## Out of scope

- Do not change the Stage 05 model, coefficients, threshold or candidate list to make an integration fixture pass.
- Do not expose or rename controls in Fiji/macros; Stage 07 owns user surfaces.
- Do not add Python behaviour; Stage 08 owns parity.
- Do not open final untouched evidence; Stage 09 owns promotion.
- Do not change reconciliation weighting or output interpolation.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` | MODIFY, GENERATED | Install the exact frozen candidate model/fixed policy and provenance. |
| `src/main/java/logratio/api/AutomaticRegistrationSelector.java` | MODIFY | Apply evidence validity, eligibility, prediction, confidence and fallback deterministically. |
| `src/main/java/logratio/api/LogRatioRegistration.java` | MODIFY | Run the frozen neutral pilot only when required and resolve the final explicit recipe. |
| `src/main/java/logratio/api/RegistrationRecipe.java` | MODIFY only if required | Represent frozen candidate metadata without changing existing recipe identifiers. |
| `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java` | MODIFY | Cover model kinds, validity, prediction, fallback, eligibility, provenance and replay. |
| `src/test/java/logratio/api/LogRatioRegistrationApiTest.java` | MODIFY | Cover real two-pass resolution and untouched input/final estimation behaviour. |
| `src/test/java/logratio/api/ModelConstantsAreReadAtRuntimeTest.java` | MODIFY if generated scalars change | Prevent stale compiled readers of regenerated model values. |

## Implementation sketch

Keep the production flow explicit:

```java
category = completeCategoryRecommendation(requested);
eligible = model.eligibleCandidates(category.imageType, requested.fitRotation);

if (model.isFixedPolicy()) {
    return explicit(model.fixedOrCategoryPolicy(category));
}
if (eligible.isEmpty()) {
    return fallback(category, "no validated candidate serves this scope");
}

pilotParameters = neutralProvisionalBase(category, frozenPilotContract);
pilot = estimateResolved(rawImage, pilotParameters);
evidence = AutomaticRegistrationSelector.measure(rawGuideFrames, pilot, category);

if (!evidence.valid() || model.outOfDistribution(evidence)) {
    return fallback(category, evidence.reason());
}

prediction = model.predict(evidence.vector(), eligible);
if (!prediction.clearsFrozenConfidenceThreshold()) {
    return fallback(category, prediction.reason());
}
return explicit(prediction.recipe().applyTo(category));
```

This is structural pseudocode, not a demand to add these exact method names. Preserve the current immutable `Result` pattern where possible.

The explanation/provenance must make replay possible and distinguish:

```text
adaptive recipe selected: <recipe id>; predicted <target> ...; model <version>
fixed automatic policy: <recipe id>; model <version>
category recommendation retained: <recipe id>; reason <...>; model <version>
manual replay of resolved recipe: <recipe id>; no selector rerun
```

Generated scalar constants must continue to be read at run time or through a versioned model object. Do not restore bare compile-time literals that can be copied into stale callers.

## Exit gate

1. The generated model source hash matches the artifact named in Stage 05.
2. Candidate identities, feature order, contract version, coefficients/rules, confidence threshold, OOD metadata and training provenance match the frozen artifact exactly.
3. Invalid, non-finite, unsupported and out-of-distribution evidence returns the complete category fallback with an explicit reason.
4. A candidate without transform capability or separate rigid validation can never be selected.
5. Early pilot skipping changes no decision: it occurs only when the frozen model has no possible recording-dependent choice.
6. The pilot uses exactly the settings frozen in Stage 01 and the same evidence method used by training.
7. Resolved parameters are `MANUAL`, fully explicit, macro-replayable and do not rerun Automatic selection.
8. Manual and recommended fixtures reproduce their pre-stage parameters and transforms exactly.
9. The original image is not mutated, and final transforms are still applied once to all untouched channels and Z planes.
10. `mvn -Dtest=logratio.api.AutomaticRegistrationSelectorTest,logratio.api.LogRatioRegistrationApiTest,logratio.api.ModelConstantsAreReadAtRuntimeTest,logratio.api.RegistrationRecipeTest test` passes.
11. `mvn test` passes before handoff to Stage 07.

## Known risks

- A regenerated model can be correct while stale compiled callers retain old literal constants. Keep the runtime-read regression test.
- The current early skip assumes image type alone decides eligibility. That assumption is invalid if any recording in the category may receive an adaptive choice.
- The provisional pass can nearly double runtime. Preserve short-circuiting only where it is mathematically decision-neutral under the new model.
- Changing neutral-pilot settings breaks training/production parity even if the final estimator is unchanged.
- Reusing provisional transforms as a final warm start could change candidate behaviour. Do it only if the protocol and benchmark measured that exact workflow.
- A recipe object can carry inert settings under area correlation. Continue normalizing descriptions and IDs so provenance does not claim settings that had no effect.
