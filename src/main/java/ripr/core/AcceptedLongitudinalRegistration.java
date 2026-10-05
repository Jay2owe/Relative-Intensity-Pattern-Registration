/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.ImagePlus;
import ripr.StackFrames;
import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.SelectionMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Executes the accepted, checksum-pinned native recipes without sharing experimental classes with Fiji. */
public final class AcceptedLongitudinalRegistration {
    private AcceptedLongitudinalRegistration() { }
    private static final String RESOURCE="/ripr/longitudinal/";
    public static boolean selected(SelectionMode mode) {
        return mode==SelectionMode.ACCEPTED_LONGITUDINAL || mode==SelectionMode.ACCEPTED_MOVING_CELLS;
    }
    public static final class Outcome {
        public final Registration.Result registration;
        public final LongitudinalRegistration.Diagnostics diagnostics;
        public final String recipeId,reviewLabel,provenance;
        public final double estimationSeconds;
        Outcome(Registration.Result registration,LongitudinalRegistration.Diagnostics diagnostics,Properties metadata) {
            this.registration=registration;this.diagnostics=diagnostics;
            recipeId=metadata.getProperty("recipe_id");reviewLabel=metadata.getProperty("review_label");
            estimationSeconds=Double.parseDouble(metadata.getProperty("estimation_seconds"));
            provenance="selection=manual_explicit_accepted_java_recipe; recipe="+recipeId
                    +"; image_type="+metadata.getProperty("image_type")+"; automatic_router_used=false; execution_policy="
                    +metadata.getProperty("execution_policy")+"; fallback_used="+metadata.getProperty("fallback_used");
        }
    }

    public static Outcome estimate(ImagePlus image,RelativeIntensityPatternParameters parameters,
            PairScheduler.Progress progress,PairScheduler.Cancellation cancellation) {
        if(image==null || parameters==null || !selected(parameters.selectionMode)) throw new IllegalArgumentException("Accepted longitudinal parameters are required");
        if(parameters.rotationMode!=RotationMode.OFF) throw new IllegalArgumentException("Accepted longitudinal recipes do not support continuous or declared-event rotation");
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows") || !System.getProperty("os.arch","").contains("64"))
            throw new IllegalStateException("The accepted native longitudinal build is validated for 64-bit Windows only; no alternative engine will be substituted");
        StackFrames source=StackFrames.of(image,parameters.channel,parameters.slice);
        if(source.count()<2) throw new IllegalArgumentException("At least two timepoints are required");
        PairScheduler.Cancellation cancel=cancellation==null?PairScheduler.Cancellation.NEVER:cancellation;
        if(cancel.cancelled()) throw new CancellationException("cancelled");
        String recipe=parameters.selectionMode==SelectionMode.ACCEPTED_MOVING_CELLS?"moving_cells"
                :parameters.imageType==ImageType.PHASE_CONTRAST || parameters.imageType==ImageType.BRIGHTFIELD_DIC?"landmarks":"bright_dim";
        String subtype=recipe.equals("moving_cells")?ImageType.DENSE_FLUORESCENCE.name():parameters.imageType.name();
        Path directory=null;Process process=null;
        try {
            String java=javaRuntime();
            directory=Files.createTempDirectory("ripr-longitudinal-");
            Properties pins=new Properties();
            try(InputStream input=resource("runtime.properties")) { pins.load(input); }
            List<String> names=new ArrayList<>(pins.stringPropertyNames());Collections.sort(names);
            List<String> classpath=new ArrayList<>();
            String candidate=recipe.equals("moving_cells")?"A004_candidate.jar":"A001_candidate.jar";
            for(String name:Arrays.asList(candidate,"frozen-engine.jar")) classpath.add(extract(directory,name,pins).toString());
            for(String name:names) if(!name.equals("frozen-engine.jar") && !name.startsWith("A00")) classpath.add(extract(directory,name,pins).toString());
            classpath.add(Paths.get(AcceptedLongitudinalRegistration.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            Path raw=directory.resolve("input.raw");
            try(DataOutputStream output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw)))) {
                for(int t=0;t<source.count();t++) {
                    if(cancel.cancelled()) throw new CancellationException("cancelled");
                    float[] pixels=source.plane(t);
                    for(float value:pixels) output.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(value)));
                }
            }
            if(progress!=null)progress.phase("Accepted longitudinal Java estimation",0,source.count());
            List<String> command=Arrays.asList(java,"-Djava.awt.headless=true","-Xmx4g","--enable-native-access=ALL-UNNAMED",
                    "-Dorg.bytedeco.javacpp.cachedir="+directory.resolve("native"),"-cp",String.join(File.pathSeparator,classpath),
                    "ripr.release.LongitudinalWorker",raw.toString(),Integer.toString(source.width()),Integer.toString(source.height()),
                    Integer.toString(source.count()),recipe,subtype,directory.toString());
            ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile());
            builder.environment().put("OMP_NUM_THREADS","1");builder.environment().put("OPENBLAS_NUM_THREADS","1");
            process=builder.start();
            while(!process.waitFor(250,TimeUnit.MILLISECONDS)) {
                if(cancel.cancelled() || Thread.currentThread().isInterrupted()) { process.destroyForcibly();throw new CancellationException("cancelled"); }
            }
            if(process.exitValue()!=0) throw new IllegalStateException("Accepted longitudinal Java engine failed; no output was accepted.\n"
                    +new String(Files.readAllBytes(directory.resolve("worker.log")),StandardCharsets.UTF_8));
            Properties metadata=new Properties();
            try(InputStream input=Files.newInputStream(directory.resolve("metadata.properties"))) {metadata.load(input);}
            if(!"false".equals(metadata.getProperty("automatic_router_used")) || metadata.getProperty("recipe_id","").isEmpty())
                throw new IllegalStateException("Missing explicit executed recipe provenance");
            List<String> rows=Files.readAllLines(directory.resolve("transforms.csv"),StandardCharsets.UTF_8);
            int count=source.count();if(rows.size()!=count+1)throw new IllegalStateException("Incomplete frame coverage");
            Transform[] transforms=new Transform[count];int[] support=new int[count];
            for(int t=0;t<count;t++) {
                String[] row=rows.get(t+1).split(",");if(Integer.parseInt(row[0])!=t)throw new IllegalStateException("Frame order changed");
                double dx=Double.parseDouble(row[1]),dy=Double.parseDouble(row[2]),theta=Double.parseDouble(row[3]);
                if(!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(theta))throw new IllegalStateException("Non-finite transform");
                transforms[t]=new Transform(dx,dy,theta);support[t]=Integer.parseInt(row[5]);
            }
            double[] unavailable=new double[count];Arrays.fill(unavailable,Double.NaN);
            List<Registration.Warning> warnings=new ArrayList<>();
            warnings.add(new Registration.Warning(Registration.Warning.Kind.LONGITUDINAL_ACCURACY_SCOPE,
                    "Accepted longitudinal recipes have limited real-data validation, not guaranteed unattended accuracy. Pair residuals/gain are unavailable (-1 support means not exported)."));
            int[] weak=indices(metadata.getProperty("weak_frames",""));
            if(weak.length>0)warnings.add(new Registration.Warning(Registration.Warning.Kind.LONGITUDINAL_WEAK_EVIDENCE,
                    weak.length+" frames have weak longitudinal evidence; review the recording"));
            if("true".equals(metadata.getProperty("fallback_used")))warnings.add(new Registration.Warning(Registration.Warning.Kind.LONGITUDINAL_RECOVERY_USED,
                    "Moving cells failed its independent-anchor guard; the accepted Bright/dim recovery supplied the final transforms"));
            Registration.Result registration=new Registration.Result(transforms,new ArrayList<Registration.PairResult>(),support,
                    new ChainRepair.Reason[count],unavailable.clone(),unavailable.clone(),unavailable.clone(),unavailable.clone(),
                    new PairAligner.Status[count],warnings,0,Integer.parseInt(metadata.getProperty("workers")),0,0,null);
            LongitudinalRegistration.Diagnostics diagnostics=new LongitudinalRegistration.Diagnostics(metadata.getProperty("recipe_id"),
                    Integer.parseInt(metadata.getProperty("bright","-1")),Integer.parseInt(metadata.getProperty("dim","-1")),weak,
                    indices(metadata.getProperty("persistent_jumps","")),indices(metadata.getProperty("rigid_jumps","")),
                    Integer.parseInt(metadata.getProperty("endpoint","-1")));
            if(progress!=null)progress.phase("Accepted longitudinal Java estimation",count,count);
            return new Outcome(registration,diagnostics,metadata);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new CancellationException("interrupted");
        } catch(IOException | java.net.URISyntaxException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot execute the checksum-pinned longitudinal engine",failure);
        } finally {
            if(process!=null && process.isAlive()) {process.destroyForcibly();try{process.waitFor();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
            if(directory!=null)try(java.util.stream.Stream<Path> files=Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){path.toFile().deleteOnExit();}});
            } catch(IOException ignored) {directory.toFile().deleteOnExit();}
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input=AcceptedLongitudinalRegistration.class.getResourceAsStream(RESOURCE+name);
        if(input==null)throw new IOException("Missing frozen longitudinal resource: "+name);return input;
    }
    private static Path extract(Path directory,String name,Properties pins) throws IOException,java.security.NoSuchAlgorithmException {
        if(!name.matches("[A-Za-z0-9._-]+\\.jar") || pins.getProperty(name)==null)throw new IOException("Unknown pinned artifact");
        Path destination=directory.resolve(name);try(InputStream input=resource(name)){Files.copy(input,destination);}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(destination)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));
        if(!hex.toString().equals(pins.getProperty(name)))throw new IOException("Frozen artifact checksum differs: "+name);
        return destination;
    }
    private static int[] indices(String text) {
        if(text.isEmpty())return new int[0];return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray();
    }
    private static String javaRuntime() throws IOException,InterruptedException {
        List<String> candidates=new ArrayList<>();
        for(String name:Arrays.asList("RIPR_LONGITUDINAL_JAVA","RIPR_JAVA")) {
            String value=System.getenv(name);if(value!=null && !value.isEmpty())candidates.add(value);
        }
        String javaHome=System.getenv("JAVA_HOME");if(javaHome!=null)candidates.add(Paths.get(javaHome,"bin","java.exe").toString());
        candidates.add(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString());candidates.add("java");
        for(String candidate:candidates)try {
            Process process=new ProcessBuilder(candidate,"-version").redirectErrorStream(true).start();
            if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();continue;}
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            try(InputStream input=process.getInputStream()){while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);}
            java.util.regex.Matcher version=java.util.regex.Pattern.compile("version \\\"(\\d+)").matcher(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            if(process.exitValue()==0 && version.find() && Integer.parseInt(version.group(1))>=25)return candidate;
        } catch(IOException unavailable) { /* Try the next declared/discovered runtime. */ }
        throw new IOException("Accepted longitudinal recipes require Java 25 or newer. Set RIPR_LONGITUDINAL_JAVA to its java.exe; no different registration engine will be substituted.");
    }
}
