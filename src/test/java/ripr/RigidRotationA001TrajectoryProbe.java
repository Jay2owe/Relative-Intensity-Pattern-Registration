/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.FrameSource;
import ripr.core.PairAligner;
import ripr.core.PairScheduler;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

/** Frozen 48-frame known-truth probe for the A001 lag-aware warm-start approach. */
public final class RigidRotationA001TrajectoryProbe {
    private RigidRotationA001TrajectoryProbe() { }

    static final class Score {
        final long runtimeMs;
        final double medianWarp;
        final double p95Warp;
        final double worstWarp;
        final double maxAngleError;
        final int badStatuses;

        Score(long runtimeMs, double[] warp, double maxAngleError, int badStatuses) {
            this.runtimeMs = runtimeMs;
            Arrays.sort(warp);
            medianWarp = percentile(warp, 0.50);
            p95Warp = percentile(warp, 0.95);
            worstWarp = percentile(warp, 1.00);
            this.maxAngleError = maxAngleError;
            this.badStatuses = badStatuses;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: RigidRotationA001TrajectoryProbe "
                    + "<official.ps> <metrics.csv>");
        }
        Path postscript = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        ThevenazProtocolBenchmark.ReferenceImage image =
                ThevenazProtocolBenchmark.paperFigure3(postscript);
        int frames = 48;
        Transform[] truth = new Transform[frames];
        float[][] planes = new float[frames][];
        double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                image.pixels, image.width, image.height);
        for (int t = 0; t < frames; t++) {
            double u = t / (double) (frames - 1);
            truth[t] = new Transform(4.0 * Math.sin(Math.PI * u),
                    -3.0 * u + 0.8 * Math.sin(2 * Math.PI * u),
                    Math.toRadians(10.0 * u));
            planes[t] = ThevenazProtocolBenchmark.spline7Warp(
                    coefficients, image.width, image.height, truth[t].inverse());
        }
        FrameSource source = source(image.width, image.height, planes);
        Registration.Options baseline = options(false);
        Score a000 = run(source, baseline, truth, image.width, image.height);
        Score a001 = run(source, options(true), truth, image.width, image.height);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            out.write("attempt,runtime_ms,median_warp_central50_px,p95_warp_central50_px,"
                    + "worst_warp_central50_px,max_angle_error_degrees,bad_statuses");
            out.newLine();
            write(out, "A000", a000);
            write(out, "A001", a001);
        }
    }

    private static Registration.Options options(boolean warmStarts) {
        Registration.Options options = Registration.Options.recommendedFor(
                Reconciler.Reference.MULTILAG);
        options.lags = new int[]{1, 2, 4, 8, 16};
        options.aligner.fitRotation = true;
        options.aligner.maxRotation = Math.toRadians(10);
        options.aligner.maxShift = 30;
        options.autoMaxShift = false;
        options.threads = 0;
        options.lagAwareWarmStarts = warmStarts;
        return options;
    }

    private static Score run(FrameSource source, Registration.Options options, Transform[] truth,
                             int width, int height) {
        long start = System.nanoTime();
        Registration.Result result = Registration.run(source, options,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        long runtimeMs = (System.nanoTime() - start) / 1_000_000L;
        double[] warp = new double[truth.length];
        double maxAngleError = 0;
        for (int i = 0; i < truth.length; i++) {
            ThevenazProtocolBenchmark.Affine2D expected =
                    ThevenazProtocolBenchmark.Affine2D.rigid(truth[i], width, height);
            ThevenazProtocolBenchmark.Affine2D actual =
                    ThevenazProtocolBenchmark.Affine2D.rigid(result.cumulative[i], width, height);
            warp[i] = ThevenazProtocolBenchmark.warpingIndex(expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.CENTRAL_50);
            maxAngleError = Math.max(maxAngleError,
                    Math.abs(truth[i].thetaDegrees() - result.cumulative[i].thetaDegrees()));
        }
        int badStatuses = 0;
        for (Registration.PairResult pair : result.pairs) {
            if (pair.fit == null || pair.fit.status != PairAligner.Status.OK) badStatuses++;
        }
        return new Score(runtimeMs, warp, maxAngleError, badStatuses);
    }

    private static FrameSource source(int width, int height, float[][] planes) {
        return new FrameSource() {
            @Override public int count() { return planes.length; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
            @Override public float[] plane(int frame) { return planes[frame].clone(); }
        };
    }

    private static double percentile(double[] sorted, double fraction) {
        double position = (sorted.length - 1) * fraction;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double weight = position - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    private static void write(BufferedWriter out, String name, Score score) throws Exception {
        out.write(name + "," + score.runtimeMs + "," + f(score.medianWarp) + ","
                + f(score.p95Warp) + "," + f(score.worstWarp) + ","
                + f(score.maxAngleError) + "," + score.badStatuses);
        out.newLine();
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }
}
