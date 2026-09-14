# Recipe outcome matrix findings

## Result

`RecordingAdaptiveSelectorBenchmark` generated and audited the frozen development matrix without opening validation or final outcomes. The run contains 275 cases, 55 per image type, from 25 source series assigned to 19 independent source groups. Each case has the category comparator plus all 128 frozen candidates, giving 35,475 outcome rows.

The matrix records central-50%, full-frame, p90 and worst warping error; angular and translation error; all seven failure/repair diagnostics; retained crop fraction; and runtime. Features and complete selector timings are stored separately by case ID. The timing contract is `complete_selector_timing_v2` and includes pilot, evidence and decision time.

## Audit

- feature rows: 275
- timing rows: 275
- outcome rows: 35,475
- candidate manifest SHA-256: `2d482270049113410b25ced202f41f852afc0c4a50f4fd684bb20b748438bc3c`
- source manifest SHA-256: `abedd2db6bb95a0ebc937465580aa6a85dc54fa236760fabfaefd2628b665136`
- development input manifest SHA-256: `f99395f98fa3740d72b9df72ca26834f5fbe4437c217a6c08dd53cca8986821f`
- feature table SHA-256: `461b87ebcec53f4dcc43d5c43d3c710c902e48fbea76d2b44f6a066663c4e511`
- timing table SHA-256: `7d8537697e6fa0f8a0c6e2e2a5f134f3d3a990412715f04d90f3f615773ca955`
- outcome table SHA-256: `5b1aca1222c2e8cd92653761a9e883dc6ca6ab793bafd8317399d15f8b60776c`
- audit verdict: `PASS`

The runner is resumable by partition, image type, recipe and case. Each completed row is flushed before the next candidate starts.
