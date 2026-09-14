/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.bytedeco.opencv.global.opencv_core.*;

public class LongitudinalExecutionTest {
    private static float[] pattern(int w,int h,int shift) {
        float[] p=new float[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double xx=x-shift;
            p[y*w+x]=(float)(12+Math.sin(xx*.23)*Math.cos(y*.19)+4*Math.exp(-((xx-w*.43)*(xx-w*.43)+(y-h*.51)*(y-h*.51))/60));
        }
        return p;
    }
    @Test public void cachedPhaseAndInputsStayExact() {
        setNumThreads(1);setUseOpenCL(false);
        for(int w:new int[]{48,63,96}) {
            int h=w+3;float[] a=pattern(w,h,0),b=pattern(w,h,2),aa=a.clone(),bb=b.clone();
            float[] expected=OpenCvLongitudinalOps.phase(a,b,w,h);
            try(OpenCvLongitudinalOps.Spectrum left=new OpenCvLongitudinalOps.Spectrum(a,w,h);
                OpenCvLongitudinalOps.Spectrum right=new OpenCvLongitudinalOps.Spectrum(b,w,h)) {
                for(int i=0;i<4;i++)assertArrayEquals(expected,OpenCvLongitudinalOps.phase(left,right,w,h),0f);
            }
            assertArrayEquals(aa,a,0f);assertArrayEquals(bb,b,0f);
        }
    }
    @Test public void bufferedFitSurvivesFailedSeedExactly() {
        setNumThreads(1);setUseOpenCL(false);int w=96,h=99;
        float[] a=pattern(w,h,0),b=pattern(w,h,2),aa=a.clone(),bb=b.clone();
        float[][] seeds={{1,0,0,0,1,0},{1,0,10000,0,1,10000},{1,0,2,0,1,0},{1,0,0,0,1,0}};
        int failed=0;
        try(OpenCvLongitudinalOps.FitBuffer buffer=new OpenCvLongitudinalOps.FitBuffer(a,b,w,h)) {
            for(float[] seed:seeds) {
                OpenCvLongitudinalOps.Fit expected=null;boolean oldFailed=false,newFailed=false;
                try{expected=OpenCvLongitudinalOps.fit(a,b,w,h,seed,3);}catch(RuntimeException failure){oldFailed=true;}
                OpenCvLongitudinalOps.Fit actual=null;
                try{actual=buffer.fit(seed,3);}catch(RuntimeException failure){newFailed=true;}
                assertEquals(oldFailed,newFailed);
                if(oldFailed)failed++;else {
                    assertEquals(Double.doubleToRawLongBits(expected.score),Double.doubleToRawLongBits(actual.score));
                    for(int i=0;i<6;i++)assertEquals(Float.floatToRawIntBits(expected.matrix[i]),Float.floatToRawIntBits(actual.matrix[i]));
                }
            }
        }
        assertTrue(failed>0);assertArrayEquals(aa,a,0f);assertArrayEquals(bb,b,0f);
    }
    @Test public void allCombinationsKeepOrderedDiagnosticsAndMovementBits() {
        setNumThreads(1);setUseOpenCL(false);int w=48,h=51;
        float[][] frames={pattern(w,h,0),pattern(w,h,1),pattern(w,h,2),pattern(w,h,0)};
        for(int p=0;p<frames[1].length;p++)frames[1][p]*=2;
        double[][] initial=new double[4][3];
        OpenCvLongitudinalTrajectory.Outcome base=OpenCvLongitudinalTrajectory.estimate(frames,w,h,initial);
        for(int bits=0;bits<8;bits++)for(int workers:new int[]{1,2,4}) {
            LongitudinalExecutionPolicy policy=new LongitudinalExecutionPolicy((bits&1)==0?1:workers,(bits&2)!=0,(bits&4)!=0);
            OpenCvLongitudinalTrajectory.Outcome actual=OpenCvLongitudinalTrajectory.estimate(frames,w,h,initial,policy);
            assertEquals(base.pairs,actual.pairs);assertEquals(base.bright,actual.bright);assertEquals(base.dim,actual.dim);
            for(int i=0;i<frames.length;i++) {
                assertEquals(Double.doubleToRawLongBits(base.confidence[i]),Double.doubleToRawLongBits(actual.confidence[i]));
                for(int j=0;j<3;j++)assertEquals(Double.doubleToRawLongBits(base.trajectory[i][j]),Double.doubleToRawLongBits(actual.trajectory[i][j]));
            }
        }
    }
    @Test public void parallelFailureJoinsWorkersBeforeReturning() {
        AtomicInteger active=new AtomicInteger();
        try {
            LongitudinalOrderedFrames.run(20,4,false,i->{
                active.incrementAndGet();
                try{if(i==2)throw new IllegalArgumentException("expected");for(int j=0;j<10000;j++)Math.sqrt(j);}
                finally{active.decrementAndGet();}
            });fail("Expected worker failure");
        }catch(IllegalArgumentException expected){assertEquals("expected",expected.getMessage());}
        assertEquals(0,active.get());
    }
    @Test public void interruptionIsRestoredAndWorkersJoined() throws Exception {
        CountDownLatch started=new CountDownLatch(1);AtomicInteger active=new AtomicInteger();
        AtomicInteger cancelled=new AtomicInteger();
        Thread owner=new Thread(()->{
            try{LongitudinalOrderedFrames.run(10,4,false,i->{
                active.incrementAndGet();started.countDown();
                try{while(!Thread.currentThread().isInterrupted())Thread.yield();}
                finally{active.decrementAndGet();}
            });}catch(CancellationException expected){if(Thread.currentThread().isInterrupted())cancelled.incrementAndGet();}
        });
        owner.start();assertTrue(started.await(5,TimeUnit.SECONDS));owner.interrupt();owner.join(5000);
        assertFalse(owner.isAlive());assertEquals(0,active.get());assertEquals(1,cancelled.get());
    }
    @Test public void policyDoesNotChangeDefaults() {
        assertEquals(1,LongitudinalExecutionPolicy.BASELINE.frameWorkers);
        assertFalse(LongitudinalExecutionPolicy.BASELINE.cacheSpectra);assertFalse(LongitudinalExecutionPolicy.BASELINE.reuseBuffers);
    }
}
