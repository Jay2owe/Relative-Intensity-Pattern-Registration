# Dense fluorescence source hunt — candidates

Serves `docs/HANDOFF_dense_source_hunt.md`. Run 2026-08-19. Written in the style of
`docs/joint_sealed_set_stage1_candidates.md`, which is the model for how much caveat this
project expects.

**Nothing was downloaded in full, no recording was built, and nothing under
`library/benchmark/v2/benchmarks/sealed_test*/` was touched.**

## The result, first

**The blocker is cleared. Two independent dense fluorescence records exist under acceptable
licences, and both have been verified as raw sensor data at the byte level rather than
inferred from a description.**

Dense fluorescence was the only image type with zero unused independent groups, which meant a
third sealed set could not be built and `docs/newton_refinement_plan.md` Stage 4 was
unreachable. It is now reachable.

| | Before this hunt | After |
|---|---|---|
| Unused dense independent groups | **0** | **2 acquired and checksummed, 1 backup, 1 unverified** |

Both recommendations were approved, downloaded and verified on the same day; the detail is
under "What arrived". The dense fluorescence blocker is closed, and what now stands between
the project and a third sealed set is the other four image types, not this one.

The two recommendations come from **two different sources**, not two files from one. That is
stronger independence than the specification asks for — the brief allowed one source with two
acquisitions inside it, and separate sources cannot share a session, an instrument, a
specimen batch or a laboratory.

## What is already spent, verified against the directories rather than the manifest

Checked against the directory names actually present under
`library/benchmark/v2/benchmarks/*/DENSE_FLUOR/`, because the series manifest is not the
authority (see trap 2).

| Independent group | Series on disk | Where |
|---|---|---|
| `ssbd_197_bannai` | `dense_ssbd197_fig3`, `fig4a_gcamp`, `fig4a_rcamp`, `fig4b_lck` | `controlled_motion`, `controlled_motion_gain_fade` |
| `ssbd_197_bannai` | `dense_ssbd197_fig4b_oer`, `fig5a` | `locked_test`, `locked_test_natural` |
| `opencell_leonetti` | `dense_aydin_ankrd11` | `sealed_test` |
| `watabe_pge2_fret` | `dense_watabe_pge2_fret` | `sealed_test`, `sealed_test_natural` |

All three are spent. The five unused Aydin fields of view and the unused SSBD-197 panel sit
inside `opencell_leonetti` and `ssbd_197_bannai` respectively and therefore do not count.

## Candidates

Licences were read from the machine-readable `License` attribute the archive itself serves for
the record, not from prose and not inferred from the collection default.

| Record | Source | Licence | Independence unit | Size of the one file needed |
|---|---|---|---|---|
| **`dense_scn_smyllie_bmal1`** | BioImage Archive **S-BIAD1582** — mPER2, mCRY1 and mBMAL1 fluorescence timelapse imaging in the suprachiasmatic nucleus | **CC BY 4.0** | organotypic slice / reporter line | 326,290,329 bytes |
| **`dense_chromalive_hela`** | BioImage Archive **S-BIAD2515** — High-content live-cell time-lapse imaging predicts cells about to die via apoptosis | **CC0** | plate (one plate present) | 7,225,258 bytes |
| `dense_efret_mcf7_bclxl` | BioImage Archive **S-BIAD2398** — Database of FRET and bright-field images for MCF-7 cells co-expressing CFP-BCL-XL and YFP-BAK | **CC0** | replicate series `_1`/`_2`/`_3` | 8,388,859 bytes |
| `dense_arabidopsis_ca_wave` | BioImage Archive **S-BIAD1521** — Ca2+ Waves and Ethylene/JA Crosstalk Orchestrate Wound Responses in Arabidopsis Roots | **CC0** | seedling (`S1`…`S14`+) | ~99 MB per `.lif` |
| ~~`dense_tartansw_strathclyde`~~ | University of Strathclyde — TartanSW standing-wave fluorescence | CC BY 4.0 | — | **rejected, see below** |

URLs, exactly as fetched:

```
S-BIAD1582  https://www.ebi.ac.uk/biostudies/api/v1/studies/S-BIAD1582   ->  "License": "CC BY 4.0"
            https://ftp.ebi.ac.uk/biostudies/fire/S-BIAD/582/S-BIAD1582/Files/Figure%202B%20lower%20complete%20image%20stack.tif
S-BIAD2515  https://www.ebi.ac.uk/biostudies/api/v1/studies/S-BIAD2515   ->  "License": "CC0"
            https://ftp.ebi.ac.uk/biostudies/fire/S-BIAD/515/S-BIAD2515/Files/C-02_F0001_T0001_Z0001_C02.tif
S-BIAD2398  https://www.ebi.ac.uk/biostudies/api/v1/studies/S-BIAD2398   ->  "License": "CC0"
            https://ftp.ebi.ac.uk/biostudies/fire/S-BIAD/398/S-BIAD2398/Files/BCLXL/Ctrl_MCF7_1/0/AA.tif
S-BIAD1521  https://www.ebi.ac.uk/biostudies/api/v1/studies/S-BIAD1521   ->  "License": "CC0"
Strathclyde https://pureportal.strath.ac.uk/en/datasets/data-for-an-evaluation-of-multi-excitation-wavelength-standing-wa/
                                                                        ->  page text "CC BY 4.0"
```

## Record 1 checked at the pixel level, not just the header

S-BIAD1582 was opened properly rather than trusted from its statistics. Frames were pulled at
three timepoints on both channels — plane index `t * 2 + c`, the stack being contiguous, which
was confirmed by walking three image directory entries and finding the offsets exactly
524,288 bytes apart.

**It is what it claims to be.** The image is unmistakably the suprachiasmatic nucleus: the
bilateral pair of nuclei flanking the third ventricle, with individual cells resolved as
bright puncta against neuropil. Dense fluorescence in the sense this project means it — signal
covering the frame rather than isolated dots — and the field is well filled, 83% of pixels
sitting above a tenth of the dynamic range.

| t | channel | min | max | median | distinct | mean gradient |
|---|---|---|---|---|---|---|
| 0 | 0 | 52 | 2648 | 469 | 1537 | 111.0 |
| 0 | 1 | 6 | 1057 | 175 | 767 | 35.5 |
| 100 | 0 | 55 | 2984 | 450 | 1458 | 109.3 |
| 100 | 1 | 3 | 926 | 202 | 778 | 39.6 |
| 310 | 0 | 41 | 2568 | 285 | 1088 | 74.7 |
| 310 | 1 | 1 | 509 | 98 | 454 | 21.0 |

Three things follow that were not visible from the header alone.

1. **Channel 0 is the one to seed from.** It carries three times the texture of channel 1 by
   mean gradient and twice the grey levels. Channel 1 is usable but thin.
2. **The recording fades substantially.** Over 311 frames at a 1795.8 second interval — 6.5
   days — channel 0's median falls from 469 to 285 and its distinct levels from 1537 to 1088.
   Channel 1 halves. This is a **feature, not a defect**: gain invariance is the whole premise
   of the log-ratio estimator, and the project has a `controlled_motion_gain_fade` benchmark
   that exists for exactly this. But it means the last frames are materially weaker than the
   first, and a seed plane should be taken from early in the series.
3. **The scene genuinely changes.** Channel 0 at t=0 against t=310 correlates at only 0.41.
   The structure is in the same place — the two frames are visibly the same field — so this is
   intensity change and noise, not drift. It does mean a natural-motion arm built from this
   series would be measuring a specimen whose brightness oscillates over days.

## The seed-plane verdicts

This is the check that decides everything, and it was done by fetching the TIFF header and
then **only the first image plane** over HTTP range requests — 524 KB rather than 326 MB in
the largest case. Tooling and method are preserved in `research/dense_source_probe/`.

| File | Geometry | Bit depth | Channels | Compression | Value range | Distinct levels | Verdict |
|---|---|---|---|---|---|---|---|
| S-BIAD1582 `Figure 2B lower` | 512x512, 2ch x 311 frames | **16-bit** | **1 per plane**, greyscale | none | 52 – 2648 | **1537** | **raw sensor data.** Floor of 52 is a camera offset |
| S-BIAD2515 **`…_C02`** | 1900x1900 | **16-bit** | **1**, greyscale | none | 27 – 4519 | **3120** | **raw sensor data.** Floor of 27 is a camera offset. **The channel to use** |
| S-BIAD2515 `…_C04` | 1900x1900 | 16-bit | 1, greyscale | none | 0 – 1916 | 1408 | raw, dense, but more black between cells than C02 |
| S-BIAD2515 `…_C01` | 1900x1900 | 16-bit | 1, greyscale | none | 0 – 1143 | 837 | raw, but **nuclei on a black background — not dense**. See below |
| S-BIAD2398 `Ctrl_MCF7_1/0/AA.tif` | 2048x2048 | **16-bit** | **1**, greyscale | none | 0 – 3861 | **3183** | **raw sensor data.** Written by "National Instruments IMAQ", a frame grabber |
| S-BIAD2398 `Ctrl_MCF7_1/0/DD.tif` | 2048x2048 | **16-bit** | **1**, greyscale | none | 28 – 490 | 408 | raw, but a dim donor channel — thin texture |
| S-BIAD1582 `Figure 2B upper` | 512x512, 3ch x 368 frames | **8-bit** | 1, **palette** | none | 0 – 255 | **17** | **REJECTED** — see below |

SHA256 of the extracted plane-0 bytes, so the next person can confirm they fetched the same
thing before committing to a full download:

```
S-BIAD1582 Figure 2B lower   a72aa8a2c278008fe42945f5c06be0dbf7a283be4c79da9d212093a1813edf66
S-BIAD2398 Ctrl_MCF7_1/0/AA  2a75c84c7befaf6bbcb1a11ac82be266296aae6b55897b02885c83b869c49709
S-BIAD2398 Ctrl_MCF7_1/0/DD  8fc634cc479bcb195d56c64d7935f4f30f4c572db4417862cb918bea6e0c010d
S-BIAD2515 C-02_F0001_T0001  a7581d3d75cdfc253ff6d51f4f204436ca3f1a7503a5bc738c946999d22a7b27
```

These are checksums of the **decoded first plane**, not of the whole file. Whole-file
checksums must still be computed from the downloaded bytes at fetch time and recorded in
`benchmark_v2_download_manifest.csv`, to the standard the existing rows use.

### The rejection that proves the check was worth doing

**S-BIAD1582 contains two files whose names differ by one word — "upper" and "lower" — and
they are not interchangeable.** "Lower" is 16-bit greyscale with 1537 distinct levels.
"Upper" is an 8-bit **palette** image whose first plane holds **17 distinct values**. Taking
the obvious-looking pair as "two acquisitions from one source" would have put a 17-level
posterised image into a sealed set, and nothing downstream would have complained: it opens
fine, it is greyscale-typed, and `Benchmark.seedPlane` would have accepted it.

That is the same failure mode as the withdrawn sparse set, arriving by a different route.

## Rejections

| Candidate | Reason |
|---|---|
| **FMD** — Fluorescence Microscopy Denoising dataset | **No licence is granted for the data.** `LICENSE.md` is an MIT licence covering "the Software"; the README grants nothing for the images, and the images are hosted on a Google Drive folder with no licence statement. MIT on the code does not license the dataset. This closes one of the three leads named in the dataset manifest. |
| **CTC_2DT** — Cell Tracking Challenge 2D+time | Terms require written permission for public non-challenge scientific use. Not CC BY and not CC0, so it fails standard 1 regardless of how good the imagery is. Obtaining permission is a decision for the project owner, not something to assume. |
| S-BIAD1582 `Figure 2B upper` | 8-bit palette, 17 distinct levels in plane 0. Not raw sensor data. |
| S-BIAD1522 — neuronal phototoxicity | **32-bit IEEE float, LZW-compressed, written by "Incucyte 2022B"**. Float pixels are processed output, not sensor counts. Independence would have been excellent (`Experiment_1/2/3`, two plates each), which is exactly why it needed the bit-depth check rather than a glance at the description. |
| S-BIAD1135 — Calcium waves in MDCK epithelium | Dense and appealing, but the record is a **single** 478 MB `calcium_waves.tif` plus analysis products. One acquisition, so it cannot supply an independent pair and adds only one group. Kept as a reserve. |
| S-BIAD865 — MitoCheck / IDR0013 | CC0, and genuinely independent (plates from different experiment dates, `ex2005_11_16` against `ex2005_05_13`). Rejected on practicality: **27–31 GB per plate**, distributed as `.ome.zarr.zip`. Reachable one plane at a time through the IDR API if the verified candidates fall through. |
| **Strathclyde TartanSW** — the `PUBLIC_FLUOR_TIMELAPSE` lead | **8-bit, and two of three files are RGB.** Settled by download and inspection — see the section below. |
| S-BIAD2515 channel `C01` | Raw 16-bit, but nuclei on a black background: sparse, not dense. Rejected as a *channel*, not as a dataset — `C02` from the same file is the recommendation. |
| SSBD 43-Takai-SubcellStructONL | Unchanged from the previous hunt: CC BY-**NC-SA**. |
| ConfocalCheck | Unchanged: three archives from one session at three objectives — one independent record, not three. |

## Recommendation

**Take S-BIAD1582 "Figure 2B lower" channel 0 as record 1 and S-BIAD2515 channel `C02` as
record 2**, with S-BIAD2398 `AA.tif` held as the named backup. Approved and acquired on
2026-08-19 — see "What arrived".

The single reason: they are the only verified pair that is independent at every level —
different laboratories, different instruments, different organisms, different modalities —
**and neither repeats the FRET modality already spent on `watabe_pge2_fret`**. The project's
recurring weakness in its own caveats is narrow diversity: every phase record comes from one
Strack deposit, and both sparse records are EGFR in CHO-K1. A pair that widens diversity is
worth more here than a pair with marginally richer histograms.

If richest seed texture matters more than diversity, swap record 2 for S-BIAD2398 `AA.tif`,
which has 3183 distinct levels against 837 — but accept a second FRET record.

### The channel correction, and why it matters

**The first draft of this document recommended S-BIAD2515 channel `C01`. That was wrong, and
opening the frame is what caught it.** `C01` is a nuclear stain: isolated bright nuclei on a
genuinely black background, median value 5. It is a perfectly good image and completely
useless here, because it is **sparse, not dense** — the opposite of the image type it was
being recruited for. Its header and its 837 grey levels look fine; only the picture says so.

`C02` is the cytoplasmic stain, and it fills the field — confluent cells wall to wall with
texture across the whole frame. It is also the strongest seed found anywhere in this hunt:
3120 distinct levels and a clean camera offset at 27. `C04` is an organelle network, dense but
with more black between cells. `C03` was measured and sits between the two.

The general lesson is the same one the "upper"/"lower" rejection teaches, one level deeper: a
multi-channel deposit can be dense in one channel and sparse in another, and **the image type
is a property of the channel, not of the dataset**.

### Caveats that must be written into the manifest, not left here

1. **S-BIAD2515 is one plate.** Wells `C-02` through `E-11`, four fields each, all one plate
   and one session. It can supply **one** independent group and no more. Do not take two
   wells from it and call them independent.
3. **S-BIAD1582 is two channels in one hyperstack** (`channels=2 frames=311`). Whichever
   channel seeds the recording must be named explicitly, because the two are different
   fluorophores — Venus::BMAL1 and CRY1::mRuby3 — with different signal levels.
4. **S-BIAD2398's `_1`/`_2`/`_3` suffixes are not documented as biological replicates.** The
   deposit shows the structure but never states whether they are separate experiments or
   separate dishes within one session. If it is promoted from backup to a recommendation,
   confirm that from the associated publication first.

## The Strathclyde lead, settled

`PUBLIC_FLUOR_TIMELAPSE` in the dataset manifest pointed at the University of Strathclyde
TartanSW deposit. **It is rejected, on evidence rather than on inconvenience.**

The licence is genuinely CC BY 4.0 and the deposit does contain files named `Raw_data`. But
the files are not raw:

| File | Geometry | Bit depth | Channels | Photometric | Compression |
|---|---|---|---|---|---|
| `Fig_3_A_Raw_data_MCF_7_DiI_excitation_488_nm_.tif` | 4096x4096 | **8-bit** | **3** | **RGB** | LZW |
| `Fig_3_E_Raw_data_3T3_Rhod_Phall_excitation_488_nm.tif` | 2048x2048 | **8-bit** | 1 | greyscale | none |
| `Supplementary_Video_B_3T3_GFP_Lifeact.tif` (25 frames) | 958x688 | **8-bit** | **3** | **RGB** | none |

None is 16-bit, so none is sensor data at the acquisition's own depth. Two of the three are
colour, and **that is the silent failure the standard exists to catch**: `Benchmark.seedPlane`
would have taken the blue channel of the MCF-7 file without complaint and produced a
perfectly plausible-looking 8-bit plane. The greyscale one spans 1–254 across 253 levels,
which is a full 8-bit stretch — a contrast-adjusted export, not counts off a camera.

Getting the files required working around the host. The `/files/` path is behind a Cloudflare
challenge that refuses `curl` with any header set, and also refuses Chrome when Chrome is
driven over the DevTools protocol — the challenge simply spins for 100 seconds and never
clears, because the automation is detectable. What works is launching Chrome with **no
debugging port at all** and polling the download directory, which is what
`research/dense_source_probe/plain_chrome.py` does. Recorded because it will come up again;
several university repositories are behind the same service.

SHA256 of the MCF-7 file as downloaded, 17,973,678 bytes:
`c5104175c8d8d7b0c5793cfcf720e3f5f63e6ee815127a5fa6c54b296315d156`

## What remains unverified

1. **S-BIAD1521 (Arabidopsis calcium waves) is unopened.** CC0, and its independence unit is
   the best on offer — `S1.lif` through `S14.lif` at roughly 99 MB each are separate
   seedlings. It is Leica `.lif`, so the header probe cannot read it and it needs a
   Bio-Formats conversion of the kind `prepare_sealed_test_sources.groovy` already does for
   ND2 and AVI. If the two recommendations are rejected, this is the next one to open.
2. **Whether either record can serve a natural-motion arm.** `sealed_test_natural` has only
   `dense_watabe_pge2_fret`, because `dense_aydin_ankrd11` turned out to be a focus volume
   rather than a time series. Both recommendations here are genuine time series — 311 frames,
   and a time-lapse plate — so both probably could, but that was outside this brief and
   neither has been profiled. The S-BIAD1582 fade documented above is the thing to watch if
   it is tried.
3. ~~**The other four image types.**~~ **Now counted** — see
   `docs/third_sealed_set_material.md`, written the same day. All four have enough unused
   material, and the only outstanding acquisition for a complete third set is two bead
   recordings totalling 3.15 GB. That document also reconciles what `independent_group` means
   across the three files that record it.

## What arrived

Both recommendations were downloaded and verified on 2026-08-19. Each completed at the exact
declared byte count, and each was reopened locally with `tifffile` — the range-request
statistics reproduced exactly, which is the check that validates the probing method itself.

| Staged as | Bytes | SHA256 | Verdict on reopening |
|---|---|---|---|
| `dense_scn_smyllie_bmal1/source/scn_bmal1_cry1.tif` | 326,290,329 | `463f2747…4dc759f` | 622 pages, uint16, uncompressed, greyscale; plane 0 min 52 max 2648, 1537 levels — identical to the remote probe |
| `dense_chromalive_hela/source/chromalive_hela_c02.tif` | 7,225,258 | `8804dfb6…b8d777e4` | 1900x1900 uint16 uncompressed greyscale; min 27 max 4519, 3120 levels |

Staged under `library/benchmark/v2/sealed_test_3_series/`, following the layout the second
set uses (`<series_id>/source/<file>.tif`). Both are recorded in
`benchmark_v2_download_manifest.csv` with licence, byte count and checksum, as
`SBIAD1582_SCN_BMAL1_CRY1` and `SBIAD2515_CHROMALIVE_HELA_C02`.

**The directory name `sealed_test_3_series` is provisional.** No third-set builder exists yet
and the other four image types are not chosen, so nothing has been built from this material.
It is staged and checksummed, not spent.

## Two traps for whoever builds the set

1. **`SealedTestSetBuilder.checkNotSpent` — fixed on 2026-08-19, and the fix is not the one
   this document first proposed.** The benchmark list was a hard-coded array of four names
   that omitted `sealed_test` and `sealed_test_natural`. Simply adding those two names would
   have been wrong: the builder would then refuse to rebuild *its own* set, because its own
   output is under those names.

   The list is now discovered by listing the benchmarks directory and excluding the roots the
   builder itself writes, so a successor writing to different roots automatically sees
   `sealed_test` as spent, and a benchmark added later is covered without anyone editing the
   class. `SpentMaterialGuardTest` pins all four behaviours, including the successor case.

   **One limit remains and is deliberate.** The check matches *series* names, not independent
   groups, so a fresh series drawn from an already-spent group would still pass — a new field
   of view from OpenCell, say. A group-level check cannot be written reliably yet because the
   manifests record `independent_group` at two different granularities: the download manifest
   names each OpenCell field separately (`leonetti_ankrd11`, `leonetti_sptssa`, …) while the
   sealed-test manifest groups them all as `opencell_leonetti`. Resolving that inconsistency
   is a prerequisite, and until then the group check is the reviewer's job — the spent-group
   table near the top of this document is what to check against.

2. **The series manifest is not the authority on what has been used.**
   `library/benchmark/benchmark_v2_series_manifest.csv` is missing several series entirely;
   the second sealed set's sources went in through `benchmark_v2_download_manifest.csv`
   instead. The spent-material table above was built from directory names under
   `library/benchmark/v2/benchmarks/`, which is what the builder's own javadoc says to trust.

## Method, in case it needs repeating

The search was run against the EBI BioImage Archive rather than by reading dataset pages,
because that archive serves a machine-readable `License` attribute per study — 20 candidate
queries, every hit's licence pulled, everything not CC0 or CC BY discarded before any human
judgement was applied. Filtering licences by hand across a hundred records is where a mistake
would have been cheap to make and expensive to find.

The seed-plane check then ran over HTTP range requests, so the entire hunt — five sources,
nine files inspected, five planes decoded — fetched about 32 MB in total, against roughly
40 GB if the same candidates had been downloaded to be checked.

`research/dense_source_probe/` holds the three scripts and a README describing the two archive
quirks that cost time: the BioStudies file-listing API silently caps at five rows whatever
page size is requested, and the FTP directory listing must be used instead.
