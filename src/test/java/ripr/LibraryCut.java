/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Cuts one entry of the demonstration library out of a real recording. <b>Test scope only.</b>
 *
 * <p>An entry is a window of consecutive frames, optionally cropped, written as a three-channel 8-bit
 * hyperstack: channel 1 phase contrast, channel 2 green fluorescence, channel 3 red fluorescence. The
 * source is an IncuCyte RGB composite, and the decomposition back to three channels is exact — see
 * {@link IncucyteSeries}. Writing it out decomposed rather than as RGB means the same motion can be
 * demonstrated on three imaging modalities, and that the plugin's own channel selection applies.
 *
 * <p><b>Cropping does not compromise the entry.</b> The motion here is a whole-frame translation, so a
 * spatial crop contains exactly the same displacement as the full frame. It is a faithful demonstration
 * at a fraction of the size — which matters, because a full frame is 255 MB for a 48-frame window and the
 * library needs a dozen entries in a synced folder. The crop must still be comfortably larger than the
 * motion, or the content leaves the window; {@code motion_survey.csv} gives the number to check against.
 *
 * <p>Every entry carries {@code entry.properties} recording the original path, series, frame range,
 * elapsed hours and crop, so it can be re-cut from the archive and so a reader can see it is real data
 * rather than something generated.
 *
 * <pre>
 *   java -cp ... ripr.LibraryCut &lt;staged dir&gt; &lt;series&gt; &lt;from&gt; &lt;count&gt; &lt;library dir&gt; \
 *        &lt;entry&gt; &lt;label&gt; &lt;severity&gt; &lt;origin dir&gt; &lt;roi&gt;
 *
 *   roi:  full             the whole frame
 *         auto:768x768     that size, centred on the explant
 *         160,96:768x768   that size at that offset
 * </pre>
 */
public final class LibraryCut {

    /** Names written into the stack, so a reader of the TIFF alone knows what each channel is. */
    private static final String[] CHANNEL = {"phase", "green", "red"};
    /** Pixels below this quantile of the phase plane are taken to be tissue when centring a crop. */
    private static final double DARK_PERCENTILE = 0.15;

    public static void main(String[] args) throws IOException {
        Path staged = Paths.get(args[0]);
        String series = args[1];
        int from = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        Path library = Paths.get(args[4]);
        String entry = args[5];
        String label = args[6];
        String severity = args[7];
        String origin = args[8];
        String roiSpec = args.length > 9 ? args[9] : "full";

        IncucyteSeries found = null;
        for (IncucyteSeries s : IncucyteSeries.discover(staged)) {
            if (s.key.equals(series)) found = s;
        }
        if (found == null) throw new IOException("no series " + series + " under " + staged);
        int last = Math.min(found.size(), from + count);
        if (last - from < 2) throw new IOException(series + ": only " + (last - from) + " frames");

        Path dir = library.resolve(entry);
        Files.createDirectories(dir);

        // The crop is decided from the first frame before anything is read in bulk, so every frame of the
        // entry is cut from the same box.
        ImagePlus first = IJ.openImage(found.files.get(from).toString());
        if (first == null) throw new IOException("could not open " + found.files.get(from));
        int fullW = first.getWidth();
        int fullH = first.getHeight();
        int[] box = resolveRoi(roiSpec, (ColorProcessor) first.getProcessor(), fullW, fullH);
        check(box, fullW, fullH);
        first.close();

        ImageStack out = null;
        for (int t = from; t < last; t++) {
            ImagePlus imp = IJ.openImage(found.files.get(t).toString());
            if (imp == null) throw new IOException("could not open " + found.files.get(t));
            ColorProcessor cp = (ColorProcessor) imp.getProcessor();
            if (cp.getWidth() != fullW || cp.getHeight() != fullH) {
                throw new IOException(found.files.get(t).getFileName() + " is "
                        + cp.getWidth() + "x" + cp.getHeight() + ", expected " + fullW + "x" + fullH);
            }
            if (out == null) out = new ImageStack(box[2], box[3]);
            byte[][] planes = decompose(cp, box);
            for (int c = 0; c < 3; c++) {
                out.addSlice(CHANNEL[c] + " t=" + (t - from + 1),
                        new ByteProcessor(box[2], box[3], planes[c], null));
            }
            imp.close();
        }

        int frames = last - from;
        ImagePlus result = new ImagePlus(entry, out);
        result.setDimensions(3, 1, frames);
        result.setOpenAsHyperStack(true);
        // Greyscale, not composite colour. A three-channel stack otherwise opens with ImageJ's default
        // channel colours, which renders the phase-contrast channel in red — false colour on data whose
        // whole purpose is to be measured, and actively misleading in a figure. Grey for all three says
        // what these are: three independent intensity images of the same field.
        ij.CompositeImage composite = new ij.CompositeImage(result, ij.CompositeImage.GRAYSCALE);
        IJ.saveAsTiff(composite, dir.resolve("original.tif").toString());

        StringBuilder p = new StringBuilder();
        p.append("# One entry of the Relative-Intensity Pattern Registration demonstration library.\n")
         .append("# Real data. Nothing here is synthetic and no motion has been injected.\n")
         .append("# Channels: 1 phase contrast, 2 green fluorescence, 3 red fluorescence. The source is\n")
         .append("# an IncuCyte RGB composite in which blue is a pure phase underlay and red and green\n")
         .append("# each carry phase plus their fluorophore; subtracting blue recovers each exactly.\n")
         .append("entry=").append(entry).append('\n')
         .append("motion=").append(label).append('\n')
         .append("severity=").append(severity).append('\n')
         .append("origin_dir=").append(origin).append('\n')
         .append("series=").append(series).append('\n')
         .append("set=").append(found.set).append('\n')
         .append("from_index=").append(from).append('\n')
         .append("frames=").append(frames).append('\n')
         .append("from_hours=").append(String.format("%.2f", found.absoluteHours(from))).append('\n')
         .append("to_hours=").append(String.format("%.2f", found.absoluteHours(last - 1))).append('\n')
         .append("interval_minutes=").append(String.format("%.0f", found.intervalMinutes())).append('\n')
         .append("first_file=").append(found.files.get(from).getFileName()).append('\n')
         .append("last_file=").append(found.files.get(last - 1).getFileName()).append('\n')
         .append("source_width=").append(fullW).append('\n')
         .append("source_height=").append(fullH).append('\n')
         .append("roi_x=").append(box[0]).append('\n')
         .append("roi_y=").append(box[1]).append('\n')
         .append("roi_width=").append(box[2]).append('\n')
         .append("roi_height=").append(box[3]).append('\n');
        Files.write(dir.resolve("entry.properties"), p.toString().getBytes(StandardCharsets.UTF_8));

        // Consumed by ValidationRun, which registers every directory holding an original.tif.
        Files.write(dir.resolve("dataset.properties"),
                ("# Phase contrast is channel 1 and is normally the most localisable of the three.\n"
                        + "channel=1\n").getBytes(StandardCharsets.UTF_8));

        long mb = Files.size(dir.resolve("original.tif")) / (1024 * 1024);
        System.out.printf("%-28s %s  %dx%d  %d frames x 3 ch  %d MB  (%s, %s)%n",
                entry, series, box[2], box[3], frames, mb, label, severity);
    }

    /**
     * Work out the crop box from a spec.
     *
     * <p>{@code auto} centres the box on the explant, located as the centroid of the darkest pixels of
     * the phase-contrast plane. Phase contrast renders the tissue darker than the surrounding medium, so
     * a low percentile picks it out; the plane is binned heavily first so the decision is made on
     * structure rather than on individual noisy pixels. A dozen entries is enough that hand-picking
     * every box invites a transcription error, and the box is recorded either way.
     */
    private static int[] resolveRoi(String spec, ColorProcessor cp, int w, int h) throws IOException {
        if ("full".equalsIgnoreCase(spec)) return new int[]{0, 0, w, h};
        int colon = spec.indexOf(':');
        if (colon < 0) throw new IOException("bad roi spec: " + spec);
        String[] size = spec.substring(colon + 1).split("x");
        int bw = Math.min(w, Integer.parseInt(size[0]));
        int bh = Math.min(h, Integer.parseInt(size[1]));
        String where = spec.substring(0, colon);
        if (!"auto".equalsIgnoreCase(where)) {
            String[] at = where.split(",");
            return new int[]{Integer.parseInt(at[0]), Integer.parseInt(at[1]), bw, bh};
        }

        ByteProcessor blue = new ByteProcessor(w, h);
        cp.getChannel(3, blue);
        byte[] px = (byte[]) blue.getPixels();
        float[] plane = new float[w * h];
        for (int i = 0; i < plane.length; i++) plane[i] = px[i] & 0xff;
        int bin = 8;
        float[] small = IncucyteSeries.bin(plane, w, h, bin);
        int sw = w / bin;
        float[] sorted = small.clone();
        java.util.Arrays.sort(sorted);
        float threshold = sorted[(int) (DARK_PERCENTILE * (sorted.length - 1))];
        double sx = 0;
        double sy = 0;
        double weight = 0;
        for (int i = 0; i < small.length; i++) {
            if (small[i] > threshold) continue;
            double v = threshold - small[i];
            sx += v * (i % sw);
            sy += v * (i / sw);
            weight += v;
        }
        int cx = weight > 0 ? (int) Math.round(bin * sx / weight) : w / 2;
        int cy = weight > 0 ? (int) Math.round(bin * sy / weight) : h / 2;
        int x = Math.max(0, Math.min(w - bw, cx - bw / 2));
        int y = Math.max(0, Math.min(h - bh, cy - bh / 2));
        return new int[]{x, y, bw, bh};
    }

    private static void check(int[] box, int w, int h) throws IOException {
        if (box[0] < 0 || box[1] < 0 || box[2] < 8 || box[3] < 8
                || box[0] + box[2] > w || box[1] + box[3] > h) {
            throw new IOException("crop " + box[0] + "," + box[1] + " " + box[2] + "x" + box[3]
                    + " does not fit inside " + w + "x" + h);
        }
    }

    /** Phase, green and red as three byte planes, cropped. Exact: red and green never fall below blue. */
    private static byte[][] decompose(ColorProcessor cp, int[] box) {
        int w = cp.getWidth();
        byte[][] full = new byte[3][];
        for (int c = 0; c < 3; c++) {
            ByteProcessor bp = new ByteProcessor(w, cp.getHeight());
            cp.getChannel(c + 1, bp);                       // 1 red, 2 green, 3 blue
            full[c] = (byte[]) bp.getPixels();
        }
        byte[][] out = new byte[3][box[2] * box[3]];
        for (int y = 0; y < box[3]; y++) {
            int src = (box[1] + y) * w + box[0];
            int dst = y * box[2];
            for (int x = 0; x < box[2]; x++) {
                int red = full[0][src + x] & 0xff;
                int green = full[1][src + x] & 0xff;
                int blue = full[2][src + x] & 0xff;
                out[0][dst + x] = (byte) blue;
                out[1][dst + x] = (byte) Math.max(0, green - blue);
                out[2][dst + x] = (byte) Math.max(0, red - blue);
            }
        }
        return out;
    }

    /** Kept for callers that want the series list without cutting anything. */
    static List<IncucyteSeries> series(Path staged) throws IOException {
        return IncucyteSeries.discover(staged);
    }
}
