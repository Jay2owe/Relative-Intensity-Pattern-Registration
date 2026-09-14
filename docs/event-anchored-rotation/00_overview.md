# Event-anchored rotation for longitudinal recordings

## End goal

Add an optional rotation mode for recordings in which the sample orientation changes only after known removal and replacement events. The user supplies the first frame after each event; the plugin estimates one robust angular jump from several frames around that boundary, holds the resulting angle constant until the next event, then estimates translation with those angles fixed. The original pixels are transformed only once, after rotation and translation have been composed.

This work is deliberately scheduled after the current rigid-rotation tuning. It must reuse the accepted tuned rotation engine and its frozen accuracy conventions rather than creating a competing solver while tuning is still active.

## Why we're doing this

The current rigid workflow searches rotation for every planned frame pair. That is appropriate when orientation can drift continuously, but it spends substantial work and allows angular noise to accumulate when the real acquisition has only a few remount events. A single noisy before/after pair is also too fragile for dim or biologically changing recordings.

Event-anchored rotation lets the acquisition history become a hard constraint: angles may change at supplied event frames and nowhere else. Several cross-event frame pairs provide redundancy, while the later translation pass retains the existing multi-lag protection against drift.

## Architecture overview

```text
resolved movement channel and estimation preprocessing
                         |
                         v
user event frames -> robust cross-event rigid fits -> one angular jump per event
                                                        |
                                                        v
                              exact piecewise-constant angle trajectory
                                                        |
                                                        v
ordinary pair plan -> translation fits at fixed relative angles -> reconciliation
                                                        |
                                                        v
                                  composed rigid pose per timepoint
                                                        |
                                                        v
                         one final warp of untouched channels and Z planes
```

An event frame is one-based in public controls and means **the first frame after the sample was replaced**. If events are `25,51`, frames 1-24 share the initial angle, frames 25-50 share the first accumulated event angle, and frames 51 onward share the second accumulated event angle. The core converts these to zero-based indices.

## Blocking prerequisite

Do not execute Stage 01 until all of the following are true:

- The active work under `library/rigid_rotation_tuning/` has a final, accepted verdict rather than `No tuning verdict yet`.
- The accepted rotation implementation has been promoted into production Java and Python code.
- Its permanent regression tests and Java/Python parity checks pass.
- The accepted commit and evidence locations can be recorded without reopening or retuning on spent validation material.

If those conditions are not met, this folder remains a plan only.

## Stage map

| NN | Name | One-line goal | Rough size | Depends on |
|---:|---|---|---|---|
| 01 | post-tuning contract and fixtures | Freeze the accepted rotation baseline, event semantics and backward-compatible data contract. | 1 day | accepted rotation-tuning verdict and promotion |
| 02 | robust event-angle estimator | Estimate one well-diagnosed angular jump per supplied event from several cross-boundary pairs. | 1-2 days | 01 |
| 03 | fixed-angle translation and reconciliation | Fit and repair translations without allowing the known piecewise angle trajectory to change. | 1-2 days | 01, 02 |
| 04 | Java pipeline integration | Run event-angle estimation once as the first geometric pass and reuse it through shift-bound, pilot and refit paths. | 1-2 days | 02, 03 |
| 05 | Java API, Fiji UI and macros | Expose replayable event controls and diagnostics while preserving every old rotation invocation. | 1-2 days | 04 |
| 06 | Python parity and command line | Reproduce the Java event model, diagnostics and corrected pixels in Python. | 1-2 days | 04, 05 |
| 07 | validation and promotion | Compare event mode with continuous rotation, freeze evidence and enable only the behaviour the gates support. | 1-2 days plus benchmark wall time | 05, 06 |

## House rules

- The accepted post-tuning rotation solver is the only angular engine. Do not copy, fork or independently retune it here.
- `OFF` and continuous per-pair rotation must preserve their existing transforms, statuses, recorded options and corrected pixels. Old `fit_rotation` macros remain valid and mean continuous rotation.
- Public event frames are sorted, unique, one-based first-post-remount frames. Frame 1 is not an event because it has no pre-event side.
- Angles are radians in core code and degrees in user-facing fields and reports.
- Event mode is a hard model: the cumulative angle is exactly constant inside a segment. Translation remains free per frame.
- Estimate angles before translation, but never write an intermediate rotated stack. Compose the final rigid poses and resample the untouched input once.
- A pair crossing an event uses the prescribed relative angle while fitting translation and gain. A pair inside one segment uses exactly zero relative angle.
- Known event boundaries are protected from generic step-outlier repair. A real remount translation must not be mistaken for a corrupt step and interpolated away.
- Low support, angular-bound fits and cross-pair disagreement are reported explicitly. Do not silently switch to continuous rotation or manufacture an event angle.
- Rebase the piecewise angle trajectory correctly when a fixed reference frame other than frame 1 is used.
- Apply every accepted pose to all channels and Z planes at that timepoint; estimate movement only from the chosen channel/projection.
- The automatic shift-bound pass must account for prescribed event angles, or rotational edge motion can masquerade as translation.
- Do not use a post-fit residual alone as an accuracy oracle. Consensus, support, bounds and held-out truth determine acceptance.
- Do not reuse locked or spent test material for tuning thresholds. Identify fresh event-annotated recordings before promotion.
- Java and Python event frames, signs, transform order, confidence fields, failure rules and serialized options must agree.
- Event mode remains opt-in until Stage 07 passes. It never becomes the default merely because it is implemented.
- Preserve unrelated working-tree changes.

## Known open questions

- The accepted rotation-tuning verdict may change the pair solver interface, confidence measurements or angular bound semantics. Stage 01 must adapt this plan to the promoted code without weakening these event-model constraints.
- Three frames on each side and all cross-products (up to nine pairs) are the proposed first defaults. Stage 07 must confirm or reject them on development data before any public default is promoted.
- A high angle spread should initially produce a visible warning. A hard spread-based refusal threshold may be promoted only from fresh validation evidence.
- The first release uses the accepted log-ratio rigid estimator for event angles. Whether a separately validated rigid correlation estimator may replace it is outside this plan unless the completed rotation tuning explicitly promotes one.
- Remounting can also introduce out-of-plane tilt or Z displacement. This feature models in-plane rotation plus translation only and must say so.

## How to run a stage

After the blocking prerequisite is satisfied, run `/do-step docs/event-anchored-rotation/` to execute the lowest-numbered incomplete stage.

