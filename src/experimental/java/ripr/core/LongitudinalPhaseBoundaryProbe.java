/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.bytedeco.opencv.global.opencv_core.*;

public final class LongitudinalPhaseBoundaryProbe {
    static float[] read(Path file) throws Exception {
        byte[] bytes=Files.readAllBytes(file);float[] values=new float[bytes.length/4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);return values;
    }
    static void write(Path file,float[] values) throws Exception {
        ByteBuffer buffer=ByteBuffer.allocate(4*values.length).order(ByteOrder.LITTLE_ENDIAN);buffer.asFloatBuffer().put(values);
        Files.write(file,buffer.array());
    }
    public static void main(String[] args) throws Exception {
        setNumThreads(1);setUseOpenCL(false);
        Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectory(out);
        int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]),count=Integer.parseInt(args[4]);
        LongitudinalReferencePhase.Plane[] thumbnails=new LongitudinalReferencePhase.Plane[count];
        for(int i=0;i<count;i++) {
            thumbnails[i]=LongitudinalReferencePhase.thumbnail(read(source.resolve("feature_"+i+".f32")),width,height,128);
            write(out.resolve("thumbnail_"+i+".f32"),thumbnails[i].values);
        }
        double[] responses=new double[count-1];List<String> rows=new ArrayList<>();rows.add("boundary,dx,dy,peak");
        for(int i=1;i<count;i++) {
            double[] result=LongitudinalReferencePhase.estimate(thumbnails[i-1].values,thumbnails[i].values,thumbnails[i].width,thumbnails[i].height);
            responses[i-1]=result[2];rows.add(i+","+result[0]+","+result[1]+","+result[2]);
        }
        Files.write(out.resolve("pairs.csv"),rows);
        double[] sorted=responses.clone();Arrays.sort(sorted);int n=sorted.length;
        double median=n%2==1?sorted[n/2]:(sorted[n/2-1]+sorted[n/2])/2;
        double cutoff=Math.min(.20,.35*median);List<Integer> boundaries=new ArrayList<>();
        for(int i=0;i<responses.length;i++)if(responses[i]<cutoff)boundaries.add(i+1);
        Files.writeString(out.resolve("boundaries.json"),boundaries.toString());
        Files.writeString(out.resolve("summary.json"),"{\"frames\":"+count+",\"cutoff\":"+cutoff+"}");
    }
}
