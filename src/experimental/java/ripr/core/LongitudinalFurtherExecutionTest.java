/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;
import java.util.Arrays;
import ripr.api.*;
import static org.junit.Assert.*;
import static org.bytedeco.opencv.global.opencv_core.*;

public class LongitudinalFurtherExecutionTest {
    private static float[][] frames(int w,int h) {
        float[][] result=new float[6][w*h];
        for(int i=0;i<6;i++)for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double xx=x-i*.35;
            result[i][y*w+x]=(float)(3+2*Math.sin(xx*.21)*Math.cos(y*.13)
                +(30+i*12)*Math.exp(-((xx-w*.42)*(xx-w*.42)+(y-h*.48)*(y-h*.48))/220));
        }
        return result;
    }
    private static void bits(float[] a,float[] b) {
        assertEquals(a.length,b.length);
        for(int i=0;i<a.length;i++)assertEquals(Float.floatToRawIntBits(a[i]),Float.floatToRawIntBits(b[i]));
    }
    private static void transforms(Transform[] a,Transform[] b) {
        assertEquals(a.length,b.length);
        for(int i=0;i<a.length;i++) {
            assertEquals(Double.doubleToRawLongBits(a[i].dx),Double.doubleToRawLongBits(b[i].dx));
            assertEquals(Double.doubleToRawLongBits(a[i].dy),Double.doubleToRawLongBits(b[i].dy));
            assertEquals(Double.doubleToRawLongBits(a[i].theta),Double.doubleToRawLongBits(b[i].theta));
        }
    }
    @Test public void featureWorkersPreserveEveryBitAndInput() {
        setNumThreads(1);setUseOpenCL(false);int w=96,h=99;float[][] input=frames(w,h);
        float[][] copy=Arrays.stream(input).map(float[]::clone).toArray(float[][]::new);
        for(boolean emission:new boolean[]{false,true}) {
            float[][] base=LongitudinalPreparedFrames.prepare(input,w,h,emission,1);
            for(int workers:new int[]{2,4,6,8,12,16})for(int repeat=0;repeat<2;repeat++) {
                float[][] actual=LongitudinalPreparedFrames.prepare(input,w,h,emission,workers);
                for(int i=0;i<input.length;i++)bits(base[i],actual[i]);
            }
        }
        for(int i=0;i<input.length;i++)bits(copy[i],input[i]);
    }
    @Test public void endpointWorkersPreserveOutputAndMissingSignalFallback() {
        int w=96,h=99;float[][] input=frames(w,h);Transform[] initial=new Transform[input.length];
        Arrays.fill(initial,Transform.IDENTITY);
        for(boolean missing:new boolean[]{false,true}) {
            if(missing)Arrays.fill(input[3],Float.NaN);
            LongitudinalReferenceEndpoint.Result base=LongitudinalReferenceEndpoint.repair(input,initial,w,h,1);
            for(int workers:new int[]{2,4,6,8,12,16}) {
                LongitudinalReferenceEndpoint.Result result=LongitudinalReferenceEndpoint.repair(input,initial,w,h,workers);
                assertEquals(base.boundary,result.boundary);transforms(base.transforms,result.transforms);
            }
        }
    }
    @Test public void preliminaryWorkersPreserveEveryMovementBit() {
        int w=96,h=99;ImageStack stack=new ImageStack(w,h);
        for(float[] pixels:frames(w,h))stack.addSlice(new FloatProcessor(w,h,pixels));
        ImagePlus image=new ImagePlus("owned-test",stack);
        try {
            for(ImageType type:new ImageType[]{ImageType.PHASE_CONTRAST,ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,ImageType.DENSE_FLUORESCENCE}) {
                RelativeIntensityPatternParameters p=RelativeIntensityPatternParameters.builder().recommendation(type,MotionType.INTERMITTENT_JUMPS)
                    .selectionMode(SelectionMode.AUTOMATIC).threads(1).crop(false).build();
                Registration.Result base=RelativeIntensityPatternRegistration.estimate(image,p,PairScheduler.Progress.NONE,PairScheduler.Cancellation.NEVER);
                for(int workers:new int[]{2,4,6,8,12,16}) {
                    Registration.Result actual=RelativeIntensityPatternRegistration.estimate(image,p.toBuilder().threads(workers).build(),PairScheduler.Progress.NONE,PairScheduler.Cancellation.NEVER);
                    transforms(base.cumulative,actual.cumulative);
                }
            }
        } finally {image.close();}
    }
    @Test public void extendedPolicyKeepsOriginalDefaultsAndRejectsUnboundedWorkers() {
        LongitudinalExecutionPolicy p=new LongitudinalExecutionPolicy(4,false,true);
        assertEquals(1,p.preliminaryWorkers);assertEquals(1,p.featureWorkers);assertEquals(1,p.endpointWorkers);
        for(int[] counts:new int[][]{{0,1,1,1},{1,17,1,1},{1,1,17,1},{1,1,1,17}}) {
            try {new LongitudinalExecutionPolicy(counts[0],false,false,counts[1],counts[2],counts[3]);fail("unbounded worker request");}
            catch(IllegalArgumentException expected) { }
        }
    }
}
