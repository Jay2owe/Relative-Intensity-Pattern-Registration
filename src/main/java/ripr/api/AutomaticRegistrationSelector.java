/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.AutomaticInformationSelector;
import ripr.core.FrameSource;
import ripr.core.LogPlane;
import ripr.core.PairAligner;
import ripr.core.Reconciler;
import ripr.core.RobustNorm;
import ripr.core.Transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Chooses one complete registration recipe: pairwise estimator, pixel support, intensity band,
 * filter and pixel mask.
 *
 * <p>The older {@link AutomaticFilterSelector} could only add a filter or a mask on top of whatever
 * base model the category recommendation supplied. This selector may also change the base pixel
 * support and the brightness exclusions, which is where most of the remaining accuracy sat, and it
 * may change the estimator itself. It still never invents a combination: every output is either one
 * of the 128 measured candidates or the complete category recommendation.
 *
 * <p><b>The candidate space is three axes, not one set of recipes.</b> 96 candidates are log-ratio
 * recipes over the four settings dimensions — support, band, filter, mask. Two further sets of 16
 * use {@link ripr.core.PairEstimator.Kind#AREA_CORRELATION} and
 * {@link ripr.core.PairEstimator.Kind#AREA_CORRELATION_NEWTON} over band and filter, with
 * support and mask held neutral because neither means anything to a whole-window correlation. The axes are
 * enumerated separately by {@link RegistrationRecipe#sweptCandidates()},
 * {@link RegistrationRecipe#estimatorCandidates()} and
 * {@link RegistrationRecipe#newtonEstimatorCandidates()}, and together by
 * {@link RegistrationRecipe#allCandidates()}.
 *
 * <p><b>So it may now choose a competing registration algorithm, which it previously could not.</b>
 * The legacy frozen model retains Newton-refined area correlation for
 * {@link ImageType#BRIGHTFIELD_DIC} and
 * {@link ImageType#FIDUCIAL_STATIC} and nothing on the other three image types, where the log-ratio
 * fit measured better. Which candidates are retained is a property of the fit, not of this class:
 * read {@link AutomaticRegistrationSelectorModel#candidates()} rather than this sentence.
 *
 * <p>The decision is deliberately conservative. A recipe is proposed only when its predicted gain
 * over the category recommendation clears a threshold frozen on held-out source series. Anything
 * less confident returns the category recommendation untouched.
 */
public final class AutomaticRegistrationSelector {

    /** Schema shared by production measurement, exports, training and parity tests. */
    public static final String FEATURE_CONTRACT_VERSION = "recording_evidence_v2";

    /** Image measurements, then provisional movement, then field, support and declared context. */
    public static final String[] FEATURE_NAMES = featureNames();

    /** Number of columns in the standardised evidence vector. */
    public static final int FEATURE_COUNT = FEATURE_NAMES.length;

    /** Numeric fields before the declared-context one-hot columns. */
    public static final int CONTINUOUS_FEATURE_COUNT =
            AutomaticFilterSelector.IMAGE_FEATURE_NAMES.length
                    + AutomaticFilterSelector.MOTION_FEATURE_NAMES.length + 5;

    /** Frozen distribution-shift bounds from selector_protocol.md. */
    public static final double MAX_ABSOLUTE_Z = 8.0;
    public static final double MAX_RMS_Z = 3.0;

    private AutomaticRegistrationSelector() {
    }

    /** Everything measured about one recording, in the frozen model's declared order. */
    public static final class Evidence {
        public final String contractVersion;
        public final double[] imageFeatures;
        public final double[] motionFeatures;
        /** Median position in the robust intensity range; low means a background-dominated field. */
        public final double sparsityScore;
        /** Aligned log-residual 99th-to-95th percentile ratio; high means locally moving structure. */
        public final double movingTailRatio;
        /** Share of pixels beyond the robust residual threshold after a pilot alignment. */
        public final double outlierFraction;
        /** Share of pixels the GRADIENT rule would admit at the frozen multiplier. */
        public final double gradientAdmittedFraction;
        /** Share of pixels the MUTUAL_NOISE_GRADIENT rule would admit at the frozen multiplier. */
        public final double mutualNoiseAdmittedFraction;
        public final ImageType imageType;
        public final MotionType motionType;
        public final RobustNorm baseNorm;
        public final Reconciler.Reference baseReference;
        /** False means this evidence must fall back and must never be standardized. */
        public final boolean valid;
        /** Stable machine-readable explanation when {@link #valid} is false. */
        public final String validityReason;

        Evidence(double[] imageFeatures, double[] motionFeatures, double sparsityScore,
                 double movingTailRatio, double outlierFraction, double gradientAdmittedFraction,
                 double mutualNoiseAdmittedFraction, ImageType imageType, MotionType motionType,
                 RobustNorm baseNorm, Reconciler.Reference baseReference) {
            this(imageFeatures, motionFeatures, sparsityScore, movingTailRatio, outlierFraction,
                    gradientAdmittedFraction, mutualNoiseAdmittedFraction, imageType, motionType,
                    baseNorm, baseReference, true, "");
        }

        private Evidence(double[] imageFeatures, double[] motionFeatures, double sparsityScore,
                 double movingTailRatio, double outlierFraction, double gradientAdmittedFraction,
                 double mutualNoiseAdmittedFraction, ImageType imageType, MotionType motionType,
                 RobustNorm baseNorm, Reconciler.Reference baseReference, boolean valid,
                 String validityReason) {
            this.contractVersion = FEATURE_CONTRACT_VERSION;
            this.imageFeatures = imageFeatures.clone();
            this.motionFeatures = motionFeatures.clone();
            this.sparsityScore = sparsityScore;
            this.movingTailRatio = movingTailRatio;
            this.outlierFraction = outlierFraction;
            this.gradientAdmittedFraction = gradientAdmittedFraction;
            this.mutualNoiseAdmittedFraction = mutualNoiseAdmittedFraction;
            this.imageType = imageType;
            this.motionType = motionType;
            this.baseNorm = baseNorm;
            this.baseReference = baseReference;
            this.valid = valid;
            this.validityReason = validityReason == null ? "" : validityReason;
        }

        /** The complete raw feature vector in the declared order, before standardisation. */
        public double[] vector() {
            double[] out = new double[FEATURE_COUNT];
            int at = 0;
            for (double value : imageFeatures) out[at++] = value;
            for (double value : motionFeatures) out[at++] = value;
            out[at++] = sparsityScore;
            out[at++] = movingTailRatio;
            out[at++] = outlierFraction;
            out[at++] = gradientAdmittedFraction;
            out[at++] = mutualNoiseAdmittedFraction;
            for (ImageType type : ImageType.values()) out[at++] = imageType == type ? 1 : 0;
            for (MotionType type : MotionType.values()) out[at++] = motionType == type ? 1 : 0;
            for (RobustNorm norm : RobustNorm.values()) out[at++] = baseNorm == norm ? 1 : 0;
            for (Reconciler.Reference reference : Reconciler.Reference.values()) {
                out[at++] = baseReference == reference ? 1 : 0;
            }
            if (at != FEATURE_COUNT) throw new IllegalStateException("feature layout drifted");
            return out;
        }

        /** Evaluate the frozen out-of-distribution rule using training-only centring and scales. */
        public Distribution distribution(double[] mean, double[] scale) {
            if (!valid) return new Distribution(true, validityReason);
            double[] raw = vector();
            if (mean == null || scale == null || mean.length != raw.length
                    || scale.length != raw.length) {
                throw new IllegalArgumentException("distribution statistics do not match evidence");
            }
            double squared = 0;
            int count = 0;
            for (int i = 0; i < CONTINUOUS_FEATURE_COUNT; i++) {
                if (!Double.isFinite(mean[i]) || !Double.isFinite(scale[i]) || scale[i] < 0) {
                    throw new IllegalArgumentException("invalid distribution statistics at " + i);
                }
                if (scale[i] == 0) {
                    if (Double.compare(raw[i], mean[i]) != 0) {
                        return new Distribution(true, "constant training feature changed: "
                                + FEATURE_NAMES[i]);
                    }
                    continue;
                }
                double z = (raw[i] - mean[i]) / scale[i];
                if (Math.abs(z) > MAX_ABSOLUTE_Z) {
                    return new Distribution(true, "feature exceeds 8 SD: " + FEATURE_NAMES[i]);
                }
                squared += z * z;
                count++;
            }
            double rms = count == 0 ? 0 : Math.sqrt(squared / count);
            return rms > MAX_RMS_Z
                    ? new Distribution(true, String.format(Locale.ROOT,
                            "continuous-feature RMS z %.4f exceeds 3", rms))
                    : new Distribution(false, "");
        }
    }

    /** Deterministic distribution-envelope result; no outcome label enters this calculation. */
    public static final class Distribution {
        public final boolean outOfDistribution;
        public final String reason;

        Distribution(boolean outOfDistribution, String reason) {
            this.outOfDistribution = outOfDistribution;
            this.reason = reason == null ? "" : reason;
        }
    }

    /** Why the selector did what it did, in a form a settings report can print verbatim. */
    public static final class Result {
        public final Evidence evidence;
        public final RegistrationRecipe recipe;
        /** True when the selector declined to override and returned the category recommendation. */
        public final boolean fallback;
        /** Predicted improvement over the category recommendation, in pixels. */
        public final double predictedGain;
        public final double confidenceThreshold;
        /** Predicted gain for every retained candidate, in the frozen model's order. */
        public final double[] candidateGains;
        public final RelativeIntensityPatternParameters parameters;
        /** Why no candidate was eligible before evidence was measured, or null. */
        public final String declineReason;
        /** The declared image type this decision was made for; set even when nothing was measured. */
        public final ImageType declaredType;

        Result(Evidence evidence, RegistrationRecipe recipe, boolean fallback, double predictedGain,
               double confidenceThreshold, double[] candidateGains, RelativeIntensityPatternParameters parameters) {
            this(evidence, recipe, fallback, predictedGain, confidenceThreshold, candidateGains,
                    parameters, null);
        }

        Result(Evidence evidence, RegistrationRecipe recipe, boolean fallback, double predictedGain,
               double confidenceThreshold, double[] candidateGains, RelativeIntensityPatternParameters parameters,
               String declineReason) {
            this.declaredType = evidence != null ? evidence.imageType : parameters.imageType;
            this.evidence = evidence;
            this.recipe = recipe;
            this.fallback = fallback;
            this.predictedGain = predictedGain;
            this.confidenceThreshold = confidenceThreshold;
            this.candidateGains = candidateGains.clone();
            this.parameters = parameters;
            this.declineReason = declineReason;
        }

        /** Complete resolved recipe plus the margin that produced it; never only a label. */
        public String explanation() {
            if (evidence == null) {
                if (!fallback) {
                    return "fixed automatic policy: " + recipe.describe()
                            + String.format(Locale.ROOT,
                                    "; predicted gain %.4f px clears %.4f px; no provisional pass was run",
                                    predictedGain, confidenceThreshold);
                }
                return "category recommendation retained (" + recipe.describe()
                        + "); " + (declineReason == null
                                ? "the frozen model holds no measured candidate for image type "
                                    + declaredType
                                : declineReason)
                        + ", so no provisional pass was run";
            }
            if (fallback) {
                return "category recommendation retained (" + recipe.describe()
                        + (declineReason == null
                                ? String.format(Locale.ROOT,
                                        "); best predicted gain %.4f px did not clear %.4f px",
                                        predictedGain, confidenceThreshold)
                                : "); " + declineReason);
            }
            return "automatic full selection: " + recipe.describe()
                    + String.format(Locale.ROOT, "; predicted gain %.4f px clears %.4f px",
                            predictedGain, confidenceThreshold);
        }
    }

    /**
     * Measure one recording. Movement evidence comes from an already-computed neutral provisional
     * registration, so no filter can decide which filter to test by first changing the pixels.
     */
    public static Evidence measure(FrameSource rawSource, Transform[] provisionalCumulative,
                                   RelativeIntensityPatternParameters base) {
        if (rawSource == null || provisionalCumulative == null || base == null) {
            throw new IllegalArgumentException("source, provisional transforms and base are required");
        }
        if (rawSource.count() != provisionalCumulative.length) {
            throw new IllegalArgumentException(
                    "source and provisional transforms have different frame counts");
        }
        String structuralProblem = structuralValidityProblem(rawSource);
        if (structuralProblem != null) return invalidEvidence(base, structuralProblem);
        double[] image = AutomaticFilterSelector.imageFeatures(rawSource);
        double[] motion = AutomaticFilterSelector.motionFeatures(provisionalCumulative);
        AutomaticInformationSelector.Result information =
                AutomaticInformationSelector.select(rawSource, base.epsilon, false);
        double[] admitted = admittedFractions(rawSource, base.epsilon);
        Evidence evidence = new Evidence(image, motion, information.sparseScore,
                information.movingTailRatio,
                information.temporalOutlierFraction, admitted[0], admitted[1],
                base.imageType, base.motionType, base.norm, base.reference);
        double[] vector = evidence.vector();
        for (int i = 0; i < vector.length; i++) {
            if (!Double.isFinite(vector[i])) {
                return new Evidence(image, motion, information.sparseScore,
                        information.movingTailRatio, information.temporalOutlierFraction,
                        admitted[0], admitted[1], base.imageType, base.motionType, base.norm,
                        base.reference, false, "non-finite required feature: " + FEATURE_NAMES[i]);
            }
        }
        return evidence;
    }

    /**
     * Apply the frozen model. The returned parameters are ordinary explicit settings that can be
     * reviewed, edited, recorded to a macro and replayed by hand.
     */
    public static Result select(Evidence evidence, RelativeIntensityPatternParameters base) {
        return select(evidence, base, false);
    }

    /** Apply the frozen model while requiring candidates to be separately validated for rotation. */
    public static Result select(Evidence evidence, RelativeIntensityPatternParameters base,
                                boolean rotationRequested) {
        if (evidence == null || base == null) {
            throw new IllegalArgumentException("evidence and base parameters are required");
        }
        RegistrationRecipe fallbackRecipe = categoryRecipe(base);
        List<RegistrationRecipe> candidates = AutomaticRegistrationSelectorModel.candidates();
        double threshold = AutomaticRegistrationSelectorModel.CONFIDENCE_THRESHOLD;
        if (!evidence.valid) {
            return new Result(evidence, fallbackRecipe, true, 0, threshold, new double[0],
                    fallbackRecipe.applyTo(base), "invalid selector evidence: "
                            + evidence.validityReason);
        }
        if (candidates.isEmpty()) {
            return new Result(evidence, fallbackRecipe, true, 0, threshold, new double[0],
                    fallbackRecipe.applyTo(base), "no validated candidate serves this scope");
        }
        Distribution distribution = evidence.distribution(
                AutomaticRegistrationSelectorModel.FEATURE_MEAN,
                AutomaticRegistrationSelectorModel.FEATURE_SCALE);
        if (distribution.outOfDistribution) {
            return new Result(evidence, fallbackRecipe, true, 0, threshold, new double[0],
                    fallbackRecipe.applyTo(base), "out-of-distribution selector evidence: "
                            + distribution.reason);
        }
        double[] standardised = standardise(evidence.vector());
        double[] gains = AutomaticRegistrationSelectorModel.predictGains(standardised,
                evidence.imageType);
        if (rotationRequested) {
            for (int i = 0; i < gains.length; i++) {
                if (!candidates.get(i).estimator.supportsRotation()
                        || !AutomaticRegistrationSelectorModel.candidateRigidValidated(i)) {
                    gains[i] = Double.NEGATIVE_INFINITY;
                }
            }
        }
        int best = -1;
        for (int i = 0; i < gains.length; i++) {
            if (!Double.isFinite(gains[i])) continue;
            if (best < 0 || gains[i] > gains[best]) best = i;
        }
        double bestGain = best < 0 ? 0 : gains[best];
        if (best < 0 || bestGain < threshold) {
            return new Result(evidence, fallbackRecipe, true, bestGain, threshold, gains,
                    fallbackRecipe.applyTo(base), best < 0
                            ? "no validated candidate serves this scope"
                            : String.format(Locale.ROOT,
                                    "best predicted gain %.4f px did not clear %.4f px",
                                    bestGain, threshold));
        }
        RegistrationRecipe chosen = candidates.get(best);
        if (!chosen.isSwept()) {
            // A recipe that was never measured must never reach a user, whatever the model says.
            return new Result(evidence, fallbackRecipe, true, bestGain, threshold, gains,
                    fallbackRecipe.applyTo(base));
        }
        return new Result(evidence, chosen, false, bestGain, threshold, gains,
                chosen.applyTo(base));
    }

    /**
     * True when the frozen model retains at least one candidate recipe for that declared image type.
     *
     * <p>Where it is false the selector can only ever return the category recommendation, so a
     * caller may skip the provisional pass entirely without changing the answer.
     */
    public static boolean servesImageType(ImageType imageType) {
        return servesImageType(imageType, false);
    }

    /** True only when a retained candidate can serve both the image type and requested transform. */
    public static boolean servesImageType(ImageType imageType, boolean rotationRequested) {
        if (imageType == null) return false;
        List<ImageType> types = AutomaticRegistrationSelectorModel.candidateImageTypes();
        List<RegistrationRecipe> recipes = AutomaticRegistrationSelectorModel.candidates();
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i) != imageType) continue;
            if (!rotationRequested || (recipes.get(i).estimator.supportsRotation()
                    && AutomaticRegistrationSelectorModel.candidateRigidValidated(i))) return true;
        }
        return false;
    }

    /** True only when a recording-dependent fit can change the decision for this scope. */
    public static boolean requiresRecordingEvidence(ImageType imageType,
                                                    boolean rotationRequested) {
        return "linear_gain".equals(AutomaticRegistrationSelectorModel.MODEL_KIND)
                && servesImageType(imageType, rotationRequested);
    }

    /** Resolve a generated fixed policy without paying for a provisional registration. */
    static Result selectWithoutEvidence(RelativeIntensityPatternParameters base, boolean rotationRequested) {
        if (base == null) throw new IllegalArgumentException("base parameters are required");
        RegistrationRecipe fallback = categoryRecipe(base);
        List<RegistrationRecipe> candidates = AutomaticRegistrationSelectorModel.candidates();
        double threshold = AutomaticRegistrationSelectorModel.CONFIDENCE_THRESHOLD;
        double[] gains = AutomaticRegistrationSelectorModel.predictGains(
                new double[FEATURE_COUNT], base.imageType);
        int best = -1;
        for (int i = 0; i < gains.length; i++) {
            RegistrationRecipe recipe = candidates.get(i);
            if (rotationRequested && (!recipe.estimator.supportsRotation()
                    || !AutomaticRegistrationSelectorModel.candidateRigidValidated(i))) continue;
            if (Double.isFinite(gains[i]) && (best < 0 || gains[i] > gains[best])) best = i;
        }
        double gain = best < 0 ? 0 : gains[best];
        if (best < 0 || gain < threshold) {
            return new Result(null, fallback, true, gain, threshold, gains,
                    fallback.applyTo(base), best < 0
                            ? "no validated candidate serves this scope"
                            : String.format(Locale.ROOT,
                                    "best predicted gain %.4f px did not clear %.4f px",
                                    gain, threshold));
        }
        RegistrationRecipe recipe = candidates.get(best);
        return new Result(null, recipe, false, gain, threshold, gains,
                recipe.applyTo(base), "fixed automatic policy");
    }

    /** The category recommendation, returned without measuring anything. */
    static Result declined(RelativeIntensityPatternParameters base) {
        return declined(base, null);
    }

    static Result declined(RelativeIntensityPatternParameters base, String reason) {
        RegistrationRecipe fallback = categoryRecipe(base);
        return new Result(null, fallback, true, 0,
                AutomaticRegistrationSelectorModel.CONFIDENCE_THRESHOLD, new double[0],
                fallback.applyTo(base), reason);
    }

    /** Standardise a raw feature vector with the frozen centring and scaling. */
    public static double[] standardise(double[] raw) {
        double[] mean = AutomaticRegistrationSelectorModel.FEATURE_MEAN;
        double[] scale = AutomaticRegistrationSelectorModel.FEATURE_SCALE;
        if (raw.length != mean.length) {
            throw new IllegalArgumentException("expected " + mean.length + " features, got " + raw.length);
        }
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            if (!Double.isFinite(raw[i])) {
                throw new IllegalArgumentException("non-finite feature " + FEATURE_NAMES[i]);
            }
            out[i] = scale[i] > 0 ? (raw[i] - mean[i]) / scale[i] : 0;
        }
        return out;
    }

    private static Evidence invalidEvidence(RelativeIntensityPatternParameters base, String reason) {
        double[] image = new double[AutomaticFilterSelector.IMAGE_FEATURE_NAMES.length];
        double[] motion = new double[AutomaticFilterSelector.MOTION_FEATURE_NAMES.length];
        Arrays.fill(image, Double.NaN);
        Arrays.fill(motion, Double.NaN);
        return new Evidence(image, motion, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, base.imageType, base.motionType, base.norm,
                base.reference, false, reason);
    }

    private static String structuralValidityProblem(FrameSource source) {
        if (source.count() < 2) return "fewer than two guide frames";
        if (source.width() < 16 || source.height() < 16) return "guide plane smaller than 16x16";
        int[] frames = {0, source.count() / 2, source.count() - 1};
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int frame : frames) {
            float[] pixels = source.plane(frame);
            if (pixels == null || pixels.length != source.width() * source.height()) {
                return "guide plane dimensions do not match source";
            }
            for (float value : pixels) {
                if (!Float.isFinite(value)) continue;
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        return Double.isFinite(minimum) && Double.isFinite(maximum) && maximum > minimum
                ? null : "no finite positive raw-image dynamic range";
    }

    /**
     * The complete category recommendation expressed as a recipe.
     *
     * <p>Only the recipe fields are taken from the recommendation. Writing it back with
     * {@link RegistrationRecipe#applyTo(RelativeIntensityPatternParameters)} therefore leaves the caller's reference
     * strategy, lags and movement bound alone, which is what makes declining an override a true
     * no-op rather than a quiet change of search settings.
     */
    public static RegistrationRecipe categoryRecipe(RelativeIntensityPatternParameters base) {
        return RegistrationRecipe.of(categoryBase(base));
    }

    private static RelativeIntensityPatternParameters categoryBase(RelativeIntensityPatternParameters base) {
        return base.toBuilder()
                .recommendation(base.imageType, base.motionType)
                .useRecommendation(false)
                .automaticFilterSelection(false)
                .build();
    }

    /**
     * Share of pixels each optional support rule would admit on the first frame, at the frozen
     * multiplier. This is the cheapest honest answer to "would restricting support throw away the
     * field?", and it is measured on raw pixels, before any filter is chosen.
     */
    static double[] admittedFractions(FrameSource source, double epsilon) {
        float[] frame = source.plane(0);
        LogPlane plane = LogPlane.of(frame, source.width(), source.height(), epsilon);
        int valid = 0;
        double[] magnitudes = new double[frame.length];
        for (int i = 0; i < frame.length; i++) {
            if (!plane.valid[i]) continue;
            magnitudes[valid++] = plane.gradMagnitude(i);
        }
        if (valid == 0) return new double[]{Double.NaN, Double.NaN};
        double[] sorted = Arrays.copyOf(magnitudes, valid);
        Arrays.sort(sorted);
        double threshold = sorted[valid / 2] * RegistrationRecipe.SWEPT_GRADIENT_MULTIPLIER;
        int gradient = 0;
        for (int i = 0; i < valid; i++) if (magnitudes[i] >= threshold) gradient++;

        double noiseThreshold = RegistrationRecipe.SWEPT_GRADIENT_MULTIPLIER * plane.rawNoiseSigma();
        int mutual = 0;
        for (int i = 0; i < frame.length; i++) {
            if (!plane.valid[i]) continue;
            if (plane.rawGradient(i) >= noiseThreshold) mutual++;
        }
        return new double[]{gradient / (double) valid, mutual / (double) valid};
    }

    private static String[] featureNames() {
        List<String> out = new ArrayList<>();
        out.addAll(Arrays.asList(AutomaticFilterSelector.IMAGE_FEATURE_NAMES));
        out.addAll(Arrays.asList(AutomaticFilterSelector.MOTION_FEATURE_NAMES));
        out.add("sparsity score");
        out.add("moving-tail ratio");
        out.add("temporal outlier fraction");
        out.add("gradient-admitted fraction");
        out.add("mutual-noise-admitted fraction");
        for (ImageType type : ImageType.values()) out.add("declared image type " + type.name());
        for (MotionType type : MotionType.values()) out.add("declared motion type " + type.name());
        for (RobustNorm norm : RobustNorm.values()) out.add("base robust weighting " + norm.name());
        for (Reconciler.Reference reference : Reconciler.Reference.values()) {
            out.add("base reference strategy " + reference.name());
        }
        return out.toArray(new String[0]);
    }

    /** Exposed so a settings audit can confirm the support rules the selector may choose between. */
    public static PairAligner.PixelSupport[] supports() {
        return RegistrationRecipe.SUPPORTS.clone();
    }
}
