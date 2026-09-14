/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.AutomaticFilterFeatureTable;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.core.PairScheduler;
import ripr.core.Registration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes truth-free v2 evidence for the historical development recordings only. */
public final class RecordingEvidenceAudit {
    private RecordingEvidenceAudit() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path project = (args.length == 0 ? Paths.get("") : Paths.get(args[0]))
                .toAbsolutePath().normalize();
        Path controlled = project.resolve("library/benchmark/v2/benchmarks/controlled_motion");
        Path output = project.resolve("library/recording_adaptive_selector_v1/evidence")
                .resolve("development_features.csv");
        Files.createDirectories(output.getParent());
        Map<String, String> groups = sourceGroups(project.resolve(
                "docs/recording-adaptive-selector/source_split_manifest.csv"));
        StringBuilder table = new StringBuilder(AutomaticFilterFeatureTable.header()).append('\n');
        int valid = 0;
        long pilotNanos = 0;
        long featureNanos = 0;
        List<FullSelectorFactorialBenchmark.Recording> recordings =
                FullSelectorFactorialBenchmark.recordings(controlled);
        for (FullSelectorFactorialBenchmark.Recording recording : recordings) {
            RelativeIntensityPatternParameters category = FullSelectorTraining.categoryBase(recording);
            RelativeIntensityPatternParameters pilot = RelativeIntensityPatternRegistration.automaticSelectorPilot(category)
                    .toBuilder().threads(1).crop(false).build();
            ImagePlus image = IJ.openImage(recording.input.toString());
            if (image == null) throw new IOException("could not open " + recording.input);
            try {
                long started = System.nanoTime();
                Registration.Result provisional = RelativeIntensityPatternRegistration.estimate(image, pilot,
                        PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
                pilotNanos += System.nanoTime() - started;
                started = System.nanoTime();
                AutomaticRegistrationSelector.Evidence evidence =
                        AutomaticRegistrationSelector.measure(
                                StackFrames.of(image, category.channel, category.slice),
                                provisional.cumulative, category);
                featureNanos += System.nanoTime() - started;
                if (evidence.valid) valid++;
                String group = groups.get(recording.series);
                if (group == null) throw new IOException("no source group for " + recording.series);
                table.append(AutomaticFilterFeatureTable.row(recording.key(), group, evidence))
                        .append('\n');
            } finally {
                image.close();
            }
        }
        Files.write(output, table.toString().getBytes(StandardCharsets.UTF_8));
        String timing = "feature_contract_version="
                + AutomaticRegistrationSelector.FEATURE_CONTRACT_VERSION + '\n'
                + "recordings=" + recordings.size() + '\n'
                + "valid=" + valid + '\n'
                + String.format(Locale.ROOT, "pilot_seconds=%.9f%n", pilotNanos / 1e9)
                + String.format(Locale.ROOT, "feature_seconds=%.9f%n", featureNanos / 1e9);
        Files.write(output.getParent().resolve("development_feature_timing.txt"),
                timing.getBytes(StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT, "wrote %d truth-free rows (%d valid) to %s%n",
                recordings.size(), valid, output);
    }

    private static Map<String, String> sourceGroups(Path manifest) throws IOException {
        Map<String, String> groups = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            List<String> fields = FullSelectorFactorialBenchmark.fields(lines.get(i));
            if (fields.size() < 4 || !"development".equals(fields.get(3))) continue;
            groups.put(fields.get(0), fields.get(1));
        }
        return groups;
    }
}
