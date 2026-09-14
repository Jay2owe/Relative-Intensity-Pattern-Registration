/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import ripr.api.ImageType;
import ripr.api.MotionType;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Same probe runs against the frozen binary or optional candidate switches. No Python execution. */
public final class LongitudinalSpeedProbe {
    private static Object policy(int variant) throws Exception {
        Class<?> type=Class.forName("ripr.core.LongitudinalExecutionPolicy");
        if(variant>=8) {
            int workers=(variant&4096)!=0?16:(variant&2048)!=0?12:(variant&512)!=0?8:(variant&64)!=0?6:(variant&128)!=0?2:4;
            if((variant&1024)!=0) return type.getConstructor(int.class,boolean.class,boolean.class,int.class,int.class,int.class,boolean.class,boolean.class)
                .newInstance((variant&1)!=0?workers:1,(variant&2)!=0,(variant&4)!=0,
                    (variant&8)!=0?workers:1,(variant&16)!=0?workers:1,(variant&32)!=0?workers:1,(variant&256)!=0,true);
            if((variant&256)!=0) return type.getConstructor(int.class,boolean.class,boolean.class,int.class,int.class,int.class,boolean.class)
                .newInstance((variant&1)!=0?workers:1,(variant&2)!=0,(variant&4)!=0,
                    (variant&8)!=0?workers:1,(variant&16)!=0?workers:1,(variant&32)!=0?workers:1,true);
            return type.getConstructor(int.class,boolean.class,boolean.class,int.class,int.class,int.class)
                .newInstance((variant&1)!=0?workers:1,(variant&2)!=0,(variant&4)!=0,
                    (variant&8)!=0?workers:1,(variant&16)!=0?workers:1,(variant&32)!=0?workers:1);
        }
        return type.getConstructor(int.class,boolean.class,boolean.class).newInstance((variant&1)!=0?4:1,(variant&2)!=0,(variant&4)!=0);
    }
    private static LongitudinalReferenceRegistration.Outcome estimate(ImagePlus image,ImageType type,MotionType motion,int variant) throws Exception {
        if(variant<0)return LongitudinalReferenceRegistration.estimate(image,type,motion);
        Object policy=policy(variant);
        try {
            return (LongitudinalReferenceRegistration.Outcome)LongitudinalReferenceRegistration.class
                .getMethod("estimate",ImagePlus.class,ImageType.class,MotionType.class,policy.getClass()).invoke(null,image,type,motion,policy);
        } catch(InvocationTargetException failure) {throw new IllegalStateException(failure.getCause());}
    }
    public static void main(String[] args) throws Exception {
        setNumThreads(1);setUseOpenCL(false);
        Path out=Path.of(args[3]);Files.createDirectories(out);ImagePlus image=IJ.openImage(args[0]);
        if(image==null)throw new IllegalArgumentException("Cannot open input");
        try {
            int variant=Integer.parseInt(args[4]);
            if(args.length>5 && args[5].equals("pairs")) {pairs(image,out,variant);return;}
            long start=System.nanoTime();
            LongitudinalReferenceRegistration.Outcome result=estimate(image,ImageType.from(args[1]),MotionType.from(args[2]),variant);
            double seconds=(System.nanoTime()-start)/1e9;
            write(out.resolve("preliminary.csv"),result.preliminary);write(out.resolve("transforms.csv"),result.transforms);
            List<String> raw=new ArrayList<>(),hashes=new ArrayList<>();raw.add("frame,x,y,angle_pixels,confidence");
            hashes.add("name,sha256");List<Integer> weak=new ArrayList<>();
            for(int i=0;i<result.transforms.length;i++) {
                double[] t=result.rawTrajectory[i];raw.add((i+1)+","+t[0]+","+t[1]+","+t[2]+","+result.confidence[i]);
                if(result.confidence[i]<=0)weak.add(i+1);
                Object pixels=image.getStack().getProcessor(i+1).getPixels();
                hashes.add("native_"+i+".pixels,"+hash(LongitudinalReferenceWarper.bilinear(pixels,image.getWidth(),image.getHeight(),result.transforms[i])));
                float[] values=(float[])image.getStack().getProcessor(i+1).convertToFloatProcessor().getPixels();
                hashes.add("float_"+i+".f32,"+hash(LongitudinalReferenceWarper.bilinear(values,image.getWidth(),image.getHeight(),result.transforms[i])));
            }
            Files.write(out.resolve("raw.csv"),raw);Files.write(out.resolve("pixel_hashes.csv"),hashes);
            Files.writeString(out.resolve("summary.json"),"{\"frames\":"+result.transforms.length+",\"route\":\""+result.route
                +"\",\"bright_reference_frame\":"+(result.bright<0?"null":result.bright+1)+",\"dim_reference_frame\":"+(result.dim<0?"null":result.dim+1)
                +",\"endpoint_jump_frame\":"+(result.endpoint<0?"null":result.endpoint+1)+",\"weak_frames\":"+weak
                +",\"persistent_jump_frames\":"+oneBased(result.persistentJumps)+",\"rigid_jump_frames\":"+oneBased(result.rigidJumps)
                +",\"estimation_seconds\":"+seconds+",\"preliminary_seconds\":"+result.preliminarySeconds
                +",\"refinement_seconds\":"+result.refinementSeconds+"}");
        } finally {image.close();}
    }
    private static void pairs(ImagePlus image,Path out,int variant) throws Exception {
        int n=image.getStackSize(),w=image.getWidth(),h=image.getHeight();
        int[] indices={0,n/3,2*n/3,n-1};float[][] raw=new float[4][];
        for(int i=0;i<4;i++)raw[i]=(float[])image.getStack().getProcessor(indices[i]+1).convertToFloatProcessor().getPixels();
        // Pair-tier only: four full-resolution real frames, no artificial movement or accuracy claim.
        double[][] initial=new double[4][3];
        for(int repeat=0;repeat<3;repeat++) {
            long start=System.nanoTime();OpenCvLongitudinalTrajectory.Outcome result;
            if(variant<0)result=OpenCvLongitudinalTrajectory.estimate(raw,w,h,initial);
            else {
                Object policy=policy(variant);
                Method method=OpenCvLongitudinalTrajectory.class.getDeclaredMethod("estimate",float[][].class,int.class,int.class,double[][].class,policy.getClass());
                result=(OpenCvLongitudinalTrajectory.Outcome)method.invoke(null,raw,w,h,initial,policy);
            }
            double seconds=(System.nanoTime()-start)/1e9;
            List<String> values=new ArrayList<>();
            values.add("bright="+result.bright+",dim="+result.dim);
            for(int i=0;i<4;i++)values.add(Arrays.toString(result.trajectory[i])+","+result.confidence[i]);
            values.addAll(result.pairs);
            Files.write(out.resolve("pair_"+repeat+".txt"),values);
            Files.writeString(out.resolve("time_"+repeat+".json"),"{\"seconds\":"+seconds+",\"cold\":"+(repeat==0)+",\"indices\":"+Arrays.toString(indices)+"}");
        }
    }
    private static String hash(byte[] data) throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format("%02x",b&255));return s.toString();
    }
    private static String oneBased(int[] input) {int[] out=input.clone();for(int i=0;i<out.length;i++)out[i]++;return Arrays.toString(out);}
    private static void write(Path file,Transform[] transforms) throws Exception {
        List<String> rows=new ArrayList<>();rows.add("x,y,theta");
        for(Transform t:transforms)rows.add(t.dx+","+t.dy+","+t.theta);Files.write(file,rows);
    }
}
