/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class LongitudinalReferenceWarperTest {
    @Test public void integerOutputMustRoundDoubleNotAnIntermediateFloat() {
        short[] source={100,101,100,101};
        byte[] out=LongitudinalReferenceWarper.bilinear(source,2,2,new Transform(.49999999,0,0));
        assertEquals(100,ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getShort()&65535);
        // The former float-first pattern rounds 100.49999999 to 100.5 and saves 101 instead.
        assertEquals(101,Math.round((float)(100+.49999999)));
        assertArrayEquals(new short[]{100,101,100,101},source);
    }
    @Test public void exactHalfRoundsUpAndUnsignedPixelsDoNotBecomeNegative() {
        short[] source={(short)65534,(short)65535,(short)65534,(short)65535};
        byte[] out=LongitudinalReferenceWarper.bilinear(source,2,2,new Transform(.5,0,0));
        assertEquals(65535,ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getShort()&65535);
    }
    @Test public void floatOutputRetainsFractionsAndOutsidePixelsAreZero() {
        float[] source={100,101,100,101};
        ByteBuffer out=ByteBuffer.wrap(LongitudinalReferenceWarper.bilinear(source,2,2,new Transform(.25,0,0))).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(100.25f,out.getFloat(),0);assertEquals(0f,out.getFloat(),0);
    }
}
