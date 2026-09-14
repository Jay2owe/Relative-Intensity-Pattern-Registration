/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.isolated;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Real ImageJ TIFF decoding without opening any window. */
public final class ReviewTiffReadProbe {
    public static void main(String[] args) throws Exception {
        ImagePlus image=IJ.openImage(args[0]);
        if(image==null)throw new AssertionError("ImageJ could not open the TIFF");
        try {
            int expected=Integer.parseInt(args[1]);
            if(image.getStackSize()!=expected || image.getBitDepth()!=8)
                throw new AssertionError("Wrong ImageJ frame count or pixel type");
            List<String> rows=new ArrayList<>();rows.add("frame,sha256");
            for(int i=1;i<=expected;i++) {
                StringBuilder hex=new StringBuilder();
                byte[] digest=MessageDigest.getInstance("SHA-256").digest((byte[])image.getStack().getPixels(i));
                for(byte value:digest)hex.append(String.format("%02x",value&255));
                rows.add(i+","+hex);
            }
            Files.write(Path.of(args[2]),rows);
        } finally {image.close();}
    }
}
