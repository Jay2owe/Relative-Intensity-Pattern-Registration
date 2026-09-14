/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.isolated;

import ij.ImagePlus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Experimental packaged API: private engine and native libraries, shared ImageJ only.
 * Fixed first-frame reference, one selected-channel stack, native-size bilinear output.
 * Not the installed plugin's public API and not a replacement for its other modes.
 */
public final class NativeLongitudinalRuntime {
    private static volatile PrivateLoader engine;
    private NativeLongitudinalRuntime() { }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> estimate(ImagePlus image,String imageType,String motionType) {
        return (Map<String,Object>) invoke("estimate",new Class<?>[]{ImagePlus.class,String.class,String.class},
            image,imageType,motionType);
    }

    public static ImagePlus apply(ImagePlus image,double[][] movements) {
        return (ImagePlus)invoke("apply",new Class<?>[]{ImagePlus.class,double[][].class},image,movements);
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> runtimeInfo() {
        return (Map<String,Object>)invoke("runtimeInfo",new Class<?>[0]);
    }

    private static Object invoke(String name,Class<?>[] types,Object... args) {
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        try {
            PrivateLoader runtime=loader();
            Thread.currentThread().setContextClassLoader(runtime);
            return runtime.loadClass("ripr.core.LongitudinalIsolatedWorker").getMethod(name,types).invoke(null,args);
        } catch(InvocationTargetException e) {
            Throwable cause=e.getCause();
            if(cause instanceof RuntimeException)throw (RuntimeException)cause;
            if(cause instanceof Error)throw (Error)cause;
            throw new IllegalStateException("Bundled longitudinal calculation failed",cause);
        } catch(ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("Cannot load the bundled longitudinal engine; no fallback was used",e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static synchronized PrivateLoader loader() throws IOException {
        if(engine!=null)return engine;
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        String arch=System.getProperty("os.arch","").toLowerCase(Locale.ROOT);
        if(!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64")))
            throw new UnsupportedOperationException("This experimental build supports Windows x86-64 only");
        InputStream list=NativeLongitudinalRuntime.class.getResourceAsStream("/ripr-native/runtime.list");
        if(list==null)throw new IOException("Bundled runtime inventory is missing");
        // Keep Windows native extraction paths short; never use the input/output data folder.
        Path directory=Files.createTempDirectory("ripr-native-");
        directory.toFile().deleteOnExit();
        List<URL> urls=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(list,StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                if(line.isEmpty())continue;
                String[] fields=line.split(" ",-1);
                if(fields.length!=2 || !fields[0].matches("[a-z0-9_-]+\\.jar") || !fields[1].matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid bundled runtime inventory entry");
                Path target=directory.resolve(fields[0]);
                try(InputStream source=NativeLongitudinalRuntime.class.getResourceAsStream("/ripr-native/"+fields[0])) {
                    if(source==null)throw new IOException("Missing bundled runtime: "+fields[0]);
                    Files.copy(source,target);
                }
                target.toFile().deleteOnExit();
                if(!sha256(target).equals(fields[1]))throw new IOException("Bundled runtime checksum differs: "+fields[0]);
                urls.add(target.toUri().toURL());
            }
        }
        if(urls.size()!=7)throw new IOException("Expected the engine and six pinned native/runtime archives");
        PrivateLoader loaded=new PrivateLoader(urls.toArray(new URL[0]),NativeLongitudinalRuntime.class.getClassLoader());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{loaded.close();}catch(IOException ignored){}},"ripr-native-close"));
        engine=loaded;
        return loaded;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(path)) {
                byte[] buffer=new byte[65536];int read;
                while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);
            }
            StringBuilder text=new StringBuilder();
            for(byte value:digest.digest())text.append(String.format(Locale.ROOT,"%02x",value&255));
            return text.toString();
        } catch(java.security.NoSuchAlgorithmException e) {throw new AssertionError(e);}
    }

    private static final class PrivateLoader extends URLClassLoader {
        PrivateLoader(URL[] urls,ClassLoader parent){super(urls,parent);}
        @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            if(!name.startsWith("ripr.") && !name.startsWith("org.bytedeco."))return super.loadClass(name,resolve);
            synchronized(getClassLoadingLock(name)) {
                Class<?> loaded=findLoadedClass(name);
                // Fail closed: never substitute another plugin's engine or native binding.
                if(loaded==null)loaded=findClass(name);
                if(resolve)resolveClass(loaded);
                return loaded;
            }
        }
    }
}
