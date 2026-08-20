# Thévenaz protocol findings

Run completed 2026-08-20 from `docs/thevenaz_protocol_plan.md`. The durable report and trial-level
CSVs are in `library/benchmark/thevenaz_protocol_2026-08-20/`.

## What was reproduced

The harness uses the native 256×256 Fig. 3 raster embedded in the authors' official PostScript, not
the 239×240 copy in the PDF. It generates the test and reference independently from that image with
the paper's interpolating degree-7 B-spline, applies inverse and direct half-transforms, and recovers
their square. The random seed is `6073180334341374298`; every arm receives the same 100 transforms.
The paper, PostScript, Fig. 3 raster and TurboReg binary are SHA-256 pinned in
`protocol_manifest.properties`.

The initially ambiguous region V mattered exactly as the plan anticipated. TurboReg affine produced
the following pooled warping indices against the printed ML*3 value of 0.0009 px:

| Region V | Mean px | Factor-of-two gate |
|---|---:|---|
| Full frame | 0.002943366 | Fail |
| Central 90% | 0.002681700 | Fail |
| Central 80% | 0.002432754 | Fail |
| Central 75% | 0.002307010 | Fail |
| Central 50% | 0.001711301 | **Pass** |

The predeclared interval was 0.00045–0.0018 px. Stage 0 therefore passes only with the central 50%
ROI. This is not an ROI selected to improve our estimators: Stage 0 runs TurboReg affine alone, before
any project estimator, and the plan explicitly named V as the first diagnostic.

The general affine warping-index implementation and the existing translation shortcut agree bit
exactly in the translation case (`general - shortcut == 0.0`, displayed as `0.000e+00`).

## Stage 2: matched translation comparison

On the single Fig. 3 case study, `LOG_RATIO_FIT` has the lowest mean, narrowly ahead of TurboReg:

| Arm | Mean px | Median px | Worst px |
|---|---:|---:|---:|
| `LOG_RATIO_FIT` | **0.024737810** | **0.024604467** | **0.047673535** |
| TurboReg translation | 0.024977720 | 0.025763157 | 0.048529422 |
| `AREA_CORRELATION` | 0.041170680 | 0.045774086 | 0.070166732 |
| `AREA_CORRELATION_NEWTON` | 0.041419562 | 0.046410318 | 0.071114337 |

Across the twenty development images (2,000 measurements per arm), **TurboReg wins the pooled mean
and median**. `AREA_CORRELATION_NEWTON` is only 0.000245816 px behind on mean and has the best worst
case:

| Arm | Mean px | Median px | Worst px |
|---|---:|---:|---:|
| TurboReg translation | **0.025639320** | **0.017878451** | 8.490946049 |
| `AREA_CORRELATION_NEWTON` | 0.025885136 | 0.025548323 | **0.073945208** |
| `AREA_CORRELATION` | 0.029972601 | 0.027638502 | 0.136861883 |
| `LOG_RATIO_FIT` | 0.034027321 | 0.027987214 | 0.205946054 |

TurboReg has the lowest per-image mean on 14 of the 20 development images; `AREA_CORRELATION_NEWTON`
wins three, `AREA_CORRELATION` two, and `LOG_RATIO_FIT` one. TurboReg nevertheless has three
divergences above 1 px, all absent from the other arms' worst cases. Its largest is 8.490946049 px on
`phase_strack_pputida_01`, trial 40. This is why the mean, median and worst must be reported together.

The evidence gate passes: 21 images × 100 transforms × 5 arms gives 10,500 finite rows, every
image/trial group has identical truth across arms, and the do-nothing control has the expected
3.715169713 px mean.

## Stage 3: guarded rigid-body comparison

The previously unproven `fitRotation` path passes its predeclared synthetic gate. Its rotation error
is 0.000042198° (limit 0.1°), translation error is 0.000659832 px (limit 0.1 px), and enabling
rotation changes the zero-rotation error from 0.000631090 to 0.000764624 px, well inside the allowed
0.01 px degradation.

On the full ±10°/±5 px Fig. 3 experiment, **`LOG_RATIO_FIT` with rotation has the lower pooled mean
and median**, while TurboReg has the lower worst case:

| Arm | Mean px | Median px | Worst px |
|---|---:|---:|---:|
| `LOG_RATIO_FIT_ROTATION` | **2.264249420** | **0.001814310** | 15.015906460 |
| TurboReg rigid body | 5.551586583 | 5.584999458 | **12.288476748** |

The low log-ratio median beside its very large mean and worst case shows a bimodal result: it is very
accurate when it captures the rotation, but sometimes falls into a wrong solution over the full
range. TurboReg rigid body is unexpectedly inaccurate on this image even though the same harness
passes the TurboReg-affine control. Treat this as the measured result for this exact setup, not as a
general claim that either rigid method is superior.

## Stage 4: Gaussian-noise sweep

Stage 4 uses the six levels plotted in paper Fig. 5: 25, 20, 15, 10, 5 and 0 dB. For every clean
translation trial, independent deterministic white-Gaussian realizations are added to the generated
test and reference planes. Following the paper's equation (33), the target noise variance is computed
from the variance of the original Fig. 3 raster. This is the Stage 2 translation-matched comparison,
not a reproduction of the paper's affine ML* curve.

Mean warping index rises as noise increases. `LOG_RATIO_FIT` has the lowest mean at 25 through 15 dB;
TurboReg translation has the lowest mean from 10 through 0 dB:

| SNR dB | TurboReg translation | `LOG_RATIO_FIT` | `AREA_CORRELATION` | `AREA_CORRELATION_NEWTON` | Lowest mean |
|---:|---:|---:|---:|---:|---|
| 25 | 0.027553245 | **0.027140424** | 0.052874265 | 0.052920570 | Log ratio |
| 20 | 0.028883821 | **0.027772420** | 0.091337445 | 0.090028582 | Log ratio |
| 15 | 0.030521921 | **0.029071913** | 0.187426865 | 0.181663716 | Log ratio |
| 10 | **0.034026119** | 0.039394925 | 0.281679828 | 0.270092528 | TurboReg |
| 5 | **0.057175369** | 0.085307382 | 0.335737826 | 0.294732924 | TurboReg |
| 0 | **0.106814891** | 0.312035910 | 0.363364729 | 0.369791925 | TurboReg |

The 0 dB result is the strongest separation: TurboReg is 2.92-fold lower in mean error than
`LOG_RATIO_FIT`. None of the methods diverges above 1 px in this sweep; the worst observed value is
0.910406778 px (`AREA_CORRELATION_NEWTON`, 0 dB). Log-ratio's median wall time rises from 48.3 ms at
25 dB to 136.8 ms at 0 dB, while TurboReg remains between 33.1 and 49.5 ms. These timings support the
qualitative observation that the local log-ratio fit works harder as noise rises, but they are not
the convergence-level count reported by the paper and should not be compared numerically to it.

The Stage 4 evidence gate passes. There are 3,000 finite trial rows (6 levels x 100 trials x 5 arms),
every group contains exactly 100 results, the same geometry is used across all arms and noise levels,
test and reference noise seeds are always distinct, and the pooled realized SNR is within 0.003 dB of
every target. The do-nothing error is invariant across SNR, as it must be because noise changes the
observations rather than the known geometry.

## Scope and interpretation

The paper's 0.0009 px headline is an **affine** result. Stage 2 is translation-only and Stage 3 is
rigid-body; neither may be quoted directly against 0.0009 px. Fitting fewer parameters on rigid truth
would make that comparison flattering and invalid.

This static-image protocol has no gain change, so even its additive-noise sweep does not test the gain
invariance that motivates the log-ratio estimator. No file under `src/main` was changed.

Reproduce the run from the project root with:

```powershell
.\scripts\run_thevenaz_protocol.ps1 -Trials 100
```

The script downloads and pins the authors' paper assets and TurboReg binary, bootstraps Maven if
needed, compiles the test-side harness, and writes the full Stages 0-4 report and trial CSVs.
