/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.PixelSelectionStrategy;
import ripr.core.FrameSource;
import ripr.core.LogPlane;
import ripr.core.PairScheduler;
import ripr.core.Reconciler;
import ripr.core.Registration;

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
        boolean incrementalRotation = options.incrementalRotation && options.aligner.fitRotation;
        PairScheduler.Progress pilotProgress = incrementalRotation
                ? pilotProgressForIncrementalRotation(sink)
                : passProgress(sink, false);
        Registration.Options pilotOptions = options.copy();
        // Rotation confidence is judged on the selected-pixel final fit. The incremental path must
        // also build its mask from the one translation solution it will refine, otherwise the pilot
        // quietly pays for and depends on a separate global rigid fit.
        pilotOptions.confidenceGatedRotation = false;
        if (incrementalRotation) {
            pilotOptions.incrementalRotation = false;
            pilotOptions.aligner.fitRotation = false;
        }
        Registration.Result pilot = Registration.run(
                pilotSource, pilotOptions, pilotProgress, cancel);

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
        Registration.Options rawRefit = prepareRawRefitOptions(
                rawFitSource, options, passProgress(sink, true), cancel);
        int levels = rawRefit.aligner.levelsFor(rawFitSource.width(), rawFitSource.height());
        boolean[][][] support = new boolean[rawFitSource.count()][][];
        for (int t = 0; t < support.length; t++) {
            support[t] = LagPixelSelector.sourceSupport(referenceMask,
                    rawFitSource.width(), rawFitSource.height(), levels, pilot.cumulative[t]);
        }
        PairScheduler.Progress refitProgress = incrementalRotation
                ? incrementalRefitProgress(sink)
                : passProgress(sink, true);
        return Registration.refitWithSupport(rawFitSource, rawRefit, pilot, support,
                refitProgress, cancel);
    }

    public static Registration.Options prepareRawRefitOptions(FrameSource source,
                                                               Registration.Options options) {
        return prepareRawRefitOptions(source, options, PairScheduler.Progress.NONE,
                PairScheduler.Cancellation.NEVER);
    }

    /**
     * Build the selected-pixel support for a caller-supplied translation pilot.
     *
     * <p>Split rotation uses the established Automatic translation as that pilot, so an angle
     * recipe may still choose strong-area selection without running or substituting its own global
     * translation method first.
     */
    public static boolean[][][] supportForPilot(
            FrameSource scoringSource, FrameSource fitSource, Registration.Options options,
            PixelSelectionStrategy strategy, double removePercent, Registration.Result pilot) {
        if (scoringSource == null || fitSource == null || options == null || strategy == null
                || pilot == null || strategy == PixelSelectionStrategy.NONE) {
            throw new IllegalArgumentException("selected-pixel support inputs are required");
        }
        if (!(removePercent > 0 && removePercent < 100)) {
            throw new IllegalArgumentException("pixel removal must be in (0,100)");
        }
        sameShape(scoringSource, fitSource);
        if (pilot.cumulative.length != fitSource.count()) {
            throw new IllegalArgumentException("pilot and selected-pixel sources differ in length");
        }
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
            LogPlane reference = LogPlane.of(scoringSource.plane(referenceFrame),
                    scoringSource.width(), scoringSource.height(), options.epsilon);
            scores = LagPixelSelector.spatialInformationScore(reference);
        }
        LagPixelSelector.Score score = scoreFor(strategy);
        boolean removeHighest = removeHighest(strategy);
        boolean[] referenceMask = strategy
                == PixelSelectionStrategy.STRATIFIED_LOWEST_ANCHOR_TRUST
                ? LagPixelSelector.stratifiedMask(
                        scores, score, removePercent, removeHighest, 32, 4)
                : LagPixelSelector.mask(scores, score, removePercent, removeHighest);
        int levels = options.aligner.levelsFor(fitSource.width(), fitSource.height());
        boolean[][][] support = new boolean[fitSource.count()][][];
        for (int t = 0; t < support.length; t++) {
            support[t] = LagPixelSelector.sourceSupport(referenceMask,
                    fitSource.width(), fitSource.height(), levels, pilot.cumulative[t]);
        }
        return support;
    }

    private static Registration.Options prepareRawRefitOptions(
            FrameSource source, Registration.Options options, PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
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
            rawRefit.aligner.maxShift = Registration.estimateShiftBound(
                    source, rawRefit, progress, cancellation).suggestedMaxShift;
            rawRefit.autoMaxShift = false;
        }
        return rawRefit;
    }

    /** Preserve rich lifecycle events while presenting the two fits as one overall operation. */
    static PairScheduler.Progress passProgress(PairScheduler.Progress sink,
                                               boolean secondPass) {
        return new PairScheduler.Progress() {
            private volatile int pairs;

            @Override public void begin(int total, int workers) {
                pairs = total;
                if (!secondPass) sink.begin(2 * total, workers);
                sink.phase(secondPass ? "Selected-pixel refit" : "Pilot alignment",
                        secondPass ? total : 0, 2 * total);
            }

            @Override public void taskStarted(int index, int total) {
                int count = pairs > 0 ? pairs : total;
                sink.taskStarted((secondPass ? count : 0) + index, 2 * count);
            }

            @Override public void update(int done, int total) {
                sink.update((secondPass ? total : 0) + done, 2 * total);
            }

            @Override public void phase(String name, int done, int total) {
                sink.phase((secondPass ? "Selected-pixel refit: " : "Pilot alignment: ") + name,
                        done, total);
            }
        };
    }

    /** First of three pair batches: pilot translation, selected translation, rotation test. */
    private static PairScheduler.Progress pilotProgressForIncrementalRotation(
            PairScheduler.Progress sink) {
        return new PairScheduler.Progress() {
            @Override public void begin(int total, int workers) {
                sink.begin(3 * total, workers);
                sink.phase("Pilot translation", 0, total);
            }

            @Override public void taskStarted(int index, int total) {
                sink.taskStarted(index, 3 * total);
            }

            @Override public void update(int done, int total) {
                sink.update(done, 3 * total);
            }

            @Override public void phase(String name, int done, int total) {
                sink.phase("Pilot translation: " + name, done, total);
            }
        };
    }

    /** Last two of three pair batches, supplied as one staged refit by Registration. */
    private static PairScheduler.Progress incrementalRefitProgress(PairScheduler.Progress sink) {
        return new PairScheduler.Progress() {
            private volatile int pairs;

            @Override public void begin(int total, int workers) {
                pairs = total / 2;
                sink.phase("Selected-pixel rotation refit", 0, total);
            }

            @Override public void taskStarted(int index, int total) {
                int base = pairs > 0 ? pairs : total / 2;
                sink.taskStarted(base + index, 3 * base);
            }

            @Override public void update(int done, int total) {
                int base = pairs > 0 ? pairs : total / 2;
                sink.update(base + done, 3 * base);
            }

            @Override public void phase(String name, int done, int total) {
                sink.phase("Selected-pixel rotation refit: " + name, done, total);
            }
        };
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
