/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.bytedeco.opencv.global.opencv_core.*;
public class LongitudinalMedianSelectionTest {
    private static void exact(float[] values) {
        float[] copy=values.clone();
        assertEquals(Float.floatToRawIntBits(LongitudinalReferenceFeatures.median(values,false)),
            Float.floatToRawIntBits(LongitudinalReferenceFeatures.median(values,true)));
        for(int i=0;i<values.length;i++)assertEquals(Float.floatToRawIntBits(copy[i]),Float.floatToRawIntBits(values[i]));
    }
    @Test public void emptyOddEvenNonfiniteAndSignedZeroAreExact() {
        for(float[] a:new float[][]{{},{0},{-0f},{-0f,0f},{0f,-0f,-0f},{-0f,-0f},
                {Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY},{-1,Float.NaN,3},
                {Float.MAX_VALUE,Float.MAX_VALUE},{-Float.MAX_VALUE,-Float.MAX_VALUE},
                {Float.MIN_VALUE,-Float.MIN_VALUE,0f,-0f},{5,1,5,1,5,1}})exact(a);
    }
    @Test public void repeatedRandomValuesMatchSortedDefinitionBitForBit() {
        Random random=new Random(230907);
        for(int n=0;n<300;n++)for(int repeat=0;repeat<5;repeat++) {
            float[] a=new float[n];for(int i=0;i<n;i++)a[i]=repeat%2==0?Float.intBitsToFloat(random.nextInt()):random.nextInt(17)-8;
            exact(a);
        }
    }
    @Test public void largeOrderedFlatAndMixedArraysAreExact() {
        Random random=new Random(103);
        for(int n:new int[]{1024,131073,262144})for(int kind=0;kind<4;kind++) {
            float[] a=new float[n];for(int i=0;i<n;i++)a[i]=kind==0?i:kind==1?n-i:kind==2?4f:random.nextFloat();
            exact(a);
        }
    }
    @Test public void completeFeaturesRemainBitIdentical() {
        setNumThreads(1);setUseOpenCL(false);int w=127,h=131;
        float[][] frames=new float[4][w*h];Random random=new Random(52);
        for(int i=0;i<frames.length;i++)for(int y=0;y<h;y++)for(int x=0;x<w;x++)
            frames[i][y*w+x]=(float)(12+Math.sin(x*.13)*Math.cos(y*.07)*10+random.nextFloat()*i);
        for(boolean emission:new boolean[]{false,true}) {
            float[][] expected=LongitudinalPreparedFrames.prepare(frames,w,h,emission,1,false);
            for(int workers:new int[]{1,4,6}) {
                float[][] actual=LongitudinalPreparedFrames.prepare(frames,w,h,emission,workers,true);
                for(int i=0;i<frames.length;i++)for(int p=0;p<w*h;p++)
                    assertEquals(Float.floatToRawIntBits(expected[i][p]),Float.floatToRawIntBits(actual[i][p]));
            }
        }
    }
}
