/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ripr.api.*;
import ripr.core.*;

/** Run the real Java Automatic preliminary recipe, preserving each declared image and motion type. */
public final class JavaPreliminaryTrajectoryProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("input.tif image-type motion-type output-directory");
        Path out=Path.of(args[3]);Files.createDirectory(out);
        ImagePlus image=IJ.openImage(args[0]);
        if(image==null || image.getNChannels()!=1)throw new IllegalArgumentException("One channel required");
        try {
            RelativeIntensityPatternParameters parameters=RelativeIntensityPatternParameters.builder()
                    .recommendation(ImageType.from(args[1]),MotionType.from(args[2]))
                    .selectionMode(SelectionMode.AUTOMATIC).threads(1).crop(false).build();
            long started=System.nanoTime();
            Registration.Result result=RelativeIntensityPatternRegistration.estimate(
                    image,parameters,PairScheduler.Progress.NONE,PairScheduler.Cancellation.NEVER);
            double seconds=(System.nanoTime()-started)/1e9;
            List<String> rows=new ArrayList<>();rows.add("x,y,theta");
            for(Transform value:result.cumulative)rows.add(value.dx+","+value.dy+","+value.theta);
            Files.write(out.resolve("preliminary.csv"),rows);
            Files.writeString(out.resolve("summary.json"),"{\"frames\":"+result.cumulative.length
                    +",\"seconds\":"+seconds+",\"channels\":1,\"image_type\":\""+args[1]+"\",\"motion\":\""+args[2]+"\"}");
        } finally {image.close();}
    }
}
