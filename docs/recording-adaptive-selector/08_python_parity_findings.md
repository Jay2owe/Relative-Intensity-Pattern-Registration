# Python parity findings

Python implements the same 48-field `recording_evidence_v2` contract, validity and out-of-distribution checks, candidate eligibility, confidence comparison, fallback and recipe identity as Java. Both languages consume artifacts generated from the same training model.

Final Python model source: `src/ripr/recording_selector_model.py`.

Installed override SHA-256: `0748ccac87555c6576bf13d36a4ac3311227ba86c2f1012c16c0ec08457d46d5`.

The parity probe covers every image type, motion type and translation/rigid context. With the installed fixed policy, both implementations report no evidence pass, the same image-type recipe, no fallback and model version `recording_adaptive_selector_v1_user_approved_fixed_policy_v1`.
