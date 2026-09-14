/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.Macro;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternBatch;
import ripr.api.RelativeIntensityPatternBatchParameters;
import ripr.api.RelativeIntensityPatternBatchResult;
import ripr.api.RelativeIntensityPatternBatchStatus;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.MotionType;
import ripr.api.SelectionMode;
import ripr.core.Warper;
import ripr.core.RotationMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fiji menu, macro and headless entry point for folder batches. */
public final class RelativeIntensityPatternRegistrationBatchPlugin implements PlugIn {
    public static final String COMMAND = "Relative-Intensity Pattern Registration Batch...";
    private static final String INPUT_PREF = "ripr.batch.input";
    private static final String OUTPUT_PREF = "ripr.batch.output";

    @Override
    public void run(String arg) {
        String macro = Macro.getOptions();
        boolean headless = GraphicsEnvironment.isHeadless();
        try {
            RelativeIntensityPatternBatchParameters parameters;
            if (macro != null || headless) {
                parameters = RelativeIntensityPatternBatchMacroOptionsParser.parse(macro == null ? "" : macro);
            } else {
                parameters = dialog();
                if (parameters == null) return;
                if (Recorder.record) {
                    Recorder.recordString("run(\"" + COMMAND + "\", \""
                            + RelativeIntensityPatternBatchMacroOptionsParser.record(parameters) + "\");\n");
                }
            }
            int count = RelativeIntensityPatternBatch.discover(parameters).size();
            if (count == 0) throw new IllegalArgumentException("no TIFF stacks were found in the input folder");
            if (headless || macro != null) {
                finish(RelativeIntensityPatternBatch.run(parameters, RelativeIntensityPatternRegistrationBatchPlugin::logStatus,
                        ripr.core.PairScheduler.Cancellation.NEVER));
            } else {
                start(parameters, count);
            }
        } catch (RuntimeException error) {
            if (headless || macro != null) {
                IJ.log("Relative-Intensity Pattern Registration Batch failed: " + rootMessage(error));
                throw error;
            }
            IJ.error("Relative-Intensity Pattern Registration Batch", rootMessage(error));
        }
    }

    private static RelativeIntensityPatternBatchParameters dialog() {
        String home = System.getProperty("user.home", ".");
        GenericDialog dialog = new GenericDialog("Relative-Intensity Pattern Registration Batch");
        dialog.addMessage("Apply one registration setup to every TIFF stack in a folder.\n"
                + "Stacks are processed one at a time and keep their subfolder layout.");
        dialog.addDirectoryField("Input folder", Prefs.get(INPUT_PREF, home));
        dialog.addDirectoryField("Output folder", Prefs.get(OUTPUT_PREF, new File(home, "registered").getPath()));
        dialog.addCheckbox("Include subfolders", true);
        dialog.addCheckbox("Overwrite existing corrected stacks", false);
        dialog.addMessage("Shared registration setup");
        dialog.addChoice("Image type", labels(ImageType.values()), ImageType.PHASE_CONTRAST.label());
        dialog.addChoice("Motion type", labels(MotionType.values()), MotionType.SUBPIXEL_RANDOM_WALK.label());
        dialog.addChoice("Settings source", labels(SelectionMode.values()),
                SelectionMode.AUTOMATIC.label());
        dialog.addMessage("Automatic applies the installed, validated fixed recipe for the chosen\n"
                + "image type to each stack. Image-and-motion preset uses the older recipe for\n"
                + "both chosen types. Longitudinal maximum accuracy adds whole-recording pulse and\n"
                + "isolated-jump protection. Manual replays one explicit recipe over every stack.");
        dialog.addCheckbox("Review and edit all settings before starting", false);
        dialog.addNumericField("Channel used to estimate movement", 1, 0);
        dialog.addNumericField("Estimation scale (0-1; output stays full size)", 1.0, 2);
        dialog.addNumericField("Z slice (0 = maximum projection)", 0, 0);
        dialog.addChoice("Rotation handling", labels(RotationMode.values()), RotationMode.OFF.label());
        dialog.addStringField("First frames after remounting (1-based)", "", 18);
        dialog.addNumericField("Frames used on each side of an event", 3, 0);
        dialog.addChoice("Interpolation", names(Warper.Interpolation.values()), Warper.Interpolation.NONE.name());
        dialog.addCheckbox("Crop to common valid field", true);
        dialog.setOKLabel("Start batch");
        dialog.showDialog();
        if (dialog.wasCanceled()) return null;

        File input = new File(dialog.getNextString().trim());
        File output = new File(dialog.getNextString().trim());
        boolean recursive = dialog.getNextBoolean();
        boolean overwrite = dialog.getNextBoolean();
        ImageType imageType = ImageType.values()[dialog.getNextChoiceIndex()];
        MotionType motionType = MotionType.values()[dialog.getNextChoiceIndex()];
        SelectionMode mode = SelectionMode.values()[dialog.getNextChoiceIndex()];
        boolean advanced = dialog.getNextBoolean();
        int channel = integer(dialog.getNextNumber(), "channel");
        double estimationScale = dialog.getNextNumber();
        int slice = integer(dialog.getNextNumber(), "slice");
        RotationMode rotationMode = RotationMode.values()[dialog.getNextChoiceIndex()];
        int[] rotationEvents = integerList(dialog.getNextString(), "rotation events");
        int rotationEventWindow = integer(dialog.getNextNumber(), "rotation event window");
        Warper.Interpolation interpolation = Warper.Interpolation.valueOf(dialog.getNextChoice());
        boolean crop = dialog.getNextBoolean();

        RelativeIntensityPatternParameters registration = RelativeIntensityPatternParameters.builder()
                .recommendation(imageType, motionType)
                .selectionMode(mode)
                .channel(channel).estimationScale(estimationScale).slice(slice)
                .rotationMode(rotationMode)
                .rotationEventFrames(rotationMode == RotationMode.KNOWN_EVENTS
                        ? rotationEvents : new int[0])
                .rotationEventWindow(rotationEventWindow)
                .interpolation(interpolation).crop(crop).build();
        if (advanced && mode == SelectionMode.LONGITUDINAL_ACCURACY) {
            IJ.log("Relative-Intensity Pattern Registration Batch: Longitudinal maximum accuracy "
                    + "is fixed, so ordinary pairwise settings were not opened.");
        } else if (advanced) {
            RelativeIntensityPatternParameters edited = RelativeIntensityPatternRegistrationPlugin.advancedDialog(registration);
            if (edited == null) return null;
            // Editing the shared settings makes the batch a manual replay of one explicit recipe.
            // Keeping automatic here would discard the very values the user just typed.
            registration = edited;
        }
        Prefs.set(INPUT_PREF, input.getAbsolutePath());
        Prefs.set(OUTPUT_PREF, output.getAbsolutePath());
        return RelativeIntensityPatternBatchParameters.builder(input, output, registration)
                .recursive(recursive).overwrite(overwrite).build();
    }

    private static void start(RelativeIntensityPatternBatchParameters parameters, int count) {
        AtomicBoolean cancel = new AtomicBoolean();
        BatchProgressWindow window = new BatchProgressWindow(count, cancel);
        window.showWindow();
        Thread worker = new Thread(() -> {
            try {
                RelativeIntensityPatternBatchResult result = RelativeIntensityPatternBatch.run(parameters, status -> {
                    window.update(status);
                    logStatus(status);
                }, cancel::get);
                window.finish(result);
                finish(result);
            } catch (RuntimeException error) {
                window.fail(rootMessage(error));
                IJ.log("Relative-Intensity Pattern Registration Batch failed: " + rootMessage(error));
            }
        }, "log-ratio-registration-batch");
        worker.setDaemon(true);
        worker.start();
    }

    private static void logStatus(RelativeIntensityPatternBatchStatus status) {
        IJ.showProgress(status.fraction());
        String file = status.currentFile == null ? "" : " - " + status.currentFile.getName();
        String estimate = status.estimatedRemainingMillis < 0 ? " - estimating time"
                : " - about " + duration(status.estimatedRemainingMillis) + " remaining";
        IJ.showStatus("RIPR batch: " + status.completedFiles + " / " + status.totalFiles
                + file + estimate);
    }

    private static void finish(RelativeIntensityPatternBatchResult result) {
        String state = result.cancelled ? "cancelled" : "complete";
        IJ.showProgress(result.cancelled ? 0 : 1.0);
        IJ.showStatus("RIPR batch " + state);
        IJ.log("Relative-Intensity Pattern Registration Batch " + state + ": " + result.processedFiles
                + " processed, " + result.skippedFiles + " skipped, " + result.errorFiles
                + " failed. Report: " + result.reportFile.getAbsolutePath());
    }

    private static int integer(double value, String name) {
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return (int) value;
    }

    private static int[] integerList(String text, String name) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return new int[0];
        String[] parts = trimmed.split(",");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be comma-separated integers");
        }
        return out;
    }

    private static String[] labels(ImageType[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }

    private static String[] labels(MotionType[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }

    private static String[] labels(SelectionMode[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }

    private static String[] labels(RotationMode[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }

    private static String[] names(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].name();
        return out;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }

    /** Modeless timer window; closing it requests a safe stop after current work yields. */
    private static final class BatchProgressWindow {
        private final JDialog dialog;
        private final JLabel file = new JLabel("Preparing first stack...");
        private final JLabel files = new JLabel();
        private final JLabel elapsed = new JLabel("Elapsed: 00:00");
        private final JLabel remaining = new JLabel("Estimated remaining: calculating...");
        private final JLabel finish = new JLabel("Estimated finish: calculating...");
        private final JProgressBar progress = new JProgressBar(0, 1000);
        private final JButton action = new JButton("Cancel");
        private final AtomicBoolean cancellation;
        private final Timer timer;
        private volatile RelativeIntensityPatternBatchStatus latest;
        private volatile long latestAt;
        private volatile boolean done;

        BatchProgressWindow(int total, AtomicBoolean cancellation) {
            this.cancellation = cancellation;
            dialog = new JDialog((Frame) null, "Relative-Intensity Pattern Registration Batch", false);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { requestCancel(); }
            });
            files.setText("Stacks: 0 / " + total);
            progress.setStringPainted(true);
            JPanel labels = new JPanel(new GridLayout(5, 1, 0, 5));
            labels.add(file); labels.add(files); labels.add(elapsed); labels.add(remaining); labels.add(finish);
            labels.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
            JPanel bottom = new JPanel(new BorderLayout(10, 0));
            bottom.add(progress, BorderLayout.CENTER);
            bottom.add(action, BorderLayout.EAST);
            bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
            dialog.add(labels, BorderLayout.CENTER);
            dialog.add(bottom, BorderLayout.SOUTH);
            action.addActionListener(event -> { if (done) dialog.dispose(); else requestCancel(); });
            timer = new Timer(1000, event -> refreshClock());
            timer.start();
            dialog.setMinimumSize(new Dimension(520, 230));
            dialog.pack();
            dialog.setLocationByPlatform(true);
        }

        void showWindow() { SwingUtilities.invokeLater(() -> dialog.setVisible(true)); }

        void update(RelativeIntensityPatternBatchStatus status) {
            SwingUtilities.invokeLater(() -> {
                latest = status;
                latestAt = System.currentTimeMillis();
                file.setText(status.currentFile == null ? "Finishing report..."
                        : "Current stack: " + status.currentFile.getName());
                files.setText("Stacks: " + status.completedFiles + " / " + status.totalFiles
                        + (status.totalPairs > 0 ? "    Pairs: " + status.currentPair + " / " + status.totalPairs : ""));
                elapsed.setText("Elapsed: " + duration(status.elapsedMillis));
                if (status.estimatedRemainingMillis < 0) {
                    remaining.setText("Estimated remaining: calculating...");
                    finish.setText("Estimated finish: calculating...");
                } else {
                    remaining.setText("Estimated remaining: " + duration(status.estimatedRemainingMillis));
                    finish.setText("Estimated finish: " + DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(System.currentTimeMillis() + status.estimatedRemainingMillis)));
                }
                progress.setValue((int) Math.round(status.fraction() * 1000));
                progress.setString(String.format(Locale.ROOT, "%.1f%%", status.fraction() * 100));
            });
        }

        void finish(RelativeIntensityPatternBatchResult result) {
            SwingUtilities.invokeLater(() -> {
                done = true;
                timer.stop();
                file.setText(result.cancelled ? "Batch cancelled" : "Batch complete");
                files.setText(result.processedFiles + " processed, " + result.skippedFiles
                        + " skipped, " + result.errorFiles + " failed");
                elapsed.setText("Elapsed: " + duration(result.elapsedMillis));
                remaining.setText("Report: " + result.reportFile.getName());
                finish.setText("Output: " + result.outputDirectory.getAbsolutePath());
                if (!result.cancelled) { progress.setValue(1000); progress.setString("100.0%"); }
                action.setText("Close");
            });
        }

        void fail(String message) {
            SwingUtilities.invokeLater(() -> {
                done = true;
                timer.stop();
                file.setText("Batch stopped: " + message);
                remaining.setText(""); finish.setText(""); action.setText("Close");
            });
        }

        private void requestCancel() {
            cancellation.set(true);
            action.setEnabled(false);
            action.setText("Stopping...");
            file.setText("Stopping safely after current work...");
        }

        private void refreshClock() {
            if (done || latest == null) return;
            long sinceUpdate = Math.max(0, System.currentTimeMillis() - latestAt);
            elapsed.setText("Elapsed: " + duration(latest.elapsedMillis + sinceUpdate));
            if (latest.estimatedRemainingMillis >= 0) {
                long estimate = Math.max(0, latest.estimatedRemainingMillis - sinceUpdate);
                remaining.setText("Estimated remaining: " + duration(estimate));
                finish.setText("Estimated finish: " + DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(new Date(System.currentTimeMillis() + estimate)));
            }
        }

        private static String duration(long millis) {
            long seconds = Math.max(0, millis / 1000);
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long remainder = seconds % 60;
            return RelativeIntensityPatternRegistrationBatchPlugin.duration(millis);
        }
    }
}
