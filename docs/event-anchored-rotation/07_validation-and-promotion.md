# Validate event mode and promote only the supported behaviour

## Why this stage exists

Constraining angles should reduce computation and prevent angular noise, but the constraint can be wrong or poorly estimated on real changing samples. This stage freezes fresh evidence, compares event mode against the accepted continuous baseline, and decides whether the proposed window and warning behaviour are safe to expose as documented defaults.

## Prerequisites

- `05_java-api-fiji-ui-and-macros_COMPLETED.md`
- `06_python-parity-and-command-line_COMPLETED.md`
- Fresh event-annotated recordings that were not used to tune the accepted rotation engine have been identified, or promotion remains blocked.

## Read first

- `docs/event-anchored-rotation/00_overview.md`
- `docs/event-anchored-rotation/BASELINE.md`
- All `_COMPLETED.md` files in this folder
- `library/rigid_rotation_tuning/rounds/R01_rotation_reliability/plan.md`, lines 98-138
- `library/rigid_rotation_tuning/s4_score/r00_a000/out/diagnosis.md`, lines 1-3
- `docs/thevenaz_protocol_findings.md`, lines 64-83
- `src/test/java/logratio/RigidRotationA001Grid.java`, lines 28-152
- `src/test/java/logratio/RigidRotationStackA000.java`, measurement and output sections located before editing
- `papers/_scout/event-anchored-piecewise-constant-rotation/REPORT.md`

## Scope

- Declare and hash all synthetic and real inputs before comparing event-window variants.
- Keep development, validation and final test sources independent by recording, not merely by frame subdivision.
- Include zero, one and multiple events; positive and negative jumps; translation drift; remount translation; gain/fade; noise; sparse signal; biological change; bad frames; edge-truncated windows and unidentifiable rotation.
- Compare at least: accepted continuous rotation, event mode with one boundary pair, event mode with the proposed three-by-three consensus, and translation-only as a negative control where truth contains rotation.
- Count global angular searches, fixed-angle translation fits, runtime, peak memory and pair statuses.
- Measure incremental event-angle error, cumulative segment-angle error, centre translation error, general warping index, retained crop and corrected-image similarity.
- Report medians, upper tails, worst cases and failure counts; do not promote from medians alone.
- Bootstrap frames within each side of an event when estimating uncertainty; do not bootstrap pair rows as independent observations.
- Review event overlays and corrected movies at full resolution for every worst case.
- Confirm that continuous/off modes remain unchanged and that known-event output contains no within-segment angular jitter.
- Select or reject the proposed three-frame window using development data only, then freeze it before validation/test.
- Decide from fresh evidence whether high spread is warning-only or a hard refusal and record the threshold if promoted.
- Write a durable findings report, commands, input manifest, environment and verdict.
- Promote event mode as opt-in only if every gate passes. Do not make it the global default in this plan.

## Out of scope

- Do not retune the accepted underlying rigid solver.
- Do not train or alter the automatic estimator selector from this event-mode dataset.
- Do not add Fourier-template, pose-graph or manual-angle variants unless the predeclared all-pairs candidate fails and a new separately approved round is opened.
- Do not claim correction of out-of-plane tilt, Z displacement or deformation.
- Do not reopen spent rotation-tuning test sources to select event thresholds.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/EventAnchoredRotationBenchmark.java` | NEW | Run the frozen Java event-mode comparison and emit trial-level evidence. |
| `src/test/java/logratio/EventAnchoredRotationBenchmarkTest.java` | NEW | Guard manifests, arm equality, counts and promotion gates. |
| `scripts/run_event_anchored_rotation_benchmark.ps1` | NEW | Reproduce the full frozen comparison with progress and failure handling. |
| `docs/event-anchored-rotation/validation_protocol.md` | NEW | Record hypotheses, split, inputs, metrics, gates and commands before the run. |
| `docs/event-anchored-rotation/validation_findings.md` | NEW | Record complete results, failures, visual review and verdict. |
| `README.md` | MODIFY | Replace provisional wording only with claims and defaults supported by the frozen result. |
| `README_PYTHON.md` | MODIFY | Keep Python claims/defaults aligned with the Java verdict. |
| `TODO: new unspent evidence folder under library/` | NEW | Stage executor must select a durable path after inspecting current evidence-store conventions; record it in `validation_protocol.md` before writing outputs. |

## Implementation sketch

The protocol must freeze an arm table before running validation:

```text
CONTINUOUS_ACCEPTED       accepted tuned continuous rotation
EVENT_SINGLE_PAIR         e-1 against e only
EVENT_CONSENSUS_W3        up to 3 pre x 3 post pairs, robust consensus
TRANSLATION_ONLY_CONTROL  rotation disabled
```

For a 78-frame default multi-lag recording, record the planned-pair baseline explicitly:

```text
(78-1) + (78-2) + (78-4) + (78-8) + (78-16) = 359 planned pairs
```

With two interior events and window three, the event-angle candidate cap is:

```text
2 events * 3 pre frames * 3 post frames = 18 global angular fits
```

This is an angular-search-count expectation, not a claim of 20-fold total runtime improvement. Translation, preprocessing, shift-bound and I/O remain.

Proposed gates to confirm in `validation_protocol.md` before looking at final test results:

1. Clean event angles and translations meet the accepted tuned rigid gates from `BASELINE.md`.
2. Event consensus has fewer angular failures than the single-pair arm on development and no worse failure count than continuous rotation on validation/test.
3. No catastrophic error above the accepted tuned worst-case limit is introduced.
4. Within every segment, angle standard deviation is exactly zero by construction and the stored doubles are identical.
5. Event mode reduces global angular-search count according to the declared complexity and has lower median real-stack runtime than continuous rotation; hard ceiling is no slower than continuous rotation.
6. Median accuracy may not worsen beyond the accepted easy-case allowance; upper-tail and worst error may not worsen.
7. Gain/noise conditions do not increase refusals, bound hits or low-support events beyond the predeclared allowance.
8. `OFF` and `CONTINUOUS` Java/Python transforms, statuses and corrected pixels retain their permanent regression results.
9. All event settings and diagnostics reproduce from Java macro, Java API and Python CLI logs.
10. No promotion occurs without at least one independent real recording containing a documented remount event.

If a proposed gate conflicts with the final accepted rotation-tuning gate, use the stricter already-accepted gate or pause for explicit review; never weaken a permanent gate silently.

## Exit gate

1. `validation_protocol.md` was committed or otherwise frozen before final validation/test outputs were inspected.
2. Every input has a source identifier, event annotation, split assignment and SHA-256 hash.
3. Every arm uses identical source pixels, event truth, regions, interpolation and scoring code.
4. The benchmark and manifest guard test pass reproducibly from `scripts/run_event_anchored_rotation_benchmark.ps1`.
5. Java and Python parity passes on the promoted configuration.
6. All proposed gates are reported in one table with pass/fail values; no missing row is treated as a pass.
7. Worst cases have saved full-resolution overlays or comparison stacks and a written review.
8. `validation_findings.md` names the accepted window, spread behaviour, limitations and opt-in status, or explicitly rejects promotion.
9. README claims match the frozen findings and do not generalize beyond in-plane remount rotation plus translation.
10. `mvn test` and `pytest -q` pass after documentation/default promotion.

## Known risks

- Real event annotations may be unavailable. Synthetic success is not enough to promote a user-facing default; record the evidence gap and leave the mode experimental.
- Development selection can overfit window size to one recording. Split by independent source recording and freeze before validation.
- Fewer angular searches do not imply proportionally lower total runtime. Report phase timings and absolute wall time.
- Biological change can make distant cross-event pairs disagree for legitimate reasons. Inspect temporal-distance effects and prefer a shorter window rather than tuning the robust estimator on final test cases.
- A 2D projection can hide out-of-plane remounting. Flag such cases as outside model scope rather than scoring them as ordinary failures.

