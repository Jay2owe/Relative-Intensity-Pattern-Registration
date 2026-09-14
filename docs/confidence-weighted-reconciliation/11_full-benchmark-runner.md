# Stage 11 - Build the full four-arm runner

## Objective

Replace the stop-only benchmark entry point with a resumable runner that changes reconciliation and nothing else.

## Implementation

- Load each frozen source and create translation and rigid truth stacks with independent high-order resampling.
- Estimate the lags `1, 2, 4, 8, 16` once per recording and estimator.
- Serialize every `PairAligner.Fit`, including covariance and flags, and hash the byte stream.
- Run `EQUAL`, `UNCERTAINTY_ONLY`, `ROBUST_ONLY` and `UNCERTAINTY_AND_ROBUST` from the saved fits.
- Score per-frame translation error, rigid warping index, failures, bound hits, repairs, covariance coverage, reconciliation time and total time.
- Write the Stage 06 CSV schemas, selector features and recording/strategy summaries atomically and resumably.

## Exit gate

Short tests prove split isolation, truth-free features, pair-hash identity, deterministic repeated output and metric identities. A one-recording smoke run completes before the full development run.
