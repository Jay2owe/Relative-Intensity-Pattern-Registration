/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Isolated sampling gate. Neither changes an input nor runs a registration. */
public final class LogPlaneSamplingProbe {
    public static void main(String[] args) throws Exception {
        ImagePlus image=IJ.openImage(args[0]);
        if(image==null || image.getNChannels()!=1)throw new IllegalArgumentException("One channel required");
        try {
            float[] pixels=(float[])image.getStack().getProcessor(1).convertToFloatProcessor().getPixels();
            LogPlane plane=LogPlane.of(pixels,image.getWidth(),image.getHeight(),1);
            List<double[]> rows=new ArrayList<>();
            for(int y=0;y<plane.height;y+=7)for(int x=0;x<plane.width;x+=7) {
                double xx=x+.37, yy=y+.63;
                rows.add(new double[]{xx,yy,plane.sampleReference(xx,yy),plane.sampleGxReference(xx,yy),
                        plane.sampleGyReference(xx,yy),plane.sample(xx,yy),plane.sampleGx(xx,yy),plane.sampleGy(xx,yy)});
            }
            rows.add(new double[]{-1,0,plane.sampleReference(-1,0),plane.sampleGxReference(-1,0),
                    plane.sampleGyReference(-1,0),plane.sample(-1,0),plane.sampleGx(-1,0),plane.sampleGy(-1,0)});
            ByteBuffer buffer=ByteBuffer.allocate(rows.size()*8*8).order(ByteOrder.LITTLE_ENDIAN);
            for(double[] row:rows)for(double v:row)buffer.putDouble(v);
            Files.write(Path.of(args[1]),buffer.array());
        } finally {image.close();}
    }
}
