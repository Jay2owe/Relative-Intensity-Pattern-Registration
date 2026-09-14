/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class LongitudinalStripedGaussianTest {
    @Test public void preservesCompleteEdgeAndDarkLandmarkFeatures() {
        Random random=new Random(914);
        for(int[] shape:new int[][]{{19,13},{64,41},{192,128}}) {
            float[] source=new float[shape[0]*shape[1]];
            for(int i=0;i<source.length;i++)source[i]=random.nextFloat()*65535f;
            float[] expected=LongitudinalReferenceFeatures.landmark(source,shape[0],shape[1]);
            float[] actual=LongitudinalReferenceFeatures.landmark(source,shape[0],shape[1],LongitudinalStripedGaussian::filter);
            for(int i=0;i<actual.length;i++)assertEquals(Float.floatToRawIntBits(expected[i]),Float.floatToRawIntBits(actual[i]));
        }
    }
    @Test public void preservesEveryFloatBitAcrossSizesAndWideBorders() {
        Random random=new Random(7319);
        for(int[] shape:new int[][]{{2,2},{3,7},{13,9},{64,41},{192,128}}) {
            float[] source=new float[shape[0]*shape[1]];
            for(int i=0;i<source.length;i++)source[i]=(random.nextFloat()-.5f)*10;
            float[] unchanged=source.clone();
            for(double sigma:new double[]{.8,1.5,3,8,25.6}) {
                float[] expected=LongitudinalReferenceFeatures.gaussianNearest(source,shape[0],shape[1],sigma);
                float[] actual=LongitudinalStripedGaussian.filter(source,shape[0],shape[1],sigma);
                for(int i=0;i<actual.length;i++)assertEquals("pixel "+i,Float.floatToRawIntBits(expected[i]),Float.floatToRawIntBits(actual[i]));
            }
            assertArrayEquals(unchanged,source,0);
        }
    }
    @Test public void preservesConstantsSignedZeroAndMissingPixels() {
        float[][] inputs={new float[63],new float[63],new float[63]};
        java.util.Arrays.fill(inputs[0],-.0f);java.util.Arrays.fill(inputs[1],42f);
        java.util.Arrays.fill(inputs[2],2f);inputs[2][31]=Float.NaN;
        for(float[] source:inputs) {
            float[] expected=LongitudinalReferenceFeatures.gaussianNearest(source,9,7,3);
            float[] actual=LongitudinalStripedGaussian.filter(source,9,7,3);
            for(int i=0;i<actual.length;i++)assertEquals(Float.floatToRawIntBits(expected[i]),Float.floatToRawIntBits(actual[i]));
        }
    }
}
