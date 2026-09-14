/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ij.ImagePlus;
import ripr.StackFrames;
import ripr.StackWarper;
import ripr.ScaledFrameSource;
import ripr.PreprocessedFrameSource;
import ripr.PixelSelectionEngine;
import ripr.core.FrameSource;
import ripr.core.LongitudinalRegistration;
import ripr.core.PairAligner;
import ripr.core.PairScheduler;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.RobustNorm;
import ripr.core.RotationMode;

import java.util.Locale;

/** Public no-dialog API for the complete relative-intensity pattern registration operation. */
public final class RelativeIntensityPatternRegistration {
    private RelativeIntensityPatternRegistration() { }

    public static RelativeIntensityPatternResult register(ImagePlus image) {
        return register(image, RelativeIntensityPatternParameters.builder().build());
    }

    public static RelativeIntensityPatternResult register(ImagePlus image, RelativeIntensityPatternParameters parameters) {
        return register(image, parameters, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
    }

    public static RelativeIntensityPatternResult register(ImagePlus image, RelativeIntensityPatternParameters parameters,
                                          PairScheduler.Progress progress,
                                          PairScheduler.Cancellation cancellation) {
        if (parameters != null
                && parameters.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY) {
            LongitudinalResolved longitudinal = estimateLongitudinal(
                    image, parameters, progress, cancellation);
            ImagePlus corrected = StackWarper.apply(image,
                    longitudinal.registration.cumulative,
                    longitudinal.parameters.interpolation,
                    longitudinal.parameters.crop, progress, cancellation);
            return new RelativeIntensityPatternResult(corrected, longitudinal.registration,
                    longitudinal.parameters, null, null, longitudinal.diagnostics);
        }
        AutomaticRegistrationSelector.Result selection =
                parameters != null && parameters.selectionMode == SelectionMode.AUTOMATIC
                        ? resolveAutomaticSettings(image, parameters, progress, cancellation) : null;
        RelativeIntensityPatternParameters resolved = selection == null ? parameters : selection.parameters;
        AutomaticRotationSelector.Result rotationSelection =
                AutomaticRotationSelector.fromResolved(resolved);
        Registration.Result registration = rotationSelection == null
                ? estimateResolved(image, resolved, progress, cancellation)
                : estimateWithRotationRecipe(image, resolved, rotationSelection.parameters,
                        rotationSelection.globalProposalComparison, progress, cancellation);
        ImagePlus corrected = StackWarper.apply(image, registration.cumulative,
                resolved.interpolation, resolved.crop, progress, cancellation);
        return new RelativeIntensityPatternResult(corrected, registration, resolved, selection,
                rotationSelection, null);
    }

    /** Estimates movement without creating a corrected image; useful for previews and parameter sweeps. */
    public static Registration.Result estimate(ImagePlus image, RelativeIntensityPatternParameters parameters,
                                                PairScheduler.Progress progress,
                                                PairScheduler.Cancellation cancellation) {
        if (parameters != null
                && parameters.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY) {
            return estimateLongitudinal(image, parameters, progress, cancellation).registration;
        }
        AutomaticRegistrationSelector.Result selection = parameters != null
                && parameters.selectionMode == SelectionMode.AUTOMATIC
                ? resolveAutomaticSettings(image, parameters, progress, cancellation) : null;
        RelativeIntensityPatternParameters resolved = selection == null ? parameters : selection.parameters;
        AutomaticRotationSelector.Result rotationSelection =
                AutomaticRotationSelector.fromResolved(resolved);
        return rotationSelection == null
                ? estimateResolved(image, resolved, progress, cancellation)
                : estimateWithRotationRecipe(image, resolved, rotationSelection.parameters,
                        rotationSelection.globalProposalComparison, progress, cancellation);
    }

    private static final class LongitudinalResolved {
        final Registration.Result registration;
        final RelativeIntensityPatternParameters parameters;
        final LongitudinalRegistration.Diagnostics diagnostics;

        LongitudinalResolved(Registration.Result registration,
                             RelativeIntensityPatternParameters parameters,
                             LongitudinalRegistration.Diagnostics diagnostics) {
            this.registration = registration;
            this.parameters = parameters;
            this.diagnostics = diagnostics;
        }
    }

    /** Current Automatic fixed recipe followed by the validated whole-recording refinement. */
    private static LongitudinalResolved estimateLongitudinal(
            ImagePlus image, RelativeIntensityPatternParameters requested,
            PairScheduler.Progress progress, PairScheduler.Cancellation cancellation) {
        if (image == null || requested == null) {
            throw new IllegalArgumentException("image and parameters are required");
        }
        if (requested.rotationMode != RotationMode.OFF) {
            throw new IllegalArgumentException(
                    "Longitudinal maximum accuracy supports translation and rare guarded jumps, "
                    + "not continuous or declared-event rotation; use Automatic for those recordings");
        }
        RelativeIntensityPatternParameters preliminaryRequest = requested.toBuilder()
                .selectionMode(SelectionMode.AUTOMATIC)
                .rotationMode(RotationMode.OFF)
                .rotationEventFrames()
                .fitRotation(false)
                .build();
        AutomaticRegistrationSelector.Result automatic = resolveAutomaticSettings(
                image, preliminaryRequest,
                namedProgress(progress, "Longitudinal preliminary Automatic fixed recipe"),
                cancellation);
        Registration.Result baseline = estimateResolved(
                image, automatic.parameters,
                namedProgress(progress, "Longitudinal preliminary Automatic fixed recipe"),
                cancellation);
        StackFrames source = StackFrames.of(
                image, requested.channel, requested.slice);
        boolean transmitted = requested.imageType == ImageType.PHASE_CONTRAST
                || requested.imageType == ImageType.BRIGHTFIELD_DIC;
        LongitudinalRegistration.Outcome refined = LongitudinalRegistration.refine(
                source, baseline, transmitted, requested.maxRotationDegrees,
                requested.threads,
                progress, cancellation);
        String provenance = "selection=longitudinal_maximum_accuracy; "
                + "recipe=declared_image_type_r14; "
                + "preliminary=current_automatic_fixed_recipe; "
                + "selected_channel_only=true; route=" + refined.diagnostics.route;
        RelativeIntensityPatternParameters resolved = automatic.parameters.toBuilder()
                .selectionMode(SelectionMode.LONGITUDINAL_ACCURACY)
                .recipeProvenance(provenance)
                .build();
        return new LongitudinalResolved(
                refined.registration, resolved, refined.diagnostics);
    }

    /**
     * Estimate rigid movement with separate translation and angle recipes.
     *
     * <p>If both recipes are identical this deliberately delegates to the existing incremental
     * implementation. That exact branch is the one already validated for Phase. Otherwise the
     * translation recipe runs once, the angle recipe tests rotation from that result, and accepted
     * angles are handed back to the translation recipe for a fixed-angle dx/dy refit.
     */
    public static Registration.Result estimateWithRotationRecipe(
            ImagePlus image, RelativeIntensityPatternParameters translationParameters,
            RelativeIntensityPatternParameters rotationParameters, PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
        return estimateWithRotationRecipe(image, translationParameters, rotationParameters,
                false, progress, cancellation);
    }

    /**
     * Estimate with separate recipes and choose whether the angle recipe may compare a global rigid
     * proposal against its translation-seeded proposal. The lower-residual proposal wins per pair.
     * In both modes the translation recipe remains authoritative for the final dx and dy.
     */
    public static Registration.Result estimateWithRotationRecipe(
            ImagePlus image, RelativeIntensityPatternParameters translationParameters,
            RelativeIntensityPatternParameters rotationParameters, boolean globalRotationProposal,
            PairScheduler.Progress progress, PairScheduler.Cancellation cancellation) {
        if (image == null || translationParameters == null || rotationParameters == null) {
            throw new IllegalArgumentException("image and both split recipes are required");
        }
        if (translationParameters.estimationScale != rotationParameters.estimationScale) {
            throw new IllegalArgumentException(
                    "translation and rotation recipes must use the same estimation scale");
        }
        String translationId = RegistrationRecipe.of(translationParameters).id();
        String rotationId = RegistrationRecipe.of(rotationParameters).id();
        if (translationId.equals(rotationId) && !globalRotationProposal) {
            RelativeIntensityPatternParameters sameRecipe = translationParameters.toBuilder()
                    .fitRotation(true)
                    .incrementalRotation(true)
                    .minimumRotationResidualGain(
                            rotationParameters.minimumRotationResidualGain)
                    .maxRotationDegrees(rotationParameters.maxRotationDegrees)
                    .build();
            return estimateResolved(image, sameRecipe, progress, cancellation);
        }
        RelativeIntensityPatternParameters translationOnly = translationParameters.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(false)
                .incrementalRotation(false)
                .rotationRecipeId("")
                .globalRotationProposal(false)
                .rotationSelectorStep90(Double.NaN)
                .build();
        RelativeIntensityPatternParameters rotation = rotationParameters.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(true)
                .incrementalRotation(false)
                .build();
        Registration.Result translation = estimateResolvedAtScale(
                image, translationOnly,
                namedProgress(progress, "Automatic translation recipe"), cancellation);
        PreparedSplitPass translationPass = prepareSplitPass(
                image, translationOnly, translation, false, cancellation);
        PreparedSplitPass rotationPass = prepareSplitPass(
                image, rotation, translation, true, cancellation);
        Registration.Result split = Registration.applySplitRotation(
                translationPass.source, translationPass.options, translation,
                translationPass.support,
                rotationPass.source, rotationPass.options, rotationPass.support,
                globalRotationProposal,
                namedProgress(progress, "Rotation-specific selector recipe"), cancellation);
        return translationOnly.estimationScale == 1.0
                ? split : split.scaleTranslations(1.0 / translationOnly.estimationScale);
    }

    /**
     * Resolve the complete automatic recipe without correcting the source image.
     *
     * <p>The returned parameters are ordinary explicit settings: support, gradient multiplier,
     * intensity percentiles, estimation filter, spatial removal, mask-scoring filter, removal
     * percentage and both compute budgets. Everything can be reviewed, edited, recorded and replayed.
     */
    public static AutomaticRegistrationSelector.Result resolveAutomaticSettings(
            ImagePlus image, RelativeIntensityPatternParameters parameters) {
        return resolveAutomaticSettings(image, parameters, PairScheduler.Progress.NONE,
                PairScheduler.Cancellation.NEVER);
    }

    private static AutomaticRegistrationSelector.Result resolveAutomaticSettings(
            ImagePlus image, RelativeIntensityPatternParameters parameters,
            PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
        if (image == null) throw new IllegalArgumentException("image is null");
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        PairScheduler.Progress sink = progress == null ? PairScheduler.Progress.NONE : progress;
        // Automatic starts from the declared image-and-motion recommendation. A fixed policy may
        // replace the whole registration recipe; execution controls such as movement bound and
        // worker count still come from the caller.
        boolean rotationRequested = parameters.rotationMode == RotationMode.CONTINUOUS;
        boolean knownEventsRequested = parameters.rotationMode == RotationMode.KNOWN_EVENTS;
        RelativeIntensityPatternParameters category = parameters.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                // Translation selection is unchanged by requesting rotation. Its chosen estimator,
                // filter, support and exclusions remain authoritative for dx/dy.
                .rotationMode(RotationMode.OFF)
                .rotationEventFrames()
                .incrementalRotation(false)
                .rotationRecipeId("")
                .globalRotationProposal(false)
                .rotationSelectorStep90(Double.NaN)
                .build();
        // The frozen model holds candidates only for the image types where an override was measured
        // as both safe and better. For the others it declines whatever the evidence says, so running
        // the provisional pass to reach that foregone conclusion would double the cost for nothing.
        // Skipping it cannot change any decision: there is no candidate to choose.
        AutomaticRegistrationSelector.Result translationSelection =
                AutomaticEmissionPolicy.select(category);
        if (translationSelection != null) {
            translationSelection = stamp(
                    translationSelection,
                    AutomaticEmissionPolicy.VERSION,
                    AutomaticEmissionPolicy.MODEL_KIND,
                    AutomaticEmissionPolicy.VALIDATION_STATUS,
                    AutomaticEmissionPolicy.FEATURE_CONTRACT_VERSION,
                    AutomaticEmissionPolicy.PROTOCOL_SHA256,
                    AutomaticEmissionPolicy.CANDIDATE_MANIFEST_SHA256,
                    AutomaticEmissionPolicy.MODEL_ARTIFACT_SHA256);
        } else if ("image_type_rule".equals(AutomaticRegistrationSelectorModel.MODEL_KIND)) {
            translationSelection = stamp(
                    AutomaticRegistrationSelector.selectWithoutEvidence(category, false));
        } else if (!AutomaticRegistrationSelector.requiresRecordingEvidence(
                category.imageType, false)) {
            translationSelection = stamp(AutomaticRegistrationSelector.declined(
                    category, "no validated candidate serves this scope"));
        } else {
            RelativeIntensityPatternParameters base = automaticSelectorPilot(category);
            PairScheduler.Progress provisionalProgress = namedProgress(
                    sink, "Automatic translation selector: provisional alignment");
            Registration.Result provisional = estimateResolved(image, base,
                    provisionalProgress, cancellation);
            sink.phase("Automatic translation selector: classifying image", 0, 1);
            StackFrames raw = StackFrames.of(image, base.channel, base.slice);
            AutomaticRegistrationSelector.Evidence evidence =
                    AutomaticRegistrationSelector.measure(raw, provisional.cumulative, category);
            sink.phase("Automatic translation selector: classifying image", 1, 1);
            translationSelection = stamp(AutomaticRegistrationSelector.select(
                    evidence, category, false));
        }
        if (knownEventsRequested) {
            RelativeIntensityPatternParameters combined = translationSelection.parameters.toBuilder()
                    .rotationMode(RotationMode.KNOWN_EVENTS)
                    .rotationEventFrames(parameters.rotationEventFrames())
                    .rotationEventWindow(parameters.rotationEventWindow)
                    .maxRotationDegrees(parameters.maxRotationDegrees)
                    .incrementalRotation(false)
                    .rotationRecipeId("")
                    .globalRotationProposal(false)
                    .rotationSelectorStep90(Double.NaN)
                    .recipeProvenance(translationSelection.parameters.recipeProvenance)
                    .build();
            return new AutomaticRegistrationSelector.Result(
                    translationSelection.evidence, translationSelection.recipe,
                    translationSelection.fallback, translationSelection.predictedGain,
                    translationSelection.confidenceThreshold, translationSelection.candidateGains,
                    combined, translationSelection.declineReason);
        }
        if (!rotationRequested) return translationSelection;
        RelativeIntensityPatternParameters rotationBase = category.toBuilder()
                .fitRotation(true)
                .maxRotationDegrees(parameters.maxRotationDegrees)
                .build();
        AutomaticRotationSelector.Result rotation = resolveAutomaticRotationSettings(
                image, rotationBase, sink, cancellation);
        RelativeIntensityPatternParameters combined = translationSelection.parameters.toBuilder()
                .fitRotation(true)
                .incrementalRotation(true)
                .maxRotationDegrees(parameters.maxRotationDegrees)
                .minimumRotationResidualGain(rotation.minimumResidualGain)
                .rotationRecipeId(rotation.parameters.rotationRecipeId)
                .globalRotationProposal(rotation.globalProposalComparison)
                .rotationSelectorStep90(rotation.provisionalStep90)
                .recipeProvenance(translationSelection.parameters.recipeProvenance)
                .build();
        return new AutomaticRegistrationSelector.Result(
                translationSelection.evidence, translationSelection.recipe,
                translationSelection.fallback, translationSelection.predictedGain,
                translationSelection.confidenceThreshold, translationSelection.candidateGains,
                combined, translationSelection.declineReason);
    }

    /** Resolve the separate frozen angle recipe without correcting the source image. */
    public static AutomaticRotationSelector.Result resolveAutomaticRotationSettings(
            ImagePlus image, RelativeIntensityPatternParameters parameters) {
        return resolveAutomaticRotationSettings(image, parameters, PairScheduler.Progress.NONE,
                PairScheduler.Cancellation.NEVER);
    }

    private static AutomaticRotationSelector.Result resolveAutomaticRotationSettings(
            ImagePlus image, RelativeIntensityPatternParameters parameters, PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
        if (image == null) throw new IllegalArgumentException("image is null");
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        PairScheduler.Progress sink = progress == null ? PairScheduler.Progress.NONE : progress;
        AutomaticRegistrationSelector.Evidence evidence = null;
        if (parameters.imageType == ImageType.PHASE_CONTRAST) {
            RelativeIntensityPatternParameters neutral = automaticSelectorPilot(parameters);
            Registration.Result provisional = estimateResolved(image, neutral,
                    namedProgress(sink,
                            "Automatic rotation selector: neutral provisional alignment"),
                    cancellation);
            sink.phase("Automatic rotation selector: classifying Phase search basin", 0, 1);
            StackFrames raw = StackFrames.of(image, neutral.channel, neutral.slice);
            evidence = AutomaticRegistrationSelector.measure(
                    raw, provisional.cumulative, parameters);
            sink.phase("Automatic rotation selector: classifying Phase search basin", 1, 1);
        } else {
            sink.phase("Automatic rotation selector: applying frozen image-type policy", 0, 1);
            sink.phase("Automatic rotation selector: applying frozen image-type policy", 1, 1);
        }
        return AutomaticRotationSelector.select(parameters, evidence);
    }

    private static PairScheduler.Progress namedProgress(PairScheduler.Progress progress,
                                                         String name) {
        PairScheduler.Progress sink = progress == null ? PairScheduler.Progress.NONE : progress;
        return new PairScheduler.Progress() {
            @Override public void begin(int total, int workers) {
                sink.begin(total, workers);
                sink.phase(name, 0, total);
            }

            @Override public void taskStarted(int index, int total) {
                sink.taskStarted(index, total);
            }

            @Override public void update(int done, int total) {
                sink.update(done, total);
            }

            @Override public void phase(String phase, int done, int total) {
                sink.phase(name + ": " + phase, done, total);
            }
        };
    }

    /** Record where the resolved values came from, so a run log never has to guess. */
    private static AutomaticRegistrationSelector.Result stamp(
            AutomaticRegistrationSelector.Result result) {
        return stamp(result,
                AutomaticRegistrationSelectorModel.MODEL_VERSION,
                AutomaticRegistrationSelectorModel.MODEL_KIND,
                AutomaticRegistrationSelectorModel.VALIDATION_STATUS,
                AutomaticRegistrationSelectorModel.FEATURE_CONTRACT_VERSION,
                AutomaticRegistrationSelectorModel.PROTOCOL_SHA256,
                AutomaticRegistrationSelectorModel.CANDIDATE_MANIFEST_SHA256,
                AutomaticRegistrationSelectorModel.MODEL_ARTIFACT_SHA256);
    }

    private static AutomaticRegistrationSelector.Result stamp(
            AutomaticRegistrationSelector.Result result, String version, String kind,
            String validation, String featureContract, String protocolHash,
            String candidateHash, String artifactHash) {
        String reason = result.declineReason == null ? "" : result.declineReason;
        String provenance = String.format(Locale.ROOT,
                "selector_model=%s; model_kind=%s; validation=%s; "
                        + "feature_contract=%s; protocol_sha256=%s; "
                        + "candidate_manifest_sha256=%s; model_artifact_sha256=%s; "
                        + "selected_recipe=%s; predicted_gain_px=%.17g; "
                        + "confidence_threshold_px=%.17g; fallback=%s; reason=%s",
                version, kind, validation, featureContract, protocolHash, candidateHash,
                artifactHash,
                result.recipe.id(), result.predictedGain, result.confidenceThreshold,
                result.fallback, reason);
        RelativeIntensityPatternParameters stamped = result.parameters.toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .recipeProvenance(provenance)
                .build();
        return new AutomaticRegistrationSelector.Result(result.evidence, result.recipe,
                result.fallback, result.predictedGain, result.confidenceThreshold,
                result.candidateGains, stamped, result.declineReason);
    }

    /**
     * The neutral base the provisional pass runs on: every pixel, no band, no filter, no mask.
     * Measuring movement on filtered pixels would let a filter decide which filter to test.
     */
    public static RelativeIntensityPatternParameters automaticSelectorPilot(RelativeIntensityPatternParameters parameters) {
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        return parameters.toBuilder()
                .useRecommendation(false)
                .automaticFilterSelection(false)
                .selectionMode(SelectionMode.MANUAL)
                // Evidence for the selector was trained on a neutral translation pass. Rotation
                // belongs only to the resolved production recipe and must not be paid for twice.
                .rotationMode(RotationMode.OFF)
                .rotationEventFrames()
                .incrementalRotation(false)
                .estimationScale(1.0)
                .estimator(ripr.core.PairEstimator.Kind.LOG_RATIO_FIT)
                .norm(RobustNorm.HUBER)
                .reference(Reconciler.Reference.MULTILAG)
                .lags(1, 2, 4, 8, 16)
                .pixelSupport(PairAligner.PixelSupport.ALL)
                .gradientFraction(0.5)
                .floorPercentile(Double.NaN)
                .ceilingPercentile(Double.NaN)
                .removeOffset(false)
                .preprocessing(Preprocessing.NONE)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .pixelSelectionPreprocessing(Preprocessing.NONE)
                .pixelRemovalPercent(25.0)
                .epsilon(1.0)
                .autoMaxShift(false)
                .maxShift(30.0)
                .outlierMads(0.0)
                .maxIterations(25)
                .maxSamples(200_000)
                .minValidFraction(0.10)
                .build();
    }

    /**
     * Resolve the automatic preprocessing/pixel-removal choice without correcting the source image.
     * The returned parameters are ordinary explicit settings and can be reviewed, edited or recorded.
     */
    public static AutomaticFilterSelector.Result resolveAutomaticFilters(
            ImagePlus image, RelativeIntensityPatternParameters parameters) {
        return resolveAutomaticFilters(image, parameters, PairScheduler.Cancellation.NEVER);
    }

    private static AutomaticFilterSelector.Result resolveAutomaticFilters(
            ImagePlus image, RelativeIntensityPatternParameters parameters,
            PairScheduler.Cancellation cancellation) {
        if (image == null) throw new IllegalArgumentException("image is null");
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        RelativeIntensityPatternParameters base = parameters.toBuilder()
                .automaticFilterSelection(false)
                .useRecommendation(false)
                .rotationMode(RotationMode.OFF)
                .rotationEventFrames()
                .incrementalRotation(false)
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
            ImagePlus image, RelativeIntensityPatternParameters parameters,
            PairScheduler.Progress progress,
            PairScheduler.Cancellation cancellation) {
        Registration.Result result = estimateResolvedAtScale(
                image, parameters, progress, cancellation);
        return parameters.estimationScale == 1.0
                ? result : result.scaleTranslations(1.0 / parameters.estimationScale);
    }

    /** The ordinary estimator result expressed in its estimation-image pixel coordinates. */
    private static Registration.Result estimateResolvedAtScale(
            ImagePlus image, RelativeIntensityPatternParameters parameters,
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
        return result;
    }

    /** Prepared pixels, options and optional source-coordinate support for one split pass. */
    private static final class PreparedSplitPass {
        final FrameSource source;
        final Registration.Options options;
        final boolean[][][] support;

        PreparedSplitPass(FrameSource source, Registration.Options options,
                          boolean[][][] support) {
            this.source = source;
            this.options = options;
            this.support = support;
        }
    }

    private static PreparedSplitPass prepareSplitPass(
            ImagePlus image, RelativeIntensityPatternParameters parameters, Registration.Result pilot,
            boolean rotationPass, PairScheduler.Cancellation cancellation) {
        StackFrames nativeSource = StackFrames.of(
                image, parameters.channel, parameters.slice);
        FrameSource prepared = parameters.preprocessing == Preprocessing.NONE
                ? nativeSource : new PreprocessedFrameSource(
                        nativeSource, parameters.preprocessing);
        FrameSource source = parameters.estimationScale == 1.0
                ? prepared : new ScaledFrameSource(prepared, parameters.estimationScale);
        Registration.Options options = parameters.registrationOptions();
        if (parameters.estimationScale < 1.0 && !options.autoMaxShift) {
            options.aligner.maxShift *= parameters.estimationScale;
        }
        if (parameters.pixelSelectionStrategy == PixelSelectionStrategy.NONE) {
            return new PreparedSplitPass(source, options, null);
        }
        FrameSource scoringPrepared = parameters.pixelSelectionPreprocessing == Preprocessing.NONE
                ? nativeSource : new PreprocessedFrameSource(
                        nativeSource, parameters.pixelSelectionPreprocessing);
        FrameSource scoring = parameters.estimationScale == 1.0
                ? scoringPrepared : new ScaledFrameSource(
                        scoringPrepared, parameters.estimationScale);
        if (!rotationPass) {
            FrameSource raw = parameters.estimationScale == 1.0
                    ? nativeSource : new ScaledFrameSource(
                            nativeSource, parameters.estimationScale);
            Registration.Options rawOptions = PixelSelectionEngine.prepareRawRefitOptions(
                    raw, options);
            boolean[][][] support = PixelSelectionEngine.supportForPilot(
                    scoring, raw, rawOptions, parameters.pixelSelectionStrategy,
                    parameters.pixelRemovalPercent, pilot);
            return new PreparedSplitPass(raw, rawOptions, support);
        }
        // A rotation-specific recipe is compositional: filter, intensity band, strong-area support
        // and spatial exclusion all apply to the angle objective being benchmarked. The old
        // translation selector's selected-pixel path remains unchanged above.
        if (options.autoMaxShift) {
            options.aligner.maxShift = Registration.estimateShiftBound(
                    source, options, PairScheduler.Progress.NONE, cancellation).suggestedMaxShift;
            options.autoMaxShift = false;
        }
        boolean[][][] support = PixelSelectionEngine.supportForPilot(
                scoring, source, options, parameters.pixelSelectionStrategy,
                parameters.pixelRemovalPercent, pilot);
        return new PreparedSplitPass(source, options, support);
    }
}
