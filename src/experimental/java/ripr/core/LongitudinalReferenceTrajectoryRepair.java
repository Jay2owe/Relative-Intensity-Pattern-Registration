/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reference-order pulse/jump guard. No image access and no parameters fitted per video. */
final class LongitudinalReferenceTrajectoryRepair {
    static final class Outcome {
        final double[][] trajectory; final int[] jumps;
        Outcome(double[][] trajectory, int[] jumps) { this.trajectory=trajectory; this.jumps=jumps; }
    }
    static Outcome repair(double[][] values, double[] confidence, double diagonal) {
        if(values.length==0 || confidence.length!=values.length) throw new IllegalArgumentException("Missing trajectory");
        boolean[] reliable=new boolean[confidence.length];
        for(int i=0;i<reliable.length;i++) reliable[i]=confidence[i]>0;
        int[] jumps=persistentJumps(values,reliable,diagonal);
        double[][] result=copy(values);
        int start=0;
        for(int part=0;part<=jumps.length;part++) {
            int end=part<jumps.length?jumps[part]:values.length;
            double[][] segment=fillWeak(Arrays.copyOfRange(values,start,end),Arrays.copyOfRange(reliable,start,end));
            segment=removeReturning(segment,diagonal);
            for(int i=0;i<segment.length;i++) result[start+i]=segment[i];
            start=end;
        }
        double[] zero=result[0].clone();
        for(double[] row:result) for(int axis=0;axis<3;axis++) row[axis]-=zero[axis];
        return new Outcome(result,jumps);
    }
    private static int[] persistentJumps(double[][] values,boolean[] reliable,double diagonal) {
        double threshold=Math.max(2.5,.006*diagonal),gentle=Math.max(1.5,.004*diagonal);
        List<double[]> candidates=new ArrayList<>();int count=values.length;
        for(int boundary=1;boundary<count;boundary++) {
            double[][] left=Arrays.copyOfRange(values,Math.max(0,boundary-6),boundary);
            double[][] right=Arrays.copyOfRange(values,boundary,Math.min(count,boundary+6));
            double[] lc=median(left),rc=median(right);
            double displacement=distance(rc,lc),instant=distance(values[boundary],values[boundary-1]);
            if(displacement<threshold || instant<.55*threshold) continue;
            if(boundary!=count-1) {
                double[][] near=Arrays.copyOfRange(values,boundary,Math.min(count,boundary+3));
                double[][] far=Arrays.copyOfRange(values,Math.min(count,boundary+3),Math.min(count,boundary+8));
                if(far.length>=2 && distance(median(near),median(far))>Math.max(gentle,.30*displacement)) continue;
            }
            if(scatter(left,lc)>Math.max(gentle,.35*displacement)) continue;
            if(right.length>1 && scatter(right,rc)>Math.max(gentle,.35*displacement)) continue;
            if(!any(reliable,Math.max(0,boundary-3),boundary) || !any(reliable,boundary,Math.min(count,boundary+3))) continue;
            candidates.add(new double[]{displacement+instant,boundary});
        }
        // Python sorts the (score,boundary) tuple in reverse, including ties.
        candidates.sort((a,b)->{int order=Double.compare(b[0],a[0]);return order!=0?order:Double.compare(b[1],a[1]);});
        List<Integer> selected=new ArrayList<>();
        for(double[] candidate:candidates) {
            int boundary=(int)candidate[1]; boolean separated=true;
            for(int previous:selected) if(Math.abs(boundary-previous)<=2) separated=false;
            if(separated) selected.add(boundary);
        }
        return selected.stream().mapToInt(Integer::intValue).sorted().toArray();
    }
    private static double[][] fillWeak(double[][] values,boolean[] reliable) {
        double[][] result=copy(values);
        List<Integer> anchors=new ArrayList<>();
        for(int i=0;i<reliable.length;i++) if(reliable[i]) anchors.add(i);
        if(anchors.isEmpty()) {
            double[] centre=median(values); for(int i=0;i<result.length;i++) result[i]=centre.clone(); return result;
        }
        for(int i=0;i<result.length;i++) if(!reliable[i]) {
            int before=-1,after=-1;
            for(int anchor:anchors) {if(anchor<i)before=anchor;else if(anchor>i){after=anchor;break;}}
            if(before>=0 && after>=0) {
                double f=(i-before)/(double)(after-before);
                for(int axis=0;axis<3;axis++) result[i][axis]=(1-f)*result[before][axis]+f*result[after][axis];
            } else result[i]=result[before>=0?before:after].clone();
        }
        return result;
    }
    private static double[][] removeReturning(double[][] values,double diagonal) {
        double[][] result=copy(values);double threshold=Math.max(2.5,.006*diagonal);
        for(int pass=0;pass<3;pass++) {
            boolean changed=false;
            for(int span=2;span<Math.min(13,result.length);span++) for(int start=0;start+span<result.length;start++) {
                int end=start+span;double[][] line=new double[span+1][3];
                // np.linspace computes step=(end-start)/span, then start+i*step.
                for(int axis=0;axis<3;axis++) {
                    double step=(result[end][axis]-result[start][axis])/span;
                    for(int i=0;i<span;i++) line[i][axis]=result[start][axis]+i*step;
                    line[span][axis]=result[end][axis];
                }
                double maximum=0;double[] lengths=new double[span];
                for(int i=0;i<=span;i++) {
                    maximum=Math.max(maximum,distance(result[start+i],line[i]));
                    if(i>0)lengths[i-1]=distance(result[start+i],result[start+i-1]);
                }
                double path=sum(lengths),endpoint=distance(result[end],result[start]);
                if(maximum>threshold && path-endpoint>2*threshold) {
                    for(int i=1;i<span;i++) result[start+i]=line[i]; changed=true;
                }
            }
            if(!changed)break;
        }
        return result;
    }
    private static double sum(double[] values) {
        if(values.length<8){double sum=-0.;for(double value:values)sum+=value;return sum;}
        double sum=((values[0]+values[1])+(values[2]+values[3]))+((values[4]+values[5])+(values[6]+values[7]));
        for(int i=8;i<values.length;i++)sum+=values[i];return sum;
    }
    private static double[] median(double[][] values) {
        double[] result=new double[3];
        for(int axis=0;axis<3;axis++) {
            double[] data=new double[values.length];for(int i=0;i<data.length;i++)data[i]=values[i][axis];
            result[axis]=median(data);
        }
        return result;
    }
    private static double median(double[] values) {
        double[] sorted=values.clone();Arrays.sort(sorted);int n=sorted.length;
        return n%2==1?sorted[n/2]:(sorted[n/2-1]+sorted[n/2])/2;
    }
    private static double scatter(double[][] values,double[] centre) {
        double[] distances=new double[values.length];for(int i=0;i<values.length;i++)distances[i]=distance(values[i],centre);
        return median(distances);
    }
    private static double distance(double[] a,double[] b) {
        double x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2];return Math.sqrt((x*x+y*y)+z*z);
    }
    private static boolean any(boolean[] values,int start,int end) {for(int i=start;i<end;i++)if(values[i])return true;return false;}
    private static double[][] copy(double[][] values) {
        double[][] result=new double[values.length][];for(int i=0;i<values.length;i++)result[i]=values[i].clone();return result;
    }
}
