# Exact Java throughput trials — 7 September 2026

User request:

> is there any way it can be sped up futher with the same output

> try all of them

Implementation lives under src/experimental/java/ripr/core, not the installed plugin. LongitudinalExecutionPolicy independently controls four frame workers, immutable reference spectra and pair-owned native buffers. The original three-argument estimate entry point keeps one worker with both caches off. The bundled A031 runtime, public plugin defaults and all existing TIFF reviews remain unchanged.

Reference bridge construction, candidate order, tie handling, each native fit, reduction order, frame-order diagnostics, reference selection, pulse repair and jump guards are unchanged. Parallel tasks only write their own frame slot; all workers are joined before shared native images close. Each worker disables its own OpenCL preference. Native fit buffers reset seed and mask on every attempt, including after failure.

Tuning contract and evidence: the existing single_channel_pulsing_lowlight_registration_tuning workspace beside the PySCNSlice test set, rounds/R21_exact_java_throughput. A000 freezes the matched A031 Java package and the 24-case A023 output identities before the edits. It does not substitute the old Round 19 native implementation for the user's approved Round 17 target.

The candidate build is s1_prepare_review_inputs/r21_b001_candidate_build. Six focused tests cover all combinations, one/two/four workers, failed-fit recovery, deterministic diagnostics and cancellation cleanup. The pair lattice is r21_p001; the complete-clip screen continuation is s001; the gated complete-set continuation is f002, followed by packaging continuation k002. Two additional native contract tests passed separately. These labels are experiments, not newly accepted image recipes.

Use the new code/run_r21_speed.py and code/run_r21_inherited.py harnesses for current experimental sources. Old Round 20 source lists predate the new execution-policy classes; their frozen run artifacts remain the reproducibility evidence. The already-running old native-reference and perturbation controllers were not edited, interrupted or redirected.

No speed promotion on pair probes. The fastest exact pair candidate advances to five entire 40-frame clips, then the 24-clip exact-output check and inherited controls. Complete-set continuation waits for the older native and synthetic gates; it records a hold if those gates fail. Package integration and controlled timing remain later requirements, not claimed complete here. Completed and active state are recorded separately; consult run.json and continuation status.json, not historical narrative, for the current outcome.

Native implementation facts checked against the exact upstream version:

- https://raw.githubusercontent.com/opencv/opencv/4.10.0/modules/video/src/ecc.cpp — fitting prepares separate floating-point working images from the input matrices. Reuse still requires exact failed-seed and repeated-fit tests.
- https://raw.githubusercontent.com/opencv/opencv/4.10.0/modules/core/src/ocl.cpp — OpenCL use is held in native thread-local state, so setting it on the calling thread alone is insufficient for new frame workers.

This round makes no new scale or intensity-invariance claim and reduces no scientific parameter or precision setting. Its claim is identical output at lower implementation cost, scoped only to gates that actually pass.
