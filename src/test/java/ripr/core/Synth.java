/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

/**
 * Synthetic frames with an exactly known displacement.
 *
 * <p>The frame is an <b>analytic</b> function of position — a sum of low-frequency sinusoids — so a
 * shifted frame is produced by evaluating the same function at shifted coordinates rather than by
 * resampling an image. That distinction is the whole point: resampling to build the fixture would
 * make every recovery test partly a test of the interpolator, and a sub-pixel result could not be
 * attributed to the estimator. Here the ground truth is exact at any real-valued shift.
 *
 * <p>The frequencies are low enough that the pyramid's decimation does not alias them, and the
 * amplitude is chosen so every pixel is comfortably positive — a synthetic frame that dipped negative
 * would exercise the log's clamping rather than the criterion.
 */
final class Synth {

    static final int DEFAULT_SIZE = 192;

    private Synth() {
    }

    /**
     * A frame whose <b>content has moved by {@code (dx, dy)}</b> relative to {@code frame(w,h,0,0)},
     * scaled by {@code gain} and offset by {@code offset}.
     *
     * <p>Note the minus signs, and that they are the whole subtlety of this fixture. Moving content to
     * the right means <em>sampling further left</em>: a feature at {@code x} in the reference sits at
     * {@code x + dx} here, so this pixel must show what the reference showed at {@code x - dx}. Getting
     * that backwards produces a fixture whose declared truth is the negation of
     * {@link Transform}'s convention, and then a perfectly correct estimator reads as sign-inverted.
     */
    static float[] frame(int w, int h, double dx, double dy, double gain, double offset) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = (float) (gain * value(x - dx, y - dy, w, h) + offset);
            }
        }
        return out;
    }

    static float[] frame(int w, int h, double dx, double dy) {
        return frame(w, h, dx, dy, 1.0, 0.0);
    }

    /**
     * The analytic image. Several incommensurate spatial frequencies in both axes so the
     * autocorrelation has one clear peak and the Gauss–Newton problem is well conditioned in both
     * directions.
     */
    private static double value(double x, double y, int w, int h) {
        double u = 2 * Math.PI * x / w;
        double v = 2 * Math.PI * y / h;
        double s = 0;
        s += 1.00 * Math.sin(3 * u + 0.4) * Math.cos(2 * v - 0.7);
        s += 0.70 * Math.sin(5 * u - 1.1) * Math.cos(7 * v + 0.2);
        s += 0.45 * Math.cos(11 * u + 0.9) * Math.sin(6 * v + 1.3);
        s += 0.30 * Math.sin(13 * u + 2.1);
        s += 0.30 * Math.cos(9 * v - 0.5);
        return 2000.0 + 600.0 * s;      // range is comfortably positive for every term
    }

    /**
     * Replaces a fraction of pixels with a bright blob-like value — the stand-in for genuine change.
     *
     * <p>Deterministic from {@code seed}: the same call always produces the same frame, because a
     * test that separates two robust norms must not depend on which random draw it got.
     *
     * <p>Sparse and large, deliberately. That is the shape of real change — a few objects moved,
     * appeared or brightened — as opposed to the dense, edge-aligned, small residual that
     * misregistration produces. Separating those two is the only thing the choice of norm is for.
     */
    static float[] withSparseChange(float[] base, int w, int h, double fraction, long seed) {
        float[] out = base.clone();
        java.util.Random rng = new java.util.Random(seed);
        int blobs = (int) Math.max(1, Math.round(fraction * w * h / 25.0));   // ~5x5 patches
        for (int b = 0; b < blobs; b++) {
            int cx = rng.nextInt(w);
            int cy = rng.nextInt(h);
            double amp = 3000 + 4000 * rng.nextDouble();
            for (int y = Math.max(0, cy - 2); y < Math.min(h, cy + 3); y++) {
                for (int x = Math.max(0, cx - 2); x < Math.min(w, cx + 3); x++) {
                    out[y * w + x] = (float) amp;
                }
            }
        }
        return out;
    }

    /** Adds zero-mean Gaussian noise, deterministically. */
    static float[] withNoise(float[] base, double sigma, long seed) {
        float[] out = base.clone();
        java.util.Random rng = new java.util.Random(seed);
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (out[i] + sigma * rng.nextGaussian());
        }
        return out;
    }

    static LogPlane[] pyramid(float[] plane, int w, int h, int levels) {
        return LogPlane.of(plane, w, h, 1.0).pyramid(levels);
    }

    /** A {@link FrameSource} over a fixed list of planes. */
    static FrameSource source(final int w, final int h, final float[]... planes) {
        return new FrameSource() {
            @Override public int count() {
                return planes.length;
            }

            @Override public int width() {
                return w;
            }

            @Override public int height() {
                return h;
            }

            @Override public float[] plane(int frame) {
                return planes[frame].clone();
            }
        };
    }
}
