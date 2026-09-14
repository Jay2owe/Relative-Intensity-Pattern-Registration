/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import ripr.api.*;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Full original clip, one independently reusable execution component. Not final registration evidence. */
public final class LongitudinalComponentSpeedProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=8)throw new IllegalArgumentException("input type motion output component workers repeats expectedFrames");
        setNumThreads(1);setUseOpenCL(false);
        ImagePlus image=IJ.openImage(args[0]);if(image==null)throw new IllegalArgumentException("Cannot read input");
        Path out=Path.of(args[3]);Files.createDirectories(out);
        try {
            int w=image.getWidth(),h=image.getHeight(),n=image.getStackSize(),workers=Integer.parseInt(args[5]),repeats=Integer.parseInt(args[6]);
            if(n!=Integer.parseInt(args[7]) || image.getNChannels()!=1)throw new IllegalArgumentException("Frozen input shape changed");
            ImageType type=ImageType.from(args[1]);boolean emission=type!=ImageType.PHASE_CONTRAST && type!=ImageType.BRIGHTFIELD_DIC;
            float[][] raw=new float[n][];for(int i=0;i<n;i++)raw[i]=(float[])image.getStack().getProcessor(i+1).convertToFloatProcessor().getPixels();
            for(int repeat=0;repeat<repeats;repeat++) {
                List<String> rows=new ArrayList<>();long start=System.nanoTime();double seconds;
                if(args[4].equals("preliminary")) {
                    RelativeIntensityPatternParameters p=RelativeIntensityPatternParameters.builder().recommendation(type,MotionType.from(args[2]))
                        .selectionMode(SelectionMode.AUTOMATIC).threads(workers).crop(false).build();
                    Registration.Result result=RelativeIntensityPatternRegistration.estimate(image,p,PairScheduler.Progress.NONE,PairScheduler.Cancellation.NEVER);
                    seconds=(System.nanoTime()-start)/1e9;rows.add("x,y,theta");
                    for(Transform t:result.cumulative)rows.add(t.dx+","+t.dy+","+t.theta);
                } else if(args[4].equals("features") || args[4].equals("features_select")) {
                    float[][] features=LongitudinalPreparedFrames.prepare(raw,w,h,emission,workers,args[4].equals("features_select"));
                    seconds=(System.nanoTime()-start)/1e9;rows.add("frame,sha256");
                    for(int i=0;i<n;i++) {ByteBuffer bytes=ByteBuffer.allocate(4*w*h).order(ByteOrder.LITTLE_ENDIAN);for(float value:features[i])bytes.putInt(Float.floatToRawIntBits(value));rows.add(i+","+hash(bytes.array()));}
                } else if(args[4].equals("endpoint")) {
                    LongitudinalReferenceEndpoint.Measurement[] values=new LongitudinalReferenceEndpoint.Measurement[n];String[] failures=new String[n];
                    LongitudinalOrderedFrames.run(n,workers,false,i->{try{values[i]=LongitudinalReferenceEndpoint.measure(raw[i],w,h);}catch(IllegalArgumentException e){failures[i]=e.getClass().getName()+":"+e.getMessage();}});
                    seconds=(System.nanoTime()-start)/1e9;rows.add("frame,x,y,area,width,height,mask_sha256");
                    for(int i=0;i<n;i++) {
                        if(values[i]==null){rows.add(i+",missing,"+failures[i]);continue;}
                        LongitudinalReferenceEndpoint.Measurement m=values[i];byte[] mask=new byte[m.mask.length];for(int j=0;j<mask.length;j++)mask[j]=(byte)(m.mask[j]?1:0);
                        rows.add(i+","+m.x+","+m.y+","+m.area+","+m.width+","+m.height+","+hash(mask));
                    }
                } else throw new IllegalArgumentException("Unknown component");
                Files.write(out.resolve("repeat_"+repeat+".csv"),rows);
                Files.writeString(out.resolve("time_"+repeat+".json"),"{\"seconds\":"+seconds+",\"repeat\":"+repeat+",\"workers\":"+workers+",\"frames\":"+n+"}");
                System.out.println(args[4]+" workers="+workers+" repeat="+repeat+" seconds="+seconds);System.out.flush();
            }
        } finally {image.close();}
    }
    private static String hash(byte[] bytes) throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
}
