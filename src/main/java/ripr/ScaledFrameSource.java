/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.FrameSource;

/** Area-averaged view of a frame source for reduced-resolution movement estimation. */
public final class ScaledFrameSource implements FrameSource {
    private final FrameSource source;
    private final double scale;
    private final int width;
    private final int height;

    public ScaledFrameSource(FrameSource source, double scale) {
        if (source == null) throw new IllegalArgumentException("source is null");
        if (!(scale > 0 && scale <= 1)) throw new IllegalArgumentException("scale must be in (0, 1]");
        this.source = source;
        this.scale = scale;
        width = Math.max(1, (int) Math.floor(source.width() * scale));
        height = Math.max(1, (int) Math.floor(source.height() * scale));
        if (width < 32 || height < 32) {
            throw new IllegalArgumentException("estimation scale " + scale + " leaves only "
                    + width + " x " + height + " pixels; use a scale leaving at least 32 x 32");
        }
    }

    @Override public int count() { return source.count(); }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    public double scale() { return scale; }

    @Override public float[] plane(int frame) {
        return areaAverage(source.plane(frame), source.width(), source.height(), width, height, scale);
    }

    static float[] areaAverage(float[] input, int sourceWidth, int sourceHeight,
                               int outputWidth, int outputHeight, double scale) {
        float[] output = new float[outputWidth * outputHeight];
        for (int y = 0; y < outputHeight; y++) {
            double top = y / scale;
            double bottom = Math.min(sourceHeight, (y + 1) / scale);
            int y0 = (int) Math.floor(top);
            int y1 = Math.min(sourceHeight - 1, (int) Math.ceil(bottom) - 1);
            for (int x = 0; x < outputWidth; x++) {
                double left = x / scale;
                double right = Math.min(sourceWidth, (x + 1) / scale);
                int x0 = (int) Math.floor(left);
                int x1 = Math.min(sourceWidth - 1, (int) Math.ceil(right) - 1);
                double sum = 0;
                double weight = 0;
                for (int sy = y0; sy <= y1; sy++) {
                    double wy = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    if (!(wy > 0)) continue;
                    int row = sy * sourceWidth;
                    for (int sx = x0; sx <= x1; sx++) {
                        float value = input[row + sx];
                        if (Float.isNaN(value)) continue;
                        double wx = Math.min(right, sx + 1) - Math.max(left, sx);
                        double w = wx * wy;
                        if (w > 0) { sum += w * value; weight += w; }
                    }
                }
                output[y * outputWidth + x] = weight > 0 ? (float) (sum / weight) : Float.NaN;
            }
        }
        return output;
    }
}
