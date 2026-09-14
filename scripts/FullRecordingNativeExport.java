package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ShortProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reapply recorded movements using the frozen native warper; never estimate motion. */
public final class FullRecordingNativeExport {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("input transforms pixel-hashes output");
        Path output=Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing overwrite: "+output);
        List<String> movements=Files.readAllLines(Path.of(args[1]));
        Map<String,String> hashes=new HashMap<>();
        for (String line:Files.readAllLines(Path.of(args[2])).subList(1,Files.readAllLines(Path.of(args[2])).size())) {
            String[] row=line.split(",");hashes.put(row[0],row[1]);
        }
        ImagePlus input=IJ.openImage(args[0]),result=null,reopened=null;
        if(input==null)throw new IllegalArgumentException("Cannot open source");
        try {
            int n=input.getStackSize(),w=input.getWidth(),h=input.getHeight();
            if(input.getNChannels()!=1||input.getBitDepth()!=16||movements.size()!=n+1)
                throw new IllegalArgumentException("Recording coverage or type differs");
            ImageStack stack=new ImageStack(w,h);
            for(int i=0;i<n;i++) {
                String[] row=movements.get(i+1).split(",");
                double x=Double.parseDouble(row[0]),y=Double.parseDouble(row[1]),theta=Double.parseDouble(row[2]);
                if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(theta))throw new IllegalArgumentException("Nonfinite movement");
                byte[] bytes=LongitudinalReferenceWarper.bilinear(input.getStack().getPixels(i+1),w,h,new Transform(x,y,theta));
                if(!hash(bytes).equals(hashes.get("native_"+i+".pixels")))throw new AssertionError("Native replay differs at "+i);
                short[] pixels=new short[w*h];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pixels);
                stack.addSlice(new ShortProcessor(w,h,pixels,null));
            }
            result=new ImagePlus("Registered full recording",stack);result.setDimensions(1,1,n);
            result.setCalibration(input.getCalibration().copy());
            if(!new FileSaver(result).saveAsTiffStack(output.toString()))throw new IllegalStateException("TIFF export failed");
            reopened=IJ.openImage(output.toString());
            if(reopened==null||reopened.getStackSize()!=n||reopened.getWidth()!=w||reopened.getHeight()!=h)
                throw new AssertionError("Saved TIFF dimensions differ");
            for(int i=0;i<n;i++) {
                short[] pixels=(short[])reopened.getStack().getPixels(i+1);
                ByteBuffer bytes=ByteBuffer.allocate(2*pixels.length).order(ByteOrder.LITTLE_ENDIAN);bytes.asShortBuffer().put(pixels);
                if(!hash(bytes.array()).equals(hashes.get("native_"+i+".pixels")))throw new AssertionError("Saved native pixels differ at "+i);
            }
            System.out.println("Reopened and verified all "+n+" native output frames.");
        } finally {if(reopened!=null)reopened.close();if(result!=null)result.close();input.close();}
    }
    private static String hash(byte[] bytes)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();
    }
}
