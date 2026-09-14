/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.RelativeIntensityPatternBatchParameters;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.Preprocessing;
import ripr.api.PixelSelectionStrategy;
import ripr.core.RotationMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.*;

public class RelativeIntensityPatternBatchMacroOptionsParserTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bracketedFolderPathsAndRegistrationSettingsRoundTrip() throws Exception {
        File input = temporary.newFolder("input stacks");
        File output = new File(temporary.getRoot(), "corrected stacks");
        RelativeIntensityPatternParameters registration = RelativeIntensityPatternParameters.builder()
                .useRecommendation(false).channel(2).slice(3)
                .preprocessing(Preprocessing.ANSCOMBE)
                .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE)
                .pixelSelectionPreprocessing(Preprocessing.GAUSSIAN_0_7)
                .pixelRemovalPercent(25).fitRotation(true).maxRotationDegrees(6.5)
                .crop(false).build();
        RelativeIntensityPatternBatchParameters original = RelativeIntensityPatternBatchParameters.builder(input, output, registration)
                .recursive(false).overwrite(true).build();
        String options = RelativeIntensityPatternBatchMacroOptionsParser.record(original);
        RelativeIntensityPatternBatchParameters parsed = RelativeIntensityPatternBatchMacroOptionsParser.parse(options);
        assertEquals(input.getCanonicalFile(), parsed.inputDirectory.getCanonicalFile());
        assertEquals(output.getCanonicalFile(), parsed.outputDirectory.getCanonicalFile());
        assertFalse(parsed.recursive);
        assertTrue(parsed.overwrite);
        assertEquals(2, parsed.registration.channel);
        assertEquals(3, parsed.registration.slice);
        assertEquals(Preprocessing.ANSCOMBE, parsed.registration.preprocessing);
        assertEquals(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE,
                parsed.registration.pixelSelectionStrategy);
        assertEquals(Preprocessing.GAUSSIAN_0_7,
                parsed.registration.pixelSelectionPreprocessing);
        assertFalse(parsed.registration.crop);
        assertTrue(parsed.registration.fitRotation);
        assertEquals(6.5, parsed.registration.maxRotationDegrees, 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void inputFolderIsRequired() {
        RelativeIntensityPatternBatchMacroOptionsParser.parse("output=[C:/out] recommended");
    }

    @Test
    public void automaticFilterSelectionIsPreservedForEveryBatchStack() throws Exception {
        File input = temporary.newFolder("automatic input");
        File output = new File(temporary.getRoot(), "automatic output");
        RelativeIntensityPatternParameters registration = RelativeIntensityPatternParameters.builder()
                .automaticFilterSelection(true).build();
        RelativeIntensityPatternBatchParameters original = RelativeIntensityPatternBatchParameters.builder(input, output, registration)
                .build();
        RelativeIntensityPatternBatchParameters replay = RelativeIntensityPatternBatchMacroOptionsParser.parse(
                RelativeIntensityPatternBatchMacroOptionsParser.record(original));
        assertTrue(replay.registration.automaticFilterSelection);
    }

    @Test
    public void knownEventBatchSettingsRoundTrip() throws Exception {
        File input = temporary.newFolder("event input");
        File output = new File(temporary.getRoot(), "event output");
        RelativeIntensityPatternParameters registration = RelativeIntensityPatternParameters.builder()
                .useRecommendation(false)
                .rotationMode(RotationMode.KNOWN_EVENTS)
                .rotationEventFrames(25, 51)
                .rotationEventWindow(4)
                .build();
        RelativeIntensityPatternBatchParameters original = RelativeIntensityPatternBatchParameters.builder(
                input, output, registration).build();

        RelativeIntensityPatternBatchParameters replay = RelativeIntensityPatternBatchMacroOptionsParser.parse(
                RelativeIntensityPatternBatchMacroOptionsParser.record(original));

        assertEquals(RotationMode.KNOWN_EVENTS, replay.registration.rotationMode);
        assertArrayEquals(new int[]{25, 51}, replay.registration.rotationEventFrames());
        assertEquals(4, replay.registration.rotationEventWindow);
    }
}
