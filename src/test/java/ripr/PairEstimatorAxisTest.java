/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.MotionType;
import ripr.api.PixelSelectionStrategy;
import ripr.api.Preprocessing;
import ripr.api.RegistrationRecipe;
import ripr.api.SelectionMode;
import ripr.core.PairEstimator;
import ripr.core.Registration;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The estimator is an ordinary setting on an axis of its own, and stays one.
 *
 * <p>Three separate claims are tested here, because each fails in a different way.
 *
 * <ol>
 *   <li><b>It survives a round trip.</b> A run that used the area estimator and reported settings
 *       naming the log-ratio fit would be a run nobody can reproduce, which is the specific hazard of
 *       adding a second estimator at all.</li>
 *   <li><b>It refuses combinations it cannot honour.</b> A spatial pixel mask is a second pass of the
 *       log-ratio fit; asking for it alongside a whole-window correlation is asking for two
 *       incompatible things, and being told so beats being quietly given one of them.</li>
 *   <li><b>It did not rename the 96 log-ratio recipes.</b> Those identifiers name folders on disk
 *       holding a sweep that took hours; a change to the identifier scheme would orphan every one of
 *       them and the loss would show up as a re-run rather than as a failure.</li>
 * </ol>
 */
public class PairEstimatorAxisTest {

    private static RelativeIntensityPatternParameters manual() {
        return RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.FIDUCIAL_STATIC, MotionType.STEADY_DIRECTIONAL_DRIFT)
                .selectionMode(SelectionMode.MANUAL)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .build();
    }

    @Test
    public void theDefaultIsTheLogRatioFit() {
        assertSame(PairEstimator.Kind.LOG_RATIO_FIT, manual().estimator);
        assertSame(PairEstimator.Kind.LOG_RATIO_FIT, new Registration.Options().estimator);
        assertSame(PairEstimator.Kind.LOG_RATIO_FIT,
                MacroOptionsParser.parse("selection_mode=manual").estimator);
    }

    @Test
    public void theEstimatorSurvivesAMacroRoundTrip() {
        for (PairEstimator.Kind kind : PairEstimator.Kind.values()) {
            RelativeIntensityPatternParameters original = manual().toBuilder().estimator(kind).build();
            String options = new RelativeIntensityPatternDialogModel(original).toMacroOptions();
            assertTrue(options, options.contains("estimator=" + kind.id()));
            assertSame(kind, MacroOptionsParser.parse(options).estimator);
        }
    }

    /** The batch entry point parses the same tokens, so a batch run cannot lose the choice. */
    @Test
    public void theEstimatorSurvivesTheBatchParser() {
        RelativeIntensityPatternParameters parsed = MacroOptionsParser.parse(
                "selection_mode=manual estimator=area_correlation");
        assertSame(PairEstimator.Kind.AREA_CORRELATION, parsed.estimator);
    }

    @Test
    public void namesAreAcceptedInTheFormsAUserWouldType() {
        assertSame(PairEstimator.Kind.AREA_CORRELATION, PairEstimator.Kind.of("area_correlation"));
        assertSame(PairEstimator.Kind.AREA_CORRELATION, PairEstimator.Kind.of("AREA_CORRELATION"));
        assertSame(PairEstimator.Kind.AREA_CORRELATION, PairEstimator.Kind.of("Area-Correlation"));
        assertSame(PairEstimator.Kind.LOG_RATIO_FIT, PairEstimator.Kind.of("log ratio fit"));
        try {
            PairEstimator.Kind.of("phase_correlation");
            fail("an estimator that does not exist must not resolve to one that does");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("phase_correlation"));
        }
    }

    @Test
    public void anAreaRunRefusesTheSettingsItCannotHonour() {
        try {
            manual().toBuilder()
                    .estimator(PairEstimator.Kind.AREA_CORRELATION)
                    .pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE)
                    .build();
            fail("a spatial mask has no meaning for a whole-window correlation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("per-pixel support"));
        }
        try {
            manual().toBuilder()
                    .estimator(PairEstimator.Kind.AREA_CORRELATION)
                    .selectionMode(SelectionMode.AUTOMATIC)
                    .build();
            fail("the automatic selector chooses settings of the log-ratio fit");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("automatic filter selection"));
        }
    }

    @Test
    public void theRecipeCarriesTheEstimatorIntoTheParametersAndTheRunLog() {
        RegistrationRecipe recipe = RegistrationRecipe.estimatorSwept(
                RegistrationRecipe.Band.NO_TOP_10, Preprocessing.GAUSSIAN_0_7);
        assertSame(PairEstimator.Kind.AREA_CORRELATION, recipe.estimator);
        assertTrue(recipe.isSwept());
        RelativeIntensityPatternParameters applied = recipe.applyTo(manual());
        assertSame(PairEstimator.Kind.AREA_CORRELATION, applied.estimator);
        assertSame(PairEstimator.Kind.AREA_CORRELATION, RegistrationRecipe.of(applied).estimator);
        assertTrue(recipe.describe(), recipe.describe().startsWith("estimator area_correlation;"));
    }

    /**
     * The 96 log-ratio identifiers are unchanged, and the 16 estimator candidates are distinct from
     * them and from each other.
     */
    @Test
    public void theEstimatorAxisDidNotRenameTheExistingCandidates() {
        Set<String> swept = new LinkedHashSet<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.sweptCandidates()) {
            swept.add(recipe.id());
            assertFalse(recipe.id(), recipe.id().contains("estimator_"));
            assertTrue(recipe.id(), recipe.id().startsWith("support_"));
        }
        assertEquals(96, swept.size());

        List<RegistrationRecipe> estimatorCandidates = RegistrationRecipe.estimatorCandidates();
        assertEquals(16, estimatorCandidates.size());
        for (RegistrationRecipe recipe : estimatorCandidates) {
            assertTrue(recipe.id(), recipe.id().startsWith("estimator_area_correlation__"));
            assertFalse("an estimator candidate must not collide with a log-ratio one",
                    swept.contains(recipe.id()));
            assertSame(PixelSelectionStrategy.NONE, recipe.pixelSelectionStrategy);
        }
        assertEquals(128, RegistrationRecipe.allCandidates().size());
    }

    /** The sweep and the plugin have to agree on both axes, not only the four settings dimensions. */
    @Test
    public void theSweepEnumeratesTheSameCandidatesAsTheApi() {
        Set<String> sweep = new LinkedHashSet<>();
        for (FullSelectorFactorialBenchmark.Recipe recipe
                : FullSelectorFactorialBenchmark.recipes()) {
            sweep.add(recipe.id);
        }
        Set<String> api = new LinkedHashSet<>();
        for (RegistrationRecipe recipe : RegistrationRecipe.allCandidates()) api.add(recipe.id());
        assertEquals(api, sweep);
    }

    /** Two-pass pixel selection is log-ratio machinery, and says so rather than ignoring the ask. */
    @Test
    public void theRefitPathRefusesANonLogRatioEstimator() {
        Registration.Options options = new Registration.Options();
        options.estimator = PairEstimator.Kind.AREA_CORRELATION;
        try {
            Registration.refitWithSupport(new ConstantSource(), options,
                    new Registration.Result[]{null}[0], new boolean[1][][], null, null);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** Minimal frame source: the refusal under test happens before a pixel is read. */
    private static final class ConstantSource implements ripr.core.FrameSource {
        @Override
        public int count() {
            return 2;
        }

        @Override
        public int width() {
            return 8;
        }

        @Override
        public int height() {
            return 8;
        }

        @Override
        public float[] plane(int index) {
            return new float[64];
        }
    }
}
