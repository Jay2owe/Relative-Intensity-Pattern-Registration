/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Bit-level compatibility fixtures frozen before confidence weighting changed production code. */
public class ReconciliationBaselineTest {

    @Test
    public void translationBaselineBitsAndUnsupportedFrameAreFrozen() {
        List<Reconciler.Observation> observations = Arrays.asList(
                observation(0, 1, new Transform(1.25, -0.5, 0)),
                observation(1, 2, new Transform(0.75, 0.25, 0)),
                observation(0, 2, new Transform(2.1, -0.2, 0)),
                observation(3, 2, new Transform(-0.4, 0.3, 0)));
        Reconciler.Solution result = Reconciler.multiLag(5, observations);
        long[][] expected = {
                {0x0L, 0x0L, 0x0L},
                {0x3ff4888887949446L, 0xbfdeeeeeee054980L, 0x0L},
                {0x4000888887d6b8c1L, 0xbfcbbbbbb9a3b8afL, 0x0L},
                {0x4003bbbbba8aca6bL, 0xbfe08888879803e2L, 0x0L},
                {0x0L, 0x0L, 0x0L}
        };
        assertBits(expected, result.cumulative);
        assertEquals(0, result.support[4]);
        assertExplicitEqualMatches(result, observations, 5, 2);
    }

    @Test
    public void rigidBaselineBitsIncludingReverseObservationAreFrozen() {
        Transform[] truth = {
                Transform.IDENTITY,
                new Transform(2.0, -1.0, 0.12),
                new Transform(4.5, 0.75, -0.08),
                new Transform(5.25, 2.0, 0.2)
        };
        List<Reconciler.Observation> observations = new ArrayList<>();
        for (int[] edge : new int[][]{{0, 1}, {1, 2}, {0, 2}, {3, 1}, {2, 3}}) {
            observations.add(observation(edge[0], edge[1],
                    truth[edge[0]].inverse().then(truth[edge[1]])));
        }
        Reconciler.Solution result = Reconciler.multiLag(4, observations);
        long[][] expected = {
                {0x0L, 0x0L, 0x0L},
                {0x3ffffffffc07f558L, 0xbff00000008da3cbL, 0x3fbeb851e9dbf9beL},
                {0x4011fffffee0a724L, 0x3fe7ffffffbb2772L, 0xbfb47ae148c49f76L},
                {0x4014fffffe81aaebL, 0x3ffffffffe2a31f4L, 0x3fc999999857139fL}
        };
        assertBits(expected, result.cumulative);
        assertExplicitEqualMatches(result, observations, 4, 3);
    }

    private static Reconciler.Observation observation(int from, int to, Transform transform) {
        return new Reconciler.Observation(from, to, transform);
    }

    private static void assertExplicitEqualMatches(
            Reconciler.Solution baseline, List<Reconciler.Observation> observations,
            int frames, int dimensions) {
        Reconciler.Options options = new Reconciler.Options(
                Reconciler.Weighting.EQUAL, dimensions, 50.0);
        Reconciler.Solution explicit = Reconciler.multiLag(frames, observations, options);
        for (int frame = 0; frame < frames; frame++) {
            assertEquals(Double.doubleToLongBits(baseline.cumulative[frame].dx),
                    Double.doubleToLongBits(explicit.cumulative[frame].dx));
            assertEquals(Double.doubleToLongBits(baseline.cumulative[frame].dy),
                    Double.doubleToLongBits(explicit.cumulative[frame].dy));
            assertEquals(Double.doubleToLongBits(baseline.cumulative[frame].theta),
                    Double.doubleToLongBits(explicit.cumulative[frame].theta));
        }
        assertArrayEquals(baseline.support, explicit.support);
    }

    private static void assertBits(long[][] expected, Transform[] actual) {
        assertEquals(expected.length, actual.length);
        for (int frame = 0; frame < expected.length; frame++) {
            assertEquals("frame " + frame + " dx", expected[frame][0],
                    Double.doubleToLongBits(actual[frame].dx));
            assertEquals("frame " + frame + " dy", expected[frame][1],
                    Double.doubleToLongBits(actual[frame].dy));
            assertEquals("frame " + frame + " theta", expected[frame][2],
                    Double.doubleToLongBits(actual[frame].theta));
        }
    }
}
