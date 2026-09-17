/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import logratio.core.PairAligner;
import logratio.core.RobustNorm;

/** Benchmark-derived parameter advice. It never selects a competing registration algorithm. */
public final class LogRatioRecommendations {
    private LogRatioRecommendations() { }

    public static LogRatioPreset forTypes(ImageType image, MotionType motion) {
        if (image == null || motion == null) {
            throw new IllegalArgumentException("image and motion types are required");
        }
        String evidence = "Best log-ratio configuration for " + image.label() + " with "
                + motion.label() + " in the balanced controlled benchmark.";
        LogRatioPreset base;
        switch (image) {
            case PHASE_CONTRAST:
                if (motion == MotionType.STEADY_DIRECTIONAL_DRIFT) base = mutual(evidence);
                else if (motion == MotionType.CURVED_OSCILLATING_DRIFT) base = woods(evidence);
                else base = huber(evidence);
                break;
            case BRIGHTFIELD_DIC:
                if (motion == MotionType.CURVED_OSCILLATING_DRIFT
                        || motion == MotionType.SUBPIXEL_RANDOM_WALK) base = huber(evidence);
                else base = woods(evidence);
                break;
            case DENSE_FLUORESCENCE:
                if (motion == MotionType.CURVED_OSCILLATING_DRIFT) base = floor25(evidence);
                else if (motion == MotionType.STEADY_DIRECTIONAL_DRIFT) base = huber(evidence);
                else base = mutual(evidence);
                break;
            case SPARSE_LOW_LIGHT_FLUORESCENCE:
                if (motion == MotionType.INTERMITTENT_JUMPS) base = tukeyGradient(evidence);
                else if (motion == MotionType.SUBPIXEL_RANDOM_WALK) base = ceiling10(evidence);
                else base = ceiling25(evidence);
                break;
            case FIDUCIAL_STATIC:
                if (motion == MotionType.STEADY_DIRECTIONAL_DRIFT) base = ceiling25(evidence);
                else if (motion == MotionType.SUBPIXEL_RANDOM_WALK) base = huber(evidence);
                else base = woods(evidence);
                break;
            default:
                throw new IllegalStateException("unhandled image type " + image);
        }
        base = base.withPreprocessing(recommendedPreprocessing(image, motion));
        if (image == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE
                && motion != MotionType.SUBPIXEL_RANDOM_WALK) {
            base = base.withPixelSelection(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                    Preprocessing.NONE, 25.0);
        }
        return base;
    }

    private static Preprocessing recommendedPreprocessing(ImageType image, MotionType motion) {
        if (image == ImageType.PHASE_CONTRAST && motion == MotionType.INTERMITTENT_JUMPS) {
            return Preprocessing.GAUSSIAN_0_7;
        }
        if (image == ImageType.PHASE_CONTRAST && motion == MotionType.SUBPIXEL_RANDOM_WALK) {
            return Preprocessing.GAUSSIAN_1_0;
        }
        if (image == ImageType.BRIGHTFIELD_DIC && motion == MotionType.SUBPIXEL_RANDOM_WALK) {
            return Preprocessing.MEDIAN_3X3;
        }
        if (image == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE
                && motion == MotionType.STEADY_DIRECTIONAL_DRIFT) {
            return Preprocessing.MEDIAN_3X3;
        }
        return Preprocessing.NONE;
    }

    private static LogRatioPreset huber(String evidence) {
        return preset("Huber weighting", evidence, RobustNorm.HUBER,
                PairAligner.PixelSupport.ALL, 0.5, Double.NaN, Double.NaN);
    }

    private static LogRatioPreset woods(String evidence) {
        return preset("Woods least squares", evidence, RobustNorm.LEAST_SQUARES,
                PairAligner.PixelSupport.ALL, 0.5, Double.NaN, Double.NaN);
    }

    private static LogRatioPreset tukeyGradient(String evidence) {
        return preset("Tukey weighting with gradient pixels", evidence, RobustNorm.TUKEY,
                PairAligner.PixelSupport.GRADIENT, 0.5, Double.NaN, Double.NaN);
    }

    private static LogRatioPreset mutual(String evidence) {
        return preset("Tukey weighting with shared significant edges", evidence, RobustNorm.TUKEY,
                PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT, 0.5, Double.NaN, Double.NaN,
                12, 50_000);
    }

    private static LogRatioPreset ceiling10(String evidence) {
        return preset("Tukey weighting excluding brightest 10 percent", evidence, RobustNorm.TUKEY,
                PairAligner.PixelSupport.GRADIENT, 0.5, Double.NaN, 90.0);
    }

    private static LogRatioPreset ceiling25(String evidence) {
        return preset("Tukey weighting excluding brightest 25 percent", evidence, RobustNorm.TUKEY,
                PairAligner.PixelSupport.GRADIENT, 0.5, Double.NaN, 75.0);
    }

    private static LogRatioPreset floor25(String evidence) {
        return preset("Tukey weighting excluding dimmest 25 percent", evidence, RobustNorm.TUKEY,
                PairAligner.PixelSupport.GRADIENT, 0.5, 25.0, Double.NaN);
    }

    private static LogRatioPreset preset(String name, String evidence, RobustNorm norm,
                                         PairAligner.PixelSupport support, double gradient,
                                         double floor, double ceiling) {
        return preset(name, evidence, norm, support, gradient, floor, ceiling, 25, 200_000);
    }

    private static LogRatioPreset preset(String name, String evidence, RobustNorm norm,
                                         PairAligner.PixelSupport support, double gradient,
                                         double floor, double ceiling, int iterations, int samples) {
        return new LogRatioPreset(name, evidence, Preprocessing.NONE,
                PixelSelectionStrategy.NONE, Preprocessing.NONE, 25.0,
                norm, support, gradient, floor, ceiling,
                iterations, samples);
    }
}
