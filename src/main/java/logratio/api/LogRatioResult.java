/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import ij.ImagePlus;
import logratio.core.Registration;

/** Corrected image and diagnostics. The caller owns and should close the returned image. */
public final class LogRatioResult {
    private final ImagePlus corrected;
    private final Registration.Result registration;
    private final LogRatioParameters parameters;
    private final AutomaticRegistrationSelector.Result automaticSelection;

    LogRatioResult(ImagePlus corrected, Registration.Result registration,
                   LogRatioParameters parameters,
                   AutomaticRegistrationSelector.Result automaticSelection) {
        this.corrected = corrected;
        this.registration = registration;
        this.parameters = parameters;
        this.automaticSelection = automaticSelection;
    }

    public ImagePlus correctedImage() { return corrected; }
    public Registration.Result registration() { return registration; }
    /** The settings actually used, with every automatic choice already resolved to a value. */
    public LogRatioParameters parameters() { return parameters; }

    /** Null for an explicitly configured run; otherwise the measured automatic decision. */
    public AutomaticRegistrationSelector.Result automaticSelection() {
        return automaticSelection;
    }

    /** The complete recipe this run used, whether it was chosen automatically or typed by hand. */
    public RegistrationRecipe resolvedRecipe() {
        return RegistrationRecipe.of(parameters);
    }

    /** The evidence behind an automatic choice, or null when nothing was chosen automatically. */
    public AutomaticRegistrationSelector.Evidence evidence() {
        return automaticSelection == null ? null : automaticSelection.evidence;
    }

    /** How this run's settings were decided, in one line suitable for a run log. */
    public String provenance() {
        if (automaticSelection != null) return automaticSelection.explanation();
        if (!parameters.recipeProvenance.isEmpty()) {
            return parameters.recipeProvenance + "; " + resolvedRecipe().describe();
        }
        return parameters.selectionMode.label() + ": " + resolvedRecipe().describe();
    }

    public double medianResidualBefore() { return registration.medianResidualBefore(); }
    public double medianResidualAfter() { return registration.medianResidualAfter(); }
    public void close() { corrected.close(); }
}
