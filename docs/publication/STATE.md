# Paper-Prep State

**Project:** Log-Ratio Registration
**Target:** both — bioRxiv followed by a methods journal (provisional)
**Started:** 2026-08-21

## Stages
- [>] 1. USP discovery       — current
- [ ] 2. Benchmarks design
- [ ] 3. Flagship workflow
- [ ] 4. Confidence evidence
- [ ] 5. Manuscript drafts
- [ ] 6. Journal shortlist
- [ ] 7. bioRxiv minimum

## Decisions
- 2026-08-21 — approved method-first pitch: a gain-invariant registration method for microscopy time-lapses that separates global intensity change from motion, rejects genuine scene change, and automatically selects a validated estimation strategy.
- 2026-08-21 — bioRxiv is the provisional default; final paper target awaits user confirmation.
- 2026-08-21 — existing material found for possible inclusion: README, external-comparison publication plan, four-class benchmark findings, protocol deviations and the linked research result.
- 2026-08-21 — Fiji/ImageJ is positioned as the first complete implementation and user interface, not as the scientific contribution itself.
- 2026-08-21 — at least one additional implementation, preferably Python or R, is required before release to demonstrate that the method is portable beyond Fiji.
- 2026-08-21 — retain the technical phrase “gain-invariant”; define it on first use as remaining accurate when the whole image brightens or dims, rather than replacing the phrase in the pitch.

## Notes for next stage
Await approval of the revised method-first pitch and the first non-Fiji implementation language. Stage 1 must separate algorithmic novelty from platform-specific features, while treating Fiji, Python and/or R as delivery routes. After approval, run Stage 1 discovery and write `01_usps.md`; do not advance to Stage 2 without review.
