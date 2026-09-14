/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.bytedeco.opencv.global.opencv_core.*;

public final class LongitudinalRigidBridgeProbe {
    public static void main(String[] args) throws Exception {
        setNumThreads(1);setUseOpenCL(false);
        Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectory(out);
        int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]);
        List<String> rows=new ArrayList<>();rows.add("pair,x,y,theta,peak,seed_x,seed_y,seed_theta,seconds");
        for(String pair:new String[]{"pair","block"}) {
            long start=System.nanoTime();
            float[] left=LongitudinalReferenceFeatures.landmark(LongitudinalPhaseBoundaryProbe.read(source.resolve(pair+"_before.f32")),width,height);
            float[] right=LongitudinalReferenceFeatures.landmark(LongitudinalPhaseBoundaryProbe.read(source.resolve(pair+"_after.f32")),width,height);
            LongitudinalPhaseBoundaryProbe.write(out.resolve(pair+"_feature_0.f32"),left);
            LongitudinalPhaseBoundaryProbe.write(out.resolve(pair+"_feature_1.f32"),right);
            LongitudinalReferenceRigid.Fit fit=LongitudinalReferenceRigid.fitFeatures(left,right,width,height,10);
            Transform t=fit.transform,s=fit.coarse;
            rows.add(pair+","+t.dx+","+t.dy+","+t.theta+","+fit.peak+","+s.dx+","+s.dy+","+s.theta+","+(System.nanoTime()-start)/1e9);
        }
        Files.write(out.resolve("pairs.csv"),rows);
    }
}
