/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.RelativeIntensityPatternBatchParameters;
import ripr.api.RelativeIntensityPatternParameters;

import java.io.File;
import java.util.Map;

/** Pure parser for replayable, no-dialog folder batches. */
final class RelativeIntensityPatternBatchMacroOptionsParser {
    private RelativeIntensityPatternBatchMacroOptionsParser() { }

    static RelativeIntensityPatternBatchParameters parse(String options) {
        Map<String, String> values = MacroOptionsParser.tokens(options == null ? "" : options);
        String input = required(values, "input");
        String output = required(values, "output");
        boolean recursive = flag(values, "recursive", true);
        boolean overwrite = flag(values, "overwrite", false);
        RelativeIntensityPatternParameters registration = MacroOptionsParser.parseTokens(values);
        return RelativeIntensityPatternBatchParameters.builder(new File(input), new File(output), registration)
                .recursive(recursive).overwrite(overwrite).build();
    }

    static String record(RelativeIntensityPatternBatchParameters parameters) {
        return "input=[" + safePath(parameters.inputDirectory) + "] output=["
                + safePath(parameters.outputDirectory) + "] "
                + (parameters.recursive ? "recursive " : "no_recursive ")
                + (parameters.overwrite ? "overwrite " : "no_overwrite ")
                + new RelativeIntensityPatternDialogModel(parameters.registration).toMacroOptions();
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.remove(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static boolean flag(Map<String, String> values, String key, boolean fallback) {
        if (values.remove("no_" + key) != null) return false;
        String value = values.remove(key);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false: " + value);
    }

    private static String safePath(File path) {
        String value = path.getAbsolutePath().replace('\\', '/');
        if (value.indexOf('[') >= 0 || value.indexOf(']') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("folder path contains characters that cannot be recorded safely");
        }
        return value;
    }
}
