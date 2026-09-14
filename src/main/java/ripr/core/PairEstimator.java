/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

/**
 * How one frame pair is turned into a movement estimate. The seam, and the axis, sit here.
 *
 * <p>Everything above this interface — {@link Registration}'s pair plan, {@link PyramidCache},
 * {@link PairScheduler}, {@link Reconciler}, {@link ChainRepair}, the warp, the plugin — consumes a
 * {@link PairAligner.Fit} and is indifferent to how that fit was produced. Everything below it is one
 * estimator's own business. Splitting the two at this line is what makes a second estimator a
 * <em>new axis</em> rather than a rewrite: adding {@link Kind#AREA_CORRELATION} changed no scheduling,
 * no reconciliation and no output code.
 *
 * <p><b>This is not a pixel support.</b> {@link PairAligner.PixelSupport} chooses which pixels the
 * log-ratio fit uses; it is a setting <em>of</em> the log-ratio estimator. An area-correlation
 * estimator never runs that fit, so it cannot be a value of that enumeration without the name meaning
 * two different things. Pixel support, intensity band, estimation filter and pixel mask remain
 * meaningful only for {@link Kind#LOG_RATIO_FIT}.
 *
 * <p><b>Both estimators read the same pyramids.</b> The argument is the log-domain pyramid pair that
 * {@link LogPlane#pyramid(int)} produces and {@link PyramidCache} already holds, so switching
 * estimator changes neither the preprocessing, nor the intensity band, nor the memory budget, nor how
 * many times a frame is read. A comparison between the two arms is therefore a comparison of
 * estimators and of nothing else.
 */
public interface PairEstimator {

    /**
     * Movement of the content from {@code a} to {@code b}, with the quality numbers that describe it.
     *
     * <p>Both pyramids come from {@link LogPlane#pyramid(int)} with the same level count and the same
     * dimensions. An implementation that cannot answer returns a fit with status
     * {@link PairAligner.Status#REFUSED_LOW_OVERLAP} rather than a transform it does not believe:
     * a refused pair is dropped by the reconciler, whereas a wrong one corrupts every later frame.
     */
    PairAligner.Fit estimate(LogPlane[] a, LogPlane[] b, PairAligner.Options options);

    /**
     * Refine a rigid proposal from an already-solved translation without repeating the global
     * translation search.
     *
     * <p>This is the estimator seam used by incremental rotation. Implementations must preserve the
     * supplied translation as the starting basin and return a rigid proposal measured with the same
     * full-frame quality fields as {@link #estimate}. The ordinary estimator path never calls this
     * method.
     */
    default PairAligner.Fit refineRigidFromTranslation(
            LogPlane[] a, LogPlane[] b, PairAligner.Options options,
            PairAligner.Fit translation) {
        throw new UnsupportedOperationException(id() + " has no incremental rigid refinement");
    }

    /** The same proposal with an optional source-coordinate mask. */
    default PairAligner.Fit refineRigidFromTranslation(
            LogPlane[] a, LogPlane[] b, PairAligner.Options options,
            PairAligner.Fit translation, boolean[][] solverSupport) {
        if (solverSupport != null) {
            throw new IllegalArgumentException(id() + " has no per-pixel solver support");
        }
        return refineRigidFromTranslation(a, b, options, translation);
    }

    /**
     * Refit only translation while retaining {@code start.theta} exactly.
     *
     * <p>A split rotation pass uses this after its angle recipe has made a proposal, so the
     * Automatic selector's translation recipe still determines the final dx and dy.
     */
    default PairAligner.Fit refineTranslationAtFixedRotation(
            LogPlane[] a, LogPlane[] b, PairAligner.Options options, Transform start,
            boolean[][] solverSupport) {
        throw new UnsupportedOperationException(id() + " has no fixed-angle translation refit");
    }

    /** Globally search dx/dy while retaining {@code angle.theta} exactly. */
    default PairAligner.Fit estimateTranslationAtFixedRotation(
            LogPlane[] a, LogPlane[] b, PairAligner.Options options, Transform angle,
            boolean[][] solverSupport) {
        throw new UnsupportedOperationException(
                id() + " has no global fixed-angle translation search");
    }

    /** Stable machine-readable name. Appears in run logs, macro options and result tables. */
    String id();

    /** What to call it in a dialog or a report. */
    String label();

    /**
     * Whether this estimator wants coarse pyramid levels formed in the intensity domain rather than
     * the log domain. See {@link LogPlane#linearPyramid(int)}.
     *
     * <p>Almost nothing should answer {@code true}. The log-domain pyramid keeps the log-ratio
     * criterion's gain invariance exact at every level, which is the reason this engine exists, and an
     * estimator asking for the other one is asking for a pyramid the default estimator must never be
     * given. It exists because the classical area method conventionally correlates arithmetic means,
     * and whether that difference is worth anything is a measurable question rather than a stylistic
     * one.
     */
    default boolean prefersLinearPyramid() {
        return false;
    }

    /**
     * Extra bytes per full-resolution pixel this estimator keeps cached on each pyramid it is given.
     *
     * <p>Zero for an estimator that reads the pyramid and derives nothing it wants to keep. An
     * estimator that memoises a prepared view on the plane — so it is not rebuilt on every one of the
     * nine pairs a frame appears in — must declare the cost here, because {@link PyramidCache}
     * decides how many pyramids to hold from a memory budget and would otherwise hold too many. See
     * {@link PyramidCache#AREA_WINDOW_BYTES_PER_PIXEL}.
     */
    default long cachedBytesPerPixel() {
        return 0;
    }

    /** Whether this estimator can fit the bounded rigid transform requested by {@code fitRotation}. */
    default boolean supportsRotation() {
        return false;
    }

    /** The estimators this plugin ships. The value a user picks, and the implementation, are one thing. */
    enum Kind implements PairEstimator {

        /**
         * Level the log-ratio field between the two frames. The default, and the reason this plugin
         * exists: the global intensity gain is profiled out at every evaluation, so bleaching does not
         * have to look like movement, and a redescending norm lets genuine change be outvoted rather
         * than averaged in. See {@link PairAligner}.
         */
        LOG_RATIO_FIT("log_ratio_fit", "Log-ratio fit") {
            @Override
            public PairAligner.Fit estimate(LogPlane[] a, LogPlane[] b, PairAligner.Options o) {
                return PairAligner.align(a, b, o);
            }

            @Override
            public PairAligner.Fit refineRigidFromTranslation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o,
                    PairAligner.Fit translation) {
                return PairAligner.alignRigidFromTranslation(a, b, o, translation, null);
            }

            @Override
            public PairAligner.Fit refineRigidFromTranslation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o,
                    PairAligner.Fit translation, boolean[][] solverSupport) {
                return PairAligner.alignRigidFromTranslation(
                        a, b, o, translation, solverSupport);
            }

            @Override
            public PairAligner.Fit refineTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform start,
                    boolean[][] solverSupport) {
                PairAligner.Options translation = o.copy();
                translation.fitRotation = false;
                return PairAligner.alignFrom(a, b, translation, start, solverSupport);
            }

            @Override
            public PairAligner.Fit estimateTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform angle,
                    boolean[][] solverSupport) {
                return PairAligner.alignTranslationAtFixedRotation(
                        a, b, o, angle, solverSupport);
            }

            @Override
            public boolean supportsRotation() {
                return true;
            }
        },

        /**
         * Normalised cross-correlation on a pyramid, with sub-pixel peak refinement. No gradients, no
         * robust weighting, no iterative solve against a criterion — the classical area method, in the
         * family TurboReg and StackReg belong to. See {@link AreaCorrelation} for what it does and for
         * the measured per-image-type position on which its use is decided.
         */
        AREA_CORRELATION("area_correlation", "Area correlation") {
            @Override
            public PairAligner.Fit estimate(LogPlane[] a, LogPlane[] b, PairAligner.Options o) {
                return AreaCorrelation.align(a, b, o);
            }

            @Override
            public PairAligner.Fit refineRigidFromTranslation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o,
                    PairAligner.Fit translation) {
                return AreaCorrelation.alignRigidFromTranslation(
                        a, b, o, AreaCorrelation.Refiner.GRID, translation);
            }

            @Override
            public PairAligner.Fit refineTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform start,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotation(
                        a, b, o, AreaCorrelation.Refiner.GRID, start);
            }

            @Override
            public PairAligner.Fit estimateTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform angle,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotationGlobal(
                        a, b, o, AreaCorrelation.Refiner.GRID, angle);
            }

            @Override
            public long cachedBytesPerPixel() {
                return PyramidCache.AREA_WINDOW_BYTES_PER_PIXEL;
            }

            @Override public boolean supportsRotation() { return true; }
        },

        /**
         * The same correlation on a pyramid whose coarse levels are arithmetic means of intensity
         * rather than geometric ones — what TurboReg and its relatives decimate.
         *
         * <p><b>Measured worse on every image type. Do not choose this.</b> It is kept so the finding
         * stays reproducible, not because it is an option worth having.
         *
         * <p>It existed to answer one question and only one. Measured over the 80 development
         * recordings, {@link #AREA_CORRELATION} is roughly three times less accurate than TurboReg's
         * estimator under our own solver, our own lag set and our own pair plan; the only documented
         * candidate for that gap was that we correlate a log-domain pyramid where TurboReg correlates
         * a linear-domain one. This arm isolated exactly that variable — identical correlation code,
         * search, refinement and everything above the seam — and refuted it, in the opposite
         * direction. Median of median error over 80 recordings, 2026-08-18:
         *
         * <pre>
         *   log-ratio fit                       0.022262 px    0.883 s
         *   area correlation, log pyramid       0.064094 px    2.105 s
         *   area correlation, linear pyramid    0.255405 px    3.528 s
         * </pre>
         *
         * <p>Worse on all five image types by 4x to 10x against the log-domain arm, and the slowest of
         * the three. So the decimation domain was not the cause of the deficit against TurboReg, and
         * correlating geometric means beats correlating arithmetic ones here. The reason is not
         * established; the plausible one is that the log domain compresses the bright tail that
         * otherwise dominates a normalised cross-correlation, which is the same property that makes
         * the log-ratio criterion work.
         */
        AREA_CORRELATION_LINEAR("area_correlation_linear", "Area correlation, linear pyramid") {
            @Override
            public PairAligner.Fit estimate(LogPlane[] a, LogPlane[] b, PairAligner.Options o) {
                return AreaCorrelation.align(a, b, o);
            }

            @Override
            public PairAligner.Fit refineRigidFromTranslation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o,
                    PairAligner.Fit translation) {
                return AreaCorrelation.alignRigidFromTranslation(
                        a, b, o, AreaCorrelation.Refiner.GRID, translation);
            }

            @Override
            public PairAligner.Fit refineTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform start,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotation(
                        a, b, o, AreaCorrelation.Refiner.GRID, start);
            }

            @Override
            public PairAligner.Fit estimateTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform angle,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotationGlobal(
                        a, b, o, AreaCorrelation.Refiner.GRID, angle);
            }

            @Override
            public boolean prefersLinearPyramid() {
                return true;
            }

            @Override
            public long cachedBytesPerPixel() {
                return PyramidCache.AREA_WINDOW_BYTES_PER_PIXEL;
            }

            @Override public boolean supportsRotation() { return true; }
        },

        /**
         * {@link #AREA_CORRELATION} with the sub-pixel refinement replaced by a Gauss-Newton step.
         *
         * <p><b>What the superseded 2026-08-20 selector chose for brightfield/DIC and
         * fiducial/static, over a failed sealed-set gate by the project owner's explicit
         * decision.</b> It
         * replaced {@link #AREA_CORRELATION} in both retained candidates, keeping the same
         * {@code FULL} band, the same Gaussian 0.7 estimation filter and the same neutral support and
         * mask, so only the refinement changed. The chain is Stages 0 to 4 of
         * {@code docs/newton_refinement_plan.md}. The current installed fixed-policy selector
         * superseded that model and uses {@link #LOG_RATIO_FIT} for all five image types.
         *
         * <p><b>Read the gate before trusting the adoption.</b> The third sealed test set was opened
         * once, on 2026-08-20, and this value <b>failed</b> its accuracy gate: a mean median ratio of
         * 1.000012 against a declared limit of 1.000. The rule that gate carries is that a failure
         * restores the previous model, and it did — the model was restored, and then reinstated by an
         * explicit owner decision that the five-nanopixel loss was worth the 24% saving. <b>That is an
         * override of a pre-declared gate, not a passing result</b>, and no summary of this value may
         * describe it as validated on the third sealed set. Anyone who prefers the gate's verdict to
         * the owner's can restore
         * {@code summaries/full_selector_sweep_v1/AutomaticRegistrationSelectorModel.before_newton_refinement.java.txt}
         * in one file copy.
         *
         * <pre>
         *   held-out mean median       0.020654 -> 0.020258 px     better, over 80 recordings
         *   held-out mean seconds      2.078    -> 1.594
         *   third sealed set           0.415148 -> 0.415153 px     worse by 0.000005, gate was 1.000
         *   third sealed set, seconds  1.536    -> 1.161           24.4% saved
         * </pre>
         *
         * <p><b>What the five nanopixels are.</b> One part in eighty thousand of the sealed set's mean
         * error, on a set where twenty-four of forty recordings are bit-identical between the two
         * models because the selector holds no candidate for them. Of the sixteen the change can
         * reach, ten worsened and six improved; brightfield/DIC gained 0.000125 px and fiducial/static
         * lost 0.000149 px. Every other gate passed, including a 24.4% time saving against a limit of
         * no slowdown. A gate with no declared noise band is a coin flip for a change that is
         * accuracy-neutral by construction — which is the reason the override was accepted, and a
         * lesson about writing gates rather than grounds for re-reading a spent set.
         * {@code docs/newton_refinement_stage4_third_set_findings.md}.
         *
         * <p><b>It is band-sensitive, and that is the one thing to know before changing anything.</b>
         * Forced to run at the fiducial category recommendation, which excludes the brightest 25% of
         * each frame, this refinement fails outright: on a bead field that band is a scatter of holes,
         * the Gauss-Newton pass admits 15% of the samples the grid's scoring function admits, and it
         * falls below {@code minValidFraction}, so the refinement declines to start. It then hands
         * back to the grid rather than returning a whole-pixel answer, which is what
         * {@link AreaCorrelation}'s {@code newtonRefine} guards. The model Stage 3 trained chose
         * {@code FULL} — no band — so the case never arose there either.
         * {@code docs/newton_refinement_stage2_diagnosis.md} has the measurement.
         *
         * <p>Against {@link #AREA_CORRELATION} with everything but the refinement held identical,
         * at the category recommendation over the 80 development recordings:
         *
         * <pre>
         *   mean processor seconds   17.19 -> 7.79     54.7% saved
         *   paired median            better on four image types of five, by up to 0.0025 px
         *   fiducial/static median   0.011171 -> 0.012578 px     limit was +0.001
         *   fiducial/static worst    0.034827 -> 0.321317 px     9.2x worse
         * </pre>
         *
         * <p><b>The speed is real and it reached what the plan was for</b>: 7.79 processor seconds is
         * 1.06 times the log-ratio fit's 7.35, where the grid refinement sits at 2.34 times. It fails
         * on the tail, not on the median, and the failure is concentrated — fiducial/static under
         * steady directional drift, two recordings roughly nine times worse and the rest of the image
         * type unmoved. That is the movement profile where the two frames slide monotonically apart,
         * so the set of pixels valid in both shrinks as the lag grows, on the image type where that
         * set is beads on a flat background rather than a filled rectangle. A grid search compares
         * heights and does not care about a small step in the surface; a derivative method computes
         * the slope of a function that has one, and can be confidently wrong. That is the hypothesis,
         * it is what the plan named as its risk 1 before any of this was built, and it is <b>not
         * confirmed</b> — see {@code docs/newton_refinement_stage2_findings.md}.
         *
         * <p>The shipping refinement locates the peak like a game of hot-and-cold: score the eight
         * neighbours at some spacing, fit a quadratic through the nine scores, jump to its summit,
         * shrink the spacing, repeat — up to nine correlations per round and forty-five in total,
         * every one a full pass over the sampled pixels. This value reads the ground under its feet
         * instead. The interpolating cubic B-spline the estimator already uses as its image model is
         * differentiable, so one pass accumulating fifteen sums yields the score, both first
         * derivatives and a Gauss-Newton approximation to the second derivatives, and the step to the
         * summit follows in closed form. The derivation is in {@link AreaCorrelation}'s
         * {@code newtonRefine}.
         *
         * <p><b>Everything else is the same code.</b> Preparation, interpolator, integer sweep,
         * pyramid descent, shift clamp and reporting are shared with {@link #AREA_CORRELATION}
         * through {@link AreaCorrelation.Refiner}, so a difference between the two arms is a
         * difference of refinement and of nothing else. That is also why this is a third value rather
         * than an edit to the second: the automatic selector's retained candidates were fitted to
         * measurements of the existing implementation, and moving it by one bit would invalidate the
         * model and spend a sealed test set to revalidate.
         *
         * <p><b>The analytic fixture predicted the speed and overstated the accuracy, which is why one
         * fixture is not a measurement.</b> Stage 0, 2026-08-19: 2.21 times less arithmetic than the
         * grid and 2.8 times more accurate — worst error 0.000243 px against the grid's 0.000692 px.
         * Every one of those shifts was on a synthetic image with a clean rectangle of valid pixels,
         * which is the best-conditioned input this method will ever see; on real frames with no band
         * the two refinements are within 2 to 4 percent of each other, and the accuracy advantage the
         * fixture showed does not survive. The speed did: 54.7% off the estimator on real recordings,
         * against the 36% Stage 0 counted. {@code docs/newton_refinement_stage0_findings.md} and
         * {@code docs/newton_refinement_stage2_findings.md}.
         */
        AREA_CORRELATION_NEWTON("area_correlation_newton", "Area correlation, Newton refinement") {
            @Override
            public PairAligner.Fit estimate(LogPlane[] a, LogPlane[] b, PairAligner.Options o) {
                return AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.NEWTON);
            }

            @Override
            public PairAligner.Fit refineRigidFromTranslation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o,
                    PairAligner.Fit translation) {
                return AreaCorrelation.alignRigidFromTranslation(
                        a, b, o, AreaCorrelation.Refiner.NEWTON, translation);
            }

            @Override
            public PairAligner.Fit refineTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform start,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotation(
                        a, b, o, AreaCorrelation.Refiner.NEWTON, start);
            }

            @Override
            public PairAligner.Fit estimateTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform angle,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotationGlobal(
                        a, b, o, AreaCorrelation.Refiner.NEWTON, angle);
            }

            @Override
            public long cachedBytesPerPixel() {
                return PyramidCache.AREA_WINDOW_BYTES_PER_PIXEL;
            }

            @Override public boolean supportsRotation() { return true; }
        },

        /**
         * {@link #AREA_CORRELATION} with guarded Enhanced Correlation Coefficient refinement.
         *
         * <p>The established global pyramid sweep supplies capture range; only the final translation
         * refinement uses the closed-form Enhanced Correlation Coefficient update. Each step is
         * capped and accepted only when the measured normalised-correlation score improves. An
         * ill-conditioned or rejected first step falls back to the established grid refinement.
         * Rigid three-parameter fitting continues to use the shared score-guarded grid; fixed-angle
         * translation uses the Enhanced Correlation Coefficient update.
         *
         * <p>This is a manual comparison arm. The installed Automatic selector remains frozen.
         */
        AREA_CORRELATION_ECC("area_correlation_ecc",
                "Area correlation, Enhanced Correlation Coefficient refinement") {
            @Override
            public PairAligner.Fit estimate(LogPlane[] a, LogPlane[] b, PairAligner.Options o) {
                return AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.ECC);
            }

            @Override
            public PairAligner.Fit refineRigidFromTranslation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o,
                    PairAligner.Fit translation) {
                return AreaCorrelation.alignRigidFromTranslation(
                        a, b, o, AreaCorrelation.Refiner.ECC, translation);
            }

            @Override
            public PairAligner.Fit refineTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform start,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotation(
                        a, b, o, AreaCorrelation.Refiner.ECC, start);
            }

            @Override
            public PairAligner.Fit estimateTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform angle,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotationGlobal(
                        a, b, o, AreaCorrelation.Refiner.ECC, angle);
            }

            @Override
            public long cachedBytesPerPixel() {
                return PyramidCache.AREA_WINDOW_BYTES_PER_PIXEL;
            }

            @Override public boolean supportsRotation() { return true; }
        },

        /**
         * {@link #AREA_CORRELATION} with the refinement's opening rounds run on a quarter of the
         * pixels.
         *
         * <p><b>An unshipped measurement arm, not an option to choose.</b> Item 3 of
         * {@code docs/performance_optimisation_plan.md}, which observed that the first refinement
         * rounds are locating the peak at a spacing of one pixel and then a quarter and do not need
         * every pixel to do it. Doubling the stride quarters the samples those rounds read; the last
         * rounds, which set the answer, still run at full density.
         *
         * <p>It exists so the idea can be measured against {@link #AREA_CORRELATION} with everything
         * else identical rather than argued about. The risk the plan named is that it should be worst
         * exactly where this estimator is already weakest — a sparse bead field has few informative
         * pixels to spare. See {@code docs/subsample_and_lag_pruning_findings.md} for what it measured.
         */
        AREA_CORRELATION_SUBSAMPLED("area_correlation_subsampled",
                "Area correlation, subsampled refinement") {
            @Override
            public PairAligner.Fit estimate(LogPlane[] a, LogPlane[] b, PairAligner.Options o) {
                return AreaCorrelation.align(a, b, o, AreaCorrelation.Refiner.GRID_SUBSAMPLED);
            }

            @Override
            public PairAligner.Fit refineRigidFromTranslation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o,
                    PairAligner.Fit translation) {
                return AreaCorrelation.alignRigidFromTranslation(
                        a, b, o, AreaCorrelation.Refiner.GRID_SUBSAMPLED, translation);
            }

            @Override
            public PairAligner.Fit refineTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform start,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotation(
                        a, b, o, AreaCorrelation.Refiner.GRID_SUBSAMPLED, start);
            }

            @Override
            public PairAligner.Fit estimateTranslationAtFixedRotation(
                    LogPlane[] a, LogPlane[] b, PairAligner.Options o, Transform angle,
                    boolean[][] solverSupport) {
                requireNoSolverSupport(solverSupport);
                return AreaCorrelation.alignTranslationAtFixedRotationGlobal(
                        a, b, o, AreaCorrelation.Refiner.GRID_SUBSAMPLED, angle);
            }

            @Override
            public long cachedBytesPerPixel() {
                return PyramidCache.AREA_WINDOW_BYTES_PER_PIXEL;
            }

            @Override public boolean supportsRotation() { return true; }
        };

        private final String id;
        private final String label;

        Kind(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        private static void requireNoSolverSupport(boolean[][] solverSupport) {
            if (solverSupport != null) {
                throw new IllegalArgumentException(
                        "area correlation has no per-pixel solver support");
            }
        }

        /** Parse an {@link #id()} or an enum constant name. Case and hyphens do not matter. */
        public static Kind of(String text) {
            if (text == null) throw new IllegalArgumentException("no estimator named null");
            String wanted = text.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
            for (Kind kind : values()) {
                if (kind.id.equals(wanted) || kind.name().toLowerCase(java.util.Locale.ROOT)
                        .equals(wanted)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("no estimator named " + text);
        }
    }
}
