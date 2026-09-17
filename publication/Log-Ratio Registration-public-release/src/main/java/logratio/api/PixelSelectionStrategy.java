/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

/** How a second pass removes pixels before fitting the original, unfiltered intensities. */
public enum PixelSelectionStrategy {
    NONE("None"),
    REMOVE_LEAST_INFORMATIVE("Remove least spatially informative pixels"),
    REMOVE_MOST_INFORMATIVE("Remove most spatially informative pixels"),
    REMOVE_MOST_UNSTABLE("Remove most frame-to-frame unstable pixels"),
    REMOVE_LEAST_UNSTABLE("Remove least frame-to-frame unstable pixels"),
    REMOVE_MOST_LAG_GROWTH("Remove strongest change growth with frame gap"),
    REMOVE_LEAST_LAG_GROWTH("Remove weakest change growth with frame gap"),
    REMOVE_LOWEST_ANCHOR_TRUST("Remove lowest combined anchor trust"),
    REMOVE_HIGHEST_ANCHOR_TRUST("Remove highest combined anchor trust"),
    STRATIFIED_LOWEST_ANCHOR_TRUST("Remove lowest anchor trust within local groups");

    private final String label;

    PixelSelectionStrategy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean usesTemporalEvidence() {
        return this != NONE && this != REMOVE_LEAST_INFORMATIVE
                && this != REMOVE_MOST_INFORMATIVE;
    }

    @Override
    public String toString() {
        return label;
    }

    public static PixelSelectionStrategy from(String value) {
        if (value == null) throw new IllegalArgumentException("pixel selection is null");
        String normalized = normalize(value);
        for (PixelSelectionStrategy option : values()) {
            if (normalize(option.name()).equals(normalized)
                    || normalize(option.label).equals(normalized)) return option;
        }
        throw new IllegalArgumentException("unknown pixel selection: " + value);
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
}
