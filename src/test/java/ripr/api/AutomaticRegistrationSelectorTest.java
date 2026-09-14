/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ripr.StackFrames;
import ripr.core.Reconciler;
import ripr.core.PairEstimator;
import ripr.core.Transform;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Evidence layout, the conservative decision rule, and automatic-to-manual replay equality. */
public class AutomaticRegistrationSelectorTest {

    private static ImagePlus movingTexture(int width, int frames) {
        ImageStack stack = new ImageStack(width, width);
        java.util.Random random = new java.util.Random(20260817L);
        float[] texture = new float[width * width];
        for (int i = 0; i < texture.length; i++) texture[i] = 30 + 170 * random.nextFloat();
        for (int frame = 0; frame < frames; frame++) {
            float[] pixels = new float[width * width];
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    int sourceX = x - frame;
                    pixels[y * width + x] = sourceX >= 0 ? texture[y * width + sourceX] : 30;
                }
            }
            stack.addSlice(new FloatProcessor(width, width, pixels));
        }
        return new ImagePlus("moving", stack);
    }

    private static RelativeIntensityPatternParameters base() {
        return RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK)
                .selectionMode(SelectionMode.MANUAL)
                .autoMaxShift(false).maxShift(6).threads(1).crop(false).build();
    }

    private static AutomaticRegistrationSelector.Evidence measure(ImagePlus image) {
        return measure(image, base());
    }

    private static AutomaticRegistrationSelector.Evidence measure(
            ImagePlus image, RelativeIntensityPatternParameters parameters) {
        Transform[] provisional = new Transform[image.getStackSize()];
        for (int i = 0; i < provisional.length; i++) provisional[i] = new Transform(-i, 0, 0);
        return AutomaticRegistrationSelector.measure(
                StackFrames.of(image), provisional, parameters);
    }

    @Test
    public void theEvidenceVectorMatchesTheDeclaredFeatureLayout() {
        ImagePlus image = movingTexture(64, 5);
        try {
            AutomaticRegistrationSelector.Evidence evidence = measure(image);
            assertEquals(AutomaticRegistrationSelector.FEATURE_COUNT,
                    AutomaticRegistrationSelector.FEATURE_NAMES.length);
            assertEquals(AutomaticRegistrationSelector.FEATURE_COUNT, evidence.vector().length);
            assertEquals(AutomaticRegistrationSelectorModel.FEATURE_COUNT,
                    AutomaticRegistrationSelector.FEATURE_COUNT);
            assertEquals(17, evidence.imageFeatures.length);
            assertEquals(10, evidence.motionFeatures.length);
            assertEquals("recording_evidence_v2", evidence.contractVersion);
            assertTrue(evidence.validityReason, evidence.valid);
        } finally {
            image.close();
        }
    }

    @Test
    public void theEvidenceCarriesTheFieldSupportAndDeclaredContextTerms() {
        ImagePlus image = movingTexture(64, 5);
        try {
            AutomaticRegistrationSelector.Evidence evidence = measure(image);
            assertTrue("sparsity score should be measured",
                    Double.isFinite(evidence.sparsityScore));
            assertTrue("gradient support admits a fraction between 0 and 1",
                    evidence.gradientAdmittedFraction >= 0 && evidence.gradientAdmittedFraction <= 1);
            assertTrue("mutual-noise support admits a fraction between 0 and 1",
                    evidence.mutualNoiseAdmittedFraction >= 0
                            && evidence.mutualNoiseAdmittedFraction <= 1);
            assertEquals(ImageType.PHASE_CONTRAST, evidence.imageType);
            assertEquals(MotionType.SUBPIXEL_RANDOM_WALK, evidence.motionType);
            assertEquals(base().norm, evidence.baseNorm);
            assertEquals(Reconciler.Reference.MULTILAG, evidence.baseReference);
        } finally {
            image.close();
        }
    }

    @Test
    public void theFallbackIsTheCompleteCategoryRecommendation() {
        for (ImageType image : ImageType.values()) {
            for (MotionType motion : MotionType.values()) {
                RelativeIntensityPatternParameters typed = RelativeIntensityPatternParameters.builder()
                        .recommendation(image, motion).selectionMode(SelectionMode.MANUAL).build();
                RegistrationRecipe fallback = AutomaticRegistrationSelector.categoryRecipe(typed);
                RelativeIntensityPatternParameters recommended = RelativeIntensityPatternParameters.builder()
                        .recommendation(image, motion).build();
                assertEquals(image + "/" + motion, RegistrationRecipe.of(recommended), fallback);
            }
        }
    }

    @Test
    public void anUnclearedThresholdReturnsTheCategoryRecommendationUntouched() {
        ImagePlus image = movingTexture(64, 5);
        try {
            AutomaticRegistrationSelector.Evidence evidence = measure(image);
            AutomaticRegistrationSelector.Result result =
                    AutomaticRegistrationSelector.select(evidence, base());
            assertNotNull(result);
            if (result.fallback) {
                assertEquals(AutomaticRegistrationSelector.categoryRecipe(base()), result.recipe);
                assertTrue(result.explanation(),
                        result.explanation().contains("category recommendation retained"));
            } else {
                assertTrue("an override must clear its own frozen threshold",
                        result.predictedGain >= result.confidenceThreshold);
            }
        } finally {
            image.close();
        }
    }

    @Test
    public void decliningAnOverrideChangesNothingAtAll() {
        // A fallback must be a true no-op. Rebuilding the base from the recommendation would quietly
        // reset the caller's reference strategy and movement bound, so the "unchanged" run would not
        // match a plain category run at all.
        RelativeIntensityPatternParameters explicit = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.FIDUCIAL_STATIC, MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.MANUAL)
                .reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(false).maxShift(17.5)
                .lags(1, 2, 3).templateWindow(7).threads(1).build();
        ImagePlus image = movingTexture(64, 5);
        try {
            AutomaticRegistrationSelector.Evidence evidence = AutomaticRegistrationSelector.measure(
                    StackFrames.of(image),
                    new Transform[]{new Transform(0, 0, 0), new Transform(-1, 0, 0),
                            new Transform(-2, 0, 0), new Transform(-3, 0, 0),
                            new Transform(-4, 0, 0)},
                    explicit);
            AutomaticRegistrationSelector.Result result =
                    AutomaticRegistrationSelector.select(evidence, explicit);
            if (!result.fallback) return;
            RelativeIntensityPatternParameters resolved = result.parameters;
            assertEquals(explicit.reference, resolved.reference);
            assertEquals(explicit.autoMaxShift, resolved.autoMaxShift);
            assertEquals(explicit.maxShift, resolved.maxShift, 1e-12);
            assertEquals(explicit.templateWindow, resolved.templateWindow);
            assertEquals(explicit.lags.length, resolved.lags.length);
            assertEquals("declining must restore the category recipe exactly",
                    AutomaticRegistrationSelector.categoryRecipe(explicit),
                    RegistrationRecipe.of(resolved));
        } finally {
            image.close();
        }
    }

    @Test
    public void anImageTypeWithNoCandidateSkipsTheProvisionalPassAndChangesNothing() {
        for (ImageType type : ImageType.values()) {
            boolean anyCandidate = false;
            for (ImageType candidate : AutomaticRegistrationSelectorModel.candidateImageTypes()) {
                if (candidate == type) anyCandidate = true;
            }
            assertEquals(type.name(), anyCandidate,
                    AutomaticRegistrationSelector.servesImageType(type));
            if (anyCandidate) continue;
            // An unserved type must resolve to the category recommendation without measuring.
            RelativeIntensityPatternParameters typed = RelativeIntensityPatternParameters.builder()
                    .recommendation(type, MotionType.SUBPIXEL_RANDOM_WALK)
                    .selectionMode(SelectionMode.AUTOMATIC).threads(1).build();
            ImagePlus image = movingTexture(64, 4);
            try {
                AutomaticRegistrationSelector.Result result =
                        RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, typed);
                assertTrue(type.name() + " must decline", result.fallback);
                assertNull("no provisional pass may run for an unserved type", result.evidence);
                assertEquals(AutomaticRegistrationSelector.categoryRecipe(typed), result.recipe);
                assertEquals(AutomaticRegistrationSelector.categoryRecipe(typed),
                        RegistrationRecipe.of(result.parameters));
                assertTrue(result.explanation(),
                        result.explanation().contains("no provisional pass was run"));
            } finally {
                image.close();
            }
        }
    }

    @Test
    public void everyRetainedOutputRecipeIsOneTheSweepActuallyMeasured() {
        List<RegistrationRecipe> retained = AutomaticRegistrationSelectorModel.candidates();
        for (RegistrationRecipe recipe : retained) {
            assertTrue("the selector must never emit an unmeasured combination: " + recipe.id(),
                    recipe.isSwept());
        }
        if ("linear_gain".equals(AutomaticRegistrationSelectorModel.MODEL_KIND)) {
            assertEquals(retained.size(), AutomaticRegistrationSelectorModel.WEIGHTS.length);
            assertEquals(retained.size(), AutomaticRegistrationSelectorModel.INTERCEPT.length);
            for (double[] weights : AutomaticRegistrationSelectorModel.WEIGHTS) {
                assertEquals(AutomaticRegistrationSelector.FEATURE_COUNT, weights.length);
            }
        }
    }

    @Test
    public void repeatedEvidenceMeasurementIsExact() {
        ImagePlus image = movingTexture(64, 5);
        try {
            double[] first = measure(image).vector();
            double[] second = measure(image).vector();
            assertEquals(first.length, second.length);
            for (int i = 0; i < first.length; i++) {
                assertEquals(AutomaticRegistrationSelector.FEATURE_NAMES[i],
                        Double.doubleToLongBits(first[i]), Double.doubleToLongBits(second[i]));
            }
        } finally {
            image.close();
        }
    }

    @Test
    public void constantAndStructurallyUnsupportedRecordingsAreExplicitlyInvalid() {
        float[][] constant = new float[2][32 * 32];
        for (float[] plane : constant) java.util.Arrays.fill(plane, 7);
        ripr.core.FrameSource source = source(32, 32, constant);
        Transform[] identity = {Transform.IDENTITY, Transform.IDENTITY};
        AutomaticRegistrationSelector.Evidence evidence =
                AutomaticRegistrationSelector.measure(source, identity, base());
        assertFalse(evidence.valid);
        assertTrue(evidence.validityReason, evidence.validityReason.contains("dynamic range"));
        AutomaticRegistrationSelector.Result result =
                AutomaticRegistrationSelector.select(evidence, base());
        assertTrue(result.fallback);
        assertEquals(AutomaticRegistrationSelector.categoryRecipe(base()), result.recipe);

        float[][] small = new float[2][15 * 16];
        small[0][0] = 1;
        small[0][1] = 2;
        small[1][0] = 1;
        small[1][1] = 2;
        evidence = AutomaticRegistrationSelector.measure(source(15, 16, small), identity, base());
        assertFalse(evidence.valid);
        assertTrue(evidence.validityReason.contains("16x16"));
    }

    @Test
    public void nonFinitePilotTransformsRemainIdentifiablyInvalid() {
        ImagePlus image = movingTexture(32, 2);
        try {
            Transform[] transforms = {Transform.IDENTITY,
                    new Transform(Double.NaN, 0, 0)};
            AutomaticRegistrationSelector.Evidence evidence =
                    AutomaticRegistrationSelector.measure(StackFrames.of(image), transforms, base());
            assertFalse(evidence.valid);
            assertTrue(evidence.validityReason, evidence.validityReason.contains("non-finite"));
        } finally {
            image.close();
        }
    }

    @Test
    public void standardisationRejectsNonFiniteEvidence() {
        double[] raw = AutomaticRegistrationSelectorModel.FEATURE_MEAN.clone();
        raw[3] = Double.NaN;
        try {
            AutomaticRegistrationSelector.standardise(raw);
            fail("non-finite input must not be mean-imputed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maximum tail"));
        }
    }

    @Test
    public void distributionRulePinsSingleFeatureAndRmsBounds() {
        ImagePlus image = movingTexture(64, 5);
        try {
            AutomaticRegistrationSelector.Evidence evidence = measure(image);
            double[] raw = evidence.vector();
            double[] mean = raw.clone();
            double[] scale = new double[raw.length];
            java.util.Arrays.fill(scale, 1.0);
            assertFalse(evidence.distribution(mean, scale).outOfDistribution);
            mean[0] = raw[0] - 8.01;
            assertTrue(evidence.distribution(mean, scale).outOfDistribution);
            mean = raw.clone();
            for (int i = 0; i < AutomaticRegistrationSelector.CONTINUOUS_FEATURE_COUNT; i++) {
                mean[i] = raw[i] - 3.01;
            }
            assertTrue(evidence.distribution(mean, scale).outOfDistribution);
        } finally {
            image.close();
        }
    }

    @Test
    public void outOfDistributionEvidenceFallsBackBeforePrediction() {
        ImagePlus image = movingTexture(64, 5);
        try {
            RelativeIntensityPatternParameters brightfield = RelativeIntensityPatternParameters.builder()
                    .recommendation(ImageType.BRIGHTFIELD_DIC,
                            MotionType.SUBPIXEL_RANDOM_WALK)
                    .selectionMode(SelectionMode.MANUAL).build();
            AutomaticRegistrationSelector.Evidence evidence = measure(image, brightfield);
            evidence.imageFeatures[0] = AutomaticRegistrationSelectorModel.FEATURE_MEAN[0]
                    + 9 * AutomaticRegistrationSelectorModel.FEATURE_SCALE[0];
            AutomaticRegistrationSelector.Result result =
                    AutomaticRegistrationSelector.select(evidence, brightfield);
            assertTrue(result.fallback);
            assertNotNull(result.declineReason);
            assertTrue(result.declineReason, result.declineReason.contains("out-of-distribution"));
        } finally {
            image.close();
        }
    }

    @Test
    public void universalPilotIgnoresCategoryRecipeSettings() {
        RelativeIntensityPatternParameters category = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                        MotionType.INTERMITTENT_JUMPS)
                .estimationScale(0.5).reference(Reconciler.Reference.CONSECUTIVE)
                .autoMaxShift(true).maxShift(4).build();
        RelativeIntensityPatternParameters pilot = RelativeIntensityPatternRegistration.automaticSelectorPilot(category);
        assertEquals(PairEstimator.Kind.LOG_RATIO_FIT, pilot.estimator);
        assertEquals(ripr.core.RobustNorm.HUBER, pilot.norm);
        assertEquals(Reconciler.Reference.MULTILAG, pilot.reference);
        assertEquals(1.0, pilot.estimationScale, 0);
        assertFalse(pilot.autoMaxShift);
        assertEquals(30.0, pilot.maxShift, 0);
        assertEquals(0.0, pilot.outlierMads, 0);
        assertEquals(25, pilot.maxIterations);
        assertEquals(200_000, pilot.maxSamples);
        assertEquals(5, pilot.lags.length);
        assertEquals(1, pilot.lags[0]);
        assertEquals(16, pilot.lags[4]);
    }

    @Test
    public void featureExportSchemaContainsNoOutcomeOrTruthField() {
        String header = AutomaticFilterFeatureTable.header().toLowerCase(java.util.Locale.ROOT);
        assertFalse(header.contains("truth"));
        assertFalse(header.contains("winner"));
        assertFalse(header.contains("oracle"));
        assertFalse(header.contains("error"));
        assertFalse(header.contains("recipe"));
        assertTrue(header.contains("feature_contract_version"));
        assertTrue(header.contains("evidence_valid"));
    }

    private static ripr.core.FrameSource source(int width, int height, float[][] planes) {
        return new ripr.core.FrameSource() {
            @Override public int count() { return planes.length; }
            @Override public int width() { return width; }
            @Override public int height() { return height; }
            @Override public float[] plane(int frame) { return planes[frame].clone(); }
        };
    }

    @Test
    public void rigidSelectionUsesPromotedFixedPolicy() {
        assertTrue(PairEstimator.Kind.LOG_RATIO_FIT.supportsRotation());
        assertTrue(PairEstimator.Kind.AREA_CORRELATION_NEWTON.supportsRotation());
        assertEquals(ImageType.values().length,
                AutomaticRegistrationSelectorModel.candidates().size());
        for (int i = 0; i < AutomaticRegistrationSelectorModel.candidates().size(); i++) {
            assertTrue(AutomaticRegistrationSelectorModel.candidateRigidValidated(i));
        }
        assertTrue(AutomaticRegistrationSelector.servesImageType(
                ImageType.BRIGHTFIELD_DIC, true));
        assertTrue(AutomaticRegistrationSelector.servesImageType(
                ImageType.FIDUCIAL_STATIC, true));
        assertTrue(AutomaticRegistrationSelector.servesImageType(
                ImageType.PHASE_CONTRAST, true));

        RelativeIntensityPatternParameters brightfield = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.BRIGHTFIELD_DIC,
                        MotionType.SUBPIXEL_RANDOM_WALK)
                .selectionMode(SelectionMode.MANUAL).build();
        AutomaticRegistrationSelector.Result result =
                AutomaticRegistrationSelector.selectWithoutEvidence(brightfield, true);
        assertFalse(result.fallback);
        assertNull(result.evidence);
        assertEquals(brightfield.estimator, result.parameters.estimator);
        assertTrue(result.parameters.incrementalRotation);
        assertTrue(result.explanation(), result.explanation().startsWith(
                "fixed automatic policy:"));
        assertTrue(result.explanation(), result.explanation().contains(
                "no provisional pass was run"));
    }

    @Test
    public void rigidAutomaticModeRunsTheSelectorAndKeepsIncrementalRotation() {
        RelativeIntensityPatternParameters rigid = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.BRIGHTFIELD_DIC, MotionType.STEADY_DIRECTIONAL_DRIFT)
                .selectionMode(SelectionMode.AUTOMATIC)
                .fitRotation(true).threads(1).build();
        ImagePlus image = movingTexture(64, 4);
        try {
            AutomaticRegistrationSelector.Result result =
                    RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, rigid);
            assertFalse(result.fallback);
            assertNull("a fixed model must not run the provisional pass", result.evidence);
            assertTrue(result.parameters.fitRotation);
            assertTrue(result.parameters.incrementalRotation);
            assertEquals(rigid.estimator, result.parameters.estimator);
            String provenance = result.parameters.recipeProvenance;
            assertTrue(provenance.contains("selector_model="
                    + AutomaticRegistrationSelectorModel.MODEL_VERSION));
            assertTrue(provenance.contains("feature_contract="
                    + AutomaticRegistrationSelectorModel.FEATURE_CONTRACT_VERSION));
            assertTrue(provenance.contains("protocol_sha256="
                    + AutomaticRegistrationSelectorModel.PROTOCOL_SHA256));
            assertTrue(provenance.contains("candidate_manifest_sha256="
                    + AutomaticRegistrationSelectorModel.CANDIDATE_MANIFEST_SHA256));
            assertTrue(provenance.contains("model_artifact_sha256="
                    + AutomaticRegistrationSelectorModel.MODEL_ARTIFACT_SHA256));
            assertTrue(provenance.contains("selected_recipe=" + result.recipe.id()));
            assertTrue(provenance.contains("fallback=false"));
        } finally {
            image.close();
        }
    }

    @Test
    public void rigidAutomaticModeTestsRotationOnlyInTheResolvedPass() {
        RelativeIntensityPatternParameters rigid = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.BRIGHTFIELD_DIC,
                        MotionType.STEADY_DIRECTIONAL_DRIFT)
                .selectionMode(SelectionMode.AUTOMATIC)
                .fitRotation(true).autoMaxShift(false).maxShift(6).threads(1).build();
        ImagePlus image = movingTexture(64, 4);
        AtomicInteger rotationStarts = new AtomicInteger();
        ripr.core.PairScheduler.Progress progress =
                new ripr.core.PairScheduler.Progress() {
                    @Override public void update(int done, int total) { }
                    @Override public void phase(String name, int done, int total) {
                        if (done == 0 && (name.endsWith(
                                ripr.core.Registration.PHASE_ROTATION)
                                || name.endsWith("Testing selected rotation recipe"))) {
                            rotationStarts.incrementAndGet();
                        }
                    }
                };
        try {
            RelativeIntensityPatternRegistration.estimate(image, rigid, progress,
                    ripr.core.PairScheduler.Cancellation.NEVER);
            assertEquals("the neutral selector pass is translation-only",
                    1, rotationStarts.get());
        } finally {
            image.close();
        }
    }

    @Test
    public void theSelectorResolvesToOrdinaryEditableSettings() {
        ImagePlus image = movingTexture(64, 5);
        try {
            AutomaticRegistrationSelector.Evidence evidence = measure(image);
            AutomaticRegistrationSelector.Result result =
                    AutomaticRegistrationSelector.select(evidence, base());
            RelativeIntensityPatternParameters resolved = result.parameters;
            assertFalse("a resolved bundle must not ask to be resolved again",
                    resolved.automaticFilterSelection);
            assertFalse(resolved.useRecommendation);
            assertEquals(result.recipe, RegistrationRecipe.of(resolved));
        } finally {
            image.close();
        }
    }

    @Test
    public void automaticResolutionReplaysExactlyInManualMode() {
        ImagePlus image = movingTexture(64, 5);
        try {
            RelativeIntensityPatternParameters automatic = base().toBuilder()
                    .selectionMode(SelectionMode.AUTOMATIC).build();
            AutomaticRegistrationSelector.Result resolved =
                    RelativeIntensityPatternRegistration.resolveAutomaticSettings(image, automatic);
            assertEquals(SelectionMode.MANUAL, resolved.parameters.selectionMode);
            assertFalse(resolved.parameters.recipeProvenance.isEmpty());

            RelativeIntensityPatternResult automaticRun = RelativeIntensityPatternRegistration.register(image, automatic);
            RelativeIntensityPatternResult manualRun = RelativeIntensityPatternRegistration.register(image, resolved.parameters);
            try {
                assertEquals(RegistrationRecipe.of(automaticRun.parameters()),
                        RegistrationRecipe.of(manualRun.parameters()));
                Transform[] left = automaticRun.registration().cumulative;
                Transform[] right = manualRun.registration().cumulative;
                assertEquals(left.length, right.length);
                for (int i = 0; i < left.length; i++) {
                    assertEquals("frame " + i, left[i].dx, right[i].dx, 1e-9);
                    assertEquals("frame " + i, left[i].dy, right[i].dy, 1e-9);
                }
            } finally {
                automaticRun.close();
                manualRun.close();
            }
        } finally {
            image.close();
        }
    }

    @Test
    public void theRunLogStatesEveryResolvedValueRatherThanARecipeLabel() {
        ImagePlus image = movingTexture(64, 5);
        try {
            RelativeIntensityPatternParameters automatic = base().toBuilder()
                    .selectionMode(SelectionMode.AUTOMATIC).build();
            RelativeIntensityPatternResult result = RelativeIntensityPatternRegistration.register(image, automatic);
            try {
                String provenance = result.provenance();
                assertTrue(provenance, provenance.contains("support "));
                assertTrue(provenance, provenance.contains("gradient multiplier"));
                assertTrue(provenance, provenance.contains("estimation filter"));
                assertTrue(provenance, provenance.contains("spatial removal"));
                assertTrue(provenance, provenance.contains("iterations"));
            } finally {
                result.close();
            }
        } finally {
            image.close();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticSelectionStillRefusesARollingReferenceTemplate() {
        RelativeIntensityPatternParameters.builder()
                .useRecommendation(false)
                .automaticFilterSelection(true)
                .reference(Reconciler.Reference.ROLLING)
                .build();
    }
}
