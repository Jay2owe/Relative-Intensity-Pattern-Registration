/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LongitudinalTrajectoryRepairProbe {
    public static void main(String[] args) throws Exception {
        List<String> input=Files.readAllLines(Path.of(args[0]));
        double[][] values=new double[input.size()-1][3];double[] confidence=new double[values.length];
        for(int i=0;i<values.length;i++) {
            String[] row=input.get(i+1).split(",");
            for(int j=0;j<3;j++)values[i][j]=Double.parseDouble(row[j]);
            confidence[i]=Double.parseDouble(row[3]);
        }
        LongitudinalReferenceTrajectoryRepair.Outcome result=LongitudinalReferenceTrajectoryRepair.repair(
                values,confidence,Double.parseDouble(args[1]));
        Path output=Path.of(args[2]);Files.createDirectory(output);
        List<String> rows=new ArrayList<>();rows.add("x,y,angle_radius");
        for(double[] row:result.trajectory)rows.add(row[0]+","+row[1]+","+row[2]);
        Files.write(output.resolve("repaired.csv"),rows);
        Files.writeString(output.resolve("jumps.json"),Arrays.toString(result.jumps));
    }
}
