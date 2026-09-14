/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ripr.core.PairScheduler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Public, no-dialog folder batch API. Stacks are opened, registered, saved and closed one at a time. */
public final class RelativeIntensityPatternBatch {
    public interface ProgressListener {
        void update(RelativeIntensityPatternBatchStatus status);
        ProgressListener NONE = status -> { };
    }

    private RelativeIntensityPatternBatch() { }

    /** Finds TIFF stacks in stable relative-path order, excluding the output tree. */
    public static List<File> discover(RelativeIntensityPatternBatchParameters parameters) {
        final Path input = canonical(parameters.inputDirectory);
        final Path output = canonical(parameters.outputDirectory);
        final boolean separateOutputTree = !input.equals(output);
        List<File> files = new ArrayList<>();
        int depth = parameters.recursive ? Integer.MAX_VALUE : 1;
        try (java.util.stream.Stream<Path> paths = Files.walk(input, depth)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !separateOutputTree
                            || !path.toAbsolutePath().normalize().startsWith(output))
                    .filter(path -> isInputTiff(path.getFileName().toString()))
                    .forEach(path -> files.add(path.toFile()));
        } catch (IOException error) {
            throw new IllegalArgumentException("could not scan input directory: " + error.getMessage(), error);
        }
        Collections.sort(files, Comparator.comparing(file -> relativeKey(input, file.toPath()),
                String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    public static RelativeIntensityPatternBatchResult run(RelativeIntensityPatternBatchParameters parameters) {
        return run(parameters, ProgressListener.NONE, PairScheduler.Cancellation.NEVER);
    }

    /** Runs the batch synchronously. A graphical caller should call this method on a worker thread. */
    public static RelativeIntensityPatternBatchResult run(RelativeIntensityPatternBatchParameters parameters,
                                          ProgressListener listener,
                                          PairScheduler.Cancellation cancellation) {
        if (parameters == null) throw new IllegalArgumentException("batch parameters are null");
        ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
        PairScheduler.Cancellation cancel = cancellation == null
                ? PairScheduler.Cancellation.NEVER : cancellation;
        List<File> inputs = discover(parameters);
        Path inputRoot = canonical(parameters.inputDirectory);
        Path outputRoot = canonical(parameters.outputDirectory);
        try { Files.createDirectories(outputRoot); }
        catch (IOException error) {
            throw new IllegalArgumentException("could not create output directory: " + error.getMessage(), error);
        }

        // Automatic mode registers each stack twice: a neutral provisional pass to gather evidence,
        // then the chosen recipe. Only the second reports pair progress, so the within-file fraction
        // is shifted and halved rather than letting the estimate claim a stack is half done twice.
        final boolean twoPasses =
                parameters.registration.selectionMode == SelectionMode.AUTOMATIC
                || parameters.registration.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY;
        final double passShare = twoPasses ? 0.5 : 1.0;
        final double passOffset = 1.0 - passShare;

        File reportFile = outputRoot.resolve("log_ratio_batch_report.csv").toFile();
        long batchStart = System.currentTimeMillis();
        int processed = 0;
        int skipped = 0;
        int errors = 0;
        int completed = 0;
        int timed = 0;
        long timedMillis = 0;
        boolean cancelled = false;
        int actionableTotal = 0;
        for (File input : inputs) {
            Path output = outputPath(inputRoot, outputRoot, input.toPath());
            if (parameters.overwrite || !Files.exists(output)) actionableTotal++;
        }

        try (BufferedWriter report = Files.newBufferedWriter(reportFile.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Every processed stack states the complete recipe it actually used, so an automatic
            // batch can be replayed exactly in Manual mode one stack at a time.
            report.write("input_file,output_file,status,elapsed_seconds,median_residual_before,"
                    + "median_residual_after,selection_mode,resolved_recipe_id,resolved_recipe,"
                    + "rotation_recipe_id,rotation_recipe,rotation_proposal,"
                    + "rotation_selector_step90,"
                    + "fit_rotation,max_rotation_degrees,minimum_rotation_residual_gain,"
                    + "rotation_accepted_pairs,rotation_declined_pairs,"
                    + "max_recovered_rotation_degrees,"
                    + "selection_details,rotation_selection_details,error,"
                    + "rotation_mode,rotation_events,rotation_event_window,rotation_event_results");
            report.newLine();

            for (File input : inputs) {
                if (cancel.cancelled()) { cancelled = true; break; }
                Path output = outputPath(inputRoot, outputRoot, input.toPath());
                if (Files.exists(output) && !parameters.overwrite) {
                    skipped++;
                    completed++;
                    writeRow(report, input, output.toFile(), "skipped_existing", 0,
                            Double.NaN, Double.NaN, null, "");
                    emit(progress, completed, inputs.size(), 0, 0, input, batchStart,
                            remaining(timedMillis, timed, actionableTotal - timed, 0));
                    continue;
                }

                final int filesDone = completed;
                final long completedTimedMillis = timedMillis;
                final int completedTimed = timed;
                final int totalTimedFiles = actionableTotal;
                long fileStart = System.currentTimeMillis();
                ImagePlus source = null;
                RelativeIntensityPatternResult result = null;
                Path temporary = temporaryPath(output);
                try {
                    emit(progress, completed, inputs.size(), 0, 0, input, batchStart,
                            remaining(timedMillis, timed, actionableTotal - timed, 0));
                    Files.createDirectories(output.getParent());
                    source = openImage(input);
                    if (source == null) throw new IllegalArgumentException("ImageJ could not open the file");
                    final File current = input;
                    result = RelativeIntensityPatternRegistration.register(source, parameters.registration,
                            (done, total) -> emit(progress, filesDone, inputs.size(), done, total, current,
                                    batchStart, remaining(completedTimedMillis, completedTimed,
                                            totalTimedFiles - completedTimed,
                                            total > 0 ? passOffset + passShare * done / total : 0)),
                            cancel);
                    if (cancel.cancelled()) throw new CancellationException("cancelled");
                    saveTiff(result.correctedImage(), temporary);
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                    long elapsed = System.currentTimeMillis() - fileStart;
                    processed++;
                    timed++;
                    timedMillis += elapsed;
                    writeRow(report, input, output.toFile(), "processed", elapsed,
                            result.medianResidualBefore(), result.medianResidualAfter(),
                            result, "");
                } catch (CancellationException stop) {
                    cancelled = true;
                    writeRow(report, input, output.toFile(), "cancelled",
                            System.currentTimeMillis() - fileStart, Double.NaN, Double.NaN,
                            null, "cancelled by user");
                } catch (Exception error) {
                    long elapsed = System.currentTimeMillis() - fileStart;
                    errors++;
                    timed++;
                    timedMillis += elapsed;
                    writeRow(report, input, output.toFile(), "error", elapsed,
                            Double.NaN, Double.NaN, null, rootMessage(error));
                } finally {
                    try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
                    if (result != null) {
                        result.correctedImage().changes = false;
                        result.close();
                    }
                    if (source != null) { source.changes = false; source.close(); }
                }
                if (cancelled) break;
                completed++;
                emit(progress, completed, inputs.size(), 0, 0, input, batchStart,
                        remaining(timedMillis, timed, actionableTotal - timed, 0));
                report.flush();
            }
        } catch (IOException error) {
            throw new IllegalStateException("could not write batch report: " + error.getMessage(), error);
        }

        long elapsed = System.currentTimeMillis() - batchStart;
        emit(progress, completed, inputs.size(), 0, 0, null, batchStart, cancelled ? -1 : 0);
        return new RelativeIntensityPatternBatchResult(inputs.size(), processed, skipped, errors, cancelled,
                elapsed, parameters.outputDirectory, reportFile);
    }

    static long remaining(long timedMillis, int timedFiles, int remainingFiles, double currentFraction) {
        if (remainingFiles <= 0) return 0;
        if (timedFiles <= 0) return -1;
        double average = timedMillis / (double) timedFiles;
        double units = Math.max(0, remainingFiles - Math.max(0, Math.min(1, currentFraction)));
        return Math.max(0, Math.round(average * units));
    }

    private static void emit(ProgressListener listener, int completed, int total, int pair, int pairs,
                             File file, long start, long estimate) {
        listener.update(new RelativeIntensityPatternBatchStatus(completed, total, pair, pairs, file,
                System.currentTimeMillis() - start, estimate));
    }

    private static void saveTiff(ImagePlus image, Path target) throws IOException {
        boolean saved = image.getStackSize() > 1
                ? new FileSaver(image).saveAsTiffStack(target.toString())
                : new FileSaver(image).saveAsTiff(target.toString());
        if (!saved || !Files.isRegularFile(target)) throw new IOException("ImageJ could not save the corrected TIFF");
    }

    private static ImagePlus openImage(File file) throws Exception {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tif") || lower.endsWith(".ome.tiff")) {
            try {
                Class<?> bioFormats = Class.forName("loci.plugins.BF");
                Object opened = bioFormats.getMethod("openImagePlus", String.class)
                        .invoke(null, file.getAbsolutePath());
                ImagePlus[] series = (ImagePlus[]) opened;
                if (series.length == 1) return series[0];
                for (ImagePlus image : series) { image.changes = false; image.close(); }
                if (series.length > 1) {
                    throw new IllegalArgumentException("OME-TIFF contains " + series.length
                            + " image series; export each series as a separate stack");
                }
            } catch (ClassNotFoundException missingBioFormats) {
                // A simple OME-named TIFF may still be readable by ImageJ itself.
            } catch (LinkageError unavailableBioFormats) {
                // Treat an incomplete optional reader exactly like an absent one.
            } catch (java.lang.reflect.InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw wrapped;
            }
        }
        return IJ.openImage(file.getAbsolutePath());
    }

    private static Path outputPath(Path inputRoot, Path outputRoot, Path input) {
        Path relative = inputRoot.relativize(input.toAbsolutePath().normalize());
        Path parent = relative.getParent();
        String name = outputName(relative.getFileName().toString());
        return parent == null ? outputRoot.resolve(name) : outputRoot.resolve(parent).resolve(name);
    }

    static String outputName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int remove = lower.endsWith(".tiff") ? 5 : 4;
        return name.substring(0, name.length() - remove) + "_registered.tif";
    }

    private static Path temporaryPath(Path output) {
        return output.resolveSibling(output.getFileName().toString() + ".part.tif");
    }

    private static boolean isInputTiff(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".tif") || lower.endsWith(".tiff"))
                && !lower.endsWith("_registered.tif") && !lower.endsWith(".part.tif");
    }

    private static Path canonical(File directory) {
        try { return directory.getCanonicalFile().toPath(); }
        catch (IOException error) { return directory.toPath().toAbsolutePath().normalize(); }
    }

    private static String relativeKey(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString();
    }

    private static void writeRow(BufferedWriter writer, File input, File output, String status,
                                 long elapsedMillis, double before, double after,
                                 RelativeIntensityPatternResult result, String error)
            throws IOException {
        RegistrationRecipe recipe = result == null ? null : result.resolvedRecipe();
        AutomaticRegistrationSelector.Result selection =
                result == null ? null : result.automaticSelection();
        AutomaticRotationSelector.Result rotation =
                result == null ? null : result.automaticRotationSelection();
        writer.write(csv(input.getAbsolutePath())); writer.write(',');
        writer.write(csv(output.getAbsolutePath())); writer.write(',');
        writer.write(status); writer.write(',');
        writer.write(String.format(Locale.ROOT, "%.3f", elapsedMillis / 1000.0)); writer.write(',');
        writer.write(number(before)); writer.write(',');
        writer.write(number(after)); writer.write(',');
        writer.write(result == null ? "" : result.parameters().selectionMode.macroValue());
        writer.write(',');
        writer.write(csv(recipe == null ? "" : recipe.id())); writer.write(',');
        writer.write(csv(recipe == null ? "" : recipe.describe())); writer.write(',');
        writer.write(csv(rotation == null ? "" : result.parameters().rotationRecipeId));
        writer.write(',');
        writer.write(csv(rotation == null ? "" : rotation.recipe.describe())); writer.write(',');
        writer.write(rotation == null ? "" : rotation.globalProposalComparison
                ? "best_of_translation_seeded_and_global" : "translation_seeded");
        writer.write(',');
        writer.write(rotation == null ? "" : number(rotation.provisionalStep90)); writer.write(',');
        writer.write(result == null ? "" : Boolean.toString(result.parameters().fitRotation));
        writer.write(',');
        writer.write(result == null ? "" : number(result.parameters().maxRotationDegrees));
        writer.write(',');
        writer.write(result == null ? ""
                : number(result.parameters().minimumRotationResidualGain)); writer.write(',');
        writer.write(result == null ? ""
                : Integer.toString(result.registration().rotationAcceptedPairs()));
        writer.write(',');
        writer.write(result == null ? ""
                : Integer.toString(result.registration().rotationDeclinedPairs()));
        writer.write(',');
        writer.write(result == null ? "" : number(maxRecoveredAngle(result))); writer.write(',');
        writer.write(csv(selection == null ? "" : selection.explanation())); writer.write(',');
        writer.write(csv(rotation == null ? "" : rotation.explanation())); writer.write(',');
        writer.write(csv(error)); writer.write(',');
        writer.write(result == null ? "" : result.parameters().rotationMode.macroValue());
        writer.write(',');
        writer.write(csv(result == null ? "" : integers(result.parameters().rotationEventFrames())));
        writer.write(',');
        writer.write(result == null ? "" : Integer.toString(result.parameters().rotationEventWindow));
        writer.write(',');
        writer.write(csv(result == null ? "" : eventResults(result)));
        writer.newLine();
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "";
    }

    private static double maxRecoveredAngle(RelativeIntensityPatternResult result) {
        double max = 0;
        for (ripr.core.Transform transform : result.registration().cumulative) {
            max = Math.max(max, Math.abs(Math.toDegrees(transform.theta)));
        }
        return max;
    }

    private static String integers(int[] values) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) text.append(',');
            text.append(values[index]);
        }
        return text.toString();
    }

    private static String eventResults(RelativeIntensityPatternResult result) {
        if (result.registration().eventRotations == null) return "";
        StringBuilder text = new StringBuilder();
        for (ripr.core.RotationEventResult.Event event
                : result.registration().eventRotations.events) {
            if (text.length() > 0) text.append(';');
            text.append(event.publicFrame()).append('|')
                    .append(number(Math.toDegrees(event.deltaTheta))).append('|')
                    .append(number(Math.toDegrees(event.cumulativeTheta))).append('|')
                    .append(event.candidatePairs).append('|').append(event.usablePairs).append('|')
                    .append(event.inlierPairs).append('|')
                    .append(number(Math.toDegrees(event.circularMad))).append('|')
                    .append(event.status);
        }
        return text.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return '"' + safe + '"';
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
