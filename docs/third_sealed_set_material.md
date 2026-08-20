# Material for a third sealed test set, counted rather than assumed

Written 2026-08-19, immediately after `docs/dense_source_candidates.md` cleared the dense
fluorescence blocker. That document dealt with one image type. This one counts all five, and
settles the question of what an "independent group" actually is, because the two manifests and
the benchmark code disagreed about it.

## The result

**The third sealed set exists. Ten sources, forty controlled recordings, six natural-motion
stacks, zero failures, built and accepted on 2026-08-19 and not read.**

| Image type | Records | Where they came from |
|---|---|---|
| DENSE_FLUOR | 2 | new sources, acquired today — SCN circadian slice, ChromaLIVE HeLa |
| SPARSE_LOWLIGHT | 2 | new source, acquired today — single-molecule FRET, two sessions |
| FIDUCIAL_STATIC | 2 | `d3` and `d4` commercial beads, downloaded today |
| PHASE | 2 | already in the tree — third Strack acquisition of two strains |
| BRIGHTFIELD_DIC | 2 | already in the tree — microbundle 06 and an untouched BBBC028 shape |

Four of the five image types needed nothing that was not already held; two needed a source hunt,
and both hunts succeeded the same day. Details of what was built are under
[What was built](#what-was-built).

### A correction, because the first version of this count was wrong

The first draft of this document said sparse low-light had two unused groups and that the only
outstanding work was two bead downloads. **That was wrong.** Both sparse candidates —
`sparse_ssbd131_worm2_nuclei` and `sparse_ssbd474_root_hair_nuclei` — have already been read by
the natural-motion benchmark, and so has `brightfield_microbundle_type3_04`.

The mistake was in how the benchmarks were enumerated. Most benchmarks are laid out
`benchmark / image class / series`, so listing one level below the image class gives series
names. **The natural-motion benchmark is one level deeper** —
`natural_motion / image class / motion category / series` — so the same listing returned motion
categories such as `STATIC__NEAR_STATIC` and never reached the series at all. Four series were
invisible.

**The spent-material guard would have caught it.** `SealedTestSetBuilder.spentSeriesNames` walks
each benchmark to depth 3, which does reach the natural-motion series, and re-running it against
the real tree reports all three of the above as spent. A build attempted on that material would
have failed loudly rather than quietly producing an unsealed set. That is the guard doing
exactly the job it was written for, against a mistake made by the person who had just rewritten
it.

The lesson is worth keeping: **directory depth is not uniform across benchmarks, so any hand
count must mirror what the guard does rather than assume a layout.**

## Where each number comes from

Counted from the archives on disk and from the directory names under
`library/benchmark/v2/benchmarks/`, which is the record of what was actually run.

### DENSE_FLUOR — 2, acquired today

`smyllie_scn_clock` and `chromalive_hela_apoptosis`, staged under
`library/benchmark/v2/sealed_test_3_series/` with checksums in the download manifest. Full
record in `docs/dense_source_candidates.md`. The three older groups — `ssbd_197_bannai`,
`opencell_leonetti`, `watabe_pge2_fret` — are all spent.

### SPARSE_LOWLIGHT — 0 held, so a second source hunt was run

Every sparse group the project holds has been read:

| Group | Spent by |
|---|---|
| `ssbd_fig3a`, `ssbd_fig4a`, `ssbd_fig5a`, `ssbd_figs4` | controlled motion |
| `figshare_mda231_rfp`, `ssbd166_branch2` | locked test, natural motion |
| `takayama_egfr_tirf`, `hiroshima_aisis_singlemol` | second sealed set |
| `ssbd131_worm2`, `ssbd474_root_hair` | **natural motion** |

The last two were the ones the first draft counted as free. They are not.

**Two further facts about them, established before the mistake was found, that matter for any
replacement hunt.** Both are 8-bit, and neither has a 16-bit original hiding behind it: the
SSBD 131 distribution is 8-bit, and the SSBD 474 CZI reports `PixelType Gray8` and
`ComponentBitCount 8` in its own metadata. The project has accepted an 8-bit sparse record
before — `hiroshima_aisis_singlemol` carries exactly that caveat — so 8-bit is not
disqualifying here, but a 16-bit replacement would be strictly better. `ssbd131_worm2` also
distributes frames named `aligned_*`, meaning the series has already been registered, which
would make it useless for a natural-motion arm whatever its bit depth.

**A sparse source hunt was run the same day and found one.** It was reviewed, accepted and
built; the record is below.

### The sparse candidate — BioImage Archive S-BIAD1347

*Single-molecule imaging and molecular dynamics simulations reveal early activation of the MET
receptor.* **Licence CC0**, read from
`https://www.ebi.ac.uk/biostudies/api/v1/studies/S-BIAD1347`.

The live-cell single-molecule FRET movies are the relevant part:
`MET_InlB_smFRET_live_cells/HT/` and `/HH/`, 4000-frame stacks.

| Record | File | Seed plane |
|---|---|---|
| session 240701 | `HT/240701CS2cell1_01.tif` | 512x253 **uint16** uncompressed, 148 – 8445, **3227** grey levels, floor 148 |
| session 240712 | `HH/240712CS3cell1_09.tif` | 512x253 **uint16** uncompressed, 130 – 5740, **2109** grey levels, floor 130 |

**Why the two are independent:** the file names carry acquisition dates, and the live-cell
folders span five distinct sessions — 240701, 240706, 240708 and 240712. Two recordings from
different dates are different dishes on different days. Same laboratory and same instrument, so
the caveat matches the one already accepted for the Strack phase pair and the bead pair.

**Three things weighed before it was accepted.**

1. **It is a strict improvement on what sparse low-light has had.** 16-bit with a real camera
   offset, against the 8-bit distributions of `hiroshima_aisis_singlemol`, `ssbd131_worm2` and
   `ssbd474_root_hair`. Genuine single-molecule puncta over real read noise, and not
   pre-registered.
2. **The frame is a dual-view split.** 512x253 holds donor and acceptor side by side from a
   beam splitter, so the seed plane is two half-fields with a seam between them. That is
   honest sensor data and `Benchmark.seedPlane` takes the plane whole at its native size, so
   controlled recordings are unaffected. It was looked at: single-molecule puncta are clearly
   resolved over real read noise in the acceptor half.
3. **It is 253 pixels tall, and the natural-motion arm crops to 256.** That turned out not to
   be the deciding issue — `NativeSeriesFrames.crop` clamps the request to the smaller side, so
   253 would have been handled. The deciding issue is the seam: a centred crop straddles it, and
   a seam fixed to the camera does not drift with the specimen, so it would anchor a stability
   measurement toward reporting no movement. These two seed controlled motion only, the way
   `dense_aydin_ankrd11` was given no native path.

**Accepted on 2026-08-19.** Both records were downloaded at their exact byte counts
(1,036,984,105 and 1,036,984,107), checksummed, and staged under
`library/benchmark/v2/sealed_test_3_series/`. They seed controlled motion only, for the
beam-splitter reason in point 2: the seam is fixed to the camera rather than to the specimen, so
it does not drift with the sample and would anchor a stability measurement toward reporting no
movement.

## One consequence for the Newton work

Stage 4 of `docs/newton_refinement_plan.md` was run on 2026-08-19 against a **second opening of
the spent set**, because the material check at the time found a third set impossible. That
write-up is scrupulous about the weakness — "not an independent validation", "optimistically
biased by an amount nobody can measure" — and it no longer has to stand as the final answer.

Everything needed to replace it with a clean held-out number is written up for that reader in
**`docs/newton_stage4_notice.md`**, with a pointer placed in the plan's own Stage 4 status block.
It also carries the correction to the plan's material table, which counts sparse low-light as 2
and brightfield as 4 where the true figures are 0 and 3 — the same miscount made here and
described above.


## What an independent group is, reconciled

Three files carry something called `independent_group` and they do not agree. This is the
inconsistency `SealedTestSetBuilder.spentSeriesNames` declines to depend on, and it is worth
writing down once.

| Where | What it actually holds | Authority? |
|---|---|---|
| `benchmark_v2_download_manifest.csv` | An **asset label**, one per downloaded file. Names each OpenCell field separately (`leonetti_ankrd11`, `leonetti_sptssa`, …) | **No** |
| `benchmark_v2_series_manifest.csv` | The group used for **statistics**, read by `FullSelectorFactorialBenchmark.recordings` — and **incomplete**, see below | Only for statistics |
| `locked_test_manifest.csv`, `sealed_test_manifest.csv` | The **judgement made when the set was built** | **Yes** |

**The rule the built sets actually follow: an independent group is the unit that could have
gone wrong on its own.** It is a judgement about the acquisition, not a naming convention, and
the evidence for the rule is that both directions appear in the same file:

- *Separate acquisitions, separate groups* — `strack_pputida_01` against `strack_pputida_02`;
  `ssbd197_fig5a` against `ssbd197_fig4b_oer`; `microbundle_type3_03` against `_05`.
- *One acquisition cut into two views, one group* — `fiducial_cage_d5_view_a` and
  `fiducial_cage_d5_view_b` are both declared `cage_d5`, deliberately, because splitting one
  recording into two fields of view does not create a second chance to fail.
- *Many fields from one deposit, one group* — every OpenCell field is `opencell_leonetti`,
  which is why the five unused Aydin fields do not count as fresh material even though the
  download manifest gives each its own label.

So the download manifest's column should be read as "which asset is this", never as "may these
two appear in the same sealed set". Renaming it to `asset_group` would remove the trap, but it
is written by several benchmark writers and read by none of them, so the rename is a tidy-up
rather than a fix, and it is not done here.

### The series manifest is not incomplete by accident, and sealed series must stay out of it

An earlier version of this document treated the missing rows as an oversight and proposed
writing the third set's series into `benchmark_v2_series_manifest.csv` as the set was built.
**That was tried on 2026-08-19 and is wrong.** `BenchmarkV2ManifestTest` requires every row in
that manifest to point at a full development-series folder — `source/`, `comparisons/`,
`series.csv` and a method list — and requires headline-eligible rows to fall in one of
`development`, `validation` or `locked_test` with balanced counts per image class. Sealed
material has none of that structure and belongs to none of those splits. The rows were reverted.

That manifest describes the **development series library**. Provenance for sealed material goes
in `benchmark_v2_download_manifest.csv`, which is where the third set's ten sources are recorded,
and the set's own `sealed_test_3_manifest.csv` carries the independent groups.

**And the fallback turns out to be harmless for a sealed set.** Every sealed series is its own
independent group, one to one, so `groups.getOrDefault(series, series)` produces exactly the
right grouping — a different label for the same partition. The genuinely wrong case is the one
below, and it is not a sealed set.

### The one real consequence of the series manifest being incomplete

`FullSelectorFactorialBenchmark.recordings` looks each series up in
`benchmark_v2_series_manifest.csv` and **falls back to the series name when it is absent**, so
a missing row silently makes a recording its own independent group.

| Benchmark | Series | Missing from the manifest |
|---|---|---|
| `controlled_motion` | 20 | **0** |
| `locked_test` | 10 | 4 |
| `sealed_test` | 10 | 5 |
| `natural_motion` | 8 | 8 (these are motion categories, not series) |

**`controlled_motion` is complete, and that is the one that matters most** — it is where the
selector model was trained and where the estimator comparisons run, so no published number
depends on a fallback.

The one case where the fallback is actually wrong is in the locked set:
**`fiducial_cage_d5_view_a` and `fiducial_cage_d5_view_b` fall back to their own names, so any
grouped statistic over `locked_test` counts them as two independent records when the locked
set's own manifest declares them as one (`cage_d5`).** For the sealed set the fallbacks are
harmless — every one of its series is genuinely its own group, so a different string with the
same one-to-one mapping changes no grouping.

This is recorded, not repaired. `LockedTestReport` is frozen evidence and the locked set has
been spent; editing the manifest underneath it would change what a frozen result means. The
right time to fix it is when a third set is built, by writing its rows into the series manifest
as it goes.

## The eight sources that are ready

Verified seed planes, so a builder can be written against them the moment sparse is solved.

| Class | Series | Group | Seed source | Seed plane |
|---|---|---|---|---|
| PHASE | `phase_strack_lysobacter_03` | `strack_lysobacter_03` | `v2/supplementary_series/…/source/images` | 512x512 uint16, 434–1472, 913 levels |
| PHASE | `phase_strack_pveronii_03` | `strack_pveronii_03` | `v2/supplementary_series/…/source/images` | 512x512 uint16, 597–1707, 899 levels |
| BRIGHTFIELD_DIC | `brightfield_microbundle_type3_06` | `microbundle_type3_06` | `v2/supplementary_series/…/source` | 512x227 uint16, 2081–23942, 19594 levels |
| BRIGHTFIELD_DIC | `dic_bbbc028_ring_01` *(or steplike)* | `bbbc028_ring` | `v2/supplementary_series/…/source` | to check before use |
| DENSE_FLUOR | `dense_scn_smyllie_bmal1` | `smyllie_scn_clock` | `v2/sealed_test_3_series/…/source` | 512x512 uint16, 52–2648, 1537 levels |
| DENSE_FLUOR | `dense_chromalive_hela` | `chromalive_hela_apoptosis` | `v2/sealed_test_3_series/…/source` | 1900x1900 uint16, 27–4519, 3120 levels |
| FIDUCIAL_STATIC | `fiducial_cage_commercial_d3` | `cage_commercial_d3` | `v2/sealed_test_3_series/…/source` | 512x512 uint16, floor 1472 |
| FIDUCIAL_STATIC | `fiducial_cage_commercial_d4` | `cage_commercial_d4` | `v2/sealed_test_3_series/…/source` | 512x512 uint16, floor 1520 |

Eight of ten. The two sparse records are missing and cannot be filled from anything the project
holds.

## What was built

**Built and accepted on 2026-08-19: 40 controlled recordings, 6 natural-motion stacks, 0
failures.** `SealedTestSet3Builder` writes
`library/benchmark/v2/benchmarks/sealed_test_3/` and `…_3_natural/`, 258 MB and 55 MB.

Acceptance was checked without reading a single result — the script is kept at
`research/dense_source_probe/verify_sealed_set_3.py`:

| Check | Result |
|---|---|
| Ten series, two per image type | PASS |
| Forty controlled recordings, none missing | PASS |
| Manifest written with ten rows | PASS |
| Ten independent groups, all distinct | PASS |
| Ten curved-drift checksums, all distinct | PASS — duplicates would mean two series seeded from one file |
| Every row carries its caveat note | PASS |
| Six natural stacks (four sources seed controlled motion only) | PASS |
| Second sealed set untouched | PASS — everything under it still dated 2026-08-18 |
| No results file inside the new set | PASS |

The natural-motion categories came out `STATIC__NEAR_STATIC` for both phase records, the
microbundle and both bead records, and `OSCILLATING__MODERATE` for the SCN slice — which is what
a 6.5-day circadian recording of living tissue should look like next to a bead slide.

**The set is sealed. Nothing has been run against it and no result from it has been read.**

## What to do next

1. **Write the gates for this set before opening it**, in a new report class alongside
   `SealedTestReport`. Gates first, then open once.
2. ~~Write the third-set builder.~~ Done — `SealedTestSet3Builder`. It could not be
   `SealedTestSetBuilder` — that class owns
   `sealed_test`, and its spent-material guard now excludes only its own roots, so a successor
   pointed at new roots will correctly see the second set's material as spent. Copying the
   class and changing `ROOT`/`NATURAL_ROOT` is the intended path, and
   `SpentMaterialGuardTest` already covers that case.
3. **Leave the series manifest alone.** See the section above: sealed series do not belong in
   it, and the grouping fallback is harmless for a sealed set.
4. **Declare the compromises in the set's own manifest**, as both previous sets did — done, and
   they are carried in the `note` column of `sealed_test_3_manifest.csv`.

## Where the evidence lives

| What | Where |
|---|---|
| The dense fluorescence hunt and its two records | `docs/dense_source_candidates.md` |
| What the second sealed set spent | `library/benchmark/v2/benchmarks/sealed_test/summaries/sealed_test_manifest.csv` |
| What the locked set spent | `library/benchmark/v2/benchmarks/locked_test/summaries/locked_test_manifest.csv` |
| The spent-material guard and its tests | `SealedTestSetBuilder.spentSeriesNames`, `SpentMaterialGuardTest` |
| Why a third set is wanted at all | `docs/newton_refinement_plan.md`, Stage 4 |
