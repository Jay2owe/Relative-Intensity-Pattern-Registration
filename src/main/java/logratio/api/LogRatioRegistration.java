/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import ij.ImagePlus;
import logratio.StackFrames;
import logratio.StackWarper;
import logratio.ScaledFrameSource;
import logratio.PreprocessedFrameSource;
import logratio.PixelSelectionEngine;
import logratio.core.FrameSource;
import logratio.core.PairAligner;
import logratio.core.PairScheduler;
import logratio.core.Registration;

/** Public no-dialog API for the complete log-ratio registration operation. */
public final class LogRatioRegistration {
    private LogRatioRegistration() { }

    public static LogRatioResult register(ImagePlus image) {
        return register(image, LogRatioParameters.builder().build());
    }

    public static LogRatioResult register(ImagePlus image, LogRatioParameters parameters) {
        return register(image, parameters, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
    }

    public static LogRatioResult register(ImagePlus image, LogRatioParameters parameters,
                                          PairScheduler.Progress progress,
                                          PairScheduler.Cancellation cancellation) {
        AutomaticRegistrationSelector.Result selection =
                parameters != null && parameters.selectionMode == SelectionMode.AUTOMATIC
                        ? resolveAutomaticSettings(image, parameters, cancellation) : null;
        LogRatioParameters resolved = selection == null ? parameters : selection.parameters;
        Registration.Result registration = estimateResolved(image, resolved, progress, cancellation);
        ImagePlus corrected = StackWarper.apply(image, registration.cumulative,
                resolved.interpolation, resolved.crop);
        return new LogRatioResult(corrected, registration, resolved, selection);
    }

    /** Estimates movement without creating a corrected image; useful for previews and parameter sweeps. */
    public static Registration.Result estimate(ImagePlus image, LogRatioParameters parameters,
                                                PairScheduler.Progress progress,
                                                PairScheduler.Cancellation cancellation) {
        AutomaticRegistrationSelector.Result selection = parameters != null
                && parameters.selectionMode == SelectionMode.AUTOMATIC
                ? resolveAutomaticSettings(image, parameters, cancellation) : null;
        return estimateResolved(image, selection == null ? parameters : selection.parameters,
                progress, cancellation);
    }

    /**
     * Resolve the complete automatic recipe without correcting the source image.
     *
     * <p>The returned parameters are ordinary explicit settings: support, gradient multiplier,
     * intensity percentiles, estimation filter, spatial removal, mask-scoring filter, removal
     * percentage and both compute budgets. Everything can be reviewed, edited, recorded and replayed.
     */
    public static AutomaticRegistrationSelector.Result resolveAutomaticSettings(
            ImagePlus image, LogRatioParameters parameters) {
        return resolveAutomaticSettings(image, parameters, PairScheduler.Cancellation.NEVER);
    }

    private static AutomaticRegistrationSelector.Result resolveAutomaticSettings(
            ImagePlus image, LogRatioParameters parameters,
            PairScheduler.Cancellation cancellation) {
        if (image == null) throw new IllegalArgumentException("image is null");
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        // Every entry point hands in the category recommendation as the base, and the selector may
        // override only support, band, filter and mask on top of it. Anything else the caller set —
        // reference strategy, lags, movement bound, threads — is honoured rather than overwritten,
        // so automatic selection never silently discards an explicit choice.
        LogRatioParameters category = parameters.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .build();
        // The frozen model holds candidates only for the image types where an override was measured
        // as both safe and better. For the others it declines whatever the evidence says, so running
        // the provisional pass to reach that foregone conclusion would double the cost for nothing.
        // Skipping it cannot change any decision: there is no candidate to choose.
        if (!AutomaticRegistrationSelector.servesImageType(category.imageType)) {
            return stamp(AutomaticRegistrationSelector.declined(category));
        }
        LogRatioParameters base = neutralProvisionalBase(category);
        Registration.Result provisional = estimateResolved(image, base,
                PairScheduler.Progress.NONE, cancellation);
        StackFrames raw = StackFrames.of(image, base.channel, base.slice);
        AutomaticRegistrationSelector.Evidence evidence =
                AutomaticRegistrationSelector.measure(raw, provisional.cumulative, category);
        return stamp(AutomaticRegistrationSelector.select(evidence, category));
    }

    /** Record where the resolved values came from, so a run log never has to guess. */
    private static AutomaticRegistrationSelector.Result stamp(
            AutomaticRegistrationSelector.Result result) {
        LogRatioParameters stamped = result.parameters.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .recipeProvenance((result.fallback
                        ? "category recommendation retained by automatic full selection: "
                        : "automatic full selection: ") + result.recipe.id())
                .build();
        return new AutomaticRegistrationSelector.Result(result.evidence, result.recipe,
                result.fallback, result.predictedGain, result.confidenceThreshold,
                result.candidateGains, stamped);
    }

    /**
     * The neutral base the provisional pass runs on: every pixel, no band, no filter, no mask.
     * Measuring movement on filtered pixels would let a filter decide which filter to test.
     */
    private static LogRatioParameters neutralProvisionalBase(LogRatioParameters parameters) {
        return parameters.toBuilder()
                .useRecommendation(false)
                .automaticFilterSelection(false)
                .selectionMode(SelectionMode.MANUAL)
                .pixelSupport(PairAligner.PixelSupport.ALL)
                .floorPercentile(Double.NaN)
                .ceilingPercentile(Double.NaN)
                .preprocessing(Preprocessing.NONE)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .pixelSelectionPreprocessing(Preprocessing.NONE)
                .pixelRemovalPercent(25.0)
                .build();
    }

    /**
     * Resolve the automatic preprocessing/pixel-removal choice without correcting the source image.
     * The returned parameters are ordinary explicit settings and can be reviewed, edited or recorded.
     */
    public static AutomaticFilterSelector.Result resolveAutomaticFilters(
            ImagePlus image, LogRatioParameters parameters) {
        return resolveAutomaticFilters(image, parameters, PairScheduler.Cancellation.NEVER);
    }

    private static AutomaticFilterSelector.Result resolveAutomaticFilters(
            ImagePlus image, LogRatioParameters parameters,
            PairScheduler.Cancellation cancellation) {
        if (image == null) throw new IllegalArgumentException("image is null");
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        LogRatioParameters base = parameters.toBuilder()
                .automaticFilterSelection(false)
                .useRecommendation(false)
                .preprocessing(Preprocessing.NONE)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .pixelSelectionPreprocessing(Preprocessing.NONE)
                .pixelRemovalPercent(25.0)
                .build();
        Registration.Result provisional = estimateResolved(image, base,
                PairScheduler.Progress.NONE, cancellation);
        StackFrames raw = StackFrames.of(image, base.channel, base.slice);
        AutomaticFilterSelector.Evidence evidence = AutomaticFilterSelector.measure(
                raw, provisional.cumulative);
        return AutomaticFilterSelector.select(evidence, base);
    }

    private static Registration.Result estimateResolved(
            ImagePlus image, LogRatioParameters parameters,
            PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
        if (image == null) throw new IllegalArgumentException("image is null");
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        if (parameters.channel > Math.max(1, image.getNChannels())) {
            throw new IllegalArgumentException("channel " + parameters.channel + " outside image channels");
        }
        if (parameters.slice > Math.max(1, image.getNSlices())) {
            throw new IllegalArgumentException("slice " + parameters.slice + " outside image slices");
        }
        StackFrames nativeSource = StackFrames.of(image, parameters.channel, parameters.slice);
        FrameSource preparedSource = parameters.preprocessing == Preprocessing.NONE
                ? nativeSource : new PreprocessedFrameSource(nativeSource, parameters.preprocessing);
        FrameSource source = parameters.estimationScale == 1.0
                ? preparedSource : new ScaledFrameSource(preparedSource, parameters.estimationScale);
        if (parameters.referenceFrame > source.count()) {
            throw new IllegalArgumentException("reference frame " + parameters.referenceFrame
                    + " outside image timepoints");
        }
        Registration.Options options = parameters.registrationOptions();
        if (parameters.estimationScale < 1.0 && !options.autoMaxShift) {
            options.aligner.maxShift *= parameters.estimationScale;
        }
        Registration.Result result;
        if (parameters.pixelSelectionStrategy == PixelSelectionStrategy.NONE) {
            result = Registration.run(source, options, progress, cancellation);
        } else {
            FrameSource scoringPrepared = parameters.pixelSelectionPreprocessing == Preprocessing.NONE
                    ? nativeSource : new PreprocessedFrameSource(
                            nativeSource, parameters.pixelSelectionPreprocessing);
            FrameSource scoringSource = parameters.estimationScale == 1.0
                    ? scoringPrepared : new ScaledFrameSource(
                            scoringPrepared, parameters.estimationScale);
            FrameSource rawFitSource = parameters.estimationScale == 1.0
                    ? nativeSource : new ScaledFrameSource(nativeSource, parameters.estimationScale);
            result = PixelSelectionEngine.run(source, scoringSource, rawFitSource, options,
                    parameters.pixelSelectionStrategy, parameters.pixelRemovalPercent,
                    progress, cancellation);
        }
        return parameters.estimationScale == 1.0
                ? result : result.scaleTranslations(1.0 / parameters.estimationScale);
    }
}
