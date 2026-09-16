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
import ripr.api.RelativeIntensityPatternBatch;
import ripr.api.RelativeIntensityPatternBatchParameters;
import ripr.api.RelativeIntensityPatternBatchResult;
import ripr.api.RelativeIntensityPatternBatchStatus;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.SelectionMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Choice;
import java.awt.Checkbox;
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
        String[] recipeLabels = RelativeIntensityPatternRegistrationPlugin.simpleRecipeLabels();
        dialog.addChoice("Recipe", recipeLabels, recipeLabels[0]);
        dialog.addNumericField("Channel used to estimate movement", 1, 0);
        dialog.addCheckbox("Use longitudinal mode (whole recording)", true);
        Choice recipeChoice = (Choice) dialog.getChoices().get(0);
        Checkbox longitudinalBox = (Checkbox) dialog.getCheckboxes().get(2);
        recipeChoice.addItemListener(event -> {
            if (recipeChoice.getSelectedIndex() == recipeLabels.length - 1) {
                longitudinalBox.setState(false);
            }
        });
        dialog.addCheckbox("Show advanced settings before starting", false);
        dialog.addMessage("Longitudinal mode uses the fixed whole-recording route. Moving cells\n"
                + "uses a benchmark-backed frame-to-frame recipe and therefore keeps longitudinal mode off.");
        dialog.setOKLabel("Start batch");
        dialog.showDialog();
        if (dialog.wasCanceled()) return null;

        File input = new File(dialog.getNextString().trim());
        File output = new File(dialog.getNextString().trim());
        boolean recursive = dialog.getNextBoolean();
        boolean overwrite = dialog.getNextBoolean();
        int recipeIndex = dialog.getNextChoiceIndex();
        int channel = integer(dialog.getNextNumber(), "channel");
        boolean longitudinal = dialog.getNextBoolean();
        boolean advanced = dialog.getNextBoolean();

        RelativeIntensityPatternParameters registration =
                RelativeIntensityPatternRegistrationPlugin.simpleParameters(
                        recipeIndex, channel, longitudinal);
        if (advanced && registration.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY) {
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
