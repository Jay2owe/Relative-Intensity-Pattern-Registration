/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.PairAligner;
import ripr.core.PairEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One complete, explicit registration recipe: everything the automatic selector is allowed to choose.
 *
 * <p>A recipe is not a label. It carries every value it changes, so any automatic decision can be
 * reproduced by typing the same numbers into the settings dialog, replayed from a macro, or diffed
 * against another run. {@link #applyTo(RelativeIntensityPatternParameters)} is the only route from a recipe to a
 * registration, which is what makes automatic and manual runs provably identical.
 */
public final class RegistrationRecipe {

    /** The three base pixel-support rules the selector may choose between. */
    public static final PairAligner.PixelSupport[] SUPPORTS = {
            PairAligner.PixelSupport.ALL,
            PairAligner.PixelSupport.GRADIENT,
            PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT};

    /** The four estimation filters the selector may choose between. */
    public static final Preprocessing[] FILTERS = {
            Preprocessing.NONE, Preprocessing.GAUSSIAN_0_7,
            Preprocessing.GAUSSIAN_1_0, Preprocessing.MEDIAN_3X3};

    /** Frozen for every swept recipe; still editable by hand in the settings dialog. */
    public static final double SWEPT_GRADIENT_MULTIPLIER = 0.5;
    public static final double SWEPT_REMOVAL_PERCENT = 25.0;
    public static final Preprocessing SWEPT_MASK_SCORING_FILTER = Preprocessing.NONE;
    public static final int SWEPT_MAX_ITERATIONS = 25;
    public static final int SWEPT_MAX_SAMPLES = 200_000;

    /** Intensity restriction, expressed as the two editable percentile fields. */
    public enum Band {
        FULL("full", Double.NaN, Double.NaN, "no intensity restriction"),
        NO_TOP_10("no_top_10", Double.NaN, 90.0, "exclude the brightest 10 percent"),
        NO_TOP_25("no_top_25", Double.NaN, 75.0, "exclude the brightest 25 percent"),
        NO_BOTTOM_25("no_bottom_25", 25.0, Double.NaN, "exclude the dimmest 25 percent");

        private final String id;
        private final double floor;
        private final double ceiling;
        private final String label;

        Band(String id, double floor, double ceiling, String label) {
            this.id = id;
            this.floor = floor;
            this.ceiling = ceiling;
            this.label = label;
        }

        public String id() { return id; }
        public double floorPercentile() { return floor; }
        public double ceilingPercentile() { return ceiling; }
        public String label() { return label; }

        /** The band matching an explicit percentile pair, or null when the pair is off-grid. */
        public static Band from(double floorPercentile, double ceilingPercentile) {
            for (Band band : values()) {
                if (same(band.floor, floorPercentile) && same(band.ceiling, ceilingPercentile)) {
                    return band;
                }
            }
            return null;
        }
    }

    /** Spatial removal, expressed as the editable strategy and percentage fields. */
    public enum Mask {
        NONE("none", PixelSelectionStrategy.NONE, "no spatial pixel removal"),
        LEAST_INFORMATIVE_25("least_informative_25", PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                "remove the least spatially informative 25 percent");

        private final String id;
        private final PixelSelectionStrategy strategy;
        private final String label;

        Mask(String id, PixelSelectionStrategy strategy, String label) {
            this.id = id;
            this.strategy = strategy;
            this.label = label;
        }

        public String id() { return id; }
        public PixelSelectionStrategy strategy() { return strategy; }
        public String label() { return label; }
    }

    /**
     * Which estimator turns a frame pair into a movement. A separate axis from the four below, and
     * the only one that changes what is minimised rather than how.
     *
     * <p>The four log-ratio dimensions stay in the recipe when this is
     * {@link PairEstimator.Kind#AREA_CORRELATION}, and they are inert there: an area method has no
     * pixel support to choose and no per-pixel mask to apply. The estimator candidates are built
     * with the neutral values so a reader of a run log is not told about settings that did nothing.
     */
    public final PairEstimator.Kind estimator;
    public final PairAligner.PixelSupport pixelSupport;
    public final double gradientFraction;
    public final double floorPercentile;
    public final double ceilingPercentile;
    public final Preprocessing preprocessing;
    public final PixelSelectionStrategy pixelSelectionStrategy;
    public final Preprocessing pixelSelectionPreprocessing;
    public final double pixelRemovalPercent;
    public final int maxIterations;
    public final int maxSamples;

    public RegistrationRecipe(PairAligner.PixelSupport pixelSupport, double gradientFraction,
                              double floorPercentile, double ceilingPercentile,
                              Preprocessing preprocessing,
                              PixelSelectionStrategy pixelSelectionStrategy,
                              Preprocessing pixelSelectionPreprocessing,
                              double pixelRemovalPercent, int maxIterations, int maxSamples) {
        this(PairEstimator.Kind.LOG_RATIO_FIT, pixelSupport, gradientFraction, floorPercentile,
                ceilingPercentile, preprocessing, pixelSelectionStrategy,
                pixelSelectionPreprocessing, pixelRemovalPercent, maxIterations, maxSamples);
    }

    public RegistrationRecipe(PairEstimator.Kind estimator, PairAligner.PixelSupport pixelSupport,
                              double gradientFraction,
                              double floorPercentile, double ceilingPercentile,
                              Preprocessing preprocessing,
                              PixelSelectionStrategy pixelSelectionStrategy,
                              Preprocessing pixelSelectionPreprocessing,
                              double pixelRemovalPercent, int maxIterations, int maxSamples) {
        if (estimator == null || pixelSupport == null || preprocessing == null
                || pixelSelectionStrategy == null || pixelSelectionPreprocessing == null) {
            throw new IllegalArgumentException("every recipe field is required");
        }
        if (estimator != PairEstimator.Kind.LOG_RATIO_FIT
                && pixelSelectionStrategy != PixelSelectionStrategy.NONE) {
            throw new IllegalArgumentException("a spatial mask is a second pass of the log-ratio "
                    + "fit; " + estimator.id() + " has no per-pixel support to mask");
        }
        if (!(pixelRemovalPercent > 0 && pixelRemovalPercent < 100)) {
            throw new IllegalArgumentException("pixel removal must be in (0, 100)");
        }
        if (maxIterations < 1) throw new IllegalArgumentException("iterations must be at least 1");
        if (maxSamples < 1) throw new IllegalArgumentException("samples must be at least 1");
        this.estimator = estimator;
        this.pixelSupport = pixelSupport;
        this.gradientFraction = gradientFraction;
        this.floorPercentile = floorPercentile;
        this.ceilingPercentile = ceilingPercentile;
        this.preprocessing = preprocessing;
        this.pixelSelectionStrategy = pixelSelectionStrategy;
        this.pixelSelectionPreprocessing = pixelSelectionPreprocessing;
        this.pixelRemovalPercent = pixelRemovalPercent;
        this.maxIterations = maxIterations;
        this.maxSamples = maxSamples;
    }

    /** Build one of the swept recipes from its four dimensions. */
    public static RegistrationRecipe swept(PairAligner.PixelSupport support, Band band,
                                           Preprocessing filter, Mask mask) {
        if (band == null || mask == null) throw new IllegalArgumentException("band and mask are required");
        return new RegistrationRecipe(support, SWEPT_GRADIENT_MULTIPLIER, band.floorPercentile(),
                band.ceilingPercentile(), filter, mask.strategy(), SWEPT_MASK_SCORING_FILTER,
                SWEPT_REMOVAL_PERCENT, SWEPT_MAX_ITERATIONS, SWEPT_MAX_SAMPLES);
    }

    /**
     * One candidate on either axis, named by all five dimensions.
     *
     * <p>What the generated selector model calls to rebuild a retained candidate. The estimator has
     * to be part of that call rather than implied, or a model trained with the axis would silently
     * resolve to the log-ratio fit and quietly stop being the model that was validated.
     */
    public static RegistrationRecipe swept(PairEstimator.Kind estimator,
                                           PairAligner.PixelSupport support, Band band,
                                           Preprocessing filter, Mask mask) {
        if (estimator == null) throw new IllegalArgumentException("estimator is required");
        if (band == null || mask == null) {
            throw new IllegalArgumentException("band and mask are required");
        }
        return new RegistrationRecipe(estimator, support, SWEPT_GRADIENT_MULTIPLIER,
                band.floorPercentile(), band.ceilingPercentile(), filter, mask.strategy(),
                SWEPT_MASK_SCORING_FILTER, SWEPT_REMOVAL_PERCENT, SWEPT_MAX_ITERATIONS,
                SWEPT_MAX_SAMPLES);
    }

    /**
     * One estimator-axis candidate: area correlation, with the log-ratio dimensions held neutral.
     *
     * <p>Support is {@code ALL} and the mask is {@code NONE} because neither means anything to a
     * whole-window correlation; band and filter are swept because both change the pixels the
     * correlation sees.
     */
    public static RegistrationRecipe estimatorSwept(Band band, Preprocessing filter) {
        if (band == null) throw new IllegalArgumentException("band is required");
        return new RegistrationRecipe(PairEstimator.Kind.AREA_CORRELATION,
                PairAligner.PixelSupport.ALL, SWEPT_GRADIENT_MULTIPLIER, band.floorPercentile(),
                band.ceilingPercentile(), filter, Mask.NONE.strategy(), SWEPT_MASK_SCORING_FILTER,
                SWEPT_REMOVAL_PERCENT, SWEPT_MAX_ITERATIONS, SWEPT_MAX_SAMPLES);
    }

    /** Read the recipe already present in a complete parameter bundle. */
    public static RegistrationRecipe of(RelativeIntensityPatternParameters parameters) {
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        return new RegistrationRecipe(parameters.estimator,
                parameters.pixelSupport, parameters.gradientFraction,
                parameters.floorPercentile, parameters.ceilingPercentile, parameters.preprocessing,
                parameters.pixelSelectionStrategy, parameters.pixelSelectionPreprocessing,
                parameters.pixelRemovalPercent, parameters.maxIterations, parameters.maxSamples);
    }

    /** The complete 96-recipe candidate set, in the order the sweep enumerated it. */
    public static List<RegistrationRecipe> sweptCandidates() {
        List<RegistrationRecipe> out = new ArrayList<>();
        for (PairAligner.PixelSupport support : SUPPORTS) {
            for (Band band : Band.values()) {
                for (Preprocessing filter : FILTERS) {
                    for (Mask mask : Mask.values()) {
                        out.add(swept(support, band, filter, mask));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The sixteen estimator-axis candidates: area correlation over every band and filter.
     *
     * <p>Kept separate from {@link #sweptCandidates()} rather than folded into it, because the 96
     * are a measured, published set whose identifiers name folders on disk. Callers that want the
     * whole candidate space ask for {@link #allCandidates()}.
     */
    public static List<RegistrationRecipe> estimatorCandidates() {
        List<RegistrationRecipe> out = new ArrayList<>();
        for (Band band : Band.values()) {
            for (Preprocessing filter : FILTERS) out.add(estimatorSwept(band, filter));
        }
        return out;
    }

    /**
     * The Gauss-Newton refinement over the same band and filter grid: sixteen more candidates.
     *
     * <p>Stage 3 of {@code docs/newton_refinement_plan.md}. An exact mirror of
     * {@link #estimatorCandidates()} with the refinement swapped, so the retrain compares like with
     * like, and additive to what is already on disk: the identifiers carry the estimator name, so
     * these are sixteen new folders beside the 112 rather than a change to any of them.
     */
    public static List<RegistrationRecipe> newtonEstimatorCandidates() {
        List<RegistrationRecipe> out = new ArrayList<>();
        for (Band band : Band.values()) {
            for (Preprocessing filter : FILTERS) {
                out.add(new RegistrationRecipe(PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                        PairAligner.PixelSupport.ALL, SWEPT_GRADIENT_MULTIPLIER,
                        band.floorPercentile(), band.ceilingPercentile(), filter,
                        Mask.NONE.strategy(), SWEPT_MASK_SCORING_FILTER, SWEPT_REMOVAL_PERCENT,
                        SWEPT_MAX_ITERATIONS, SWEPT_MAX_SAMPLES));
            }
        }
        return out;
    }

    /**
     * Every candidate on every axis: 96 log-ratio recipes, 16 area-correlation ones and 16 with the
     * Gauss-Newton refinement.
     */
    public static List<RegistrationRecipe> allCandidates() {
        List<RegistrationRecipe> out = new ArrayList<>(sweptCandidates());
        out.addAll(estimatorCandidates());
        out.addAll(newtonEstimatorCandidates());
        return out;
    }

    /**
     * Write this recipe over a base parameter bundle, leaving every other setting untouched.
     * The result is an ordinary explicit configuration with no automatic flag left set.
     */
    public RelativeIntensityPatternParameters applyTo(RelativeIntensityPatternParameters base) {
        if (base == null) throw new IllegalArgumentException("base parameters are null");
        return base.toBuilder()
                .useRecommendation(false)
                .automaticFilterSelection(false)
                .estimator(estimator)
                .pixelSupport(pixelSupport)
                .gradientFraction(gradientFraction)
                .floorPercentile(floorPercentile)
                .ceilingPercentile(ceilingPercentile)
                .preprocessing(preprocessing)
                .pixelSelectionStrategy(pixelSelectionStrategy)
                .pixelSelectionPreprocessing(pixelSelectionPreprocessing)
                .pixelRemovalPercent(pixelRemovalPercent)
                .maxIterations(maxIterations)
                .maxSamples(maxSamples)
                .build();
    }

    /** True when this recipe is one the sweep actually measured, on either axis. */
    public boolean isSwept() {
        if (estimator != PairEstimator.Kind.LOG_RATIO_FIT) {
            return band() != null
                    && pixelSupport == PairAligner.PixelSupport.ALL
                    && mask() == Mask.NONE
                    && isSweptFilter(preprocessing)
                    && maxIterations == SWEPT_MAX_ITERATIONS
                    && maxSamples == SWEPT_MAX_SAMPLES;
        }
        return band() != null
                && same(gradientFraction, SWEPT_GRADIENT_MULTIPLIER)
                && mask() != null
                && pixelSelectionPreprocessing == SWEPT_MASK_SCORING_FILTER
                && (mask() == Mask.NONE || same(pixelRemovalPercent, SWEPT_REMOVAL_PERCENT))
                && maxIterations == SWEPT_MAX_ITERATIONS
                && maxSamples == SWEPT_MAX_SAMPLES
                && isSweptFilter(preprocessing);
    }

    private static boolean isSweptFilter(Preprocessing filter) {
        for (Preprocessing candidate : FILTERS) if (candidate == filter) return true;
        return false;
    }

    public Band band() {
        return Band.from(floorPercentile, ceilingPercentile);
    }

    public Mask mask() {
        for (Mask candidate : Mask.values()) {
            if (candidate.strategy() == pixelSelectionStrategy) return candidate;
        }
        return null;
    }

    /**
     * The complete identifier used by the sweep folders and the manifests. Off-grid recipes, such as
     * the category fallback for a mutual-noise preset, get an explicit suffix rather than a
     * misleading match.
     */
    public String id() {
        Band band = band();
        Mask mask = mask();
        // The estimator comes first and only when it is not the default, so every identifier the
        // 96-recipe sweep already wrote to disk still names the same folder it always did.
        String prefix = estimator == PairEstimator.Kind.LOG_RATIO_FIT
                ? "" : "estimator_" + estimator.id() + "__";
        String base = prefix + "support_" + pixelSupport.name().toLowerCase(Locale.ROOT)
                + "__band_" + (band == null ? custom() : band.id())
                + "__filter_" + preprocessing.name().toLowerCase(Locale.ROOT)
                + "__mask_" + (mask == null
                        ? pixelSelectionStrategy.name().toLowerCase(Locale.ROOT)
                        : mask.id());
        return isSwept() ? base : base + "__offgrid";
    }

    private String custom() {
        return "floor_" + optional(floorPercentile) + "_ceiling_" + optional(ceilingPercentile);
    }

    /** One line naming every value, for the confirmation dialog and the run log. */
    public String describe() {
        // Two estimators mean a run log that names only the other settings is no longer
        // reproducible, so the estimator is named first and always, default included.
        return "estimator " + estimator.id() + "; support " + pixelSupport.name()
                + "; gradient multiplier " + trim(gradientFraction)
                + "; exclude below " + optional(floorPercentile)
                + "; exclude above " + optional(ceilingPercentile)
                + "; estimation filter " + preprocessing.name()
                + "; spatial removal " + pixelSelectionStrategy.name()
                + " at " + trim(pixelRemovalPercent) + "%"
                + "; mask scoring filter " + pixelSelectionPreprocessing.name()
                + "; " + maxIterations + " iterations; " + maxSamples + " samples";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RegistrationRecipe)) return false;
        RegistrationRecipe that = (RegistrationRecipe) other;
        return estimator == that.estimator
                && pixelSupport == that.pixelSupport
                && same(gradientFraction, that.gradientFraction)
                && same(floorPercentile, that.floorPercentile)
                && same(ceilingPercentile, that.ceilingPercentile)
                && preprocessing == that.preprocessing
                && pixelSelectionStrategy == that.pixelSelectionStrategy
                && pixelSelectionPreprocessing == that.pixelSelectionPreprocessing
                && same(pixelRemovalPercent, that.pixelRemovalPercent)
                && maxIterations == that.maxIterations
                && maxSamples == that.maxSamples;
    }

    @Override
    public int hashCode() {
        return id().hashCode() * 31 + maxIterations * 7 + maxSamples;
    }

    @Override
    public String toString() {
        return id();
    }

    private static boolean same(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) return true;
        return Math.abs(a - b) < 1e-9;
    }

    private static String optional(double value) {
        return Double.isNaN(value) ? "off" : trim(value);
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
