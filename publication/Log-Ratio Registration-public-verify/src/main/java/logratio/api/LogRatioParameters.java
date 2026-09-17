/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import logratio.StackFrames;
import logratio.core.PairAligner;
import logratio.core.PairEstimator;
import logratio.core.Reconciler;
import logratio.core.Registration;
import logratio.core.RobustNorm;
import logratio.core.Warper;

/** Complete, effectively immutable input bundle shared by the menu, macros and Java API. */
public final class LogRatioParameters {
    public final ImageType imageType;
    public final MotionType motionType;
    /** Exactly one of recommended, automatic full selection or manual decided these values. */
    public final SelectionMode selectionMode;
    /** Where the values came from, once resolved; empty for a plain manual bundle. */
    public final String recipeProvenance;
    public final boolean useRecommendation;
    /** Inspect the recording and resolve one validated preprocessing/pixel-removal combination. */
    public final boolean automaticFilterSelection;
    /** Fraction of native width and height used to estimate movement; output remains native size. */
    public final double estimationScale;
    /** Optional preparation of the estimation channel; the corrected output uses untouched pixels. */
    public final Preprocessing preprocessing;
    /** Optional second-pass rule; masks pixels but fits original, unfiltered intensities. */
    public final PixelSelectionStrategy pixelSelectionStrategy;
    /** Filter used only to decide the second-pass mask. */
    public final Preprocessing pixelSelectionPreprocessing;
    /** Percentage of eligible pixels deliberately removed by the second pass. */
    public final double pixelRemovalPercent;
    public final int channel;
    public final int slice;
    public final Reconciler.Reference reference;
    public final int referenceFrame;
    public final int[] lags;
    public final int templateWindow;
    public final RobustNorm norm;
    /**
     * Which estimator turns a frame pair into a movement.
     *
     * <p>A separate axis from every other setting here, and the only one that decides <em>what is
     * minimised</em> rather than how. {@code LOG_RATIO_FIT} is the default and the plugin's own
     * method. The pixel support, the norm and the gradient fraction below are settings of that fit
     * and do nothing under {@code AREA_CORRELATION}, which correlates whole windows.
     */
    public final PairEstimator.Kind estimator;
    public final PairAligner.PixelSupport pixelSupport;
    public final double gradientFraction;
    public final double epsilon;
    public final double floorPercentile;
    public final double ceilingPercentile;
    public final boolean removeOffset;
    public final double offsetPercentile;
    public final boolean autoMaxShift;
    public final double maxShift;
    public final double outlierMads;
    public final int maxIterations;
    public final int maxSamples;
    public final double minValidFraction;
    public final int threads;
    public final Warper.Interpolation interpolation;
    public final boolean crop;

    private LogRatioParameters(Builder b) {
        imageType = b.imageType;
        motionType = b.motionType;
        // One mode is authoritative. Callers may set either the mode or the two legacy flags; the
        // builder reconciles them here so no bundle can claim recommended and automatic at once.
        selectionMode = b.resolveSelectionMode();
        recipeProvenance = b.recipeProvenance == null ? "" : b.recipeProvenance;
        useRecommendation = selectionMode == SelectionMode.RECOMMENDED;
        automaticFilterSelection = selectionMode == SelectionMode.AUTOMATIC;
        estimationScale = b.estimationScale;
        preprocessing = b.preprocessing;
        pixelSelectionStrategy = b.pixelSelectionStrategy;
        pixelSelectionPreprocessing = b.pixelSelectionPreprocessing;
        pixelRemovalPercent = b.pixelRemovalPercent;
        channel = b.channel;
        slice = b.slice;
        reference = b.reference;
        referenceFrame = b.referenceFrame;
        lags = b.lags.clone();
        templateWindow = b.templateWindow;
        norm = b.norm;
        estimator = b.estimator;
        pixelSupport = b.pixelSupport;
        gradientFraction = b.gradientFraction;
        epsilon = b.epsilon;
        floorPercentile = b.floorPercentile;
        ceilingPercentile = b.ceilingPercentile;
        removeOffset = b.removeOffset;
        offsetPercentile = b.offsetPercentile;
        autoMaxShift = b.autoMaxShift;
        maxShift = b.maxShift;
        outlierMads = b.outlierMads;
        maxIterations = b.maxIterations;
        maxSamples = b.maxSamples;
        minValidFraction = b.minValidFraction;
        threads = b.threads;
        interpolation = b.interpolation;
        crop = b.crop;
        validate();
    }

    public static Builder builder() { return new Builder(); }

    /** Starts an editable builder with every current value preserved. */
    public Builder toBuilder() { return new Builder(this); }

    public Registration.Options registrationOptions() {
        Registration.Options options = new Registration.Options();
        options.reference = reference;
        options.referenceFrame = referenceFrame - 1;
        options.lags = lags.clone();
        options.templateWindow = templateWindow;
        options.epsilon = epsilon;
        options.intensityFloorPercentile = floorPercentile;
        options.saturationPercentile = ceilingPercentile;
        options.removeOffset = removeOffset;
        options.offsetPercentile = offsetPercentile;
        options.autoMaxShift = autoMaxShift;
        options.outlierMads = outlierMads;
        options.threads = threads;
        options.estimator = estimator;
        options.aligner.norm = norm;
        options.aligner.support = pixelSupport;
        options.aligner.gradientFraction = gradientFraction;
        options.aligner.maxShift = maxShift;
        options.aligner.maxIterations = maxIterations;
        options.aligner.maxSamples = maxSamples;
        options.aligner.minValidFraction = minValidFraction;
        options.aligner.profileGain = true;
        return options;
    }

    private void validate() {
        if (imageType == null || motionType == null) throw new IllegalArgumentException("image and motion types are required");
        if (preprocessing == null || pixelSelectionPreprocessing == null) {
            throw new IllegalArgumentException("preprocessing choices are required");
        }
        if (pixelSelectionStrategy == null) {
            throw new IllegalArgumentException("pixel selection is required");
        }
        if (!(pixelRemovalPercent > 0 && pixelRemovalPercent < 100)) {
            throw new IllegalArgumentException("pixel removal must be in (0, 100)");
        }
        if (!(estimationScale > 0 && estimationScale <= 1)) {
            throw new IllegalArgumentException("estimation scale must be in (0, 1]");
        }
        if (channel < 1) throw new IllegalArgumentException("channel must be at least 1");
        if (slice < StackFrames.PROJECT_Z) throw new IllegalArgumentException("slice must be 0 (project Z) or a one-based slice");
        if (reference == null || norm == null || pixelSupport == null || interpolation == null) {
            throw new IllegalArgumentException("reference, norm, pixel support and interpolation are required");
        }
        if (estimator == null) throw new IllegalArgumentException("a pairwise estimator is required");
        if (estimator != PairEstimator.Kind.LOG_RATIO_FIT
                && pixelSelectionStrategy != PixelSelectionStrategy.NONE) {
            throw new IllegalArgumentException("pixel removal is a second pass of the log-ratio fit; "
                    + estimator.id() + " has no per-pixel support to mask");
        }
        if (estimator != PairEstimator.Kind.LOG_RATIO_FIT && automaticFilterSelection) {
            throw new IllegalArgumentException("automatic filter selection chooses settings for the "
                    + "log-ratio fit; it cannot be combined with " + estimator.id());
        }
        if (referenceFrame < 1) throw new IllegalArgumentException("reference frame must be at least 1");
        if (lags == null || lags.length == 0) throw new IllegalArgumentException("at least one lag is required");
        if (reference == Reconciler.Reference.MULTILAG && !contains(lags, 1)) {
            throw new IllegalArgumentException("multi-lag registration requires lag 1");
        }
        if (pixelSelectionStrategy != PixelSelectionStrategy.NONE
                && reference == Reconciler.Reference.ROLLING) {
            throw new IllegalArgumentException(
                    "pixel removal cannot be combined with a rolling reference template");
        }
        if (automaticFilterSelection && reference == Reconciler.Reference.ROLLING) {
            throw new IllegalArgumentException(
                    "automatic filter selection cannot use a rolling reference template");
        }
        if (!(epsilon > 0)) throw new IllegalArgumentException("epsilon must be greater than zero");
        percentileOrDisabled(floorPercentile, "floor percentile");
        percentileOrDisabled(ceilingPercentile, "ceiling percentile");
        if (!Double.isNaN(floorPercentile) && !Double.isNaN(ceilingPercentile)
                && floorPercentile >= ceilingPercentile) {
            throw new IllegalArgumentException("floor percentile must be below ceiling percentile");
        }
        if (!(maxShift > 0)) throw new IllegalArgumentException("maximum shift must be greater than zero");
        if (maxIterations < 1) throw new IllegalArgumentException("maximum iterations must be at least 1");
        if (maxSamples < 1) throw new IllegalArgumentException("maximum sampled pixels must be at least 1");
        if (!(minValidFraction > 0 && minValidFraction <= 1)) {
            throw new IllegalArgumentException("minimum valid fraction must be in (0, 1]");
        }
    }

    private static void percentileOrDisabled(double value, String name) {
        if (!Double.isNaN(value) && !(value > 0 && value < 100)) {
            throw new IllegalArgumentException(name + " must be in (0, 100), or disabled");
        }
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    public static final class Builder {
        private ImageType imageType = ImageType.PHASE_CONTRAST;
        private MotionType motionType = MotionType.SUBPIXEL_RANDOM_WALK;
        private SelectionMode selectionMode;
        private String recipeProvenance = "";
        private boolean useRecommendation = true;
        private boolean automaticFilterSelection;
        private double estimationScale = 1.0;
        private Preprocessing preprocessing = Preprocessing.NONE;
        private PixelSelectionStrategy pixelSelectionStrategy = PixelSelectionStrategy.NONE;
        private Preprocessing pixelSelectionPreprocessing = Preprocessing.NONE;
        private double pixelRemovalPercent = 25.0;
        private int channel = 1;
        private int slice = StackFrames.PROJECT_Z;
        private Reconciler.Reference reference = Reconciler.Reference.MULTILAG;
        private int referenceFrame = 1;
        private int[] lags = {1, 2, 4, 8, 16};
        private int templateWindow = 5;
        private RobustNorm norm = RobustNorm.HUBER;
        private PairEstimator.Kind estimator = PairEstimator.Kind.LOG_RATIO_FIT;
        private PairAligner.PixelSupport pixelSupport = PairAligner.PixelSupport.ALL;
        private double gradientFraction = 0.5;
        private double epsilon = 1.0;
        private double floorPercentile = Double.NaN;
        private double ceilingPercentile = Double.NaN;
        private boolean removeOffset;
        private double offsetPercentile = 1.0;
        private boolean autoMaxShift = true;
        private double maxShift = 30.0;
        private double outlierMads = 6.0;
        private int maxIterations = 25;
        private int maxSamples = 200_000;
        private double minValidFraction = 0.10;
        private int threads;
        private Warper.Interpolation interpolation = Warper.Interpolation.NONE;
        private boolean crop = true;

        public Builder() { }

        private Builder(LogRatioParameters p) {
            imageType = p.imageType;
            motionType = p.motionType;
            selectionMode = p.selectionMode;
            recipeProvenance = p.recipeProvenance;
            useRecommendation = p.useRecommendation;
            automaticFilterSelection = p.automaticFilterSelection;
            estimationScale = p.estimationScale;
            preprocessing = p.preprocessing;
            pixelSelectionStrategy = p.pixelSelectionStrategy;
            pixelSelectionPreprocessing = p.pixelSelectionPreprocessing;
            pixelRemovalPercent = p.pixelRemovalPercent;
            channel = p.channel;
            slice = p.slice;
            reference = p.reference;
            referenceFrame = p.referenceFrame;
            lags = p.lags.clone();
            templateWindow = p.templateWindow;
            norm = p.norm;
            estimator = p.estimator;
            pixelSupport = p.pixelSupport;
            gradientFraction = p.gradientFraction;
            epsilon = p.epsilon;
            floorPercentile = p.floorPercentile;
            ceilingPercentile = p.ceilingPercentile;
            removeOffset = p.removeOffset;
            offsetPercentile = p.offsetPercentile;
            autoMaxShift = p.autoMaxShift;
            maxShift = p.maxShift;
            outlierMads = p.outlierMads;
            maxIterations = p.maxIterations;
            maxSamples = p.maxSamples;
            minValidFraction = p.minValidFraction;
            threads = p.threads;
            interpolation = p.interpolation;
            crop = p.crop;
        }

        public Builder imageType(ImageType v) { imageType = v; return this; }
        public Builder motionType(MotionType v) { motionType = v; return this; }
        /** Set the mode directly. Call this after any legacy flag, which clears it. */
        public Builder selectionMode(SelectionMode v) { selectionMode = v; return this; }
        public Builder recipeProvenance(String v) { recipeProvenance = v == null ? "" : v; return this; }
        public Builder useRecommendation(boolean v) { useRecommendation = v; selectionMode = null; return this; }
        public Builder automaticFilterSelection(boolean v) { automaticFilterSelection = v; selectionMode = null; return this; }

        SelectionMode resolveSelectionMode() {
            if (selectionMode != null) return selectionMode;
            if (automaticFilterSelection) return SelectionMode.AUTOMATIC;
            if (useRecommendation) return SelectionMode.RECOMMENDED;
            return SelectionMode.MANUAL;
        }
        public Builder estimationScale(double v) { estimationScale = v; return this; }
        public Builder preprocessing(Preprocessing v) { preprocessing = v; return this; }
        public Builder pixelSelectionStrategy(PixelSelectionStrategy v) { pixelSelectionStrategy = v; return this; }
        public Builder pixelSelectionPreprocessing(Preprocessing v) { pixelSelectionPreprocessing = v; return this; }
        public Builder pixelRemovalPercent(double v) { pixelRemovalPercent = v; return this; }
        public Builder channel(int v) { channel = v; return this; }
        public Builder slice(int v) { slice = v; return this; }
        public Builder reference(Reconciler.Reference v) { reference = v; return this; }
        public Builder referenceFrame(int v) { referenceFrame = v; return this; }
        public Builder lags(int... v) { lags = v == null ? null : v.clone(); return this; }
        public Builder templateWindow(int v) { templateWindow = v; return this; }
        public Builder norm(RobustNorm v) { norm = v; return this; }
        public Builder estimator(PairEstimator.Kind v) { estimator = v; return this; }
        public Builder pixelSupport(PairAligner.PixelSupport v) { pixelSupport = v; return this; }
        public Builder gradientFraction(double v) { gradientFraction = v; return this; }
        public Builder epsilon(double v) { epsilon = v; return this; }
        public Builder floorPercentile(double v) { floorPercentile = v; return this; }
        public Builder ceilingPercentile(double v) { ceilingPercentile = v; return this; }
        public Builder removeOffset(boolean v) { removeOffset = v; return this; }
        public Builder offsetPercentile(double v) { offsetPercentile = v; return this; }
        public Builder autoMaxShift(boolean v) { autoMaxShift = v; return this; }
        public Builder maxShift(double v) { maxShift = v; return this; }
        public Builder outlierMads(double v) { outlierMads = v; return this; }
        public Builder maxIterations(int v) { maxIterations = v; return this; }
        public Builder maxSamples(int v) { maxSamples = v; return this; }
        public Builder minValidFraction(double v) { minValidFraction = v; return this; }
        public Builder threads(int v) { threads = v; return this; }
        public Builder interpolation(Warper.Interpolation v) { interpolation = v; return this; }
        public Builder crop(boolean v) { crop = v; return this; }

        public Builder recommendation(ImageType image, MotionType motion) {
            imageType = image;
            motionType = motion;
            useRecommendation = true;
            selectionMode = null;
            LogRatioPreset preset = LogRatioRecommendations.forTypes(image, motion);
            preprocessing = preset.preprocessing();
            pixelSelectionStrategy = preset.pixelSelectionStrategy();
            pixelSelectionPreprocessing = preset.pixelSelectionPreprocessing();
            pixelRemovalPercent = preset.pixelRemovalPercent();
            norm = preset.norm();
            pixelSupport = preset.support();
            gradientFraction = preset.gradientFraction();
            floorPercentile = preset.floorPercentile();
            ceilingPercentile = preset.ceilingPercentile();
            reference = Reconciler.Reference.MULTILAG;
            autoMaxShift = true;
            maxIterations = preset.maxIterations();
            maxSamples = preset.maxSamples();
            return this;
        }

        public LogRatioParameters build() { return new LogRatioParameters(this); }
    }
}
