/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

/**
 * Pairwise movement by normalised cross-correlation on a pyramid, with sub-pixel peak refinement.
 *
 * <p>The other estimator ({@link PairAligner}) asks "what movement makes the log-ratio between these
 * two frames flat?" This one asks the older and simpler question: "at what offset do these two
 * pictures look most alike?" It is the classical area method — the family TurboReg, StackReg,
 * Correct 3D Drift and every cross-correlation drift corrector belong to. No gradients, no robust
 * norm, no iterative solve against a criterion; a similarity score evaluated on a grid of candidate
 * offsets, coarse to fine.
 *
 * <h2>Why it is written here rather than bound to TurboReg</h2>
 *
 * <p>TurboReg is published under the GNU General Public License v3, which is redistributable but
 * copyleft: a shipped plugin that depends on it becomes GPL, and this project is BSD 3-Clause. The
 * benchmark may drive it reflectively as an optional, unshipped comparison — that distributes
 * nothing — but an estimator a user can select has to be ours. Twenty-odd lines of correlation
 * arithmetic is a small price for keeping the licence position simple.
 *
 * <h2>What is correlated, and why intensity rather than the log</h2>
 *
 * <p>The pyramids handed in are logarithmic, because that is what the rest of the engine uses and
 * sharing them means switching estimator changes nothing about preprocessing, intensity band, memory
 * or how many times a frame is read. This estimator undoes the log per pixel ({@code 2^v}) and
 * correlates <em>intensity</em>, which is what an area method conventionally does and what TurboReg
 * does. The distinction is not cosmetic: the log's slope is {@code 1/(I + epsilon)}, so a log-domain
 * correlation would weight dark pixels far more heavily, and the comparison against the log-ratio fit
 * would then be confounded by the very transform that is supposed to distinguish them.
 *
 * <p><b>Gain invariance comes free, and by a different route.</b> Normalised cross-correlation
 * subtracts each window's mean and divides by its standard deviation, so it is unchanged by any
 * {@code aI + b} rescaling of either frame. A lamp drift or a bleaching step therefore cancels
 * exactly, as it does in the log-ratio fit — but the offset {@code epsilon} added before the log
 * cancels for the same reason, so it need not be undone. What this estimator does <em>not</em> have
 * is the log-ratio fit's separate treatment of genuine change: a region that appears or disappears
 * enters the correlation with full weight, where a redescending norm would have pushed it out.
 *
 * <h2>The search</h2>
 *
 * <ol>
 *   <li><b>Coarsest level, exhaustive integer sweep</b> over every offset within {@code maxShift},
 *       exactly as {@link PairAligner} does and for the same reason: the score surface is not convex,
 *       and a local method alone settles into whichever peak it started nearest.</li>
 *   <li><b>Descend</b>, doubling the offset at each step and re-searching a two-pixel neighbourhood.
 *       Doubling an estimate good to half a coarse pixel leaves an error of about one fine pixel, so
 *       a radius of two covers it with margin.</li>
 *   <li><b>Sub-pixel refinement at full resolution</b> by repeated quadratic fits on a shrinking
 *       three-by-three grid: score the eight neighbours, fit the two-dimensional quadratic through
 *       the nine points, jump to its vertex, shrink the grid, repeat. A single parabola through the
 *       integer neighbourhood — what most implementations stop at — is worth about a twentieth of a
 *       pixel; continuing on interpolated samples is what makes an area method competitive with a
 *       solver at this benchmark's accuracy.</li>
 * </ol>
 *
 * <h2>Two refinements, one of everything else</h2>
 *
 * <p>Step 3 is the only part of this class that {@link Refiner} switches. {@link Refiner#GRID} is the
 * shrinking grid described above and is what {@link PairEstimator.Kind#AREA_CORRELATION} runs;
 * {@link Refiner#NEWTON} replaces it with a step computed from the correlation surface's own
 * derivatives and is what {@link PairEstimator.Kind#AREA_CORRELATION_NEWTON} runs. The preparation,
 * the interpolator, the integer sweep, the pyramid descent, the shift clamp and the reporting are
 * one piece of code shared by both, so a measurement that differs between the two arms differs
 * because of the refinement and because of nothing else. See {@link #newtonRefine} for the
 * derivation and {@code docs/newton_refinement_plan.md} for why it is a third estimator value rather
 * than an edit to the second.
 *
 * <h2>What was tried and measured worse</h2>
 *
 * <p><b>Splitting the displacement between the two frames</b> — sampling {@code a} at {@code -d/2}
 * and {@code b} at {@code +d/2} — is the textbook cure for pixel locking, because it puts both frames
 * through the same interpolator so the offset-dependent smoothing cancels instead of biasing. On the
 * analytic fixture, where the frames are band-limited, it works exactly as advertised: mean error
 * 0.002 px against the log-ratio fit's 0.001 px. <b>On this benchmark's real frames it is four times
 * worse</b> — 0.136 px against 0.031 px per pair on a fiducial recording, with the bias growing
 * rather than cancelling. The frames here are decimated from a supersampled seed and carry energy
 * above their own Nyquist limit; interpolating both of them compounds that error where interpolating
 * one leaves the reference exact. The one-sided form is kept because it is what measures better on
 * the data this plugin is for, and the symmetric form is recorded here so the next person does not
 * spend the afternoon rediscovering it.
 *
 * <p><b>Zero is the incumbent and a candidate must beat it strictly.</b> Same defect, same fix as
 * {@link PairAligner}: on a frame with no texture every offset scores alike, and a sweep that takes
 * the first equal-best it scans returns the corner of its own search box.
 *
 * <p><b>Settings that belong to the log-ratio fit do nothing here</b>, and that is a property of the
 * axis rather than an omission: pixel support, the gradient fraction and the robust norm are all
 * choices about a criterion this estimator does not evaluate. {@code maxShift}, {@code maxSamples},
 * {@code minValidFraction}, {@code maxIterations}, {@code convergence} and the pyramid depth all
 * apply unchanged, so the two arms can be run at the same compute budget.
 */
final class AreaCorrelation {

    /** Neighbourhood re-searched at each finer level, in pixels of that level. */
    private static final int LOCAL_RADIUS = 2;
    /**
     * First refinement grid spacing, in pixels.
     *
     * <p>One whole pixel, not a half, and the reason is a measured failure. The true peak often lies
     * diagonally between four integer offsets; the best of those four then sits on the shoulder of
     * the peak, where a half-pixel grid is not concave at all and a quadratic fit refuses it. On a
     * phase-contrast pair whose true displacement was (2.500, 9.500) the estimator stopped dead at
     * (3.000, 9.000) -- correlation 0.807 where 0.913 was available half a pixel away. A grid of one
     * pixel spans the peak instead of leaning on it.
     */
    private static final double START_STEP = 1.0;
    /** Refinement stops here whatever the tolerance asks; below this the interpolator is the limit. */
    private static final double MIN_STEP = 1.0 / 256;
    /** Grid spacing shrinks by this much per round. */
    private static final double SHRINK = 0.25;
    /**
     * Refinement rounds, whatever {@code maxIterations} says. Each round costs eight correlations
     * over the whole frame, and with a quarter-shrink the fifth round is already asking for less
     * than a hundredth of a pixel -- below what the interpolator can honestly resolve.
     */
    private static final int MAX_REFINEMENT_ROUNDS = 5;

    /**
     * Gauss-Newton iterations, whatever {@code maxIterations} says.
     *
     * <p>Eight is the prototype's cap and it is never reached on well-conditioned data: the step
     * lands inside the convergence tolerance in two or three. It is a runaway guard, not a budget.
     */
    private static final int MAX_NEWTON_ITERATIONS = 8;
    /**
     * Longest single Gauss-Newton step, in pixels.
     *
     * <p>The refinement starts at the integer offset the sweep chose, and the sweep is the part of
     * this estimator that decides <em>which</em> peak is being refined. Capping the step at one pixel
     * means a badly conditioned Hessian can make the refinement useless but cannot make it wrong: it
     * cannot carry the estimate out of the cell the sweep picked and into a neighbouring peak.
     */
    private static final double MAX_NEWTON_STEP = 1.0;
    /** Halvings the line search tries before it concludes the step is not an improvement. */
    private static final int NEWTON_BACKTRACKS = 4;
    /**
     * Fewest samples a Gauss-Newton surface may be built from.
     *
     * <p>Six numbers are being estimated from the pass. {@code minValidFraction} is the real guard
     * and it bites long before this does; this only stops a degenerate window producing a Hessian
     * out of three pixels on a pyramid level small enough for that to be possible.
     */
    private static final int MIN_NEWTON_SAMPLES = 8;
    /**
     * Stride multiplier for the subsampled refinement rounds. Two, so a quarter of the pixels.
     */
    private static final int SUBSAMPLE_FACTOR = 2;
    /** How many opening rounds {@link Refiner#GRID_SUBSAMPLED} runs on that subsample. */
    private static final int SUBSAMPLED_ROUNDS = 2;

    private AreaCorrelation() {
    }

    /**
     * How the sub-pixel peak is located once the integer sweep has chosen a cell. The only thing
     * that differs between {@link PairEstimator.Kind#AREA_CORRELATION} and
     * {@link PairEstimator.Kind#AREA_CORRELATION_NEWTON}.
     */
    enum Refiner {
        /** Quadratic fits on a shrinking three-by-three grid. What ships. See {@link #refine}. */
        GRID,
        /** A step from the surface's own derivatives. See {@link #newtonRefine}. */
        NEWTON,
        /**
         * {@link #GRID} with its opening rounds on a quarter of the pixels. Item 3 of
         * {@code docs/performance_optimisation_plan.md}, measured rather than assumed.
         */
        GRID_SUBSAMPLED
    }

    /**
     * Align {@code b} onto {@code a} with the shipping grid refinement.
     *
     * <p>Both pyramids must come from {@link LogPlane#pyramid(int)} with the same number of levels
     * and the same dimensions, and the answer follows the same sign convention as
     * {@link PairAligner#align}: the motion of the content from {@code a} to {@code b}.
     */
    static PairAligner.Fit align(LogPlane[] a, LogPlane[] b, PairAligner.Options o) {
        return align(a, b, o, Refiner.GRID);
    }

    /**
     * The same alignment with the sub-pixel refinement chosen explicitly.
     *
     * <p>Everything except the refinement is shared, so this overload is the whole of the estimator
     * axis's third value. {@link Refiner#GRID} must reproduce {@link #align(LogPlane[], LogPlane[],
     * PairAligner.Options)} bit for bit, because that is what the automatic selector's retained
     * candidates were fitted to.
     */
    static PairAligner.Fit align(LogPlane[] a, LogPlane[] b, PairAligner.Options o, Refiner refiner) {
        PairAligner.validatePyramids(a, b);
        int levels = a.length;
        int top = levels - 1;

        Transform p = Transform.IDENTITY;
        int iterations = 0;
        boolean anySolved = false;
        boolean converged = true;

        for (int l = top; l >= 0; l--) {
            double scale = 1 << l;
            double maxShiftHere = o.maxShift / scale;
            Window wa = Window.of(a[l], o);
            Window wb = Window.of(b[l], o);

            int radius = l == top ? (int) Math.ceil(maxShiftHere) : LOCAL_RADIUS;
            Peak peak = sweep(wa, wb, p, radius, maxShiftHere, o);
            if (peak.found) {
                p = peak.p;
                anySolved = true;
            }

            if (l == 0) {
                if (peak.found) {
                    Refinement refined;
                    if (refiner == Refiner.NEWTON) {
                        refined = newtonRefine(wa, wb, p, maxShiftHere, o);
                    } else if (refiner == Refiner.GRID_SUBSAMPLED) {
                        refined = refine(wa, wb, p, maxShiftHere, o, SUBSAMPLED_ROUNDS);
                    } else {
                        refined = refine(wa, wb, p, maxShiftHere, o);
                    }
                    p = refined.p;
                    iterations = refined.rounds;
                    converged = refined.converged;
                }
            } else {
                p = clampShift(p.scaleTranslation(2.0), o.maxShift / (scale / 2));
            }
        }

        PairAligner.Status status;
        if (!anySolved) {
            status = PairAligner.Status.REFUSED_LOW_OVERLAP;
        } else if (p.magnitude() >= o.maxShift * (1 - 1e-9)) {
            status = PairAligner.Status.AT_SHIFT_BOUND;
        } else if (!converged) {
            status = PairAligner.Status.NOT_CONVERGED;
        } else {
            status = PairAligner.Status.OK;
        }
        return PairAligner.report(a, b, o, p, iterations, status);
    }

    // ------------------------------------------------------------------------------------ //

    /** An integer sweep's outcome. {@code found} is false when nothing had enough overlap. */
    private static final class Peak {
        final Transform p;
        final boolean found;

        Peak(Transform p, boolean found) {
            this.p = p;
            this.found = found;
        }
    }

    /**
     * Best integer offset within {@code radius} of {@code start}, judged by correlation.
     *
     * <p>The incumbent is {@code start} itself, so a sweep that finds nothing better keeps the
     * estimate it was given rather than drifting to an arbitrary corner.
     */
    private static Peak sweep(Window a, Window b, Transform start, int radius, double maxShiftHere,
                              PairAligner.Options o) {
        double cx = Math.rint(start.dx);
        double cy = Math.rint(start.dy);
        double best = correlation(a, b, cx, cy, o);
        boolean found = Double.isFinite(best);
        double bestX = cx;
        double bestY = cy;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue;
                double x = cx + dx;
                double y = cy + dy;
                // The radius is rounded up, so it can reach past the requested bound. A sweep that
                // ignored the bound could return a shift the user forbade and then double it at
                // every level on the way down.
                if (Math.hypot(x, y) > maxShiftHere + 1e-9) continue;
                double score = correlation(a, b, x, y, o);
                if (!Double.isFinite(score)) continue;
                if (!found || score > best) {
                    best = score;
                    bestX = x;
                    bestY = y;
                    found = true;
                }
            }
        }
        return new Peak(Transform.translation(bestX, bestY), found);
    }

    private static final class Refinement {
        final Transform p;
        final int rounds;
        final boolean converged;

        Refinement(Transform p, int rounds, boolean converged) {
            this.p = p;
            this.rounds = rounds;
            this.converged = converged;
        }
    }

    /**
     * Sub-pixel refinement: a quadratic surface fitted to a shrinking three-by-three grid of scores.
     *
     * <p>Each round scores the eight neighbours of the current offset at spacing {@code h}, fits the
     * full two-dimensional quadratic through the nine points by least squares, and jumps to its
     * vertex. Then {@code h} shrinks and the round repeats.
     *
     * <p><b>Two dimensions rather than two separate one-dimensional fits.</b> A correlation peak on
     * anisotropic structure — filaments, a cell edge, a bacterial rod — is an ellipse whose axes are
     * not the image axes, and separable parabolas ignore the cross term that describes exactly that
     * tilt. They then walk to the peak in a slow zig-zag instead of stepping to it, which costs both
     * accuracy at a fixed budget and evaluations to reach a fixed accuracy.
     *
     * <p>A round that cannot score a neighbour — the pair has slid to the edge of the overlap — or
     * whose fitted surface is not a maximum keeps the offset it had and stops, because an unscored
     * neighbour is missing evidence rather than evidence of a peak.
     */
    private static Refinement refine(Window a, Window b, Transform start, double maxShiftHere,
                                     PairAligner.Options o) {
        return refine(a, b, start, maxShiftHere, o, 0);
    }

    /**
     * The same refinement with its first {@code coarseRounds} rounds run on a subsample.
     *
     * <p>{@code coarseRounds == 0} is the shipping search and executes exactly the same arithmetic it
     * always did — the window swap below is a no-op and the loop is unchanged. That matters: this
     * method is what {@link PairEstimator.Kind#AREA_CORRELATION} runs, and it is frozen.
     *
     * <p><b>The idea, and it is item 3 of {@code docs/performance_optimisation_plan.md}.</b> The first
     * rounds are locating the peak to a tenth of a pixel at a spacing of one pixel and then a quarter;
     * they do not need every pixel to do that. Doubling the stride quarters the samples, so a round
     * costs a quarter as much, and the last rounds — the ones that set the answer — still run at full
     * density. What it risks is exactly where the estimator is weakest: a sparse bead field has few
     * informative pixels to spare, and taking three quarters of them away is worst precisely there.
     */
    private static Refinement refine(Window a, Window b, Transform start, double maxShiftHere,
                                     PairAligner.Options o, int coarseRounds) {
        double x = start.dx;
        double y = start.dy;
        Window ca = coarseRounds > 0 ? a.withStride(a.stride * SUBSAMPLE_FACTOR) : a;
        Window cb = coarseRounds > 0 ? b.withStride(b.stride * SUBSAMPLE_FACTOR) : b;
        double centre = correlation(coarseRounds > 0 ? ca : a, coarseRounds > 0 ? cb : b, x, y, o);
        if (!Double.isFinite(centre)) return new Refinement(start, 0, true);

        double tolerance = Math.max(o.convergence, MIN_STEP);
        int maxRounds = Math.max(1, Math.min(o.maxIterations, MAX_REFINEMENT_ROUNDS));
        double step = START_STEP;
        int rounds = 0;
        double move = Double.POSITIVE_INFINITY;
        double[] grid = new double[9];
        while (rounds < maxRounds && step >= MIN_STEP) {
            boolean coarse = rounds < coarseRounds;
            Window wa = coarse ? ca : a;
            Window wb = coarse ? cb : b;
            // Crossing from the subsample to full density changes the score's meaning, so the
            // incumbent has to be re-read on the new sample set before it is compared against
            // anything. Comparing a full-density candidate against a subsampled incumbent is how a
            // refinement talks itself out of a step it should have taken.
            if (coarseRounds > 0 && rounds == coarseRounds) {
                centre = correlation(wa, wb, x, y, o);
                if (!Double.isFinite(centre)) break;
            }
            boolean scored = true;
            double bestNeighbour = centre;
            int bestI = 0;
            int bestJ = 0;
            for (int j = -1; j <= 1 && scored; j++) {
                for (int i = -1; i <= 1; i++) {
                    double score = i == 0 && j == 0
                            ? centre
                            : correlation(wa, wb, x + i * step, y + j * step, o);
                    if (!Double.isFinite(score)) {
                        scored = false;
                        break;
                    }
                    grid[(j + 1) * 3 + i + 1] = score;
                    if (score > bestNeighbour) {
                        bestNeighbour = score;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }
            rounds++;
            if (!scored) break;

            double[] vertex = vertex(grid, step);
            boolean climbed = false;
            if (vertex != null) {
                double nx = x + vertex[0];
                double ny = y + vertex[1];
                if (Math.hypot(nx, ny) <= maxShiftHere + 1e-9) {
                    double score = correlation(wa, wb, nx, ny, o);
                    // Only accept a step the score agrees with. The quadratic is a model of the
                    // surface, not the surface; on a sharp peak a wide grid underestimates the
                    // curvature and the vertex overshoots.
                    if (Double.isFinite(score) && score >= centre) {
                        move = Math.hypot(vertex[0], vertex[1]);
                        x = nx;
                        y = ny;
                        centre = score;
                        if (move < tolerance) break;
                        climbed = true;
                    }
                }
            }
            if (!climbed && (bestI != 0 || bestJ != 0)) {
                // No usable curvature, but a neighbour is plainly better: walk to it and keep the
                // same spacing. This is the case where the peak lies outside the grid rather than
                // inside it, and refusing to move is how an estimator ends up parked on a whole
                // pixel while a better offset sits next door.
                x += bestI * step;
                y += bestJ * step;
                centre = bestNeighbour;
                move = Math.hypot(bestI * step, bestJ * step);
                continue;
            }
            step *= SHRINK;
        }
        return new Refinement(clampShift(Transform.translation(x, y), maxShiftHere), rounds,
                move < tolerance);
    }

    /**
     * Vertex of the quadratic {@code c0 + c1 x + c2 y + c3 x^2 + c4 y^2 + c5 xy} fitted by least
     * squares to a three-by-three grid of scores at spacing {@code h}, in pixels, clamped to the
     * grid. Null when the fit is not a maximum, which is the honest answer for a saddle, a plateau,
     * or a peak that lies outside the grid entirely.
     */
    private static double[] vertex(double[] grid, double h) {
        double c1 = 0;
        double c2 = 0;
        double c3 = 0;
        double c4 = 0;
        for (int k = -1; k <= 1; k++) {
            c1 += grid[(k + 1) * 3 + 2] - grid[(k + 1) * 3];
            c2 += grid[6 + k + 1] - grid[k + 1];
            c3 += grid[(k + 1) * 3 + 2] + grid[(k + 1) * 3] - 2 * grid[(k + 1) * 3 + 1];
            c4 += grid[6 + k + 1] + grid[k + 1] - 2 * grid[3 + k + 1];
        }
        c1 /= 6;
        c2 /= 6;
        c3 /= 6;
        c4 /= 6;
        double c5 = (grid[8] - grid[6] - grid[2] + grid[0]) / 4;

        double hxx = 2 * c3;
        double hyy = 2 * c4;
        double determinant = hxx * hyy - c5 * c5;
        if (!(hxx < 0) || !(hyy < 0) || !(determinant > 0)) return null;
        double dx = (-hyy * c1 + c5 * c2) / determinant;
        double dy = (c5 * c1 - hxx * c2) / determinant;
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return null;
        return new double[]{h * Math.max(-1, Math.min(1, dx)), h * Math.max(-1, Math.min(1, dy))};
    }

    // ------------------------------------------- the Gauss-Newton refinement, and its derivation //

    /**
     * Everything one pass over the samples yields: the score, its gradient, and its Hessian.
     *
     * <p>{@code hxx}, {@code hyy} and {@code hxy} are the Gauss-Newton approximation to the second
     * derivatives of the sum of squares, so they describe a bowl and are <em>positive</em> definite
     * at a correlation maximum. {@code gx} and {@code gy} are the first derivatives of the
     * correlation itself, which is being maximised, so the step is {@code +H^-1 g}.
     */
    static final class Surface {
        boolean valid;
        double c;
        double gx;
        double gy;
        double hxx;
        double hyy;
        double hxy;
    }

    /**
     * Sub-pixel refinement by a Gauss-Newton step read off the correlation surface's own derivatives.
     *
     * <h2>Why the normalisation is not in the way</h2>
     *
     * <p>Write {@code a_i} for the reference samples and {@code b_i(d)} for the moving frame
     * interpolated at offset {@code d = (dx, dy)}. With {@code n} samples valid in both and centred
     * values {@code A_i = a_i - mean(a)}, {@code B_i = b_i - mean(b)}, the score is
     *
     * <pre>  C(d) = sum(A_i B_i) / sqrt( sum(A_i^2) sum(B_i^2) ) = num / (sa sb)</pre>
     *
     * <p>A zero-normalised cross-correlation is a ratio, not a sum of squares, and its mean and
     * variance terms move with the offset too — so the textbook Gauss-Newton derivation looks
     * inapplicable, and an approximate one would bias the answer while appearing to work. It is not
     * inapplicable. The normalised vectors {@code A/sa} and {@code B/sb} are both unit length, so
     *
     * <pre>  E(d) = sum( A_i/sa - B_i/sb )^2 = 2 - 2 C(d)</pre>
     *
     * <p>Maximising the correlation <em>is</em> minimising a sum of squared residuals
     * {@code r_i = A_i/sa - B_i/sb}. The normalisation is absorbed into the residual exactly, not
     * approximated around.
     *
     * <h2>The closed forms</h2>
     *
     * <p>With {@code b'_i} the spatial gradient of the interpolated moving frame and
     * {@code u_i = b'_i - mean(b')}, and using {@code sum(B_i) = 0} and {@code sum(B_i^2) = Vb}:
     *
     * <pre>
     *   J_i    = dr_i/dd = -u_i/sb + B_i Q/sb^3
     *   P      = sum(A_i b'_i)          Q = sum(B_i b'_i)
     *   grad C = P/(sa sb) - C Q/Vb
     *   H      = ( sum(u u') - Q Q'/Vb ) / Vb
     *   step   = H^-1 grad C
     * </pre>
     *
     * <p>So <b>one pass accumulating fifteen numbers replaces nine whole correlations</b>, and the
     * pass costs about 1.62 plain interpolations rather than three, because the four taps across a
     * row give that row's value and its x-derivative together and the row sums are then shared.
     * Measured against the grid on the analytic fixture: 2.21 times less arithmetic and 2.8 times
     * more accurate. {@code docs/newton_refinement_stage0_findings.md} has the numbers and the checks
     * against a numerical derivative.
     *
     * <h2>Three guards, and what each is for</h2>
     *
     * <ul>
     *   <li><b>Hand back to the grid rather than stopping, whenever no step was taken.</b> A saddle
     *       or a plateau — repeating structure, a single edge — gives a step that points nowhere in
     *       particular, and a derivative method has more ways to fall off a peak than a grid search
     *       does. The plan left open whether the right response was to stop or to hand back; the
     *       first Stage 2 run answered it. Stopping returns the integer offset the sweep found, which
     *       is an error of up to sqrt(2)/2 px reported as a refinement, and on two fiducial
     *       recordings that happened on <em>every</em> pair, because the recipe's intensity band
     *       leaves a scattered valid mask through which this pass admits only 15% of the samples the
     *       grid's scoring function admits — below {@code minValidFraction}, so the surface was never
     *       usable at all. See {@code docs/newton_refinement_stage2_diagnosis.md}.</li>
     *   <li><b>Cap the step at one pixel</b>, so a poorly conditioned Hessian
     *       cannot throw the estimate out of the cell the integer sweep chose.</li>
     *   <li><b>Accept a step only if the score agrees</b>, halving up to four
     *       times. A rejected trial costs a value-only pass, about half of a gradient pass, which is
     *       why the line search is affordable.</li>
     * </ul>
     *
     * <p><b>One known limit, quantified rather than hidden.</b> The correlation surface is only
     * piecewise smooth: the set of samples whose full four-by-four spline neighbourhood is valid
     * changes in steps as the offset moves. On the analytic fixture, 189 samples of 35,721 leave the
     * overlap exactly at a whole-pixel offset, stepping the score by 1.17e-05 — worth about 7e-04 px.
     * Every refinement starts at an integer offset, so the first gradient is always evaluated on one
     * of these boundaries. It is harmless on a clean rectangle of valid pixels and unproven where the
     * validity boundary is ragged, which is sparse low-light. The pass below at least keeps the value
     * and its own derivative on the same sample set, so the gradient always describes the quantity
     * the score reports.
     */
    private static Refinement newtonRefine(Window a, Window b, Transform start, double maxShiftHere,
                                           PairAligner.Options o) {
        double x = start.dx;
        double y = start.dy;
        double[] out = new double[3];
        double[] scratch = new double[16];
        Surface s = surface(a, b, x, y, o, out, scratch);
        if (!s.valid) return refine(a, b, start, maxShiftHere, o);

        double tolerance = Math.max(o.convergence, MIN_STEP);
        int maxIterations = Math.max(1, Math.min(o.maxIterations, MAX_NEWTON_ITERATIONS));
        int iterations = 0;
        double move = Double.POSITIVE_INFINITY;
        for (int k = 0; k < maxIterations; k++) {
            double determinant = s.hxx * s.hyy - s.hxy * s.hxy;
            if (!(determinant > 0) || !(s.hxx > 0)) break;
            double stepX = (s.hyy * s.gx - s.hxy * s.gy) / determinant;
            double stepY = (s.hxx * s.gy - s.hxy * s.gx) / determinant;
            if (!Double.isFinite(stepX) || !Double.isFinite(stepY)) break;
            double length = Math.hypot(stepX, stepY);
            if (length > MAX_NEWTON_STEP) {
                stepX *= MAX_NEWTON_STEP / length;
                stepY *= MAX_NEWTON_STEP / length;
                length = MAX_NEWTON_STEP;
            }
            iterations++;

            boolean moved = false;
            double scale = 1.0;
            for (int back = 0; back < NEWTON_BACKTRACKS; back++) {
                double nx = x + scale * stepX;
                double ny = y + scale * stepY;
                if (Math.hypot(nx, ny) <= maxShiftHere + 1e-9) {
                    double trial = score(a, b, nx, ny, o, scratch);
                    if (Double.isFinite(trial) && trial >= s.c) {
                        x = nx;
                        y = ny;
                        moved = true;
                        break;
                    }
                }
                scale *= 0.5;
            }
            if (!moved) break;
            move = scale * length;
            if (move < tolerance) break;
            s = surface(a, b, x, y, o, out, scratch);
            if (!s.valid) break;
        }
        // Never hand back a whole-pixel answer. Every path out of the loop above that has taken no
        // step leaves the offset exactly where the integer sweep put it, and returning that is not a
        // refinement at all — it is an error of up to sqrt(2)/2 px reported as a measurement. That is
        // not a hypothetical: it is what the first Stage 2 run did on every one of the 209 pairs of
        // two fiducial recordings, and it is what failed the stage. See
        // docs/newton_refinement_stage2_diagnosis.md.
        if (iterations == 0) return refine(a, b, start, maxShiftHere, o);
        return new Refinement(clampShift(Transform.translation(x, y), maxShiftHere), iterations,
                move < tolerance);
    }

    /**
     * Score, gradient and Gauss-Newton Hessian at one offset, in a single pass.
     *
     * <p>Only samples whose full four-by-four spline neighbourhood is valid contribute, to the value
     * and to the derivatives alike, so the gradient describes exactly the quantity {@code s.c}
     * reports. {@code out} and {@code scratch} are caller-owned: a {@code double[4]} allocated per
     * sample, four times over, is 147,456 allocations per pass on a 192-pixel frame, and that cost
     * would have been charged to the method rather than to the code that wrote it.
     */
    static Surface surface(Window a, Window b, double dx, double dy, PairAligner.Options o,
                           double[] out, double[] scratch) {
        double sa = 0;
        double saa = 0;
        double sb = 0;
        double sbb = 0;
        double sab = 0;
        double sbx = 0;
        double sby = 0;
        double sbxx = 0;
        double sbyy = 0;
        double sbxy = 0;
        double sbbx = 0;
        double sbby = 0;
        double sabx = 0;
        double saby = 0;
        int n = 0;
        int possible = 0;
        for (int y = 0; y < a.height; y += a.stride) {
            int row = y * a.width;
            for (int x = 0; x < a.width; x += a.stride) {
                int i = row + x;
                possible++;
                if (!a.plane.valid[i]) continue;
                if (!b.sampleWithGradient(x + dx, y + dy, out, scratch)) continue;
                double av = a.value[i];
                double bv = out[0];
                double bx = out[1];
                double by = out[2];
                n++;
                sa += av;
                saa += av * av;
                sb += bv;
                sbb += bv * bv;
                sab += av * bv;
                sbx += bx;
                sby += by;
                sbxx += bx * bx;
                sbyy += by * by;
                sbxy += bx * by;
                sbbx += bv * bx;
                sbby += bv * by;
                sabx += av * bx;
                saby += av * by;
            }
        }
        Surface s = new Surface();
        if (n < MIN_NEWTON_SAMPLES || n < o.minValidFraction * possible) return s;

        double meanA = sa / n;
        double meanB = sb / n;
        double va = saa - sa * sa / n;
        double vb = sbb - sb * sb / n;
        if (!(va > 0) || !(vb > 0)) return s;
        double sigA = Math.sqrt(va);
        double sigB = Math.sqrt(vb);
        double c = (sab - sa * sb / n) / (sigA * sigB);

        double px = sabx - meanA * sbx;
        double py = saby - meanA * sby;
        double qx = sbbx - meanB * sbx;
        double qy = sbby - meanB * sby;
        double uxx = sbxx - sbx * sbx / n;
        double uyy = sbyy - sby * sby / n;
        double uxy = sbxy - sbx * sby / n;

        s.c = c;
        s.gx = px / (sigA * sigB) - c * qx / vb;
        s.gy = py / (sigA * sigB) - c * qy / vb;
        s.hxx = (uxx - qx * qx / vb) / vb;
        s.hyy = (uyy - qy * qy / vb) / vb;
        s.hxy = (uxy - qx * qy / vb) / vb;
        s.valid = Double.isFinite(s.gx) && Double.isFinite(s.gy)
                && Double.isFinite(s.hxx) && Double.isFinite(s.hyy) && Double.isFinite(s.hxy);
        return s;
    }

    /**
     * The score alone, over exactly the sample set {@link #surface} would have used.
     *
     * <p>Not {@link #correlation}: that one falls back to a bilinear sample at the frame border,
     * where the surface pass refuses the position outright. A line search judged on a different
     * sample set from the gradient that proposed the step is comparing two different functions, and
     * would accept or reject on the difference between them.
     */
    static double score(Window a, Window b, double dx, double dy, PairAligner.Options o,
                        double[] scratch) {
        double sa = 0;
        double saa = 0;
        double sb = 0;
        double sbb = 0;
        double sab = 0;
        int n = 0;
        int possible = 0;
        for (int y = 0; y < a.height; y += a.stride) {
            int row = y * a.width;
            for (int x = 0; x < a.width; x += a.stride) {
                int i = row + x;
                possible++;
                if (!a.plane.valid[i]) continue;
                double bv = b.sampleInterior(x + dx, y + dy, scratch);
                if (Double.isNaN(bv)) continue;
                double av = a.value[i];
                n++;
                sa += av;
                saa += av * av;
                sb += bv;
                sbb += bv * bv;
                sab += av * bv;
            }
        }
        if (n < MIN_NEWTON_SAMPLES || n < o.minValidFraction * possible) return Double.NaN;
        double va = saa - sa * sa / n;
        double vb = sbb - sb * sb / n;
        if (!(va > 0) || !(vb > 0)) return Double.NaN;
        return (sab - sa * sb / n) / Math.sqrt(va * vb);
    }

    /**
     * Zero-normalised cross-correlation between {@code a} and {@code b} offset by {@code (dx, dy)},
     * over the pixels valid in both. NaN when too little overlaps or when either window is flat, so
     * that "no information" is never confused with "no similarity".
     */
    private static double correlation(Window a, Window b, double dx, double dy,
                                      PairAligner.Options o) {
        int n = 0;
        int possible = 0;
        double sa = 0;
        double sb = 0;
        double saa = 0;
        double sbb = 0;
        double sab = 0;
        // A whole-pixel offset needs no interpolator, and most of the offsets this estimator scores
        // are whole pixels: every candidate in every sweep, at every level. Indexing straight into
        // the array there rather than running sixteen cubic taps that all reduce to one weight is
        // what keeps the sweep affordable.
        boolean whole = dx == Math.rint(dx) && dy == Math.rint(dy);
        int shiftX = (int) Math.rint(dx);
        int shiftY = (int) Math.rint(dy);
        for (int y = 0; y < a.height; y += a.stride) {
            int row = y * a.width;
            int shiftedRow = y + shiftY;
            boolean rowInside = whole && shiftedRow >= 0 && shiftedRow < b.height;
            for (int x = 0; x < a.width; x += a.stride) {
                int i = row + x;
                possible++;
                if (!a.plane.valid[i]) continue;
                double bv;
                if (whole) {
                    int sx = x + shiftX;
                    if (!rowInside || sx < 0 || sx >= b.width) continue;
                    int j = shiftedRow * b.width + sx;
                    if (!b.plane.valid[j]) continue;
                    bv = b.value[j];
                } else {
                    bv = b.sample(x + dx, y + dy);
                    if (Double.isNaN(bv)) continue;
                }
                double av = a.value[i];
                n++;
                sa += av;
                sb += bv;
                saa += av * av;
                sbb += bv * bv;
                sab += av * bv;
            }
        }
        if (n == 0 || n < o.minValidFraction * possible) return Double.NaN;
        double va = saa - sa * sa / n;
        double vb = sbb - sb * sb / n;
        if (!(va > 0) || !(vb > 0)) return Double.NaN;
        return (sab - sa * sb / n) / Math.sqrt(va * vb);
    }

    private static Transform clampShift(Transform t, double maxShift) {
        double m = t.magnitude();
        if (m <= maxShift || m == 0) return t;
        double f = maxShift / m;
        return new Transform(t.dx * f, t.dy * f, t.theta);
    }

    /**
     * One pyramid level with its intensities reconstructed and centred, ready to correlate.
     *
     * <p>Centring on the plane's own mean is not cosmetic. Backgrounds of tens of thousands of counts
     * with a few counts of structure on top are ordinary in this benchmark, and accumulating raw
     * squares there loses the structure to rounding long before the correlation sees it. Subtracting
     * any constant leaves the correlation unchanged, so the mean is free to use.
     */
    static final class Window {
        /** Pole of the cubic B-spline prefilter. Unser, Aldroubi and Eden, IEEE SP 41:821, 1993. */
        private static final double POLE = Math.sqrt(3.0) - 2.0;

        final LogPlane plane;
        final float[] value;
        /** Spline coefficients of {@link #value}; interpolation reads these, never {@code value}. */
        final float[] coefficients;
        final int width;
        final int height;
        final int stride;

        private Window(LogPlane plane, float[] value, float[] coefficients, int stride) {
            this.plane = plane;
            this.value = value;
            this.coefficients = coefficients;
            this.width = plane.width;
            this.height = plane.height;
            this.stride = stride;
        }

        /**
         * The prepared view of {@code plane}, built once and then reused.
         *
         * <p>Everything but the stride is a pure function of the plane, so it is memoised on the
         * plane itself and lives exactly as long as the plane does — see {@link LogPlane#areaWindow}.
         * The stride is not: it depends on {@code maxSamples}, which belongs to the options rather
         * than the image, so a cached view whose stride does not match is rewrapped around the same
         * two arrays instead of being rebuilt. That rewrap is three field writes; the rebuild it
         * avoids is two full-frame passes and a four-pass B-spline recursion.
         */
        static Window of(LogPlane plane, PairAligner.Options o) {
            int stride = PairAligner.strideFor(plane.width, plane.height, o.maxSamples);
            Window cached = plane.areaWindow;
            if (cached != null) {
                return cached.stride == stride
                        ? cached
                        : new Window(plane, cached.value, cached.coefficients, stride);
            }
            Window built = build(plane, stride);
            plane.areaWindow = built;
            return built;
        }

        private static Window build(LogPlane plane, int stride) {
            float[] out = new float[plane.v.length];
            double sum = 0;
            int n = 0;
            for (int i = 0; i < out.length; i++) {
                if (!plane.valid[i]) continue;
                double intensity = PairAligner.fastExp2(plane.v[i]);
                out[i] = (float) intensity;
                sum += intensity;
                n++;
            }
            float mean = n == 0 ? 0f : (float) (sum / n);
            for (int i = 0; i < out.length; i++) {
                if (plane.valid[i]) out[i] -= mean;
            }
            return new Window(plane, out, prefilter(out, plane.width, plane.height), stride);
        }

        /**
         * Cubic B-spline coefficients: the array whose spline passes exactly through the samples.
         *
         * <p><b>Why a prefilter is the whole difference between two cubics.</b> Weighting the four
         * nearest samples by the B-spline basis directly -- what "cubic" usually means, and what a
         * Catmull-Rom kernel approximates -- builds a curve that misses the samples it came from, so
         * it low-passes the frame by an amount that depends on the fractional offset. That is the
         * same pixel-locking mechanism bilinear suffers from, one order milder. Solving for
         * coefficients first makes the spline interpolate, and the residual bias falls with it. It is
         * also what TurboReg does, and the reason its estimates are worth comparing against. The
         * recursion is two passes per axis, linear in the pixel count, run once per pyramid level
         * rather than once per candidate offset.
         *
         * <p>Invalid pixels are already zero here, which is the plane mean after centring. That is a
         * compromise with no better alternative: the filter is infinite-impulse-response, so there is
         * no local way to omit a pixel, and a hole filled with the mean at least pulls the spline
         * nowhere. Positions whose neighbourhood touches an invalid pixel are refused by
         * {@link #sample} regardless, so this only affects how far a hole's influence reaches.
         */
        private static float[] prefilter(float[] samples, int width, int height) {
            float[] c = samples.clone();
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) c[row + x] *= 6.0f;
                filterLine(c, row, 1, width);
            }
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) c[y * width + x] *= 6.0f;
                filterLine(c, x, width, height);
            }
            return c;
        }

        /** Causal then anti-causal first-order recursion along one line. */
        private static void filterLine(float[] c, int offset, int step, int count) {
            if (count < 2) return;
            // Truncate the causal initialisation where the pole's influence is below float precision.
            int horizon = Math.min(count,
                    (int) Math.ceil(Math.log(1e-7) / Math.log(Math.abs(POLE))));
            double sum = c[offset];
            double z = POLE;
            for (int k = 1; k < horizon; k++) {
                sum += z * c[offset + k * step];
                z *= POLE;
            }
            c[offset] = (float) sum;
            for (int k = 1; k < count; k++) {
                int i = offset + k * step;
                c[i] = (float) (c[i] + POLE * c[i - step]);
            }
            int last = offset + (count - 1) * step;
            c[last] = (float) (POLE / (POLE * POLE - 1.0) * (c[last] + POLE * c[last - step]));
            for (int k = count - 2; k >= 0; k--) {
                int i = offset + k * step;
                c[i] = (float) (POLE * (c[i + step] - c[i]));
            }
        }

        /**
         * Sample at a sub-pixel position. NaN out of bounds or where any contributing pixel is
         * invalid — the same rule {@link LogPlane#sample} applies, so the two estimators agree about
         * which positions exist.
         *
         * <p><b>Cubic where there is room for it, and that is not a refinement of taste.</b> Bilinear
         * interpolation is a low-pass filter whose strength depends on the fractional offset: zero at
         * a whole pixel, worst at a half. A correlation score computed through it is therefore
         * systematically better at whole-pixel offsets than between them, and the peak slides toward
         * the nearest integer — pixel locking, the standard failure of correlation-based
         * displacement. Measured here on a fiducial recording, bilinear left a systematic per-pair
         * bias of 0.015 to 0.032 px, which is larger than the whole error the log-ratio fit makes.
         * An interpolating cubic B-spline costs sixteen taps instead of four and removes most of it.
         */
        double sample(double x, double y) {
            if (!(x >= 0 && y >= 0 && x <= width - 1 && y <= height - 1)) return Double.NaN;
            int x0 = (int) x;
            int y0 = (int) y;
            double fx = x - x0;
            double fy = y - y0;
            if (x0 >= 1 && y0 >= 1 && x0 + 2 <= width - 1 && y0 + 2 <= height - 1) {
                double cubic = cubic(x0, y0, fx, fy);
                if (!Double.isNaN(cubic)) return cubic;
            }
            int x1 = x0 + 1 < width ? x0 + 1 : x0;
            int y1 = y0 + 1 < height ? y0 + 1 : y0;
            int r0 = y0 * width;
            int r1 = y1 * width;
            boolean[] valid = plane.valid;
            if (!(valid[r0 + x0] && valid[r0 + x1] && valid[r1 + x0] && valid[r1 + x1])) {
                return Double.NaN;
            }
            double top = value[r0 + x0] + fx * (value[r0 + x1] - value[r0 + x0]);
            double bot = value[r1 + x0] + fx * (value[r1 + x1] - value[r1 + x0]);
            return top + fy * (bot - top);
        }

        /** Cubic B-spline over the 4x4 neighbourhood. NaN if any of the sixteen pixels is invalid. */
        private double cubic(int x0, int y0, double fx, double fy) {
            boolean[] valid = plane.valid;
            double[] wx = weights(fx);
            double[] wy = weights(fy);
            double sum = 0;
            for (int j = 0; j < 4; j++) {
                int row = (y0 - 1 + j) * width;
                double line = 0;
                for (int i = 0; i < 4; i++) {
                    int index = row + x0 - 1 + i;
                    if (!valid[index]) return Double.NaN;
                    line += wx[i] * coefficients[index];
                }
                sum += wy[j] * line;
            }
            return sum;
        }

        /**
         * The same prepared arrays read at a different sampling stride.
         *
         * <p>Three field writes and no arithmetic: the intensities and the spline coefficients are a
         * pure function of the plane and do not depend on how densely they are then read. This is the
         * same rewrap {@link #of} performs when a cached window's stride does not match the options.
         */
        Window withStride(int stride) {
            return stride == this.stride ? this : new Window(plane, value, coefficients, stride);
        }

        /** Cubic B-spline basis at offsets -1, 0, 1 and 2 from the sample below the position. */
        private static double[] weights(double f) {
            double f2 = f * f;
            double f3 = f2 * f;
            double g = 1.0 - f;
            return new double[]{
                    g * g * g / 6.0,
                    (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0,
                    (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0,
                    f3 / 6.0};
        }

        // ------------------------------------------- what the Gauss-Newton refinement reads //

        /**
         * Interpolated value and both spatial derivatives, from the same sixteen coefficients.
         *
         * <p>The row sums are shared: four taps across a row give that row's value and that row's
         * x-derivative, and the column weights then give the value, the x-derivative and the
         * y-derivative. That is why a pass producing both derivatives costs about 1.62 plain
         * interpolations rather than three, and it is the measurement the whole speed-up rests on.
         *
         * <p><b>Strictly the interior, and no bilinear fallback.</b> {@link #sample} drops to
         * bilinear at the frame border, which is the right answer for a score but the wrong one for
         * a derivative: the two interpolators have different slopes, so a gradient assembled from
         * both would describe neither. A position whose full four-by-four neighbourhood is not
         * available is refused instead. {@link #sampleInterior} refuses exactly the same positions,
         * so the line search and the gradient always see one sample set.
         *
         * @param out     receives value, d/dx and d/dy; caller-owned, length 3
         * @param scratch caller-owned, length 16: x weights, y weights, then both derivative sets
         * @return false where the position is refused, leaving {@code out} untouched
         */
        boolean sampleWithGradient(double x, double y, double[] out, double[] scratch) {
            if (!(x >= 0 && y >= 0 && x <= width - 1 && y <= height - 1)) return false;
            int x0 = (int) x;
            int y0 = (int) y;
            if (!(x0 >= 1 && y0 >= 1 && x0 + 2 <= width - 1 && y0 + 2 <= height - 1)) return false;
            fillWeights(x - x0, scratch, 0);
            fillWeights(y - y0, scratch, 4);
            fillWeightDerivatives(x - x0, scratch, 8);
            fillWeightDerivatives(y - y0, scratch, 12);
            double value = 0;
            double gx = 0;
            double gy = 0;
            for (int j = 0; j < 4; j++) {
                int row = (y0 - 1 + j) * width + x0 - 1;
                double line = 0;
                double dline = 0;
                for (int i = 0; i < 4; i++) {
                    int index = row + i;
                    if (!plane.valid[index]) return false;
                    double c = coefficients[index];
                    line += scratch[i] * c;
                    dline += scratch[8 + i] * c;
                }
                value += scratch[4 + j] * line;
                gx += scratch[4 + j] * dline;
                gy += scratch[12 + j] * line;
            }
            out[0] = value;
            out[1] = gx;
            out[2] = gy;
            return true;
        }

        /**
         * The value alone, at exactly the positions {@link #sampleWithGradient} accepts. NaN
         * elsewhere.
         *
         * <p>About half the arithmetic of the gradient pass, which is what makes a line-search trial
         * that may be rejected cheap enough to be worth taking.
         */
        double sampleInterior(double x, double y, double[] scratch) {
            if (!(x >= 0 && y >= 0 && x <= width - 1 && y <= height - 1)) return Double.NaN;
            int x0 = (int) x;
            int y0 = (int) y;
            if (!(x0 >= 1 && y0 >= 1 && x0 + 2 <= width - 1 && y0 + 2 <= height - 1)) {
                return Double.NaN;
            }
            fillWeights(x - x0, scratch, 0);
            fillWeights(y - y0, scratch, 4);
            double value = 0;
            for (int j = 0; j < 4; j++) {
                int row = (y0 - 1 + j) * width + x0 - 1;
                double line = 0;
                for (int i = 0; i < 4; i++) {
                    int index = row + i;
                    if (!plane.valid[index]) return Double.NaN;
                    line += scratch[i] * coefficients[index];
                }
                value += scratch[4 + j] * line;
            }
            return value;
        }

        /** {@link #weights} written into caller-owned scratch instead of a fresh array. */
        static void fillWeights(double f, double[] into, int at) {
            double f2 = f * f;
            double f3 = f2 * f;
            double g = 1.0 - f;
            into[at] = g * g * g / 6.0;
            into[at + 1] = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
            into[at + 2] = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
            into[at + 3] = f3 / 6.0;
        }

        /**
         * The basis derivatives with respect to the fractional position.
         *
         * <p><b>The four must sum to zero at every {@code f}</b>, because the basis itself sums to
         * one everywhere: a constant image has no gradient, and a set of derivative weights that did
         * not cancel would manufacture one out of the background level. Asserted in the tests at
         * several fractional positions rather than taken on trust.
         */
        static void fillWeightDerivatives(double f, double[] into, int at) {
            double f2 = f * f;
            double g = 1.0 - f;
            into[at] = -g * g / 2.0;
            into[at + 1] = (-12.0 * f + 9.0 * f2) / 6.0;
            into[at + 2] = (3.0 + 6.0 * f - 9.0 * f2) / 6.0;
            into[at + 3] = f2 / 2.0;
        }
    }
}
