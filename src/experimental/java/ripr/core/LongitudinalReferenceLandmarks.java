/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Arrays;

/** Frozen Round 17 same-channel landmark reference. Experimental, not a public route. */
final class LongitudinalReferenceLandmarks {
    private LongitudinalReferenceLandmarks() { }

    static float[] warp(float[] source,int width,int height,Transform transform) {
        if(source.length!=width*height)throw new IllegalArgumentException("Shape mismatch");
        float[] output=new float[source.length];
        double[] mapped=new double[2];
        double cosine=Math.cos(transform.theta),sine=Math.sin(transform.theta);
        double cx=(width-1)/2.0,cy=(height-1)/2.0;
        boolean whole=transform.theta==0 && Math.abs(transform.dx-Math.round(transform.dx))<1e-9
                && Math.abs(transform.dy-Math.round(transform.dy))<1e-9;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            if(whole) {mapped[0]=x+Math.round(transform.dx);mapped[1]=y+Math.round(transform.dy);}
            else if(transform.theta==0) {mapped[0]=x+transform.dx;mapped[1]=y+transform.dy;}
            else {
                double u=x-cx,v=y-cy;
                mapped[0]=cosine*u-sine*v+cx+transform.dx;
                mapped[1]=sine*u+cosine*v+cy+transform.dy;
            }
            double xx=mapped[0],yy=mapped[1];int p=y*width+x;
            if(!(xx>=0 && yy>=0 && xx<=width-1 && yy<=height-1)) {output[p]=Float.NaN;continue;}
            int x0=(int)xx,y0=(int)yy,x1=Math.min(x0+1,width-1),y1=Math.min(y0+1,height-1);
            if(whole) {output[p]=source[y0*width+x0];continue;}
            double fx=xx-x0,fy=yy-y0;
            // ndimage order=1 converts the source to double, then sums four weighted pixels.
            double value=0;
            value+=source[y0*width+x0]*(1-fy)*(1-fx);
            value+=source[y0*width+x1]*(1-fy)*fx;
            value+=source[y1*width+x0]*fy*(1-fx);
            value+=source[y1*width+x1]*fy*fx;
            output[p]=(float)value;
        }
        return output;
    }

    static float[] reference(float[][] features,Transform[] baseline,int width,int height) {
        if(features.length<2 || baseline.length!=features.length)throw new IllegalArgumentException("Frame count differs");
        int pixels=width*height;
        float[][] aligned=new float[features.length][];
        for(int i=0;i<features.length;i++)aligned[i]=warp(features[i],width,height,baseline[i]);
        float[] median=new float[pixels],salience=new float[pixels],scratch=new float[features.length];
        boolean[] support=new boolean[pixels];int supported=0;
        for(int p=0;p<pixels;p++) {
            int count=0;
            for(float[] frame:aligned)if(Float.isFinite(frame[p]))scratch[count++]=frame[p];
            Arrays.sort(scratch,0,count);
            median[p]=count==0?Float.NaN:(count%2==1?scratch[count/2]:(scratch[count/2-1]+scratch[count/2])/2f);
            support[p]=count/(double)features.length>=.80;
            if(support[p])salience[supported++]=Math.abs(median[p]-6f);
        }
        if(supported==0)throw new IllegalArgumentException("No common landmark support");
        salience=Arrays.copyOf(salience,supported);Arrays.sort(salience);
        double threshold=LongitudinalReferenceFeatures.percentileSorted(salience,55);
        boolean[] strong=new boolean[pixels];
        for(int p=0;p<pixels;p++)strong[p]=support[p] && Math.abs(median[p]-6f)>=threshold;
        int size=Math.max(1,(int)Math.rint(7.0*Math.min(width,height)/512));
        boolean[] mask=dilate(strong,width,height,size);int selected=0;
        for(int p=0;p<pixels;p++) {
            if(mask[p] && support[p])selected++;
            else median[p]=Float.NaN;
        }
        if(selected<Math.max(16,(int)Math.rint(.0009765625*pixels)))
            throw new IllegalArgumentException("Insufficient edge and dark-landmark support");
        return median;
    }

    static boolean[] dilate(boolean[] source,int width,int height,int size) {
        boolean[] output=new boolean[source.length];int centre=size/2;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            outer:for(int j=0;j<size;j++)for(int i=0;i<size;i++) {
                int yy=y+centre-j,xx=x+centre-i;
                if(xx>=0 && xx<width && yy>=0 && yy<height && source[yy*width+xx]) {
                    output[y*width+x]=true;break outer;
                }
            }
        }
        return output;
    }
}
