/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.LogRatioParameters;
import logratio.api.Preprocessing;
import logratio.api.PixelSelectionStrategy;
import logratio.api.RegistrationRecipe;
import logratio.api.SelectionMode;
import logratio.core.PairAligner;
import logratio.core.PairEstimator;
import logratio.core.Reconciler;
import logratio.core.RobustNorm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure parameter-grid parser and generator used by the interactive sweep. */
final class LogRatioSweepPlan {
    static final int MAX_COMBINATIONS = 24;

    enum Parameter {
        NONE("None"),
        ESTIMATOR("Pairwise estimator",
                "LOG_RATIO_FIT, AREA_CORRELATION, AREA_CORRELATION_NEWTON"),
        PREPROCESSING("Preprocessing", "NONE, GAUSSIAN_0_7, GAUSSIAN_1_0, MEDIAN_3X3"),
        PIXEL_SELECTION("Pixel removal strategy",
                "NONE, REMOVE_LEAST_INFORMATIVE, REMOVE_MOST_UNSTABLE, REMOVE_LOWEST_ANCHOR_TRUST"),
        MASK_PREPROCESSING("Pixel-mask scoring filter",
                "NONE, GAUSSIAN_0_7, GAUSSIAN_1_0, MEDIAN_3X3, ANSCOMBE"),
        PIXEL_REMOVAL("Eligible pixels removed (%)", "10, 25, 50, 75"),
        ESTIMATION_SCALE("Estimation scale", "1, 0.75, 0.5, 0.25"),
        ROBUST_WEIGHTING("Robust weighting", "HUBER, TUKEY, LEAST_SQUARES"),
        PIXEL_SUPPORT("Pixel evidence", "ALL, GRADIENT, MUTUAL_NOISE_GRADIENT"),
        GRADIENT_MULTIPLIER("Gradient multiplier", "0.25, 0.5, 1, 2"),
        BRIGHT_EXCLUSION("Brightest pixels excluded (%)", "off, 5, 10, 25"),
        DIM_EXCLUSION("Dimmest pixels excluded (%)", "off, 10, 25, 40"),
        REFERENCE_STRATEGY("Reference strategy", "MULTILAG, CONSECUTIVE, FIXED, ROLLING"),
        MAX_ITERATIONS("Maximum iterations", "12, 25, 50"),
        MAX_SAMPLES("Maximum sampled pixels", "50000, 200000, all"),
        MAX_SHIFT("Maximum shift (pixels)", "10, 30, 60");

        final String label;
        final String suggested;

        Parameter(String label) { this(label, ""); }
        Parameter(String label, String suggested) { this.label = label; this.suggested = suggested; }

        static String[] labels() {
            String[] out = new String[values().length];
            for (int i = 0; i < out.length; i++) out[i] = values()[i].label;
            return out;
        }

        static Parameter fromLabel(String label) {
            for (Parameter value : values()) if (value.label.equals(label)) return value;
            throw new IllegalArgumentException("unknown sweep parameter: " + label);
        }

        String canonical(String raw) {
            String value = raw.trim();
            switch (this) {
                case ESTIMATOR:
                    return PairEstimator.Kind.of(value).name();
                case PREPROCESSING:
                    return enumName(value, Preprocessing.class, label);
                case PIXEL_SELECTION:
                    return enumName(value, PixelSelectionStrategy.class, label);
                case MASK_PREPROCESSING:
                    return enumName(value, Preprocessing.class, label);
                case PIXEL_REMOVAL:
                    double removal = number(trimPercent(value), label);
                    if (!(removal > 0 && removal < 100)) {
                        throw new IllegalArgumentException(label + " must be in (0, 100)");
                    }
                    return compact(removal);
                case ESTIMATION_SCALE:
                    double scale = number(value, label);
                    if (!(scale > 0 && scale <= 1)) throw new IllegalArgumentException(label + " must be in (0, 1]");
                    return compact(scale);
                case ROBUST_WEIGHTING:
                    return enumName(value, RobustNorm.class, label);
                case PIXEL_SUPPORT:
                    return enumName(value, PairAligner.PixelSupport.class, label);
                case GRADIENT_MULTIPLIER:
                    double gradient = number(value, label);
                    if (gradient < 0) throw new IllegalArgumentException(label + " cannot be negative");
                    return compact(gradient);
                case BRIGHT_EXCLUSION:
                case DIM_EXCLUSION:
                    if (off(value)) return "off";
                    double percent = number(trimPercent(value), label);
                    if (!(percent > 0 && percent < 100)) throw new IllegalArgumentException(label + " must be off or in (0, 100)");
                    return compact(percent);
                case REFERENCE_STRATEGY:
                    return enumName(value, Reconciler.Reference.class, label);
                case MAX_ITERATIONS:
                    return Integer.toString(positiveInteger(value, label));
                case MAX_SAMPLES:
                    if ("all".equalsIgnoreCase(value)) return "all";
                    return Integer.toString(positiveInteger(value, label));
                case MAX_SHIFT:
                    double shift = number(value, label);
                    if (!(shift > 0)) throw new IllegalArgumentException(label + " must be greater than zero");
                    return compact(shift);
                case NONE:
                default:
                    throw new IllegalArgumentException("None cannot have values");
            }
        }

        LogRatioParameters apply(LogRatioParameters base, String value) {
            // A swept combination is a set of explicit values, never a request to re-resolve
            // them: leaving the mode on automatic would discard the very axis being swept.
            LogRatioParameters.Builder b = base.toBuilder().useRecommendation(false)
                    .selectionMode(SelectionMode.MANUAL);
            switch (this) {
                case ESTIMATOR:
                    // An area-correlation arm cannot carry the log-ratio fit's second-pass mask, and
                    // sweeping the estimator against a base recipe that has one would fail the whole
                    // combination rather than measure it. Dropping the mask on that arm alone is
                    // what makes the two arms of the sweep comparable.
                    PairEstimator.Kind estimator = PairEstimator.Kind.valueOf(value);
                    if (estimator != PairEstimator.Kind.LOG_RATIO_FIT) {
                        b.pixelSelectionStrategy(PixelSelectionStrategy.NONE);
                    }
                    return b.estimator(estimator).build();
                case PREPROCESSING: return b.preprocessing(Preprocessing.valueOf(value)).build();
                case PIXEL_SELECTION:
                    return b.pixelSelectionStrategy(PixelSelectionStrategy.valueOf(value)).build();
                case MASK_PREPROCESSING:
                    return b.pixelSelectionPreprocessing(Preprocessing.valueOf(value)).build();
                case PIXEL_REMOVAL:
                    return b.pixelRemovalPercent(Double.parseDouble(value)).build();
                case ESTIMATION_SCALE: return b.estimationScale(Double.parseDouble(value)).build();
                case ROBUST_WEIGHTING: return b.norm(RobustNorm.valueOf(value)).build();
                case PIXEL_SUPPORT: return b.pixelSupport(PairAligner.PixelSupport.valueOf(value)).build();
                case GRADIENT_MULTIPLIER: return b.gradientFraction(Double.parseDouble(value)).build();
                case BRIGHT_EXCLUSION:
                    return b.ceilingPercentile("off".equals(value) ? Double.NaN
                            : 100 - Double.parseDouble(value)).build();
                case DIM_EXCLUSION:
                    return b.floorPercentile("off".equals(value) ? Double.NaN
                            : Double.parseDouble(value)).build();
                case REFERENCE_STRATEGY: return b.reference(Reconciler.Reference.valueOf(value)).build();
                case MAX_ITERATIONS: return b.maxIterations(Integer.parseInt(value)).build();
                case MAX_SAMPLES: return b.maxSamples("all".equals(value)
                        ? Integer.MAX_VALUE : Integer.parseInt(value)).build();
                case MAX_SHIFT: return b.autoMaxShift(false).maxShift(Double.parseDouble(value)).build();
                default: return base;
            }
        }
    }

    static final class Axis {
        final Parameter parameter;
        final List<String> values;

        Axis(Parameter parameter, String text) {
            this.parameter = parameter;
            if (parameter == Parameter.NONE) {
                values = Collections.singletonList("");
                return;
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String raw : text.split("[,;]")) {
                if (!raw.trim().isEmpty()) unique.add(parameter.canonical(raw));
            }
            if (unique.isEmpty()) throw new IllegalArgumentException("enter at least one value for " + parameter.label);
            values = Collections.unmodifiableList(new ArrayList<>(unique));
        }
    }

    static final class Combination {
        final LogRatioParameters parameters;
        final String label;

        Combination(LogRatioParameters parameters, String label) {
            this.parameters = parameters;
            this.label = label;
        }

        /** The complete recipe this combination represents, support and band included. */
        RegistrationRecipe recipe() {
            return RegistrationRecipe.of(parameters);
        }

        /** Replayable macro options for exactly these values, with nothing left to resolve. */
        String macroOptions() {
            return new LogRatioDialogModel(parameters).toMacroOptions();
        }

        /** One line naming every swept value, for a results table or a saved sweep report. */
        String describe() {
            return recipe().describe();
        }
    }

    final List<Combination> combinations;

    LogRatioSweepPlan(LogRatioParameters base, Axis... axes) {
        if (base == null) throw new IllegalArgumentException("base parameters are null");
        List<Axis> active = new ArrayList<>();
        Set<Parameter> used = new LinkedHashSet<>();
        for (Axis axis : axes) {
            if (axis == null || axis.parameter == Parameter.NONE) continue;
            if (!used.add(axis.parameter)) throw new IllegalArgumentException(axis.parameter.label + " is selected more than once");
            active.add(axis);
        }
        if (active.isEmpty()) throw new IllegalArgumentException("select at least one parameter to sweep");
        long count = 1;
        for (Axis axis : active) count *= axis.values.size();
        if (count > MAX_COMBINATIONS) {
            throw new IllegalArgumentException("this grid has " + count + " combinations; reduce it to "
                    + MAX_COMBINATIONS + " or fewer");
        }
        List<Combination> out = new ArrayList<>();
        build(base, active, 0, new ArrayList<String>(), out);
        combinations = Collections.unmodifiableList(out);
    }

    private static void build(LogRatioParameters current, List<Axis> axes, int index,
                              List<String> labels, List<Combination> out) {
        if (index == axes.size()) {
            out.add(new Combination(current, join(labels)));
            return;
        }
        Axis axis = axes.get(index);
        for (String value : axis.values) {
            labels.add(axis.parameter.label + " = " + display(axis.parameter, value));
            build(axis.parameter.apply(current, value), axes, index + 1, labels, out);
            labels.remove(labels.size() - 1);
        }
    }

    private static String display(Parameter parameter, String value) {
        if ((parameter == Parameter.BRIGHT_EXCLUSION || parameter == Parameter.DIM_EXCLUSION)
                && !"off".equals(value)) return value + "%";
        if (parameter == Parameter.PIXEL_REMOVAL) return value + "%";
        if (parameter == Parameter.ESTIMATION_SCALE) return value + "x";
        return value;
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder("<html>");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append("<br>");
            out.append(values.get(i));
        }
        return out.append("</html>").toString();
    }

    private static String enumName(String value, Class<? extends Enum> type, String label) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try { return Enum.valueOf(type, normalized).name(); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("unknown " + label + ": " + value); }
    }

    private static double number(String value, String label) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(label + " must contain numbers: " + value); }
    }

    private static int positiveInteger(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " must contain positive integers: " + value);
        }
    }

    private static boolean off(String value) {
        return "off".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value) || "0".equals(value);
    }

    private static String trimPercent(String value) { return value.endsWith("%") ? value.substring(0, value.length() - 1) : value; }
    private static String compact(double value) { return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value); }
}
