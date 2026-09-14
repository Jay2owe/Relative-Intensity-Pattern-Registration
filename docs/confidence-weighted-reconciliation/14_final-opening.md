# Stage 14 - Open final evidence once

## Objective

Run equal, the frozen fixed policy and the frozen selector policy once on the untouched final sources.

## Rules

- Log each truth-free selector decision before scoring against truth.
- Do not alter a threshold, source, formula, recipe or scope after opening final results.
- Missing/corrupt final input, hash mismatch or truth leakage invalidates the run; it does not permit substitution from spent data.

## Exit gate

Every final source has complete decision and result rows, pair hashes match across arms, gate tables reproduce directly from CSV and the final manifest remains unchanged.
