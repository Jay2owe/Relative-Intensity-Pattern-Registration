/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Whole-emission-stage probe on a real single-channel stack and frozen preliminary trajectory. */
public final class OpenCvLongitudinalTrajectoryProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("source.tif preliminary.csv output-directory");
        Path output = Path.of(args[2]); Files.createDirectory(output);
        setNumThreads(1); setUseOpenCL(false);
        ImagePlus image = IJ.openImage(args[0]);
        if (image == null || image.getNChannels() != 1) throw new IllegalArgumentException("One channel required");
        try {
            List<String> rows = Files.readAllLines(Path.of(args[1]));
            int count = image.getStackSize();
            if (rows.size() != count+1) throw new IllegalArgumentException("Preliminary frame count differs");
            float[][] frames = new float[count][]; double[][] preliminary = new double[count][3];
            for (int i = 0; i < count; i++) {
                frames[i] = (float[]) image.getStack().getProcessor(i+1).convertToFloatProcessor().getPixels();
                String[] values = rows.get(i+1).split(",");
                for (int j = 0; j < 3; j++) preliminary[i][j] = Double.parseDouble(values[j]);
            }
            long started = System.nanoTime();
            OpenCvLongitudinalTrajectory.Outcome result = OpenCvLongitudinalTrajectory.estimate(
                    frames, image.getWidth(), image.getHeight(), preliminary);
            double seconds = (System.nanoTime()-started)/1e9;
            List<String> trajectory = new ArrayList<>();
            trajectory.add("x,y,angle_radius,confidence");
            for (int i = 0; i < count; i++) trajectory.add(result.trajectory[i][0]+","+result.trajectory[i][1]
                    +","+result.trajectory[i][2]+","+result.confidence[i]);
            Files.write(output.resolve("trajectory.csv"), trajectory);
            Files.write(output.resolve("pairs.csv"), result.pairs);
            Files.writeString(output.resolve("summary.json"), "{\"frames\":"+count+",\"bright\":"+(result.bright+1)
                    +",\"dim\":"+(result.dim+1)+",\"seconds\":"+seconds+",\"channels\":1}");
        } finally { image.close(); }
    }
}
