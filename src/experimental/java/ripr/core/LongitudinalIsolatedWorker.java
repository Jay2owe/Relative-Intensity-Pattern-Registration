/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ij.process.FloatProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import ripr.api.ImageType;
import ripr.api.MotionType;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Private bridge crossing the classloader boundary only with ImageJ/JDK types. */
public final class LongitudinalIsolatedWorker {
    private LongitudinalIsolatedWorker() { }
    public static synchronized Map<String,Object> estimate(ImagePlus image,String imageType,String motionType) {
        checkImage(image);
        return withNativeSettings(()->estimateConfigured(image,imageType,motionType));
    }
    private static Map<String,Object> estimateConfigured(ImagePlus image,String imageType,String motionType) {
        long start=System.nanoTime();
        // Execution only: retain every recipe, seed, convergence rule and output value.
        // The reference overload remains available for explicit serial comparisons.
        int workers=executionWorkers();
        LongitudinalReferenceRegistration.Outcome result=LongitudinalReferenceRegistration.estimate(
            image,ImageType.from(imageType),MotionType.from(motionType),
            new LongitudinalExecutionPolicy(workers,false,true,workers,workers,workers,true,false));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("movements",array(result.transforms));out.put("preliminary",array(result.preliminary));
        out.put("confidence",result.confidence.clone());out.put("route",result.route);
        out.put("bright_reference_frame",result.bright<0?null:result.bright+1);
        out.put("dim_reference_frame",result.dim<0?null:result.dim+1);
        out.put("endpoint_jump_frame",result.endpoint<0?null:result.endpoint+1);
        out.put("persistent_jump_frames",oneBased(result.persistentJumps));
        out.put("rigid_jump_frames",oneBased(result.rigidJumps));
        out.put("estimation_seconds",(System.nanoTime()-start)/1e9);
        out.put("preliminary_seconds",result.preliminarySeconds);out.put("refinement_seconds",result.refinementSeconds);
        return out;
    }
    private static int executionWorkers() {
        return Math.max(1,Math.min(16,Runtime.getRuntime().availableProcessors()));
    }
    public static ImagePlus apply(ImagePlus image,double[][] movements) {
        checkImage(image);
        if(movements==null || movements.length!=image.getStackSize())throw new IllegalArgumentException("One movement per frame is required");
        for(double[] row:movements) {
            if(row==null || row.length!=3)throw new IllegalArgumentException("Movements must be x/y/angle triples");
            for(double value:row)if(!Double.isFinite(value))throw new IllegalArgumentException("Movements must be finite");
        }
        int width=image.getWidth(),height=image.getHeight(),length=width*height;
        ImageStack stack=new ImageStack(width,height);
        for(int i=0;i<movements.length;i++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            double[] row=movements[i];Object pixels=image.getStack().getPixels(i+1);
            byte[] warped=LongitudinalReferenceWarper.bilinear(pixels,width,height,new Transform(row[0],row[1],row[2]));
            ByteBuffer buffer=ByteBuffer.wrap(warped).order(ByteOrder.LITTLE_ENDIAN);
            String label=image.getStack().getSliceLabel(i+1);
            if(pixels instanceof byte[])stack.addSlice(label,new ByteProcessor(width,height,warped));
            else if(pixels instanceof short[]) {
                short[] values=new short[length];buffer.asShortBuffer().get(values);stack.addSlice(label,new ShortProcessor(width,height,values,null));
            } else {
                float[] values=new float[length];buffer.asFloatBuffer().get(values);stack.addSlice(label,new FloatProcessor(width,height,values));
            }
        }
        ImagePlus corrected=new ImagePlus(image.getTitle()+" - longitudinal native candidate",stack);
        corrected.setCalibration(image.getCalibration().copy());corrected.setDimensions(1,1,movements.length);
        corrected.setDisplayRange(image.getDisplayRangeMin(),image.getDisplayRangeMax());
        return corrected;
    }
    public static synchronized Map<String,Object> runtimeInfo() {
        return withNativeSettings(LongitudinalIsolatedWorker::runtimeInfoConfigured);
    }
    private static Map<String,Object> runtimeInfoConfigured() {
        Map<String,Object> out=new LinkedHashMap<>();
        // The native string wrapper can include a trailing NUL in its declared length.
        // Numeric version functions keep the recorded version exact and printable.
        out.put("opencv_version",getVersionMajor()+"."+getVersionMinor()+"."+getVersionRevision());
        out.put("independent_frame_workers",executionWorkers());
        out.put("exact_median_selection",true);
        out.put("reused_pair_buffers",true);
        out.put("reused_smoothing",false);
        float[] sample=new float[256];sample[8*16+8]=1;
        float[] blurred=OpenCvLongitudinalOps.gaussian(sample,16,16,2);
        out.put("gaussian_centre",blurred[8*16+8]);
        out.put("engine_classloader",LongitudinalIsolatedWorker.class.getClassLoader().getClass().getName());
        out.put("native_classloader",org.bytedeco.opencv.global.opencv_core.class.getClassLoader().getClass().getName());
        out.put("shared_imagej_classloader",String.valueOf(ImagePlus.class.getClassLoader()));
        return out;
    }
    private static <T> T withNativeSettings(Supplier<T> calculation) {
        // Windows may share native OpenCV state despite separate Java classloaders.
        // Preserve the caller's state on success and failure; keep the checked
        // single-thread/non-OpenCL calculation unchanged inside this scope.
        int previousThreads=getNumThreads();
        boolean previousOpenCL=useOpenCL();
        try {
            setNumThreads(1);setUseOpenCL(false);
            return calculation.get();
        } finally {
            try {setUseOpenCL(previousOpenCL);}
            finally {setNumThreads(previousThreads);}
        }
    }
    private static void checkImage(ImagePlus image) {
        if(image==null || image.getNChannels()!=1 || image.getStackSize()<2)
            throw new IllegalArgumentException("A single-channel time stack is required; no other channel is read");
        if(image.isHyperStack() && image.getNSlices()!=1)
            throw new IllegalArgumentException("Select one Z plane before using this experimental time-stack API");
        if(image.getBitDepth()!=8 && image.getBitDepth()!=16 && image.getBitDepth()!=32)
            throw new IllegalArgumentException("Only unsigned 8/16-bit or float32 images are supported");
    }
    private static int[] oneBased(int[] source){int[] out=source.clone();for(int i=0;i<out.length;i++)out[i]++;return out;}
    private static double[][] array(Transform[] source) {
        double[][] out=new double[source.length][3];
        for(int i=0;i<out.length;i++){out[i][0]=source[i].dx;out[i][1]=source[i].dy;out[i][2]=source[i].theta;}
        return out;
    }
}
