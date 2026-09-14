package ripr;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternResult;
import ripr.api.SelectionMode;
import ripr.core.LongitudinalRegistration;
import ripr.core.Transform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Synthetic-only exact comparison over size, brightness, recording length and image routes. */
public final class LongitudinalPerturbationGate {
    public static void main(String[] args) throws Exception {
        StringBuilder rows=new StringBuilder("case,frame,dx_bits,dy_bits,theta_bits,pixels_sha256,decisions\n");
        for(boolean transmitted:new boolean[]{false,true}) for(int variant=0;variant<6;variant++) {
            int size=variant==1?48:variant==2?96:64;
            int count=variant==5?8:12;
            double gain=variant==3?0.0001:variant==4?27:1;
            double offset=variant==4?400:0;
            ImageStack stack=new ImageStack(size,size);
            for(int frame=0;frame<count;frame++) {
                float[] pixels=new float[size*size];
                double dx=frame>=count-2?5.0*size/64:0;
                double dy=frame>=count-2?-3.0*size/64:0;
                for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
                    double u=(x-dx)*64/size,v=(y-dy)*64/size;
                    double signal=10+120*Math.exp(-((u-18)*(u-18)+(v-23)*(v-23))/70)
                            +80*Math.exp(-((u-47)*(u-47)+(v-43)*(v-43))/45)
                            +8*Math.sin(.31*u+.17*v);
                    if(!transmitted && frame>=3 && frame<=4 && u>35 && u<58 && v>12 && v<34)
                        signal+=300;
                    pixels[y*size+x]=(float)(gain*(transmitted?220-signal:signal)+offset);
                }
                stack.addSlice(new FloatProcessor(size,size,pixels));
            }
            ImagePlus input=new ImagePlus("synthetic",stack);
            RelativeIntensityPatternParameters params=RelativeIntensityPatternParameters.builder()
                    .recommendation(transmitted?ImageType.PHASE_CONTRAST:ImageType.SPARSE_LOW_LIGHT_FLUORESCENCE,
                            MotionType.INTERMITTENT_JUMPS)
                    .selectionMode(SelectionMode.LONGITUDINAL_ACCURACY).threads(1).crop(false).build();
            RelativeIntensityPatternResult result=null;
            try {
                result=RelativeIntensityPatternRegistration.register(input,params);
                LongitudinalRegistration.Diagnostics d=result.longitudinalDiagnostics();
                String decisions=(d.route+"/"+d.brightReferenceFrame+"/"+d.dimReferenceFrame+"/"
                        +Arrays.toString(d.weakFrames)+"/"+Arrays.toString(d.persistentJumpFrames)+"/"
                        +Arrays.toString(d.rigidJumpFrames)+"/"+d.endpointJumpFrame).replace(',',';');
                for(int frame=0;frame<count;frame++) {
                    Transform t=result.registration().cumulative[frame];
                    rows.append(transmitted?"transmitted":"emission").append('_').append(variant).append(',')
                            .append(frame+1).append(',').append(Long.toHexString(Double.doubleToRawLongBits(t.dx)))
                            .append(',').append(Long.toHexString(Double.doubleToRawLongBits(t.dy)))
                            .append(',').append(Long.toHexString(Double.doubleToRawLongBits(t.theta)))
                            .append(',').append(LongitudinalExactOutputGate.pixelHash(result.correctedImage().getStack().getPixels(frame+1)))
                            .append(',').append(decisions).append('\n');
                }
                System.out.println("completed "+(transmitted?"transmitted":"emission")+" "+variant);
            } finally { if(result!=null)result.close();input.close(); }
        }
        Files.write(Paths.get(args[0]),rows.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
}
