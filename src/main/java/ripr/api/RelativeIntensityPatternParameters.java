/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.StackFrames;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.RotationMode;
import ripr.core.RobustNorm;
import ripr.core.Warper;

/** Complete, effectively immutable input bundle shared by the menu, macros and Java API. */
public final class RelativeIntensityPatternParameters {
    public final ImageType imageType;
    public final MotionType motionType;
    /** Exactly one of recommended, automatic full selection or manual decided these values. */
    public final SelectionMode selectionMode;
    /** Where the values came from, once resolved; empty for a plain manual bundle. */
    public final String recipeProvenance;
    public final boolean useRecommendation;
    /** Apply the installed fixed policy for the declared image and motion types. */
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
    /** Experimental A001 tuning switch; ignored unless rigid multi-lag fitting is active. */
    public final boolean lagAwareWarmStarts;
    /** Test rotation only after the selected estimator and preprocessing have fitted translation. */
    public final boolean incrementalRotation;
    /** Minimum full-frame residual improvement required to retain a rotation proposal. */
    public final double minimumRotationResidualGain;
    /** Frozen rotation-selector recipe; empty keeps the translation recipe for manual/legacy runs. */
    public final String rotationRecipeId;
    /** Compare translation-seeded and global angle proposals, then refit translation at the winner. */
    public final boolean globalRotationProposal;
    /** Provisional 90th-percentile step used by the Phase rotation selector, or NaN when unused. */
    public final double rotationSelectorStep90;
    public final double maxShift;
    /** Authoritative three-state rotation model. */
    public final RotationMode rotationMode;
    /** One-based first frames after remount events. Defensively copied on input and output. */
    private final int[] rotationEventFrames;
    /** Maximum frames drawn from each side of a known event. */
    public final int rotationEventWindow;
    /** Detect and correct in-plane rotation. Compatibility view; true for either rotation mode. */
    public final boolean fitRotation;
    /** Per-compared-frame angular bound in degrees. Ignored when rotation fitting is disabled. */
    public final double maxRotationDegrees;
    public final double outlierMads;
    /** Minimum pair residual reduction that protects a measured outlier step; NaN disables it. */
    public final double outlierProtectionResidualGain;
    public final int maxIterations;
    public final int maxSamples;
    public final double minValidFraction;
    public final int threads;
    public final Warper.Interpolation interpolation;
    public final boolean crop;

    private RelativeIntensityPatternParameters(Builder b) {
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
        lagAwareWarmStarts = b.lagAwareWarmStarts;
        minimumRotationResidualGain = b.minimumRotationResidualGain;
        rotationRecipeId = b.rotationRecipeId == null ? "" : b.rotationRecipeId;
        globalRotationProposal = b.globalRotationProposal;
        rotationSelectorStep90 = b.rotationSelectorStep90;
        maxShift = b.maxShift;
        rotationMode = b.resolveRotationMode();
        rotationEventFrames = b.rotationEventFrames.clone();
        rotationEventWindow = b.rotationEventWindow;
        fitRotation = rotationMode != RotationMode.OFF;
        // Preserve the recorded legacy flag in OFF/CONTINUOUS modes. It is inert while rotation is
        // off, but older API and macro round trips expose it. Known events is the only mode where
        // allowing an incremental angular policy would contradict the hard trajectory.
        incrementalRotation = rotationMode != RotationMode.KNOWN_EVENTS && b.incrementalRotation;
        maxRotationDegrees = b.maxRotationDegrees;
        outlierMads = b.outlierMads;
        outlierProtectionResidualGain = b.outlierProtectionResidualGain;
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

    /** One-based first-post-remount frames. The returned array may be modified safely. */
    public int[] rotationEventFrames() { return rotationEventFrames.clone(); }

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
        options.lagAwareWarmStarts = lagAwareWarmStarts;
        options.incrementalRotation = incrementalRotation;
        options.minimumRotationResidualGain = minimumRotationResidualGain;
        options.outlierMads = outlierMads;
        options.outlierProtectionResidualGain = outlierProtectionResidualGain;
        options.threads = threads;
        options.estimator = estimator;
        options.aligner.norm = norm;
        options.aligner.support = pixelSupport;
        options.aligner.gradientFraction = gradientFraction;
        options.aligner.maxShift = maxShift;
        options.rotationMode = rotationMode;
        options.rotationEventFrames = zeroBasedRotationEventFrames();
        options.rotationEventWindow = rotationEventWindow;
        options.aligner.fitRotation = rotationMode == RotationMode.CONTINUOUS;
        options.aligner.maxRotation = Math.toRadians(maxRotationDegrees);
        options.nearestNeighbourRotation = rotationMode != RotationMode.OFF
                && interpolation == Warper.Interpolation.NONE;
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
        if (rotationMode == null) throw new IllegalArgumentException("rotation mode is required");
        if (rotationEventWindow < 1) {
            throw new IllegalArgumentException("rotation event window must be at least 1");
        }
        int previous = 1;
        for (int event : rotationEventFrames) {
            if (event < 2) {
                throw new IllegalArgumentException("rotation event frame " + event
                        + " must be at least 2 (the first frame after a remount)");
            }
            if (event <= previous) {
                throw new IllegalArgumentException("rotation event frame " + event
                        + " must be unique and strictly increasing");
            }
            previous = event;
        }
        if (rotationMode == RotationMode.KNOWN_EVENTS && rotationEventFrames.length == 0) {
            throw new IllegalArgumentException(
                    "known-events rotation requires at least one rotation event frame");
        }
        if (rotationMode != RotationMode.KNOWN_EVENTS && rotationEventFrames.length != 0) {
            throw new IllegalArgumentException("rotation event frames require rotation mode known_events");
        }
        if (fitRotation && (!(maxRotationDegrees > 0) || !Double.isFinite(maxRotationDegrees))) {
            throw new IllegalArgumentException(
                    "maximum rotation must be a finite number greater than zero");
        }
        if (!Double.isFinite(minimumRotationResidualGain)
                || minimumRotationResidualGain < 0) {
            throw new IllegalArgumentException(
                    "minimum rotation residual gain must be finite and at least zero");
        }
        if (!Double.isNaN(outlierProtectionResidualGain)
                && (!Double.isFinite(outlierProtectionResidualGain)
                    || outlierProtectionResidualGain < 0
                    || outlierProtectionResidualGain > 1)) {
            throw new IllegalArgumentException(
                    "outlier protection residual gain must be in [0, 1], or disabled");
        }
        if (!rotationRecipeId.isEmpty() && rotationMode != RotationMode.CONTINUOUS) {
            throw new IllegalArgumentException(
                    "a rotation-specific recipe requires continuous rotation fitting");
        }
        if (globalRotationProposal && rotationRecipeId.isEmpty()) {
            throw new IllegalArgumentException(
                    "a global rotation proposal requires a rotation-specific recipe");
        }
        if (!Double.isNaN(rotationSelectorStep90)
                && (!Double.isFinite(rotationSelectorStep90) || rotationSelectorStep90 < 0)) {
            throw new IllegalArgumentException(
                    "rotation selector step evidence must be finite and at least zero, or NaN");
        }
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

    /** The single public-to-core indexing conversion for event frames. */
    private int[] zeroBasedRotationEventFrames() {
        int[] core = new int[rotationEventFrames.length];
        for (int i = 0; i < core.length; i++) core[i] = rotationEventFrames[i] - 1;
        return core;
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
        // Accepted by R01: 0.483x runtime over 12 rigid trajectories with a slightly better worst
        // error, while fitRotation=false bypasses the path exactly.
        private boolean lagAwareWarmStarts = true;
        // A20-A24: fitted after the selected translation recipe, with exact fallback on decline.
        private boolean incrementalRotation = true;
        private double minimumRotationResidualGain = 0.005;
        private String rotationRecipeId = "";
        private boolean globalRotationProposal;
        private double rotationSelectorStep90 = Double.NaN;
        private double maxShift = 30.0;
        private RotationMode rotationMode = RotationMode.OFF;
        private int[] rotationEventFrames = new int[0];
        private int rotationEventWindow = 3;
        private boolean fitRotation;
        private boolean rotationModeExplicit;
        private boolean fitRotationExplicit;
        // Matches the core bound and comfortably covers normal stage/camera shake without making
        // the coarse angular search unnecessarily expensive.
        private double maxRotationDegrees = 10.0;
        private double outlierMads = 6.0;
        private double outlierProtectionResidualGain = Double.NaN;
        private int maxIterations = 25;
        private int maxSamples = 200_000;
        private double minValidFraction = 0.10;
        private int threads;
        private Warper.Interpolation interpolation = Warper.Interpolation.NONE;
        private boolean crop = true;

        public Builder() { }

        private Builder(RelativeIntensityPatternParameters p) {
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
            lagAwareWarmStarts = p.lagAwareWarmStarts;
            incrementalRotation = p.incrementalRotation;
            minimumRotationResidualGain = p.minimumRotationResidualGain;
            rotationRecipeId = p.rotationRecipeId;
            globalRotationProposal = p.globalRotationProposal;
            rotationSelectorStep90 = p.rotationSelectorStep90;
            maxShift = p.maxShift;
            rotationMode = p.rotationMode;
            rotationEventFrames = p.rotationEventFrames();
            rotationEventWindow = p.rotationEventWindow;
            fitRotation = p.fitRotation;
            maxRotationDegrees = p.maxRotationDegrees;
            outlierMads = p.outlierMads;
            outlierProtectionResidualGain = p.outlierProtectionResidualGain;
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
        public Builder lagAwareWarmStarts(boolean v) { lagAwareWarmStarts = v; return this; }
        public Builder incrementalRotation(boolean v) { incrementalRotation = v; return this; }
        public Builder minimumRotationResidualGain(double v) {
            minimumRotationResidualGain = v;
            return this;
        }
        public Builder rotationRecipeId(String v) {
            rotationRecipeId = v == null ? "" : v;
            return this;
        }
        public Builder globalRotationProposal(boolean v) {
            globalRotationProposal = v;
            return this;
        }
        public Builder rotationSelectorStep90(double v) {
            rotationSelectorStep90 = v;
            return this;
        }
        public Builder maxShift(double v) { maxShift = v; return this; }
        /** Legacy control. True resolves to continuous rotation unless an explicit mode contradicts it. */
        public Builder fitRotation(boolean v) {
            fitRotation = v;
            fitRotationExplicit = true;
            return this;
        }
        public Builder rotationMode(RotationMode v) {
            rotationMode = v;
            rotationModeExplicit = true;
            if (v != RotationMode.CONTINUOUS) {
                rotationRecipeId = "";
                globalRotationProposal = false;
                rotationSelectorStep90 = Double.NaN;
            }
            return this;
        }
        public Builder rotationEventFrames(int... v) {
            rotationEventFrames = v == null ? new int[0] : v.clone();
            return this;
        }
        public Builder rotationEventWindow(int v) { rotationEventWindow = v; return this; }
        public Builder maxRotationDegrees(double v) { maxRotationDegrees = v; return this; }
        public Builder outlierMads(double v) { outlierMads = v; return this; }
        public Builder outlierProtectionResidualGain(double v) {
            outlierProtectionResidualGain = v;
            return this;
        }
        public Builder maxIterations(int v) { maxIterations = v; return this; }
        public Builder maxSamples(int v) { maxSamples = v; return this; }
        public Builder minValidFraction(double v) { minValidFraction = v; return this; }
        public Builder threads(int v) { threads = v; return this; }
        public Builder interpolation(Warper.Interpolation v) { interpolation = v; return this; }
        public Builder crop(boolean v) { crop = v; return this; }

        RotationMode resolveRotationMode() {
            RotationMode explicit = rotationMode == null ? RotationMode.OFF : rotationMode;
            RotationMode legacy = fitRotation ? RotationMode.CONTINUOUS : RotationMode.OFF;
            if (rotationModeExplicit && fitRotationExplicit && explicit != legacy) {
                throw new IllegalArgumentException("rotation mode " + explicit.macroValue()
                        + " contradicts fitRotation=" + fitRotation + "; keep only one control");
            }
            if (rotationModeExplicit) return explicit;
            if (fitRotationExplicit) return legacy;
            return explicit;
        }

        public Builder recommendation(ImageType image, MotionType motion) {
            imageType = image;
            motionType = motion;
            useRecommendation = true;
            selectionMode = null;
            RelativeIntensityPatternPreset preset = RelativeIntensityPatternRecommendations.forTypes(image, motion);
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
            // A discontinuous-motion declaration means large steps are expected evidence, not
            // corrupt observations. Keep repair of unsupported frames, but do not smooth genuine
            // remount/stage jumps merely because they differ from the quiet parts of the recording.
            outlierMads = motion == MotionType.INTERMITTENT_JUMPS ? 0.0 : 6.0;
            outlierProtectionResidualGain = Double.NaN;
            maxIterations = preset.maxIterations();
            maxSamples = preset.maxSamples();
            return this;
        }

        public RelativeIntensityPatternParameters build() { return new RelativeIntensityPatternParameters(this); }
    }
}
