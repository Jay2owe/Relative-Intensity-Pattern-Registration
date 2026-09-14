/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

/** Visual signal family used only to recommend log-ratio parameters. */
public enum ImageType {
    PHASE_CONTRAST("Phase contrast"),
    BRIGHTFIELD_DIC("Brightfield / differential interference contrast"),
    DENSE_FLUORESCENCE("Dense fluorescence"),
    SPARSE_LOW_LIGHT_FLUORESCENCE("Sparse / low-light fluorescence or bioluminescence"),
    FIDUCIAL_STATIC("Fiducial / nominally static reference");

    private final String label;

    ImageType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static ImageType from(String value) {
        if (value == null) throw new IllegalArgumentException("image type is null");
        String normalized = normalize(value);
        for (ImageType type : values()) {
            if (normalize(type.name()).equals(normalized) || normalize(type.label).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown image type: " + value);
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
}
