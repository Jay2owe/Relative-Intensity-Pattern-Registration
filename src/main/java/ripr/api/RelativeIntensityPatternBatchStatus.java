/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import java.io.File;

/** Immutable progress snapshot suitable for a graphical interface, log or headless caller. */
public final class RelativeIntensityPatternBatchStatus {
    public final int completedFiles;
    public final int totalFiles;
    public final int currentPair;
    public final int totalPairs;
    public final File currentFile;
    public final long elapsedMillis;
    /** -1 until at least one file has completed. */
    public final long estimatedRemainingMillis;

    RelativeIntensityPatternBatchStatus(int completedFiles, int totalFiles, int currentPair, int totalPairs,
                        File currentFile, long elapsedMillis, long estimatedRemainingMillis) {
        this.completedFiles = completedFiles;
        this.totalFiles = totalFiles;
        this.currentPair = currentPair;
        this.totalPairs = totalPairs;
        this.currentFile = currentFile;
        this.elapsedMillis = elapsedMillis;
        this.estimatedRemainingMillis = estimatedRemainingMillis;
    }

    public double fraction() {
        if (totalFiles == 0) return 1.0;
        double inside = totalPairs > 0 ? currentPair / (double) totalPairs : 0;
        return Math.min(1.0, (completedFiles + inside) / totalFiles);
    }
}
