# Pixel mask and automatic movement pyramid mismatch
**Date**: 2026-08-17
**Files changed**: `src/main/java/logratio/PixelSelectionEngine.java`, `src/test/java/logratio/PixelSelectionEngineTest.java`
**Guard**: `PixelSelectionEngineTest.automaticMovementBoundIsResolvedBeforeSupportPyramidAllocation`

## What went wrong
Pixel-removal registration failed on a large fiducial-marker recording when automatic movement sizing was enabled. The pixel mask was allocated using the caller's original three-level image pyramid, then the refit measured a larger movement bound and required six levels. Registration stopped instead of producing a correction.

## The broken pattern
```java
int levels = options.aligner.levelsFor(width, height); // used the original movement bound
boolean[][][] support = buildSupport(levels);
Registration.refitWithSupport(source, options, pilot, support); // auto sizing changed levels here
```

## The fix
```java
Registration.Options rawRefit = prepareRawRefitOptions(source, options);
int levels = rawRefit.aligner.levelsFor(width, height);
boolean[][][] support = buildSupport(levels);
Registration.refitWithSupport(source, rawRefit, pilot, support);
```
The automatic movement bound is now resolved before support allocation and disabled after resolution so the refit uses the same scale count.

## Why it matters
If mask and registration pyramids have different scale counts, every pixel-removal strategy can fail on recordings whose measured movement exceeds the initial search bound.
