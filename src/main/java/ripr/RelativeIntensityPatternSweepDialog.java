/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.core.Transform;
import ripr.core.Warper;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Window;
import java.awt.event.ItemEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Configuration and selectable-result grid for bounded parameter sweeps. */
final class RelativeIntensityPatternSweepDialog {
    private RelativeIntensityPatternSweepDialog() { }

    static RelativeIntensityPatternParameters show(Window owner, ImagePlus image, RelativeIntensityPatternParameters base) {
        Configuration configuration = configure(image, base);
        if (configuration == null) return null;
        RelativeIntensityPatternSweepPlan plan = new RelativeIntensityPatternSweepPlan(base, configuration.axes);
        Results results = new Results(owner, image, plan, configuration.previewFrame);
        return results.showDialog();
    }

    private static Configuration configure(ImagePlus image, RelativeIntensityPatternParameters base) {
        int frameCount = StackFrames.of(image, base.channel, base.slice).count();
        GenericDialog dialog = new GenericDialog("Parameter Sweep Setup");
        dialog.addMessage("Choose up to three parameters and comma-separated values.\n"
                + "The current stack is measured once for every combination; the source is unchanged.");
        RelativeIntensityPatternSweepPlan.Parameter[] defaults = {
                RelativeIntensityPatternSweepPlan.Parameter.PIXEL_SELECTION,
                RelativeIntensityPatternSweepPlan.Parameter.PIXEL_REMOVAL,
                RelativeIntensityPatternSweepPlan.Parameter.NONE
        };
        String[] labels = RelativeIntensityPatternSweepPlan.Parameter.labels();
        for (int i = 0; i < 3; i++) {
            dialog.addChoice("Parameter " + (i + 1), labels, defaults[i].label);
            dialog.addStringField("Values " + (i + 1), suggested(defaults[i], image), 48);
        }
        dialog.addNumericField("Preview frame (1-based)", frameCount, 0);
        dialog.addMessage("Maximum " + RelativeIntensityPatternSweepPlan.MAX_COMBINATIONS
                + " combinations. Runs are sequential to keep memory bounded.");
        dialog.setOKLabel("Run sweep");
        dialog.showDialog();
        if (dialog.wasCanceled()) return null;
        RelativeIntensityPatternSweepPlan.Axis[] axes = new RelativeIntensityPatternSweepPlan.Axis[3];
        for (int i = 0; i < axes.length; i++) {
            RelativeIntensityPatternSweepPlan.Parameter parameter = RelativeIntensityPatternSweepPlan.Parameter.fromLabel(dialog.getNextChoice());
            axes[i] = new RelativeIntensityPatternSweepPlan.Axis(parameter, dialog.getNextString());
        }
        int preview = integer(dialog.getNextNumber(), "preview frame");
        if (preview < 1 || preview > frameCount) {
            throw new IllegalArgumentException("preview frame must be inside 1.." + frameCount);
        }
        return new Configuration(axes, preview - 1);
    }

    private static String suggested(RelativeIntensityPatternSweepPlan.Parameter parameter, ImagePlus image) {
        if (parameter != RelativeIntensityPatternSweepPlan.Parameter.ESTIMATION_SCALE) return parameter.suggested;
        int shortest = Math.min(image.getWidth(), image.getHeight());
        double[] candidates = {1, 0.75, 0.5, 0.25};
        StringBuilder out = new StringBuilder();
        for (double value : candidates) {
            if (shortest * value < 32) continue;
            if (out.length() > 0) out.append(", ");
            out.append(value == 1 ? "1" : Double.toString(value));
        }
        return out.toString();
    }

    private static int integer(double value, String name) {
        if (!Double.isFinite(value) || value != Math.rint(value)) throw new IllegalArgumentException(name + " must be an integer");
        return (int) value;
    }

    private static final class Configuration {
        final RelativeIntensityPatternSweepPlan.Axis[] axes;
        final int previewFrame;
        Configuration(RelativeIntensityPatternSweepPlan.Axis[] axes, int previewFrame) {
            this.axes = axes; this.previewFrame = previewFrame;
        }
    }

    private static final class Outcome {
        final RelativeIntensityPatternSweepPlan.Combination combination;
        final Registration.Result registration;
        final long elapsedMillis;
        final BufferedImage preview;
        final double fullResolutionResidual;
        final String error;

        Outcome(RelativeIntensityPatternSweepPlan.Combination combination, Registration.Result registration,
                long elapsedMillis, BufferedImage preview, double fullResolutionResidual,
                String error) {
            this.combination = combination;
            this.registration = registration;
            this.elapsedMillis = elapsedMillis;
            this.preview = preview;
            this.fullResolutionResidual = fullResolutionResidual;
            this.error = error;
        }
    }

    private static final class Results {
        private final JDialog dialog;
        private final ImagePlus image;
        private final RelativeIntensityPatternSweepPlan plan;
        private final int previewFrame;
        private final JPanel grid = new JPanel();
        private final JProgressBar progress = new JProgressBar();
        private final JLabel status = new JLabel("Preparing sweep...");
        private final JButton use = new JButton("Use selected settings");
        private final JButton cancel = new JButton("Cancel");
        private final ButtonGroup selection = new ButtonGroup();
        private final List<Card> cards = new ArrayList<>();
        private SwingWorker<Void, Published> worker;
        private RelativeIntensityPatternParameters accepted;
        private long start;

        Results(Window owner, ImagePlus image, RelativeIntensityPatternSweepPlan plan, int previewFrame) {
            this.image = image;
            this.plan = plan;
            this.previewFrame = previewFrame;
            dialog = new JDialog(owner,
                    "Registration Parameter Sweep", JDialog.ModalityType.APPLICATION_MODAL);
            buildUi();
        }

        RelativeIntensityPatternParameters showDialog() {
            start = System.currentTimeMillis();
            worker = new Runner();
            worker.execute();
            dialog.setVisible(true);
            return accepted;
        }

        private void buildUi() {
            int columns = plan.combinations.size() <= 4 ? 2 : plan.combinations.size() <= 9 ? 3 : 4;
            grid.setLayout(new GridLayout(0, columns, 8, 8));
            grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            for (int i = 0; i < plan.combinations.size(); i++) {
                Card card = new Card(i, plan.combinations.get(i));
                cards.add(card);
                selection.add(card.radio);
                grid.add(card.panel);
            }
            JScrollPane scroll = new JScrollPane(grid);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            JPanel header = new JPanel();
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            JLabel guidance = new JLabel("Red-cyan overlay: grey means the reference and corrected preview frame agree. "
                    + "Select one result, then load it into the main dialog.");
            guidance.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
            header.add(guidance);
            progress.setMinimum(0); progress.setMaximum(plan.combinations.size());
            progress.setStringPainted(true);
            header.add(progress);
            JPanel footer = new JPanel(new BorderLayout(10, 0));
            status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            footer.add(status, BorderLayout.CENTER);
            JPanel buttons = new JPanel();
            use.setEnabled(false);
            buttons.add(cancel); buttons.add(use);
            footer.add(buttons, BorderLayout.EAST);
            use.addActionListener(event -> acceptSelected());
            cancel.addActionListener(event -> closeOrCancel());
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) { closeOrCancel(); }
            });
            dialog.add(header, BorderLayout.NORTH);
            dialog.add(scroll, BorderLayout.CENTER);
            dialog.add(footer, BorderLayout.SOUTH);
            dialog.setMinimumSize(new Dimension(840, 620));
            dialog.setSize(Math.min(1300, 260 * Math.min(columns, 4) + 80), 760);
            dialog.setLocationRelativeTo(dialog.getOwner());
        }

        private void closeOrCancel() {
            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
                cancel.setEnabled(false);
                cancel.setText("Stopping...");
                status.setText("Stopping after the current fit yields...");
            } else {
                dialog.dispose();
            }
        }

        private void acceptSelected() {
            for (Card card : cards) {
                if (card.radio.isSelected() && card.outcome != null && card.outcome.error == null) {
                    accepted = card.outcome.combination.parameters;
                    if (worker != null && !worker.isDone()) worker.cancel(true);
                    dialog.dispose();
                    return;
                }
            }
        }

        private final class Runner extends SwingWorker<Void, Published> {
            @Override protected Void doInBackground() {
                for (int index = 0; index < plan.combinations.size(); index++) {
                    if (isCancelled()) break;
                    final int resultIndex = index;
                    RelativeIntensityPatternSweepPlan.Combination combination = plan.combinations.get(index);
                    long itemStart = System.currentTimeMillis();
                    try {
                        Registration.Result registration = RelativeIntensityPatternRegistration.estimate(image,
                                combination.parameters, (done, total) -> publish(new Published(resultIndex, done, total, null)),
                                this::isCancelled);
                        BufferedImage preview = preview(image, combination.parameters, registration,
                                previewFrame, 224, 168);
                        double residual = fullResolutionResidual(image, combination.parameters,
                                registration);
                        publish(new Published(index, 0, 0, new Outcome(combination, registration,
                                System.currentTimeMillis() - itemStart, preview, residual, null)));
                    } catch (CancellationException stop) {
                        break;
                    } catch (RuntimeException error) {
                        publish(new Published(index, 0, 0, new Outcome(combination, null,
                                System.currentTimeMillis() - itemStart, null, Double.NaN,
                                rootMessage(error))));
                    }
                }
                return null;
            }

            @Override protected void process(List<Published> values) {
                for (Published value : values) {
                    Card card = cards.get(value.index);
                    if (value.outcome == null) {
                        status.setText("Combination " + (value.index + 1) + " / " + cards.size()
                                + (value.totalPairs > 0 ? ": pair " + value.pair + " / " + value.totalPairs : ""));
                    } else {
                        card.setOutcome(value.outcome);
                        int complete = 0;
                        for (Card c : cards) if (c.outcome != null) complete++;
                        progress.setValue(complete);
                        progress.setString(complete + " / " + cards.size());
                    }
                }
            }

            @Override protected void done() {
                cancel.setEnabled(true);
                cancel.setText("Close");
                if (isCancelled()) status.setText("Sweep stopped. Completed results remain selectable.");
                else status.setText("Sweep complete in " + duration(System.currentTimeMillis() - start)
                        + ". Select the result you want to load.");
            }
        }

        private final class Card {
            final JPanel panel = new JPanel(new BorderLayout(4, 4));
            final JRadioButton radio = new JRadioButton();
            final JLabel imageLabel = new JLabel("Waiting", SwingConstants.CENTER);
            final JLabel metrics = new JLabel(" ", SwingConstants.CENTER);
            Outcome outcome;

            Card(int index, RelativeIntensityPatternSweepPlan.Combination combination) {
                radio.setText("#" + (index + 1));
                radio.setEnabled(false);
                radio.setToolTipText("Select this result");
                JLabel label = new JLabel(combination.label);
                label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
                JPanel top = new JPanel(new BorderLayout());
                top.add(radio, BorderLayout.WEST); top.add(label, BorderLayout.CENTER);
                imageLabel.setPreferredSize(new Dimension(224, 168));
                imageLabel.setOpaque(true); imageLabel.setBackground(Color.DARK_GRAY);
                metrics.setFont(metrics.getFont().deriveFont(Font.PLAIN, 11f));
                panel.add(top, BorderLayout.NORTH); panel.add(imageLabel, BorderLayout.CENTER);
                panel.add(metrics, BorderLayout.SOUTH);
                panel.setBorder(BorderFactory.createLineBorder(new Color(180, 180, 180)));
                radio.addItemListener(event -> {
                    if (event.getStateChange() == ItemEvent.SELECTED) {
                        use.setEnabled(outcome != null && outcome.error == null);
                        for (Card card : cards) card.panel.setBorder(BorderFactory.createLineBorder(
                                card == this ? new Color(40, 110, 220) : new Color(180, 180, 180),
                                card == this ? 3 : 1));
                    }
                });
            }

            void setOutcome(Outcome value) {
                outcome = value;
                if (value.error != null) {
                    imageLabel.setText("<html><center>Failed<br>" + escape(value.error) + "</center></html>");
                    metrics.setText(duration(value.elapsedMillis));
                    radio.setEnabled(false);
                } else {
                    imageLabel.setText("");
                    imageLabel.setIcon(new javax.swing.ImageIcon(value.preview));
                    metrics.setText(String.format(Locale.ROOT,
                            "full-size residual %.4f · %s",
                            value.fullResolutionResidual, duration(value.elapsedMillis)));
                    radio.setEnabled(true);
                }
            }
        }
    }

    private static final class Published {
        final int index;
        final int pair;
        final int totalPairs;
        final Outcome outcome;
        Published(int index, int pair, int totalPairs, Outcome outcome) {
            this.index = index; this.pair = pair; this.totalPairs = totalPairs; this.outcome = outcome;
        }
    }

    private static BufferedImage preview(ImagePlus source, RelativeIntensityPatternParameters parameters,
                                         Registration.Result result, int frame,
                                         int targetWidth, int targetHeight) {
        StackFrames frames = StackFrames.of(source, parameters.channel, parameters.slice);
        int referenceFrame = parameters.reference == ripr.core.Reconciler.Reference.FIXED
                ? parameters.referenceFrame - 1 : 0;
        float[] reference = frames.plane(referenceFrame);
        float[] input = frames.plane(frame);
        int width = frames.width();
        int height = frames.height();
        float[] corrected = new float[input.length];
        Warper.warp(input, corrected, width, height, result.cumulative[frame],
                Warper.Interpolation.BILINEAR, Float.NaN);
        BufferedImage raw = overlay(reference, corrected, width, height);
        double scale = Math.min(targetWidth / (double) width, targetHeight / (double) height);
        int outWidth = Math.max(1, (int) Math.round(width * scale));
        int outHeight = Math.max(1, (int) Math.round(height * scale));
        Image resized = raw.getScaledInstance(outWidth, outHeight, Image.SCALE_SMOOTH);
        BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = output.createGraphics();
        graphics.setColor(Color.BLACK); graphics.fillRect(0, 0, targetWidth, targetHeight);
        graphics.drawImage(resized, (targetWidth - outWidth) / 2, (targetHeight - outHeight) / 2, null);
        graphics.dispose();
        return output;
    }

    /** One comparable score for every sweep arm, always evaluated on native-resolution pixels. */
    static double fullResolutionResidual(ImagePlus source, RelativeIntensityPatternParameters parameters,
                                         Registration.Result result) {
        return fullResolutionResidual(source, parameters, result.cumulative);
    }

    /**
     * The same score computed from cumulative movement alone, so a saved run can be rescored without
     * being registered again. This is the score the sweep ranks its arms by.
     */
    static double fullResolutionResidual(ImagePlus source, RelativeIntensityPatternParameters parameters,
                                         Transform[] cumulative) {
        StackFrames frames = StackFrames.of(source, parameters.channel, parameters.slice);
        if (frames.count() < 2) return Double.NaN;
        int width = frames.width();
        int height = frames.height();
        float[] previous = new float[width * height];
        float[] current = new float[width * height];
        double[] pairScores = new double[frames.count() - 1];
        float[] rawPrevious = frames.plane(0);
        Warper.warp(rawPrevious, previous, width, height, cumulative[0],
                Warper.Interpolation.BILINEAR, Float.NaN);
        for (int frame = 1; frame < frames.count(); frame++) {
            float[] raw = frames.plane(frame);
            Warper.warp(raw, current, width, height, cumulative[frame],
                    Warper.Interpolation.BILINEAR, Float.NaN);
            pairScores[frame - 1] = logRatioResidual(previous, current, parameters.epsilon);
            float[] swap = previous; previous = current; current = swap;
        }
        java.util.Arrays.sort(pairScores);
        return pairScores[pairScores.length / 2];
    }

    private static double logRatioResidual(float[] a, float[] b, double epsilon) {
        double[] residual = new double[a.length];
        int count = 0;
        double invLog2 = 1.0 / Math.log(2);
        for (int i = 0; i < a.length; i++) {
            double av = a[i] + epsilon;
            double bv = b[i] + epsilon;
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i]) || !(av > 0) || !(bv > 0)) continue;
            residual[count++] = Math.log(bv / av) * invLog2;
        }
        if (count == 0) return Double.NaN;
        java.util.Arrays.sort(residual, 0, count);
        double gain = residual[count / 2];
        double absolute = 0;
        for (int i = 0; i < count; i++) absolute += Math.abs(residual[i] - gain);
        return absolute / count;
    }

    private static BufferedImage overlay(float[] reference, float[] corrected, int width, int height) {
        float[] referenceRange = range(reference);
        float[] correctedRange = range(corrected);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < reference.length; i++) {
            int red = normalize(reference[i], referenceRange);
            int cyan = normalize(corrected[i], correctedRange);
            image.setRGB(i % width, i / width, (red << 16) | (cyan << 8) | cyan);
        }
        return image;
    }

    private static float[] range(float[] pixels) {
        float[] finite = new float[pixels.length];
        int count = 0;
        for (float value : pixels) if (Float.isFinite(value)) finite[count++] = value;
        if (count == 0) return new float[] {0, 1};
        java.util.Arrays.sort(finite, 0, count);
        float low = finite[(int) (0.01 * (count - 1))];
        float high = finite[(int) (0.99 * (count - 1))];
        return new float[] {low, high > low ? high : low + 1};
    }

    private static int normalize(float value, float[] range) {
        if (!Float.isFinite(value)) return 0;
        return Math.max(0, Math.min(255, Math.round(255 * (value - range[0]) / (range[1] - range[0]))));
    }

    private static String duration(long millis) {
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
