/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import logratio.api.LogRatioParameters;
import logratio.api.LogRatioRegistration;
import logratio.core.PairScheduler;
import logratio.core.Reconciler;
import logratio.core.Registration;
import org.junit.Test;

import static org.junit.Assert.*;

public class LogRatioSweepResidualTest {
    @Test
    public void correctedFullResolutionResidualBeatsIdentityOnShiftedStack() {
        int size = 72;
        float[] texture = new float[size * size];
        java.util.Random random = new java.util.Random(98L);
        for (int i = 0; i < texture.length; i++) texture[i] = 20 + random.nextFloat() * 180;
        ImageStack stack = new ImageStack(size, size);
        for (int frame = 0; frame < 4; frame++) {
            float[] pixels = new float[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int source = x - 2 * frame;
                pixels[y * size + x] = source >= 0 ? texture[y * size + source] : Float.NaN;
            }
            stack.addSlice(new FloatProcessor(size, size, pixels));
        }
        ImagePlus image = new ImagePlus("shift", stack);
        LogRatioParameters parameters = LogRatioParameters.builder().useRecommendation(false)
                .reference(Reconciler.Reference.CONSECUTIVE).autoMaxShift(false).maxShift(10)
                .threads(1).crop(false).build();
        Registration.Result registered = LogRatioRegistration.estimate(image, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        ImagePlus staticImage = new ImagePlus("same", staticStack(texture, size));
        Registration.Result identity = LogRatioRegistration.estimate(staticImage, parameters,
                PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
        // Replace the static result's identities with the deliberate no-correction comparator while
        // retaining a structurally valid result object from the public engine.
        double corrected = LogRatioSweepDialog.fullResolutionResidual(image, parameters, registered);
        double uncorrected = LogRatioSweepDialog.fullResolutionResidual(image, parameters, identity);
        assertTrue("corrected=" + corrected + " uncorrected=" + uncorrected, corrected < uncorrected);
        image.close();
        staticImage.close();
    }

    private static ImageStack staticStack(float[] texture, int size) {
        ImageStack stack = new ImageStack(size, size);
        for (int i = 0; i < 4; i++) stack.addSlice(new FloatProcessor(size, size, texture.clone()));
        return stack;
    }
}
