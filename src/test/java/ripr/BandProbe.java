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
import ripr.core.LogPlane;

/**
 * Why an intensity band cannot exclude more than about a quarter of a frame. <b>Test scope only.</b>
 *
 * <p><b>The wall is geometric, not informational.</b> {@link LogPlane#sample} returns NaN for any
 * position whose bilinear footprint touches an invalid pixel — deliberately, so a criterion cannot lower
 * its own cost by sliding the frame off the edge of the valid region. The consequence is that excluding
 * one pixel destroys up to four <em>positions</em>, and the solver's usable fraction falls far faster
 * than the excluded fraction.
 *
 * <p>That is what stops both {@code saturationPercentile} and {@code intensityFloorPercentile} at
 * roughly 25%: past there the usable fraction drops under {@code minValidFraction} (0.10) and the pair
 * is refused. Refusing is correct — the alternative is a confident answer built on a twentieth of the
 * frame — but it means the controls have a usable range of 0–25%, not 0–100%, and it is why a two-sided
 * band does not stack.
 *
 * <p>Measured 2026-08-11 on the benchmark seeds, and quoted in {@code README.md},
 * {@code IMPROVEMENTS.md} and {@code HANDOFF_pixel_selection.md}. This class is what produced those
 * numbers; {@link FluorSeed} prints the companion measurement, where the log-domain gradient lives by
 * intensity decile.
 *
 * <pre>
 *   java -cp &lt;classes&gt;;&lt;test-classes&gt;;ij.jar ripr.BandProbe &lt;seed.tif&gt; [more seeds ...]
 * </pre>
 */
public final class BandProbe {

    private BandProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: BandProbe <seed.tif> [more seeds ...]");
            return;
        }
        for (String arg : args) {
            probe(arg);
        }
    }

    private static void probe(String path) throws java.io.IOException {
        ImagePlus imp = IJ.openImage(path);
        if (imp == null) throw new java.io.IOException("could not open " + path);
        int sw = imp.getWidth();
        int sh = imp.getHeight();
        float[] fine = Benchmark.seedPlane(imp);
        imp.close();

        // The frame the benchmark would actually align, so the numbers describe the real fixture rather
        // than the raw seed: same trajectory, same margin, same 4x4 decimation, same noise.
        int[] fx = new int[Benchmark.FRAMES];
        int[] fy = new int[Benchmark.FRAMES];
        Benchmark.trajectory(fx, fy);
        int span = 0;
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            span = Math.max(span, Math.max(Math.abs(fx[t]), Math.abs(fy[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int win = Math.min((sw - 2 * margin) / Benchmark.FINE, (sh - 2 * margin) / Benchmark.FINE);
        if (win < 64) throw new java.io.IOException(path + ": window would be only " + win + " px");
        float[] plane = Benchmark.frame(fine, sw, sh, win, margin, fx[0], fy[0],
                Benchmark.Condition.CLEAN, 0, Benchmark.standardDeviation(fine));

        String name = path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
        System.out.printf("%n%s  %dx%d%n", name, win, win);
        System.out.printf("%-22s %11s %13s %10s%n",
                "excluded", "px kept", "positions kept", "ratio");
        report("nothing", plane, win, LogPlane.NO_FLOOR, LogPlane.NO_SATURATION);
        for (double p : new double[]{90, 75, 50, 25}) {
            report(String.format("brightest %.0f%%", 100 - p), plane, win,
                    LogPlane.NO_FLOOR, percentile(plane, p));
        }
        for (double p : new double[]{10, 25, 50}) {
            report(String.format("dimmest %.0f%%", p), plane, win,
                    percentile(plane, p), LogPlane.NO_SATURATION);
        }
        System.out.printf("  a pair is refused below minValidFraction = %.2f of positions%n",
                new PairAlignerDefaults().minValidFraction);
    }

    /** Reads the shipped default rather than restating it, so the two cannot drift apart. */
    private static final class PairAlignerDefaults {
        final double minValidFraction = new ripr.core.PairAligner.Options().minValidFraction;
    }

    private static void report(String label, float[] plane, int win, double floor, double sat) {
        LogPlane lp = LogPlane.of(plane, win, win, 1.0, floor, sat);
        double kept = lp.validCount / (double) (win * win);

        // A position is usable only if all four of its bilinear neighbours survived.
        int usable = 0;
        for (int y = 0; y < win - 1; y++) {
            for (int x = 0; x < win - 1; x++) {
                int i = y * win + x;
                if (lp.valid[i] && lp.valid[i + 1] && lp.valid[i + win] && lp.valid[i + win + 1]) {
                    usable++;
                }
            }
        }
        double positions = usable / (double) ((win - 1) * (win - 1));
        System.out.printf("%-22s %10.1f%% %12.1f%% %10.2f%s%n", label, 100 * kept, 100 * positions,
                positions / kept, positions < 0.10 ? "   <- REFUSED" : "");
    }

    private static double percentile(float[] a, double q) {
        float[] s = a.clone();
        java.util.Arrays.sort(s);
        return s[(int) Math.round(q / 100.0 * (s.length - 1))];
    }
}
