/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ripr.api.ImageType;
import ripr.api.MotionType;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Same-input repeated timing. Initial loading and diagnostic saving are outside the timer. */
public final class LongitudinalTimingProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("input image-type motion output repeats");
        setNumThreads(1);setUseOpenCL(false);Path out=Path.of(args[3]);Files.createDirectory(out);
        int repeats=Integer.parseInt(args[4]);if(repeats<1)throw new IllegalArgumentException("Positive repeat count required");
        ImagePlus image=IJ.openImage(args[0]);if(image==null)throw new IllegalArgumentException("Cannot read input");
        List<String> timing=new ArrayList<>();timing.add("trial,phase,seconds,preliminary_seconds,refinement_seconds");
        try {
            for(int trial=0;trial<repeats;trial++) {
                long start=System.nanoTime();
                LongitudinalReferenceRegistration.Outcome result=LongitudinalReferenceRegistration.estimate(image,ImageType.from(args[1]),MotionType.from(args[2]));
                double seconds=(System.nanoTime()-start)/1e9;
                timing.add(trial+","+(trial==0?"cold":"warm")+","+seconds+","+result.preliminarySeconds+","+result.refinementSeconds);
                List<String> movements=new ArrayList<>();movements.add("x,y,theta");
                for(Transform t:result.transforms)movements.add(t.dx+","+t.dy+","+t.theta);
                Files.write(out.resolve("trial_"+trial+".csv"),movements);Files.write(out.resolve("timings.csv"),timing);
            }
        } finally {image.close();}
    }
}
