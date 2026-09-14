# The Python recommendation does not disable the outlier guard for declared jumps

Run 2026-08-28 with `scripts/probe_repair_controlled_motion.py`. Per-folder
records in `tmp/repair_controlled_motion/*.json`. Follows from
`docs/repair_pair_support_findings.md`, whose conclusion this supersedes.

**Read only `benchmarks/controlled_motion/`**, which
`docs/external_parameter_sweep_sealed_set_declaration.md` names as the
development set. `locked_test`, `sealed_test` and `sealed_test_3` were not read,
scored or probed. No engine file was modified.

## Fixed 2026-08-29

`src/ripr/parameters.py` now resolves the setting the way Java does, in
`outlier_mads_for(motion_type)`, applied from `RegistrationRecipe.apply` so
that every path into a recommendation goes through it: `recommended(...)`,
`resolve()` on a recommended selection, and the automatic selector's category
step. Verified equal to Java across all twenty image-type by motion-type
combinations.

The gap that let it through is closed too.
`tests_python/test_java_parity.py::test_recommendation_resolves_outlier_repair_exactly_as_java_does`
reads the rule out of the Java source, which is the authority for it, and
checks Python resolves to the same numbers for every declaration a caller can
make. It needs no Java toolchain, so it runs wherever the repository is
checked out, and it fails if either side is changed alone. Confirmed to fail
against the old behaviour before being kept.

Nothing else in the recommendation layer diverges: `reference`,
`auto_max_shift` and the pilot's own settings already matched.

## The defect

Java, `RelativeIntensityPatternParameters.java:534`, with its own comment
stating the reason:

```java
// A discontinuous-motion declaration means large steps are expected evidence, not
// corrupt observations. Keep repair of unsupported frames, but do not smooth genuine
// remount/stage jumps merely because they differ from the quiet parts of the recording.
outlierMads = motion == MotionType.INTERMITTENT_JUMPS ? 0.0 : 6.0;
```

Python, `LogRatioParameters.recommended(...)`, for the same four declarations:

| motion type declared | Java `outlierMads` | Python `outlier_mads` |
|---|---|---|
| `curved_oscillating_drift` | 6.0 | 6.0 |
| `steady_directional_drift` | 6.0 | 6.0 |
| `subpixel_random_walk` | 6.0 | 6.0 |
| **`intermittent_jumps`** | **0.0** | **6.0** |

`README_PYTHON.md` maps `RelativeIntensityPatternRecommendations.forTypes(...)`
onto `ripr.recommendation(...)` as equivalents. For jump motion they are not.
`tests_python/test_java_parity.py` compares the engine on an analytic fixture
and does not reach the recommendation layer, so nothing catches it.

## What it costs, measured against declared truth

`benchmarks/controlled_motion/*/INTERMITTENT_JUMPS/CLEAN/` is a declared camera
path — slow drift interrupted by three abrupt stage jumps, injected at four
times resolution and box-averaged down, so the movement is exact and belongs to
no method. Twenty folders, four series in each of five image types. The jumps
are 6.25 px against a median step of 0.25 px, so the guard's `6 x median` limit
of about 1.5 px refuses **all three of them, in every folder**.

Reference is the published arm `01_log_ratio_tukey_standard_gradient`, whose own
median error against the injected truth is recorded per folder; it is a proxy
for truth and its error is given below so it cannot be mistaken for truth.

| | worst departure from the published arm, over 19 folders |
|---|---|
| guard off (what Java produces for this declaration) | **≤ 0.008 px** |
| guard on at 6.0 (what Python produces for this declaration) | **3.19 – 3.24 px** |
| the pair-agreement gate from the companion document | **≤ 0.008 px** |

Every one of the nineteen behaves the same way: three declared jumps, three
refused, 3.2 px of trajectory error, and both the Java setting and the proposed
gate recover the correct answer exactly. The published arm's own median error
is below 0.05 px on seventeen of them.

The published benchmark figures are guard-off figures. That is consistent with
`RigidSelectorFactorialBenchmark.java:899`, which sets `outlierMads(0.0)`
explicitly for `INTERMITTENT_JUMPS`; a run of the shipped Python pipeline on the
same inputs, with the same declaration, does not reproduce them.

### The folder that is excluded, and why

`SPARSE_LOWLIGHT/sparse_ssbd_fig3a` is left out of the table. My run does not
reproduce the published arm there **with the guard off** — 8.1 px median, 17.0 px
worst — so the folder cannot report anything about the guard. It is a recording
where the estimator itself struggles: the published arm's own median error
against truth is 0.3458 px, thirty times the well-behaved folders, and its step
pattern differs from the other nineteen (median 1.11 px, biggest 23.9 px,
against 0.25 px and 6.25 px elsewhere).

The proposed gate declined to protect it: 7 of 27 crossing pairs agreed, median
residual 7.76 px. That is the correct call, and it is the second time the gate
has refused precisely where the estimator is unreliable.

## What this means for the change proposed in the companion document

The companion document proposed making `_repair` consult pair agreement. That
work stands, and the controlled-motion set is stronger evidence for it than the
demonstration library was — it recovers the exact declared trajectory on all
nineteen usable folders. But it is no longer the first thing to do, because the
project already had an answer to this problem and Python simply does not carry
it.

| | fixes | leaves open |
|---|---|---|
| **Port the Java rule** — `outlier_mads = 0` for `intermittent_jumps` in the Python recommendation | the parity defect; any user who declares jump motion | anyone who does not declare it, including every default-options caller |
| **Pair-agreement gate in `_repair`** | both, without a declaration | needs its own validation before it can ship |
| **Both** | — | the gate makes the declaration a belt-and-braces measure rather than the only defence |

The default path is not hypothetical. `ValidationRun` constructs
`new Registration.Options()` directly, never touching the recommendation layer,
which is why `library/06_knock` has a real 35.2 px knock refused and one frame
left 17.5 px out, and why `library/README.md` reports that entry's biggest step
as the repaired 17.3 px half-step.

## What is still not established

- The pair-agreement gate has still not been run on a held-out set. Nothing
  here changes that; `controlled_motion` is the development set.
- The gate's four constants remain untuned, and its tolerance still has no
  condition on agreement *between* the crossing pairs.
- Whether the jumps in `controlled_motion` are representative of real remounts
  is a separate question. They are 6.25 px; `06_knock`'s is 35.2 px.
