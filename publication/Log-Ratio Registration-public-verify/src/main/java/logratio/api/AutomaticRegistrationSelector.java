/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import logratio.core.AutomaticInformationSelector;
import logratio.core.FrameSource;
import logratio.core.LogPlane;
import logratio.core.PairAligner;
import logratio.core.Reconciler;
import logratio.core.RobustNorm;
import logratio.core.Transform;

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
 * of the 112 measured candidates or the complete category recommendation.
 *
 * <p><b>The candidate space is two axes, not one set of recipes.</b> 96 candidates are log-ratio
 * recipes over the four settings dimensions — support, band, filter, mask. The other 16 are
 * {@link logratio.core.PairEstimator.Kind#AREA_CORRELATION} over band and filter, with support and
 * mask held neutral because neither means anything to a whole-window correlation. The two axes are
 * enumerated separately by {@link RegistrationRecipe#sweptCandidates()} and
 * {@link RegistrationRecipe#estimatorCandidates()}, and together by
 * {@link RegistrationRecipe#allCandidates()}.
 *
 * <p><b>So it may now choose a competing registration algorithm, which it previously could not.</b>
 * The frozen model retains area correlation for {@link ImageType#BRIGHTFIELD_DIC} and
 * {@link ImageType#FIDUCIAL_STATIC} and nothing on the other three image types, where the log-ratio
 * fit measured better. Which candidates are retained is a property of the fit, not of this class:
 * read {@link AutomaticRegistrationSelectorModel#candidates()} rather than this sentence.
 *
 * <p>The decision is deliberately conservative. A recipe is proposed only when its predicted gain
 * over the category recommendation clears a threshold frozen on held-out source series. Anything
 * less confident returns the category recommendation untouched.
 */
public final class AutomaticRegistrationSelector {

    /** Image measurements, then provisional movement, then field, support and declared context. */
    public static final String[] FEATURE_NAMES = featureNames();

    /** Number of columns in the standardised evidence vector. */
    public static final int FEATURE_COUNT = FEATURE_NAMES.length;

    private AutomaticRegistrationSelector() {
    }

    /** Everything measured about one recording, in the frozen model's declared order. */
    public static final class Evidence {
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

        Evidence(double[] imageFeatures, double[] motionFeatures, double sparsityScore,
                 double movingTailRatio, double outlierFraction, double gradientAdmittedFraction,
                 double mutualNoiseAdmittedFraction, ImageType imageType, MotionType motionType,
                 RobustNorm baseNorm, Reconciler.Reference baseReference) {
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
        public final LogRatioParameters parameters;
        /** The declared image type this decision was made for; set even when nothing was measured. */
        public final ImageType declaredType;

        Result(Evidence evidence, RegistrationRecipe recipe, boolean fallback, double predictedGain,
               double confidenceThreshold, double[] candidateGains, LogRatioParameters parameters) {
            this.declaredType = evidence != null ? evidence.imageType : parameters.imageType;
            this.evidence = evidence;
            this.recipe = recipe;
            this.fallback = fallback;
            this.predictedGain = predictedGain;
            this.confidenceThreshold = confidenceThreshold;
            this.candidateGains = candidateGains.clone();
            this.parameters = parameters;
        }

        /** Complete resolved recipe plus the margin that produced it; never only a label. */
        public String explanation() {
            if (evidence == null) {
                return "category recommendation retained (" + recipe.describe()
                        + "); the frozen model holds no measured candidate for image type "
                        + declaredType + ", so no provisional pass was run";
            }
            if (fallback) {
                return "category recommendation retained (" + recipe.describe()
                        + String.format(Locale.ROOT,
                                "); best predicted gain %.4f px did not clear %.4f px",
                                predictedGain, confidenceThreshold);
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
                                   LogRatioParameters base) {
        if (rawSource == null || provisionalCumulative == null || base == null) {
            throw new IllegalArgumentException("source, provisional transforms and base are required");
        }
        if (rawSource.count() != provisionalCumulative.length) {
            throw new IllegalArgumentException(
                    "source and provisional transforms have different frame counts");
        }
        if (rawSource.count() < 1) throw new IllegalArgumentException("source has no frames");
        double[] image = AutomaticFilterSelector.imageFeatures(rawSource);
        double[] motion = AutomaticFilterSelector.motionFeatures(provisionalCumulative);
        AutomaticInformationSelector.Result information =
                AutomaticInformationSelector.select(rawSource, base.epsilon, false);
        double[] admitted = admittedFractions(rawSource, base.epsilon);
        return new Evidence(image, motion, information.sparseScore, information.movingTailRatio,
                information.temporalOutlierFraction, admitted[0], admitted[1],
                base.imageType, base.motionType, base.norm, base.reference);
    }

    /**
     * Apply the frozen model. The returned parameters are ordinary explicit settings that can be
     * reviewed, edited, recorded to a macro and replayed by hand.
     */
    public static Result select(Evidence evidence, LogRatioParameters base) {
        if (evidence == null || base == null) {
            throw new IllegalArgumentException("evidence and base parameters are required");
        }
        RegistrationRecipe fallbackRecipe = categoryRecipe(base);
        List<RegistrationRecipe> candidates = AutomaticRegistrationSelectorModel.candidates();
        double threshold = AutomaticRegistrationSelectorModel.CONFIDENCE_THRESHOLD;
        if (candidates.isEmpty()) {
            return new Result(evidence, fallbackRecipe, true, 0, threshold, new double[0],
                    fallbackRecipe.applyTo(base));
        }
        double[] standardised = standardise(evidence.vector());
        double[] gains = AutomaticRegistrationSelectorModel.predictGains(standardised,
                evidence.imageType);
        int best = -1;
        for (int i = 0; i < gains.length; i++) {
            if (!Double.isFinite(gains[i])) continue;
            if (best < 0 || gains[i] > gains[best]) best = i;
        }
        double bestGain = best < 0 ? 0 : gains[best];
        if (best < 0 || bestGain < threshold) {
            return new Result(evidence, fallbackRecipe, true, bestGain, threshold, gains,
                    fallbackRecipe.applyTo(base));
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
        if (imageType == null) return false;
        for (ImageType candidate : AutomaticRegistrationSelectorModel.candidateImageTypes()) {
            if (candidate == imageType) return true;
        }
        return false;
    }

    /** The category recommendation, returned without measuring anything. */
    static Result declined(LogRatioParameters base) {
        RegistrationRecipe fallback = categoryRecipe(base);
        return new Result(null, fallback, true, 0,
                AutomaticRegistrationSelectorModel.CONFIDENCE_THRESHOLD, new double[0],
                fallback.applyTo(base));
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
            double value = Double.isFinite(raw[i]) ? raw[i] : mean[i];
            out[i] = scale[i] > 0 ? (value - mean[i]) / scale[i] : 0;
        }
        return out;
    }

    /**
     * The complete category recommendation expressed as a recipe.
     *
     * <p>Only the recipe fields are taken from the recommendation. Writing it back with
     * {@link RegistrationRecipe#applyTo(LogRatioParameters)} therefore leaves the caller's reference
     * strategy, lags and movement bound alone, which is what makes declining an override a true
     * no-op rather than a quiet change of search settings.
     */
    public static RegistrationRecipe categoryRecipe(LogRatioParameters base) {
        return RegistrationRecipe.of(categoryBase(base));
    }

    private static LogRatioParameters categoryBase(LogRatioParameters base) {
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
