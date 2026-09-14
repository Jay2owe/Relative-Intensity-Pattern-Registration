/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ripr.core.FrameSource;
import ripr.core.Localisability;

/**
 * Presents an {@link ImagePlus} to the estimator as one 2-D plane per timepoint.
 *
 * <p>This is the whole of "which pixels drive the estimate". By the time a plane reaches
 * {@code ripr.core}, the channel, the slice and any projection have already been decided — which is
 * what lets the estimator stay free of every ImageJ class and be tested against synthetic frames.
 *
 * <p><b>Choosing the estimation channel matters more than it looks, and both obvious heuristics are
 * wrong.</b> On a photon-limited recording the channel with the most per-pixel contrast is usually the
 * noisiest, not the most structured. Measured on a bioluminescence test stack, the four channels had
 * frame-to-frame correlations of 0.063, 0.496, 0.992 and 0.995; the first had by far the largest mean
 * gradient and was pure shot noise, with consecutive frames essentially uncorrelated. Nothing can
 * register that — phase correlation returned 1264 px of nonsense on it.
 *
 * <p>Correlation is the better of the two, and it is still not the right one. It is near one for a
 * smooth featureless blob as well as for real structure, and ranking 24 real recordings by it chose a
 * saturated fluorescence channel on which two independent estimators then disagreed by 15.7 px per
 * step. <b>Rank by {@link #localisability} and warn below {@link Localisability#WARN_BELOW}</b>;
 * {@link #rankChannels} does both in one pass. {@link #frameCorrelation} is kept because a correlation
 * near zero is still the cleanest statement that a channel is pure noise, and because the survey
 * reports both.
 */
public final class StackFrames implements FrameSource {

    /** Passed as the slice to project across Z instead of taking one slice. */
    public static final int PROJECT_Z = 0;

    private final ImageStack stack;
    private final int width;
    private final int height;
    private final int frames;
    private final int channels;
    private final int slices;
    private final int channel;
    private final int slice;

    private StackFrames(ImagePlus imp, int channel, int slice) {
        this.stack = imp.getStack();
        this.width = imp.getWidth();
        this.height = imp.getHeight();
        int declaredChannels = Math.max(1, imp.getNChannels());
        int declaredSlices = Math.max(1, imp.getNSlices());
        int t = Math.max(1, imp.getNFrames());
        // REGRESSION GUARD: ImageJ reports a plain TIFF stack as C=1, Z=stack size, T=1.
        // This adapter's contract is a time-series adapter, so only an explicitly dimensional
        // hyperstack may treat that third axis as Z. Otherwise every ordinary stack became one frame.
        boolean plainTimeStack = !imp.isHyperStack() && declaredChannels == 1 && t == 1;
        this.channels = plainTimeStack ? 1 : declaredChannels;
        this.slices = plainTimeStack ? 1 : declaredSlices;
        this.frames = plainTimeStack ? stack.getSize() : t;
        this.channel = channel;
        this.slice = slice;
        if (channel < 1 || channel > channels) {
            throw new IllegalArgumentException("channel " + channel + " outside 1.." + channels);
        }
        if (slice != PROJECT_Z && (slice < 1 || slice > slices)) {
            throw new IllegalArgumentException("slice " + slice + " outside 1.." + slices);
        }
    }

    /** Estimate from one channel, projecting across Z if there is more than one slice. */
    public static StackFrames of(ImagePlus imp) {
        return new StackFrames(imp, 1, PROJECT_Z);
    }

    public static StackFrames of(ImagePlus imp, int channel, int slice) {
        return new StackFrames(imp, channel, slice);
    }

    @Override
    public int count() {
        return frames;
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
        if (slices == 1 || slice != PROJECT_Z) {
            int z = slices == 1 ? 1 : slice;
            return floats(index(frame, z));
        }
        // Maximum across Z. Maximum rather than mean because a mean dilutes a thin in-focus feature
        // with the out-of-focus slices either side of it, and the in-focus feature is the thing whose
        // displacement is being measured.
        float[] out = null;
        for (int z = 1; z <= slices; z++) {
            float[] p = floats(index(frame, z));
            if (out == null) {
                out = p;
            } else {
                for (int i = 0; i < out.length; i++) {
                    if (p[i] > out[i]) out[i] = p[i];
                }
            }
        }
        return out;
    }

    private int index(int frame, int z) {
        if (channels == 1 && slices == 1) return frame + 1;
        return stackIndex(channel, z, frame + 1);
    }

    private int stackIndex(int c, int z, int t) {
        return (t - 1) * channels * slices + (z - 1) * channels + c;
    }

    /**
     * A fresh float copy. Fresh because {@link FrameSource#plane} is called from worker threads, and
     * {@code ImageProcessor.toFloat} on a {@code FloatProcessor} would otherwise hand out the live
     * pixel array.
     */
    private float[] floats(int stackIndex) {
        ImageProcessor ip = stack.getProcessor(stackIndex);
        float[] p = (float[]) ip.toFloat(0, null).getPixels();
        return ip instanceof ij.process.FloatProcessor ? p.clone() : p;
    }

    /**
     * How much of this channel's frame-to-frame correlation is lost by displacing one frame a single
     * pixel. See {@link Localisability} — this is the number to rank channels by.
     */
    public static double localisability(ImagePlus imp, int channel) {
        return Localisability.of(new StackFrames(imp, channel, PROJECT_Z));
    }

    /** One channel's registrability, as {@link #rankChannels} reports it. */
    public static final class ChannelQuality implements Comparable<ChannelQuality> {
        /** One-based, as ImageJ numbers channels. */
        public final int channel;
        /** See {@link Localisability}. Larger is better; the ranking key. */
        public final double localisability;
        /** See {@link #frameCorrelation}. Reported alongside, never ranked on. */
        public final double frameCorrelation;

        ChannelQuality(int channel, double localisability, double frameCorrelation) {
            this.channel = channel;
            this.localisability = localisability;
            this.frameCorrelation = frameCorrelation;
        }

        /** True when this channel is below {@link Localisability#WARN_BELOW}. */
        public boolean poor() {
            return Localisability.poor(localisability);
        }

        /** Best first. NaN sorts last, so an undefined channel is never chosen over a measured one. */
        @Override
        public int compareTo(ChannelQuality o) {
            boolean an = Double.isNaN(localisability);
            boolean bn = Double.isNaN(o.localisability);
            if (an || bn) return an == bn ? Integer.compare(channel, o.channel) : (an ? 1 : -1);
            int c = Double.compare(o.localisability, localisability);
            return c != 0 ? c : Integer.compare(channel, o.channel);
        }

        @Override
        public String toString() {
            return String.format("channel %d: localisability %.4f, frame correlation %.4f%s",
                    channel, localisability, frameCorrelation, poor() ? "  (POOR)" : "");
        }
    }

    /**
     * Every channel scored and sorted best first, so a caller or a dialog can pick the estimation
     * channel and warn about it without knowing how either number is defined.
     *
     * <p>Costs three correlations per frame pair per channel and reads every plane once per channel,
     * which on a Z-stack means the projection is recomputed — acceptable for a pre-run check, and the
     * reason this is a separate call rather than something {@link ripr.core.Registration} does on
     * every run.
     */
    public static ChannelQuality[] rankChannels(ImagePlus imp) {
        int n = Math.max(1, imp.getNChannels());
        ChannelQuality[] out = new ChannelQuality[n];
        for (int c = 1; c <= n; c++) {
            out[c - 1] = new ChannelQuality(c, localisability(imp, c), frameCorrelation(imp, c));
        }
        java.util.Arrays.sort(out);
        return out;
    }

    /**
     * Pearson correlation between consecutive frames of a channel, averaged over the recording.
     *
     * <p>Near 1 means persistent structure; near 0 means each frame is independent noise and no method
     * will work. <b>It is not sufficient on its own</b> — it is also near 1 for a smooth featureless
     * blob, which is equally unregistrable. Rank on {@link #localisability} and read this as the
     * separate statement that the channel is or is not pure noise.
     */
    public static double frameCorrelation(ImagePlus imp, int channel) {
        StackFrames f = new StackFrames(imp, channel, PROJECT_Z);
        int n = f.count();
        if (n < 2) return Double.NaN;
        double total = 0;
        int pairs = 0;
        float[] prev = f.plane(0);
        for (int t = 1; t < n; t++) {
            float[] cur = f.plane(t);
            double r = Localisability.correlation(prev, cur);
            if (!Double.isNaN(r)) {
                total += r;
                pairs++;
            }
            prev = cur;
        }
        return pairs > 0 ? total / pairs : Double.NaN;
    }
}
