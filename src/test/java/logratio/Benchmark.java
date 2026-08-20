/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import logratio.core.AutomaticInformationSelector;
import logratio.core.LogPlane;
import logratio.core.PairAligner;
import logratio.core.PhaseCorrelation;
import logratio.core.Reconciler;
import logratio.core.RobustNorm;
import logratio.core.Transform;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Every estimator, with and without RCC, on real content with exactly known motion — and how long each
 * takes. <b>Test scope only.</b>
 *
 * <h2>Why the factorial</h2>
 *
 * <p>Redundant cross-correlation (RCC; Wang et al., <i>Opt Express</i> 22:15982, 2014) reconciles many
 * overlapping pairwise measurements by least squares instead of chaining consecutive ones. <b>It is not
 * this plugin's contribution and it is indifferent to how each pair was measured.</b> Quoting a log-ratio
 * result with RCC against a phase-correlation result without it credits the cost function with an
 * improvement that belongs to the reconciliation. So every estimator here runs through the same
 * {@link Reconciler} with the same pair plan, and only the per-pair estimate varies.
 *
 * <h2>Why truth is absolute and nothing is differenced</h2>
 *
 * <p>An earlier version of this file, and the experiment in {@code 00_CASE.md} before it, measured
 * {@code estimate(shifted) - estimate(static)} so that the recording's own unknown drift would cancel.
 * <b>That design also cancels the conditions being tested.</b> A global gain change and an identical
 * sparse-change pattern are pure functions of the frame pair, so differencing removes their effect
 * along with the native drift: measured on the whole-pixel rebuild, a 4x fade left phase correlation
 * bit-identical to the clean case and degraded the SSD baseline 1.4x instead of the 6.8x the differential
 * design had reported, and under sparse change phase correlation read 0.010 px instead of collapsing.
 *
 * <p>So there is no differencing here. A stack is built from <b>one real frame</b>, which removes native
 * drift by construction rather than by subtraction, and every nuisance is introduced deliberately per
 * frame where it cannot cancel.
 *
 * <h2>How sub-pixel truth is exact without favouring anyone</h2>
 *
 * <p>The window is moved by whole pixels at full resolution and then box-averaged {@value #FINE}x
 * {@value #FINE} down, so one fine pixel of movement is a quarter of a coarse pixel and the truth is
 * exact. The resampling is pixel integration — physically what a camera sensor does, and not the
 * interpolation kernel of any estimator here, so it hands no method an advantage. Injecting sub-pixel
 * motion with a spline instead, as {@code 00_CASE.md} did, blurs the spectrum and costs a Fourier method
 * more than a pyramid-gradient one; injecting whole-pixel motion at native resolution instead makes the
 * test nearly degenerate, because the shifted frames are then the same data relabelled.
 *
 * <h2>Conditions</h2>
 *
 * <ul>
 *   <li><b>clean</b> — independent per-frame sensor noise only. Without noise every method scores near
 *       zero and the comparison discriminates nothing, since the frames would be identical up to the
 *       injected shift.
 *   <li><b>gain fade</b> — a 4x intensity ramp across the recording. Separates a gain-invariant
 *       criterion from a sum-of-squared-differences one.
 *   <li><b>change, blocks</b> — a tenth of the field overwritten each frame with constant bright patches.
 *       Harsh, and harsh in a direction that specifically penalises an intensity-ratio criterion.
 *   <li><b>change, moved</b> — the same patches, at the same positions, filled instead with real content
 *       from a nearby offset, so texture and intensity scale survive and the change is a local
 *       displacement of structure. This is the fair test of robustness to genuine change; the block model
 *       is kept alongside it so the two can be attributed apart.
 * </ul>
 *
 * <pre>
 *   java -cp ... logratio.Benchmark &lt;seed frame dir&gt; &lt;out dir&gt;
 * </pre>
 */
public final class Benchmark {

    // The fixture — the constants, `frame`, `trajectory`, `seedPlane` and the scoring helpers — is
    // package-private rather than private so {@link ThirdParty} can compare an external plugin against
    // exactly the same stacks and the same truth. A second copy of the fixture would be worse than
    // useless: three earlier versions of this experiment gave three different answers, and the only
    // reason a third-party number is comparable at all is that it comes from these pixels.

    /** Oversampling factor. One fine pixel of window movement is 1/FINE of a coarse pixel. */
    static final int FINE = 4;
    /** Slack in fine pixels beyond the trajectory's own reach. The margin is derived, never guessed. */
    static final int FINE_SLACK = 8;
    /** Frames in the synthetic recording. */
    static final int FRAMES = 48;
    /** Lags for the RCC arm. Must contain 1 so every consecutive transition is covered. */
    static final int[] LAGS = {1, 2, 4, 8, 16};
    /** One stored grey-level count. All benchmark seeds are integer-valued, so this is not fitted. */
    static final double EPSILON = 1.0;
    /**
     * Per-frame noise as a fraction of the frame's standard deviation.
     *
     * <p>Real consecutive frames of these recordings correlate at about 0.976, which for two frames
     * differing only by noise implies a noise-to-signal standard deviation ratio of
     * {@code sqrt((1-r)/r)} = 0.16. That correlation also absorbs genuine change and motion, so it is an
     * upper bound; 0.10 is the conservative choice.
     */
    static final double NOISE_FRACTION = 0.10;
    /** Below this robust-range position, most pixels are background rather than field texture. */
    static final double AUTO_SPARSE_BOUNDARY = AutomaticInformationSelector.SPARSE_BOUNDARY;
    /** Above this q99/q95 residual ratio, a dense field contains locally moving structure. */
    static final double AUTO_MOVING_TAIL_BOUNDARY =
            AutomaticInformationSelector.MOVING_TAIL_BOUNDARY;
    private static final double FADE_LOG2 = -2.0;
    private static final double CHANGE_FRACTION = 0.10;
    /** Patch size in FINE pixels, so it is 8 coarse pixels after decimation. */
    private static final int FINE_PATCH = 32;
    /** How far structure is displaced under {@link Condition#CHANGE_MOVED}, in fine pixels. */
    private static final int MOVE_FINE = 24;

    /**
     * The estimators, plus the intensity-band variants of the best one.
     *
     * <p><b>Why the band variants are standing rows rather than a side experiment.</b> Excluding pixels
     * by intensity changes accuracy <em>and</em> speed at the same time, in opposite directions
     * depending on which end you cut, and neither effect is guessable. Keeping them out of the matrix
     * meant the one condition this benchmark lost on looked unfixable for four months, and it meant a
     * sensible-sounding speed idea — trim the empty background — had to be talked about rather than
     * looked up. Both are now rows.
     *
     * <p>The variants all wrap {@link #LOGRATIO_TUKEY}, the recommended configuration, so the only
     * thing that differs from its row is which pixels are allowed to vote.
     */
    enum Estimator {
        LOGRATIO_TUKEY("log-ratio Tukey+grad", true),
        /** The accuracy optimum: best on clean data and it closes the block-artefact failure. */
        LOGRATIO_TUKEY_NO_TOP_10("log-ratio Tukey+grad (no top 10%)", true, 90, Double.NaN),
        /** The speed optimum: 20% faster, and still closes the block-artefact failure. */
        LOGRATIO_TUKEY_NO_TOP_25("log-ratio Tukey+grad (no top 25%)", true, 75, Double.NaN),
        /**
         * The mirror image, kept as a standing negative result. It looks like the obvious speed
         * optimisation — the dimmest pixels are empty background — and it is the wrong end to cut,
         * because the solver descends the gradient of the log plane and the log is steep where the
         * frame is dark. One row costs three minutes and stops this being re-proposed.
         */
        LOGRATIO_TUKEY_NO_BOTTOM_25("log-ratio Tukey+grad (no bottom 25%)", true, Double.NaN, 25),
        /** Objective information-selection sweep: multiples of each frame's median gradient. */
        LOGRATIO_TUKEY_GRAD_0("log-ratio Tukey (gradient x0)", true, 0),
        LOGRATIO_TUKEY_GRAD_025("log-ratio Tukey (gradient x0.25)", true, 0.25),
        LOGRATIO_TUKEY_GRAD_05("log-ratio Tukey (gradient x0.5 control)", true, 0.5),
        LOGRATIO_TUKEY_GRAD_1("log-ratio Tukey (gradient x1)", true, 1),
        LOGRATIO_TUKEY_GRAD_2("log-ratio Tukey (gradient x2)", true, 2),
        LOGRATIO_TUKEY_GRAD_4("log-ratio Tukey (gradient x4)", true, 4),
        LOGRATIO_TUKEY_MUTUAL_NOISE("log-ratio Tukey (mutual noise edge x0.5)", true),
        LOGRATIO_AUTO_INFORMATION("log-ratio Tukey (automatic information support)", true),
        LOGRATIO_HUBER("log-ratio Huber", true),
        LOGRATIO_WOODS("log-ratio least sq (Woods)", true),
        // No comma in the label: it is written into a CSV unquoted, and a comma here shifted every
        // column of this row so the summary reported the wrong numbers for it.
        SSD("SSD no gain (StackReg family)", true),
        PHASE_CORRELATION("phase correlation", false),
        CROSS_CORRELATION("cross-correlation", false);

        final String label;
        final boolean pyramid;
        /** Exclude pixels at or above this percentile of the frame's own intensities. NaN: none. */
        final double ceilingPercentile;
        /** Exclude pixels below this percentile of the frame's own intensities. NaN: none. */
        final double floorPercentile;
        /** Median-gradient multiplier declared by a sweep arm. NaN: use the production default. */
        final double gradientFraction;

        Estimator(String label, boolean pyramid) {
            this(label, pyramid, Double.NaN, Double.NaN, Double.NaN);
        }

        Estimator(String label, boolean pyramid, double ceilingPercentile, double floorPercentile) {
            this(label, pyramid, ceilingPercentile, floorPercentile, Double.NaN);
        }

        Estimator(String label, boolean pyramid, double gradientFraction) {
            this(label, pyramid, Double.NaN, Double.NaN, gradientFraction);
        }

        Estimator(String label, boolean pyramid, double ceilingPercentile, double floorPercentile,
                  double gradientFraction) {
            this.label = label;
            this.pyramid = pyramid;
            this.ceilingPercentile = ceilingPercentile;
            this.floorPercentile = floorPercentile;
            this.gradientFraction = gradientFraction;
        }

        /** The estimator this one is a band variant of, or itself. */
        Estimator base() {
            switch (this) {
                case LOGRATIO_TUKEY_NO_TOP_10:
                case LOGRATIO_TUKEY_NO_TOP_25:
                case LOGRATIO_TUKEY_NO_BOTTOM_25:
                case LOGRATIO_TUKEY_GRAD_0:
                case LOGRATIO_TUKEY_GRAD_025:
                case LOGRATIO_TUKEY_GRAD_05:
                case LOGRATIO_TUKEY_GRAD_1:
                case LOGRATIO_TUKEY_GRAD_2:
                case LOGRATIO_TUKEY_GRAD_4:
                    return LOGRATIO_TUKEY;
                default:
                    return this;
            }
        }

        PairAligner.Options options(double maxShift) {
            PairAligner.Options o = new PairAligner.Options();
            o.maxShift = maxShift;
            switch (base()) {
                case LOGRATIO_TUKEY:
                    o.norm = RobustNorm.TUKEY;
                    o.support = PairAligner.PixelSupport.GRADIENT;
                    break;
                case LOGRATIO_HUBER:
                    o.norm = RobustNorm.HUBER;
                    break;
                case LOGRATIO_WOODS:
                    o.norm = RobustNorm.LEAST_SQUARES;
                    break;
                case SSD:
                    o.norm = RobustNorm.LEAST_SQUARES;
                    // Without the gain nuisance parameter the criterion is a plain sum of squared
                    // differences on log intensities, which is what StackReg, TurboReg and Image
                    // Stabilizer minimise.
                    o.profileGain = false;
                    break;
                default:
                    break;
            }
            if (!Double.isNaN(gradientFraction)) o.gradientFraction = gradientFraction;
            if (this == LOGRATIO_TUKEY_MUTUAL_NOISE) {
                o.norm = RobustNorm.TUKEY;
                o.support = PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT;
                o.gradientFraction = Double.parseDouble(
                        System.getProperty("logratio.mutualNoiseFraction", "0.5"));
                o.maxIterations = Integer.parseInt(
                        System.getProperty("logratio.mutualMaxIterations", "12"));
                o.maxSamples = Integer.parseInt(
                        System.getProperty("logratio.mutualMaxSamples", "50000"));
            }
            return o;
        }

        /** True when this arm declares its own band, and so ignores the system properties entirely. */
        private boolean declaresBand() {
            return !Double.isNaN(ceilingPercentile) || !Double.isNaN(floorPercentile);
        }

        /**
         * The ceiling this arm uses.
         *
         * <p>An arm that declares a band ignores both properties, including the axis it left unset.
         * Without that rule, running the matrix with {@code -Dlogratio.floorPercentile=25} would
         * silently turn the {@code no top 10%} row into a two-sided band and the CSV would record a row
         * that does not mean what its label says.
         */
        double ceiling() {
            if (declaresBand()) return ceilingPercentile;
            return saturationPercentile();
        }

        /** The floor this arm uses. Same rule as {@link #ceiling}. */
        double floor() {
            if (declaresBand()) return floorPercentile;
            return floorPercentile();
        }
    }

    enum Recon { CHAIN, RCC }

    /**
     * Two change models, at identical patch positions, so the only thing that differs is what the change
     * <i>is</i>.
     *
     * <p>{@link #CHANGE_BLOCKS} overwrites patches with a constant bright value. It is a harsh model and
     * it is harsh in a direction that specifically penalises an intensity-ratio criterion: a constant
     * block at twice the 99.5th percentile creates an enormous log-ratio against whatever was there,
     * while to a Fourier method it is merely uncorrelated broadband noise, which magnitude normalisation
     * absorbs. It also has zero gradient inside and the largest gradients in the frame at its edges,
     * which is exactly the wrong thing to hand a gradient-based pixel selector.
     *
     * <p>{@link #CHANGE_MOVED} instead copies real content from a nearby offset into each patch, so
     * texture and the intensity distribution are preserved and the change is a local displacement of
     * structure — what a cell moving between frames actually looks like. This is the fair test of the
     * robustness claim.
     */
    enum Condition { CLEAN, GAIN_FADE, CHANGE_BLOCKS, CHANGE_MOVED }

    /** Benchmark-only ways to turn graph-aligned temporal evidence into a removal ranking. */
    enum LagArm {
        MOST_UNSTABLE("remove most unstable", LagPixelSelector.Score.INSTABILITY, true),
        LEAST_UNSTABLE("remove least unstable", LagPixelSelector.Score.INSTABILITY, false),
        MOST_LAG_GROWTH("remove greatest lag growth", LagPixelSelector.Score.LAG_GROWTH, true),
        LOWEST_ANCHOR_TRUST("remove lowest anchor trust", LagPixelSelector.Score.ANCHOR_TRUST, false),
        STRATIFIED_LOWEST_ANCHOR_TRUST("remove spatially stratified lowest anchor trust",
                LagPixelSelector.Score.ANCHOR_TRUST, false);

        final String label;
        final LagPixelSelector.Score score;
        final boolean removeHighest;

        LagArm(String label, LagPixelSelector.Score score, boolean removeHighest) {
            this.label = label;
            this.score = score;
            this.removeHighest = removeHighest;
        }
    }

    /** Whether temporal scores use exact benchmark motion or the production lag-graph pilot. */
    enum LagPilot { ORACLE, GRAPH }

    public static void main(String[] args) throws IOException {
        Path seeds = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);

        Path csv = out.resolve(System.getProperty("logratio.outputName", "benchmark.csv"));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv))) {
            // Every timing column is CPU time and is named so. Wall clock is not a property of the
            // code — see Timing, and the fabricated 11,149 s arm it documents. unscheduled_ms is the
            // elapsed time that was not computation, kept rather than dropped so that a run interrupted
            // by a standby is visible in the record instead of merely absent from it.
            w.println("seed,condition,estimator,reconciliation,frames,pairs,estimate_cpu_ms,"
                    + "reconcile_cpu_ms,total_cpu_ms,cpu_ms_per_pair,unscheduled_ms,"
                    + "median_err_px,p90_err_px,max_err_px,refused,saturation_pct,floor_pct");
        }
        if ("modal-tile".equals(System.getProperty("logratio.estimatorSet", ""))) {
            Path diagnostics = modalDiagnosticsPath(csv);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(diagnostics))) {
                w.println("seed,condition,from,to,lag,start_dx,start_dy,mode_dx,mode_dy,"
                        + "truth_dx,truth_dy,mode_direction_deg,truth_direction_deg,"
                        + "angle_error_deg,tile_votes,inlier_tiles,coverage_x,coverage_y,"
                        + "bandwidth,usable");
            }
        }
        if ("tile-graph".equals(System.getProperty("logratio.estimatorSet", ""))
                || "tile-coordinate".equals(System.getProperty("logratio.estimatorSet", ""))) {
            Path diagnostics = tileGraphDiagnosticsPath(csv);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(diagnostics))) {
                w.println("seed,condition,frame,pilot_dx,pilot_dy,raw_dx,raw_dy,guarded_dx,"
                        + "guarded_dy,truth_dx,truth_dy,correction_weight,pilot_error_px,"
                        + "raw_error_px,guarded_error_px");
            }
        }

        List<Path> frames = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(seeds)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".tif")).sorted()
                    .forEach(frames::add);
        }
        if (frames.isEmpty()) throw new IOException("no seed frames in " + seeds);
        // Filters, so one configuration can be reproduced without rerunning the whole matrix.
        String onlySeed = System.getProperty("logratio.onlySeed", "");
        String excludeSeed = System.getProperty("logratio.excludeSeed", "");
        for (Path seed : frames) {
            if (!onlySeed.isEmpty() && !seed.getFileName().toString().contains(onlySeed)) continue;
            if (!excludeSeed.isEmpty() && seed.getFileName().toString().contains(excludeSeed)) continue;
            run(seed, csv);
        }
        System.out.println("wrote " + csv);
    }

    private static void run(Path seed, Path csv) throws IOException {
        ImagePlus imp = IJ.openImage(seed.toString());
        if (imp == null) throw new IOException("could not open " + seed);
        int sw = imp.getWidth();
        int sh = imp.getHeight();
        float[] fine = seedPlane(imp);
        imp.close();
        String name = seed.getFileName().toString().replaceFirst("\\.tif$", "");

        int[] fx = new int[FRAMES];
        int[] fy = new int[FRAMES];
        trajectory(fx, fy);
        double[] tx = new double[FRAMES];
        double[] ty = new double[FRAMES];
        double reach = 0;
        int span = 0;
        for (int t = 0; t < FRAMES; t++) {
            tx[t] = fx[t] / (double) FINE;
            ty[t] = fy[t] / (double) FINE;
            reach = Math.max(reach, Math.hypot(tx[t], ty[t]));
            span = Math.max(span, Math.max(Math.abs(fx[t]), Math.abs(fy[t])));
        }
        // Derived from the trajectory rather than assumed: a hardcoded margin silently overran the
        // source and the window read outside the array.
        int margin = span + FINE_SLACK;
        int win = Math.min((sw - 2 * margin) / FINE, (sh - 2 * margin) / FINE);
        if (win < 64) throw new IOException(name + ": window would be only " + win + " px");
        double maxShift = 2 * reach + 8;
        double sd = standardDeviation(fine);

        System.out.printf("%n=== %s: %dx%d fine -> %dx%d coarse, %d frames ===%n",
                name, sw, sh, win, win, FRAMES);
        System.out.printf("    truth: quarter-pixel exact, |motion| up to %.2f coarse px, "
                + "margin %d fine px, maxShift %.0f px, noise sd %.2f grey levels%n",
                reach, margin, maxShift, NOISE_FRACTION * sd);

        String onlyCondition = System.getProperty("logratio.onlyCondition", "");
        String onlyEstimator = System.getProperty("logratio.onlyEstimator", "");
        String estimatorSet = System.getProperty("logratio.estimatorSet", "");
        String onlyRecon = System.getProperty("logratio.onlyRecon", "");

        for (Condition condition : Condition.values()) {
            if (!onlyCondition.isEmpty() && !onlyCondition.equals(condition.name())) continue;
            float[][] plane = new float[FRAMES][];
            for (int t = 0; t < FRAMES; t++) {
                plane[t] = frame(fine, sw, sh, win, margin, fx[t], fy[t], condition, t, sd);
            }
            System.out.printf("  %-13s %-38s %-6s %6s %8s %9s %9s %9s%n", condition, "estimator",
                    "recon", "pairs", "cpu ms", "median px", "p90 px", "max px");
            if (Boolean.getBoolean("logratio.printArbiter")) {
                AutomaticInformationSelector.Result evidence =
                        AutomaticInformationSelector.select(
                                plane[0], plane[1], win, win, EPSILON, false);
                System.out.printf("      arbiter: sparse score %.4f, temporal outliers %.4f, "
                                + "tail ratio %.4f%n",
                        evidence.sparseScore, evidence.temporalOutlierFraction,
                        evidence.movingTailRatio);
            }
            if ("arbiter-only".equals(estimatorSet)) continue;
            if ("lag-sweep".equals(estimatorSet)) {
                if (onlyRecon.isEmpty() || "RCC".equals(onlyRecon)) {
                    runLagSweep(plane, win, maxShift, tx, ty, name, condition, csv);
                }
                continue;
            }
            if ("modal-tile".equals(estimatorSet)) {
                if (onlyRecon.isEmpty() || "RCC".equals(onlyRecon)) {
                    runModalTile(plane, win, maxShift, tx, ty, name, condition, csv);
                }
                continue;
            }
            if ("tile-graph".equals(estimatorSet)) {
                if (onlyRecon.isEmpty() || "RCC".equals(onlyRecon)) {
                    runTileGraph(plane, win, maxShift, tx, ty, name, condition, csv);
                }
                continue;
            }
            if ("tile-coordinate".equals(estimatorSet)) {
                if (onlyRecon.isEmpty() || "RCC".equals(onlyRecon)) {
                    runTileCoordinate(plane, win, maxShift, tx, ty, name, condition, csv);
                }
                continue;
            }
            for (Estimator e : Estimator.values()) {
                if (!onlyEstimator.isEmpty() && !onlyEstimator.equals(e.name())) continue;
                boolean sweepArm = !Double.isNaN(e.gradientFraction);
                if ("standing".equals(estimatorSet) && sweepArm) continue;
                if ("legacy-standing".equals(estimatorSet) && !isLegacyStanding(e)) continue;
                if ("gradient-sweep".equals(estimatorSet) && !sweepArm) continue;
                for (Recon r : Recon.values()) {
                    if (!onlyRecon.isEmpty() && !onlyRecon.equals(r.name())) continue;
                    Run run = solve(plane, win, e, r, maxShift);
                    double[] err = errors(run.solution, tx, ty);
                    double median = quantile(err, 0.5);
                    long estMs = (long) (run.extraEstimateMs + run.estimate.cpuMs());
                    long recMs = (long) run.reconcile.cpuMs();
                    long lostMs = (long) (run.extraUnscheduledMs
                            + run.estimate.unscheduledMs() + run.reconcile.unscheduledMs());
                    System.out.printf("  %-13s %-38s %-6s %6d %8d %9.3f %9.3f %9.3f%n", "", e.label, r,
                            run.pairs, estMs, median, quantile(err, 0.9), quantile(err, 1.0));
                    // Loud, and on its own line, because the whole point is that this must never again
                    // be quoted as though it were the cost of the estimator.
                    if (run.estimate.unscheduled()) {
                        System.out.printf("      !! %s%n", run.estimate.note());
                    }
                    Estimator effective = run.effectiveEstimator == null ? e : run.effectiveEstimator;
                    double satPct = effective.pyramid ? effective.ceiling() : Double.NaN;
                    double floorPct = effective.pyramid ? effective.floor() : Double.NaN;
                    String row = String.format(
                            "%s,%s,%s,%s,%d,%d,%d,%d,%d,%.3f,%d,%.4f,%.4f,%.4f,%d,%s,%s%n",
                            name, condition, e.label, r, FRAMES, run.pairs, estMs, recMs,
                            estMs + recMs, (estMs + recMs) / (double) run.pairs, lostMs,
                            median, quantile(err, 0.9), quantile(err, 1.0), run.refused,
                            Double.isNaN(satPct) ? "none" : String.format("%.1f", satPct),
                            Double.isNaN(floorPct) ? "none" : String.format("%.1f", floorPct));
                    Files.write(csv, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                }
            }
        }
    }

    /** The nine estimators forming the historical 216-row acceptance matrix. */
    private static boolean isLegacyStanding(Estimator estimator) {
        switch (estimator) {
            case LOGRATIO_TUKEY:
            case LOGRATIO_TUKEY_NO_TOP_10:
            case LOGRATIO_TUKEY_NO_TOP_25:
            case LOGRATIO_TUKEY_NO_BOTTOM_25:
            case LOGRATIO_HUBER:
            case LOGRATIO_WOODS:
            case SSD:
            case PHASE_CORRELATION:
            case CROSS_CORRELATION:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------------------------ //

    /**
     * Percentile above which a pixel is excluded as saturated, or NaN for no exclusion.
     *
     * <p>{@code PairAligner.Options} has carried {@code saturationMax} since the first version and it
     * had never been measured. The reason to measure it is {@link Condition#CHANGE_BLOCKS}, where the
     * log-ratio family loses to phase correlation by 15x: a constant block at twice the 99.5th
     * percentile is exactly what an intensity ceiling is for. The blocks cover 10% of the field, so a
     * cut has to be near the 90th percentile to reach them, and the cost of that cut on the other three
     * conditions is the other half of the measurement.
     */
    private static double saturationPercentile() {
        String s = System.getProperty("logratio.saturationPercentile", "");
        return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }

    /**
     * Percentile below which a pixel is excluded as background, or NaN for no exclusion.
     *
     * <p>The mirror of {@link #saturationPercentile}, and the more interesting one. Excluding the
     * <em>dimmest</em> pixels cannot be justified the way excluding the brightest can — there is no
     * artefact down there to remove — so the question it answers is different: <b>how much of the frame
     * does the solver actually need?</b> Every excluded pixel is one fewer residual, one fewer
     * bilinear sample and one fewer weight per iteration, at every level of the pyramid, for every pair.
     * If accuracy survives a large cut, the cut is a speed control rather than a compromise.
     *
     * <p>It is not the same as {@code PixelSupport.GRADIENT}, which keeps pixels whose <em>gradient</em>
     * exceeds a fraction of the median gradient. That selects informative pixels but still visits every
     * pixel to decide, and it does not affect the gain estimate or the reported residual. An intensity
     * floor is applied once, in {@link LogPlane}, and removes the pixel from everything downstream.
     */
    private static double floorPercentile() {
        String s = System.getProperty("logratio.floorPercentile", "");
        return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }

    private static final class Run {
        final Reconciler.Solution solution;
        final int pairs;
        /** Pair estimation, as CPU time and elapsed time. Quote the former. See {@link Timing}. */
        final Timing estimate;
        final Timing reconcile;
        final int refused;
        /** Recording-level selector work performed before pair estimation. */
        final double extraEstimateMs;
        final double extraUnscheduledMs;
        /** Concrete estimator selected by a recording-level automatic arm. */
        final Estimator effectiveEstimator;

        Run(Reconciler.Solution solution, int pairs, Timing estimate, Timing reconcile, int refused) {
            this(solution, pairs, estimate, reconcile, refused, 0, 0, null);
        }

        Run(Reconciler.Solution solution, int pairs, Timing estimate, Timing reconcile, int refused,
            double extraEstimateMs, double extraUnscheduledMs, Estimator effectiveEstimator) {
            this.solution = solution;
            this.pairs = pairs;
            this.estimate = estimate;
            this.reconcile = reconcile;
            this.refused = refused;
            this.extraEstimateMs = extraEstimateMs;
            this.extraUnscheduledMs = extraUnscheduledMs;
            this.effectiveEstimator = effectiveEstimator;
        }
    }

    /** One pilot or warm-started multi-lag pass used only by the temporal-support experiment. */
    private static final class LagPairRun {
        final Reconciler.Solution solution;
        final Transform[] measured;
        final Timing estimate;
        final Timing reconcile;
        final int refused;

        LagPairRun(Reconciler.Solution solution, Transform[] measured, Timing estimate,
                   Timing reconcile, int refused) {
            this.solution = solution;
            this.measured = measured;
            this.estimate = estimate;
            this.reconcile = reconcile;
            this.refused = refused;
        }
    }

    /**
     * Two-pass lag-graph pixel-selection screen.
     *
     * <p>The first pass is the unmodified production estimator. Its reconciled transforms place all
     * frames in one coordinate system. {@link LagPixelSelector} then asks which gradient-qualified
     * locations remain inconsistent across lags. Every non-zero row pays for the pilot, scoring,
     * support construction and warm-started refit; otherwise a masked refit could look fast merely by
     * hiding the pass that created its mask.
     */
    private static void runLagSweep(float[][] plane, int win, double maxShift,
                                    double[] truthX, double[] truthY, String seed,
                                    Condition condition, Path csv) throws IOException {
        PairAligner.Options options = Estimator.LOGRATIO_TUKEY.options(maxShift);
        int levels = options.levelsFor(win, win);
        LogPlane[][] pyramids = new LogPlane[plane.length][];
        LogPlane[] full = new LogPlane[plane.length];
        for (int t = 0; t < plane.length; t++) {
            pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON).pyramid(levels);
            full[t] = pyramids[t][0];
        }
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, plane.length, 0, 1, LAGS);
        LagPairRun pilot = solveLagPairs(pyramids, plan, options, null, null);

        String pilotProperty = System.getProperty("logratio.lagPilot", "graph");
        LagPilot pilotKind;
        try {
            pilotKind = LagPilot.valueOf(pilotProperty.trim().toUpperCase());
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("logratio.lagPilot must be graph or oracle, was "
                    + pilotProperty);
        }
        Transform[] scoreCumulative;
        if (pilotKind == LagPilot.ORACLE) {
            scoreCumulative = new Transform[plane.length];
            for (int t = 0; t < plane.length; t++) {
                scoreCumulative[t] = Transform.translation(truthX[t], truthY[t]);
            }
        } else {
            scoreCumulative = pilot.solution.cumulative;
        }

        Timing scoreTiming = Timing.start();
        LagPixelSelector.Scores scores = LagPixelSelector.score(full, scoreCumulative, LAGS);
        scoreTiming.stop();
        System.out.printf("      lag scores: %s pilot, %,d of %,d pixels gradient-qualified, %s%n",
                pilotKind.name().toLowerCase(), scores.eligibleCount, win * win, scoreTiming);

        double[] percentages = lagPercentages();
        boolean hasZero = false;
        for (double percentage : percentages) if (percentage == 0) hasZero = true;
        if (hasZero) {
            LagPairRun control = solveLagPairs(pyramids, plan, options,
                    startsFrom(pilot.solution, plan), null);
            writeLagRow(seed, condition, "lag second-pass control 0% ("
                            + pilotKind.name().toLowerCase() + ")",
                    control, plan.size(), truthX, truthY, csv,
                    pilot.estimate.cpuMs(), pilot.reconcile.cpuMs(),
                    pilot.estimate.unscheduledMs() + pilot.reconcile.unscheduledMs());
        }

        String onlyArm = System.getProperty("logratio.lagArm", "").trim();
        for (LagArm arm : LagArm.values()) {
            if (!onlyArm.isEmpty() && !arm.name().equalsIgnoreCase(onlyArm)) continue;
            for (double percentage : percentages) {
                if (percentage <= 0) continue;
                boolean[] referenceMask = arm == LagArm.STRATIFIED_LOWEST_ANCHOR_TRUST
                        ? LagPixelSelector.stratifiedMask(
                                scores, arm.score, percentage, arm.removeHighest, 32, 4)
                        : LagPixelSelector.mask(
                                scores, arm.score, percentage, arm.removeHighest);
                int removed = LagPixelSelector.removedEligible(referenceMask, scores);
                Timing supportTiming = Timing.start();
                boolean[][][] supportByFrame = new boolean[plane.length][][];
                for (int t = 0; t < plane.length; t++) {
                    supportByFrame[t] = LagPixelSelector.sourceSupport(
                            referenceMask, win, win, pyramids[t], scoreCumulative[t]);
                }
                supportTiming.stop();
                LagPairRun selected = solveLagPairs(pyramids, plan, options,
                        startsFrom(pilot.solution, plan), supportByFrame);
                double effective = scores.eligibleCount == 0
                        ? 0 : 100.0 * removed / scores.eligibleCount;
                String label = String.format("lag %s %.0f%% (%s)", arm.label, percentage,
                        pilotKind.name().toLowerCase());
                System.out.printf("      %-46s removed %,d / %,d (%.1f%%)%n",
                        label, removed, scores.eligibleCount, effective);
                double extraEstimateMs = pilot.estimate.cpuMs() + scoreTiming.cpuMs()
                        + supportTiming.cpuMs();
                double extraReconcileMs = pilot.reconcile.cpuMs();
                double extraLostMs = pilot.estimate.unscheduledMs()
                        + pilot.reconcile.unscheduledMs() + scoreTiming.unscheduledMs()
                        + supportTiming.unscheduledMs();
                writeLagRow(seed, condition, label, selected, plan.size(), truthX, truthY, csv,
                        extraEstimateMs, extraReconcileMs, extraLostMs);
            }
        }
    }

    private static double[] lagPercentages() {
        String property = System.getProperty("logratio.lagPercentages", "0,5,10,25,50,75");
        String[] fields = property.split(",");
        double[] out = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            out[i] = Double.parseDouble(fields[i].trim());
            if (!(out[i] >= 0 && out[i] <= 100)) {
                throw new IllegalArgumentException("lag removal percentage must be in [0,100], was "
                        + out[i]);
            }
        }
        return out;
    }

    /** Fixed recording-level choice made from image evidence, never from a modality label. */
    static Estimator chooseAutoInformation(double sparseScore, double tailRatio,
                                           boolean explicitIntensityBand) {
        return estimatorFor(AutomaticInformationSelector.choose(
                sparseScore, tailRatio, explicitIntensityBand));
    }

    /** Resolve and report the concrete estimator chosen by the automatic recording-level arm. */
    static Estimator resolveAutoInformation(float[][] plane, int win) {
        boolean explicitBand = !Double.isNaN(saturationPercentile())
                || !Double.isNaN(floorPercentile());
        AutomaticInformationSelector.Result evidence = AutomaticInformationSelector.select(
                plane[0], plane[1], win, win, EPSILON, explicitBand);
        Estimator selected = estimatorFor(evidence.choice);
        System.out.printf("      automatic support: sparse %.4f, tail %s -> %s%n",
                evidence.sparseScore,
                Double.isNaN(evidence.movingTailRatio)
                        ? "not needed" : String.format("%.4f", evidence.movingTailRatio),
                selected.label);
        return selected;
    }

    private static Estimator estimatorFor(AutomaticInformationSelector.Choice choice) {
        switch (choice) {
            case SPARSE_MUTUAL_NOISE_EDGES:
                return Estimator.LOGRATIO_TUKEY_MUTUAL_NOISE;
            case STABLE_EXCLUDE_BRIGHTEST_TEN_PERCENT:
                return Estimator.LOGRATIO_TUKEY_NO_TOP_10;
            case EXPLICIT_INTENSITY_BAND:
            case MOVING_STANDARD_GRADIENT:
            default:
                return Estimator.LOGRATIO_TUKEY;
        }
    }

    /** Exact benchmark RCC solve exposed only to the TIFF comparison writer. */
    static Reconciler.Solution solveRccForStacks(float[][] plane, int win,
                                                  Estimator estimator, double maxShift) {
        return solve(plane, win, estimator, Recon.RCC, maxShift).solution;
    }

    /** Benchmark-only lag-graph comparison exposed to the TIFF comparison writer. */
    static Reconciler.Solution solveLagInstabilityForStacks(float[][] plane, int win,
                                                             double maxShift,
                                                             boolean removeMostMoving,
                                                             double removePercent) {
        PairAligner.Options options = Estimator.LOGRATIO_TUKEY.options(maxShift);
        int levels = options.levelsFor(win, win);
        LogPlane[][] pyramids = new LogPlane[plane.length][];
        LogPlane[] full = new LogPlane[plane.length];
        for (int t = 0; t < plane.length; t++) {
            pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON).pyramid(levels);
            full[t] = pyramids[t][0];
        }
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, plane.length, 0, 1, LAGS);
        LagPairRun pilot = solveLagPairs(pyramids, plan, options, null, null);
        LagPixelSelector.Scores scores = LagPixelSelector.score(
                full, pilot.solution.cumulative, LAGS);
        boolean[] referenceMask = LagPixelSelector.mask(scores,
                LagPixelSelector.Score.INSTABILITY, removePercent, removeMostMoving);
        boolean[][][] supportByFrame = new boolean[plane.length][][];
        for (int t = 0; t < plane.length; t++) {
            supportByFrame[t] = LagPixelSelector.sourceSupport(
                    referenceMask, win, win, pyramids[t], pilot.solution.cumulative[t]);
        }
        return solveLagPairs(pyramids, plan, options,
                startsFrom(pilot.solution, plan), supportByFrame).solution;
    }

    /** Benchmark-only global modal-movement comparison exposed to the TIFF writer. */
    static Reconciler.Solution solveModalMovementForStacks(float[][] plane, int win,
                                                            double maxShift) {
        PairAligner.Options options = Estimator.LOGRATIO_TUKEY.options(maxShift);
        int levels = options.levelsFor(win, win);
        LogPlane[][] pyramids = new LogPlane[plane.length][];
        for (int t = 0; t < plane.length; t++) {
            pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON).pyramid(levels);
        }
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, plane.length, 0, 1, LAGS);
        LagPairRun pilot = solveLagPairs(pyramids, plan, options, null, null);
        Transform[] starts = startsFrom(pilot.solution, plan);
        List<Reconciler.Observation> observations = new ArrayList<>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            Transform measured = ModalTileAligner.align(
                    pyramids[edge.from][0], pyramids[edge.to][0], starts[i]).transform;
            observations.add(new Reconciler.Observation(edge.from, edge.to, measured));
        }
        return Reconciler.multiLag(plane.length, observations);
    }

    /**
     * Simple global modal-movement baseline: local tile shifts vote in the two-dimensional
     * displacement plane independently for every lag edge, then the existing graph reconciles the
     * resulting per-edge modes. The pilot is included in the reported time because local gradient
     * refinement needs to start inside the right displacement basin.
     */
    private static void runModalTile(float[][] plane, int win, double maxShift,
                                     double[] truthX, double[] truthY, String seed,
                                     Condition condition, Path csv) throws IOException {
        PairAligner.Options options = Estimator.LOGRATIO_TUKEY.options(maxShift);
        int levels = options.levelsFor(win, win);
        LogPlane[][] pyramids = new LogPlane[plane.length][];
        for (int t = 0; t < plane.length; t++) {
            pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON).pyramid(levels);
        }
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, plane.length, 0, 1, LAGS);
        LagPairRun pilot = solveLagPairs(pyramids, plan, options, null, null);
        writeLagRow(seed, condition, "tile-modal pilot control", pilot, plan.size(),
                truthX, truthY, csv, 0, 0, 0);

        Transform[] starts = startsFrom(pilot.solution, plan);
        Transform[] measured = new Transform[plan.size()];
        double[] angleErrors = new double[plan.size()];
        double[] vectorErrors = new double[plan.size()];
        int diagnosticCount = 0;
        int fallbacks = 0;
        StringBuilder diagnostics = new StringBuilder();
        Timing tileTiming = Timing.start();
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            ModalTileAligner.Result modal = ModalTileAligner.align(
                    pyramids[edge.from][0], pyramids[edge.to][0], starts[i]);
            measured[i] = modal.transform;
            if (!modal.usable) fallbacks++;

            double truthDx = truthX[edge.to] - truthX[edge.from];
            double truthDy = truthY[edge.to] - truthY[edge.from];
            double truthDirection = Math.toDegrees(Math.atan2(truthDy, truthDx));
            double angleError = angularDistance(modal.directionDegrees, truthDirection);
            double vectorError = Math.hypot(modal.transform.dx - truthDx,
                    modal.transform.dy - truthDy);
            if (Math.hypot(truthDx, truthDy) >= 0.1 && modal.transform.magnitude() >= 0.1) {
                angleErrors[diagnosticCount] = angleError;
                vectorErrors[diagnosticCount] = vectorError;
                diagnosticCount++;
            }
            diagnostics.append(String.format(
                    "%s,%s,%d,%d,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.3f,%.3f,"
                            + "%.3f,%d,%d,%.3f,%.3f,%s,%s%n",
                    seed, condition, edge.from, edge.to, edge.lag(),
                    starts[i].dx, starts[i].dy, modal.transform.dx, modal.transform.dy,
                    truthDx, truthDy, modal.directionDegrees, truthDirection, angleError,
                    modal.tileVotes, modal.inlierTiles, modal.coverageX, modal.coverageY,
                    Double.isFinite(modal.bandwidth)
                            ? String.format("%.5f", modal.bandwidth) : "none",
                    modal.usable));
        }
        tileTiming.stop();
        Files.write(modalDiagnosticsPath(csv), diagnostics.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        List<Reconciler.Observation> observations = new ArrayList<>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            observations.add(new Reconciler.Observation(edge.from, edge.to, measured[i]));
        }
        Timing modalReconcile = Timing.start();
        Reconciler.Solution modalSolution = Reconciler.multiLag(plane.length, observations);
        modalReconcile.stop();
        LagPairRun modalRun = new LagPairRun(modalSolution, measured,
                tileTiming, modalReconcile, fallbacks);
        writeLagRow(seed, condition, "tile modal movement", modalRun, plan.size(),
                truthX, truthY, csv, pilot.estimate.cpuMs(), pilot.reconcile.cpuMs(),
                pilot.estimate.unscheduledMs() + pilot.reconcile.unscheduledMs());

        double medianAngle = diagnosticCount == 0 ? Double.NaN
                : quantile(java.util.Arrays.copyOf(angleErrors, diagnosticCount), 0.5);
        double medianVector = diagnosticCount == 0 ? Double.NaN
                : quantile(java.util.Arrays.copyOf(vectorErrors, diagnosticCount), 0.5);
        System.out.printf("      tile-mode diagnostics: %d/%d fallbacks, median edge vector error "
                        + "%.3f px, median direction error %.2f deg, %s%n",
                fallbacks, plan.size(), medianVector, medianAngle, tileTiming);
    }

    /**
     * Recording-level tile graph: the same reference-grid tiles vote on every multi-lag edge, each
     * tile's observations are reconciled into a trajectory, and the densest field-wide trajectory
     * layer is compared with the production pilot. The guarded row changes the pilot only when the
     * layer's correction is larger than its measured tile-to-tile uncertainty.
     */
    private static void runTileGraph(float[][] plane, int win, double maxShift,
                                     double[] truthX, double[] truthY, String seed,
                                     Condition condition, Path csv) throws IOException {
        PairAligner.Options options = Estimator.LOGRATIO_TUKEY.options(maxShift);
        int levels = options.levelsFor(win, win);
        LogPlane[][] pyramids = new LogPlane[plane.length][];
        for (int t = 0; t < plane.length; t++) {
            pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON).pyramid(levels);
        }
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, plane.length, 0, 1, LAGS);
        LagPairRun pilot = solveLagPairs(pyramids, plan, options, null, null);
        writeLagRow(seed, condition, "tile-graph pilot control", pilot, plan.size(),
                truthX, truthY, csv, 0, 0, 0);

        Transform[] starts = startsFrom(pilot.solution, plan);
        List<TileLagGraph.EdgeVotes> edgeVotes = new ArrayList<>(plan.size());
        Timing voteTiming = Timing.start();
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            List<ModalTileAligner.Vote> votes = ModalTileAligner.votes(
                    pyramids[edge.from][0], pyramids[edge.to][0], starts[i],
                    pilot.solution.cumulative[edge.from]);
            edgeVotes.add(new TileLagGraph.EdgeVotes(
                    edge.from, edge.to, starts[i], votes, win, win));
        }
        voteTiming.stop();

        Timing graphTiming = Timing.start();
        TileLagGraph.Result graph = TileLagGraph.reconcile(
                plane.length, pilot.solution.cumulative, edgeVotes, win, win);
        graphTiming.stop();

        LagPairRun raw = new LagPairRun(Reconciler.fixed(graph.raw, 0), null,
                voteTiming, graphTiming, 0);
        LagPairRun guarded = new LagPairRun(Reconciler.fixed(graph.guarded, 0), null,
                voteTiming, graphTiming, 0);
        double pilotEstimateMs = pilot.estimate.cpuMs();
        double pilotReconcileMs = pilot.reconcile.cpuMs();
        double pilotUnscheduledMs = pilot.estimate.unscheduledMs()
                + pilot.reconcile.unscheduledMs();
        writeLagRow(seed, condition, "tile graph raw trajectory", raw, plan.size(),
                truthX, truthY, csv, pilotEstimateMs, pilotReconcileMs, pilotUnscheduledMs);
        writeLagRow(seed, condition, "tile graph guarded correction", guarded, plan.size(),
                truthX, truthY, csv, pilotEstimateMs, pilotReconcileMs, pilotUnscheduledMs);

        boolean[] referenceSupport;
        if (graph.usable) {
            referenceSupport = graph.referenceSupport;
        } else {
            referenceSupport = new boolean[win * win];
            java.util.Arrays.fill(referenceSupport, true);
        }
        Timing supportTiming = Timing.start();
        boolean[][][] supportByFrame = new boolean[plane.length][][];
        for (int t = 0; t < plane.length; t++) {
            supportByFrame[t] = LagPixelSelector.sourceSupport(
                    referenceSupport, win, win, pyramids[t], pilot.solution.cumulative[t]);
        }
        supportTiming.stop();
        LagPairRun supported = solveLagPairs(pyramids, plan, options, starts, supportByFrame);
        writeLagRow(seed, condition, "tile-layer majority support refit", supported, plan.size(),
                truthX, truthY, csv,
                pilotEstimateMs + voteTiming.cpuMs() + supportTiming.cpuMs(),
                pilotReconcileMs + graphTiming.cpuMs(),
                pilotUnscheduledMs + voteTiming.unscheduledMs()
                        + graphTiming.unscheduledMs() + supportTiming.unscheduledMs());

        LogPlane[] full = new LogPlane[plane.length];
        for (int t = 0; t < plane.length; t++) full[t] = pyramids[t][0];
        Timing dualScoreTiming = Timing.start();
        LagPixelSelector.Scores pilotScores = LagPixelSelector.score(
                full, pilot.solution.cumulative, LAGS);
        LagPixelSelector.Scores tileScores = LagPixelSelector.score(full, graph.raw, LAGS);
        LagPixelSelector.Scores guardedScores = LagPixelSelector.score(full, graph.guarded, LAGS);
        LagPixelSelector.Scores dualScores = LagPixelSelector.dualCoordinateTrust(
                pilotScores, tileScores, referenceSupport);
        boolean[] dualMask = LagPixelSelector.mask(
                dualScores, LagPixelSelector.Score.ANCHOR_TRUST, 55, false);
        boolean[][][] dualSupportByFrame = new boolean[plane.length][][];
        for (int t = 0; t < plane.length; t++) {
            dualSupportByFrame[t] = LagPixelSelector.sourceSupport(
                    dualMask, win, win, pyramids[t], pilot.solution.cumulative[t]);
        }
        boolean[] tileCoordinateMask = LagPixelSelector.mask(
                tileScores, LagPixelSelector.Score.ANCHOR_TRUST, 55, false);
        boolean[] guardedCoordinateMask = LagPixelSelector.mask(
                guardedScores, LagPixelSelector.Score.ANCHOR_TRUST, 55, false);
        boolean[][][] tileCoordinateSupport = new boolean[plane.length][][];
        boolean[][][] guardedCoordinateSupport = new boolean[plane.length][][];
        for (int t = 0; t < plane.length; t++) {
            tileCoordinateSupport[t] = LagPixelSelector.sourceSupport(
                    tileCoordinateMask, win, win, pyramids[t], graph.raw[t]);
            guardedCoordinateSupport[t] = LagPixelSelector.sourceSupport(
                    guardedCoordinateMask, win, win, pyramids[t], graph.guarded[t]);
        }
        dualScoreTiming.stop();
        LagPairRun dualSupported = solveLagPairs(
                pyramids, plan, options, starts, dualSupportByFrame);
        writeLagRow(seed, condition, "dual-coordinate anchor trust 55%", dualSupported,
                plan.size(), truthX, truthY, csv,
                pilotEstimateMs + voteTiming.cpuMs() + dualScoreTiming.cpuMs(),
                pilotReconcileMs + graphTiming.cpuMs(),
                pilotUnscheduledMs + voteTiming.unscheduledMs()
                        + graphTiming.unscheduledMs() + dualScoreTiming.unscheduledMs());
        LagPairRun tileCoordinateRun = solveLagPairs(
                pyramids, plan, options, starts, tileCoordinateSupport);
        writeLagRow(seed, condition, "tile-coordinate anchor trust 55%", tileCoordinateRun,
                plan.size(), truthX, truthY, csv,
                pilotEstimateMs + voteTiming.cpuMs() + dualScoreTiming.cpuMs(),
                pilotReconcileMs + graphTiming.cpuMs(),
                pilotUnscheduledMs + voteTiming.unscheduledMs()
                        + graphTiming.unscheduledMs() + dualScoreTiming.unscheduledMs());
        LagPairRun guardedCoordinateRun = solveLagPairs(
                pyramids, plan, options, starts, guardedCoordinateSupport);
        writeLagRow(seed, condition, "guarded-coordinate anchor trust 55%", guardedCoordinateRun,
                plan.size(), truthX, truthY, csv,
                pilotEstimateMs + voteTiming.cpuMs() + dualScoreTiming.cpuMs(),
                pilotReconcileMs + graphTiming.cpuMs(),
                pilotUnscheduledMs + voteTiming.unscheduledMs()
                        + graphTiming.unscheduledMs() + dualScoreTiming.unscheduledMs());

        double[] pilotErrors = errors(pilot.solution, truthX, truthY);
        double[] rawErrors = errors(raw.solution, truthX, truthY);
        double[] guardedErrors = errors(guarded.solution, truthX, truthY);
        StringBuilder diagnostics = new StringBuilder();
        int corrected = 0;
        for (int t = 0; t < plane.length; t++) {
            if (graph.correctionWeight[t] > 0) corrected++;
            diagnostics.append(String.format(
                    "%s,%s,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,"
                            + "%.5f,%.5f,%.5f,%.5f%n",
                    seed, condition, t,
                    pilot.solution.cumulative[t].dx, pilot.solution.cumulative[t].dy,
                    graph.raw[t].dx, graph.raw[t].dy,
                    graph.guarded[t].dx, graph.guarded[t].dy,
                    truthX[t], truthY[t], graph.correctionWeight[t],
                    pilotErrors[t], rawErrors[t], guardedErrors[t]));
        }
        Files.write(tileGraphDiagnosticsPath(csv),
                diagnostics.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        int supportedPixels = 0;
        for (boolean keep : referenceSupport) if (keep) supportedPixels++;
        System.out.printf("      tile graph: %d/%d trajectory inliers, coverage %.2f x %.2f, "
                        + "bandwidth %.3f px, closure %.3f px, direction error %.2f deg, "
                        + "%d/%d frames corrected, support %.1f%%, votes %s, graph %s%n",
                graph.inlierTracks, graph.tileTracks, graph.coverageX, graph.coverageY,
                graph.trajectoryBandwidth, graph.medianClosure,
                graph.medianDirectionErrorDegrees, corrected, plane.length,
                100.0 * supportedPixels / referenceSupport.length,
                voteTiming, graphTiming);
        System.out.printf("      dual-coordinate support: removed %,d / %,d eligible pixels, %s%n",
                LagPixelSelector.removedEligible(dualMask, dualScores),
                dualScores.eligibleCount, dualScoreTiming);
    }

    /** Accuracy gate for the winning tile-coordinate support mechanism, without rejected side arms. */
    private static void runTileCoordinate(float[][] plane, int win, double maxShift,
                                          double[] truthX, double[] truthY, String seed,
                                          Condition condition, Path csv) throws IOException {
        PairAligner.Options options = Estimator.LOGRATIO_TUKEY.options(maxShift);
        int levels = options.levelsFor(win, win);
        LogPlane[][] pyramids = new LogPlane[plane.length][];
        LogPlane[] full = new LogPlane[plane.length];
        for (int t = 0; t < plane.length; t++) {
            pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON).pyramid(levels);
            full[t] = pyramids[t][0];
        }
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, plane.length, 0, 1, LAGS);
        LagPairRun pilot = solveLagPairs(pyramids, plan, options, null, null);
        writeLagRow(seed, condition, "tile-coordinate pilot control", pilot, plan.size(),
                truthX, truthY, csv, 0, 0, 0);

        Transform[] starts = startsFrom(pilot.solution, plan);
        List<TileLagGraph.EdgeVotes> edgeVotes = new ArrayList<>(plan.size());
        Timing voteTiming = Timing.start();
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            List<ModalTileAligner.Vote> votes = FastTileVotes.votes(
                    pyramids[edge.from][0], pyramids[edge.to][0], starts[i],
                    pilot.solution.cumulative[edge.from]);
            edgeVotes.add(new TileLagGraph.EdgeVotes(
                    edge.from, edge.to, starts[i], votes, win, win));
        }
        voteTiming.stop();
        Timing graphTiming = Timing.start();
        TileLagGraph.Result graph = TileLagGraph.reconcile(
                plane.length, pilot.solution.cumulative, edgeVotes, win, win);
        graphTiming.stop();

        Timing scoreTiming = Timing.start();
        LagPixelSelector.Scores scores = LagPixelSelector.score(full, graph.raw, LAGS);
        boolean[] mask = LagPixelSelector.mask(
                scores, LagPixelSelector.Score.ANCHOR_TRUST, 55, false);
        boolean[][][] supportByFrame = new boolean[plane.length][][];
        for (int t = 0; t < plane.length; t++) {
            supportByFrame[t] = LagPixelSelector.sourceSupport(
                    mask, win, win, pyramids[t], graph.raw[t]);
        }
        scoreTiming.stop();
        LagPairRun selected = solveLagPairs(pyramids, plan, options, starts, supportByFrame);
        writeLagRow(seed, condition, "tile-coordinate anchor trust 55%", selected, plan.size(),
                truthX, truthY, csv,
                pilot.estimate.cpuMs() + voteTiming.cpuMs() + scoreTiming.cpuMs(),
                pilot.reconcile.cpuMs() + graphTiming.cpuMs(),
                pilot.estimate.unscheduledMs() + pilot.reconcile.unscheduledMs()
                        + voteTiming.unscheduledMs() + graphTiming.unscheduledMs()
                        + scoreTiming.unscheduledMs());
        System.out.printf("      tile-coordinate mask: %d/%d trajectory inliers, removed %,d / %,d "
                        + "eligible pixels, votes %s, graph %s, score %s%n",
                graph.inlierTracks, graph.tileTracks,
                LagPixelSelector.removedEligible(mask, scores), scores.eligibleCount,
                voteTiming, graphTiming, scoreTiming);
    }

    private static Path modalDiagnosticsPath(Path csv) {
        String property = System.getProperty("logratio.modalDiagnosticsName", "");
        if (!property.isEmpty()) return csv.resolveSibling(property);
        String name = csv.getFileName().toString();
        String stem = name.toLowerCase().endsWith(".csv")
                ? name.substring(0, name.length() - 4) : name;
        return csv.resolveSibling(stem + "_modal_edges.csv");
    }

    private static Path tileGraphDiagnosticsPath(Path csv) {
        String property = System.getProperty("logratio.tileGraphDiagnosticsName", "");
        if (!property.isEmpty()) return csv.resolveSibling(property);
        String name = csv.getFileName().toString();
        String stem = name.toLowerCase().endsWith(".csv")
                ? name.substring(0, name.length() - 4) : name;
        return csv.resolveSibling(stem + "_tile_graph_frames.csv");
    }

    private static double angularDistance(double a, double b) {
        double difference = Math.abs(a - b) % 360.0;
        return difference > 180 ? 360 - difference : difference;
    }

    private static Transform[] startsFrom(Reconciler.Solution pilot,
                                          List<Reconciler.Observation> plan) {
        Transform[] starts = new Transform[plan.size()];
        for (int i = 0; i < starts.length; i++) {
            Reconciler.Observation edge = plan.get(i);
            starts[i] = pilot.cumulative[edge.from].inverse().then(pilot.cumulative[edge.to]);
        }
        return starts;
    }

    private static LagPairRun solveLagPairs(LogPlane[][] pyramids,
                                            List<Reconciler.Observation> plan,
                                            PairAligner.Options options, Transform[] starts,
                                            boolean[][][] supportByFrame) {
        Transform[] measured = new Transform[plan.size()];
        int refused = 0;
        Timing estimate = Timing.start();
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            PairAligner.Fit fit;
            if (starts == null) {
                fit = PairAligner.align(pyramids[edge.from], pyramids[edge.to], options);
            } else {
                boolean[][] support = supportByFrame == null ? null : supportByFrame[edge.from];
                fit = PairAligner.alignFrom(pyramids[edge.from], pyramids[edge.to], options,
                        starts[i], support);
            }
            measured[i] = fit.transform;
            if (fit.status != PairAligner.Status.OK) {
                if (fit.status == PairAligner.Status.REFUSED_LOW_OVERLAP) refused++;
            }
        }
        estimate.stop();

        List<Reconciler.Observation> observations = new ArrayList<>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation edge = plan.get(i);
            observations.add(new Reconciler.Observation(edge.from, edge.to, measured[i]));
        }
        Timing reconcile = Timing.start();
        Reconciler.Solution solution = Reconciler.multiLag(pyramids.length, observations);
        reconcile.stop();
        return new LagPairRun(solution, measured, estimate, reconcile, refused);
    }

    private static void writeLagRow(String seed, Condition condition, String label,
                                    LagPairRun run, int pairs, double[] truthX, double[] truthY,
                                    Path csv, double extraEstimateMs, double extraReconcileMs,
                                    double extraUnscheduledMs) throws IOException {
        double[] err = errors(run.solution, truthX, truthY);
        long estMs = (long) (extraEstimateMs + run.estimate.cpuMs());
        long recMs = (long) (extraReconcileMs + run.reconcile.cpuMs());
        long lostMs = (long) (extraUnscheduledMs + run.estimate.unscheduledMs()
                + run.reconcile.unscheduledMs());
        double median = quantile(err, 0.5);
        System.out.printf("  %-13s %-38s %-6s %6d %8d %9.3f %9.3f %9.3f%n", "", label,
                Recon.RCC, pairs, estMs + recMs, median, quantile(err, 0.9), quantile(err, 1.0));
        String row = String.format(
                "%s,%s,%s,%s,%d,%d,%d,%d,%d,%.3f,%d,%.4f,%.4f,%.4f,%d,none,none%n",
                seed, condition, label, Recon.RCC, FRAMES, pairs, estMs, recMs, estMs + recMs,
                (estMs + recMs) / (double) pairs, lostMs, median,
                quantile(err, 0.9), quantile(err, 1.0), run.refused);
        Files.write(csv, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    private static Run solve(float[][] plane, int win, Estimator e, Recon r, double maxShift) {
        if (e == Estimator.LOGRATIO_AUTO_INFORMATION) {
            Timing decisionTiming = Timing.start();
            Estimator selected = resolveAutoInformation(plane, win);
            decisionTiming.stop();
            Run run = solve(plane, win, selected, r, maxShift);
            return new Run(run.solution, run.pairs, run.estimate, run.reconcile, run.refused,
                    run.extraEstimateMs + decisionTiming.cpuMs(),
                    run.extraUnscheduledMs + decisionTiming.unscheduledMs(), selected);
        }

        int n = plane.length;
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                r == Recon.CHAIN ? Reconciler.Reference.CONSECUTIVE : Reconciler.Reference.MULTILAG,
                n, 0, 1, LAGS);

        LogPlane[][] pyramids = null;
        if (e.pyramid) {
            int levels = e.options(maxShift).levelsFor(win, win);
            double satPct = e.ceiling();
            double floorPct = e.floor();
            pyramids = new LogPlane[n][];
            for (int t = 0; t < n; t++) {
                // Per frame, not once for the recording: a fade moves the whole intensity
                // distribution, so a fixed absolute ceiling would exclude a different fraction of each
                // frame and manufacture exactly the frame-varying support the criterion must not have.
                double satMax = Double.isNaN(satPct)
                        ? LogPlane.NO_SATURATION
                        : percentile(plane[t], satPct);
                double floor = Double.isNaN(floorPct)
                        ? LogPlane.NO_FLOOR
                        : percentile(plane[t], floorPct);
                pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON,
                        floor, satMax).pyramid(levels);
            }
        }

        Transform[] measured = new Transform[plan.size()];
        int refused = 0;
        PairAligner.Options options = e.options(maxShift);
        // Per-pair timing is off by default but is the whole point when chasing a stall: a per-arm
        // total cannot tell you whether an outlier was one pair or all of them. It records CPU and
        // elapsed time separately, because the one time this was needed in anger the answer turned out
        // to be neither — the arm had spent three hours suspended. See Timing.
        boolean perPair = Boolean.getBoolean("logratio.perPairTiming");
        Timing[] pairTime = perPair ? new Timing[plan.size()] : null;
        int[] pairIters = perPair ? new int[plan.size()] : null;
        Timing estimate = Timing.start();
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation ob = plan.get(i);
            Timing pair = perPair ? Timing.start() : null;
            if (e.pyramid) {
                PairAligner.Fit fit = PairAligner.align(pyramids[ob.from], pyramids[ob.to], options);
                measured[i] = fit.transform;
                if (fit.status != PairAligner.Status.OK) refused++;
                if (perPair) pairIters[i] = fit.iterations;
            } else {
                double[] d = PhaseCorrelation.shift(plane[ob.from], plane[ob.to], win, win,
                        e == Estimator.PHASE_CORRELATION);
                measured[i] = Transform.translation(d[0], d[1]);
            }
            if (perPair) pairTime[i] = pair.stop();
        }
        estimate.stop();
        if (perPair) reportSlowestPairs(plan, pairTime, pairIters, measured);

        Timing reconcile = Timing.start();
        Reconciler.Solution solution;
        if (r == Recon.CHAIN) {
            solution = Reconciler.chain(measured);
        } else {
            List<Reconciler.Observation> obs = new ArrayList<>(plan.size());
            for (int i = 0; i < plan.size(); i++) {
                obs.add(new Reconciler.Observation(plan.get(i).from, plan.get(i).to, measured[i]));
            }
            solution = Reconciler.multiLag(n, obs);
        }
        reconcile.stop();
        return new Run(solution, plan.size(), estimate, reconcile, refused);
    }

    /**
     * The five slowest pairs of an arm, with their lag, iteration count and answer.
     *
     * <p>Ranked by CPU time and printed with elapsed time beside it. Ranking by elapsed time would put
     * whichever pair happened to straddle a standby at the top of the list and send the reader after a
     * defect that is not in the code — which is exactly what happened on 2026-08-10.
     */
    private static void reportSlowestPairs(List<Reconciler.Observation> plan, Timing[] time,
                                           int[] iters, Transform[] measured) {
        Integer[] order = new Integer[time.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(time[b].cpuNs(), time[a].cpuNs()));
        long total = 0;
        long lost = 0;
        for (Timing t : time) {
            total += t.cpuNs();
            lost += t.unscheduledNs();
        }
        System.out.printf("      slowest pairs (total %.1f s cpu over %d pairs%s):%n",
                total / 1e9, time.length,
                lost > 1_000_000_000L ? String.format(", plus %.1f s not computing", lost / 1e9) : "");
        for (int k = 0; k < Math.min(5, order.length); k++) {
            int i = order[k];
            Reconciler.Observation ob = plan.get(i);
            System.out.printf("        %3d->%3d lag %2d  %9.3f s cpu  %9.3f s wall  %5.1f%% of arm  "
                            + "iters %3d  dx %8.3f dy %8.3f%n",
                    ob.from, ob.to, ob.to - ob.from, time[i].cpuNs() / 1e9, time[i].wallNs() / 1e9,
                    100.0 * time[i].cpuNs() / Math.max(1, total), iters[i],
                    measured[i] == null ? Double.NaN : measured[i].dx,
                    measured[i] == null ? Double.NaN : measured[i].dy);
        }
    }

    /**
     * One coarse frame: window the fine source at a whole-pixel offset, average {@value #FINE}x
     * {@value #FINE} blocks down, then apply the condition and add independent noise.
     *
     * <p>Because the averaging blocks start at the window origin, moving that origin by one fine pixel
     * moves the sampling grid by a quarter of a coarse pixel. Noise is added after decimation, which is
     * where a sensor adds it.
     */
    static float[] frame(float[] fine, int sw, int sh, int win, int margin,
                                 int dxFine, int dyFine, Condition condition, int t, double sd) {
        float[] source = fine;
        if (condition == Condition.CHANGE_BLOCKS || condition == Condition.CHANGE_MOVED) {
            // Applied before decimation so the change travels with the content, which is what makes this
            // a test of genuine change rather than of a static overlay. Fresh every frame.
            //
            // Two independent streams: positions come from one, content from the other, so both change
            // models put their patches in exactly the same places and only the content differs. With a
            // single stream the two conditions would diverge after the first patch and the comparison
            // would confound where the change is with what it is.
            source = fine.clone();
            Random where = new Random(0x5EEDL * 31 + t);
            Random what = new Random(0xC04FEEL * 17 + t);
            float bright = (float) (percentile(fine, 99.5) * 2.0 + 8);
            int patches = (int) (CHANGE_FRACTION * sw * sh / (double) (FINE_PATCH * FINE_PATCH));
            int room = FINE_PATCH + MOVE_FINE;
            for (int k = 0; k < patches; k++) {
                int px = MOVE_FINE + where.nextInt(Math.max(1, sw - room - MOVE_FINE));
                int py = MOVE_FINE + where.nextInt(Math.max(1, sh - room - MOVE_FINE));
                if (condition == Condition.CHANGE_BLOCKS) {
                    float value = bright * (0.75f + 0.5f * what.nextFloat());
                    for (int y = 0; y < FINE_PATCH; y++) {
                        int row = (py + y) * sw + px;
                        for (int x = 0; x < FINE_PATCH; x++) source[row + x] = value;
                    }
                } else {
                    // Real structure displaced locally. Read from the pristine frame, never from the
                    // copy being written, so patches cannot cascade into one another.
                    int ox = what.nextInt(2 * MOVE_FINE + 1) - MOVE_FINE;
                    int oy = what.nextInt(2 * MOVE_FINE + 1) - MOVE_FINE;
                    for (int y = 0; y < FINE_PATCH; y++) {
                        int dst = (py + y) * sw + px;
                        int src = (py + y + oy) * sw + px + ox;
                        System.arraycopy(fine, src, source, dst, FINE_PATCH);
                    }
                }
            }
        }

        int ox = margin - dxFine;
        int oy = margin - dyFine;
        float[] out = new float[win * win];
        double norm = 1.0 / (FINE * FINE);
        for (int y = 0; y < win; y++) {
            for (int x = 0; x < win; x++) {
                double s = 0;
                for (int j = 0; j < FINE; j++) {
                    int row = (oy + y * FINE + j) * sw + ox + x * FINE;
                    for (int i = 0; i < FINE; i++) s += source[row + i];
                }
                out[y * win + x] = (float) (s * norm);
            }
        }

        if (condition == Condition.GAIN_FADE) {
            double g = Math.pow(2.0, FADE_LOG2 * t / (FRAMES - 1.0));
            for (int i = 0; i < out.length; i++) out[i] *= (float) g;
        }
        // Independent every frame, and seeded from the frame index so a rerun is identical.
        Random noise = new Random(918_273_645L + t);
        float sigma = (float) (NOISE_FRACTION * sd);
        for (int i = 0; i < out.length; i++) {
            out[i] += (float) (sigma * noise.nextGaussian());
            if (out[i] < 0) out[i] = 0;
        }
        return out;
    }

    /**
     * A whole-fine-pixel trajectory, rebased so frame 0 is the origin.
     *
     * <p>Deliberately not a multiple of {@value #FINE}: the point is to exercise every sub-pixel phase,
     * so the step sizes are chosen to land on quarters rather than on whole coarse pixels.
     */
    static void trajectory(int[] fx, int[] fy) {
        ControlledMotionProfile.CURVED_OSCILLATING_DRIFT.fill(fx, fy);
    }

    /**
     * The scalar plane used as a seed.
     *
     * <p>RGB input keeps the original fixture path exactly: channel 3 is the IncuCyte composite's
     * phase-contrast channel (see {@link IncucyteSeries}), copied through the same
     * {@link ByteProcessor} as before. A scalar TIFF is already one physical channel, so its unsigned
     * 8- or 16-bit values (or 32-bit float values) are copied without display-range conversion.
     */
    static float[] seedPlane(ImagePlus imp) throws IOException {
        if (imp.getProcessor() instanceof ColorProcessor) {
            ColorProcessor cp = (ColorProcessor) imp.getProcessor();
            ByteProcessor bp = new ByteProcessor(cp.getWidth(), cp.getHeight());
            cp.getChannel(3, bp);
            byte[] px = (byte[]) bp.getPixels();
            float[] out = new float[px.length];
            for (int i = 0; i < out.length; i++) out[i] = px[i] & 0xff;
            return out;
        }

        Object pixels = imp.getProcessor().getPixels();
        float[] out = new float[imp.getWidth() * imp.getHeight()];
        if (pixels instanceof byte[]) {
            byte[] px = (byte[]) pixels;
            for (int i = 0; i < out.length; i++) out[i] = px[i] & 0xff;
        } else if (pixels instanceof short[]) {
            short[] px = (short[]) pixels;
            for (int i = 0; i < out.length; i++) out[i] = px[i] & 0xffff;
        } else if (pixels instanceof float[]) {
            System.arraycopy(pixels, 0, out, 0, out.length);
        } else {
            throw new IOException("unsupported scalar seed type "
                    + imp.getProcessor().getClass().getSimpleName());
        }
        return out;
    }

    static double[] errors(Reconciler.Solution s, double[] tx, double[] ty) {
        double[] err = new double[tx.length];
        for (int t = 0; t < tx.length; t++) {
            Transform c = s.cumulative[t] == null ? Transform.IDENTITY : s.cumulative[t];
            err[t] = Math.hypot(c.dx - tx[t], c.dy - ty[t]);
        }
        return err;
    }

    static double standardDeviation(float[] a) {
        double mean = 0;
        for (float v : a) mean += v;
        mean /= a.length;
        double s = 0;
        for (float v : a) s += (v - mean) * (v - mean);
        return Math.sqrt(s / a.length);
    }

    static double quantile(double[] v, double q) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        return s[(int) Math.round(q * (s.length - 1))];
    }

    static double percentile(float[] a, double q) {
        float[] s = a.clone();
        java.util.Arrays.sort(s);
        return s[(int) Math.round(q / 100.0 * (s.length - 1))];
    }

}
