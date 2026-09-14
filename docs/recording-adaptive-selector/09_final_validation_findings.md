# Final validation and promotion findings

## Sealed procedure

The final partition was frozen before any final outcome was opened. The freeze record pins the protocol, source split, 110-case input and feature tables, candidate manifest, installed Java/Python model sources, report source and independent gate test.

- freeze record SHA-256: `77f6c1214b972d3713a3afcb45c2c2d11925a4cb19c2539226ad1be9fff6c41f`
- opening count: 1
- final outcomes SHA-256: `d9c9cea66aa43266ea96e671778f1985e35229fb2102cdaec3269643b82af553`
- final gate report SHA-256: `0e6cc568d1800829b2c7750016dbf358e7b477ee69b78c6eba833313c44d87a2`

## Result

The category policy measured 0.0108124 px source-balanced median central-50% warping error, with no failures or diagnostic events. The proposed fixed image-type policy measured 0.00543586 px, improving by 0.00537656 px, and passed primary, image-type, p90, worst, recording-guard and diagnostic gates. It failed the retained-crop and complete-runtime gates, so it was not promoted.

The candidate adaptive arm reduced to category fallback and failed its primary, image-type, p90, recording-guard, crop and runtime gates. It was not promoted.

## Final production decision

`CATEGORY_RECOMMENDATION`

Model version: `recording_adaptive_selector_v1_final_category_recommendation`.

This is a positive safety result, not an unfinished adaptive fit: the system implements recording-aware measurement, prediction, fallback and provenance, but the generated production model deliberately retains no override because none passed every predeclared gate. Automatic therefore costs no provisional pass in this version.

Final generated/installed source hashes:

- Java: `a36d356c228e3c1e9b26fa0b840abf5b1b19e273d8ef7e23b597d8d3047ea5c2`
- Python: `fa0ed3c2fe4865e092f89ce4468b9e1e56ea256327143721779b4b723abfeaf1`

Verification: 478 Java tests and 46 Python tests pass; `git diff --check` reports no whitespace errors.

## Later production override

The sealed result above remains immutable. After reviewing the magnitude of the trade-offs, the user explicitly approved promotion of the fixed policy. That later decision is recorded separately in `10_user_approved_fixed_policy_override.md`; it does not change the original final gate report.
