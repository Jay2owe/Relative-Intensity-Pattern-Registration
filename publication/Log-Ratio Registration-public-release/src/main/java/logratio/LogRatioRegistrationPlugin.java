/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import logratio.api.ImageType;
import logratio.api.AutomaticRegistrationSelector;
import logratio.api.LogRatioParameters;
import logratio.api.LogRatioPreset;
import logratio.api.LogRatioRecommendations;
import logratio.api.LogRatioRegistration;
import logratio.api.LogRatioResult;
import logratio.api.MotionType;
import logratio.api.Preprocessing;
import logratio.api.PixelSelectionStrategy;
import logratio.api.RegistrationRecipe;
import logratio.api.SelectionMode;
import logratio.core.PairAligner;
import logratio.core.PairEstimator;
import logratio.core.PairScheduler;
import logratio.core.Reconciler;
import logratio.core.Registration;
import logratio.core.RobustNorm;
import logratio.core.Warper;

import java.awt.GraphicsEnvironment;
import java.awt.Choice;
import java.awt.Checkbox;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Vector;

/** Fiji menu, macro and headless entry point for the log-ratio engine. */
public final class LogRatioRegistrationPlugin implements PlugIn {
    public static final String COMMAND = "Log-Ratio Registration...";

    @Override
    public void run(String arg) {
        ImagePlus image = WindowManager.getCurrentImage();
        if (image == null) {
            IJ.error("Log-Ratio Registration", "No active image. Open a time series before running this command.");
            return;
        }
        String options = Macro.getOptions();
        boolean headless = GraphicsEnvironment.isHeadless();
        try {
            if (options != null || headless) {
                execute(image, MacroOptionsParser.parse(options == null ? "" : options), !headless);
            } else {
                LogRatioParameters parameters = dialog(image, null);
                if (parameters == null) return;
                if (Recorder.record) {
                    Recorder.recordString("run(\"" + COMMAND + "\", \""
                            + new LogRatioDialogModel(parameters).toMacroOptions() + "\");\n");
                }
                execute(image, parameters, true);
            }
        } catch (RuntimeException error) {
            IJ.error("Log-Ratio Registration", message(error));
        }
    }

    private static void execute(ImagePlus image, LogRatioParameters parameters, boolean show) {
        IJ.showStatus("Log-ratio registration: planning pairs");
        LogRatioResult result = LogRatioRegistration.register(image, parameters,
                new PairScheduler.Progress() {
                    @Override public void update(int done, int total) {
                        IJ.showProgress(done, total);
                        IJ.showStatus("Log-ratio registration: " + done + " / " + total + " pairs");
                    }
                }, PairScheduler.Cancellation.NEVER);
        if (show) result.correctedImage().show();
        Registration.Result registration = result.registration();
        // State every value that was used, not only the name of a recipe. A run log that says
        // "automatic" and nothing else cannot be reproduced by hand.
        IJ.log("Log-Ratio Registration settings: " + result.provenance());
        IJ.log("Log-Ratio Registration: median log-ratio residual "
                + format(registration.medianResidualBefore()) + " -> "
                + format(registration.medianResidualAfter()) + "; "
                + registration.workers + " worker(s), " + registration.levels + " pyramid level(s).");
        for (Registration.Warning warning : registration.warnings) {
            IJ.log("Log-Ratio Registration warning: " + warning.message);
        }
        IJ.showProgress(1.0);
        IJ.showStatus("Log-ratio registration complete");
    }

    private static LogRatioParameters dialog(ImagePlus image, LogRatioParameters loaded) {
        ImageType defaultImage = loaded == null ? ImageType.PHASE_CONTRAST : loaded.imageType;
        MotionType defaultMotion = loaded == null ? MotionType.SUBPIXEL_RANDOM_WALK : loaded.motionType;
        // Automatic is the default: it passed every declared limit on the locked test set, and on the
        // image types it does not serve it returns the Recommended recipe with no extra work.
        SelectionMode defaultMode = loaded == null ? SelectionMode.AUTOMATIC : loaded.selectionMode;
        final LogRatioParameters[] swept = {loaded};
        GenericDialog dialog = new GenericDialog("Log-Ratio Registration");
        dialog.addMessage("Choose the closest image and movement types, then choose where the\n"
                + "settings come from. Automatic is the default: it inspects this recording and\n"
                + "resolves pixel support, brightness limits, filter and mask, then shows you the\n"
                + "result before it runs. It can only differ from Recommended for the image types\n"
                + "where an override was measured as safe and better, and it registers those\n"
                + "recordings twice, so allow roughly double the time. For every other image type\n"
                + "it returns the Recommended settings at no extra cost.\n"
                + "Recommended loads the measured recipe for the chosen types and changes nothing.\n"
                + "Manual uses exactly the values in the settings window.");
        dialog.addChoice("Image type", labels(ImageType.values()), defaultImage.label());
        dialog.addChoice("Motion type", labels(MotionType.values()), defaultMotion.label());
        dialog.addChoice("Settings source", labels(SelectionMode.values()), defaultMode.label());
        dialog.addCheckbox("Review and edit all settings before running", false);
        dialog.addButton("Sweep parameters on this stack...", new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                try {
                    LogRatioParameters current = readMainControls(dialog, swept[0]);
                    if (current == null) return;
                    if (current.selectionMode == SelectionMode.AUTOMATIC) {
                        current = resolveAutomatic(image, current).parameters;
                        loadMainControls(dialog, current);
                    }
                    LogRatioParameters chosen = LogRatioSweepDialog.show(dialog, image, current);
                    if (chosen != null) {
                        swept[0] = chosen;
                        loadMainControls(dialog, chosen);
                    }
                } catch (RuntimeException error) {
                    IJ.error("Parameter Sweep", message(error));
                }
            }
        });
        dialog.addMessage("Input and output");
        int channel = loaded == null ? 1 : Math.min(image.getNChannels(), loaded.channel);
        dialog.addChoice("Channel used to estimate movement", channelLabels(image), "Channel " + channel);
        dialog.addNumericField("Z slice (0 = maximum projection)", loaded == null ? 0 : loaded.slice, 0);
        dialog.addChoice("Interpolation", names(Warper.Interpolation.values()),
                loaded == null ? Warper.Interpolation.NONE.name() : loaded.interpolation.name());
        dialog.addCheckbox("Crop to common valid field", loaded == null || loaded.crop);
        dialog.setOKLabel("Register");
        dialog.showDialog();
        if (dialog.wasCanceled()) return null;

        LogRatioParameters initial = readMainControls(dialog, swept[0]);
        if (initial == null) return null;
        Vector checkboxes = dialog.getCheckboxes();
        boolean review = ((Checkbox) checkboxes.get(0)).getState();
        String automaticNote = null;
        if (initial.selectionMode == SelectionMode.AUTOMATIC) {
            AutomaticRegistrationSelector.Result automatic = resolveAutomatic(image, initial);
            initial = automatic.parameters;
            automaticNote = automatic.explanation();
            // Automatic mode always shows what it chose. The values are ordinary editable settings
            // by the time they reach the review window, so nothing can be silently overwritten.
            review = true;
        }
        if (!review) return initial;
        LogRatioParameters edited = advancedDialog(initial, automaticNote);
        if (edited == null) return null;
        return edited;
    }

    private static LogRatioParameters readMainControls(GenericDialog dialog,
                                                        LogRatioParameters swept) {
        Vector choices = dialog.getChoices();
        Vector checks = dialog.getCheckboxes();
        Vector numbers = dialog.getNumericFields();
        ImageType imageType = ImageType.values()[((Choice) choices.get(0)).getSelectedIndex()];
        MotionType motionType = MotionType.values()[((Choice) choices.get(1)).getSelectedIndex()];
        SelectionMode mode = SelectionMode.values()[((Choice) choices.get(2)).getSelectedIndex()];
        int channel = ((Choice) choices.get(3)).getSelectedIndex() + 1;
        int slice = parseInteger((TextField) numbers.get(0), "slice");
        Warper.Interpolation interpolation = Warper.Interpolation.valueOf(
                ((Choice) choices.get(4)).getSelectedItem());
        boolean crop = ((Checkbox) checks.get(1)).getState();
        LogRatioParameters.Builder builder;
        if (mode == SelectionMode.MANUAL && swept != null) builder = swept.toBuilder();
        else builder = LogRatioParameters.builder().recommendation(imageType, motionType);
        builder.imageType(imageType).motionType(motionType).selectionMode(mode);
        return builder.channel(channel).slice(slice).interpolation(interpolation).crop(crop).build();
    }

    private static void loadMainControls(GenericDialog dialog, LogRatioParameters parameters) {
        Vector choices = dialog.getChoices();
        Vector checks = dialog.getCheckboxes();
        Vector numbers = dialog.getNumericFields();
        ((Choice) choices.get(0)).select(parameters.imageType.ordinal());
        ((Choice) choices.get(1)).select(parameters.motionType.ordinal());
        // Loading concrete values makes this a manual run; the selector must not overwrite them.
        ((Choice) choices.get(2)).select(SelectionMode.MANUAL.ordinal());
        ((Choice) choices.get(3)).select(parameters.channel - 1);
        ((TextField) numbers.get(0)).setText(Integer.toString(parameters.slice));
        ((Choice) choices.get(4)).select(parameters.interpolation.name());
        ((Checkbox) checks.get(1)).setState(parameters.crop);
    }

    private static int parseInteger(TextField field, String name) {
        try { return integer(Double.parseDouble(field.getText().trim()), name); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be an integer"); }
    }

    static LogRatioParameters advancedDialog(LogRatioParameters p) {
        return advancedDialog(p, null);
    }

    private static LogRatioParameters advancedDialog(LogRatioParameters p, String automaticNote) {
        LogRatioPreset preset = LogRatioRecommendations.forTypes(p.imageType, p.motionType);
        RegistrationRecipe loadedRecipe = RegistrationRecipe.of(p);
        GenericDialog dialog = new GenericDialog("Log-Ratio Registration - All Settings");
        dialog.addMessage(header(p, preset, automaticNote));

        dialog.addMessage("0. Pairwise estimator\n"
                + "How one frame pair is measured. Sections 1 to 4 below are settings of the\n"
                + "log-ratio fit and do nothing under area correlation.");
        dialog.addChoice("Estimator", estimatorLabels(), p.estimator.label());

        dialog.addMessage("1. Base pixel support");
        dialog.addChoice("Pixels used to estimate movement", names(PairAligner.PixelSupport.values()),
                p.pixelSupport.name());
        dialog.addNumericField("Gradient multiplier", p.gradientFraction, 2);

        dialog.addMessage("2. Intensity restrictions\n"
                + "Percentile thresholds of the frame's own intensity range; \"off\" keeps everything.");
        dialog.addStringField("Exclude dimmest pixels below percentile", optional(p.floorPercentile), 8);
        dialog.addStringField("Exclude brightest pixels above percentile", optional(p.ceilingPercentile), 8);

        dialog.addMessage("3. Estimation filter\n"
                + "Applied only to the channel used to estimate movement; output pixels stay untouched.");
        dialog.addChoice("Filter", labels(Preprocessing.values()), p.preprocessing.label());
        dialog.addCheckbox("Remove additive background", p.removeOffset);
        dialog.addNumericField("Background percentile", p.offsetPercentile, 2);

        dialog.addMessage("4. Spatial pixel removal (optional second pass)\n"
                + "A scoring copy chooses the mask; original unfiltered pixels drive the final fit.");
        dialog.addChoice("Removal strategy", labels(PixelSelectionStrategy.values()),
                p.pixelSelectionStrategy.label());
        dialog.addChoice("Filter used only to choose the mask", labels(Preprocessing.values()),
                p.pixelSelectionPreprocessing.label());
        dialog.addNumericField("Eligible pixels removed (%)", p.pixelRemovalPercent, 1);

        dialog.addMessage("5. Fit and performance");
        dialog.addChoice("Robust weighting", names(RobustNorm.values()), p.norm.name());
        dialog.addChoice("Reference strategy", names(Reconciler.Reference.values()), p.reference.name());
        dialog.addNumericField("Reference frame (1-based)", p.referenceFrame, 0);
        dialog.addStringField("Lags", join(p.lags), 18);
        dialog.addNumericField("Rolling template window", p.templateWindow, 0);
        dialog.addNumericField("Log epsilon", p.epsilon, 3);
        dialog.addNumericField("Estimation scale (0-1; output stays full size)", p.estimationScale, 2);
        dialog.addCheckbox("Estimate maximum shift automatically", p.autoMaxShift);
        dialog.addNumericField("Maximum shift when manual (pixels)", p.maxShift, 1);
        dialog.addNumericField("Step outlier repair (median deviations; 0 = off)", p.outlierMads, 1);
        dialog.addNumericField("Maximum iterations per level", p.maxIterations, 0);
        dialog.addNumericField("Maximum sampled pixels", p.maxSamples, 0);
        dialog.addNumericField("Minimum usable pixel fraction", p.minValidFraction, 2);
        dialog.addNumericField("Worker threads (0 = automatic)", p.threads, 0);
        dialog.setOKLabel("Use these settings");
        dialog.showDialog();
        if (dialog.wasCanceled()) return null;

        PairEstimator.Kind estimator = estimatorFrom(dialog.getNextChoice());
        PairAligner.PixelSupport support = PairAligner.PixelSupport.valueOf(dialog.getNextChoice());
        double gradient = dialog.getNextNumber();
        double floor = optional(dialog.getNextString(), "dimmest percentile");
        double ceiling = optional(dialog.getNextString(), "brightest percentile");
        Preprocessing preprocessing = Preprocessing.from(dialog.getNextChoice());
        boolean removeOffset = dialog.getNextBoolean();
        double offset = dialog.getNextNumber();
        PixelSelectionStrategy pixelSelection = PixelSelectionStrategy.from(dialog.getNextChoice());
        Preprocessing maskPreprocessing = Preprocessing.from(dialog.getNextChoice());
        double pixelRemoval = dialog.getNextNumber();
        RobustNorm norm = RobustNorm.valueOf(dialog.getNextChoice());
        Reconciler.Reference reference = Reconciler.Reference.valueOf(dialog.getNextChoice());
        int referenceFrame = integer(dialog.getNextNumber(), "reference frame");
        int[] lags = parseLags(dialog.getNextString());
        int templateWindow = integer(dialog.getNextNumber(), "template window");
        double epsilon = dialog.getNextNumber();
        double estimationScale = dialog.getNextNumber();
        boolean autoShift = dialog.getNextBoolean();
        double maxShift = dialog.getNextNumber();
        double outlierMads = dialog.getNextNumber();
        int iterations = integer(dialog.getNextNumber(), "iterations");
        int maxSamples = integer(dialog.getNextNumber(), "maximum sampled pixels");
        double minValid = dialog.getNextNumber();
        int threads = integer(dialog.getNextNumber(), "threads");

        // A whole-window correlation has no per-pixel support to mask, and the parameter bundle
        // refuses the pair rather than ignoring one of them. Telling the user which setting is
        // being dropped, once, beats an exception naming a field they did not think they set.
        if (estimator != PairEstimator.Kind.LOG_RATIO_FIT
                && pixelSelection != PixelSelectionStrategy.NONE) {
            ij.IJ.log("Log-Ratio Registration: spatial pixel removal is a second pass of the "
                    + "log-ratio fit and has no meaning for " + estimator.label().toLowerCase()
                    + "; it has been switched off for this run.");
            pixelSelection = PixelSelectionStrategy.NONE;
        }

        LogRatioParameters edited = LogRatioParameters.builder()
                .imageType(p.imageType).motionType(p.motionType)
                .channel(p.channel).slice(p.slice)
                .estimator(estimator)
                .estimationScale(estimationScale).preprocessing(preprocessing)
                .pixelSelectionStrategy(pixelSelection)
                .pixelSelectionPreprocessing(maskPreprocessing)
                .pixelRemovalPercent(pixelRemoval)
                .reference(reference).referenceFrame(referenceFrame).lags(lags)
                .templateWindow(templateWindow).norm(norm).pixelSupport(support)
                .gradientFraction(gradient).epsilon(epsilon).floorPercentile(floor)
                .ceilingPercentile(ceiling).removeOffset(removeOffset).offsetPercentile(offset)
                .autoMaxShift(autoShift).maxShift(maxShift).outlierMads(outlierMads)
                .maxIterations(iterations).maxSamples(maxSamples).minValidFraction(minValid)
                .threads(threads).interpolation(p.interpolation).crop(p.crop)
                .selectionMode(SelectionMode.MANUAL)
                .recipeProvenance(provenanceAfterEditing(p, loadedRecipe, estimator, support,
                        gradient, floor, ceiling, preprocessing, pixelSelection, maskPreprocessing,
                        pixelRemoval, iterations, maxSamples))
                .build();
        return edited;
    }

    /**
     * Keep the automatic provenance only while the resolved recipe is untouched. The moment a
     * resolved value is edited the run is the user's, and saying otherwise would misreport it.
     */
    private static String provenanceAfterEditing(LogRatioParameters p, RegistrationRecipe loaded,
            PairEstimator.Kind estimator, PairAligner.PixelSupport support, double gradient,
            double floor, double ceiling,
            Preprocessing preprocessing, PixelSelectionStrategy pixelSelection,
            Preprocessing maskPreprocessing, double pixelRemoval, int iterations, int maxSamples) {
        RegistrationRecipe editedRecipe = new RegistrationRecipe(estimator, support, gradient, floor,
                ceiling, preprocessing, pixelSelection, maskPreprocessing, pixelRemoval, iterations,
                maxSamples);
        if (editedRecipe.equals(loaded)) return p.recipeProvenance;
        return p.recipeProvenance.isEmpty() ? "edited by hand"
                : p.recipeProvenance + ", then edited by hand";
    }

    private static String[] estimatorLabels() {
        PairEstimator.Kind[] kinds = PairEstimator.Kind.values();
        String[] out = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) out[i] = kinds[i].label();
        return out;
    }

    private static PairEstimator.Kind estimatorFrom(String label) {
        for (PairEstimator.Kind kind : PairEstimator.Kind.values()) {
            if (kind.label().equals(label)) return kind;
        }
        return PairEstimator.Kind.of(label);
    }

    private static String header(LogRatioParameters p, LogRatioPreset preset, String automaticNote) {
        StringBuilder out = new StringBuilder();
        out.append(p.selectionMode.label()).append(" settings. Every value below is editable.\n");
        if (automaticNote != null) {
            out.append("Automatic full selection resolved this recording to:\n")
                    .append(wrap(RegistrationRecipe.of(p).describe())).append('\n')
                    .append(wrap(automaticNote)).append('\n');
            out.append("Editing any value below makes this a manual run.\n");
        } else if (p.useRecommendation) {
            out.append("Loaded recommendation: ").append(preset.name()).append('\n');
        }
        if (!p.recipeProvenance.isEmpty() && automaticNote == null) {
            out.append("Source: ").append(p.recipeProvenance).append('\n');
        }
        return out.toString();
    }

    /** GenericDialog messages do not wrap, so fold long recipe text at sensible points. */
    private static String wrap(String text) {
        StringBuilder out = new StringBuilder();
        int since = 0;
        for (String part : text.split("; ")) {
            if (since > 0 && since + part.length() > 72) {
                out.append("\n");
                since = 0;
            } else if (since > 0) {
                out.append("; ");
                since += 2;
            }
            out.append(part);
            since += part.length();
        }
        return out.toString();
    }

    private static AutomaticRegistrationSelector.Result resolveAutomatic(
            ImagePlus image, LogRatioParameters parameters) {
        IJ.showStatus("Log-ratio registration: measuring the recording to choose settings");
        AutomaticRegistrationSelector.Result result = LogRatioRegistration.resolveAutomaticSettings(
                image, parameters);
        IJ.log("Log-Ratio Registration automatic full selection: " + result.explanation());
        return result;
    }

    private static String[] labels(ImageType[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }
    private static String[] channelLabels(ImagePlus image) {
        int count = Math.max(1, image.getNChannels());
        String[] labels = new String[count];
        for (int channel = 1; channel <= count; channel++) {
            labels[channel - 1] = "Channel " + channel;
        }
        return labels;
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
    private static String[] labels(Preprocessing[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }
    private static String[] labels(PixelSelectionStrategy[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }
    private static String[] names(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].name();
        return out;
    }
    private static int integer(double value, String name) {
        if (!Double.isFinite(value) || value != Math.rint(value)) throw new IllegalArgumentException(name + " must be an integer");
        return (int) value;
    }
    private static int[] parseLags(String text) {
        String[] parts = text.trim().split(",");
        int[] out = new int[parts.length];
        try { for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("lags must be comma-separated integers"); }
        return out;
    }
    private static String join(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) { if (i > 0) out.append(','); out.append(values[i]); }
        return out.toString();
    }
    private static double optional(String value, String name) {
        if ("off".equalsIgnoreCase(value.trim()) || "none".equalsIgnoreCase(value.trim())) return Double.NaN;
        try { return Double.parseDouble(value.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be a number or off"); }
    }
    private static String optional(double value) { return Double.isNaN(value) ? "off" : Double.toString(value); }
    private static String format(double value) { return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.4f", value) : "unavailable"; }
    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
