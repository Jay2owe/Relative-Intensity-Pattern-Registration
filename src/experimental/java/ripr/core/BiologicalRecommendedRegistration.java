/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.ImagePlus;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/** Executes the pinned, already-tested Recommended engine without longitudinal class overrides. */
public final class BiologicalRecommendedRegistration {
    private BiologicalRecommendedRegistration() { }
    public static final String ENGINE_SHA256 = "56dc20e6a4a6118c88d5c239214a465e6216616181df3de26c9ca84c5acb3d5d";
    public static final String RESOURCE = "/ripr/biological/recommended-engine.jar";

    /** Plain values cross the loader boundary; no legacy object is mistaken for a current-core type. */
    public static final class Result {
        public final Transform[] cumulative;
        public final int[] support;
        public final int pairCount;
        public final String engineSha256 = ENGINE_SHA256;
        Result(Transform[] cumulative,int[] support,int pairCount) {
            this.cumulative=cumulative.clone();this.support=support.clone();this.pairCount=pairCount;
        }
    }

    public static Result estimate(ImagePlus image,int workers) {
        if(image==null||image.getNChannels()!=1||image.getStackSize()<2||image.getNSlices()>1&&image.getNFrames()>1)
            throw new IllegalArgumentException("A single-channel two-dimensional time stack is required");
        if(workers<1||workers>16)throw new IllegalArgumentException("Biological worker count must be 1 through 16");
        try{return Holder.ENGINE.estimate(image,workers);}
        catch(InvocationTargetException e){
            Throwable cause=e.getCause();
            if(cause instanceof RuntimeException)throw (RuntimeException)cause;
            if(cause instanceof Error)throw (Error)cause;
            throw new IllegalStateException("Original Recommended estimation failed",cause);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Bundled Recommended API is incompatible",e);}
    }

    static void verifyEngine(byte[] bytes) {
        try{
            StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!ENGINE_SHA256.equals(hex.toString()))throw new IllegalArgumentException("Biological Recommended engine checksum differs; refusing mixed arithmetic");
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    static ClassLoader engineLoader(){return Holder.ENGINE.loader;}
    private static final class Holder {static final Engine ENGINE=new Engine();}

    private static final class Engine {
        final URLClassLoader loader;
        final Class<?> parameters,progress,cancellation;
        final Method estimate;
        Engine(){
            try{
                byte[] bytes;
                try(InputStream in=BiologicalRecommendedRegistration.class.getResourceAsStream(RESOURCE)){
                    if(in==null)throw new IOException("Bundled original Recommended engine is missing");bytes=in.readAllBytes();
                }
                verifyEngine(bytes);
                Path directory=Files.createTempDirectory("ripr-biological-engine-");Path jar=directory.resolve("engine.jar");
                directory.toFile().deleteOnExit();jar.toFile().deleteOnExit();Files.write(jar,bytes);
                loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},ImagePlus.class.getClassLoader()){
                    @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                        // No parent fallback for our engine: a missing class is an error, not a mixed-version run.
                        if(!name.startsWith("ripr."))return super.loadClass(name,resolve);
                        synchronized(getClassLoadingLock(name)){
                            Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                        }
                    }
                };
                parameters=loader.loadClass("ripr.api.RelativeIntensityPatternParameters");
                progress=loader.loadClass("ripr.core.PairScheduler$Progress");cancellation=loader.loadClass("ripr.core.PairScheduler$Cancellation");
                estimate=loader.loadClass("ripr.api.RelativeIntensityPatternRegistration").getMethod("estimate",ImagePlus.class,parameters,progress,cancellation);
                // Reject accidental class sharing before any input is registered.
                if(loader.loadClass("ripr.core.PairAligner")==PairAligner.class||loader.loadClass("ripr.core.LogPlane")==LogPlane.class)
                    throw new IllegalStateException("Original and longitudinal arithmetic were not isolated");
                Runtime.getRuntime().addShutdownHook(new Thread(()->{try{loader.close();}catch(IOException ignored){ }},"ripr-biological-engine-close"));
            }catch(IOException|ReflectiveOperationException e){throw new IllegalStateException("Cannot load the pinned biological Recommended engine",e);}
        }
        Object enumValue(String className,String name) throws ReflectiveOperationException {
            return loader.loadClass(className).getMethod("valueOf",String.class).invoke(null,name);
        }
        Result estimate(ImagePlus image,int workers) throws ReflectiveOperationException {
            Object builder=parameters.getMethod("builder").invoke(null);Class<?> b=builder.getClass();
            Object imageType=enumValue("ripr.api.ImageType","DENSE_FLUORESCENCE"),motion=enumValue("ripr.api.MotionType","INTERMITTENT_JUMPS");
            b.getMethod("recommendation",imageType.getClass(),motion.getClass()).invoke(builder,imageType,motion);
            Object selection=enumValue("ripr.api.SelectionMode","RECOMMENDED");
            b.getMethod("selectionMode",selection.getClass()).invoke(builder,selection);
            Object rotation=enumValue("ripr.core.RotationMode","OFF"),interpolation=enumValue("ripr.core.Warper$Interpolation","NONE");
            b.getMethod("rotationMode",rotation.getClass()).invoke(builder,rotation);
            b.getMethod("interpolation",interpolation.getClass()).invoke(builder,interpolation);
            b.getMethod("crop",boolean.class).invoke(builder,false);b.getMethod("threads",int.class).invoke(builder,workers);
            Object requested=b.getMethod("build").invoke(builder);
            Object raw=estimate.invoke(null,image,requested,progress.getField("NONE").get(null),cancellation.getField("NEVER").get(null));
            Object[] values=(Object[])raw.getClass().getField("cumulative").get(raw);Transform[] transforms=new Transform[values.length];
            Class<?> t=loader.loadClass("ripr.core.Transform");Field dx=t.getField("dx"),dy=t.getField("dy"),theta=t.getField("theta");
            for(int i=0;i<values.length;i++)transforms[i]=new Transform(dx.getDouble(values[i]),dy.getDouble(values[i]),theta.getDouble(values[i]));
            int[] support=(int[])raw.getClass().getField("support").get(raw);List<?> pairs=(List<?>)raw.getClass().getField("pairs").get(raw);
            if(transforms.length!=image.getStackSize()||support.length!=transforms.length)throw new IllegalStateException("Original engine returned different frame coverage");
            return new Result(transforms,support,pairs.size());
        }
    }
}
