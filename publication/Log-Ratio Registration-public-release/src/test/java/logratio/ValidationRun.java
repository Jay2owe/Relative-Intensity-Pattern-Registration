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
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.LUT;
import logratio.core.PairAligner;
import logratio.core.Reconciler;
import logratio.core.Registration;
import logratio.core.RobustNorm;
import logratio.core.Transform;
import logratio.core.Warper;

import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Registers every dataset under {@code validation/} and writes before/after artefacts plus a run record.
 *
 * <p>Layout, one directory per dataset:
 *
 * <pre>
 *   validation/
 *     RESULTS.md                  one appended row per dataset per run
 *     &lt;dataset&gt;/
 *       original.tif              the untouched input, never modified
 *       input.tif                 optional: an ImageJ-native copy, when original.tif is an OME-TIFF
 *       dataset.properties        optional: maxShift, channel
 *       registered.tif            only with -Dlogratio.writeRegistered=true; as big as the input
 *       before_after.tif          raw | registered, with a static crosshair
 *       logratio_before_after.tif the log-ratio map, raw | registered
 *       sd_before_after.tif       per-pixel temporal SD, raw | registered
 *       kymograph_before_after.tif line profiles with time across the panel: wavy becomes straight
 *       shifts.csv
 * </pre>
 *
 * <p>Re-run after any engine change; a row is appended per dataset, so a regression shows up as a
 * number moving rather than as something nobody noticed by scrubbing.
 *
 * <p><b>On the arbiter, and why it needs a control.</b> Mean per-pixel temporal standard deviation is
 * computed from the pixels alone, so it is independent of this plugin's own criterion — but bilinear
 * interpolation is a low-pass filter and lowers it for free, whether or not anything was aligned.
 * Measured on the first dataset, an uncontrolled comparison credited registration with -18.7% when
 * -15.0% of that was the interpolator. So each run also registers a <b>control</b>: the raw stack
 * shifted by the fractional part only of each transform, which applies interpolation of the same
 * character and removes none of the systematic drift. Only {@code SD vs ctrl} is attributable to
 * holding the field still, and it is an approximation, because a sub-pixel shift is not perfectly
 * alignment-neutral.
 *
 * <p><b>And the arbiter has a limit that no better arbiter removes.</b> Temporal SD cannot separate
 * misregistration from genuine biological change; it only gets away with it while frames are close
 * enough together that the sample has not moved. See {@link #reportArbiterLimit}, which says so at the
 * point where a run meets it.
 *
 * <p>The primary evidence for the method is not here. It is the injected-known-drift experiments in
 * {@code library/benchmark} and {@code 00_CASE.md}, which need no arbiter at all because the truth is
 * known exactly. This folder is a visual check and a regression tracker.
 *
 * <pre>
 *   java -cp &lt;classes&gt;;ij.jar logratio.ValidationRun &lt;validation dir&gt; [note] [dataset ...]
 * </pre>
 */
public final class ValidationRun {

    /** Display range for the log-ratio panels, log2 units. Fixed, so panels and runs compare. */
    private static final double CLIP = 1.0;
    /**
     * Side-by-side panels are downsampled so their combined width stays under this.
     *
     * <p>Was 1600, which never triggered on a 512 px crop — a 1024-wide panel of 48 frames is 25 MB, and
     * two such panels per entry took a twelve-entry library to 1.67 GB against an estimate of 700 MB. The
     * scrub panels are a check the eye does at a glance and they are regenerable from `original.tif` and
     * `shifts.csv`; the kymograph, which is the artefact that belongs in a figure, stays full resolution
     * and costs half a megabyte.
     */
    private static final int PANEL_MAX_WIDTH = 700;
    /** Each kymograph panel is stretched along time to at least this width, so a wobble is visible. */
    private static final int KYMOGRAPH_MIN_WIDTH = 340;
    /** Pixels of the profile kept either side of the sharpest edge. */
    private static final int KYMOGRAPH_SPAN = 56;
    /** Vertical magnification of that span. A few pixels of drift has to be several pixels on screen. */
    private static final int KYMOGRAPH_ZOOM = 6;
    /** Separator between the row-profile and column-profile halves of the kymograph. */
    private static final int KYMOGRAPH_GAP = 3;
    /** Blur applied before looking for the profile window, so single-pixel noise does not decide it. */
    private static final int EDGE_BLUR_RADIUS = 2;
    /** Strides for the profile-window search. Neighbouring candidates are near-identical. */
    private static final int LINE_STRIDE = 4;
    private static final int WINDOW_STRIDE = 8;

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args[0]);
        String note = args.length > 1 ? args[1] : "";
        List<String> only = new ArrayList<>();
        for (int i = 2; i < args.length; i++) only.add(args[i]);

        List<Path> datasets = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).sorted().forEach(d -> {
                if (!only.isEmpty() && !only.contains(d.getFileName().toString())) return;
                if (Files.exists(d.resolve("original.tif")) || Files.exists(d.resolve("input.tif"))) {
                    datasets.add(d);
                }
            });
        }
        if (datasets.isEmpty()) throw new IOException("no datasets found under " + root);

        for (Path dataset : datasets) {
            System.out.println("================ " + dataset.getFileName() + " ================");
            try {
                run(root, dataset, note);
            } catch (RuntimeException | IOException e) {
                System.out.println("  FAILED: " + e);
                e.printStackTrace(System.out);
            }
            System.out.println();
        }
    }

    private static void run(Path root, Path dir, String note) throws IOException {
        String name = dir.getFileName().toString();
        // original.tif may be an OME-TIFF, which core ImageJ reads as a single page without
        // Bio-Formats. input.tif, when present, is an ImageJ-native copy of it; the original is never
        // modified. The plugin itself never faces this, because it receives an already-open ImagePlus.
        Path input = Files.exists(dir.resolve("input.tif"))
                ? dir.resolve("input.tif") : dir.resolve("original.tif");
        ImagePlus raw = IJ.openImage(input.toString());
        if (raw == null) throw new IOException("could not open " + input);
        if (raw.getStackSize() < 2) {
            throw new IOException(input.getFileName() + " opened as a single slice — if it is an "
                    + "OME-TIFF, write an ImageJ-native copy beside it as input.tif");
        }

        Properties props = new Properties();
        Path cfg = dir.resolve("dataset.properties");
        if (Files.exists(cfg)) {
            try (java.io.InputStream in = Files.newInputStream(cfg)) {
                props.load(in);
            }
        }

        int channels = Math.max(1, raw.getNChannels());
        int w = raw.getWidth();
        int h = raw.getHeight();
        System.out.printf("  %s: %dx%d, %d channels, %d timepoints, %d-bit%n",
                input.getFileName(), w, h, channels, Math.max(1, raw.getNFrames()), raw.getBitDepth());

        // Rank channels by the quantity that predicts registrability. On a photon-limited recording the
        // noisiest channel has the most per-pixel contrast, so choosing by gradient picks exactly the
        // wrong one — measured 0.063 correlation on a bioluminescence channel that no method could
        // register, and which phase correlation answered with 1264 px of nonsense.
        double[] corr = new double[channels + 1];
        int best = 1;
        System.out.println("  frame-to-frame correlation (near 0 = unregisterable noise):");
        for (int c = 1; c <= channels; c++) {
            corr[c] = StackFrames.frameCorrelation(raw, c);
            System.out.printf("    channel %d: %.4f%n", c, corr[c]);
            if (corr[c] > corr[best]) best = c;
        }
        int channel = Integer.parseInt(props.getProperty("channel", String.valueOf(best)));
        double maxShift = Double.parseDouble(props.getProperty("maxShift", "30"));
        System.out.printf("  estimating from channel %d, maxShift %.0f px%n", channel, maxShift);

        Registration.Options o = new Registration.Options();
        o.reference = Reconciler.Reference.MULTILAG;
        o.lags = new int[]{1, 2, 4, 8, 16};
        o.aligner.norm = RobustNorm.TUKEY;
        o.aligner.support = PairAligner.PixelSupport.GRADIENT;
        o.aligner.maxShift = maxShift;

        Timing timing = Timing.start();
        Registration.Result r = Registration.run(
                StackFrames.of(raw, channel, StackFrames.PROJECT_Z), o, null, null);
        // CPU, not elapsed, so a laptop that goes to standby mid-run does not get reported as a slow
        // registration. See Timing.
        double secs = timing.stop().cpuNs() / 1e9;
        if (timing.unscheduled()) System.out.printf("  !! %s%n", timing.note());

        Warper.Margin margin = Warper.validMargin(r.cumulative, w, h, Warper.Interpolation.BILINEAR);
        System.out.printf("  net drift %.2f px, path %.1f px, margin %s, %.1f s%n",
                r.cumulative[r.cumulative.length - 1].magnitude(), path(r.cumulative), margin, secs);
        System.out.printf("  log-ratio residual %.5f -> %.5f log2 (this plugin's own criterion)%n",
                r.medianResidualBefore(), r.medianResidualAfter());
        System.out.printf("  log2 gain over the recording: %+.3f (a bleaching trace, free from the fit)%n",
                r.log2Gain[r.log2Gain.length - 1]);

        ImagePlus registered = StackWarper.apply(raw, r.cumulative, Warper.Interpolation.BILINEAR, false);
        // The full-resolution registered stack is as large as the input — 682 MB for one of these — and
        // it is not what anyone looks at; the panels below are. It is also reproducible from
        // original.tif and shifts.csv, so it is written only on request.
        if (Boolean.getBoolean("logratio.writeRegistered")) {
            IJ.saveAsTiff(registered, dir.resolve("registered.tif").toString());
        }

        float[][] before = planes(raw, channel);
        float[][] after = planes(registered, channel);
        double sdBefore = meanSd(before, w, h, margin);
        double sdAfter = meanSd(after, w, h, margin);

        ImagePlus integerReg = StackWarper.apply(raw, r.cumulative, Warper.Interpolation.NONE, false);
        double sdInteger = meanSd(planes(integerReg, channel), w, h, margin);
        integerReg.close();

        Transform[] fractional = new Transform[r.cumulative.length];
        for (int t = 0; t < fractional.length; t++) {
            Transform c = r.cumulative[t];
            fractional[t] = Transform.translation(c.dx - Math.round(c.dx), c.dy - Math.round(c.dy));
        }
        ImagePlus control = StackWarper.apply(raw, fractional, Warper.Interpolation.BILINEAR, false);
        double sdControl = meanSd(planes(control, channel), w, h, margin);
        control.close();

        System.out.println("  mean per-pixel temporal SD, valid margin only:");
        System.out.printf("    raw                                        %9.2f%n", sdBefore);
        System.out.printf("    CONTROL: fractional shift, no alignment    %9.2f  (%+.1f%% blur alone)%n",
                sdControl, 100 * (sdControl / sdBefore - 1));
        System.out.printf("    registered, integer shifts                 %9.2f  (%+.1f%% vs raw)%n",
                sdInteger, 100 * (sdInteger / sdBefore - 1));
        System.out.printf("    registered, bilinear                       %9.2f  (%+.1f%% vs raw, "
                        + "%+.1f%% VS CONTROL <- the attributable figure)%n",
                sdAfter, 100 * (sdAfter / sdBefore - 1), 100 * (sdAfter / sdControl - 1));
        reportArbiterLimit(100 * (sdAfter / sdControl - 1), r);

        int step = Math.max(1, (int) Math.ceil(2.0 * w / PANEL_MAX_WIDTH));
        IJ.saveAsTiff(sideBySide(before, after, w, h, margin, step),
                dir.resolve("before_after.tif").toString());
        IJ.saveAsTiff(logRatioPanels(before, after, w, h, margin, step),
                dir.resolve("logratio_before_after.tif").toString());
        IJ.saveAsTiff(sdPanels(before, after, w, h), dir.resolve("sd_before_after.tif").toString());
        IJ.saveAsTiff(kymographs(before, after, w, h, margin),
                dir.resolve("kymograph_before_after.tif").toString());
        writeShifts(dir.resolve("shifts.csv"), r);
        registered.close();
        raw.close();

        appendRecord(root.resolve("RESULTS.md"), name, note, channel, corr[channel], r, secs,
                sdBefore, sdControl, sdInteger, sdAfter);
        System.out.printf("  panels downsampled %dx; wrote four panels and shifts.csv%s%n", step,
                Boolean.getBoolean("logratio.writeRegistered") ? ", plus registered.tif" : "");
    }

    // ------------------------------------------------------------------------------------ //

    /** {@code SD vs ctrl} below this in magnitude is indistinguishable from no effect. */
    private static final double SD_EFFECT_NOISE_PCT = 1.0;
    /** A residual this much smaller after aligning is a fit that plainly did something. */
    private static final double RESIDUAL_CLEARLY_IMPROVED = 0.75;

    /**
     * Says so, on the spot, when the arbiter has run out before the method has.
     *
     * <p><b>The limit, stated rather than repaired.</b> Mean per-pixel temporal standard deviation
     * measures how still the field is held. It cannot separate misregistration from genuine biological
     * change, and it does not need to while frames are minutes apart, because cells barely move in that
     * time. At a long baseline they do. Measured on {@code library/12_long_baseline_9d} — frames two
     * hours apart over nine days — the attributable figure read -0.2% while the log-ratio residual more
     * than halved (1.027 to 0.474) and two independent estimators agreed to 0.71 px across 878 px of
     * recovered path. The registration was right and the arbiter could not see it.
     *
     * <p>So this is not a defect to fix by a better arbiter. Any pixel-variance criterion has the same
     * blind spot, because at a long baseline the pixels genuinely differ. <b>The comparative claims rest
     * on the exact-truth experiments</b> — {@code library/benchmark} and {@code 00_CASE.md} — where the
     * displacement is injected and known, and this folder stays what it is: a visual check and a
     * regression tracker.
     */
    private static void reportArbiterLimit(double sdVsControlPct, Registration.Result r) {
        boolean noEffectSeen = Math.abs(sdVsControlPct) < SD_EFFECT_NOISE_PCT;
        double before = r.medianResidualBefore();
        double after = r.medianResidualAfter();
        boolean fitWorked = before > 0 && after < RESIDUAL_CLEARLY_IMPROVED * before;
        if (!(noEffectSeen && fitWorked)) return;
        System.out.printf("    NOTE: the arbiter has run out before the method has. It reports "
                + "%+.1f%% while the%n"
                + "          residual fell %.3f -> %.3f. Temporal SD cannot separate real change from%n"
                + "          misregistration once frames are far enough apart that the sample moves%n"
                + "          between them. Read the exact-truth experiments, not this number.%n",
                sdVsControlPct, before, after);
    }

    private static double path(Transform[] cum) {
        double s = 0;
        for (int t = 1; t < cum.length; t++) {
            s += Math.hypot(cum[t].dx - cum[t - 1].dx, cum[t].dy - cum[t - 1].dy);
        }
        return s;
    }

    private static float[][] planes(ImagePlus imp, int channel) {
        StackFrames f = StackFrames.of(imp, channel, StackFrames.PROJECT_Z);
        float[][] out = new float[f.count()][];
        for (int t = 0; t < out.length; t++) out[t] = f.plane(t);
        return out;
    }

    /**
     * Mean over pixels of the temporal standard deviation, inside the valid margin only, after
     * equalising each frame's brightness.
     *
     * <p><b>The brightness equalisation is not a nicety.</b> On a recording that fades by a factor of two
     * across its length, every pixel's intensity halves, and that swamps everything a few pixels of drift
     * contribute: measured on `VID95_A1`, registration accounted for -0.1% of the raw temporal SD purely
     * because the bleaching accounted for almost all of it. Scaling each frame to a common median removes
     * the fade from all three arms of the comparison equally and leaves drift and genuine change, which
     * is what the arbiter is meant to be sensitive to.
     *
     * <p>Each frame is scaled by <b>its own median</b>, not by the gain this plugin fitted. Using the
     * fitted gain would make the arbiter depend on the thing being tested; a median is computed from the
     * pixels and knows nothing about the estimator.
     */
    private static double meanSd(float[][] p, int w, int h, Warper.Margin m) {
        double[] scale = new double[p.length];
        double reference = frameMedian(p[0], w, h, m);
        for (int t = 0; t < p.length; t++) {
            double med = frameMedian(p[t], w, h, m);
            scale[t] = med > 0 ? reference / med : 1.0;
        }
        double total = 0;
        long counted = 0;
        for (int y = m.top; y < h - m.bottom; y++) {
            for (int x = m.left; x < w - m.right; x++) {
                int i = y * w + x;
                double mean = 0;
                for (int t = 0; t < p.length; t++) mean += p[t][i] * scale[t];
                mean /= p.length;
                double v = 0;
                for (int t = 0; t < p.length; t++) {
                    double d = p[t][i] * scale[t] - mean;
                    v += d * d;
                }
                total += Math.sqrt(v / p.length);
                counted++;
            }
        }
        return counted > 0 ? total / counted : Double.NaN;
    }

    /** Median of one frame inside the margin, from a subsample — a median needs no more than that. */
    private static double frameMedian(float[] frame, int w, int h, Warper.Margin m) {
        int stride = Math.max(1, (int) Math.round(Math.sqrt((double) w * h / 40_000)));
        java.util.List<Float> v = new java.util.ArrayList<>();
        for (int y = m.top; y < h - m.bottom; y += stride) {
            for (int x = m.left; x < w - m.right; x += stride) {
                v.add(frame[y * w + x]);
            }
        }
        if (v.isEmpty()) return 0;
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    /**
     * Raw on the left, registered on the right, with a static crosshair burned into both.
     *
     * <p>The crosshair is not decoration. Several pixels of drift over a hundred frames is invisible
     * without a fixed reference, because the whole field moves together so nothing looks like it is
     * moving. Against the crosshair, the left panel visibly slides and the right does not.
     *
     * <p>8-bit and downsampled: a full-resolution RGB panel of a 1536-wide recording is 1.4 GB, which is
     * not a thing to put in a synced folder for a check the eye does at a glance.
     */
    private static ImagePlus sideBySide(float[][] a, float[][] b, int w, int h,
                                        Warper.Margin m, int step) {
        double lo = percentile(a[0], 1.0);
        double hi = percentile(a[0], 99.5);
        int pw = w / step;
        int ph = h / step;
        ImageStack out = new ImageStack(2 * pw, ph);
        for (int t = 0; t < a.length; t++) {
            ByteProcessor bp = new ByteProcessor(2 * pw, ph);
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    int i = (y * step) * w + x * step;
                    bp.set(x, y, grey(a[t][i], lo, hi));
                    bp.set(x + pw, y, grey(b[t][i], lo, hi));
                }
            }
            for (int panel = 0; panel < 2; panel++) {
                drawCross(bp, panel * pw + pw / 2, ph / 2, 255);
            }
            drawRect(bp, pw + m.left / step, m.top / step,
                    pw - (m.left + m.right) / step, ph - (m.top + m.bottom) / step, 200);
            out.addSlice("t=" + (t + 1), bp);
        }
        return new ImagePlus("raw | registered", out);
    }

    /**
     * The log-ratio map before and after, side by side, on a blue-black-red lookup table.
     *
     * <p>This is the artefact the whole plugin came from: on an unregistered stack every object carries a
     * bright rim on one side and a dark rim on the other whether or not it moved, and registering makes
     * that structure go away. The display range is fixed for both panels and across runs, so what is
     * being compared is the data and not two auto-scalings.
     */
    private static ImagePlus logRatioPanels(float[][] a, float[][] b, int w, int h,
                                            Warper.Margin m, int step) {
        int pw = w / step;
        int ph = h / step;
        ImageStack out = new ImageStack(2 * pw, ph);
        for (int t = 0; t + 1 < a.length; t++) {
            ByteProcessor bp = new ByteProcessor(2 * pw, ph);
            paintRatio(bp, a[t], a[t + 1], 0, w, pw, ph, step);
            paintRatio(bp, b[t], b[t + 1], pw, w, pw, ph, step);
            drawRect(bp, pw + m.left / step, m.top / step,
                    pw - (m.left + m.right) / step, ph - (m.top + m.bottom) / step, 255);
            out.addSlice("t=" + (t + 1) + "->" + (t + 2), bp);
        }
        ImagePlus imp = new ImagePlus("log-ratio: raw | registered  (+-" + CLIP + " log2)", out);
        imp.getProcessor().setColorModel(divergingLut());
        imp.setLut(divergingLut());
        return imp;
    }

    private static void paintRatio(ByteProcessor bp, float[] p0, float[] p1,
                                   int xOffset, int w, int pw, int ph, int step) {
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int i = (y * step) * w + x * step;
                double l = Math.log((p1[i] + 1.0) / (p0[i] + 1.0)) / Math.log(2.0);
                double c = Math.max(-1, Math.min(1, l / CLIP));
                bp.set(x + xOffset, y, (int) Math.round(127.5 + 127.5 * c));
            }
        }
    }

    /** Blue for dimmed, black for unchanged, red for brightened. */
    private static LUT divergingLut() {
        byte[] rr = new byte[256];
        byte[] gg = new byte[256];
        byte[] bb = new byte[256];
        for (int i = 0; i < 256; i++) {
            double c = (i - 127.5) / 127.5;
            rr[i] = (byte) Math.round(255 * Math.max(0, c));
            bb[i] = (byte) Math.round(255 * Math.max(0, -c));
            gg[i] = (byte) Math.round(30 * Math.abs(c));
        }
        return new LUT(new IndexColorModel(8, 256, rr, gg, bb), 0, 255);
    }

    /**
     * Line profiles through the strongest edge in the frame, with time running across the panel.
     *
     * <p><b>The verification that works as a still.</b> Everything else here is a movie: to see that
     * drift is gone you scrub, and a reader of a paper cannot scrub. A kymograph puts position on one
     * axis and time on the other, so a stationary edge draws a straight line and a drifting one draws a
     * wavy line. Before and after sit side by side and the difference is a single glance, in a figure.
     *
     * <p>Two panels, because one axis of motion is invisible in the wrong profile: a row profile shows
     * horizontal movement, a column profile shows vertical. The row and column are chosen automatically
     * as the ones with the most gradient energy along the relevant axis, which puts the line through the
     * sharpest real edge rather than through wherever the middle of the frame happens to be.
     *
     * <p><b>Both axes are magnified, and that is what makes it work at all.</b> The first version showed
     * the whole profile at native scale: 4.6 px of drift inside a 512 px line, which is invisible and
     * looked like a clean result when nothing had been demonstrated. So the profile is cropped to a short
     * span centred on the sharpest edge and stretched vertically, and time is stretched horizontally
     * because a hundred frames is otherwise a hundred pixels wide.
     */
    private static ImagePlus kymographs(float[][] a, float[][] b, int w, int h, Warper.Margin m) {
        int n = a.length;
        int scale = Math.max(1, (int) Math.ceil((double) KYMOGRAPH_MIN_WIDTH / n));
        int span = Math.min(KYMOGRAPH_SPAN, Math.min(w - m.left - m.right, h - m.top - m.bottom));
        // Mild smoothing only: enough to stop single-pixel noise deciding where the profile goes, not
        // enough to erase the tissue texture that is the most informative thing to watch move.
        float[] reference = blur(temporalMean(a, w * h), w, h, EDGE_BLUR_RADIUS);
        int[] rowEdge = bestProfileWindow(reference, w, h, m, span, true);
        int[] colEdge = bestProfileWindow(reference, w, h, m, span, false);
        int panelW = n * scale;
        int panelH = span * KYMOGRAPH_ZOOM;
        int rowStart = clampStart(rowEdge[1], m.left, w - m.right - span);
        int colStart = clampStart(colEdge[1], m.top, h - m.bottom - span);

        float[][] rowRaw = profile(a, w, span, rowStart, rowEdge[0], true);
        float[][] rowReg = profile(b, w, span, rowStart, rowEdge[0], true);
        float[][] colRaw = profile(a, w, span, colStart, colEdge[0], false);
        float[][] colReg = profile(b, w, span, colStart, colEdge[0], false);

        // Level each frame's profile to a common mean before scaling.
        //
        // Without this the display range is set by the frame-to-frame BRIGHTNESS swing rather than by the
        // spatial structure, and the structure is what carries the motion. Measured on the trial entry,
        // the profile's spatial standard deviation was 7 grey levels inside a display range of 68 — the
        // texture was compressed into a tenth of the panel and the drift was invisible. Levelling is
        // safe here for the same reason it is safe in the temporal-SD arbiter: it changes each frame's
        // offset and not the position of anything in it.
        double target = profileMean(rowRaw, colRaw);
        level(rowRaw, target);
        level(rowReg, target);
        level(colRaw, target);
        level(colReg, target);

        float[] shown = new float[2 * n * span];
        int k = 0;
        for (int t = 0; t < n; t++) {
            for (int i = 0; i < span; i++) {
                shown[k++] = rowRaw[t][i];
                shown[k++] = colRaw[t][i];
            }
        }
        double lo = percentile(shown, 2.0);
        double hi = percentile(shown, 98.0);

        ByteProcessor bp = new ByteProcessor(2 * panelW, 2 * panelH + KYMOGRAPH_GAP);
        // Top half: a profile ALONG a row, so horizontal movement shows as the pattern sliding.
        paintKymograph(bp, rowRaw, rowReg, 0, panelW, span, scale, n, lo, hi);
        // Bottom half: a profile down a column, which is the only way vertical movement is visible.
        paintKymograph(bp, colRaw, colReg, panelH + KYMOGRAPH_GAP, panelW, span, scale, n, lo, hi);

        // Reported because it decides whether the panel is worth looking at: a profile with little
        // spatial variation cannot show motion however much it is magnified, and the choice of
        // cross-section belongs in a figure caption rather than hidden inside a heuristic.
        System.out.printf("  kymograph: row %d from x=%d, column %d from y=%d; "
                        + "spatial SD along the profile %.2f (row) %.2f (column), range %.0f..%.0f%n",
                rowEdge[0], rowStart, colEdge[0], colStart,
                spatialSd(rowRaw), spatialSd(colRaw), lo, hi);

        for (int x = 0; x < 2 * panelW; x++) {
            for (int g = 0; g < KYMOGRAPH_GAP; g++) set(bp, x, panelH + g, 255);
        }
        for (int y = 0; y < bp.getHeight(); y++) set(bp, panelW, y, 255);
        return new ImagePlus("kymograph, raw | registered, time ->: row " + rowEdge[0]
                + " above column " + colEdge[0] + " (x" + KYMOGRAPH_ZOOM + ")", bp);
    }

    private static int clampStart(int start, int low, int high) {
        return Math.max(low, Math.min(high, start));
    }

    /** Separable box blur, run twice so the kernel is roughly Gaussian. */
    private static float[] blur(float[] src, int w, int h, int radius) {
        float[] a = src;
        for (int pass = 0; pass < 2; pass++) {
            float[] b = new float[a.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double s = 0;
                    int count = 0;
                    for (int i = -radius; i <= radius; i++) {
                        int xx = x + i;
                        if (xx < 0 || xx >= w) continue;
                        s += a[y * w + xx];
                        count++;
                    }
                    b[y * w + x] = (float) (s / count);
                }
            }
            float[] c = new float[a.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double s = 0;
                    int count = 0;
                    for (int i = -radius; i <= radius; i++) {
                        int yy = y + i;
                        if (yy < 0 || yy >= h) continue;
                        s += b[yy * w + x];
                        count++;
                    }
                    c[y * w + x] = (float) (s / count);
                }
            }
            a = c;
        }
        return a;
    }

    private static float[] temporalMean(float[][] p, int size) {
        float[] out = new float[size];
        for (float[] frame : p) {
            for (int i = 0; i < size; i++) out[i] += frame[i];
        }
        for (int i = 0; i < size; i++) out[i] /= p.length;
        return out;
    }

    private static void paintKymograph(ByteProcessor bp, float[][] raw, float[][] registered,
                                       int yOffset, int panelW, int span, int scale, int n,
                                       double lo, double hi) {
        for (int t = 0; t < n; t++) {
            for (int i = 0; i < span; i++) {
                int va = grey(raw[t][i], lo, hi);
                int vb = grey(registered[t][i], lo, hi);
                for (int zy = 0; zy < KYMOGRAPH_ZOOM; zy++) {
                    int y = yOffset + i * KYMOGRAPH_ZOOM + zy;
                    for (int rep = 0; rep < scale; rep++) {
                        int x = t * scale + rep;
                        set(bp, x, y, va);
                        set(bp, x + panelW, y, vb);
                    }
                }
            }
        }
    }

    /** One profile per frame: {@code [frame][position]}. */
    private static float[][] profile(float[][] p, int w, int span, int start, int line,
                                     boolean alongRow) {
        float[][] out = new float[p.length][span];
        for (int t = 0; t < p.length; t++) {
            for (int i = 0; i < span; i++) {
                out[t][i] = alongRow ? p[t][line * w + start + i] : p[t][(start + i) * w + line];
            }
        }
        return out;
    }

    private static double profileMean(float[][] first, float[][] second) {
        double sum = 0;
        long count = 0;
        for (float[][] set : new float[][][]{first, second}) {
            for (float[] frame : set) {
                for (float v : frame) {
                    sum += v;
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : 0;
    }

    /** Shift each frame's profile so they all share one mean. Positions are untouched. */
    private static void level(float[][] profiles, double target) {
        for (float[] frame : profiles) {
            double sum = 0;
            for (float v : frame) sum += v;
            float shift = (float) (target - sum / frame.length);
            for (int i = 0; i < frame.length; i++) frame[i] += shift;
        }
    }

    /** Mean over frames of the spatial standard deviation within one profile. */
    private static double spatialSd(float[][] profiles) {
        double total = 0;
        for (float[] frame : profiles) {
            double sum = 0;
            double sumSq = 0;
            for (float v : frame) {
                sum += v;
                sumSq += v * v;
            }
            double mean = sum / frame.length;
            total += Math.sqrt(Math.max(0, sumSq / frame.length - mean * mean));
        }
        return total / profiles.length;
    }

    /**
     * The window of {@code span} pixels along one axis with the most spatial contrast in it.
     *
     * <p>Returns {@code {line, start}}. <b>The criterion is the standard deviation of the profile inside
     * the window itself</b>, which is a direct answer to the only question that matters here: magnified
     * six times, will this strip look like anything? Three earlier criteria all failed on real data, and
     * each failed for its own reason worth recording. Gradient energy in one frame maximises on shot
     * noise. Gradient energy on the temporal mean still maximises on the static out-of-focus texture
     * outside the culture insert, which averaging cannot remove and which is too fine to see magnified.
     * Gradient energy on a heavily blurred mean picks the broad illumination gradient and lands the
     * profile in the uniform insert interior, where the strip is flat.
     *
     * <p>Searching windows rather than lines is what makes it work: contrast is only meaningful over the
     * span actually displayed. Coarse strides, because neighbouring windows are near-identical.
     */
    private static int[] bestProfileWindow(float[] p, int w, int h, Warper.Margin m, int span,
                                           boolean alongRow) {
        int outerFrom = alongRow ? m.top : m.left;
        int outerTo = alongRow ? h - m.bottom : w - m.right;
        int innerFrom = alongRow ? m.left : m.top;
        int innerTo = (alongRow ? w - m.right : h - m.bottom) - span;
        int bestLine = (outerFrom + outerTo) / 2;
        int bestStart = Math.max(innerFrom, (innerFrom + innerTo) / 2);
        double bestSd = -1;
        for (int outer = outerFrom; outer < outerTo; outer += LINE_STRIDE) {
            for (int start = innerFrom; start <= innerTo; start += WINDOW_STRIDE) {
                double sum = 0;
                double sumSq = 0;
                for (int i = 0; i < span; i++) {
                    double v = alongRow ? p[outer * w + start + i] : p[(start + i) * w + outer];
                    sum += v;
                    sumSq += v * v;
                }
                double sd = sumSq / span - (sum / span) * (sum / span);
                if (sd > bestSd) {
                    bestSd = sd;
                    bestLine = outer;
                    bestStart = start;
                }
            }
        }
        return new int[]{bestLine, bestStart};
    }

    /** Per-pixel temporal SD before and after, one shared display range. One slice, full resolution. */
    private static ImagePlus sdPanels(float[][] a, float[][] b, int w, int h) {
        float[] sa = sdImage(a, w, h);
        float[] sb = sdImage(b, w, h);
        FloatProcessor fp = new FloatProcessor(2 * w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                fp.setf(x, y, sa[y * w + x]);
                fp.setf(x + w, y, sb[y * w + x]);
            }
        }
        double hi = percentile(sa, 99.0);
        fp.setMinAndMax(0, hi);
        ImagePlus imp = new ImagePlus("temporal SD: raw | registered", fp);
        imp.getProcessor().setMinAndMax(0, hi);
        return imp;
    }

    private static float[] sdImage(float[][] p, int w, int h) {
        float[] out = new float[w * h];
        for (int i = 0; i < out.length; i++) {
            double mean = 0;
            for (float[] frame : p) mean += frame[i];
            mean /= p.length;
            double v = 0;
            for (float[] frame : p) {
                double d = frame[i] - mean;
                v += d * d;
            }
            out[i] = (float) Math.sqrt(v / p.length);
        }
        return out;
    }

    private static int grey(double v, double lo, double hi) {
        double f = (v - lo) / Math.max(1e-9, hi - lo);
        f = Math.pow(Math.max(0, Math.min(1, f)), 0.7);       // mild gamma, for visibility
        return (int) Math.round(255 * f);
    }

    private static double percentile(float[] a, double q) {
        float[] copy = a.clone();
        java.util.Arrays.sort(copy);
        int k = (int) Math.round(q / 100.0 * (copy.length - 1));
        return copy[Math.max(0, Math.min(copy.length - 1, k))];
    }

    private static void drawCross(ByteProcessor bp, int cx, int cy, int value) {
        int arm = Math.max(12, bp.getHeight() / 12);
        for (int d = -arm; d <= arm; d++) {
            if (Math.abs(d) > arm / 5) {
                if (cx + d >= 0 && cx + d < bp.getWidth()) bp.set(cx + d, cy, value);
                if (cy + d >= 0 && cy + d < bp.getHeight()) bp.set(cx, cy + d, value);
            }
        }
    }

    private static void drawRect(ByteProcessor bp, int x, int y, int w, int h, int value) {
        for (int i = 0; i < w; i++) {
            set(bp, x + i, y, value);
            set(bp, x + i, y + h - 1, value);
        }
        for (int i = 0; i < h; i++) {
            set(bp, x, y + i, value);
            set(bp, x + w - 1, y + i, value);
        }
    }

    private static void set(ByteProcessor bp, int x, int y, int value) {
        if (x >= 0 && y >= 0 && x < bp.getWidth() && y < bp.getHeight()) bp.set(x, y, value);
    }

    private static void writeShifts(Path file, Registration.Result r) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("t,cum_dx,cum_dy,log2_gain,residual_before,residual_after,valid_fraction,status");
            for (int t = 0; t < r.cumulative.length; t++) {
                w.printf("%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.4f,%s%n",
                        t + 1, r.cumulative[t].dx, r.cumulative[t].dy, r.log2Gain[t],
                        r.residualBefore[t], r.residualAfter[t], r.validFraction[t],
                        r.status[t] == null ? "" : r.status[t]);
            }
        }
    }

    private static void appendRecord(Path file, String dataset, String note, int channel, double corr,
                                     Registration.Result r, double secs, double sdBefore,
                                     double sdControl, double sdInteger, double sdBilinear)
            throws IOException {
        boolean fresh = !Files.exists(file);
        StringBuilder s = new StringBuilder();
        if (fresh) {
            s.append("# Validation log\n\n")
             .append("One row per dataset per run. Each dataset directory holds `original.tif`, the\n")
             .append("untouched input, plus the artefacts produced from it. Re-run\n")
             .append("`logratio.ValidationRun <validation dir> \"<note>\"` after any engine change.\n\n")
             .append("**Read `SD vs ctrl` and nothing else as the effect of registration.** Temporal\n")
             .append("standard deviation is computed from the pixels alone, so it is independent of this\n")
             .append("plugin's criterion, but bilinear interpolation is a low-pass filter and lowers it for\n")
             .append("free. `SD ctrl` is the raw stack shifted by the FRACTIONAL PART ONLY of each frame's\n")
             .append("transform: under a pixel, so no systematic drift is removed, but every frame goes\n")
             .append("through the same interpolation. An identity warp would be no control at all, since a\n")
             .append("whole-pixel shift of zero takes the block-copy path and interpolates nothing.\n")
             .append("The control is an approximation — a sub-pixel shift is not perfectly\n")
             .append("alignment-neutral. `SD int` uses whole-pixel shifts and needs no control, but on\n")
             .append("sub-pixel drift it corrects almost nothing.\n\n")
             .append("`gain` is the fitted log2 intensity change across the recording — a bleaching trace\n")
             .append("that falls out of the same fit at no extra cost.\n\n")
             .append("**The arbiter runs out before the method does, and this is a limit rather than a\n")
             .append("defect.** Temporal SD cannot separate misregistration from genuine biological\n")
             .append("change. That costs nothing while frames are minutes apart, because cells barely\n")
             .append("move in that time; at a long baseline they do. On `library/12_long_baseline_9d`,\n")
             .append("frames two hours apart over nine days, `SD vs ctrl` read -0.2% while the\n")
             .append("log-ratio residual more than halved (1.027 to 0.474) and two independent\n")
             .append("estimators agreed to 0.71 px across 878 px of recovered path. No pixel-variance\n")
             .append("criterion can do better, because at that baseline the pixels genuinely differ.\n")
             .append("A run that meets this prints a NOTE saying so.\n\n")
             .append("The primary evidence for the method is NOT here. It is the injected-known-drift\n")
             .append("experiments in `library/benchmark` and `00_CASE.md`, which need no arbiter because\n")
             .append("the truth is known exactly. This folder is a visual check and a regression\n")
             .append("tracker.\n\n")
             .append("| date | dataset | note | ch | corr | net px | path px | gain | resid before "
                     + "| resid after | SD raw | SD ctrl | SD int | SD bilin | **SD vs ctrl** "
                     + "| repaired | s |\n")
             .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        }
        int repaired = 0;
        for (Object x : r.repairs) if (x != null) repaired++;
        s.append(String.format("| %s | %s | %s | %d | %.4f | %.2f | %.1f | %+.3f | %.5f | %.5f "
                        + "| %.1f | %.1f | %.1f | %.1f | **%+.1f%%** | %d | %.0f |%n",
                LocalDate.now(), dataset, note.isEmpty() ? "-" : note, channel, corr,
                r.cumulative[r.cumulative.length - 1].magnitude(), path(r.cumulative),
                r.log2Gain[r.log2Gain.length - 1],
                r.medianResidualBefore(), r.medianResidualAfter(),
                sdBefore, sdControl, sdInteger, sdBilinear,
                100 * (sdBilinear / sdControl - 1), repaired, secs));
        Files.write(file, s.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
