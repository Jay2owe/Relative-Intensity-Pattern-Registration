/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ij.ImagePlus;
import ripr.core.Registration;
import ripr.core.LongitudinalRegistration;
import ripr.core.RotationMode;

import java.util.Locale;

/** Corrected image and diagnostics. The caller owns and should close the returned image. */
public final class RelativeIntensityPatternResult {
    private final ImagePlus corrected;
    private final Registration.Result registration;
    private final RelativeIntensityPatternParameters parameters;
    private final AutomaticRegistrationSelector.Result automaticSelection;
    private final AutomaticRotationSelector.Result automaticRotationSelection;
    private final LongitudinalRegistration.Diagnostics longitudinalDiagnostics;

    RelativeIntensityPatternResult(ImagePlus corrected, Registration.Result registration,
                   RelativeIntensityPatternParameters parameters,
                   AutomaticRegistrationSelector.Result automaticSelection,
                   AutomaticRotationSelector.Result automaticRotationSelection,
                   LongitudinalRegistration.Diagnostics longitudinalDiagnostics) {
        this.corrected = corrected;
        this.registration = registration;
        this.parameters = parameters;
        this.automaticSelection = automaticSelection;
        this.automaticRotationSelection = automaticRotationSelection;
        this.longitudinalDiagnostics = longitudinalDiagnostics;
    }

    public ImagePlus correctedImage() { return corrected; }
    public Registration.Result registration() { return registration; }
    /** The settings actually used, with every automatic choice already resolved to a value. */
    public RelativeIntensityPatternParameters parameters() { return parameters; }

    /** Null for an explicitly configured run; otherwise the measured automatic decision. */
    public AutomaticRegistrationSelector.Result automaticSelection() {
        return automaticSelection;
    }

    /** Null unless a resolved automatic run used a separate frozen angle recipe. */
    public AutomaticRotationSelector.Result automaticRotationSelection() {
        return automaticRotationSelection;
    }

    /** Fixed-route decisions for Longitudinal maximum accuracy, otherwise null. */
    public LongitudinalRegistration.Diagnostics longitudinalDiagnostics() {
        return longitudinalDiagnostics;
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
        String movement = parameters.rotationMode == RotationMode.KNOWN_EVENTS
                ? String.format(Locale.ROOT,
                    "; known-event rotation at %s, window %d, maximum %.4f degrees per event; "
                    + "piecewise-constant angles and one final resampling",
                    java.util.Arrays.toString(parameters.rotationEventFrames()),
                    parameters.rotationEventWindow, parameters.maxRotationDegrees)
                : parameters.fitRotation
                ? automaticRotationSelection != null
                    ? String.format(Locale.ROOT,
                        "; translation refit at separately selected angles, maximum %.4f degrees"
                        + " per compared frame; accepted %d pair(s); declined %d pair(s)",
                        parameters.maxRotationDegrees,
                        registration.rotationAcceptedPairs(),
                        registration.rotationDeclinedPairs())
                    : parameters.incrementalRotation
                    ? String.format(Locale.ROOT,
                        "; selected translation plus incremental rotation, maximum %.4f degrees"
                        + " per compared frame; minimum full-frame residual gain %.4f; accepted %d"
                        + " pair(s); declined %d pair(s)",
                        parameters.maxRotationDegrees, parameters.minimumRotationResidualGain,
                        registration.rotationAcceptedPairs(),
                        registration.rotationDeclinedPairs())
                    : "; rigid translation + rotation, maximum "
                        + parameters.maxRotationDegrees + " degrees per compared frame"
                        + (parameters.lagAwareWarmStarts ? "; lag-aware rigid warm starts" : "")
                : "; translation only";
        String selection = automaticSelection != null
                ? automaticSelection.explanation()
                : !parameters.recipeProvenance.isEmpty()
                    ? parameters.recipeProvenance + "; " + resolvedRecipe().describe()
                    : parameters.selectionMode.label() + ": " + resolvedRecipe().describe();
        if (automaticRotationSelection != null) {
            selection += "; " + automaticRotationSelection.explanation();
        }
        return selection + movement;
    }

    public double medianResidualBefore() { return registration.medianResidualBefore(); }
    public double medianResidualAfter() { return registration.medianResidualAfter(); }
    public void close() { corrected.close(); }
}
