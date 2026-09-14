# Match the approved correlation backup search

**Date**: 2026-09-07
**Files changed**: `src/experimental/java/ripr/core/compat/AreaCorrelation.java`, `src/experimental/java/ripr/core/LongitudinalReferenceArea.java`
**Guard**: `src/experimental/java/ripr/core/LongitudinalReferenceFallbackTest.java`

## What went wrong

When the preliminary enhanced-correlation fit could not accept a derivative
step, the experimental Java copy still used the original Java quadratic backup.
The approved Python reference instead tried a fixed, shrinking grid. On the
fluorescence control, the first difference appeared at frame 7 and left a
0.104915-pixel offset in later preliminary positions. This changed the winning
bright/dim reference fit and 26 of the 40 final review image panels.

## The broken pattern

```java
if (acceptedSteps == 0) return refine(a, b, start, maxShiftHere, o);
// refine fits a quadratic vertex: not the approved reference backup.
```

## The fix

Only the experimental translation enhanced-correlation fallback now delegates
to the reference-order grid: five row-ordered rounds, spacing reduced by four
when no better point is found. The final round index is retained exactly as in
Python. No production class or other estimator's backup is changed.

```java
if (acceptedSteps == 0) return referenceEccFallback(a, b, start, o);
```

The saved 40-frame preliminary check improves from a maximum disagreement of
0.104915 pixels to 1.96e-14 pixels. The regression test fails using the pre-fix
compiled classes and passes with the fix. Whole-video confirmation is separate.

## Why it matters

Matching ordinary correlation fits is insufficient: one different backup result
can change a later reference choice. Keep this fallback separate from other
estimators rather than silently replacing their existing behaviour.
