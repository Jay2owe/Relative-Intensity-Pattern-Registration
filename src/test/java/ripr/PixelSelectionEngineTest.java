/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.FrameSource;
import ripr.core.PairScheduler;
import ripr.core.Registration;
import ripr.api.PixelSelectionStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PixelSelectionEngineTest {
    @Test
    public void automaticMovementBoundIsResolvedBeforeSupportPyramidAllocation() {
        FrameSource source = texturedSource(64, 64);
        Registration.Options options = new Registration.Options();
        options.autoMaxShift = true;
        options.aligner.maxShift = 1;
        options.aligner.coarseRadiusBudget = 1;
        options.threads = 1;

        int levelsBeforeAutomaticBound = options.aligner.levelsFor(source.width(), source.height());
        Registration.Options effective = PixelSelectionEngine.prepareRawRefitOptions(source, options);
        int expectedLevels = effective.aligner.levelsFor(source.width(), source.height());

        assertTrue(!effective.autoMaxShift);
        assertTrue(expectedLevels > levelsBeforeAutomaticBound);
        assertEquals(4, expectedLevels);
    }

    /** Automatic image types that use two fits must not lose the rich live-progress callbacks. */
    @Test
    public void twoPassProgressReportsActivityBeforeEitherPassCompletes() {
        List<String> events = new ArrayList<>();
        PairScheduler.Progress sink = new PairScheduler.Progress() {
            @Override public void begin(int total, int workers) {
                events.add("begin:" + total);
            }

            @Override public void taskStarted(int index, int total) {
                events.add("active:" + index + "/" + total);
            }

            @Override public void update(int done, int total) {
                events.add("done:" + done + "/" + total);
            }

            @Override public void phase(String name, int done, int total) {
                events.add("phase:" + name + ":" + done + "/" + total);
            }
        };

        PairScheduler.Progress pilot = PixelSelectionEngine.passProgress(sink, false);
        pilot.begin(3, 2);
        pilot.taskStarted(0, 3);
        assertEquals("begin:6", events.get(0));
        assertEquals("phase:Pilot alignment:0/6", events.get(1));
        assertEquals("active:0/6", events.get(2));

        PairScheduler.Progress refit = PixelSelectionEngine.passProgress(sink, true);
        refit.begin(3, 2);
        refit.taskStarted(0, 3);
        assertEquals("phase:Selected-pixel refit:3/6", events.get(3));
        assertEquals("active:3/6", events.get(4));
    }

    @Test
    public void selectedPixelRefitCarriesRotationConfidenceEvidence() {
        FrameSource source = texturedSource(64, 64);
        Registration.Options options = new Registration.Options();
        options.aligner.fitRotation = true;
        options.aligner.maxRotation = Math.toRadians(5);
        options.aligner.maxShift = 4;
        options.confidenceGatedRotation = true;
        options.minimumRotationResidualGain = 0.001;
        options.threads = 1;

        Registration.Result result = PixelSelectionEngine.run(
                source, source, source, options,
                PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE, 25,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);

        assertEquals(1, result.pairs.size());
        assertTrue(result.pairs.get(0).fit.rotationEvidence != null);
        assertTrue(!result.pairs.get(0).fit.rotationEvidence.accepted);
        assertEquals(0.0, result.pairs.get(0).fit.transform.theta, 0.0);
    }

    @Test
    public void declinedIncrementalSelectedPixelRotationKeepsTranslationExactly() {
        FrameSource source = texturedSource(64, 64);
        Registration.Options translation = new Registration.Options();
        translation.aligner.maxShift = 4;
        translation.threads = 1;
        Registration.Result baseline = PixelSelectionEngine.run(
                source, source, source, translation,
                PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE, 25,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);

        Registration.Options incremental = translation.copy();
        incremental.aligner.fitRotation = true;
        incremental.aligner.maxRotation = Math.toRadians(5);
        incremental.incrementalRotation = true;
        incremental.minimumRotationResidualGain = 1.0;
        Registration.Result result = PixelSelectionEngine.run(
                source, source, source, incremental,
                PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE, 25,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);

        assertEquals(baseline.cumulative[1].dx, result.cumulative[1].dx, 0.0);
        assertEquals(baseline.cumulative[1].dy, result.cumulative[1].dy, 0.0);
        assertEquals(baseline.cumulative[1].theta, result.cumulative[1].theta, 0.0);
        assertEquals(0, result.rotationAcceptedPairs());
        assertEquals(1, result.rotationDeclinedPairs());
    }

    private static FrameSource texturedSource(final int width, final int height) {
        final float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = 20f + (x % 7) * 3f + (y % 5) * 2f;
            }
        }
        return new FrameSource() {
            @Override public int count() { return 2; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
            @Override public float[] plane(int frame) { return pixels; }
        };
    }
}
