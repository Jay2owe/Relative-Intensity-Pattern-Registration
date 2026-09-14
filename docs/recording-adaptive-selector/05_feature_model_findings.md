# Feature model and reserved-validation findings

## Development action

The Stage 04 stop decision was honored. `LINEAR_GAIN` is recorded as `FIXED_POLICY_HEADROOM_STOP`; no coefficients, feature selection, ridge value or confidence threshold were fitted. The emitted fixed policy used one recipe per image type and was evaluated once on 110 recordings from 10 reserved source series.

## Reserved validation

The fixed policy improved the source-balanced primary metric from 0.0187750 px to 0.00757701 px, an improvement of 0.0111980 px. Accuracy, image-type, p90, worst, recording guard, diagnostics and runtime gates passed. The retained-crop gate failed, so the fixed policy was not safe to promote.

The adaptive arm was identical to category fallback because development fitting had stopped. It was safe but provided zero improvement and failed the required primary improvement gate.

Exactly one integration decision was emitted:

`CATEGORY_RECOMMENDATION`

Candidate model version: `recording_adaptive_selector_v1_category_recommendation`. The generated Java and Python artifacts contain no retained candidates and therefore require no recording evidence.

Reserved input hashes are in `library/recording_adaptive_selector_v1/training/validation_decision.properties`. That decision file was created once and prevents a second validation run.
