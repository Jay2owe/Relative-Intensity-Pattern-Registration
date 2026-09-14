/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LongitudinalAreaProbe {
    static float[] read(Path file) throws Exception {
        byte[] bytes=Files.readAllBytes(file);float[] result=new float[bytes.length/4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(result);return result;
    }
    static void write(Path file,float[] values) throws Exception {
        ByteBuffer buffer=ByteBuffer.allocate(4*values.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values);Files.write(file,buffer.array());
    }
    static void windows(Path out,String name,LogPlane[] planes,PairAligner.Options o) throws Exception {
        for(int level=0;level<planes.length;level++) {
            AreaCorrelation.Window window=AreaCorrelation.Window.of(planes[level],o);
            String prefix=name+"_l"+level;
            write(out.resolve(prefix+"_log.f32"),planes[level].v);
            write(out.resolve(prefix+"_value.f32"),window.value);
            write(out.resolve(prefix+"_coeff.f32"),window.coefficients);
            byte[] valid=new byte[planes[level].valid.length];
            for(int i=0;i<valid.length;i++)valid[i]=(byte)(planes[level].valid[i]?1:0);
            Files.write(out.resolve(prefix+"_valid.u8"),valid);
        }
    }
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),out=Path.of(args[2]);Files.createDirectory(out);
        int width=Integer.parseInt(args[3]),height=Integer.parseInt(args[4]);
        List<String> starts=Files.readAllLines(Path.of(args[1]));
        PairAligner.Options o=LongitudinalReferenceArea.options(width,height);int levels=o.levelsFor(width,height);
        LogPlane[] reference=LogPlane.of(read(source.resolve("reference.f32")),width,height,1).pyramid(levels);
        windows(out,"ref",reference,o);List<String> rows=new ArrayList<>();
        rows.add("frame,x,y,theta,status,iterations,valid_fraction,seconds");
        long total=System.nanoTime();
        for(String frameText:args[5].split(",")) {
            int frame=Integer.parseInt(frameText);String[] values=starts.get(frame+1).split(",");
            Transform start=new Transform(Double.parseDouble(values[0]),Double.parseDouble(values[1]),Double.parseDouble(values[2]));
            LogPlane[] moving=LogPlane.of(read(source.resolve("feature_"+frame+".f32")),width,height,1).pyramid(levels);
            windows(out,"f"+frame,moving,o);long clock=System.nanoTime();
            PairAligner.Fit fit=LongitudinalReferenceArea.align(reference,moving,start,o);
            rows.add(frame+","+fit.transform.dx+","+fit.transform.dy+","+fit.transform.theta+","+fit.status
                +","+fit.iterations+","+fit.validFraction+","+(System.nanoTime()-clock)/1e9);
        }
        Files.write(out.resolve("pairs.csv"),rows);
        Files.writeString(out.resolve("summary.json"),"{\"levels\":"+levels+",\"pairs\":"+(rows.size()-1)
            +",\"seconds\":"+(System.nanoTime()-total)/1e9+"}");
    }
}
