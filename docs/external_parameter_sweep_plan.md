# External parameter sweep plan — give every other engine the tuning we gave ourselves

> Active scope changed 2026-08-21: publication tables now cover four image classes. Sparse/low-light
> is excluded after an invalid locked-input channel was found. This file remains the historical
> prospective plan; protocol deviation D4 records the post-result change.

Written 2026-08-20. Serves item 3 of `docs/external_comparison_publication_plan.md`, which named this
as the objection most likely to sink a benchmark paper: **we swept 128 recipes and fitted a
per-image-type model, and every third-party engine ran once, as we happened to configure it.**

> **Status: completed 2026-08-20.** See
> [`external_parameter_sweep_findings.md`](external_parameter_sweep_findings.md) and the generated
> [`FINDINGS.md`](../library/benchmark/v2/runs/external_parameter_sweep_v1/FINDINGS.md). All 24
> development configurations and the frozen locked evaluation completed; both sealed sets remained
> unopened.

## Outcome

A comparison where each external engine has had the same opportunity to be at its best that ours has,
reported as **two tables — defaults against defaults, and tuned against tuned** — so a reader can see
what tuning is worth and cannot mistake one for the other.

And, before any of that, an answer to a more uncomfortable question: **are the numbers in our current
table the tools' fault or ours?**

## Stage 0 comes first, and it is not tuning — it is checking whether we handicapped them

Every external engine is driven through a direct API adapter in
`src/test/java/logratio/ExternalPluginComparisonStacks.java`, with its parameters written into the
Java as literals. That is the right design — it avoids dialogs and macro recording — but it means
**every one of those literals is a choice we made on the tool's behalf**, and four of them look
questionable enough to check before anything else happens.

| Engine | What our adapter does | Why it is suspicious |
|---|---|---|
| **Fast4DReg / NanoJ** | `getShiftFromCrossCorrelationPeak(map, 2)` | The second argument is the factor by which the correlation map is upscaled to get sub-pixel precision. **Their paper states sub-pixel accuracy comes from exactly this upscaling.** A factor of 2 may be capping the precision of the method we are grading. |
| **Correct 3D Drift** | `PhaseCorrelationPeak.getPosition()` returns `int[]` | **The adapter can only ever return a whole-pixel shift.** If the real plugin refines beyond integers and we do not, its row is our artefact. If it genuinely is integer-only, that is a fair and publishable finding — but we must know which. |
| **SIFT** | RANSAC `maxEpsilon = 25.0` px, `minInlierRatio = 0.05`, `minNumInliers = 1` | Twenty-five pixels is an enormous inlier tolerance for a method being asked for sub-pixel translation, and a model fitted from a single inlier is not fitted at all. |
| **Image Stabilizer** | a 5-slot pyramid array with only levels 0 and 1 populated, and only if `width >= 100` | The plugin's own pyramid depth is a documented parameter. Ours is a helper we wrote. |

**The rule for this stage: every literal is checked against the plugin's own default, in its own source
or dialog — not against what we would prefer.** A value that matches the plugin's default stays and is
recorded as such. A value that does not is a defect in our harness, and fixing it is a correction to
published evidence, not a tuning decision.

> **Exit gate.** Every parameter in every adapter is either (a) shown to match that plugin's own
> default, with the source quoted, or (b) corrected, with the comparison re-run and the difference
> reported. **If any row moves materially, `library/benchmark/v2/benchmarks/*/summaries/external_comparison_v1/`
> and the published summary page are wrong and must be reissued before anything else is written.**

**Our own arms must not move at all through Stage 0** — nothing in `src/main` is touched — so the
gate has a free control: every `this plugin` row reproduces at `0.000e+00`, in the shape
`docs/newton_refinement_stage1_findings.md` established. If they move, the harness changed something
it should not have.

## Stage 1 — Expose the parameters

Turn each adapter's literals into a named settings object with the plugin's default as its default,
so a sweep can vary them and a report can print what was used. No behaviour changes when nothing is
overridden.

> **Exit gate.** With every setting left at its default, all twelve external rows reproduce at
> `0.000e+00` against Stage 0's corrected baseline. Exposing a parameter must not change an answer.

## Stage 2 — Sweep, on the development set only

**The fairness rule, stated before any number exists: tuning happens on the 80 development
recordings and nowhere else.** The locked set and both sealed sets are not touched. Whatever setting
the development sweep picks per image type is carried unchanged to the test sets, exactly as our own
selector's settings were. Any other arrangement gives the externals an advantage ours never had, which
fails in the opposite direction and is just as unpublishable.

Candidate axes, kept deliberately small — this is a product and it grows fast:

| Engine | Axis | Levels |
|---|---|---|
| TurboReg | transformation class | translation, rigid body |
| SIFT | RANSAC `maxEpsilon` | 1, 3, 10, 25 px |
| SIFT | `minNumInliers` | 1, 4, 8 |
| SIFT | octave range | as-is, and one finer |
| Fast4DReg | sub-pixel upscale factor | 2, 4, 10 |
| Image Stabilizer | pyramid depth | 2, 3, 4 |
| Image Stabilizer | template update rate | the plugin's default, and one pinned to the first frame |
| Correct 3D Drift | peaks examined | 5, and one higher |
| Descriptor-based | detection threshold | three levels around its default |

**Roughly 25 configurations across all engines, not a cross product of everything.** Sweep one engine
at a time with the others fixed; a full factorial across engines measures nothing, because the engines
do not interact.

> **Exit gate.** Every configuration ran on all 80 recordings, or the ones it failed are counted and
> reported. The winner per engine per image type is chosen by the same criterion we use on ourselves —
> median of per-recording median error — and is written down before the test sets are touched.

## Stage 3 — Re-run the comparison and publish both tables

1. **Defaults against defaults.** Every external at its own default, and **our base arm with no
   automatic selection** — not the trained selector. This is the honest untuned headline, and item 2 of
   `docs/external_comparison_publication_plan.md` covers why it has to lead.
2. **Tuned against tuned.** Every external at its swept best, our trained selector, both carried to the
   locked set unchanged.

Report both on the development set, on the locked set, and on the third sealed set if and only if the
sealed-set rules allow it — which they may not, and that is Stage 4.

> **Exit gate.** Both tables exist and disagree in a way that is explained rather than explained away.
> If tuning moves an external past us on any image type, **that row is published as it stands.**

## Stage 4 — The sealed-set question, which must be decided before it is convenient

Every sealed set in this project has been opened against a *model*, under gates written first. Adding
third-party arms to a sealed set is a different question and the existing rules do not cover it
cleanly: the external tools were not trained on anything, so there is no leakage in the usual sense —
but running twenty-five configurations of somebody else's software on a set and reporting the best is
tuning against that set, whatever it is called.

**The position this plan takes: the sweep never touches a sealed set.** Only the single chosen
configuration per engine per image type is ever run there, once, and only if the accompanying model
run is also warranted. Decide and write this down before Stage 2 produces a result that makes the
decision tempting.

## Risks, named before the result is known

1. **An external overtakes us once tuned.** Descriptor-based registration already beats us on sparse
   low light at its default. A properly bounded SIFT, or a Fast4DReg with a sensible upscale factor,
   could plausibly take more. **That is the correct outcome of a fair test and it must be publishable
   as-is** — a benchmark whose author wins every row is not believed. If the project cannot publish
   that, it should not run this plan.
2. **Stage 0 finds our own numbers were wrong.** This is the outcome that costs real credibility, and
   it is precisely why Stage 0 is first and why its gate demands reissuing the published page rather
   than quietly regenerating it. Finding it ourselves is much cheaper than a reviewer finding it.
3. **Version drift.** The comparison is only reproducible if the exact jar versions of every third-party
   plugin are pinned and recorded. They are currently whatever is installed. Record them in Stage 0 and
   treat a version change as invalidating the table.
4. **Sweeping a tool into a configuration its authors would not recommend.** A tuned setting that beats
   the default on our material may be overfitted to our material. Reporting both tables is the guard;
   quoting only the tuned one would be the same error we are trying to correct, pointed the other way.
5. **Cost.** Twenty-five configurations across 80 recordings is roughly the size of one selector sweep,
   and this machine has already shown that wall-clock timings under load are worthless. Run it when the
   machine is quiet, and report processor seconds rather than elapsed.

## What must not move

`src/main` is untouched by this entire plan. Every change is to the test-side adapters and the
benchmark harness. The `this plugin` rows are therefore a built-in control at every stage, and any
movement in them is a bug in the harness rather than a result.

## Completion definition

Complete when:

- every external parameter is either matched to that plugin's documented default or corrected, with
  the source recorded, and the plugin versions are pinned;
- our own arms reproduce at `0.000e+00` across every stage;
- a defaults-against-defaults table and a tuned-against-tuned table both exist over the development and
  locked sets, with failure counts in the main table rather than a footnote;
- the sealed-set position is written down and was written down before it mattered;
- any row where an external engine beats us is stated plainly, in the same table as the rest.
