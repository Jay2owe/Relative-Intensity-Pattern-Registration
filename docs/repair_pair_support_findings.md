# The outlier guard refuses real knocks, and the pairs already know it

Run 2026-08-28 on the twelve demonstration-library entries, with
`scripts/probe_repair_pair_support.py`. Raw per-entry records in
`tmp/repair_probe/*.json`.

> **Superseded in part by `docs/repair_jump_motion_parity_findings.md`.** The
> project already had an answer to this problem: the Java recommendation sets
> `outlierMads = 0` when the caller declares intermittent-jump motion. The
> Python recommendation does not, which is a parity defect and is the first
> thing to fix. The change proposed below still matters for the default path,
> where no motion type is declared, and it recovers the exact declared
> trajectory on nineteen controlled-motion folders — but read that document
> first.

**This is not a sealed-set result and must not be quoted as one.** The
demonstration library is not held out from anything; it is the set these
recordings were chosen for. What follows is evidence that a change is worth
validating, and nothing stronger. No engine file was modified to produce it.

## What was found

`_repair` refuses a cumulative step whose magnitude exceeds
`max(median + 8 x robust_scale, 6 x median)` and replaces that frame's position
with the midpoint of its neighbours. It reads step magnitudes and nothing else.

On `06_knock` — a recording the library labels `KNOCK2`, with `maxShift` set to
60 px expressly to accommodate knocks — the stage moved 35.2 px in one step
between frames 46 and 47. Nine pairs span that step, each fitted on its own, and
all nine measured it: −33.4 to −35.6 px, with a median disagreement against the
reconciled trajectory of **0.036 px**. The guard's limit was 10.4 px, six times
the recording's own median step of 1.42 px. The step was refused, and frame 47
was moved to the midpoint of its neighbours — about 17.5 px from where every
pair says it belongs.

`library/README.md` reports the biggest step of this entry as 17.3 px. That is
the repaired half-step, not the movement the estimator measured.

## The change tested

Before refusing a step, ask the pairs that span it. A pair corroborates the raw
solution when its `influence.standardized_residual` — the root-mean-square
disagreement in pixels between that pair's own transform and the reconciled
trajectory, which the reconciliation already computes for every pair — is inside
a tolerance the recording sets itself:

    tolerance = max(1.0 px, 3.0 x median standardized residual over usable pairs)
    protect the step when >= 3 pairs span it and >= 75% of them are inside

Nothing else changes. The proposal can only ever *un-refuse* a step the shipped
rule flagged; it never refuses one the shipped rule kept.

**Neither rule was re-implemented.** `_repair` already takes a
`protected_event_frames` argument, so both answers are produced by the shipped
function and the only difference between the two runs is which flags it is
handed.

The four constants above were set once, before any entry was scored, and were
not tuned afterwards.

## The verdict

Scored with the library's own figure: mean per-pixel temporal standard deviation
inside the valid margin, each frame divided by its own median first so a
brightness change cannot flatter it. Both rules are scored on the **same**
margin — the wider of the two — so lower really is better. The implementation
reproduces `ValidationRun.meanSd`: on `06_knock` it returns raw 27.39, control
18.48 and shipped 11.04 against the 27.4, 18.5 and 11.0 published in
`library/RESULTS.md`.

| entry | steps refused | corroborated | frames moved | largest move | shipped | proposed | change |
|---|---|---|---|---|---|---|---|
| `01_jitter_mild` | 0 | — | 0 | — | — | — | unchanged |
| `02_jitter` | 0 | — | 0 | — | — | — | unchanged |
| `03_jitter_drift` | 0 | — | 0 | — | — | — | unchanged |
| `04_drift` | 0 | — | 0 | — | — | — | unchanged |
| `05_drift_dominant` | 0 | — | 0 | — | — | — | unchanged |
| `06_knock` | 1 | 1 | 1 | 17.47 px | 11.037 | 10.511 | **−4.76%** |
| `07_knock_drift` | 1 | 1 | 1 | 16.94 px | 11.185 | 10.328 | **−7.66%** |
| `08_knock_severe` | 1 | 0 | 0 | — | — | — | unchanged |
| `09_knock_extreme` | 1 | 1 | 1 | 77.18 px | 10.475 | 10.350 | −1.19% |
| `10_unresolved_methods_disagree` | 2 | 1 | 2 | 78.80 px | 12.938 | 12.679 | −2.00% |
| `11_unresolved_moving_artefact` | 0 | — | 0 | — | — | — | unchanged |
| `12_long_baseline_9d` | 4 | 1 | 1 | 34.23 px | 33.103 | 33.028 | −0.23% |

Ten steps refused across five entries; five of them corroborated. Seven entries
untouched. **No entry scored worse.** Five improved.

`10_unresolved_methods_disagree` moves two frames while protecting one: its
frame 9 stays refused, and un-refusing frame 8 changes the neighbours its
interpolation reads.

## The gate does discriminate

It is not a rubber stamp, and the entry that shows it is the one where the
estimator is documented to fail.

| entry | pairs agreeing | median residual | tolerance | outcome |
|---|---|---|---|---|
| `06_knock` (VID52) | 9 of 9 | 0.036 px | 1.00 px | protected |
| `07_knock_drift` (VID52) | 9 of 9 | 0.018 px | 1.00 px | protected |
| `08_knock_severe` (VID47) | 13 of 21 | 8.091 px | 8.78 px | **still refused** |
| `12_long_baseline_9d` frame 26 | 8 of 31 | 6.459 px | 3.93 px | **still refused** |
| `12_long_baseline_9d` frame 74 | 28 of 31 | 0.714 px | 3.93 px | protected |

VID47's poor localisability — the boundary the library exists to document —
shows up here as pair disagreement, and the gate declines. VID52's knocks show
up as near-perfect pair agreement, and the gate lets them through.

## What is not established

- **This is the demonstration library, not a held-out set.** The proper
  validation is the 40 sealed recordings in `library/benchmark`, which took no
  part in choosing anything. Until this has run there, it is a candidate.
- **Three of the five improvements are small** (−0.23% to −2.00%) and land on
  recordings the library already labels extreme or unresolved, where the
  absolute registration is poor either way — on `10` the shipped score of 12.938
  is *worse* than its own 12.90 fractional-shift control, which is the +0.7% the
  library already reports for that entry, so the proposal's −2.00% moves a
  number that was never a win. The two decisive results are `06_knock` and
  `07_knock_drift`, and both are VID52.
- **The tolerance is the parameter most likely to be wrong.** It reaches
  8.78 px on `08_knock_severe`, which is permissive; it declined there anyway,
  but not by a wide margin.
- **Agreement between the crossing pairs themselves is not part of the gate.**
  `12_long_baseline_9d` frame 74 was protected on 28-of-31 agreement while those
  31 pairs' own measured dx spanned 76.4 px. A second condition on that spread
  is the obvious refinement, and the evidence to design it is already in
  `tmp/repair_probe/*.json` — no re-run needed.

## What landing it would cost

`shifts.csv`, the `RESULTS.md` row and the four comparison panels change for
five of the twelve library entries, and any benchmark result that includes the
repair stage would need re-running. The movie in
`docs/figures/method-movie/` documents the current behaviour and its last act
would need rebuilding.
