# Preserve double precision until integer output is rounded

**Date**: 2026-09-07
**Files changed**: `src/experimental/java/ripr/core/LongitudinalReferenceWarper.java`
**Guard**: `src/experimental/java/ripr/core/LongitudinalReferenceWarperTest.java`

## What went wrong

The approved reference interpolates four source pixels in double precision, then
rounds once to save an unsigned integer image. A float-returning intermediate
sampler can move a result across a half-integer boundary before that rounding.
Identical movements can therefore produce different saved pixels. This guard
protects the experimental compatibility route; production is unchanged.

## The broken pattern

```java
float intermediate = (float) 100.49999999;
int saved = Math.round(intermediate); // 101: prematurely rounded to 100.5
```

## The fix

Keep the four weighted terms in double precision until final pixel conversion.

```java
double intermediate = 100.49999999;
int saved = (int) Math.floor(intermediate + 0.5); // 100
```

## Why it matters

Close movement values alone cannot establish identical output. A future speed
change must preserve this final rounding order as well as the alignment itself.
