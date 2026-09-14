/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ripr.api.ImageType;
import ripr.api.MotionType;
import static org.bytedeco.opencv.global.opencv_core.*;

public final class LongitudinalFullProbe {
    public static void main(String[] args) throws Exception {
        setNumThreads(1);setUseOpenCL(false);
        Path out=Path.of(args[3]);Files.createDirectory(out);ImagePlus image=IJ.openImage(args[0]);
        if(image==null)throw new IllegalArgumentException("Cannot read input");
        try {
            long start=System.nanoTime();
            LongitudinalReferenceRegistration.Outcome result=LongitudinalReferenceRegistration.estimate(image,ImageType.from(args[1]),MotionType.from(args[2]));
            double estimationSeconds=(System.nanoTime()-start)/1e9;
            write(out.resolve("preliminary.csv"),result.preliminary);write(out.resolve("transforms.csv"),result.transforms);
            List<String> raw=new ArrayList<>();raw.add("frame,x,y,angle_pixels,confidence");
            List<Integer> weak=new ArrayList<>();
            for(int i=0;i<result.transforms.length;i++) {
                double[] t=result.rawTrajectory[i];raw.add((i+1)+","+t[0]+","+t[1]+","+t[2]+","+result.confidence[i]);
                if(result.confidence[i]<=0)weak.add(i+1);
            }
            Files.write(out.resolve("raw.csv"),raw);start=System.nanoTime();
            for(int i=0;i<result.transforms.length;i++) {
                Object pixels=image.getStack().getProcessor(i+1).getPixels();
                Files.write(out.resolve("native_"+i+".pixels"),LongitudinalReferenceWarper.bilinear(pixels,image.getWidth(),image.getHeight(),result.transforms[i]));
                float[] values=(float[])image.getStack().getProcessor(i+1).convertToFloatProcessor().getPixels();
                Files.write(out.resolve("float_"+i+".f32"),LongitudinalReferenceWarper.bilinear(values,image.getWidth(),image.getHeight(),result.transforms[i]));
            }
            double outputSeconds=(System.nanoTime()-start)/1e9;
            Files.writeString(out.resolve("summary.json"),"{\"frames\":"+result.transforms.length+",\"width\":"+image.getWidth()
                +",\"height\":"+image.getHeight()+",\"bit_depth\":"+image.getBitDepth()+",\"channels\":1,\"route\":\""+result.route
                +"\",\"bright_reference_frame\":"+(result.bright<0?"null":result.bright+1)+",\"dim_reference_frame\":"+(result.dim<0?"null":result.dim+1)
                +",\"endpoint_jump_frame\":"+(result.endpoint<0?"null":result.endpoint+1)+",\"weak_frames\":"+weak
                +",\"persistent_jump_frames\":"+oneBased(result.persistentJumps)+",\"rigid_jump_frames\":"+oneBased(result.rigidJumps)
                +",\"estimation_seconds\":"+estimationSeconds+",\"preliminary_seconds\":"+result.preliminarySeconds
                +",\"refinement_seconds\":"+result.refinementSeconds+",\"diagnostic_output_seconds\":"+outputSeconds+"}");
        } finally {image.close();}
    }
    private static String oneBased(int[] input) {int[] out=input.clone();for(int i=0;i<out.length;i++)out[i]++;return Arrays.toString(out);}
    private static void write(Path file,Transform[] transforms) throws Exception {
        List<String> rows=new ArrayList<>();rows.add("x,y,theta");
        for(Transform t:transforms)rows.add(t.dx+","+t.dy+","+t.theta);Files.write(file,rows);
    }
}
