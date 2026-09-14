# Private native runtime experiment

This is a self-contained **experimental API artifact**, not an installed plugin,
new menu item, replacement for Automatic mode, or public release. No GUI opens.

## Immutable inputs and boundaries

- Core artifact: the frozen Java exact-speed JAR, SHA-256
  `f727da672a35989b76936943deeacd2507ebbfea448832029b7921c7b28864d5`.
  Its project coordinate is
  `io.github.jay2owe:RelativeIntensityPatternRegistration:0.1.0`; the hash, not that
  unreleased version string, identifies the actual experimental input.
- Source/runtime namespaces inside the private engine: `ripr.*`. The three
  numerical compatibility classes replace only their private counterparts.
- Host-facing experimental adapter: `ripr.isolated.NativeLongitudinalRuntime`.
  Existing `ripr.api.*` entry points, plugin descriptor and defaults are unchanged.
- Private destination: nested `ripr-native/*.jar` resources loaded by an exclusive
  child classloader. No engine or Bytedeco classes appear on the host classpath.
  Native Java class names are deliberately **not renamed**, because their native
  bindings and resources depend on those names. Missing private classes fail;
  the loader cannot substitute another plugin's version.
- The shared host provides `net.imagej:ij`; it is not bundled. ImageJ objects,
  primitive arrays and standard Java collections cross the private boundary.
- Runtime: Windows x86-64, Java 11 or newer. OpenCV 4.10.0 / JavaCPP 1.5.11 /
  OpenBLAS 0.3.28; six retained runtime archives are individually hash-pinned.
- Input: one already-selected channel and Z plane, full native resolution, first
  frame as the coordinate origin. Output application is bilinear, fill zero,
  original pixel type, no crop. Other channels are never inspected.
- RIPR declares BSD 3-Clause. Its exact licence text is reused from existing Git
  blob `0c8226e0620165dfd140992fdd0fdc8bec8d1a81` on the prior local public-candidate
  branch; the live checkout has no root licence file. No new licence choice is
  made. Version-pinned upstream top-level notices are included unchanged.

```text
Host ImageJ + experimental adapter
    -> private engine classloader
        -> frozen core + checked numerical corrections
        -> private OpenCV / JavaCPP / OpenBLAS
    <- movements or corrected ImageJ stack
```

Runtime archives are verified before loading and extracted to a short temporary
path, not beside experimental images. No Python process, environment variable,
system path, installed JAR or native-cache setting is changed.

Private Java classloading does not guarantee separate native global state on
Windows. The real-host coexistence check found that the initial worker left
another caller's thread/OpenCL configuration changed. A031 saves and restores
these settings in a finally block while retaining the verified one-thread,
non-OpenCL registration calculation. Both real loading orders pass after success,
runtime inspection and an exception; the same guard fails on the old package.
The wrapper serializes its own native entry points. Concurrent unrelated native
operations are not covered by this experimental API's guarantees.

## Required gates before any production integration

1. Complete A023 image and movement preservation gate, including exact native and
   float output bytes against the matched A022 Java implementation.
2. Packaged-only loading with ImageJ but no external engine/native classpath.
   Check actual native execution and intentional conflicting host classes.
3. Packaged full-video movement/output comparison, not merely class loading.
4. Later native brightfield and Incucyte controls against fresh frozen reference.
5. Preserve existing public API parameter semantics, cancellation, progress,
   selected-channel handling and output metadata in a future production adapter.
6. Complete native third-party/redistribution audit, clean-source/clean-cache
   build and CI integration, supported-platform policy and host acceptance.

The initial builder consumes the retained local frozen core artifact. The
separate A026 all-source rebuild removes that executable build dependency:
72 Java engine sources compile to 200 byte-identical classes with only ImageJ
and the six pinned runtime dependencies on the compiler classpath. The outer
adapter and complete packaged JAR also reproduce byte-for-byte. Source snapshots,
compiler arguments and comparisons are retained in
s1_prepare_review_inputs/r20_a026_source_rebuild under the tuning folder.

This is source reproducibility, not yet a clean-cache Maven/CI release build.
The full 24-video Java preservation gate, packaged-only native loading and two
complete packaged video comparisons have passed. The later brightfield control
also passes against its fresh frozen reference. Incucyte controls, perturbation
gates, production adapter semantics, redistribution audit and host acceptance
remain separate gates; none is waived by archive equality.

Latest archive: s1_prepare_review_inputs/r20_a031_isolated_runtime_package,
31,187,083 bytes, SHA-256
9395a6848934bd84af211783c54255ceac384bc65b0616c505627b14a10e8345.
The A031 all-source rebuild is byte-identical to this archive. A package-member
comparison proves all 199 other engine classes, the outer adapter and the six
native/runtime archives are unchanged. Its new complete phase-contrast and
difficult fluorescence image checks both pass all movements and native/float
frames exactly.
