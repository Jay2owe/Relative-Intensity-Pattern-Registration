/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import logratio.core.Transform;
import logratio.core.Warper;

/**
 * Applies one transform per timepoint to every channel and slice of an {@link ImagePlus}.
 *
 * <p>The transform is estimated from one 2-D plane per timepoint and applied to all of them, which is
 * the correct behaviour: the stage moved, not the channel. Bit depth, calibration and hyperstack
 * dimensions are preserved.
 *
 * <p><b>A whole-pixel shift takes a block-copy path and is bit-exact.</b> Not a micro-optimisation — it
 * means a registered 16-bit stack still holds exactly the original counts, so a per-pixel intensity
 * trajectory measured afterwards is measuring the sample rather than the interpolator. Interpolation
 * smooths by an amount that varies frame to frame with the fractional part of the shift, and that
 * variation looks exactly like signal to any temporal statistic.
 */
public final class StackWarper {

    private StackWarper() {
    }

    /**
     * @param cumulative one transform per timepoint, from {@code Registration.Result.cumulative}
     * @param crop       trim to the region real in every frame; see {@link Warper#validMargin}
     */
    public static ImagePlus apply(ImagePlus imp, Transform[] cumulative,
                                  Warper.Interpolation interpolation, boolean crop) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        int channels = Math.max(1, imp.getNChannels());
        int slices = Math.max(1, imp.getNSlices());
        int frames = Math.max(1, imp.getNFrames());
        // A plain ImageJ stack reports its stack axis as Z. In this time-registration adapter the
        // plain stack axis is time; explicit hyperstack dimensions are required to mean Z.
        boolean plain = !imp.isHyperStack() && channels == 1 && frames == 1;
        if (plain) {
            slices = 1;
            frames = imp.getStackSize();
        }
        if (cumulative.length != frames) {
            throw new IllegalArgumentException("got " + cumulative.length
                    + " transforms for " + frames + " timepoints");
        }

        Warper.Margin margin = crop
                ? Warper.validMargin(cumulative, w, h, interpolation)
                : new Warper.Margin[]{null}[0];
        int outW = crop ? margin.croppedWidth(w) : w;
        int outH = crop ? margin.croppedHeight(h) : h;

        ImageStack in = imp.getStack();
        ImageStack out = new ImageStack(outW, outH);
        float[] dst = new float[w * h];

        for (int t = 1; t <= frames; t++) {
            Transform tf = cumulative[t - 1];
            for (int z = 1; z <= slices; z++) {
                for (int c = 1; c <= channels; c++) {
                    int index = plain ? t : (t - 1) * channels * slices + (z - 1) * channels + c;
                    ImageProcessor ip = in.getProcessor(index);
                    ImageProcessor warped = warpOne(ip, tf, interpolation, dst, w, h);
                    if (crop && !margin.isEmpty()) {
                        warped.setRoi(margin.left, margin.top, outW, outH);
                        warped = warped.crop();
                    }
                    out.addSlice(in.getSliceLabel(index), warped);
                }
            }
        }

        ImagePlus result = new ImagePlus(imp.getTitle() + " [registered]", out);
        result.setCalibration(imp.getCalibration().copy());
        if (!plain) {
            result.setDimensions(channels, slices, frames);
            result.setOpenAsHyperStack(true);
        }
        return result;
    }

    /**
     * Warp one processor, back into its own type.
     *
     * <p>Values are clamped to the type's range on the way back. Bicubic interpolation genuinely
     * overshoots at a hard edge, so an unclamped 16-bit conversion would wrap a bright edge to black —
     * a spectacular artefact from a subtle cause.
     */
    private static ImageProcessor warpOne(ImageProcessor ip, Transform t,
                                          Warper.Interpolation interpolation,
                                          float[] dst, int w, int h) {
        if (ip instanceof ColorProcessor) {
            // RGB carries three independent 8-bit channels in one int; warp each and reassemble.
            ColorProcessor cp = (ColorProcessor) ip;
            ColorProcessor result = new ColorProcessor(w, h);
            for (int channel = 0; channel < 3; channel++) {
                byte[] plane = new byte[w * h];
                cp.getChannel(channel + 1, new ByteProcessor(w, h, plane, null));
                float[] src = new float[w * h];
                for (int i = 0; i < src.length; i++) src[i] = plane[i] & 0xff;
                Warper.warp(src, dst, w, h, t, interpolation, 0f);
                byte[] back = new byte[w * h];
                for (int i = 0; i < back.length; i++) back[i] = (byte) clamp(dst[i], 255);
                result.setChannel(channel + 1, new ByteProcessor(w, h, back, null));
            }
            return result;
        }

        float[] src = (float[]) ip.toFloat(0, null).getPixels();
        if (ip instanceof FloatProcessor) src = src.clone();
        Warper.warp(src, dst, w, h, t, interpolation, 0f);

        if (ip instanceof ByteProcessor) {
            byte[] p = new byte[w * h];
            for (int i = 0; i < p.length; i++) p[i] = (byte) clamp(dst[i], 255);
            return new ByteProcessor(w, h, p, ip.getColorModel());
        }
        if (ip instanceof ShortProcessor) {
            short[] p = new short[w * h];
            for (int i = 0; i < p.length; i++) p[i] = (short) clamp(dst[i], 65535);
            return new ShortProcessor(w, h, p, ip.getColorModel());
        }
        return new FloatProcessor(w, h, dst.clone(), ip.getColorModel());
    }

    private static int clamp(float v, int max) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > max ? max : i);
    }
}
