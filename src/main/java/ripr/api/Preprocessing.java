/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

/** Optional conventional image preparation applied only to the channel used to estimate movement. */
public enum Preprocessing {
    NONE("None"),
    GAUSSIAN_0_7("Gaussian smoothing (0.7 pixel)"),
    GAUSSIAN_1_0("Gaussian smoothing (1.0 pixel)"),
    GAUSSIAN_1_4("Gaussian smoothing (1.4 pixels)"),
    MEDIAN_3X3("Median denoising (3 by 3)"),
    ANSCOMBE("Photon-noise stabilisation"),
    ANSCOMBE_GAUSSIAN_1_0("Photon-noise stabilisation plus Gaussian smoothing"),
    UNSHARP_0_5("Mild sharpening"),
    LOCAL_CONTRAST_1_8("Remove background broader than 1/8 of the field"),
    LOCAL_CONTRAST_1_16("Remove background broader than 1/16 of the field"),
    LOCAL_CONTRAST_1_32("Remove background broader than 1/32 of the field"),
    STRUCTURAL_GRADIENT("Structural gradient magnitude");

    private final String label;

    Preprocessing(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static Preprocessing from(String value) {
        if (value == null) throw new IllegalArgumentException("preprocessing is null");
        String normalized = normalize(value);
        for (Preprocessing option : values()) {
            if (normalize(option.name()).equals(normalized) || normalize(option.label).equals(normalized)) {
                return option;
            }
        }
        throw new IllegalArgumentException("unknown preprocessing: " + value);
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
}
