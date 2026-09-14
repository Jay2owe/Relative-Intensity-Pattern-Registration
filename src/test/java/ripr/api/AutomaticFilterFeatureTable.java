/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ij.IJ;
import ij.ImagePlus;
import ripr.StackFrames;
import ripr.core.Transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Emits the exact production selector features for model fitting and grouped validation. */
public final class AutomaticFilterFeatureTable {
    private AutomaticFilterFeatureTable() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = args.length == 0 ? Paths.get("") : Paths.get(args[0]);
        Path root = project.toAbsolutePath().normalize()
                .resolve("library/benchmark/v2/benchmarks/controlled_motion");
        List<Path> inputs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName().toString().equals("00_input_uncorrected.tif"))
                    .forEach(inputs::add);
        }
        inputs.sort(Comparator.comparing(Path::toString));
        System.out.println(header());
        for (Path input : inputs) emit(root, input);
    }

    /** Exact truth-free production schema. Join identifiers are metadata, not model inputs. */
    public static String header() {
        StringBuilder header = new StringBuilder("recording_id,independent_group,image_type,"
                + "motion_type,feature_contract_version,evidence_valid,"
                + "evidence_out_of_distribution,evidence_reason");
        for (String name : AutomaticRegistrationSelector.FEATURE_NAMES) {
            header.append(',').append(csv(name));
        }
        return header.toString();
    }

    private static void emit(Path root, Path input) throws IOException {
        Path relative = root.relativize(input);
        String imageClass = relative.getName(0).toString();
        String series = relative.getName(1).toString();
        String motion = relative.getName(2).toString();
        ImagePlus image = IJ.openImage(input.toString());
        if (image == null) throw new IOException("could not open " + input);
        try {
            Path transforms = input.getParent().resolve(
                    "traditional_preprocessing/00_no_preprocessing/log_ratio_recommended_transforms.csv");
            Transform[] cumulative = readTransforms(transforms);
            RelativeIntensityPatternParameters base = RelativeIntensityPatternParameters.builder()
                    .recommendation(imageType(imageClass), MotionType.valueOf(motion))
                    .selectionMode(SelectionMode.MANUAL).build();
            AutomaticRegistrationSelector.Evidence evidence =
                    AutomaticRegistrationSelector.measure(StackFrames.of(image), cumulative, base);
            System.out.println(row(imageClass + "/" + series + "/" + motion, series, evidence));
        } finally {
            image.close();
        }
    }

    private static Transform[] readTransforms(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Transform[] out = new Transform[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",");
            out[i - 1] = new Transform(Double.parseDouble(fields[1]),
                    Double.parseDouble(fields[2]), Double.parseDouble(fields[3]));
        }
        return out;
    }

    public static String row(String recordingId, String independentGroup,
                             AutomaticRegistrationSelector.Evidence evidence) {
        AutomaticRegistrationSelector.Distribution distribution = evidence.distribution(
                AutomaticRegistrationSelectorModel.FEATURE_MEAN,
                AutomaticRegistrationSelectorModel.FEATURE_SCALE);
        String reason = evidence.valid ? distribution.reason : evidence.validityReason;
        StringBuilder row = new StringBuilder();
        row.append(csv(recordingId)).append(',').append(csv(independentGroup)).append(',')
                .append(evidence.imageType).append(',').append(evidence.motionType).append(',')
                .append(evidence.contractVersion).append(',').append(evidence.valid).append(',')
                .append(distribution.outOfDistribution).append(',').append(csv(reason));
        for (double value : evidence.vector()) row.append(',').append(format(value));
        return row.toString();
    }

    private static ImageType imageType(String imageClass) {
        if ("PHASE".equals(imageClass)) return ImageType.PHASE_CONTRAST;
        if ("SPARSE_LOWLIGHT".equals(imageClass)) {
            return ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE;
        }
        return ImageType.valueOf(imageClass);
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "NaN";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
