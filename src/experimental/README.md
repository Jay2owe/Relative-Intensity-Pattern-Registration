# Round 20: matching the approved registration in Java

This folder is an experimental compatibility implementation, not the installed
plugin and not part of the normal Maven source tree. Production defaults and
the Java build dependencies have not been changed.

The approved target is the user's Round 17 review, computed with Python.
Round 19 proved preservation of the separate Round 18 Java baseline only.

## Latest result

- The matched A022 Java calculation passes all 24 original review-image gates:
  960/960 image panels and 960/960 native uint16 replay frames are exact. Saved
  decimal movement and float32 rounded-history differences remain documented.
- A023 (the exact Gaussian traversal speed-up) keeps each pixel's sum unchanged
  but processes neighbouring pixels together. All 562 Java tests pass. Its
  additional 24-video gate is complete: every movement file and all 960 native
  plus 960 float output frames are byte-identical to matched Java attempt A022.
- Sequential Java-only phase-contrast timing, one cold plus two warm repeats:
  warm median 64.297 s -> 48.899 s (24% less time). Repeated movements are
  bit-identical. This is one clip, not a speed claim for all image types.
- The experimental self-contained Windows Java runtime is 31,186,603 bytes.
  It passes native loading with only ImageJ provided, including a deliberately
  conflicting host engine. Complete phase-contrast and difficult fluorescence
  runs each preserve all movements and 40 native plus 40 float frames exactly.
- A026 (the all-source rebuild) compiles all 72 engine sources without the old
  compiled core on the compiler classpath. All 200 engine classes, the adapter
  and the complete packaged JAR are byte-identical to the tested package.
- The later difficult microglia brightfield control matches a fresh frozen
  reference: 40/40 native and 40/40 float frames exact, including the guarded
  rigid jump at frame 21. This is not historical Round 17 evidence. The two full
  143-frame Incucyte controls remain in progress.
- All 24 updated review TIFFs are registered and verified by actual ImageJ
  decoding: 960 pages, 22 panels each, all original image panels unchanged.
  Java label/time and page headings alone change. External outputs are reused;
  the mixed-run panel times are explicitly not a controlled speed comparison.
- The runtime is an experimental API artifact, not a menu command or installed
  replacement. See [the packaging contract](PACKAGING_CONTRACT.md). Other public
  API modes and settings are unchanged; no Python registration is called.
- A031 (native caller-setting preservation) corrects the packaged wrapper only.
  Real host OpenCV loading exposed shared native thread/OpenCL settings despite
  private Java classloaders. Both loading orders now preserve those settings
  after inspection, successful estimation and an exception. The exact same
  strengthened guard fails on the old package. All registration/warping classes
  and all native archives are byte-identical; only the wrapper class changes.
  The revised package is 31,187,083 bytes and reproduces exactly from source.
  Its complete phase-contrast and difficult fluorescence packaged rechecks
  both preserve every movement and native/float output frame. This does not guarantee concurrent unrelated
  plugins cannot change native settings during an operation.

The chronology below retains earlier failed/partial checkpoints. Statements
about work being in flight there describe those earlier points, not this latest
result. Remaining Incucyte controls, perturbations and production integration
remain pending. The updated reviews fulfil the user's explicit request to
update labels/times when images match; they do not promote a production method.

## What runs in Java

```text
Real single-channel input
  -> declared image/motion Automatic recipe
       + three isolated reference-precision compatibility classes
  -> emission: native OpenCV bright/dim reference fitting
     OR transmitted light: median landmark reference + spline area correlation
  -> reference-order pulse/jump guard
  -> same-channel endpoint/rare rigid-jump verification
  -> native-type bilinear output + saved review comparison
```

Python drives the immutable test stages and supplies frozen reference evidence.
It is not called by the Java registration classes. Candidate stages may reuse
their own Java-produced checkpoints; frozen Python starting positions are
explicitly labelled diagnostic inputs, never treated as Java estimates.

## Important boundaries

- `core/compat/LogPlane.java`, `core/compat/PairAligner.java` and
  `core/compat/AreaCorrelation.java` have the same
  package/class names as production. Compile them **instead of**, not alongside,
  their production counterparts. Diagnostic classpaths load them before the
  frozen Java jar. They are not an approved change to the public Automatic route.
- LogPlane keeps its original float-returning methods for binary compatibility;
  the experimental PairAligner uses added double-returning sampling methods.
- `OpenCvLongitudinalOps` provides native Gaussian filtering, phase seeds and
  enhanced-correlation fitting. `OpenCvLongitudinalTrajectory` implements the
  emission bright/dim stage, including the reference-order matrix composition
  and confidence rules.
- `LongitudinalReferenceTrajectoryRepair` implements the pulse/jump guard.
- `LongitudinalReferenceLandmarks`, `LongitudinalReferenceArea` and
  `LongitudinalReferenceLandmarkTrajectory` retain the reference's transmitted-
  light algorithm. It is not a substitution with OpenCV's fitting function.
- `LongitudinalReferencePhase`, `LongitudinalReferenceRigid` and
  `LongitudinalReferenceEndpoint` implement rare-jump checks in reference order.
- `LongitudinalReferenceRegistration` is now one complete experimental Java
  entry point, with no Python callback. `LongitudinalReferenceWarper` preserves
  double precision until final native-type conversion. `LongitudinalFullProbe`
  records all stages and raw numerical output for independently repeatable gates.
- Complete route execution is not full-set acceptance. In particular, the
  terminal rigid-chain acceptance branch still needs positive full-video evidence.
- No controlled complete-method speed gain, complete 24-video agreement or
  production promotion has been established. No new review TIFFs are warranted yet.

## Verified checkpoint, 7 September 2026

- Reduced Windows library archives: 66,310,316 -> 30,645,139 bytes, preserving
  every retained file byte. This includes JavaCPP and OpenBLAS, not just OpenCV.
  Windows-only; this is not a cross-platform release package.
- Native image/pair fixtures pass across three emission clips.
- Five pyramid levels and 5,329 valid sampled coordinates from a real frame
  match frozen reference arithmetic exactly.
- Actual Java preliminary alignment of the 40-frame per2_a2 bioluminescence clip
  differs from the frozen reference by at most 8.6e-14 pixels.
- The same fix matches the 40-frame transmitted_a1 phase-contrast preliminary
  to 1.4e-14 pixels. All 44 landmark/reference array fixtures and 861 subsequent
  window-array fixtures are exact; all 40 pair fits differ by at most 1.2e-14 px.
- All 237 subsequent OpenCV fit calls match exactly; the guarded output matches
  every saved Round 17 transform at its original 12-significant-digit precision.
  This is a movement-value comparison, not TIFF byte equality.
- Two real microglia brightfield rigid fits (adjacent frames and independent
  median blocks) match translation and rotation exactly. Windowed phase peaks
  differ by at most 1.2e-15, without changing either fit.
- All 1,286 frame centroids, areas, masks and rejection decisions across all
  27 real clips match. Six endpoint decision fixtures and 20 morphology fixtures
  match. These include both full 143-frame Incucyte controls, but are primitive
  checks, not full Incucyte registration acceptance.
- Two complete Java runs: transmitted_a1 and per2_a2. Both reproduce all 40
  native unsigned-16-bit frames when the saved Round 17 movements are replayed
  through the frozen output calculation. Java output warping itself matches on
  all 80 native/float comparisons per clip when given identical movements.
- Every image panel in both actual saved Round 17 review TIFFs matches a
  reconstruction using the newly estimated Java movements: 80/80 panels total,
  zero changed image pixels. Labels and TIFF-container bytes are excluded.
- Saved movement strings match 39/40 frames for transmitted_a1 and 40/40 for
  per2_a2. The saved reference was rounded to 12 significant digits. Float32
  native-size replay differs in two pixels per clip, while the actual Java
  warper matches the frozen reference at identical movements. This distinction
  is retained; no movement tolerance or pixel-equality gate was relaxed.
- 22 pulse/jump fixtures and all 558 Java tests pass with the experimental
  precision classes (549 inherited tests plus 9 experimental guards).
- Initial complete estimation times: 54.7 s phase contrast, 276.2 s bioluminescence.
  These are not controlled timing comparisons; the latter overlapped unit tests.

### Screen completion: broader benchmark held

The second bioluminescence screen, per2_b3, also matches all 40 original review
panels and native uint16 replay frames. Across these three clips, 120/120 review
panels match. The fluorescence control syngabasnfr_b3 does **not** pass: only
14/40 original review panels match (49,698 changed pixels). Its maximum movement
disagreement is 0.00166884 px, with a small angular difference on many frames.
Native warping at identical movements and branch decisions still match, so the
remaining problem is in estimation, not output conversion. A preliminary-only
frozen Python probe is locating the first difference. The 24-clip benchmark,
production promotion, packaging and new reviews are held pending that fix.

The first discrepancy was subsequently isolated to frame 7 of the preliminary:
the enhanced-correlation backup still fitted a quadratic vertex instead of the
approved shrinking grid. The isolated A022 fix reduces the 40-frame preliminary
disagreement from 0.1049 px to 1.953e-14 px. Its new guard fails with old classes
and passes with the correction. Final fluorescence confirmation and the updated
full test suite are in flight; this preliminary result does not erase A020's
recorded failure or establish complete-video agreement yet.

A022 complete confirmation subsequently passed: the fluorescence control now
matches all 40 actual original review panels and native uint16 replay frames,
and phase contrast remains exact. All 559 Java tests pass. The bounded 24-video
diagnostic is now running with three workers, first checking all four screens
on the same A022 sources and stopping expansion on an image-gate failure. This
does not erase retained decimal-movement differences or constitute promotion.

Evidence lives in the sibling PySCNSlice test-set tuning workspace:
`single_channel_pulsing_lowlight_registration_tuning/rounds/R20_match_approved_round17`.
Its checkpoint and append-only attempts ledger identify exact immutable runs,
hashes, failed attempts, timings, limitations and the next gates.

Do not deploy, change defaults or advertise a speed-up from this experimental
folder. Its retained third-party dependencies still need a redistribution audit.
