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
import logratio.core.Transform;
import logratio.core.Warper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adds the no-dialog descriptor result and writes the one-table comparison and tool inventory. */
public final class FinalizeComparisonArtifacts {

    private static final String METHOD = "32_real_descriptor_based_series_translation";
    private static final int DESCRIPTOR_PAIRS = 225; // all pairs separated by at most five frames

    private static final class Recording {
        final String folder;
        final String seed;
        final Benchmark.Condition condition;
        final String column;

        Recording(String folder, String seed, Benchmark.Condition condition, String column) {
            this.folder = folder;
            this.seed = seed;
            this.condition = condition;
            this.column = column;
        }
    }

    private static final List<Recording> RECORDINGS = Arrays.asList(
            new Recording("SYNTH_FLUOR_B6__CLEAN", "SYNTH_FLUOR_B6",
                    Benchmark.Condition.CLEAN, "fluorescence"),
            new Recording("VID47_D3_1_09d20h00m__GAIN_FADE", "VID47_D3_1_09d20h00m",
                    Benchmark.Condition.GAIN_FADE, "fade"),
            new Recording("VID52_C3_1_02d00h00m__CHANGE_MOVED", "VID52_C3_1_02d00h00m",
                    Benchmark.Condition.CHANGE_MOVED, "moving"));

    private static final class Row {
        String stem;
        String family;
        String label;
        double error;
        Double cpu;
        Double elapsed;
        Double p90;
        Double maximum;
        String recording;
    }

    private FinalizeComparisonArtifacts() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: FinalizeComparisonArtifacts <stacks dir> <seed dir> <control seed dir>");
            return;
        }
        Path stacks = Paths.get(args[0]);
        Path seeds = Paths.get(args[1]);
        Path control = Paths.get(args[2]);
        for (Recording recording : RECORDINGS) finalizeRecording(stacks, seeds, control, recording);
        writeSummary(stacks);
        writeInventory(stacks);
        writeRootReadme(stacks);
        System.out.println("wrote complete comparison summary and external-tool inventory");
    }

    private static void finalizeRecording(Path stacks, Path seeds, Path control,
                                          Recording recording) throws Exception {
        Path folder = stacks.resolve(recording.folder);
        Path transforms = folder.resolve(METHOD + "_transforms.csv");
        List<String> lines = Files.readAllLines(transforms, StandardCharsets.UTF_8);
        if (lines.size() != Benchmark.FRAMES + 1) {
            throw new IOException("expected " + Benchmark.FRAMES + " descriptor transforms in " + transforms);
        }
        double[] dx = new double[Benchmark.FRAMES];
        double[] dy = new double[Benchmark.FRAMES];
        double elapsed = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",");
            int frame = Integer.parseInt(fields[0]);
            dx[frame] = Double.parseDouble(fields[3]);
            dy[frame] = Double.parseDouble(fields[4]);
            elapsed = Double.parseDouble(fields[5]);
        }

        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        Benchmark.trajectory(fineX, fineY);
        double[] errors = new double[Benchmark.FRAMES];
        for (int t = 0; t < errors.length; t++) {
            double truthX = fineX[t] / (double) Benchmark.FINE;
            double truthY = fineY[t] / (double) Benchmark.FINE;
            errors[t] = Math.hypot(dx[t] - truthX, dy[t] - truthY);
        }
        double median = Benchmark.quantile(errors, 0.5);
        double p90 = Benchmark.quantile(errors, 0.9);
        double maximum = Benchmark.quantile(errors, 1.0);
        appendComparison(folder.resolve("comparison.csv"), median, p90, maximum, elapsed);
        appendReadme(folder.resolve("README.txt"), median, p90, maximum, elapsed);
        writeDiagnostic(folder, seeds, control, recording, dx, dy);
        System.out.printf("%-45s %.4f px  %.3f elapsed s%n", recording.folder, median, elapsed);
    }

    private static void appendComparison(Path path, double median, double p90,
                                         double maximum, double elapsed) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith(METHOD + ",")) out.append(line).append('\n');
        }
        out.append(METHOD).append(',').append(f(median)).append(",,,")
                .append(q("Descriptor-based series registration: translation")).append(',')
                .append(q("Difference-of-Gaussian landmark descriptors")).append(',')
                .append(q("no-dialog Groovy; all-to-all matching within five frames")).append(',')
                .append(f(p90)).append(',').append(f(maximum)).append(',')
                .append(f(elapsed)).append(',').append(f(1000 * elapsed / DESCRIPTOR_PAIRS)).append(',')
                .append(q("elapsed measured; CPU unavailable from separate Fiji process")).append('\n');
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendReadme(Path path, double median, double p90,
                                     double maximum, double elapsed) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String line = METHOD + "\t" + f(median) + "\t" + f(p90) + "\tNA\t"
                + f(elapsed) + "\tDescriptor-based series registration; translation; "
                + "no-dialog Groovy; max error " + f(maximum) + " px\n";
        if (text.contains(METHOD + "\t")) {
            text = text.replaceAll("(?m)^" + METHOD + "\\t.*$", line.trim());
        } else {
            int end = text.indexOf("END EXTERNAL FIJI METHODS");
            text = end < 0 ? text + line : text.substring(0, end) + line + text.substring(end);
        }
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeDiagnostic(Path folder, Path seeds, Path control,
                                        Recording recording, double[] dx, double[] dy)
            throws IOException {
        Path seedPath = (recording.seed.startsWith("SYNTH_") ? control : seeds)
                .resolve(recording.seed + ".tif");
        ImagePlus image = IJ.openImage(seedPath.toString());
        if (image == null) throw new IOException("could not open " + seedPath);
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        float[] fine = Benchmark.seedPlane(image);
        image.close();
        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        Benchmark.trajectory(fineX, fineY);
        int span = 0;
        for (int t = 0; t < fineX.length; t++) {
            span = Math.max(span, Math.max(Math.abs(fineX[t]), Math.abs(fineY[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int width = Math.min((sourceWidth - 2 * margin) / Benchmark.FINE,
                (sourceHeight - 2 * margin) / Benchmark.FINE);
        double sourceSd = Benchmark.standardDeviation(fine);
        float[][] clean = new float[Benchmark.FRAMES][];
        float[][] corrected = new float[Benchmark.FRAMES][];
        for (int t = 0; t < clean.length; t++) {
            clean[t] = Benchmark.frame(fine, sourceWidth, sourceHeight, width, margin,
                    fineX[t], fineY[t], Benchmark.Condition.CLEAN, t, sourceSd);
            corrected[t] = new float[width * width];
            Warper.warp(clean[t], corrected[t], width, width, Transform.translation(dx[t], dy[t]),
                    Warper.Interpolation.NONE, Float.NaN);
        }
        Path diagnostics = folder.resolve("diagnostics");
        Files.createDirectories(diagnostics);
        IJ.saveAsTiff(BenchmarkStacks.sdPanel(clean, corrected, width),
                diagnostics.resolve(METHOD + "_before_after_motion.tif").toString());
    }

    private static void writeSummary(Path stacks) throws IOException {
        Map<String, Map<String, Row>> byMethod = new LinkedHashMap<>();
        for (Recording recording : RECORDINGS) {
            List<String> lines = Files.readAllLines(
                    stacks.resolve(recording.folder).resolve("comparison.csv"), StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                List<String> fields = parseCsv(lines.get(i));
                if (fields.size() < 5) continue;
                Row row = new Row();
                row.stem = fields.get(0);
                row.error = number(fields, 1);
                row.cpu = nullable(fields, 2);
                row.label = fields.get(4);
                row.family = fields.size() > 5 && !fields.get(5).isEmpty()
                        ? fields.get(5) : internalFamily(row.stem);
                row.p90 = nullable(fields, 7);
                row.maximum = nullable(fields, 8);
                row.elapsed = nullable(fields, 9);
                row.recording = recording.column;
                String summaryKey = row.stem.startsWith("20_automatic_selector")
                        ? "20_automatic_selector" : row.stem;
                byMethod.computeIfAbsent(summaryKey, k -> new LinkedHashMap<>())
                        .put(recording.column, row);
            }
        }
        StringBuilder csv = new StringBuilder("number,family,method,submethod,selected_branch_by_recording,")
                .append("fluorescence_error_px,fluorescence_cpu_seconds,fluorescence_elapsed_seconds,")
                .append("fade_error_px,fade_cpu_seconds,fade_elapsed_seconds,")
                .append("moving_error_px,moving_cpu_seconds,moving_elapsed_seconds,")
                .append("worst_error_px,geometric_mean_error_px\n");
        for (Map.Entry<String, Map<String, Row>> entry : byMethod.entrySet()) {
            Map<String, Row> cells = entry.getValue();
            Row exemplar = cells.values().iterator().next();
            Row fluorescence = cells.get("fluorescence");
            Row fade = cells.get("fade");
            Row moving = cells.get("moving");
            if (fluorescence == null || fade == null || moving == null) {
                throw new IOException("method is not present in all recordings: " + entry.getKey());
            }
            double worst = Math.max(fluorescence.error, Math.max(fade.error, moving.error));
            double geometric = Math.cbrt(fluorescence.error * fade.error * moving.error);
            boolean automatic = entry.getKey().equals("20_automatic_selector");
            String methodLabel = automatic ? "Automatic selector (dataset-specific branch)" : exemplar.label;
            String selectedBranches = automatic
                    ? "fluorescence=" + fluorescence.label + "; fade=" + fade.label
                    + "; moving=" + moving.label : "fixed method";
            csv.append(entry.getKey().substring(0, 2)).append(',').append(q(exemplar.family)).append(',')
                    .append(q(methodLabel)).append(',').append(q(entry.getKey())).append(',')
                    .append(q(selectedBranches)).append(',')
                    .append(cell(fluorescence)).append(',').append(cell(fade)).append(',')
                    .append(cell(moving)).append(',').append(f(worst)).append(',').append(f(geometric))
                    .append('\n');
        }
        Files.write(stacks.resolve("comparison_all_methods_2026-08-13.csv"),
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String cell(Row row) {
        return f(row.error) + ',' + nullable(row.cpu) + ',' + nullable(row.elapsed);
    }

    private static String internalFamily(String stem) {
        int n = Integer.parseInt(stem.substring(0, 2));
        if (n == 1 || n == 12) return "log-ratio robust";
        if (n >= 2 && n <= 4) return "intensity selection";
        if (n >= 5 && n <= 10) return "gradient selection";
        if (n == 11) return "noise-aware selection";
        if (n == 13) return "log-ratio least squares";
        if (n == 14) return "squared difference";
        if (n == 15 || n == 16) return "Fourier correlation";
        if (n == 17 || n == 18) return "two-pass lag selection";
        if (n == 19) return "two-pass tile movement";
        if (n == 20) return "automatic selector";
        return "other";
    }

    private static void writeInventory(Path stacks) throws IOException {
        String csv = "tool,installed,benchmark_status,methods,reason_or_scope\n"
                + "StackReg 2.0.1,yes,compared,21,real TurboReg engine and native previous-frame chain\n"
                + "TurboReg 2.0.1,yes,compared,21;22,area-registration engine; chain and multi-lag\n"
                + "MultiStackReg 1.46.5,yes,alias shown,23,same TurboReg estimator and chain as StackReg\n"
                + "Image Stabilizer,yes,compared,24,Lucas-Kanade translation with rolling template\n"
                + "Fast4DReg 2.4.0,yes,compared,25;26,NanoJ cross-correlation; previous and first reference\n"
                + "NanoJ-Core,yes,used by Fast4DReg,25;26,cross-correlation engine\n"
                + "Correct 3D Drift 1.0.7,yes,compared,27;28,2D mode; standard and multi-time-scale\n"
                + "Linear Stack Alignment with SIFT 1.6.0,yes,compared,29;30,translation chain and multi-lag\n"
                + "Register Virtual Stack Slices 3.0.8,yes,alias shown,31,same SIFT translation family for this configuration\n"
                + "Descriptor-based series registration 2.1.8,yes,compared,32,no-dialog Groovy; Difference-of-Gaussian landmarks\n"
                + "Phase correlation / FFT,yes,compared,15;27;28,internal Fourier estimator plus installed Correct 3D Drift\n"
                + "Cross-correlation / FFT,yes,compared,16;25;26,internal estimator plus installed Fast4DReg\n"
                + "bUnwarpJ 2.6.13,yes,excluded,,non-rigid deformation changes the benchmark geometry\n"
                + "Elastic Stack Alignment 1.6.0,yes,excluded,,non-rigid deformation changes the benchmark geometry\n"
                + "Fijiyama 4.4 snapshot,yes,excluded,,3D volume and multimodal project workflow; not a rigid 2D movie method\n"
                + "ImageJ-ITK / SimpleITK,yes,backend only,,registration backend used by 3D workflows; no like-for-like 2D stack command\n"
                + "ElastixWrapper 0.5.0,yes,backend only,,backend for volume workflows; no like-for-like 2D stack command\n"
                + "Fast FFT (2D/3D),yes,not registration,,displays a Fourier transform; it does not estimate or correct drift\n"
                + "MorphoLibJ 1.6.5,yes,not registration,,morphology toolkit installed as a dependency; not a drift method\n";
        Files.write(stacks.resolve("external_tool_inventory_2026-08-13.csv"),
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeRootReadme(Path stacks) throws IOException {
        String text = "DRIFT-CORRECTION TIFF COMPARISON\n"
                + "Generated 2026-08-13\n\n"
                + "LAYOUT\n"
                + "Each recording has its own folder. Inside it, 00_input.tif is the uncorrected "
                + "48-frame movie and files 01 through 32 are corrected by the clearly named method.\n"
                + "The diagnostics folder contains one before/after motion panel per method.\n"
                + "comparison.csv contains that recording's measured error and runtime.\n\n"
                + "RECORDINGS\n"
                + "SYNTH_FLUOR_B6__CLEAN                 fluorescence, no intensity change\n"
                + "VID47_D3_1_09d20h00m__GAIN_FADE      phase contrast with intensity fade\n"
                + "VID52_C3_1_02d00h00m__CHANGE_MOVED   phase contrast with moving content\n\n"
                + "RESULT\n"
                + "Method 20, the automatic selector, is the overall accuracy winner across all three "
                + "recordings. It chooses a named existing correction branch for each recording; it is "
                + "not a separate registration algorithm. See selected_branch_by_recording in "
                + "comparison_all_methods_2026-08-13.csv.\n\n"
                + "FILES\n"
                + "comparison_all_methods_2026-08-13.csv  all 32 methods, submethods, errors and times\n"
                + "external_tool_inventory_2026-08-13.csv installed tools, compared aliases and exclusions\n\n"
                + "TIMING\n"
                + "CPU seconds measures processor time inside the Java benchmark. Elapsed seconds measures "
                + "wall-clock time. Method 32 ran as a separate headless Fiji process, so only elapsed time "
                + "is available. Alias methods 23 and 31 reuse their identical engine result and do not claim "
                + "an independent runtime.\n";
        Files.write(stacks.resolve("README.txt"), text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(field.toString());
                field.setLength(0);
            } else field.append(c);
        }
        out.add(field.toString());
        return out;
    }

    private static double number(List<String> fields, int index) {
        return Double.parseDouble(fields.get(index));
    }

    private static Double nullable(List<String> fields, int index) {
        if (index >= fields.size() || fields.get(index).isEmpty()) return null;
        return Double.parseDouble(fields.get(index));
    }

    private static String nullable(Double value) {
        return value == null ? "" : f(value);
    }

    private static String f(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static String q(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
