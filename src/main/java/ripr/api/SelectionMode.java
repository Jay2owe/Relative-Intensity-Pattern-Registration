/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

/**
 * How the settings for one run were decided. Exactly one mode applies, so "recommended" and
 * "automatic" can no longer be switched on together and leave the user guessing which one won.
 */
public enum SelectionMode {
    /** Load the measured recipe for the declared image and motion types, and change nothing else. */
    RECOMMENDED("Image-and-motion preset", "recommended"),
    /**
     * Apply the installed validated fixed recipe for the declared image and motion types, then show it.
     * The current installed model needs no recording evidence and performs no provisional pass.
     */
    AUTOMATIC("Automatic fixed recipe", "automatic"),
    /** Use the whole selected channel to protect long recordings from light pulses and remounts. */
    LONGITUDINAL_ACCURACY("Longitudinal maximum accuracy", "longitudinal_accuracy"),
    /** Use exactly the values shown in the settings dialog. */
    MANUAL("Manual", "manual");

    private final String label;
    private final String macroValue;

    SelectionMode(String label, String macroValue) {
        this.label = label;
        this.macroValue = macroValue;
    }

    public String label() {
        return label;
    }

    /** The token used by {@code selection_mode=} in macro options. */
    public String macroValue() {
        return macroValue;
    }

    @Override
    public String toString() {
        return label;
    }

    public static SelectionMode from(String value) {
        if (value == null) throw new IllegalArgumentException("selection mode is null");
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        for (SelectionMode mode : values()) {
            if (mode.name().equals(normalized)) return mode;
            if (mode.macroValue.toUpperCase(java.util.Locale.ROOT).equals(normalized)) return mode;
            if (mode.label.toUpperCase(java.util.Locale.ROOT)
                    .replaceAll("[^A-Z0-9]+", "_").equals(normalized)) return mode;
        }
        throw new IllegalArgumentException("unknown selection mode: " + value);
    }
}
