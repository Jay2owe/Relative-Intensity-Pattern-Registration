# Stage 01 complete: post-rotation protocol

Completed 2026-08-26.

- Frozen protocol: `selector_protocol.md`
- Candidate manifest: 128 unique executable recipes; SHA-256 `2d482270049113410b25ced202f41f852afc0c4a50f4fd684bb20b748438bc3c`
- Source manifest: 45 unique acquisitions, balanced 25/10/10 across development/validation/final; SHA-256 `abedd2db6bb95a0ebc937465580aa6a85dc54fa236760fabfaefd2628b665136`
- Rotation baseline: SHA-256 `c663230a02b87c526e69ffff6039130ddc995f465b38c79446712d10ae21f308`
- All 45 source files exist and match their recorded hashes.
- No independent group crosses a partition and no fresh validation/final exact hash occurs in the prior manifests.
- Audit verdict: `READY`.
- Compatibility gate: `mvn "-Dtest=logratio.FullSelectorSweepTest,logratio.RigidSelectorFactorialBenchmarkTest,logratio.api.AutomaticRegistrationSelectorTest" test` passed 30 tests.

No candidate outcome or per-recording winner was opened during this stage.
