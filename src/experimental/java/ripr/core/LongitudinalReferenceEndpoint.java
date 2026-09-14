/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Arrays;
import java.util.Collections;

/** Frozen same-channel bright-tissue end-jump check. No added motion or other channel. */
final class LongitudinalReferenceEndpoint {
    private LongitudinalReferenceEndpoint() { }
    static final class Measurement {
        final double x,y,area;final boolean[] mask;final int width,height;
        Measurement(double x,double y,double area,boolean[] mask,int width,int height) {
            this.x=x;this.y=y;this.area=area;this.mask=mask;this.width=width;this.height=height;
        }
    }
    static final class Result {
        final Transform[] transforms;final int boundary;
        Result(Transform[] transforms,int boundary) {this.transforms=transforms;this.boundary=boundary;}
    }

    static Measurement measure(float[] source,int width,int height) {
        double scale=Math.min(1,512.0/Math.min(width,height));
        int w=(int)Math.rint(width*scale),h=(int)Math.rint(height*scale);
        float[] small=resize(source,width,height,w,h),sorted=new float[small.length];int count=0;
        for(float value:small)if(Float.isFinite(value))sorted[count++]=value;
        if(count==0)throw new IllegalArgumentException("No finite tissue pixels");
        sorted=Arrays.copyOf(sorted,count);Arrays.sort(sorted);
        double lo=LongitudinalReferenceFeatures.percentileSorted(sorted,20);
        double range=Math.max(LongitudinalReferenceFeatures.percentileSorted(sorted,99.8)-lo,1e-6);
        boolean[] mask=new boolean[small.length];
        for(int i=0;i<small.length;i++) {
            float normalised=(float)Math.max(0,Math.min(1,(small[i]-lo)/range));
            mask[i]=normalised>.22f;
        }
        int size=Math.max(3,(int)Math.rint(9*scale));
        mask=box(box(mask,w,h,size,false),w,h,size,true);
        boolean[] visited=new boolean[mask.length];int[] queue=new int[mask.length];
        int largest=0;long winnerX=0,winnerY=0;
        for(int p=0;p<mask.length;p++)if(mask[p] && !visited[p]) {
            int head=0,tail=1;queue[0]=p;visited[p]=true;long sumX=0,sumY=0;
            while(head<tail) {
                int q=queue[head++],x=q%w,y=q/w;sumX+=x;sumY+=y;
                if(x>0)tail=enqueue(q-1,mask,visited,queue,tail);
                if(x<w-1)tail=enqueue(q+1,mask,visited,queue,tail);
                if(y>0)tail=enqueue(q-w,mask,visited,queue,tail);
                if(y<h-1)tail=enqueue(q+w,mask,visited,queue,tail);
            }
            if(tail>largest) {largest=tail;winnerX=sumX;winnerY=sumY;}
        }
        double area=largest/(double)mask.length;
        if(largest==0 || area<.003)throw new IllegalArgumentException("No sufficiently large bright tissue component");
        return new Measurement(winnerX/(double)largest/scale,winnerY/(double)largest/scale,area,mask,w,h);
    }

    static Result repair(float[][] frames,Transform[] transforms,int width,int height) {
        return repair(frames,transforms,width,height,1);
    }

    static Result repair(float[][] frames,Transform[] transforms,int width,int height,int workers) {
        if(frames.length!=transforms.length)throw new IllegalArgumentException("Frame count mismatch");
        if(frames.length<6)return new Result(transforms.clone(),-1);
        Measurement[] measured=new Measurement[frames.length];
        try {LongitudinalOrderedFrames.run(frames.length,workers,false,i -> measured[i]=measure(frames[i],width,height));}
        catch(IllegalArgumentException missingTissue) {return new Result(transforms.clone(),-1);}
        return repairMeasured(measured,transforms,width,height);
    }

    static Result repairMeasured(Measurement[] measured,Transform[] transforms,int width,int height) {
        int count=measured.length;if(count!=transforms.length)throw new IllegalArgumentException("Frame count mismatch");
        if(count<6)return new Result(transforms.clone(),-1);
        double[][] raw=new double[count][2],aligned=new double[count][2];double[] area=new double[count],steps=new double[count-1];
        for(int i=0;i<count;i++) {
            raw[i][0]=measured[i].x;raw[i][1]=measured[i].y;area[i]=measured[i].area;
            transforms[i].inverse().apply(raw[i][0],raw[i][1],(width-1)/2.0,(height-1)/2.0,aligned[i]);
            if(i>0)steps[i-1]=distance(raw[i],raw[i-1]);
        }
        double cutoff=LongitudinalReferenceRigid.percentile(steps,80);double[] ordinaryValues=new double[steps.length];int n=0;
        for(double value:steps)if(value<=cutoff)ordinaryValues[n++]=value;
        double ordinary=LongitudinalReferenceRigid.median(Arrays.copyOf(ordinaryValues,n));
        double diagonal=Math.hypot(width,height),bestResidual=Double.NEGATIVE_INFINITY;
        int tail=Math.max(2,Math.min(8,(int)Math.ceil(.04*count))),winner=-1;Transform event=null;
        for(int boundary=Math.max(2,count-tail);boundary<count-1;boundary++) {
            int size=Math.min(4,Math.min(boundary,count-boundary));if(size<2)continue;
            double[] before=centre(raw,boundary-size,boundary),after=centre(raw,boundary,boundary+size);
            double dx=after[0]-before[0],dy=after[1]-before[1];
            double residual=distance(centre(aligned,boundary,boundary+size),centre(aligned,boundary-size,boundary));
            double spread=0;
            for(int i=boundary-size;i<boundary+size;i++)spread=Math.max(spread,distance(raw[i],i<boundary?before:after));
            double ratio=LongitudinalReferenceRigid.median(Arrays.copyOfRange(area,boundary,boundary+size))
                    /Math.max(LongitudinalReferenceRigid.median(Arrays.copyOfRange(area,boundary-size,boundary)),1e-9);
            if(Math.hypot(dx,dy)<Math.max(.08*diagonal,8*Math.max(ordinary,1)))continue;
            if(residual<.02*diagonal || spread>.02*diagonal || ratio<.40 || ratio>2.50)continue;
            // Python max((residual,boundary,event), ...) prefers the later boundary on an exact tie.
            if(residual>=bestResidual) {bestResidual=residual;winner=boundary;event=new Transform(dx,dy,0);}
        }
        if(winner<0)return new Result(transforms.clone(),-1);
        return new Result(LongitudinalReferenceRigid.apply(transforms,
            Collections.singletonList(new LongitudinalReferenceRigid.Event(winner,event))),winner);
    }

    static float[] resize(float[] source,int width,int height,int w,int h) {
        if(source.length!=width*height || Math.min(Math.min(width,height),Math.min(w,h))<1)
            throw new IllegalArgumentException("Invalid shape");
        if(w==width && h==height)return source.clone();
        float[] out=new float[w*h];double sx=w>1?(width-1)/(double)(w-1):0,sy=h>1?(height-1)/(double)(h-1):0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double xx=x*sx,yy=y*sy;int x0=(int)xx,y0=(int)yy,x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);
            double fx=xx-x0,fy=yy-y0,value=0;
            value+=source[y0*width+x0]*(1-fy)*(1-fx);value+=source[y0*width+x1]*(1-fy)*fx;
            value+=source[y1*width+x0]*fy*(1-fx);value+=source[y1*width+x1]*fy*fx;
            out[y*w+x]=(float)value;
        }
        return out;
    }

    /** Full square morphology, zero outside the image and SciPy's even-footprint origin. */
    static boolean[] box(boolean[] source,int width,int height,int size,boolean dilation) {
        if(size<1 || source.length!=width*height)throw new IllegalArgumentException("Invalid shape or footprint");
        int stride=width+1;int[] integral=new int[stride*(height+1)];
        for(int y=0;y<height;y++) {int row=0;for(int x=0;x<width;x++) {
            row+=source[y*width+x]?1:0;integral[(y+1)*stride+x+1]=integral[y*stride+x+1]+row;
        }}
        boolean[] out=new boolean[source.length];int low=dilation?size/2-(size-1):-size/2;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int xa=x+low,ya=y+low,xb=xa+size,yb=ya+size;
            if(!dilation && (xa<0 || ya<0 || xb>width || yb>height))continue;
            xa=Math.max(0,Math.min(width,xa));xb=Math.max(0,Math.min(width,xb));
            ya=Math.max(0,Math.min(height,ya));yb=Math.max(0,Math.min(height,yb));
            int count=integral[yb*stride+xb]-integral[ya*stride+xb]-integral[yb*stride+xa]+integral[ya*stride+xa];
            out[y*width+x]=dilation?count>0:count==size*size;
        }
        return out;
    }
    private static int enqueue(int p,boolean[] mask,boolean[] seen,int[] queue,int tail) {
        if(mask[p] && !seen[p]) {seen[p]=true;queue[tail++]=p;}return tail;
    }
    private static double[] centre(double[][] data,int start,int end) {
        double[] x=new double[end-start],y=new double[end-start];
        for(int i=start;i<end;i++) {x[i-start]=data[i][0];y[i-start]=data[i][1];}
        return new double[]{LongitudinalReferenceRigid.median(x),LongitudinalReferenceRigid.median(y)};
    }
    private static double distance(double[] a,double[] b) {return Math.hypot(a[0]-b[0],a[1]-b[1]);}
}
