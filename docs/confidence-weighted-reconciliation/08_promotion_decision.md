# Stage 08 promotion decision

Decision: `NO_PROMOTION`.

Reason code: `NO_PROMOTION_FAILED_FROZEN_GATES`.

## Evidence audit

| Split | Original sources | Recordings | Selected equal verdict | Non-equal arm safe in every claimed scope |
|---|---:|---:|---|---|
| Development | 20 | 320 | pass | none |
| Validation | 10 | 160 | pass | none |
| Final | 10 | 160 | pass | none |

All derivatives stayed with their original acquisition. All four arms used byte-identical serialized pair fits. Truth-free selector keys joined one-to-one to outcome rows. The final cohort was opened once after the equal/fixed-only policy and validation verdict were frozen.

Equal remains the Java, Python, Fiji, API and macro default. Experimental uncertainty, robust and combined modes and their diagnostics remain available to the internal engine, but none is promoted, scoped or selected automatically.

The decision is performance-based rather than evidence-limited: the final source-balanced median error was 0.025032 px for equal, 0.026836 px for uncertainty, 0.025667 px for robust and 0.026210 px for combined. Equal was the best final fixed arm. Robust improvements seen in some rigid log-ratio subsets did not pass the same frozen development policy and incurred orders-of-magnitude reconciliation cost.
