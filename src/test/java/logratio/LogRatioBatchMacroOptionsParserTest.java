/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.api.LogRatioBatchParameters;
import logratio.api.LogRatioParameters;
import logratio.api.Preprocessing;
import logratio.api.PixelSelectionStrategy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.*;

public class LogRatioBatchMacroOptionsParserTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bracketedFolderPathsAndRegistrationSettingsRoundTrip() throws Exception {
        File input = temporary.newFolder("input stacks");
        File output = new File(temporary.getRoot(), "corrected stacks");
        LogRatioParameters registration = LogRatioParameters.builder()
                .useRecommendation(false).channel(2).slice(3)
                .preprocessing(Preprocessing.ANSCOMBE)
                .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE)
                .pixelSelectionPreprocessing(Preprocessing.GAUSSIAN_0_7)
                .pixelRemovalPercent(25).crop(false).build();
        LogRatioBatchParameters original = LogRatioBatchParameters.builder(input, output, registration)
                .recursive(false).overwrite(true).build();
        String options = LogRatioBatchMacroOptionsParser.record(original);
        LogRatioBatchParameters parsed = LogRatioBatchMacroOptionsParser.parse(options);
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
    }

    @Test(expected = IllegalArgumentException.class)
    public void inputFolderIsRequired() {
        LogRatioBatchMacroOptionsParser.parse("output=[C:/out] recommended");
    }

    @Test
    public void automaticFilterSelectionIsPreservedForEveryBatchStack() throws Exception {
        File input = temporary.newFolder("automatic input");
        File output = new File(temporary.getRoot(), "automatic output");
        LogRatioParameters registration = LogRatioParameters.builder()
                .automaticFilterSelection(true).build();
        LogRatioBatchParameters original = LogRatioBatchParameters.builder(input, output, registration)
                .build();
        LogRatioBatchParameters replay = LogRatioBatchMacroOptionsParser.parse(
                LogRatioBatchMacroOptionsParser.record(original));
        assertTrue(replay.registration.automaticFilterSelection);
    }
}
