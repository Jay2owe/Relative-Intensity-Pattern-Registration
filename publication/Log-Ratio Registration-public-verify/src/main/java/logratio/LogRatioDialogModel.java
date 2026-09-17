/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.ImageType;
import logratio.api.LogRatioParameters;
import logratio.api.MotionType;
import logratio.api.SelectionMode;

/** Swing-free bridge between the dialog controls, macro recorder and public API. */
public final class LogRatioDialogModel {
    private final LogRatioParameters parameters;

    public LogRatioDialogModel(LogRatioParameters parameters) {
        if (parameters == null) throw new IllegalArgumentException("parameters are null");
        this.parameters = parameters;
    }

    public LogRatioParameters parameters() { return parameters; }

    public String toMacroOptions() {
        StringBuilder out = new StringBuilder();
        add(out, "image_type", parameters.imageType.name());
        add(out, "motion_type", parameters.motionType.name());
        // Record the resolved settings, not a moving pointer to a recommendation table. A macro
        // therefore replays the exact registration after an upgrade. A dialog run reaches here with
        // every automatic choice already resolved, so the recorded mode is normally manual.
        add(out, "selection_mode", parameters.selectionMode.macroValue());
        add(out, "estimation_scale", parameters.estimationScale);
        boolean explicit = parameters.selectionMode == SelectionMode.MANUAL;
        if (explicit) {
            add(out, "preprocessing", parameters.preprocessing.name());
            add(out, "pixel_selection", parameters.pixelSelectionStrategy.name());
            add(out, "mask_preprocessing", parameters.pixelSelectionPreprocessing.name());
            add(out, "pixel_removal", parameters.pixelRemovalPercent);
        }
        add(out, "channel", parameters.channel);
        add(out, "slice", parameters.slice);
        add(out, "reference_frame", parameters.referenceFrame);
        add(out, "lags", join(parameters.lags));
        add(out, "template_window", parameters.templateWindow);
        add(out, "epsilon", parameters.epsilon);
        addFlag(out, parameters.removeOffset ? "remove_offset" : "no_remove_offset");
        add(out, "offset_percentile", parameters.offsetPercentile);
        add(out, "max_shift", parameters.maxShift);
        add(out, "outlier_mads", parameters.outlierMads);
        add(out, "min_valid", parameters.minValidFraction);
        add(out, "reference", parameters.reference.name());
        // Recorded in every mode, unlike the fields below. Two estimators mean a macro that names
        // only a recipe is no longer reproducible, and unlike norm or support this is not something
        // a recommended or automatic run resolves for itself: it is whatever the run actually used.
        add(out, "estimator", parameters.estimator.id());
        if (explicit) {
            // These are exactly the fields a recommended or automatic run resolves for itself.
            // Recording them in those modes would claim a control the mode does not honour.
            add(out, "norm", parameters.norm.name());
            add(out, "support", parameters.pixelSupport.name());
            add(out, "gradient", parameters.gradientFraction);
            add(out, "floor", optional(parameters.floorPercentile));
            add(out, "ceiling", optional(parameters.ceilingPercentile));
            addFlag(out, parameters.autoMaxShift ? "auto_max_shift" : "no_auto_max_shift");
            add(out, "iterations", parameters.maxIterations);
            add(out, "max_samples", parameters.maxSamples);
        }
        add(out, "threads", parameters.threads);
        add(out, "interpolation", parameters.interpolation.name());
        addFlag(out, parameters.crop ? "crop" : "no_crop");
        return out.toString();
    }

    public static LogRatioDialogModel recommended(ImageType image, MotionType motion) {
        return new LogRatioDialogModel(LogRatioParameters.builder().recommendation(image, motion).build());
    }

    private static String optional(double value) { return Double.isNaN(value) ? "off" : Double.toString(value); }
    private static String join(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) { if (i > 0) out.append(','); out.append(values[i]); }
        return out.toString();
    }
    private static void add(StringBuilder out, String key, Object value) {
        if (out.length() > 0) out.append(' ');
        out.append(key).append('=').append(value);
    }
    private static void addFlag(StringBuilder out, String flag) {
        if (out.length() > 0) out.append(' ');
        out.append(flag);
    }
}
