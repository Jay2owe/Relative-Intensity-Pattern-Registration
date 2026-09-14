/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LongitudinalRegistrationTest {
    @Test
    public void emissionFeatureIgnoresGlobalGainAndOffset() {
        int width = 64;
        float[] first = textured(width, width);
        float[] second = new float[first.length];
        for (int i = 0; i < first.length; i++) second[i] = 3.7f * first[i] + 91.0f;

        float[] a = LongitudinalRegistration.emissionFeature(first, width, width);
        float[] b = LongitudinalRegistration.emissionFeature(second, width, width);

        assertTrue(pearson(a, b) > 0.999);
    }

    @Test
    public void emissionFeaturePhaseSeedFindsAStageJump() {
        int width = 64;
        float[] first = LongitudinalRegistration.emissionFeature(
                textured(width, width), width, width);
        float[] second = LongitudinalRegistration.emissionFeature(
                translate(textured(width, width), width, width, 9, -4), width, width);

        Transform seed = LongitudinalRegistration.phaseSeed(first, second, width, width);

        assertEquals(9.0, seed.dx, 1.0);
        assertEquals(-4.0, seed.dy, 1.0);
        assertTrue(LongitudinalRegistration.correlation(first, second, width, width, seed)
                > LongitudinalRegistration.correlation(
                        first, second, width, width, Transform.IDENTITY));
    }

    @Test
    public void emissionRouteRejectsReturningPulseAndKeepsPersistentJump() {
        int width = 64;
        int frames = 18;
        float[] base = textured(width, width);
        float[][] planes = new float[frames][];
        for (int frame = 0; frame < frames; frame++) {
            int shift = frame >= 12 ? 9 : 0;
            int shiftY = frame >= 12 ? -4 : 0;
            planes[frame] = translate(base, width, width, shift, shiftY);
            double globalGain = frame == 3 ? 2.0 : frame == 10 ? 0.55 : 1.0;
            for (int pixel = 0; pixel < planes[frame].length; pixel++) {
                planes[frame][pixel] *= globalGain;
            }
            if (frame >= 5 && frame <= 8) {
                double gain = frame == 6 || frame == 7 ? 4.0 : 2.0;
                for (int y = 12; y < 34; y++) for (int x = 35; x < 58; x++) {
                    planes[frame][y * width + x] += gain * 180.0;
                }
            }
        }
        FrameSource source = Synth.source(width, width, planes);
        Registration.Options options = Registration.Options.recommendedFor(
                Reconciler.Reference.MULTILAG);
        options.aligner.maxShift = 20;
        options.autoMaxShift = false;
        options.threads = 1;
        Registration.Result baseline = Registration.run(source, options, null, null);
        Transform[] artificialSwoosh = baseline.cumulative.clone();
        artificialSwoosh[6] = Transform.translation(7, -5);
        artificialSwoosh[7] = Transform.translation(7, -5);
        baseline = baseline.withCumulative(artificialSwoosh, null);

        LongitudinalRegistration.Outcome result = LongitudinalRegistration.refine(
                source, baseline, false, 10, 1, null, null);
        LongitudinalRegistration.Outcome parallel = LongitudinalRegistration.refine(
                source, baseline, false, 10, 4, null, null);

        assertTrue(Math.abs(result.registration.cumulative[6].dx) < 1.0);
        assertTrue(Math.abs(result.registration.cumulative[7].dy) < 1.0);
        assertEquals("dx=" + java.util.Arrays.toString(java.util.Arrays.stream(
                        result.registration.cumulative).mapToDouble(value -> value.dx).toArray())
                        + "; weak=" + java.util.Arrays.toString(result.diagnostics.weakFrames)
                        + "; jumps=" + java.util.Arrays.toString(
                                result.diagnostics.persistentJumpFrames),
                9.0, result.registration.cumulative[17].dx, 1.0);
        assertEquals(-4.0, result.registration.cumulative[17].dy, 1.0);
        assertTrue(result.diagnostics.persistentJumpFrames.length >= 1);
        for (int frame = 0; frame < frames; frame++) {
            assertEquals(result.registration.cumulative[frame].dx,
                    parallel.registration.cumulative[frame].dx, 0.0);
            assertEquals(result.registration.cumulative[frame].dy,
                    parallel.registration.cumulative[frame].dy, 0.0);
            assertEquals(result.registration.cumulative[frame].theta,
                    parallel.registration.cumulative[frame].theta, 0.0);
        }
    }

    @Test
    public void rigidBridgeSearchKeepsRotationCornersOutOfPhaseCorrelation() {
        int width = 128;
        float[] first = textured(width, width);
        Transform expected = new Transform(7.0, -5.0, Math.toRadians(-8.0));
        float[] second = new float[first.length];
        Warper.warp(first, second, width, width, expected.inverse(),
                Warper.Interpolation.BILINEAR, 10.0f);

        LongitudinalRegistration.Fit fit = LongitudinalRegistration.rigidBridge(
                first, second, width, width, 10.0, null);

        assertEquals(expected.dx, fit.transform.dx, 1.5);
        assertEquals(expected.dy, fit.transform.dy, 1.5);
        assertEquals(expected.thetaDegrees(), fit.transform.thetaDegrees(), 1.0);
    }

    private static float[] textured(int width, int height) {
        float[] result = new float[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double first = 130 * Math.exp(-((x - 18.0) * (x - 18.0)
                    + (y - 22.0) * (y - 22.0)) / 70.0);
            double second = 80 * Math.exp(-((x - 47.0) * (x - 47.0)
                    + (y - 43.0) * (y - 43.0)) / 45.0);
            result[y * width + x] = (float) (10 + first + second
                    + 8 * Math.sin(0.31 * x + 0.17 * y));
        }
        return result;
    }

    private static float[] translate(float[] source, int width, int height, int dx, int dy) {
        float[] result = new float[source.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int sx = x - dx;
            int sy = y - dy;
            result[y * width + x] = sx >= 0 && sx < width && sy >= 0 && sy < height
                    ? source[sy * width + sx] : 10.0f;
        }
        return result;
    }

    private static double pearson(float[] left, float[] right) {
        double meanLeft = 0, meanRight = 0;
        for (int i = 0; i < left.length; i++) {
            meanLeft += left[i];
            meanRight += right[i];
        }
        meanLeft /= left.length;
        meanRight /= right.length;
        double covariance = 0, varianceLeft = 0, varianceRight = 0;
        for (int i = 0; i < left.length; i++) {
            double a = left[i] - meanLeft;
            double b = right[i] - meanRight;
            covariance += a * b;
            varianceLeft += a * a;
            varianceRight += b * b;
        }
        return covariance / Math.sqrt(varianceLeft * varianceRight);
    }
}
