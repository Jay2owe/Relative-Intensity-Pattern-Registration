/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class LongitudinalReferenceFallbackTest {
    @Test public void rejectedDerivativeFitUsesReferenceGridNotQuadraticVertex() {
        int width=24,height=24;
        PairAligner.Options options=new PairAligner.Options();options.maxShift=3;
        options.maxIterations=25;options.minValidFraction=.90;options.support=PairAligner.PixelSupport.ALL;
        LogPlane[] a=Synth.pyramid(Synth.frame(width,height,0,0),width,height,1);
        LogPlane[] b=Synth.pyramid(Synth.frame(width,height,.20,.30),width,height,1);
        PairAligner.Fit fit=AreaCorrelation.align(a,b,options,AreaCorrelation.Refiner.ECC);
        // Four-pixel cubic support cannot reach 90% of this small image, so ECC
        // must use its backup. Five reference grid rounds only visit multiples of 1/256.
        assertTrue(fit.usable());
        assertEquals(Math.rint(fit.transform.dx*256),fit.transform.dx*256,1e-10);
        assertEquals(Math.rint(fit.transform.dy*256),fit.transform.dy*256,1e-10);
        assertEquals(4,fit.iterations);
    }
}
