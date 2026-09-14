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
import ripr.core.Reconciler;
import ripr.core.Transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Describes observed global image movement; it does not claim to recover true camera drift. */
final class NativeMotionProfiler {
    static final class Profile {
        final double[] phaseDx;
        final double[] phaseDy;
        final double[] modalDx;
        final double[] modalDy;
        final double medianStep;
        final double p90Step;
        final double maximumStep;
        final double netMovement;
        final double pathLength;
        final double directionality;
        final double medianEstimatorDisagreement;
        final String modalCheck;
        final String pattern;
        final String magnitude;
        final String category;

        Profile(double[] phaseDx, double[] phaseDy, double[] modalDx, double[] modalDy,
                double medianStep, double p90Step, double maximumStep, double netMovement,
                double pathLength, double directionality, double medianEstimatorDisagreement,
                String modalCheck, String pattern, String magnitude) {
            this.phaseDx = phaseDx;
            this.phaseDy = phaseDy;
            this.modalDx = modalDx;
            this.modalDy = modalDy;
            this.medianStep = medianStep;
            this.p90Step = p90Step;
            this.maximumStep = maximumStep;
            this.netMovement = netMovement;
            this.pathLength = pathLength;
            this.directionality = directionality;
            this.medianEstimatorDisagreement = medianEstimatorDisagreement;
            this.modalCheck = modalCheck;
            this.pattern = pattern;
            this.magnitude = magnitude;
            this.category = pattern + "__" + magnitude;
        }
    }

    static final class Residual {
        final double median;
        final double p90;
        final double maximum;

        Residual(double median, double p90, double maximum) {
            this.median = median;
            this.p90 = p90;
            this.maximum = maximum;
        }
    }

    private NativeMotionProfiler() { }

    static Profile profile(float[][] frames, int width) {
        int pairs = frames.length - 1;
        double[] phaseDx = new double[pairs];
        double[] phaseDy = new double[pairs];
        for (int t = 0; t < pairs; t++) {
            double[] shift = PhaseCorrelation.shift(frames[t], frames[t + 1], width, width);
            phaseDx[t] = shift[0];
            phaseDy[t] = shift[1];
        }

        double[] modalDx = new double[pairs];
        double[] modalDy = new double[pairs];
        Arrays.fill(modalDx, Double.NaN);
        Arrays.fill(modalDy, Double.NaN);
        try {
            Reconciler.Solution modal = Benchmark.solveModalMovementForStacks(
                    frames, width, Math.max(8, width / 4.0));
            for (int t = 0; t < pairs; t++) {
                Transform a = modal.cumulative[t] == null
                        ? Transform.IDENTITY : modal.cumulative[t];
                Transform b = modal.cumulative[t + 1] == null
                        ? Transform.IDENTITY : modal.cumulative[t + 1];
                modalDx[t] = b.dx - a.dx;
                modalDy[t] = b.dy - a.dy;
            }
        } catch (RuntimeException ignored) {
            // The phase trace still gives a declared category; disagreement is reported as unavailable.
        }

        double[] step = new double[pairs];
        double[] disagreement = new double[pairs];
        double sumX = 0;
        double sumY = 0;
        double path = 0;
        int disagreementCount = 0;
        for (int t = 0; t < pairs; t++) {
            double dx = phaseDx[t];
            double dy = phaseDy[t];
            if (Double.isFinite(modalDx[t])) {
                disagreement[disagreementCount++] = Math.hypot(
                        dx - modalDx[t], dy - modalDy[t]);
            }
            step[t] = Math.hypot(dx, dy);
            path += step[t];
            sumX += dx;
            sumY += dy;
        }
        double median = quantile(step, 0.5);
        double p90 = quantile(step, 0.9);
        double maximum = quantile(step, 1.0);
        double net = Math.hypot(sumX, sumY);
        double directionality = path == 0 ? 0 : net / path;
        double agreement = disagreementCount == 0 ? Double.NaN
                : quantile(Arrays.copyOf(disagreement, disagreementCount), 0.5);
        String modalCheck = disagreementCount == 0 ? "UNAVAILABLE"
                : agreement <= Math.max(0.5, 2.0 * p90) ? "AGREES" : "DISAGREES";

        String magnitude = p90 < 0.20 ? "NEAR_STATIC"
                : p90 < 0.75 ? "SUBPIXEL"
                : p90 < 2.5 ? "MODERATE" : "LARGE";
        String pattern;
        if ("NEAR_STATIC".equals(magnitude)) {
            pattern = "STATIC";
        } else if (maximum > Math.max(2.0, 3.0 * Math.max(median, 0.15))) {
            pattern = "JUMP_DOMINATED";
        } else if (directionality >= 0.75) {
            pattern = "DIRECTIONAL";
        } else if (directionality <= 0.25) {
            pattern = "OSCILLATING";
        } else {
            pattern = "WANDERING";
        }
        return new Profile(phaseDx, phaseDy, modalDx, modalDy, median, p90, maximum,
                net, path, directionality, agreement, modalCheck, pattern, magnitude);
    }

    static Residual residual(float[][] corrected, int width) {
        int minX = 0;
        int minY = 0;
        int maxX = width - 1;
        int maxY = width - 1;
        for (float[] frame : corrected) {
            int frameMinX = width;
            int frameMinY = width;
            int frameMaxX = -1;
            int frameMaxY = -1;
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    if (!Float.isFinite(frame[y * width + x])) continue;
                    frameMinX = Math.min(frameMinX, x);
                    frameMinY = Math.min(frameMinY, y);
                    frameMaxX = Math.max(frameMaxX, x);
                    frameMaxY = Math.max(frameMaxY, y);
                }
            }
            minX = Math.max(minX, frameMinX);
            minY = Math.max(minY, frameMinY);
            maxX = Math.min(maxX, frameMaxX);
            maxY = Math.min(maxY, frameMaxY);
        }
        int cropWidth = maxX - minX + 1;
        int cropHeight = maxY - minY + 1;
        if (cropWidth < 32 || cropHeight < 32) {
            return new Residual(Double.NaN, Double.NaN, Double.NaN);
        }
        float[][] finite = new float[corrected.length][];
        for (int t = 0; t < corrected.length; t++) {
            finite[t] = new float[cropWidth * cropHeight];
            double mean = 0;
            int count = 0;
            for (float value : corrected[t]) {
                if (Float.isFinite(value)) { mean += value; count++; }
            }
            float fill = count == 0 ? 0 : (float) (mean / count);
            for (int y = 0; y < cropHeight; y++) {
                for (int x = 0; x < cropWidth; x++) {
                    float value = corrected[t][(y + minY) * width + x + minX];
                    finite[t][y * cropWidth + x] = Float.isFinite(value) ? value : fill;
                }
            }
        }
        double[] step = new double[corrected.length - 1];
        for (int t = 0; t < step.length; t++) {
            double[] shift = PhaseCorrelation.shift(
                    finite[t], finite[t + 1], cropWidth, cropHeight);
            step[t] = Math.hypot(shift[0], shift[1]);
        }
        return new Residual(quantile(step, 0.5), quantile(step, 0.9), quantile(step, 1.0));
    }

    static void write(Path folder, Profile profile, NativeSeriesFrames.Recording recording)
            throws IOException {
        Files.createDirectories(folder);
        String summary = "field,value\n"
                + "meaning,observed global image movement; not known camera-drift truth\n"
                + "source_frame_count," + recording.frames.length + "\n"
                + "source_frame_start," + recording.firstFrame + "\n"
                + "source_frame_stride," + recording.stride + "\n"
                + "source_dimensions," + recording.sourceWidth + "x" + recording.sourceHeight + "\n"
                + "analysis_crop," + recording.width + "x" + recording.width + " center crop\n"
                + "categorization_method,phase correlation\n"
                + "global_modal_cross_check," + profile.modalCheck + "\n"
                + "pattern," + profile.pattern + "\n"
                + "magnitude," + profile.magnitude + "\n"
                + "category," + profile.category + "\n"
                + "median_step_px," + format(profile.medianStep) + "\n"
                + "p90_step_px," + format(profile.p90Step) + "\n"
                + "maximum_step_px," + format(profile.maximumStep) + "\n"
                + "net_movement_px," + format(profile.netMovement) + "\n"
                + "path_length_px," + format(profile.pathLength) + "\n"
                + "directionality," + format(profile.directionality) + "\n"
                + "median_phase_vs_modal_disagreement_px,"
                + format(profile.medianEstimatorDisagreement) + "\n";
        Files.write(folder.resolve("motion_profile.csv"),
                summary.getBytes(StandardCharsets.UTF_8));

        StringBuilder trace = new StringBuilder(
                "from_frame,to_frame,phase_dx_px,phase_dy_px,modal_dx_px,modal_dy_px\n");
        for (int t = 0; t < profile.phaseDx.length; t++) {
            trace.append(t + 1).append(',').append(t + 2).append(',')
                    .append(format(profile.phaseDx[t])).append(',')
                    .append(format(profile.phaseDy[t])).append(',')
                    .append(format(profile.modalDx[t])).append(',')
                    .append(format(profile.modalDy[t])).append('\n');
        }
        Files.write(folder.resolve("raw_motion_trace.csv"),
                trace.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static double quantile(double[] values, double q) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        double position = q * (copy.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return copy[lower];
        double fraction = position - lower;
        return copy[lower] * (1 - fraction) + copy[upper] * fraction;
    }

    static String format(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.6f", value) : "";
    }
}
