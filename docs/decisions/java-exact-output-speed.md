# Java exact-output speed optimisation

Accepted implementation: Round 19 in the existing single-channel pulsing/low-light
tuning workspace. User authority: "go autonomously".

Only `ripr.core.LongitudinalRegistration` changed among the 57 main Java sources:

- Reuse the same sorted, sampled finite intensities for multiple percentiles.
- Compute the rotation-background median once per immutable image pair.
- Cache previously scored transforms within one refinement call. Keys include
  all three exact double values, including signed zero; there is no rounding.

Candidate order, strict tie-breaking, score arithmetic, sample stride, search
steps, iteration limits, reference selection, pulse/jump guards and settings stay
unchanged. The cache is bounded by the existing search caps (at most 521 scores).
The Automatic fixed recipe is not replaced or retrained.

Proof: 27 real test stacks (1286 frames) and 12 synthetic variants (136 frames)
have identical transforms, registered pixels and decisions to the fresh Round 18
Java baseline. All 545 Java tests pass, plus six repeat checks against the packaged
jar. Only one main source file differs; packaged main classes match the tested build.

Repeated short-video timings use 13.8% less time. One complete Incucyte recording
is effectively unchanged across valid timing pairs; the other uses 16.2% less time
in its awake comparison. A lid-triggered sleep invalidated one timing, which was
retained but excluded. No power settings were changed. Do not claim every recording
is faster or reuse historical Round 18 speedup numbers as this round's result.

Java source SHA256: BA3D80DE789F94EFB273674F795003FBD39D299AB5ED88702B50EBFE139DEFA2.
Jar: `target/r19-exact-speed/RelativeIntensityPatternRegistration-java-exact-speed.jar`.
Jar SHA256: F727DA672A35989B76936943DEEACD2507EBBFEA448832029B7921C7B28864D5.
Not deployed to Fiji. Python registration is unchanged and has not received these
optimisations; its earlier checks do not establish cross-language output parity.
