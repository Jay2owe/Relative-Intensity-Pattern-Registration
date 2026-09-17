/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers a whole recording: plans the pairs, aligns them, reconciles, repairs, and reports.
 *
 * <p>The one class that ties the engine together, and the only one a caller outside
 * {@code logratio.core} needs. It touches no ImageJ class — pixels arrive through
 * {@link FrameSource} and leave as {@link Transform}s, so the estimator is testable and reusable with no
 * {@code ij} on the classpath.
 *
 * <p>Order of business, and why each step is where it is:
 *
 * <ol>
 *   <li><b>Plan the pairs first, from the strategy alone.</b> A fixed plan computed before any pixel is
 *       read is what makes the parallel phase deterministic — results land at their planned index, so the
 *       output cannot depend on which worker finished first.</li>
 *   <li><b>Build each pyramid once</b> ({@link PyramidCache}). For multi-lag this is the difference
 *       between {@code T} pyramid constructions and {@code 10T}.</li>
 *   <li><b>Align pairs in parallel</b> ({@link PairScheduler}), one bounded pool, workers capped by
 *       memory as well as cores.</li>
 *   <li><b>Reconcile</b> ({@link Reconciler}) — chain, all-pairs least squares, or direct.</li>
 *   <li><b>Repair</b> ({@link ChainRepair}) — because one refused pair otherwise corrupts every later
 *       frame, silently, in any chained method.</li>
 * </ol>
 *
 * <p>{@link Reconciler.Reference#ROLLING} bypasses steps 1 and 3 and runs sequentially. Its target for
 * frame {@code t} is built from frames already registered, so its pairs cannot be planned in advance and
 * its work cannot be parallelised. That is a property of the strategy, not a shortcoming of this class.
 */
public final class Registration {

    private Registration() {
    }

    public static final class Options {
        /** Everything about how one pair is aligned. */
        public PairAligner.Options aligner = new PairAligner.Options();
        /** Which frames are compared against which. */
        public Reconciler.Reference reference = Reconciler.Reference.CONSECUTIVE;
        /** Zero-based reference frame for {@link Reconciler.Reference#FIXED}. */
        public int referenceFrame = 0;
        /** Lag set for {@link Reconciler.Reference#MULTILAG}. Must contain 1 to cover every frame. */
        public int[] lags = {1, 2, 4, 8, 16};
        /** Frames averaged into the template for {@link Reconciler.Reference#ROLLING}. */
        public int templateWindow = 5;
        /** Offset added before the log. See {@link LogPlane}. */
        public double epsilon = 1.0;
        /** Intensities below this are excluded. {@link LogPlane#NO_FLOOR} to keep everything. */
        public double intensityFloor = LogPlane.NO_FLOOR;
        /**
         * Derive {@link #intensityFloor} from each frame's own intensities, at this percentile.
         * NaN — the default — uses {@link #intensityFloor} as given.
         *
         * <p><b>Whether this helps or ruins the fit depends on the modality, and it is the only setting
         * here of which that is true. Read this before using it.</b>
         *
         * <p>On phase contrast, cutting the dimmest quarter costs 2.2x accuracy on a chain and 7.2x with
         * multi-lag, for a 7% time saving — while cutting the <em>brightest</em> quarter gains accuracy
         * and saves 20%. On the same pixels remapped to a fluorescence-shaped histogram — background at
         * zero, structure sparse and bright — the sign reverses: cutting the dimmest quarter becomes
         * 1.9x better than no cut and the best setting measured, while the ceiling drops to second.
         * Measured, one seed, multi-lag, clean condition: 0.1715 against 0.0202 on phase contrast and
         * 0.0347 against 0.0675 remapped.
         *
         * <p>A real fluorescence seed narrows that control result. On CC0 BBBC038 nuclei, cutting the
         * dimmest quarter reads 0.0162 px against 0.0135 with no band on clean data, but 0.0342 against
         * 0.0391 under a gain fade. Native noise and point-spread therefore make the floor mixed rather
         * than a fluorescence default. See {@code library/benchmark/fluorescence_seed_2026-08-11.csv}.
         *
         * <p>The reason is specific to this criterion and worth stating, because it is counterintuitive
         * and it is the opposite of what would hold for a plain sum-of-squared-differences method. The
         * solver descends the gradient of the <em>log</em> plane, and the log's slope is
         * {@code 1/(I + epsilon)} — steep where the frame is dark, shallow where it is bright. Measured
         * per intensity decile on the phase-contrast seeds, the darkest tenth carries 19-20% of the
         * total log-gradient against about 8% for each middle tenth. <b>There, the dark pixels are where
         * the alignment information is.</b> On a fluorescence histogram the dark pixels are empty
         * background instead, and the same reasoning points the other way.
         *
         * <p><b>So intensity is only a proxy for "which pixels are informative", and its sign is not
         * fixed.</b> Set this from what the frame actually looks like, not from a remembered default: a
         * value chosen for the wrong modality costs an order of magnitude and nothing will warn you.
         * {@link PairAligner.PixelSupport#GRADIENT} selects on the gradient itself and is
         * modality-free, but it is drawn to the edges of a bright artefact and so cannot do the job
         * {@link #saturationPercentile} does.
         *
         * <p>There is also a hard floor on how much may be excluded from either end, and it is not about
         * information. {@link LogPlane#sample} refuses any position whose bilinear footprint touches an
         * invalid pixel, so each excluded pixel destroys up to four positions. Measured: excluding half
         * the frame leaves 7-12% of positions usable on phase contrast, which trips {@code minValidFraction} and the pair
         * is refused. Excluding a quarter leaves 33-41%, which is fine.
         *
         * <p>Not the same as {@link PairAligner.PixelSupport#GRADIENT}, which keeps pixels whose
         * gradient exceeds a fraction of the median gradient. That still visits every pixel to decide,
         * and by design it does not touch the gain estimate or the reported residual. A floor is
         * applied once, in {@link LogPlane}, and the pixel is gone from everything downstream.
         */
        public double intensityFloorPercentile = Double.NaN;
        /** Intensities at or above this are excluded as saturated. An absolute grey level. */
        public double saturationMax = LogPlane.NO_SATURATION;
        /**
         * Derive {@link #saturationMax} from each frame's own intensities, at this percentile.
         * NaN — the default — uses {@link #saturationMax} as given.
         *
         * <p><b>This is the mitigation for the one condition the benchmark loses on.</b> Under
         * constant bright blocks over 10% of the field, log-ratio Tukey with gradient support scored
         * 24.4 px on a chain and 4.76 with RCC, against phase correlation's 1.60 and 0.137. Excluding
         * every pixel at or above each frame's 90th percentile takes those to <b>0.31 and 0.06</b> —
         * from 15x worse than phase correlation to 5x better. The same cut costs nothing on clean data
         * and helps under a gain fade.
         *
         * <p><b>And it is not a free parameter.</b> The cut has to reach the artefact, and there is no
         * partial credit: at the 99th and 99.5th percentiles it removes too little and measures
         * slightly <em>worse</em> than doing nothing, because it strips real signal and leaves the
         * artefact behind. The 90 that works is the same 90 as the blocks' 10% coverage. So the
         * question is not which percentile is best but how much of the field the artefact covers —
         * set it to that, or leave it off.
         *
         * <p><b>The failure mode to be afraid of is measured, not hypothetical.</b> A percentile is not saturation: it removes the
         * brightest fraction whether or not anything is saturated. If the sample <em>is</em> the
         * brightest tenth of the frame — cell bodies on a dark background, which is the normal case in
         * fluorescence — this deletes part of the sample. On a real BBBC038 fluorescence seed, the
         * 90th-percentile ceiling worsens multi-lag median error from 0.0135 to 0.0386 px on clean data
         * and from 0.0391 to 0.2069 under a gain fade. It still improves the bright-block condition from
         * 19.164 to 2.375 px. Use this as deliberate bright-artefact rejection, not as a modality-free
         * accuracy default. See {@code library/benchmark/fluorescence_seed_2026-08-11.csv}.
         *
         * <p>Per frame rather than once for the recording, deliberately: a fade moves the whole
         * intensity distribution, so a fixed absolute ceiling would exclude a different fraction of
         * each frame and manufacture exactly the frame-varying pixel support the criterion must not
         * have.
         */
        public double saturationPercentile = Double.NaN;
        /** Also remove a per-frame additive background. See {@link #removeOffset}. */
        public boolean removeOffset = false;
        /** Percentile used as the additive background when {@link #removeOffset} is set. */
        public double offsetPercentile = 1.0;
        /** Step-magnitude outlier threshold in MADs; 0 disables outlier repair. */
        public double outlierMads = ChainRepair.DEFAULT_OUTLIER_MADS;
        /** Worker threads; 0 or less decides automatically, 1 forces serial. */
        public int threads = 0;
        /** Bytes available for pyramids and workers; 0 derives it from {@link Runtime#maxMemory}. */
        public long memoryBudgetBytes = 0;
        /**
         * Replace {@link PairAligner.Options#maxShift} with a bound measured from the recording.
         *
         * <p>Off by default so nothing changes under a caller who set the bound deliberately, but the
         * 30 px default is far too small for real data: the largest frame-to-frame steps across 24
         * surveyed recordings ran to 1,154 px. See {@link #estimateShiftBound}.
         */
        public boolean autoMaxShift = false;
        /**
         * Choose the pixel-evidence rule from the recording itself.
         *
         * <p>Off preserves the caller's explicit settings. On applies
         * {@link AutomaticInformationSelector}'s measured recording-level choice before pyramids are
         * built. The reference strategy and robust norm remain caller choices; the plugin's Automatic
         * preset pairs this flag with MULTILAG and TUKEY.
         */
        public boolean automaticInformationSupport = false;
        AutomaticInformationSelector.Result automaticInformationResult;
        /**
         * How one frame pair is estimated. A separate axis from everything in {@link #aligner}.
         *
         * <p>{@link PairEstimator.Kind#LOG_RATIO_FIT} is the default and the reason this plugin
         * exists. {@link PairEstimator.Kind#AREA_CORRELATION} replaces the criterion and the solver
         * with normalised cross-correlation and keeps everything else — the pair plan, the pyramids,
         * the reconciliation, the repair, the warp — identical, so the two can be compared with the
         * estimator as the only difference.
         *
         * <p>Two paths deliberately stay on the log-ratio fit whatever this is set to, and the
         * reason is the same in both cases: they are log-ratio machinery, not pair estimation.
         * {@link #estimateShiftBound} probes for a movement bound with a one-level all-pixel fit, and
         * holding it fixed means both estimators are then given the same {@code maxShift}. The
         * two-pass pixel-selection refit needs per-pixel solver masks, which an area method has no
         * concept of, and asking for both is refused rather than silently ignored.
         */
        public PairEstimator estimator = PairEstimator.Kind.LOG_RATIO_FIT;

        public Options copy() {
            Options o = new Options();
            o.aligner = aligner.copy();
            o.estimator = estimator;
            o.reference = reference;
            o.referenceFrame = referenceFrame;
            o.lags = lags == null ? null : lags.clone();
            o.templateWindow = templateWindow;
            o.epsilon = epsilon;
            o.intensityFloor = intensityFloor;
            o.intensityFloorPercentile = intensityFloorPercentile;
            o.saturationMax = saturationMax;
            o.saturationPercentile = saturationPercentile;
            o.removeOffset = removeOffset;
            o.offsetPercentile = offsetPercentile;
            o.outlierMads = outlierMads;
            o.threads = threads;
            o.memoryBudgetBytes = memoryBudgetBytes;
            o.autoMaxShift = autoMaxShift;
            o.automaticInformationSupport = automaticInformationSupport;
            o.automaticInformationResult = automaticInformationResult;
            return o;
        }

        /**
         * The norm and pixel support measured best for a given reconciliation.
         *
         * <p><b>The two settings are not independent, and the API used to pretend they were.</b> On the
         * benchmark's clean condition with a consecutive chain, plain least squares reaches 0.281 px
         * where Tukey with gradient support reaches 0.466 — with no outliers to reject, a redescending
         * norm discards good pixels and gradient support throws away most of the field. Move to
         * {@link Reconciler.Reference#MULTILAG} and the ordering inverts under every condition, because
         * the redundancy absorbs the robust norm's noisier individual estimates: Tukey scores 0.036 /
         * 0.057 / 0.050 / 4.76 across clean, gain fade, moved change and block change.
         *
         * <p>So the right norm follows from the reconciliation:
         *
         * <table border="1">
         *   <caption>Measured best combination per reconciliation</caption>
         *   <tr><th>reference</th><th>norm</th><th>support</th></tr>
         *   <tr><td>{@code MULTILAG}</td><td>{@code TUKEY}</td><td>{@code GRADIENT}</td></tr>
         *   <tr><td>everything else</td><td>{@code LEAST_SQUARES}</td><td>{@code ALL}</td></tr>
         * </table>
         *
         * <p>Two caveats, stated because the table hides them. First, only {@code CONSECUTIVE} and
         * {@code MULTILAG} were measured; {@code FIXED} and {@code ROLLING} take the chain's answer
         * because they share its structure — one observation per frame, so nothing absorbs a noisier
         * estimate — and that is an inference from the mechanism, not a measurement. Second, on a
         * consecutive chain under saturating bright artefacts, Tukey still wins (24.4 px against least
         * squares' 39.3). Both are useless at that magnitude, which is why the recommendation follows
         * the conditions where the answer is usable — but a caller who expects debris and must chain
         * should set {@code TUKEY} by hand and accept the warning.
         */
        public static Options recommendedFor(Reconciler.Reference reference) {
            Options o = new Options();
            o.reference = reference == null ? Reconciler.Reference.CONSECUTIVE : reference;
            if (o.reference == Reconciler.Reference.MULTILAG) {
                o.aligner.norm = RobustNorm.TUKEY;
                o.aligner.support = PairAligner.PixelSupport.GRADIENT;
            } else {
                o.aligner.norm = RobustNorm.LEAST_SQUARES;
                o.aligner.support = PairAligner.PixelSupport.ALL;
            }
            return o;
        }
    }

    /** Something about the configuration or the outcome that the user has to be told. */
    public static final class Warning {
        public enum Kind {
            /** The norm does not match the reconciliation. See {@link Options#recommendedFor}. */
            NORM_FOR_REFERENCE,
            /** Frames whose solution sits on {@code maxShift}, so the motion reported is a floor. */
            AT_SHIFT_BOUND,
            /** Pairs that were refused, so their frames were repaired rather than measured. */
            REFUSED_PAIRS,
            /** The pixels themselves cannot be localised. See {@link Localisability}. */
            LOW_LOCALISABILITY
        }

        public final Kind kind;
        public final String message;

        Warning(Kind kind, String message) {
            this.kind = kind;
            this.message = message;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    /** One aligned pair, kept with its indices so the caller can build tables without re-planning. */
    public static final class PairResult {
        public final int from;
        public final int to;
        public final PairAligner.Fit fit;

        PairResult(int from, int to, PairAligner.Fit fit) {
            this.from = from;
            this.to = to;
            this.fit = fit;
        }

        public int lag() {
            return Math.abs(to - from);
        }
    }

    public static final class Result {
        /** Per-frame transform relative to the reference. What {@link Warper} consumes. */
        public final Transform[] cumulative;
        /** Every pair that was aligned, in plan order. */
        public final List<PairResult> pairs;
        /** Observations per frame before repair; zero means the frame had no evidence. */
        public final int[] support;
        /** Why each frame was repaired, or null. */
        public final ChainRepair.Reason[] repairs;
        /** Cumulative {@code log2} intensity gain per frame. The bleaching trace. */
        public final double[] log2Gain;
        /** Mean absolute log-ratio into each frame before aligning, log2 units. */
        public final double[] residualBefore;
        /** The same after aligning. Never accuracy. */
        public final double[] residualAfter;
        /** Fraction of pixels usable for the pair that owns each frame. */
        public final double[] validFraction;
        /** Per-frame outcome of the pair that owns it. */
        public final PairAligner.Status[] status;
        /**
         * What the user has to be told about this run, empty when there is nothing.
         *
         * <p><b>Read this.</b> Two of the three kinds describe outcomes that a table of per-frame
         * statuses already contains and nobody notices: a registration that quietly reports a floor on
         * the motion instead of the motion is worse than one that fails.
         */
        public final List<Warning> warnings;
        public final int levels;
        public final int workers;
        public final long pyramidsBuilt;
        public final long pyramidCacheHits;
        /** Automatic pixel-evidence decision, or null when the caller supplied the rule. */
        public final AutomaticInformationSelector.Result automaticInformation;

        Result(Transform[] cumulative, List<PairResult> pairs, int[] support,
               ChainRepair.Reason[] repairs, double[] log2Gain, double[] residualBefore,
               double[] residualAfter, double[] validFraction, PairAligner.Status[] status,
               List<Warning> warnings, int levels, int workers, long pyramidsBuilt,
               long pyramidCacheHits,
               AutomaticInformationSelector.Result automaticInformation) {
            this.cumulative = cumulative;
            this.pairs = pairs;
            this.support = support;
            this.repairs = repairs;
            this.log2Gain = log2Gain;
            this.residualBefore = residualBefore;
            this.residualAfter = residualAfter;
            this.validFraction = validFraction;
            this.status = status;
            this.warnings = java.util.Collections.unmodifiableList(warnings);
            this.levels = levels;
            this.workers = workers;
            this.pyramidsBuilt = pyramidsBuilt;
            this.pyramidCacheHits = pyramidCacheHits;
            this.automaticInformation = automaticInformation;
        }

        /** Median of {@link #residualAfter} over frames that have one. The headline QC number. */
        public double medianResidualAfter() {
            return median(residualAfter);
        }

        public double medianResidualBefore() {
            return median(residualBefore);
        }

        /** Returns the same result expressed in a coordinate system with larger pixels. */
        public Result scaleTranslations(double factor) {
            if (!(factor > 0) || factor == 1.0) return this;
            Transform[] scaledCumulative = new Transform[cumulative.length];
            for (int i = 0; i < cumulative.length; i++) {
                scaledCumulative[i] = cumulative[i].scaleTranslation(factor);
            }
            List<PairResult> scaledPairs = new ArrayList<>(pairs.size());
            for (PairResult pair : pairs) {
                PairAligner.Fit fit = pair.fit;
                PairAligner.Fit scaledFit = new PairAligner.Fit(
                        fit.transform.scaleTranslation(factor), fit.residualBefore,
                        fit.residualAfter, fit.logGain, fit.validFraction,
                        fit.iterations, fit.status);
                scaledPairs.add(new PairResult(pair.from, pair.to, scaledFit));
            }
            return new Result(scaledCumulative, scaledPairs, support, repairs, log2Gain,
                    residualBefore, residualAfter, validFraction, status, warnings, levels,
                    workers, pyramidsBuilt, pyramidCacheHits, automaticInformation);
        }

        private static double median(double[] a) {
            double[] v = new double[a.length];
            int n = 0;
            for (double x : a) {
                if (!Double.isNaN(x)) v[n++] = x;
            }
            if (n == 0) return Double.NaN;
            return RobustNorm.select(v, n, n / 2);
        }
    }

    /**
     * Register a recording.
     *
     * @throws java.util.concurrent.CancellationException if {@code cancellation} fires
     */
    public static Result run(FrameSource source, Options options,
                             PairScheduler.Progress progress,
                             PairScheduler.Cancellation cancellation) {
        if (source == null) throw new IllegalArgumentException("source is null");
        Options o = options == null ? new Options() : options.copy();
        PairScheduler.Progress prog = progress == null ? PairScheduler.Progress.NONE : progress;
        PairScheduler.Cancellation cancel =
                cancellation == null ? PairScheduler.Cancellation.NEVER : cancellation;

        int frames = source.count();
        int w = source.width();
        int h = source.height();
        if (frames < 1) throw new IllegalArgumentException("no frames");
        if (w < 2 || h < 2) throw new IllegalArgumentException("frames must be at least 2x2");
        if (o.referenceFrame < 0 || o.referenceFrame >= frames) {
            throw new IllegalArgumentException("reference frame " + o.referenceFrame
                    + " is outside 0.." + (frames - 1));
        }
        if (o.reference == Reconciler.Reference.MULTILAG && !containsLagOne(o.lags)) {
            throw new IllegalArgumentException("the lag set must contain 1, otherwise consecutive "
                    + "frames are never compared and some frames end up unconstrained");
        }
        // Refused rather than warned about, because it is not a configuration anybody should reach by
        // accident and the failure it produces looks like a working registration. See the message.
        if (o.reference == Reconciler.Reference.MULTILAG && !o.aligner.profileGain) {
            throw new IllegalArgumentException(MULTILAG_NEEDS_GAIN);
        }

        if (o.automaticInformationSupport) {
            o.automaticInformationResult = AutomaticInformationSelector.select(
                    source, o.epsilon, hasExplicitIntensityBand(o));
            o.automaticInformationResult.applyTo(o);
        }

        if (o.autoMaxShift) {
            o.aligner.maxShift = estimateShiftBound(source, o).suggestedMaxShift;
        }

        int levels = o.aligner.levelsFor(w, h);
        PyramidCache cache = new PyramidCache(
                frame -> pyramidFor(source, frame, levels, o),
                PyramidCache.capacityFor(frames, w, h,
                        PyramidCache.reachFor(o.reference, 1, o.lags),
                        Math.max(1, o.threads), o.memoryBudgetBytes, bytesPerPyramidPixel(o)));

        Result result;
        if (frames == 1) {
            result = trivial(o, levels);
        } else if (o.reference == Reconciler.Reference.ROLLING) {
            result = runRolling(source, o, cache, levels, prog, cancel);
        } else {
            result = runPlanned(source, o, cache, levels, frames, w, h, prog, cancel);
        }
        cache.clear();
        return result;
    }

    /**
     * Warm-start a second fit on caller-selected source-coordinate pixels.
     *
     * <p>The source supplies the actual intensities and gradients fitted by the solver. The masks
     * only decide which source pixels may vote; they never replace or filter a pixel value. This is
     * the production counterpart of the controlled filtered-mask benchmark.
     *
     * @param supportByFrame one finest-first mask pyramid per source frame
     */
    public static Result refitWithSupport(FrameSource source, Options options, Result pilot,
                                          boolean[][][] supportByFrame,
                                          PairScheduler.Progress progress,
                                          PairScheduler.Cancellation cancellation) {
        if (source == null || pilot == null || supportByFrame == null) {
            throw new IllegalArgumentException("source, pilot and support masks are required");
        }
        Options o = options == null ? new Options() : options.copy();
        PairScheduler.Progress prog = progress == null ? PairScheduler.Progress.NONE : progress;
        PairScheduler.Cancellation cancel = cancellation == null
                ? PairScheduler.Cancellation.NEVER : cancellation;
        int frames = source.count();
        int w = source.width();
        int h = source.height();
        if (frames != pilot.cumulative.length || frames != supportByFrame.length) {
            throw new IllegalArgumentException("source, pilot and support masks have different frame counts");
        }
        if (o.reference == Reconciler.Reference.ROLLING) {
            throw new IllegalArgumentException("pixel-selection refitting does not support a rolling template");
        }
        // A per-pixel solver mask only means something to a solver that votes pixel by pixel. An
        // area-correlation estimator scores whole windows, so a caller asking for both has asked for
        // two incompatible things and is told so rather than quietly getting one of them.
        if (o.estimator != PairEstimator.Kind.LOG_RATIO_FIT) {
            throw new IllegalArgumentException("pixel-selection refitting is a log-ratio fit method; "
                    + o.estimator.id() + " estimates whole windows and has no per-pixel support");
        }
        if (frames == 1) return trivial(o, o.aligner.levelsFor(w, h));
        if (o.automaticInformationSupport) {
            o.automaticInformationResult = AutomaticInformationSelector.select(
                    source, o.epsilon, hasExplicitIntensityBand(o));
            o.automaticInformationResult.applyTo(o);
        }
        if (o.autoMaxShift) {
            o.aligner.maxShift = estimateShiftBound(source, o).suggestedMaxShift;
        }

        int levels = o.aligner.levelsFor(w, h);
        for (int t = 0; t < frames; t++) {
            if (supportByFrame[t] == null || supportByFrame[t].length != levels) {
                throw new IllegalArgumentException("support for frame " + t + " has "
                        + (supportByFrame[t] == null ? "null" : supportByFrame[t].length)
                        + " levels, expected " + levels);
            }
        }
        PyramidCache cache = new PyramidCache(
                frame -> pyramidFor(source, frame, levels, o),
                PyramidCache.capacityFor(frames, w, h,
                        PyramidCache.reachFor(o.reference, 1, o.lags),
                        Math.max(1, o.threads), o.memoryBudgetBytes, bytesPerPyramidPixel(o)));
        try {
            final List<Reconciler.Observation> plan = Reconciler.planPairs(
                    o.reference, frames, o.referenceFrame, 1, o.lags);
            long memPerTask = 2L * bytesPerPyramidPixel(o) * w * h;
            int workers = PairScheduler.workersFor(plan.size(), o.threads, memPerTask,
                    o.memoryBudgetBytes);
            List<PairAligner.Fit> fits = PairScheduler.map(plan.size(), workers, index -> {
                Reconciler.Observation edge = plan.get(index);
                Transform start = pilot.cumulative[edge.from].inverse()
                        .then(pilot.cumulative[edge.to]);
                return PairAligner.alignFrom(cache.get(edge.from), cache.get(edge.to), o.aligner,
                        start, supportByFrame[edge.from]);
            }, prog, cancel);

            List<PairResult> pairs = new ArrayList<>(plan.size());
            List<Reconciler.Observation> solved = new ArrayList<>(plan.size());
            for (int i = 0; i < plan.size(); i++) {
                Reconciler.Observation edge = plan.get(i);
                PairAligner.Fit fit = fits.get(i);
                pairs.add(new PairResult(edge.from, edge.to, fit));
                if (fit != null && fit.usable()) {
                    solved.add(new Reconciler.Observation(edge.from, edge.to, fit.transform));
                }
            }
            Reconciler.Solution solution = reconcile(o, frames, pairs, solved);
            return assemble(o, frames, levels, workers, pairs, solution, cache);
        } finally {
            cache.clear();
        }
    }

    // ---- what the user has to be told ---------------------------------------------------- //

    /** Why {@link Reconciler.Reference#MULTILAG} without gain profiling is refused, not warned about. */
    static final String MULTILAG_NEEDS_GAIN =
            "MULTILAG with profileGain=false is not a supported configuration. Under the benchmark's 4x "
            + "gain fade it costs a consecutive chain 3.3x and multi-lag 163x on the three-seed mean — "
            + "and 550x on the seed with the least structure, where 0.043 px became 23.7 px. The reason "
            + "is structural: a consecutive pair spans one 2.9% brightness step where a lag-16 pair "
            + "spans 38%, so the reconciliation that most improves accuracy is the one that most "
            + "punishes a missing gain model. The flag exists so the gain-invariance claim can be "
            + "measured against its own negation; drive PairAligner.align directly to do that.";

    /**
     * Everything wrong with a configuration that can be known before a pixel is read.
     *
     * <p>Separate from {@link #run} so a dialog can tell the user before they wait, rather than after.
     * {@link Result#warnings} contains these plus the ones that only the outcome can reveal.
     */
    public static List<Warning> configurationWarnings(Options options) {
        List<Warning> out = new ArrayList<>();
        if (options == null) return out;
        boolean multiLag = options.reference == Reconciler.Reference.MULTILAG;
        RobustNorm norm = options.aligner.norm;
        if (!multiLag && norm == RobustNorm.TUKEY) {
            String measured = options.reference == Reconciler.Reference.CONSECUTIVE
                    ? "Measured on a consecutive chain: "
                    : "Measured on a consecutive chain, and " + options.reference + " has the same "
                      + "single observation per frame, so the same reasoning applies: ";
            out.add(new Warning(Warning.Kind.NORM_FOR_REFERENCE,
                    measured + "a redescending norm (TUKEY) reaches 0.466 px where LEAST_SQUARES "
                    + "reaches 0.281 px on clean data, and it also loses on the gain fade and on moved "
                    + "change. Tukey only pays for itself once MULTILAG's redundancy absorbs its "
                    + "noisier per-pair estimates, or when the recording contains saturating debris. "
                    + "Registration.Options.recommendedFor(" + options.reference + ") gives the "
                    + "measured-best combination."));
        }
        if (multiLag && norm == RobustNorm.LEAST_SQUARES) {
            out.add(new Warning(Warning.Kind.NORM_FOR_REFERENCE,
                    "LEAST_SQUARES with MULTILAG measures worse than TUKEY under every benchmark "
                    + "condition (0.049 / 0.081 / 0.122 / 6.82 against 0.036 / 0.057 / 0.050 / 4.76). "
                    + "Registration.Options.recommendedFor(MULTILAG) gives the measured-best "
                    + "combination."));
        }
        return out;
    }

    /**
     * Everything worth telling the user <em>before</em> the run, including what only the pixels know.
     *
     * <p>{@link #configurationWarnings} plus a localisability check, which needs the frames and so
     * cannot be answered from the options alone. Reads every frame once. Meant for a dialog: the point
     * of a warning about unregistrable data is that it arrives before the wait, not after it, and the
     * one thing that predicts registrability is not the one a user would think to look at — across 24
     * real recordings, localisability separated the usable from the unusable by an order of magnitude
     * where frame correlation did not separate them at all.
     */
    public static List<Warning> preflight(FrameSource source, Options options) {
        List<Warning> out = configurationWarnings(options);
        if (source == null) return out;
        double d = Localisability.of(source);
        if (Localisability.poor(d)) {
            out.add(new Warning(Warning.Kind.LOW_LOCALISABILITY, String.format(
                    "Localisability %.4f, below the %.2f warning threshold. A one-pixel shift barely "
                    + "changes this recording's frame-to-frame correlation, which is the gradient any "
                    + "registration has to descend. Across 24 real recordings, the eleven in this band "
                    + "had two independent estimators disagreeing by 1.6 to 18.6 px; the thirteen above "
                    + "it agreed to better than 0.7 px. If the recording has other channels, rank them "
                    + "and estimate from the best one.", d, Localisability.WARN_BELOW)));
        }
        return out;
    }

    /** The configuration warnings, plus the ones only the finished run can know. */
    private static List<Warning> warningsFor(Options o, List<PairResult> pairs,
                                             PairAligner.Status[] status) {
        List<Warning> out = configurationWarnings(o);

        int atBound = 0;
        for (PairAligner.Status s : status) {
            if (s == PairAligner.Status.AT_SHIFT_BOUND) atBound++;
        }
        if (atBound > 0) {
            out.add(new Warning(Warning.Kind.AT_SHIFT_BOUND, String.format(
                    "%d frame%s ended on the %.1f px maxShift bound. Their motion is reported as a "
                    + "floor, not as a measurement, and a chain carries that error into every later "
                    + "frame. Real recordings reach frame-to-frame steps of 1,154 px, so the 30 px "
                    + "default is not a safe bound: raise aligner.maxShift, or set autoMaxShift=true "
                    + "to derive it from the recording.",
                    atBound, atBound == 1 ? "" : "s", o.aligner.maxShift)));
        }

        int refused = 0;
        for (PairResult p : pairs) {
            if (p.fit != null && !p.fit.usable()) refused++;
        }
        if (refused > 0) {
            out.add(new Warning(Warning.Kind.REFUSED_PAIRS, String.format(
                    "%d of %d pairs had too few usable pixels and were refused. Their frames were "
                    + "filled in by ChainRepair rather than measured.", refused, pairs.size())));
        }
        return out;
    }

    // ---- deriving the shift bound from the recording ------------------------------------- //

    /** What a coarse pass found out about how far the content actually moves. */
    public static final class ShiftEstimate {
        /** Largest displacement over any planned pair, in full-resolution pixels. */
        public final double largestStepPx;
        /** A bound with headroom, safe to hand to {@link PairAligner.Options#maxShift}. */
        public final double suggestedMaxShift;
        /** One coarse pixel, in full-resolution pixels — the resolution of {@link #largestStepPx}. */
        public final double resolutionPx;
        /** Pairs measured. */
        public final int pairs;

        ShiftEstimate(double largestStepPx, double suggestedMaxShift, double resolutionPx, int pairs) {
            this.largestStepPx = largestStepPx;
            this.suggestedMaxShift = suggestedMaxShift;
            this.resolutionPx = resolutionPx;
            this.pairs = pairs;
        }

        @Override
        public String toString() {
            return String.format("largest step %.0f px (+-%.0f), suggested maxShift %.0f px, "
                    + "from %d pairs", largestStepPx, resolutionPx, suggestedMaxShift, pairs);
        }
    }

    /** Headroom on the largest measured step, matching what the motion survey uses. */
    private static final double SHIFT_HEADROOM = 1.5;
    /** Coarsest plane used by the shift estimate, pixels on the shorter axis. */
    private static final int SHIFT_PASS_SIZE = 32;
    /** Levels the shift estimate may reduce by. Eight halvings take 8,192 px down to 32. */
    private static final int SHIFT_PASS_MAX_LEVELS = 9;

    /**
     * How far the content actually moves, from one heavily reduced pass over the planned pairs.
     *
     * <p><b>Why this is needed at all.</b> {@code maxShift} is a bound, and a solution that lands on it
     * is reported as {@link PairAligner.Status#AT_SHIFT_BOUND} and otherwise looks like a successful
     * registration. Verified on {@code VID47_C1}, where a 223 px jump pinned the estimate at 128 px.
     * A user cannot be expected to guess the right bound: the measured largest steps across 24
     * recordings ran from under a pixel to 1,154.
     *
     * <p><b>How it stays cheap.</b> Every frame is reduced until its shorter axis is about
     * {@value #SHIFT_PASS_SIZE} px, and the same criterion and the same code path — a one-level
     * pyramid through {@link PairAligner#align} — searches the whole of that tiny plane. A 1,024 px
     * frame becomes 32 px, so a 33x33 integer sweep covers the entire half-frame of shift that is worth
     * having any overlap for, at 1/1024 of the pixels. The cost is that the answer is quantised to one
     * coarse pixel, which is why {@link ShiftEstimate#resolutionPx} is reported and why the suggestion
     * carries {@value #SHIFT_HEADROOM}x headroom on top.
     *
     * <p>The pass covers <em>every planned pair</em>, not only consecutive ones, because a lag-16 pair
     * spans sixteen steps' worth of drift and it is that span the bound has to cover.
     */
    public static ShiftEstimate estimateShiftBound(FrameSource source, Options options) {
        if (source == null) throw new IllegalArgumentException("source is null");
        Options o = options == null ? new Options() : options.copy();
        int frames = source.count();
        int w = source.width();
        int h = source.height();
        if (frames < 2) return new ShiftEstimate(0, o.aligner.maxShift, 1, 0);

        int levels = LogPlane.autoLevels(w, h, SHIFT_PASS_SIZE, SHIFT_PASS_MAX_LEVELS);
        double scale = 1 << (levels - 1);
        int cw = w, ch = h;
        for (int l = 1; l < levels; l++) {
            cw /= 2;
            ch /= 2;
        }
        // Half the coarse frame: beyond that the two frames barely overlap, so a larger sweep would
        // only offer the solver more ways to be confidently wrong.
        double coarseRadius = Math.max(1, Math.min(cw, ch) / 2);

        PairAligner.Options po = o.aligner.copy();
        po.levels = 1;
        po.maxShift = coarseRadius;
        po.coarseRadiusBudget = (int) Math.ceil(coarseRadius);
        po.support = PairAligner.PixelSupport.ALL;

        List<Reconciler.Observation> plan = o.reference == Reconciler.Reference.ROLLING
                ? Reconciler.planPairs(Reconciler.Reference.CONSECUTIVE, frames, 0, 1, o.lags)
                : Reconciler.planPairs(o.reference, frames, o.referenceFrame, 1, o.lags);

        LogPlane[] coarse = new LogPlane[frames];
        double largest = 0;
        int measured = 0;
        for (Reconciler.Observation ob : plan) {
            LogPlane[] a = coarsePlane(coarse, source, ob.from, levels, o);
            LogPlane[] b = coarsePlane(coarse, source, ob.to, levels, o);
            PairAligner.Fit fit = PairAligner.align(a, b, po);
            if (!fit.usable()) continue;
            largest = Math.max(largest, fit.transform.magnitude() * scale);
            measured++;
        }

        double floor = new PairAligner.Options().maxShift;
        double suggested = Math.max(floor, SHIFT_HEADROOM * largest + scale);
        return new ShiftEstimate(largest, suggested, scale, measured);
    }

    private static LogPlane[] coarsePlane(LogPlane[] cache, FrameSource source, int frame, int levels,
                                          Options o) {
        if (cache[frame] == null) {
            LogPlane[] p = logPlane(preprocess(source.plane(frame), o), source.width(),
                    source.height(), o).pyramid(levels);
            cache[frame] = p[levels - 1];
        }
        return new LogPlane[]{cache[frame]};
    }

    // ------------------------------------------------------------------------------------ //

    private static Result runPlanned(FrameSource source, Options o, PyramidCache cache, int levels,
                                     int frames, int w, int h, PairScheduler.Progress prog,
                                     PairScheduler.Cancellation cancel) {
        final List<Reconciler.Observation> plan = Reconciler.planPairs(
                o.reference, frames, o.referenceFrame, 1, o.lags);

        long memPerTask = 2L * bytesPerPyramidPixel(o) * w * h;
        int workers = PairScheduler.workersFor(plan.size(), o.threads, memPerTask,
                o.memoryBudgetBytes);

        List<PairAligner.Fit> fits = PairScheduler.map(plan.size(), workers, index -> {
            Reconciler.Observation ob = plan.get(index);
            return o.estimator.estimate(cache.get(ob.from), cache.get(ob.to), o.aligner);
        }, prog, cancel);

        List<PairResult> pairs = new ArrayList<>(plan.size());
        List<Reconciler.Observation> solved = new ArrayList<>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation ob = plan.get(i);
            PairAligner.Fit fit = fits.get(i);
            pairs.add(new PairResult(ob.from, ob.to, fit));
            if (fit != null && fit.usable()) {
                solved.add(new Reconciler.Observation(ob.from, ob.to, fit.transform));
            }
        }

        Reconciler.Solution solution = reconcile(o, frames, pairs, solved);
        return assemble(o, frames, levels, workers, pairs, solution, cache);
    }

    private static Reconciler.Solution reconcile(Options o, int frames, List<PairResult> pairs,
                                                 List<Reconciler.Observation> solved) {
        switch (o.reference) {
            case MULTILAG:
                return Reconciler.multiLag(frames, solved);
            case FIXED: {
                Transform[] toRef = new Transform[frames];
                for (PairResult p : pairs) {
                    if (p.fit != null && p.fit.usable() && p.from == o.referenceFrame) {
                        toRef[p.to] = p.fit.transform;
                    }
                }
                return Reconciler.fixed(toRef, o.referenceFrame);
            }
            case CONSECUTIVE:
            default: {
                Transform[] steps = new Transform[frames - 1];
                for (PairResult p : pairs) {
                    if (p.fit != null && p.fit.usable() && p.to == p.from + 1) {
                        steps[p.from] = p.fit.transform;
                    }
                }
                return Reconciler.chain(steps);
            }
        }
    }

    /**
     * Sequential alignment to a running mean of the frames already registered.
     *
     * <p>Averaging the target suppresses its noise, which is worth real accuracy on a dim recording
     * where a single frame is a poor thing to aim at. The cost is that fast genuine change gets smeared
     * into the template, so the estimate lags behind reality — which is why this is a preset for noisy
     * data and not the default.
     */
    private static Result runRolling(FrameSource source, Options o, PyramidCache cache, int levels,
                                     PairScheduler.Progress prog,
                                     PairScheduler.Cancellation cancel) {
        int frames = source.count();
        int w = source.width();
        int h = source.height();
        int window = Math.max(1, o.templateWindow);

        Transform[] cum = new Transform[frames];
        List<PairResult> pairs = new ArrayList<>();
        cum[0] = Transform.IDENTITY;

        List<float[]> recent = new ArrayList<>();
        recent.add(preprocess(source.plane(0), o));
        float[] template = new float[w * h];
        float[] warped = new float[w * h];

        for (int t = 1; t < frames; t++) {
            if (cancel.cancelled()) throw new java.util.concurrent.CancellationException("cancelled");
            average(recent, template);
            LogPlane[] templatePyramid = logPlane(template, w, h, o).pyramid(levels);
            PairAligner.Fit fit = o.estimator.estimate(templatePyramid, cache.get(t), o.aligner);
            // Recorded against the reference frame, not against t-1. The template is held at
            // reference brightness below, so the gain this pair measures is already the frame's gain
            // relative to the reference. Recording it as a (t-1, t) step would make `assemble`
            // accumulate it on top of the previous frame's gain and count the whole trace twice —
            // measured as -2.59 log2 against a true -1.00 on a synthetic bleaching series.
            pairs.add(new PairResult(0, t, fit));
            cum[t] = fit.usable() ? fit.transform : null;

            // The frame joins the template only after being put back into reference coordinates AND
            // reference brightness. Without the brightness step the template tracks the recording's
            // own fading, so every later gain is measured against a moving baseline.
            float[] src = preprocess(source.plane(t), o);
            double toReference = fit.usable() ? Math.pow(2.0, -fit.logGain) : 1.0;
            if (toReference != 1.0) {
                src = src.clone();
                for (int i = 0; i < src.length; i++) {
                    src[i] = (float) (src[i] * toReference);
                }
            }
            Warper.warp(src, warped, w, h,
                    cum[t] == null ? Transform.IDENTITY : cum[t],
                    Warper.Interpolation.BILINEAR, Float.NaN);
            recent.add(warped.clone());
            while (recent.size() > window) recent.remove(0);
            prog.update(t, frames - 1);
        }

        int[] support = new int[frames];
        support[0] = 1;
        for (int t = 1; t < frames; t++) support[t] = cum[t] == null ? 0 : 1;
        Reconciler.Solution solution = new Reconciler.Solution(replaceNulls(cum), support);
        return assemble(o, frames, levels, 1, pairs, solution, cache);
    }

    private static Transform[] replaceNulls(Transform[] a) {
        Transform[] out = a.clone();
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) out[i] = Transform.IDENTITY;
        }
        return out;
    }

    private static void average(List<float[]> planes, float[] out) {
        java.util.Arrays.fill(out, 0f);
        int[] counts = new int[out.length];
        for (float[] p : planes) {
            for (int i = 0; i < out.length; i++) {
                float v = p[i];
                if (Float.isNaN(v)) continue;
                out[i] += v;
                counts[i]++;
            }
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = counts[i] > 0 ? out[i] / counts[i] : Float.NaN;
        }
    }

    // ------------------------------------------------------------------------------------ //

    /** Repair the chain, then derive every per-frame column from the pair that owns each frame. */
    private static Result assemble(Options o, int frames, int levels, int workers,
                                   List<PairResult> pairs, Reconciler.Solution solution,
                                   PyramidCache cache) {
        int anchor = o.reference == Reconciler.Reference.FIXED ? o.referenceFrame : 0;
        ChainRepair.Result repaired = ChainRepair.repair(
                solution.cumulative, solution.support, anchor, o.outlierMads);

        // The pair that "owns" a frame is the one that measured it most directly: smallest lag among
        // those ending at it. That is the lag-1 transition for a chain, and the reference pair for
        // fixed. Long-lag pairs still constrain the solve; they just are not what the row reports.
        PairResult[] owner = new PairResult[frames];
        for (PairResult p : pairs) {
            if (p.to < 0 || p.to >= frames) continue;
            PairResult held = owner[p.to];
            if (held == null || p.lag() < held.lag()) owner[p.to] = p;
        }

        double[] gain = new double[frames];
        double[] before = new double[frames];
        double[] after = new double[frames];
        double[] valid = new double[frames];
        PairAligner.Status[] status = new PairAligner.Status[frames];
        java.util.Arrays.fill(before, Double.NaN);
        java.util.Arrays.fill(after, Double.NaN);
        java.util.Arrays.fill(valid, Double.NaN);
        status[anchor] = PairAligner.Status.OK;
        valid[anchor] = 1.0;

        for (int t = 0; t < frames; t++) {
            PairResult p = owner[t];
            if (p == null || p.fit == null) continue;
            before[t] = p.fit.residualBefore;
            after[t] = p.fit.residualAfter;
            valid[t] = p.fit.validFraction;
            status[t] = p.fit.status;
        }
        // Gain accumulates along the ownership chain. Frames are visited in increasing index, and an
        // owner's `from` is always a lower index for chained strategies or the anchor for fixed, so
        // the value it depends on is already final.
        for (int t = 0; t < frames; t++) {
            if (t == anchor) {
                gain[t] = 0;
                continue;
            }
            PairResult p = owner[t];
            if (p == null || p.fit == null || !p.fit.usable()) {
                gain[t] = t > 0 ? gain[t - 1] : 0;
                continue;
            }
            double base = p.from >= 0 && p.from < frames ? gain[p.from] : 0;
            gain[t] = base + p.fit.logGain;
        }

        return new Result(repaired.cumulative, pairs, solution.support, repaired.reasons,
                gain, before, after, valid, status, warningsFor(o, pairs, status), levels, workers,
                cache.misses(), cache.hits(), o.automaticInformationResult);
    }

    private static Result trivial(Options o, int levels) {
        return new Result(new Transform[]{Transform.IDENTITY}, new ArrayList<>(), new int[]{1},
                new ChainRepair.Reason[1], new double[1],
                new double[]{Double.NaN}, new double[]{Double.NaN}, new double[]{1.0},
                new PairAligner.Status[]{PairAligner.Status.OK}, configurationWarnings(o),
                levels, 1, 0, 0, o.automaticInformationResult);
    }

    private static boolean hasExplicitIntensityBand(Options o) {
        return !Double.isNaN(o.intensityFloorPercentile)
                || !Double.isNaN(o.saturationPercentile)
                || o.intensityFloor != LogPlane.NO_FLOOR
                || o.saturationMax != LogPlane.NO_SATURATION;
    }

    /**
     * What one resident pyramid costs per full-resolution pixel under these options.
     *
     * <p>The bare pyramid, plus whatever the chosen estimator keeps prepared on each plane. Both the
     * cache capacity and the worker count are derived from this, so an estimator that memoises a
     * prepared view has to be counted here or a long recording holds too many pyramids and runs out
     * of memory — which is a worse outcome than being slow.
     */
    private static long bytesPerPyramidPixel(Options o) {
        return PyramidCache.BYTES_PER_PIXEL + o.estimator.cachedBytesPerPixel();
    }

    private static LogPlane[] pyramidFor(FrameSource source, int frame, int levels, Options o) {
        float[] plane = preprocess(source.plane(frame), o);
        LogPlane base = logPlane(plane, source.width(), source.height(), o);
        // Level 0 is identical either way; only how coarse levels are averaged differs, and only an
        // estimator that has declared it wants intensity-domain averaging ever sees the difference.
        return o.estimator.prefersLinearPyramid()
                ? base.linearPyramid(levels) : base.pyramid(levels);
    }

    /** One frame's level 0, with both intensity limits resolved from the frame's own intensities. */
    private static LogPlane logPlane(float[] plane, int w, int h, Options o) {
        double satMax = Double.isNaN(o.saturationPercentile)
                ? o.saturationMax
                : percentile(plane, o.saturationPercentile);
        if (Double.isNaN(satMax)) satMax = o.saturationMax;
        double floor = Double.isNaN(o.intensityFloorPercentile)
                ? o.intensityFloor
                : percentile(plane, o.intensityFloorPercentile);
        if (Double.isNaN(floor)) floor = o.intensityFloor;
        return LogPlane.of(plane, w, h, o.epsilon, floor, satMax);
    }

    /**
     * Removes a per-frame additive background, when asked.
     *
     * <p><b>Be clear about what this is and is not.</b> The log-ratio criterion is <em>exactly</em>
     * invariant to a multiplicative gain, because a gain becomes an additive constant in the log domain
     * and that constant is profiled out analytically. An additive offset in <em>intensity</em> — dark
     * current, background fluorescence, a changed camera offset — is not, because it does not survive
     * the log as a constant. So it is removed here, before the log, by subtracting a low percentile of
     * the frame's own intensities.
     *
     * <p>That is an estimate, not a profiled-out parameter, and it is worth saying so plainly: it
     * assumes the darkest percent of the frame is background. On a frame that is genuinely full of
     * signal edge to edge, it will remove real intensity instead. It is off by default for that reason.
     */
    static float[] preprocess(float[] plane, Options o) {
        if (!o.removeOffset) return plane;
        double background = percentile(plane, o.offsetPercentile);
        if (!(background > 0) || Double.isNaN(background)) return plane;
        float[] out = new float[plane.length];
        for (int i = 0; i < plane.length; i++) {
            out[i] = Float.isNaN(plane[i]) ? Float.NaN : (float) (plane[i] - background);
        }
        return out;
    }

    /** Percentile of the finite values of {@code a}, {@code q} in percent. */
    static double percentile(float[] a, double q) {
        double[] v = new double[a.length];
        int n = 0;
        for (float x : a) {
            if (!Float.isNaN(x)) v[n++] = x;
        }
        if (n == 0) return Double.NaN;
        int k = (int) Math.round((q / 100.0) * (n - 1));
        k = Math.max(0, Math.min(n - 1, k));
        return RobustNorm.select(v, n, k);
    }

    private static boolean containsLagOne(int[] lags) {
        if (lags == null) return false;
        for (int k : lags) {
            if (k == 1) return true;
        }
        return false;
    }
}
