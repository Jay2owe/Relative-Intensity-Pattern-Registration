# Joint sealed set — Stage 1 record

Serves `docs/pairwise_estimator_axis_plan.md` Stage 1, which after the decision recorded in
`docs/sparse_low_light_gap_findings.md` section 7 is now the only sealed set being built. Written
2026-08-18. **Stage 1 is complete.** Ten series collected and verified, 40 sealed recordings built,
nothing read.

## Specification

Two independent records per image type, five types, ten series, four standard movement paths each,
40 sealed recordings. No `independent_group` shared with any development or existing locked series.

"Used nowhere" was verified against every benchmark on disk — `controlled_motion`, `locked_test`,
`locked_test_natural` and `natural_motion` — not against the series manifest alone. The natural-motion
benchmark has read four sparse records and four of each other type that the series manifest does not
mark as spent.

## Position by image type

| Image type | Record 1 | Record 2 | Collection needed |
|---|---|---|---|
| BRIGHTFIELD_DIC | `microbundle_type3_05` — on disk | `bbbc028_hangar` — on disk | none |
| PHASE | `strack_pputida_02` — on disk | `strack_rahnella_02` — on disk | none, but see caveat |
| DENSE_FLUOR | `opencell_leonetti` (Aydin) — on disk | `389-Watabe-PGE2discharge` | 1 download |
| SPARSE_LOWLIGHT | `439-Takayama-dynamicsEGFR` | `192-Hiroshima-SingleMolDyn` | 2 downloads |
| FIDUCIAL_STATIC | `cage_commercial_d1` | `cage_commercial_d2` | 2 downloads, large |

Five downloads, roughly 3.5 GB, of which 3.2 GB is the two fiducial records.

## Candidates to collect

All licences below were read from the dataset page, not inferred.

| Record | Source | Licence | URL | Size |
|---|---|---|---|---|
| `takayama_egfr_tirf` | SSBD 439-Takayama-dynamicsEGFR | CC BY 4.0 | `https://ssbd.riken.jp/data/439-Takayama-dynamicsEGFR/source/Fig1A_MbCD_withEGF_2.zip` | 42.4 MB |
| `hiroshima_aisis_singlemol` | SSBD 192-Hiroshima-SingleMolDyn | CC BY | `https://ssbd.riken.jp/data/192-Hiroshima-SingleMolDyn/source/2B-2Y2016M5D23h10m7s37AD_CAM1.zip` | 20.2 MB |
| `watabe_pge2_fret` | SSBD 389-Watabe-PGE2discharge | CC BY 4.0 | `https://ssbd.riken.jp/data/389-Watabe-PGE2discharge/source/FigS1_HeLa_ctrl.zip` | 187.0 MB |
| `cage_commercial_d1` | Zenodo 15407010 | CC BY 4.0 | `https://zenodo.org/api/records/15407010/files/d1_commercial_beads_no_autofocus.tif/content` | 1.6 GB |
| `cage_commercial_d2` | Zenodo 15407010 | CC BY 4.0 | `https://zenodo.org/api/records/15407010/files/d2_commercial_beads_no_autofocus.tif/content` | 1.6 GB |

Checksums are not listed because they must be computed from the downloaded bytes and recorded in
`benchmark_v2_download_manifest.csv` at fetch time, to the standard the existing rows use.

### The two sparse candidates

Both are single-molecule fluorescence: genuine sparse punctate low-light time series of real sensor
data, which is what section 1 of the sparse findings says the current development material is not.
They are from different laboratories, different instruments and different acquisition systems.

They are, however, both EGFR in CHO-K1 cells. That is narrower biological diversity than ideal. It is
recorded rather than hidden, and it is a different weakness from the one that spoiled the previous
sparse set — these are four independent real acquisitions of low-light pixels, not four renderings of
one.

The existing development sparse material covers nuclei (`figshare_mda231_rfp`, `ssbd166_branch2`,
`ssbd131_worm2`, `ssbd474_root_hair`), so single-molecule material adds a modality the project has
never tested rather than duplicating one.

### The fiducial candidates, and the caveat that goes with them

The project had exhausted its fiducial supply: all five `cage_d1`–`cage_d5` acquisitions are used, and
the existing locked set already had to cut `cage_d5` into two overlapping fields of view and record
them as not independent of each other.

Zenodo record 15407010 turns out to contain a second, unused set of five bead acquisitions taken on a
**commercial** microscope — `d1_commercial_beads_no_autofocus.tif` through `d5` — alongside the
`rocs` homebuilt ones the project downloaded. These have never been touched.

**Caveat to record in the manifest:** same published record and same laboratory as the development
fiducial material, different microscope. That is weaker independence than a different laboratory would
give, and stronger than the `cage_d5` two-view split the existing locked set accepted. It is the best
fiducial material available without a new source hunt, and the estimator plan cares about fiducial
specifically — it is one of the two image types where the area estimator wins on both existing sets —
so the caveat matters and is stated rather than buried.

### The phase caveat

Both phase candidates are Strack fields of view. Every phase record in this project comes from that one
Zenodo deposit, so a second phase laboratory does not exist in the current inventory. `KER_C2C12`
(OSF, `10.17605/OSF.IO/YSAQ2`) is listed in the dataset manifest as a candidate needing licence
verification and would break the single-laboratory position if that verification passes.

## Rejected candidates

| Candidate | Reason |
|---|---|
| SSBD 43-Takai-SubcellStructONL (ONL-PTS1 luminescence peroxisomes) | Licence is CC BY-NC-SA. Non-commercial and share-alike, incompatible with this project's standard of CC BY / CC0. |
| ConfocalCheck (PMC3818239 datasets S2–S4) | Three archives from one Leica SP5II session at three objectives — one independent record, not three. Leica `.lif` needs Bio-Formats conversion, and the bead time-lapse is embedded inside larger quality-control archives rather than standing alone. |

## What arrived

Every download completed at the exact declared byte count. The seed plane of each was checked against
the criterion in the last section before any recording was built.

| Series | Format as distributed | Seed plane | Verdict |
|---|---|---|---|
| `sparse_takayama_egfr_tirf` | TIFF, 100 frames, 768x768 | **uint16**, greyscale, uncompressed, values 29–244 | raw sensor data; the first genuinely low-light sparse seed in the project |
| `sparse_hiroshima_aisis_singlemol` | uncompressed DIB AVI, 100 frames, 512x512 | **uint8**, greyscale, full 0–255 range | real acquisition — frame-to-frame noise and visible bleaching — but distributed at 8 bits, not sensor depth. Caveat recorded. |
| `dense_watabe_pge2_fret` | Nikon ND2, 4 channels x 21 time points, 1152x1152 | **uint16**, greyscale, 5260 distinct values, camera offset 4278 | raw sensor data |
| `fiducial_cage_commercial_d1` | TIFF, 3000 frames, 512x512 | **uint16**, greyscale, uncompressed, camera offset 1520 | raw sensor data |
| `fiducial_cage_commercial_d2` | TIFF, 3000 frames, 512x512 | **uint16**, greyscale, uncompressed | raw sensor data |

Two conversions were needed and both are in `prepare_sealed_test_sources.groovy`, run through Fiji so
Bio-Formats is on the classpath: the ND2 and the AVI were repacked to plain TIFF stacks. Neither moves
a pixel value.

### The dense record the locked set had to skip

`LockedTestSetBuilder` records that the one wholly separate dense record on disk — the OpenCell
`ZENODO_AYDIN` material — is "a TIFF variant ImageJ cannot open", so the locked set had to fall back to
a second acquisition from the same published record as its development dense series.

That variant is zstd compression (TIFF tag 50000). It is now repacked losslessly to an uncompressed
TIFF and verified pixel-identical to the original, so `dense_aydin_ankrd11` can seed a recording after
all. The sealed set therefore gets a genuinely independent dense pair — OpenCell and Watabe — where the
locked set could not.

## What was built

`SealedTestSetBuilder` — the successor to `LockedTestSetBuilder`, which cannot be reused because the
locked set has been spent.

- 40 controlled recordings under `library/benchmark/v2/benchmarks/sealed_test/`, ten series times four
  movement paths, zero failures.
- 8 natural-motion stacks under `sealed_test_natural/`. Two series have none: `dic_bbbc028_hangar_01`
  is a single field, and `dense_aydin_ankrd11` is a volume (see below).
- `sealed_test/summaries/sealed_test_manifest.csv`, carrying the independent group, seed path, native
  path, caveat and the SHA256 of each curved-drift input. All ten checksums are distinct, which is the
  check that two series did not accidentally seed from the same file.
- Five new rows in `benchmark_v2_download_manifest.csv` with licence, byte count and SHA256. Every row
  in that manifest now has a checksum.

The builder refuses to run if any source name already appears under `controlled_motion`, `locked_test`,
`locked_test_natural` or `natural_motion`. The series manifest is not the authority for this: it does
not record that the natural-motion benchmark has already read several series, and a sealed set built on
material that has been read is not sealed.

### One source excluded from the natural arm after the first build

The first build classified `dense_aydin_ankrd11` as `JUMP_DOMINATED__LARGE` while every genuine time
series came out `STATIC__NEAR_STATIC`. The download manifest records that asset as a "dense
fluorescence volume": its 106 planes are focus, not time, so profiling them as a series measures how
fast the specimen leaves focus and reports it as stage movement. Its native path is now empty and the
stack was deleted. One plane of it remains a perfectly good controlled-motion seed, which is the use
the manifest always intended.

## The seed-plane check, and why it is the one that matters

Controlled recordings shift a single seed plane 48 times, so a source's frame count barely matters.
What matters is that the plane is raw sensor data.

`Benchmark.seedPlane` silently takes `ColorProcessor.getChannel(3, ...)` — the blue channel — from any
colour seed, so a colour source fails quietly rather than loudly. That is how the previous sparse
material passed unnoticed. Every seed in this set was checked to be greyscale at the acquisition's own
bit depth, or greyscale replicated into an RGB container, before any recording was built.

## Still open

1. `KER_C2C12` (OSF, `10.17605/OSF.IO/YSAQ2`) licence verification, which would break the
   single-laboratory position on phase. Not required for Stage 1 to be complete; recorded as a known
   weakness of the set.
2. The set stays sealed until the estimator plan reaches its Stage 5. Nothing has been run against it,
   and no result from it has been read.
