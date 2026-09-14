/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.MotionType;
import ripr.api.SelectionMode;
import ripr.api.Preprocessing;
import ripr.api.PixelSelectionStrategy;
import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;
import ripr.core.RobustNorm;
import ripr.core.RotationMode;
import ripr.core.Warper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure parser for replayable ImageJ macro options. */
public final class MacroOptionsParser {
    private MacroOptionsParser() { }

    public static RelativeIntensityPatternParameters parse(String options) {
        return parseTokens(tokens(options == null ? "" : options));
    }

    static RelativeIntensityPatternParameters parseTokens(Map<String, String> input) {
        Map<String, String> values = new LinkedHashMap<>(input);
        ImageType image = ImageType.from(take(values, "image_type", "PHASE_CONTRAST"));
        MotionType motion = MotionType.from(take(values, "motion_type", "SUBPIXEL_RANDOM_WALK"));
        SelectionMode mode = selectionMode(values);
        boolean recommended = mode == SelectionMode.RECOMMENDED;
        boolean automaticFilters = mode == SelectionMode.AUTOMATIC;
        boolean longitudinal = mode == SelectionMode.LONGITUDINAL_ACCURACY;
        RotationMode rotationMode = rotationMode(values);

        RelativeIntensityPatternParameters.Builder builder = RelativeIntensityPatternParameters.builder();
        // The automatic image-type policy starts from the category recommendation, so load it here
        // too. A bundle parsed from a macro then reports the same base model the run will use.
        if (recommended || automaticFilters || longitudinal) builder.recommendation(image, motion);
        else builder.imageType(image).motionType(motion).useRecommendation(false);
        builder.selectionMode(mode);

        builder.channel(integer(values, "channel", 1))
                .estimationScale(number(values, "estimation_scale", 1.0))
                .slice(integer(values, "slice", 0))
                .reference(enumValue(values, "reference", Reconciler.Reference.class,
                        Reconciler.Reference.MULTILAG))
                .referenceFrame(integer(values, "reference_frame", 1))
                .lags(lags(take(values, "lags", "1,2,4,8,16")))
                .templateWindow(integer(values, "template_window", 5))
                .epsilon(number(values, "epsilon", 1.0))
                .removeOffset(flag(values, "remove_offset", false))
                .offsetPercentile(number(values, "offset_percentile", 1.0))
                .autoMaxShift(flag(values, "auto_max_shift", true))
                .maxShift(number(values, "max_shift", 30.0))
                .rotationMode(rotationMode)
                .rotationEventFrames(integerList(
                        take(values, "rotation_events", ""), "rotation_events"))
                .rotationEventWindow(integer(values, "rotation_event_window", 3))
                .maxRotationDegrees(number(values, "max_rotation_degrees", 10.0))
                .incrementalRotation(flag(values, "incremental_rotation", true))
                .minimumRotationResidualGain(
                        number(values, "minimum_rotation_residual_gain", 0.005))
                .rotationRecipeId(take(values, "rotation_recipe_id", ""))
                .globalRotationProposal(flag(values, "global_rotation_proposal", false))
                .rotationSelectorStep90(
                        optionalNumber(values, "rotation_selector_step90", Double.NaN))
                .outlierMads(number(values, "outlier_mads",
                        (recommended || automaticFilters || longitudinal)
                                && motion == MotionType.INTERMITTENT_JUMPS ? 0.0 : 6.0))
                .outlierProtectionResidualGain(optionalNumber(
                        values, "outlier_protection_residual_gain", Double.NaN))
                .minValidFraction(number(values, "min_valid", 0.10))
                .threads(integer(values, "threads", 0))
                .interpolation(enumValue(values, "interpolation", Warper.Interpolation.class,
                        Warper.Interpolation.NONE))
                .crop(flag(values, "crop", true));

        if (automaticFilters || longitudinal) {
            ensureNoFixedRouteOverrides(values, mode);
        }
        if (recommended) {
            ensureNoRecommendationOverrides(values);
        } else {
            if (!automaticFilters && !longitudinal) {
                builder.preprocessing(enumValue(values, "preprocessing", Preprocessing.class,
                                Preprocessing.NONE))
                        .pixelSelectionStrategy(enumValue(values, "pixel_selection",
                                PixelSelectionStrategy.class, PixelSelectionStrategy.NONE))
                        .pixelSelectionPreprocessing(enumValue(values, "mask_preprocessing",
                                Preprocessing.class, Preprocessing.NONE))
                        .pixelRemovalPercent(number(values, "pixel_removal", 25.0));
            }
            builder.gradientFraction(number(values, "gradient", 0.5))
                    .maxIterations(integer(values, "iterations", 25))
                    .maxSamples(integer(values, "max_samples", 200000));
        }

        if (values.containsKey("norm")) {
            builder.norm(enumValue(values, "norm", RobustNorm.class, RobustNorm.HUBER));
        }
        if (values.containsKey("support")) {
            builder.pixelSupport(enumValue(values, "support", PairAligner.PixelSupport.class,
                    PairAligner.PixelSupport.ALL));
        }
        // Manual replay records the selected estimator. Automatic and Recommended reject it above
        // because their validated complete recipe owns that choice.
        if (values.containsKey("estimator")) {
            builder.estimator(PairEstimator.Kind.of(values.remove("estimator")));
        }
        if (values.containsKey("floor")) builder.floorPercentile(optionalNumber(values, "floor"));
        if (values.containsKey("ceiling")) builder.ceilingPercentile(optionalNumber(values, "ceiling"));

        if (!values.isEmpty()) throw new IllegalArgumentException("unknown macro option: " + values.keySet().iterator().next());
        return builder.build();
    }

    /**
     * Resolve the one authoritative mode. The new {@code selection_mode} token wins; the older
     * {@code recommended}, {@code automatic_filters} and {@code manual} tokens keep working and are
     * rejected only when they contradict an explicit mode, so an old macro never changes meaning
     * silently.
     */
    private static SelectionMode selectionMode(Map<String, String> values) {
        String explicit = values.remove("selection_mode");
        boolean recommended = flag(values, "recommended", false);
        boolean automaticFilters = flag(values, "automatic_filters", false);
        boolean manual = values.remove("manual") != null;
        SelectionMode legacy = automaticFilters ? SelectionMode.AUTOMATIC
                : (recommended ? SelectionMode.RECOMMENDED
                        : (manual ? SelectionMode.MANUAL : null));
        if (explicit == null) return legacy == null ? SelectionMode.MANUAL : legacy;
        SelectionMode mode = SelectionMode.from(explicit);
        if (legacy != null && legacy != mode) {
            throw new IllegalArgumentException("selection_mode=" + mode.macroValue()
                    + " contradicts the older " + legacy.macroValue()
                    + " option; keep only one of them");
        }
        return mode;
    }

    /** Resolve explicit three-state rotation without changing any old fit_rotation macro. */
    private static RotationMode rotationMode(Map<String, String> values) {
        String explicit = values.remove("rotation_mode");
        boolean legacyPresent = values.containsKey("fit_rotation")
                || values.containsKey("no_fit_rotation");
        boolean legacyEnabled = flag(values, "fit_rotation", false);
        RotationMode legacy = legacyEnabled ? RotationMode.CONTINUOUS : RotationMode.OFF;
        if (explicit == null) return legacy;
        RotationMode mode = RotationMode.from(explicit);
        if (legacyPresent && mode != legacy) {
            throw new IllegalArgumentException("rotation_mode=" + mode.macroValue()
                    + " contradicts the older "
                    + (legacyEnabled ? "fit_rotation" : "no_fit_rotation")
                    + " option; keep only one rotation control");
        }
        return mode;
    }

    private static void ensureNoRecommendationOverrides(Map<String, String> values) {
        String[] recommendationFields = {"preprocessing", "pixel_selection", "mask_preprocessing",
                "pixel_removal", "norm", "support", "gradient", "floor", "ceiling",
                "iterations", "max_samples"};
        for (String field : recommendationFields) {
            if (values.containsKey(field)) {
                throw new IllegalArgumentException("recommended cannot be combined with " + field
                        + "; use manual to replay explicit settings");
            }
        }
    }

    private static void ensureNoFixedRouteOverrides(Map<String, String> values,
                                                    SelectionMode mode) {
        // The automatic image-type policy resolves support and the intensity band as well, so an
        // explicit value for any of them would be silently discarded rather than honoured.
        String[] fields = {"preprocessing", "pixel_selection", "mask_preprocessing", "pixel_removal",
                "estimator", "support", "gradient", "floor", "ceiling", "iterations",
                "max_samples"};
        for (String field : fields) {
            if (values.containsKey(field)) {
                throw new IllegalArgumentException("selection_mode=" + mode.macroValue()
                        + " cannot be combined with "
                        + field + "; remove the explicit value or use selection_mode=manual");
            }
        }
    }

    static Map<String, String> tokens(String input) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.List<String> parsed = new java.util.ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean bracketed = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '[') {
                if (bracketed) throw new IllegalArgumentException("nested brackets are not allowed in macro options");
                bracketed = true;
                token.append(ch);
            } else if (ch == ']') {
                if (!bracketed) throw new IllegalArgumentException("unmatched ] in macro options");
                bracketed = false;
                token.append(ch);
            } else if (Character.isWhitespace(ch) && !bracketed) {
                if (token.length() > 0) { parsed.add(token.toString()); token.setLength(0); }
            } else {
                token.append(ch);
            }
        }
        if (bracketed) throw new IllegalArgumentException("unclosed [ in macro options");
        if (token.length() > 0) parsed.add(token.toString());
        for (String rawToken : parsed) {
            String tokenText = rawToken;
            int equals = tokenText.indexOf('=');
            String key = (equals < 0 ? tokenText : tokenText.substring(0, equals)).trim().toLowerCase(java.util.Locale.ROOT);
            String value = equals < 0 ? "true" : tokenText.substring(equals + 1).trim();
            if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
            if (key.isEmpty() || value.isEmpty()) throw new IllegalArgumentException("invalid macro token: " + tokenText);
            if (out.put(key, value) != null) throw new IllegalArgumentException("duplicate macro option: " + key);
        }
        return out;
    }

    private static String take(Map<String, String> values, String key, String fallback) {
        String value = values.remove(key);
        return value == null ? fallback : value;
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.remove(key);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer: " + value); }
    }

    private static double number(Map<String, String> values, String key, double fallback) {
        String value = values.remove(key);
        if (value == null) return fallback;
        return parseNumber(key, value);
    }

    private static double optionalNumber(Map<String, String> values, String key) {
        String value = values.remove(key);
        if ("off".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value)) return Double.NaN;
        return parseNumber(key, value);
    }

    private static double optionalNumber(Map<String, String> values, String key,
                                         double fallback) {
        if (!values.containsKey(key)) return fallback;
        return optionalNumber(values, key);
    }

    private static double parseNumber(String key, String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be a number: " + value); }
    }

    private static boolean flag(Map<String, String> values, String key, boolean fallback) {
        String negative = "no_" + key;
        if (values.containsKey(negative)) {
            values.remove(negative);
            return false;
        }
        String value = values.remove(key);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false: " + value);
    }

    private static int[] lags(String value) {
        String[] parts = value.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("lags must be comma-separated integers: " + value); }
        }
        return out;
    }

    private static int[] integerList(String value, String key) {
        if (value == null || value.trim().isEmpty()) return new int[0];
        String[] parts = value.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        key + " must be comma-separated integers: " + value);
            }
        }
        return out;
    }

    private static <E extends Enum<E>> E enumValue(Map<String, String> values, String key,
                                                    Class<E> type, E fallback) {
        String value = values.remove(key);
        if (value == null) return fallback;
        try { return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown " + key + ": " + value); }
    }
}
