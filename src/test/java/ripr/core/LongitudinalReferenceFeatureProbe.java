package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Binary feature probe; comparison runs in Python against the immutable reference. */
public final class LongitudinalReferenceFeatureProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("source.tif landmark|emission output-directory");
        Path out = Paths.get(args[2]);
        Files.createDirectory(out);
        ImagePlus image = IJ.openImage(args[0]);
        if (image == null || image.getNChannels() != 1) throw new IllegalArgumentException("One channel required");
        try {
            for (int frame : new int[]{0, image.getStackSize() / 2, image.getStackSize() - 1}) {
                float[] pixels = (float[]) image.getStack().getProcessor(frame + 1).convertToFloatProcessor().getPixels();
                float[] result = args[1].equals("landmark")
                        ? LongitudinalReferenceFeatures.landmark(pixels, image.getWidth(), image.getHeight())
                        : LongitudinalReferenceFeatures.emission(pixels, image.getWidth(), image.getHeight());
                ByteBuffer binary = ByteBuffer.allocate(4 * result.length).order(ByteOrder.LITTLE_ENDIAN);
                for (float value : result) binary.putFloat(value);
                Files.write(out.resolve("feature_" + frame + ".f32"), binary.array());
            }
        } finally { image.close(); }
    }
}
