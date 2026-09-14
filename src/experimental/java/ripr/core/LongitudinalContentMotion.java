/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.*;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/** Classification evidence only. Never modifies input pixels or registration transforms. */
public final class LongitudinalContentMotion {
    private LongitudinalContentMotion() { }
    public static final String[] NAMES={"local_shift_disagreement","local_coherent_share","local_supported_share","aligned_temporal_change"};
    public static double[] measure(float[][] frames,int w,int h){
        List<Double> disagreement=new ArrayList<>(),coherent=new ArrayList<>(),supported=new ArrayList<>(),changes=new ArrayList<>();
        for(int t=1;t<frames.length;t++){
            float[] a=frames[t-1],b=frames[t];double[] global=phase(a,b,w,h);
            if(!Double.isFinite(global[0])||!Double.isFinite(global[1])||global[2]<.10||Math.abs(global[0])>w*.25||Math.abs(global[1])>h*.25)continue;
            int side=Math.min(w,h)/3,dx=(int)Math.rint(global[0]),dy=(int)Math.rint(global[1]);int valid=0,agree=0;
            List<Double> pairDisagreement=new ArrayList<>();
            for(int gy=0;gy<3;gy++)for(int gx=0;gx<3;gx++){
                int x=gx*(w-side)/2,y=gy*(h-side)/2;
                if(x+dx<0||y+dy<0||x+dx+side>w||y+dy+side>h)continue;
                float[] pa=patch(a,w,x,y,side),pb=patch(b,w,x+dx,y+dy,side);
                double[] local=phase(pa,pb,side,side);
                if(!Double.isFinite(local[0])||!Double.isFinite(local[1])||local[2]<.20||Math.abs(local[0])>side*.25||Math.abs(local[1])>side*.25)continue;
                double difference=Math.hypot(local[0]-(global[0]-dx),local[1]-(global[1]-dy));
                pairDisagreement.add(difference/Math.hypot(w,h));valid++;
                if(difference<=Math.max(.75,Math.min(w,h)*.0075))agree++;
                changes.add(1-correlation(pa,pb));
            }
            supported.add(valid/9.0);
            if(valid>=3){disagreement.add(median(pairDisagreement));coherent.add(agree/(double)valid);}
        }
        return new double[]{median(disagreement),median(coherent),median(supported),median(changes)};
    }
    /** Fine/broad spatial contrast, separate from temporal biological-motion evidence. */
    public static double[] spatial(float[] input,int w,int h){
        float[] a=normalize(input),blur=LongitudinalReferenceFeatures.gaussianReflect101(a,w,h,Math.min(w,h)/24.0);
        double detail=0,broad=0,positive=0,negative=0;
        for(int i=0;i<a.length;i++){double d=a[i]-blur[i];detail+=d*d;broad+=blur[i]*blur[i];if(d>0)positive+=d*d;else negative+=d*d;}
        return new double[]{detail/Math.max(1e-12,detail+broad),positive/Math.max(1e-12,positive+negative)};
    }
    static float[] patch(float[] a,int w,int x,int y,int side){float[] p=new float[side*side];for(int r=0;r<side;r++)System.arraycopy(a,(y+r)*w+x,p,r*side,side);return p;}
    static double[] phase(float[] a,float[] b,int w,int h){
        float[] x=normalize(a),y=normalize(b);double[] response=new double[1];
        try(FloatPointer pa=new FloatPointer(x);FloatPointer pb=new FloatPointer(y);
            Mat ma=new Mat(h,w,CV_32FC1,pa);Mat mb=new Mat(h,w,CV_32FC1,pb);Mat window=new Mat();Size size=new Size(w,h)){
            createHanningWindow(window,size,CV_32FC1);
            try(Point2d shift=phaseCorrelate(ma,mb,window,response)){return new double[]{shift.x(),shift.y(),response[0]};}
        }
    }
    static float[] normalize(float[] a){
        double mean=0;int n=0;for(float v:a)if(Float.isFinite(v)){mean+=v;n++;}mean/=Math.max(1,n);
        double variance=0;for(float v:a)if(Float.isFinite(v))variance+=(v-mean)*(v-mean);
        double sd=Math.sqrt(variance/Math.max(1,n));float[] b=new float[a.length];
        if(sd>1e-12)for(int i=0;i<b.length;i++)if(Float.isFinite(a[i]))b[i]=(float)((a[i]-mean)/sd);
        return b;
    }
    static double correlation(float[] a,float[] b){float[] x=normalize(a),y=normalize(b);double dot=0,xx=0,yy=0;for(int i=0;i<x.length;i++){dot+=x[i]*y[i];xx+=x[i]*x[i];yy+=y[i]*y[i];}return dot/Math.max(1e-12,Math.sqrt(xx*yy));}
    public static double median(Collection<Double> values){double[] a=values.stream().filter(Double::isFinite).mapToDouble(Double::doubleValue).sorted().toArray();return a.length==0?Double.NaN:(a[(a.length-1)/2]+a[a.length/2])/2;}
}
