/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import java.io.File;

/** Immutable folder-batch input: one registration configuration reused for every discovered stack. */
public final class RelativeIntensityPatternBatchParameters {
    public final File inputDirectory;
    public final File outputDirectory;
    public final boolean recursive;
    public final boolean overwrite;
    public final RelativeIntensityPatternParameters registration;

    private RelativeIntensityPatternBatchParameters(Builder builder) {
        inputDirectory = builder.inputDirectory;
        outputDirectory = builder.outputDirectory;
        recursive = builder.recursive;
        overwrite = builder.overwrite;
        registration = builder.registration;
        validate();
    }

    public static Builder builder(File inputDirectory, File outputDirectory,
                                  RelativeIntensityPatternParameters registration) {
        return new Builder(inputDirectory, outputDirectory, registration);
    }

    private void validate() {
        if (inputDirectory == null || !inputDirectory.isDirectory()) {
            throw new IllegalArgumentException("input directory does not exist: " + inputDirectory);
        }
        if (outputDirectory == null) throw new IllegalArgumentException("output directory is null");
        if (outputDirectory.exists() && !outputDirectory.isDirectory()) {
            throw new IllegalArgumentException("output path is not a directory: " + outputDirectory);
        }
        if (registration == null) throw new IllegalArgumentException("registration parameters are null");
    }

    public static final class Builder {
        private final File inputDirectory;
        private final File outputDirectory;
        private final RelativeIntensityPatternParameters registration;
        private boolean recursive = true;
        private boolean overwrite;

        private Builder(File inputDirectory, File outputDirectory,
                        RelativeIntensityPatternParameters registration) {
            this.inputDirectory = inputDirectory;
            this.outputDirectory = outputDirectory;
            this.registration = registration;
        }

        public Builder recursive(boolean value) { recursive = value; return this; }
        public Builder overwrite(boolean value) { overwrite = value; return this; }
        public RelativeIntensityPatternBatchParameters build() { return new RelativeIntensityPatternBatchParameters(this); }
    }
}
