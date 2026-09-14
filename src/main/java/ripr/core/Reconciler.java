/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Turns pairwise alignments into one cumulative transform per frame.
 *
 * <p>This is where the reference strategy lives, and the choice matters more than it looks.
 *
 * <p><b>Chaining lag-1 steps is the obvious method and it has a specific, measurable failure.</b>
 * Each transition's estimate carries a small bias; cumulating a hundred of them integrates it. On one
 * 101-frame recording in the microglia pipeline the lag-1 chain left <em>more</em> net drift than doing
 * nothing at all — 4.61 px became 6.32 px — while its per-transition residual looked healthy at
 * 0.52 px, because a per-transition measure is structurally blind to a slow common bias. Anything
 * reading a fixed pixel's trajectory across many frames depends on the cumulative number, not the
 * per-transition one.
 *
 * <p><b>Registering everything to one reference frame fails worse, in the opposite direction.</b> Over
 * a long recording the field changes enough that late frames no longer resemble the first: measured on
 * the same data, all-to-frame-0 gave 2.598 px against 1.788 px for no correction. Offered because it is
 * what users expect and what most plugins do, but it is the wrong default for anything long.
 *
 * <p><b>The fix is to over-determine the problem and solve it globally.</b> Measure displacements at
 * several lags — 1, 2, 4, 8, 16 — and treat each as an observation {@code u[t+k] - u[t] = d}, then
 * solve the whole over-determined system at once so that a bias in the short lags is out-voted rather
 * than compounded. This is standard practice under other names: bundle adjustment for camera poses,
 * frame-motion correction for cryo-EM, and specifically <b>redundant cross-correlation</b> (Wang et
 * al., <i>Opt Express</i> 22:15982, 2014), which ships in Picasso and ThunderSTORM. <b>No novelty is
 * claimed for it here</b> — what is new is that a Fiji user can select it.
 *
 * <p>Long lags cost nothing in robustness: each pair alignment is bounded by {@code maxShift} and
 * refused when the overlap is too small, so a pair that has genuinely moved apart drops out rather
 * than misleading the fit.
 *
 * <p><b>Rotation and the parameter-space caveat.</b> The least-squares system is linear in its
 * unknowns, so the multi-lag solve necessarily works in parameter space, where composing two
 * transforms is {@link Transform#plus} rather than the exact {@link Transform#then}. The two differ by
 * a rotation applied to the earlier translation, so the solve is exact in the angle and first-order in
 * the translation, with an error of order {@code |t| * theta} — under a hundredth of a pixel for the
 * fractions of a degree that stage shake produces. The chained and fixed strategies use exact
 * composition and have no such caveat. v0.1.0 fits translation only, which makes the point moot; it is
 * recorded so that enabling rotation later does not silently change what the solve means.
 */
public final class Reconciler {

    /** Diagonal loading, as a fraction of the mean diagonal, to keep the normal equations SPD. */
    private static final double RIDGE = 1e-9;

    private Reconciler() {
    }

    /** Which frames are compared against which. */
    public enum Reference {
        /** Each frame to the one before, cumulated. The robust default. */
        CONSECUTIVE,
        /** Displacements at several lags, reconciled by least squares. Redundant cross-correlation. */
        MULTILAG,
        /** Every frame to one chosen frame. Expected, and wrong for long recordings. */
        FIXED,
        /** Each frame to a running mean of those already registered. Sequential by construction. */
        ROLLING
    }

    /** How redundant frame-pair constraints influence the global trajectory. */
    public enum Weighting {
        /** Bit-compatible historical solve. */
        EQUAL,
        /** Full estimator information matrices. */
        UNCERTAINTY,
        /** Equal pixel-equivalent information plus graph-consistency factors. */
        ROBUST,
        /** Estimator information plus graph-consistency factors. */
        COMBINED;

        public boolean usesUncertainty() {
            return this == UNCERTAINTY || this == COMBINED;
        }

        public boolean usesRobustFactors() {
            return this == ROBUST || this == COMBINED;
        }
    }

    /** Recording-level scalar used to put estimator information matrices on a common scale. */
    public enum InformationNormalizer {
        MEDIAN,
        TRIMMED_MEAN_25
    }

    /** Internal reconciliation configuration; production registration defaults to {@link Weighting#EQUAL}. */
    public static final class Options {
        public Weighting weighting = Weighting.EQUAL;
        public int dimensions = 2;
        public double rotationRadiusPixels = 1.0;
        public double minimumRobustFactor = 0.05;
        public int maximumRobustIterations = 8;
        public double robustLossConstant = 1.345;
        public InformationNormalizer informationNormalizer = InformationNormalizer.MEDIAN;
        public double factorTolerance = 1e-4;
        public double trajectoryTolerance = 1e-5;

        public Options() {
        }

        public Options(Weighting weighting, int dimensions, double rotationRadiusPixels) {
            this.weighting = weighting == null ? Weighting.EQUAL : weighting;
            this.dimensions = dimensions;
            this.rotationRadiusPixels = rotationRadiusPixels;
        }

        Options copy() {
            Options out = new Options(weighting, dimensions, rotationRadiusPixels);
            out.minimumRobustFactor = minimumRobustFactor;
            out.maximumRobustIterations = maximumRobustIterations;
            out.robustLossConstant = robustLossConstant;
            out.informationNormalizer = informationNormalizer;
            out.factorTolerance = factorTolerance;
            out.trajectoryTolerance = trajectoryTolerance;
            return out;
        }
    }

    /** One measured displacement between two frames. */
    public static final class Observation {
        public final int from;
        public final int to;
        public final Transform displacement;
        public final PairUncertainty uncertainty;
        /** Stable pair-plan identity; -1 when the caller has no external plan index. */
        public final int planIndex;

        public Observation(int from, int to, Transform displacement) {
            this(from, to, displacement, PairUncertainty.unavailable(
                    displacement != null && displacement.theta != 0.0 ? 3 : 2), -1);
        }

        public Observation(int from, int to, Transform displacement,
                           PairUncertainty uncertainty) {
            this(from, to, displacement, uncertainty, -1);
        }

        public Observation(int from, int to, Transform displacement,
                           PairUncertainty uncertainty, int planIndex) {
            if (from == to) {
                throw new IllegalArgumentException("observation from == to == " + from);
            }
            this.from = from;
            this.to = to;
            this.displacement = displacement;
            this.uncertainty = uncertainty == null
                    ? PairUncertainty.unavailable(
                            displacement != null && displacement.theta != 0.0 ? 3 : 2)
                    : uncertainty;
            this.planIndex = planIndex;
        }

        public int lag() {
            return Math.abs(to - from);
        }
    }

    /** Cumulative transforms plus how well each frame was constrained. */
    public static final class Solution {
        /** Per-frame transform relative to the reference frame. */
        public final Transform[] cumulative;
        /** How many observations touched each frame. Zero means the value is not evidence. */
        public final int[] support;
        /** Per-used-pair graph diagnostics, restored to deterministic plan order. */
        public final List<PairInfluence> influences;
        public final Weighting weighting;
        public final boolean robustConverged;

        /**
         * Public for test-only Fiji launchers that load the outer reconciler and this value type
         * through separate cooperating class loaders.
         */
        public Solution(Transform[] cumulative, int[] support) {
            this(cumulative, support, Collections.emptyList(), Weighting.EQUAL, true);
        }

        /** Full diagnostic value constructor; see the two-argument constructor's loader contract. */
        public Solution(Transform[] cumulative, int[] support, List<PairInfluence> influences,
                        Weighting weighting, boolean robustConverged) {
            this.cumulative = cumulative;
            this.support = support;
            this.influences = Collections.unmodifiableList(new ArrayList<>(influences));
            this.weighting = weighting;
            this.robustConverged = robustConverged;
        }
    }

    /** Evidence behind one used pair's final influence, calculated before frame repair. */
    public static final class PairInfluence {
        public final int from;
        public final int to;
        public final int planIndex;
        public final boolean used;
        public final Transform graphResidual;
        public final double standardizedResidual;
        public final double robustFactor;
        public final double finalInformationScale;
        public final boolean factorFloored;
        public final boolean uncertaintyFallback;
        public final boolean robustConverged;

        PairInfluence(int from, int to, int planIndex, Transform graphResidual,
                      double standardizedResidual, double robustFactor,
                      double finalInformationScale, boolean factorFloored,
                      boolean uncertaintyFallback, boolean robustConverged) {
            this(from, to, planIndex, true, graphResidual, standardizedResidual, robustFactor,
                    finalInformationScale, factorFloored, uncertaintyFallback, robustConverged);
        }

        private PairInfluence(int from, int to, int planIndex, boolean used,
                              Transform graphResidual, double standardizedResidual,
                              double robustFactor, double finalInformationScale,
                              boolean factorFloored, boolean uncertaintyFallback,
                              boolean robustConverged) {
            this.from = from;
            this.to = to;
            this.planIndex = planIndex;
            this.used = used;
            this.graphResidual = graphResidual;
            this.standardizedResidual = standardizedResidual;
            this.robustFactor = robustFactor;
            this.finalInformationScale = finalInformationScale;
            this.factorFloored = factorFloored;
            this.uncertaintyFallback = uncertaintyFallback;
            this.robustConverged = robustConverged;
        }

        public static PairInfluence notUsed(int from, int to, int planIndex) {
            return new PairInfluence(from, to, planIndex, false, Transform.IDENTITY,
                    Double.NaN, 0.0, 0.0, false, false, true);
        }

        PairInfluence scaleTranslations(double factor) {
            if (!used || factor == 1.0) return this;
            return new PairInfluence(from, to, planIndex, true,
                    graphResidual.scaleTranslation(factor), standardizedResidual,
                    robustFactor, finalInformationScale, factorFloored,
                    uncertaintyFallback, robustConverged);
        }
    }

    /**
     * Which pairs a strategy needs, in a deterministic order so that the parallel scheduler's output
     * does not depend on completion order.
     *
     * <p>{@link Reference#ROLLING} returns an empty list: its target for frame {@code t} is built from
     * frames already registered, so its pairs cannot be enumerated in advance. That is a correctness
     * constraint, not an oversight — see {@link #rolling}.
     */
    public static List<Observation> planPairs(Reference reference, int frames, int referenceFrame,
                                              int lag, int[] lags) {
        List<Observation> out = new ArrayList<>();
        switch (reference) {
            case CONSECUTIVE:
                for (int t = 0; t + lag < frames; t++) out.add(new Observation(t, t + lag, null));
                break;
            case MULTILAG:
                // Frame-major, not lag-major. Both orders produce the same set of pairs and the same
                // answer, but lag-major sweeps the whole recording once per lag, so the pyramid cache
                // sees repeated 0..T scans and thrashes: measured at 38 pyramid builds for 12 frames.
                // Frame-major keeps the working set to maxLag + 1 frames, which is exactly what
                // PyramidCache.reachFor sizes the cache to hold.
                for (int t = 0; t < frames; t++) {
                    for (int k : lags) {
                        if (k < 1 || t + k >= frames) continue;
                        out.add(new Observation(t, t + k, null));
                    }
                }
                break;
            case FIXED:
                for (int t = 0; t < frames; t++) {
                    if (t != referenceFrame) out.add(new Observation(referenceFrame, t, null));
                }
                break;
            case ROLLING:
            default:
                break;
        }
        return out;
    }

    /**
     * Cumulate consecutive steps by exact composition. {@code steps[t]} maps frame {@code t} to
     * {@code t+1}; a null step is treated as the identity and recorded as unsupported.
     */
    public static Solution chain(Transform[] steps) {
        int frames = steps.length + 1;
        Transform[] cum = new Transform[frames];
        int[] support = new int[frames];
        cum[0] = Transform.IDENTITY;
        support[0] = 1;
        for (int t = 0; t < steps.length; t++) {
            Transform s = steps[t] == null ? Transform.IDENTITY : steps[t];
            cum[t + 1] = cum[t].then(s);
            support[t + 1] = steps[t] == null ? 0 : 1;
        }
        return new Solution(cum, support);
    }

    /**
     * Every frame measured directly against {@code referenceFrame}. {@code toRef[t]} is the
     * displacement from the reference to frame {@code t}; the reference's own entry is ignored.
     */
    public static Solution fixed(Transform[] toRef, int referenceFrame) {
        Transform[] cum = new Transform[toRef.length];
        int[] support = new int[toRef.length];
        for (int t = 0; t < toRef.length; t++) {
            if (t == referenceFrame) {
                cum[t] = Transform.IDENTITY;
                support[t] = 1;
            } else if (toRef[t] == null) {
                cum[t] = Transform.IDENTITY;
                support[t] = 0;
            } else {
                cum[t] = toRef[t];
                support[t] = 1;
            }
        }
        return new Solution(cum, support);
    }

    /**
     * Sequential alignment to a running template. The caller supplies the alignment because each step
     * depends on the previous result, so this cannot be planned or parallelised.
     *
     * @param steps displacement from the template in force at frame {@code t} to frame {@code t}
     */
    public static Solution rolling(Transform[] steps) {
        Transform[] cum = new Transform[steps.length];
        int[] support = new int[steps.length];
        for (int t = 0; t < steps.length; t++) {
            cum[t] = steps[t] == null ? Transform.IDENTITY : steps[t];
            support[t] = steps[t] == null ? 0 : 1;
        }
        return new Solution(cum, support);
    }

    /**
     * Least-squares reconciliation of displacements measured at several lags.
     *
     * <p>Frame 0 is the gauge and is <b>eliminated by substitution</b>, not pinned with a heavy
     * weight. Pinning works, and it is what a quick implementation does, but it adds
     * {@code W * u[0]^2} to the objective and therefore biases every other unknown by an amount that
     * depends on {@code W}. Substituting {@code u[0] = 0} removes the freedom exactly and leaves
     * nothing to tune.
     *
     * <p>For an observation from pose {@code lo} to {@code hi}, the exact rigid constraints are
     * {@code thetaHi - thetaLo = thetaObserved} and
     * {@code translationHi - R(thetaObserved) translationLo = translationObserved}. Angles and
     * translations therefore use separate banded systems. A reversed observation is inverted as a
     * rigid transform before its rows are added.
     *
     * <p>The normal equations remain <b>banded</b>: each observation connects only its two endpoint
     * poses, so storage and factorisation scale with the largest lag rather than the frame count.
     */
    public static Solution multiLag(int frames, List<Observation> observations) {
        if (frames < 1) throw new IllegalArgumentException("frames must be >= 1");
        Transform[] cum = new Transform[frames];
        int[] support = new int[frames];
        if (frames == 1) {
            cum[0] = Transform.IDENTITY;
            support[0] = 1;
            return new Solution(cum, support);
        }

        List<Observation> used = new ArrayList<>();
        int bandwidth = 1;
        for (Observation ob : observations) {
            if (ob.displacement == null) continue;
            if (ob.from < 0 || ob.to < 0 || ob.from >= frames || ob.to >= frames) continue;
            used.add(ob);
            support[ob.from]++;
            support[ob.to]++;
            bandwidth = Math.max(bandwidth, ob.lag());
        }
        support[0]++;   // frame 0 is the gauge; it is constrained by definition

        int n = frames - 1;               // unknown poses are frames 1..frames-1
        int angleBandwidth = Math.min(bandwidth, n - 1 < 1 ? 1 : n - 1);
        int translationN = 2 * n;         // x,y interleaved per frame
        int translationBandwidth = Math.min(translationN - 1,
                Math.max(1, 2 * bandwidth + 1));
        double[][] translationBand = new double[translationN][translationBandwidth + 1];
        double[] translationTarget = new double[translationN];
        double[][] angleBand = new double[n][angleBandwidth + 1];
        double[] angleTarget = new double[n];

        for (Observation ob : used) {
            // A rigid inverse is not component-wise negation: its translation is rotated.
            int lo = Math.min(ob.from, ob.to);
            int hi = Math.max(ob.from, ob.to);
            Transform observed = ob.to > ob.from
                    ? ob.displacement : ob.displacement.inverse();
            int hiPose = hi - 1;
            int hiX = 2 * hiPose;
            int hiY = hiX + 1;
            double c = Math.cos(observed.theta);
            double s = Math.sin(observed.theta);
            if (lo > 0) {
                int loPose = lo - 1;
                int loX = 2 * loPose;
                int loY = loX + 1;
                addNormalEquation(translationBand, translationTarget,
                        new int[]{hiX, loX, loY}, new double[]{1.0, -c, s},
                        observed.dx, translationBandwidth);
                addNormalEquation(translationBand, translationTarget,
                        new int[]{hiY, loX, loY}, new double[]{1.0, -s, -c},
                        observed.dy, translationBandwidth);
                addNormalEquation(angleBand, angleTarget,
                        new int[]{hiPose, loPose}, new double[]{1.0, -1.0},
                        observed.theta, angleBandwidth);
            } else {
                addNormalEquation(translationBand, translationTarget,
                        new int[]{hiX}, new double[]{1.0}, observed.dx,
                        translationBandwidth);
                addNormalEquation(translationBand, translationTarget,
                        new int[]{hiY}, new double[]{1.0}, observed.dy,
                        translationBandwidth);
                addNormalEquation(angleBand, angleTarget,
                        new int[]{hiPose}, new double[]{1.0}, observed.theta,
                        angleBandwidth);
            }
        }

        // Diagonal loading. A frame touched by no observation leaves a zero pivot; the ridge makes
        // the factorisation succeed and drives that frame to the gauge, which `support` flags as
        // unsupported so ChainRepair can interpolate it from its neighbours instead.
        loadDiagonal(translationBand);
        loadDiagonal(angleBand);

        if (!choleskyBanded(translationBand, translationBandwidth)
                || !choleskyBanded(angleBand, angleBandwidth)) {
            // Should be unreachable after loading. Degrade to the chain rather than throwing:
            // a wrong-but-bounded answer with `support` zeroed is more useful than an exception
            // in the middle of a hundred-frame run.
            Transform[] steps = new Transform[frames - 1];
            for (Observation ob : used) {
                if (ob.lag() == 1) {
                    int t = Math.min(ob.from, ob.to);
                    steps[t] = ob.to > ob.from ? ob.displacement : ob.displacement.inverse();
                }
            }
            return chain(steps);
        }
        solveBanded(translationBand, translationBandwidth, translationTarget);
        solveBanded(angleBand, angleBandwidth, angleTarget);

        cum[0] = Transform.IDENTITY;
        for (int t = 1; t < frames; t++) {
            int pose = t - 1;
            cum[t] = new Transform(translationTarget[2 * pose],
                    translationTarget[2 * pose + 1], angleTarget[pose]);
        }
        return new Solution(cum, support);
    }

    /**
     * Information-weighted and robust reconciliation. The two-argument overload above remains the
     * exact compatibility implementation.
     */
    public static Solution multiLag(int frames, List<Observation> observations, Options options) {
        Options o = options == null ? new Options() : options.copy();
        validateOptions(o);
        if (o.weighting == Weighting.EQUAL) {
            Solution equal = multiLag(frames, observations);
            List<Edge> edges = canonicalEdges(frames, observations, o);
            prepareInformation(edges, o);
            List<PairInfluence> influences = influences(
                    equal.cumulative, edges, unitFactors(edges.size()), o, true, null);
            return new Solution(equal.cumulative, equal.support, influences,
                    Weighting.EQUAL, true);
        }
        if (frames < 1) throw new IllegalArgumentException("frames must be >= 1");
        List<Edge> edges = canonicalEdges(frames, observations, o);
        int[] support = support(frames, edges);
        prepareInformation(edges, o);
        double[] factors = unitFactors(edges.size());
        Transform[] cumulative;
        boolean converged = true;
        double[] standardized = null;

        if (o.weighting.usesRobustFactors() && !edges.isEmpty()) {
            converged = false;
            Transform[] previous = null;
            for (int iteration = 0; iteration < o.maximumRobustIterations; iteration++) {
                Transform[] current = solveWeighted(frames, edges, factors, -1, o);
                standardized = guardedStandardizedResiduals(frames, current, edges, factors, o);
                double median = median(standardized);
                double[] deviations = new double[standardized.length];
                for (int i = 0; i < standardized.length; i++) {
                    deviations[i] = Math.abs(standardized[i] - median);
                }
                double scale = Math.max(1e-6, 1.4826 * median(deviations));
                double threshold = o.robustLossConstant * scale;
                double maximumFactorChange = 0;
                double[] next = factors.clone();
                for (int i = 0; i < standardized.length; i++) {
                    double excess = Math.max(0.0, standardized[i] - median);
                    double factor = excess <= threshold || excess == 0.0
                            ? 1.0 : threshold / excess;
                    factor = Math.max(o.minimumRobustFactor, Math.min(1.0, factor));
                    maximumFactorChange = Math.max(maximumFactorChange,
                            Math.abs(factor - factors[i]));
                    next[i] = factor;
                }
                double trajectoryChange = previous == null
                        ? Double.POSITIVE_INFINITY
                        : trajectoryChange(previous, current, o.rotationRadiusPixels);
                factors = next;
                previous = current;
                if (maximumFactorChange <= o.factorTolerance
                        && trajectoryChange <= o.trajectoryTolerance) {
                    converged = true;
                    break;
                }
            }
        }
        cumulative = solveWeighted(frames, edges, factors, -1, o);
        if (o.weighting.usesRobustFactors()) {
            standardized = guardedStandardizedResiduals(frames, cumulative, edges, factors, o);
        }
        List<PairInfluence> influences = influences(
                cumulative, edges, factors, o, converged, standardized);
        return new Solution(cumulative, support, influences, o.weighting, converged);
    }

    /**
     * Reconcile translation rows while taking every absolute frame angle as a hard constraint.
     *
     * <p>The existing exact rigid rows already use each observed relative angle when coupling x and
     * y. This method keeps that translation solve and replaces the independently solved angular
     * gauge with the caller's trajectory exactly.
     */
    public static Solution multiLagKnownAngles(
            int frames, List<Observation> observations, Options options, double[] knownAngles) {
        validateKnownAngles(frames, knownAngles);
        Options translation = options == null ? new Options() : options.copy();
        translation.dimensions = 2;
        Solution solved = multiLag(frames, observations, translation);
        return withKnownAngles(solved, knownAngles, 0);
    }

    /** Copy a solution's translations while rebasing and restoring the exact known angles. */
    public static Solution withKnownAngles(Solution solved, double[] knownAngles, int anchor) {
        if (solved == null) throw new IllegalArgumentException("solution is required");
        validateKnownAngles(solved.cumulative.length, knownAngles);
        if (anchor < 0 || anchor >= knownAngles.length) {
            throw new IllegalArgumentException("known-angle anchor is outside the recording");
        }
        Transform[] cumulative = new Transform[solved.cumulative.length];
        double reference = knownAngles[anchor];
        for (int frame = 0; frame < cumulative.length; frame++) {
            Transform transform = solved.cumulative[frame];
            cumulative[frame] = new Transform(transform.dx, transform.dy,
                    knownAngles[frame] - reference);
        }
        return new Solution(cumulative, solved.support, solved.influences,
                solved.weighting, solved.robustConverged);
    }

    private static void validateKnownAngles(int frames, double[] knownAngles) {
        if (knownAngles == null || knownAngles.length != frames) {
            throw new IllegalArgumentException("known angles must contain one finite value per frame");
        }
        for (int frame = 0; frame < frames; frame++) {
            if (!Double.isFinite(knownAngles[frame])) {
                throw new IllegalArgumentException("known angle for frame " + frame + " is not finite");
            }
        }
    }

    private static void validateOptions(Options options) {
        if (options.weighting == null) options.weighting = Weighting.EQUAL;
        if (options.dimensions != 2 && options.dimensions != 3) {
            throw new IllegalArgumentException("reconciliation dimensions must be 2 or 3");
        }
        if (!(options.rotationRadiusPixels > 0)
                || !Double.isFinite(options.rotationRadiusPixels)) {
            throw new IllegalArgumentException("rotation radius must be finite and > 0");
        }
        if (!(options.minimumRobustFactor > 0 && options.minimumRobustFactor <= 1)) {
            throw new IllegalArgumentException("minimum robust factor must be in (0, 1]");
        }
        if (options.maximumRobustIterations < 1) {
            throw new IllegalArgumentException("maximum robust iterations must be >= 1");
        }
        if (!(options.robustLossConstant > 0) || !Double.isFinite(options.robustLossConstant)) {
            throw new IllegalArgumentException("robust loss constant must be finite and > 0");
        }
        if (options.informationNormalizer == null) {
            throw new IllegalArgumentException("information normalizer is required");
        }
    }

    private static final class Edge {
        final Observation source;
        final int inputOrder;
        final int lo;
        final int hi;
        final Transform observed;
        final PairUncertainty uncertainty;
        double[] baseInformation;
        boolean uncertaintyFallback;

        Edge(Observation source, int inputOrder, int lo, int hi,
             Transform observed, PairUncertainty uncertainty) {
            this.source = source;
            this.inputOrder = inputOrder;
            this.lo = lo;
            this.hi = hi;
            this.observed = observed;
            this.uncertainty = uncertainty;
        }

        int lag() {
            return hi - lo;
        }
    }

    private static List<Edge> canonicalEdges(int frames, List<Observation> observations,
                                             Options options) {
        List<Edge> edges = new ArrayList<>();
        if (observations == null) return edges;
        int order = 0;
        for (Observation observation : observations) {
            if (observation == null || observation.displacement == null) {
                order++;
                continue;
            }
            if (observation.from < 0 || observation.to < 0
                    || observation.from >= frames || observation.to >= frames) {
                order++;
                continue;
            }
            int lo = Math.min(observation.from, observation.to);
            int hi = Math.max(observation.from, observation.to);
            boolean forward = observation.to > observation.from;
            Transform observed = forward
                    ? observation.displacement : observation.displacement.inverse();
            PairUncertainty uncertainty = observation.uncertainty;
            if (!forward && uncertainty != null && uncertainty.available()) {
                uncertainty = uncertainty.inverseFor(observation.displacement);
            }
            edges.add(new Edge(observation, order, lo, hi, observed, uncertainty));
            order++;
        }
        edges.sort(Comparator
                .comparingInt((Edge edge) -> edge.lo)
                .thenComparingInt(edge -> edge.hi)
                .thenComparingInt(edge -> edge.source.planIndex >= 0
                        ? edge.source.planIndex : Integer.MAX_VALUE)
                .thenComparingLong(edge -> Double.doubleToLongBits(edge.observed.dx))
                .thenComparingLong(edge -> Double.doubleToLongBits(edge.observed.dy))
                .thenComparingLong(edge -> Double.doubleToLongBits(edge.observed.theta))
                .thenComparingInt(edge -> edge.inputOrder));
        return edges;
    }

    private static int[] support(int frames, List<Edge> edges) {
        int[] support = new int[frames];
        for (Edge edge : edges) {
            support[edge.source.from]++;
            support[edge.source.to]++;
        }
        if (frames > 0) support[0]++;
        return support;
    }

    /** Normalize available information globally; missing matrices receive median isotropic weight. */
    private static void prepareInformation(List<Edge> edges, Options options) {
        int dimensions = options.dimensions;
        if (options.weighting == Weighting.ROBUST || options.weighting == Weighting.EQUAL) {
            double[] physical = identity(dimensions);
            if (dimensions == 3) physical[2 * dimensions + 2]
                    = options.rotationRadiusPixels * options.rotationRadiusPixels;
            double normalizer = trace(physical, dimensions) / dimensions;
            for (Edge edge : edges) {
                edge.baseInformation = physical.clone();
                for (int i = 0; i < edge.baseInformation.length; i++) {
                    edge.baseInformation[i] /= normalizer;
                }
                edge.uncertaintyFallback = false;
            }
            return;
        }

        double[] scales = new double[edges.size()];
        int available = 0;
        for (Edge edge : edges) {
            PairUncertainty uncertainty = edge.uncertainty;
            if (uncertainty == null || !uncertainty.available()
                    || uncertainty.dimensions != dimensions) continue;
            double[] information = uncertainty.information();
            double scale = trace(information, dimensions) / dimensions;
            if (!(scale > 0) || !Double.isFinite(scale)) continue;
            edge.baseInformation = information;
            scales[available++] = scale;
        }
        double normalizer = available > 0
                ? informationNormalizer(Arrays.copyOf(scales, available),
                        options.informationNormalizer) : 1.0;
        if (!(normalizer > 0) || !Double.isFinite(normalizer)) normalizer = 1.0;
        for (Edge edge : edges) {
            if (edge.baseInformation == null) {
                edge.baseInformation = identity(dimensions);
                for (int i = 0; i < dimensions; i++) {
                    edge.baseInformation[i * dimensions + i] = normalizer;
                }
                edge.uncertaintyFallback = true;
            }
            for (int i = 0; i < edge.baseInformation.length; i++) {
                edge.baseInformation[i] /= normalizer;
            }
        }
    }

    private static double informationNormalizer(double[] values, InformationNormalizer normalizer) {
        if (normalizer == InformationNormalizer.MEDIAN) return median(values);
        Arrays.sort(values);
        int trim = values.length / 4;
        int from = trim;
        int to = values.length - trim;
        if (from >= to) return median(values);
        double sum = 0.0;
        for (int index = from; index < to; index++) sum += values[index];
        return sum / (to - from);
    }

    private static Transform[] solveWeighted(int frames, List<Edge> edges, double[] factors,
                                             int skippedEdge, Options options) {
        Transform[] cumulative = new Transform[frames];
        if (frames == 1) {
            cumulative[0] = Transform.IDENTITY;
            return cumulative;
        }
        int dimensions = options.dimensions;
        int unknowns = dimensions * (frames - 1);
        int maximumLag = 1;
        for (Edge edge : edges) maximumLag = Math.max(maximumLag, edge.lag());
        int bandwidth = Math.min(unknowns - 1,
                Math.max(1, dimensions * maximumLag + dimensions - 1));
        double[][] band = new double[unknowns][bandwidth + 1];
        double[] target = new double[unknowns];
        for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
            if (edgeIndex == skippedEdge) continue;
            Edge edge = edges.get(edgeIndex);
            accumulateEdge(band, target, edge, factors[edgeIndex], bandwidth, options);
        }
        loadDiagonal(band);
        if (!choleskyBanded(band, bandwidth)) return weightedChain(frames, edges);
        solveBanded(band, bandwidth, target);
        cumulative[0] = Transform.IDENTITY;
        for (int frame = 1; frame < frames; frame++) {
            int base = dimensions * (frame - 1);
            cumulative[frame] = new Transform(target[base], target[base + 1],
                    dimensions == 3 ? target[base + 2] : 0.0);
        }
        return cumulative;
    }

    /** Accumulate A' Omega A and A' Omega d from one small rigid design block. */
    private static void accumulateEdge(double[][] band, double[] rhs, Edge edge, double factor,
                                       int bandwidth, Options options) {
        int dimensions = options.dimensions;
        int variableCount = dimensions + (edge.lo > 0 ? dimensions : 0);
        int[] variables = new int[variableCount];
        double[][] design = new double[dimensions][variableCount];
        int hiBase = dimensions * (edge.hi - 1);
        for (int d = 0; d < dimensions; d++) {
            variables[d] = hiBase + d;
            design[d][d] = 1.0;
        }
        if (edge.lo > 0) {
            int loBase = dimensions * (edge.lo - 1);
            for (int d = 0; d < dimensions; d++) variables[dimensions + d] = loBase + d;
            double c = Math.cos(edge.observed.theta);
            double s = Math.sin(edge.observed.theta);
            design[0][dimensions] = -c;
            design[0][dimensions + 1] = s;
            design[1][dimensions] = -s;
            design[1][dimensions + 1] = -c;
            if (dimensions == 3) design[2][dimensions + 2] = -1.0;
        }
        double[] measurement = dimensions == 3
                ? new double[]{edge.observed.dx, edge.observed.dy, edge.observed.theta}
                : new double[]{edge.observed.dx, edge.observed.dy};
        double[] omegaTarget = new double[dimensions];
        for (int row = 0; row < dimensions; row++) {
            for (int col = 0; col < dimensions; col++) {
                omegaTarget[row] += factor * edge.baseInformation[row * dimensions + col]
                        * measurement[col];
            }
        }
        for (int local = 0; local < variableCount; local++) {
            double value = 0;
            for (int row = 0; row < dimensions; row++) {
                value += design[row][local] * omegaTarget[row];
            }
            rhs[variables[local]] += value;
        }
        for (int left = 0; left < variableCount; left++) {
            for (int right = 0; right <= left; right++) {
                double value = 0;
                for (int row = 0; row < dimensions; row++) {
                    for (int col = 0; col < dimensions; col++) {
                        value += design[row][left] * factor
                                * edge.baseInformation[row * dimensions + col]
                                * design[col][right];
                    }
                }
                addBand(band, variables[left], variables[right], value, bandwidth);
            }
        }
    }

    private static Transform[] weightedChain(int frames, List<Edge> edges) {
        Transform[] steps = new Transform[frames - 1];
        for (Edge edge : edges) if (edge.lag() == 1) steps[edge.lo] = edge.observed;
        return chain(steps).cumulative;
    }

    private static double[] guardedStandardizedResiduals(
            int frames, Transform[] cumulative, List<Edge> edges,
            double[] factors, Options options) {
        double[] standardized = new double[edges.size()];
        for (int index = 0; index < edges.size(); index++) {
            Transform[] reference = hasAlternatePath(frames, edges, index)
                    ? solveWeighted(frames, edges, factors, index, options) : cumulative;
            double[] residual = residual(reference, edges.get(index), options.dimensions);
            standardized[index] = standardized(residual,
                    edges.get(index).baseInformation, options.dimensions);
        }
        return standardized;
    }

    private static boolean hasAlternatePath(int frames, List<Edge> edges, int excluded) {
        Edge wanted = edges.get(excluded);
        boolean[] seen = new boolean[frames];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[wanted.lo] = true;
        queue.add(wanted.lo);
        while (!queue.isEmpty()) {
            int frame = queue.removeFirst();
            if (frame == wanted.hi) return true;
            for (int index = 0; index < edges.size(); index++) {
                if (index == excluded) continue;
                Edge edge = edges.get(index);
                int next = edge.lo == frame ? edge.hi : edge.hi == frame ? edge.lo : -1;
                if (next >= 0 && !seen[next]) {
                    seen[next] = true;
                    queue.addLast(next);
                }
            }
        }
        return false;
    }

    private static List<PairInfluence> influences(
            Transform[] cumulative, List<Edge> edges, double[] factors,
            Options options, boolean converged, double[] guardedStandardized) {
        List<PairInfluence> out = new ArrayList<>(edges.size());
        for (int index = 0; index < edges.size(); index++) {
            Edge edge = edges.get(index);
            double[] raw = residual(cumulative, edge, options.dimensions);
            Transform graphResidual = new Transform(raw[0], raw[1],
                    options.dimensions == 3 ? raw[2] : 0.0);
            double standardized = guardedStandardized == null
                    ? standardized(raw, edge.baseInformation, options.dimensions)
                    : guardedStandardized[index];
            double factor = factors[index];
            double informationScale = factor
                    * trace(edge.baseInformation, options.dimensions) / options.dimensions;
            out.add(new PairInfluence(edge.source.from, edge.source.to,
                    edge.source.planIndex >= 0 ? edge.source.planIndex : edge.inputOrder,
                    graphResidual, standardized, factor, informationScale,
                    factor <= options.minimumRobustFactor + 1e-15,
                    edge.uncertaintyFallback, converged));
        }
        out.sort(Comparator.comparingInt((PairInfluence influence) -> influence.planIndex)
                .thenComparingInt(influence -> influence.from)
                .thenComparingInt(influence -> influence.to));
        return out;
    }

    /** Residual of the exact rows used by the solve, in canonical lo-to-hi direction. */
    private static double[] residual(Transform[] cumulative, Edge edge, int dimensions) {
        Transform lo = cumulative[edge.lo];
        Transform hi = cumulative[edge.hi];
        double c = Math.cos(edge.observed.theta);
        double s = Math.sin(edge.observed.theta);
        double[] out = new double[dimensions];
        out[0] = hi.dx - c * lo.dx + s * lo.dy - edge.observed.dx;
        out[1] = hi.dy - s * lo.dx - c * lo.dy - edge.observed.dy;
        if (dimensions == 3) out[2] = hi.theta - lo.theta - edge.observed.theta;
        return out;
    }

    private static double standardized(double[] residual, double[] information, int dimensions) {
        double quadratic = 0;
        for (int row = 0; row < dimensions; row++) {
            for (int col = 0; col < dimensions; col++) {
                quadratic += residual[row] * information[row * dimensions + col]
                        * residual[col];
            }
        }
        return Math.sqrt(Math.max(0.0, quadratic) / dimensions);
    }

    private static double trajectoryChange(Transform[] before, Transform[] after,
                                           double rotationRadius) {
        double maximum = 0;
        for (int i = 0; i < before.length; i++) {
            double dx = after[i].dx - before[i].dx;
            double dy = after[i].dy - before[i].dy;
            double dt = rotationRadius * (after[i].theta - before[i].theta);
            maximum = Math.max(maximum, Math.sqrt(dx * dx + dy * dy + dt * dt));
        }
        return maximum;
    }

    private static double[] unitFactors(int count) {
        double[] factors = new double[count];
        Arrays.fill(factors, 1.0);
        return factors;
    }

    private static double[] identity(int dimensions) {
        double[] identity = new double[dimensions * dimensions];
        for (int i = 0; i < dimensions; i++) identity[i * dimensions + i] = 1.0;
        return identity;
    }

    private static double trace(double[] matrix, int dimensions) {
        double trace = 0;
        for (int i = 0; i < dimensions; i++) trace += matrix[i * dimensions + i];
        return trace;
    }

    private static double median(double[] values) {
        if (values.length == 0) return 0.0;
        double[] copy = values.clone();
        return RobustNorm.select(copy, copy.length, copy.length / 2);
    }

    /** Add one sparse design row to normal equations without allocating a dense matrix. */
    private static void addNormalEquation(double[][] band, double[] rhs,
                                          int[] indices, double[] coefficients,
                                          double target, int bandwidth) {
        for (int a = 0; a < indices.length; a++) {
            int ia = indices[a];
            double ca = coefficients[a];
            rhs[ia] += ca * target;
            for (int b = 0; b <= a; b++) {
                addBand(band, ia, indices[b], ca * coefficients[b], bandwidth);
            }
        }
    }

    private static void loadDiagonal(double[][] band) {
        double mean = 0;
        for (double[] row : band) mean += row[0];
        mean = band.length > 0 ? mean / band.length : 1;
        double ridge = Math.max(RIDGE * (mean > 0 ? mean : 1), Double.MIN_NORMAL);
        for (double[] row : band) row[0] += ridge;
    }

    /** Rebase a solution so {@code frame} is the identity, by exact composition. */
    public static Transform[] rebase(Transform[] cumulative, int frame) {
        Transform inv = cumulative[frame].inverse();
        Transform[] out = new Transform[cumulative.length];
        for (int t = 0; t < cumulative.length; t++) {
            out[t] = inv.then(cumulative[t]);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ //
    // Banded symmetric positive-definite linear algebra. Lower band, row-major:
    //     band[i][d] holds M[i][i-d]  for d = 0..bandwidth,  band[i][0] the diagonal.
    // ------------------------------------------------------------------------------------ //

    private static void addBand(double[][] band, int i, int j, double value, int bandwidth) {
        int row = Math.max(i, j);
        int d = Math.abs(i - j);
        if (d > bandwidth) {
            throw new IllegalStateException("entry (" + i + "," + j + ") outside bandwidth "
                    + bandwidth);
        }
        band[row][d] += value;
    }

    private static double get(double[][] band, int i, int j, int bandwidth) {
        int d = i - j;
        if (d < 0 || d > bandwidth) return 0;
        return band[i][d];
    }

    /** In-place banded Cholesky. Returns false if the matrix is not positive definite. */
    static boolean choleskyBanded(double[][] band, int bandwidth) {
        int n = band.length;
        double[][] l = new double[n][bandwidth + 1];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - bandwidth);
            for (int j = lo; j < i; j++) {
                double s = get(band, i, j, bandwidth);
                for (int k = lo; k < j; k++) {
                    s -= l[i][i - k] * l[j][j - k];
                }
                l[i][i - j] = s / l[j][0];
            }
            double s = get(band, i, i, bandwidth);
            for (int k = lo; k < i; k++) {
                s -= l[i][i - k] * l[i][i - k];
            }
            if (!(s > 0)) return false;
            l[i][0] = Math.sqrt(s);
        }
        for (int i = 0; i < n; i++) System.arraycopy(l[i], 0, band[i], 0, bandwidth + 1);
        return true;
    }

    /** Forward then back substitution against a factor from {@link #choleskyBanded}, in place. */
    static void solveBanded(double[][] l, int bandwidth, double[] b) {
        int n = b.length;
        for (int i = 0; i < n; i++) {
            double s = b[i];
            for (int k = Math.max(0, i - bandwidth); k < i; k++) {
                s -= l[i][i - k] * b[k];
            }
            b[i] = s / l[i][0];
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int k = i + 1; k < Math.min(n, i + bandwidth + 1); k++) {
                s -= l[k][k - i] * b[k];
            }
            b[i] = s / l[i][0];
        }
    }
}
