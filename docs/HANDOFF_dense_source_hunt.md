# Handoff — find one more dense fluorescence source

Paste this whole file as the opening prompt of a fresh session. It is written to be read cold.

---

You are picking up work in `C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\Log-Ratio Registration`, a Fiji/ImageJ time-series registration plugin. Not a git repository.

## Your job, in one sentence

**Find one publicly available dense fluorescence microscopy source, licensed CC BY or CC0, containing at least two independent acquisitions suitable as registration seeds — and write up the candidates with licences and checksums. Download nothing large and build no recordings until the write-up is reviewed.**

## Why this matters

The plugin's accuracy claims rest on **sealed test sets**: forty recordings from ten source series that nothing in development has ever touched, with pass/fail limits written down before the set is opened, opened exactly once. Two such sets have been built and both are spent. A third is needed if `docs/newton_refinement_plan.md` reaches its Stage 4.

Counted on 2026-08-19, four of the five image types have enough unused material for a third set. **Dense fluorescence has none, and it is the only blocker.** All three dense groups the project holds — `opencell_leonetti`, `ssbd_197_bannai` and `watabe_pge2_fret` — are used. The full count is in `docs/newton_refinement_plan.md` under "The material check".

## The one concept you must get right

**"Independent group", not "file" and not "field of view".** Two recordings are independent only if they come from separate acquisitions that could have gone wrong separately — different specimens, sessions or instruments. The project already holds five unused Aydin fields of view and one unused SSBD-197 panel, and **none of them count**, because they sit inside groups that are already spent. Using them would make the third sealed set share an independent group with the second, which is the one thing the standard forbids.

So: **a new source, not a new file from an old source.**

## What has already been tried, so you do not repeat it

Used and spent, dense fluorescence:

| Group | Source |
|---|---|
| `ssbd_197_bannai` | SSBD 197, calcium-signalling fluorescence; six panels used across development and the first sealed set |
| `opencell_leonetti` | Zenodo/Aydin OpenCell denoising examples; one field used in the second sealed set |
| `watabe_pge2_fret` | SSBD 389-Watabe-PGE2discharge; used in the second sealed set |

Rejected previously, with reasons that still stand: **SSBD 43-Takai-SubcellStructONL** (CC BY-NC-SA — non-commercial and share-alike, incompatible with this project's CC BY / CC0 standard), and **ConfocalCheck** (three archives from one session at three objectives, so one independent record rather than three).

Three leads are already named in `library/benchmark/benchmark_v2_dataset_manifest.csv` and none has been explored:

- `PUBLIC_FLUOR_TIMELAPSE` — "additional CC BY fluorescence time lapses", never inventoried;
- `FMD` — Fluorescence Microscopy Denoising dataset, licence check outstanding;
- `CTC_2DT` — Cell Tracking Challenge 2D+time, needs permission.

Start there, but do not stop there. SSBD (`ssbd.riken.jp`), BioImage Archive, IDR and Zenodo have all yielded usable material for this project before.

## The standards a candidate must meet

1. **Licence read from the dataset page, not inferred.** CC BY or CC0. Record the exact wording and the URL you read it from. A licence that is NC or SA is a rejection, and rejections get recorded with their reason rather than dropped.
2. **At least two independent acquisitions**, and say plainly what makes them independent. If they are two fields from one session, say so — that is a caveat the project has accepted before, with the caveat written into the manifest, but it must never be silent.
3. **Raw sensor data, and this is the check that has caught real problems.** A controlled recording shifts a single seed plane 48 times, so frame count barely matters and pixel provenance is everything. The seed plane must be greyscale at the acquisition's own bit depth. **`Benchmark.seedPlane` silently takes the blue channel of any colour image**, so a colour source fails quietly rather than loudly — that is exactly how a previous sparse set passed unnoticed and later had to be withdrawn. Check the bit depth, the channel count and the value range before recommending anything.
4. **A checksum computed from the downloaded bytes**, recorded at fetch time. Not from the page, not from a sidecar.

## What to produce

A document, `docs/dense_source_candidates.md`, in the style of `docs/joint_sealed_set_stage1_candidates.md` — read that first; it is the model, and it shows the level of caveat the project expects. It should carry:

- a table of candidates with source, licence, URL, size and independence unit;
- the seed-plane verdict for each, with bit depth and channel count stated;
- rejections with reasons;
- your recommendation of two, and the caveat that goes with them;
- what remains unverified.

**Download the smallest thing that lets you check the seed plane**, not the whole record. Several of the useful archives are hundreds of megabytes to gigabytes, and the point of this stage is to decide what is worth fetching.

## Two traps specific to this task

1. **`SealedTestSetBuilder.checkNotSpent` does not know about the sealed sets.** It refuses material already used under `controlled_motion`, `locked_test`, `locked_test_natural` and `natural_motion` — but **not** under `sealed_test` or `sealed_test_natural`. So it will happily let a third set reuse material the second set already spent. Whoever builds the third set must add those two names to that array first. Flag it in your write-up even though building is not your job.

2. **The series manifest is not the authority on what has been used.** `library/benchmark/benchmark_v2_series_manifest.csv` does not record everything the benchmarks have read, and it is missing several series entirely — the second sealed set's sources were added through `benchmark_v2_download_manifest.csv` instead. The authority is the directory names actually present under `library/benchmark/v2/benchmarks/`. Check against those.

## Environment notes

- **A hook blocks recursive searches over this Dropbox tree** unless the command has a file-type filter (`--include=`/`-name`) *and* a `timeout <=120` prefix. Prefer the Grep tool with a narrow `path` and `glob`.
- `mvn` is not on PATH: `/c/Users/Owner/.m2/wrapper/dists/apache-maven-3.9.9/8e74001100ff70d6af083c5511fcc5ec49282d7017cde82c3698eee8fdf86698/bin/mvn -o`. You probably will not need it.
- Another agent works in this tree and can wipe `target/` mid-run.
- Fiji is available if a format needs Bio-Formats conversion; `prepare_sealed_test_sources.groovy` shows how the last two conversions were done, and neither moved a pixel value.

## Definition of done

- `docs/dense_source_candidates.md` exists and names at least two independent CC BY or CC0 dense fluorescence acquisitions, or states plainly that none could be found and what was searched;
- every licence is quoted from the page it was read on, with the URL;
- every recommendation has a seed-plane verdict with bit depth and channel count;
- rejections are recorded with reasons;
- nothing under `library/benchmark/v2/benchmarks/sealed_test*/` has been touched.

**If the honest answer is that no suitable source exists,** say so and stop. That is a complete result: it means a third sealed set cannot be built from public material, and the Newton plan's Stage 4 is unreachable — in which case the right outcome is to run its Stages 0 to 2 for the knowledge and ship nothing. An unvalidated model is worse than a slow one.
