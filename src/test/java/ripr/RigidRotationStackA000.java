/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternResult;
import ripr.api.MotionType;
import ripr.api.SelectionMode;
import ripr.core.PairAligner;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exact full-workflow A000 adapter for the supplied four-channel OME-TIFF. */
public final class RigidRotationStackA000 {
    private RigidRotationStackA000() { }

    static final class Event {
        final long elapsedMs;
        final String kind;
        final String phase;
        final int done;
        final int total;
        final int workers;
        final int index;

        Event(long elapsedMs, String kind, String phase, int done, int total,
              int workers, int index) {
            this.elapsedMs = elapsedMs;
            this.kind = kind;
            this.phase = phase;
            this.done = done;
            this.total = total;
            this.workers = workers;
            this.index = index;
        }
    }

    /** Thread-safe recorder for the same lifecycle callbacks used by the Fiji progress window. */
    static final class EventProgress implements PairScheduler.Progress {
        private final long startNanos = System.nanoTime();
        private final List<Event> events = new ArrayList<>();
        private String phase = "Preparing registration";
        private long firstTaskMs = -1;
        private long firstCompletionMs = -1;

        private long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }

        private synchronized void add(String kind, String name, int done, int total,
                                      int workers, int index) {
            long elapsed = elapsedMs();
            if ("task_started".equals(kind) && firstTaskMs < 0) firstTaskMs = elapsed;
            if ("update".equals(kind) && firstCompletionMs < 0) firstCompletionMs = elapsed;
            events.add(new Event(elapsed, kind, name, done, total, workers, index));
        }

        @Override public synchronized void begin(int total, int workers) {
            phase = "Aligning frame pairs";
            add("begin", phase, 0, total, workers, -1);
            System.out.println("A000 stack: aligning " + total + " frame pairs with "
                    + workers + " worker(s)");
        }

        @Override public synchronized void taskStarted(int index, int total) {
            add("task_started", phase, -1, total, -1, index);
        }

        @Override public synchronized void update(int done, int total) {
            add("update", phase, done, total, -1, -1);
            if (done == 1 || done == total || done % 25 == 0) {
                System.out.println("A000 stack: frame pairs " + done + "/" + total);
            }
        }

        @Override public synchronized void phase(String name, int done, int total) {
            phase = name;
            add("phase", name, done, total, -1, -1);
            if (done == 0 || done == total || done % 50 == 0) {
                System.out.println("A000 stack: " + name + " " + done + "/" + total);
            }
        }

        synchronized long firstTaskMs() { return firstTaskMs; }
        synchronized long firstCompletionMs() { return firstCompletionMs; }
        synchronized int size() { return events.size(); }

        synchronized void write(Path path) throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("elapsed_ms,event,phase,done,total,workers,index");
                out.newLine();
                for (Event event : events) {
                    out.write(event.elapsedMs + "," + csv(event.kind) + "," + csv(event.phase)
                            + "," + event.done + "," + event.total + "," + event.workers
                            + "," + event.index);
                    out.newLine();
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException("usage: RigidRotationStackA000 "
                    + "<input.tif> <metrics.csv> <pairs.csv> <events.csv> <channels.csv> "
                    + "<channel> <image-type> <lag-aware-warm-starts>");
        }
        System.setProperty("java.awt.headless", "true");
        Path input = Paths.get(args[0]);
        Path metricsPath = Paths.get(args[1]);
        Path pairsPath = Paths.get(args[2]);
        Path eventsPath = Paths.get(args[3]);
        Path channelsPath = Paths.get(args[4]);
        int selectedChannel = Integer.parseInt(args[5]);
        ImageType selectedType = ImageType.from(args[6]);
        boolean lagAwareWarmStarts = Boolean.parseBoolean(args[7]);
        Files.createDirectories(metricsPath.toAbsolutePath().getParent());

        ImagePlus image = IJ.openImage(input.toString());
        if (image == null) throw new IllegalArgumentException("ImageJ could not open " + input);
        RelativeIntensityPatternResult result = null;
        try {
            normalizeDimensions(image);
            long rankStart = System.nanoTime();
            StackFrames.ChannelQuality[] qualities = StackFrames.rankChannels(image);
            long rankMs = (System.nanoTime() - rankStart) / 1_000_000L;
            writeChannels(channelsPath, qualities, rankMs);

            RelativeIntensityPatternParameters requested = RelativeIntensityPatternParameters.builder()
                    .recommendation(selectedType, MotionType.SUBPIXEL_RANDOM_WALK)
                    .selectionMode(SelectionMode.AUTOMATIC)
                    .channel(selectedChannel)
                    .slice(StackFrames.PROJECT_Z)
                    .fitRotation(true)
                    .maxRotationDegrees(10.0)
                    .lagAwareWarmStarts(lagAwareWarmStarts)
                    .build();
            EventProgress progress = new EventProgress();
            long start = System.nanoTime();
            result = RelativeIntensityPatternRegistration.register(image, requested, progress,
                    PairScheduler.Cancellation.NEVER);
            long runtimeMs = (System.nanoTime() - start) / 1_000_000L;
            progress.write(eventsPath);
            writePairs(pairsPath, result.registration());
            writeMetrics(metricsPath, input, image, requested, result, qualities, rankMs,
                    runtimeMs, progress);
        } finally {
            if (result != null) result.close();
            image.close();
        }
    }

    static void normalizeDimensions(ImagePlus image) {
        if (image.getWidth() != 512 || image.getHeight() != 512
                || image.getStackSize() != 312) {
            throw new IllegalArgumentException("expected 512x512x312 pages, got "
                    + image.getWidth() + "x" + image.getHeight() + "x"
                    + image.getStackSize());
        }
        if (image.getNChannels() != 4 || image.getNSlices() != 1 || image.getNFrames() != 78) {
            image.setDimensions(4, 1, 78);
        }
        image.setOpenAsHyperStack(true);
        if (image.getNChannels() != 4 || image.getNSlices() != 1 || image.getNFrames() != 78) {
            throw new IllegalArgumentException("could not assign OME dimensions C=4,Z=1,T=78");
        }
    }

    private static void writeChannels(Path path, StackFrames.ChannelQuality[] qualities,
                                      long runtimeMs) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("rank,channel,localisability,frame_correlation,poor,ranking_runtime_ms");
            out.newLine();
            for (int rank = 0; rank < qualities.length; rank++) {
                StackFrames.ChannelQuality q = qualities[rank];
                out.write((rank + 1) + "," + q.channel + "," + f(q.localisability) + ","
                        + f(q.frameCorrelation) + "," + q.poor() + "," + runtimeMs);
                out.newLine();
            }
        }
    }

    private static void writePairs(Path path, Registration.Result registration) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("pair,from,to,lag,dx,dy,theta_degrees,status,iterations,valid_fraction,"
                    + "residual_before,residual_after,rotation_tested,rotation_accepted,"
                    + "rotation_residual_gain");
            out.newLine();
            for (int i = 0; i < registration.pairs.size(); i++) {
                Registration.PairResult pair = registration.pairs.get(i);
                PairAligner.Fit fit = pair.fit;
                Transform transform = fit == null ? Transform.IDENTITY : fit.transform;
                out.write(i + "," + pair.from + "," + pair.to + "," + pair.lag() + ","
                        + f(transform.dx) + "," + f(transform.dy) + ","
                        + f(transform.thetaDegrees()) + ","
                        + (fit == null ? "NULL" : fit.status.name()) + ","
                        + (fit == null ? -1 : fit.iterations) + ","
                        + f(fit == null ? Double.NaN : fit.validFraction) + ","
                        + f(fit == null ? Double.NaN : fit.residualBefore) + ","
                        + f(fit == null ? Double.NaN : fit.residualAfter) + ","
                        + (fit != null && fit.rotationEvidence != null) + ","
                        + (fit != null && fit.rotationEvidence != null
                            && fit.rotationEvidence.accepted) + ","
                        + f(fit == null || fit.rotationEvidence == null ? Double.NaN
                            : fit.rotationEvidence.residualGain));
                out.newLine();
            }
        }
    }

    private static void writeMetrics(Path path, Path input, ImagePlus image,
                                     RelativeIntensityPatternParameters requested, RelativeIntensityPatternResult result,
                                     StackFrames.ChannelQuality[] qualities, long rankMs,
                                     long runtimeMs, EventProgress progress) throws IOException {
        Registration.Result registration = result.registration();
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("case_id", "user_stack_full");
        metrics.put("input_path", input.toAbsolutePath().toString());
        metrics.put("width", Integer.toString(image.getWidth()));
        metrics.put("height", Integer.toString(image.getHeight()));
        metrics.put("channels", Integer.toString(image.getNChannels()));
        metrics.put("slices", Integer.toString(image.getNSlices()));
        metrics.put("frames", Integer.toString(image.getNFrames()));
        metrics.put("requested_image_type", requested.imageType.name());
        metrics.put("requested_motion_type", requested.motionType.name());
        metrics.put("requested_selection_mode", requested.selectionMode.name());
        metrics.put("requested_channel", Integer.toString(requested.channel));
        metrics.put("requested_fit_rotation", Boolean.toString(requested.fitRotation));
        metrics.put("requested_max_rotation_degrees", f(requested.maxRotationDegrees));
        metrics.put("requested_incremental_rotation",
                Boolean.toString(requested.incrementalRotation));
        metrics.put("requested_minimum_rotation_residual_gain",
                f(requested.minimumRotationResidualGain));
        metrics.put("requested_lag_aware_warm_starts",
                Boolean.toString(requested.lagAwareWarmStarts));
        metrics.put("resolved_recipe", result.resolvedRecipe().id());
        metrics.put("provenance", result.provenance());
        AutomaticRegistrationSelector.Result selection = result.automaticSelection();
        metrics.put("automatic_fallback", Boolean.toString(selection != null && selection.fallback));
        metrics.put("automatic_decline_reason", selection == null ? "" : selection.declineReason);
        metrics.put("best_ranked_channel", Integer.toString(qualities[0].channel));
        metrics.put("selected_channel_rank", Integer.toString(rankOf(qualities, requested.channel)));
        metrics.put("selected_channel_localisability", f(qualityOf(qualities, requested.channel).localisability));
        metrics.put("selected_channel_frame_correlation", f(qualityOf(qualities, requested.channel).frameCorrelation));
        metrics.put("channel_ranking_runtime_ms", Long.toString(rankMs));
        metrics.put("registration_runtime_ms", Long.toString(runtimeMs));
        metrics.put("first_pair_task_ms", Long.toString(progress.firstTaskMs()));
        metrics.put("first_pair_completion_ms", Long.toString(progress.firstCompletionMs()));
        metrics.put("progress_events", Integer.toString(progress.size()));
        metrics.put("pairs", Integer.toString(registration.pairs.size()));
        metrics.put("rotation_accepted_pairs",
                Integer.toString(registration.rotationAcceptedPairs()));
        metrics.put("rotation_declined_pairs",
                Integer.toString(registration.rotationDeclinedPairs()));
        metrics.put("workers", Integer.toString(registration.workers));
        metrics.put("pyramid_levels", Integer.toString(registration.levels));
        metrics.put("pyramids_built", Long.toString(registration.pyramidsBuilt));
        metrics.put("pyramid_cache_hits", Long.toString(registration.pyramidCacheHits));
        metrics.put("median_residual_before", f(registration.medianResidualBefore()));
        metrics.put("median_residual_after", f(registration.medianResidualAfter()));
        metrics.put("repairs", Integer.toString(countNotNull(registration.repairs)));
        metrics.put("warnings", Integer.toString(registration.warnings.size()));
        for (Registration.Warning.Kind kind : Registration.Warning.Kind.values()) {
            metrics.put("warning_" + kind.name().toLowerCase(Locale.ROOT),
                    Integer.toString(countWarning(registration, kind)));
        }
        for (PairAligner.Status status : PairAligner.Status.values()) {
            metrics.put("pair_status_" + status.name().toLowerCase(Locale.ROOT),
                    Integer.toString(countStatus(registration, status)));
        }
        double[] pairAngles = new double[registration.pairs.size()];
        for (int i = 0; i < pairAngles.length; i++) {
            Registration.PairResult pair = registration.pairs.get(i);
            pairAngles[i] = pair.fit == null ? Double.NaN
                    : Math.abs(pair.fit.transform.thetaDegrees());
        }
        metrics.put("pair_abs_angle_p50_degrees", f(percentileFinite(pairAngles, 0.50)));
        metrics.put("pair_abs_angle_p95_degrees", f(percentileFinite(pairAngles, 0.95)));
        metrics.put("pair_abs_angle_max_degrees", f(percentileFinite(pairAngles, 1.00)));
        double maxCumulative = 0;
        for (Transform transform : registration.cumulative) {
            maxCumulative = Math.max(maxCumulative, Math.abs(transform.thetaDegrees()));
        }
        metrics.put("cumulative_abs_angle_max_degrees", f(maxCumulative));
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("metric,value");
            out.newLine();
            for (Map.Entry<String, String> metric : metrics.entrySet()) {
                out.write(csv(metric.getKey()) + "," + csv(metric.getValue()));
                out.newLine();
            }
        }
    }

    private static int rankOf(StackFrames.ChannelQuality[] qualities, int channel) {
        for (int i = 0; i < qualities.length; i++) if (qualities[i].channel == channel) return i + 1;
        return -1;
    }

    private static StackFrames.ChannelQuality qualityOf(StackFrames.ChannelQuality[] qualities,
                                                         int channel) {
        for (StackFrames.ChannelQuality quality : qualities) {
            if (quality.channel == channel) return quality;
        }
        throw new IllegalArgumentException("missing channel " + channel);
    }

    private static int countNotNull(Object[] values) {
        int count = 0;
        for (Object value : values) if (value != null) count++;
        return count;
    }

    private static int countWarning(Registration.Result result, Registration.Warning.Kind kind) {
        int count = 0;
        for (Registration.Warning warning : result.warnings) if (warning.kind == kind) count++;
        return count;
    }

    private static int countStatus(Registration.Result result, PairAligner.Status status) {
        int count = 0;
        for (Registration.PairResult pair : result.pairs) {
            if (pair.fit != null && pair.fit.status == status) count++;
        }
        return count;
    }

    static double percentileFinite(double[] values, double fraction) {
        double[] finite = new double[values.length];
        int count = 0;
        for (double value : values) if (Double.isFinite(value)) finite[count++] = value;
        if (count == 0) return Double.NaN;
        Arrays.sort(finite, 0, count);
        double position = (count - 1) * fraction;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return finite[lower];
        double weight = position - lower;
        return finite[lower] * (1 - weight) + finite[upper] * weight;
    }

    private static String csv(String value) {
        if (value == null) value = "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }
}
