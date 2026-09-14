/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Same-channel rare-jump verification, preserving the frozen search and acceptance order. */
final class LongitudinalReferenceRigid {
    private LongitudinalReferenceRigid() { }
    static final class Fit {
        final Transform transform,coarse;final double peak;
        Fit(Transform transform,double peak) {this(transform,peak,transform);}
        Fit(Transform transform,double peak,Transform coarse) {this.transform=transform;this.peak=peak;this.coarse=coarse;}
    }
    static final class Event {
        final int boundary;final Transform transform;
        Event(int boundary,Transform transform) {this.boundary=boundary;this.transform=transform;}
    }

    static Fit coarse(float[] left,float[] right,int width,int height,double maxDegrees) {
        LongitudinalReferencePhase.Plane a=LongitudinalReferencePhase.thumbnail(left,width,height,192);
        LongitudinalReferencePhase.Plane b=LongitudinalReferencePhase.thumbnail(right,width,height,192);
        double scaleX=a.width/(double)width,scaleY=a.height/(double)height;
        double maximum=Math.max(15,maxDegrees);
        double step=Math.max(.25,Math.toDegrees(.75/Math.max(1,.5*Math.hypot(a.width,a.height))));
        int count=Math.max(1,(int)Math.ceil(maximum/step));
        double increment=2*maximum/(2*count),best=Double.NEGATIVE_INFINITY;Transform winner=Transform.IDENTITY;
        for(int i=0;i<=2*count;i++) {
            double degrees=i==2*count?maximum:-maximum+i*increment,theta=Math.toRadians(degrees);
            float[] rotated=LongitudinalReferenceLandmarks.warp(b.values,b.width,b.height,new Transform(0,0,theta));
            double[] phase=LongitudinalReferencePhase.estimate(a.values,rotated,a.width,a.height);
            double cosine=Math.cos(theta),sine=Math.sin(theta);
            double dx=cosine*phase[0]/scaleX-sine*phase[1]/scaleY;
            double dy=sine*phase[0]/scaleX+cosine*phase[1]/scaleY;
            if(phase[2]>best) {best=phase[2];winner=new Transform(dx,dy,theta);}
        }
        return new Fit(winner,best);
    }

    static Fit fit(float[] before,float[] after,int width,int height,double maxDegrees) {
        float[] left=LongitudinalReferenceFeatures.landmark(before,width,height,LongitudinalStripedGaussian::filter);
        float[] right=LongitudinalReferenceFeatures.landmark(after,width,height,LongitudinalStripedGaussian::filter);
        return fitFeatures(left,right,width,height,maxDegrees);
    }

    static Fit fitFeatures(float[] left,float[] right,int width,int height,double maxDegrees) {
        Fit seed=coarse(left,right,width,height,maxDegrees);
        Transform accepted=seed.transform;
        double score=LongitudinalReferenceLandmarkTrajectory.correlation(left,right,accepted,width,height);
        if(!Double.isFinite(score))throw new IllegalArgumentException("Insufficient correlation support");
        double radius=Math.max(1,.5*Math.hypot(width,height));
        for(double step:new double[]{2,1,.5,.25,.125})for(int repeat=0;repeat<4;repeat++) {
            Transform winner=accepted;double winnerScore=score;
            for(int angle=-1;angle<=1;angle++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                if(angle==0 && y==0 && x==0)continue;
                Transform candidate=new Transform(accepted.dx+x*step,accepted.dy+y*step,accepted.theta+angle*step/radius);
                double candidateScore=LongitudinalReferenceLandmarkTrajectory.correlation(left,right,candidate,width,height);
                if(candidateScore>winnerScore) {winner=candidate;winnerScore=candidateScore;}
            }
            if(winner==accepted)break;
            accepted=winner;score=winnerScore;
        }
        return new Fit(accepted,seed.peak,seed.transform);
    }

    static int[] boundaries(float[][] features,int width,int height) {
        if(features.length<2)return new int[0];
        LongitudinalReferencePhase.Plane previous=LongitudinalReferencePhase.thumbnail(features[0],width,height,128);
        double[] responses=new double[features.length-1];
        for(int i=1;i<features.length;i++) {
            LongitudinalReferencePhase.Plane next=LongitudinalReferencePhase.thumbnail(features[i],width,height,128);
            responses[i-1]=LongitudinalReferencePhase.estimate(previous.values,next.values,next.width,next.height)[2];previous=next;
        }
        double cutoff=Math.min(.20,.35*median(responses));int n=0;int[] output=new int[responses.length];
        for(int i=0;i<responses.length;i++)if(responses[i]<cutoff)output[n++]=i+1;
        return Arrays.copyOf(output,n);
    }

    static List<Event> transmitted(float[][] frames,float[][] features,int width,int height,double maxDegrees) {
        List<Event> events=new ArrayList<>();double diagonal=Math.hypot(width,height),radius=.5*diagonal;
        for(int boundary:boundaries(features,width,height)) {
            int size=Math.min(8,Math.min(boundary,frames.length-boundary));if(size<3)continue;
            Fit pair,block;
            try {
                pair=fit(frames[boundary-1],frames[boundary],width,height,maxDegrees);
                block=fit(medianBlock(frames,boundary-size,boundary),medianBlock(frames,boundary,boundary+size),width,height,maxDegrees);
            } catch(IllegalArgumentException failure) {continue;}
            Transform a=pair.transform,b=block.transform;
            if(Math.hypot(a.dx-b.dx,a.dy-b.dy)>.01*diagonal || Math.abs(a.theta-b.theta)>Math.toRadians(1))continue;
            if(Math.max(b.magnitude(),radius*Math.abs(b.theta))<.005*diagonal)continue;
            events.add(new Event(boundary,b));
        }
        return events;
    }

    static List<Event> terminal(float[][] frames,Transform[] baseline,int width,int height,double maxDegrees) {
        List<Event> none=new ArrayList<>();int count=frames.length;
        if(count<6 || baseline.length!=count)return none;
        double diagonal=Math.hypot(width,height),radius=.5*diagonal;
        Transform[] increments=new Transform[count];double[] movement=new double[count],values=new double[count-1];
        for(int i=1;i<count;i++) {
            increments[i]=baseline[i-1].inverse().then(baseline[i]);
            values[i-1]=movement[i]=Math.max(increments[i].magnitude(),radius*Math.abs(increments[i].theta));
        }
        double limit=percentile(values,80);double[] ordinaryValues=new double[values.length];int n=0;
        for(double v:values)if(v<=limit)ordinaryValues[n++]=v;
        double ordinary=n>0?median(Arrays.copyOf(ordinaryValues,n)):0;
        double threshold=Math.max(.04*diagonal,8*Math.max(ordinary,1));
        int tail=Math.max(4,Math.min(8,(int)Math.ceil(.04*count)));List<Integer> large=new ArrayList<>();
        for(int i=Math.max(1,count-tail);i<count-1;i++)if(movement[i]>=threshold)large.add(i);
        if(large.isEmpty())return none;
        List<Integer> group=new ArrayList<>();group.add(large.get(large.size()-1));
        for(int i=large.size()-2;i>=0;i--) {if(group.get(0)-large.get(i)!=1)break;group.add(0,large.get(i));}
        int first=group.get(0),last=group.get(group.size()-1);
        if(group.size()>3 || count-last<2)return none;
        Transform persistent=baseline[first-1].inverse().then(baseline[count-1]);
        if(Math.max(persistent.magnitude(),radius*Math.abs(persistent.theta))<threshold)return none;
        List<Event> events=new ArrayList<>();
        for(int i=first;i<count;i++) {
            Fit measured;try {measured=fit(frames[i-1],frames[i],width,height,maxDegrees);}catch(IllegalArgumentException failure) {return none;}
            if(measured.peak<.15)return none;Transform event=measured.transform;
            double size=Math.max(event.magnitude(),radius*Math.abs(event.theta));
            if(group.contains(i)) {
                Transform expected=increments[i];double disagreement=Math.hypot(event.dx-expected.dx,event.dy-expected.dy);
                double dot=event.dx*expected.dx+event.dy*expected.dy,lengths=Math.max(event.magnitude()*expected.magnitude(),1e-9);
                if(size<.70*threshold || disagreement>.04*diagonal || dot/lengths<.75)return none;
            } else if(size>=threshold)return none;
            events.add(new Event(i,event));
        }
        return events;
    }

    static Transform[] apply(Transform[] transforms,List<Event> events) {
        Transform[] result=transforms.clone();
        for(Event event:events) {
            Transform predicted=result[event.boundary-1].inverse().then(result[event.boundary]);
            Transform correction=predicted.inverse().then(event.transform);
            for(int i=event.boundary;i<result.length;i++)result[i]=result[i].then(correction);
        }
        return result;
    }

    static float[] medianBlock(float[][] frames,int start,int end) {
        int n=end-start;float[] out=new float[frames[0].length],scratch=new float[n];
        for(int p=0;p<out.length;p++) {
            boolean nan=false;for(int i=0;i<n;i++) {scratch[i]=frames[start+i][p];nan|=Float.isNaN(scratch[i]);}
            if(nan) {out[p]=Float.NaN;continue;}
            Arrays.sort(scratch);out[p]=n%2==1?scratch[n/2]:(scratch[n/2-1]+scratch[n/2])/2f;
        }
        return out;
    }
    static double median(double[] source) {double[] values=source.clone();Arrays.sort(values);int n=values.length;return n%2==1?values[n/2]:(values[n/2-1]+values[n/2])/2;}
    static double percentile(double[] source,double percent) {
        double[] values=source.clone();Arrays.sort(values);double p=percent/100*(values.length-1);
        int lo=(int)p,hi=Math.min(values.length-1,lo+1);double f=p-lo,d=values[hi]-values[lo];
        return f>=.5?values[hi]-d*(1-f):values[lo]+d*f;
    }
}
