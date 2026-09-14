# Stage 06 benchmark findings

The original v1 run stopped because it had no untouched balanced final evidence. Protocol v2 supersedes that stop with a source-separated cohort and complete development, validation and final runs.

The runner completed 320 development, 160 validation and 160 final recordings. Each recording reused one serialized pair-fit stream across equal, uncertainty-only, robust-only and combined reconciliation. All pair hashes, source splits, selector-feature joins and output counts passed.

## Source-balanced median trajectory error

| Split | Equal | Uncertainty | Robust | Combined |
|---|---:|---:|---:|---:|
| Development | 0.026491 px | 0.030191 px | 0.024227 px | 0.028752 px |
| Validation | 0.024629 px | 0.025236 px | 0.024239 px | 0.025298 px |
| Final | 0.025032 px | 0.026836 px | 0.025667 px | 0.026210 px |

Robust-only had the best global development result, 8.55% below equal, but no declared scope cleared the 5% development gate: rigid area correlation improved 4.65%, rigid log-ratio 1.18%, translation area correlation 0.07%, and translation log-ratio worsened 0.98%. It also added repaired frames, did not converge on all recordings and cost roughly 1,400 to 3,700 times equal reconciliation within scope. The predeclared parameter lattice cannot remove the leave-one-edge-out runtime veto, so no outcome-driven retune was justified.

Uncertainty-only was neutral for translation, worse for rigid log-ratio, and increased repairs in development. Combined inherited the robust runtime cost and did not supply a safe accuracy gain.

Authoritative artifacts are under `library/benchmark/v2/benchmarks/confidence_weighted_reconciliation_v1/tuning/rounds/R01_full_evidence/runs/`.
