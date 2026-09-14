/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LongitudinalReferenceFeaturesTest {
    @Test public void nativeFilterReceivesTheSameNormalisedImage() {
        float[] input = new float[64];
        for (int i = 0; i < input.length; i++) input[i] = i*i;
        float[] original = input.clone();
        float[] expected = LongitudinalReferenceFeatures.emission(input, 8, 8);
        float[] actual = LongitudinalReferenceFeatures.emission(input, 8, 8, (pixels,w,h,sigma) -> {
            assertArrayEquals(LongitudinalReferenceFeatures.normalise(input, true), pixels, 0f);
            assertEquals(8,w); assertEquals(8,h); assertEquals(2.0,sigma,0);
            return LongitudinalReferenceFeatures.gaussianReflect101(pixels,w,h,sigma);
        });
        for (int i=0;i<actual.length;i++) assertEquals(Float.floatToRawIntBits(expected[i]),Float.floatToRawIntBits(actual[i]));
        assertArrayEquals(original,input,0f);
    }
    @Test public void percentileUsesAllPixelsAndInterpolates() {
        assertEquals(1.5,LongitudinalReferenceFeatures.percentileSorted(new float[]{0,1,2,3},50),0);
        assertEquals(.03,LongitudinalReferenceFeatures.percentileSorted(new float[]{0,1,2,3},1),1e-15);
        assertEquals(0,LongitudinalReferenceFeatures.percentileSorted(new float[0],50),0);
    }
    @Test public void flatEmissionFeatureIsZeroAndHasNoArtificialOffset() {
        float[] input = new float[64]; Arrays.fill(input,42);
        for(float value:LongitudinalReferenceFeatures.emission(input,8,8)) assertEquals(0f,value,0f);
    }
    @Test(expected=IllegalArgumentException.class) public void dimensionsAreChecked() {
        LongitudinalReferenceFeatures.emission(new float[8],8,8);
    }
}
