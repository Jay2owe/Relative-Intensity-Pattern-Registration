# Event-anchored rotation implementation

Status: implemented as an experimental opt-in mode on 2026-08-26. Promotion is blocked by the fresh
real-recording requirement in `07_validation-and-promotion.md`.

## Implemented contract

- `OFF`, `CONTINUOUS` and `KNOWN_EVENTS` modes in Java and Python.
- Existing `fitRotation(true)` and `fit_rotation=True` still mean continuous rotation.
- Public events are one-based first-post-remount frames and convert once to zero-based core indices.
- Event lists are defensive, unique, ascending and at least frame 2; stack bounds are checked at run time.
- All available pre-event × post-event fits are evaluated within the temporal window.
- Refused, non-finite and angular-bound fits are excluded; at least three usable fits are required.
- Circular median plus Huber consensus returns incremental event angles, exact cumulative segment angles,
  support counts, inlier counts, circular spread, contributing frame ranges and status.
- Ordinary graph pairs search translation globally at their prescribed relative angle.
- Reconciliation and repair preserve the supplied angular trajectory exactly; declared remount steps are
  protected from generic step-outlier repair.
- Event angles are estimated once and reused by automatic shift-bound, pilot and pixel-selection refit
  paths. Final correction still warps untouched source pixels once and applies each pose to every channel
  and Z plane at that timepoint.
- Fiji dialogs, macros, Java API, batch reports, Python API and Python command line expose the same mode,
  event list, window and diagnostics.

## Verification

Commands run from the repository root:

```powershell
mvn test
mvn test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$env:PYTHONPATH='src'
python -m pytest -q
```

Permanent event tests cover indexing, validation, one and several events, gain change, truncated support,
fixed-angle fitting, exact within-segment angles, fixed-reference rebasing, protected repair, pilot/refit
reuse, macros and Java/Python parity.

The event-angle parity gate remains 2e-6 radians. The fixed-angle Java/Python translation fixture needs a
6e-4-pixel tolerance (measured maximum 4.85e-4 pixels) because Java scalar and NumPy vector reductions
take slightly different paths before the overdetermined translation graph is solved. The actual synthetic
translation-recovery tests in each implementation use a 0.04-pixel accuracy gate.

## Deliberately not promoted

- Known-event rotation is not selected automatically and is not the global default.
- The three-frame window and high-disagreement warning remain provisional.
- No spread-based refusal threshold has been introduced.
- Only in-plane rotation plus translation is modelled; out-of-plane tilt, Z movement and deformation are
  outside scope.
