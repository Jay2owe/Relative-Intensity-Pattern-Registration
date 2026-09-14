/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

/** How in-plane rotation is modelled during a registration. */
public enum RotationMode {
    /** Estimate translation only. */
    OFF("off", "Off"),
    /** Estimate rotation independently for every planned frame pair. */
    CONTINUOUS("continuous", "Search continuously"),
    /** Estimate angular jumps only at caller-supplied remount frames. */
    KNOWN_EVENTS("known_events", "Known remount frames");

    private final String macroValue;
    private final String label;

    RotationMode(String macroValue, String label) {
        this.macroValue = macroValue;
        this.label = label;
    }

    public String macroValue() { return macroValue; }
    public String label() { return label; }

    /** Parse a macro/API name. Case, spaces and hyphens do not matter. */
    public static RotationMode from(String value) {
        if (value == null) throw new IllegalArgumentException("rotation mode is required");
        String wanted = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        for (RotationMode mode : values()) {
            if (mode.macroValue.equals(wanted)
                    || mode.name().toLowerCase(java.util.Locale.ROOT).equals(wanted)) return mode;
        }
        throw new IllegalArgumentException("unknown rotation mode: " + value);
    }
}
