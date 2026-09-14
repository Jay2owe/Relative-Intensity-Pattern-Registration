/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.PairAligner;
import ripr.core.RotationMode;

import java.util.Locale;

/**
 * Frozen selector for the angle recipe used after Automatic has selected translation.
 *
 * <p>Translation and rotation are deliberately separate decisions. The existing automatic selector
 * remains authoritative for dx/dy. This selector chooses only the pixels, filter, estimator and
 * confidence gate used to propose an angle; the translation recipe then refits dx/dy at any angle
 * that survives the confidence test.
 */
public final class AutomaticRotationSelector {
    public static final String CATEGORY_RECIPE = "category_recommendation";
    public static final double PHASE_GLOBAL_STEP90_THRESHOLD = 2.0;

    private AutomaticRotationSelector() { }

    /** Complete, replayable decision made for one automatic rigid run. */
    public static final class Result {
        public final RegistrationRecipe recipe;
        public final RelativeIntensityPatternParameters parameters;
        public final double minimumResidualGain;
        public final boolean globalProposalComparison;
        public final double provisionalStep90;

        Result(RegistrationRecipe recipe, RelativeIntensityPatternParameters parameters,
               double minimumResidualGain, boolean globalProposalComparison,
               double provisionalStep90) {
            this.recipe = recipe;
            this.parameters = parameters;
            this.minimumResidualGain = minimumResidualGain;
            this.globalProposalComparison = globalProposalComparison;
            this.provisionalStep90 = provisionalStep90;
        }

        public String explanation() {
            String proposal = globalProposalComparison
                    ? "best of translation-seeded and global angle proposals"
                    : "translation-seeded angle proposal";
            String phaseRule = Double.isFinite(provisionalStep90)
                    ? String.format(Locale.ROOT,
                        "; provisional 90th-percentile step %.4f px %s %.4f px",
                        provisionalStep90,
                        globalProposalComparison ? "exceeded" : "did not exceed",
                        PHASE_GLOBAL_STEP90_THRESHOLD)
                    : "";
            return "automatic rotation selection: " + recipe.describe()
                    + "; " + proposal
                    + String.format(Locale.ROOT,
                            "; minimum residual gain %.4f", minimumResidualGain)
                    + phaseRule;
        }
    }

    /** Apply the frozen type policy, using neutral provisional evidence only for Phase. */
    public static Result select(RelativeIntensityPatternParameters base,
                                AutomaticRegistrationSelector.Evidence evidence) {
        if (base == null) throw new IllegalArgumentException("base parameters are null");
        RegistrationRecipe recipe;
        double gain;
        boolean global = false;
        double step90 = Double.NaN;
        switch (base.imageType) {
            case BRIGHTFIELD_DIC:
                recipe = AutomaticRegistrationSelector.categoryRecipe(base);
                gain = 0.001;
                break;
            case DENSE_FLUORESCENCE:
                recipe = RegistrationRecipe.swept(
                        PairAligner.PixelSupport.MUTUAL_NOISE_GRADIENT,
                        RegistrationRecipe.Band.NO_TOP_10,
                        Preprocessing.GAUSSIAN_0_7,
                        RegistrationRecipe.Mask.LEAST_INFORMATIVE_25);
                gain = 0.002;
                break;
            case FIDUCIAL_STATIC:
                recipe = RegistrationRecipe.swept(
                        PairAligner.PixelSupport.ALL,
                        RegistrationRecipe.Band.NO_TOP_10,
                        Preprocessing.GAUSSIAN_0_7,
                        RegistrationRecipe.Mask.NONE);
                gain = 0.005;
                break;
            case SPARSE_LOW_LIGHT_FLUORESCENCE:
                recipe = RegistrationRecipe.swept(
                        PairAligner.PixelSupport.ALL,
                        RegistrationRecipe.Band.NO_BOTTOM_25,
                        Preprocessing.GAUSSIAN_1_0,
                        RegistrationRecipe.Mask.LEAST_INFORMATIVE_25);
                gain = 0.010;
                break;
            case PHASE_CONTRAST:
                if (evidence == null || evidence.motionFeatures.length < 2) {
                    throw new IllegalArgumentException(
                            "Phase rotation selection requires neutral provisional movement evidence");
                }
                step90 = evidence.motionFeatures[1];
                global = needsGlobalPhaseProposal(step90);
                recipe = RegistrationRecipe.swept(
                        PairAligner.PixelSupport.ALL,
                        RegistrationRecipe.Band.NO_TOP_25,
                        global ? Preprocessing.NONE : Preprocessing.GAUSSIAN_0_7,
                        global ? RegistrationRecipe.Mask.NONE
                                : RegistrationRecipe.Mask.LEAST_INFORMATIVE_25);
                gain = global ? 0.005 : 0.010;
                break;
            default:
                throw new IllegalStateException("unhandled image type " + base.imageType);
        }
        RelativeIntensityPatternParameters rotation = recipe.applyTo(base).toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(true)
                .incrementalRotation(false)
                .minimumRotationResidualGain(gain)
                .rotationRecipeId(recipeId(base, recipe))
                .globalRotationProposal(global)
                .rotationSelectorStep90(step90)
                .build();
        return new Result(recipe, rotation, gain, global, step90);
    }

    /** Frozen Phase branch boundary, exposed so the exact edge is regression-tested. */
    public static boolean needsGlobalPhaseProposal(double provisionalStep90) {
        return Double.isFinite(provisionalStep90)
                && provisionalStep90 > PHASE_GLOBAL_STEP90_THRESHOLD;
    }

    /** Rebuild a previously resolved decision without measuring the image again. */
    public static Result fromResolved(RelativeIntensityPatternParameters parameters) {
        if (parameters == null || parameters.rotationMode != RotationMode.CONTINUOUS
                || parameters.rotationRecipeId.isEmpty()) return null;
        RegistrationRecipe recipe = recipe(parameters.rotationRecipeId, parameters);
        RelativeIntensityPatternParameters rotation = recipe.applyTo(parameters).toBuilder()
                .selectionMode(SelectionMode.MANUAL)
                .fitRotation(true)
                .incrementalRotation(false)
                .minimumRotationResidualGain(parameters.minimumRotationResidualGain)
                .rotationRecipeId(parameters.rotationRecipeId)
                .globalRotationProposal(parameters.globalRotationProposal)
                .rotationSelectorStep90(parameters.rotationSelectorStep90)
                .build();
        return new Result(recipe, rotation, parameters.minimumRotationResidualGain,
                parameters.globalRotationProposal, parameters.rotationSelectorStep90);
    }

    private static String recipeId(RelativeIntensityPatternParameters base, RegistrationRecipe recipe) {
        return base.imageType == ImageType.BRIGHTFIELD_DIC
                ? CATEGORY_RECIPE : recipe.id();
    }

    private static RegistrationRecipe recipe(String id, RelativeIntensityPatternParameters base) {
        if (CATEGORY_RECIPE.equals(id)) {
            return AutomaticRegistrationSelector.categoryRecipe(base);
        }
        for (RegistrationRecipe candidate : RegistrationRecipe.allCandidates()) {
            if (candidate.id().equals(id)) return candidate;
        }
        throw new IllegalArgumentException("unknown rotation recipe: " + id);
    }
}
