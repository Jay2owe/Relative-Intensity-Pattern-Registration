# Plain ImageJ stack treated as Z
**Date**: 2026-08-14
**Files changed**: `src/main/java/logratio/StackFrames.java`, `src/main/java/logratio/StackWarper.java`
**Guard**: `LogRatioRegistrationApiTest.returnsCorrectedImageWithoutMutatingInput`

## What went wrong
A plain multi-frame TIFF stack has no explicit ImageJ hyperstack dimensions. ImageJ reports that stack as one channel, several Z slices and one timepoint. The time-series adapters trusted those reported dimensions, so registration saw one frame and the corrected output contained one plane instead of the full recording.

## The broken pattern
```java
int slices = imp.getNSlices();       // equals the plain stack size
int frames = imp.getNFrames();       // equals one
boolean plain = channels == 1 && slices == 1; // false for every multi-plane stack
```

## The fix
When an image is not an explicit hyperstack and has one channel and one reported timepoint, the adapters now interpret its stack axis as time. Only an explicitly dimensional hyperstack can use that axis as Z.

## Why it matters
Most ordinary TIFF time series are plain stacks. Reintroducing this pattern makes the plugin appear to succeed while registering none of their movement.
