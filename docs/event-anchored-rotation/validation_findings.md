# Event-anchored rotation validation findings

Status: **promotion blocked** on 2026-08-26.

## What has passed

- The accepted rigid solver is reused without a second angular engine.
- Java and Python synthetic tests recover positive, negative and cumulative event angles, translation drift
  and gain change while storing bit-identical angles inside each segment.
- Insufficient event support fails explicitly; declared remount translations are protected from repair.
- Fixed-reference angles rebase correctly, and pilot/refit paths reuse one event estimate.
- Legacy off and continuous controls retain their regression coverage.
- Fiji macro and Python command-line settings round-trip one-based events and window values.
- The complete Java and Python test suites pass with the declared implementation tolerances.

## Evidence gap

No fresh independent real recording with a documented removal/replacement event was supplied or found in
the project evidence store. Therefore the plan's mandatory real-data gate, visual worst-case review,
runtime comparison and input-manifest gates have not run. Synthetic success cannot substitute for them.

## Verdict

Keep `KNOWN_EVENTS` experimental and opt-in. Do not make it an automatic or global default, do not claim a
validated runtime or accuracy improvement, and do not promote the provisional three-frame window or a
hard spread-refusal threshold. Resume this protocol when eligible real recordings and their event
annotations are available.
