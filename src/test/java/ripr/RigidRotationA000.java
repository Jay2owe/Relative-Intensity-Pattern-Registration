/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.Transform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Thin A000 adapter: score the current rigid pair solver without changing production code. */
public final class RigidRotationA000 {
    private RigidRotationA000() { }

    static final class CaseSpec {
        final String id;
        final String label;
        final String imageType;
        final String scope;
        final Path source;
        final int trial;
        final Transform half;
        final double gain;
        final String snr;

        CaseSpec(String id, String label, String imageType, String scope, Path source, int trial,
                 Transform half, double gain, String snr) {
            this.id = id;
            this.label = label;
            this.imageType = imageType;
            this.scope = scope;
            this.source = source;
            this.trial = trial;
            this.half = half;
            this.gain = gain;
            this.snr = snr;
        }
    }

    static final class Measurement {
        final PairAligner.Fit fit;
        final Transform truth;
        final double rotationError;
        final double translationError;
        final double central50;
        final double central80;
        final double full;
        final double runtimeMs;

        Measurement(PairAligner.Fit fit, Transform truth, int width, int height, double runtimeMs) {
            this.fit = fit;
            this.truth = truth;
            rotationError = Math.abs(fit.transform.thetaDegrees() - truth.thetaDegrees());
            translationError = Math.hypot(fit.transform.dx - truth.dx,
                    fit.transform.dy - truth.dy);
            ThevenazProtocolBenchmark.Affine2D expected =
                    ThevenazProtocolBenchmark.Affine2D.rigid(truth, width, height);
            ThevenazProtocolBenchmark.Affine2D actual =
                    ThevenazProtocolBenchmark.Affine2D.rigid(fit.transform, width, height);
            central50 = ThevenazProtocolBenchmark.warpingIndex(expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.CENTRAL_50);
            central80 = ThevenazProtocolBenchmark.warpingIndex(expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.CENTRAL_80);
            full = ThevenazProtocolBenchmark.warpingIndex(expected, actual, width, height,
                    ThevenazProtocolBenchmark.Region.FULL_FRAME);
            this.runtimeMs = runtimeMs;
        }
    }

    static Measurement measure(ThevenazProtocolBenchmark.Frames frames) {
        long start = System.nanoTime();
        PairAligner.Fit fit = ThevenazProtocolBenchmark.estimateInternalFit(
                PairEstimator.Kind.LOG_RATIO_FIT, frames, true, Math.toRadians(10.0));
        return new Measurement(fit, frames.truth, frames.width, frames.height,
                (System.nanoTime() - start) / 1_000_000.0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: RigidRotationA000 <official.ps> <cases.tsv> <metrics.csv>");
        }
        Path postscript = Paths.get(args[0]);
        Path cases = Paths.get(args[1]);
        Path output = Paths.get(args[2]);
        List<ThevenazProtocolBenchmark.Trial> trials = ThevenazProtocolBenchmark.trials(
                ThevenazProtocolBenchmark.RANDOM_SEED,
                ThevenazProtocolBenchmark.DEFAULT_TRIALS, true);
        ThevenazProtocolBenchmark.ReferenceImage figure =
                ThevenazProtocolBenchmark.paperFigure3(postscript);
        Map<Path, float[]> pixels = new LinkedHashMap<>();

        Files.createDirectories(output.toAbsolutePath().getParent());
        try (BufferedReader reader = Files.newBufferedReader(cases, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) throw new IOException("empty case TSV");
            writer.write("case_id,label,image_type,scope,truth_dx,truth_dy,truth_theta_degrees,"
                    + "estimate_dx,estimate_dy,estimate_theta_degrees,rotation_error_degrees,"
                    + "translation_error_px,warp_central50_px,warp_central80_px,warp_full_px,"
                    + "status,iterations,valid_fraction,residual_before,residual_after,runtime_ms");
            writer.newLine();
            int done = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                CaseSpec spec = parse(line);
                ThevenazProtocolBenchmark.Frames frames;
                if ("historical_pair".equals(spec.scope)) {
                    ThevenazProtocolBenchmark.Trial trial = trials.get(spec.trial);
                    frames = ThevenazProtocolBenchmark.generate(
                            figure.pixels, figure.width, figure.height, trial.half);
                } else {
                    float[] base = pixels.get(spec.source);
                    if (base == null) {
                        base = loadCentralPlane(spec.source, 256);
                        pixels.put(spec.source, base);
                    }
                    frames = ThevenazProtocolBenchmark.generate(base, 256, 256, spec.half);
                    if (spec.gain != 1.0) {
                        float[] gained = frames.reference.clone();
                        for (int i = 0; i < gained.length; i++) gained[i] *= spec.gain;
                        frames = new ThevenazProtocolBenchmark.Frames(frames.test, gained,
                                frames.width, frames.height, frames.truth);
                    }
                    if (!"clean".equals(spec.snr)) {
                        double snr = Double.parseDouble(spec.snr);
                        double variance = ThevenazProtocolBenchmark.sampleVariance(base);
                        double sigma = ThevenazProtocolBenchmark.noiseSigma(variance, snr);
                        long seed = Integer.toUnsignedLong(spec.id.hashCode());
                        frames = ThevenazProtocolBenchmark.addGaussianNoise(
                                frames, sigma, seed, seed ^ 0x5deece66dL);
                    }
                }
                Measurement measurement = measure(frames);
                write(writer, spec, measurement);
                done++;
                if (done % 10 == 0) System.out.println("A000 pair cases " + done);
            }
        }
    }

    private static CaseSpec parse(String line) {
        String[] p = line.split("\\t", -1);
        if (p.length != 10) throw new IllegalArgumentException("expected 10 TSV fields: " + line);
        return new CaseSpec(p[0], p[1], p[2], p[3],
                p[4].isEmpty() ? null : Paths.get(p[4]),
                p[5].isEmpty() ? -1 : Integer.parseInt(p[5]),
                p[6].isEmpty() ? null : new Transform(Double.parseDouble(p[6]),
                        Double.parseDouble(p[7]), Math.toRadians(Double.parseDouble(p[8]))),
                p[9].isEmpty() ? 1.0 : Double.parseDouble(p[9].split(";", -1)[0]),
                p[9].isEmpty() ? "clean" : p[9].split(";", -1)[1]);
    }

    static float[] loadCentralPlane(Path path, int size) {
        ImagePlus image = IJ.openImage(path.toString());
        if (image == null || image.getStackSize() < 1) {
            throw new IllegalArgumentException("ImageJ could not open " + path);
        }
        try {
            ImageProcessor processor = image.getStack().getProcessor(1).convertToFloatProcessor();
            if (processor.getWidth() < size || processor.getHeight() < size) {
                throw new IllegalArgumentException(path + " is smaller than " + size + "x" + size);
            }
            int x = (processor.getWidth() - size) / 2;
            int y = (processor.getHeight() - size) / 2;
            processor.setRoi(x, y, size, size);
            return (float[]) processor.crop().getPixels();
        } finally {
            image.close();
        }
    }

    private static void write(BufferedWriter writer, CaseSpec spec, Measurement m)
            throws IOException {
        writer.write(spec.id + "," + spec.label + "," + spec.imageType + "," + spec.scope
                + "," + f(m.truth.dx) + "," + f(m.truth.dy) + "," + f(m.truth.thetaDegrees())
                + "," + f(m.fit.transform.dx) + "," + f(m.fit.transform.dy) + ","
                + f(m.fit.transform.thetaDegrees()) + "," + f(m.rotationError) + ","
                + f(m.translationError) + "," + f(m.central50) + "," + f(m.central80)
                + "," + f(m.full) + "," + m.fit.status + "," + m.fit.iterations + ","
                + f(m.fit.validFraction) + "," + f(m.fit.residualBefore) + ","
                + f(m.fit.residualAfter) + "," + f(m.runtimeMs));
        writer.newLine();
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }
}
