# External parameter sweep: sealed-set declaration

Declared 2026-08-20, before the external development sweep produced any result. This declaration
implements Stage 4 of `docs/external_parameter_sweep_plan.md` and is deliberately separate from the
later findings document so that the rule cannot be rewritten around an attractive result.

## Decision

The external parameter sweep may read only the 80 recordings under
`library/benchmark/v2/benchmarks/controlled_motion/`. It must not read, score, probe, or select from
`locked_test`, `sealed_test`, or `sealed_test_3`.

After one configuration per engine and image type has been selected solely by the development-set
criterion (median of the per-recording median geometric error), those frozen settings may be run once
on `locked_test`. All defaults may also be run there once to form the untuned table. A failure remains
a failure and is counted in the main table; it is not an invitation to try another configuration.

The third sealed set is not opened for this plan. Adding external arms alone does not warrant spending
it, and this plan does not accompany a new model claim that would satisfy the existing third-set gate.

No sweep result, failure pattern, or locked-set result can change these rules.
