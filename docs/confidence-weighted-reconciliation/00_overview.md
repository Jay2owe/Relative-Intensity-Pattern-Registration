# Confidence-weighted and robust frame-pair reconciliation

## End goal

Replace the assumption that every successful frame-pair measurement is equally reliable with a reconciliation system that can use estimated movement uncertainty and reduce the influence of pairs that contradict the wider frame graph. The system must support translation and the finalized rigid model of horizontal movement, vertical movement and in-plane rotation. Users should receive the most accurate validated trajectory together with visible per-pair confidence and downweighting diagnostics, while the present equal-weight solve remains available as a reproducible baseline and fallback.

## Why we're doing this

Multi-lag registration deliberately measures overlapping routes through a recording, but the current global solve gives every successful pair the same formal influence. A precise, distinctive match therefore counts the same as a broad or ambiguous match. One incorrect pair can pull the final trajectory even when its own internal matching score appears convincing.

The two pair estimators already calculate local curvature information while refining movement, and the multi-lag graph provides a second source of evidence: whether one pair agrees with the other routes through time. Combining those signals could improve trajectory accuracy without abandoning the redundancy that makes multi-lag registration useful. It is not guaranteed to improve every image category, so equal weighting must remain the production behaviour until held-out tests show that another strategy is safer and more accurate.

Implementation starts only after the rotation engine is stable. Pair uncertainty, reconciliation and diagnostics all touch the same contracts currently being finalized for rotation. This plan does not need to wait for new data to unblock rigid Automatic-selector retraining; the documented rigid log-ratio fallback may remain in place. It does require the rotation estimator, transform convention, multi-frame equations, warping and fallback behaviour to be frozen and passing their tests.

## Architecture overview

For a measurement from frame `i` to frame `j`, the graph residual is the difference between the movement predicted by the cumulative trajectory and the movement measured directly for that pair. The present solve minimizes the unweighted sum of squared residuals. The proposed solve can instead minimize:

```text
sum over pairs of: robust_factor(i,j) * residual(i,j)^T
                   * information_matrix(i,j) * residual(i,j)
```

The information matrix is the inverse of estimated movement uncertainty. It should be `2 x 2` for translation and `3 x 3` for translation plus rotation, allowing a pair to be informative in one direction but weak in another. The robust factor starts at full influence and is reduced when a pair is unusually inconsistent with the wider graph. If the benchmark shows that no single weighting strategy is safest across recordings, a separate post-pair selector may choose among the validated strategies from pair uncertainty and graph evidence. The final flow is:

```text
pair estimator
    -> transform + uncertainty evidence + ordinary quality diagnostics
    -> equal provisional trajectory and graph evidence
    -> fixed validated strategy or validated post-pair selector
    -> initial confidence-weighted trajectory
    -> graph-consistency reweighting
    -> final trajectory + final pair influences
    -> existing repair, reporting and image warping
```

Raw remaining image disagreement and usable overlap remain diagnostics. Neither is treated as confidence by itself: biological change can raise disagreement for a correct long-lag match, while repeated structure can produce a low-disagreement but incorrect match.

## Stage map

| NN | Name | One-line goal | Rough size | Depends on |
|---:|---|---|---|---|
| 01 | post-rotation-baseline | Freeze the stable rigid baseline, candidate strategies, data splits and accuracy gates before changing reconciliation. | 1 day | finalized rotation engine and fallback behaviour |
| 02 | pair-uncertainty | Expose direction-aware uncertainty evidence from log-ratio fitting and normalized area correlation through one estimator-neutral contract. | 1-2 days | 01 |
| 03 | weighted-reconciliation | Add information-weighted translation and rigid graph equations while preserving an exactly reproducible equal-weight mode. | 1-2 days | 02 |
| 04 | robust-reweighting | Iteratively reduce the influence of graph-inconsistent pairs without silently deleting measurements or disconnecting the trajectory. | 1-2 days | 03 |
| 05 | pipeline-diagnostics | Wire weighting through registration results and expose estimated uncertainty, consistency residuals and final pair influence. | 1-2 days | 04 |
| 06 | strategy-benchmark | Compare equal, uncertainty-only, robust-only and combined weighting on frozen translation and rotation evidence. | 1-2 days plus benchmark wall time | 05 |
| 07 | automatic-reconciliation-selector | Determine whether recording-level evidence can safely choose a better weighting strategy than the best fixed strategy. | 1-2 days plus benchmark wall time | 06 |
| 08 | promote-validated-winner | Promote only a fixed strategy or selector that passes the frozen gates; otherwise retain equal weighting and document the evidence gap. | 1 day | 07 |

Stages are intentionally sequential. Stages 02 to 05 change shared pair and trajectory contracts, Stage 06 evaluates the integrated fixed strategies, and Stage 07 tests whether their recording-to-recording differences are predictable enough to justify a selector. Stage 08 makes the production decision. Planning and data curation may happen while rotation tuning finishes, but Stage 01 cannot freeze its baseline until the rotation implementation itself is stable.

## House rules

- Do not execute Stage 01 against a moving rotation implementation. First freeze the angle sign convention, angular bounds, pair-estimator behaviour, multi-lag rigid equations, repair behaviour, warping and Automatic-mode fallback.
- The missing fresh data for rigid Automatic-selector retraining does not block this plan. Keep the explicit rigid log-ratio fallback documented in `docs/rigid_selector_validation_gap.md` until its separate evidence requirement is met.
- Preserve the current equal-weight solve as the baseline, compatibility mode and rollback. Before a validated winner is promoted, it remains the production default.
- Confidence means estimated movement precision, not merely a good-looking internal match. Never use `residualAfter`, match correlation or `validFraction` alone as the pair weight.
- Preserve directional information. A straight edge may constrain movement across the edge but not along it. Use a matrix rather than forcing horizontal movement, vertical movement and rotation into one scalar confidence when the estimator can distinguish them.
- Core translation units are pixels and core angles are radians. Any uncertainty or information matrix must respect those units and their coupling; do not tune rotation in degrees inside core equations.
- The log-ratio and normalized-area-correlation estimators may derive uncertainty differently, but they must expose the same physical meaning. Raw estimator scores with unrelated scales cannot be inserted directly into one graph solve.
- Combine measurement uncertainty with graph consistency. A sharp but incorrect repeated-pattern match can be confidently wrong, so estimator confidence alone is insufficient.
- Robust reweighting must be deterministic, bounded and reported. Never silently discard a pair. Preserve enough influence or explicit connectivity handling to avoid turning the multi-lag graph into an unsupported trajectory.
- Do not penalize a pair merely because it spans a longer lag. Longer gaps can contain more biological change but are also the independent constraints that prevent accumulated drift.
- Weight caps, floors and robust thresholds must be data-relative or justified by frozen development evidence. Do not choose them solely because they make the current recordings look better.
- Tune only on development data. Keep validation and locked data separated by original source recording, and do not reopen a spent locked set after it influences a design decision.
- Judge success using known trajectory error, failure rate, bound hits, repaired positions, worst-case error and runtime. A lower internal image residual is not proof of more accurate registration.
- Benchmark all four planned strategies: present equal weighting, uncertainty weighting only, robust graph weighting only, and their combination. A valid outcome is that none improves safely enough to replace equal weighting.
- Do not build a selector merely because different arms win individual recordings. Stage 07 proceeds past the headroom check only if the best possible per-recording choice materially beats the best fixed strategy under a threshold frozen in Stage 01.
- Keep reconciliation selection separate from the existing 48-feature preprocessing/estimator selector. It runs after pair estimation and may use only declared context, pair uncertainty, ambiguity, failure/connectivity evidence and graph diagnostics available without movement truth.
- A reconciliation selector must beat the best validated fixed strategy, not merely equal weighting, under source-grouped validation and a final untouched test. Low confidence falls back to that fixed strategy, or equal weighting if no non-equal fixed strategy passed.
- Report the evidence behind every final pair influence: estimator uncertainty, graph-consistency adjustment and whether any cap or floor was applied.
- Do not add public tuning controls before Stage 06 establishes that the quantities are interpretable and useful. Experimental configuration may remain internal to the benchmark until the winner decision.
- Preserve preprocessing isolation: confidence may be estimated from temporary guide images, but the final trajectory must still be applied once to the untouched original recording.
- Preserve unrelated working-tree changes. The repository is already dirty from rotation and benchmark work; every stage edits only its declared files.
- Follow the session communication rules supplied with the task: concise, plain language, exact paths and commands.

## Known open questions

- The exact conversion from each estimator's local curvature and residual noise to calibrated movement covariance is not yet fixed. Stage 01 must define the candidate calculations before Stage 02 implements them.
- The normalized area-correlation surface can be sharp at a repeated but wrong peak. Stage 01 must decide which peak-ambiguity measurement accompanies curvature; graph-consistency reweighting remains required regardless.
- The robust loss family and its scale are not yet chosen. The conversational design proposes Huber weighting because it preserves small residuals and gradually reduces large ones, but Stage 01 must freeze the compared candidate and threshold rule before locked evaluation.
- Confidence weights may need normalization within a recording or lag group so numerous short-lag edges do not erase the intended long-lag checks. This must be tested rather than assumed.
- Fresh independent rigid validation material is currently limited, especially for fiducial/static imaging. If the frozen evidence cannot support a rigid default claim, Stage 08 must retain equal weighting for that scope and record the gap.
- The conversation has not decided whether the first production release must include Python parity or only the Java/Fiji implementation. Proposed for review: require parity before making a non-equal strategy the shared default, but do not let Python work alter the Java benchmark decision.
- Stage 07 may find too little predictable recording-level headroom to justify a selector. In that case it must freeze the best safe fixed strategy, run that policy on the untouched test and record that automatic reconciliation selection was not supported.
- The conversation has not decided whether users should see a weighting-strategy control. Proposed for review: keep selection internal and automatic unless Stages 06-07 demonstrate a scientifically useful manual choice.

## How to run a stage

After the numbered stage files are approved and written, run `/do-step docs/confidence-weighted-reconciliation/` to execute the lowest-numbered incomplete stage.
