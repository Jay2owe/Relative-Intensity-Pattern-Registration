/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LongitudinalGaussianTimingProbe {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectory(out);
        int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]);
        byte[] bytes=Files.readAllBytes(source);float[] input=new float[width*height];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(input);
        List<String> rows=new ArrayList<>();rows.add("sigma,repeat,order,reference_seconds,candidate_seconds,changed_pixels");
        for(double sigma:new double[]{.8,1,1.5,3,8,25.6}) {
            for(int repeat=0;repeat<4;repeat++) {
                float[] a,b;double ta,tb;long started;
                if(repeat%2==0) {
                    started=System.nanoTime();a=LongitudinalReferenceFeatures.gaussianNearest(input,width,height,sigma);ta=(System.nanoTime()-started)/1e9;
                    started=System.nanoTime();b=LongitudinalStripedGaussian.filter(input,width,height,sigma);tb=(System.nanoTime()-started)/1e9;
                } else {
                    started=System.nanoTime();b=LongitudinalStripedGaussian.filter(input,width,height,sigma);tb=(System.nanoTime()-started)/1e9;
                    started=System.nanoTime();a=LongitudinalReferenceFeatures.gaussianNearest(input,width,height,sigma);ta=(System.nanoTime()-started)/1e9;
                }
                int changed=0;for(int i=0;i<a.length;i++)if(Float.floatToRawIntBits(a[i])!=Float.floatToRawIntBits(b[i]))changed++;
                rows.add(sigma+","+repeat+","+(repeat%2==0?"reference_first":"candidate_first")+","+ta+","+tb+","+changed);
            }
        }
        Files.write(out.resolve("comparison.csv"),rows);
    }
}
