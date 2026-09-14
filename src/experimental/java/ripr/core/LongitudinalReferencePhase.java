/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Arrays;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Windowed double-precision phase check for rare rigid jumps, not the emission seed. */
final class LongitudinalReferencePhase {
    private LongitudinalReferencePhase() { }

    static final class Plane {
        final float[] values;final int width,height;
        Plane(float[] values,int width,int height) {this.values=values;this.width=width;this.height=height;}
    }

    static Plane thumbnail(float[] feature,int width,int height,int maximum) {
        double scale=Math.min(1,maximum/(double)Math.max(width,height));
        int w=(int)Math.rint(width*scale),h=(int)Math.rint(height*scale);
        float[] value=new float[feature.length];for(int i=0;i<value.length;i++)value[i]=feature[i]-6f;
        if(scale==1)return new Plane(value,width,height);
        float[] out=new float[w*h];
        double stepX=w>1?(width-1)/(double)(w-1):0,stepY=h>1?(height-1)/(double)(h-1):0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double xx=x*stepX,yy=y*stepY;int x0=(int)xx,y0=(int)yy,x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);
            double fx=xx-x0,fy=yy-y0,result=0;
            result+=value[y0*width+x0]*(1-fy)*(1-fx);result+=value[y0*width+x1]*(1-fy)*fx;
            result+=value[y1*width+x0]*fy*(1-fx);result+=value[y1*width+x1]*fy*fx;
            out[y*w+x]=(float)result;
        }
        return new Plane(out,w,h);
    }

    static double[] estimate(float[] a,float[] b,int width,int height) {
        if(a.length!=width*height || b.length!=a.length)throw new IllegalArgumentException("Shape mismatch");
        int n=0;double sumA=0,sumB=0;
        for(int i=0;i<a.length;i++)if(Float.isFinite(a[i]) && Float.isFinite(b[i])) {n++;sumA+=a[i];sumB+=b[i];}
        if(n<Math.max(64,(int)Math.rint(.1*a.length)))return new double[3];
        double meanA=sumA/n,meanB=sumB/n,fillA=median(a),fillB=median(b);
        double[] left=new double[a.length],right=new double[a.length],wx=window(width),wy=window(height);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int i=y*width+x;double weight=wy[y]*wx[x];
            left[i]=((Float.isFinite(a[i])?a[i]:fillA)-meanA)*weight;
            right[i]=((Float.isFinite(b[i])?b[i]:fillB)-meanB)*weight;
        }
        try(DoublePointer lp=new DoublePointer(left);DoublePointer rp=new DoublePointer(right);
            Mat lm=new Mat(height,width,CV_64FC1,lp);Mat rm=new Mat(height,width,CV_64FC1,rp);
            Mat lf=new Mat();Mat rf=new Mat();Mat cross=new Mat();Mat surface=new Mat()) {
            dft(lm,lf,DFT_COMPLEX_OUTPUT,0);dft(rm,rf,DFT_COMPLEX_OUTPUT,0);
            mulSpectrums(rf,lf,cross,0,true);
            java.nio.DoubleBuffer values=cross.createBuffer();double[] spectrum=new double[2*a.length];values.get(spectrum);
            for(int i=0;i<a.length;i++) {
                double divisor=Math.max(Math.hypot(spectrum[2*i],spectrum[2*i+1]),1e-12);
                spectrum[2*i]/=divisor;spectrum[2*i+1]/=divisor;
            }
            values.rewind();values.put(spectrum);dft(cross,surface,DFT_INVERSE|DFT_SCALE|DFT_REAL_OUTPUT,0);
            java.nio.DoubleBuffer result=surface.createBuffer();double[] spatial=new double[a.length];result.get(spatial);
            int peak=0;for(int i=1;i<spatial.length;i++)if(spatial[i]>spatial[peak])peak=i;
            int x=peak%width,y=peak/width;if(x>width/2)x-=width;if(y>height/2)y-=height;
            return new double[]{x,y,spatial[peak]};
        }
    }

    private static double[] window(int n) {
        double[] out=new double[n];
        for(int i=0;i<n;i++)out[i]=n==1?1:.5+.5*Math.cos(Math.PI*(1-n+2.*i)/(n-1));
        return out;
    }
    private static double median(float[] source) {
        double[] values=new double[source.length];int n=0;
        for(float value:source)if(Float.isFinite(value))values[n++]=value;
        if(n==0)return 0;Arrays.sort(values,0,n);return n%2==1?values[n/2]:(values[n/2-1]+values[n/2])/2;
    }
}
