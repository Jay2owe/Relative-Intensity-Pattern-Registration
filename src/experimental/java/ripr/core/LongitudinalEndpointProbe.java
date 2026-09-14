/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class LongitudinalEndpointProbe {
    public static void main(String[] args) throws Exception {
        Path fixture=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectory(out);
        List<String> rows=new ArrayList<>();rows.add("case,frame,status,x,y,area,mask_sha256,seconds");
        for(String line:Files.readAllLines(fixture.resolve("cases.tsv"))) {
            String[] fields=line.split("\t",-1);ImagePlus image=IJ.openImage(fields[1]);
            if(image==null || image.getNChannels()!=1)throw new IllegalArgumentException("One channel required");
            try {
                int w=image.getWidth(),h=image.getHeight();
                for(int frame=0;frame<image.getStackSize();frame++) {
                    float[] values=(float[])image.getStack().getProcessor(frame+1).convertToFloatProcessor().getPixels();
                    long start=System.nanoTime();String result;
                    try {
                        LongitudinalReferenceEndpoint.Measurement m=LongitudinalReferenceEndpoint.measure(values,w,h);
                        byte[] mask=new byte[m.mask.length];for(int i=0;i<mask.length;i++)mask[i]=m.mask[i]?(byte)1:0;
                        result="ok,"+m.x+","+m.y+","+m.area+","+hash(mask);
                    }catch(IllegalArgumentException missing) {result="no_tissue,,,,";}
                    rows.add(fields[0]+","+(frame+1)+","+result+","+(System.nanoTime()-start)/1e9);
                }
            } finally {image.close();}
            Files.write(out.resolve("measurements.csv"),rows);
        }
        boolean[] source=new boolean[13*11];
        for(int y=0;y<11;y++)for(int x=0;x<13;x++)source[y*13+x]=(x+y)%4!=0;
        source[0]=source[1]=source[13]=true;
        for(int size=1;size<=10;size++)for(boolean dilation:new boolean[]{false,true}) {
            boolean[] mask=LongitudinalReferenceEndpoint.box(source,13,11,size,dilation);
            byte[] data=new byte[mask.length];for(int i=0;i<data.length;i++)data[i]=mask[i]?(byte)1:0;
            Files.write(out.resolve((dilation?"dilate_":"erode_")+size+".u8"),data);
        }
        List<String> decisions=new ArrayList<>();decisions.add("fixture,boundary,frame,x,y,theta");
        for(String line:Files.readAllLines(fixture.resolve("decisions.tsv"))) {
            String[] f=line.split("\t");int n=Integer.parseInt(f[1]),w=Integer.parseInt(f[2]),h=Integer.parseInt(f[3]);
            Transform[] transforms=new Transform[n];LongitudinalReferenceEndpoint.Measurement[] m=new LongitudinalReferenceEndpoint.Measurement[n];
            List<String> data=Files.readAllLines(fixture.resolve(f[0]+".csv"));
            for(int i=0;i<n;i++) {
                String[] v=data.get(i+1).split(",");double[] a=new double[6];for(int j=0;j<6;j++)a[j]=Double.parseDouble(v[j]);
                m[i]=new LongitudinalReferenceEndpoint.Measurement(a[0],a[1],a[2],null,0,0);
                transforms[i]=new Transform(a[3],a[4],a[5]);
            }
            LongitudinalReferenceEndpoint.Result r=LongitudinalReferenceEndpoint.repairMeasured(m,transforms,w,h);
            for(int i=0;i<n;i++)decisions.add(f[0]+","+r.boundary+","+i+","+r.transforms[i].dx+","+r.transforms[i].dy+","+r.transforms[i].theta);
        }
        Files.write(out.resolve("decisions.csv"),decisions);
    }
    private static String hash(byte[] data) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder out=new StringBuilder();
        for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();
    }
}
