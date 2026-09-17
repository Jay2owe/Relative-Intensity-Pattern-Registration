/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import logratio.core.PairAligner;
import logratio.core.Reconciler;
import logratio.core.Registration;
import logratio.core.RobustNorm;

/** One evidence-based recommendation; every option still belongs to the log-ratio engine. */
public final class LogRatioPreset {
    private final String name;
    private final String evidence;
    private final Preprocessing preprocessing;
    private final PixelSelectionStrategy pixelSelectionStrategy;
    private final Preprocessing pixelSelectionPreprocessing;
    private final double pixelRemovalPercent;
    private final RobustNorm norm;
    private final PairAligner.PixelSupport support;
    private final double gradientFraction;
    private final double floorPercentile;
    private final double ceilingPercentile;
    private final int maxIterations;
    private final int maxSamples;

    LogRatioPreset(String name, String evidence, Preprocessing preprocessing,
                   PixelSelectionStrategy pixelSelectionStrategy,
                   Preprocessing pixelSelectionPreprocessing, double pixelRemovalPercent,
                   RobustNorm norm,
                   PairAligner.PixelSupport support, double gradientFraction,
                   double floorPercentile, double ceilingPercentile,
                   int maxIterations, int maxSamples) {
        this.name = name;
        this.evidence = evidence;
        this.preprocessing = preprocessing;
        this.pixelSelectionStrategy = pixelSelectionStrategy;
        this.pixelSelectionPreprocessing = pixelSelectionPreprocessing;
        this.pixelRemovalPercent = pixelRemovalPercent;
        this.norm = norm;
        this.support = support;
        this.gradientFraction = gradientFraction;
        this.floorPercentile = floorPercentile;
        this.ceilingPercentile = ceilingPercentile;
        this.maxIterations = maxIterations;
        this.maxSamples = maxSamples;
    }

    public String name() { return name; }
    public String evidence() { return evidence; }
    public Preprocessing preprocessing() { return preprocessing; }
    public PixelSelectionStrategy pixelSelectionStrategy() { return pixelSelectionStrategy; }
    public Preprocessing pixelSelectionPreprocessing() { return pixelSelectionPreprocessing; }
    public double pixelRemovalPercent() { return pixelRemovalPercent; }
    public RobustNorm norm() { return norm; }
    public PairAligner.PixelSupport support() { return support; }
    public double gradientFraction() { return gradientFraction; }
    public double floorPercentile() { return floorPercentile; }
    public double ceilingPercentile() { return ceilingPercentile; }
    public int maxIterations() { return maxIterations; }
    public int maxSamples() { return maxSamples; }

    LogRatioPreset withPreprocessing(Preprocessing value) {
        if (value == Preprocessing.NONE) return this;
        return new LogRatioPreset(name + " + " + value.label(), evidence, value,
                pixelSelectionStrategy, pixelSelectionPreprocessing, pixelRemovalPercent, norm, support,
                gradientFraction, floorPercentile, ceilingPercentile, maxIterations, maxSamples);
    }

    LogRatioPreset withPixelSelection(PixelSelectionStrategy strategy,
                                      Preprocessing scoringFilter, double removePercent) {
        if (strategy == PixelSelectionStrategy.NONE) return this;
        return new LogRatioPreset(name + " + " + strategy.label(), evidence, preprocessing,
                strategy, scoringFilter, removePercent, norm, support, gradientFraction,
                floorPercentile, ceilingPercentile, maxIterations, maxSamples);
    }

    /** Fresh engine settings so callers may safely tune the returned object. */
    public Registration.Options options() {
        Registration.Options options = new Registration.Options();
        options.reference = Reconciler.Reference.MULTILAG;
        options.aligner.norm = norm;
        options.aligner.support = support;
        options.aligner.gradientFraction = gradientFraction;
        options.intensityFloorPercentile = floorPercentile;
        options.saturationPercentile = ceilingPercentile;
        options.autoMaxShift = true;
        options.aligner.maxIterations = maxIterations;
        options.aligner.maxSamples = maxSamples;
        return options;
    }
}
