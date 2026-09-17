/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import java.io.File;

/** Final batch counts and the permanent per-file report. */
public final class LogRatioBatchResult {
    public final int discoveredFiles;
    public final int processedFiles;
    public final int skippedFiles;
    public final int errorFiles;
    public final boolean cancelled;
    public final long elapsedMillis;
    public final File outputDirectory;
    public final File reportFile;

    LogRatioBatchResult(int discoveredFiles, int processedFiles, int skippedFiles,
                        int errorFiles, boolean cancelled, long elapsedMillis,
                        File outputDirectory, File reportFile) {
        this.discoveredFiles = discoveredFiles;
        this.processedFiles = processedFiles;
        this.skippedFiles = skippedFiles;
        this.errorFiles = errorFiles;
        this.cancelled = cancelled;
        this.elapsedMillis = elapsedMillis;
        this.outputDirectory = outputDirectory;
        this.reportFile = reportFile;
    }
}
