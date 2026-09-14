/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class LongitudinalReferenceLandmarkTest {
    @Test public void warpHasTheSamplingSignAndDoesNotChangeInput() {
        float[] source=new float[16];for(int i=0;i<16;i++)source[i]=i;
        float[] copy=source.clone();float[] out=LongitudinalReferenceLandmarks.warp(source,4,4,new Transform(.5,.5,0));
        assertEquals(2.5f,out[0],0);assertEquals(7.5f,out[5],0);assertTrue(Float.isNaN(out[15]));
        assertArrayEquals(copy,source,0);
        assertArrayEquals(source,LongitudinalReferenceLandmarks.warp(source,4,4,Transform.IDENTITY),0);
    }
    @Test public void evenDilationFootprintsHaveReferenceOrigin() {
        boolean[] point=new boolean[49];point[24]=true;
        boolean[] mask=LongitudinalReferenceLandmarks.dilate(point,7,7,2);int count=0;
        for(int i=0;i<mask.length;i++)if(mask[i]) {count++;assertTrue(i/7>=2 && i/7<=3 && i%7>=2 && i%7<=3);}
        assertEquals(4,count);
    }
    @Test public void invalidCubicNeighbourCannotFallBackToBilinear() {
        float[] source=new float[16*16];Arrays.fill(source,100);source[6*16+6]=Float.NaN;
        AreaCorrelation.Window window=AreaCorrelation.Window.of(LogPlane.of(source,16,16,1),new PairAligner.Options());
        assertTrue(Double.isNaN(window.sample(7.2,7.4)));
        assertFalse(Double.isNaN(window.sample(10.2,10.4)));
    }
    @Test public void selectedReferenceRejectsMissingCommonSupport() {
        float[][] features={new float[64],new float[64]};Arrays.fill(features[0],6);Arrays.fill(features[1],6);
        try {LongitudinalReferenceLandmarks.reference(features,new Transform[]{new Transform(100,0,0),new Transform(-100,0,0)},8,8);
            fail("Expected no common support");}catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("support"));}
    }
    @Test public void missingPairPositionsAreInterpolatedAndAnchorIsRetained() {
        Transform[] input={Transform.IDENTITY,new Transform(100,100,0),new Transform(4,8,0),new Transform(-50,-50,0)};
        Transform[] result=LongitudinalReferenceLandmarkTrajectory.repairUnsupported(input,new boolean[]{true,false,true,false});
        assertEquals(0,result[0].dx,0);assertEquals(2,result[1].dx,0);assertEquals(4,result[1].dy,0);
        assertEquals(4,result[3].dx,0);assertEquals(100,input[1].dx,0);
    }
    @Test public void referenceAreaRecoversKnownTranslationWithoutChangingInputs() {
        int w=96,h=96;float[] a=Synth.frame(w,h,0,0),b=Synth.frame(w,h,2.4,-1.7,2,100);
        float[] saved=b.clone();PairAligner.Options o=LongitudinalReferenceArea.options(w,h);o.maxShift=6;
        int levels=o.levelsFor(w,h);
        PairAligner.Fit result=LongitudinalReferenceArea.align(LogPlane.of(a,w,h,1).pyramid(levels),
            LogPlane.of(b,w,h,1).pyramid(levels),new Transform(2,-2,0),o);
        assertTrue(result.usable());assertEquals(2.4,result.transform.dx,.10);assertEquals(-1.7,result.transform.dy,.10);
        assertArrayEquals(saved,b,0);
    }
}
