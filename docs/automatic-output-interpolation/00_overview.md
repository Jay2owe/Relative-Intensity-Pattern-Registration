# Automatic output interpolation for Relative-Intensity Pattern Registration

## End goal

Add a separate, validated output-resampling decision to Automatic mode. After registration has estimated and reconciled the movement path, the software will either select the safest supported interpolation for the declared data role and measured transform geometry, or use a validated fixed fallback when automatic selection is not justified.

The decision must never alter the estimated transforms. It controls only how those transforms are applied once to the untouched original recording. Explicit manual interpolation remains authoritative, and discrete labels or masks are never blended.

## Why we're doing this

The current output choices preserve different properties. `NONE` uses nearest-neighbour placement and an exact block copy for whole-pixel translations, preserving original values but quantizing fractional movement. Bilinear interpolation gives smooth fractional placement but blurs detail. Bicubic interpolation can preserve sharper detail but may overshoot at hard edges. Fourier interpolation is well suited to band-limited rigid movement but can ring around sharp features and costs more to run.

The best trade-off can therefore depend on what the pixels mean, the actual movement that was found and the structure of the source images. A label mask must keep integer category values. A quantitative intensity recording may prioritize counts, peaks and temporal measurements. A display-oriented intensity recording may prioritize smooth spatial placement. Translation with no meaningful fractional component does not need interpolated values, whereas rotation necessarily requires a spatial sampling decision.

This is presented as part of the overall Automatic workflow, but it is technically separate from both the preprocessing/pair-estimator selector and the planned confidence-weighted reconciliation selector. It runs only after the final movement path is known. Rotation should be finalized before implementation and benchmarking so the tested warper and transform conventions do not change underneath the evidence. Confidence-weighted reconciliation may be developed independently because interpolation candidates must always be compared using identical frozen transforms.

## Architecture overview

```text
image type + movement request
          |
          v
existing recipe selector -> pair measurements -> reconciliation -> final transforms
                                                                  |
                                                                  v
declared data role + image/motion context + actual transform geometry
          + truth-free source-image evidence
                                                                  |
                                                                  v
                         output-resampling policy
                  fixed fallback / selector / manual override
                                                                  |
                                                                  v
              warp every untouched channel and Z plane once
```

The proposed data roles are:

- `QUANTITATIVE_INTENSITY`: measured intensity values, counts, peaks and temporal traces matter.
- `VISUAL_INTENSITY`: visually faithful spatial placement is the main output goal.
- `LABELS_MASKS`: pixels are discrete identities or categories and must remain discrete.

Data role is not inferred from microscopy image type. Fluorescence, brightfield, phase contrast, differential interference contrast and fixed-marker images may still contain either measured intensities or derived masks. Because the present warper applies one interpolation policy to every channel and Z plane, Automatic mode must not infer the semantics of the complete stack from the guide channel alone. A mixed-semantics stack requires an explicit compatible choice, separate output handling in a later design, or a refusal to select automatically.

The selector may use evidence available without knowing the correct output: declared data role, declared image and motion categories, whether rotation is present, the distribution of fractional translations, source sparsity, edge strength, noise and predicted ringing risk. It must not choose from post-warp registration disagreement alone, because smoothing can lower that disagreement without improving geometric or measurement fidelity.

## Stage map

| NN | Name | One-line goal | Rough size | Depends on |
|---:|---|---|---|---|
| 01 | resampling-contract | Freeze data roles, candidate policies, transform thresholds, fidelity metrics, evidence splits and promotion gates. | 1 day | finalized rotation warper and transform conventions |
| 02 | fidelity-benchmark | Build an interpolation benchmark that holds transforms fixed and compares outputs with independently generated ground truth. | 1-2 days | 01 |
| 03 | fixed-policy-evaluation | Measure nearest-neighbour, bilinear, bicubic and Fourier fidelity across data roles, image categories and movement geometries. | 1-2 days plus benchmark wall time | 02 |
| 04 | selector-headroom | Test whether truth-free evidence can predict a better policy than the best safe fixed rule, and train a selector only if sufficient headroom exists. | 1-2 days plus benchmark wall time | 03 |
| 05 | java-integration | Add the validated fixed policy or selector to Java Automatic mode with data-role safeguards, manual override and provenance. | 1-2 days | 04 |
| 06 | python-parity | Match Java's data roles, policy resolution, warping choice, safeguards and recorded provenance in Python. | 1-2 days | 05 |
| 07 | final-validation-and-promotion | Run untouched validation, verify Java/Python behaviour and promote only the policy that passes the frozen gates. | 1-2 days plus benchmark wall time | 06 |

The stages are sequential because the scientific objective and comparison rules must be frozen before the benchmark is built, and the selector decision depends on the measured advantage over the best fixed policy. A valid Stage 04 result is that no selector is justified. In that case Stages 05-07 implement and validate the best safe fixed or data-role-specific policy instead.

## House rules

- Keep output interpolation separate from movement estimation and reconciliation. Every interpolation arm in a comparison receives exactly the same transforms.
- Apply the chosen transform once to the untouched original recording. Never compare methods after cumulative or repeated resampling.
- Preserve explicit manual choices. Automatic resolution runs only when the caller requests Automatic output interpolation, and its selected policy and reason are recorded.
- Require an explicit data role. Microscopy image type and estimator choice do not prove whether pixels are measured intensities, display values or labels.
- Never blend `LABELS_MASKS`. Use `Warper.Interpolation.NONE`, which is the current serialized name for nearest-neighbour/no-blending behaviour, and test that category values remain exact.
- Do not infer the semantics of every channel or Z plane from the guide channel. If one output stack mixes labels with intensity planes, decline a single automatic interpolation decision unless the user explicitly chooses a compatible policy.
- Preserve the existing exact block-copy path for whole-pixel translations. The benchmark must distinguish that path from fractional nearest-neighbour placement even though both currently use `NONE`.
- Treat rotation and fractional translation as actual transform geometry, not merely a declared motion label. A declared subpixel experiment may resolve to whole-pixel transforms, while an apparently translational run may contain meaningful fractional components.
- Define "effectively whole pixel" in Stage 01 using a frozen tolerance tied to the maximum displacement error it permits. Do not tune the tolerance on final validation data.
- Generate known-motion test inputs with an independent high-order model, such as the existing degree-7 B-spline generator. Do not generate and evaluate an interpolation arm with the same implementation, because that would give it an artificial advantage.
- Compare candidates on one common valid region that is safe for every arm in that trial. Interpolation-specific crop margins must not give one method an easier set of pixels.
- Measure output fidelity against known unwarped truth. Include pixel-intensity error, total intensity or flux, peak amplitude, feature width or sharpness, edge overshoot/ringing, temporal flicker, label validity and runtime as appropriate to the frozen data role.
- Record overshoot before integer-type clamping as well as the final stored output. Clamping can hide bicubic or Fourier ringing while still altering measurements.
- Do not use lower remaining registration disagreement, lower temporal variation or a smoother appearance alone as proof of better interpolation. Blur can improve all three without preserving the correct signal.
- Separate the measurement objective from the display objective. Do not combine incompatible metrics into one score until Stage 01 defines and justifies the weighting for each data role.
- Evaluate nearest-neighbour/no interpolation, bilinear, bicubic and Fourier as fixed candidates. Include the current production default as a named baseline.
- Calculate oracle headroom before training a selector: measure how much an ideal per-recording choice could improve over the best fixed or data-role-specific rule. Do not build a model if that advantage is too small or inconsistent.
- A selector must beat the best validated fixed policy, not merely the present default. Use source-grouped development and validation splits so frames from one original recording cannot appear on both sides.
- Selector inputs must be available at run time without output truth. Keep label role as a hard rule rather than a learned prediction.
- Low-confidence, unsupported or out-of-distribution cases use the frozen conservative fallback or request an explicit choice. They must not silently select the most visually attractive method.
- Keep interpolation selection independent of confidence-weighted reconciliation. Reconciliation changes the transforms; interpolation evaluation begins only after one transform path has been frozen for all arms.
- Do not promote a new public default until rotation is stable, Java and Python agree, macro/API compatibility is tested and the untouched validation gate passes.
- Preserve compatibility with the serialized `NONE`, `BILINEAR`, `BICUBIC` and `FOURIER` names unless a separately tested migration is added.
- Keep crop selection as a separate user policy. The chosen interpolator may determine the required valid margin, but it does not silently change whether cropping is enabled.
- Report enough provenance to reproduce the output: requested selection mode, declared data role, selected interpolation, fallback or override reason, transform-geometry summary and selector/model version.
- Preserve unrelated working-tree changes. Each stage edits only its declared files and tests.
- Follow the session communication rules supplied with the task: concise, plain language, exact paths and commands.

## Known open questions

- The quantitative-intensity objective is not yet ranked. Stage 01 must decide whether pixel error, integrated flux, peak amplitude, feature width and temporal stability are hard gates or a weighted score.
- The intended scope of `VISUAL_INTENSITY` needs freezing. A visually smooth result and a quantitatively faithful result may choose different methods, so one cannot be used as evidence for the other.
- The user-interface default for data role is undecided. Proposed for review: require an explicit role when Automatic output interpolation is enabled, while preserving the current manual interpolation default for existing macros and API calls.
- Mixed-semantics stacks need a product decision. Proposed first implementation: refuse automatic selection for a stack known to mix intensity planes and labels, rather than split and recombine outputs silently.
- The exact whole-pixel tolerance and rotation-negligibility threshold are not fixed. Stage 01 must connect both to a bounded spatial error and preserve exact zero-rotation behaviour.
- Fourier interpolation may be too slow or ring too strongly for some image categories. Stage 03 must decide from fidelity and runtime evidence rather than treating its theoretical model as an automatic advantage.
- The best supported result may be a small rule table instead of a trained selector. For example, labels and true whole-pixel translation may be hard rules, with a learned choice considered only for fractional intensity movement.
- Fresh independent rigid validation material may be limited for some image categories. If the evidence is insufficient, Stage 07 must retain the validated fallback and record the unsupported scope.
- The public wording for `NONE` is potentially confusing because it performs nearest-neighbour placement for fractional transforms and exact block copying for integer translations. The first implementation should clarify the label without breaking serialized compatibility.

## How to run a stage

After the numbered stage files are approved and written, run `/do-step docs/automatic-output-interpolation/` to execute the lowest-numbered incomplete stage.
