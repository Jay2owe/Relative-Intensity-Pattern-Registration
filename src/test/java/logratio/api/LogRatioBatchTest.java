/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import logratio.core.Reconciler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class LogRatioBatchTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void discoveryIsSortedRecursiveAndExcludesNestedOutput() throws Exception {
        File input = temporary.newFolder("input");
        File sub = new File(input, "sub");
        File output = new File(input, "results");
        assertTrue(sub.mkdirs());
        assertTrue(output.mkdirs());
        Files.write(new File(input, "z.tif").toPath(), new byte[] {1});
        Files.write(new File(sub, "A.TIFF").toPath(), new byte[] {1});
        Files.write(new File(sub, "ignore.png").toPath(), new byte[] {1});
        Files.write(new File(output, "hidden.tif").toPath(), new byte[] {1});
        Files.write(new File(input, "old_registered.tif").toPath(), new byte[] {1});

        LogRatioBatchParameters parameters = LogRatioBatchParameters.builder(input, output,
                LogRatioParameters.builder().build()).recursive(true).build();
        List<File> found = LogRatioBatch.discover(parameters);
        assertEquals(2, found.size());
        assertEquals("A.TIFF", found.get(0).getName());
        assertEquals("z.tif", found.get(1).getName());
    }

    @Test
    public void sameInputAndOutputFolderStillDiscoversOriginals() throws Exception {
        File input = temporary.newFolder("same");
        Files.write(new File(input, "source.tif").toPath(), new byte[] {1});
        Files.write(new File(input, "source_registered.tif").toPath(), new byte[] {1});
        LogRatioBatchParameters parameters = LogRatioBatchParameters.builder(input, input,
                LogRatioParameters.builder().build()).build();
        assertEquals(1, LogRatioBatch.discover(parameters).size());
    }

    @Test
    public void processesStacksSequentiallyPreservesFoldersAndSkipsExistingOutputs() throws Exception {
        File input = temporary.newFolder("source");
        File nested = new File(input, "experiment_a");
        assertTrue(nested.mkdirs());
        saveStack(new File(input, "one.tif"), 11);
        saveStack(new File(nested, "two.ome.tif"), 22);
        File output = temporary.newFolder("corrected");
        LogRatioParameters registration = LogRatioParameters.builder()
                .useRecommendation(false).reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false).maxShift(4).maxIterations(4).maxSamples(3000)
                .threads(1).crop(false).build();
        LogRatioBatchParameters parameters = LogRatioBatchParameters.builder(input, output, registration)
                .recursive(true).overwrite(false).build();

        LogRatioBatchResult first = LogRatioBatch.run(parameters);
        assertEquals(2, first.processedFiles);
        assertEquals(0, first.errorFiles);
        assertTrue(new File(output, "one_registered.tif").isFile());
        File nestedOutput = new File(output, "experiment_a/two.ome_registered.tif");
        assertTrue(nestedOutput.isFile());
        ImagePlus reopened = IJ.openImage(nestedOutput.getAbsolutePath());
        assertNotNull(reopened);
        assertEquals(3, reopened.getStackSize());
        reopened.close();
        String report = new String(Files.readAllBytes(first.reportFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(report.contains("median_residual_before"));
        assertTrue(report.contains("processed"));

        LogRatioBatchResult second = LogRatioBatch.run(parameters);
        assertEquals(0, second.processedFiles);
        assertEquals(2, second.skippedFiles);
        assertEquals(0, second.errorFiles);
    }

    @Test
    public void badFileIsReportedWithoutStoppingTheRest() throws Exception {
        File input = temporary.newFolder("mixed");
        Files.write(new File(input, "a_bad.tif").toPath(), new byte[] {1, 2, 3});
        saveStack(new File(input, "b_good.tif"), 33);
        File output = temporary.newFolder("mixed_output");
        LogRatioParameters registration = LogRatioParameters.builder()
                .useRecommendation(false).reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false).maxShift(4).maxIterations(3).maxSamples(2000)
                .threads(1).crop(false).build();
        LogRatioBatchResult result = LogRatioBatch.run(LogRatioBatchParameters
                .builder(input, output, registration).build());
        assertEquals(1, result.processedFiles);
        assertEquals(1, result.errorFiles);
        assertTrue(new File(output, "b_good_registered.tif").isFile());
        String report = new String(Files.readAllBytes(result.reportFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(report.contains("error"));
        assertTrue(report.contains("processed"));
    }

    @Test
    public void estimateUsesCompletedStackAverageAndCurrentFraction() {
        assertEquals(-1, LogRatioBatch.remaining(0, 0, 4, 0));
        assertEquals(3000, LogRatioBatch.remaining(2000, 2, 4, 1));
        assertEquals(3500, LogRatioBatch.remaining(2000, 2, 4, 0.5));
        assertEquals(0, LogRatioBatch.remaining(2000, 2, 0, 0));
    }

    private static void saveStack(File file, long seed) {
        int size = 48;
        java.util.Random random = new java.util.Random(seed);
        float[] texture = new float[size * size];
        for (int i = 0; i < texture.length; i++) texture[i] = 20 + random.nextFloat() * 180;
        ImageStack stack = new ImageStack(size, size);
        for (int frame = 0; frame < 3; frame++) {
            float[] pixels = new float[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int source = x - frame;
                pixels[y * size + x] = source >= 0 ? texture[y * size + source] : 20;
            }
            stack.addSlice(new FloatProcessor(size, size, pixels));
        }
        ImagePlus image = new ImagePlus(file.getName(), stack);
        assertTrue(new FileSaver(image).saveAsTiffStack(file.getAbsolutePath()));
        image.close();
    }
}
