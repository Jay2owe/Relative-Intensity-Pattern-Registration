package ripr.core;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

/** Frozen, uncached arithmetic oracles for exact-output speed changes. */
public class LongitudinalExactSpeedTest {
    @Test public void percentileNormalisationPreservesSamplingAndNonfiniteRules() throws Exception {
        Method normalise = method("percentileNormalise", float[].class, double.class, double.class);
        Random random = new Random(19001);
        for (int size : new int[]{1, 127, 249999, 250000, 499999, 500000}) {
            float[] input = new float[size];
            for (int i=0; i<size; i++) input[i] = (float)(random.nextGaussian()*40 + 150);
            for (int i=0; i<size; i+=137) input[i] = Float.NaN;
            for (int i=3; i<size; i+=191) input[i] = Float.POSITIVE_INFINITY;
            for (double[] bounds : new double[][]{{1,99},{20,99.8}}) {
                float[] expected = normaliseLegacy(input,bounds[0],bounds[1]);
                float[] actual = (float[])normalise.invoke(null,input,bounds[0],bounds[1]);
                for (int i=0; i<size; i++) assertEquals("size="+size+" pixel="+i,
                        Float.floatToRawIntBits(expected[i]),Float.floatToRawIntBits(actual[i]));
            }
        }
        for (float[] input : new float[][]{{-0f,0f,-0f,0f},{Float.NaN,Float.NEGATIVE_INFINITY}, {7,7,7}}) {
            float[] expected=normaliseLegacy(input,1,99);
            float[] actual=(float[])normalise.invoke(null,input,1.0,99.0);
            for(int i=0;i<input.length;i++) assertEquals(Float.floatToRawIntBits(expected[i]),Float.floatToRawIntBits(actual[i]));
        }
    }

    @Test public void cachedSearchPreservesUncachedWinnersAndScores() throws Exception {
        Method refine=method("refineCorrelation",float[].class,float[].class,int.class,int.class,
                Transform.class,double.class,double[].class,boolean.class,PairScheduler.Cancellation.class);
        Random random=new Random(19002);
        for(int run=0;run<12;run++) {
            int width=run%2==0?64:73,height=55;
            float[] reference=new float[width*height], moving=new float[reference.length];
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) reference[y*width+x]=
                    (float)(4*Math.sin(x*.21)+3*Math.cos(y*.31)+random.nextDouble());
            Transform truth=new Transform(1.23+run*.13,-2.17,run*.0007);
            Warper.warp(reference,moving,width,height,truth.inverse(),Warper.Interpolation.BILINEAR,Float.NaN);
            if(run%3==0) for(int i=0;i<reference.length;i+=17) reference[i]=Float.NaN;
            Transform seed=new Transform(run%2==0?0.0:-0.0,run%4*.25,0);
            double seedScore=LongitudinalRegistration.correlation(reference,moving,width,height,seed);
            for(boolean rotation:new boolean[]{false,true}) {
                double[] steps=run%2==0?new double[]{1,.25,.0625}:new double[]{2,1,.5,.25,.125};
                LongitudinalRegistration.Fit expected=legacySearch(reference,moving,width,height,seed,seedScore,steps,rotation);
                LongitudinalRegistration.Fit actual=(LongitudinalRegistration.Fit)refine.invoke(null,reference,moving,
                        width,height,seed,seedScore,steps,rotation,PairScheduler.Cancellation.NEVER);
                assertBits(expected.transform.dx,actual.transform.dx);
                assertBits(expected.transform.dy,actual.transform.dy);
                assertBits(expected.transform.theta,actual.transform.theta);
                assertBits(expected.score,actual.score);
            }
        }
    }

    private static LongitudinalRegistration.Fit legacySearch(float[] a,float[] b,int width,int height,
            Transform seed,double seedScore,double[] steps,boolean rotation) {
        double radius=Math.max(1,.5*Math.hypot(width,height));
        Transform accepted=seed;double score=seedScore;
        for(double step:steps) for(int iteration=0;iteration<4;iteration++) {
            Transform winner=accepted;double best=score;
            for(int kt=rotation?-1:0;kt<=(rotation?1:0);kt++)
                for(int ky=-1;ky<=1;ky++) for(int kx=-1;kx<=1;kx++) {
                    if(kx==0&&ky==0&&kt==0) continue;
                    Transform candidate=new Transform(accepted.dx+kx*step,accepted.dy+ky*step,accepted.theta+kt*step/radius);
                    double value=LongitudinalRegistration.correlation(a,b,width,height,candidate);
                    if(value>best){winner=candidate;best=value;}
                }
            if(winner==accepted)break;
            accepted=winner;score=best;
        }
        return new LongitudinalRegistration.Fit(accepted,score);
    }

    private static float[] normaliseLegacy(float[] input,double low,double high) {
        double lo=percentileLegacy(input,low),hi=percentileLegacy(input,high);
        double scale=1.0/Math.max(hi-lo,1e-6);
        float[] output=new float[input.length];
        for(int i=0;i<input.length;i++) output[i]=(float)Math.max(0,Math.min(1,(input[i]-lo)*scale));
        return output;
    }
    private static double percentileLegacy(float[] input,double percentile) {
        int stride=Math.max(1,input.length/250000);
        float[] finite=new float[(input.length+stride-1)/stride];int count=0;
        for(int i=0;i<input.length;i+=stride)if(Float.isFinite(input[i]))finite[count++]=input[i];
        if(count==0)return 0;
        float[] sorted=Arrays.copyOf(finite,count);Arrays.sort(sorted);
        if(sorted.length==1)return sorted[0];
        double position=Math.max(0,Math.min(100,percentile))*(sorted.length-1)/100.0;
        int lower=(int)Math.floor(position),upper=Math.min(sorted.length-1,lower+1);
        double fraction=position-lower;
        return sorted[lower]*(1-fraction)+sorted[upper]*fraction;
    }
    private static void assertBits(double expected,double actual) {
        assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(actual));
    }
    private static Method method(String name,Class<?>... types)throws Exception {
        Method result=LongitudinalRegistration.class.getDeclaredMethod(name,types);
        result.setAccessible(true);return result;
    }
}
