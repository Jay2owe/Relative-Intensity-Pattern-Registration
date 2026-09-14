/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.bytedeco.opencv.global.opencv_core.*;
public class LongitudinalSmoothingReuseTest {
    private static float[] pattern(int w,int h,int shift) {
        float[] p=new float[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double xx=x-shift;p[y*w+x]=(float)(12+Math.sin(xx*.23)*Math.cos(y*.19)+4*Math.exp(-((xx-w*.43)*(xx-w*.43)+(y-h*.51)*(y-h*.51))/60));
        }return p;
    }
    @Test public void reusedSmoothingSurvivesFailedSeedsAndFilterChanges() {
        setNumThreads(1);setUseOpenCL(false);int w=96,h=99;float[] a=pattern(w,h,0),b=pattern(w,h,2);
        int[] filters={3,3,5,11,3};float[][] seeds={{1,0,0,0,1,0},{1,0,10000,0,1,10000},{1,0,2,0,1,0},{1,0,0,0,1,0},{1,0,0,0,1,0}};
        int failures=0;
        try(OpenCvLongitudinalOps.FitBuffer buffer=new OpenCvLongitudinalOps.FitBuffer(a,b,w,h,true)) {
            for(int i=0;i<filters.length;i++) {
                boolean af=false,bf=false;OpenCvLongitudinalOps.Fit expected=null,actual=null;
                try{expected=OpenCvLongitudinalOps.fit(a,b,w,h,seeds[i],filters[i]);}catch(RuntimeException failure){af=true;}
                try{actual=buffer.fit(seeds[i],filters[i]);}catch(RuntimeException failure){bf=true;}
                assertEquals(af,bf);if(af){failures++;continue;}
                assertEquals(Double.doubleToRawLongBits(expected.score),Double.doubleToRawLongBits(actual.score));
                for(int j=0;j<6;j++)assertEquals(Float.floatToRawIntBits(expected.matrix[j]),Float.floatToRawIntBits(actual.matrix[j]));
            }
        }assertTrue(failures>0);
    }
    @Test public void allCandidateSeedJournalsAndFinalConfidenceRemainExact() {
        setNumThreads(1);setUseOpenCL(false);int w=96,h=99;
        float[][] frames={pattern(w,h,0),pattern(w,h,1),pattern(w,h,2),pattern(w,h,0),pattern(w,h,3),pattern(w,h,1)};
        for(int i=0;i<frames[1].length;i++)frames[1][i]*=2;
        double[][] initial=new double[frames.length][3];
        LongitudinalExecutionPolicy base=new LongitudinalExecutionPolicy(4,false,true,4,4,4,true,false);
        LongitudinalExecutionPolicy fast=new LongitudinalExecutionPolicy(4,false,true,4,4,4,true,true);
        OpenCvLongitudinalTrajectory.Outcome expected=OpenCvLongitudinalTrajectory.estimate(frames,w,h,initial,base);
        OpenCvLongitudinalTrajectory.Outcome actual=OpenCvLongitudinalTrajectory.estimate(frames,w,h,initial,fast);
        assertEquals(expected.pairs,actual.pairs);assertEquals(expected.bright,actual.bright);assertEquals(expected.dim,actual.dim);
        for(int i=0;i<frames.length;i++) {
            assertEquals(Double.doubleToRawLongBits(expected.confidence[i]),Double.doubleToRawLongBits(actual.confidence[i]));
            for(int j=0;j<3;j++)assertEquals(Double.doubleToRawLongBits(expected.trajectory[i][j]),Double.doubleToRawLongBits(actual.trajectory[i][j]));
        }
    }
    @Test public void smoothingCannotBeRequestedWithoutOwnedBuffers() {
        try{new LongitudinalExecutionPolicy(4,false,false,4,4,4,true,true);fail("Buffer prerequisite missing");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("buffers"));}
    }
}
