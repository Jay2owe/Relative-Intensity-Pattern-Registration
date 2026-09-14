package ripr;

import ij.IJ;
import ij.ImagePlus;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternResult;
import ripr.api.SelectionMode;
import ripr.core.LongitudinalRegistration;
import ripr.core.PairScheduler;
import ripr.core.Transform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Headless exact-output gate. Timings exclude input loading and evidence hashing. */
public final class LongitudinalExactOutputGate {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "source.tif IMAGE_TYPE new-output-directory threads");
        Path source = Paths.get(args[0]).toAbsolutePath();
        Path out = Paths.get(args[2]).toAbsolutePath();
        Files.createDirectory(out);
        ImagePlus input = IJ.openImage(source.toString());
        if (input == null) throw new IllegalArgumentException("Cannot open " + source);
        if (input.getNChannels() != 1) throw new IllegalArgumentException("Single channel required");
        int threads = Integer.parseInt(args[3]);
        RelativeIntensityPatternParameters parameters = RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.from(args[1]), MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.LONGITUDINAL_ACCURACY)
                .threads(threads).crop(false).build();
        long started = System.nanoTime();
        PairScheduler.Progress progress = new PairScheduler.Progress() {
            String last = "";
            public void begin(int total, int workers) { }
            public void taskStarted(int index, int total) { }
            public void update(int done, int total) { }
            public synchronized void phase(String name, int done, int total) {
                if (!name.equals(last)) {
                    last = name;
                    System.out.printf(Locale.ROOT, "%.3f %s%n", (System.nanoTime()-started)/1e9, name);
                }
            }
        };
        RelativeIntensityPatternResult result = null;
        try {
            result = RelativeIntensityPatternRegistration.register(input, parameters, progress,
                    PairScheduler.Cancellation.NEVER);
            double seconds = (System.nanoTime() - started) / 1e9;
            StringBuilder transforms = new StringBuilder("frame,dx_bits,dy_bits,theta_bits\n");
            Transform[] values = result.registration().cumulative;
            for (int i = 0; i < values.length; i++) {
                Transform t = values[i];
                if (!Double.isFinite(t.dx) || !Double.isFinite(t.dy) || !Double.isFinite(t.theta))
                    throw new IllegalStateException("Nonfinite transform at " + i);
                transforms.append(i+1).append(',').append(Long.toHexString(Double.doubleToRawLongBits(t.dx)))
                        .append(',').append(Long.toHexString(Double.doubleToRawLongBits(t.dy)))
                        .append(',').append(Long.toHexString(Double.doubleToRawLongBits(t.theta))).append('\n');
            }
            Files.write(out.resolve("transforms_bits.csv"), transforms.toString().getBytes(StandardCharsets.UTF_8));
            LongitudinalRegistration.Diagnostics d = result.longitudinalDiagnostics();
            String decisions = d.route + "\nbright=" + d.brightReferenceFrame + "\ndim=" + d.dimReferenceFrame
                    + "\nweak=" + Arrays.toString(d.weakFrames)
                    + "\npersistent=" + Arrays.toString(d.persistentJumpFrames)
                    + "\nrigid=" + Arrays.toString(d.rigidJumpFrames)
                    + "\nendpoint=" + d.endpointJumpFrame + "\n";
            Files.write(out.resolve("decisions.txt"), decisions.getBytes(StandardCharsets.UTF_8));
            ImagePlus corrected = result.correctedImage();
            StringBuilder pixels = new StringBuilder("frame,pixels_sha256\n");
            for (int i = 1; i <= corrected.getStackSize(); i++)
                pixels.append(i).append(',').append(pixelHash(corrected.getStack().getPixels(i))).append('\n');
            Files.write(out.resolve("pixels.csv"), pixels.toString().getBytes(StandardCharsets.UTF_8));
            String summary = String.format(Locale.ROOT,
                    "{\"frames\":%d,\"width\":%d,\"height\":%d,\"bit_depth\":%d,"
                    + "\"channels\":%d,\"threads_requested\":%d,\"processors\":%d,"
                    + "\"motion_requested\":\"INTERMITTENT_JUMPS\",\"artificial_motion\":false,"
                    + "\"elapsed_seconds\":%.9f,\"java_version\":\"%s\"}\n",
                    values.length,corrected.getWidth(),corrected.getHeight(),corrected.getBitDepth(),
                    corrected.getNChannels(),threads,Runtime.getRuntime().availableProcessors(),seconds,
                    System.getProperty("java.version"));
            Files.write(out.resolve("summary.json"), summary.getBytes(StandardCharsets.UTF_8));
            System.out.println(summary);
        } finally {
            if (result != null) result.close();
            input.close();
        }
    }

    static String pixelHash(Object pixels) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (pixels instanceof byte[]) digest.update((byte[]) pixels);
        else {
            ByteBuffer buffer = ByteBuffer.allocate(65536).order(ByteOrder.BIG_ENDIAN);
            int length = java.lang.reflect.Array.getLength(pixels);
            for (int i = 0; i < length; i++) {
                if (buffer.remaining() < 4) { digest.update(buffer.array(),0,buffer.position()); buffer.clear(); }
                if (pixels instanceof short[]) buffer.putShort(((short[])pixels)[i]);
                else if (pixels instanceof float[]) buffer.putInt(Float.floatToRawIntBits(((float[])pixels)[i]));
                else if (pixels instanceof int[]) buffer.putInt(((int[])pixels)[i]);
                else throw new IllegalArgumentException("Unsupported pixels");
            }
            digest.update(buffer.array(),0,buffer.position());
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",b & 255));
        return hex.toString();
    }
}
