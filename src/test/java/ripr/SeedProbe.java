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
import ripr.core.Localisability;
import ripr.core.LogPlane;

import java.io.IOException;

/**
 * Admission and intensity-decile measurements for benchmark seeds. <b>Test scope only.</b>
 *
 * <p>A seed is admitted before the expensive matrix is run: this reports whether a one-pixel error is
 * visible in the frame, the raw intensity distribution, the brightness used for synthetic blocks, and
 * which intensity deciles carry the log-domain gradient. It uses {@link Benchmark#seedPlane} and
 * {@link Benchmark#EPSILON}, so the probe and benchmark cannot silently interpret a TIFF differently.
 *
 * <pre>
 *   java -cp &lt;classes&gt;;&lt;test-classes&gt;;ij.jar ripr.SeedProbe &lt;seed.tif&gt; [more seeds ...]
 * </pre>
 */
public final class SeedProbe {

    private SeedProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: SeedProbe <seed.tif> [more seeds ...]");
            return;
        }
        for (String path : args) probe(path);
    }

    private static void probe(String path) throws IOException {
        ImagePlus imp = IJ.openImage(path);
        if (imp == null) throw new IOException("could not open " + path);
        int w = imp.getWidth();
        int h = imp.getHeight();
        String type = imp.getProcessor().getClass().getSimpleName();
        float[] raw = Benchmark.seedPlane(imp);
        imp.close();

        float[] sorted = raw.clone();
        java.util.Arrays.sort(sorted);
        double localisability = Localisability.ofPair(raw, raw, w, h);
        String name = path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
        System.out.printf("%n%s  %dx%d  %s%n", name, w, h, type);
        System.out.printf("  raw-frame localisability %.6f%s%n", localisability,
                Localisability.poor(localisability) ? "  <- BELOW 0.05" : "");
        System.out.printf("  intensity p0 %.0f, p10 %.0f, p50 %.0f, p70 %.0f, p90 %.0f, "
                        + "p99.5 %.0f, max %.0f%n",
                percentile(sorted, 0), percentile(sorted, 10), percentile(sorted, 50),
                percentile(sorted, 70), percentile(sorted, 90), percentile(sorted, 99.5),
                percentile(sorted, 100));
        System.out.printf("  epsilon %.1f stored count; CHANGE_BLOCKS brightness %.1f%n",
                Benchmark.EPSILON, percentile(sorted, 99.5) * 2.0 + 8.0);

        int[] fx = new int[Benchmark.FRAMES];
        int[] fy = new int[Benchmark.FRAMES];
        Benchmark.trajectory(fx, fy);
        int span = 0;
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            span = Math.max(span, Math.max(Math.abs(fx[t]), Math.abs(fy[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int win = Math.min((w - 2 * margin) / Benchmark.FINE,
                (h - 2 * margin) / Benchmark.FINE);
        if (win < 64) throw new IOException(path + ": window would be only " + win + " px");
        float[] frame = Benchmark.frame(raw, w, h, win, margin, fx[0], fy[0],
                Benchmark.Condition.CLEAN, 0, Benchmark.standardDeviation(raw));
        float[] frameSorted = frame.clone();
        java.util.Arrays.sort(frameSorted);
        System.out.printf("  benchmark frame %dx%d; localisability %.6f%n", win, win,
                Localisability.ofPair(frame, frame, win, win));
        deciles(frame, frameSorted, win, win);
    }

    private static void deciles(float[] raw, float[] sorted, int w, int h) {
        LogPlane log = LogPlane.of(raw, w, h, Benchmark.EPSILON);
        double[] edge = new double[11];
        for (int d = 0; d <= 10; d++) edge[d] = percentile(sorted, 10.0 * d);
        double[] sum = new double[10];
        int[] count = new int[10];
        double total = 0;
        for (int i = 0; i < raw.length; i++) {
            int decile = 9;
            for (int d = 0; d < 10; d++) {
                if (raw[i] <= edge[d + 1]) {
                    decile = d;
                    break;
                }
            }
            double gradient = log.gradMagnitude(i);
            sum[decile] += gradient;
            count[decile]++;
            total += gradient;
        }

        System.out.printf("  %-9s %13s %13s %16s%n",
                "decile", "intensity", "mean |grad|", "share of total");
        for (int d = 0; d < 10; d++) {
            System.out.printf("  %2d-%-3d%% %5.0f-%-6.0f %13.5f %15.1f%%%n",
                    d * 10, d * 10 + 10, edge[d], edge[d + 1],
                    count[d] == 0 ? 0 : sum[d] / count[d], 100.0 * sum[d] / total);
        }
        double bottom = sum[0] + sum[1] + sum[2];
        double top = sum[7] + sum[8] + sum[9];
        System.out.printf("  dimmest 30%% carry %.1f%%; brightest 30%% carry %.1f%%%n",
                100.0 * bottom / total, 100.0 * top / total);
    }

    private static float percentile(float[] sorted, double q) {
        return sorted[(int) Math.round(q / 100.0 * (sorted.length - 1))];
    }
}
