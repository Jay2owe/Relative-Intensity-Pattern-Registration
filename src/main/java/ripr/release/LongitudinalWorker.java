/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.release;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Private process protocol. Reflection keeps the pinned scientific classes off Fiji's classloader. */
public final class LongitudinalWorker {
    private LongitudinalWorker() { }

    static int executionWorkers(String recipe) {
        return recipe.equals("bright_dim")?Math.max(1,Math.min(16,Runtime.getRuntime().availableProcessors())):16;
    }

    private static Object field(Object value, String name) throws Exception {
        return value.getClass().getField(name).get(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("Expected raw, width, height, frames, recipe, image type, output directory");
        int width=Integer.parseInt(args[1]), height=Integer.parseInt(args[2]), count=Integer.parseInt(args[3]);
        if(width<1 || height<1 || count<2) throw new IllegalArgumentException("A time series of at least two frames is required");
        String recipe=args[4];
        if(!Arrays.asList("bright_dim", "landmarks", "moving_cells").contains(recipe)) throw new IllegalArgumentException("Unknown explicit recipe");
        System.setProperty("ripr.longitudinal.candidate", recipe.equals("bright_dim") ? "A001_PHASE_SEED_RESCUE"
                : recipe.equals("moving_cells") ? "A004_GUARDED_BRIGHT_DIM_FALLBACK" : "");
        ImageStack stack=new ImageStack(width,height);
        try(DataInputStream input=new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(args[0]))))) {
            for(int t=0;t<count;t++) {
                float[] pixels=new float[Math.multiplyExact(width,height)];
                for(int p=0;p<pixels.length;p++) pixels[p]=Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
                stack.addSlice(new FloatProcessor(width,height,pixels));
            }
            if(input.read()!=-1) throw new IllegalArgumentException("Raw image length differs from its declared shape");
        }
        ImagePlus image=new ImagePlus("selected estimation channel",stack);
        Class<?> routing=Class.forName("ripr.core.LongitudinalVideoRouting");
        Class<? extends Enum> routeType=(Class<? extends Enum>)Class.forName("ripr.core.LongitudinalVideoRouting$Route");
        Class<? extends Enum> imageType=(Class<? extends Enum>)Class.forName("ripr.api.ImageType");
        Class<? extends Enum> motionType=(Class<? extends Enum>)Class.forName("ripr.api.MotionType");
        Class<?> policyType=Class.forName("ripr.core.LongitudinalExecutionPolicy");
        String route=recipe.equals("bright_dim")?"BRIGHT_DIM_TISSUE":recipe.equals("landmarks")?"TISSUE_LANDMARKS":"BIOLOGICAL_MOVING_CELLS";
        // Moving cells/Landmarks retain the original accepted producer's 16-worker
        // policy. Only Bright/dim changes that cap to the accepted A013 hardware cap.
        int workers=executionWorkers(recipe);
        Object policy=policyType.getConstructor(int.class,boolean.class,boolean.class,
                int.class,int.class,int.class,boolean.class,boolean.class).newInstance(workers,false,true,workers,workers,workers,true,false);
        Class<?> nativeCore=Class.forName("org.bytedeco.opencv.global.opencv_core");
        nativeCore.getMethod("setNumThreads",int.class).invoke(null,1);
        nativeCore.getMethod("setUseOpenCL",boolean.class).invoke(null,false);
        Object result;
        try {
            result=routing.getMethod("estimate",ImagePlus.class,routeType,imageType,motionType,policyType).invoke(null,image,
                    Enum.valueOf(routeType,route),Enum.valueOf(imageType,args[5]),Enum.valueOf(motionType,"INTERMITTENT_JUMPS"),policy);
        } catch(InvocationTargetException failure) {
            Throwable cause=failure.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            if(cause instanceof Error) throw (Error)cause;
            throw failure;
        } finally { image.close(); }
        Object[] transforms=(Object[])field(result,"transforms");
        if(transforms.length!=count) throw new IllegalStateException("The pinned engine dropped frames");
        Properties metadata=new Properties();
        String recipeId=(String)field(result,"recipeId");
        metadata.setProperty("recipe_id",recipeId);
        metadata.setProperty("review_label",(String)field(result,"reviewLabel"));
        metadata.setProperty("selection_source","manual_explicit_accepted_java_recipe");
        metadata.setProperty("automatic_router_used","false");
        metadata.setProperty("image_type",args[5]);
        metadata.setProperty("workers",Integer.toString(workers));
        metadata.setProperty("native_threads",String.valueOf(nativeCore.getMethod("getNumThreads").invoke(null)));
        metadata.setProperty("opencl_enabled",String.valueOf(nativeCore.getMethod("useOpenCL").invoke(null)));
        if(!"1".equals(metadata.getProperty("native_threads")) || !"false".equals(metadata.getProperty("opencl_enabled")))
            throw new IllegalStateException("Accepted native thread/OpenCL settings changed");
        metadata.setProperty("execution_policy",recipe.equals("bright_dim")?"A013":"accepted_benchmark_16_workers");
        metadata.setProperty("estimation_seconds",String.valueOf(field(result,"estimationSeconds")));
        metadata.setProperty("fallback_used",Boolean.toString(recipeId.contains("fallback_bright_dim")));
        Object longitudinal=field(result,"longitudinal"), biological=field(result,"biological");
        double[] confidence=new double[count];Arrays.fill(confidence,Double.NaN);
        int[] support=new int[count];Arrays.fill(support,-1);
        if(longitudinal!=null) {
            confidence=(double[])field(longitudinal,"confidence");
            for(String key:Arrays.asList("bright","dim","endpoint")) metadata.setProperty(key,String.valueOf(field(longitudinal,key)));
            metadata.setProperty("persistent_jumps",join((int[])field(longitudinal,"persistentJumps")));
            metadata.setProperty("rigid_jumps",join((int[])field(longitudinal,"rigidJumps")));
        } else if(biological!=null) { support=(int[])field(biological,"support"); }
        List<Integer> weak=new ArrayList<>();
        Path destination=Paths.get(args[6]);
        try(PrintWriter output=new PrintWriter(Files.newBufferedWriter(destination.resolve("transforms.csv"),StandardCharsets.UTF_8))) {
            output.println("frame,dx,dy,theta,confidence,support");
            for(int t=0;t<count;t++) {
                double dx=((Number)field(transforms[t],"dx")).doubleValue(), dy=((Number)field(transforms[t],"dy")).doubleValue(),
                        theta=((Number)field(transforms[t],"theta")).doubleValue();
                if(!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(theta)) throw new IllegalStateException("Non-finite transform at frame "+t);
                output.println(t+","+dx+","+dy+","+theta+","+confidence[t]+","+support[t]);
                if((Double.isFinite(confidence[t]) && confidence[t]<=0) || support[t]==0) weak.add(t);
            }
        }
        metadata.setProperty("weak_frames",join(weak.stream().mapToInt(Integer::intValue).toArray()));
        try(OutputStream output=Files.newOutputStream(destination.resolve("metadata.properties"))) { metadata.store(output,"Accepted longitudinal process result"); }
    }

    private static String join(int[] values) {
        StringBuilder text=new StringBuilder();
        for(int value:values) { if(text.length()>0)text.append(',');text.append(value); }
        return text.toString();
    }
}
