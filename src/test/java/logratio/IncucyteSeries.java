/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import logratio.core.FrameSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an IncuCyte export that stores one RGB TIFF per timepoint. <b>Test scope only.</b>
 *
 * <p>Files are named {@code VID52_B6_1_08d22h30m.tif}: video, well, position, then elapsed time. One
 * well is one recording, and the elapsed time in the name is the only reliable ordering — sorting the
 * filenames as text puts {@code 10d} before {@code 2d}.
 *
 * <p><b>The export is a composited render, not three raw channels, and the composition is invertible.</b>
 * IncuCyte lays the phase-contrast image down as a grey underlay and adds fluorescence on top, so blue
 * carries phase alone while red and green each carry phase plus their fluorophore. Measured on both
 * experiment sets, <i>zero</i> pixels have red or green below blue, which is what an additive overlay
 * predicts and what a genuine three-channel acquisition would not obey. Subtracting blue therefore
 * recovers each fluorescence channel exactly.
 *
 * <p>That is worth more than a convenience. It yields phase contrast, green fluorescence and red
 * fluorescence <b>of the same field with identical motion</b>, so the criterion can be compared across
 * three imaging modalities with the thing being estimated held constant — which no pair of separate
 * recordings can offer.
 *
 * <p>Reading is deliberately paired with binning. These exports live in Dropbox as online-only
 * placeholders, so every frame opened is a download, and the survey needs a coarse trace from many
 * recordings rather than a precise one from a few.
 */
final class IncucyteSeries {

    private static final Pattern NAME =
            Pattern.compile("^(.+?)_(\\d+)d(\\d+)h(\\d+)m$", Pattern.CASE_INSENSITIVE);

    /** Which plane to pull out of the composite. */
    enum Plane {
        /** The grey underlay: phase contrast, alone. */
        PHASE,
        /** Green minus the underlay: green fluorescence, alone. */
        GREEN,
        /** Red minus the underlay: red fluorescence, alone. */
        RED
    }

    final String key;
    final String set;
    final List<Path> files = new ArrayList<>();
    final List<Integer> minutes = new ArrayList<>();

    private IncucyteSeries(String key, String set) {
        this.key = key;
        this.set = set;
    }

    int size() {
        return files.size();
    }

    /** Elapsed hours from the first frame present to frame {@code i}. */
    double hours(int i) {
        return (minutes.get(i) - minutes.get(0)) / 60.0;
    }

    /**
     * Elapsed hours since the recording began, as encoded in the filename.
     *
     * <p>Distinct from {@link #hours} on purpose. Frames are staged locally in windows before being
     * surveyed, so the first file present is usually not the first frame of the experiment, and only
     * the name knows where in the recording a window actually sits.
     */
    double absoluteHours(int i) {
        return minutes.get(i) / 60.0;
    }

    /** Median interval between consecutive frames, in minutes. */
    double intervalMinutes() {
        if (minutes.size() < 2) return Double.NaN;
        List<Integer> d = new ArrayList<>();
        for (int i = 1; i < minutes.size(); i++) d.add(minutes.get(i) - minutes.get(i - 1));
        d.sort(null);
        return d.get(d.size() / 2);
    }

    /** Every recording in a directory, ordered by name, each ordered by elapsed time. */
    static List<IncucyteSeries> discover(Path dir) throws IOException {
        Map<String, IncucyteSeries> byKey = new LinkedHashMap<>();
        String set = dir.getFileName().toString();
        List<Path> tifs = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".tif") || n.endsWith(".tiff");
            }).forEach(tifs::add);
        }
        List<Object[]> parsed = new ArrayList<>();
        for (Path p : tifs) {
            String base = p.getFileName().toString().replaceFirst("\\.[Tt][Ii][Ff][Ff]?$", "");
            Matcher m = NAME.matcher(base);
            if (!m.matches()) continue;
            int min = Integer.parseInt(m.group(2)) * 1440
                    + Integer.parseInt(m.group(3)) * 60
                    + Integer.parseInt(m.group(4));
            parsed.add(new Object[]{m.group(1), min, p});
        }
        parsed.sort(Comparator.<Object[], String>comparing(a -> (String) a[0])
                .thenComparingInt(a -> (Integer) a[1]));
        for (Object[] row : parsed) {
            IncucyteSeries s = byKey.computeIfAbsent((String) row[0], k -> new IncucyteSeries(k, set));
            s.minutes.add((Integer) row[1]);
            s.files.add((Path) row[2]);
        }
        return new ArrayList<>(byKey.values());
    }

    /** One loaded window: the three planes, already binned, plus the geometry they are in. */
    static final class Window {
        final int width;
        final int height;
        final int bin;
        final int from;
        final float[][][] planes = new float[Plane.values().length][][];

        Window(int width, int height, int bin, int from) {
            this.width = width;
            this.height = height;
            this.bin = bin;
            this.from = from;
        }

        float[][] plane(Plane p) {
            return planes[p.ordinal()];
        }

        /** Present one plane to the estimator. Displacements come back in binned pixels. */
        FrameSource source(Plane p) {
            float[][] data = plane(p);
            return new FrameSource() {
                @Override
                public int count() {
                    return data.length;
                }

                @Override
                public int width() {
                    return width;
                }

                @Override
                public int height() {
                    return height;
                }

                @Override
                public float[] plane(int frame) {
                    return data[frame];
                }
            };
        }
    }

    /**
     * Load {@code count} consecutive frames starting at index {@code from}, binned by {@code bin}.
     *
     * <p>All three planes are extracted from the one file read, because the cost here is the download
     * and not the arithmetic.
     */
    Window load(int from, int count, int bin) throws IOException {
        int last = Math.min(files.size(), from + count);
        int n = last - from;
        if (n < 2) throw new IOException(key + ": only " + n + " frames from index " + from);
        Window win = null;
        for (int t = 0; t < n; t++) {
            Path p = files.get(from + t);
            ImagePlus imp = IJ.openImage(p.toString());
            if (imp == null) throw new IOException("could not open " + p);
            if (!(imp.getProcessor() instanceof ColorProcessor)) {
                throw new IOException(p.getFileName() + " is not an RGB composite; got "
                        + imp.getProcessor().getClass().getSimpleName());
            }
            ColorProcessor cp = (ColorProcessor) imp.getProcessor();
            int w = cp.getWidth();
            int h = cp.getHeight();
            if (win == null) {
                win = new Window(w / bin, h / bin, bin, from);
                for (Plane pl : Plane.values()) win.planes[pl.ordinal()] = new float[n][];
            } else if (w / bin != win.width || h / bin != win.height) {
                throw new IOException(p.getFileName() + " is " + w + "x" + h
                        + ", expected the size of the first frame");
            }
            float[] r = channel(cp, 1, w, h);
            float[] g = channel(cp, 2, w, h);
            float[] b = channel(cp, 3, w, h);
            for (int i = 0; i < r.length; i++) {
                r[i] -= b[i];
                g[i] -= b[i];
            }
            win.planes[Plane.PHASE.ordinal()][t] = bin(b, w, h, bin);
            win.planes[Plane.GREEN.ordinal()][t] = bin(g, w, h, bin);
            win.planes[Plane.RED.ordinal()][t] = bin(r, w, h, bin);
            imp.close();
        }
        return win;
    }

    private static float[] channel(ColorProcessor cp, int which, int w, int h) {
        ByteProcessor bp = new ByteProcessor(w, h);
        cp.getChannel(which, bp);
        byte[] px = (byte[]) bp.getPixels();
        float[] out = new float[w * h];
        for (int i = 0; i < out.length; i++) out[i] = px[i] & 0xff;
        return out;
    }

    /**
     * Mean over each {@code bin x bin} block.
     *
     * <p>Averaging, not decimation. Decimation aliases the shot noise straight into the structure the
     * estimator is meant to lock onto, and these frames are mostly noise outside the tissue.
     */
    static float[] bin(float[] src, int w, int h, int bin) {
        if (bin <= 1) return src;
        int bw = w / bin;
        int bh = h / bin;
        float[] out = new float[bw * bh];
        double norm = 1.0 / (bin * bin);
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                double s = 0;
                for (int j = 0; j < bin; j++) {
                    int row = (y * bin + j) * w + x * bin;
                    for (int i = 0; i < bin; i++) s += src[row + i];
                }
                out[y * bw + x] = (float) (s * norm);
            }
        }
        return out;
    }
}
