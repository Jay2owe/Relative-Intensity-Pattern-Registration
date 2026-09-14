/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.concurrent.CancellationException;

/**
 * Aligns one frame to another by levelling the log-ratio field between them.
 *
 * <p>The criterion. For a transform {@code p} and a free scalar {@code c},
 *
 * <pre>
 *   r(x) = logB(W(x; p)) - logA(x) - c
 *   cost = mean over usable x of rho(r(x))
 * </pre>
 *
 * {@code c} is the log of the global intensity gain between the two frames and is profiled out rather
 * than fitted — at every evaluation it is set to the median of the difference field, which is its L1
 * optimum. That is where gain invariance comes from, and it is also a free measurement:
 * {@link Fit#logGain} is a bleaching or lamp-drift trace that costs nothing to produce.
 *
 * <p><b>One gain estimator, everywhere.</b> The median is used in the iteration, in the line search
 * and in the reported residual. Mixing estimators — a weighted mean inside the iteration and a median
 * in the line search — makes the two costs different objectives, so the search can reject a good step
 * or accept a bad one, and the symptom is not a crash but quietly worse alignment on some pairs and
 * not others. A median throughout is also the robust choice: a mean would be pulled by the same sparse
 * changes the robust norm exists to reject.
 *
 * <p>The solver. Forward-additive Gauss–Newton with iteratively reweighted least squares, run coarse
 * to fine on a log-domain pyramid, with an exhaustive integer search at the coarsest level to pick the
 * basin. The exhaustive stage is not belt-and-braces: a redescending norm (see
 * {@link RobustNorm#TUKEY}) deliberately makes the cost surface non-convex, and gradient descent alone
 * would happily settle into whichever local minimum it started nearest. Doing the search at the
 * coarsest level makes it affordable — a 30 px bound over four levels is a 9x9 integer sweep on a
 * sixteenth of the pixels.
 *
 * <p>Note what is <em>not</em> here: no feature detection, no FFT, no segmentation, no template. The
 * only inputs are the two frames.
 *
 * <p>Relation to the literature: minimising the spread of a ratio image is Woods' criterion (Woods,
 * Cherry &amp; Mazziotta 1992). Doing it in the log domain with a free additive constant, by
 * Gauss–Newton on image gradients, is the gain-invariant member of the Lucas–Kanade family — the
 * generalised dynamic image model of Gennert &amp; Negahdaripour (1987) and Negahdaripour (<i>IEEE
 * TPAMI</i> 20:961, 1998). Doing it with a redescending robust norm so that genuine change is rejected
 * as outlying is the part that makes it usable on data where the frames are supposed to differ.
 */
public final class PairAligner {

    private static final double LN2 = Math.log(2.0);
    private static final double EXP2_MIN = -16.0;
    private static final int EXP2_STEPS_PER_UNIT = 512;
    private static final double[] EXP2 = exp2Table();

    private PairAligner() {
    }

    /** How pixels are chosen to vote in the solver. */
    public enum PixelSupport {
        /** Every valid pixel. */
        ALL,
        /**
         * Only pixels whose gradient magnitude in the source frame exceeds a fraction of the median.
         *
         * <p>A flat pixel carries no information about displacement at all — its Jacobian row is
         * zero — so excluding it removes work rather than information. On a mostly-empty field that
         * is most of the image. The threshold is relative to the frame's own median gradient, so it
         * needs no value in log2 units from the user.
         */
        GRADIENT,
        /** Require an edge above the raw-noise estimate in both mapped frames. */
        MUTUAL_NOISE_GRADIENT
    }

    /** Tuning for {@link #align}. */
    public static final class Options {
        /** Pyramid levels including full resolution; 0 or less means choose automatically. */
        public int levels = 0;
        /** Ceiling when levels is chosen automatically. */
        public int maxLevels = 4;
        /** Coarsest level is never smaller than this on either axis. */
        public int minCoarseSize = 48;
        /** Bound on translation, full-resolution pixels. Sets the coarse search radius. */
        public double maxShift = 30;
        /** Largest coarse-sweep radius tolerated before levels are raised automatically. */
        public int coarseRadiusBudget = 8;
        /** How a residual is penalised. See {@link RobustNorm} — this is the setting that matters. */
        public RobustNorm norm = RobustNorm.HUBER;
        /**
         * Profile out the global intensity gain. <b>Leave this on.</b>
         *
         * <p>Turning it off forces {@code c = 0}, which makes the criterion a plain sum of squared
         * differences on log intensities — i.e. it removes the one property that distinguishes this
         * from StackReg, TurboReg and Image Stabilizer. It exists so that the gain-invariance claim can
         * be measured against its own negation on identical data, rather than asserted, and so a
         * benchmark can include an SSD baseline without depending on another plugin.
         */
        public boolean profileGain = true;
        /** Which pixels vote in the solver. Does not affect the reported residual. */
        public PixelSupport support = PixelSupport.ALL;
        /** Gradient or raw-noise multiplier used by the selected pixel-support rule. */
        public double gradientFraction = 0.5;
        /** Fit rotation as well as translation. Off: v0.1.0 is translation only. */
        public boolean fitRotation = false;
        /** Maximum absolute fitted rotation, radians. Ignored when {@link #fitRotation} is false. */
        public double maxRotation = Math.toRadians(10.0);
        /** Gauss–Newton iterations per pyramid level. */
        public int maxIterations = 25;
        /** Converged when a step moves the frame less than this many pixels. */
        public double convergence = 1e-3;
        /** Below this fraction of usable pixels the pair is refused rather than guessed at. */
        public double minValidFraction = 0.10;
        /** Cap on pixels visited per evaluation; above it, a regular stride is used. */
        public int maxSamples = 200_000;
        /** Smallest robust scale, log2 units. Stops identical frames dividing by zero. */
        public double scaleFloor = 1e-4;
        /** Smallest retained pair-uncertainty standard deviation in pixel-equivalent units. */
        public double uncertaintyMinimumStdPixels = 0.02;
        /** Largest retained pair-uncertainty standard deviation in pixel-equivalent units. */
        public double uncertaintyMaximumStdPixels = 20.0;
        /** Cooperative cancellation, polled between costly candidates and solver rounds. */
        public PairScheduler.Cancellation cancellation = PairScheduler.Cancellation.NEVER;

        public Options copy() {
            Options o = new Options();
            o.levels = levels;
            o.maxLevels = maxLevels;
            o.minCoarseSize = minCoarseSize;
            o.maxShift = maxShift;
            o.coarseRadiusBudget = coarseRadiusBudget;
            o.norm = norm;
            o.profileGain = profileGain;
            o.support = support;
            o.gradientFraction = gradientFraction;
            o.fitRotation = fitRotation;
            o.maxRotation = maxRotation;
            o.maxIterations = maxIterations;
            o.convergence = convergence;
            o.minValidFraction = minValidFraction;
            o.maxSamples = maxSamples;
            o.scaleFloor = scaleFloor;
            o.uncertaintyMinimumStdPixels = uncertaintyMinimumStdPixels;
            o.uncertaintyMaximumStdPixels = uncertaintyMaximumStdPixels;
            o.cancellation = cancellation;
            return o;
        }

        /**
         * Levels to actually use for a frame of this size: the requested or automatic count, raised
         * if necessary so the coarse sweep radius stays within {@link #coarseRadiusBudget}.
         */
        public int levelsFor(int width, int height) {
            int wanted = levels > 0
                    ? levels
                    : LogPlane.autoLevels(width, height, minCoarseSize, maxLevels);
            int needed = LogPlane.levelsForShift(maxShift, coarseRadiusBudget);
            int capped = LogPlane.autoLevels(width, height, 8, Math.max(wanted, needed));
            return Math.max(1, Math.min(capped, Math.max(wanted, needed)));
        }
    }

    /** Why a pair alignment ended. Maps one-to-one onto the {@code status} output column. */
    public enum Status {
        /** A step met the convergence tolerance, or no downhill step remained. */
        OK,
        /** Too few usable pixels. The transform is the identity and must not be trusted. */
        REFUSED_LOW_OVERLAP,
        /** Ran out of iterations at the finest level with the step still moving. */
        NOT_CONVERGED,
        /** The solution sits on the {@code maxShift} bound, so the true shift may be larger. */
        AT_SHIFT_BOUND,
        /** The solution sits on the angular bound, so the true rotation may be larger. */
        AT_ROTATION_BOUND,
        /** Both translation and rotation sit on their configured bounds. */
        AT_SHIFT_AND_ROTATION_BOUND
    }

    /** What one pair alignment produced, including the numbers worth reporting to the user. */
    public static final class Fit {
        /** Motion of the content from A to B. See {@link Transform} for the sign convention. */
        public final Transform transform;
        /** Mean absolute log-ratio at full resolution before aligning, log2 units. */
        public final double residualBefore;
        /** The same quantity after aligning. The plugin's own QC number. Never accuracy. */
        public final double residualAfter;
        /** {@code log2} of the global intensity gain from A to B. */
        public final double logGain;
        /** Fraction of full-resolution pixels that were usable at the solution. */
        public final double validFraction;
        /** Gauss–Newton iterations used at the finest level. */
        public final int iterations;
        public final Status status;
        /** Opt-in rotation confidence evidence, otherwise null. */
        public final RotationEvidence rotationEvidence;
        /** Direction-aware movement covariance, or an explicit unavailable value. */
        public final PairUncertainty uncertainty;

        Fit(Transform transform, double residualBefore, double residualAfter, double logGain,
            double validFraction, int iterations, Status status) {
            this(transform, residualBefore, residualAfter, logGain, validFraction, iterations,
                    status, null, PairUncertainty.unavailable(
                            transform != null && transform.theta != 0.0 ? 3 : 2));
        }

        Fit(Transform transform, double residualBefore, double residualAfter, double logGain,
            double validFraction, int iterations, Status status,
            RotationEvidence rotationEvidence) {
            this(transform, residualBefore, residualAfter, logGain, validFraction, iterations,
                    status, rotationEvidence, PairUncertainty.unavailable(
                            transform != null && transform.theta != 0.0 ? 3 : 2));
        }

        Fit(Transform transform, double residualBefore, double residualAfter, double logGain,
            double validFraction, int iterations, Status status,
            RotationEvidence rotationEvidence, PairUncertainty uncertainty) {
            this.transform = transform;
            this.residualBefore = residualBefore;
            this.residualAfter = residualAfter;
            this.logGain = logGain;
            this.validFraction = validFraction;
            this.iterations = iterations;
            this.status = status;
            this.rotationEvidence = rotationEvidence;
            this.uncertainty = uncertainty == null
                    ? PairUncertainty.unavailable(transform != null && transform.theta != 0.0 ? 3 : 2)
                    : uncertainty;
        }

        /** True when the transform may be used. A refused pair may not. */
        public boolean usable() {
            return status != Status.REFUSED_LOW_OVERLAP;
        }

        /** How much of the log-ratio residual was removed, as a fraction of what was there. */
        public double residualRemoved() {
            return residualBefore > 0 ? 1.0 - residualAfter / residualBefore : 0.0;
        }
    }

    /** Audit record attached to a fit selected by {@link RotationComparison#select(double)}. */
    public static final class RotationEvidence {
        public final double residualGain;
        public final double translationResidual;
        public final double rigidResidual;
        public final double proposedAngleRadians;
        public final boolean accepted;

        RotationEvidence(double residualGain, Fit translation, Fit rigid, boolean accepted) {
            this.residualGain = residualGain;
            this.translationResidual = translation == null
                    ? Double.NaN : translation.residualAfter;
            this.rigidResidual = rigid == null ? Double.NaN : rigid.residualAfter;
            this.proposedAngleRadians = rigid == null
                    ? Double.NaN : rigid.transform.theta;
            this.accepted = accepted;
        }

        /** Rehydrate an immutable benchmark record without rerunning either proposal. */
        RotationEvidence(double residualGain, double translationResidual,
                         double rigidResidual, double proposedAngleRadians,
                         boolean accepted) {
            this.residualGain = residualGain;
            this.translationResidual = translationResidual;
            this.rigidResidual = rigidResidual;
            this.proposedAngleRadians = proposedAngleRadians;
            this.accepted = accepted;
        }
    }

    /**
     * Side-by-side translation-only and rigid fits for one frame pair.
     *
     * <p>This is an opt-in seam for rotation confidence work. It does not alter
     * {@link #align(LogPlane[], LogPlane[], Options)}. A caller can keep the rigid answer only when
     * its full-frame residual improvement is large enough, otherwise it can retain the translation
     * and an exact zero angle.
     */
    public static final class RotationComparison {
        public final Fit translation;
        public final Fit rigid;
        /** Fractional reduction in full-frame residual from allowing rotation. */
        public final double residualGain;

        RotationComparison(Fit translation, Fit rigid) {
            this.translation = translation;
            this.rigid = rigid;
            if (translation == null || rigid == null || !rigid.usable()
                    || !Double.isFinite(translation.residualAfter)
                    || !Double.isFinite(rigid.residualAfter)) {
                residualGain = Double.NEGATIVE_INFINITY;
            } else if (translation.residualAfter > 0) {
                residualGain = 1.0 - rigid.residualAfter / translation.residualAfter;
            } else {
                residualGain = rigid.residualAfter <= translation.residualAfter ? 0.0
                        : Double.NEGATIVE_INFINITY;
            }
        }

        /** Keep rotation only when it produces at least the requested residual gain. */
        public Fit select(double minimumResidualGain) {
            if (!Double.isFinite(minimumResidualGain) || minimumResidualGain < 0) {
                throw new IllegalArgumentException(
                        "minimum rotation residual gain must be finite and >= 0");
            }
            boolean accepted = residualGain >= minimumResidualGain;
            Fit selected = accepted ? rigid : translation;
            RotationEvidence evidence = new RotationEvidence(
                    residualGain, translation, rigid, accepted);
            return new Fit(selected.transform, selected.residualBefore, selected.residualAfter,
                    selected.logGain, selected.validFraction, selected.iterations,
                    selected.status, evidence, selected.uncertainty);
        }
    }

    /**
     * Fit translation first, then test a coarse-to-fine local rigid refinement from that solution.
     *
     * <p>The method requires rigid options so accidentally calling it from a translation-only path
     * is visible. It is deliberately not used by the ordinary estimator until a development-tuned
     * confidence rule passes independent validation.
     */
    public static RotationComparison compareTranslationAndRotation(
            LogPlane[] a, LogPlane[] b, Options o) {
        validatePyramids(a, b);
        if (o == null || !o.fitRotation) {
            throw new IllegalArgumentException("rotation comparison requires fitRotation=true");
        }
        checkCancellation(o);
        Options translationOptions = o.copy();
        translationOptions.fitRotation = false;
        Fit translation = align(a, b, translationOptions);
        Fit rigid;
        if (translation.usable()) {
            Transform start = new Transform(
                    translation.transform.dx, translation.transform.dy, 0.0);
            rigid = alignFrom(a, b, o, start, null);
        } else {
            // A refused translation is not evidence against rotation. Preserve the existing global
            // rigid initializer as the recovery path rather than manufacturing a zero-angle answer.
            rigid = align(a, b, o);
        }
        return new RotationComparison(translation, rigid);
    }

    /**
     * Refine rotation from a solved translation after a bounded angular-basin search.
     *
     * <p>A Gauss-Newton step from exactly zero angle is sufficient for small rotations but cannot
     * cross the several-degree discontinuities in an intermittent-jump trajectory. This initializer
     * searches the complete allowed angle range at the coarsest level while moving translation only
     * inside a two-pixel neighbourhood of the already-solved basin. It therefore finds an angular
     * basin without repeating the global translation sweep, then hands that start to the established
     * masked coarse-to-fine refinement.
     */
    public static Fit alignRigidFromTranslation(
            LogPlane[] a, LogPlane[] b, Options o, Fit translation,
            boolean[][] solverSupport) {
        validatePyramids(a, b);
        checkCancellation(o);
        if (o == null || !o.fitRotation || translation == null || !translation.usable()) {
            throw new IllegalArgumentException(
                    "incremental rotation requires fitRotation=true and a usable translation");
        }
        if (solverSupport != null) {
            if (solverSupport.length != a.length) {
                throw new IllegalArgumentException("support has " + solverSupport.length
                        + " levels, expected " + a.length);
            }
            for (int level = 0; level < a.length; level++) {
                if (solverSupport[level] == null
                        || solverSupport[level].length != a[level].width * a[level].height) {
                    throw new IllegalArgumentException("support level " + level + " has "
                            + (solverSupport[level] == null ? "null" : solverSupport[level].length)
                            + " pixels, expected " + (a[level].width * a[level].height));
                }
            }
        }
        int top = a.length - 1;
        double scale = 1 << top;
        double maxShiftHere = o.maxShift / scale;
        double cx = (a[top].width - 1) / 2.0;
        double cy = (a[top].height - 1) / 2.0;
        Transform start = new Transform(translation.transform.dx / scale,
                translation.transform.dy / scale, 0.0);
        start = clampTransform(start, maxShiftHere, o);
        Scratch scratch = Scratch.forPlane(a[top], o);
        boolean[] support = solverSupport == null ? null : solverSupport[top];
        Sample incumbent = evaluate(a[top], b[top], start, cx, cy, o, scratch,
                0, 0, support);
        double best = incumbent.n >= o.minValidFraction * incumbent.nPossible
                ? incumbent.meanAbs : Double.POSITIVE_INFINITY;
        Transform winner = start;
        double centreX = Math.rint(start.dx);
        double centreY = Math.rint(start.dy);
        double halfDiagonal = Math.hypot(cx, cy);
        for (double theta : angularCandidates(o.maxRotation, halfDiagonal)) {
            checkCancellation(o);
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    double x = centreX + dx;
                    double y = centreY + dy;
                    if (Math.hypot(x, y) > maxShiftHere + 1e-9) continue;
                    Transform candidate = new Transform(x, y, theta);
                    Sample sample = evaluate(a[top], b[top], candidate, cx, cy, o, scratch,
                            0, 0, support);
                    if (sample.n < o.minValidFraction * sample.nPossible) continue;
                    if (sample.meanAbs < best) {
                        best = sample.meanAbs;
                        winner = candidate;
                    }
                }
            }
        }
        return alignFrom(a, b, o, winner.scaleTranslation(scale), solverSupport);
    }

    /**
     * Run the existing global rigid estimator, then compare it with a locally refined zero-angle fit.
     *
     * <p>This preserves the established rigid search exactly whenever rotation is accepted. The
     * comparison pass removes the proposed angle, keeps its translation as the starting basin, and
     * optimises translation alone without repeating the exhaustive coarse search. It is therefore the
     * lower-risk confidence path when translation-first initialisation is not accurate enough.
     */
    public static RotationComparison compareGlobalRotationToTranslation(
            LogPlane[] a, LogPlane[] b, Options o) {
        validatePyramids(a, b);
        if (o == null || !o.fitRotation) {
            throw new IllegalArgumentException("rotation comparison requires fitRotation=true");
        }
        checkCancellation(o);
        Fit rigid = align(a, b, o);
        return compareRotationToTranslation(a, b, o, rigid);
    }

    /** Compare an already-solved rigid fit with its locally refined zero-angle alternative. */
    public static RotationComparison compareRotationToTranslation(
            LogPlane[] a, LogPlane[] b, Options o, Fit rigid) {
        return compareRotationToTranslation(a, b, o, rigid, null);
    }

    /** The same comparison using an explicit source-coordinate solver support at every level. */
    public static RotationComparison compareRotationToTranslation(
            LogPlane[] a, LogPlane[] b, Options o, Fit rigid,
            boolean[][] solverSupport) {
        validatePyramids(a, b);
        if (o == null || !o.fitRotation || rigid == null) {
            throw new IllegalArgumentException(
                    "rotation comparison requires fitRotation=true and a rigid fit");
        }
        checkCancellation(o);
        Options translationOptions = o.copy();
        translationOptions.fitRotation = false;
        Transform start = new Transform(rigid.transform.dx, rigid.transform.dy, 0.0);
        Fit translation = alignFrom(a, b, translationOptions, start, solverSupport);
        return new RotationComparison(translation, rigid);
    }

    /**
     * Align {@code b} onto {@code a}. Both pyramids must come from {@link LogPlane#pyramid(int)} with
     * the same number of levels and the same dimensions.
     */
    public static Fit align(LogPlane[] a, LogPlane[] b, Options o) {
        validatePyramids(a, b);
        checkCancellation(o);
        int levels = a.length;
        double cx0 = (a[0].width - 1) / 2.0;
        double cy0 = (a[0].height - 1) / 2.0;

        // ---- coarsest level: exhaustive integer sweep to choose the basin --------------------
        int top = levels - 1;
        double topScale = 1 << top;
        int radius = (int) Math.ceil(o.maxShift / topScale);
        Scratch coarse = Scratch.forPlane(a[top], o);
        Transform p = coarseSearch(a[top], b[top], radius, o.maxShift / topScale,
                cx0 / topScale, cy0 / topScale, o, coarse);

        // ---- descend, refining by Gauss–Newton at each level --------------------------------
        int iterations = 0;
        Status status = Status.OK;
        for (int l = top; l >= 0; l--) {
            checkCancellation(o);
            double scale = 1 << l;
            Scratch s = l == top ? coarse : Scratch.forPlane(a[l], o);
            Refined ref = refine(a[l], b[l], p, cx0 / scale, cy0 / scale, o.maxShift / scale, o, s);
            p = ref.p;
            iterations = ref.iterations;
            if (ref.bailed) status = Status.REFUSED_LOW_OVERLAP;
            else if (!ref.converged) status = Status.NOT_CONVERGED;
            else status = Status.OK;
            if (l > 0) {
                p = clampTransform(p.scaleTranslation(2.0), o.maxShift / (scale / 2), o);
            }
        }
        // Reported from the solution, not from whether a particular trial step happened to be
        // clamped: a run can arrive at the bound and then find no downhill step, which is a
        // converged-looking outcome that must still warn the true shift may be larger.
        status = boundStatus(p, o, status);

        // ---- report at full resolution, over every valid pixel ------------------------------
        return report(a, b, o, p, iterations, status);
    }

    /**
     * Turn a solved transform into a {@link Fit}, measuring the quality numbers the same way for
     * every estimator.
     *
     * <p>Shared with {@link AreaCorrelation} on purpose. {@code residualBefore},
     * {@code residualAfter}, {@code logGain} and {@code validFraction} describe <em>the frame pair at
     * this transform</em>, not the machinery that found the transform, so they must not depend on
     * which estimator was used or the two arms would be reporting different quantities under one
     * column heading. The evaluation uses gradient threshold 0 and robust threshold 0 for the same
     * reason it does inside {@link #align}: the numbers describe the whole frame rather than the
     * solver's chosen subset, so a gradient-weighted run, an all-pixels run and an area-correlation
     * run stay comparable.
     *
     * <p>Refusing is the correct answer when too little of the frame is usable. A pair with almost no
     * overlap can always be given a transform, and it will be wrong; propagating it into the
     * cumulative chain corrupts every later frame. The caller substitutes the identity and records
     * that it did.
     */
    static Fit report(LogPlane[] a, LogPlane[] b, Options o, Transform p, int iterations,
                      Status status) {
        return report(a, b, o, p, iterations, status, false);
    }

    /** Shared reporting with area-correlation-specific local uncertainty. */
    static Fit reportArea(LogPlane[] a, LogPlane[] b, Options o, Transform p, int iterations,
                          Status status) {
        return report(a, b, o, p, iterations, status, true);
    }

    private static Fit report(LogPlane[] a, LogPlane[] b, Options o, Transform p, int iterations,
                              Status status, boolean areaCorrelation) {
        double cx0 = (a[0].width - 1) / 2.0;
        double cy0 = (a[0].height - 1) / 2.0;
        Scratch fine = Scratch.forPlane(a[0], o);
        Sample zero = evaluate(a[0], b[0], Transform.IDENTITY, cx0, cy0, o, fine, 0, 0);
        Sample fin = evaluate(a[0], b[0], p, cx0, cy0, o, fine, 0, 0);
        double validFrac = fin.nPossible > 0 ? (double) fin.n / fin.nPossible : 0;

        if (status == Status.REFUSED_LOW_OVERLAP || validFrac < o.minValidFraction) {
            return new Fit(Transform.IDENTITY, zero.meanAbs, zero.meanAbs, 0.0,
                    validFrac, iterations, Status.REFUSED_LOW_OVERLAP, null,
                    PairUncertainty.unavailable(o.fitRotation ? 3 : 2));
        }
        PairUncertainty uncertainty = areaCorrelation
                ? AreaCorrelation.uncertainty(a, b, o, p)
                : PairUncertainty.estimateLogRatio(a[0], b[0], o, p);
        return new Fit(p, zero.meanAbs, fin.meanAbs, fin.gain, validFrac, iterations, status,
                null, uncertainty);
    }

    /**
     * Refine a provisional pair transform without repeating the exhaustive coarse search.
     *
     * <p>{@code solverSupport}, when non-null, contains one immutable boolean mask per pyramid level.
     * A true source pixel may vote in the iterative fit; a false one is deliberately absent from the
     * solver and therefore is not counted as missing geometric overlap. The mask does not alter the
     * gain-independent quality numbers reported by {@link Fit}, which remain measured over every
     * geometrically valid pixel. This separation is important for two-pass methods: choosing 25% of
     * the image on purpose must not look like losing 75% of the image at a boundary.
     *
     * <p>The ordinary {@link #align(LogPlane[], LogPlane[], Options)} path does not call this method,
     * so adding a second-pass experiment cannot change a one-pass registration unless a caller opts
     * into it explicitly.
     *
     * @param start         provisional full-resolution transform
     * @param solverSupport source-coordinate masks, finest first; null uses every valid source pixel
     */
    public static Fit alignFrom(LogPlane[] a, LogPlane[] b, Options o, Transform start,
                                boolean[][] solverSupport) {
        validatePyramids(a, b);
        checkCancellation(o);
        if (start == null) throw new IllegalArgumentException("start transform must not be null");
        if (solverSupport != null) {
            if (solverSupport.length != a.length) {
                throw new IllegalArgumentException("support has " + solverSupport.length
                        + " levels, expected " + a.length);
            }
            for (int l = 0; l < a.length; l++) {
                if (solverSupport[l] == null
                        || solverSupport[l].length != a[l].width * a[l].height) {
                    throw new IllegalArgumentException("support level " + l + " has "
                            + (solverSupport[l] == null ? "null" : solverSupport[l].length)
                            + " pixels, expected " + (a[l].width * a[l].height));
                }
            }
        }

        int levels = a.length;
        double cx0 = (a[0].width - 1) / 2.0;
        double cy0 = (a[0].height - 1) / 2.0;
        int top = levels - 1;
        double topScale = 1 << top;
        Transform p = clampTransform(start.scaleTranslation(1.0 / topScale),
                o.maxShift / topScale, o);

        int iterations = 0;
        Status status = Status.OK;
        for (int l = top; l >= 0; l--) {
            checkCancellation(o);
            double scale = 1 << l;
            Scratch s = Scratch.forPlane(a[l], o);
            boolean[] support = solverSupport == null ? null : solverSupport[l];
            Refined ref = refine(a[l], b[l], p, cx0 / scale, cy0 / scale,
                    o.maxShift / scale, o, s, support);
            p = ref.p;
            iterations = ref.iterations;
            if (ref.bailed) status = Status.REFUSED_LOW_OVERLAP;
            else if (!ref.converged) status = Status.NOT_CONVERGED;
            else status = Status.OK;
            if (l > 0) {
                p = clampTransform(p.scaleTranslation(2.0), o.maxShift / (scale / 2), o);
            }
        }
        status = boundStatus(p, o, status);

        // Selection affects only the fit. Keep quality control directly comparable with one-pass
        // registration by evaluating the complete geometrically valid frame here.
        return report(a, b, o, p, iterations, status);
    }

    /**
     * Exhaustively choose the translation basin while retaining a caller-supplied angle, then
     * refine only dx/dy down the pyramid. Unlike {@link #alignFrom}, this does not inherit the
     * translation basin that may have been corrupted by unmodelled rotation.
     */
    public static Fit alignTranslationAtFixedRotation(
            LogPlane[] a, LogPlane[] b, Options options, Transform angle,
            boolean[][] solverSupport) {
        validatePyramids(a, b);
        checkCancellation(options);
        if (options == null || angle == null) {
            throw new IllegalArgumentException(
                    "global fixed-angle translation requires options and an angle");
        }
        if (solverSupport != null) {
            if (solverSupport.length != a.length) {
                throw new IllegalArgumentException("support has " + solverSupport.length
                        + " levels, expected " + a.length);
            }
            for (int level = 0; level < a.length; level++) {
                if (solverSupport[level] == null
                        || solverSupport[level].length != a[level].width * a[level].height) {
                    throw new IllegalArgumentException(
                            "support does not match pyramid level " + level);
                }
            }
        }
        Options o = options.copy();
        o.fitRotation = false;
        int top = a.length - 1;
        double topScale = 1 << top;
        double cx0 = (a[0].width - 1) / 2.0;
        double cy0 = (a[0].height - 1) / 2.0;
        int radius = (int) Math.ceil(o.maxShift / topScale);
        Scratch coarse = Scratch.forPlane(a[top], o);
        Transform p = coarseFixedAngleTranslationSearch(
                a[top], b[top], radius, o.maxShift / topScale,
                cx0 / topScale, cy0 / topScale, angle.theta, o, coarse,
                solverSupport == null ? null : solverSupport[top]);
        int iterations = 0;
        Status status = Status.OK;
        for (int level = top; level >= 0; level--) {
            checkCancellation(o);
            double scale = 1 << level;
            Scratch scratch = level == top ? coarse : Scratch.forPlane(a[level], o);
            Refined refined = refine(a[level], b[level], p,
                    cx0 / scale, cy0 / scale, o.maxShift / scale, o, scratch,
                    solverSupport == null ? null : solverSupport[level]);
            p = refined.p;
            iterations = refined.iterations;
            if (refined.bailed) status = Status.REFUSED_LOW_OVERLAP;
            else if (!refined.converged) status = Status.NOT_CONVERGED;
            else status = Status.OK;
            if (level > 0) {
                p = clampTransform(p.scaleTranslation(2.0),
                        o.maxShift / (scale / 2), o);
            }
        }
        status = boundStatus(p, o, status);
        return report(a, b, o, p, iterations, status);
    }

    static void validatePyramids(LogPlane[] a, LogPlane[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            throw new IllegalArgumentException("pyramids must contain at least one level");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("pyramids have " + a.length + " and "
                    + b.length + " levels");
        }
        for (int l = 0; l < a.length; l++) {
            if (a[l].width != b[l].width || a[l].height != b[l].height) {
                throw new IllegalArgumentException("frames differ at level " + l + ": "
                        + a[l].width + "x" + a[l].height + " vs "
                        + b[l].width + "x" + b[l].height);
            }
        }
    }

    // ------------------------------------------------------------------------------------ //

    private static Transform coarseSearch(LogPlane a, LogPlane b, int radius, double maxShiftHere,
                                          double cx, double cy, Options o, Scratch s) {
        // L1 with the gain as the median. Directly comparable between candidate shifts, whereas a
        // robustly scaled cost is measured against a threshold that itself moves with the shift.
        // L1 is also already the sparse-residual criterion, so the basin it picks is the one the
        // refinement wants. No gradient filtering here: at the coarsest level there may not be
        // enough pixels left to spare.
        //
        // Zero is the incumbent, evaluated first, and a candidate must beat it strictly. That is not
        // a micro-optimisation: on a frame with no texture — blank, saturated, or all background —
        // every candidate scores an identical zero, and a sweep that merely takes the first improvement
        // it scans returns the corner of its own search box. Measured before this was fixed: a flat
        // 192x192 pair produced a confident 45 px shift.
        if (!o.fitRotation || !(o.maxRotation > 0) || !Double.isFinite(o.maxRotation)) {
            return coarseTranslationSearch(a, b, radius, maxShiftHere, cx, cy, o, s);
        }

        double best = Double.POSITIVE_INFINITY;
        Sample zero = evaluate(a, b, Transform.IDENTITY, cx, cy, o, s, 0, 0);
        if (zero.n >= o.minValidFraction * zero.nPossible) best = zero.meanAbs;
        int bestDy = 0;
        int bestDx = 0;
        double bestTheta = 0;
        double[] angles = angularCandidates(o.maxRotation, Math.hypot(cx, cy));
        for (double theta : angles) {
            checkCancellation(o);
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (theta == 0.0 && dx == 0 && dy == 0) continue;
                    // The radius is a rounded-up integer, so it can reach past the requested bound;
                    // a sweep that ignored the bound could return a shift the user forbade, then
                    // have it doubled at every level on the way down.
                    if (Math.hypot(dx, dy) > maxShiftHere + 1e-9) continue;
                    Sample e = evaluate(a, b, new Transform(dx, dy, theta),
                            cx, cy, o, s, 0, 0);
                    if (e.n < o.minValidFraction * e.nPossible) continue;
                    if (e.meanAbs < best) {
                        best = e.meanAbs;
                        bestDy = dy;
                        bestDx = dx;
                        bestTheta = theta;
                    }
                }
            }
        }
        return new Transform(bestDx, bestDy, bestTheta);
    }

    /** Original translation-only initializer, kept separate so the disabled path is unchanged. */
    private static Transform coarseTranslationSearch(LogPlane a, LogPlane b, int radius,
                                                     double maxShiftHere, double cx, double cy,
                                                     Options o, Scratch s) {
        double best = Double.POSITIVE_INFINITY;
        Sample zero = evaluate(a, b, Transform.IDENTITY, cx, cy, o, s, 0, 0);
        if (zero.n >= o.minValidFraction * zero.nPossible) best = zero.meanAbs;
        int bestDy = 0;
        int bestDx = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            checkCancellation(o);
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue;
                if (Math.hypot(dx, dy) > maxShiftHere + 1e-9) continue;
                Sample e = evaluate(a, b, Transform.translation(dx, dy), cx, cy, o, s, 0, 0);
                if (e.n < o.minValidFraction * e.nPossible) continue;
                if (e.meanAbs < best) {
                    best = e.meanAbs;
                    bestDy = dy;
                    bestDx = dx;
                }
            }
        }
        return Transform.translation(bestDx, bestDy);
    }

    private static Transform coarseFixedAngleTranslationSearch(
            LogPlane a, LogPlane b, int radius, double maxShiftHere,
            double cx, double cy, double theta, Options o, Scratch scratch,
            boolean[] solverSupport) {
        double best = Double.POSITIVE_INFINITY;
        int bestDy = 0;
        int bestDx = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            checkCancellation(o);
            for (int dx = -radius; dx <= radius; dx++) {
                if (Math.hypot(dx, dy) > maxShiftHere + 1e-9) continue;
                Sample evaluated = evaluate(a, b, new Transform(dx, dy, theta),
                        cx, cy, o, scratch, 0, 0, solverSupport);
                if (evaluated.n < o.minValidFraction * evaluated.nPossible) continue;
                if (evaluated.meanAbs < best) {
                    best = evaluated.meanAbs;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        return new Transform(bestDx, bestDy, theta);
    }

    /** Zero first, then symmetric pairs out to both exact endpoints. */
    static double[] angularCandidates(double maxRotation, double halfDiagonal) {
        if (!(maxRotation > 0) || !Double.isFinite(maxRotation)) return new double[]{0};
        double wantedStep = 1.0 / Math.max(1.0, halfDiagonal);
        int intervals = Math.max(1, (int) Math.ceil(maxRotation / wantedStep));
        double step = maxRotation / intervals;
        double[] out = new double[1 + 2 * intervals];
        out[0] = 0;
        for (int i = 1; i <= intervals; i++) {
            out[2 * i - 1] = -i * step;
            out[2 * i] = i * step;
        }
        return out;
    }

    private static final class Refined {
        final Transform p;
        final int iterations;
        final boolean converged;
        final boolean bailed;
        final boolean atBound;

        Refined(Transform p, int iterations, boolean converged, boolean bailed, boolean atBound) {
            this.p = p;
            this.iterations = iterations;
            this.converged = converged;
            this.bailed = bailed;
            this.atBound = atBound;
        }
    }

    private static Refined refine(LogPlane a, LogPlane b, Transform start,
                                  double cx, double cy, double maxShift, Options o, Scratch s) {
        return refine(a, b, start, cx, cy, maxShift, o, s, null);
    }

    private static Refined refine(LogPlane a, LogPlane b, Transform start,
                                  double cx, double cy, double maxShift, Options o, Scratch s,
                                  boolean[] solverSupport) {
        int dof = o.fitRotation ? 3 : 2;
        double gradThreshold = o.support == PixelSupport.GRADIENT
                ? gradientThreshold(a, s, o)
                : 0;
        boolean hardMutualNoise = o.support == PixelSupport.MUTUAL_NOISE_GRADIENT;
        double sourceNoiseThreshold = hardMutualNoise
                ? o.gradientFraction * a.rawNoiseSigma() : 0;
        double targetNoiseThreshold = hardMutualNoise
                ? o.gradientFraction * b.rawNoiseSigma() : 0;
        double[] out = new double[2];

        Transform p = start;
        int it = 0;
        boolean converged = false;
        boolean bailed = false;
        boolean atBound = false;

        for (it = 0; it < o.maxIterations; it++) {
            checkCancellation(o);
            double sinT = Math.sin(p.theta);
            double cosT = Math.cos(p.theta);
            int n = 0;
            int possible = 0;
            for (int y = 0; y < a.height; y += s.stride) {
                int row = y * a.width;
                for (int x = 0; x < a.width; x += s.stride) {
                    int i = row + x;
                    if (solverSupport != null && !solverSupport[i]) continue;
                    possible++;
                    if (!a.valid[i]) continue;
                    if (gradThreshold > 0 && a.gradMagnitude(i) < gradThreshold) continue;
                    if (hardMutualNoise && sourceNoiseThreshold > 0
                            && a.rawGradient(i) < sourceNoiseThreshold) {
                        continue;
                    }
                    p.apply(x, y, cx, cy, out);
                    float bv = b.sample(out[0], out[1]);
                    if (Float.isNaN(bv)) continue;
                    float gxb = b.sampleGx(out[0], out[1]);
                    float gyb = b.sampleGy(out[0], out[1]);
                    if (Float.isNaN(gxb) || Float.isNaN(gyb)) continue;
                    if (hardMutualNoise && targetNoiseThreshold > 0) {
                        double targetGradient = (Math.abs(gxb) + Math.abs(gyb))
                                * fastExp2(bv) * LN2;
                        if (targetGradient < targetNoiseThreshold) continue;
                    }
                    s.d[n] = bv - a.v[i];
                    s.jx[n] = gxb;
                    s.jy[n] = gyb;
                    if (s.jt != null) {
                        double u = x - cx;
                        double v = y - cy;
                        s.jt[n] = gxb * (-u * sinT - v * cosT) + gyb * (u * cosT - v * sinT);
                    }
                    n++;
                }
            }
            if (n < o.minValidFraction * possible || n < dof + 1) {
                bailed = true;
                break;
            }

            System.arraycopy(s.d, 0, s.sel, 0, n);
            double c = o.profileGain ? RobustNorm.select(s.sel, n, n / 2) : 0.0;
            for (int i = 0; i < n; i++) s.sel[i] = s.d[i] - c;
            double residualScale = RobustNorm.scale(s.sel, n, o.scaleFloor);
            double k = o.norm.threshold(residualScale);
            double h00 = 0, h01 = 0, h02 = 0, h11 = 0, h12 = 0, h22 = 0;
            double g0 = 0, g1 = 0, g2 = 0;
            double cost0 = 0;
            int selected = 0;
            for (int i = 0; i < n; i++) {
                selected++;
                double r = s.d[i] - c;
                double w = o.norm.weight(r, k);
                cost0 += o.norm.rho(r, k);
                if (w == 0) continue;
                double wx = w * s.jx[i];
                double wy = w * s.jy[i];
                h00 += wx * s.jx[i];
                h01 += wx * s.jy[i];
                h11 += wy * s.jy[i];
                g0 += wx * r;
                g1 += wy * r;
                if (s.jt != null) {
                    double wt = w * s.jt[i];
                    h02 += wx * s.jt[i];
                    h12 += wy * s.jt[i];
                    h22 += wt * s.jt[i];
                    g2 += wt * r;
                }
            }
            if (selected < dof + 1) break;
            cost0 /= selected;

            double[] step = s.jt == null
                    ? solve2(h00, h01, h11, -g0, -g1)
                    : solve3(h00, h01, h02, h11, h12, h22, -g0, -g1, -g2);
            if (step == null) {
                // No gradient information at all: a featureless pair. Not a refusal — the transform
                // found so far is the best available — but not a convergence either.
                break;
            }

            // Backtracking on the same objective the iteration used: same norm, same threshold, same
            // gain estimator, same pixel set. That equality is the whole point of defect 1's fix.
            Transform accepted = null;
            boolean acceptedAtBound = false;
            double f = 1.0;
            for (int t = 0; t < 6; t++, f *= 0.5) {
                Transform raw = new Transform(p.dx + f * step[0], p.dy + f * step[1],
                        s.jt == null ? p.theta : p.theta + f * step[2]);
                Transform trial = clampTransform(raw, maxShift, o);
                boolean clamped = trial != raw;
                Sample e = evaluate(a, b, trial, cx, cy, o, s, k, gradThreshold,
                        solverSupport);
                if (e.n >= o.minValidFraction * e.nPossible && e.cost < cost0) {
                    accepted = trial;
                    acceptedAtBound = clamped;
                    break;
                }
            }
            if (accepted == null) {
                converged = true;      // no downhill step exists at this scale
                break;
            }
            double moved = Math.hypot(accepted.dx - p.dx, accepted.dy - p.dy)
                    + Math.abs(accepted.theta - p.theta) * Math.max(cx, cy);
            p = accepted;
            atBound = acceptedAtBound;
            if (moved < o.convergence) {
                converged = true;
                break;
            }
        }
        return new Refined(p, it, converged, bailed, atBound);
    }

    /** Median gradient magnitude over valid sampled pixels, times {@code gradientFraction}. */
    private static double gradientThreshold(LogPlane a, Scratch s, Options o) {
        int n = 0;
        for (int y = 0; y < a.height; y += s.stride) {
            int row = y * a.width;
            for (int x = 0; x < a.width; x += s.stride) {
                int i = row + x;
                if (a.valid[i]) s.sel[n++] = a.gradMagnitude(i);
            }
        }
        if (n == 0) return 0;
        double median = RobustNorm.select(s.sel, n, n / 2);
        return median * o.gradientFraction;
    }

    /** Clamp translation and, when fitted, rotation. Returns the argument when already in bounds. */
    private static Transform clampTransform(Transform t, double maxShift, Options o) {
        double m = t.magnitude();
        boolean shiftClamped = m > maxShift && m != 0;
        double f = shiftClamped ? maxShift / m : 1.0;
        double theta = t.theta;
        if (o.fitRotation && Double.isFinite(o.maxRotation) && o.maxRotation >= 0) {
            theta = Math.max(-o.maxRotation, Math.min(o.maxRotation, theta));
        }
        if (!shiftClamped && theta == t.theta) return t;
        return new Transform(t.dx * f, t.dy * f, theta);
    }

    private static Status boundStatus(Transform p, Options o, Status status) {
        if (status != Status.OK) return status;
        boolean shift = p.magnitude() >= o.maxShift * (1 - 1e-9);
        boolean rotation = o.fitRotation && o.maxRotation > 0
                && Math.abs(p.theta) >= o.maxRotation * (1 - 1e-9);
        if (shift && rotation) return Status.AT_SHIFT_AND_ROTATION_BOUND;
        if (shift) return Status.AT_SHIFT_BOUND;
        if (rotation) return Status.AT_ROTATION_BOUND;
        return status;
    }

    static void checkCancellation(Options o) {
        if (o != null && o.cancellation != null && o.cancellation.cancelled()) {
            throw new CancellationException("cancelled");
        }
    }

    // ------------------------------------------------------------------------------------ //

    /** Reusable buffers for one pyramid level. Sized once; never reallocated per evaluation. */
    private static final class Scratch {
        final double[] d;
        final double[] jx;
        final double[] jy;
        final double[] jt;
        final double[] res;
        final double[] sel;
        final int stride;
        final int cap;

        private Scratch(int cap, int stride, boolean rotation) {
            this.cap = cap;
            this.stride = stride;
            this.d = new double[cap];
            this.jx = new double[cap];
            this.jy = new double[cap];
            this.jt = rotation ? new double[cap] : null;
            this.res = new double[cap];
            this.sel = new double[cap];
        }

        static Scratch forPlane(LogPlane a, Options o) {
            int stride = strideFor(a.width, a.height, o.maxSamples);
            return new Scratch(countSamples(a.width, a.height, stride), stride, o.fitRotation);
        }
    }

    /** One evaluation of the criterion. */
    private static final class Sample {
        /** Pixels that produced a residual. */
        final int n;
        /** Pixels that were visited — the denominator the validity check must use. */
        final int nPossible;
        final double gain;
        final double meanAbs;
        final double cost;

        Sample(int n, int nPossible, double gain, double meanAbs, double cost) {
            this.n = n;
            this.nPossible = nPossible;
            this.gain = gain;
            this.meanAbs = meanAbs;
            this.cost = cost;
        }
    }

    /**
     * Evaluate at {@code p}, reusing {@code s}'s buffers and allocating nothing.
     *
     * @param k             robust threshold; {@code <= 0} reports mean absolute residual as the cost
     * @param gradThreshold source-gradient cut-off; {@code <= 0} uses every valid pixel
     */
    private static Sample evaluate(LogPlane a, LogPlane b, Transform p, double cx, double cy,
                                   Options o, Scratch s, double k, double gradThreshold) {
        return evaluate(a, b, p, cx, cy, o, s, k, gradThreshold, null);
    }

    private static Sample evaluate(LogPlane a, LogPlane b, Transform p, double cx, double cy,
                                   Options o, Scratch s, double k, double gradThreshold,
                                   boolean[] solverSupport) {
        double[] out = new double[2];
        boolean hardMutualNoise = k > 0 && o.support == PixelSupport.MUTUAL_NOISE_GRADIENT;
        double sourceNoiseThreshold = hardMutualNoise
                ? o.gradientFraction * a.rawNoiseSigma() : 0;
        double targetNoiseThreshold = hardMutualNoise
                ? o.gradientFraction * b.rawNoiseSigma() : 0;
        int n = 0;
        int possible = 0;
        for (int y = 0; y < a.height; y += s.stride) {
            int row = y * a.width;
            for (int x = 0; x < a.width; x += s.stride) {
                int i = row + x;
                if (solverSupport != null && !solverSupport[i]) continue;
                possible++;
                if (!a.valid[i]) continue;
                if (gradThreshold > 0 && a.gradMagnitude(i) < gradThreshold) continue;
                if (hardMutualNoise && sourceNoiseThreshold > 0
                        && a.rawGradient(i) < sourceNoiseThreshold) continue;
                p.apply(x, y, cx, cy, out);
                float bv = b.sample(out[0], out[1]);
                if (Float.isNaN(bv)) continue;
                if (hardMutualNoise && targetNoiseThreshold > 0) {
                    float gx = b.sampleGx(out[0], out[1]);
                    float gy = b.sampleGy(out[0], out[1]);
                    if (Float.isNaN(gx) || Float.isNaN(gy)) continue;
                    double targetGradient = (Math.abs(gx) + Math.abs(gy))
                            * fastExp2(bv) * LN2;
                    if (targetGradient < targetNoiseThreshold) continue;
                }
                s.res[n++] = bv - a.v[i];
            }
        }
        if (n == 0) {
            return new Sample(0, possible, 0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }

        System.arraycopy(s.res, 0, s.sel, 0, n);
        double c = o.profileGain ? RobustNorm.select(s.sel, n, n / 2) : 0.0;

        double sumAbs = 0;
        double cost = 0;
        for (int i = 0; i < n; i++) {
            double r = s.res[i] - c;
            sumAbs += Math.abs(r);
            if (k > 0) {
                cost += o.norm.rho(r, k);
            }
        }
        double meanAbs = sumAbs / n;
        return new Sample(n, possible, c, meanAbs,
                k > 0 ? cost / n : meanAbs);
    }

    static double fastExp2(double value) {
        double position = (value - EXP2_MIN) * EXP2_STEPS_PER_UNIT;
        int lower = (int) position;
        if (lower < 0 || lower + 1 >= EXP2.length) return Math.pow(2.0, value);
        double fraction = position - lower;
        return EXP2[lower] + fraction * (EXP2[lower + 1] - EXP2[lower]);
    }

    private static double[] exp2Table() {
        int size = (48 * EXP2_STEPS_PER_UNIT) + 1;
        double[] table = new double[size];
        for (int i = 0; i < size; i++) {
            table[i] = Math.pow(2.0, EXP2_MIN + i / (double) EXP2_STEPS_PER_UNIT);
        }
        return table;
    }

    static int strideFor(int w, int h, int maxSamples) {
        if (maxSamples <= 0) return 1;
        long px = (long) w * h;
        if (px <= maxSamples) return 1;
        return Math.max(1, (int) Math.ceil(Math.sqrt((double) px / maxSamples)));
    }

    static int countSamples(int w, int h, int stride) {
        return ((w + stride - 1) / stride) * ((h + stride - 1) / stride);
    }

    /** 2x2 symmetric solve. Null when singular. */
    private static double[] solve2(double a, double b, double d, double r0, double r1) {
        double det = a * d - b * b;
        if (!(Math.abs(det) > 1e-12 * (Math.abs(a * d) + b * b + 1e-30))) return null;
        return new double[]{(d * r0 - b * r1) / det, (a * r1 - b * r0) / det};
    }

    /** 3x3 symmetric solve by Cholesky. Null when not positive definite. */
    private static double[] solve3(double a00, double a01, double a02,
                                   double a11, double a12, double a22,
                                   double r0, double r1, double r2) {
        if (!(a00 > 1e-20)) return null;
        double l00 = Math.sqrt(a00);
        double l10 = a01 / l00;
        double l20 = a02 / l00;
        double t11 = a11 - l10 * l10;
        if (!(t11 > 1e-20)) return null;
        double l11 = Math.sqrt(t11);
        double l21 = (a12 - l20 * l10) / l11;
        double t22 = a22 - l20 * l20 - l21 * l21;
        if (!(t22 > 1e-20)) return null;
        double l22 = Math.sqrt(t22);

        double y0 = r0 / l00;
        double y1 = (r1 - l10 * y0) / l11;
        double y2 = (r2 - l20 * y0 - l21 * y1) / l22;
        double x2 = y2 / l22;
        double x1 = (y1 - l21 * x2) / l11;
        double x0 = (y0 - l10 * x1 - l20 * x2) / l00;
        return new double[]{x0, x1, x2};
    }
}
