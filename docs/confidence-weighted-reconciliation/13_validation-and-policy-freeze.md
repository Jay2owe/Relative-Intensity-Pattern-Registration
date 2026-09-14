# Stage 13 - Validate and freeze the policy

## Objective

Open only the frozen validation sources, evaluate the development-selected candidates once and freeze either a safe fixed strategy, a selector candidate or equal.

## Rules

- A validation failure never changes an existing attempt. It starts a new development round with a documented hypothesis and then re-runs validation as a newly versioned policy.
- The independent source recording is the unit of analysis.
- A selector is trained only if oracle headroom over the best safe fixed arm is at least 10% on development and validation.
- Any learned selector must beat the fixed policy by at least 3% and pass all fixed safety/runtime gates.

## Exit gate

`selected_configuration_v2.properties` and `frozen_selector_policy_v2.properties` are written with source, protocol, feature-order and code hashes. No final source has been scored.
