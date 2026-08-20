# What makes Log-Ratio Registration different — the long explanation

## Overview

This project is a plugin for Fiji, the image-analysis program used across biology labs. It fixes a
problem that spoils almost every long time-lapse: the picture does not stay still. Over hours or days,
the thing you are photographing slides around in the frame, so a movie that should show cells doing
something instead shows the whole field wobbling.

Correcting that is called **registration**. There are already several tools that do it, some of them
twenty years old and very good. So the only question worth answering is: what does this one do that they
do not? The short version is that it is the only one of them that stays correct while the **brightness**
of the picture is changing, and that is the situation almost every long fluorescence recording is in.

This document explains that claim from the ground up. Every term is defined where it first appears. It
ends with the two places the method is genuinely worse than the alternatives, because a claim with no
stated boundary is not a claim.

---

# Part 1 — The problem

## Registration

### What it means

You have a stack of pictures taken of the same place at different times. Ideally, a pixel in the corner
of picture one is looking at exactly the same speck of sample as the same pixel in picture two hundred.
In practice it is not, because something has moved: the stage the dish sits on has crept, the incubator
door was closed a bit hard, the plastic of the dish has warmed up and expanded.

Registration is the job of working out, for every picture in the stack, how far the contents have
shifted, and then shifting them back so the stack lines up.

### In plain terms

Registration is stacking a pile of photographs so the edges line up, except you have to work out the
alignment from the pictures themselves because nobody wrote down how much things moved.

### Why it has to be sub-pixel accurate

If you correct movement only to the nearest whole pixel, you leave up to half a pixel of error in every
frame. For most purposes that sounds harmless. It is not, for two reasons.

First, a lot of biological measurement is about *change over time at a fixed location* — did this spot
get brighter, did this cell move. Half a pixel of jitter injected into every frame looks exactly like a
small, fast, meaningless fluctuation at every location in the image. It adds noise to the very
measurement you are making.

Second, the errors accumulate. More on that in Part 6.

### Worked example

A recording is 500 frames long. Between frame 1 and frame 500 the field has crept 12 pixels to the
right and 4 pixels down. That is roughly 0.024 pixels of movement per frame — far too small to notice
by eye between neighbouring frames, and completely obvious when you compare the first frame with the
last.

That is the normal case. The movement is invisible frame to frame and ruinous end to end.

---

## Drift, and the four kinds of movement

"Drift" gets used loosely to mean "the picture moved". It is worth splitting it into four kinds, because
they are caused by different things, they look different, and they are not equally hard to correct.

### Drift proper

A slow, steady creep in one direction. Draw a straight line through the position over time and drift is
how much of the movement that line accounts for.

**Cause.** Thermal expansion, mostly. A microscope stage that is a degree warmer than it was an hour ago
is physically a slightly different size. Also mechanical relaxation — a dish settling into its holder
over the first hour.

**In plain terms:** the picture slides gradually in one direction, like a slow leak.

**Difficulty:** easy. It is smooth, so any method that works at all will find it.

### Jitter

Small random movement that does not go anywhere. Position bounces about a fixed point.

**Cause.** Vibration — a fan, a centrifuge in the next room, footsteps — plus the finite precision of
the stage motors.

**In plain terms:** the picture trembles in place.

**Difficulty:** easy in size, but it is the kind that most contaminates measurements, because it adds a
fresh error to every single frame rather than a slowly-growing one.

### Wander (a random walk)

The distinction that matters and the one most easily missed. Jitter bounces around a fixed point; a
random walk takes a small random step each frame and *keeps* it, so it drifts away over time without
ever moving in a consistent direction.

**In plain terms:** jitter is a moored boat rocking. A random walk is an unmoored boat.

**How you tell them apart.** Not by how far the picture travels in total — a recording that drifts five
pixels with a pixel of trembling on top travels a long total distance and ends up five pixels away, and
so does a random walk. You tell them apart by comparing how far the position strays from its starting
point against the size of the individual steps. Trembling strays about as far as one step. A random walk
strays several steps' worth, because the steps accumulate instead of cancelling.

**Difficulty:** moderate, and specifically it is the case where the choice of *reconciliation* matters
most (Part 6).

### Knocks

A single large jump between two consecutive frames, then back to normal.

**Cause.** Somebody opened the incubator, the plate was reseated, a robot arm bumped the stage.

**In plain terms:** one frame where everything suddenly leaps.

**Difficulty:** the hardest, for a mundane reason. Every method searches for the alignment within some
maximum distance. If the jump is bigger than the search limit, the method reports the limit, not the
jump — and then everything after that frame is wrong by the difference. This project measured real jumps
up to 1,154 pixels. That is why the plugin now warns when a frame lands exactly on its search limit
rather than quietly recording the limit as though it were a measurement.

### Worked example putting all four together

A 24-hour recording of cultured cells. Over the day the stage creeps 4 pixels down and to the right
(drift). Superimposed on that, each frame is 1–2 pixels off in a random direction from vibration
(jitter). At hour 9 the incubator door opens for a media change and the field jumps 17 pixels (knock),
then continues creeping from its new position.

A method that handles all four gives you a clean movie. A method that handles the first three and clips
the knock gives you a movie that is perfectly aligned for nine hours and then permanently 17 pixels out.

---

# Part 2 — What the existing tools do

There are three families. Understanding where each breaks is the whole point, because this method's
contribution is a fix for a weakness the biggest family shares.

## Family 1 — Minimising the difference

**The idea.** Slide picture B over picture A. At each position, subtract one from the other pixel by
pixel, square all those differences, and add them up. The position with the smallest total is declared
the alignment. Squaring is there to make negative and positive differences both count as "wrong".

This total has a name in the literature: the **sum of squared differences**. Everything in this family
minimises it or something close to it.

**Who is in it.** StackReg and TurboReg, the standard Fiji registration plugins — TurboReg is the engine
and StackReg is the wrapper that applies it down a stack. Image Stabilizer. MultiStackReg. This family is
the default choice in most labs and has been for two decades.

**In plain terms:** find the position where the two pictures disagree least.

**Where it breaks.** The method assumes the two pictures differ *only* because something moved. If the
second picture is also, say, 3% dimmer everywhere, then some of the disagreement is caused by brightness
rather than position — and the method has no way to tell those apart. It will happily shift the picture
to a wrong position if doing so happens to make the numbers agree slightly better. This is Part 3, and
it is the crux of this document.

## Family 2 — Fourier methods

**The idea.** There is a mathematical transform — the Fourier transform — that re-describes an image not
as a grid of brightnesses but as a recipe of overlapping waves: how much of the pattern is coarse and
slow, how much is fine and rapid, and crucially *where the crests of each wave sit*. That last part is
called the **phase**.

Shifting a picture sideways does not change which waves are present, only where their crests sit. So if
you compare the phases of two pictures, the shift falls straight out. This is **phase correlation**, and
its close relative **cross-correlation** does something similar without the normalisation step.

**In plain terms:** describe both pictures as a mixture of ripples, then measure how far the ripples have
been slid along.

**Its strengths.** Two, both real. It searches the entire picture at once rather than within a limited
radius, so a huge jump is no harder than a small one. And because it throws away how *strong* each wave
is and keeps only where the crests sit, a uniform brightness change makes no difference to it at all —
it is naturally immune to the problem that wrecks Family 1.

**Where it breaks.** It assumes the whole picture moved as one. Real change inside the picture — a cell
dividing, a bright piece of debris drifting across — is, in wave terms, an unfamiliar pattern with no
counterpart in the other picture, and it muddies the answer everywhere at once rather than in one place.
It is also the slowest option here, because the transform has to be computed several times per
comparison and the picture has to be padded out to a convenient size first.

## Family 3 — Feature matching

**The idea.** Find distinctive landmarks in each picture — corners, blobs, junctions — describe each one
by its local surroundings, then match landmarks between pictures and work out the movement that best
explains the matches. Linear Stack Alignment with SIFT is the Fiji example.

**In plain terms:** pick out recognisable landmarks in both pictures and see how far each has moved.

**Where it breaks.** It needs distinctive landmarks. Smooth, low-contrast biological images frequently do
not have them, and when the landmark detector finds nothing it fails outright rather than degrading
gracefully. It is also the most complex of the three, with the most settings to get wrong.

## What all three share

None of them was designed for a picture whose overall brightness is changing during the recording.
Family 1 is actively broken by it. Families 2 and 3 tolerate it but have their own weaknesses. That gap
is where this method lives.

---

# Part 3 — Brightness drift, the term this all turns on

## What it is

The whole picture gets dimmer, or brighter, over the course of the recording, for reasons that have
nothing to do with anything moving.

**Three causes, all routine:**

- **Photobleaching.** Fluorescence microscopy works by attaching a molecule that glows when you shine
  light on it. The light that makes it glow also gradually destroys it. Over hours of imaging, the
  sample genuinely gets dimmer. This is not a fault; it is chemistry, and it is unavoidable.
- **Illumination drift.** The lamp or laser producing the light does not put out exactly the same amount
  hour after hour. It warms up over the first half hour, and it ages over months.
- **Detector settings.** Somebody adjusts the camera gain between sessions, or the automatic exposure
  moves.

### In plain terms

Brightness drift means the picture fades (or brightens) as the recording goes on, and none of that fading
tells you anything about where the sample is.

### The specific shape it usually takes

Crucially, it is almost always **multiplicative** rather than additive. Every pixel gets multiplied by
roughly the same factor. A pixel reading 100 and a pixel reading 20 both fade by 3%, becoming 97 and
19.4 — they do not both lose 3 counts. That is because it is fundamentally about *how much light is
arriving or how many glowing molecules survive*, both of which scale everything together.

This matters enormously, and Part 4 is entirely about why.

## Why it destroys a difference-minimising method

Here is the mechanism, with numbers.

Take a tiny patch of picture with three pixels, reading 100, 60, 20. In the next frame nothing has moved,
but the sample has faded by 10%, so it reads 90, 54, 18.

A difference-minimising method compares them at zero shift:

| position | frame A | frame B | difference | squared |
|---|---|---|---|---|
| 1 | 100 | 90 | 10 | 100 |
| 2 | 60 | 54 | 6 | 36 |
| 3 | 20 | 18 | 2 | 4 |
| | | | **total** | **140** |

Correct answer, score 140. Now suppose there is *also* a wrong alignment that shuffles things so the
comparison happens to be 100 vs 96, 60 vs 58, 20 vs 22 — a genuinely wrong position, but one where the
numbers happen to sit closer together:

| position | frame A | frame B | difference | squared |
|---|---|---|---|---|
| 1 | 100 | 96 | 4 | 16 |
| 2 | 60 | 58 | 2 | 4 |
| 3 | 20 | 22 | 2 | 4 |
| | | | **total** | **24** |

Score 24, which beats 140. **The method picks the wrong position, and it does so confidently, because by
its own criterion the wrong position genuinely is better.** It is not a bug in the implementation. The
criterion itself cannot distinguish "the picture moved" from "the picture dimmed".

### When it actually bites, and when it does not

This is the most important qualification in this document, and it took running the real StackReg engine
to establish it.

The size of the error above depends on how it compares to the *real* signal — how sharply the brightness
changes from pixel to pixel because of genuine structure. In an image packed with fine detail, moving by
one pixel changes the numbers enormously, and a 3% fade is a rounding error by comparison. In a smooth,
low-contrast image, moving by one pixel barely changes anything, and a 3% fade dominates.

Measured on three real recordings under an identical fourfold fade, with the recordings ordered by how
much fine structure they contain:

| recording | fine structure | this method's error | StackReg's error |
|---|---|---|---|
| the featureless one | very low | 0.085 pixels | 23.173 pixels |
| a well-textured one | good | 0.031 pixels | 0.111 pixels |
| another well-textured one | good | 0.056 pixels | 0.085 pixels |

**On good data StackReg handles a fourfold fade perfectly well.** It is only when the picture has little
structure to lock onto that the brightness change takes over and the answer collapses — and it collapses
completely, from a fraction of a pixel to twenty-three pixels.

So the honest claim is not "we beat StackReg on fading data". It is: **on fading data with weak
structure, which is exactly the hard case, we are right and it is not.**

## Why longer time gaps make it much worse

A recording that fades fourfold over 48 frames fades by about 2.9% between one frame and the next. But if
you compare frame 1 with frame 17, you are spanning sixteen of those steps — a 38% brightness difference.

That becomes critical in Part 6.

---

# Part 4 — The log-ratio idea

This is the core of the method, and it is a single mathematical observation applied carefully.

## What a logarithm does to multiplication

A logarithm is a way of re-expressing numbers so that **multiplying becomes adding**. It is the principle
behind slide rules. If you take the logarithm of every number, then multiplying two of the originals
corresponds to adding two of the logarithms.

### In plain terms

Logarithms turn "times" into "plus".

## Why that solves the brightness problem exactly

Brightness drift is a multiplication — every pixel times the same factor. So in the logarithm world, it
is an **addition** — every pixel plus the same amount.

That is a categorically easier thing to deal with. "Every pixel is multiplied by an unknown factor" is
tangled up with the picture content. "Every pixel has the same unknown number added to it" is a single
constant sitting on top of everything, and you can find and remove it in one step, without knowing
anything about the picture.

So the method converts both pictures to logarithms first. Then it subtracts one from the other. That
difference is called the **log-ratio field** — "ratio" because subtracting logarithms corresponds to
dividing the originals, so what you have is a picture of *how many times brighter each spot got*.

### In plain terms

Instead of asking "how different are these two pictures", it asks "what is the brightness ratio at each
spot" — and then it looks for the alignment that makes that ratio the same everywhere.

## Why "the same everywhere" is the right target

Think about what the ratio picture looks like in the two situations.

**Perfectly aligned, with a fade.** Every spot is looking at the same bit of sample in both frames, and
every bit faded by the same 10%. So the ratio is 0.9 at every single spot. The ratio picture is
completely flat and featureless. Boring, and boring is exactly the signal.

**Misaligned by two pixels.** Now every spot in frame B is looking at slightly the wrong bit of sample.
Wherever the sample has an edge, the ratio is wildly wrong — a bright bit divided by a dark bit, or the
reverse. The ratio picture is full of edges, ghosting and structure.

So: **misalignment shows up as texture in the ratio picture, and the fade shows up as an overall level
that carries no texture at all.** Flatten the texture and you are aligned, regardless of the level.

### Worked example

Same three pixels, 100/60/20, faded 10% to 90/54/18. As ratios: 0.9, 0.9, 0.9. Perfectly flat, so the
method reads it as correctly aligned and the fade tells it nothing about position — which is right.

Now the misaligned comparison from Part 3 — 100 vs 96, 60 vs 58, 20 vs 22 — as ratios: 0.96, 0.97, 1.10.
Not flat. That last spot is 10% *brighter* while the others are slightly dimmer, which is impossible if
the only thing happening is a uniform fade. The method correctly rejects this position, where the
difference-minimising method preferred it.

**Same two pictures, same two candidate positions, opposite conclusions.** That is the whole
contribution in one example.

## The free constant, and why it is not fitted

The overall level of the ratio picture — the 0.9 — is treated as a nuisance to be removed, not a quantity
to be estimated by trial and error. At every step of the calculation, the method takes the middle value
of the ratio picture (the median: the value with half the picture above it and half below) and subtracts
it. Whatever remains is the texture, and the texture is what gets minimised.

Using the middle value rather than the average matters. An average is dragged around by extremes: if a
few spots have genuinely changed a great deal, they pull the average and corrupt the estimate for
everywhere else. The middle value ignores them, because it only cares about ranking.

## The measurement you get for free

Because the method computes that overall brightness level at every frame in order to remove it, it also
*knows* it. Collect those numbers across the recording and you have a photobleaching curve — a record of
how the sample faded over time — at no extra computational cost, from a calculation that had to happen
anyway.

Nothing else in the comparison produces this. For the difference-minimising family it does not exist as a
quantity; for the Fourier family it has been deliberately discarded.

**In plain terms:** it hands you the bleaching curve as a by-product of aligning the pictures.

---

# Part 5 — Telling real change from misalignment

## The problem this solves

Biology moves. Between two frames of a real recording, cells crawl, divide, extend processes and die.
Those are genuine differences between the pictures that have nothing to do with the stage having moved,
and a naive method will try to "align" them away — dragging the whole picture sideways to make one
migrating cell match up, and throwing everything else out in the process.

## The observation that separates them

Misalignment and genuine change leave differently-shaped fingerprints.

**Misalignment is dense and small.** Shift the picture by two pixels and *every single edge in the whole
image* is slightly wrong. Millions of pixels are each a little bit off. There are a great many of them
and none is dramatic.

**Genuine change is sparse and large.** One cell moved. A few thousand pixels are enormously wrong; the
other several million are untouched.

### In plain terms

Misalignment is a small error absolutely everywhere. Real change is a huge error in a few places.

## Why squaring the error is the wrong response

Squaring — the standard approach — means an error of 10 counts ten times as much as an error of 3, not
three times as much. So squaring deliberately amplifies the big, rare disagreements: exactly the genuine
biological change you want to *ignore*. Under squaring, one migrating cell can outvote the entire rest of
the image.

This is especially severe on data that has been background-subtracted or thresholded — a very common
preprocessing step — because there a pixel flipping between "nothing" and "signal" produces an enormous
ratio, hundreds of times larger than any genuine edge effect.

## The three ways of scoring an error

The method offers three, and which is right depends on the situation.

**Plain squared error.** Big errors count enormously. Correct only when everything that differs between
the two pictures really is misalignment — which is true of clean test data and rarely true of real
biology.

**Huber.** Small errors are squared as usual; beyond a threshold, they count in proportion to their size
rather than their size squared. An outlier still pulls, but no harder than an error sitting right at the
threshold. The cautious middle.

**Tukey.** Beyond the threshold, an error counts for *nothing at all*. A difference judged to be genuine
change is discarded outright rather than merely discounted.

### In plain terms

Squared error says "the biggest disagreements matter most". Tukey says "the biggest disagreements are
probably a cell doing something, so ignore them and listen to the crowd".

### Where the threshold comes from

Not from the user. It is worked out from the picture itself, every step, as a measure of the typical
spread of the ratio values. So it adapts to how noisy each particular pair of frames is, rather than
requiring a number nobody could guess.

## The cost of Tukey, and the surprise

Discarding data is not free. If there is no genuine change to reject — clean data — then Tukey throws
away perfectly good information and the answer gets *worse*. Measured on clean data with simple frame-to-
frame chaining, plain squared error achieves 0.281 pixels and Tukey achieves 0.466. **The cautious choice
is the wrong one when there is nothing to be cautious about.**

But switch to the redundant reconciliation described in Part 6 and the ordering completely reverses:
Tukey becomes the best option under every single condition tested. The redundancy soaks up the extra
noise that discarding data introduces, and what is left is the robustness.

**This is why the two settings are not independent, and the plugin now says so** rather than letting
somebody pick the combination that measures worse.

---

# Part 6 — Reconciliation: chaining versus RCC

Everything so far has been about comparing **two** frames. This part is about assembling hundreds of
those pairwise answers into one answer per frame. It is a separate question, and it turns out to matter
as much as the first one.

## Chaining, the obvious method

Measure how far frame 2 moved relative to frame 1. Then frame 3 relative to frame 2. Then 4 relative to
3. To find out where frame 500 is relative to frame 1, add up all 499 steps.

**In plain terms:** work out each step and add them up.

### Why it goes wrong

Every one of those 499 measurements has a small error. Adding them up adds the errors too. Worse, the
errors never get corrected, because nothing ever checks frame 500 against frame 1 directly — so an error
made at step 3 is silently carried through all 496 later frames.

This is the same reason that pacing out a distance heel-to-toe accumulates error while measuring it with
a long tape does not.

### Worked example

500 frames, each step measured with a typical error of 0.1 pixels in a random direction. Errors in random
directions partly cancel, and the accumulated error grows with the square root of the number of steps —
so by frame 500 you expect roughly the square root of 499, about 22, times 0.1, which is about 2.2
pixels of drift error. That drift is entirely invented by the measurement process.

And that is the *well-behaved* case. If a single pair is badly measured — a knock exceeding the search
limit, say — the entire error appears at once and is carried forever.

## RCC — redundant cross-correlation

**RCC stands for redundant cross-correlation.** The name is unhelpful; the idea is simple.

Instead of measuring only neighbouring pairs, also measure pairs that are further apart. This project
measures frames 1 apart, 2 apart, 4 apart, 8 apart and 16 apart. So frame 20 is compared against frames
19, 18, 16, 12 and 4 — five independent measurements, where chaining would have used one.

Those measurements will not perfectly agree, because each has its own error. So the final positions are
worked out by finding the single set of positions that best satisfies all of them at once — a least-
squares fit, meaning the set of positions that minimises the total disagreement with all the
measurements collectively.

### In plain terms

Instead of measuring each step and adding up, measure lots of overlapping spans — including long ones —
and find the set of positions that best fits all of them together.

### Why "redundant" is the operative word

The redundancy is the point. Any single measurement can be wrong. But a long-span measurement is an
independent check on the sum of the short-span ones, so an error in one step now contradicts other
measurements instead of quietly propagating. And unlike chaining, the long spans measure the
accumulated drift *directly*, so it cannot silently build up.

### What it costs

For a 48-frame recording, chaining needs 47 comparisons; this lag set needs 209. That is 4.45 times the
work, and it is the entire cost — the fitting step itself takes about a thousandth of a second.

### What it buys

Between six and twenty-four times better accuracy, for every method tested, including the competitors.
Measured on 48 frames: 0.466 pixels chained, 0.036 pixels with RCC.

### An important honesty point

**RCC is not this project's invention.** It comes from the super-resolution microscopy literature
(published in 2014), and it is completely indifferent to how each pair was measured. Quoting a
log-ratio result *with* RCC against a competitor's result *without* it would credit this method with an
improvement that belongs entirely to the reconciliation.

So in this project's benchmark every method — including StackReg's engine — is run through the identical
reconciliation with the identical set of pairs. Only the per-pair measurement differs. That is the only
way the comparison means anything.

## The interaction that makes both features necessary at once

Here is where Parts 3 and 6 collide, and it is the sharpest result in the project.

A chained comparison spans one frame, so it spans one 2.9% brightness step. An RCC comparison spanning
16 frames spans a 38% brightness difference — thirteen times larger.

**So the reconciliation that most improves accuracy is also the one that most punishes a method with no
brightness model.** Measured, using an in-house imitation of StackReg's criterion so that everything else
is held identical:

| | clean data | with a fourfold fade |
|---|---|---|
| no brightness model, chained | 0.297 px | 0.972 px (3.3x worse) |
| no brightness model, RCC | 0.049 px | **7.96 px (163x worse)** |

Turning RCC on makes a gain-blind method *catastrophically* worse rather than better. And this was
confirmed against the real StackReg engine, which produced 7.79 pixels where the imitation produced 7.96
— and reproduced it recording by recording, not merely on average.

**The consequence for anybody using this plugin:** long-span comparison and brightness invariance are not
two independent features you can mix and match. Using the first without the second is worse than using
neither. The plugin now refuses that combination outright rather than warning about it.

---

# Part 7 — Localisability: knowing in advance whether this will work

## What it is

A number, computed from the pictures alone before any registration is attempted, that says whether these
pictures *can* be aligned at all.

It is measured like this: take two consecutive frames and see how similar they are. Then deliberately
shift one of them by a single pixel and measure the similarity again. **Localisability is how much
similarity you lost.**

### In plain terms

How much worse do these two pictures match if I nudge one by a single pixel? A lot means the picture has
fine detail and its position can be pinned down precisely. Hardly at all means the picture is smooth or
noisy, and no method on earth can tell you exactly where it is.

## Why the obvious alternatives fail

**Contrast fails.** The most obvious idea is "pick the picture with the most contrast". On a
light-starved recording, the channel with the most pixel-to-pixel contrast is usually pure noise. Noise
has enormous contrast and carries no information whatsoever.

**Plain similarity fails too.** The next idea is "pick the channel where consecutive frames are most
similar", which correctly rules out noise. But it is *also* near-perfect for a smooth, featureless blob
on a smooth background — which is equally impossible to localise, for the opposite reason. Ranking 24
real recordings this way picked a saturated fluorescence channel on which two independent methods then
disagreed by 15.7 pixels per step.

Localisability rules out both at once: noise fails it because the two frames were never similar to begin
with, and a smooth blob fails it because nudging a smooth blob by one pixel changes almost nothing.

## What the number is worth

Across 24 real recordings it separated the usable from the unusable by a factor of ten, and it predicted
how well two completely independent methods would agree — which is the closest thing to ground truth
available on real data where nobody knows the true answer:

| | localisability | agreement between two independent methods |
|---|---|---|
| 13 recordings | 0.086 – 0.284 | **0.53 – 0.68 pixels** |
| 11 recordings | 0.008 – 0.031 | 1.6 – 18.6 pixels |

There is a clean empty gap between the two groups, and the warning threshold sits in it, at 0.05. It was
not tuned to a borderline case, because nothing was measured in between.

### The most useful thing this measurement established

The original claim in this project was that the method works up to about 35 pixels of movement. That was
wrong, and localisability is what revealed it. Extending three well-structured recordings to a nine-day
baseline produced movements of up to 632 pixels — recovered to 0.55 pixels. Meanwhile a structureless
recording with an 89-pixel jump was not recovered at all.

**The size of the movement is not the limit. The structure in the picture is.**

## The one caveat

The threshold is calibrated on real, noisy recordings. Clean synthetic test images can score below it and
still register perfectly, because with no noise even a shallow signal is a clean one. It is a warning
about real data, not a law of nature.

---

# Part 8 — The intensity band

This part covers the newest work and directly addresses two questions that were put to the method.

## The ceiling: excluding the brightest pixels

### The problem it exists for

Bright, hard-edged things that are not the sample: dust on the lens, an air bubble, a chunk of debris
drifting across the field, a saturated speck. They are catastrophic for this method specifically, and
here is why.

A very bright constant patch dropped on top of the picture destroys the ratio at that location — a huge
number divided by whatever was there before. And the ratio criterion is precisely a criterion about the
ratio being consistent. A Fourier method, by contrast, sees such a patch as merely a bit of unfamiliar
texture and shrugs it off.

The measurement is stark. With bright patches covering a tenth of the field, phase correlation scores
1.60 pixels and the best log-ratio setting scores 24.4 — fifteen times worse. This is not a contrived
worry: it matches a real failure in the library, where an out-of-focus patch swept across a recording and
the method tracked *the patch* rather than the tissue.

### The fix

Before doing anything else, mark every pixel above a chosen brightness percentile as unusable. They then
take no part in anything — not the alignment, not the brightness estimate, not the quality score.

A "percentile" here means a rank: the 90th percentile is the brightness level that 90% of the picture
falls below. Cutting there discards the brightest tenth.

### What it achieves

| | phase correlation | this method, no ceiling | this method, ceiling at the 90th percentile |
|---|---|---|---|
| chained | 1.601 | 24.38 | **0.31** |
| with RCC | 0.137 | 4.755 | **0.06** |

**From fifteen and thirty-five times worse, to five and two times better.** The single condition on which
this method lost is now the one it wins by the widest margin.

### Why it is a threshold, not a dial

The artefact covers 10% of the field, and only a cut that reaches it does anything:

| cut at | removes | resulting error |
|---|---|---|
| nothing | nothing | 4.76 |
| 99.5th percentile | 0.5% — a sliver of artefact plus real signal | 4.51 |
| 99th | 1% — most of the artefact survives | 4.79 |
| 95th | 5% — half of it | 1.31 |
| **90th** | **10% — all of it** | **0.06** |

At the 99th percentile it is **worse than doing nothing**: real signal removed, artefact still there. So
the question is never "which percentile is best" but "how much of the picture is the artefact".

---

## Two challenges put to this setting

Both were tested rather than argued about. The measurements are in the section after next.

### Challenge one: surely deleting the sample is fine?

The worry about the ceiling was that in fluorescence imaging the sample often *is* the brightest thing in
the picture, so cutting the brightest tenth would delete the very thing you are tracking.

**The counter-argument put to that was that it should not matter, because everything in the picture
drifts together.** The background, the dim edges of cells, the out-of-focus haze and the debris all move
exactly as much as the bright cell bodies do, because the whole field is sliding as one. Registration
does not need *the sample*; it needs *any structure that moves with the field*.

**That argument is incomplete.** This plugin does correct a whole-frame movement, so surviving background
can in principle locate it. But a real fluorescence seed shows that the removed bright tail can still be
the strongest evidence: the ceiling worsens clean accuracy 2.9-fold and fading accuracy 5.3-fold. The
question is whether enough distinguishable structure survives, and modality changes the answer.

### Challenge two: could trimming the dimmest pixels be a speed dial?

The second proposal came from noticing that the ceiling made the calculation *faster* as well as more
accurate. If excluding pixels buys speed, then excluding the **dimmest** pixels — empty background, which
carries no positional information anyway — might buy more speed for free, and the amount could be a dial
the user turns. Nothing else in the comparison offers a speed/accuracy trade at all.

The reasoning is sound on its face. A pixel in featureless background costs exactly as much to process as
an informative one. The method works by asking, in effect, "if I nudge the picture, does this pixel's
value change?" — and in flat background the answer is no.

It is worth saying how this differs from something the method already does. There is an existing option
that keeps only pixels whose local *steepness* exceeds a threshold. That sounds similar but differs in
two ways: it still examines every pixel in order to decide, so it saves less; and by design it does not
touch the brightness estimate or the quality score. A brightness floor is applied once, at the very
start, and the pixel disappears from everything downstream.

**Both propositions were implemented and measured. One is right, one is wrong, and the reason the second
one is wrong turns out to be the most method-specific thing in this document.**

### The measurement, and the answer

It was run first on three phase-contrast seeds, then repeated on a real fluorescence seed. The phase
results explain the mechanism; the fluorescence result supplies the limiting counter-example.

#### How far the ceiling can go

Cutting from the top on the three phase-contrast seeds, with the redundant reconciliation. Error in
pixels; smaller is better.

| what is removed | clean | fading | real change | bright debris | time per pair |
|---|---|---|---|---|---|
| nothing | 0.036 | 0.057 | **0.050** | 4.755 | 59.3 |
| brightest 5% | 0.032 | 0.049 | 0.057 | 1.31 | 60.8 |
| brightest 10% | **0.029** | 0.049 | 0.059 | 0.060 | 59.3 |
| brightest 15% | 0.032 | 0.063 | 0.067 | 0.040 | 59.4 |
| **brightest 25%** | 0.031 | 0.060 | 0.074 | **0.036** | **47.8** |
| brightest 50% | refuses | refuses | — | refuses | 12.6 |

**On phase contrast, removing a quarter of the picture costs essentially nothing and in places helps.**
Clean data improves. Fading data improves. The debris case improves by a factor of 133. And it runs 20%
faster.

The only real cost is the "real change" column, where 0.050 becomes 0.074. That makes sense: those are
frames where structure has genuinely been displaced, and throwing away a quarter of the evidence about
where it went is not free.

#### The first challenge, answered

**The proposition was right only for phase contrast.** There, deleting the brightest quarter left enough
structure and the answer stayed as good or better. On real fluorescence, deleting just the brightest
tenth worsens clean accuracy from 0.014 to 0.039 pixels and fading accuracy from 0.039 to 0.207. Deleting
the brightest quarter takes those to 1.28 and 3.54 pixels.

So the risk is exactly that the removed bright tail held most of the distinguishable sample. It remains
a quantity question, but intensity is a modality-dependent proxy for that quantity.

**And there is a hard, sharp limit, at about half.** Cut half the picture in either direction and the
method refuses outright rather than guessing. The reason is worth knowing because it is not about
information at all.

When the method looks up a value at a non-whole-numbered position — which it must, because the movement
is not a whole number of pixels — it blends the four surrounding pixels. If **any one** of those four has
been excluded, the whole position is unusable. So excluded pixels do not merely remove themselves; each
one poisons up to four positions around it.

Measured directly on a real frame:

| what is removed | pixels surviving | *positions* actually usable |
|---|---|---|
| nothing | 100% | 100% |
| brightest 10% | 90% | 66% |
| brightest 25% | 75% | 33% |
| brightest 50% | 50% | **12%** |
| dimmest 50% | 50% | **6%** |

The method refuses any pair with under 10% usable, precisely so it never reports a confident answer built
on almost nothing. At a half-cut it lands under that line, and refusing is the correct behaviour.

**So the answer to "can we delete the sample" is: on these phase images, up to a quarter; on the measured
fluorescence image, not safely even at a tenth.** A separate geometric limit appears when exclusions leave
too few blended positions, but it is not permission to cut everything below that limit.

#### The second challenge, and why the floor is the wrong end

The proposal was that trimming the dimmest pixels — empty background carrying no positional information —
would buy speed for free, and be a dial the user could turn. It was tested against exactly the same cuts
from the other direction:

| what is removed | clean, chained | clean, RCC | time per pair |
|---|---|---|---|
| nothing | 0.466 | 0.036 | 57.2 |
| **brightest 25%** | **0.338** | **0.031** | **47.5** |
| **dimmest 25%** | 1.044 | 0.258 | 53.2 |
| dimmest 50% | refuses | refuses | — |

**Same amount removed, opposite results.** Cutting the top improves accuracy and saves 17% of the time.
Cutting the bottom makes it 2.2 times worse chained, 7.2 times worse with RCC, and saves only 7%.

It is not the blending problem — the dim cut actually leaves *more* usable positions than the bright cut
at the same depth (41% against 33%). Something else is going on, and it turns out to be a property of
this method specifically.

**The dim pixels are the most informative ones in phase contrast.** The method does not work on brightness; it works on
the *logarithm* of brightness. And the logarithm has a steep slope where the picture is dark and a shallow
one where it is bright — the same physical change of, say, ten counts produces an enormous change in the
logarithm at a dark pixel and a tiny one at a bright pixel. Since the method finds the alignment by
following how sharply the log picture changes from place to place, dark regions carry most of the signal.

Measured on a real frame, splitting all pixels into ten brightness bands:

| brightness band | share of the total signal the method uses |
|---|---|
| darkest tenth | **19–20%** |
| each middle tenth | about 8% |
| brightest tenth | 10% |

The darkest tenth carries roughly twice its fair share, and the darkest three tenths carry 37–40% of the
signal against 28% for the brightest three tenths.

**In plain terms:** in these phase images, the dark parts are where the information is. On the real
fluorescence seed the result reverses: the brightest 30% carry 36.2% of total log-gradient and the dimmest
30% carry 28.5%.

Note that this is specific to the log-ratio criterion and is the *opposite* of what would be true for a
plain difference-minimising method, where the biggest raw differences — and so the apparent information —
sit in the bright regions.

#### But the speed dial the proposal was after does exist

It is simply the ceiling rather than the floor, and it comes in two forms:

| | mechanism | measured |
|---|---|---|
| **On the measured phase data** | cutting a quarter means a quarter fewer comparisons per repetition, per zoom level, per pair | 59.3 → 47.8 per pair, **20% faster**, accuracy unchanged or better |
| **On debris-affected data** | with the artefact gone the calculation stops struggling and settles in fewer repetitions | 86.2 → 56.2 per pair, **35% faster**, and 79 times more accurate |

So: **the ceiling gives immunity to bright artefacts and can give a 20–35% speed-up, but its accuracy cost
is modality-dependent.** On the measured fluorescence seed it is slower in decision terms even if faster
in CPU terms, because clean and fading accuracy worsen substantially. Use it for visible bright
contamination, not as a general speed control.

---

# Part 9 — The unique benefit, stated exactly

## What only this method does

Put plainly: **it is the only one of these tools that measures position and brightness at the same time,
treating the brightness change as something to be removed exactly rather than tolerated, ignored, or
mistaken for movement.**

That single design choice produces four things, only the first of which is shared with anything else:

1. **Correct alignment while the sample fades** — shared with the Fourier family, not with StackReg.
2. **Robustness to genuine biological change** — not shared with either. The Fourier family is confused
   by real change because it re-describes the whole picture at once; this method can identify a
   disagreement as "probably a cell doing something" and discard it while keeping everything else.
3. **A photobleaching curve as a free by-product** — not produced by anything else here.
4. **A brightness ceiling the user can set** — deliberate protection from bright contamination, with a
   20–35% speed gain measured on phase contrast and a clean/fading accuracy cost measured on fluorescence.

The fourth needs modality knowledge. Working in the logarithm makes brightness drift cancel exactly, but
the useful end of the intensity histogram is not fixed: dark pixels dominate the phase seeds and bright
pixels dominate the fluorescence seed. The ceiling is reliable for rejecting bright contamination, not
for selecting uninformative pixels.

## Where it is genuinely worse

**Speed is the general weakness.** StackReg's engine is roughly seventeen times faster: 1.0 second
against 16.0 seconds to register a 48-frame stack at matched settings, which on a 500-frame overnight
recording is about ten seconds against three minutes. That gap is not inefficiency; it is the cost of the
robust scoring, the per-step brightness estimate and the careful step-size search. If your recording does
not fade and has plenty of structure, StackReg is the better tool and this document should say so.

**The bright-artefact weakness can be closed deliberately.** It used to be thirty-five times worse than
phase correlation. On the three phase-contrast seeds, excluding the brightest tenth makes the method read
better than phase correlation on all four test conditions:

| | this method, no top 10% | phase correlation |
|---|---|---|
| clean | **0.029** | 0.110 |
| fading | **0.049** | 0.111 |
| real change | **0.059** | 0.114 |
| bright debris | **0.055** | 0.137 |

And it runs 14% faster than the unbanded version on those seeds.

**Real fluorescence gives the counter-example.** The fourth seed is a CC0 BBBC038 image with 0–6-count
background and bright nuclei. With redundant cross-correlation, which combines overlapping pairwise
measurements, the same ceiling gives:

| | no ceiling | brightest 10% removed | phase correlation |
|---|---:|---:|---:|
| clean | **0.014** | 0.039 | 0.079 |
| fading | **0.039** | 0.207 | 0.072 |
| real change | 0.041 | **0.034** | 0.063 |
| bright debris | 19.16 | **2.37** | 24.04 |

The ceiling still rejects bright debris, improving it eightfold. It is 2.9 times worse on clean data and
5.3 times worse under fading because the removed tail contains the sample; under the fade it also loses
to phase correlation. So the setting is an artefact tool to turn on when bright contamination is visible,
not a generally safe default.

## The honest one-line summary

**The unbanded method is the accurate general setting; the brightness ceiling trades clean and fading
accuracy for protection from visible bright debris, and that trade is much larger in fluorescence.**

---

# How these concepts relate

| if the problem is... | the relevant concept |
|---|---|
| the picture is sliding around | registration (Part 1) |
| what kind of sliding, and how hard to fix | drift, jitter, wander, knocks (Part 1) |
| the sample is fading as it is imaged | brightness drift, and the log-ratio idea (Parts 3–4) |
| cells are moving and dividing between frames | the choice of error scoring (Part 5) |
| errors accumulating along a long recording | chaining versus RCC (Part 6) |
| you do not know whether this recording can be aligned at all | localisability (Part 7) |
| debris or bubbles are wrecking the alignment | the brightness ceiling (Part 8) |
| it is too slow | the brightness floor (Part 8) |

Two pairings are not free choices but genuine dependencies:

- **RCC requires the brightness model.** Using long-span comparisons without it is worse than not using
  them at all — 163 times worse. The plugin refuses this combination.
- **The error scoring depends on the reconciliation.** Discarding outliers costs accuracy when chaining
  and gains it under RCC. The plugin warns when they are mismatched.

---

# Interpretation guide

| your situation | what to use |
|---|---|
| short recording, bright, no fading, plenty of detail | StackReg is faster and just as good |
| long recording, fluorescence, visible fading | this method, with RCC |
| picture is smooth or dim, little fine detail | check localisability first; if it is below 0.05, no tool will help and the honest answer is to change the imaging |
| debris, bubbles or dust drifting across | this method with the brightness ceiling set to roughly the fraction of the frame the debris covers |
| a huge jump somewhere in the recording | raise the search limit, or let it be derived from the recording; watch for the warning that a frame landed on the limit |
| you need it faster | on phase contrast, the 75th-percentile ceiling measured 20% faster; do not transfer that speed setting to fluorescence, where it damaged accuracy |
| you also want a bleaching curve | this method; it produces one anyway |

---

# Quick reference

- **Registration** — working out how far the picture moved in each frame and shifting it back.
- **Drift** — slow steady movement in one direction, usually thermal.
- **Jitter** — small random trembling about a fixed point, usually vibration.
- **Wander (random walk)** — small random steps that accumulate rather than cancelling.
- **Knock** — a single large jump, usually somebody touching the equipment.
- **Brightness drift (gain)** — the whole picture fading or brightening for reasons unrelated to
  movement: bleaching, lamp drift, changed detector settings.
- **Photobleaching** — the fluorescent molecule being destroyed by the light used to see it.
- **Sum of squared differences** — the classical alignment score: subtract, square, add. What StackReg
  and its family minimise, and what brightness drift breaks.
- **Logarithm** — a re-expression of numbers in which multiplying becomes adding.
- **Log-ratio field** — the picture of how many times brighter each spot got between two frames. Flat
  means aligned; textured means misaligned.
- **Robust scoring (Huber, Tukey)** — ways of scoring disagreements that stop a few enormous ones from
  outvoting a great many small ones. Tukey discards them entirely.
- **Chaining** — measuring each consecutive step and adding them up. Errors accumulate.
- **RCC (redundant cross-correlation)** — measuring many overlapping spans, including long ones, and
  fitting all positions at once. Six to twenty-four times more accurate for 4.45 times the work. Not
  this project's invention.
- **Phase correlation** — a Fourier method that finds the shift from where the wave crests sit.
  Naturally brightness-immune, slower, confused by real change.
- **Localisability** — how much similarity is lost when one frame is nudged a single pixel. Predicts
  whether a recording can be registered at all. Warn below 0.05.
- **Brightness ceiling** — excluding the brightest pixels. Rejects bright contamination and can run
  20–35% faster, but damages clean and fading accuracy when the bright tail is the fluorescence sample.
- **Brightness floor** — excluding the dimmest pixels. Harmful on phase contrast and mixed on measured
  fluorescence; intensity is not a modality-free measure of positional information.
- **Sub-pixel** — accurate to a fraction of a pixel, which is necessary because whole-pixel correction
  leaves an error big enough to contaminate the measurements the recording exists to make.
