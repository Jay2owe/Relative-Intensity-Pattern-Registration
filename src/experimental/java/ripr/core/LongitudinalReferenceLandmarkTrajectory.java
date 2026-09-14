/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.Arrays;

/** Reference-order fixed-template assembly and confidence; rigid-jump checks are separate. */
final class LongitudinalReferenceLandmarkTrajectory {
    private LongitudinalReferenceLandmarkTrajectory() { }

    static final class Outcome {
        final double[][] trajectory;final double[] confidence,scores;
        Outcome(double[][] t,double[] c,double[] s) {trajectory=t;confidence=c;scores=s;}
    }

    static Transform[] repairUnsupported(Transform[] input,boolean[] support) {
        if(input.length!=support.length || !support[0])throw new IllegalArgumentException("Invalid reference support");
        Transform[] output=input.clone();
        for(int i=0;i<input.length;i++)if(!support[i]) {
            int before=i-1,after=i+1;
            while(before>=0 && !support[before])before--;
            while(after<input.length && !support[after])after++;
            if(before<0)output[i]=input[after];
            else if(after==input.length)output[i]=input[before];
            else {
                double fraction=(i-before)/(double)(after-before);Transform a=input[before],b=input[after];
                double delta=Math.atan2(Math.sin(b.theta-a.theta),Math.cos(b.theta-a.theta));
                output[i]=new Transform(a.dx+fraction*(b.dx-a.dx),a.dy+fraction*(b.dy-a.dy),a.theta+fraction*delta);
            }
        }
        return output;
    }

    static Outcome assemble(float[][] features,float[] reference,Transform[] fits,boolean[] usable,int width,int height) {
        int count=features.length;
        if(fits.length!=count || usable.length!=count)throw new IllegalArgumentException("Frame count differs");
        Transform[] cumulative=new Transform[count+1];boolean[] support=new boolean[count+1];
        cumulative[0]=Transform.IDENTITY;support[0]=true;
        for(int i=0;i<count;i++) {cumulative[i+1]=usable[i]?fits[i]:Transform.IDENTITY;support[i+1]=usable[i];}
        cumulative=repairUnsupported(cumulative,support);Transform anchor=cumulative[1].inverse();
        double[][] trajectory=new double[count][3];double[] scores=new double[count];
        for(int i=0;i<count;i++) {
            Transform value=anchor.then(cumulative[i+1]);
            trajectory[i][0]=value.dx;trajectory[i][1]=value.dy;
            scores[i]=correlation(reference,features[i],value,width,height);
        }
        double median=median(scores),mad;double[] deviations=new double[count];
        for(int i=0;i<count;i++)deviations[i]=Math.abs(scores[i]-median);
        mad=1.4826*median(deviations);
        double floor=Math.max(.15,Math.min(.45,median-3*mad));double[] confidence=new double[count];
        for(int i=0;i<count;i++)confidence[i]=Math.max(0,Math.min(1,(scores[i]-floor)/Math.max(median-floor,.05)));
        return new Outcome(trajectory,confidence,scores);
    }

    static double correlation(float[] reference,float[] moving,Transform transform,int width,int height) {
        float[] aligned=LongitudinalReferenceLandmarks.warp(moving,width,height,transform);
        int count=0;double sumA=0,sumB=0;
        for(int i=0;i<aligned.length;i++)if(Float.isFinite(reference[i]) && Float.isFinite(aligned[i])) {
            count++;sumA+=reference[i];sumB+=aligned[i];
        }
        if(count<64)return 0;
        double meanA=sumA/count,meanB=sumB/count,aa=0,bb=0,ab=0;
        for(int i=0;i<aligned.length;i++)if(Float.isFinite(reference[i]) && Float.isFinite(aligned[i])) {
            double a=reference[i]-meanA,b=aligned[i]-meanB;aa+=a*a;bb+=b*b;ab+=a*b;
        }
        double denominator=Math.sqrt(aa)*Math.sqrt(bb);return denominator>0?ab/denominator:0;
    }

    private static double median(double[] source) {
        double[] values=source.clone();Arrays.sort(values);int n=values.length;
        return n%2==1?values[n/2]:(values[n/2-1]+values[n/2])/2;
    }
}
