# Stage 07 automatic selector findings

Outcome: `FIXED_ONLY` with safe fallback `EQUAL`.

The per-recording oracle improved the best fixed strategy by 1.57% on development, 3.83% on validation and 6.82% on final. All are below the 10% prerequisite frozen before outcomes existed, so no selector was fitted. The 18 truth-free feature columns remain implemented and hash-guarded for audit, but no scope is eligible and every call deterministically falls back to equal.

This is a headroom failure, not a model-training failure: even a perfect retrospective chooser could not meet the required gain on development or validation.
