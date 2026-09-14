/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ripr.core.LogPlane;
import ripr.core.PairAligner;
import ripr.core.Reconciler;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The stacks the benchmark aligns, before and after, written out so they can be looked at.
 * <b>Test scope only.</b>
 *
 * <p><b>Why this exists.</b> {@link Benchmark} reduces every run to a median error in pixels and throws
 * the pixels away. That is the right output for a comparison — but it means the difference between 24.4
 * px and 0.31 px, which is the largest result in the project, has never been visible to anybody. A
 * number nobody can see is a number nobody can sanity-check, and a fixture bug that produces plausible
 * numbers is exactly the failure this project has already been caught by three times (see "What this
 * replaced" in {@code library/benchmark/README.md}).
 *
 * <p>Same fixture, same solver, same reconciliation as {@link Benchmark} — this only keeps the frames.
 * For each requested arm it writes:
 *
 * <ul>
 *   <li>{@code <arm>_input.tif} — the recording as the estimator received it: drifting, with the
 *       condition's nuisance applied. This is what the problem looks like.
 *   <li>{@code <arm>_corrected.tif} — that same stack corrected by the transforms the estimator
 *       recovered. This is what a user would get.
 *   <li>{@code <arm>_sd.tif} — per-pixel temporal standard deviation, uncorrected on the left and
 *       corrected on the right, one shared display range. <b>This is the panel to look at</b>, and see
 *       the warning below about what is in it.
 * </ul>
 *
 * <p><b>The standard-deviation panel is built from the clean stack, not from the condition's stack, and
 * that is not a cheat — it is the only way the panel measures anything.</b> Temporal standard deviation
 * shows how much each pixel varied over the recording, so on a well-aligned stack it is near zero
 * everywhere except where the sample genuinely changed, and on a misaligned one every edge in the frame
 * draws a bright outline as it sweeps back and forth. That works only while alignment is the dominant
 * source of variation. Under {@code GAIN_FADE} the frame changes brightness fourfold, and under
 * {@code CHANGE_BLOCKS} a tenth of the field is repainted every frame; both swamp the panel completely.
 * Measured before this was fixed: the arm that misregistered by 23.7 px and the arm that got it right to
 * 0.085 px produced panels differing by 0.9%, and the failing one looked <em>better</em>.
 *
 * <p>So the transforms are recovered from the condition's stack — the hard problem, unchanged — and then
 * applied to the clean stack built from the same seed on the same trajectory. The panel then shows the
 * estimator's answer and nothing else. The two image stacks either side of it are the real thing.
 *
 * <p>Warping uses {@link Warper.Interpolation#NONE} — whole-pixel shifts — deliberately. Bilinear
 * resampling is a low-pass filter and lowers temporal standard deviation whether or not anything was
 * aligned, by -15% on the first dataset this was measured on. A panel meant to show alignment must not
 * be showing interpolation instead.
 *
 * <pre>
 *   java -cp &lt;classes&gt;;&lt;test-classes&gt;;ij.jar ripr.BenchmarkStacks &lt;seed dir&gt; &lt;out dir&gt;
 *        [-Dlogratio.onlySeed=...] [-Dlogratio.onlyCondition=...]
 *        [-Dlogratio.onlyEstimator=...] [-Dlogratio.onlyRecon=...]
 * </pre>
 */
public final class BenchmarkStacks {

    private static final double EPSILON = 1.0;

    private BenchmarkStacks() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: BenchmarkStacks <seed dir> <out dir>");
            return;
        }
        Path seeds = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);

        List<Path> frames = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(seeds)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".tif")).sorted()
                    .forEach(frames::add);
        }
        if (frames.isEmpty()) throw new IOException("no seed frames in " + seeds);

        String onlySeed = System.getProperty("ripr.onlySeed", "");
        for (Path seed : frames) {
            if (!onlySeed.isEmpty() && !seed.getFileName().toString().contains(onlySeed)) continue;
            run(seed, out);
        }
        System.out.println("wrote stacks to " + out);
    }

    private static void run(Path seed, Path out) throws IOException {
        ImagePlus imp = IJ.openImage(seed.toString());
        if (imp == null) throw new IOException("could not open " + seed);
        int sw = imp.getWidth();
        int sh = imp.getHeight();
        float[] fine = Benchmark.seedPlane(imp);
        imp.close();
        String name = seed.getFileName().toString().replaceFirst("\\.tif$", "");

        int[] fx = new int[Benchmark.FRAMES];
        int[] fy = new int[Benchmark.FRAMES];
        Benchmark.trajectory(fx, fy);
        double[] tx = new double[Benchmark.FRAMES];
        double[] ty = new double[Benchmark.FRAMES];
        double reach = 0;
        int span = 0;
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            tx[t] = fx[t] / (double) Benchmark.FINE;
            ty[t] = fy[t] / (double) Benchmark.FINE;
            reach = Math.max(reach, Math.hypot(tx[t], ty[t]));
            span = Math.max(span, Math.max(Math.abs(fx[t]), Math.abs(fy[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int win = Math.min((sw - 2 * margin) / Benchmark.FINE, (sh - 2 * margin) / Benchmark.FINE);
        if (win < 64) throw new IOException(name + ": window would be only " + win + " px");
        double maxShift = 2 * reach + 8;
        double sd = Benchmark.standardDeviation(fine);

        String onlyCondition = System.getProperty("ripr.onlyCondition", "");
        String onlyEstimator = System.getProperty("ripr.onlyEstimator", "");
        String onlyRecon = System.getProperty("ripr.onlyRecon", "");

        // The same seed on the same trajectory with no nuisance applied. Only the standard-deviation
        // panel uses it, and only because the nuisances swamp that panel — see the class javadoc.
        float[][] clean = new float[Benchmark.FRAMES][];
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            clean[t] = Benchmark.frame(fine, sw, sh, win, margin, fx[t], fy[t],
                    Benchmark.Condition.CLEAN, t, sd);
        }

        for (Benchmark.Condition condition : Benchmark.Condition.values()) {
            if (!onlyCondition.isEmpty() && !onlyCondition.equals(condition.name())) continue;
            float[][] plane = new float[Benchmark.FRAMES][];
            for (int t = 0; t < Benchmark.FRAMES; t++) {
                plane[t] = Benchmark.frame(fine, sw, sh, win, margin, fx[t], fy[t], condition, t, sd);
            }
            for (Benchmark.Estimator e : Benchmark.Estimator.values()) {
                if (!onlyEstimator.isEmpty() && !onlyEstimator.equals(e.name())) continue;
                if (!e.pyramid) continue;      // the Fourier baselines have no transforms to apply here
                for (Benchmark.Recon r : Benchmark.Recon.values()) {
                    if (!onlyRecon.isEmpty() && !onlyRecon.equals(r.name())) continue;
                    write(out, name, condition, e, r, plane, clean, win, maxShift, tx, ty);
                }
            }
        }
    }

    private static void write(Path out, String seed, Benchmark.Condition condition,
                              Benchmark.Estimator e, Benchmark.Recon r, float[][] plane,
                              float[][] clean, int win,
                              double maxShift, double[] tx, double[] ty) throws IOException {
        int n = plane.length;
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                r == Benchmark.Recon.CHAIN
                        ? Reconciler.Reference.CONSECUTIVE : Reconciler.Reference.MULTILAG,
                n, 0, 1, Benchmark.LAGS);

        Benchmark.Estimator effective = e == Benchmark.Estimator.LOGRATIO_AUTO_INFORMATION
                ? Benchmark.resolveAutoInformation(plane, win) : e;
        PairAligner.Options options = effective.options(maxShift);
        int levels = options.levelsFor(win, win);
        double satPct = effective.ceiling();
        double floorPct = effective.floor();
        LogPlane[][] pyramids = new LogPlane[n][];
        for (int t = 0; t < n; t++) {
            double satMax = Double.isNaN(satPct)
                    ? LogPlane.NO_SATURATION : Benchmark.percentile(plane[t], satPct);
            double floor = Double.isNaN(floorPct)
                    ? LogPlane.NO_FLOOR : Benchmark.percentile(plane[t], floorPct);
            pyramids[t] = LogPlane.of(plane[t], win, win, EPSILON, floor, satMax).pyramid(levels);
        }

        Transform[] measured = new Transform[plan.size()];
        for (int i = 0; i < plan.size(); i++) {
            Reconciler.Observation ob = plan.get(i);
            measured[i] = PairAligner.align(pyramids[ob.from], pyramids[ob.to], options).transform;
        }
        Reconciler.Solution solution;
        if (r == Benchmark.Recon.CHAIN) {
            solution = Reconciler.chain(measured);
        } else {
            List<Reconciler.Observation> obs = new ArrayList<>(plan.size());
            for (int i = 0; i < plan.size(); i++) {
                obs.add(new Reconciler.Observation(plan.get(i).from, plan.get(i).to, measured[i]));
            }
            solution = Reconciler.multiLag(n, obs);
        }

        float[][] corrected = new float[n][];
        float[][] correctedClean = new float[n][];
        for (int t = 0; t < n; t++) {
            corrected[t] = new float[win * win];
            correctedClean[t] = new float[win * win];
            Transform c = solution.cumulative[t] == null ? Transform.IDENTITY : solution.cumulative[t];
            // Passed directly, NOT negated. Warper.warp samples through the transform, so it already
            // undoes the motion the transform describes; negating it doubles the drift instead of
            // removing it. Caught by a panel in which the corrected half was 5-8% worse than the
            // uncorrected one, which is the signature of exactly that mistake.
            Warper.warp(plane[t], corrected[t], win, win, c, Warper.Interpolation.NONE, Float.NaN);
            Warper.warp(clean[t], correctedClean[t], win, win, c,
                    Warper.Interpolation.NONE, Float.NaN);
        }

        double[] err = Benchmark.errors(solution, tx, ty);
        String tag = String.format("%s_%s_%s_%s", seed, condition, slug(e.label), r);
        IJ.saveAsTiff(stack(plane, win, tag + " input"), out.resolve(tag + "_input.tif").toString());
        IJ.saveAsTiff(stack(corrected, win, tag + " corrected"),
                out.resolve(tag + "_corrected.tif").toString());
        IJ.saveAsTiff(sdPanel(clean, correctedClean, win), out.resolve(tag + "_sd.tif").toString());
        System.out.printf("  %-56s median %8.3f px%n", tag, Benchmark.quantile(err, 0.5));
    }

    /** Filesystem-safe, and stable, so a rerun overwrites rather than accumulating near-duplicates. */
    private static String slug(String label) {
        return label.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    static ImagePlus stack(float[][] planes, int win, String title) {
        ImageStack s = new ImageStack(win, win);
        for (int t = 0; t < planes.length; t++) {
            s.addSlice("t" + (t + 1), new FloatProcessor(win, win, planes[t].clone(), null));
        }
        return new ImagePlus(title, s);
    }

    /**
     * Per-pixel temporal standard deviation, uncorrected on the left and corrected on the right,
     * sharing one display range so the two halves are directly comparable by eye.
     *
     * <p>Both halves are built from the clean stack; see the class javadoc for why that is necessary
     * rather than convenient.
     *
     * <p>NaN pixels — the margin a whole-pixel warp leaves behind — are skipped rather than counted as
     * zero, which would otherwise draw a dark frame around the corrected half and flatter it.
     */
    static ImagePlus sdPanel(float[][] before, float[][] after, int win) {
        float[] a = temporalSd(before, win);
        float[] b = temporalSd(after, win);
        int gap = 6;
        float[] panel = new float[(2 * win + gap) * win];
        java.util.Arrays.fill(panel, Float.NaN);
        for (int y = 0; y < win; y++) {
            System.arraycopy(a, y * win, panel, y * (2 * win + gap), win);
            System.arraycopy(b, y * win, panel, y * (2 * win + gap) + win + gap, win);
        }
        ImagePlus imp = new ImagePlus("temporal SD: raw | registered",
                new FloatProcessor(2 * win + gap, win, panel, null));
        imp.getProcessor().resetMinAndMax();
        return imp;
    }

    private static float[] temporalSd(float[][] planes, int win) {
        float[] out = new float[win * win];
        for (int i = 0; i < out.length; i++) {
            double sum = 0;
            int k = 0;
            for (float[] p : planes) {
                if (Float.isNaN(p[i])) continue;
                sum += p[i];
                k++;
            }
            if (k < 2) {
                out[i] = Float.NaN;
                continue;
            }
            double mean = sum / k;
            double ss = 0;
            for (float[] p : planes) {
                if (Float.isNaN(p[i])) continue;
                ss += (p[i] - mean) * (p[i] - mean);
            }
            out[i] = (float) Math.sqrt(ss / k);
        }
        return out;
    }
}
