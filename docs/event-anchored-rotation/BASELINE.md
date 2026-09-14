# Accepted rotation baseline

Status: frozen for event-anchored rotation on 2026-08-26.

## Accepted dependency

- Verdict: `library/rigid_rotation_tuning/rounds/R01_rotation_reliability/verdict.md`.
- Owner decision: Jamie accepted the deployed A001 result after visual review on 2026-08-26.
- Production implementation: A001 lag-aware rigid warm starts, first present in commit
  `9df757036b32c1ced6ad431c3d7feaf88bb4ef4b`; event work starts from
  `1ad87600d9270846076b505613627e1b948793b7`.
- Solver path: the bounded log-ratio rigid estimator in `PairAligner`; adjacent pairs retain the
  accepted global search and longer multi-lag pairs may use the accepted provisional-trajectory
  warm start with exact global fallback.
- Scored evidence: `library/rigid_rotation_tuning/s4_score/r02_accepted_a001/out/scorecard.csv`
  (12 passes, 0 failures).
- Diagnosis: `library/rigid_rotation_tuning/s4_score/r02_accepted_a001/out/diagnosis.md`.
- Accepted clean injected-case limits: worst angular error 0.010631 degrees and worst centre
  translation error 0.019461 pixels, as recorded in the verdict.

## Permanent compatibility and parity gates

- Rotation-disabled execution bypasses A001 exactly.
- Existing `fitRotation(true)` / `fit_rotation=True` means continuous per-pair rotation.
- Java/Python rigid parity tolerances are 1e-4 pixels for translation, 2e-6 radians for angle,
  4e-6 log2 units for gain and 1e-8 for residual columns.
- Reproducible parity command after Java test compilation:

  `mvn test-compile; python -m pytest -q tests_python/test_java_parity.py`

Event-anchored rotation reuses this solver and these conventions. It does not retune the angular
objective or weaken any accepted gate.
