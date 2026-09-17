/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import ij.IJ;
import ij.ImagePlus;
import logratio.StackFrames;
import logratio.core.Transform;

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
        StringBuilder header = new StringBuilder("image_class,series,motion,recipe");
        for (String name : AutomaticFilterSelector.IMAGE_FEATURE_NAMES) {
            header.append(',').append(csv(name));
        }
        for (String name : AutomaticFilterSelector.MOTION_FEATURE_NAMES) {
            header.append(',').append(csv(name));
        }
        System.out.println(header);
        for (Path input : inputs) emit(root, input);
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
            AutomaticFilterSelector.Evidence evidence = AutomaticFilterSelector.measure(
                    StackFrames.of(image), cumulative);
            StringBuilder row = new StringBuilder();
            row.append(imageClass).append(',').append(series).append(',').append(motion).append(',')
                    .append(recipe(imageClass, motion));
            for (double value : evidence.imageFeatures) row.append(',').append(format(value));
            for (double value : evidence.motionFeatures) row.append(',').append(format(value));
            System.out.println(row);
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

    private static String recipe(String imageClass, String motion) {
        if ("PHASE".equals(imageClass) && "INTERMITTENT_JUMPS".equals(motion)) return "GAUSSIAN_0_7";
        if ("PHASE".equals(imageClass) && "SUBPIXEL_RANDOM_WALK".equals(motion)) return "GAUSSIAN_1_0";
        if ("BRIGHTFIELD_DIC".equals(imageClass) && "SUBPIXEL_RANDOM_WALK".equals(motion)) {
            return "MEDIAN_3X3";
        }
        if ("SPARSE_LOWLIGHT".equals(imageClass) && "STEADY_DIRECTIONAL_DRIFT".equals(motion)) {
            return "MEDIAN_3X3_AND_SPATIAL_MASK_25";
        }
        if ("SPARSE_LOWLIGHT".equals(imageClass) && !"SUBPIXEL_RANDOM_WALK".equals(motion)) {
            return "SPATIAL_MASK_25";
        }
        return "NONE";
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "NaN";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
