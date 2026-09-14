/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.PhaseCorrelation;

import ripr.core.Localisability;
import ripr.core.PairAligner;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.RobustNorm;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Measures what kind of movement each recording in an archive actually contains, so exemplars can be
 * chosen rather than guessed at. <b>Test scope only.</b>
 *
 * <p>The demonstration library needs recordings that span the kinds of movement a user will meet —
 * slow drift, jitter, a knocked stage, a wandering walk, something periodic — at several severities.
 * Hundreds of gigabytes of real time-lapse already exist; the problem is not finding data, it is knowing
 * which recording is an example of what. This walks a directory of recordings, reduces each to a handful
 * of numbers describing its motion, and labels it.
 *
 * <p><b>Two guards, because the obvious way to do this is circular.</b>
 *
 * <p>First, the trace used to classify a recording comes from this plugin, so it cannot also be the
 * evidence that the plugin handled that recording. Every series is therefore measured twice — once by
 * the log-ratio estimator and once by {@link PhaseCorrelation}, which shares none of its machinery — and
 * the agreement between them is reported alongside the label. A label backed by two independent methods
 * is usable; one where they disagree is a separate and more interesting finding, and is flagged rather
 * than averaged away.
 *
 * <p>Second, a straightness ratio does not separate these motions. A recording drifting five pixels
 * with a pixel of jitter on top has a path length far longer than its net displacement, so
 * net-over-path calls it jitter. Each trace is therefore split into a fitted linear component and the
 * residual about it, and the residual is judged by whether it wanders or rattles — a random walk's
 * excursion is many times its step size, white jitter's is comparable to it. That single ratio,
 * {@code wander}, is what distinguishes the two, and neither path length nor net displacement can.
 *
 * <p><b>Both estimators run as a plain consecutive chain, and multi-lag reconciliation is deliberately
 * not used here.</b> Two reasons, and the second is the important one. First, a lag of 8 or 16 spanning a
 * large jump exceeds the search bound, and the least-squares reconciliation then spreads that one bad
 * observation across every frame in the window — measured on the first two recordings surveyed, that made
 * the two methods disagree everywhere rather than only at the jump. Second, multi-lag exists to average
 * error out of a trace, which means it alters the very motion process these descriptors are trying to
 * characterise: it would suppress the difference between jitter and a random walk by construction. The
 * question of which reconciliation strategy suits which kind of motion is what the library is being
 * built to answer, so the survey that chooses the library's contents must not presuppose an answer.
 *
 * <p>Frames are binned on load. The classification needs a coarse trace from many recordings, not a
 * precise one from a few, and every frame read is a Dropbox download.
 *
 * <pre>
 *   java -cp &lt;classes&gt;;&lt;test-classes&gt;;ij.jar ripr.MotionSurvey \
 *        &lt;out dir&gt; &lt;bin&gt; &lt;from&gt; &lt;count&gt; &lt;recordings dir&gt; [more dirs ...]
 * </pre>
 */
public final class MotionSurvey {

    /** Ceiling on the derived translation bound, in binned pixels. A runaway trace must not run away. */
    private static final double MAX_SHIFT_BINNED = 96;
    /** Floor on it, so a nearly still recording still gets a sane search radius. */
    private static final double MIN_SHIFT_BINNED = 12;
    /** Multiple of the largest phase-correlation step allowed for, before the floor is added. */
    private static final double SHIFT_HEADROOM = 1.5;
    /** Below this frame-to-frame correlation nothing can be registered, so nothing is claimed. */
    private static final double UNREGISTRABLE_CORRELATION = 0.30;
    /** A step must exceed both of these to count as a knock: absolute pixels, and multiples of typical. */
    private static final double KNOCK_FLOOR_PX = 3.0;
    private static final double KNOCK_MULTIPLE = 6.0;
    /** Residual excursion over step size. Near 0.7 is white jitter; a random walk is several. */
    private static final double WANDER_WALK = 2.5;
    /** Linear component must exceed this multiple of the residual to be called drift-dominated. */
    private static final double DRIFT_DOMINANCE = 3.0;
    /** Fraction of residual power in one frequency bin before the motion is called periodic. */
    private static final double OSCILLATION_POWER = 0.25;
    /** Minimum cycles a period must complete inside the window to be believed. See {@link #periodicity}. */
    private static final int MIN_CYCLES = 4;
    /**
     * Severity bands, in pixels of total excursion.
     *
     * <p>Four bands, not three. The measured range across 24 real recordings runs from 0.6 px to a
     * single frame-to-frame step of 1154 px, and with a ceiling at 8 px everything above a couple of
     * pixels collapsed into one label — which made a 17 px knock and a 210 px one indistinguishable in
     * the summary. The break points sit where the data actually separates.
     */
    private static final double MILD_PX = 2.0;
    private static final double MODERATE_PX = 8.0;
    private static final double SEVERE_PX = 32.0;

    public static void main(String[] args) throws IOException {
        Path out = Paths.get(args[0]);
        int bin = Integer.parseInt(args[1]);
        int from = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        Files.createDirectories(out.resolve("traces"));

        Path csv = out.resolve("motion_survey.csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv))) {
            w.println("set,series,frames,from_h,span_h,interval_min,plane,corr_phase,corr_green,"
                    + "corr_red,sharp_phase,sharp_green,sharp_red,net_px,path_px,drift_px,"
                    + "resid_rms_px,step_rms_px,step_max_px,wander,knocks,period_frames,period_h,"
                    + "period_power,gain_log2,pc_net_px,pc_path_px,pc_max_step_px,max_shift_binned,"
                    + "agree_step_px,agree_net_px,label,severity,at_bound,refused,cpu_secs");
        }

        System.out.printf("bin %d, frames %d starting at index %d; search bound derived per recording "
                        + "from phase correlation, %.0f..%.0f binned px (%.0f..%.0f real)%n%n",
                bin, count, from, MIN_SHIFT_BINNED, MAX_SHIFT_BINNED,
                MIN_SHIFT_BINNED * bin, MAX_SHIFT_BINNED * bin);
        System.out.printf("%-12s %-6s %6s %7s %7s %6s %5s %5s %3s %-24s %s%n",
                "series", "plane", "drift", "residRM", "stepMax", "wander", "knock", "agree", "bnd",
                "label", "severity");
        System.out.println("--------------------------------------------------------------"
                + "------------------------------------------------");

        // Every frame read is a Dropbox download, so a dry run on two recordings has to be possible
        // without committing to the whole archive.
        String only = System.getProperty("ripr.onlySeries", "");
        List<String> wanted = only.isEmpty()
                ? java.util.Collections.<String>emptyList()
                : java.util.Arrays.asList(only.split(","));

        for (int i = 4; i < args.length; i++) {
            Path dir = Paths.get(args[i]);
            List<IncucyteSeries> all = IncucyteSeries.discover(dir);
            System.out.println("## " + dir.getFileName() + "  (" + all.size() + " recordings)");
            for (IncucyteSeries s : all) {
                if (!wanted.isEmpty() && !wanted.contains(s.key)) continue;
                try {
                    survey(s, from, count, bin, csv, out.resolve("traces"));
                } catch (RuntimeException | IOException e) {
                    System.out.printf("%-12s FAILED: %s%n", s.key, e);
                }
            }
            System.out.println();
        }
        System.out.println("wrote " + csv);
    }

    private static void survey(IncucyteSeries s, int from, int count, int bin, Path csv, Path traces)
            throws IOException {
        Timing timing = Timing.start();
        IncucyteSeries.Window win = s.load(from, count, bin);
        int n = win.plane(IncucyteSeries.Plane.PHASE).length;

        double[] corr = new double[IncucyteSeries.Plane.values().length];
        double[] sharp = new double[IncucyteSeries.Plane.values().length];
        IncucyteSeries.Plane best = IncucyteSeries.Plane.PHASE;
        for (IncucyteSeries.Plane p : IncucyteSeries.Plane.values()) {
            corr[p.ordinal()] = frameCorrelation(win.plane(p));
            sharp[p.ordinal()] = localisability(win.plane(p), win.width, win.height);
            if (sharp[p.ordinal()] > sharp[best.ordinal()]) best = p;
        }
        if (Boolean.getBoolean("ripr.perPlane")) {
            perPlane(s, win, bin);
            return;
        }

        // The independent second opinion runs FIRST, because it also sets the other method's search
        // bound. Phase correlation searches the whole frame, so it needs no prior on how far anything
        // moved; the log-ratio estimator takes an explicit maxShift and silently saturates against it.
        // With a fixed guess of 32 binned px, the first recording surveyed contained a 223 px jump, the
        // estimator stopped at 128 px, and the descriptors described the bound instead of the motion.
        float[][] plane = win.plane(best);
        double[] px = new double[n];
        double[] py = new double[n];
        double largestStep = 0;
        for (int t = 1; t < n; t++) {
            double[] step = PhaseCorrelation.shift(plane[t - 1], plane[t], win.width, win.height);
            px[t] = px[t - 1] + step[0] * bin;
            py[t] = py[t - 1] + step[1] * bin;
            largestStep = Math.max(largestStep, Math.hypot(step[0], step[1]));
        }
        double maxShift = Math.max(MIN_SHIFT_BINNED,
                Math.min(MAX_SHIFT_BINNED, SHIFT_HEADROOM * largestStep + MIN_SHIFT_BINNED));

        Registration.Options o = new Registration.Options();
        o.reference = Reconciler.Reference.CONSECUTIVE;
        o.aligner.norm = RobustNorm.TUKEY;
        o.aligner.support = PairAligner.PixelSupport.GRADIENT;
        o.aligner.maxShift = maxShift;
        Registration.Result r = Registration.run(win.source(best), o, null, null);

        double[] dx = new double[n];
        double[] dy = new double[n];
        for (int t = 0; t < n; t++) {
            dx[t] = r.cumulative[t].dx * bin;
            dy[t] = r.cumulative[t].dy * bin;
        }

        Descriptors d = describe(dx, dy);
        Descriptors pc = describe(px, py);
        double agreeStep = medianStepDifference(dx, dy, px, py);
        double agreeNet = Math.hypot(dx[n - 1] - px[n - 1], dy[n - 1] - py[n - 1]);
        // Counted apart from other refusals: hitting the bound means the number is a floor on the true
        // motion, not a measurement of it, and any descriptor built on it is describing the search box.
        int atBound = 0;
        int refused = 0;
        for (PairAligner.Status st : r.status) {
            if (st == null || st == PairAligner.Status.OK) continue;
            refused++;
            if (st == PairAligner.Status.AT_SHIFT_BOUND) atBound++;
        }

        String label = label(corr[best.ordinal()], d);
        String severity = severity(d);
        double periodH = d.periodFrames * s.intervalMinutes() / 60.0;
        // CPU, not elapsed: a survey left running overnight on a laptop spends much of it suspended,
        // and that is not a cost of the estimator. See Timing.
        double secs = timing.stop().cpuNs() / 1e9;
        if (timing.unscheduled()) System.out.printf("  !! %s%n", timing.note());

        System.out.printf("%-12s %-6s %6.2f %7.2f %7.2f %6.2f %5d %5.2f %3d %-24s %s%n",
                s.key, best, d.driftPx, d.residRms, d.stepMax, d.wander, d.knocks, agreeStep,
                atBound, label, severity);

        String row = String.format("%s,%s,%d,%.1f,%.1f,%.0f,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,"
                        + "%.2f,%.1f,%.2f,%.2f,"
                        + "%.2f,%.2f,%.2f,%d,%.1f,%.2f,%.3f,%+.4f,%.2f,%.1f,%.1f,%.0f,%.2f,%.2f,"
                        + "%s,%s,%d,%d,%.1f%n",
                s.set, s.key, n, s.absoluteHours(from), s.hours(from + n - 1),
                s.intervalMinutes(), best,
                corr[IncucyteSeries.Plane.PHASE.ordinal()],
                corr[IncucyteSeries.Plane.GREEN.ordinal()],
                corr[IncucyteSeries.Plane.RED.ordinal()],
                sharp[IncucyteSeries.Plane.PHASE.ordinal()],
                sharp[IncucyteSeries.Plane.GREEN.ordinal()],
                sharp[IncucyteSeries.Plane.RED.ordinal()],
                d.netPx, d.pathPx, d.driftPx, d.residRms, d.stepRms, d.stepMax, d.wander, d.knocks,
                d.periodFrames, periodH, d.periodPower, r.log2Gain[n - 1],
                pc.netPx, pc.pathPx, largestStep * bin, maxShift,
                agreeStep, agreeNet, label, severity, atBound, refused, secs);
        Files.write(csv, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                traces.resolve(s.set + "__" + s.key + ".csv")))) {
            w.println("t,hours,lr_dx,lr_dy,pc_dx,pc_dy,log2_gain,valid_fraction,status");
            for (int t = 0; t < n; t++) {
                w.printf("%d,%.2f,%.4f,%.4f,%.4f,%.4f,%.5f,%.4f,%s%n",
                        t, s.hours(from + t), dx[t], dy[t], px[t], py[t], r.log2Gain[t],
                        r.validFraction[t], r.status[t] == null ? "" : r.status[t]);
            }
        }
    }

    // ------------------------------------------------------------------------------------ //

    /** What a motion trace is made of, once the systematic part is separated from the rest. */
    private static final class Descriptors {
        double netPx;
        double pathPx;
        /** Displacement accounted for by a straight line through the trace. */
        double driftPx;
        /** RMS excursion about that line. */
        double residRms;
        /** RMS of consecutive differences of the residual. */
        double stepRms;
        double stepMax;
        /** {@link #residRms} over {@link #stepRms}: how far it strays relative to how fast it moves. */
        double wander;
        int knocks;
        double periodFrames;
        double periodPower;
    }

    private static Descriptors describe(double[] dx, double[] dy) {
        int n = dx.length;
        Descriptors d = new Descriptors();
        d.netPx = Math.hypot(dx[n - 1] - dx[0], dy[n - 1] - dy[0]);
        for (int t = 1; t < n; t++) {
            d.pathPx += Math.hypot(dx[t] - dx[t - 1], dy[t] - dy[t - 1]);
        }
        double[] rx = detrend(dx);
        double[] ry = detrend(dy);
        d.driftPx = Math.hypot(slope(dx) * (n - 1), slope(dy) * (n - 1));

        double sumSq = 0;
        for (int t = 0; t < n; t++) sumSq += rx[t] * rx[t] + ry[t] * ry[t];
        d.residRms = Math.sqrt(sumSq / n);

        double[] step = new double[n - 1];
        double stepSq = 0;
        for (int t = 1; t < n; t++) {
            step[t - 1] = Math.hypot(rx[t] - rx[t - 1], ry[t] - ry[t - 1]);
            stepSq += step[t - 1] * step[t - 1];
        }
        d.stepRms = Math.sqrt(stepSq / step.length);
        for (double v : step) d.stepMax = Math.max(d.stepMax, v);
        d.wander = d.stepRms > 1e-9 ? d.residRms / d.stepRms : 0;

        double[] sorted = step.clone();
        java.util.Arrays.sort(sorted);
        double typical = sorted[sorted.length / 2];
        double threshold = Math.max(KNOCK_FLOOR_PX, KNOCK_MULTIPLE * typical);
        for (double v : step) if (v > threshold) d.knocks++;

        double[] spectrum = periodicity(rx, ry);
        d.periodFrames = spectrum[0];
        d.periodPower = spectrum[1];
        return d;
    }

    /** Least-squares slope per frame. */
    private static double slope(double[] v) {
        int n = v.length;
        double mt = (n - 1) / 2.0;
        double mv = 0;
        for (double x : v) mv += x;
        mv /= n;
        double num = 0;
        double den = 0;
        for (int t = 0; t < n; t++) {
            num += (t - mt) * (v[t] - mv);
            den += (t - mt) * (t - mt);
        }
        return den > 0 ? num / den : 0;
    }

    private static double[] detrend(double[] v) {
        int n = v.length;
        double b = slope(v);
        double mt = (n - 1) / 2.0;
        double mv = 0;
        for (double x : v) mv += x;
        mv /= n;
        double[] out = new double[n];
        for (int t = 0; t < n; t++) out[t] = v[t] - (mv + b * (t - mt));
        return out;
    }

    /**
     * Dominant period of the residual, in frames, and the fraction of its power that sits there.
     *
     * <p><b>Only periods completing at least {@link #MIN_CYCLES} cycles inside the window are considered,
     * and the residual is tapered first.</b> Both guards were added after the first survey labelled 12 of
     * 24 recordings periodic: the peak sat in bin 2 or bin 3 every single time, giving "periods" of
     * exactly 16.00 h and 10.67 h, which are those bins and not biology. Zero-padding a 48-sample record
     * to 64 leaks the residual trend straight into the lowest bins, and a window spanning one or two
     * cycles cannot distinguish a cycle from a bend in the drift anyway.
     *
     * <p>The consequence is worth stating plainly rather than working around: a 24-hour window <b>cannot
     * detect a circadian rhythm</b>. That needs a baseline of several days, which is a different survey
     * pass rather than a different threshold.
     */
    private static double[] periodicity(double[] rx, double[] ry) {
        int n = PhaseCorrelation.nextPowerOfTwo(rx.length);
        double[] power = new double[n / 2 + 1];
        for (double[] v : new double[][]{rx, ry}) {
            double[] re = new double[n];
            double[] im = new double[n];
            for (int i = 0; i < v.length; i++) {
                // Hann taper over the real samples: without it the record's ends are a step to the
                // zero padding, and that step's spectrum swamps everything being looked for.
                re[i] = v[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (v.length - 1.0)));
            }
            PhaseCorrelation.fft(re, im, false);
            for (int k = 0; k <= n / 2; k++) power[k] += re[k] * re[k] + im[k] * im[k];
        }
        double total = 0;
        int peak = -1;
        for (int k = MIN_CYCLES; k <= n / 2; k++) {
            total += power[k];
            if (peak < 0 || power[k] > power[peak]) peak = k;
        }
        if (peak < 0 || total <= 0) return new double[]{0, 0};
        return new double[]{(double) n / peak, power[peak] / total};
    }

    private static double medianStepDifference(double[] ax, double[] ay, double[] bx, double[] by) {
        double[] diff = new double[ax.length - 1];
        for (int t = 1; t < ax.length; t++) {
            double sax = ax[t] - ax[t - 1];
            double say = ay[t] - ay[t - 1];
            double sbx = bx[t] - bx[t - 1];
            double sby = by[t] - by[t - 1];
            diff[t - 1] = Math.hypot(sax - sbx, say - sby);
        }
        java.util.Arrays.sort(diff);
        return diff[diff.length / 2];
    }

    /**
     * The motion kind, as a compound label.
     *
     * <p>Compound because real recordings are compound: a plate scanner that drifts thermally also
     * repositions imperfectly every timepoint, and both matter to a user deciding whether this plugin
     * will help them.
     */
    private static String label(double corr, Descriptors d) {
        if (corr < UNREGISTRABLE_CORRELATION) return "UNREGISTRABLE";
        String base;
        if (d.driftPx > DRIFT_DOMINANCE * d.residRms && d.driftPx > MILD_PX / 2) {
            base = d.residRms > 0.5 ? (d.wander > WANDER_WALK ? "DRIFT+WALK" : "DRIFT+JITTER") : "DRIFT";
        } else if (d.wander > WANDER_WALK) {
            base = d.driftPx > MILD_PX / 2 ? "WALK+DRIFT" : "WALK";
        } else {
            base = d.driftPx > MILD_PX / 2 ? "JITTER+DRIFT" : "JITTER";
        }
        if (d.knocks > 0) base = "KNOCK" + d.knocks + "+" + base;
        // Periodicity is only claimed for a trace with no discontinuities in it. A record dominated by a
        // few large steps has a strongly low-frequency spectrum whatever else is going on, so a single
        // bin can hold well over a quarter of the power without anything being periodic — which is what
        // kept labelling the knocked recordings as oscillating even after the leakage was fixed.
        if (d.knocks == 0 && d.periodPower > OSCILLATION_POWER) base += "+OSC";
        return base;
    }

    /**
     * Severity as the larger of the two displacements a user actually faces: how far the field walks
     * away over the recording, and the biggest jump between one frame and the next.
     *
     * <p>An earlier version also carried four times the residual RMS, which put a recording with 1.9 px
     * of gentle jitter in the same band as one with a 17 px knock. An RMS multiplied by an arbitrary
     * factor is not a displacement anybody experiences; drift and the largest single step are.
     */
    private static String severity(Descriptors d) {
        double total = Math.max(d.driftPx, d.stepMax);
        if (total < MILD_PX) return "mild";
        if (total < MODERATE_PX) return "moderate";
        return total < SEVERE_PX ? "severe" : "extreme";
    }

    /**
     * How much of this plane's frame-to-frame correlation is lost by displacing one frame a single pixel.
     *
     * <p>Defined and justified in {@link Localisability}; this survey is where it was measured, and its
     * conclusion is why the measure now lives in the shipped engine rather than only here.
     */
    private static double localisability(float[][] p, int w, int h) {
        double total = 0;
        int pairs = 0;
        for (int t = 1; t < p.length; t++) {
            double d = Localisability.ofPair(p[t - 1], p[t], w, h);
            if (Double.isNaN(d)) continue;
            total += d;
            pairs++;
        }
        return pairs > 0 ? total / pairs : Double.NaN;
    }

    /**
     * Every plane through both estimators, printed rather than recorded.
     *
     * <p>A diagnostic, for deciding which modality a library entry should be estimated from. The whole
     * point of the composite decomposition is that all three planes carry the <i>same</i> motion, so
     * three traces that disagree are telling you which planes are unusable rather than anything about
     * the recording.
     */
    private static void perPlane(IncucyteSeries s, IncucyteSeries.Window win, int bin) {
        int n = win.plane(IncucyteSeries.Plane.PHASE).length;
        System.out.printf("%n  %s: %d frames, %dx%d binned %dx%n",
                s.key, n, win.width, win.height, bin);
        System.out.printf("  %-6s %8s %8s %8s %8s %8s %8s %8s%n",
                "plane", "corr", "sharp", "lr_net", "lr_path", "pc_net", "pc_path", "agree");
        for (IncucyteSeries.Plane p : IncucyteSeries.Plane.values()) {
            float[][] plane = win.plane(p);
            double[] px = new double[n];
            double[] py = new double[n];
            double largestStep = 0;
            for (int t = 1; t < n; t++) {
                double[] step = PhaseCorrelation.shift(plane[t - 1], plane[t], win.width, win.height);
                px[t] = px[t - 1] + step[0] * bin;
                py[t] = py[t - 1] + step[1] * bin;
                largestStep = Math.max(largestStep, Math.hypot(step[0], step[1]));
            }
            Registration.Options o = new Registration.Options();
            o.reference = Reconciler.Reference.CONSECUTIVE;
            o.aligner.norm = RobustNorm.TUKEY;
            o.aligner.support = PairAligner.PixelSupport.GRADIENT;
            o.aligner.maxShift = Math.max(MIN_SHIFT_BINNED, Math.min(MAX_SHIFT_BINNED,
                    SHIFT_HEADROOM * largestStep + MIN_SHIFT_BINNED));
            Registration.Result r = Registration.run(win.source(p), o, null, null);
            double[] dx = new double[n];
            double[] dy = new double[n];
            for (int t = 0; t < n; t++) {
                dx[t] = r.cumulative[t].dx * bin;
                dy[t] = r.cumulative[t].dy * bin;
            }
            Descriptors a = describe(dx, dy);
            Descriptors b = describe(px, py);
            System.out.printf("  %-6s %8.4f %8.4f %8.2f %8.1f %8.2f %8.1f %8.2f%n",
                    p, frameCorrelation(plane), localisability(plane, win.width, win.height),
                    a.netPx, a.pathPx, b.netPx, b.pathPx,
                    medianStepDifference(dx, dy, px, py));
        }
    }

    private static double frameCorrelation(float[][] p) {
        double total = 0;
        int pairs = 0;
        for (int t = 1; t < p.length; t++) {
            double r = correlation(p[t - 1], p[t]);
            if (!Double.isNaN(r)) {
                total += r;
                pairs++;
            }
        }
        return pairs > 0 ? total / pairs : Double.NaN;
    }

    private static double correlation(float[] a, float[] b) {
        return Localisability.correlation(a, b);
    }
}
