# User-approved fixed-policy override

## Decision

Promote the frozen five-recipe image-type policy despite its sealed crop and runtime gate failures.

The decision accepts two isolated operational trade-offs in exchange for a 49.7% reduction in source-balanced final median error:

- one of 110 recordings retained 94.604% rather than 97.668% of image area, a 3.064 percentage-point loss against a 2-point limit;
- a different recording ran at 1.6578 times category runtime, against a 1.50-times limit (0.776 rather than 0.468 seconds on the benchmark stack).

All frozen accuracy, image-type, p90, worst, recording-guard, status, repair and diagnostic gates passed. The override command refuses to operate if any of those gates failed; only crop/runtime can be accepted.

## Audit separation

The once-opened final report remains `CATEGORY_RECOMMENDATION` and was not edited or rerun. The new decision is stored in `library/recording_adaptive_selector_v1/training/operator_override_decision.properties` and pins the original gate report, final outcomes and freeze-record hashes.

Installed model: `recording_adaptive_selector_v1_user_approved_fixed_policy_v1`.

- model kind: `image_type_rule`
- retained candidates: 5, one per image type
- recording evidence required: no
- Java source SHA-256: `a7c9c643f13c836d3add51b401d07cf1bd99481e25ba74fe7ea4670063ee54b1`
- Python source SHA-256: `0748ccac87555c6576bf13d36a4ac3311227ba86c2f1012c16c0ec08457d46d5`
- model artifact SHA-256: `8c5207e792e85d45403894483c682594d040b9837224e2efacbbfac51370c94e`

Verification: 479 Java tests and 46 Python tests pass. The installed Java and Python files match the generated override artifacts byte-for-byte, and `git diff --check` reports no whitespace errors.
