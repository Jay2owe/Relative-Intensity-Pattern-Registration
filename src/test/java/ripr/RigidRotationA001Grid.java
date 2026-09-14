/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.ImagePlus;
import ij.ImageStack;
import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.MotionType;
import ripr.api.SelectionMode;
import ripr.core.PairAligner;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Random;

/** A000/A001 trajectory grid on every frozen automatic-selector image type. */
public final class RigidRotationA001Grid {
    private static final int SIZE = 256;
    private static final int FRAMES = 20;

    private RigidRotationA001Grid() { }

    static final class Score {
        long runtimeMs;
        double medianWarp;
        double p95Warp;
        double worstWarp;
        double maxAngleError;
        int badStatuses;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: RigidRotationA001Grid <sources.tsv> <metrics.csv>");
        }
        Path sources = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (BufferedReader in = Files.newBufferedReader(sources, StandardCharsets.UTF_8);
             BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            out.write("case_id,image_type,condition,attempt,runtime_ms,median_warp_central50_px,"
                    + "p95_warp_central50_px,worst_warp_central50_px,max_angle_error_degrees,"
                    + "bad_statuses");
            out.newLine();
            String header = in.readLine();
            if (header == null) throw new IllegalArgumentException("empty source TSV");
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3) throw new IllegalArgumentException("bad source row: " + line);
                String caseId = fields[0];
                ImageType imageType = imageType(fields[1]);
                float[] base = RigidRotationA000.loadCentralPlane(Paths.get(fields[2]), SIZE);
                for (String condition : new String[]{"clean", "gain_fade_snr20"}) {
                    Transform[] truth = trajectory();
                    ImagePlus stack = generate(base, truth, condition, caseId.hashCode());
                    try {
                        Score a000 = run(stack, imageType, truth, false);
                        Score a001 = run(stack, imageType, truth, true);
                        write(out, caseId, imageType, condition, "A000", a000);
                        write(out, caseId, imageType, condition, "A001", a001);
                        out.flush();
                    } finally {
                        stack.close();
                    }
                }
            }
        }
    }

    private static Transform[] trajectory() {
        Transform[] truth = new Transform[FRAMES];
        for (int t = 0; t < FRAMES; t++) {
            double u = t / (double) (FRAMES - 1);
            truth[t] = new Transform(3.0 * Math.sin(Math.PI * u),
                    -2.5 * u + 0.5 * Math.sin(2 * Math.PI * u),
                    Math.toRadians(8.0 * u + 0.25 * Math.sin(2 * Math.PI * u)));
        }
        return truth;
    }

    private static ImagePlus generate(float[] base, Transform[] truth, String condition, long seed) {
        double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(base, SIZE, SIZE);
        double sigma = ThevenazProtocolBenchmark.noiseSigma(
                ThevenazProtocolBenchmark.sampleVariance(base), 20.0);
        ImageStack stack = new ImageStack(SIZE, SIZE);
        for (int t = 0; t < truth.length; t++) {
            float[] plane = ThevenazProtocolBenchmark.spline7Warp(
                    coefficients, SIZE, SIZE, truth[t].inverse());
            if (!"clean".equals(condition)) {
                double gain = 1.0 - 0.5 * t / (truth.length - 1.0);
                Random random = new Random(seed * 31L + t);
                for (int i = 0; i < plane.length; i++) {
                    plane[i] = (float) (gain * plane[i] + sigma * random.nextGaussian());
                }
            }
            stack.addSlice(null, plane);
        }
        ImagePlus image = new ImagePlus("A001 grid", stack);
        image.setDimensions(1, 1, truth.length);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static Score run(ImagePlus stack, ImageType imageType, Transform[] truth,
                             boolean warmStarts) {
        RelativeIntensityPatternParameters parameters = RelativeIntensityPatternParameters.builder()
                .recommendation(imageType, MotionType.SUBPIXEL_RANDOM_WALK)
                .selectionMode(SelectionMode.AUTOMATIC)
                .fitRotation(true)
                .maxRotationDegrees(10)
                .autoMaxShift(false)
                .maxShift(12)
                .lagAwareWarmStarts(warmStarts)
                .build();
        long start = System.nanoTime();
        Registration.Result result = RelativeIntensityPatternRegistration.estimate(stack, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        Score score = new Score();
        score.runtimeMs = (System.nanoTime() - start) / 1_000_000L;
        double[] warp = new double[truth.length];
        for (int i = 0; i < truth.length; i++) {
            ThevenazProtocolBenchmark.Affine2D expected =
                    ThevenazProtocolBenchmark.Affine2D.rigid(truth[i], SIZE, SIZE);
            ThevenazProtocolBenchmark.Affine2D actual =
                    ThevenazProtocolBenchmark.Affine2D.rigid(result.cumulative[i], SIZE, SIZE);
            warp[i] = ThevenazProtocolBenchmark.warpingIndex(expected, actual, SIZE, SIZE,
                    ThevenazProtocolBenchmark.Region.CENTRAL_50);
            score.maxAngleError = Math.max(score.maxAngleError,
                    Math.abs(truth[i].thetaDegrees() - result.cumulative[i].thetaDegrees()));
        }
        java.util.Arrays.sort(warp);
        score.medianWarp = percentile(warp, 0.50);
        score.p95Warp = percentile(warp, 0.95);
        score.worstWarp = percentile(warp, 1.00);
        for (Registration.PairResult pair : result.pairs) {
            if (pair.fit == null || pair.fit.status != PairAligner.Status.OK) score.badStatuses++;
        }
        return score;
    }

    private static ImageType imageType(String value) {
        switch (value) {
            case "PHASE": return ImageType.PHASE_CONTRAST;
            case "BRIGHTFIELD_DIC": return ImageType.BRIGHTFIELD_DIC;
            case "DENSE_FLUOR": return ImageType.DENSE_FLUORESCENCE;
            case "SPARSE_LOWLIGHT": return ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
            case "FIDUCIAL_STATIC": return ImageType.FIDUCIAL_STATIC;
            default: throw new IllegalArgumentException("unknown grid image type " + value);
        }
    }

    private static double percentile(double[] sorted, double fraction) {
        double position = (sorted.length - 1) * fraction;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double weight = position - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    private static void write(BufferedWriter out, String caseId, ImageType imageType,
                              String condition, String attempt, Score score) throws Exception {
        out.write(caseId + "," + imageType.name() + "," + condition + "," + attempt + ","
                + score.runtimeMs + "," + f(score.medianWarp) + "," + f(score.p95Warp) + ","
                + f(score.worstWarp) + "," + f(score.maxAngleError) + ","
                + score.badStatuses);
        out.newLine();
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }
}
