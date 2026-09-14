/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.isolated;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Test classpath contains only this probe, the packaged runtime and host ImageJ. */
public final class PackagedRuntimeProbe {
    public static void main(String[] args) throws Exception {
        boolean smoke=args.length==2 && args[0].equals("--smoke");
        if(!smoke && args.length!=4)throw new IllegalArgumentException("--smoke output OR input image-type motion output");
        Path output=Path.of(args[smoke?1:3]);Files.createDirectory(output);
        ClassLoader original=Thread.currentThread().getContextClassLoader();
        Map<String,Object> info=NativeLongitudinalRuntime.runtimeInfo();
        List<String> runtime=new ArrayList<>();for(Map.Entry<String,Object> e:info.entrySet())runtime.add(e.getKey()+"="+e.getValue());
        Files.write(output.resolve("runtime.txt"),runtime);
        if(!info.get("opencv_version").equals("4.10.0"))throw new AssertionError("Wrong native OpenCV version: "+info.get("opencv_version"));
        if(!info.get("engine_classloader").equals(info.get("native_classloader")))throw new AssertionError("Private classloaders differ");
        if(!info.get("engine_classloader").toString().contains("PrivateLoader"))throw new AssertionError("Engine is not private");
        if(!(((Number)info.get("gaussian_centre")).doubleValue()>0))throw new AssertionError("Native operation did not execute");
        NativeLongitudinalRuntime.runtimeInfo();
        if(original!=Thread.currentThread().getContextClassLoader())throw new AssertionError("Caller context loader changed");
        // Reject another channel before any registration is started.
        ImageStack stack=new ImageStack(8,8);for(int i=0;i<4;i++)stack.addSlice("",new byte[64]);
        ImagePlus multi=new ImagePlus("channel refusal guard",stack);multi.setDimensions(2,1,2);multi.setOpenAsHyperStack(true);
        try {
            NativeLongitudinalRuntime.estimate(multi,"DENSE_FLUORESCENCE","INTERMITTENT_JUMPS");
            throw new AssertionError("Multiple channels accepted");
        } catch(IllegalArgumentException expected) {
            if(!expected.getMessage().contains("single-channel"))throw expected;
        } finally {multi.close();}
        if(smoke)return;
        ImagePlus image=IJ.openImage(args[0]);if(image==null)throw new IllegalArgumentException("Cannot read input");
        try {
            String[] before=new String[image.getStackSize()];
            for(int i=0;i<before.length;i++)before[i]=hash(image.getStack().getPixels(i+1));
            Map<String,Object> result=NativeLongitudinalRuntime.estimate(image,args[1],args[2]);
            double[][] movements=(double[][])result.get("movements");
            List<String> rows=new ArrayList<>();rows.add("x,y,theta");
            for(double[] row:movements)rows.add(row[0]+","+row[1]+","+row[2]);
            Files.write(output.resolve("transforms.csv"),rows);
            List<String> preliminary=new ArrayList<>();preliminary.add("x,y,theta");
            for(double[] row:(double[][])result.get("preliminary"))preliminary.add(row[0]+","+row[1]+","+row[2]);
            Files.write(output.resolve("preliminary.csv"),preliminary);
            List<String> confidenceRows=new ArrayList<>();confidenceRows.add("frame,confidence");
            double[] confidenceValues=(double[])result.get("confidence");
            for(int i=0;i<confidenceValues.length;i++)confidenceRows.add((i+1)+","+confidenceValues[i]);
            Files.write(output.resolve("confidence.csv"),confidenceRows);
            List<String> pixels=new ArrayList<>();pixels.add("frame,pixel_type,sha256");
            ImagePlus corrected=NativeLongitudinalRuntime.apply(image,movements);
            try {
                if(corrected.getBitDepth()!=image.getBitDepth() || corrected.getStackSize()!=image.getStackSize())
                    throw new AssertionError("Output pixel type or frame coverage changed");
                for(int i=0;i<before.length;i++)pixels.add((i+1)+",native,"+hash(corrected.getStack().getPixels(i+1)));
            } finally {corrected.close();}
            ImageStack floatStack=new ImageStack(image.getWidth(),image.getHeight());
            for(int i=0;i<before.length;i++)floatStack.addSlice(image.getStack().getProcessor(i+1).convertToFloatProcessor().duplicate());
            ImagePlus floating=new ImagePlus("same shifts float output guard",floatStack);
            ImagePlus correctedFloat=NativeLongitudinalRuntime.apply(floating,movements);
            try {for(int i=0;i<before.length;i++)pixels.add((i+1)+",float,"+hash(correctedFloat.getStack().getPixels(i+1)));}
            finally {correctedFloat.close();floating.close();}
            Files.write(output.resolve("pixel_hashes.csv"),pixels);
            for(int i=0;i<before.length;i++)if(!before[i].equals(hash(image.getStack().getPixels(i+1))))
                throw new AssertionError("Input pixels were modified");
            List<Integer> weak=new ArrayList<>();double[] confidence=(double[])result.get("confidence");
            for(int i=0;i<confidence.length;i++)if(confidence[i]<=0)weak.add(i+1);
            Files.writeString(output.resolve("summary.json"),"{\"frames\":"+movements.length+",\"route\":\""+result.get("route")
                +"\",\"estimation_seconds\":"+result.get("estimation_seconds")
                +",\"bright_reference_frame\":"+result.get("bright_reference_frame")+",\"dim_reference_frame\":"+result.get("dim_reference_frame")
                +",\"endpoint_jump_frame\":"+result.get("endpoint_jump_frame")+",\"weak_frames\":"+weak
                +",\"persistent_jump_frames\":"+Arrays.toString((int[])result.get("persistent_jump_frames"))
                +",\"rigid_jump_frames\":"+Arrays.toString((int[])result.get("rigid_jump_frames"))
                +",\"input_preserved\":true,\"context_loader_restored\":true}");
            if(original!=Thread.currentThread().getContextClassLoader())throw new AssertionError("Caller context loader changed");
        } finally {image.close();}
    }
    private static String hash(Object pixels) throws Exception {
        byte[] bytes;
        if(pixels instanceof byte[])bytes=(byte[])pixels;
        else if(pixels instanceof short[]) {
            short[] values=(short[])pixels;ByteBuffer buffer=ByteBuffer.allocate(values.length*2).order(ByteOrder.LITTLE_ENDIAN);
            buffer.asShortBuffer().put(values);bytes=buffer.array();
        } else {
            float[] values=(float[])pixels;ByteBuffer buffer=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);
            buffer.asFloatBuffer().put(values);bytes=buffer.array();
        }
        StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",value&255));
        return out.toString();
    }
}
