/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ripr.api.ImageType;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.AutomaticRegistrationSelectorModel;
import ripr.api.AutomaticRotationSelector;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternPreset;
import ripr.api.RelativeIntensityPatternRecommendations;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternResult;
import ripr.api.MotionType;
import ripr.api.Preprocessing;
import ripr.api.PixelSelectionStrategy;
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.PairScheduler;
import ripr.core.Reconciler;
import ripr.core.Registration;
import ripr.core.RobustNorm;
import ripr.core.RotationMode;
import ripr.core.Warper;

import java.awt.GraphicsEnvironment;
import java.awt.Choice;
import java.awt.Checkbox;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Locale;
import java.util.Vector;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Fiji menu, macro and headless entry point for the log-ratio engine. */
public final class RelativeIntensityPatternRegistrationPlugin implements PlugIn {
    public static final String COMMAND = "Relative-Intensity Pattern Registration...";

    /** The three choices exposed by the normal dialog; detailed fitting controls stay advanced. */
    private enum SimpleRecipe {
        LANDMARKS("Landmarks (phase contrast / brightfield)", ImageType.PHASE_CONTRAST,
                MotionType.INTERMITTENT_JUMPS),
        BRIGHT_DIM("Bright/dim references (fluorescence / bioluminescence)",
                ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE, MotionType.INTERMITTENT_JUMPS),
        MOVING_CELLS("Moving cells (biological foreground)", ImageType.DENSE_FLUORESCENCE,
                MotionType.INTERMITTENT_JUMPS);

        final String label;
        final ImageType imageType;
        final MotionType motionType;

        SimpleRecipe(String label, ImageType imageType, MotionType motionType) {
            this.label = label;
            this.imageType = imageType;
            this.motionType = motionType;
        }
    }

    static String[] simpleRecipeLabels() {
        SimpleRecipe[] values = SimpleRecipe.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].label;
        return labels;
    }

    /** Build the benchmark-backed parameters represented by the small dialog. */
    static RelativeIntensityPatternParameters simpleParameters(int recipeIndex, int channel,
                                                               boolean longitudinal) {
        if (recipeIndex < 0 || recipeIndex >= SimpleRecipe.values().length) {
            throw new IllegalArgumentException("unknown registration recipe");
        }
        SimpleRecipe recipe = SimpleRecipe.values()[recipeIndex];
        if (recipe == SimpleRecipe.MOVING_CELLS && longitudinal) {
            throw new IllegalArgumentException("Moving cells uses a benchmark-backed frame-to-frame recipe; "
                    + "turn off longitudinal mode");
        }
        SelectionMode mode = longitudinal ? SelectionMode.LONGITUDINAL_ACCURACY
                : recipe == SimpleRecipe.MOVING_CELLS ? SelectionMode.RECOMMENDED
                : SelectionMode.AUTOMATIC;
        return RelativeIntensityPatternParameters.builder()
                .recommendation(recipe.imageType, recipe.motionType)
                .selectionMode(mode).channel(channel).slice(0).build();
    }

    static int simpleRecipeIndex(RelativeIntensityPatternParameters parameters) {
        if (parameters == null) return SimpleRecipe.LANDMARKS.ordinal();
        if (parameters.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE) {
            return SimpleRecipe.BRIGHT_DIM.ordinal();
        }
        if (parameters.imageType == ImageType.DENSE_FLUORESCENCE) {
            return SimpleRecipe.MOVING_CELLS.ordinal();
        }
        return SimpleRecipe.LANDMARKS.ordinal();
    }

    @Override
    public void run(String arg) {
        ImagePlus image = WindowManager.getCurrentImage();
        if (image == null) {
            IJ.error("Relative-Intensity Pattern Registration", "No active image. Open a time series before running this command.");
            return;
        }
        String options = Macro.getOptions();
        boolean headless = GraphicsEnvironment.isHeadless();
        try {
            if (options != null || headless) {
                execute(image, MacroOptionsParser.parse(options == null ? "" : options), !headless);
            } else {
                RelativeIntensityPatternParameters parameters = dialog(image, null);
                if (parameters == null) return;
                if (Recorder.record) {
                    Recorder.recordString("run(\"" + COMMAND + "\", \""
                            + new RelativeIntensityPatternDialogModel(parameters).toMacroOptions() + "\");\n");
                }
                execute(image, parameters, true);
            }
        } catch (CancellationException cancelled) {
            IJ.showProgress(0.0);
            IJ.showStatus("Relative-intensity pattern registration cancelled");
            IJ.log("Relative-Intensity Pattern Registration: cancelled by user.");
        } catch (RuntimeException error) {
            IJ.error("Relative-Intensity Pattern Registration", message(error));
        }
    }

    private static void execute(ImagePlus image, RelativeIntensityPatternParameters parameters, boolean show) {
        IJ.resetEscape();
        IJ.showStatus("RIPR: checking whether the selected channel can be registered");
        for (Registration.Warning warning : Registration.preflight(
                StackFrames.of(image, parameters.channel, parameters.slice),
                parameters.registrationOptions())) {
            if (warning.kind != Registration.Warning.Kind.LOW_LOCALISABILITY) continue;
            // Logged, never a dialog. A modal stop here read as "the plugin is broken", and the
            // measure is calibrated on noisy recordings: clean data can score below the threshold
            // and still register to a hundredth of a pixel. Judge the run by its reported residual.
            IJ.log("Relative-Intensity Pattern Registration: channel " + parameters.channel
                    + " ranks poorly for movement estimation; registering anyway. " + warning.message);
        }
        LiveProgress progress = new LiveProgress(image, parameters);
        RelativeIntensityPatternResult result;
        try {
            result = RelativeIntensityPatternRegistration.register(image, parameters, progress,
                    progress::cancelled);
        } finally {
            progress.close();
        }
        if (show) result.correctedImage().show();
        Registration.Result registration = result.registration();
        // State every value that was used, not only the name of a recipe. A run log that says
        // "automatic" and nothing else cannot be reproduced by hand.
        IJ.log("Relative-Intensity Pattern Registration settings: " + result.provenance());
        IJ.log("Relative-Intensity Pattern Registration: median log-ratio residual "
                + format(registration.medianResidualBefore()) + " -> "
                + format(registration.medianResidualAfter()) + "; "
                + registration.workers + " worker(s), " + registration.levels + " pyramid level(s).");
        if (result.longitudinalDiagnostics() != null) {
            ripr.core.LongitudinalRegistration.Diagnostics diagnostic =
                    result.longitudinalDiagnostics();
            IJ.log("Relative-Intensity Pattern Registration longitudinal route: "
                    + diagnostic.route + "; bright reference="
                    + diagnostic.brightReferenceFrame + "; dim reference="
                    + diagnostic.dimReferenceFrame + "; persistent jumps="
                    + java.util.Arrays.toString(diagnostic.persistentJumpFrames)
                    + "; rigid jumps=" + java.util.Arrays.toString(diagnostic.rigidJumpFrames)
                    + "; final jump=" + diagnostic.endpointJumpFrame + ".");
        }
        double maxAngle = 0;
        for (ripr.core.Transform transform : registration.cumulative) {
            maxAngle = Math.max(maxAngle, Math.abs(Math.toDegrees(transform.theta)));
        }
        if (result.parameters().fitRotation) {
            IJ.log("Relative-Intensity Pattern Registration: maximum recovered absolute rotation "
                    + format(maxAngle) + " degrees.");
            if (result.parameters().incrementalRotation) {
                IJ.log("Relative-Intensity Pattern Registration: incremental rotation accepted for "
                        + registration.rotationAcceptedPairs() + " pair(s) and declined for "
                        + registration.rotationDeclinedPairs() + " pair(s); minimum residual gain "
                        + format(result.parameters().minimumRotationResidualGain) + ".");
            }
        }
        if (registration.eventRotations != null) {
            IJ.log("Relative-Intensity Pattern Registration rotation events: "
                    + "event_frame,delta_degrees,cumulative_degrees,candidate_pairs,"
                    + "usable_pairs,inlier_pairs,spread_degrees,status");
            for (ripr.core.RotationEventResult.Event event
                    : registration.eventRotations.events) {
                IJ.log("Relative-Intensity Pattern Registration rotation event: " + event.publicFrame() + ","
                        + format(Math.toDegrees(event.deltaTheta)) + ","
                        + format(Math.toDegrees(event.cumulativeTheta)) + ","
                        + event.candidatePairs + "," + event.usablePairs + ","
                        + event.inlierPairs + ","
                        + format(Math.toDegrees(event.circularMad)) + "," + event.status);
            }
        }
        for (Registration.Warning warning : registration.warnings) {
            IJ.log("Relative-Intensity Pattern Registration warning: " + warning.message);
        }
        IJ.showProgress(1.0);
        IJ.showStatus("Relative-intensity pattern registration complete");
        IJ.log("Relative-Intensity Pattern Registration: complete in "
                + duration(System.currentTimeMillis() - progress.startedAt()) + ".");
    }

    private static RelativeIntensityPatternParameters dialog(ImagePlus image, RelativeIntensityPatternParameters loaded) {
        final RelativeIntensityPatternParameters[] swept = {loaded};
        GenericDialog dialog = new GenericDialog("Relative-Intensity Pattern Registration");
        int defaultRecipe = simpleRecipeIndex(loaded);
        int defaultChannel = loaded == null ? Math.max(1, Math.min(image.getNChannels(), image.getC()))
                : Math.min(image.getNChannels(), loaded.channel);
        boolean defaultLongitudinal = loaded == null
                ? true : loaded.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY;
        dialog.addMessage("Choose a recipe, the channel used to estimate movement, and whether to\n"
                + "use the whole recording. Fitting, rotation, and output controls are under\n"
                + "Advanced settings.");
        String[] recipeLabels = simpleRecipeLabels();
        dialog.addChoice("Recipe", recipeLabels, recipeLabels[defaultRecipe]);
        String[] channelNames = channelLabels(image);
        dialog.addChoice("Channel used to estimate movement", channelNames,
                channelNames[defaultChannel - 1]);
        dialog.addCheckbox("Use longitudinal mode (whole recording)", defaultLongitudinal);
        Choice recipeChoice = (Choice) dialog.getChoices().get(0);
        Checkbox longitudinalBox = (Checkbox) dialog.getCheckboxes().get(0);
        recipeChoice.addItemListener(event -> {
            if (recipeChoice.getSelectedIndex() == SimpleRecipe.MOVING_CELLS.ordinal()) {
                longitudinalBox.setState(false);
            }
        });
        dialog.addCheckbox("Show advanced settings before running", false);
        dialog.addMessage("Longitudinal mode uses the fixed whole-recording route. Moving cells\n"
                + "uses a benchmark-backed frame-to-frame recipe and therefore keeps longitudinal mode off.");
        dialog.addButton("Advanced parameter sweep...", new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                try {
                    RelativeIntensityPatternParameters current = readMainControls(dialog, swept[0]);
                    if (current == null) return;
                    if (current.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY) {
                        throw new IllegalArgumentException(
                                "Longitudinal maximum accuracy is one fixed whole-recording route; "
                                + "choose Manual to sweep ordinary recipe settings");
                    }
                    if (current.selectionMode == SelectionMode.AUTOMATIC) {
                        current = resolveAutomatic(image, current).parameters;
                        loadMainControls(dialog, current);
                    }
                    RelativeIntensityPatternParameters chosen = RelativeIntensityPatternSweepDialog.show(dialog, image, current);
                    if (chosen != null) {
                        swept[0] = chosen;
                        loadMainControls(dialog, chosen);
                    }
                } catch (RuntimeException error) {
                    IJ.error("Parameter Sweep", message(error));
                }
            }
        });
        dialog.setOKLabel("Register");
        dialog.showDialog();
        if (dialog.wasCanceled()) return null;

        RelativeIntensityPatternParameters initial = readMainControls(dialog, swept[0]);
        if (initial == null) return null;
        Vector checkboxes = dialog.getCheckboxes();
        boolean review = ((Checkbox) checkboxes.get(1)).getState();
        String automaticNote = null;
        if (initial.selectionMode == SelectionMode.AUTOMATIC) {
            AutomaticRegistrationSelector.Result automatic = resolveAutomatic(image, initial);
            automaticNote = automaticExplanation(automatic);
            // Resolved here, so mark it resolved: left as AUTOMATIC the engine would run the
            // whole selection a second time inside register(). What it chose is logged either
            // way, so an unticked box is quiet, never silent.
            initial = automatic.parameters.toBuilder()
                    .selectionMode(SelectionMode.MANUAL).build();
        }
        if (initial.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY && review) {
            IJ.log("Relative-Intensity Pattern Registration: Longitudinal maximum accuracy is a "
                    + "fixed whole-recording route, so ordinary pairwise settings were not opened.");
            return initial;
        }
        if (!review) return initial;
        RelativeIntensityPatternParameters edited = advancedDialog(initial, automaticNote);
        if (edited == null) return null;
        return edited;
    }

    private static RelativeIntensityPatternParameters readMainControls(GenericDialog dialog,
                                                        RelativeIntensityPatternParameters swept) {
        Vector choices = dialog.getChoices();
        Vector checkboxes = dialog.getCheckboxes();
        int recipeIndex = ((Choice) choices.get(0)).getSelectedIndex();
        int channel = ((Choice) choices.get(1)).getSelectedIndex() + 1;
        boolean longitudinal = ((Checkbox) checkboxes.get(0)).getState();
        SimpleRecipe recipe = SimpleRecipe.values()[recipeIndex];
        // Keep a sweep's explicit values when the user leaves its recipe and mode unchanged.
        if (!longitudinal && swept != null && swept.imageType == recipe.imageType
                && swept.motionType == recipe.motionType
                && swept.selectionMode != SelectionMode.LONGITUDINAL_ACCURACY) {
            return swept.toBuilder().channel(channel).selectionMode(SelectionMode.MANUAL).build();
        }
        return simpleParameters(recipeIndex, channel, longitudinal);
    }

    private static void loadMainControls(GenericDialog dialog, RelativeIntensityPatternParameters parameters) {
        Vector choices = dialog.getChoices();
        ((Choice) choices.get(0)).select(simpleRecipeIndex(parameters));
        ((Choice) choices.get(1)).select(Math.max(0, parameters.channel - 1));
        Vector checkboxes = dialog.getCheckboxes();
        ((Checkbox) checkboxes.get(0)).setState(
                parameters.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY);
    }

    static RelativeIntensityPatternParameters advancedDialog(RelativeIntensityPatternParameters p) {
        return advancedDialog(p, null);
    }

    private static RelativeIntensityPatternParameters advancedDialog(RelativeIntensityPatternParameters p, String automaticNote) {
        RelativeIntensityPatternPreset preset = RelativeIntensityPatternRecommendations.forTypes(p.imageType, p.motionType);
        RegistrationRecipe loadedRecipe = RegistrationRecipe.of(p);
        GenericDialog dialog = new GenericDialog("Relative-Intensity Pattern Registration - All Settings");
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
        dialog.addChoice("Rotation handling", labels(RotationMode.values()),
                p.rotationMode.label());
        dialog.addNumericField("Maximum rotation per event or compared frame (degrees)",
                p.maxRotationDegrees, 2);
        dialog.addStringField("First frames after remounting (1-based)",
                join(p.rotationEventFrames()), 18);
        dialog.addNumericField("Frames used on each side of an event", p.rotationEventWindow, 0);
        dialog.addNumericField("Step outlier repair (median deviations; 0 = off)", p.outlierMads, 1);
        dialog.addStringField("Protect repaired step above fit improvement (0-1; off = disabled)",
                optional(p.outlierProtectionResidualGain), 8);
        dialog.addNumericField("Maximum iterations per level", p.maxIterations, 0);
        dialog.addNumericField("Maximum sampled pixels", p.maxSamples, 0);
        dialog.addNumericField("Minimum usable pixel fraction", p.minValidFraction, 2);
        dialog.addNumericField("Worker threads (0 = automatic)", p.threads, 0);

        dialog.addMessage("6. Output image\n"
                + "Nearest-neighbour rotation preserves label values but can make intensity\n"
                + "images jagged; use bilinear, bicubic or Fourier interpolation for intensity data.");
        dialog.addChoice("Interpolation", names(Warper.Interpolation.values()),
                p.interpolation.name());
        dialog.addCheckbox("Crop to common valid field", p.crop);
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
        RotationMode rotationMode = RotationMode.values()[dialog.getNextChoiceIndex()];
        double maxRotationDegrees = dialog.getNextNumber();
        int[] rotationEvents = parseIntegerList(dialog.getNextString(), "rotation events");
        int rotationEventWindow = integer(dialog.getNextNumber(), "rotation event window");
        double outlierMads = dialog.getNextNumber();
        double outlierProtectionResidualGain = optional(
                dialog.getNextString(), "outlier protection residual gain");
        int iterations = integer(dialog.getNextNumber(), "iterations");
        int maxSamples = integer(dialog.getNextNumber(), "maximum sampled pixels");
        double minValid = dialog.getNextNumber();
        int threads = integer(dialog.getNextNumber(), "threads");
        Warper.Interpolation interpolation =
                Warper.Interpolation.valueOf(dialog.getNextChoice());
        boolean crop = dialog.getNextBoolean();

        // A whole-window correlation has no per-pixel support to mask, and the parameter bundle
        // refuses the pair rather than ignoring one of them. Telling the user which setting is
        // being dropped, once, beats an exception naming a field they did not think they set.
        if (estimator != PairEstimator.Kind.LOG_RATIO_FIT
                && pixelSelection != PixelSelectionStrategy.NONE) {
            ij.IJ.log("Relative-Intensity Pattern Registration: spatial pixel removal is a second pass of the "
                    + "log-ratio fit and has no meaning for " + estimator.label().toLowerCase()
                    + "; it has been switched off for this run.");
            pixelSelection = PixelSelectionStrategy.NONE;
        }

        RelativeIntensityPatternParameters edited = RelativeIntensityPatternParameters.builder()
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
                .outlierProtectionResidualGain(outlierProtectionResidualGain)
                .rotationMode(rotationMode)
                .rotationEventFrames(rotationMode == RotationMode.KNOWN_EVENTS
                        ? rotationEvents : new int[0])
                .rotationEventWindow(rotationEventWindow)
                .maxRotationDegrees(maxRotationDegrees)
                .rotationRecipeId(rotationMode == RotationMode.CONTINUOUS ? p.rotationRecipeId : "")
                .globalRotationProposal(rotationMode == RotationMode.CONTINUOUS
                        && p.globalRotationProposal)
                .rotationSelectorStep90(rotationMode == RotationMode.CONTINUOUS
                        ? p.rotationSelectorStep90 : Double.NaN)
                .minimumRotationResidualGain(p.minimumRotationResidualGain)
                .maxIterations(iterations).maxSamples(maxSamples).minValidFraction(minValid)
                .threads(threads).interpolation(interpolation).crop(crop)
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
    private static String provenanceAfterEditing(RelativeIntensityPatternParameters p, RegistrationRecipe loaded,
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

    private static String header(RelativeIntensityPatternParameters p, RelativeIntensityPatternPreset preset, String automaticNote) {
        StringBuilder out = new StringBuilder();
        out.append(p.selectionMode.label()).append(" settings. Every value below is editable.\n");
        if (automaticNote != null) {
            out.append("Automatic decision for this recording:\n")
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
            ImagePlus image, RelativeIntensityPatternParameters parameters) {
        IJ.showStatus(AutomaticRegistrationSelector.requiresRecordingEvidence(
                parameters.imageType, false)
                ? "Relative-intensity pattern registration: measuring the recording to choose settings"
                : "Relative-intensity pattern registration: resolving the validated automatic policy");
        AutomaticRegistrationSelector.Result result = RelativeIntensityPatternRegistration.resolveAutomaticSettings(
                image, parameters);
        IJ.log("Relative-Intensity Pattern Registration Automatic model "
                + AutomaticRegistrationSelectorModel.MODEL_VERSION + ": "
                + result.explanation());
        AutomaticRotationSelector.Result rotation =
                AutomaticRotationSelector.fromResolved(result.parameters);
        if (rotation != null) {
            IJ.log("Relative-Intensity Pattern Registration " + rotation.explanation());
        }
        return result;
    }

    private static String automaticExplanation(AutomaticRegistrationSelector.Result result) {
        AutomaticRotationSelector.Result rotation =
                AutomaticRotationSelector.fromResolved(result.parameters);
        String translation = result.explanation() + "\nModel: "
                + AutomaticRegistrationSelectorModel.MODEL_VERSION;
        return rotation == null ? translation
                : translation + "\n\n" + rotation.explanation();
    }

    /** Visible heartbeat for expensive rigid fits; receives callbacks from several worker threads. */
    private static final class LiveProgress implements PairScheduler.Progress, AutoCloseable {
        private static final long LOG_INTERVAL_MS = 15_000L;
        private final long start = System.currentTimeMillis();
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger total = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final Timer heartbeat;
        private volatile String phase = "Preparing registration";
        private volatile int workers;
        private volatile long phaseStart = start;
        private volatile long lastLog = start;
        private volatile boolean closed;

        LiveProgress(ImagePlus image, RelativeIntensityPatternParameters parameters) {
            int frames = StackFrames.of(image, parameters.channel, parameters.slice).count();
            String movement = parameters.selectionMode == SelectionMode.LONGITUDINAL_ACCURACY
                    ? "whole-recording light-pulse and isolated-jump protection"
                    : parameters.fitRotation
                    ? !parameters.rotationRecipeId.isEmpty()
                        ? "separate automatic translation and rotation recipes, +/-"
                            + format(parameters.maxRotationDegrees) + " degrees, minimum residual "
                            + "gain=" + format(parameters.minimumRotationResidualGain)
                        : parameters.incrementalRotation
                        ? "selected translation plus incremental rotation, +/-"
                            + format(parameters.maxRotationDegrees) + " degrees, minimum residual "
                            + "gain=" + format(parameters.minimumRotationResidualGain)
                        : "rigid, +/-" + format(parameters.maxRotationDegrees) + " degrees"
                    : "translation only";
            IJ.log("Relative-Intensity Pattern Registration: started on '" + image.getTitle() + "' ("
                    + image.getWidth() + "x" + image.getHeight() + ", " + frames
                    + " timepoints); channel=" + parameters.channel + "; estimator="
                    + parameters.estimator.id() + "; " + movement
                    + "; reference=" + parameters.reference.name().toLowerCase(Locale.ROOT)
                    + "; lags=" + join(parameters.lags) + ". Press Esc to cancel.");
            IJ.showStatus("RIPR: preparing registration; Esc cancels");
            heartbeat = new Timer(1000, event -> refresh(true));
            heartbeat.setInitialDelay(1000);
            heartbeat.start();
        }

        @Override public void begin(int totalPairs, int workerCount) {
            phase = "Aligning frame pairs";
            phaseStart = System.currentTimeMillis();
            done.set(0);
            total.set(totalPairs);
            active.set(0);
            workers = workerCount;
            postLog("Relative-Intensity Pattern Registration: aligning " + totalPairs + " frame pairs with "
                    + workerCount + " worker(s).");
            postRefresh();
        }

        @Override public void taskStarted(int index, int totalPairs) {
            total.set(totalPairs);
            active.incrementAndGet();
            postRefresh();
        }

        @Override public void update(int completed, int totalPairs) {
            done.set(completed);
            total.set(totalPairs);
            active.updateAndGet(value -> Math.max(0, value - 1));
            postRefresh();
        }

        @Override public void phase(String name, int completed, int phaseTotal) {
            boolean changed = !name.equals(phase);
            if (changed) {
                phase = name;
                phaseStart = System.currentTimeMillis();
                active.set(0);
                postLog("Relative-Intensity Pattern Registration: " + name.toLowerCase(Locale.ROOT)
                        + (phaseTotal > 0 ? " (" + phaseTotal + " step(s))." : "."));
            }
            done.set(completed);
            total.set(phaseTotal);
            postRefresh();
        }

        boolean cancelled() {
            return IJ.escapePressed();
        }

        long startedAt() {
            return start;
        }

        private void postRefresh() {
            if (!closed) SwingUtilities.invokeLater(() -> refresh(false));
        }

        private void postLog(String message) {
            if (!closed) SwingUtilities.invokeLater(() -> IJ.log(message));
        }

        private void refresh(boolean allowHeartbeatLog) {
            if (closed) return;
            long now = System.currentTimeMillis();
            int completed = done.get();
            int count = total.get();
            int running = active.get();
            StringBuilder status = new StringBuilder("RIPR: ").append(phase);
            if (count > 0) {
                status.append(' ').append(completed).append('/').append(count);
                IJ.showProgress(completed, count);
            }
            if (running > 0) status.append("; ").append(running).append(" active");
            status.append("; ").append(duration(now - phaseStart));
            if (count > completed && completed > 0) {
                long eta = (now - phaseStart) * (count - completed) / completed;
                status.append("; ETA ").append(duration(eta));
            } else if (count > 0 && completed == 0 && running > 0) {
                status.append("; first results pending");
            }
            status.append("; Esc cancels");
            IJ.showStatus(status.toString());
            if (allowHeartbeatLog && now - lastLog >= LOG_INTERVAL_MS) {
                lastLog = now;
                IJ.log("Relative-Intensity Pattern Registration progress: " + status.substring("RIPR: ".length())
                        + (workers > 0 ? "; workers=" + workers : ""));
            }
        }

        @Override public void close() {
            closed = true;
            heartbeat.stop();
        }
    }

    private static String[] labels(ImageType[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < out.length; i++) out[i] = values[i].label();
        return out;
    }
    private static String[] channelLabels(ImagePlus image) {
        int count = Math.max(1, image.getNChannels());
        String[] labels = new String[count];
        for (int channel = 1; channel <= count; channel++) labels[channel - 1] = "Channel " + channel;
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
    private static int[] parseIntegerList(String text, String name) {
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
    private static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, remainder)
                : String.format(Locale.ROOT, "%dm %02ds", minutes, remainder);
    }
    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
