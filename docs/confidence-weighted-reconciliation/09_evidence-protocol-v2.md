# Stage 09 - Freeze a second evidence protocol

## Objective

Replace the unavailable-final-evidence stop with a new source-separated protocol before any new registration outcome is inspected.

## Scope and split

- Retain the existing 20-source V2 controlled cohort as development evidence only.
- Acquire a balanced new cohort covering `PHASE`, `BRIGHTFIELD_DIC`, `DENSE_FLUOR`, `SPARSE_LOWLIGHT` and `FIDUCIAL_STATIC`.
- Freeze two independent original sources per category for validation and two different independent sources per category for final evaluation. A source is an original acquisition, not a crop, frame or generated motion path.
- Keep every crop, channel, motion path and corruption derived from one original source in the same split.
- Translation and rigid scopes are evaluated separately. Both log-ratio and area-correlation estimator families are retained.

## Frozen cases

Each source produces four 48-frame known-motion recordings: curved oscillating drift, steady directional drift, subpixel random walk and intermittent jumps. Translation cases use x/y truth. Rigid cases add a bounded in-plane angle defined over normalized time. The independent high-order spline generator and full-frame warping index from `ThevenazProtocolBenchmark` are reused.

## Gates

The Stage 01 numerical gates remain unchanged. Retuning is allowed only in development rounds. Validation can reject a candidate but cannot tune it. A rejected candidate returns to a new immutable development round; it does not reinterpret validation. Final data are opened once after policy freeze and never used for tuning.

## Exit gate

- The acquisition manifest records URLs, licences, original-acquisition identities, dimensions and SHA-256 hashes.
- Validation and final each contain exactly two independent sources per category or the protocol is revised and re-frozen before outcomes exist.
- `weighting_protocol_v2.md`, its hash and the source manifest hash exist before Stage 11 runs.
