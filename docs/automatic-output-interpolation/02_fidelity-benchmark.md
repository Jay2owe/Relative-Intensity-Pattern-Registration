# Build the fixed-transform interpolation benchmark

## Why this stage exists

Registration accuracy and interpolation fidelity are different questions. This stage creates a benchmark that holds the transforms fixed, generates movement with an independent high-order model and compares only how each output method places and alters pixels.

## Prerequisites

- `01_resampling-contract_COMPLETED.md`

## Read first

- `docs/automatic-output-interpolation/00_overview.md`, entire file.
- `docs/automatic-output-interpolation/resampling_protocol.md`, entire file.
- `docs/automatic-output-interpolation/source_split_manifest.csv`, development rows and hashes.
- `src/main/java/logratio/core/Transform.java`, lines 14-116: exact transform composition and sign.
- `src/main/java/logratio/core/Warper.java`, lines 14-205 and 211-end: candidate behaviour, inverse sampling, Fourier padding and local kernels.
- `src/main/java/logratio/StackWarper.java`, lines 35-166: hyperstack application, crop, conversion and clamping.
- `src/test/java/logratio/core/WarperTest.java`, entire file.
- `src/test/java/logratio/ThevenazProtocolBenchmark.java`, lines 269-285, 433-505 and 924-1035: general metric, degree-7 B-spline generator and benchmark structure.
- `src/test/java/logratio/Benchmark.java`, lines 55-80: independent motion-injection rationale.
- `src/test/java/logratio/ValidationRun.java`, lines 55-75 and 200-245: why blur-sensitive temporal metrics need controls.
- `src/test/java/logratio/RigidSelectorFactorialBenchmark.java`, lines 124-215, 407-515 and 520-590: hashed input generation, rigid scoring and manifests.

## Scope

- Choose and record short Java benchmark/test filenames before editing; no canonical interpolation benchmark class exists yet.
- Generate controlled moved frames from analytic fixtures and real microscopy source planes using the independent generator frozen in Stage 01.
- Include the exact transform regimes, image categories, data roles, bit depths, intensity/noise conditions and source splits from the protocol.
- Supply the same known cumulative transform path directly to every interpolation arm. Do not call the registration estimator inside the candidate loop.
- Evaluate `NONE`, `BILINEAR`, `BICUBIC` and `FOURIER`, with identity and exact whole-pixel block copy as explicit controls.
- Evaluate float output before integer clamping and the actual stored byte/short/float output after `StackWarper` conversion.
- Calculate one common valid region from the maximum safe margin required by every candidate in the trial.
- Implement the frozen per-role metrics: intensity error, flux, peak, width/sharpness, overshoot/ringing, flicker, label validity and runtime as applicable.
- Separate generator error from candidate error with analytic identity, integer and inverse-transform fixtures.
- Record complete trial, source, transform, generator, candidate, environment and hash manifests.
- Make long runs resumable and audit duplicate/missing rows, recorded failures and artifact integrity.
- Produce a small development smoke run proving the harness, without drawing a policy conclusion.

## Out of scope

- Do not estimate transforms separately for each interpolation arm.
- Do not rank candidates, choose a fixed policy or calculate selector headroom; Stages 03-04 own those analyses.
- Do not alter `Warper` to improve an arm after seeing benchmark output. A correctness defect stops the benchmark and requires a separately documented fix/re-freeze.
- Do not use final untouched sources.
- Do not change Java/Fiji/Python production settings.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/logratio/<TODO-interpolation-fidelity-benchmark>.java` | NEW | Generate fixed-transform trials, run all interpolation arms and emit fidelity metrics. |
| `src/test/java/logratio/<TODO-interpolation-fidelity-benchmark-test>.java` | NEW | Pin transform sign, independent generation, common region, metric definitions and resumability. |
| `<artifact_root from resampling_protocol.md>/development/` | NEW | Store manifests, outcomes, logs and audited smoke/full-run artifacts. |
| `docs/automatic-output-interpolation/02_fidelity_benchmark_findings.md` | NEW | Record harness verification, coverage and defects without selecting a policy. |

The two Java filenames remain `TODO` because the conversational plan supplied no canonical class name. Choose them once before editing, record them in the findings and do not rename them after outcomes exist.

## Implementation sketch

Use the established transform convention directly:

```text
cumulative[t] = content motion from reference to observed frame t
candidate corrected output at x samples observed frame t at cumulative[t](x)
```

Generate each observed frame through a separate high-order route. For real source samples, the existing degree-7 B-spline functions are an available independent generator:

```java
double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
        reference, width, height);
float[] observed = ThevenazProtocolBenchmark.spline7Warp(
        coefficients, width, height, cumulative[t].inverse());
```

Verify the exact inverse/sign with analytic point and ramp fixtures before accepting that skeleton; do not assume the example is correct merely because it compiles.

For every trial:

```java
for (Interpolation candidate : Interpolation.values()) {
    // Float, pre-clamp candidate result.
    Warper.warp(observed, floatOutput, width, height,
            cumulative[t], candidate, fill);

    // Actual typed stack result, including clamping and optional crop disabled.
    ImagePlus stored = StackWarper.apply(inputStack, cumulative,
            candidate, false);
}
```

Use one comparison region:

```text
common margin = component-wise maximum valid margin over every candidate
                plus any independent-generator guard required by the protocol
```

The outcome table should contain one row per trial/frame/candidate, for example:

```text
trial_id,source_series_id,independent_group,data_role,image_type,geometry,
candidate,storage_type,status,common_roi_pixels,pixel_error,flux_error,
peak_error,width_error,sharpness_error,undershoot,overshoot,ringing,
temporal_flicker,label_invalid_pixels,wall_ms
```

Exact columns come from `resampling_protocol.md`. Keep truth-free source/transform features in a separate table for Stage 04.

## Exit gate

1. Analytic fixtures prove the generator/warper sign convention and exact identity restoration.
2. Whole-pixel translation through every candidate reaches the existing block-copy path and is bit-exact on the common valid region.
3. The movement generator is independent from all four evaluated candidate implementations.
4. Every candidate receives byte-identical source frames and transform arrays within a trial.
5. The evaluation region is identical across candidates and excludes fill/generator boundaries as frozen.
6. Pre-clamp overshoot and final stored/clamped metrics are both recorded.
7. Label fixtures count invalid category values and prove `NONE` preserves them exactly.
8. Temporal metrics include the blur control required by the protocol and are not treated as standalone accuracy.
9. Repeated smoke runs with identical seeds produce identical non-timing values and resume only missing rows.
10. The artifact audit rejects missing candidates, duplicate keys, hash drift and outcome fields in the truth-free feature table.
11. `mvn -Dtest=logratio.core.WarperTest,logratio.<chosen-benchmark-test-class> test` passes.
12. `02_fidelity_benchmark_findings.md` declares the harness structurally ready but does not name a preferred interpolation.

## Known risks

- Applying an independent generator and then a candidate correction is necessarily a two-sampling experiment. Include analytic continuous fixtures and report the generator floor.
- The degree-7 generator uses mirror boundaries while production warping uses fill/padding rules. The common region must exclude boundary-model differences.
- Integer clamping can hide overshoot by clipping it. Preserve float pre-clamp measurements.
- Fourier interpolation uses a global kernel, so a finite local reach does not describe ringing. The common region and ringing metrics must handle this explicitly.
- A visually smoother output can score better on temporal variation by blur alone. Keep sharpness and truth error beside it.
- Large full matrices are costly. Estimate runtime/disk first and keep resumption deterministic.
