package ripr.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ripr.api.*;

public class AcceptedLongitudinalRegistrationTest {
    @Test public void acceptedArtifactsAreExact() throws Exception {
        String[] names={"A001_candidate.jar","A004_candidate.jar","frozen-engine.jar"};
        String[] hashes={"2eae079bbfe7207c848a525fb16fa9a98a321f9b810f1f14ed6d1fc94643a9b8",
                "ebfeed6c5752807209e50c99eec228864ce47bbacd3a31a81138a1280fa7a7da",
                "f539da3d43564d8df4dd8b32baf134332e019a1e725667c7ea0a275f094f76be"};
        for(int i=0;i<names.length;i++) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=AcceptedLongitudinalRegistration.class.getResourceAsStream("/ripr/longitudinal/"+names[i])) {
                assertNotNull(input);byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);
            }
            StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));
            assertEquals(hashes[i],hex.toString());
        }
    }
    @Test(expected=java.util.concurrent.CancellationException.class) public void cancellationPrecedesWorkerLaunch() {
        ImageStack stack=new ImageStack(16,16);stack.addSlice(new FloatProcessor(16,16));stack.addSlice(new FloatProcessor(16,16));
        AcceptedLongitudinalRegistration.estimate(new ImagePlus("test",stack),RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE,MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.ACCEPTED_LONGITUDINAL).build(),PairScheduler.Progress.NONE,()->true);
    }
}
