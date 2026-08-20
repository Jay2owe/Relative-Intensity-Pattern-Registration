/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

/** Observed recording movement used only to recommend log-ratio parameters. */
public enum MotionType {
    CURVED_OSCILLATING_DRIFT("Curved / oscillating drift"),
    STEADY_DIRECTIONAL_DRIFT("Steady directional drift"),
    SUBPIXEL_RANDOM_WALK("Subpixel random walk"),
    INTERMITTENT_JUMPS("Intermittent jumps");

    private final String label;

    MotionType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static MotionType from(String value) {
        if (value == null) throw new IllegalArgumentException("motion type is null");
        String normalized = normalize(value);
        for (MotionType type : values()) {
            if (normalize(type.name()).equals(normalized) || normalize(type.label).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown motion type: " + value);
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
}
