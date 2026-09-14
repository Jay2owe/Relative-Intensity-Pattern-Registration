/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Raw numerical stage outputs, not display images or an accepted registration. */
public final class LongitudinalLandmarkProbe {
    static void write(Path path,float[] data) throws Exception {
        ByteBuffer buffer=ByteBuffer.allocate(4*data.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(data);Files.write(path,buffer.array());
    }
    public static void main(String[] args) throws Exception {
        ImagePlus image=IJ.openImage(args[0]);Path out=Path.of(args[2]);Files.createDirectory(out);
        if(image==null || image.getNChannels()!=1)throw new IllegalArgumentException("One channel required");
        try {
            int count=image.getStackSize(),width=image.getWidth(),height=image.getHeight();
            List<String> rows=Files.readAllLines(Path.of(args[1]));
            if(rows.size()!=count+1)throw new IllegalArgumentException("Incomplete preliminary");
            float[][] features=new float[count][];Transform[] baseline=new Transform[count];
            long start=System.nanoTime();
            for(int i=0;i<count;i++) {
                String[] row=rows.get(i+1).split(",");
                baseline[i]=new Transform(Double.parseDouble(row[0]),Double.parseDouble(row[1]),Double.parseDouble(row[2]));
                float[] frame=(float[])image.getStack().getProcessor(i+1).convertToFloatProcessor().getPixels();
                features[i]=LongitudinalReferenceFeatures.landmark(frame,width,height);
                write(out.resolve("feature_"+i+".f32"),features[i]);
            }
            for(int i:new int[]{0,count/2,count-1})
                write(out.resolve("warped_"+i+".f32"),LongitudinalReferenceLandmarks.warp(features[i],width,height,baseline[i]));
            write(out.resolve("reference.f32"),LongitudinalReferenceLandmarks.reference(features,baseline,width,height));
            Files.writeString(out.resolve("summary.json"),"{\"frames\":"+count+",\"width\":"+width+",\"height\":"+height
                +",\"seconds\":"+(System.nanoTime()-start)/1e9+",\"channels\":1}");
            boolean[] point=new boolean[49];point[24]=true;
            for(int size=1;size<=4;size++) {
                boolean[] mask=LongitudinalReferenceLandmarks.dilate(point,7,7,size);byte[] bytes=new byte[49];
                for(int i=0;i<49;i++)bytes[i]=(byte)(mask[i]?1:0);
                Files.write(out.resolve("dilation_"+size+".u8"),bytes);
            }
        } finally {image.close();}
    }
}
