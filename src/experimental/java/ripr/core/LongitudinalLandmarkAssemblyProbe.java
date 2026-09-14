/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Assemble all saved pair outputs, preserving exact frame coverage and producer provenance. */
public final class LongitudinalLandmarkAssemblyProbe {
    public static void main(String[] args) throws Exception {
        Path featuresPath=Path.of(args[0]),pairsPath=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectory(out);
        int width=Integer.parseInt(args[3]),height=Integer.parseInt(args[4]),count=Integer.parseInt(args[5]);
        List<String> pairs=Files.readAllLines(pairsPath);
        if(pairs.size()!=count+1)throw new IllegalArgumentException("Incomplete pairs");
        float[][] features=new float[count][];Transform[] fits=new Transform[count];boolean[] usable=new boolean[count];
        for(int i=0;i<count;i++) {
            String[] row=pairs.get(i+1).split(",");
            if(Integer.parseInt(row[0])!=i)throw new IllegalArgumentException("Frame order differs");
            fits[i]=new Transform(Double.parseDouble(row[1]),Double.parseDouble(row[2]),Double.parseDouble(row[3]));
            usable[i]=PairAligner.Status.valueOf(row[4])!=PairAligner.Status.REFUSED_LOW_OVERLAP;
            features[i]=LongitudinalAreaProbe.read(featuresPath.resolve("feature_"+i+".f32"));
        }
        float[] reference=LongitudinalAreaProbe.read(featuresPath.resolve("reference.f32"));
        LongitudinalReferenceLandmarkTrajectory.Outcome assembled=LongitudinalReferenceLandmarkTrajectory.assemble(
            features,reference,fits,usable,width,height);
        List<String> raw=new ArrayList<>();raw.add("x,y,angle_radius,confidence,score");
        for(int i=0;i<count;i++)raw.add(assembled.trajectory[i][0]+","+assembled.trajectory[i][1]+",0.0,"
            +assembled.confidence[i]+","+assembled.scores[i]);
        Files.write(out.resolve("trajectory.csv"),raw);
        LongitudinalReferenceTrajectoryRepair.Outcome repaired=LongitudinalReferenceTrajectoryRepair.repair(
            assembled.trajectory,assembled.confidence,Math.hypot(width,height));
        List<String> finalRows=new ArrayList<>();finalRows.add("x,y,theta");
        for(double[] row:repaired.trajectory)finalRows.add(row[0]+","+row[1]+",0.0");
        Files.write(out.resolve("repaired.csv"),finalRows);
        Files.writeString(out.resolve("jumps.json"),Arrays.toString(repaired.jumps));
    }
}
