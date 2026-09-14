# Make Automatic selection capability-aware for rigid requests

## Why this stage exists

The frozen Automatic selector chooses `AREA_CORRELATION_NEWTON` for brightfield/DIC and fiducial/static images, and that estimator is translation-only until Stage 06. Rigid Automatic mode therefore needs a formal capability gate. It must use the bounded log-ratio fallback for those image types, state why, and remain gated even after correlation code exists until rotated validation passes in Stage 07.

## Prerequisites

- `01_bounded-logratio-rotation_COMPLETED.md`

## Read first

- `docs/rigid-registration/00_overview.md`
- `src/main/java/logratio/core/PairEstimator.java`, lines 43-80 and 80-315: estimator seam and shipped kinds.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 12-52: two-axis selector contract and retained image types.
- `src/main/java/logratio/api/AutomaticRegistrationSelector.java`, lines 191-249: selection, served-type shortcut and fallback.
- `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`, lines 95-190: generated candidate image types and estimators.
- `src/test/java/logratio/FullSelectorTraining.java`, lines 784-990: generated-model writer. Do not hand-edit output without changing this generator.
- `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java`, lines 96-292: fallback, served-type, replay and run-log gates.
- `README.md`, lines 45-59: measured per-image estimator choices.

## Scope

- Add an explicit estimator capability query for whether the engine can estimate rotation.
- Keep a separate Automatic-validation capability: implemented rotation is not automatically trusted by the frozen selector.
- Mark `LOG_RATIO_FIT` engine-capable after Stage 01; keep all area-correlation kinds unvalidated for rigid Automatic use until Stages 06-07.
- Extend the selector API with an explicit rigid-request argument while preserving the old translation-only entry points.
- Filter candidates for a rigid request before choosing the best gain.
- When no rigid-validated candidate serves an image type, return the complete category log-ratio recommendation with the rigid request preserved by the caller.
- Explain that the fallback occurred because the preferred automatic estimator is not yet validated for rotation.
- Ensure the served-image-type shortcut does not run a needless provisional selection pass when every retained candidate is ineligible.
- Make rigid-candidate validation metadata generator-owned so Stage 07 can populate it from evidence.
- Preserve existing translation-only candidate lists, predicted gains and explanations.
- Test all five image types in both translation and rigid selection modes.

## Out of scope

- This stage does not add `fitRotation` to `LogRatioParameters`; Stage 04 wires the selector's explicit rigid argument into the public Java configuration.
- This stage does not claim area correlation can rotate; Stage 06 implements it.
- This stage does not mark area correlation validated merely because Stage 06 passes unit tests; Stage 07 owns selector-level training and validation.
- Python Automatic behaviour belongs to Stage 05.
- Do not retrain or alter existing translation coefficients.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/logratio/core/PairEstimator.java` | MODIFY | Declare estimator engine capabilities without changing estimation behaviour. |
| `src/main/java/logratio/api/AutomaticRegistrationSelector.java` | MODIFY | Filter rigid requests and produce an explicit log-ratio fallback explanation. |
| `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java` | MODIFY | Regenerate model source with rigid-validation metadata while preserving translation coefficients. |
| `src/test/java/logratio/FullSelectorTraining.java` | MODIFY | Emit rigid-validation metadata; initially no correlation candidate is validated. |
| `src/test/java/logratio/api/AutomaticRegistrationSelectorTest.java` | MODIFY | Cover every image type, capability filtering, fallback explanation and translation stability. |
| `src/test/java/logratio/api/ModelConstantsAreReadAtRuntimeTest.java` | MODIFY | Include any new generated scalar/metadata contract that could otherwise go stale. |

## Implementation sketch

Separate implementation from evidence:

```java
interface PairEstimator {
    default boolean supportsRotation() { return false; }
}

LOG_RATIO_FIT(...) {
    @Override public boolean supportsRotation() { return true; }
}
```

Do not let Stage 06 flipping `supportsRotation()` immediately alter Automatic behaviour. The generated model needs candidate-level rigid validation metadata, initially false for the retained correlation candidates:

```java
static final boolean[] CANDIDATE_RIGID_VALIDATED = {
    false, // BRIGHTFIELD_DIC / AREA_CORRELATION_NEWTON
    false  // FIDUCIAL_STATIC / AREA_CORRELATION_NEWTON
};
```

The generator in `FullSelectorTraining` must emit that array. Translation-only predictions continue to use the existing candidates and coefficients exactly.

Preserve existing methods as translation shims and add explicit overloads:

```java
public static Result select(Evidence evidence, LogRatioParameters base) {
    return select(evidence, base, false);
}

public static Result select(Evidence evidence, LogRatioParameters base,
                            boolean rigidRequested) { ... }

public static boolean servesImageType(ImageType type, boolean rigidRequested) { ... }
```

A rigid candidate is eligible only when both are true:

```text
candidate.estimator.supportsRotation()
and AutomaticRegistrationSelectorModel says this candidate was rigid-validated
```

Until Stage 07, rigid requests for `BRIGHTFIELD_DIC` and `FIDUCIAL_STATIC` have no eligible override and therefore return the category recommendation. The explanation must name the declared type, preferred estimator and reason, for example:

```text
category log-ratio recommendation retained for rigid registration;
the automatic area-correlation candidate for BRIGHTFIELD_DIC is not yet validated for rotation
```

Do not describe this as an algorithm failure. It is a deliberate capability fallback.

## Exit gate

These are proposed gates for the temporary safe-fallback release; confirm them before accepting the stage.

1. `mvn -Dtest=logratio.api.AutomaticRegistrationSelectorTest,logratio.api.ModelConstantsAreReadAtRuntimeTest test` passes.
2. Translation-only Automatic selection remains `AREA_CORRELATION_NEWTON` for brightfield/DIC and fiducial/static with the same predicted gains as before.
3. A rigid request for those two types returns the category log-ratio recipe and a non-empty capability explanation.
4. Rigid requests for phase contrast, dense fluorescence and sparse/low-light fluorescence retain their category log-ratio recommendation without a spurious estimator switch.
5. No rigid request resolves to an estimator for which either engine support or selector validation is false.
6. The old selector method signatures retain translation-only behaviour.
7. Regenerating `AutomaticRegistrationSelectorModel.java` produces no coefficient drift in the translation branch.
8. `mvn test` passes.

## Known risks

- Conflating engine capability with selector validation would let Stage 06 change public Automatic behaviour before cross-image evidence exists. Keep the two gates distinct.
- Calling `.recommendation(...)` can reset recipe fields. Stage 04 must prove the rigid movement request and angular bound survive fallback resolution.
- The served-type shortcut can accidentally skip necessary measurement or run it twice. Test both served and declined paths.
- `AutomaticRegistrationSelectorModel.java` is generated. A hand edit that is not mirrored in `FullSelectorTraining` will disappear at the next retrain.
