/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.SelectionMode;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Whole-recording Java reference for the cross-language parity harness.
 *
 * <p>Runs the public no-dialog API over one real TIFF stack and writes every per-frame quantity the
 * Python package also produces, so a divergence is attributed to a named frame and column rather
 * than to a single summary number. Values print at full {@code double} precision; the comparison
 * tolerance belongs to the checker, not to this writer.
 */
public final class EndToEndParityProbe {
    private EndToEndParityProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "source.tif imageType motionType selectionMode channel output.csv [threads]");
        }
        String source = Paths.get(args[0]).toAbsolutePath().toString();
        ImageType imageType = ImageType.valueOf(args[1].toUpperCase(Locale.ROOT));
        MotionType motionType = MotionType.valueOf(args[2].toUpperCase(Locale.ROOT));
        SelectionMode selectionMode = SelectionMode.valueOf(args[3].toUpperCase(Locale.ROOT));
        int channel = Integer.parseInt(args[4]);
        int threads = args.length > 6 ? Integer.parseInt(args[6]) : 1;

        ImagePlus image = IJ.openImage(source);
        if (image == null) throw new IllegalArgumentException("could not open " + source);

        // recommendation() is what copies the preset's preprocessing, norm, support and percentiles
        // into the parameters. Setting selectionMode alone marks the mode and leaves every one of
        // those at the builder default, which silently fits unpreprocessed pixels -- the Python side
        // populates them, so anything less than this compares two different recipes.
        RelativeIntensityPatternParameters.Builder builder = RelativeIntensityPatternParameters
                .builder()
                .recommendation(imageType, motionType);
        if (selectionMode != SelectionMode.RECOMMENDED) {
            builder = builder.selectionMode(selectionMode);
        }
        RelativeIntensityPatternParameters parameters = builder
                .channel(channel)
                .threads(threads)
                .build();

        long started = System.nanoTime();
        Registration.Result result = RelativeIntensityPatternRegistration.estimate(
                image, parameters, PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        long elapsed = System.nanoTime() - started;

        try (PrintStream out = new PrintStream(
                Files.newOutputStream(Paths.get(args[5])), false, StandardCharsets.UTF_8.name())) {
            out.printf(Locale.ROOT, "# elapsed_seconds,%.6f%n", elapsed / 1e9);
            out.printf(Locale.ROOT, "# frames,%d%n", result.cumulative.length);
            out.printf(Locale.ROOT, "# levels,%d%n", result.levels);
            out.println("frame,dx,dy,theta,log2_gain,support,residual_before,residual_after,"
                    + "valid_fraction,status,repair");
            for (int frame = 0; frame < result.cumulative.length; frame++) {
                Transform t = result.cumulative[frame];
                out.printf(Locale.ROOT, "%d,%s,%s,%s,%s,%d,%s,%s,%s,%s,%s%n",
                        frame,
                        repr(t.dx), repr(t.dy), repr(t.theta),
                        repr(result.log2Gain[frame]),
                        result.support[frame],
                        repr(result.residualBefore[frame]),
                        repr(result.residualAfter[frame]),
                        repr(result.validFraction[frame]),
                        result.status[frame] == null ? "" : result.status[frame].name(),
                        result.repairs[frame] == null ? "" : result.repairs[frame].name());
            }
        } finally {
            image.close();
        }

        // Per-pair fits, written beside the chain. A cumulative transform is a sum of these, so a
        // divergence here is the estimator and a divergence only in the chain is the reconciler.
        Path pairsPath = Paths.get(args[5].replaceAll("\\.csv$", "") + "_pairs.csv");
        try (PrintStream out = new PrintStream(
                Files.newOutputStream(pairsPath), false, StandardCharsets.UTF_8.name())) {
            out.println("from,to,dx,dy,theta,residual_before,residual_after,log_gain,"
                    + "valid_fraction,iterations,status");
            for (Registration.PairResult pair : result.pairs) {
                PairAligner.Fit fit = pair.fit;
                out.printf(Locale.ROOT, "%d,%d,%s,%s,%s,%s,%s,%s,%s,%d,%s%n",
                        pair.from, pair.to,
                        repr(fit.transform.dx), repr(fit.transform.dy), repr(fit.transform.theta),
                        repr(fit.residualBefore), repr(fit.residualAfter), repr(fit.logGain),
                        repr(fit.validFraction), fit.iterations,
                        fit.status == null ? "" : fit.status.name());
            }
        }
    }

    /** Full-precision, round-trippable text for one double, including non-finite values. */
    private static String repr(double value) {
        if (Double.isNaN(value)) return "nan";
        if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
        return Double.toString(value);
    }
}
