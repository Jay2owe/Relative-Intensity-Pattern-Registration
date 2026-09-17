/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

/**
 * How the settings for one run were decided. Exactly one mode applies, so "recommended" and
 * "automatic" can no longer be switched on together and leave the user guessing which one won.
 */
public enum SelectionMode {
    /** Load the measured recipe for the declared image and motion types, and change nothing else. */
    RECOMMENDED("Recommended", "recommended"),
    /**
     * Inspect the recording and resolve support, intensity band, filter and mask, then show them.
     *
     * <p>The plugin's default since the locked test of 2026-08-17, which it passed on every declared
     * limit. It can differ from {@link #RECOMMENDED} only for the image types where an override was
     * measured as both safe and better; for the others it returns the recommended recipe unchanged
     * and at no extra cost.
     */
    AUTOMATIC("Automatic full selection", "automatic"),
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
