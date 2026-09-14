/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;
import ij.IJ;
import ij.ImagePlus;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
public final class LogPlaneReferenceProbe {
    public static void main(String[] args) throws Exception {
        ImagePlus image=IJ.openImage(args[0]);Path out=Path.of(args[1]);Files.createDirectory(out);
        if(image==null || image.getNChannels()!=1)throw new IllegalArgumentException("One channel required");
        try {
            float[] pixels=(float[])image.getStack().getProcessor(1).convertToFloatProcessor().getPixels();
            LogPlane[] levels=LogPlane.of(pixels,image.getWidth(),image.getHeight(),1).pyramid(5);
            for(int level=0;level<levels.length;level++) {
                ByteBuffer buffer=ByteBuffer.allocate(4*levels[level].v.length).order(ByteOrder.LITTLE_ENDIAN);
                buffer.asFloatBuffer().put(levels[level].v);Files.write(out.resolve("level_"+level+".f32"),buffer.array());
            }
        } finally {image.close();}
    }
}
