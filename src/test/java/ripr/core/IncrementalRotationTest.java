/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression guards for the opt-in single-translation rotation architecture. */
public class IncrementalRotationTest {

    private static final int W = 64;
    private static final int H = 64;

    @Test
    public void allDeclinedIsNumericallyIdenticalToTranslationOnlyForEveryPlannedReference() {
        FrameSource source = translatedSource();
        for (PairEstimator.Kind estimator : new PairEstimator.Kind[]{
                PairEstimator.Kind.LOG_RATIO_FIT,
                PairEstimator.Kind.AREA_CORRELATION,
                PairEstimator.Kind.AREA_CORRELATION_LINEAR,
                PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                PairEstimator.Kind.AREA_CORRELATION_ECC,
                PairEstimator.Kind.AREA_CORRELATION_SUBSAMPLED}) {
            for (Reconciler.Reference reference : new Reconciler.Reference[]{
                    Reconciler.Reference.CONSECUTIVE,
                    Reconciler.Reference.MULTILAG,
                    Reconciler.Reference.FIXED}) {
                Registration.Options baselineOptions = options(estimator, reference);
                Registration.Result baseline = Registration.run(
                        source, baselineOptions, null, null);

                Registration.Options incrementalOptions = options(estimator, reference);
                incrementalOptions.aligner.fitRotation = true;
                incrementalOptions.incrementalRotation = true;
                incrementalOptions.minimumRotationResidualGain = 1.0;
                Registration.Result incremental = Registration.run(
                        source, incrementalOptions, null, null);

                assertEquals(estimator + " " + reference + " pair count",
                        baseline.pairs.size(), incremental.pairs.size());
                assertEquals(0, incremental.rotationAcceptedPairs());
                assertEquals(incremental.pairs.size(), incremental.rotationDeclinedPairs());
                for (int t = 0; t < baseline.cumulative.length; t++) {
                    assertTransformEquals(estimator + " " + reference + " frame " + t,
                            baseline.cumulative[t], incremental.cumulative[t]);
                    assertEquals(baseline.log2Gain[t], incremental.log2Gain[t], 0.0);
                    assertEquals(baseline.residualBefore[t], incremental.residualBefore[t], 0.0);
                    assertEquals(baseline.residualAfter[t], incremental.residualAfter[t], 0.0);
                    assertEquals(baseline.validFraction[t], incremental.validFraction[t], 0.0);
                    assertEquals(baseline.status[t], incremental.status[t]);
                }
                for (int i = 0; i < baseline.pairs.size(); i++) {
                    PairAligner.Fit expected = baseline.pairs.get(i).fit;
                    PairAligner.Fit actual = incremental.pairs.get(i).fit;
                    assertTransformEquals(estimator + " " + reference + " pair " + i,
                            expected.transform, actual.transform);
                    assertEquals(expected.residualAfter, actual.residualAfter, 0.0);
                    assertTrue(actual.rotationEvidence != null);
                    assertTrue(!actual.rotationEvidence.accepted);
                }
            }
        }
    }

    @Test
    public void automaticSelectorEstimatorsCanAcceptKnownRotation() {
        float[] first = Synth.frame(W, H, 0, 0);
        float[] second = new float[first.length];
        Transform truth = new Transform(1.2, -0.8, Math.toRadians(1.5));
        Warper.warp(first, second, W, H, truth.inverse(),
                Warper.Interpolation.BILINEAR, Float.NaN);
        FrameSource source = Synth.source(W, H, first, second);

        for (PairEstimator.Kind estimator : new PairEstimator.Kind[]{
                PairEstimator.Kind.LOG_RATIO_FIT,
                PairEstimator.Kind.AREA_CORRELATION,
                PairEstimator.Kind.AREA_CORRELATION_LINEAR,
                PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                PairEstimator.Kind.AREA_CORRELATION_ECC,
                PairEstimator.Kind.AREA_CORRELATION_SUBSAMPLED}) {
            Registration.Options options = options(
                    estimator, Reconciler.Reference.CONSECUTIVE);
            options.aligner.fitRotation = true;
            options.incrementalRotation = true;
            options.minimumRotationResidualGain = 0.0;
            Registration.Result result = Registration.run(source, options, null, null);

            assertEquals(estimator.toString(), 1, result.rotationAcceptedPairs());
            assertEquals(estimator.toString(), truth.thetaDegrees(),
                    result.cumulative[1].thetaDegrees(), 0.20);
            assertTrue(estimator + " must improve the full-frame residual",
                    result.pairs.get(0).fit.rotationEvidence.residualGain > 0);
        }
    }

    @Test
    public void everyEstimatorRefitsTranslationWithoutChangingAnotherRecipesAngle() {
        float[] first = Synth.frame(W, H, 0, 0);
        float[] second = new float[first.length];
        Transform truth = new Transform(1.2, -0.8, Math.toRadians(1.5));
        Warper.warp(first, second, W, H, truth.inverse(),
                Warper.Interpolation.BILINEAR, Float.NaN);
        PairAligner.Options rotationOptions = new PairAligner.Options();
        rotationOptions.maxShift = 8;
        rotationOptions.fitRotation = true;
        rotationOptions.maxRotation = Math.toRadians(5);
        int levels = rotationOptions.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(first, W, H, levels);
        LogPlane[] b = Synth.pyramid(second, W, H, levels);
        PairAligner.Options translationOptions = rotationOptions.copy();
        translationOptions.fitRotation = false;
        PairAligner.Fit translation = PairEstimator.Kind.LOG_RATIO_FIT.estimate(
                a, b, translationOptions);
        PairAligner.Fit angle = PairEstimator.Kind.LOG_RATIO_FIT.refineRigidFromTranslation(
                a, b, rotationOptions, translation);

        for (PairEstimator.Kind estimator : PairEstimator.Kind.values()) {
            PairAligner.Fit refit = estimator.refineTranslationAtFixedRotation(
                    a, b, rotationOptions, angle.transform, null);
            assertEquals(estimator + " changed the proposed angle",
                    angle.transform.theta, refit.transform.theta, 0.0);
            assertTrue(estimator + " returned an unusable fixed-angle fit", refit.usable());
            assertEquals(estimator.toString(), truth.thetaDegrees(),
                    refit.transform.thetaDegrees(), 0.20);
        }
    }

    @Test
    public void everyEstimatorCanGloballyRecoverTranslationAtAFixedAngle() {
        float[] first = Synth.frame(W, H, 0, 0);
        float[] second = new float[first.length];
        Transform truth = new Transform(5.2, -3.8, Math.toRadians(1.5));
        Warper.warp(first, second, W, H, truth.inverse(),
                Warper.Interpolation.BILINEAR, Float.NaN);
        PairAligner.Options options = new PairAligner.Options();
        options.maxShift = 8;
        options.fitRotation = true;
        options.maxRotation = Math.toRadians(5);
        int levels = options.levelsFor(W, H);
        LogPlane[] a = Synth.pyramid(first, W, H, levels);
        LogPlane[] b = Synth.pyramid(second, W, H, levels);
        Transform badTranslationBasin = new Transform(-7, 7, truth.theta);

        for (PairEstimator.Kind estimator : PairEstimator.Kind.values()) {
            PairAligner.Fit refit = estimator.estimateTranslationAtFixedRotation(
                    a, b, options, badTranslationBasin, null);
            assertTrue(estimator + " returned an unusable global fixed-angle fit",
                    refit.usable());
            assertEquals(estimator + " changed the fixed angle",
                    truth.theta, refit.transform.theta, 0.0);
            assertEquals(estimator + " dx", truth.dx, refit.transform.dx, 0.35);
            assertEquals(estimator + " dy", truth.dy, refit.transform.dy, 0.35);
        }
    }

    @Test
    public void splitRotationKeepsTheAutomaticTranslationExactlyWhenAllAnglesAreDeclined() {
        FrameSource source = translatedSource();
        for (PairEstimator.Kind translationEstimator : PairEstimator.Kind.values()) {
            Registration.Options translationOptions = options(
                    translationEstimator, Reconciler.Reference.MULTILAG);
            Registration.Result baseline = Registration.run(
                    source, translationOptions, null, null);
            Registration.Options rotationOptions = options(
                    PairEstimator.Kind.LOG_RATIO_FIT, Reconciler.Reference.MULTILAG);
            rotationOptions.aligner.fitRotation = true;
            rotationOptions.minimumRotationResidualGain = 1.0;

            Registration.Result split = Registration.applySplitRotation(
                    source, translationOptions, baseline, null,
                    source, rotationOptions, null, null, null);

            assertEquals(0, split.rotationAcceptedPairs());
            for (int t = 0; t < baseline.cumulative.length; t++) {
                assertTransformEquals(translationEstimator + " frame " + t,
                        baseline.cumulative[t], split.cumulative[t]);
                assertEquals(baseline.residualAfter[t], split.residualAfter[t], 0.0);
                assertEquals(baseline.log2Gain[t], split.log2Gain[t], 0.0);
            }
            for (int i = 0; i < baseline.pairs.size(); i++) {
                assertTransformEquals(translationEstimator + " pair " + i,
                        baseline.pairs.get(i).fit.transform,
                        split.pairs.get(i).fit.transform);
            }
        }
    }

    @Test
    public void splitRotationUsesOneRecipeForAngleAndAnotherForFinalTranslation() {
        float[] first = Synth.frame(W, H, 0, 0);
        float[] second = new float[first.length];
        Transform truth = new Transform(1.2, -0.8, Math.toRadians(1.5));
        Warper.warp(first, second, W, H, truth.inverse(),
                Warper.Interpolation.BILINEAR, Float.NaN);
        FrameSource source = Synth.source(W, H, first, second);
        Registration.Options translationOptions = options(
                PairEstimator.Kind.AREA_CORRELATION_NEWTON,
                Reconciler.Reference.CONSECUTIVE);
        Registration.Result translation = Registration.run(
                source, translationOptions, null, null);
        Registration.Options rotationOptions = options(
                PairEstimator.Kind.LOG_RATIO_FIT, Reconciler.Reference.CONSECUTIVE);
        rotationOptions.aligner.fitRotation = true;
        rotationOptions.minimumRotationResidualGain = 0.0;

        Registration.Result split = Registration.applySplitRotation(
                source, translationOptions, translation, null,
                source, rotationOptions, null, null, null);

        assertEquals(1, split.rotationAcceptedPairs());
        assertEquals(truth.thetaDegrees(), split.cumulative[1].thetaDegrees(), 0.20);
        assertEquals(PairAligner.Status.OK, split.pairs.get(0).fit.status);
    }

    @Test
    public void progressCoversTranslationAndRotationWithNamedPhases() {
        Registration.Options options = options(
                PairEstimator.Kind.LOG_RATIO_FIT, Reconciler.Reference.CONSECUTIVE);
        options.aligner.fitRotation = true;
        options.incrementalRotation = true;
        options.minimumRotationResidualGain = 1.0;
        List<String> phases = new ArrayList<>();
        int[] begun = {-1};
        int[] latest = {-1, -1};
        PairScheduler.Progress progress = new PairScheduler.Progress() {
            @Override public void begin(int total, int workers) { begun[0] = total; }
            @Override public void update(int done, int total) {
                latest[0] = done;
                latest[1] = total;
            }
            @Override public void phase(String name, int done, int total) {
                phases.add(name);
            }
        };

        Registration.Result result = Registration.run(
                translatedSource(), options, progress, null);
        int pairCount = result.pairs.size();
        assertEquals(2 * pairCount, begun[0]);
        assertEquals(2 * pairCount, latest[0]);
        assertEquals(2 * pairCount, latest[1]);
        assertTrue(phases.contains(Registration.PHASE_TRANSLATION));
        assertTrue(phases.contains(Registration.PHASE_ROTATION));
        assertTrue(phases.contains(Registration.PHASE_RECONCILE));
        assertTrue(phases.contains(Registration.PHASE_REPAIR));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rollingReferenceIsExplicitlyRejected() {
        Registration.Options options = options(
                PairEstimator.Kind.LOG_RATIO_FIT, Reconciler.Reference.ROLLING);
        options.aligner.fitRotation = true;
        options.incrementalRotation = true;
        Registration.run(translatedSource(), options, null, null);
    }

    private static Registration.Options options(PairEstimator.Kind estimator,
                                                 Reconciler.Reference reference) {
        Registration.Options options = new Registration.Options();
        options.estimator = estimator;
        options.reference = reference;
        options.lags = new int[]{1, 2};
        options.threads = 1;
        options.outlierMads = 0;
        options.aligner.maxShift = 8;
        options.aligner.maxRotation = Math.toRadians(5);
        return options;
    }

    private static FrameSource translatedSource() {
        return Synth.source(W, H,
                Synth.frame(W, H, 0.0, 0.0),
                Synth.frame(W, H, 0.8, -0.4),
                Synth.frame(W, H, 1.5, -0.9),
                Synth.frame(W, H, 2.1, -1.2));
    }

    private static void assertTransformEquals(String message, Transform expected,
                                              Transform actual) {
        assertEquals(message + " dx", expected.dx, actual.dx, 0.0);
        assertEquals(message + " dy", expected.dy, actual.dy, 0.0);
        assertEquals(message + " theta", expected.theta, actual.theta, 0.0);
    }
}
