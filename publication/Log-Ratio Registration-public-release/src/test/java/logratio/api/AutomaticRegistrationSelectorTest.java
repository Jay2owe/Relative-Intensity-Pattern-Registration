/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import logratio.StackFrames;
import logratio.core.Reconciler;
import logratio.core.Transform;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    private static LogRatioParameters base() {
        return LogRatioParameters.builder()
                .recommendation(ImageType.PHASE_CONTRAST, MotionType.SUBPIXEL_RANDOM_WALK)
                .selectionMode(SelectionMode.MANUAL)
                .autoMaxShift(false).maxShift(6).threads(1).crop(false).build();
    }

    private static AutomaticRegistrationSelector.Evidence measure(ImagePlus image) {
        Transform[] provisional = new Transform[image.getStackSize()];
        for (int i = 0; i < provisional.length; i++) provisional[i] = new Transform(-i, 0, 0);
        return AutomaticRegistrationSelector.measure(StackFrames.of(image), provisional, base());
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
                LogRatioParameters typed = LogRatioParameters.builder()
                        .recommendation(image, motion).selectionMode(SelectionMode.MANUAL).build();
                RegistrationRecipe fallback = AutomaticRegistrationSelector.categoryRecipe(typed);
                LogRatioParameters recommended = LogRatioParameters.builder()
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
        LogRatioParameters explicit = LogRatioParameters.builder()
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
            LogRatioParameters resolved = result.parameters;
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
            LogRatioParameters typed = LogRatioParameters.builder()
                    .recommendation(type, MotionType.SUBPIXEL_RANDOM_WALK)
                    .selectionMode(SelectionMode.AUTOMATIC).threads(1).build();
            ImagePlus image = movingTexture(64, 4);
            try {
                AutomaticRegistrationSelector.Result result =
                        LogRatioRegistration.resolveAutomaticSettings(image, typed);
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
    public void theSelectorResolvesToOrdinaryEditableSettings() {
        ImagePlus image = movingTexture(64, 5);
        try {
            AutomaticRegistrationSelector.Evidence evidence = measure(image);
            AutomaticRegistrationSelector.Result result =
                    AutomaticRegistrationSelector.select(evidence, base());
            LogRatioParameters resolved = result.parameters;
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
            LogRatioParameters automatic = base().toBuilder()
                    .selectionMode(SelectionMode.AUTOMATIC).build();
            AutomaticRegistrationSelector.Result resolved =
                    LogRatioRegistration.resolveAutomaticSettings(image, automatic);
            assertEquals(SelectionMode.MANUAL, resolved.parameters.selectionMode);
            assertFalse(resolved.parameters.recipeProvenance.isEmpty());

            LogRatioResult automaticRun = LogRatioRegistration.register(image, automatic);
            LogRatioResult manualRun = LogRatioRegistration.register(image, resolved.parameters);
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
            LogRatioParameters automatic = base().toBuilder()
                    .selectionMode(SelectionMode.AUTOMATIC).build();
            LogRatioResult result = LogRatioRegistration.register(image, automatic);
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
        LogRatioParameters.builder()
                .useRecommendation(false)
                .automaticFilterSelection(true)
                .reference(Reconciler.Reference.ROLLING)
                .build();
    }
}
