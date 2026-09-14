/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bilinear output in the frozen double accumulation order, preserving native pixel type. */
final class LongitudinalReferenceWarper {
    private LongitudinalReferenceWarper() { }
    static byte[] bilinear(Object source,int width,int height,Transform t) {
        int bytes=source instanceof byte[]?1:source instanceof short[]?2:source instanceof float[]?4:0;
        if(bytes==0)throw new IllegalArgumentException("Only unsigned 8/16-bit or float32 pixels are supported");
        int length=java.lang.reflect.Array.getLength(source);
        if(length!=width*height)throw new IllegalArgumentException("Shape mismatch");
        ByteBuffer out=ByteBuffer.allocate(bytes*length).order(ByteOrder.LITTLE_ENDIAN);
        double c=Math.cos(t.theta),s=Math.sin(t.theta),cx=(width-1)/2.0,cy=(height-1)/2.0;
        boolean whole=t.theta==0 && Math.abs(t.dx-Math.round(t.dx))<1e-9 && Math.abs(t.dy-Math.round(t.dy))<1e-9;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double xx,yy;
            if(whole) {xx=x+Math.round(t.dx);yy=y+Math.round(t.dy);}
            else if(t.theta==0) {xx=x+t.dx;yy=y+t.dy;}
            else {double u=x-cx,v=y-cy;xx=c*u-s*v+cx+t.dx;yy=s*u+c*v+cy+t.dy;}
            double value=0;
            if(xx>=0 && yy>=0 && xx<=width-1 && yy<=height-1) {
                int x0=(int)xx,y0=(int)yy,x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);
                if(whole)value=get(source,y0*width+x0);
                else {
                    double fx=xx-x0,fy=yy-y0;
                    value+=get(source,y0*width+x0)*(1-fy)*(1-fx);
                    value+=get(source,y0*width+x1)*(1-fy)*fx;
                    value+=get(source,y1*width+x0)*fy*(1-fx);
                    value+=get(source,y1*width+x1)*fy*fx;
                }
            }
            if(bytes==1)out.put((byte)Math.max(0,Math.min(255,Math.floor(value+.5))));
            else if(bytes==2)out.putShort((short)Math.max(0,Math.min(65535,Math.floor(value+.5))));
            else out.putFloat((float)value);
        }
        return out.array();
    }
    private static double get(Object a,int i) {
        return a instanceof byte[]?((byte[])a)[i]&255:a instanceof short[]?((short[])a)[i]&65535:((float[])a)[i];
    }
}
