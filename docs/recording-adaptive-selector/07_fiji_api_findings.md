# Fiji and API surface findings

The main dialog, batch dialog, macro parser and public parameter builder use one exclusive settings source: Automatic, Recommended or Manual. Automatic macros omit explicit estimator and recipe settings; contradictory mixtures are rejected. Recorded interactive runs use complete Manual settings so replay does not reclassify the stack.

Automatic reports distinguish a measured selection from a fallback and include the model identity. Batch rows retain the resolved recipe, fallback and provenance for each file. The installed fixed-policy override returns one approved recipe per image type without a provisional pass.

Backward-compatible legacy mode tokens remain accepted only when they do not contradict an explicit selection mode.
