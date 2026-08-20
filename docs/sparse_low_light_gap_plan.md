# Sparse low-light gap plan

> **SUPERSEDED 2026-08-18. Do not execute this plan.**
>
> It was executed as far as its own evidence allowed, and two of its gates turned out to be
> answerable from data already on disk. One of those answers removed the plan's premise:
>
> - The development sparse material seeds from the blue channel of 8-bit colour print renderings of
>   published figure panels, not from sensor pixels.
> - Descriptor-based registration loses lock on 7 of the 16 development sparse recordings, with a mean
>   error of 4.92 px against our default's 0.055 px. The `0.62x` win below is a median sitting on a
>   bimodal split and is withdrawn.
> - The mechanism is not feature matching. The strongest surviving candidate is the pairwise
>   estimator, so under the standing rule that an estimator verdict makes the sparse work the
>   estimator work, this plan hands over to `docs/pairwise_estimator_axis_plan.md`.
> - Fiji's Descriptor-based registration is GPL v3+ and its `mpicbg` backend GPL v2+, against this
>   project's BSD 3-Clause, so binding to it on the shipped path was never available.
>
> The full record, including which figures still stand, is `docs/sparse_low_light_gap_findings.md`.
> The text below is kept unchanged as the original plan.

## Outcome

Find out why descriptor-based feature matching registers sparse low-light recordings better than
anything this plugin can do, and decide what to do about it.

The decision at the end is one of four, and this plan does not pre-judge which: add a sparse recipe to
the automatic selector, add a new pairwise estimator to the engine, tell sparse users in the
documentation to use another plugin, or accept the gap and record why.

## What is already measured

From `library/benchmark/v2/benchmarks/*/summaries/external_comparison_v1/paired_against_default.csv`.
Every figure is the median across recordings where both methods produced a finite answer, with drops
stated. Ratios above 1 mean the third-party engine is that many times worse than our shipped default.

Descriptor-based series registration against our default:

| Scope | Development n | Ratio | Locked n | Ratio |
|---|---|---|---|---|
| BRIGHTFIELD_DIC | 16 | 5.80x | 8 | 3.77x |
| DENSE_FLUOR | 16 | 2.57x | 8 | 1.25x |
| FIDUCIAL_STATIC | 16 | 1.24x | 8 | 1.11x |
| PHASE | 8 of 16 | 22.06x | 5 of 8 | 7.14x |
| **SPARSE_LOWLIGHT** | **16** | **0.62x** | **4 of 8** | **0.24x** |

It loses everywhere except sparse low-light, where it wins on both sets independently. On the
development sparse set it completes all 16 recordings and reaches `0.028147 px` against our default's
`0.045494 px` — which is also better than our per-recording oracle of `0.031943 px`. The oracle is the
best of all 96 recipes chosen with hindsight, so this is not a tuning gap inside our recipe space. No
arrangement of the settings we sweep reaches where it already is.

Three further facts constrain any explanation:

1. **No sparse recipe survived the safety rule.** For an image type to gain a candidate, a recipe must
   never breach the per-recording guard on that type, improve it on average, and help at least two
   independent sources. Sparse recipes are erratic: the one that rescues one recording wrecks another.
2. **The development sparse material is not four independent sources.** All four series
   (`sparse_ssbd_fig3a`, `fig4a`, `fig5a`, `figs4`) share `independent_group = ssbd_235_hattori` in
   `library/benchmark/benchmark_v2_series_manifest.csv`. They are four panels from one published
   record. "Helps two independent sources" is a weak bar when the four are siblings.
3. **Descriptor-based fails outright on the hardest sparse source.** It completes 4 of the 8 locked
   sparse recordings; the 4 it drops are `sparse_ssbd166_branch2_nuclei`, the source on which every
   method including ours loses lock entirely at whole-pixel error. Its win is on tractable sparse
   material, not on the hard case. This plan must not conflate the two.

The engine it uses is Fiji's Descriptor-based registration, configured in
`ExternalPluginComparisonStacks.DescriptorSeriesSolver` as: 200 brightest points, difference-of-Gaussian
sigmas 2.0 and 3.2, detection threshold 0.03, translation model, three nearest neighbours, redundancy 1,
significance 3.0, RANSAC threshold 5.0 px, global optimisation over all pairs within a range of 5
frames.

## What this plan does not decide

It does not decide to adopt feature matching. It does not change the frozen selector, the shipped
default, or any recipe. Nothing here is allowed to alter `AutomaticRegistrationSelectorModel.java`
until Stage 5, and Stage 5 requires a new locked test set.

## The locked test problem, stated once

`library/benchmark/v2/benchmarks/locked_test/` has been spent. It was run once against a frozen model
and its results have been read, so it can no longer support an independent claim about anything trained
or chosen afterwards. Adding third-party comparators to it did not spend it further — nothing was tuned
against it — but any new model coming out of this plan needs a **new** locked set built from sources
used nowhere else. Stage 1 collects that material at the same time as the development material, and it
stays sealed until Stage 5.

## Stage 1 — Fix the material before touching the method

The current sparse evidence cannot distinguish "sparse is hard" from "this one paper's panels are
hard", and no conclusion drawn on it is worth much.

1. Identify sparse low-light source series from at least **eight independent records**, no two sharing
   an `independent_group`. Four become development material, four are sealed for the new locked set.
2. Record each in the manifest with `licence_verified`, `checksum` and `local_raw_path` filled in, to
   the same standard as the existing rows.
3. Apply the four standard controlled movement paths, giving 16 new development sparse recordings and
   16 sealed sparse recordings.
4. Re-measure the existing arms on the new development sparse material: category recommendation,
   current default, per-recording oracle over the 96 recipes, and descriptor-based.

**Exit gate:** eight independent sparse records, licences verified, checksums recorded, 32 recordings
built, and a restatement of the descriptor gap on material that is not four panels of one paper. If the
gap disappears on independent material, this plan stops here and says so.

## Stage 2 — Characterise the win before explaining it

Run per recording, not per image type, on the Stage 1 development material.

1. For every sparse recording, tabulate: our default, our oracle, descriptor-based, and the difference.
2. Split by movement profile. The four profiles differ in step size and smoothness, and a feature
   matcher and an intensity fit should not fail in the same places.
3. Record where descriptor-based fails outright and what those recordings have in common — count of
   detected points, contrast, frame-to-frame point persistence.
4. Measure the same image features the selector already computes on each recording, and check whether
   any of them separates "descriptor wins" from "descriptor fails".

**Exit gate:** a written statement of the conditions under which descriptor-based wins, and a stated
count of the recordings where it cannot run at all. A win that only exists on a subset it selects for
itself is not a win, and the report must say so if that is what the data shows.

## Stage 3 — Ablate the mechanism

The descriptor pipeline differs from ours in three ways at once, and the interesting question is which
one matters. This is the stage that decides whether there is anything to import.

Three candidate mechanisms, tested by swapping one piece at a time into our own machinery:

| Mechanism | Test |
|---|---|
| Feature matching instead of an intensity fit | Feed descriptor point correspondences into our `Reconciler.multiLag`, replacing our per-pair `PairAligner.Fit`. If our solver plus their pairs reaches their accuracy, the estimator is what matters. |
| All-to-all pairing within 5 frames | Run our own estimator with a lag set matching their range, against our standard `1,2,4,8,16`. If our accuracy moves, the pairing is what matters. |
| RANSAC outlier rejection | Compare our robust weighting (Huber, Tukey) against a hard RANSAC rejection at the same pair set. If this closes the gap, the change belongs in `RobustNorm`, which is the cheapest of the three to adopt. |

Each is run on the Stage 1 development sparse material only.

**Exit gate:** the gap is attributed to one mechanism, to a combination, or explicitly to none of the
three. "None of the three" is a real answer and terminates the plan with a recorded finding.

## Stage 4 — Choose the route

Only after Stage 3. The route is chosen by which mechanism won, not by preference.

| If the mechanism is | Route |
|---|---|
| The pairing or the outlier rule | A new sparse candidate recipe or a new reference strategy, inside the existing selector. Cheapest, no new dependency. |
| The estimator itself | Hand over to `docs/pairwise_estimator_axis_plan.md`, which already covers adding a pairwise estimator as a selectable axis. Do not build a second mechanism for it here. |
| None of the three, and descriptor-based still wins | Document it: state in the README that sparse low-light users should try Fiji's Descriptor-based registration, with the measured numbers and the failure caveat. Recommending a better tool honestly is a legitimate outcome. |

**Licence gate, before any code that ships.** Establish the licence of anything to be depended on, and
record it the way the manifest already records source licences. Fiji's descriptor-based registration is
a third-party component; if its terms are incompatible with this project's BSD 3-Clause licence, the
route becomes "document it" regardless of what Stage 3 found. This gate is checked before
implementation, not after.

## Stage 5 — Validate on the sealed set

Whatever Stage 4 produces is frozen, then run **once** on the 16 sealed sparse recordings from Stage 1
plus the existing non-sparse locked material, with gates declared in writing beforehand:

- zero failures on sparse;
- sparse median error no worse than the shipped default;
- no image-type regression greater than `0.002 px` on the four non-sparse types;
- runtime within the limit that applies to the current default.

No coefficient, threshold, feature or recipe changes after reading the result.

## Completion definition

Complete only when:

- sparse development material comes from four independent records and a further four are sealed;
- the descriptor gap is restated on that independent material;
- the gap is attributed to a named mechanism or explicitly to none;
- a route is chosen by evidence and its licence position is recorded;
- anything shipped has passed a single run against the sealed set with pre-declared gates;
- the record states plainly whether the gap was closed, narrowed or left open, and by how much.
