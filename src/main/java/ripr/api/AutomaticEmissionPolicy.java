/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.api;

import ripr.core.PairAligner;
import ripr.core.PairEstimator;
import ripr.core.Reconciler;

/** Fixed fluorescence and bioluminescence routes selected from declared input categories. */
final class AutomaticEmissionPolicy {
    static final String VERSION = "single_channel_emission_max_accuracy_r04_a208";
    static final String MODEL_KIND = "declared_image_and_motion_rule";
    static final String VALIDATION_STATUS = "REAL_24_SINGLE_CHANNEL_NATIVE_PASS";
    static final String FEATURE_CONTRACT_VERSION = "declared_inputs_v2";
    static final String PROTOCOL_SHA256 =
            "dfb454716dd831a0c17221756dd5ed167a080d8cedc8ff287ec4a326016c86d1";
    static final String CANDIDATE_MANIFEST_SHA256 =
            "4af4e55141e34c5ee8670fa248aebd025ba718f1648cf34b4c21aedb729ea139";
    static final String MODEL_ARTIFACT_SHA256 =
            "681bc10ff5edec137cc9977f49a329ac9ea2016766520b79c67d1a10e181e32d";

    private AutomaticEmissionPolicy() { }

    static boolean serves(RelativeIntensityPatternParameters base) {
        return base != null && (base.imageType == ImageType.DENSE_FLUORESCENCE
                || base.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE);
    }

    /** Apply only declared categories; no image plane is available to inspect. */
    static AutomaticRegistrationSelector.Result select(
            RelativeIntensityPatternParameters base) {
        if (!serves(base)) return null;
        RelativeIntensityPatternParameters.Builder builder = base.toBuilder()
                .recommendation(base.imageType, base.motionType)
                .selectionMode(SelectionMode.MANUAL);
        String route;
        if (base.imageType == ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE) {
            route = "sparse_lowlight_image_and_motion_logratio_preset";
        } else {
            builder.reference(Reconciler.Reference.CONSECUTIVE)
                    .estimator(PairEstimator.Kind.AREA_CORRELATION_ECC)
                    .pixelSupport(PairAligner.PixelSupport.GRADIENT)
                    .gradientFraction(0.5)
                    .preprocessing(Preprocessing.MEDIAN_3X3)
                    .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                    .pixelSelectionPreprocessing(Preprocessing.NONE)
                    .floorPercentile(Double.NaN)
                    .ceilingPercentile(90.0)
                    .outlierMads(0.0)
                    .outlierProtectionResidualGain(Double.NaN)
                    .maxIterations(25)
                    .maxSamples(200_000);
            route = "dense_filtered_previous_image_ecc";
        }
        RelativeIntensityPatternParameters resolved = builder.build();
        return new AutomaticRegistrationSelector.Result(
                null, RegistrationRecipe.of(resolved), false, 0.0, 0.0,
                new double[]{0.0}, resolved, "fixed_route=" + route);
    }
}
