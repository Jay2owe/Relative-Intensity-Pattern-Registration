# Stage 12 - Run development and tune failures

## Objective

Establish A000 equal performance, evaluate the three non-equal strategies and tune only predeclared uncertainty/robust reconciliation parameters when a candidate fails.

## Immutable rounds

- A000: equal compatibility baseline.
- A001: frozen uncertainty weighting.
- A002: frozen robust weighting.
- A003: frozen combined weighting.
- Later attempts change one attributable development parameter at a time and append to the tuning ledger. Losing attempts remain on disk.

Candidate parameters are covariance eigenvalue floor/cap, information normalization, robust residual scale/loss constant, factor floor and convergence limits. Pair estimation, preprocessing, estimator, lag plan, bounds and source pixels remain byte-identical within a comparison.

## Exit gate

A fixed strategy and optional selector candidate are chosen from development only, with source-balanced summaries and all inherited equal-regression tests passing.
