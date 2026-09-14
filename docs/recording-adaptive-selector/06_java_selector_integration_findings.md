# Java selector integration findings

The Java API now treats Automatic, Recommended and Manual as exclusive selection modes. Automatic resolves through `AutomaticRegistrationSelector`. The installed user-approved fixed model holds one candidate per image type, so `LogRatioRegistration` skips the provisional registration and applies that deterministic recipe.

The result provenance includes model version, feature contract, protocol hash, candidate-manifest hash, model-artifact hash, selected recipe and fallback. Continuous rotation remains a separate translation-then-incremental-rotation decision and is preserved when the selector falls back.

The generated production source is `src/main/java/logratio/api/AutomaticRegistrationSelectorModel.java`. Installed override SHA-256: `a7c9c643f13c836d3add51b401d07cf1bd99481e25ba74fe7ea4670063ee54b1`.

Runtime-read initializers prevent Java compile-time constant inlining when the generated model is replaced.
