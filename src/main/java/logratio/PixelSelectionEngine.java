/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.PixelSelectionStrategy;
import logratio.core.FrameSource;
import logratio.core.LogPlane;
import logratio.core.PairScheduler;
import logratio.core.Reconciler;
import logratio.core.Registration;

/** Two-pass registration in which a scoring copy selects pixels and raw values drive the final fit. */
public final class PixelSelectionEngine {
    private PixelSelectionEngine() {
    }

    public static Registration.Result run(FrameSource pilotSource, FrameSource scoringSource,
                                          FrameSource rawFitSource, Registration.Options options,
                                          PixelSelectionStrategy strategy, double removePercent,
                                          PairScheduler.Progress progress,
                                          PairScheduler.Cancellation cancellation) {
        if (pilotSource == null || scoringSource == null || rawFitSource == null
                || options == null || strategy == null) {
            throw new IllegalArgumentException("pixel-selection inputs are required");
        }
        if (strategy == PixelSelectionStrategy.NONE) {
            return Registration.run(pilotSource, options, progress, cancellation);
        }
        if (!(removePercent > 0 && removePercent < 100)) {
            throw new IllegalArgumentException("pixel removal must be in (0,100)");
        }
        sameShape(pilotSource, scoringSource);
        sameShape(pilotSource, rawFitSource);
        if (options.reference == Reconciler.Reference.ROLLING) {
            throw new IllegalArgumentException(
                    "pixel removal cannot be combined with a rolling reference template");
        }

        PairScheduler.Progress sink = progress == null ? PairScheduler.Progress.NONE : progress;
        PairScheduler.Cancellation cancel = cancellation == null
                ? PairScheduler.Cancellation.NEVER : cancellation;
        PairScheduler.Progress pilotProgress = (done, total) -> sink.update(done, 2 * total);
        Registration.Result pilot = Registration.run(
                pilotSource, options, pilotProgress, cancel);

        LagPixelSelector.Scores scores;
        int referenceFrame = options.reference == Reconciler.Reference.FIXED
                ? options.referenceFrame : 0;
        if (strategy.usesTemporalEvidence()) {
            LogPlane[] frames = new LogPlane[scoringSource.count()];
            for (int t = 0; t < frames.length; t++) {
                frames[t] = LogPlane.of(scoringSource.plane(t), scoringSource.width(),
                        scoringSource.height(), options.epsilon);
            }
            scores = LagPixelSelector.score(
                    frames, pilot.cumulative, options.lags, referenceFrame);
        } else {
            LogPlane reference = LogPlane.of(scoringSource.plane(referenceFrame), scoringSource.width(),
                    scoringSource.height(), options.epsilon);
            scores = LagPixelSelector.spatialInformationScore(reference);
        }

        LagPixelSelector.Score score = scoreFor(strategy);
        boolean removeHighest = removeHighest(strategy);
        boolean[] referenceMask = strategy
                == PixelSelectionStrategy.STRATIFIED_LOWEST_ANCHOR_TRUST
                ? LagPixelSelector.stratifiedMask(
                        scores, score, removePercent, removeHighest, 32, 4)
                : LagPixelSelector.mask(scores, score, removePercent, removeHighest);
        Registration.Options rawRefit = prepareRawRefitOptions(rawFitSource, options);
        int levels = rawRefit.aligner.levelsFor(rawFitSource.width(), rawFitSource.height());
        boolean[][][] support = new boolean[rawFitSource.count()][][];
        for (int t = 0; t < support.length; t++) {
            support[t] = LagPixelSelector.sourceSupport(referenceMask,
                    rawFitSource.width(), rawFitSource.height(), levels, pilot.cumulative[t]);
        }
        PairScheduler.Progress refitProgress = (done, total) -> sink.update(total + done, 2 * total);
        return Registration.refitWithSupport(rawFitSource, rawRefit, pilot, support,
                refitProgress, cancel);
    }

    static Registration.Options prepareRawRefitOptions(FrameSource source,
                                                        Registration.Options options) {
        Registration.Options rawRefit = options.copy();
        // The controlled winner fitted the original measurement range under the explicit spatial
        // mask. Percentile bands and additive-background removal belong to the provisional preset;
        // carrying them into the second pass would silently combine a different pixel-removal rule.
        rawRefit.intensityFloor = LogPlane.NO_FLOOR;
        rawRefit.intensityFloorPercentile = Double.NaN;
        rawRefit.saturationMax = LogPlane.NO_SATURATION;
        rawRefit.saturationPercentile = Double.NaN;
        rawRefit.removeOffset = false;
        // REGRESSION GUARD: automatic movement can require more pyramid levels than the caller's
        // initial bound. Resolve it before the support pyramids are allocated, then do not resolve it
        // a second time inside refitWithSupport.
        if (rawRefit.autoMaxShift) {
            rawRefit.aligner.maxShift = Registration.estimateShiftBound(source, rawRefit)
                    .suggestedMaxShift;
            rawRefit.autoMaxShift = false;
        }
        return rawRefit;
    }

    private static LagPixelSelector.Score scoreFor(PixelSelectionStrategy strategy) {
        switch (strategy) {
            case REMOVE_MOST_UNSTABLE:
            case REMOVE_LEAST_UNSTABLE:
                return LagPixelSelector.Score.INSTABILITY;
            case REMOVE_MOST_LAG_GROWTH:
            case REMOVE_LEAST_LAG_GROWTH:
                return LagPixelSelector.Score.LAG_GROWTH;
            case REMOVE_LEAST_INFORMATIVE:
            case REMOVE_MOST_INFORMATIVE:
                return LagPixelSelector.Score.INFORMATION;
            case REMOVE_LOWEST_ANCHOR_TRUST:
            case REMOVE_HIGHEST_ANCHOR_TRUST:
            case STRATIFIED_LOWEST_ANCHOR_TRUST:
                return LagPixelSelector.Score.ANCHOR_TRUST;
            default:
                throw new IllegalArgumentException("no score for " + strategy);
        }
    }

    private static boolean removeHighest(PixelSelectionStrategy strategy) {
        return strategy == PixelSelectionStrategy.REMOVE_MOST_UNSTABLE
                || strategy == PixelSelectionStrategy.REMOVE_MOST_LAG_GROWTH
                || strategy == PixelSelectionStrategy.REMOVE_MOST_INFORMATIVE
                || strategy == PixelSelectionStrategy.REMOVE_HIGHEST_ANCHOR_TRUST;
    }

    private static void sameShape(FrameSource a, FrameSource b) {
        if (a.count() != b.count() || a.width() != b.width() || a.height() != b.height()) {
            throw new IllegalArgumentException("pilot, scoring and raw sources must have the same shape");
        }
    }
}
